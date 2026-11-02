package com.jfx.git.core;

import com.jfx.git.graph.GraphPage;
import com.jfx.git.model.BisectStatus;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.CommitPosition;
import com.jfx.git.model.MergeMode;
import com.jfx.git.model.PullMode;
import com.jfx.git.model.RebaseRange;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.ResetMode;
import com.jfx.git.model.StagingState;
import com.jfx.git.model.StashOptions;
import com.jfx.git.model.Submodule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link GitBackend} that runs the user's own {@code git}.
 *
 * <h2>Where the work is</h2>
 * This class is the engine's public face and nothing more: every call is handed to one of
 * the package-private {@code Cli*} classes, one per capability, which share a
 * {@link CliRepo} - the repository the engine is open on, its {@link NativeGitRunner}, and
 * what open and closed mean. The split keeps each capability's commands, and the comments
 * explaining them, in a class of its own size:
 * <ul>
 *   <li>{@link CliRepo} - lifecycle, configuration, the network environment;</li>
 *   <li>{@link CliStaging} - status, staging, hunks, discarding, committing;</li>
 *   <li>{@link CliBranches} - branches, tags, checkout, upstreams;</li>
 *   <li>{@link CliRemotes} - remotes, fetch, pull, push;</li>
 *   <li>{@link CliIntegration} - merge, rebase, cherry-pick, revert, reset, patches;</li>
 *   <li>{@link CliStash}, {@link CliBisect};</li>
 *   <li>{@link CliHistory} - the graph, search, file history, blame, reflog;</li>
 *   <li>{@link CliDiffs} - diffs, blobs, working-tree content;</li>
 *   <li>{@link CliStructure} - sparse checkout, submodules, worktrees, maintenance, hooks;</li>
 *   <li>{@link CliLfs};</li>
 *   <li>{@link CliParsers} - git's output read into values, with no git involved;</li>
 *   <li>{@link CliClone} - {@code clone} and {@code init}, behind the statics here;</li>
 *   <li>{@link GitCommand} - how a revision or path from the UI is put on a command line.</li>
 * </ul>
 */
public class NativeGitBackend implements GitBackend {

    /** The format of a graph page's {@code git log}; see {@link CliHistory#GRAPH_FORMAT}. */
    static final String GRAPH_FORMAT = CliHistory.GRAPH_FORMAT;

    private final CliRepo repo = new CliRepo();

    public NativeGitBackend() {}

    public NativeGitBackend(File dir) throws GitOperationException {
        openRepository(dir);
    }

    // ------------------------------------------------------------------
    // CliRepo
    // ------------------------------------------------------------------

    @Override public boolean openRepository(File dir) throws GitOperationException {
        return repo.openRepository(dir);
    }
    @Override public boolean isOpen() { return repo.isOpen(); }
    @Override public File getDirectory() { return repo.getDirectory(); }
    @Override public File getWorkTree() { return repo.getWorkTree(); }
    @Override public void close() { repo.close(); }

    // ------------------------------------------------------------------
    // CliClone
    // ------------------------------------------------------------------

    /** See {@link CliClone#initRepository}. */
    public static GitResult initRepository(File dir) { return CliClone.initRepository(dir); }
    /** See {@link CliClone#initRepository}. */
    public static GitResult initRepository(File dir, RepoInitTemplates templates) {
        return CliClone.initRepository(dir, templates);
    }
    /** See {@link CliClone#cloneRepository}. */
    public static GitResult cloneRepository(String uri, File dir) {
        return CliClone.cloneRepository(uri, dir);
    }
    /** See {@link CliClone#cloneRepository}. */
    public static GitResult cloneRepository(String uri, File dir, String branch, int depth) {
        return CliClone.cloneRepository(uri, dir, branch, depth);
    }
    /** See {@link CliClone#cloneRepository}. */
    public static GitResult cloneRepository(String uri, File dir, String branch, int depth, java.util.function.BooleanSupplier cancelled) {
        return CliClone.cloneRepository(uri, dir, branch, depth, cancelled);
    }

    // ------------------------------------------------------------------
    // CliStaging
    // ------------------------------------------------------------------

