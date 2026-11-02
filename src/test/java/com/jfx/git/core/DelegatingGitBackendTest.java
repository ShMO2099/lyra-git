package com.jfx.git.core;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.ResetMode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.model.BisectStatus;
import com.jfx.git.model.CommitPosition;
import com.jfx.git.model.PullMode;
import com.jfx.git.model.RebaseRange;
import com.jfx.git.model.StagingState;
import com.jfx.git.model.Submodule;

/**
 * Pins the reader/writer routing the three delegating backends now share.
 *
 * <p>Before, each hand-wrote its own copy of this table, and a divergence between them
 * would have been a silent behaviour difference rather than a compile error. Now there is
 * one table, so it is worth stating in a test which side each call is expected to land on.
 */
class DelegatingGitBackendTest {

    /** Records which methods were called on it and answers everything harmlessly. */
    private static final class RecordingBackend implements GitBackend {
        private final String name;
        private final List<String> calls = new ArrayList<>();

        RecordingBackend(String name) {
            this.name = name;
        }

        private <T> T record(String method, T value) {
            calls.add(method);
            return value;
        }

        @Override public boolean openRepository(File dir) { return record("openRepository", true); }
        @Override public boolean isOpen() { return true; }
        @Override public File getDirectory() { return record("getDirectory", null); }
        @Override public File getWorkTree() { return record("getWorkTree", null); }
        @Override public void close() { calls.add("close"); }

        @Override public List<RefInfo> getLocalBranches() { return record("getLocalBranches", List.of()); }
        @Override public List<RefInfo> getRemoteBranches() { return record("getRemoteBranches", List.of()); }
        @Override public List<RefInfo> getTags() { return record("getTags", List.of()); }
        @Override public String getCurrentBranch() { return record("getCurrentBranch", name); }
        @Override public int[] getTrackingStatus() { return record("getTrackingStatus", new int[]{0, 0}); }
        @Override public GitResult checkoutBranch(String b) { return record("checkoutBranch", GitResult.ok(name)); }
        @Override public boolean isHeadDetached() { record("isHeadDetached", GitResult.ok(name)); return false; }
        @Override public String symbolicRefTarget(String n) { return record("symbolicRefTarget", null); }
        @Override public boolean isDetachedHeadOrphanRisk() {
            record("isDetachedHeadOrphanRisk", GitResult.ok(name));
            return false;
        }

        @Override public com.jfx.git.core.GitResult discardAllChanges() {
            record("discardAllChanges", com.jfx.git.core.GitResult.ok(name));
            return null;
        }

        @Override public com.jfx.git.core.GitResult restoreFileFromCommit(String h, String p) {
            record("restoreFileFromCommit", com.jfx.git.core.GitResult.ok(name));
            return null;
        }
        @Override public GitResult restoreFileFromCommit(String h, String p, String into) {
            return record("restoreFileFromCommit", GitResult.ok(name));
        }
        @Override public GitResult checkoutRemoteBranch(String b) { return record("checkoutRemoteBranch", GitResult.ok(name)); }
        @Override public GitResult createBranch(String n, String s, boolean c) { return record("createBranch", GitResult.ok(name)); }
        @Override public GitResult deleteBranch(String n, boolean f) { return record("deleteBranch", GitResult.ok(name)); }
        @Override public GitResult renameBranch(String o, String n) { return record("renameBranch", GitResult.ok(name)); }
        @Override public String getUpstream(String b) { return record("getUpstream", name); }
        @Override public java.util.Map<String, String> getUpstreams() { return record("getUpstreams", java.util.Map.of()); }
        @Override public GitResult setUpstream(String b, String u) { return record("setUpstream", GitResult.ok(name)); }
        @Override public GitResult unsetUpstream(String b) { return record("unsetUpstream", GitResult.ok(name)); }
        @Override public java.util.Set<String> getGoneBranches() { return record("getGoneBranches", java.util.Set.of()); }
        @Override public List<String> getBranchesMergedInto(String t) { return record("getBranchesMergedInto", List.of()); }

