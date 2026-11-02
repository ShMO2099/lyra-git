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
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.ProcessResult;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * JGit facade for a single open repository.
 *
 * <p>Mutating operations return a {@link GitResult} rather than a bare {@code boolean}
 * so that "succeeded", "hit a conflict" and "failed" stay distinguishable - previously
 * a conflicting merge reported success and the UI simply refreshed.
 *
 * <p>Read queries return empty values on failure but log the cause; they never throw
 * into a UI event handler.
 *
 * <p>The work itself is done by package-private parts, one per capability - see
 * {@link JGitPart}. This class keeps the public surface: every method other code and
 * tests call, the statics they reach as {@code GitService.X}, and the two test seams.
 */
public class GitService implements GitBackend {

    /** Commits fetched per graph page; the previous code loaded the entire history at once. */
    public static final int DEFAULT_GRAPH_LIMIT = 1000;

    /**
     * A hook name no repository can define, used only to ask the platform whether it runs
     * hooks at all. Nothing is executed: the answer is NOT_PRESENT or NOT_SUPPORTED.
     */
    private static final String HOOK_CAPABILITY_PROBE = "lyra-hook-capability-probe";

    /**
     * Whether JGit can run hooks for {@code repository} on this platform - the probe
     * above. Shared by the commit and pre-push guards, and a seam for tests: the
     * platform that answers "no" (Windows with no {@code sh}) is not one a test machine
     * with Git for Windows can reproduce, and without it neither guard's refusal could
     * be exercised at all.
     */
    static volatile java.util.function.Predicate<Repository> platformRunsHooks = repository -> {
        FS fs = repository.getFS();
        ProcessResult probe = fs.runHookIfPresent(repository, HOOK_CAPABILITY_PROBE, new String[0]);
        return probe.getStatus() != ProcessResult.Status.NOT_SUPPORTED;
    };

    /**
     * Tells {@link CredentialsManager} whether the credentials it handed out during the
     * last network call worked, so a login the user typed can be passed on to their git
     * credential helper only once it has been proven - the way git itself approves.
     *
     * <p>A seam, as {@link #platformRunsHooks} is: a test swaps it to see that every
     * network call reports, which no real credential flow can show without a dialog.
     */
    static volatile java.util.function.Consumer<Boolean> networkOutcome =
            worked -> CredentialsManager.getInstance().networkOperationFinished(worked);

    /** Every capability, and the open repository they share. */
    private final JGitRepo ctx = new JGitRepo();

    public boolean openRepository(File dir) {
        return ctx.openRepository(dir);
    }

    public boolean openAlreadyVerifiedRepository(File workTree) {
        return ctx.openAlreadyVerifiedRepository(workTree);
    }

    public static GitResult initRepository(File dir) {
        return JGitRepo.initRepository(dir);
    }

    public static GitResult initRepository(File dir, RepoInitTemplates templates) {
        return JGitRepo.initRepository(dir, templates);
    }

    public static GitResult cloneRepository(String uri, File dir) {
        return JGitRepo.cloneRepository(uri, dir);
    }

    public static GitResult cloneRepository(String uri, File dir, String branch, int depth) {
        return JGitRepo.cloneRepository(uri, dir, branch, depth);
    }

    public static GitResult cloneRepository(String uri, File dir, String branch, int depth,
                                            java.util.function.BooleanSupplier cancelled) {
        return JGitRepo.cloneRepository(uri, dir, branch, depth, cancelled);
    }

    static String cancelledCloneMessage(File dir) {
        return JGitRepo.cancelledCloneMessage(dir);
    }

    public boolean isOpen() {
        return ctx.isOpen();
    }

    public File getDirectory() {
        return ctx.getDirectory();
    }

    public File getWorkTree() {
        return ctx.getWorkTree();
    }

    public void close() {
        ctx.close();
    }

    public List<RefInfo> getLocalBranches() {
        return ctx.branches.getLocalBranches();
    }

    public List<RefInfo> getRemoteBranches() {
        return ctx.branches.getRemoteBranches();
    }

