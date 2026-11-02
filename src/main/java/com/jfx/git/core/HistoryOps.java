package com.jfx.git.core;

import com.jfx.git.graph.GraphPage;
import com.jfx.git.model.BisectStatus;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.CommitPosition;
import com.jfx.git.model.SignatureStatus;
import java.util.List;

/**
 * Reading history: the commit graph, diffs, file content at a commit, blame, the reflog,
 * and bisect.
 *
 * <p>Bisect belongs here rather than with the integration operations because what it
 * really is, is a guided walk of history - it moves HEAD only as a side effect of
 * offering the next commit to test.
 *
 * <p>One of the capability interfaces {@link GitBackend} composes; see its javadoc for
 * why the contract is split up rather than kept as one list.
 */
public interface HistoryOps {

    // ------------------------------------------------------------------
    // Bisect
    // ------------------------------------------------------------------

    /**
     * Begins a bisect session, remembering the commit HEAD was on so {@link
     * #bisectReset()} can return to it.
     *
     * <p>Unlike {@code git bisect}, this never calls the real {@code bisect} porcelain -
     * the session is this application's own, kept in {@code .git/lyra-bisect} so an
     * engine switch or a restart carries on with it (a session the command line started
     * is picked up from git's own files). That is what lets every engine, including JGit, implement it
     * identically: the algorithm is a plain topological range walk ({@code good..bad},
     * exactly what {@code git rev-list --bisect} answers) plus a checkout, both of
     * which every engine already has a native way to do. A future engine that adds
     * bisect support of its own gains nothing from this method deferring to it.
     *
     * <p>Starting again while a session is already active discards it without
     * checking out anything - the caller is expected to have confirmed that with the
     * user first, the same way starting a new rebase over an old one would.
     */
    GitResult bisectStart();

    /**
     * Marks a commit good (known to not have the problem). Once at least one good and
     * one bad commit are marked, this also checks out the next candidate and reports it
     * via the returned {@link BisectStatus} - so a caller only needs to
     * inspect one thing after any mark to know what happened.
     */
    BisectStatus bisectMarkGood(String commitHash);

    /** Marks a commit bad (known to have the problem). See {@link #bisectMarkGood}. */
    BisectStatus bisectMarkBad(String commitHash);

    /**
     * Skips the currently checked-out candidate - it cannot be tested (does not build,
     * say) - and checks out a different one from what remains.
     */
    BisectStatus bisectSkip();

    /** Where the session stands: the current candidate, how many remain, or the answer. */
    BisectStatus bisectStatus();

    /** Ends the session and returns to the commit HEAD was on before {@link #bisectStart()}. */
    GitResult bisectReset();

    // ------------------------------------------------------------------
    // Graph / history
    // ------------------------------------------------------------------