        @Override public boolean hasUncommittedChanges() { return record("hasUncommittedChanges", false); }
        @Override public List<String> getStagedFiles() { return record("getStagedFiles", List.of()); }
        @Override public List<String> getConflictingFiles() { return record("getConflictingFiles", List.of()); }
        @Override public StagingState getStagingState() { return record("getStagingState", StagingState.empty()); }
        @Override public GitResult stageFile(String p) { return record("stageFile", GitResult.ok(name)); }
        @Override public GitResult stageAll() { return record("stageAll", GitResult.ok(name)); }
        @Override public GitResult unstageFile(String p) { return record("unstageFile", GitResult.ok(name)); }
        @Override public GitResult unstageAll() { return record("unstageAll", GitResult.ok(name)); }
        @Override public GitResult discardChanges(String p) { return record("discardChanges", GitResult.ok(name)); }

        @Override public GitResult commit(String m, boolean a) { return record("commit", GitResult.ok(name)); }
        @Override public GitResult commit(String m, boolean a, boolean skipHooks) { return record("commit", GitResult.ok(name)); }
        @Override public String getHeadCommitMessage() { return record("getHeadCommitMessage", name); }
        @Override public String getHeadHash() { return record("getHeadHash", name); }
        @Override public GitResult setLocalIdentity(String n, String e, String k, boolean s) { return record("setLocalIdentity", GitResult.ok(name)); }

        @Override public List<String> getRemoteNames() { return record("getRemoteNames", List.of()); }
        @Override public String getRemoteUrl(String n) { return record("getRemoteUrl", name); }
        @Override public GitResult addRemote(String n, String u) { return record("addRemote", GitResult.ok(name)); }
        @Override public GitResult removeRemote(String n) { return record("removeRemote", GitResult.ok(name)); }
        @Override public String getRemotePushUrl(String n) { return record("getRemotePushUrl", name); }
        @Override public GitResult renameRemote(String o, String n) { return record("renameRemote", GitResult.ok(name)); }
        @Override public GitResult setRemoteUrl(String n, String u, boolean push) { return record("setRemoteUrl", GitResult.ok(name)); }
        @Override public GitResult fetchRemote(String n) { return record("fetchRemote", GitResult.ok(name)); }
        @Override public GitResult deleteRemoteBranch(String r, String b) { return record("deleteRemoteBranch", GitResult.ok(name)); }
        @Override public GitResult fetchAll() { return record("fetchAll", GitResult.ok(name)); }
        @Override public GitResult pull(PullMode m) { return record("pull", GitResult.ok(name)); }
        @Override public GitResult push(String r, PushLease l, boolean t, boolean u) { return record("push", GitResult.ok(name)); }
        @Override public PushLease pushLease(String r) { record("pushLease", GitResult.ok(name)); return null; }
        @Override public GitResult checkoutPullRequest(String r, int n, boolean reset) { return record("checkoutPullRequest", GitResult.ok(name)); }
        @Override public GitResult checkoutRemoteRef(String r, String ref, String local, boolean reset) { return record("checkoutRemoteRef", GitResult.ok(name)); }
        @Override public GitResult fetchRemoteRef(String r, String ref) { return record("fetchRemoteRef", GitResult.ok(name)); }
        @Override public String mergeBase(String a, String b) { record("mergeBase", GitResult.ok(name)); return name; }