    @Override public boolean hasUncommittedChanges() { return repo.staging.hasUncommittedChanges(); }
    @Override public List<String> getStagedFiles() { return repo.staging.getStagedFiles(); }
    @Override public List<String> getConflictingFiles() { return repo.staging.getConflictingFiles(); }
    @Override public StagingState getStagingState() { return repo.staging.getStagingState(); }
    @Override public GitResult stageFile(String path) { return repo.staging.stageFile(path); }
    @Override public GitResult stageAll() { return repo.staging.stageAll(); }
    @Override public GitResult unstageFile(String path) { return repo.staging.unstageFile(path); }
    @Override public GitResult unstageAll() { return repo.staging.unstageAll(); }
    @Override public GitResult discardAllChanges() { return repo.staging.discardAllChanges(); }
    @Override public GitResult discardChanges(String path) { return repo.staging.discardChanges(path); }
    @Override public GitResult commit(String message, boolean amend) {
        return repo.staging.commit(message, amend);
    }
    @Override public GitResult commit(String message, boolean amend, boolean skipHooks) {
        return repo.staging.commit(message, amend, skipHooks);
    }
    @Override public String getHeadCommitMessage() { return repo.staging.getHeadCommitMessage(); }
    @Override public String getCommitIdentity() { return repo.staging.getCommitIdentity(); }
    @Override public String getCommitTemplate() { return repo.staging.getCommitTemplate(); }
    @Override public String getHeadHash() { return repo.staging.getHeadHash(); }

    // ------------------------------------------------------------------
    // CliHistory
    // ------------------------------------------------------------------

    @Override public List<CommitInfo> getCommitsSince(String ancestorHash) {
        return repo.history.getCommitsSince(ancestorHash);
    }
    @Override public List<CommitInfo> getCommitsInRange(String excludeRef, String includeRef, int limit) {
        return repo.history.getCommitsInRange(excludeRef, includeRef, limit);
    }
    @Override public List<CommitInfo> searchCommits(com.jfx.git.model.CommitSearch query, int limit) {
        return repo.history.searchCommits(query, limit);
    }
    @Override public List<com.jfx.git.model.GrepMatch> grep(String revOrNull, com.jfx.git.model.GrepQuery query,
                                                            int limit) {
        return repo.history.grep(revOrNull, query, limit);
    }

    // ------------------------------------------------------------------
    // CliStaging
    // ------------------------------------------------------------------

    @Override public String getLocalConfig(String key) { return repo.staging.getLocalConfig(key); }
    @Override public GitResult setLocalConfig(String key, String value) {
        return repo.staging.setLocalConfig(key, value);
    }
    @Override public GitResult setLocalIdentity(String name, String email, String signingKey, boolean gpgSign) {
        return repo.staging.setLocalIdentity(name, email, signingKey, gpgSign);
    }

    // ------------------------------------------------------------------
    // CliLfs
    // ------------------------------------------------------------------

    // Not on GitBackend: nothing in the app asks yet, so it stays this engine's own.
    java.util.Set<String> lfsTrackedPaths(java.util.Collection<String> paths) {
        return repo.lfsPart.lfsTrackedPaths(paths);
    }
    @Override public boolean canMaterializeLfsContent() { return repo.lfsPart.canMaterializeLfsContent(); }

    // ------------------------------------------------------------------
    // CliBranches
    // ------------------------------------------------------------------

    @Override public List<RefInfo> getLocalBranches() { return repo.branches.getLocalBranches(); }
    @Override public List<RefInfo> getRemoteBranches() { return repo.branches.getRemoteBranches(); }
    @Override public List<RefInfo> getTags() { return repo.branches.getTags(); }
    @Override public String getCurrentBranch() { return repo.branches.getCurrentBranch(); }
    @Override public int[] getTrackingStatus() { return repo.branches.getTrackingStatus(); }
    @Override public boolean isHeadDetached() { return repo.branches.isHeadDetached(); }
    @Override public String symbolicRefTarget(String fullName) { return repo.branches.symbolicRefTarget(fullName); }
    @Override public boolean isDetachedHeadOrphanRisk() { return repo.branches.isDetachedHeadOrphanRisk(); }
    @Override public GitResult checkoutBranch(String branchName) {
        return repo.branches.checkoutBranch(branchName);
    }
    @Override public GitResult checkoutRemoteBranch(String remoteBranch) {
        return repo.branches.checkoutRemoteBranch(remoteBranch);
    }
    @Override public GitResult checkoutPullRequest(String remoteName, int number, boolean resetToFetched) {
        return repo.branches.checkoutPullRequest(remoteName, number, resetToFetched);
    }
    @Override public GitResult checkoutRemoteRef(String remoteNameOrUrl, String remoteRef, String localBranchName, boolean resetToFetched) {
        return repo.branches.checkoutRemoteRef(remoteNameOrUrl, remoteRef, localBranchName, resetToFetched);
    }

