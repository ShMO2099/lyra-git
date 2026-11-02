package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.ResetMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.jfx.git.model.BisectStatus;

@Tag("heavy")
class NativeGitBackendTest {

    @TempDir
    Path tempDir;

    private NativeGitBackend backend;
    private File repoDir;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");

        repoDir = tempDir.toFile();
        // Initialize git repo using native runner
        NativeGitRunner initRunner = new NativeGitRunner(repoDir);
        var initRes = initRunner.run("init", "-b", "main");
        if (!initRes.isOk()) {
            initRunner.run("init");
        }
        initRunner.run("config", "user.name", "Test User");
        initRunner.run("config", "user.email", "test@example.com");
        initRunner.run("config", "core.autocrlf", "false");
        initRunner.run("config", "core.eol", "lf");

        backend = new NativeGitBackend();
        assertTrue(backend.openRepository(repoDir));
    }

    private void writeFile(String relativePath, String content) throws IOException {
        File file = new File(repoDir, relativePath);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    @Test
    void openRepositoryIdentifiesWorkTreeAndGitDir() {
        assertTrue(backend.isOpen());
        assertNotNull(backend.getWorkTree());
        assertNotNull(backend.getDirectory());
        assertEquals(repoDir.getAbsolutePath(), backend.getWorkTree().getAbsolutePath());
    }

    @Test
    void stagingWorkflowIsFastAndAccurate() throws Exception {
        writeFile("hello.txt", "Hello World\n");
        writeFile("nested/test.txt", "Test Content\n");

        var state1 = backend.getStagingState();
        assertEquals(2, state1.unstaged().size(), "Two files should be unstaged initially");
        assertTrue(state1.staged().isEmpty());

        // Stage one file
        var stageRes = backend.stageFile("hello.txt");
        assertTrue(stageRes.isOk());

        var state2 = backend.getStagingState();
        assertEquals(1, state2.staged().size());
        assertTrue(state2.staged().contains("hello.txt"));
        assertEquals(1, state2.unstaged().size());
        assertTrue(state2.unstaged().contains("nested/test.txt"));

        // Stage all
        assertTrue(backend.stageAll().isOk());
        var state3 = backend.getStagingState();
        assertEquals(2, state3.staged().size());
        assertTrue(state3.unstaged().isEmpty());

        // Unstage one
        assertTrue(backend.unstageFile("hello.txt").isOk());
        var state4 = backend.getStagingState();
        assertEquals(1, state4.staged().size());
        assertEquals(1, state4.unstaged().size());

        // Unstage all
        assertTrue(backend.unstageAll().isOk());
        var state5 = backend.getStagingState();
        assertTrue(state5.staged().isEmpty());
        assertEquals(2, state5.unstaged().size());
    }

    @Test
    void commitAndHistory() throws Exception {
        writeFile("file.txt", "initial\n");
        assertTrue(backend.stageAll().isOk());
        var commitRes = backend.commit("Initial commit", false);
        assertTrue(commitRes.isOk());
        assertFalse(backend.hasUncommittedChanges());

        assertEquals("Initial commit", backend.getHeadCommitMessage().trim());

        var page = backend.getCommitGraphPage(10, GraphOrder.current());
        assertEquals(1, page.rows().size());
        assertEquals("Initial commit", page.rows().get(0).getCommit().getSubject());
        assertEquals(0, page.rows().get(0).getCommit().getLane());
    }

    /** A real, executable {@code pre-commit} hook that always blocks the commit. */
    private void installBlockingPreCommitHook() throws Exception {
        File hooksDir = new File(repoDir, ".git/hooks");
        hooksDir.mkdirs();
        File hook = new File(hooksDir, "pre-commit");
        Files.writeString(hook.toPath(), "#!/bin/sh\nexit 1\n");
        hook.setExecutable(true);
    }

    @Test
    void aRealPreCommitHookBlocksAnOrdinaryCommit() throws Exception {
        installBlockingPreCommitHook();
        writeFile("file.txt", "initial\n");
        backend.stageAll();

        assertFalse(backend.commit("should be blocked", false).isOk(),
                "a pre-commit hook that exits non-zero must block the commit");
    }

    @Test
    void skipHooksBypassesARealBlockingPreCommitHookViaNoVerify() throws Exception {
        installBlockingPreCommitHook();
        writeFile("file.txt", "initial\n");
        backend.stageAll();

        var result = backend.commit("bypasses the hook", false, true);

        assertTrue(result.isOk(), result.getMessage());
    }

    @Test
    void skipHooksDefaultsToFalseOnTheTwoArgOverload() throws Exception {
        installBlockingPreCommitHook();
        writeFile("file.txt", "initial\n");
        backend.stageAll();

        assertFalse(backend.commit("two-arg overload must still run hooks", false).isOk());
    }

    @Test
    void branchOperations() throws Exception {
        writeFile("file.txt", "initial\n");
        backend.stageAll();
        backend.commit("Initial commit", false);

        assertTrue(backend.createBranch("feature", null, false).isOk());
        var localBranches = backend.getLocalBranches();
        assertEquals(2, localBranches.size());

        assertTrue(backend.checkoutBranch("feature").isOk());
        assertEquals("feature", backend.getCurrentBranch());

        writeFile("file.txt", "feature change\n");
        backend.stageAll();
        backend.commit("Feature commit", false);

        assertTrue(backend.checkoutBranch("main").isOk());
        assertEquals("main", backend.getCurrentBranch());
    }

    @Test
    void discardChangesRestoresWorkingTree() throws Exception {
        writeFile("file.txt", "clean state\n");
        backend.stageAll();
        backend.commit("Clean commit", false);

        writeFile("file.txt", "dirty edit\n");
        assertTrue(backend.hasUncommittedChanges());

        assertTrue(backend.discardChanges("file.txt").isOk());
        assertFalse(backend.hasUncommittedChanges());
        assertEquals("clean state", Files.readString(new File(repoDir, "file.txt").toPath()).trim());
    }

    /**
     * Discarding an untracked file used to delete it and then report failure, because
     * {@code clean} ran unconditionally and the failed {@code restore} supplied the
     * result. A destructive action must not report as a no-op.
     */
    @Test
    void discardingAnUntrackedFileDeletesItAndSaysSo() throws Exception {
        writeFile("base.txt", "base\n");
        backend.stageAll();
        backend.commit("base", false);

        writeFile("junk.txt", "throw me away\n");
        File junk = new File(repoDir, "junk.txt");
        assertTrue(junk.exists());

        var result = backend.discardChanges("junk.txt");
        assertFalse(junk.exists(), "the file should be gone");
        assertTrue(result.isOk(),
                () -> "deleting an untracked file is not a failure, got: " + result.getMessage());
    }

    @Test
    void conflictsAppearInBothUnstagedAndConflicting() throws Exception {
        NativeGitRunner runner = new NativeGitRunner(repoDir);
        writeFile("merge.txt", "base\n");
        backend.stageAll();
        backend.commit("base", false);

        runner.run("checkout", "-b", "side");
        writeFile("merge.txt", "side\n");
        runner.run("commit", "-am", "side");
        runner.run("checkout", "-");
        writeFile("merge.txt", "main\n");
        runner.run("commit", "-am", "main");
        runner.run("merge", "side");

        var state = backend.getStagingState();
        assertTrue(state.conflicting().contains("merge.txt"));
        assertTrue(state.unstaged().contains("merge.txt"),
                "JGit lists conflicted paths as unstaged too; the panel reads that list");
    }

    /**
     * The graph format once used a bare {@code %s} and nothing else, so the body never
     * reached the UI and the commit detail panel showed an empty one. The page now
     * carries a preview of it, and the whole message is one call away.
     */
    @Test
    void commitBodySurvivesTheGraphFormat() throws Exception {
        writeFile("f.txt", "x\n");
        backend.stageAll();
        backend.commit("Subject line\n\nBody paragraph one.\nBody paragraph two.\n", false);

        var page = backend.getCommitGraphPage(10, GraphOrder.current());
        var commit = page.rows().get(0).getCommit();

        assertEquals("Subject line", commit.getSubject());
        assertEquals("Body paragraph one. Body paragraph two.", commit.getBodySummary());
        String full = backend.getCommitMessage(commit.getHash());
        assertTrue(full.contains("Body paragraph one."), "full message was: " + full);
        assertTrue(full.contains("Body paragraph two."));
    }

    /**
     * G15, measured. The graph's {@code git log} used to ask for {@code %B}, so every
     * refresh and "load more" read every body on the page; it now asks for the subject
     * and a preview git cuts to {@link com.jfx.git.model.CommitInfo#BODY_PREVIEW_COLUMNS}.
     *
     * <p>Measured on commits whose bodies are about four kilobytes each - long, but the
     * shape of a real repository with explanatory commit messages - against the format
     * the graph used before, run over the same commits.
     */
    @Test
    void theGraphLogNoLongerReadsWholeBodies() throws Exception {
        assertFalse(NativeGitBackend.GRAPH_FORMAT.contains("%B"),
                "the graph must not ask git for whole messages: " + NativeGitBackend.GRAPH_FORMAT);
        assertTrue(NativeGitBackend.GRAPH_FORMAT.contains("%s"));
        assertFalse(NativeGitBackend.GRAPH_FORMAT.replace(
                        "%<(" + com.jfx.git.model.CommitInfo.BODY_PREVIEW_COLUMNS + ",trunc)%b", "")
                        .contains("%b"),
                "the only body the graph asks for is the cut preview: " + NativeGitBackend.GRAPH_FORMAT);

        String body = "Why this change was needed, at some length.\n".repeat(90);
        for (int i = 0; i < 20; i++) {
            writeFile("f" + i + ".txt", i + "\n");
            backend.stageAll();
            assertTrue(backend.commit("change " + i + "\n\n" + body, false).isOk());
        }

        NativeGitRunner runner = new NativeGitRunner(repoDir);
        String before = "--format=%x1e%H%x1f%B%x1f%an%x1f%ae%x1f%at%x1f%P%x1f%x1f%ct";
        int wholeBodies = runner.run("log", "--no-decorate", before).stdoutBytes().length;
        int summaries = runner.run("log", "--no-decorate", NativeGitBackend.GRAPH_FORMAT)
                .stdoutBytes().length;
        assertTrue(summaries * 10 < wholeBodies,
                () -> "the graph read " + summaries + " bytes where whole bodies are "
                        + wholeBodies + "; it should be a small fraction");

        for (var row : backend.getCommitGraphPage(50, GraphOrder.current()).rows()) {
            var commit = row.getCommit();
            if (commit == null) continue;
            assertFalse(commit.hasFullMessage(), "a graph row holds no whole message");
            assertTrue(commit.getBodySummary().length()
                    <= com.jfx.git.model.CommitInfo.BODY_PREVIEW_COLUMNS + 2,
                    () -> "the preview is bounded: " + commit.getBodySummary().length());
        }
    }

    /**
     * The root commit has an empty %P and, once it is no longer the branch tip, an empty
     * %D. Its record therefore ends in separators, and trimming the output removed them
     * (0x1E and 0x1F both sort below U+0020), leaving a short record that the parser
     * discarded — so the first commit of every repository was missing from the graph.
     */
    @Test
    void theRootCommitIsNotDroppedFromTheGraph() throws Exception {
        writeFile("a.txt", "one\n");
        backend.stageAll();
        backend.commit("root commit", false);

        writeFile("b.txt", "two\n");
        backend.stageAll();
        backend.commit("second commit", false);

        var subjects = backend.getCommitGraphPage(20, GraphOrder.current()).rows().stream()
                .filter(r -> !r.isSynthetic())
                .map(r -> r.getCommit().getSubject())
                .toList();

        assertEquals(List.of("second commit", "root commit"), subjects);
    }

    // ------------------------------------------------------------------
    // Hide & Solo (GraphVisibility)
    // ------------------------------------------------------------------

    private List<String> subjectsWith(com.jfx.git.graph.GraphVisibility visibility) throws Exception {
        return backend.getCommitGraphPage(20, com.jfx.git.graph.GraphOrder.CHRONOLOGICAL, visibility)
                .rows().stream()
                .filter(r -> !r.isSynthetic())
                .map(r -> r.getCommit().getSubject())
                .toList();
    }

    @Test
    void hidingABranchThroughTheCliExcludesCommitsOnlyItReaches() throws Exception {
        writeFile("m0.txt", "m0\n");
        backend.stageAll();
        backend.commit("commit M0", false);
        String trunk = backend.getCurrentBranch();

        assertTrue(backend.createBranch("side", null, true).isOk());
        writeFile("s0.txt", "s0\n");
        backend.stageAll();
        backend.commit("commit S0 on side", false);
        assertTrue(backend.checkoutBranch(trunk).isOk());

        var hideSide = new com.jfx.git.graph.GraphVisibility(
                java.util.Set.of("refs/heads/side"), null);
        List<String> subjects = subjectsWith(hideSide);

        assertTrue(subjects.contains("commit M0"));
        assertFalse(subjects.contains("commit S0 on side"),
                "hiding side's only ref should drop the commit reachable only through it");
    }

    @Test
    void soloingABranchThroughTheCliShowsOnlyItAndHead() throws Exception {
        writeFile("m0.txt", "m0\n");
        backend.stageAll();
        backend.commit("commit M0", false);
        String trunk = backend.getCurrentBranch();

        assertTrue(backend.createBranch("side1", null, true).isOk());
        writeFile("s1.txt", "s1\n");
        backend.stageAll();
        backend.commit("commit S1 on side1", false);
        assertTrue(backend.checkoutBranch(trunk).isOk());

        assertTrue(backend.createBranch("side2", null, true).isOk());
        writeFile("s2.txt", "s2\n");
        backend.stageAll();
        backend.commit("commit S2 on side2", false);
        assertTrue(backend.checkoutBranch(trunk).isOk());

        var soloSide1 = new com.jfx.git.graph.GraphVisibility(java.util.Set.of(), "refs/heads/side1");
        List<String> subjects = subjectsWith(soloSide1);

        assertTrue(subjects.contains("commit M0"), "HEAD's own history is never solo'd away");
        assertTrue(subjects.contains("commit S1 on side1"));
        assertFalse(subjects.contains("commit S2 on side2"),
                "solo overrides everything not named");
    }

    @Test
    void aRootCommitWithNoRefsStillParses() throws Exception {
        writeFile("a.txt", "one\n");
        backend.stageAll();
        backend.commit("root commit", false);
        writeFile("b.txt", "two\n");
        backend.stageAll();
        backend.commit("second", false);

        var root = backend.getCommitGraphPage(20, GraphOrder.current()).rows().stream()
                .filter(r -> !r.isSynthetic())
                .map(r -> r.getCommit())
                .filter(c -> c.getParentHashes().isEmpty())
                .findFirst();

        assertTrue(root.isPresent(), "the parentless commit must survive parsing");
        assertEquals("root commit", root.get().getSubject());
        assertTrue(root.get().getRefs().isEmpty());
    }

    @Test
    void commitPositionAnswersFromTheRepositoryRatherThanAssuming() throws Exception {
        NativeGitRunner runner = new NativeGitRunner(repoDir);
        writeFile("f.txt", "one\n");
        backend.stageAll();
        backend.commit("first", false);
        String root = backend.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        var rootPosition = backend.getCommitPosition(root);
        assertTrue(rootPosition.known());
        assertTrue(rootPosition.root(), "the first commit has no parents");
        assertFalse(rootPosition.merge());
        assertTrue(rootPosition.onCurrentBranch());

        // A commit on a branch that HEAD cannot reach is not on the current branch.
        runner.run("checkout", "-b", "elsewhere");
        writeFile("f.txt", "two\n");
        backend.stageAll();
        backend.commit("only on elsewhere", false);
        String offBranch = backend.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();
        runner.run("checkout", "-");

        var offPosition = backend.getCommitPosition(offBranch);
        assertTrue(offPosition.known());
        assertFalse(offPosition.onCurrentBranch(),
                "commit lives only on 'elsewhere', which HEAD cannot reach");
    }

    @Test
    void mergeCommitsAreRecognised() throws Exception {
        NativeGitRunner runner = new NativeGitRunner(repoDir);
        writeFile("a.txt", "a\n");
        backend.stageAll();
        backend.commit("base", false);

        runner.run("checkout", "-b", "feature");
        writeFile("b.txt", "b\n");
        backend.stageAll();
        backend.commit("feature work", false);
        runner.run("checkout", "-");
        runner.run("merge", "--no-ff", "feature", "-m", "merge feature");

        String head = backend.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();
        var position = backend.getCommitPosition(head);
        assertTrue(position.merge(), "a --no-ff merge has two parents");
        assertFalse(position.root());
    }

    @Test
    void unknownCommitsReportThemselvesAsUnknown() {
        var position = backend.getCommitPosition("0123456789abcdef0123456789abcdef01234567");
        assertFalse(position.known(),
                "an unreadable commit must not claim to be on the current branch");
    }

    @Test
    void reflogCarriesBothEndsOfEachMove() throws Exception {
        writeFile("f.txt", "one\n");
        backend.stageAll();
        backend.commit("first", false);
        writeFile("f.txt", "two\n");
        backend.stageAll();
        backend.commit("second", false);

        var reflog = backend.getReflog(10);
        assertFalse(reflog.isEmpty());

        var newest = reflog.get(0);
        assertNotNull(newest.newHash());
        assertNotEquals(newest.oldHash(), newest.newHash(),
                "a move must record where it came from, not repeat where it went");

        var oldest = reflog.get(reflog.size() - 1);
        assertTrue(oldest.isBranchCreation(),
                "the earliest entry has nothing before it");
    }

    @Test
    void worktreesAreListedRatherThanReportedAsAbsent() throws Exception {
        writeFile("f.txt", "x\n");
        backend.stageAll();
        backend.commit("base", false);

        var worktrees = backend.getWorktrees();
        assertEquals(1, worktrees.size(), "the main working tree is still a worktree");
        assertTrue(worktrees.get(0).main());
        assertFalse(backend.getWorktrees().size() > 1);

        File linked = new File(tempDir.toFile().getParentFile(), "linked-" + System.nanoTime());
        assertTrue(backend.addWorktree(linked, "wt-branch", true).isOk());
        try {
            var afterAdd = backend.getWorktrees();
            assertEquals(2, afterAdd.size());
            assertTrue(backend.getWorktrees().size() > 1);
            assertTrue(afterAdd.stream().anyMatch(w -> "wt-branch".equals(w.branch())));
        } finally {
            backend.removeWorktree(linked, true);
        }
    }

    /** An omitted flag must be left out, not passed as an empty argument. */
    @Test
    void removingAWorktreeWithoutForceDoesNotPassAnEmptyArgument() throws Exception {
        writeFile("f.txt", "x\n");
        backend.stageAll();
        backend.commit("base", false);

        File linked = new File(tempDir.toFile().getParentFile(), "linked2-" + System.nanoTime());
        assertTrue(backend.addWorktree(linked, "wt2", true).isOk());

        var result = backend.removeWorktree(linked, false);
        assertTrue(result.isOk(), () -> "remove failed: " + result.getMessage());
        assertEquals(1, backend.getWorktrees().size());
    }
    @Test
    void headDetachedReflectsACheckedOutCommitThroughTheCli() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        NativeGitRunner git = new NativeGitRunner(repoDir);
        assertTrue(git.run("add", "a.txt").isOk());
        assertTrue(git.run("commit", "-m", "first").isOk());
        String first;
        try (org.eclipse.jgit.api.Git jgit = org.eclipse.jgit.api.Git.open(tempDir.toFile())) {
            first = jgit.getRepository().resolve("HEAD").getName();
        }

        assertFalse(backend.isHeadDetached(), "attached after init");

        var res = git.run("checkout", first);
        assertTrue(res.isOk(), () -> "checkout failed: " + res.stderr());
        assertTrue(backend.isHeadDetached(), "detached on the checked-out commit");

        assertTrue(git.run("checkout", "-b", "rescued").isOk());
        assertFalse(backend.isHeadDetached(), "re-attached on the new branch");
    }

    @Test
    void detachedHeadOrphanRiskIsFalseWhileAttached() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        NativeGitRunner git = new NativeGitRunner(repoDir);
        git.run("add", "a.txt");
        git.run("commit", "-m", "first");

        assertFalse(backend.isDetachedHeadOrphanRisk(), "attached HEAD is never at risk");
    }

    @Test
    void detachedHeadOnACommitNoRefReachesIsAnOrphanRiskThroughTheCli() throws Exception {
        NativeGitRunner git = new NativeGitRunner(repoDir);
        Files.writeString(tempDir.resolve("a.txt"), "a");
        git.run("add", "a.txt");
        git.run("commit", "-m", "first");

        String first;
        try (org.eclipse.jgit.api.Git jgit = org.eclipse.jgit.api.Git.open(tempDir.toFile())) {
            first = jgit.getRepository().resolve("HEAD").getName();
        }

        // Detach, then commit here: this new commit is on no branch and no tag,
        // unlike checking out an older commit that main still contains.
        assertTrue(git.run("checkout", first).isOk());
        Files.writeString(tempDir.resolve("b.txt"), "b");
        git.run("add", "b.txt");
        git.run("commit", "-m", "orphan candidate");

        assertTrue(backend.isDetachedHeadOrphanRisk(),
                "no branch or tag is an ancestor of a commit made only while detached");
    }

    @Test
    void detachedHeadStillReachableFromATagIsNotAnOrphanRiskThroughTheCli() throws Exception {
        NativeGitRunner git = new NativeGitRunner(repoDir);
        Files.writeString(tempDir.resolve("a.txt"), "a");
        git.run("add", "a.txt");
        git.run("commit", "-m", "first");

        String tagged;
        try (org.eclipse.jgit.api.Git jgit = org.eclipse.jgit.api.Git.open(tempDir.toFile())) {
            tagged = jgit.getRepository().resolve("HEAD").getName();
        }
        assertTrue(git.run("tag", "keepsake", tagged).isOk());
        Files.writeString(tempDir.resolve("b.txt"), "b");
        git.run("add", "b.txt");
        git.run("commit", "-m", "second");

        assertTrue(git.run("checkout", tagged).isOk());
        assertFalse(backend.isDetachedHeadOrphanRisk(),
                "the tag still reaches this commit, so leaving it costs nothing");
    }


    @Test
    void discardAllChangesThroughTheCliRestoresAndCleans() throws Exception {
        NativeGitRunner git = new NativeGitRunner(repoDir);
        // Two tracked files at HEAD.
        Files.writeString(tempDir.resolve("a.txt"), "original a");
        assertTrue(git.run("add", "a.txt").isOk());
        assertTrue(git.run("commit", "-m", "first").isOk());

        // Modify one tracked file, delete another, drop an untracked one in.
        Files.writeString(tempDir.resolve("a.txt"), "modified");
        Files.writeString(tempDir.resolve("b.txt"), "doomed");
        assertTrue(git.run("add", "b.txt").isOk());
        assertTrue(git.run("commit", "-m", "second").isOk());
        Files.delete(tempDir.resolve("b.txt"));
        Files.writeString(tempDir.resolve("temp.txt"), "untracked scratch");

        var result = backend.discardAllChanges();
        assertTrue(result.isOk(), () -> "discardAll failed: " + result.getMessage());
        assertEquals("original a", Files.readString(tempDir.resolve("a.txt")),
                "the tracked modification must be restored");
        assertEquals("doomed", Files.readString(tempDir.resolve("b.txt")),
                "the deleted tracked file must come back");
        assertFalse(Files.exists(tempDir.resolve("temp.txt")),
                "untracked files must be removed");
    }

    // ------------------------------------------------------------------
    // fetchAllSilently (background auto-fetch)
    // ------------------------------------------------------------------

    /**
     * {@link NativeGitBackend#fetchAllSilently()} runs the same fetch with every prompt
     * switched off ({@link AskpassBridge#silentEnvironment}). This proves that
     * environment still lets a fetch that needs no prompt through.
     */
    @Test
    void fetchAllSilentlySucceedsAgainstARemoteThatNeedsNoCredentials() throws Exception {
        Path remoteDir = Files.createTempDirectory("lyra-native-silent-fetch-remote");
        try {
            NativeGitRunner remoteInit = new NativeGitRunner(remoteDir.toFile());
            assertTrue(remoteInit.run("init", "--bare").isOk());

            writeFile("a.txt", "a\n");
            backend.stageAll();
            assertTrue(backend.commit("first", false).isOk());
            assertTrue(backend.addRemote("origin", remoteDir.toString()).isOk());
            NativeGitRunner git = new NativeGitRunner(repoDir);
            var pushResult = git.run("push", "-u", "origin", "HEAD");
            assertTrue(pushResult.isOk(), () -> "push failed: " + pushResult.stderr());

            var result = backend.fetchAllSilently();
            assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    /**
     * R5: a fetch whose only rejections are tags that would clobber local ones still
     * fetched every branch, and says which tags it left alone instead of failing.
     */
    @Test
    void aFetchThatOnlyClobberedTagsIsReportedAsFetched() {
        var clobbered = new NativeGitRunner.Result(1, new byte[0],
                "From ../remote\n ! [rejected]        v1.0       -> v1.0  (would clobber existing tag)\n"
                        + "error: could not fetch origin");
        var broken = new NativeGitRunner.Result(128, new byte[0],
                "fatal: 'nowhere' does not appear to be a git repository");

        GitResult tagsOnly = NativeGitBackend.classifyFetch(clobbered);
        assertTrue(tagsOnly.isOk(), tagsOnly.getMessage());
        assertTrue(tagsOnly.getMessage().contains("v1.0"), tagsOnly.getMessage());
        assertFalse(NativeGitBackend.classifyFetch(broken).isOk());
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    /** See {@code GitServiceTest.aNonNullMessageProducesARealAnnotatedTagObject} - same shape, CLI idiom. */
    @Test
    void aNonNullMessageProducesARealAnnotatedTagObject() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", "release notes", "HEAD").isOk());

        NativeGitRunner git = new NativeGitRunner(repoDir);
        var type = git.run("cat-file", "-t", "refs/tags/v1.0");
        assertTrue(type.isOk());
        assertEquals("tag", type.stdout().trim());

        var message = git.run("tag", "-l", "-n99", "v1.0");
        assertTrue(message.stdout().contains("release notes"));
    }

    /** See {@code GitServiceTest.aNullMessageProducesALightweightTagPointingDirectlyAtTheCommit} - same shape, CLI idiom. */
    @Test
    void aNullMessageProducesALightweightTagPointingDirectlyAtTheCommit() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", null, "HEAD").isOk());

        NativeGitRunner git = new NativeGitRunner(repoDir);
        var type = git.run("cat-file", "-t", "refs/tags/v1.0");
        assertTrue(type.isOk());
        assertEquals("commit", type.stdout().trim());
    }

    /**
     * See {@code GitServiceTest.deleteRemoteTagRemovesItFromTheRemoteButKeepsTheLocalTag} -
     * same shape, CLI idiom: an empty source refspec ({@code push origin :refs/tags/<name>}).
     */
    @Test
    void deleteRemoteTagRemovesItFromTheRemoteButKeepsTheLocalTag() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", null, "HEAD").isOk());

        Path remoteDir = Files.createTempDirectory("lyra-native-delete-remote-tag");
        try {
            NativeGitRunner remoteInit = new NativeGitRunner(remoteDir.toFile());
            assertTrue(remoteInit.run("init", "--bare").isOk());
            assertTrue(backend.addRemote("origin", remoteDir.toString()).isOk());
            NativeGitRunner git = new NativeGitRunner(repoDir);
            assertTrue(git.run("push", "-u", "origin", "HEAD").isOk());
            assertTrue(git.run("push", "origin", "v1.0").isOk());

            assertTrue(remoteInit.run("cat-file", "-e", "refs/tags/v1.0").isOk());

            var result = backend.deleteRemoteTag("origin", "v1.0");
            assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());

            assertFalse(remoteInit.run("cat-file", "-e", "refs/tags/v1.0").isOk());
            assertTrue(git.run("cat-file", "-e", "refs/tags/v1.0").isOk());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    @Test
    void deleteRemoteTagFailsCleanlyWhenTheRemoteDoesNotExist() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", null, "HEAD").isOk());

        var result = backend.deleteRemoteTag("origin", "v1.0");
        assertFalse(result.isOk());
    }

    /** See {@code GitServiceTest.pushTagPublishesOnlyTheNamedTagToTheRemote} - same shape, CLI idiom. */
    @Test
    void pushTagPublishesOnlyTheNamedTagToTheRemote() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", null, "HEAD").isOk());
        assertTrue(backend.createTag("v2.0", null, "HEAD").isOk());

        Path remoteDir = Files.createTempDirectory("lyra-native-push-tag");
        try {
            NativeGitRunner remoteInit = new NativeGitRunner(remoteDir.toFile());
            assertTrue(remoteInit.run("init", "--bare").isOk());
            assertTrue(backend.addRemote("origin", remoteDir.toString()).isOk());

            var result = backend.pushTag("origin", "v1.0");
            assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());

            assertTrue(remoteInit.run("cat-file", "-e", "refs/tags/v1.0").isOk());
            assertFalse(remoteInit.run("cat-file", "-e", "refs/tags/v2.0").isOk(), "only v1.0 was asked for");
            var branches = remoteInit.run("for-each-ref", "refs/heads/");
            assertTrue(branches.stdout().isBlank(), "pushTag must not also push the current branch");
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    @Test
    void pushTagFailsCleanlyWhenTheRemoteDoesNotExist() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", null, "HEAD").isOk());

        var result = backend.pushTag("origin", "v1.0");
        assertFalse(result.isOk());
    }

    /** See {@code GitServiceTest.movingATagToADescendantCommitIsAFastForwardAndActuallyMovesIt} - same shape, CLI idiom. */
    @Test
    void movingATagToADescendantCommitIsAFastForwardAndActuallyMovesIt() throws Exception {
        writeFile("a.txt", "one\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", null, "HEAD").isOk());
        String original = headHash();

        writeFile("a.txt", "two\n");
        backend.stageAll();
        assertTrue(backend.commit("second", false).isOk());
        String descendant = headHash();

        assertTrue(backend.isTagMoveFastForward("v1.0", descendant));

        var result = backend.moveTag("v1.0", descendant);
        assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());
        assertEquals(descendant, tagTargetHash("v1.0"));
        assertNotEquals(original, tagTargetHash("v1.0"));
    }

    /** See {@code GitServiceTest.movingATagBackwardToAnAncestorIsNotAFastForwardButStillWorksIfAsked}. */
    @Test
    void movingATagBackwardToAnAncestorIsNotAFastForwardButStillWorksIfAsked() throws Exception {
        writeFile("a.txt", "one\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        String ancestor = headHash();

        writeFile("a.txt", "two\n");
        backend.stageAll();
        assertTrue(backend.commit("second", false).isOk());
        assertTrue(backend.createTag("v2.0", null, "HEAD").isOk());

        assertFalse(backend.isTagMoveFastForward("v2.0", ancestor),
                "moving to an earlier commit on the same line of history is not a fast-forward");

        assertTrue(backend.moveTag("v2.0", ancestor).isOk());
        assertEquals(ancestor, tagTargetHash("v2.0"));
    }

    /** See {@code GitServiceTest.movingATagToAnUnrelatedBranchIsNotAFastForward}. */
    @Test
    void movingATagToAnUnrelatedBranchIsNotAFastForward() throws Exception {
        writeFile("a.txt", "one\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        String mainBranch = backend.getCurrentBranch();
        assertTrue(backend.createBranch("side", null, false).isOk());

        writeFile("a.txt", "two\n");
        backend.stageAll();
        assertTrue(backend.commit("second, only on " + mainBranch, false).isOk());
        assertTrue(backend.createTag("v1.0", null, "HEAD").isOk());

        assertTrue(backend.checkoutBranch("side").isOk());
        writeFile("side.txt", "side\n");
        backend.stageAll();
        assertTrue(backend.commit("side commit, diverging from the tag", false).isOk());
        String sideTip = headHash();

        assertFalse(backend.isTagMoveFastForward("v1.0", sideTip),
                "an unrelated commit on a diverging branch is neither an ancestor nor a descendant");
    }

    /** See {@code GitServiceTest.movingAnAnnotatedTagPreservesItsOriginalMessage}. */
    @Test
    void movingAnAnnotatedTagPreservesItsOriginalMessage() throws Exception {
        writeFile("a.txt", "one\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", "release notes", "HEAD").isOk());

        writeFile("a.txt", "two\n");
        backend.stageAll();
        assertTrue(backend.commit("second", false).isOk());
        String descendant = headHash();

        assertTrue(backend.moveTag("v1.0", descendant).isOk());

        NativeGitRunner git = new NativeGitRunner(repoDir);
        var type = git.run("cat-file", "-t", "refs/tags/v1.0");
        assertTrue(type.isOk());
        assertEquals("tag", type.stdout().trim());
        var message = git.run("tag", "-l", "-n99", "v1.0");
        assertTrue(message.stdout().contains("release notes"));
    }

    /** See {@code GitServiceTest.movingALightweightTagStaysLightweight}. */
    @Test
    void movingALightweightTagStaysLightweight() throws Exception {
        writeFile("a.txt", "one\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(backend.createTag("v1.0", null, "HEAD").isOk());

        writeFile("a.txt", "two\n");
        backend.stageAll();
        assertTrue(backend.commit("second", false).isOk());
        String descendant = headHash();

        assertTrue(backend.moveTag("v1.0", descendant).isOk());

        var type = new NativeGitRunner(repoDir).run("cat-file", "-t", "refs/tags/v1.0");
        assertTrue(type.isOk());
        assertEquals("commit", type.stdout().trim());
    }

    private String headHash() {
        return new NativeGitRunner(repoDir).run("rev-parse", "HEAD").trimmedStdout();
    }

    private String tagTargetHash(String tagName) {
        return new NativeGitRunner(repoDir).run("rev-parse", tagName + "^{commit}").trimmedStdout();
    }

    // ------------------------------------------------------------------
    // Sparse Checkout
    // ------------------------------------------------------------------

    private void twoDirsAndATopLevelFileCommitted() throws Exception {
        writeFile("root.txt", "root\n");
        Files.createDirectories(new File(repoDir, "dir1").toPath());
        Files.createDirectories(new File(repoDir, "dir2").toPath());
        writeFile("dir1/a.txt", "a\n");
        writeFile("dir2/b.txt", "b\n");
        backend.stageAll();
        assertTrue(backend.commit("add two dirs and a root file", false).isOk());
    }

    @Test
    void sparseCheckoutIsDisabledByDefault() throws Exception {
        twoDirsAndATopLevelFileCommitted();

        assertFalse(backend.isSparseCheckoutEnabled());
        assertTrue(backend.getSparseCheckoutPaths().isEmpty());
    }

    @Test
    void settingSparseCheckoutPathsRestrictsTheWorkingTreeToThem() throws Exception {
        twoDirsAndATopLevelFileCommitted();

        var result = backend.setSparseCheckoutPaths(List.of("dir1"));
        assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());

        assertTrue(backend.isSparseCheckoutEnabled());
        assertTrue(new File(repoDir, "dir1").isDirectory(), "dir1 was asked for");
        assertFalse(new File(repoDir, "dir2").exists(), "dir2 was not asked for");
        assertTrue(new File(repoDir, "root.txt").exists(),
                "cone mode always keeps top-level files regardless of which directories are chosen");
    }

    @Test
    void getSparseCheckoutPathsReportsWhatWasActuallySet() throws Exception {
        twoDirsAndATopLevelFileCommitted();
        assertTrue(backend.setSparseCheckoutPaths(List.of("dir1")).isOk());

        assertEquals(List.of("dir1"), backend.getSparseCheckoutPaths());
    }

    @Test
    void disablingSparseCheckoutRestoresTheFullWorkingTree() throws Exception {
        twoDirsAndATopLevelFileCommitted();
        assertTrue(backend.setSparseCheckoutPaths(List.of("dir1")).isOk());
        assertFalse(new File(repoDir, "dir2").exists(), "sanity check: dir2 really was narrowed away");

        var result = backend.disableSparseCheckout();
        assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());

        assertFalse(backend.isSparseCheckoutEnabled());
        assertTrue(new File(repoDir, "dir2").exists(), "the full tree should be back");
    }

    @Test
    void settingSparseCheckoutPathsWithAnEmptyListFailsCleanlyRatherThanNarrowingToNothing() throws Exception {
        twoDirsAndATopLevelFileCommitted();

        var result = backend.setSparseCheckoutPaths(List.of());
        assertFalse(result.isOk());
        assertFalse(backend.isSparseCheckoutEnabled(), "a refused call must not have enabled anything");
    }

    // ------------------------------------------------------------------
    // Remote URLs and pull-request checkout
    // ------------------------------------------------------------------

    @Test
    void getRemoteUrlReturnsTheConfiguredUri() {
        assertTrue(backend.addRemote("origin", "https://example.invalid/repo.git").isOk());
        assertEquals("https://example.invalid/repo.git", backend.getRemoteUrl("origin"));
    }

    @Test
    void getRemoteUrlIsNullForAnUnknownRemote() {
        assertNull(backend.getRemoteUrl("origin"));
    }

    /** See {@code GitServiceTest.checkoutPullRequestFetchesTheHeadRefAndChecksItOutAsALocalBranch} - same shape, CLI idiom. */
    @Test
    void checkoutPullRequestFetchesTheHeadRefAndChecksItOutAsALocalBranch() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        String first = resolveHead();

        writeFile("b.txt", "b\n");
        backend.stageAll();
        assertTrue(backend.commit("pr head", false).isOk());
        String prHead = resolveHead();

        Path remoteDir = Files.createTempDirectory("lyra-native-pr-remote");
        try {
            NativeGitRunner remoteInit = new NativeGitRunner(remoteDir.toFile());
            assertTrue(remoteInit.run("init", "--bare").isOk());
            assertTrue(backend.addRemote("origin", remoteDir.toString()).isOk());
            var pushResult = new NativeGitRunner(repoDir).run("push", "-u", "origin", "HEAD");
            assertTrue(pushResult.isOk(), () -> "push failed: " + pushResult.stderr());

            var refUpdate = remoteInit.run("update-ref", "refs/pull/7/head", prHead);
            assertTrue(refUpdate.isOk(), () -> "update-ref failed: " + refUpdate.stderr());

            assertTrue(backend.reset(first, ResetMode.HARD).isOk());

            var checkout = backend.checkoutPullRequest("origin", 7, false);
            assertTrue(checkout.isOk(), () -> "expected success, got: " + checkout.getMessage());
            assertEquals("pr-7", backend.getCurrentBranch());
            assertEquals(prHead, resolveHead());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    /** See {@code GitServiceTest.checkoutRemoteRefFetchesAnArbitraryRefFromAConfiguredRemote} - same shape, CLI idiom. */
    @Test
    void checkoutRemoteRefFetchesAnArbitraryRefFromAConfiguredRemote() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        String first = resolveHead();

        writeFile("b.txt", "b\n");
        backend.stageAll();
        assertTrue(backend.commit("mr head", false).isOk());
        String mrHead = resolveHead();

        Path remoteDir = Files.createTempDirectory("lyra-native-mr-remote");
        try {
            NativeGitRunner remoteInit = new NativeGitRunner(remoteDir.toFile());
            assertTrue(remoteInit.run("init", "--bare").isOk());
            assertTrue(backend.addRemote("origin", remoteDir.toString()).isOk());
            var pushResult = new NativeGitRunner(repoDir).run("push", "-u", "origin", "HEAD");
            assertTrue(pushResult.isOk(), () -> "push failed: " + pushResult.stderr());

            var refUpdate = remoteInit.run("update-ref", "refs/merge-requests/7/head", mrHead);
            assertTrue(refUpdate.isOk(), () -> "update-ref failed: " + refUpdate.stderr());

            assertTrue(backend.reset(first, ResetMode.HARD).isOk());

            var checkout = backend.checkoutRemoteRef("origin", "refs/merge-requests/7/head", "mr-7", false);
            assertTrue(checkout.isOk(), () -> "expected success, got: " + checkout.getMessage());
            assertEquals("mr-7", backend.getCurrentBranch());
            assertEquals(mrHead, resolveHead());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    /** See {@code GitServiceTest.checkoutRemoteRefCanFetchDirectlyFromARawUrlWithNoConfiguredRemote} - same shape, CLI idiom. */
    @Test
    void checkoutRemoteRefCanFetchDirectlyFromARawUrlWithNoConfiguredRemote() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());

        Path forkDir = Files.createTempDirectory("lyra-native-fork-remote");
        try {
            NativeGitRunner forkInit = new NativeGitRunner(forkDir.toFile());
            var initRes = forkInit.run("init", "-b", "feature");
            if (!initRes.isOk()) forkInit.run("init");
            forkInit.run("config", "user.name", "Fork Author");
            forkInit.run("config", "user.email", "fork@example.com");
            Files.writeString(forkDir.resolve("feature.txt"), "feature\n");
            forkInit.run("add", "feature.txt");
            assertTrue(forkInit.run("commit", "-m", "feature work").isOk());
            var branchRes = forkInit.run("branch", "--show-current");
            assertTrue(branchRes.isOk());
            String forkBranch = branchRes.trimmedStdout();
            String forkHead = new NativeGitRunner(forkDir.toFile()).run("rev-parse", "HEAD").trimmedStdout();

            var checkout = backend.checkoutRemoteRef(
                    forkDir.toString(), "refs/heads/" + forkBranch, "pr-99", false);
            assertTrue(checkout.isOk(), () -> "expected success, got: " + checkout.getMessage());
            assertEquals("pr-99", backend.getCurrentBranch());
            assertEquals(forkHead, resolveHead());
        } finally {
            deleteRecursively(forkDir);
        }
    }

    /** See {@code GitServiceTest.fetchRemoteRefMakesTheCommitResolvableWithoutTouchingHeadOrTheWorkingTree} - same shape, CLI idiom. */
    @Test
    void fetchRemoteRefMakesTheCommitResolvableWithoutTouchingHeadOrTheWorkingTree() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        String originalHead = resolveHead();
        String originalBranch = backend.getCurrentBranch();

        Path remoteDir = Files.createTempDirectory("lyra-native-fetch-only-remote");
        Path otherClone = Files.createTempDirectory("lyra-native-fetch-only-other-clone");
        try {
            NativeGitRunner remoteInit = new NativeGitRunner(remoteDir.toFile());
            assertTrue(remoteInit.run("init", "--bare").isOk());
            assertTrue(backend.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new NativeGitRunner(repoDir).run("push", "-u", "origin", "HEAD").isOk());

            NativeGitRunner other = new NativeGitRunner(otherClone.toFile());
            assertTrue(other.run("clone", remoteDir.toString(), ".").isOk());
            // The bare remote's own HEAD guess (e.g. "master") can mismatch whichever
            // branch was actually pushed (this suite inits on "main") - checked out
            // explicitly rather than trusting what the clone happened to land on.
            assertTrue(other.run("checkout", "-B", originalBranch, "origin/" + originalBranch).isOk());
            other.run("config", "user.name", "Other Author");
            other.run("config", "user.email", "other@example.com");
            Files.writeString(otherClone.resolve("pr.txt"), "pr change\n");
            other.run("add", "pr.txt");
            assertTrue(other.run("commit", "-m", "pr commit").isOk());
            assertTrue(other.run("push").isOk());
            String remoteOnlyCommit = other.run("rev-parse", "HEAD").trimmedStdout();

            var fetch = backend.fetchRemoteRef("origin", "refs/heads/" + originalBranch);
            assertTrue(fetch.isOk(), () -> "expected success, got: " + fetch.getMessage());

            assertEquals(remoteOnlyCommit,
                    new NativeGitRunner(repoDir).run("rev-parse",
                            GitBackend.INTERNAL_REF_PREFIX + "review/fetched").trimmedStdout(),
                    "the fetched commit should be resolvable");
            assertEquals(originalHead, resolveHead(), "HEAD must not move");
            assertEquals(originalBranch, backend.getCurrentBranch(), "the current branch must not change");
            assertFalse(new File(repoDir, "pr.txt").exists(),
                    "the working tree must not be touched - this was a fetch, not a checkout");
        } finally {
            deleteRecursively(remoteDir);
            deleteRecursively(otherClone);
        }
    }

    /**
     * B5. Checking out a remote branch whose local counterpart already exists is the
     * ordinary case - the user checked it out before. {@code git checkout -b} refuses
     * outright there, so the reference engine's "reuse the branch you already have" is
     * what has to happen.
     */
    @Test
    void checkingOutARemoteBranchTwiceReusesTheLocalBranch() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        String branch = backend.getCurrentBranch();

        Path remoteDir = Files.createTempDirectory("lyra-native-recheckout-remote");
        try {
            assertTrue(new NativeGitRunner(remoteDir.toFile()).run("init", "--bare").isOk());
            assertTrue(backend.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new NativeGitRunner(repoDir).run("push", "-u", "origin", "HEAD").isOk());
            assertTrue(backend.createBranch("elsewhere", null, true).isOk());
            assertTrue(backend.deleteBranch(branch, true).isOk());

            var first = backend.checkoutRemoteBranch("origin/" + branch);
            assertTrue(first.isOk(), () -> "expected success, got: " + first.getMessage());
            assertEquals(branch, backend.getCurrentBranch());

            assertTrue(backend.checkoutBranch("elsewhere").isOk());
            var second = backend.checkoutRemoteBranch("origin/" + branch);

            assertTrue(second.isOk(),
                    () -> "the local branch already exists, which is not an error: " + second.getMessage());
            assertEquals(branch, backend.getCurrentBranch());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    /**
     * B19. {@code getCurrentBranch()} answers with a short hash when HEAD is detached, and
     * pushing that produces a refspec naming a branch after a commit id. The user gets a
     * cryptic transport error where the real problem is one sentence long.
     */
    @Test
    void pushingFromADetachedHeadSaysWhyRatherThanFailingCryptically() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());
        assertTrue(new NativeGitRunner(repoDir).run("checkout", "--detach", "HEAD").isOk());
        assertTrue(backend.isHeadDetached(), "the fixture should be detached now");

        GitResult result = new RepositoryCommands(backend).push("origin", false, false, false);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("detached"),
                () -> "the message should name the real problem: " + result.getMessage());
    }

    /**
     * B18. Native commands pass user data as argv and never through a shell, but git
     * parses a leading-dash positional as an option. Where no {@code --} separator is
     * available - {@code git checkout <ref>} - the name is refused with a clear message.
     */
    @Test
    void aBranchNameThatLooksLikeAnOptionIsRefused() throws Exception {
        writeFile("a.txt", "a\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());

        GitResult result = backend.checkoutBranch("--orphan");

        assertFalse(result.isOk(), "a leading dash must not reach git as a positional");
        assertTrue(result.getMessage().contains("-"),
                () -> "the message should quote what was refused: " + result.getMessage());
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // Best-effort cleanup.
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Submodule auto-update (AppPreferences.autoUpdateSubmodules)
    // ------------------------------------------------------------------

    /** See {@code GitServiceTest.createDivergingSubmoduleScenario} - same shape, CLI idiom. */
    private String createDivergingSubmoduleScenario(String mainBranch) throws Exception {
        Path submoduleSource = Files.createTempDirectory("lyra-native-automod-source");
        try {
            NativeGitRunner subRunner = new NativeGitRunner(submoduleSource.toFile());
            assertTrue(subRunner.run("init", "-b", "main").isOk());
            subRunner.run("config", "user.name", "Test User");
            subRunner.run("config", "user.email", "test@example.com");
            Files.writeString(submoduleSource.resolve("a.txt"), "a");
            assertTrue(subRunner.run("add", "a.txt").isOk());
            assertTrue(subRunner.run("commit", "-m", "commit A").isOk());

            // Permit file transport for this one disposable-fixture command. The tests
            // below exercise branch checkout behavior; they must not rely on a user's
            // global protocol.file.allow setting.
            var addRes = new NativeGitRunner(repoDir).run(
                    "-c", "protocol.file.allow=always", "submodule", "add", "--",
                    submoduleSource.toAbsolutePath().toString(), "libs/sub");
            assertTrue(addRes.isOk(), () -> "could not create the temporary submodule fixture: "
                    + addRes.stderr());
            backend.stageAll();
            assertTrue(backend.commit("add submodule", false).isOk());

            assertTrue(backend.createBranch("feature", null, true).isOk());
            File subDirOnDisk = new File(repoDir, "libs/sub");
            NativeGitRunner subDirRunner = new NativeGitRunner(subDirOnDisk);
            Files.writeString(subDirOnDisk.toPath().resolve("b.txt"), "b");
            assertTrue(subDirRunner.run("add", "b.txt").isOk());
            assertTrue(subDirRunner.run("commit", "-m", "commit B").isOk());
            String advancedCommit = subDirRunner.run("rev-parse", "HEAD").trimmedStdout();

            backend.stageAll();
            assertTrue(backend.commit("advance submodule", false).isOk());
            assertTrue(backend.checkoutBranch(mainBranch).isOk());
            return advancedCommit;
        } finally {
            deleteRecursively(submoduleSource);
        }
    }

    private String submoduleHead() {
        return new NativeGitRunner(new File(repoDir, "libs/sub")).run("rev-parse", "HEAD").trimmedStdout();
    }

    @Test
    void checkoutLeavesTheSubmoduleStaleWhenAutoUpdateIsOff() throws Exception {
        writeFile("base.txt", "base");
        backend.stageAll();
        assertTrue(backend.commit("base commit", false).isOk());
        String mainBranch = backend.getCurrentBranch();
        String advancedCommit = createDivergingSubmoduleScenario(mainBranch);

        assertFalse(AppPreferences.autoUpdateSubmodules(), "off by default");
        assertEquals(advancedCommit, submoduleHead(),
                "a plain checkout never touches the submodule's own working tree");
    }

    @Test
    void checkoutAutomaticallyUpdatesTheSubmoduleWhenThePreferenceIsOn() throws Exception {
        writeFile("base.txt", "base");
        backend.stageAll();
        assertTrue(backend.commit("base commit", false).isOk());
        String mainBranch = backend.getCurrentBranch();

        AppPreferences.setAutoUpdateSubmodules(true);
        try {
            String advancedCommit = createDivergingSubmoduleScenario(mainBranch);

            assertNotEquals(advancedCommit, submoduleHead(),
                    "auto-update should have brought the submodule back to what this branch recorded");
        } finally {
            AppPreferences.setAutoUpdateSubmodules(false);
        }
    }

    @Test
    void aRepositoryWithNoSubmodulesIsUnaffectedEitherWay() throws Exception {
        writeFile("base.txt", "base");
        backend.stageAll();
        assertTrue(backend.commit("base commit", false).isOk());
        String mainBranch = backend.getCurrentBranch();
        assertTrue(backend.createBranch("feature", null, true).isOk());
        writeFile("feature.txt", "feature");
        backend.stageAll();
        assertTrue(backend.commit("feature commit", false).isOk());

        AppPreferences.setAutoUpdateSubmodules(true);
        try {
            assertTrue(backend.checkoutBranch(mainBranch).isOk());
        } finally {
            AppPreferences.setAutoUpdateSubmodules(false);
        }
    }

    // ------------------------------------------------------------------
    // Bisect
    // ------------------------------------------------------------------

    /** See {@code GitServiceTest.commitChainWithABugPartway} - same shape, CLI idiom. */
    private String commitChainWithABugPartway(int count, int firstBadIndex) throws Exception {
        String firstBadHash = null;
        for (int i = 0; i < count; i++) {
            String marker = i >= firstBadIndex ? "bad" : "good";
            writeFile("f.txt", marker + " " + i + "\n");
            backend.stageAll();
            assertTrue(backend.commit("commit " + i, false).isOk());
            if (i == firstBadIndex) {
                firstBadHash = resolveHead();
            }
        }
        return firstBadHash;
    }

    private String resolveHead() {
        var res = new NativeGitRunner(repoDir).run("rev-parse", "HEAD");
        assertTrue(res.isOk());
        return res.trimmedStdout();
    }

    private BisectStatus runBisectToCompletion(String goodHash, String badHash) throws Exception {
        assertTrue(backend.bisectStart().isOk());
        BisectStatus status = backend.bisectMarkBad(badHash);
        assertTrue(status.active());
        status = backend.bisectMarkGood(goodHash);

        int guard = 0;
        while (!status.done()) {
            assertTrue(++guard < 50, "bisect did not converge - likely an infinite loop in pickNext");
            String content = Files.readString(new File(repoDir, "f.txt").toPath()).strip();
            status = content.startsWith("bad") ? backend.bisectMarkBad(status.currentCommitHash())
                    : backend.bisectMarkGood(status.currentCommitHash());
        }
        return status;
    }

    @Test
    void bisectFindsTheExactFirstBadCommitOnALinearHistoryThroughTheCli() throws Exception {
        writeFile("f.txt", "seed\n");
        backend.stageAll();
        assertTrue(backend.commit("seed", false).isOk());
        String goodHash = resolveHead();

        String firstBad = commitChainWithABugPartway(15, 7);
        String badHash = resolveHead();

        BisectStatus result = runBisectToCompletion(goodHash, badHash);

        assertTrue(result.done());
        assertEquals(firstBad, result.currentCommitHash(),
                "bisect should converge on exactly the commit that introduced the bug");

        assertTrue(backend.bisectReset().isOk());
        assertFalse(backend.isHeadDetached());
    }

    @Test
    void bisectWithOnlyABadMarkWaitsForAGoodOneThroughTheCli() throws Exception {
        commitChainWithABugPartway(5, 2);
        assertTrue(backend.bisectStart().isOk());

        BisectStatus status = backend.bisectMarkBad(resolveHead());

        assertTrue(status.active());
        assertFalse(status.done());
        assertNull(status.currentCommitHash(), "no candidate exists until a good commit is also marked");
    }

    @Test
    void bisectResetReturnsToTheOriginalBranchThroughTheCli() throws Exception {
        String originalBranch = backend.getCurrentBranch();
        commitChainWithABugPartway(5, 2);
        String badHash = resolveHead();

        assertTrue(backend.bisectStart().isOk());
        backend.bisectMarkBad(badHash);

        GitResult reset = backend.bisectReset();

        assertTrue(reset.isOk());
        assertEquals(originalBranch, backend.getCurrentBranch());
        assertFalse(backend.isHeadDetached());
    }

    @Test
    void bisectSkipTriesADifferentCandidateThroughTheCli() throws Exception {
        writeFile("f.txt", "seed\n");
        backend.stageAll();
        assertTrue(backend.commit("seed", false).isOk());
        String goodHash = resolveHead();
        commitChainWithABugPartway(11, 6);
        String badHash = resolveHead();

        assertTrue(backend.bisectStart().isOk());
        backend.bisectMarkBad(badHash);
        BisectStatus first = backend.bisectMarkGood(goodHash);
        String firstCandidate = first.currentCommitHash();

        BisectStatus afterSkip = backend.bisectSkip();

        assertNotEquals(firstCandidate, afterSkip.currentCommitHash(),
                "skipping must move to a different candidate");
    }

    // ------------------------------------------------------------------
    // Signature status
    // ------------------------------------------------------------------

    @Test
    void anOrdinaryCommitHasNoSignatureThroughTheCli() throws Exception {
        writeFile("a.txt", "x\n");
        backend.stageAll();
        assertTrue(backend.commit("plain commit", false).isOk());
        String head = resolveHead();

        assertEquals(com.jfx.git.model.SignatureStatus.NONE, backend.getSignatureStatus(head));
    }

    @Test
    void signatureStatusOfANonexistentCommitIsNoneThroughTheCli() {
        assertEquals(com.jfx.git.model.SignatureStatus.NONE,
                backend.getSignatureStatus("0000000000000000000000000000000000000000"));
    }

    // ------------------------------------------------------------------
    // Profiles: applying an identity to this repository's local config
    // ------------------------------------------------------------------

    @Test
    void setLocalIdentityWritesToTheRepositorysOwnConfigThroughTheCli() {
        assertTrue(backend.setLocalIdentity("Alice", "alice@work.com", "", false).isOk());

        var name = new NativeGitRunner(repoDir).run("config", "--get", "user.name");
        var email = new NativeGitRunner(repoDir).run("config", "--get", "user.email");
        assertEquals("Alice", name.trimmedStdout());
        assertEquals("alice@work.com", email.trimmedStdout());
    }

    @Test
    void aCommitMadeAfterApplyingAnIdentityCarriesThatIdentityThroughTheCli() throws Exception {
        assertTrue(backend.setLocalIdentity("Alice", "alice@work.com", "", false).isOk());

        writeFile("a.txt", "x\n");
        backend.stageAll();
        assertTrue(backend.commit("as alice", false).isOk());

        var res = new NativeGitRunner(repoDir).run("log", "-1", "--format=%an\t%ae");
        assertEquals("Alice\talice@work.com", res.trimmedStdout());
    }

    @Test
    void aBlankSigningKeyUnsetsAnyPreviouslyStoredOneThroughTheCli() {
        assertTrue(backend.setLocalIdentity("Alice", "alice@work.com", "KEY123", true).isOk());
        assertTrue(backend.setLocalIdentity("Alice", "alice@work.com", "", false).isOk());

        var key = new NativeGitRunner(repoDir).run("config", "--get", "user.signingkey");
        var sign = new NativeGitRunner(repoDir).run("config", "--get", "commit.gpgsign");
        assertFalse(key.isOk(), "an unset key must report as not found, not as empty");
        assertEquals("false", sign.trimmedStdout());
    }

    // ------------------------------------------------------------------
    // getHeadHash / getCommitsSince - chaining a rebase over a batch's new commits
    // ------------------------------------------------------------------

    @Test
    void getHeadHashMatchesWhatWasJustCommittedThroughTheCli() throws Exception {
        writeFile("a.txt", "x\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());

        assertEquals(resolveHead(), backend.getHeadHash());
    }

    @Test
    void getCommitsSinceReturnsNothingWhenNothingWasAddedThroughTheCli() throws Exception {
        writeFile("a.txt", "x\n");
        backend.stageAll();
        assertTrue(backend.commit("first", false).isOk());

        assertTrue(backend.getCommitsSince(backend.getHeadHash()).isEmpty());
    }

    @Test
    void getCommitsSinceReturnsNewCommitsOldestFirstThroughTheCli() throws Exception {
        writeFile("a.txt", "x\n");
        backend.stageAll();
        assertTrue(backend.commit("base", false).isOk());
        String base = backend.getHeadHash();

        writeFile("b.txt", "y\n");
        backend.stageAll();
        assertTrue(backend.commit("second", false).isOk());
        writeFile("c.txt", "z\n");
        backend.stageAll();
        assertTrue(backend.commit("third", false).isOk());

        var since = backend.getCommitsSince(base);
        assertEquals(2, since.size());
        assertEquals("second", since.get(0).getSubject(), "oldest of the new commits comes first");
        assertEquals("third", since.get(1).getSubject());
    }

    @Test
    void getCommitsSinceAnUnresolvableAncestorIsEmptyThroughTheCli() {
        assertTrue(backend.getCommitsSince("0000000000000000000000000000000000000000").isEmpty());
        assertTrue(backend.getCommitsSince(null).isEmpty());
    }

    /**
     * The exact shape a reword/squash follow-up to a cherry-pick needs: the new commits
     * can only be found by walking from the pre-pick HEAD forward, since cherry-picking
     * gives each one a new hash.
     */
    @Test
    void getCommitsSinceRecoversCherryPickedCommitsByTheirNewHashesThroughTheCli() throws Exception {
        writeFile("base.txt", "b\n");
        backend.stageAll();
        assertTrue(backend.commit("base", false).isOk());
        String trunk = backend.getCurrentBranch();

        assertTrue(backend.createBranch("feature", null, true).isOk());
        writeFile("f1.txt", "1\n");
        backend.stageAll();
        assertTrue(backend.commit("feature one", false).isOk());
        String featureFirst = backend.getHeadHash();
        writeFile("f2.txt", "2\n");
        backend.stageAll();
        assertTrue(backend.commit("feature two", false).isOk());
        String featureSecond = backend.getHeadHash();

        assertTrue(backend.checkoutBranch(trunk).isOk());
        writeFile("trunk-only.txt", "t\n");
        backend.stageAll();
        assertTrue(backend.commit("trunk moved on", false).isOk());
        String preCherryPickHead = backend.getHeadHash();

        assertTrue(new RepositoryCommands(backend).cherryPickAll(List.of(featureFirst, featureSecond), java.util.Map.of()).isOk());

        var recovered = backend.getCommitsSince(preCherryPickHead);
        assertEquals(2, recovered.size());
        assertEquals("feature one", recovered.get(0).getSubject());
        assertEquals("feature two", recovered.get(1).getSubject());
        assertNotEquals(featureFirst, recovered.get(0).getHash(),
                "a cherry-picked commit gets a new hash, not the original's");
    }

    // ------------------------------------------------------------------
    // LFS detection: .gitattributes-based path tracking, through the CLI
    // ------------------------------------------------------------------

    private void commitGitattributes(String rule) throws Exception {
        writeFile(".gitattributes", rule);
        backend.stageAll();
        assertTrue(backend.commit("track via gitattributes", false).isOk());
    }

    @Test
    void aPathMatchingAnLfsGitattributesRuleIsTrackedThroughTheCli() throws Exception {
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");
        writeFile("asset.psd", "not actually binary, just here to exist\n");

        assertEquals(Set.of("asset.psd"), backend.lfsTrackedPaths(List.of("asset.psd")));
    }

    @Test
    void aPathNotMatchingAnyRuleIsNotTrackedThroughTheCli() throws Exception {
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");
        writeFile("readme.md", "just text\n");

        assertTrue(backend.lfsTrackedPaths(List.of("readme.md")).isEmpty());
    }

    @Test
    void severalPathsAreCheckedInOneCallThroughTheCli() throws Exception {
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");
        writeFile("a.psd", "x\n");
        writeFile("b.psd", "y\n");
        writeFile("c.txt", "z\n");

        var tracked = backend.lfsTrackedPaths(List.of("a.psd", "b.psd", "c.txt"));

        assertEquals(Set.of("a.psd", "b.psd"), tracked);
    }

    @Test
    void aNestedGitattributesFileIsHonouredThroughTheCli() throws Exception {
        new File(repoDir, "sub").mkdirs();
        writeFile("sub/.gitattributes", "*.bin filter=lfs\n");
        backend.stageAll();
        assertTrue(backend.commit("nested rule", false).isOk());
        writeFile("sub/data.bin", "x\n");

        assertEquals(Set.of("sub/data.bin"), backend.lfsTrackedPaths(List.of("sub/data.bin")));
    }

    @Test
    void noRulesAtAllMeansNothingIsTrackedThroughTheCli() throws Exception {
        writeFile("a.txt", "x\n");
        backend.stageAll();
        assertTrue(backend.commit("plain", false).isOk());

        assertTrue(backend.lfsTrackedPaths(List.of("a.txt")).isEmpty());
    }

    @Test
    void hasAnyLfsTrackedPathsIsFalseWithNoGitattributesAtAllThroughTheCli() throws Exception {
        writeFile("a.txt", "x\n");
        backend.stageAll();
        assertTrue(backend.commit("plain", false).isOk());

        assertFalse(new RepositoryCommands(backend).hasAnyLfsTrackedPaths());
    }

    @Test
    void hasAnyLfsTrackedPathsIsTrueWhenTheRootGitattributesDeclaresOneThroughTheCli() throws Exception {
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");

        assertTrue(new RepositoryCommands(backend).hasAnyLfsTrackedPaths());
    }

    @Test
    void emptyOrNullPathsAskNothingOfTheRepositoryThroughTheCli() {
        assertTrue(backend.lfsTrackedPaths(List.of()).isEmpty());
        assertTrue(backend.lfsTrackedPaths(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // LFS smudge capability - whether checkout would materialise real content
    // ------------------------------------------------------------------

    @Test
    void gitLfsInstalledIsDetectedWhenPresentOnThisMachine() {
        assumeTrue(NativeGitRunner.isGitLfsInstalled(),
                "git-lfs is not installed on this system - nothing to confirm here");

        assertTrue(NativeGitRunner.isGitLfsInstalled());
        assertNotEquals("Unknown", NativeGitRunner.getGitLfsVersion());
    }

    /**
     * Whether the native engine can actually smudge LFS content depends on this real
     * machine's own git-lfs setup (system/global/local {@code filter.lfs.smudge}
     * registration), not on anything this test repository does - so this observes
     * the real environment rather than asserting a specific outcome, exactly like
     * {@code isGitLfsInstalled()} itself only being meaningfully testable when
     * git-lfs actually happens to be present.
     */
    @Test
    void canMaterializeLfsContentReflectsWhetherGitLfsIsUsableThroughTheCli() {
        boolean expected = NativeGitRunner.isGitLfsInstalled()
                && new NativeGitRunner(repoDir).run("config", "--get", "filter.lfs.smudge").isOk();

        assertEquals(expected, backend.canMaterializeLfsContent());
    }
}
