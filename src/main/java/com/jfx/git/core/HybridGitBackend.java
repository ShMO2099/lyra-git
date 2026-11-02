package com.jfx.git.core;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Native {@code git} for the hot path, JGit for the operations it is better at.
 *
 * <p>Status, staging and branch switching go to the CLI, where porcelain v2 beats a JGit
 * scan and the user's own config, hooks and credential helpers are honoured. History
 * walking, diff formatting and interactive rebase go to JGit, which already has the
 * object database open and does not pay a subprocess per call.
 *
 * <p>The routing itself lives in {@link DelegatingGitBackend}; this class only says which
 * engine plays which role and how both are opened.
 */
public class HybridGitBackend extends DelegatingGitBackend {

    private static final Logger LOGGER = Logger.getLogger(HybridGitBackend.class.getName());

    private final NativeGitBackend nativeBackend;
    private final GitService jgitBackend;

    public HybridGitBackend() {
        this(new NativeGitBackend(), new GitService());
    }

    /** For tests that need an engine to fail in a particular way. */
    HybridGitBackend(NativeGitBackend nativeBackend, GitService jgitBackend) {
        this.nativeBackend = nativeBackend;
        this.jgitBackend = jgitBackend;
    }

    public NativeGitBackend getNativeBackend() {
        return nativeBackend;
    }

    public GitService getJgitBackend() {
        return jgitBackend;
    }

    // ------------------------------------------------------------------
    // Engine choice
    // ------------------------------------------------------------------

    /**
     * JGit where it is open, the CLI otherwise.
     *
     * <p>Falling back rather than failing matters because either engine can decline to
     * open a repository the other handles — a JGit version that does not understand a
     * newer index extension, say.
     */
    @Override
    protected GitBackend reader() {
        return jgitBackend.isOpen() ? jgitBackend : nativeBackend;
    }

    @Override
    protected GitBackend writer() {
        return nativeBackend.isOpen() ? nativeBackend : jgitBackend;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Opens both engines, and succeeds if either did.
     *
     * <p>A failure in one is logged rather than thrown: the surviving engine can serve
     * every call through the fallbacks above, and refusing to open a repository this
     * application can in fact read would be the worse outcome.
     *
     * <p>With one exception, which is why native git goes first. A dubious-ownership
     * refusal is git declining to run another user's config and hooks, and JGit has no
     * such check - so "fall back to the engine that did open it" was a way round the
     * refusal, not a way round a failure. It is rethrown, and JGit never opens.
     *
     * @throws DubiousOwnershipException when git refuses the repository's owner
     */
    @Override
    public boolean openRepository(File dir) throws GitOperationException {
        String nativeFailure = null;
        try {
            nativeBackend.openRepository(dir);
        } catch (DubiousOwnershipException e) {
            close();
            throw e;
        } catch (Exception e) {
            if (SafeDirectory.isDubiousOwnership(e.getMessage())) {
                close();
                throw new DubiousOwnershipException(e.getMessage());
            }
            nativeFailure = e.getMessage();
            LOGGER.log(Level.FINE, "Native git could not open " + dir, e);
        }

        boolean jgitOpened = false;
        try {
            // Opened at native's resolved root when it has one: git has checked the
            // owner already, and the two engines then agree on which repository this is.
            jgitOpened = nativeBackend.isOpen()
                    ? jgitBackend.openAlreadyVerifiedRepository(nativeBackend.getWorkTree())
                    : jgitBackend.openRepository(dir);
        } catch (DubiousOwnershipException e) {
            close();
            throw e;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "JGit could not open " + dir, e);
        }

        if (!jgitOpened && !nativeBackend.isOpen()) {
            throw new GitOperationException("Neither JGit nor native git could open " + dir
                    + (nativeFailure == null ? "" : ": " + nativeFailure));
        }
        return true;
    }

    @Override
    public boolean isOpen() {
        return jgitBackend.isOpen() || nativeBackend.isOpen();
    }

    @Override
    public File getDirectory() {
        return nativeBackend.isOpen() ? nativeBackend.getDirectory() : jgitBackend.getDirectory();
    }

    @Override
    public File getWorkTree() {
        return nativeBackend.isOpen() ? nativeBackend.getWorkTree() : jgitBackend.getWorkTree();
    }

    @Override
    public void close() {
        nativeBackend.close();
        jgitBackend.close();
    }
}