    public String getCurrentBranch() {
        return ctx.branches.getCurrentBranch();
    }

    @Override
    public java.util.Map<String, int[]> getBranchTracking() {
        return ctx.branches.getBranchTracking();
    }

    @Override
    public int[] getTrackingStatus() {
        return ctx.branches.getTrackingStatus();
    }

    @Override
    public boolean isHeadDetached() {
        return ctx.branches.isHeadDetached();
    }

    @Override
    public String symbolicRefTarget(String fullName) {
        return ctx.branches.symbolicRefTarget(fullName);
    }

    @Override
    public boolean isDetachedHeadOrphanRisk() {
        return ctx.branches.isDetachedHeadOrphanRisk();
    }

    public GitResult checkoutBranch(String branchName) {
        return ctx.branches.checkoutBranch(branchName);
    }

    public GitResult checkoutRemoteBranch(String remoteBranch) {
        return ctx.branches.checkoutRemoteBranch(remoteBranch);
    }

    @Override
    public GitResult checkoutPullRequest(String remoteName, int number, boolean resetToFetched) {
        return ctx.remotes.checkoutPullRequest(remoteName, number, resetToFetched);
    }

    @Override
    public GitResult checkoutRemoteRef(String remoteNameOrUrl, String remoteRef, String localBranchName,
                                       boolean resetToFetched) {
        return ctx.remotes.checkoutRemoteRef(remoteNameOrUrl, remoteRef, localBranchName, resetToFetched);
    }

    @Override
    public GitResult fetchRemoteRef(String remoteNameOrUrl, String remoteRef) {
        return ctx.remotes.fetchRemoteRef(remoteNameOrUrl, remoteRef);
    }

    @Override
    public String mergeBase(String commitA, String commitB) {
        return ctx.history.mergeBase(commitA, commitB);
    }

    public GitResult createBranch(String name, String startPoint, boolean checkout) {
        return ctx.branches.createBranch(name, startPoint, checkout);
    }

    public GitResult deleteBranch(String name, boolean force) {
        return ctx.branches.deleteBranch(name, force);
    }

    public GitResult renameBranch(String oldName, String newName) {
        return ctx.branches.renameBranch(oldName, newName);
    }

    @Override
    public String getUpstream(String localBranch) {
        return ctx.branches.getUpstream(localBranch);
    }

    @Override
    public Map<String, String> getUpstreams() {
        return ctx.branches.getUpstreams();
    }

    @Override
    public GitResult setUpstream(String localBranch, String upstream) {
        return ctx.branches.setUpstream(localBranch, upstream);
    }

    @Override
    public java.util.Set<String> getGoneBranches() {
        return ctx.branches.getGoneBranches();
    }

    @Override
    public List<String> getBranchesMergedInto(String target) {
        return ctx.branches.getBranchesMergedInto(target);
    }

    @Override
    public GitResult unsetUpstream(String localBranch) {
        return ctx.branches.unsetUpstream(localBranch);
    }

    public boolean hasUncommittedChanges() {
        return ctx.staging.hasUncommittedChanges();
    }

    public List<String> getStagedFiles() {
        return ctx.staging.getStagedFiles();
    }

    public List<String> getConflictingFiles() {
        return ctx.staging.getConflictingFiles();
    }

    public StagingState getStagingState() {
        return ctx.staging.getStagingState();
    }

    public GitResult stageFile(String path) {
        return ctx.staging.stageFile(path);
    }

    public GitResult stageAll() {
        return ctx.staging.stageAll();
    }

    public GitResult unstageFile(String path) {
        return ctx.staging.unstageFile(path);
    }

    public GitResult unstageAll() {
        return ctx.staging.unstageAll();
    }

    public GitResult discardChanges(String path) {
        return ctx.staging.discardChanges(path);
    }

    public GitResult restoreFileFromCommit(String commitHash, String filePath) {
        return ctx.staging.restoreFileFromCommit(commitHash, filePath);
    }

    public GitResult restoreFileFromCommit(String commitHash, String filePath, String intoPath) {
        return ctx.staging.restoreFileFromCommit(commitHash, filePath, intoPath);
    }

