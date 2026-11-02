package com.jfx.git.core;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.ResetMode;

import java.io.File;
import java.util.List;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.model.BisectStatus;
import com.jfx.git.model.CommitPosition;
import com.jfx.git.model.PullMode;
import com.jfx.git.model.RebaseRange;
import com.jfx.git.model.StagingState;
import com.jfx.git.model.Submodule;

/**
 * Routes every {@link GitBackend} call to one of two engines.
 *
 * <h2>Why this exists</h2>
 * Three classes needed the same thing — {@link HybridGitBackend},
 * {@link com.jfx.git.core.libgit2.Libgit2Backend} and {@link SwitchableGitBackend} each
 * forwarded the whole ~90-method interface by hand, and each had independently arrived at
 * the same split of which engine should serve which call. Keeping three hand-written
 * copies in step meant a new interface method had to be added in four places, and a
 * mistake in any one of them would have been a silent behaviour difference between
 * engines rather than a compile error.
 *
 * <h2>The split</h2>
 * <dl>
 *   <dt>{@link #reader()}</dt>
 *   <dd>Walking history, formatting diffs, reading blobs out of commits, and planning a
 *       rebase. JGit is usually the better answer here: it already has the object
 *       database open, and these are the operations where a subprocess per call costs
 *       most.</dd>
 *   <dt>{@link #writer()}</dt>
 *   <dd>Anything that changes the repository, anything that talks to a remote, and
 *       working-tree reads. The git CLI is usually the better answer: it honours the
 *       user's own config, credential helpers and hooks, which a library reimplementation
 *       does not.</dd>
 * </dl>
 *
 * <h2>The rule</h2>
 * Anything that reads or writes the index or the working tree goes to the writer, and so
 * does anything only one engine can do properly. Per-method exceptions argued case by case
 * had drifted: hunk patching went to the reader "because it needs the diff machinery",
 * though the hunks came from the writer's diff and both engines patch through the same
 * HunkPatcher - so JGit rewrote {@code .git/index} behind git's back; "Save as patch" went
 * to JGit, which refuses binary files; signature checks went to JGit, which cannot verify
 * one. The reader is left with what only reads the object database: history, commits,
 * trees and blobs.
 *
 * <p>Every method of the interface is declared here, defaults included, so an engine that
 * overrides a default is not silently bypassed; {@code DelegatingGitBackendTest} checks it.
 *
 * <p>Subclasses decide what those two are and own the lifecycle, since opening one
 * engine, two, or three differs between them.
 *
 * <p>Individual methods stay overridable rather than {@code final}, because an engine
 * that genuinely serves a call itself needs to say so — {@link
 * com.jfx.git.core.libgit2.Libgit2Backend} overrides the dozen it answers in-process and
 * inherits the rest. The default for a subclass is still to change {@link #reader()} or
 * {@link #writer()} rather than a single method: overriding one call is a claim that this
 * engine does that work, and should read as one.
 */
public abstract class DelegatingGitBackend implements GitBackend {

    /** Engine for history walks, diff formatting and rebase planning. */
    protected abstract GitBackend reader();

    /** Engine for mutations, network transport and working-tree reads. */
    protected abstract GitBackend writer();

    // ------------------------------------------------------------------
    // Branches and refs
    // ------------------------------------------------------------------

    @Override public List<RefInfo> getLocalBranches() { return writer().getLocalBranches(); }
    @Override public List<RefInfo> getRemoteBranches() { return writer().getRemoteBranches(); }
    @Override public List<RefInfo> getTags() { return writer().getTags(); }
    @Override public String getCurrentBranch() { return writer().getCurrentBranch(); }
    @Override public int[] getTrackingStatus() { return writer().getTrackingStatus(); }
    @Override public java.util.Map<String, int[]> getBranchTracking() { return writer().getBranchTracking(); }

