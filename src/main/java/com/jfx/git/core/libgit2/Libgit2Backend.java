package com.jfx.git.core.libgit2;

import com.jfx.git.core.*;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.jfx.git.model.StagingState;

/**
 * {@link GitBackend} backed by in-process {@code libgit2} calls through the Java Foreign
 * Function &amp; Memory API.
 *
 * <h2>What actually runs in-process</h2>
 * The hot path a UI hits on every refresh — repository open, working-tree status, index
 * staging and unstaging, current branch — is served by direct libgit2 calls with no
 * subprocess and no JGit object graph. History walking, diff formatting, network transport
 * and rebase are <em>delegated</em>: they are large surfaces where the CLI and JGit are
 * already correct, and reimplementing them over FFM would trade real behaviour for a
 * benchmark. {@link #describesAsFullyNative()} reports that split honestly so the status
 * bar does not claim more than the engine does.
 *
 * <h2>Threading</h2>
 * A {@code git_repository} is not safe for concurrent use, so every native call in this
 * class holds {@link #nativeLock}. The delegates have their own guarantees.
 */
public class Libgit2Backend extends DelegatingGitBackend {

    private static final Logger LOGGER = Logger.getLogger(Libgit2Backend.class.getName());

    /**
     * Status flags requested on every scan; mirrors {@code git status -uall}.
     *
     * <h2>Rename detection is deliberately off</h2>
     * With it on, libgit2 does find the pair - and then reports the entry under the path
     * the file had <em>before</em> the move, because {@code git_status_foreach}'s callback
     * receives {@code head_to_index->old_file.path}. The staging list then showed a row
     * for {@code a.txt} marked R while the file on disk was {@code renamed.txt}: a path
     * that no longer exists, which no action in the panel can operate on.
     *
     * <p>Naming the row correctly means reading {@code new_file.path} out of the
     * {@code git_status_entry}, which means hand-written offsets into
     * {@code git_diff_delta} and {@code git_diff_file} - and the latter's layout moves
     * with the oid size, so it differs between a SHA-1 and a SHA-256 build of the same
     * library. This class already warns that its hand-written offsets assume a 64-bit ABI;
     * adding offsets that also assume a hash size is a worse trade than reporting the move
     * as the add and delete it literally is.
     *
     * <p>So this engine reports both real paths, and {@code getStagingState} does not fold
     * them. See {@code BackendConformanceTest.aStagedRenameIsOneRowNotTwo}, which pins
     * that divergence by name rather than letting it pass unnoticed.
     *
     * <h2>Submodules are included</h2>
     * This used to pass {@code GIT_STATUS_OPT_EXCLUDE_SUBMODULES}, which made a
     * submodule pointer bump invisible on the default engine - and a bump staged
     * elsewhere was then committed without ever appearing in the staging list. Without
     * the flag libgit2 reports submodules the way git does, honouring each one's
     * {@code submodule.<name>.ignore} setting, which is where a user who wants them
     * quiet already says so.
     */
    private static final int STATUS_FLAGS =
            Libgit2Bindings.GIT_STATUS_OPT_INCLUDE_UNTRACKED
                    | Libgit2Bindings.GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS;

    private final Object nativeLock = new Object();

    private File workTree;
    private File gitDir;
    private MemorySegment repoHandle = MemorySegment.NULL;

    /** Serves history, diffs and network work; opened alongside the native handle. */
    private final NativeGitBackend delegateCli = new NativeGitBackend();
    private final GitService delegateJgit = new GitService();

    public Libgit2Backend() {}

    public static boolean isAvailable() {
        return Libgit2Loader.isAvailable();
    }