    // ------------------------------------------------------------------
    // CliRemotes
    // ------------------------------------------------------------------

    @Override public GitResult fetchRemoteRef(String remoteNameOrUrl, String remoteRef) {
        return repo.remotes.fetchRemoteRef(remoteNameOrUrl, remoteRef);
    }

    // ------------------------------------------------------------------
    // CliHistory
    // ------------------------------------------------------------------

    @Override public String mergeBase(String commitA, String commitB) {
        return repo.history.mergeBase(commitA, commitB);
    }

    // ------------------------------------------------------------------
    // CliBranches
    // ------------------------------------------------------------------

    @Override public GitResult createBranch(String name, String startPoint, boolean checkout) {
        return repo.branches.createBranch(name, startPoint, checkout);
    }
    @Override public GitResult deleteBranch(String name, boolean force) {
        return repo.branches.deleteBranch(name, force);
    }
    @Override public GitResult renameBranch(String oldName, String newName) {
        return repo.branches.renameBranch(oldName, newName);
    }
    @Override public String getUpstream(String localBranch) { return repo.branches.getUpstream(localBranch); }
    @Override public Map<String, String> getUpstreams() { return repo.branches.getUpstreams(); }
    @Override public GitResult setUpstream(String localBranch, String upstream) {
        return repo.branches.setUpstream(localBranch, upstream);
    }
    @Override public GitResult unsetUpstream(String localBranch) {
        return repo.branches.unsetUpstream(localBranch);
    }
    @Override public Set<String> getGoneBranches() { return repo.branches.getGoneBranches(); }
    @Override public List<String> getBranchesMergedInto(String target) {
        return repo.branches.getBranchesMergedInto(target);
    }

    // ------------------------------------------------------------------
    // CliRemotes
    // ------------------------------------------------------------------

    @Override public List<String> getRemoteNames() { return repo.remotes.getRemoteNames(); }
    @Override public String getRemoteUrl(String remoteName) { return repo.remotes.getRemoteUrl(remoteName); }
    @Override public GitResult addRemote(String name, String uri) {
        return repo.remotes.addRemote(name, uri);
    }
    @Override public GitResult removeRemote(String name) { return repo.remotes.removeRemote(name); }
    @Override public String getRemotePushUrl(String remoteName) {
        return repo.remotes.getRemotePushUrl(remoteName);
    }
    @Override public GitResult renameRemote(String oldName, String newName) {
        return repo.remotes.renameRemote(oldName, newName);
    }
    @Override public GitResult setRemoteUrl(String name, String url, boolean push) {
        return repo.remotes.setRemoteUrl(name, url, push);
    }
    @Override public GitResult fetchRemote(String name) { return repo.remotes.fetchRemote(name); }
    @Override public GitResult deleteRemoteBranch(String remoteName, String branchName) {
        return repo.remotes.deleteRemoteBranch(remoteName, branchName);
    }
    @Override public GitResult fetchAll() { return repo.remotes.fetchAll(); }
    @Override public GitResult fetchAllSilently() { return repo.remotes.fetchAllSilently(); }

    // ------------------------------------------------------------------
    // CliParsers
    // ------------------------------------------------------------------

    static GitResult classifyFetch(NativeGitRunner.Result res) { return CliParsers.classifyFetch(res); }
    static GitResult classifyFetch(NativeGitRunner.Result res, String what) {
        return CliParsers.classifyFetch(res, what);
    }

    // ------------------------------------------------------------------
    // CliRemotes
    // ------------------------------------------------------------------

    @Override public GitResult pull(PullMode mode) { return repo.remotes.pull(mode); }
    @Override public PushLease pushLease(String remote) { return repo.remotes.pushLease(remote); }
    @Override public GitResult push(String remote, PushLease lease, boolean includeTags, boolean setUpstream) {
        return repo.remotes.push(remote, lease, includeTags, setUpstream);
    }

