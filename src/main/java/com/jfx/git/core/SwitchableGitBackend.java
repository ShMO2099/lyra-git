package com.jfx.git.core;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link GitBackend} whose engine can be replaced while the UI keeps its reference.
 *
 * <h2>Why this exists</h2>
 * A repository tab hands its backend to a dozen collaborators — toolbar, sidebar, graph
 * cells, diff viewer, detail panel — at construction time. Without an indirection, letting
 * the user pick a different engine would mean rebuilding the whole tab, so the engine
 * switcher could only ever change a stored preference and hope the user reopened the
 * repository. Everything holds this wrapper instead, and {@link #switchTo} swaps the
 * engine underneath them.
 *
 * <p>Unlike its siblings there is no reader/writer split here: one engine serves
 * everything, so both roles resolve to the same delegate.
 *
 * <p>The delegate is {@code volatile} rather than lock-guarded. What keeps a swap from
 * landing under a running call is not this class but the lane: every call belongs on
 * this repository's {@link GitTaskRunner} lane, and {@link #switchTo} runs there too, so no other
 * call is in flight when it closes the engine it replaces. A call made from anywhere else
 * could still be closed under - which is one more reason {@link #reader()} and
 * {@link #writer()} report a call from the FX thread. The replacement is opened before
 * the old engine is discarded, so a failed switch leaves the tab working.
 */
public final class SwitchableGitBackend extends DelegatingGitBackend {

    private static final Logger LOGGER = Logger.getLogger(SwitchableGitBackend.class.getName());

    private volatile GitBackend delegate;
    private volatile File openedAt;

    /** Answers keyed by a full commit hash, which never go stale; see CommitCache. */
    private final CommitCache commits = new CommitCache(64L << 20);

    public SwitchableGitBackend(GitBackend initial) {
        this.delegate = initial;
    }

    /**
     * A backend whose engine is chosen when it is first opened, rather than now.
     *
     * <h2>Why</h2>
     * Choosing the engine asks whether git is installed, which runs {@code git --version}
     * the first time - and a tab was built on the FX thread, so the first tab of a session
     * waited for that probe, holding a lock the start-up warm-up thread had already taken.
     * On a cold start, or the first time a virus scanner saw git.exe, that was seconds of a
     * frozen window. Opening runs on the repository lane, where the probe costs nothing
     * anyone is waiting on.
     */
    public static SwitchableGitBackend deferred() {
        return new SwitchableGitBackend(null);
    }

    /** The engine, or a clear failure when none has been chosen because nothing is open. */
    private GitBackend engine() {
        GitBackend current = delegate;
        if (current == null) {
            throw new IllegalStateException("No repository is open yet");
        }
        return current;
    }

    /** The engine currently doing the work — for describing it, not for holding on to. */
    public GitBackend currentEngine() {
        return delegate;
    }

    /** The directory this backend was opened against, or {@code null}. */
    public File openedAt() {
        return openedAt;
    }

    // Every call the UI makes into a repository passes through here, which makes this the
    // place to hold the threading rule for all engines at once: not on the FX thread, and
    // not on another repository's lane. See ThreadGuard.
    @Override protected GitBackend reader() {
        ThreadGuard.engineCall(this);
        return engine();
    }

    @Override protected GitBackend writer() {
        ThreadGuard.engineCall(this);
        return engine();
    }

    // Plain file I/O in the working tree, not git: what an editor does with its own file.
    // The editor reads a file to start editing it and writes it to save, synchronously,
    // as any editor does, and no engine runs git for either. The guard is for git work -
    // a walk of the object database, a subprocess - so these skip it rather than make
    // every save look like the application freezing itself.
    @Override public byte[] getWorkingTreeBytes(String p) { return engine().getWorkingTreeBytes(p); }
    @Override public String getWorkingTreeContent(String p) { return engine().getWorkingTreeContent(p); }
    @Override public GitResult writeWorkingTreeFile(String p, byte[] b) { return engine().writeWorkingTreeFile(p, b); }
    @Override public File submoduleWorkTree(String p) { return engine().submoduleWorkTree(p); }

    // ------------------------------------------------------------------
    // Answers about one commit, kept (CommitCache)
    // ------------------------------------------------------------------
    //
    // Here rather than in an engine, so every engine gets it and an engine switch keeps
    // it: what a commit contained does not depend on which engine read it. The thread
    // check runs first, hit or miss, so a call from the FX thread is reported the same
    // whether or not the answer happened to be kept.

    private <T> T kept(String hash, Object key, java.util.function.Supplier<T> compute) {
        ThreadGuard.engineCall(this);
        return CommitCache.isFullHash(hash) ? commits.get(key, compute) : compute.get();
    }

    /** Lists are kept unmodifiable and handed out as copies, so no caller edits the kept one. */
    private <T> java.util.List<T> keptList(String hash, Object key,
                                            java.util.function.Supplier<java.util.List<T>> compute) {
        java.util.List<T> kept = kept(hash, key, () -> {
            java.util.List<T> fresh = compute.get();
            return fresh == null ? null : java.util.List.copyOf(fresh);
        });
        return kept == null ? null : new java.util.ArrayList<>(kept);
    }

    private record Key(String what, Object... parts) {
        @Override public boolean equals(Object other) {
            return other instanceof Key k && what.equals(k.what) && java.util.Arrays.equals(parts, k.parts);
        }

        @Override public int hashCode() {
            return 31 * what.hashCode() + java.util.Arrays.hashCode(parts);
        }
    }

    @Override public java.util.List<FileChange> getChangesInCommit(String h) {
        return keptList(h, new Key("changes", h), () -> super.getChangesInCommit(h));
    }

    @Override public java.util.List<FileChange> getChangesBetweenCommits(String f, String t) {
        return CommitCache.isFullHash(f)
                ? keptList(t, new Key("between", f, t), () -> super.getChangesBetweenCommits(f, t))
                : super.getChangesBetweenCommits(f, t);
    }

    @Override public java.util.List<String> getFilesInCommit(String h) {
        return keptList(h, new Key("files", h), () -> super.getFilesInCommit(h));
    }

    @Override public java.util.List<String> getTopLevelDirectoriesAtCommit(String h) {
        return keptList(h, new Key("topLevelDirectories", h),
                () -> super.getTopLevelDirectoriesAtCommit(h));
    }

    @Override public java.util.List<String> getFilesInCommit(com.jfx.git.model.CommitInfo c) {
        return getFilesInCommit(c.getHash());
    }

    @Override public byte[] getDiffBytesForFileInCommit(String h, String p, boolean w) {
        return kept(h, new Key("diff", h, p, null, w), () -> super.getDiffBytesForFileInCommit(h, p, w));
    }

    @Override public byte[] getDiffBytesForFileInCommit(String h, String p, String o, boolean w) {
        return kept(h, new Key("diff", h, p, o, w), () -> super.getDiffBytesForFileInCommit(h, p, o, w));
    }

    @Override public byte[] getDiffBytesForFileInCommit(com.jfx.git.model.CommitInfo c, String p, boolean w) {
        return getDiffBytesForFileInCommit(c.getHash(), p, w);
    }

    @Override public byte[] getDiffBytesBetweenCommits(String f, String t, String p, boolean w) {
        return CommitCache.isFullHash(f)
                ? kept(t, new Key("diffBetween", f, t, p, null, w), () -> super.getDiffBytesBetweenCommits(f, t, p, w))
                : super.getDiffBytesBetweenCommits(f, t, p, w);
    }

    @Override public byte[] getDiffBytesBetweenCommits(String f, String t, String p, String o, boolean w) {
        return CommitCache.isFullHash(f)
                ? kept(t, new Key("diffBetween", f, t, p, o, w), () -> super.getDiffBytesBetweenCommits(f, t, p, o, w))
                : super.getDiffBytesBetweenCommits(f, t, p, o, w);
    }

    @Override public byte[] getFileBytesAtCommit(String h, String p) {
        return kept(h, new Key("blob", h, p), () -> super.getFileBytesAtCommit(h, p));
    }

    @Override public byte[] getFileBytesAtCommit(com.jfx.git.model.CommitInfo c, String p) {
        return getFileBytesAtCommit(c.getHash(), p);
    }

    @Override public byte[][] getCommitSides(com.jfx.git.model.CommitInfo c, String p) {
        return kept(c.getHash(), new Key("sides", c.getHash(), p), () -> super.getCommitSides(c, p));
    }

    @Override public String getCommitMessage(String h) {
        return kept(h, new Key("message", h), () -> super.getCommitMessage(h));
    }

    /** Blame at a commit is fixed; blame of the working tree (no commit) is not, and is never kept. */
    @Override public java.util.List<BlameLine> getBlameLines(String p, String c) {
        return keptList(c, new Key("blame", c, p), () -> super.getBlameLines(p, c));
    }

    /** For the log and for tests: how often a kept answer was used. */
    public String describeCache() {
        return commits.describe();
    }

    /**
     * Replaces the engine with a fresh one for {@code engine}, reopened at the same
     * directory.
     *
     * <p>The new engine is opened <em>before</em> the old one is discarded, so a failure
     * leaves the tab working on what it already had rather than dropping it into a
     * closed state.
     *
     * @return a result describing the switch, for the status bar to report
     */
    public GitResult switchTo(GitBackendFactory.BackendEngine engine) {
        File dir = openedAt;
        if (dir == null) {
            // Also the answer after close(): a switch queued before the tab closed must
            // not reopen the repository behind it and leave the handles open for good.
            return GitResult.failed("No repository is open, so there is nothing to switch");
        }

        GitBackend replacement;
        try {
            replacement = GitBackendFactory.createForEngine(engine);
            replacement.openRepository(dir);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not switch to " + engine, e);
            return GitResult.failed("Could not switch to " + engine.label() + ": " + e.getMessage());
        }

        GitBackend previous = delegate;
        delegate = replacement;

        if (previous != null) {
            try {
                previous.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error closing the previous engine", e);
            }
        }
        return GitResult.ok("Git engine switched to " + GitBackendFactory.describeBackend(replacement));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public boolean openRepository(File dir) throws GitOperationException {
        if (delegate == null) {
            // Chosen here, on the repository lane; see deferred().
            delegate = GitBackendFactory.createDefault();
        }
        boolean opened = delegate.openRepository(dir);
        if (opened) {
            // Remember the resolved work tree so a later switch reopens the repository
            // root rather than whichever subdirectory the user originally picked.
            File resolved = delegate.getWorkTree();
            openedAt = resolved != null ? resolved : dir;
        }
        return opened;
    }

    @Override public boolean isOpen() {
        GitBackend current = delegate;
        return current != null && current.isOpen();
    }

    @Override public File getDirectory() {
        GitBackend current = delegate;
        return current == null ? null : current.getDirectory();
    }

    @Override public File getWorkTree() {
        GitBackend current = delegate;
        return current == null ? null : current.getWorkTree();
    }

    @Override public void close() {
        openedAt = null;
        commits.clear();
        GitBackend current = delegate;
        if (current != null) current.close();
    }
}