    @Override public GitResult checkoutBranch(String b) { return writer().checkoutBranch(b); }
    @Override public boolean isHeadDetached() { return writer().isHeadDetached(); }
    @Override public String symbolicRefTarget(String n) { return writer().symbolicRefTarget(n); }
    @Override public boolean isDetachedHeadOrphanRisk() { return writer().isDetachedHeadOrphanRisk(); }
    @Override public GitResult checkoutRemoteBranch(String b) { return writer().checkoutRemoteBranch(b); }
    @Override public GitResult createBranch(String n, String s, boolean c) { return writer().createBranch(n, s, c); }
    @Override public GitResult deleteBranch(String n, boolean f) { return writer().deleteBranch(n, f); }
    @Override public GitResult renameBranch(String o, String n) { return writer().renameBranch(o, n); }
    // The writer, because it is the engine whose pull and push the upstream steers.
    @Override public String getUpstream(String b) { return writer().getUpstream(b); }
    @Override public java.util.Map<String, String> getUpstreams() { return writer().getUpstreams(); }
    @Override public GitResult setUpstream(String b, String u) { return writer().setUpstream(b, u); }
    @Override public GitResult unsetUpstream(String b) { return writer().unsetUpstream(b); }
    // The writer, with the upstreams: what is gone is what the last fetch it ran pruned.
    @Override public java.util.Set<String> getGoneBranches() { return writer().getGoneBranches(); }
    @Override public List<String> getBranchesMergedInto(String t) { return writer().getBranchesMergedInto(t); }

    // ------------------------------------------------------------------
    // Staging area
    // ------------------------------------------------------------------

    @Override public boolean hasUncommittedChanges() throws GitOperationException { return writer().hasUncommittedChanges(); }
    @Override public List<String> getStagedFiles() { return writer().getStagedFiles(); }
    @Override public List<String> getConflictingFiles() { return writer().getConflictingFiles(); }
    @Override public StagingState getStagingState() { return writer().getStagingState(); }

    @Override public GitResult stageFile(String p) { return writer().stageFile(p); }
    @Override public GitResult stageAll() { return writer().stageAll(); }
    @Override public GitResult unstageFile(String p) { return writer().unstageFile(p); }
    @Override public GitResult unstageAll() { return writer().unstageAll(); }
    @Override public GitResult discardChanges(String p) { return writer().discardChanges(p); }
    @Override public GitResult discardAllChanges() { return writer().discardAllChanges(); }

    // ------------------------------------------------------------------
    // Committing
    // ------------------------------------------------------------------

    @Override public GitResult commit(String m, boolean amend) { return writer().commit(m, amend); }
    @Override public GitResult commit(String m, boolean amend, boolean skipHooks) { return writer().commit(m, amend, skipHooks); }
    @Override public String getHeadCommitMessage() { return writer().getHeadCommitMessage(); }
    @Override public String getCommitTemplate() { return writer().getCommitTemplate(); }
    // The writer, because it is the engine that will make the commit being signed off.
    @Override public String getCommitIdentity() { return writer().getCommitIdentity(); }
    @Override public String getHeadHash() { return writer().getHeadHash(); }
    @Override public GitResult setLocalIdentity(String n, String e, String k, boolean s) { return writer().setLocalIdentity(n, e, k, s); }
    // Read from the engine that writes it, so a value just set is the value read back.
    @Override public String getLocalConfig(String k) { return writer().getLocalConfig(k); }
    @Override public GitResult setLocalConfig(String k, String v) { return writer().setLocalConfig(k, v); }

    // ------------------------------------------------------------------
    // Remotes and network
    // ------------------------------------------------------------------

    @Override public List<String> getRemoteNames() { return writer().getRemoteNames(); }
    @Override public String getRemoteUrl(String n) { return writer().getRemoteUrl(n); }
    @Override public GitResult addRemote(String n, String u) { return writer().addRemote(n, u); }
    @Override public GitResult removeRemote(String n) { return writer().removeRemote(n); }
    @Override public String getRemotePushUrl(String n) { return writer().getRemotePushUrl(n); }
    @Override public GitResult renameRemote(String o, String n) { return writer().renameRemote(o, n); }
    @Override public GitResult setRemoteUrl(String n, String u, boolean push) { return writer().setRemoteUrl(n, u, push); }
    @Override public GitResult fetchRemote(String n) { return writer().fetchRemote(n); }
    @Override public GitResult deleteRemoteBranch(String r, String b) { return writer().deleteRemoteBranch(r, b); }
    @Override public GitResult fetchAll() { return writer().fetchAll(); }
    @Override public GitResult fetchAllSilently() { return writer().fetchAllSilently(); }
    @Override public GitResult pull(PullMode m) { return writer().pull(m); }
    @Override public PushLease pushLease(String r) { return writer().pushLease(r); }
    @Override public GitResult push(String r, PushLease l, boolean t, boolean u) { return writer().push(r, l, t, u); }
    @Override public GitResult checkoutPullRequest(String r, int n, boolean reset) { return writer().checkoutPullRequest(r, n, reset); }
    @Override public GitResult checkoutRemoteRef(String r, String ref, String local, boolean reset) { return writer().checkoutRemoteRef(r, ref, local, reset); }
    @Override public GitResult fetchRemoteRef(String r, String ref) { return writer().fetchRemoteRef(r, ref); }
    // The writer, because it is what fetchRemoteRef just fetched the commits into view of.
    @Override public String mergeBase(String a, String b) { return writer().mergeBase(a, b); }