        @Override public GitResult merge(String b, com.jfx.git.model.MergeMode mo, String m) { return record("merge", GitResult.ok(name)); }
        @Override public GitResult rebase(String u) { return record("rebase", GitResult.ok(name)); }
        @Override public GitResult rebaseInteractive(RebaseTodo t) { return record("rebaseInteractive", GitResult.ok(name)); }
        @Override public com.jfx.git.model.OperationState getOperationState() { return record("getOperationState", com.jfx.git.model.OperationState.none()); }
        @Override public GitResult continueOperation() { return record("continueOperation", GitResult.ok(name)); }
        @Override public GitResult skipOperation() { return record("skipOperation", GitResult.ok(name)); }
        @Override public GitResult abortOperation() { return record("abortOperation", GitResult.ok(name)); }
        @Override public GitResult cherryPick(String h, int m) { return record("cherryPickMainline", GitResult.ok(name)); }
        @Override public GitResult revert(String h, int m) { return record("revertMainline", GitResult.ok(name)); }
        @Override public RebaseRange getRebaseRange(String h) { return record("getRebaseRange", new RebaseRange(List.of(), null, name)); }
        @Override public List<CommitInfo> getCommitsSince(String h) { return record("getCommitsSince", List.of()); }
        @Override public GitResult cherryPick(String h) { return record("cherryPick", GitResult.ok(name)); }
        @Override public List<String> lfsTrackedPatterns() { return record("lfsTrackedPatterns", List.of()); }
        @Override public GitResult lfsInstall() { return record("lfsInstall", GitResult.ok(name)); }
        @Override public GitResult lfsTrack(String p) { return record("lfsTrack", GitResult.ok(name)); }
        @Override public GitResult lfsUntrack(String p) { return record("lfsUntrack", GitResult.ok(name)); }
        @Override public GitResult lfsFetch() { return record("lfsFetch", GitResult.ok(name)); }
        @Override public GitResult lfsPull() { return record("lfsPull", GitResult.ok(name)); }
        @Override public GitResult lfsPrune() { return record("lfsPrune", GitResult.ok(name)); }
        @Override public List<com.jfx.git.model.LfsLock> lfsLocks() { return record("lfsLocks", List.of()); }
        @Override public GitResult lfsLock(String p) { return record("lfsLock", GitResult.ok(name)); }
        @Override public GitResult lfsUnlock(String p, boolean f) { return record("lfsUnlock", GitResult.ok(name)); }
        @Override public String getLocalConfig(String k) { return record("getLocalConfig", name); }
        @Override public GitResult setLocalConfig(String k, String v) { return record("setLocalConfig", GitResult.ok(name)); }
        @Override public GitResult createPatch(List<String> c, java.io.File o) { return record("createPatch", GitResult.ok(name)); }
        @Override public GitResult applyPatch(java.io.File p, com.jfx.git.model.PatchApplyMode m) { return record("applyPatch", GitResult.ok(name)); }
        @Override public GitResult revert(String h) { return record("revert", GitResult.ok(name)); }
        @Override public GitResult reset(String c, ResetMode m) { return record("reset", GitResult.ok(name)); }

        @Override public GitResult bisectStart() { return record("bisectStart", GitResult.ok(name)); }
        @Override public BisectStatus bisectMarkGood(String h) { return record("bisectMarkGood", BisectStatus.inactive()); }
        @Override public BisectStatus bisectMarkBad(String h) { return record("bisectMarkBad", BisectStatus.inactive()); }
        @Override public BisectStatus bisectSkip() { return record("bisectSkip", BisectStatus.inactive()); }
        @Override public BisectStatus bisectStatus() { return record("bisectStatus", BisectStatus.inactive()); }
        @Override public GitResult bisectReset() { return record("bisectReset", GitResult.ok(name)); }

        @Override public GraphPage getCommitGraphPage(int l, com.jfx.git.graph.GraphOrder o) { return record("getCommitGraphPage", new GraphPage(List.of(), false)); }
        @Override public GraphPage getCommitGraphPage(int l, com.jfx.git.graph.GraphOrder o, com.jfx.git.graph.GraphVisibility v) { return record("getCommitGraphPage", new GraphPage(List.of(), false)); }
        @Override public List<CommitInfo> getCommitsInRange(String e, String i, int l) { return record("getCommitsInRange", List.of()); }
        @Override public java.util.Map<String, int[]> getBranchTracking() { return record("getBranchTracking", java.util.Map.of()); }
        @Override public List<FileChange> getChangesInCommit(String h) { return record("getChangesInCommit", List.of()); }
        @Override public GitResult fetchAllSilently() { return record("fetchAllSilently", GitResult.ok(name)); }
        @Override public boolean isSparseCheckoutEnabled() { return record("isSparseCheckoutEnabled", false); }
        @Override public List<String> getSparseCheckoutPaths() { return record("getSparseCheckoutPaths", List.of()); }
        @Override public boolean needsMaintenance() { return record("needsMaintenance", false); }
        @Override public List<Hook> getHooks() { return record("getHooks", List.of()); }
        @Override public java.util.Map<String, int[]> getWorkingTreeLineCounts(boolean s, List<String> p) { return record("getWorkingTreeLineCounts", java.util.Map.of()); }
        @Override public String getCommitTemplate() { return record("getCommitTemplate", ""); }
        @Override public String getCommitIdentity() { return record("getCommitIdentity", name); }
        @Override public boolean graphReaches(String h, com.jfx.git.graph.GraphVisibility v) { return record("graphReaches", false); }
        @Override public com.jfx.git.model.RefSnapshot getRefSnapshot() {
            return record("getRefSnapshot", com.jfx.git.model.RefSnapshot.EMPTY);
        }
        @Override public CommitPosition getCommitPosition(String h) { return record("getCommitPosition", new CommitPosition(true, false, false, true, false)); }
        @Override public String getCommitMessage(String h) { return record("getCommitMessage", name); }
        @Override public List<CommitInfo> getFileHistoryCommits(String p, int l) { return record("getFileHistoryCommits", List.of()); }
        @Override public List<CommitInfo> searchCommits(com.jfx.git.model.CommitSearch q, int l) { return record("searchCommits", List.of()); }
        @Override public List<com.jfx.git.model.GrepMatch> grep(String r, com.jfx.git.model.GrepQuery q, int l) { return record("grep", List.of()); }
        @Override public List<ReflogItem> getReflog(int l) { return record("getReflog", List.of()); }

