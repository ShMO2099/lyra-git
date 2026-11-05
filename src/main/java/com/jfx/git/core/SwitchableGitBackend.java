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
 * <p>The engine is {@code volatile} rather than lock-guarded. What keeps a swap from
 * landing under git work is not this class but the lane: every call through
 * {@link #reader()} and {@link #writer()} belongs on this repository's {@link GitTaskRunner}
 * lane, and {@link #switchTo} runs there too, so none is in flight when it replaces the
 * engine. One made from anywhere else could be closed under - which is one more reason
 * those two report a call from the FX thread. The calls that skip that guard - the
 * editor's file I/O, and where the repository is - come from the FX thread on purpose,
 * so the lane does nothing for them: each holds the engine it started on open until it
 * returns, and an engine a switch replaced is closed once the last of them has (see
 * Engine, below). The replacement is opened before the old engine is discarded, so a
 * failed switch leaves the tab working.
 */
public final class SwitchableGitBackend extends DelegatingGitBackend {

    private static final Logger LOGGER = Logger.getLogger(SwitchableGitBackend.class.getName());

    private volatile Engine current;
    private volatile File openedAt;

    /** Answers keyed by a full commit hash, which never go stale; see CommitCache. */
    private final CommitCache commits = new CommitCache(64L << 20);

    public SwitchableGitBackend(GitBackend initial) {
        this.current = initial == null ? null : new Engine(initial);
    }

    /**
     * An engine, and how many calls still need it open.
     *
     * <h2>Why</h2>
     * A switch used to close the engine it replaced straight away. Git work cannot be
     * running then, but an editor save from the FX thread can: one that picked up the old
     * engine just before the swap reached it just after the close, and failed - JGit
     * answered "No repository open" and an empty file, the CLI engine threw.
     *
     * <p>Being the current engine is one hold; each call through {@link #held} takes
     * another for as long as it runs. A switch gives up the first, and whichever release
     * brings the count to zero closes the engine - so it is closed once the last call
     * that started on it has returned, on that call's thread, and no call waits on a lock
     * to find out.
     */
    private static final class Engine {
        final GitBackend backend;
        private final java.util.concurrent.atomic.AtomicInteger holds =
                new java.util.concurrent.atomic.AtomicInteger(1);

        Engine(GitBackend backend) {
            this.backend = backend;
        }

        /** Takes a hold, unless the engine has already been closed. */
        boolean hold() {
            for (int n = holds.get(); n > 0; n = holds.get()) {
                if (holds.compareAndSet(n, n + 1)) return true;
            }
            return false;
        }

        void release() {
            if (holds.decrementAndGet() == 0) {
                try {
                    backend.close();
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error closing the previous engine", e);
                }
            }
        }
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
        Engine engine = current;
        if (engine == null) {
            throw new IllegalStateException("No repository is open yet");
        }
        return engine.backend;
    }

    /** {@code call} against the current engine, which stays open until it returns. */
    private <T> T held(java.util.function.Function<GitBackend, T> call) {
        while (true) {
            Engine engine = current;
            if (engine == null) {
                throw new IllegalStateException("No repository is open yet");
            }
            if (engine.hold()) {
                try {
                    return call.apply(engine.backend);
                } finally {
                    engine.release();
                }
            }
            // Closed between being read and being held, which only a switch does - and a
            // switch makes its replacement current before it lets go of the old engine.
        }
    }

    /** The engine currently doing the work — for describing it, not for holding on to. */
    public GitBackend currentEngine() {
        Engine engine = current;
        return engine == null ? null : engine.backend;
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
    // every save look like the application freezing itself. Being off the lane, they hold
    // the engine open while they run, so a switch cannot close it under a save.
    @Override public byte[] getWorkingTreeBytes(String p) { return held(e -> e.getWorkingTreeBytes(p)); }
    @Override public String getWorkingTreeContent(String p) { return held(e -> e.getWorkingTreeContent(p)); }
    @Override public GitResult writeWorkingTreeFile(String p, byte[] b) { return held(e -> e.writeWorkingTreeFile(p, b)); }
    @Override public File submoduleWorkTree(String p) { return held(e -> e.submoduleWorkTree(p)); }

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

        Engine previous = current;
        current = new Engine(replacement);

        if (previous != null) {
            // Closes it now, or - if an editor call off the lane is still running on it -
            // when the last such call returns; see Engine.
            previous.release();
        }
        return GitResult.ok("Git engine switched to " + GitBackendFactory.describeBackend(replacement));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public boolean openRepository(File dir) throws GitOperationException {
        if (current == null) {
            // Chosen here, on the repository lane; see deferred().
            current = new Engine(GitBackendFactory.createDefault());
        }
        GitBackend engine = current.backend;
        boolean opened = engine.openRepository(dir);
        if (opened) {
            // Remember the resolved work tree so a later switch reopens the repository
            // root rather than whichever subdirectory the user originally picked.
            File resolved = engine.getWorkTree();
            openedAt = resolved != null ? resolved : dir;
        }
        return opened;
    }

    // Asked from anywhere, the FX thread included, so held like the editor's file I/O: a
    // closed JGit engine has no work tree, and the tab would briefly look closed.
    @Override public boolean isOpen() {
        return current != null && held(GitBackend::isOpen);
    }

    @Override public File getDirectory() {
        return current == null ? null : held(GitBackend::getDirectory);
    }

    @Override public File getWorkTree() {
        return current == null ? null : held(GitBackend::getWorkTree);
    }

    // At once, holds or not: the tab is going away, and a call still running against it
    // has nothing to come back to.
    @Override public void close() {
        openedAt = null;
        commits.clear();
        Engine engine = current;
        if (engine != null) engine.backend.close();
    }
}