    // ------------------------------------------------------------------
    // Merge, rebase, cherry-pick
    // ------------------------------------------------------------------

    @Override public GitResult merge(String b, com.jfx.git.model.MergeMode mode, String m) { return writer().merge(b, mode, m); }
    @Override public GitResult rebase(String u) { return writer().rebase(u); }

    // The in-progress operation is read and driven by the engine that runs the
    // operations themselves, so "continue" lands where the stopped rebase was started.
    @Override public com.jfx.git.model.OperationState getOperationState() { return writer().getOperationState(); }
    @Override public GitResult continueOperation() { return writer().continueOperation(); }
    @Override public GitResult skipOperation() { return writer().skipOperation(); }
    @Override public GitResult abortOperation() { return writer().abortOperation(); }

    // Planning a rebase is a history walk; running one changes the repository - and
    // runs the user's hooks, signing and includeIf identity, which only the CLI honours.
    // It used to go to reader(), i.e. JGit, on both default engines: commit.gpgsign threw
    // mid-rebase and left a detached HEAD, and an includeIf identity was silently lost.
    @Override public GitResult rebaseInteractive(RebaseTodo t) { return writer().rebaseInteractive(t); }
    @Override public RebaseRange getRebaseRange(String h) { return reader().getRebaseRange(h); }
    // Reads the just-written result of a batch of cherry-picks, which run on writer() -
    // reading it back from a possibly-different reader() engine risks the reader's
    // already-open repository handle not yet seeing what the writer just wrote.
    @Override public List<CommitInfo> getCommitsSince(String h) { return writer().getCommitsSince(h); }

    // The writer: JGit refuses a patch that touches a binary file; git writes --binary.
    @Override public GitResult createPatch(List<String> c, java.io.File o) { return writer().createPatch(c, o); }
    @Override public GitResult applyPatch(java.io.File p, com.jfx.git.model.PatchApplyMode m) { return writer().applyPatch(p, m); }
    @Override public GitResult cherryPick(String h) { return writer().cherryPick(h); }
    @Override public GitResult cherryPick(String h, int m) { return writer().cherryPick(h, m); }
    @Override public GitResult revert(String h, int m) { return writer().revert(h, m); }
    @Override public GitResult revert(String h) { return writer().revert(h); }
    @Override public GitResult reset(String c, ResetMode m) { return writer().reset(c, m); }

    // ------------------------------------------------------------------
    // Bisect
    // ------------------------------------------------------------------

    // A bisect session is kept in the git directory (BisectSession.STATE_FILE) and every
    // engine re-reads it before acting, so which engine instance answers - including a
    // new one after SwitchableGitBackend swaps engines mid-session - does not matter.
    @Override public GitResult bisectStart() { return writer().bisectStart(); }
    @Override public BisectStatus bisectMarkGood(String h) { return writer().bisectMarkGood(h); }
    @Override public BisectStatus bisectMarkBad(String h) { return writer().bisectMarkBad(h); }
    @Override public BisectStatus bisectSkip() { return writer().bisectSkip(); }
    @Override public BisectStatus bisectStatus() { return writer().bisectStatus(); }
    @Override public GitResult bisectReset() { return writer().bisectReset(); }

    // ------------------------------------------------------------------
    // Graph and history
    // ------------------------------------------------------------------