        @Override public byte[] getDiffBytesForFileInCommit(String h, String p, boolean w) { return record("getDiffBytesForFileInCommit", new byte[0]); }
        @Override public byte[] getDiffBytesForFileInCommit(CommitInfo c, String p, boolean w) { return record("getDiffBytesForFileInCommitInfo", new byte[0]); }
        @Override public byte[] getDiffBytesForFileInCommit(String h, String p, String o, boolean w) { return record("getDiffBytesForFileInCommitRenamed", new byte[0]); }
        @Override public byte[] getUnstagedDiffBytes(String p, boolean w) { return record("getUnstagedDiffBytes", new byte[0]); }
        @Override public byte[] getStagedDiffBytes(String p, boolean w) { return record("getStagedDiffBytes", new byte[0]); }
        @Override public List<String> getFilesInCommit(String h) { return record("getFilesInCommit", List.of()); }
        @Override public List<String> getFilesInCommit(CommitInfo c) { return record("getFilesInCommitInfo", List.of()); }
        @Override public com.jfx.git.model.SignatureStatus getSignatureStatus(String h) { return record("getSignatureStatus", com.jfx.git.model.SignatureStatus.NONE); }
        @Override public List<String> getFilesBetweenCommits(String f, String t) { return record("getFilesBetweenCommits", List.of()); }
        @Override public List<FileChange> getChangesBetweenCommits(String f, String t) { return record("getChangesBetweenCommits", List.of()); }
        @Override public byte[] getDiffBytesBetweenCommits(String f, String t, String p, boolean w) { return record("getDiffBytesBetweenCommits", new byte[0]); }
        @Override public byte[] getDiffBytesBetweenCommits(String f, String t, String p, String o, boolean w) { return record("getDiffBytesBetweenCommitsRenamed", new byte[0]); }
        @Override public String getFileContentAtCommit(String h, String p) { return record("getFileContentAtCommit", name); }
        @Override public String getFileContentAtCommit(CommitInfo c, String p) { return record("getFileContentAtCommitInfo", name); }
        @Override public byte[] getFileBytesAtCommit(String h, String p) { return record("getFileBytesAtCommit", new byte[0]); }
        @Override public byte[] getFileBytesAtCommit(CommitInfo c, String p) { return record("getFileBytesAtCommitInfo", new byte[0]); }
        @Override public byte[][] getCommitSides(CommitInfo c, String p) { return record("getCommitSides", new byte[][]{}); }
        @Override public byte[][] getWorkingTreeSides(String p) { return record("getWorkingTreeSides", new byte[][]{}); }
        @Override public String getConflictStage(String p, int s) { return record("getConflictStage", name); }
        @Override public byte[] getConflictStageBytes(String p, int s) { return record("getConflictStageBytes", new byte[0]); }
        @Override public GitResult resolveConflict(String p, String c) { return record("resolveConflict", GitResult.ok(name)); }
        @Override public String getWorkingTreeContent(String p) { return record("getWorkingTreeContent", name); }
        @Override public byte[] getWorkingTreeBytes(String p) { return record("getWorkingTreeBytes", new byte[0]); }
        @Override public GitResult writeWorkingTreeFile(String p, byte[] b) { return record("writeWorkingTreeFile", GitResult.ok(name)); }
        @Override public GitResult revertHunk(String p, HunkEdit e) { return record("revertHunk", GitResult.ok(name)); }
        @Override public GitResult applyToIndex(String p, HunkEdit e, String a) { return record("applyToIndex", GitResult.ok(name)); }
        @Override public byte[] getStagedBytes(String p) { return record("getStagedBytes", new byte[0]); }

        @Override public List<BlameLine> getBlameLines(String p, String c) { return record("getBlameLines", List.of()); }