    /**
     * Whether every operation is served natively. Always {@code false}: see the class
     * comment for which surfaces delegate.
     */
    public boolean describesAsFullyNative() {
        return false;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public boolean openRepository(File dir) throws GitOperationException {
        if (!isAvailable()) {
            throw new GitOperationException("libgit2 (FFM) is not available on this platform");
        }
        close();

        synchronized (nativeLock) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment path = arena.allocateFrom(dir.getAbsolutePath());
                MemorySegment repoOut = arena.allocate(ValueLayout.ADDRESS);

                // Searching upward, as git does: the no-search git_repository_open could
                // not open a subfolder of a work tree at all.
                int rc = Libgit2Bindings.repositoryOpenExt(repoOut, path,
                        Libgit2Bindings.GIT_REPOSITORY_OPEN_SEARCH);
                if (rc == Libgit2Bindings.GIT_EOWNER) {
                    // libgit2's safe.directory refusal. Final, like git's: see
                    // DubiousOwnershipException for why no delegate may open it instead.
                    throw new DubiousOwnershipException(Libgit2Bindings.getLastError());
                }
                if (rc != Libgit2Bindings.GIT_OK) {
                    throw new GitOperationException(
                            "libgit2 could not open " + dir + ": " + Libgit2Bindings.getLastError());
                }
                // A pointer read out of arena-allocated memory is a zero-length segment
                // with global scope, so it outlives this arena.
                this.repoHandle = repoOut.get(ValueLayout.ADDRESS, 0);

                String workdir = Libgit2Bindings.cString(Libgit2Bindings.repositoryWorkdir(repoHandle));
                String gitPath = Libgit2Bindings.cString(Libgit2Bindings.repositoryPath(repoHandle));
                this.workTree = workdir != null ? new File(workdir) : dir;
                this.gitDir = gitPath != null ? new File(gitPath) : new File(dir, ".git");
            } catch (GitOperationException e) {
                throw e;
            } catch (Throwable t) {
                throw new GitOperationException("libgit2 failed to open " + dir, t);
            }
        }