    // Only the explicit form is forwarded; the one-argument version is the interface
    // default, which resolves the preference once here rather than again in the engine.
    @Override public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder o) { return reader().getCommitGraphPage(limit, o); }
    @Override public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder o, com.jfx.git.graph.GraphVisibility v) { return reader().getCommitGraphPage(limit, o, v); }
    // The reader, because it is what walks the pages the answer is about.
    @Override public boolean graphReaches(String h, com.jfx.git.graph.GraphVisibility v) { return reader().graphReaches(h, v); }
    // The reader, because it is what reads them for the pages: the refs a page that failed
    // did not bring are the ones it would have.
    @Override public com.jfx.git.model.RefSnapshot getRefSnapshot() { return reader().getRefSnapshot(); }
    @Override public CommitPosition getCommitPosition(String h) { return reader().getCommitPosition(h); }
    // The reader, because it is what walked the graph page the hash came from.
    @Override public String getCommitMessage(String h) { return reader().getCommitMessage(h); }
    // The writer, though it only reads: verifying a signature means running gpg or ssh,
    // which the CLI does and JGit cannot - JGit can only say a signature is present. On
    // the reader, a commit with a bad signature showed "Signed (unverified)" while the
    // engine that could say BAD was open beside it.
    @Override public com.jfx.git.model.SignatureStatus getSignatureStatus(String h) { return writer().getSignatureStatus(h); }
    // Whether checkout smudges LFS content is a fact about whichever engine actually
    // writes the working tree, not about whichever one is faster to ask.
    @Override public boolean canMaterializeLfsContent() { return writer().canMaterializeLfsContent(); }
    // A history read, routed to the writer by measurement: git walks a file's history
    // across renames two to three times faster than JGit - on a 20,000-commit history,
    // 159 ms against 360, and 119 ms once a commit-graph with changed-path filters exists,
    // which JGit's rename-following walk cannot use. Every other history read measured as
    // fast or faster on JGit (search 70 ms against 200, a commit's position 62 against
    // 104), so this is the one exception to "history goes to the reader".
    @Override public List<CommitInfo> getFileHistoryCommits(String p, int l) { return writer().getFileHistoryCommits(p, l); }
    @Override public List<CommitInfo> getCommitsInRange(String e, String i, int l) {
        return reader().getCommitsInRange(e, i, l);
    }
    // A history walk like the graph's, so it goes where the graph goes.
    @Override public List<CommitInfo> searchCommits(com.jfx.git.model.CommitSearch q, int l) { return reader().searchCommits(q, l); }
    // The writer: a search of the working tree reads it, and the rule sends those there.
    @Override public List<com.jfx.git.model.GrepMatch> grep(String r, com.jfx.git.model.GrepQuery q, int l) { return writer().grep(r, q, l); }
    @Override public List<ReflogItem> getReflog(int limit) { return writer().getReflog(limit); }

    // ------------------------------------------------------------------
    // Diffs and file content
    // ------------------------------------------------------------------

    @Override public byte[] getDiffBytesForFileInCommit(String h, String p, boolean w) { return reader().getDiffBytesForFileInCommit(h, p, w); }
    @Override public byte[] getDiffBytesForFileInCommit(CommitInfo c, String p, boolean w) { return reader().getDiffBytesForFileInCommit(c, p, w); }
    @Override public byte[] getDiffBytesForFileInCommit(String h, String p, String o, boolean w) { return reader().getDiffBytesForFileInCommit(h, p, o, w); }
    @Override public List<String> getFilesInCommit(String h) { return reader().getFilesInCommit(h); }
    @Override public List<String> getTopLevelDirectoriesAtCommit(String h) {
        return reader().getTopLevelDirectoriesAtCommit(h);
    }
    @Override public List<String> getFilesInCommit(CommitInfo c) { return reader().getFilesInCommit(c); }
    @Override public List<FileChange> getChangesInCommit(String h) { return reader().getChangesInCommit(h); }
    @Override public java.util.Map<String, int[]> getWorkingTreeLineCounts(
            boolean staged, List<String> paths) {
        return writer().getWorkingTreeLineCounts(staged, paths);
    }
    @Override public List<String> getFilesBetweenCommits(String f, String t) { return reader().getFilesBetweenCommits(f, t); }
    @Override public List<FileChange> getChangesBetweenCommits(String f, String t) { return reader().getChangesBetweenCommits(f, t); }
    @Override public byte[] getDiffBytesBetweenCommits(String f, String t, String p, boolean w) { return reader().getDiffBytesBetweenCommits(f, t, p, w); }
    @Override public byte[] getDiffBytesBetweenCommits(String f, String t, String p, String o, boolean w) { return reader().getDiffBytesBetweenCommits(f, t, p, o, w); }

    @Override public String getFileContentAtCommit(String h, String p) { return reader().getFileContentAtCommit(h, p); }
    @Override public String getFileContentAtCommit(CommitInfo c, String p) { return reader().getFileContentAtCommit(c, p); }
    @Override public byte[] getFileBytesAtCommit(String h, String p) { return reader().getFileBytesAtCommit(h, p); }
    @Override public byte[] getFileBytesAtCommit(CommitInfo c, String p) { return reader().getFileBytesAtCommit(c, p); }
    @Override public byte[][] getCommitSides(CommitInfo c, String p) { return reader().getCommitSides(c, p); }
    // Index and working-tree reads: the writer, so the staging list, the diff beside it
    // and the conflict panel are all read from the engine that writes them.
    @Override public byte[][] getWorkingTreeSides(String p) { return writer().getWorkingTreeSides(p); }
    @Override public String getConflictStage(String p, int s) { return writer().getConflictStage(p, s); }
    @Override public byte[] getConflictStageBytes(String p, int s) { return writer().getConflictStageBytes(p, s); }
    @Override public byte[] getStagedBytes(String p) { return writer().getStagedBytes(p); }

    // Patching the index or the working tree is a write. The hunks come from the writer's
    // own diff, and both engines apply them through the same HunkPatcher.
    @Override public GitResult revertHunk(String p, HunkEdit e) { return writer().revertHunk(p, e); }
    @Override public GitResult applyToIndex(String p, HunkEdit e, String a) { return writer().applyToIndex(p, e, a); }

    // The working tree is a filesystem read, not a history one.
    @Override public byte[] getUnstagedDiffBytes(String p, boolean w) { return writer().getUnstagedDiffBytes(p, w); }
    @Override public byte[] getStagedDiffBytes(String p, boolean w) { return writer().getStagedDiffBytes(p, w); }
    @Override public GitResult resolveConflict(String p, String c) { return writer().resolveConflict(p, c); }
    @Override public String getWorkingTreeContent(String p) { return writer().getWorkingTreeContent(p); }
    @Override public byte[] getWorkingTreeBytes(String p) { return writer().getWorkingTreeBytes(p); }
    @Override public GitResult writeWorkingTreeFile(String p, byte[] b) { return writer().writeWorkingTreeFile(p, b); }
    // Restoring a file into the working tree is a write, not a history read.
    @Override public GitResult restoreFileFromCommit(String hash, String path) { return writer().restoreFileFromCommit(hash, path); }
    @Override public GitResult restoreFileFromCommit(String hash, String path, String into) {
        return writer().restoreFileFromCommit(hash, path, into);
    }

    @Override public List<BlameLine> getBlameLines(String p, String c) { return writer().getBlameLines(p, c); }

    // ------------------------------------------------------------------
    // Stash
    // ------------------------------------------------------------------

    @Override public List<com.jfx.git.model.StashEntry> getStashEntries() { return writer().getStashEntries(); }
    @Override public GitResult stash(com.jfx.git.model.StashOptions o) { return writer().stash(o); }
    @Override public GitResult stashPop(int i) { return writer().stashPop(i); }
    @Override public GitResult stashApply(int i) { return writer().stashApply(i); }
    @Override public GitResult stashDrop(int i) { return writer().stashDrop(i); }
    @Override public GitResult stashBranch(String b, int i) { return writer().stashBranch(b, i); }

    // ------------------------------------------------------------------
    // Working-tree snapshots
    // ------------------------------------------------------------------

    // The writer: taking one reads the index and the working tree, restoring one writes
    // the working tree, and the list is read from the engine that writes the refs.
    @Override public String snapshotWorkingTree(String r) { return writer().snapshotWorkingTree(r); }
    @Override public List<com.jfx.git.model.Snapshot> listSnapshots() { return writer().listSnapshots(); }
    @Override public GitResult deleteSnapshot(String id) { return writer().deleteSnapshot(id); }
    @Override public GitResult restoreFromSnapshot(String id, List<String> p) { return writer().restoreFromSnapshot(id, p); }

    // ------------------------------------------------------------------
    // Conflict prediction
    // ------------------------------------------------------------------

    // The reader: an in-memory merge of two commits only reads the object database.
    @Override public com.jfx.git.model.ConflictPrediction predictMergeConflicts(String o, String t) {
        return reader().predictMergeConflicts(o, t);
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    @Override public GitResult createTag(String n, String m, String c) { return writer().createTag(n, m, c); }
    @Override public GitResult deleteTag(String n) { return writer().deleteTag(n); }
    @Override public String getRawTagObjectId(String t) { return writer().getRawTagObjectId(t); }
    @Override public GitResult restoreTagRef(String t, String o) { return writer().restoreTagRef(t, o); }
    @Override public GitResult deleteRemoteTag(String r, String t) { return writer().deleteRemoteTag(r, t); }
    @Override public GitResult pushTag(String r, String t) { return writer().pushTag(r, t); }
    @Override public boolean isTagMoveFastForward(String t, String c) { return writer().isTagMoveFastForward(t, c); }
    @Override public GitResult moveTag(String t, String c) { return writer().moveTag(t, c); }

    // ------------------------------------------------------------------
    // Sparse Checkout
    // ------------------------------------------------------------------

    @Override public boolean isSparseCheckoutEnabled() { return writer().isSparseCheckoutEnabled(); }
    @Override public List<String> getSparseCheckoutPaths() { return writer().getSparseCheckoutPaths(); }
    @Override public GitResult setSparseCheckoutPaths(List<String> p) { return writer().setSparseCheckoutPaths(p); }
    @Override public GitResult disableSparseCheckout() { return writer().disableSparseCheckout(); }

    // Git LFS: all of it to the writer, which is the CLI wherever git-lfs can run at all.
    @Override public List<String> lfsTrackedPatterns() { return writer().lfsTrackedPatterns(); }
    @Override public GitResult lfsInstall() { return writer().lfsInstall(); }
    @Override public GitResult lfsTrack(String p) { return writer().lfsTrack(p); }
    @Override public GitResult lfsUntrack(String p) { return writer().lfsUntrack(p); }
    @Override public GitResult lfsFetch() { return writer().lfsFetch(); }
    @Override public GitResult lfsPull() { return writer().lfsPull(); }
    @Override public GitResult lfsPrune() { return writer().lfsPrune(); }
    @Override public List<com.jfx.git.model.LfsLock> lfsLocks() { return writer().lfsLocks(); }
    @Override public GitResult lfsLock(String p) { return writer().lfsLock(p); }
    @Override public GitResult lfsUnlock(String p, boolean f) { return writer().lfsUnlock(p, f); }

    // ------------------------------------------------------------------
    // Submodules and worktrees
    // ------------------------------------------------------------------

    @Override public List<Submodule> getSubmodules() { return reader().getSubmodules(); }
    @Override public GitResult updateSubmodules(List<String> p) { return writer().updateSubmodules(p); }
    @Override public GitResult addSubmodule(String u, String p) { return writer().addSubmodule(u, p); }
    @Override public GitResult syncSubmodules() { return writer().syncSubmodules(); }
    @Override public GitResult deinitSubmodule(String p, boolean f) { return writer().deinitSubmodule(p, f); }
    @Override public File submoduleWorkTree(String p) { return writer().submoduleWorkTree(p); }

    @Override public List<Worktrees.Worktree> getWorktrees() { return reader().getWorktrees(); }
    @Override public GitResult addWorktree(File p, String b, boolean c) { return writer().addWorktree(p, b, c); }
    @Override public GitResult removeWorktree(File p, boolean f) { return writer().removeWorktree(p, f); }
    @Override public GitResult pruneWorktrees() { return writer().pruneWorktrees(); }
    @Override public GitResult setWorktreeLocked(File p, boolean l) { return writer().setWorktreeLocked(p, l); }

    // Housekeeping goes to the writer - it rewrites the object database - and reading
    // which hooks exist is a look at a directory, which either engine answers the same.
    @Override public GitResult runMaintenance() { return writer().runMaintenance(); }
    @Override public boolean needsMaintenance() { return writer().needsMaintenance(); }
    @Override public List<Hook> getHooks() { return reader().getHooks(); }

    // ------------------------------------------------------------------
    // The interface's convenience overloads
    // ------------------------------------------------------------------
    //
    // A default composed from the methods above; forwarding it changes nothing while no
    // engine overrides it. It is forwarded anyway so that one that does is reached:
    // inherited here, the composition ran on the wrapper and an engine's own version was
    // skipped without a sound. It goes where the method it composes goes. The other
    // conveniences callers use are not on the interface at all: see RepositoryCommands.

    @Override public GraphPage getCommitGraphPageVisible(int l) { return reader().getCommitGraphPageVisible(l); }
}