    public GitResult discardAllChanges() {
        return ctx.staging.discardAllChanges();
    }

    @Override
    public GitResult commit(String message, boolean amend) {
        return ctx.staging.commit(message, amend);
    }

    @Override
    public GitResult commit(String message, boolean amend, boolean skipHooks) {
        return ctx.staging.commit(message, amend, skipHooks);
    }

    static PersonIdent parseAuthorScript(String script) {
        return JGitStaging.parseAuthorScript(script);
    }

    public String getHeadCommitMessage() {
        return ctx.history.getHeadCommitMessage();
    }

    @Override
    public String getLocalConfig(String key) {
        return ctx.structure.getLocalConfig(key);
    }

    @Override
    public GitResult setLocalConfig(String key, String value) {
        return ctx.structure.setLocalConfig(key, value);
    }

    @Override
    public GitResult setLocalIdentity(String name, String email, String signingKey, boolean gpgSign) {
        return ctx.structure.setLocalIdentity(name, email, signingKey, gpgSign);
    }

    /**
     * Which of {@code paths} are declared {@code filter=lfs} - see {@link JGitStructure#lfsTrackedPaths}.
     * Not on {@link GitBackend}: nothing in the app asks yet, so it stays this engine's own.
     */
    java.util.Set<String> lfsTrackedPaths(java.util.Collection<String> paths) {
        return ctx.structure.lfsTrackedPaths(paths);
    }

    public List<String> getRemoteNames() {
        return ctx.remotes.getRemoteNames();
    }

    @Override
    public String getRemoteUrl(String remoteName) {
        return ctx.remotes.getRemoteUrl(remoteName);
    }

    public GitResult addRemote(String name, String uri) {
        return ctx.remotes.addRemote(name, uri);
    }

    public GitResult removeRemote(String name) {
        return ctx.remotes.removeRemote(name);
    }

    @Override
    public String getRemotePushUrl(String remoteName) {
        return ctx.remotes.getRemotePushUrl(remoteName);
    }

    @Override
    public GitResult renameRemote(String oldName, String newName) {
        return ctx.remotes.renameRemote(oldName, newName);
    }

    @Override
    public GitResult setRemoteUrl(String name, String url, boolean push) {
        return ctx.remotes.setRemoteUrl(name, url, push);
    }

    @Override
    public GitResult fetchRemote(String name) {
        return ctx.remotes.fetchRemote(name);
    }

    @Override
    public GitResult deleteRemoteBranch(String remoteName, String branchName) {
        return ctx.remotes.deleteRemoteBranch(remoteName, branchName);
    }

    static final int NETWORK_IDLE_TIMEOUT_SECONDS = JGitRepo.NETWORK_IDLE_TIMEOUT_SECONDS;

    public GitResult fetchAll() {
        return ctx.remotes.fetchAll();
    }

    @Override
    public GitResult fetchAllSilently() {
        return ctx.remotes.fetchAllSilently();
    }

    public GitResult pull(PullMode mode) {
        return ctx.remotes.pull(mode);
    }

    @Override
    public PushLease pushLease(String remote) {
        return ctx.remotes.pushLease(remote);
    }

    @Override
    public GitResult push(String remote, PushLease lease, boolean includeTags, boolean setUpstream) {
        return ctx.remotes.push(remote, lease, includeTags, setUpstream);
    }

    @Override
    public GitResult merge(String sourceBranch, MergeMode mode, String message) {
        return ctx.integration.merge(sourceBranch, mode, message);
    }

    static String squashedMessage(String sourceBranch) {
        return JGitIntegration.squashedMessage(sourceBranch);
    }

    static String alreadySquashedMessage(String sourceBranch) {
        return JGitIntegration.alreadySquashedMessage(sourceBranch);
    }

    public GitResult rebase(String upstream) {
        return ctx.rebases.rebase(upstream);
    }

    public CommitPosition getCommitPosition(String hash) {
        return ctx.history.getCommitPosition(hash);
    }