        @Override public List<com.jfx.git.model.StashEntry> getStashEntries() { return record("getStashEntries", List.of()); }
        @Override public GitResult stash(com.jfx.git.model.StashOptions o) { return record("stash", GitResult.ok(name)); }
        @Override public GitResult stashBranch(String b, int i) { return record("stashBranch", GitResult.ok(name)); }
        @Override public GitResult stashPop(int i) { return record("stashPop", GitResult.ok(name)); }
        @Override public GitResult stashApply(int i) { return record("stashApply", GitResult.ok(name)); }
        @Override public GitResult stashDrop(int i) { return record("stashDrop", GitResult.ok(name)); }

        @Override public String snapshotWorkingTree(String r) { return record("snapshotWorkingTree", name); }
        @Override public List<com.jfx.git.model.Snapshot> listSnapshots() { return record("listSnapshots", List.of()); }
        @Override public GitResult deleteSnapshot(String id) { return record("deleteSnapshot", GitResult.ok(name)); }
        @Override public GitResult restoreFromSnapshot(String id, List<String> p) {
            return record("restoreFromSnapshot", GitResult.ok(name));
        }
        @Override public com.jfx.git.model.ConflictPrediction predictMergeConflicts(String o, String t) {
            return record("predictMergeConflicts", com.jfx.git.model.ConflictPrediction.clean());
        }

        @Override public GitResult createTag(String n, String m, String c) { return record("createTag", GitResult.ok(name)); }
        @Override public GitResult deleteTag(String n) { return record("deleteTag", GitResult.ok(name)); }
        @Override public GitResult deleteRemoteTag(String r, String t) { return record("deleteRemoteTag", GitResult.ok(name)); }
        @Override public GitResult pushTag(String r, String t) { return record("pushTag", GitResult.ok(name)); }
        @Override public boolean isTagMoveFastForward(String t, String c) {
            record("isTagMoveFastForward", GitResult.ok(name));
            return false;
        }
        @Override public GitResult moveTag(String t, String c) { return record("moveTag", GitResult.ok(name)); }

        @Override public List<Submodule> getSubmodules() { return record("getSubmodules", List.of()); }
        @Override public GitResult updateSubmodules(List<String> p) { return record("updateSubmodules", GitResult.ok(name)); }
        @Override public GitResult addSubmodule(String u, String p) { return record("addSubmodule", GitResult.ok(name)); }
        @Override public GitResult syncSubmodules() { return record("syncSubmodules", GitResult.ok(name)); }
        @Override public GitResult deinitSubmodule(String p, boolean f) { return record("deinitSubmodule", GitResult.ok(name)); }
        @Override public File submoduleWorkTree(String p) { return record("submoduleWorkTree", null); }

        @Override public List<Worktrees.Worktree> getWorktrees() { return record("getWorktrees", List.of()); }
        @Override public GitResult addWorktree(File p, String b, boolean c) { return record("addWorktree", GitResult.ok(name)); }
        @Override public GitResult removeWorktree(File p, boolean f) { return record("removeWorktree", GitResult.ok(name)); }
        @Override public GitResult pruneWorktrees() { return record("pruneWorktrees", GitResult.ok(name)); }
        @Override public GitResult setWorktreeLocked(File p, boolean l) { return record("setWorktreeLocked", GitResult.ok(name)); }
    }

    /** A backend with the two roles wired to distinguishable recorders. */
    private static final class Probe extends DelegatingGitBackend {
        final RecordingBackend readSide = new RecordingBackend("reader");
        final RecordingBackend writeSide = new RecordingBackend("writer");

        @Override protected GitBackend reader() { return readSide; }
        @Override protected GitBackend writer() { return writeSide; }