        try {
            delegateCli.openRepository(workTree);
        } catch (DubiousOwnershipException e) {
            // Final, as it is for libgit2's own check above and in HybridGitBackend: git
            // declining another user's config and hooks is not a failure to route around.
            // Falling through let the JGit delegate open the repository and serve writes
            // - hooks included - from a repository git had refused to trust.
            close();
            throw e;
        } catch (Exception e) {
            if (SafeDirectory.isDubiousOwnership(e.getMessage())) {
                close();
                throw new DubiousOwnershipException(e.getMessage());
            }
            LOGGER.log(Level.FINE, "Native git delegate unavailable for " + workTree, e);
        }
        try {
            // libgit2 has already checked the owner of this repository.
            delegateJgit.openAlreadyVerifiedRepository(workTree);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "JGit delegate unavailable for " + workTree, e);
        }

        LOGGER.info("Libgit2Backend opened " + workTree);
        return true;
    }

    @Override
    public boolean isOpen() {
        synchronized (nativeLock) {
            return repoHandle != null && repoHandle.address() != 0;
        }
    }

    @Override
    public File getDirectory() {
        return gitDir;
    }

    @Override
    public File getWorkTree() {
        return workTree;
    }

    @Override
    public void close() {
        synchronized (nativeLock) {
            if (repoHandle != null && repoHandle.address() != 0) {
                try {
                    Libgit2Bindings.repositoryFree(repoHandle);
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "Error freeing libgit2 repository", t);
                }
            }
            repoHandle = MemorySegment.NULL;
        }
        delegateCli.close();
        delegateJgit.close();
    }

    private void ensureOpen() {
        if (!isOpen()) throw new IllegalStateException("Repository is not open");
    }

    // ------------------------------------------------------------------
    // Status — in-process
    // ------------------------------------------------------------------

    /**
     * How one path changed, from libgit2's status flag.
     *
     * <p>The index side wins where both are set, matching {@code NativeGitBackend.letterOf}
     * and {@code GitService.statusLetters}: a file staged as an add and then edited again
     * in the tree is an add, because that is what committing it would record. The three
     * engines have to agree here or the same repository reads differently depending on
     * which one is selected.
     */
    private static char letterOf(int flags) {
        if ((flags & Libgit2Bindings.GIT_STATUS_INDEX_NEW) != 0) return 'A';
        if ((flags & Libgit2Bindings.GIT_STATUS_INDEX_DELETED) != 0) return 'D';
        if ((flags & Libgit2Bindings.GIT_STATUS_INDEX_RENAMED) != 0) return 'R';
        if ((flags & Libgit2Bindings.GIT_STATUS_INDEX_TYPECHANGE) != 0) return 'T';
        if ((flags & Libgit2Bindings.GIT_STATUS_INDEX_MODIFIED) != 0) return 'M';
        // Untracked reads as an add: not in the index yet, but that is what it is about
        // to become, and what git itself shows.
        if ((flags & Libgit2Bindings.GIT_STATUS_WT_NEW) != 0) return 'A';
        if ((flags & Libgit2Bindings.GIT_STATUS_WT_DELETED) != 0) return 'D';
        if ((flags & Libgit2Bindings.GIT_STATUS_WT_RENAMED) != 0) return 'R';
        if ((flags & Libgit2Bindings.GIT_STATUS_WT_TYPECHANGE) != 0) return 'T';
        return 'M';
    }

    @Override
    public StagingState getStagingState() {
        ensureOpen();
        List<String> staged = new ArrayList<>();
        List<String> unstaged = new ArrayList<>();
        Set<String> conflicts = new LinkedHashSet<>();
        // The callback is already handed the flag that says how each path changed, and
        // this used to drop it and pass an empty map - which sends StagingState.statusOf
        // to its "M" fallback, so a brand-new file read as a modification. Keeping the
        // flag costs nothing here and is what makes the three engines agree.
        Map<String, Character> letters = new HashMap<>();

        synchronized (nativeLock) {
            refreshIndex();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment opts = Libgit2Bindings.newStatusOptions(arena, STATUS_FLAGS);
                MemorySegment stub = Libgit2Bindings.statusCallbackStub(arena, (path, flags) -> {
                    if ((flags & Libgit2Bindings.GIT_STATUS_CONFLICTED) != 0) {
                        conflicts.add(path);
                        // Conflicted paths also need resolving in the working tree, and
                        // the staging panel reads that from the unstaged list. JGit
                        // reports them in both places; matching it keeps the panel's
                        // contents independent of which engine produced them.
                        unstaged.add(path);
                        return 0;
                    }
                    if ((flags & Libgit2Bindings.STAGED_MASK) != 0) staged.add(path);
                    if ((flags & Libgit2Bindings.UNSTAGED_MASK) != 0) unstaged.add(path);
                    letters.put(path, letterOf(flags));
                    return 0;
                });

                int rc = Libgit2Bindings.statusForeachExt(repoHandle, opts, stub, MemorySegment.NULL);
                if (rc != Libgit2Bindings.GIT_OK) {
                    LOGGER.warning("libgit2 status failed: " + Libgit2Bindings.getLastError());
                    return fallbackStagingState();
                }
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "libgit2 status failed; falling back", t);
                return fallbackStagingState();
            }
        }

        // Carried with the lists so the staging panel does not have to ask separately
        // on the FX thread - see StagingState. Both are cheap here: the branch is a ref
        // lookup and the operation state is a handful of file checks.
        return new StagingState(
                unstaged.stream().distinct().sorted().toList(),
                staged.stream().distinct().sorted().toList(),
                conflicts,
                letters,
                getCurrentBranch(),
                GitBackend.isOperationInProgress(gitDir));
    }

    /**
     * Re-reads {@code .git/index} into the repository's cached index before a scan.
     *
     * <p>Status reads the cached copy, which libgit2 reloads only when the file's stat
     * data says it changed. Nearly every write here is made by a delegate - the CLI
     * stages, commits, checks out; JGit patches hunks - and one that lands within the
     * filesystem's timestamp granularity, with the index the same size, left the staging
     * list showing the state from before it. The same reasoning as {@link #reloadIndex},
     * for reads.
     */
    private void refreshIndex() {
        MemorySegment index = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment indexOut = arena.allocate(ValueLayout.ADDRESS);
            if (Libgit2Bindings.repositoryIndex(indexOut, repoHandle) == Libgit2Bindings.GIT_OK) {
                index = indexOut.get(ValueLayout.ADDRESS, 0);
                reloadIndex(index);
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Could not refresh the libgit2 index before a status scan", t);
        } finally {
            freeIndexQuietly(index);
        }
    }

    private StagingState fallbackStagingState() {
        if (delegateCli.isOpen()) return delegateCli.getStagingState();
        if (delegateJgit.isOpen()) return delegateJgit.getStagingState();
        return StagingState.empty();
    }

    /**
     * Whether anything is pending - including a merge, cherry-pick, revert or rebase
     * that is byte-clean but still waiting for its finishing commit.
     *
     * @see GitBackend#isOperationInProgress(File)
     */
    /**
     * Strict, as the interface asks: a scan that fails must not read as "clean".
     * {@link #getStagingState} falls back to an empty state when every engine fails, which
     * is right for drawing a list and wrong for this question - restoring and other
     * destructive steps ask it before discarding work - so a failed scan is asked again of
     * the writer, whose own check throws rather than guess.
     */
    @Override
    public boolean hasUncommittedChanges() {
        synchronized (nativeLock) {
            refreshIndex();
            try (Arena arena = Arena.ofConfined()) {
                boolean[] dirty = {false};
                MemorySegment opts = Libgit2Bindings.newStatusOptions(arena, STATUS_FLAGS);
                MemorySegment stub = Libgit2Bindings.statusCallbackStub(arena, (path, flags) -> {
                    dirty[0] = true;
                    return 0;
                });
                if (Libgit2Bindings.statusForeachExt(repoHandle, opts, stub, MemorySegment.NULL)
                        == Libgit2Bindings.GIT_OK) {
                    return dirty[0] || GitBackend.isOperationInProgress(gitDir);
                }
                LOGGER.fine("libgit2 status failed; asking the writer: " + Libgit2Bindings.getLastError());
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "libgit2 status failed; asking the writer", t);
            }
        }
        return writer().hasUncommittedChanges();
    }

    @Override
    public List<String> getStagedFiles() {
        return getStagingState().staged();
    }

    @Override
    public List<String> getConflictingFiles() {
        return new ArrayList<>(getStagingState().conflicting());
    }

    // ------------------------------------------------------------------
    // Index mutation — in-process
    // ------------------------------------------------------------------

    /**
     * Stages one path - through the git CLI whenever it is available.
     *
     * <h2>Why not in-process</h2>
     * {@code git_index_add_bypath} runs libgit2's built-in filters (CRLF, ident) and none
     * of the {@code filter.<driver>.clean} commands a repository's attributes name,
     * because this engine registers no filter drivers. So a Git LFS file went into the
     * index as its full content instead of a pointer, and a git-crypt file went in as
     * <em>plaintext</em> - the one outcome that tool exists to prevent - while "Stage all",
     * which already went to the CLI, did the right thing. The CLI is also the engine that
     * honours {@code core.safecrlf} and friends exactly as the user's own git does.
     *
     * <p>A subprocess per click is cheap next to that, so this does not try to work out
     * per path whether a filter applies: asking {@code check-attr} would itself be a
     * subprocess. The in-process path remains for a machine without git, where no clean
     * filter could run anyway.
     */
    @Override
    public GitResult stageFile(String path) {
        ensureOpen();
        if (delegateCli.isOpen()) {
            return delegateCli.stageFile(path);
        }
        synchronized (nativeLock) {
            MemorySegment index = MemorySegment.NULL;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment indexOut = arena.allocate(ValueLayout.ADDRESS);
                if (Libgit2Bindings.repositoryIndex(indexOut, repoHandle) != Libgit2Bindings.GIT_OK) {
                    return delegateStage(path, "could not open index: " + Libgit2Bindings.getLastError());
                }
                index = indexOut.get(ValueLayout.ADDRESS, 0);
                if (!reloadIndex(index)) {
                    return delegateStage(path, "could not reload index: " + Libgit2Bindings.getLastError());
                }
                MemorySegment pathSeg = arena.allocateFrom(path);

                // add_bypath fails with GIT_ENOTFOUND for a path that no longer exists
                // on disk, which is exactly how a deletion presents; removing it from
                // the index is the same gesture for that case.
                //
                // Only for that code. Any other failure against a path that *is* in the
                // index would have the remove succeed and report "Staged" for what is
                // actually an unstage - the opposite of what the user asked for.
                int rc = Libgit2Bindings.indexAddByPath(index, pathSeg);
                if (rc == Libgit2Bindings.GIT_ENOTFOUND) {
                    rc = Libgit2Bindings.indexRemoveByPath(index, pathSeg);
                }
                if (rc != Libgit2Bindings.GIT_OK) {
                    return delegateStage(path, Libgit2Bindings.getLastError());
                }

                int written = Libgit2Bindings.indexWrite(index);
                if (written != Libgit2Bindings.GIT_OK) {
                    return delegateStage(path, "could not write index: " + Libgit2Bindings.getLastError());
                }
                return GitResult.ok("Staged " + path);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "libgit2 stage failed for " + path, t);
                return delegateStage(path, t.toString());
            } finally {
                freeIndexQuietly(index);
            }
        }
    }

    @Override
    public GitResult stageAll() {
        ensureOpen();
        // Staging everything means honouring ignore rules, submodule boundaries and
        // deletions in one pass; the index API has no single call for it, so this is one
        // of the surfaces that delegates.
        return delegateCli.isOpen() ? delegateCli.stageAll() : delegateJgit.stageAll();
    }

    @Override
    public GitResult unstageFile(String path) {
        ensureOpen();
        synchronized (nativeLock) {
            MemorySegment head = MemorySegment.NULL;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment headOut = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment spec = arena.allocateFrom("HEAD");
                int rc = Libgit2Bindings.revparseSingle(headOut, repoHandle, spec);
                if (rc != Libgit2Bindings.GIT_OK) {
                    // No HEAD yet: nothing to reset to, so drop the entry outright.
                    return unstageWithoutHead(path);
                }
                head = headOut.get(ValueLayout.ADDRESS, 0);

                MemorySegment pathspecs = strArray(arena, path);
                int reset = Libgit2Bindings.resetDefault(repoHandle, head, pathspecs);
                if (reset != Libgit2Bindings.GIT_OK) {
                    String error = Libgit2Bindings.getLastError();
                    LOGGER.fine("libgit2 reset failed for " + path + ": " + error);
                    return delegateUnstage(path);
                }
                return GitResult.ok("Unstaged " + path);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "libgit2 unstage failed for " + path, t);
                return delegateUnstage(path);
            } finally {
                if (head != null && head.address() != 0) {
                    try {
                        Libgit2Bindings.objectFree(head);
                    } catch (Throwable t) {
                        LOGGER.log(Level.FINE, "Error freeing libgit2 object", t);
                    }
                }
            }
        }
    }

    /** Unstaging in a repository with no commits: remove the path from the index. */
    private GitResult unstageWithoutHead(String path) {
        MemorySegment index = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment indexOut = arena.allocate(ValueLayout.ADDRESS);
            if (Libgit2Bindings.repositoryIndex(indexOut, repoHandle) != Libgit2Bindings.GIT_OK) {
                return delegateUnstage(path);
            }
            index = indexOut.get(ValueLayout.ADDRESS, 0);
            if (!reloadIndex(index)) {
                return delegateUnstage(path);
            }
            MemorySegment pathSeg = arena.allocateFrom(path);
            if (Libgit2Bindings.indexRemoveByPath(index, pathSeg) != Libgit2Bindings.GIT_OK
                    || Libgit2Bindings.indexWrite(index) != Libgit2Bindings.GIT_OK) {
                return delegateUnstage(path);
            }
            return GitResult.ok("Unstaged " + path);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "libgit2 unstage (no HEAD) failed for " + path, t);
            return delegateUnstage(path);
        } finally {
            freeIndexQuietly(index);
        }
    }

    /**
     * Reloads the index from disk before this engine writes it back.
     *
     * <p>{@code git_repository_index} hands back the {@code git_repository}'s
     * <em>cached</em> index, loaded the first time anything asked for it. Everything this
     * backend does not serve in-process - checkout, commit, merge, reset, stash - is
     * delegated to the CLI or to JGit, and each of those rewrites {@code .git/index}
     * behind libgit2's back. Adding one path to the stale copy and writing it out then
     * resurrects whatever the delegate had removed: after switching branches, the next
     * partial stage silently put the other branch's files back in the index.
     *
     * <p>Forced, because the check libgit2 would otherwise make is a stat comparison, and
     * a delegate's write can land inside the same filesystem timestamp granularity as the
     * load it is replacing.
     *
     * @return false when the reload failed, in which case the caller must not write
     */
    private boolean reloadIndex(MemorySegment index) {
        try {
            return Libgit2Bindings.indexRead(index, true) == Libgit2Bindings.GIT_OK;
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Could not reload the libgit2 index", t);
            return false;
        }
    }

    private void freeIndexQuietly(MemorySegment index) {
        if (index == null || index.address() == 0) return;
        try {
            Libgit2Bindings.indexFree(index);
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Error freeing libgit2 index", t);
        }
    }

    /** Builds a {@code git_strarray} holding a single string. */
    private static MemorySegment strArray(Arena arena, String value) {
        MemorySegment string = arena.allocateFrom(value);
        MemorySegment strings = arena.allocate(ValueLayout.ADDRESS);
        strings.set(ValueLayout.ADDRESS, 0, string);

        MemorySegment array = arena.allocate(16); // { char **strings; size_t count; }
        array.set(ValueLayout.ADDRESS, 0, strings);
        array.set(ValueLayout.JAVA_LONG, 8, 1L);
        return array;
    }

    private GitResult delegateStage(String path, String reason) {
        LOGGER.fine("libgit2 stage delegating for " + path + ": " + reason);
        return delegateCli.isOpen() ? delegateCli.stageFile(path) : delegateJgit.stageFile(path);
    }

    private GitResult delegateUnstage(String path) {
        return delegateCli.isOpen() ? delegateCli.unstageFile(path) : delegateJgit.unstageFile(path);
    }

    @Override
    public GitResult unstageAll() {
        ensureOpen();
        return delegateCli.isOpen() ? delegateCli.unstageAll() : delegateJgit.unstageAll();
    }

    @Override
    public GitResult discardChanges(String path) {
        ensureOpen();
        return delegateCli.isOpen() ? delegateCli.discardChanges(path) : delegateJgit.discardChanges(path);
    }

    // ------------------------------------------------------------------
    // Current branch — in-process
    // ------------------------------------------------------------------

    @Override
    public String getCurrentBranch() {
        ensureOpen();
        synchronized (nativeLock) {
            MemorySegment ref = MemorySegment.NULL;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment refOut = arena.allocate(ValueLayout.ADDRESS);
                int rc = Libgit2Bindings.repositoryHead(refOut, repoHandle);
                if (rc != Libgit2Bindings.GIT_OK) {
                    // Unborn branch or detached HEAD with no resolvable ref.
                    return delegateCurrentBranch();
                }
                ref = refOut.get(ValueLayout.ADDRESS, 0);
                String name = Libgit2Bindings.cString(Libgit2Bindings.referenceShorthand(ref));
                // A detached HEAD's shorthand is the literal "HEAD", which is no branch:
                // the other engines answer blank, so ask one of them.
                return name != null && !"HEAD".equals(name) ? name : delegateCurrentBranch();
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "libgit2 head lookup failed", t);
                return delegateCurrentBranch();
            } finally {
                if (ref != null && ref.address() != 0) {
                    try {
                        Libgit2Bindings.referenceFree(ref);
                    } catch (Throwable t) {
                        LOGGER.log(Level.FINE, "Error freeing libgit2 reference", t);
                    }
                }
            }
        }
    }

    private String delegateCurrentBranch() {
        return delegateCli.isOpen() ? delegateCli.getCurrentBranch() : delegateJgit.getCurrentBranch();
    }

    // ------------------------------------------------------------------
    // Engine choice for everything not served in-process above
    // ------------------------------------------------------------------

    /** History and diffs prefer JGit, whose readers are already correct. */
    @Override
    protected GitBackend reader() {
        return delegateJgit.isOpen() ? delegateJgit : delegateCli;
    }

    /** Mutations and network work prefer the CLI, which honours the user's git config. */
    @Override
    protected GitBackend writer() {
        return delegateCli.isOpen() ? delegateCli : delegateJgit;
    }
}