    @Override
    public String getCommitMessage(String commitHash) {
        return ctx.history.getCommitMessage(commitHash);
    }

    @Override
    public com.jfx.git.model.SignatureStatus getSignatureStatus(String commitHash) {
        return ctx.history.getSignatureStatus(commitHash);
    }

    @Override
    public GitResult bisectStart() {
        return ctx.bisection.bisectStart();
    }

    @Override
    public BisectStatus bisectMarkGood(String commitHash) {
        return ctx.bisection.bisectMarkGood(commitHash);
    }

    @Override
    public BisectStatus bisectMarkBad(String commitHash) {
        return ctx.bisection.bisectMarkBad(commitHash);
    }

    @Override
    public BisectStatus bisectSkip() {
        return ctx.bisection.bisectSkip();
    }

    @Override
    public BisectStatus bisectStatus() {
        return ctx.bisection.bisectStatus();
    }

    @Override
    public GitResult bisectReset() {
        return ctx.bisection.bisectReset();
    }

    public RebaseRange getRebaseRange(String startHash) {
        return ctx.rebases.getRebaseRange(startHash);
    }

    @Override
    public List<CommitInfo> getCommitsInRange(String excludeRef, String includeRef, int limit) {
        return ctx.history.getCommitsInRange(excludeRef, includeRef, limit);
    }

    @Override
    public List<CommitInfo> searchCommits(com.jfx.git.model.CommitSearch query, int limit) {
        return ctx.history.searchCommits(query, limit);
    }

    @Override
    public List<com.jfx.git.model.GrepMatch> grep(String revOrNull, com.jfx.git.model.GrepQuery query, int limit) {
        return ctx.history.grep(revOrNull, query, limit);
    }

    @Override
    public List<CommitInfo> getCommitsSince(String ancestorHash) {
        return ctx.history.getCommitsSince(ancestorHash);
    }

    static List<CommitInfo> oldestFirst(List<CommitInfo> newestFirst, String headHash) {
        return JGitHistory.oldestFirst(newestFirst, headHash);
    }

    public GitResult rebaseInteractive(RebaseTodo todo) {
        return ctx.rebases.rebaseInteractive(todo);
    }

    public List<ReflogItem> getReflog(int limit) {
        return ctx.history.getReflog(limit);
    }

    @Override
    public GitResult createPatch(List<String> commits, File out) {
        return ctx.integration.createPatch(commits, out);
    }

    @Override
    public GitResult applyPatch(File patch, com.jfx.git.model.PatchApplyMode mode) {
        return ctx.integration.applyPatch(patch, mode);
    }

    public GitResult cherryPick(String commitHash) {
        return ctx.integration.cherryPick(commitHash);
    }

    @Override
    public GitResult cherryPick(String commitHash, int mainline) {
        return ctx.integration.cherryPick(commitHash, mainline);
    }

    public GitResult revert(String commitHash) {
        return ctx.integration.revert(commitHash);
    }

    @Override
    public GitResult revert(String commitHash, int mainline) {
        return ctx.integration.revert(commitHash, mainline);
    }

    public GitResult reset(String commitish, ResetMode mode) {
        return ctx.integration.reset(commitish, mode);
    }

    @Override
    public com.jfx.git.model.OperationState getOperationState() {
        return ctx.integration.getOperationState();
    }

    @Override
    public GitResult continueOperation() {
        return ctx.integration.continueOperation();
    }

    @Override
    public GitResult skipOperation() {
        return ctx.integration.skipOperation();
    }

    @Override
    public GitResult abortOperation() {
        return ctx.integration.abortOperation();
    }

    @Override
    public List<com.jfx.git.model.StashEntry> getStashEntries() {
        return ctx.stashes.getStashEntries();
    }

    @Override
    public GitResult stash(StashOptions options) {
        return ctx.stashes.stash(options);
    }

    static final String NOTHING_TO_STASH = JGitStash.NOTHING_TO_STASH;

    static String stashedMessage(StashOptions o) {
        return JGitStash.stashedMessage(o);
    }