        @Override public boolean openRepository(File dir) { return true; }
        @Override public boolean isOpen() { return true; }
        @Override public File getDirectory() { return null; }
        @Override public File getWorkTree() { return null; }
        @Override public void close() { }
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    @Test
    void historyAndDiffReadsGoToTheReader() {
        try (Probe probe = new Probe()) {
            probe.getCommitGraphPage(10, com.jfx.git.graph.GraphOrder.current());
            probe.getCommitPosition("abc");
            // The same engine that walked the graph page answers for the bodies it left out.
            probe.getCommitMessage("abc");
            probe.getFilesInCommit("abc");
            probe.getFilesBetweenCommits("a", "b");
            probe.getDiffBytesBetweenCommits("a", "b", "f.txt", false);
            probe.getChangesBetweenCommits("a", "b");
            probe.getDiffBytesBetweenCommits("a", "b", "new.txt", "old.txt", false);
            probe.getDiffBytesForFileInCommit("abc", "new.txt", "old.txt", false);
            probe.getFileBytesAtCommit("abc", "f.txt");
            probe.getSubmodules();
            probe.getWorktrees();
            probe.searchCommits(com.jfx.git.model.CommitSearch.byMessage("fix"), 10);
            // The engine that walks the pages says what they reach.
            probe.graphReaches("abc", com.jfx.git.graph.GraphVisibility.ALL);

            assertEquals(14, probe.readSide.calls.size(), probe.readSide.calls.toString());
            assertTrue(probe.writeSide.calls.isEmpty(),
                    "no history read should reach the writer: " + probe.writeSide.calls);
        }
    }

    @Test
    void mutationsAndNetworkGoToTheWriter() {
        try (Probe probe = new Probe()) {
            probe.stageAll();
            probe.commit("m", false);
            probe.commit("m", false, true);
            probe.checkoutBranch("main");
            probe.fetchAll();
            probe.push(null, (PushLease) null, false, true);
            probe.stash(com.jfx.git.model.StashOptions.everything(null));
            probe.createTag("v1", null, null);
            probe.deleteRemoteTag("origin", "v1");
            probe.pushTag("origin", "v1");
            probe.isTagMoveFastForward("v1", "abc");
            probe.moveTag("v1", "abc");
            probe.reset("abc", ResetMode.HARD);
            probe.getStagingState();
            probe.getWorkingTreeBytes("f.txt");
            probe.getRemoteUrl("origin");
            probe.checkoutPullRequest("origin", 42, false);
            probe.checkoutRemoteRef("origin", "refs/merge-requests/7/head", "mr-7", false);
            probe.fetchRemoteRef("origin", "refs/pull/42/head");

            assertEquals(19, probe.writeSide.calls.size(), probe.writeSide.calls.toString());
            assertTrue(probe.readSide.calls.isEmpty(),
                    "no mutation should reach the reader: " + probe.readSide.calls);
        }
    }

    /**
     * Snapshots read the index and the working tree and write refs, so all four go to the
     * writer; predicting a merge's conflicts only reads objects, so it goes to the reader.
     */
    @Test
    void snapshotsGoToTheWriterAndConflictPredictionToTheReader() {
        try (Probe probe = new Probe()) {
            probe.snapshotWorkingTree("timer");
            probe.listSnapshots();
            probe.deleteSnapshot("1");
            probe.restoreFromSnapshot("1", List.of("a.txt"));
            probe.predictMergeConflicts("HEAD", "origin/main");

            assertEquals(List.of("snapshotWorkingTree", "listSnapshots", "deleteSnapshot",
                    "restoreFromSnapshot"), probe.writeSide.calls);
            assertEquals(List.of("predictMergeConflicts"), probe.readSide.calls);
        }
    }