    // ------------------------------------------------------------------
    // CliIntegration
    // ------------------------------------------------------------------

    @Override public GitResult merge(String sourceBranch, MergeMode mode, String message) {
        return repo.integration.merge(sourceBranch, mode, message);
    }
    @Override public com.jfx.git.model.OperationState getOperationState() {
        return repo.integration.getOperationState();
    }
    @Override public GitResult continueOperation() { return repo.integration.continueOperation(); }
    @Override public GitResult skipOperation() { return repo.integration.skipOperation(); }
    @Override public GitResult abortOperation() { return repo.integration.abortOperation(); }
    @Override public GitResult rebase(String upstream) { return repo.integration.rebase(upstream); }
    @Override public GitResult rebaseInteractive(RebaseTodo todo) {
        return repo.integration.rebaseInteractive(todo);
    }
    String buildTodoFile(RebaseTodo todo, List<Path> temporaries) throws IOException {
        return repo.integration.buildTodoFile(todo, temporaries);
    }
    @Override public RebaseRange getRebaseRange(String startHash) {
        return repo.integration.getRebaseRange(startHash);
    }
    @Override public GitResult createPatch(List<String> commits, File out) {
        return repo.integration.createPatch(commits, out);
    }
    @Override public GitResult applyPatch(File patch, com.jfx.git.model.PatchApplyMode mode) {
        return repo.integration.applyPatch(patch, mode);
    }
    @Override public GitResult cherryPick(String commitHash) {
        return repo.integration.cherryPick(commitHash);
    }
    @Override public GitResult cherryPick(String commitHash, int mainline) {
        return repo.integration.cherryPick(commitHash, mainline);
    }
    @Override public GitResult revert(String commitHash) { return repo.integration.revert(commitHash); }
    @Override public GitResult revert(String commitHash, int mainline) {
        return repo.integration.revert(commitHash, mainline);
    }
    @Override public GitResult reset(String commitish, ResetMode mode) {
        return repo.integration.reset(commitish, mode);
    }

    // ------------------------------------------------------------------
    // CliBisect
    // ------------------------------------------------------------------

    @Override public GitResult bisectStart() { return repo.bisection.bisectStart(); }
    @Override public BisectStatus bisectMarkGood(String commitHash) {
        return repo.bisection.bisectMarkGood(commitHash);
    }
    @Override public BisectStatus bisectMarkBad(String commitHash) {
        return repo.bisection.bisectMarkBad(commitHash);
    }
    @Override public BisectStatus bisectSkip() { return repo.bisection.bisectSkip(); }
    @Override public BisectStatus bisectStatus() { return repo.bisection.bisectStatus(); }
    @Override public GitResult bisectReset() { return repo.bisection.bisectReset(); }

    // ------------------------------------------------------------------
    // CliHistory
    // ------------------------------------------------------------------