    public GitResult stashPop(int index) {
        return ctx.stashes.stashPop(index);
    }

    public GitResult stashApply(int index) {
        return ctx.stashes.stashApply(index);
    }

    public GitResult stashDrop(int index) {
        return ctx.stashes.stashDrop(index);
    }

    @Override
    public GitResult stashBranch(String branchName, int index) {
        return ctx.stashes.stashBranch(branchName, index);
    }

    @Override
    public String snapshotWorkingTree(String reason) {
        return ctx.snapshots.snapshotWorkingTree(reason);
    }

    @Override
    public List<com.jfx.git.model.Snapshot> listSnapshots() {
        return ctx.snapshots.listSnapshots();
    }

    @Override
    public GitResult deleteSnapshot(String id) {
        return ctx.snapshots.deleteSnapshot(id);
    }

    @Override
    public GitResult restoreFromSnapshot(String id, List<String> paths) {
        return ctx.snapshots.restoreFromSnapshot(id, paths);
    }

    @Override
    public com.jfx.git.model.ConflictPrediction predictMergeConflicts(String ours, String theirs) {
        return ctx.history.predictMergeConflicts(ours, theirs);
    }

    public List<RefInfo> getTags() {
        return ctx.branches.getTags();
    }

    @Override
    public String getRawTagObjectId(String tagName) {
        return ctx.branches.getRawTagObjectId(tagName);
    }

    public GitResult createTag(String name, String message, String commitish) {
        return ctx.branches.createTag(name, message, commitish);
    }

    public GitResult deleteTag(String name) {
        return ctx.branches.deleteTag(name);
    }

    @Override
    public GitResult restoreTagRef(String tagName, String objectId) {
        return ctx.branches.restoreTagRef(tagName, objectId);
    }

    @Override
    public GitResult deleteRemoteTag(String remoteName, String tagName) {
        return ctx.remotes.deleteRemoteTag(remoteName, tagName);
    }

    @Override
    public GitResult pushTag(String remoteName, String tagName) {
        return ctx.remotes.pushTag(remoteName, tagName);
    }

    @Override
    public boolean isTagMoveFastForward(String tagName, String newCommitish) {
        return ctx.branches.isTagMoveFastForward(tagName, newCommitish);
    }

    @Override
    public GitResult moveTag(String tagName, String newCommitish) {
        return ctx.branches.moveTag(tagName, newCommitish);
    }