    /**
     * git-lfs is a program git runs, so every LFS call - listing included - goes to the
     * writer, which is the CLI wherever it can run at all.
     */
    @Test
    void everyLfsCallGoesToTheWriter() {
        try (Probe probe = new Probe()) {
            probe.lfsTrackedPatterns();
            probe.lfsInstall();
            probe.lfsTrack("*.bin");
            probe.lfsUntrack("*.bin");
            probe.lfsFetch();
            probe.lfsPull();
            probe.lfsPrune();
            probe.lfsLocks();
            probe.lfsLock("a.bin");
            probe.lfsUnlock("a.bin", false);

            assertEquals(10, probe.writeSide.calls.size(), probe.writeSide.calls.toString());
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /** A repository's own settings are read from the engine that writes them. */
    @Test
    void repositoryConfigurationIsReadAndWrittenByTheWriter() {
        try (Probe probe = new Probe()) {
            probe.setLocalConfig("gitflow.branch.develop", "develop");
            probe.getLocalConfig("gitflow.branch.develop");

            assertEquals(List.of("setLocalConfig", "getLocalConfig"), probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /**
     * Both ends of a patch go to the writer. Applying one changes the repository and runs
     * the user's hooks; saving one only reads, but JGit refuses any commit that touches a
     * binary file, which git writes with --binary.
     */
    @Test
    void patchesAreSavedAndAppliedByTheWriter() {
        try (Probe probe = new Probe()) {
            probe.createPatch(List.of("abc"), new java.io.File("x.patch"));
            probe.applyPatch(new java.io.File("x.patch"), com.jfx.git.model.PatchApplyMode.AM);

            assertEquals(List.of("createPatch", "applyPatch"), probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /**
     * Managing remotes and upstreams is configuration the writer's pull, push and fetch
     * then act on, so all of it - the reads included - belongs to the writer.
     */
    @Test
    void remoteAndUpstreamManagementGoesToTheWriter() {
        try (Probe probe = new Probe()) {
            probe.getRemotePushUrl("origin");
            probe.renameRemote("origin", "upstream");
            probe.setRemoteUrl("origin", "https://example.com/r.git", false);
            probe.fetchRemote("origin");
            probe.deleteRemoteBranch("origin", "old");
            probe.getUpstream("main");
            probe.getUpstreams();
            probe.setUpstream("main", "origin/main");
            probe.unsetUpstream("main");

            assertEquals(List.of("getRemotePushUrl", "renameRemote", "setRemoteUrl", "fetchRemote",
                    "deleteRemoteBranch", "getUpstream", "getUpstreams", "setUpstream", "unsetUpstream"),
                    probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /**
     * Planning a rebase walks history; running one - interactive included - changes the
     * repository, and has to run the user's hooks, signing and includeIf identity, which
     * only the writer (the CLI where git exists) honours. Interactive rebase used to be
     * routed to the reader, i.e. JGit, on both default engines.
     */
    @Test
    void rebasePlanningReadsButRunningWrites() {
        try (Probe probe = new Probe()) {
            probe.getRebaseRange("abc");
            assertEquals(List.of("getRebaseRange"), probe.readSide.calls);

            probe.rebaseInteractive(null);
            probe.rebase("main");
            probe.abortOperation();
            probe.continueOperation();
            assertEquals(List.of("rebaseInteractive", "rebase", "abortOperation", "continueOperation"),
                    probe.writeSide.calls);
        }
    }

    /**
     * Merge modes and stash options are mutations like the calls they grew out of; the
     * older signatures reach the writer through the same new methods.
     */
    @Test
    void mergeModesAndStashOptionsGoToTheWriter() {
        try (Probe probe = new Probe()) {
            probe.merge("side", com.jfx.git.model.MergeMode.SQUASH, "m");
            probe.merge("side", com.jfx.git.model.MergeMode.NO_FF, null);
            probe.stash(new com.jfx.git.model.StashOptions("m", false, true, List.of()));
            probe.stash(com.jfx.git.model.StashOptions.everything("named"));
            probe.stashBranch("from-stash", 0);

            assertEquals(List.of("merge", "merge", "stash", "stash", "stashBranch"),
                    probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /**
     * The in-progress operation is read and driven by the engine that ran it, so a
     * "Continue" lands where the stopped rebase or cherry-pick was started.
     */
    @Test
    void inProgressOperationsGoToTheWriter() {
        try (Probe probe = new Probe()) {
            probe.getOperationState();
            probe.continueOperation();
            probe.skipOperation();
            probe.abortOperation();
            probe.cherryPick("abc", 1);
            probe.revert("abc", 2);

            assertEquals(List.of("getOperationState", "continueOperation", "skipOperation",
                    "abortOperation", "cherryPickMainline", "revertMainline"), probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /**
     * Hunk patching writes the index or the working tree, so it goes where the hunks came
     * from: the writer's diff. On the reader, JGit rewrote .git/index behind git's back.
     */
    @Test
    void hunkPatchingGoesToTheWriter() {
        try (Probe probe = new Probe()) {
            probe.revertHunk("f.txt", new HunkEdit(1, List.of(), List.of()));
            probe.applyToIndex("f.txt", new HunkEdit(1, List.of(), List.of()), "Staged");

            assertEquals(List.of("revertHunk", "applyToIndex"), probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /** The working tree is a filesystem read, not a history one. */
    @Test
    void workingTreeReadsGoToTheWriter() {
        try (Probe probe = new Probe()) {
            probe.getUnstagedDiffBytes("f.txt", false);
            probe.getStagedDiffBytes("f.txt", false);
            probe.getWorkingTreeContent("f.txt");
            probe.getBlameLines("f.txt", null);
            // The index is the working tree's other half: the staging list's counts, the
            // comparison base and the conflict stages are read where they are written.
            probe.getWorkingTreeLineCounts(true, List.of("f.txt"));
            probe.getWorkingTreeSides("f.txt");
            probe.getStagedBytes("f.txt");
            probe.getConflictStage("f.txt", 2);
            probe.getConflictStageBytes("f.txt", 3);

            assertEquals(9, probe.writeSide.calls.size(), probe.writeSide.calls.toString());
            assertTrue(probe.readSide.calls.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // SwitchableGitBackend
    // ------------------------------------------------------------------

    @Test
    void aSwitchableBackendSendsEverythingToOneEngine() {
        RecordingBackend only = new RecordingBackend("only");
        try (SwitchableGitBackend switchable = new SwitchableGitBackend(only)) {
            switchable.getCommitGraphPage(10, com.jfx.git.graph.GraphOrder.current());   // a read
            switchable.stageAll();               // a write

            assertEquals(List.of("getCommitGraphPage", "stageAll"), only.calls,
                    "one engine serves both roles");
            assertSame(only, switchable.currentEngine());
        }
    }

    /**
     * Every interface method, defaults included, is declared by the router.
     *
     * <p>A default the router inherits runs as the interface's own composition on the
     * wrapper rather than reaching an engine, so an engine that overrides that default -
     * with a faster or more correct answer - was silently bypassed on every composed
     * engine. Nothing failed; the override just never ran.
     */
    @Test
    void everyMethodIsForwardedRatherThanInherited() {
        // Opening and closing are each subclass's own: one engine, two or three.
        java.util.Set<String> lifecycle = java.util.Set.of(
                "openRepository", "isOpen", "getDirectory", "getWorkTree", "close");
        List<String> inherited = new java.util.ArrayList<>();
        for (java.lang.reflect.Method method : GitBackend.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            if (lifecycle.contains(method.getName())) continue;
            try {
                DelegatingGitBackend.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                inherited.add(method.getName() + java.util.Arrays.toString(method.getParameterTypes()));
            }
        }
        java.util.Collections.sort(inherited);
        assertEquals(List.of(), inherited, "methods DelegatingGitBackend does not forward");
    }

    /**
     * The refs read without a page come from the engine that reads the pages: what a page
     * that failed did not bring is what it would have.
     */
    @Test
    void theRefsReadWithoutAPageGoToTheReader() {
        try (Probe probe = new Probe()) {
            probe.getRefSnapshot();

            assertEquals(List.of("getRefSnapshot"), probe.readSide.calls);
            assertTrue(probe.writeSide.calls.isEmpty(), probe.writeSide.calls.toString());
        }
    }

    /**
     * Branch cleanup reads what the last fetch pruned, and a search in files reads the
     * working tree: both belong to the writer, with the upstreams and the status.
     */
    @Test
    void branchCleanupAndSearchInFilesGoToTheWriter() {
        try (Probe probe = new Probe()) {
            probe.getGoneBranches();
            probe.getBranchesMergedInto("main");
            probe.grep(null, com.jfx.git.model.GrepQuery.literal("x"), 10);
            probe.grep("HEAD", com.jfx.git.model.GrepQuery.literal("x"), 10);

            assertEquals(List.of("getGoneBranches", "getBranchesMergedInto", "grep", "grep"),
                    probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /** git follows a file through its history two to three times faster than JGit; measured. */
    @Test
    void fileHistoryGoesToTheWriterByMeasurement() {
        try (Probe probe = new Probe()) {
            probe.getFileHistoryCommits("f.txt", 5);

            assertEquals(List.of("getFileHistoryCommits"), probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    /** Verifying needs gpg or ssh, which only the CLI runs; JGit can only see a signature. */
    @Test
    void signatureChecksGoToTheEngineThatCanVerifyThem() {
        try (Probe probe = new Probe()) {
            probe.getSignatureStatus("abc");

            assertEquals(List.of("getSignatureStatus"), probe.writeSide.calls);
            assertTrue(probe.readSide.calls.isEmpty(), probe.readSide.calls.toString());
        }
    }

    @Test
    void switchingWithNoRepositoryOpenIsRefusedRatherThanSilent() {
        try (SwitchableGitBackend switchable = new SwitchableGitBackend(new RecordingBackend("only"))) {
            var result = switchable.switchTo(GitBackendFactory.BackendEngine.JGIT);

            assertFalse(result.isOk());
            assertTrue(result.getMessage().contains("nothing to switch"), result.getMessage());
        }
    }
}