    @Override public boolean graphReaches(String hash, com.jfx.git.graph.GraphVisibility visibility) {
        return repo.history.graphReaches(hash, visibility);
    }
    @Override public com.jfx.git.model.RefSnapshot getRefSnapshot() {
        repo.ensureOpen();
        return repo.history.readRefSnapshot();
    }
    @Override public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order) {
        return repo.history.getCommitGraphPage(limit, order);
    }
    @Override public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order, com.jfx.git.graph.GraphVisibility visibility) {
        return repo.history.getCommitGraphPage(limit, order, visibility);
    }
    @Override public CommitPosition getCommitPosition(String hash) {
        return repo.history.getCommitPosition(hash);
    }
    @Override public String getCommitMessage(String commitHash) {
        return repo.history.getCommitMessage(commitHash);
    }
    @Override public com.jfx.git.model.SignatureStatus getSignatureStatus(String commitHash) {
        return repo.history.getSignatureStatus(commitHash);
    }
    @Override public List<CommitInfo> getFileHistoryCommits(String filePath, int limit) {
        return repo.history.getFileHistoryCommits(filePath, limit);
    }
    @Override public List<ReflogItem> getReflog(int limit) { return repo.history.getReflog(limit); }

    // ------------------------------------------------------------------
    // CliDiffs
    // ------------------------------------------------------------------

    @Override public byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, boolean ignoreWhitespace) {
        return repo.diffs.getDiffBytesForFileInCommit(commitHash, filePath, ignoreWhitespace);
    }
    @Override public byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, String oldPath, boolean ignoreWhitespace) {
        return repo.diffs.getDiffBytesForFileInCommit(commitHash, filePath, oldPath, ignoreWhitespace);
    }
    @Override public byte[] getDiffBytesForFileInCommit(CommitInfo commit, String filePath, boolean ignoreWhitespace) {
        return repo.diffs.getDiffBytesForFileInCommit(commit, filePath, ignoreWhitespace);
    }
    @Override public byte[] getUnstagedDiffBytes(String filePath, boolean ignoreWhitespace) {
        return repo.diffs.getUnstagedDiffBytes(filePath, ignoreWhitespace);
    }
    @Override public byte[] getStagedDiffBytes(String filePath, boolean ignoreWhitespace) {
        return repo.diffs.getStagedDiffBytes(filePath, ignoreWhitespace);
    }

    // ------------------------------------------------------------------
    // CliBranches
    // ------------------------------------------------------------------

    @Override public Map<String, int[]> getBranchTracking() { return repo.branches.getBranchTracking(); }

    // ------------------------------------------------------------------
    // CliDiffs
    // ------------------------------------------------------------------

    @Override public List<String> getFilesInCommit(String commitHash) {
        return repo.diffs.getFilesInCommit(commitHash);
    }
    @Override public List<String> getTopLevelDirectoriesAtCommit(String commitRef) {
        return repo.diffs.getTopLevelDirectoriesAtCommit(commitRef);
    }
    @Override public List<String> getFilesInCommit(CommitInfo commit) {
        return repo.diffs.getFilesInCommit(commit);
    }
    @Override public List<FileChange> getChangesInCommit(String commitHash) {
        return repo.diffs.getChangesInCommit(commitHash);
    }
    @Override public List<FileChange> getChangesBetweenCommits(String fromHash, String toHash) {
        return repo.diffs.getChangesBetweenCommits(fromHash, toHash);
    }
    @Override public Map<String, int[]> getWorkingTreeLineCounts(boolean staged, List<String> paths) {
        return repo.diffs.getWorkingTreeLineCounts(staged, paths);
    }
    @Override public List<String> getFilesBetweenCommits(String fromHash, String toHash) {
        return repo.diffs.getFilesBetweenCommits(fromHash, toHash);
    }
    @Override public byte[] getDiffBytesBetweenCommits(String fromHash, String toHash, String filePath, boolean ignoreWhitespace) {
        return repo.diffs.getDiffBytesBetweenCommits(fromHash, toHash, filePath, ignoreWhitespace);
    }
    @Override public byte[] getDiffBytesBetweenCommits(String fromHash, String toHash, String filePath, String oldPath, boolean ignoreWhitespace) {
        return repo.diffs.getDiffBytesBetweenCommits(fromHash, toHash, filePath, oldPath, ignoreWhitespace);
    }
    @Override public String getFileContentAtCommit(String commitHash, String filePath) {
        return repo.diffs.getFileContentAtCommit(commitHash, filePath);
    }
    @Override public String getFileContentAtCommit(CommitInfo commit, String filePath) {
        return repo.diffs.getFileContentAtCommit(commit, filePath);
    }
    @Override public byte[] getFileBytesAtCommit(String commitHash, String filePath) {
        return repo.diffs.getFileBytesAtCommit(commitHash, filePath);
    }
    @Override public byte[] getFileBytesAtCommit(CommitInfo commit, String filePath) {
        return repo.diffs.getFileBytesAtCommit(commit, filePath);
    }
    @Override public GitResult restoreFileFromCommit(String commitHash, String filePath) {
        return repo.diffs.restoreFileFromCommit(commitHash, filePath);
    }
    @Override public GitResult restoreFileFromCommit(String commitHash, String filePath, String intoPath) {
        return repo.diffs.restoreFileFromCommit(commitHash, filePath, intoPath);
    }
    @Override public byte[][] getCommitSides(CommitInfo commit, String path) {
        return repo.diffs.getCommitSides(commit, path);
    }
    @Override public byte[][] getWorkingTreeSides(String path) {
        return repo.diffs.getWorkingTreeSides(path);
    }
    @Override public String getConflictStage(String path, int stage) {
        return repo.diffs.getConflictStage(path, stage);
    }
    @Override public byte[] getConflictStageBytes(String path, int stage) {
        return repo.diffs.getConflictStageBytes(path, stage);
    }
    @Override public GitResult resolveConflict(String path, String content) {
        return repo.diffs.resolveConflict(path, content);
    }
    @Override public String getWorkingTreeContent(String path) {
        return repo.diffs.getWorkingTreeContent(path);
    }
    @Override public byte[] getWorkingTreeBytes(String path) { return repo.diffs.getWorkingTreeBytes(path); }
    @Override public GitResult writeWorkingTreeFile(String path, byte[] bytes) {
        return repo.diffs.writeWorkingTreeFile(path, bytes);
    }

    // ------------------------------------------------------------------
    // CliStaging
    // ------------------------------------------------------------------

    @Override public GitResult revertHunk(String path, HunkEdit edit) {
        return repo.staging.revertHunk(path, edit);
    }
    @Override public GitResult applyToIndex(String path, HunkEdit edit, String action) {
        return repo.staging.applyToIndex(path, edit, action);
    }

    // ------------------------------------------------------------------
    // CliDiffs
    // ------------------------------------------------------------------

    @Override public byte[] getStagedBytes(String path) { return repo.diffs.getStagedBytes(path); }

    // ------------------------------------------------------------------
    // CliHistory
    // ------------------------------------------------------------------

    @Override public List<BlameLine> getBlameLines(String filePath, String startCommit) {
        return repo.history.getBlameLines(filePath, startCommit);
    }

    // ------------------------------------------------------------------
    // CliParsers
    // ------------------------------------------------------------------

    static List<BlameLine> parseBlamePorcelain(String stdout) {
        return CliParsers.parseBlamePorcelain(stdout);
    }

    // ------------------------------------------------------------------
    // CliStash
    // ------------------------------------------------------------------

    @Override public List<com.jfx.git.model.StashEntry> getStashEntries() {
        return repo.stashes.getStashEntries();
    }
    @Override public GitResult stash(StashOptions options) { return repo.stashes.stash(options); }
    @Override public GitResult stashPop(int index) { return repo.stashes.stashPop(index); }
    @Override public GitResult stashApply(int index) { return repo.stashes.stashApply(index); }
    @Override public GitResult stashDrop(int index) { return repo.stashes.stashDrop(index); }
    @Override public GitResult stashBranch(String branchName, int index) {
        return repo.stashes.stashBranch(branchName, index);
    }

    // ------------------------------------------------------------------
    // CliSnapshots
    // ------------------------------------------------------------------

    @Override public String snapshotWorkingTree(String reason) { return repo.snapshots.snapshotWorkingTree(reason); }
    @Override public List<com.jfx.git.model.Snapshot> listSnapshots() { return repo.snapshots.listSnapshots(); }
    @Override public GitResult deleteSnapshot(String id) { return repo.snapshots.deleteSnapshot(id); }
    @Override public GitResult restoreFromSnapshot(String id, List<String> paths) {
        return repo.snapshots.restoreFromSnapshot(id, paths);
    }

    // ------------------------------------------------------------------
    // CliHistory: conflict prediction
    // ------------------------------------------------------------------

    @Override public com.jfx.git.model.ConflictPrediction predictMergeConflicts(String ours, String theirs) {
        return repo.history.predictMergeConflicts(ours, theirs);
    }

    // ------------------------------------------------------------------
    // CliBranches
    // ------------------------------------------------------------------

    @Override public GitResult createTag(String name, String message, String commitish) {
        return repo.branches.createTag(name, message, commitish);
    }
    @Override public GitResult deleteTag(String name) { return repo.branches.deleteTag(name); }
    @Override public String getRawTagObjectId(String tagName) {
        return repo.branches.getRawTagObjectId(tagName);
    }
    @Override public GitResult restoreTagRef(String tagName, String objectId) {
        return repo.branches.restoreTagRef(tagName, objectId);
    }

    // ------------------------------------------------------------------
    // CliRemotes
    // ------------------------------------------------------------------

    @Override public GitResult deleteRemoteTag(String remoteName, String tagName) {
        return repo.remotes.deleteRemoteTag(remoteName, tagName);
    }
    @Override public GitResult pushTag(String remoteName, String tagName) {
        return repo.remotes.pushTag(remoteName, tagName);
    }

    // ------------------------------------------------------------------
    // CliBranches
    // ------------------------------------------------------------------

    @Override public boolean isTagMoveFastForward(String tagName, String newCommitish) {
        return repo.branches.isTagMoveFastForward(tagName, newCommitish);
    }
    @Override public GitResult moveTag(String tagName, String newCommitish) {
        return repo.branches.moveTag(tagName, newCommitish);
    }

    // ------------------------------------------------------------------
    // CliStructure
    // ------------------------------------------------------------------

    @Override public boolean isSparseCheckoutEnabled() { return repo.structure.isSparseCheckoutEnabled(); }
    @Override public List<String> getSparseCheckoutPaths() { return repo.structure.getSparseCheckoutPaths(); }
    @Override public GitResult setSparseCheckoutPaths(List<String> paths) {
        return repo.structure.setSparseCheckoutPaths(paths);
    }
    @Override public GitResult disableSparseCheckout() { return repo.structure.disableSparseCheckout(); }
    @Override public List<Submodule> getSubmodules() { return repo.structure.getSubmodules(); }
    CliStructure.SubmoduleStatusLine parseSubmoduleStatusLine(String line) {
        return repo.structure.parseSubmoduleStatusLine(line);
    }
    @Override public GitResult updateSubmodules(List<String> paths) {
        return repo.structure.updateSubmodules(paths);
    }
    @Override public GitResult addSubmodule(String url, String path) {
        return repo.structure.addSubmodule(url, path);
    }
    @Override public GitResult syncSubmodules() { return repo.structure.syncSubmodules(); }
    @Override public GitResult deinitSubmodule(String path, boolean force) {
        return repo.structure.deinitSubmodule(path, force);
    }
    @Override public File submoduleWorkTree(String path) { return repo.structure.submoduleWorkTree(path); }
    @Override public List<Worktrees.Worktree> getWorktrees() { return repo.structure.getWorktrees(); }
    @Override public GitResult addWorktree(File path, String branch, boolean createBranch) {
        return repo.structure.addWorktree(path, branch, createBranch);
    }
    @Override public GitResult removeWorktree(File path, boolean force) {
        return repo.structure.removeWorktree(path, force);
    }
    @Override public GitResult pruneWorktrees() { return repo.structure.pruneWorktrees(); }
    @Override public GitResult setWorktreeLocked(File path, boolean locked) {
        return repo.structure.setWorktreeLocked(path, locked);
    }
    @Override public GitResult runMaintenance() { return repo.structure.runMaintenance(); }
    @Override public boolean needsMaintenance() { return repo.structure.needsMaintenance(); }
    @Override public List<Hook> getHooks() { return repo.structure.getHooks(); }
    static List<Hook> readHooks(File dir) { return CliStructure.readHooks(dir); }

    // ------------------------------------------------------------------
    // CliLfs
    // ------------------------------------------------------------------

    @Override public List<String> lfsTrackedPatterns() { return repo.lfsPart.lfsTrackedPatterns(); }
    @Override public GitResult lfsInstall() { return repo.lfsPart.lfsInstall(); }
    @Override public GitResult lfsTrack(String pattern) { return repo.lfsPart.lfsTrack(pattern); }
    @Override public GitResult lfsUntrack(String pattern) { return repo.lfsPart.lfsUntrack(pattern); }
    @Override public GitResult lfsFetch() { return repo.lfsPart.lfsFetch(); }
    @Override public GitResult lfsPull() { return repo.lfsPart.lfsPull(); }
    @Override public GitResult lfsPrune() { return repo.lfsPart.lfsPrune(); }
    @Override public List<com.jfx.git.model.LfsLock> lfsLocks() { return repo.lfsPart.lfsLocks(); }
    @Override public GitResult lfsLock(String path) { return repo.lfsPart.lfsLock(path); }
    @Override public GitResult lfsUnlock(String path, boolean force) {
        return repo.lfsPart.lfsUnlock(path, force);
    }
}