    public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order) {
        return ctx.history.getCommitGraphPage(limit, order);
    }

    @Override
    public GraphPage getCommitGraphPage(int limit, com.jfx.git.graph.GraphOrder order,
                                         com.jfx.git.graph.GraphVisibility visibility) {
        return ctx.history.getCommitGraphPage(limit, order, visibility);
    }

    @Override
    public boolean graphReaches(String hash, com.jfx.git.graph.GraphVisibility visibility) {
        return ctx.history.graphReaches(hash, visibility);
    }

    @Override
    public com.jfx.git.model.RefSnapshot getRefSnapshot() {
        // The page's own read; none for no repository, as the page has no rows then.
        return ctx.repository == null ? com.jfx.git.model.RefSnapshot.EMPTY : ctx.history.readRefSnapshot();
    }

    @Override
    public String getHeadHash() {
        return ctx.history.getHeadHash();
    }

    public List<CommitInfo> getFileHistoryCommits(String filePath, int limit) {
        return ctx.history.getFileHistoryCommits(filePath, limit);
    }

    public String getFileContentAtCommit(String commitHash, String filePath) {
        return ctx.diffs.getFileContentAtCommit(commitHash, filePath);
    }

    public String getFileContentAtCommit(CommitInfo commit, String filePath) {
        return ctx.diffs.getFileContentAtCommit(commit, filePath);
    }

    public byte[] getFileBytesAtCommit(String commitHash, String filePath) {
        return ctx.diffs.getFileBytesAtCommit(commitHash, filePath);
    }

    public byte[] getFileBytesAtCommit(CommitInfo commit, String filePath) {
        return ctx.diffs.getFileBytesAtCommit(commit, filePath);
    }

    public List<BlameLine> getBlameLines(String filePath, String startCommit) {
        return ctx.history.getBlameLines(filePath, startCommit);
    }

    public String getBlame(String filePath) {
        return ctx.history.getBlame(filePath);
    }

    @Override
    public List<HistoryOps.FileChange> getChangesInCommit(String commitHash) {
        return ctx.diffs.getChangesInCommit(commitHash);
    }

    @Override
    public List<HistoryOps.FileChange> getChangesBetweenCommits(String fromHash, String toHash) {
        return ctx.diffs.getChangesBetweenCommits(fromHash, toHash);
    }

    public List<String> getFilesInCommit(String commitHash) {
        return ctx.diffs.getFilesInCommit(commitHash);
    }

    @Override
    public List<String> getTopLevelDirectoriesAtCommit(String commitRef) {
        return ctx.diffs.getTopLevelDirectoriesAtCommit(commitRef);
    }

    public List<String> getFilesInCommit(CommitInfo commit) {
        return ctx.diffs.getFilesInCommit(commit);
    }

    @Override
    public List<String> getFilesBetweenCommits(String fromHash, String toHash) {
        return ctx.diffs.getFilesBetweenCommits(fromHash, toHash);
    }

    @Override
    public byte[] getDiffBytesBetweenCommits(String fromHash, String toHash,
                                             String filePath, boolean ignoreWhitespace) {
        return ctx.diffs.getDiffBytesBetweenCommits(fromHash, toHash, filePath, ignoreWhitespace);
    }

    @Override
    public byte[] getDiffBytesBetweenCommits(String fromHash, String toHash, String filePath,
                                             String oldPath, boolean ignoreWhitespace) {
        return ctx.diffs.getDiffBytesBetweenCommits(fromHash, toHash, filePath, oldPath, ignoreWhitespace);
    }

    public byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, boolean ignoreWhitespace) {
        return ctx.diffs.getDiffBytesForFileInCommit(commitHash, filePath, ignoreWhitespace);
    }

    @Override
    public byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, String oldPath,
                                              boolean ignoreWhitespace) {
        return ctx.diffs.getDiffBytesForFileInCommit(commitHash, filePath, oldPath, ignoreWhitespace);
    }

    public byte[] getDiffBytesForFileInCommit(CommitInfo commit, String filePath, boolean ignoreWhitespace) {
        return ctx.diffs.getDiffBytesForFileInCommit(commit, filePath, ignoreWhitespace);
    }

    public byte[] getUnstagedDiffBytes(String filePath, boolean ignoreWhitespace) {
        return ctx.diffs.getUnstagedDiffBytes(filePath, ignoreWhitespace);
    }

    public byte[] getStagedDiffBytes(String filePath, boolean ignoreWhitespace) {
        return ctx.diffs.getStagedDiffBytes(filePath, ignoreWhitespace);
    }

    @Override
    public java.util.Map<String, int[]> getWorkingTreeLineCounts(boolean staged,
                                                                 List<String> paths) {
        return ctx.diffs.getWorkingTreeLineCounts(staged, paths);
    }

    public String getConflictStage(String path, int stage) {
        return ctx.diffs.getConflictStage(path, stage);
    }

    @Override
    public byte[] getConflictStageBytes(String path, int stage) {
        return ctx.diffs.getConflictStageBytes(path, stage);
    }

    public GitResult resolveConflict(String path, String content) {
        return ctx.staging.resolveConflict(path, content);
    }

    @Override
    public GitResult revertHunk(String path, HunkEdit edit) {
        return ctx.staging.revertHunk(path, edit);
    }

    @Override
    public GitResult applyToIndex(String path, HunkEdit edit, String action) {
        return ctx.staging.applyToIndex(path, edit, action);
    }

    @Override
    public byte[] getStagedBytes(String path) {
        return ctx.staging.getStagedBytes(path);
    }

    static String unmergedRefusal(String path) {
        return JGitStaging.unmergedRefusal(path);
    }

    public String getWorkingTreeContent(String path) {
        return ctx.diffs.getWorkingTreeContent(path);
    }

    public byte[][] getWorkingTreeSides(String path) {
        return ctx.diffs.getWorkingTreeSides(path);
    }

    public byte[][] getCommitSides(CommitInfo commit, String path) {
        return ctx.diffs.getCommitSides(commit, path);
    }

    public GitResult writeWorkingTreeFile(String path, byte[] bytes) {
        return ctx.diffs.writeWorkingTreeFile(path, bytes);
    }

    public byte[] getWorkingTreeBytes(String path) {
        return ctx.diffs.getWorkingTreeBytes(path);
    }

    public List<Submodule> getSubmodules() {
        return ctx.structure.getSubmodules();
    }

    public GitResult updateSubmodules(List<String> paths) {
        return ctx.structure.updateSubmodules(paths);
    }

    public GitResult addSubmodule(String url, String path) {
        return ctx.structure.addSubmodule(url, path);
    }

    public GitResult syncSubmodules() {
        return ctx.structure.syncSubmodules();
    }

    public GitResult deinitSubmodule(String path, boolean force) {
        return ctx.structure.deinitSubmodule(path, force);
    }

    public File submoduleWorkTree(String path) {
        return ctx.structure.submoduleWorkTree(path);
    }

    public List<Worktrees.Worktree> getWorktrees() {
        return ctx.structure.getWorktrees();
    }

    public GitResult addWorktree(File path, String branch, boolean createBranch) {
        return ctx.structure.addWorktree(path, branch, createBranch);
    }

    public GitResult removeWorktree(File path, boolean force) {
        return ctx.structure.removeWorktree(path, force);
    }

    public GitResult pruneWorktrees() {
        return ctx.structure.pruneWorktrees();
    }

    public GitResult setWorktreeLocked(File path, boolean locked) {
        return ctx.structure.setWorktreeLocked(path, locked);
    }

    @Override
    public GitResult runMaintenance() {
        return ctx.structure.runMaintenance();
    }

    @Override
    public boolean needsMaintenance() {
        return ctx.structure.needsMaintenance();
    }

    @Override
    public List<RepoStructureOps.Hook> getHooks() {
        return ctx.structure.getHooks();
    }

    File hooksDirectory() {
        return ctx.hooksDirectory();
    }

    @Override
    public boolean isSparseCheckoutEnabled() {
        return ctx.structure.isSparseCheckoutEnabled();
    }

    @Override
    public List<String> getSparseCheckoutPaths() {
        return ctx.structure.getSparseCheckoutPaths();
    }

    static List<String> coneDirectories(List<String> patterns) {
        return JGitStructure.coneDirectories(patterns);
    }

    @Override
    public String getCommitTemplate() {
        return ctx.staging.getCommitTemplate();
    }

    @Override
    public String getCommitIdentity() {
        return ctx.staging.getCommitIdentity();
    }

    @Override
    public List<String> lfsTrackedPatterns() {
        return ctx.structure.lfsTrackedPatterns();
    }

    @Override public GitResult lfsInstall() {
        return ctx.structure.lfsInstall();
    }

    @Override public GitResult lfsTrack(String pattern) {
        return ctx.structure.lfsTrack(pattern);
    }

    @Override public GitResult lfsUntrack(String pattern) {
        return ctx.structure.lfsUntrack(pattern);
    }

    @Override public GitResult lfsFetch() {
        return ctx.structure.lfsFetch();
    }

    @Override public GitResult lfsPull() {
        return ctx.structure.lfsPull();
    }

    @Override public GitResult lfsPrune() {
        return ctx.structure.lfsPrune();
    }

    @Override public List<com.jfx.git.model.LfsLock> lfsLocks() {
        return ctx.structure.lfsLocks();
    }

    @Override public GitResult lfsLock(String path) {
        return ctx.structure.lfsLock(path);
    }

    @Override public GitResult lfsUnlock(String path, boolean force) {
        return ctx.structure.lfsUnlock(path, force);
    }
}