    /**
     * A page of history, laid out in the given order.
     *
     * <p>The order is a parameter rather than something the implementation looks up,
     * because it changes the answer: {@link com.jfx.git.graph.GraphOrder#BY_BRANCH} follows
     * one line of history to its end and keeps the trunk on lane 0, while {@link
     * com.jfx.git.graph.GraphOrder#CHRONOLOGICAL} interleaves branches by date and lets any
     * of them take lane 0. A caller that cares which picture it gets has to be able to say.
     *
     * <p>It used to be read from the stored user preference down inside the walk, which
     * meant a test asking for a graph silently got whichever mode the developer had last
     * chosen in the Preferences dialog — so the same repository produced different lanes on
     * different machines.
     */
    GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order);

    /**
     * A page restricted to the given {@link com.jfx.git.graph.GraphVisibility Hide &amp;
     * Solo} choice.
     *
     * <p>{@link com.jfx.git.graph.GraphVisibility#ALL} - the default the other two
     * overloads use - is unrestricted, so this is a strict superset of the interface's
     * existing behaviour rather than a second code path: a backend can implement it by
     * falling back to {@link #getCommitGraphPage(int, com.jfx.git.graph.GraphOrder)}
     * whenever {@link com.jfx.git.graph.GraphVisibility#isUnrestricted()} is true.
     */
    GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order,
                                 com.jfx.git.graph.GraphVisibility visibility);

    /** A page in the order and Hide/Solo visibility the user has chosen. What the UI calls. */
    default GraphPage getCommitGraphPageVisible(int limit) {
        return getCommitGraphPage(limit, com.jfx.git.graph.GraphOrder.current(),
                com.jfx.git.graph.GraphVisibility.current());
    }

    /**
     * Whether the graph's walk under {@code visibility} reaches {@code hash}: from HEAD,
     * the branches, remotes and tags it leaves standing, and the stash bases the graph
     * draws - the start points of {@link #getCommitGraphPage(int,
     * com.jfx.git.graph.GraphOrder, com.jfx.git.graph.GraphVisibility)}, however deep.
     *
     * <p>What a click on a hidden branch or tag asks before paging history in to find its
     * tip: a tip only the hidden ref reaches is never on any page, and looking for it read
     * history up to the cap for nothing; a tip a shown branch also reaches - an old tag on
     * {@code main} under Solo, a hidden branch merged since - is, further down.
     *
     * <p>Abstract on purpose: a default answering false would quietly leave such a commit
     * unselectable, and one answering true would bring the full-history hunt back.
     *
     * @return false for a hash that does not name a commit
     */
    boolean graphReaches(String hash, com.jfx.git.graph.GraphVisibility visibility);

    /**
     * The repository's refs as one read sees them: the read {@link #getCommitGraphPage(int,
     * com.jfx.git.graph.GraphOrder, com.jfx.git.graph.GraphVisibility)} makes to lay a page
     * out, and hands back with it as {@link GraphPage#refs()}.
     *
     * <p>Not what a refresh's views read - they take the page's (ADR 0002). For when no
     * page comes: one that could not be read, at a branch whose commit is not there or in a
     * corrupt pack, brings no refs, and the sidebar and the toolbar read them here rather
     * than list no branches at all.
     *
     * <p>Abstract on purpose: a default answering {@link com.jfx.git.model.RefSnapshot#EMPTY}
     * would draw a repository with no branches and no remotes, which is the failure this is
     * here to prevent.
     */
    com.jfx.git.model.RefSnapshot getRefSnapshot();

    CommitPosition getCommitPosition(String hash);

    /**
     * The whole message of {@code commitHash}, exactly as the commit stores it, or
     * {@code null} when there is no such commit.
     *
     * <p>A graph page carries only each commit's subject and a preview of its body (see
     * {@link CommitInfo#summary}) - most rows are never opened, and every refresh re-read
     * every body on the page (G15). The detail panel asks for one message here, once a
     * commit is actually selected.
     *
     * <p>Abstract rather than defaulted: a default could only answer {@code null} or the
     * subject, and either would show a commit with a body as one without, silently.
     */
    String getCommitMessage(String commitHash);

    /**
     * Whether {@code commitHash} is GPG-signed, and how far that has actually been
     * checked - see {@link SignatureStatus} for why "signed" and "verified" are kept
     * apart rather than one boolean. Computed on demand for one commit at a time (the
     * commit detail panel's use), not carried on every {@link CommitInfo} the graph
     * loads - most of those are never opened, and verifying a signature is real work,
     * a subprocess call on the native engine.
     */
    SignatureStatus getSignatureStatus(String commitHash);

    /**
     * Whether this backend can materialise real LFS content on checkout, rather than
     * leaving a pointer file sitting in the working tree.
     *
     * <p>The committed blob for an LFS-tracked path is always the pointer text - the
     * real object lives only in {@code .git/lfs/objects/}, addressed separately - so
     * every engine's checkout writes the pointer first, and only a registered smudge
     * filter turns that into real content afterward. Native git already does this
     * transparently once {@code git lfs install} has registered {@code
     * filter.lfs.smudge} somewhere in the effective config (system, global or local) -
     * nothing this app does triggers that registration, and nothing it does is needed
     * once it is there.
     *
     * <p>Default answers {@code false}. This is not "unimplemented, assume the best" -
     * it is the correct answer for any engine with no smudge pipeline wired in at all,
     * which today includes JGit unconditionally: a JGit checkout of an LFS-tracked path
     * writes the raw pointer regardless of what is installed elsewhere on the machine,
     * so it must never be asked to promise otherwise.
     */
    default boolean canMaterializeLfsContent() {
        return false;
    }

    List<CommitInfo> getFileHistoryCommits(String filePath, int limit);

    /**
     * The commits {@code includeRef} has that {@code excludeRef} does not - git's
     * {@code excludeRef..includeRef}.
     *
     * <p>What a pull request would actually propose. Choosing a base branch without it is
     * choosing blind: the two branch names in the form look equally plausible whether the
     * request carries three commits or three hundred, and the difference is usually a
     * stale base rather than a deliberate one.
     *
     * <p>Newest first, as git log reports it. Defaulted to empty so an engine that has not
     * implemented it degrades to a form with no preview rather than a broken one.
     *
     * @param limit the most commits to return; the caller says how many it can draw
     */
    List<CommitInfo> getCommitsInRange(String excludeRef, String includeRef, int limit);

    /**
     * Commits anywhere in history that match {@code query}, newest first by committer
     * date - the order the graph selects in.
     *
     * <p>The graph's filter box only ever sees the rows already loaded; this is the search
     * that reaches the rest. It walks from the graph's start points with nothing hidden -
     * HEAD when it resolves, every branch, remote-tracking branch and tag, and the commit
     * every stash was made on, see {@link com.jfx.git.graph.GraphPageBuilder#startPoints}
     * - regardless of Hide/Solo or hidden stashes, and never {@code --all}, which would
     * also search stash internals and the app's own refs. What
     * each criterion means is defined once, on {@link com.jfx.git.model.CommitSearch}, and
     * {@code BackendConformanceTest} holds every engine to it.
     *
     * <p>Abstract on purpose: a default returning nothing would read to the user as "no
     * commit matches", which is the one answer a search must never give falsely.
     *
     * <p>Stops early, returning what it has found so far, when the {@link GitTaskRunner}
     * task running it is cancelled - a content search over a long history can take a
     * while, and a newer search replaces it.
     *
     * @param limit the most commits to return; a caller that wants to say "limit reached"
     *              asks for one more than it shows
     * @return no commits for an empty query or a non-positive limit
     */
    List<CommitInfo> searchCommits(com.jfx.git.model.CommitSearch query, int limit);

    /**
     * The lines that match {@code query} in the files of one tree - {@code git grep -n -I}.
     *
     * <p>With {@code revOrNull} null the working tree is searched: every file on disk that
     * no ignore rule covers, tracked or untracked - {@code --untracked}, which also leaves
     * out a tracked file an ignore rule covers. Otherwise the files of that commit's tree.
     *
     * <p>Files that look binary - a NUL in their first 8000 bytes, as git decides it -
     * are skipped, as are submodules and symbolic links. Each line is reported once
     * however often it matches, without its line ending, numbered from 1.
     *
     * <p>In git's order: by path, and by line within a file. Stops at {@code limit}
     * matches, and early, with what it has,
     * when the {@link GitTaskRunner} task running it is cancelled.
     *
     * <p>Abstract on purpose, for the reason {@link #searchCommits} is: a default finding
     * nothing would read as "no file contains this".
     *
     * @param revOrNull a revision whose tree to search, or {@code null} for the working tree
     * @param limit     the most matches to return; a caller that wants to say "limit
     *                  reached" asks for one more than it shows
     * @return no matches for an empty query or a non-positive limit
     * @throws GitOperationException when the revision does not resolve or the expression
     *                               cannot be read
     */
    List<com.jfx.git.model.GrepMatch> grep(String revOrNull, com.jfx.git.model.GrepQuery query, int limit);

    // ------------------------------------------------------------------
    // Reflog
    // ------------------------------------------------------------------

    List<ReflogItem> getReflog(int limit);

    // ------------------------------------------------------------------
    // Diffs and file content
    // ------------------------------------------------------------------

    byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, boolean ignoreWhitespace);
    byte[] getDiffBytesForFileInCommit(CommitInfo commit, String filePath, boolean ignoreWhitespace);

    /**
     * The diff a commit made to one file that may have been renamed or copied.
     *
     * <p>A diff narrowed to the new path alone cannot see the old one, so a renamed file
     * came back as an addition of its whole content. Naming both lets the engine pair them
     * and show what actually changed across the rename.
     *
     * @param oldPath where the file came from ({@link FileChange#oldPath()}), or
     *                {@code null} for a change that kept its path
     */
    byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, String oldPath,
                                       boolean ignoreWhitespace);

    byte[] getUnstagedDiffBytes(String filePath, boolean ignoreWhitespace);
    byte[] getStagedDiffBytes(String filePath, boolean ignoreWhitespace);

    /**
     * How far each local branch is ahead of and behind its upstream.
     *
     * <p>Keyed by short branch name; the value is {@code {ahead, behind}}. Only branches
     * that differ from their upstream are present: one with no upstream, a deleted
     * upstream, or one exactly in step with it is absent, since none of those has a count
     * for the sidebar to draw. So absence does not mean "not published" - ask the refs
     * whether an upstream holds HEAD, as the amend warning does.
     */
    java.util.Map<String, int[]> getBranchTracking();

    List<String> getFilesInCommit(String commitHash);
    List<String> getFilesInCommit(CommitInfo commit);

    /**
     * What a commit did to one file: how it changed, and by how much.
     *
     * <p>{@code added} and {@code deleted} are {@link #UNKNOWN_COUNT} when the backend
     * cannot supply them, and for binary files, where git reports {@code -} rather than a
     * line count. The UI shows the status letter alone in that case rather than inventing
     * a zero, which would read as "nothing changed".
     *
     * @param status  one of {@code A M D R C T}, as git's {@code --name-status} reports it
     * @param oldPath where a renamed or copied file came from; {@code null} for every
     *                other change, whose path did not move
     */
    record FileChange(String path, char status, int added, int deleted, String oldPath) {
        /** Counts are not available — binary file, or a backend that cannot report them. */
        public static final int UNKNOWN_COUNT = -1;

        /** A change that kept its path. */
        public FileChange(String path, char status, int added, int deleted) {
            this(path, status, added, deleted, null);
        }

        public boolean hasCounts() {
            return added != UNKNOWN_COUNT && deleted != UNKNOWN_COUNT;
        }
    }

    /**
     * The files a commit touched, with status and line counts.
     */
    List<FileChange> getChangesInCommit(String commitHash);

    /**
     * Top-level directories present in the commit tree, whether or not this commit
     * changed them. Names are sorted and repository-relative.
     */
    default List<String> getTopLevelDirectoriesAtCommit(String commitRef) {
        throw new UnsupportedOperationException(
                "This Git backend does not support listing commit tree directories");
    }

    /**
     * Paths that differ between two commits.
     *
     * <p>Not the same as the union of what each commit touched: comparing two points in
     * history asks what is different <em>now</em>, so a file changed and changed back does
     * not appear.
     */
    List<String> getFilesBetweenCommits(String fromHash, String toHash);

    /**
     * What differs between two commits, per file: how it changed and by how much.
     *
     * <p>The two-point counterpart of {@link #getChangesInCommit}, with the same letters
     * and the same rename detection. Not defaulted: a fallback that read
     * {@link #getFilesBetweenCommits} and called everything a modification is exactly what
     * the comparison view used to show, with every added file marked {@code M}.
     */
    List<FileChange> getChangesBetweenCommits(String fromHash, String toHash);

    /** Unified diff of one path between two commits. */
    byte[] getDiffBytesBetweenCommits(String fromHash, String toHash,
                                      String filePath, boolean ignoreWhitespace);

    /**
     * Unified diff of one path between two commits, across a rename.
     *
     * @param oldPath where the file was at {@code fromHash} if it moved, else {@code null};
     *                see {@link #getDiffBytesForFileInCommit(String, String, String, boolean)}
     */
    byte[] getDiffBytesBetweenCommits(String fromHash, String toHash,
                                      String filePath, String oldPath, boolean ignoreWhitespace);

    String getFileContentAtCommit(String commitHash, String filePath);
    String getFileContentAtCommit(CommitInfo commit, String filePath);

    byte[] getFileBytesAtCommit(String commitHash, String filePath);

    /**
     * Writes {@code filePath} as of {@code commitHash} over the working-tree copy,
     * creating parent directories if the file was deleted along with its folder. The
     * restored content is left unstaged.
     *
     * @return a failed result when the blob does not exist in that commit
     */
    GitResult restoreFileFromCommit(String commitHash, String filePath);

    /**
     * The same restore, written into {@code intoPath} rather than {@code filePath}: for a
     * file renamed since that commit, whose working copy is under the newer name. Only
     * {@code intoPath} is written - {@code filePath} is not recreated - and the result is
     * left unstaged.
     *
     * <p>Not defaulted to a read and a write in the UI: the CLI's restore runs the blob
     * through the work tree's filters (line endings, LFS), and a composed write would not,
     * so the two answers of one confirmation would write different bytes.
     *
     * @param intoPath the working-tree file to write; {@code filePath} itself is the plain
     *                 restore
     * @return a failed result when the blob does not exist in that commit
     */
    GitResult restoreFileFromCommit(String commitHash, String filePath, String intoPath);
    byte[] getFileBytesAtCommit(CommitInfo commit, String filePath);

    byte[][] getCommitSides(CommitInfo commit, String path);
    byte[][] getWorkingTreeSides(String path);

    String getConflictStage(String path, int stage);

    /**
     * One side of a conflicted file exactly as the index holds it, undecoded.
     *
     * <p>For handing to a program outside the application. {@link #getConflictStage}
     * decodes to text, and writing that text back out as a file would give an external
     * merge tool three inputs in an encoding - and with line endings - the real file
     * never had, which it would then faithfully write into the result.
     *
     * @param stage 1 = common ancestor, 2 = ours, 3 = theirs
     * @return the bytes; empty when that stage does not exist, as for a file one side added
     */
    byte[] getConflictStageBytes(String path, int stage);
    GitResult resolveConflict(String path, String content);

    String  getWorkingTreeContent(String path);
    byte[]  getWorkingTreeBytes(String path);
    GitResult writeWorkingTreeFile(String path, byte[] bytes);

    /**
     * Undoes one hunk in the working-tree copy of {@code path}, the equivalent of
     * {@code git apply --reverse} for one hunk.
     *
     * <p>{@code edit}'s search block is the hunk's "after" content, to be found in the
     * file as it stands, and its replacement is the "before" content to restore.
     */
    GitResult revertHunk(String path, HunkEdit edit);

    /**
     * Rewrites part of the index copy of {@code path} - what stages or unstages one hunk.
     *
     * @param action verb for the status message, e.g. {@code "Staged hunk"}
     */
    GitResult applyToIndex(String path, HunkEdit edit, String action);

    // ------------------------------------------------------------------
    // Blame
    // ------------------------------------------------------------------

    List<BlameLine> getBlameLines(String filePath, String startCommit);

    // ------------------------------------------------------------------
    // Conflict prediction
    // ------------------------------------------------------------------

    /**
     * The files merging {@code theirs} into {@code ours} would leave conflicted, worked
     * out in memory: the working tree, the index and HEAD are not touched, so this can run
     * in the background while the user works.
     *
     * <p>An object-database read, so the history engine answers it. Merge results may be
     * written as loose objects along the way; nothing references them and git's own
     * housekeeping removes them.
     *
     * @return {@link ConflictPrediction#unsupported()} on a git older than 2.38 (no
     *         {@code merge-tree --write-tree}), for unrelated histories, or for a
     *         revision that does not resolve
     */
    com.jfx.git.model.ConflictPrediction predictMergeConflicts(String ours, String theirs);
}
