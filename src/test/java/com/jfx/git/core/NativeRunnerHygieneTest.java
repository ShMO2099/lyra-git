package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.StashOptions;
import com.jfx.git.model.Submodule;
import com.jfx.git.model.SubmoduleState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native runner's per-invocation hygiene, and the backend fixes that depend on it:
 * free text through stdin, literal pathspecs, pinned config, cancel-only writes, and the
 * failure modes that used to pass for success. Each test reproduces its finding against
 * a real repository (see docs/comparative-review-2026-09-27.md for the IDs).
 */
@Tag("heavy")
class NativeRunnerHygieneTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    @TempDir
    Path tempDir;

    private NativeGitBackend backend;
    private NativeGitRunner git;
    private File repoDir;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");
        repoDir = tempDir.toFile();
        git = new NativeGitRunner(repoDir);
        assertTrue(git.run("init", "-b", "main").isOk());
        git.run("config", "user.name", "Test User");
        git.run("config", "user.email", "test@example.com");
        git.run("config", "core.autocrlf", "false");
        git.run("config", "commit.gpgsign", "false");
        backend = new NativeGitBackend();
        assertTrue(backend.openRepository(repoDir));
    }

    private void write(String path, String content) throws Exception {
        File file = new File(repoDir, path);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    private String read(String path) throws Exception {
        return Files.readString(new File(repoDir, path).toPath());
    }

    private void commitAll(String message) {
        assertTrue(backend.stageAll().isOk());
        var res = backend.commit(message, false);
        assertTrue(res.isOk(), () -> "commit failed: " + res.getMessage());
    }

    private String headMessage() {
        return git.run("log", "-1", "--format=%B").stdout().stripTrailing();
    }

    private List<String> filesInHead() {
        return List.of(git.run("show", "--name-only", "--format=", "HEAD").trimmedStdout().split("\\R"));
    }

    // ------------------------------------------------------------------
    // N1: messages with quotes
    // ------------------------------------------------------------------

    @Test
    void aQuoteInACommitMessageKeepsTheMessageAndCommitsOnlyWhatIsStaged() throws Exception {
        write("other", "o\n");
        write("display", "d\n");
        commitAll("base");
        write("other", "o2\n");
        write("display", "d2\n");
        assertTrue(backend.stageFile("other").isOk());

        var res = backend.commit("Support 12\" display", false);

        assertTrue(res.isOk(), res::getMessage);
        assertEquals("Support 12\" display", headMessage());
        assertEquals(List.of("other"), filesInHead(), "the unstaged file stays out of the commit");
    }

    @Test
    void aCommitMessageCannotSmuggleInOptions() throws Exception {
        write("f", "1\n");
        commitAll("base");
        write("f", "2\n");
        String hostile = "x\" --allow-empty --author=\"Mallory <m@evil>";

        assertTrue(backend.stageAll().isOk());
        assertTrue(backend.commit(hostile, false).isOk());

        assertEquals(hostile, headMessage());
        assertEquals("Test User <test@example.com>",
                git.run("log", "-1", "--format=%an <%ae>").trimmedStdout());
    }

    @Test
    void anAnnotatedTagMessageWithQuotesIsKeptExactly() throws Exception {
        write("f", "1\n");
        commitAll("base");
        String message = "Release \"12\" - \"final\"\n\nSecond paragraph";

        assertTrue(backend.createTag("v1", message, null).isOk());

        assertEquals(message, git.run("for-each-ref", "--format=%(contents)", "refs/tags/v1")
                .stdout().stripTrailing());
    }

    @Test
    void aStashMessageWithQuotesIsKeptExactly() throws Exception {
        write("f", "1\n");
        commitAll("base");
        write("f", "2\n");

        assertTrue(backend.stash(StashOptions.everything("wip on 12\" \"panel\"")).isOk());

        assertTrue(git.run("stash", "list", "--format=%gs").trimmedStdout()
                .endsWith("wip on 12\" \"panel\""), git.run("stash", "list").stdout());
    }

    @Test
    void anIdentityWithQuotesIsWrittenExactly() {
        assertTrue(backend.setLocalIdentity("John \"JJ\" Doe", "jj@example.com", null, false).isOk());
        assertEquals("John \"JJ\" Doe", git.run("config", "--get", "user.name").trimmedStdout());
    }

    /** Whatever the platform, git must receive each argument exactly. */
    @Test
    void argumentsWithQuotesAndBackslashesReachGitIntact() {
        List<String> tricky = List.of("a \"b\" c", "\"quoted\"", "\"", "trail\\ \"x\\",
                "back\\\\\" slash", "C:\\dir x\\", "", "no-quote");
        for (String arg : tricky) {
            var res = git.run("rev-parse", "--sq-quote", arg);
            assertTrue(res.isOk(), res::stderr);
            assertEquals("'" + arg + "'", res.trimmedStdout(), "argument: " + arg);
        }
    }

    @Test
    void windowsArgumentQuotingFollowsTheCommandLineRules() {
        assertEquals("plain arg", NativeGitRunner.windowsArgument("plain arg", false),
                "no quote: left to the JDK");
        assertEquals("\"a\\\"b\"", NativeGitRunner.windowsArgument("a\"b", false));
        assertEquals("\"\\\\\\\"\"", NativeGitRunner.windowsArgument("\\\"", false),
                "a backslash before a quote is doubled, then the quote escaped");
        assertEquals("\"x\\\"y\\\\\"", NativeGitRunner.windowsArgument("x\"y\\", false),
                "a trailing backslash is doubled before the closing quote");
        assertEquals("a\"b", NativeGitRunner.windowsArgument("a\"b", true),
                "strict mode: the JDK escapes an interior quote itself");
        assertNull(NativeGitRunner.windowsArgument("\"wrapped\"", true),
                "strict mode: an argument wrapped in quotes cannot survive, so it is refused");
    }

    @Test
    void aCommandLineTooLongForWindowsIsRefusedWithAReason() {
        assumeTrue(WINDOWS, "the limit is a Windows one");
        var res = git.run("rev-parse", "--sq-quote", "x".repeat(40_000));
        assertFalse(res.isOk());
        assertTrue(res.stderr().contains("more than Windows accepts"), res.stderr());
    }

    // ------------------------------------------------------------------
    // N2: a failed add is not a deletion
    // ------------------------------------------------------------------

    @Test
    void aFailedAddOfAnExistingFileIsReportedAndStagesNothing() throws Exception {
        write("f.bin", "one\n");
        commitAll("base");
        // A required clean filter that fails - what a missing git-lfs looks like.
        git.run("config", "filter.boom.clean", "false");
        git.run("config", "filter.boom.required", "true");
        write(".git/info/attributes", "*.bin filter=boom\n");
        write("f.bin", "two\n");

        var res = backend.stageFile("f.bin");

        assertFalse(res.isOk(), "a failed add must not read as staged");
        assertEquals("", git.run("diff", "--cached", "--name-status").trimmedStdout(),
                "and above all must not stage a deletion");
        assertTrue(new File(repoDir, "f.bin").exists());
    }

    @Test
    void stagingAFileThatIsGoneStillStagesTheDeletion() throws Exception {
        write("gone.txt", "x\n");
        commitAll("base");
        Files.delete(new File(repoDir, "gone.txt").toPath());

        assertTrue(backend.stageFile("gone.txt").isOk());
        assertEquals("D\tgone.txt", git.run("diff", "--cached", "--name-status").trimmedStdout());
    }

    // ------------------------------------------------------------------
    // N3: paths are literal
    // ------------------------------------------------------------------

    @Test
    void discardingABracketedPathLeavesTheFilesItWouldGlobMatchAlone() throws Exception {
        write("[ab].txt", "x\n");
        write("a.txt", "a\n");
        write("b.txt", "b\n");
        commitAll("base");
        write("[ab].txt", "x2\n");
        write("a.txt", "a2\n");
        write("b.txt", "b2\n");

        assertTrue(backend.discardChanges("[ab].txt").isOk());

        assertEquals("x\n", read("[ab].txt"));
        assertEquals("a2\n", read("a.txt"), "a.txt is not [ab].txt");
        assertEquals("b2\n", read("b.txt"), "b.txt is not [ab].txt");
    }

    @Test
    void deletingAnUntrackedBracketedPathDeletesOnlyThatFile() throws Exception {
        write("a.txt", "a\n");
        commitAll("base");
        // Untracked, while a tracked a.txt matches it as a glob - which made isTracked lie.
        write("[ab].txt", "x\n");
        write("[bc].log", "y\n");
        write("b.log", "keep me\n");

        assertTrue(backend.discardChanges("[ab].txt").isOk());
        assertTrue(backend.discardChanges("[bc].log").isOk());

        assertFalse(new File(repoDir, "[ab].txt").exists());
        assertFalse(new File(repoDir, "[bc].log").exists());
        assertTrue(new File(repoDir, "b.log").exists(), "b.log is untracked and unrecoverable");
        assertEquals("a\n", read("a.txt"));
    }

    @Test
    void stagingABracketedPathStagesOnlyThatFile() throws Exception {
        write("[ab].txt", "x\n");
        write("a.txt", "a\n");
        commitAll("base");
        write("[ab].txt", "x2\n");
        write("a.txt", "a2\n");

        assertTrue(backend.stageFile("[ab].txt").isOk());
        assertEquals("[ab].txt", git.run("diff", "--cached", "--name-only").trimmedStdout());
    }

    @Test
    void pathspecMagicIsAvailableOnRequest() throws Exception {
        write("a.txt", "a\n");
        commitAll("base");
        assertEquals("", git.run("ls-files", "--", "*.txt").trimmedStdout(), "literal by default");
        assertEquals("a.txt", git.runWithEnvironment(NativeGitRunner.PATHSPEC_MAGIC,
                List.of("ls-files", "--", "*.txt")).trimmedStdout());
    }

    // ------------------------------------------------------------------
    // N9, N12, S8: pinned editor and config
    // ------------------------------------------------------------------

    @Test
    void theUsersEditorIsNeverLaunched() {
        git.run("config", "core.editor", "code --wait");
        assertEquals("true", git.run("var", "GIT_EDITOR").trimmedStdout());
    }

    @Test
    void configThatChangesParsedOutputIsOverridden() {
        git.run("config", "log.showSignature", "true");
        git.run("config", "core.quotepath", "true");
        git.run("config", "diff.suppressBlankEmpty", "true");
        assertEquals("false", git.run("config", "--get", "log.showSignature").trimmedStdout());
        assertEquals("off", git.run("config", "--get", "core.quotepath").trimmedStdout());
        assertEquals("false", git.run("config", "--get", "diff.suppressBlankEmpty").trimmedStdout());
    }

    @Test
    void aBlankContextLineKeepsItsLeadingSpace() throws Exception {
        git.run("config", "diff.suppressBlankEmpty", "true");
        write("f.txt", "one\n\ntwo\n");
        commitAll("base");
        write("f.txt", "one\n\nTWO\n");

        String diff = new String(backend.getUnstagedDiffBytes("f.txt", false), StandardCharsets.UTF_8);

        assertTrue(diff.contains("\n one\n \n-two\n"), diff);
    }

    // ------------------------------------------------------------------
    // N13, S6, S7: diffs whose hunks are acted on
    // ------------------------------------------------------------------

    @Test
    void anExternalDiffToolDoesNotReplaceTheWorkingTreeDiff() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        write("f.txt", "two\n");
        git.run("config", "diff.external", "echo EXTERNAL");

        String unstaged = new String(backend.getUnstagedDiffBytes("f.txt", false), StandardCharsets.UTF_8);
        assertTrue(unstaged.contains("@@") && unstaged.contains("+two"), unstaged);
        assertFalse(unstaged.contains("EXTERNAL"), unstaged);

        assertTrue(backend.stageFile("f.txt").isOk());
        String staged = new String(backend.getStagedDiffBytes("f.txt", false), StandardCharsets.UTF_8);
        assertTrue(staged.contains("+two") && !staged.contains("EXTERNAL"), staged);

        commitAll("second");
        String inCommit = new String(backend.getDiffBytesForFileInCommit(
                git.run("rev-parse", "HEAD").trimmedStdout(), "f.txt", false), StandardCharsets.UTF_8);
        assertTrue(inCommit.contains("+two") && !inCommit.contains("EXTERNAL"), inCommit);
    }

    @Test
    void aTextconvFilterDoesNotRewriteTheHunksToStage() throws Exception {
        write(".gitattributes", "*.up diff=upper\n");
        write("f.up", "one\n");
        commitAll("base");
        git.run("config", "diff.upper.textconv", "tr a-z A-Z <");
        write("f.up", "two\n");

        String diff = new String(backend.getUnstagedDiffBytes("f.up", false), StandardCharsets.UTF_8);

        assertTrue(diff.contains("+two"), "the real bytes, not the rendering: " + diff);
    }

    @Test
    void theContextIsPinnedToThreeLines() throws Exception {
        git.run("config", "diff.context", "0");
        write("f.txt", "l1\nl2\nl3\nl4\nl5\nl6\nl7\n");
        commitAll("base");
        write("f.txt", "l1\nl2\nl3\nL4\nl5\nl6\nl7\n");

        String diff = new String(backend.getUnstagedDiffBytes("f.txt", false), StandardCharsets.UTF_8);

        assertTrue(diff.contains("\n l1\n l2\n l3\n-l4\n+L4\n l5\n l6\n l7\n"), diff);
    }

    @Test
    void anUntrackedFileDiffsAsAnAddition() throws Exception {
        write("base.txt", "b\n");
        commitAll("base");
        write("new.txt", "hello\nworld\n");

        String diff = new String(backend.getUnstagedDiffBytes("new.txt", false), StandardCharsets.UTF_8);

        assertTrue(diff.contains("+++ b/new.txt"), diff);
        assertTrue(diff.contains("+hello\n+world"), diff);
        assertFalse(diff.startsWith("Error"), diff);
        assertEquals(0, backend.getUnstagedDiffBytes("base.txt", false).length,
                "an unchanged tracked file still has no diff");
    }

    // ------------------------------------------------------------------
    // N21: -z file lists
    // ------------------------------------------------------------------

    @Test
    void commitFileListsKeepUnusualNamesExactly() throws Exception {
        write("base.txt", "b\n");
        commitAll("base");
        String from = git.run("rev-parse", "HEAD").trimmedStdout();
        write("café.txt", "c\n");
        write(" lead.txt", "l\n");
        commitAll("unusual names");
        String to = git.run("rev-parse", "HEAD").trimmedStdout();

        assertEquals(java.util.Set.of("café.txt", " lead.txt"),
                java.util.Set.copyOf(backend.getFilesInCommit(to)));
        assertEquals(java.util.Set.of("café.txt", " lead.txt"),
                java.util.Set.copyOf(backend.getFilesBetweenCommits(from, to)));
    }

    // ------------------------------------------------------------------
    // N15: a failed status is not a clean tree
    // ------------------------------------------------------------------

    @Test
    void aFailedStatusIsNotReadAsClean() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        String base = git.run("rev-parse", "HEAD").trimmedStdout();
        write("f.txt", "two\n");
        commitAll("second");
        write("f.txt", "uncommitted work\n");
        Files.writeString(new File(repoDir, ".git/index").toPath(), "not an index");

        assertThrows(GitOperationException.class, backend::hasUncommittedChanges);
        var restored = new RepositoryCommands(backend).restoreTo(base, "test");
        assertFalse(restored.isOk(), "a restore must refuse when it cannot see the working tree");
        assertEquals("uncommitted work\n", read("f.txt"));
    }

    // ------------------------------------------------------------------
    // N5, N8: timeouts and cancellation
    // ------------------------------------------------------------------

    @Test
    void writesAreNeverTimedOutAndReadsAre() {
        for (List<String> write : List.of(List.of("commit", "-F", "-"), List.of("checkout", "main"),
                List.of("stash", "push", "-u"), List.of("reset", "--hard"), List.of("pull"),
                List.of("submodule", "update", "--init"), List.of("remote", "add", "--", "o", "u"),
                List.of("config", "user.name", "x"), List.of("lfs", "pull"), List.of("rebase", "main"),
                List.of("clone", "--recurse-submodules", "--", "u", "d"))) {
            assertEquals(NativeGitRunner.NO_TIMEOUT, NativeGitRunner.timeoutFor(write), write.toString());
        }
        for (List<String> read : List.of(List.of("status"), List.of("-c", "a=b", "log"),
                List.of("stash", "list"), List.of("remote"), List.of("config", "--get", "x"),
                List.of("diff-tree", "HEAD"))) {
            assertNotEquals(NativeGitRunner.NO_TIMEOUT, NativeGitRunner.timeoutFor(read), read.toString());
        }
        assertEquals("status", NativeGitRunner.subcommandOf(List.of("-c", "x=y", "status")));
    }

    /** History walks scale with history, not the tree; a content search must not be cut at 30s. */
    @Test
    void historyReadersHaveLongerThanStatus() {
        int status = NativeGitRunner.timeoutFor(List.of("status"));
        assertTrue(NativeGitRunner.timeoutFor(List.of("log", "-G", "x")) > status);
        assertTrue(NativeGitRunner.timeoutFor(List.of("blame", "--porcelain")) > status);
    }

    /**
     * A runner whose folder has gone must not run git anywhere else. It used to fall back
     * to the JVM's working directory - a repository whenever the application was started
     * from inside one - and a "discard all" would have run there.
     */
    @Test
    void aRunnerWhoseFolderIsGoneRunsNothing() {
        File gone = tempDir.resolve("deleted-repository").toFile();

        var result = new NativeGitRunner(gone).run("status");

        assertFalse(result.isOk());
        assertTrue(result.stderr().contains("no longer exists"), result.stderr());
    }

    /**
     * Variables a hook or an IDE terminal exports would aim every command at their
     * repository, whatever folder git is started in. The deliberate ones stay.
     */
    @Test
    void inheritedVariablesThatRedirectGitAreRemoved() {
        var env = new java.util.HashMap<>(java.util.Map.of(
                "GIT_DIR", "/elsewhere/.git", "GIT_WORK_TREE", "/elsewhere", "GIT_INDEX_FILE", "idx",
                "GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "core.bare", "GIT_CONFIG_VALUE_0", "true",
                "GIT_SSH_COMMAND", "ssh -i key", "GIT_ASKPASS", "askpass", "PATH", "bin"));

        NativeGitRunner.scrubInheritedRepositoryVariables(env);

        assertEquals(java.util.Set.of("GIT_SSH_COMMAND", "GIT_ASKPASS", "PATH"), env.keySet());
    }

    /**
     * Cancelling a commit stuck in its pre-commit hook must stop the hook too. Killing
     * only git.exe left the hook's shell running; here it would go on to write a marker.
     */
    @Test
    void cancellingKillsTheHooksGitStarted() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        File marker = new File(tempDir.toFile().getParentFile(), "hook-survived-" + System.nanoTime());
        Path hook = new File(repoDir, ".git/hooks/pre-commit").toPath();
        Files.writeString(hook, "#!/bin/sh\nsleep 3\necho survived > '"
                + marker.getAbsolutePath().replace('\\', '/') + "'\nexit 1\n");
        hook.toFile().setExecutable(true);
        write("f.txt", "two\n");
        assertTrue(backend.stageAll().isOk());

        AtomicReference<GitResult> result = new AtomicReference<>();
        var task = GitTaskRunner.run(backend, () -> {
            GitResult r = backend.commit("slow hook", false);
            result.set(r);
            return r;
        }, r -> { });
        Thread.sleep(1000);
        task.cancel();

        long until = System.currentTimeMillis() + 10_000;
        while (result.get() == null && System.currentTimeMillis() < until) Thread.sleep(50);
        assertNotNull(result.get(), "the cancelled commit returned");
        assertTrue(result.get().getMessage().contains("cancelled"), result.get().getMessage());

        Thread.sleep(4000);
        try {
            assertFalse(marker.exists(), "the hook kept running after Cancel");
        } finally {
            marker.delete();
        }
    }

    // ------------------------------------------------------------------
    // N16: the git probe
    // ------------------------------------------------------------------

    @Test
    void gitVersionsAreParsedAndCompared() {
        assertArrayEquals(new int[]{2, 45, 1}, NativeGitRunner.parseVersion("git version 2.45.1.windows.1"));
        assertArrayEquals(new int[]{2, 39, 3}, NativeGitRunner.parseVersion("git version 2.39.3 (Apple Git-146)"));
        assertArrayEquals(new int[]{2, 20, 0}, NativeGitRunner.parseVersion("git version 2.20"));
        assertNull(NativeGitRunner.parseVersion("command not found"));
        assertTrue(NativeGitRunner.compareVersions(new int[]{2, 20, 1}, NativeGitRunner.MINIMUM_VERSION) < 0);
        // 2.23-2.25 have no rebase --empty or stash --pathspec-from-file, which the engine runs.
        assertTrue(NativeGitRunner.compareVersions(new int[]{2, 25, 4}, NativeGitRunner.MINIMUM_VERSION) < 0);
        assertTrue(NativeGitRunner.compareVersions(new int[]{2, 26, 0}, NativeGitRunner.MINIMUM_VERSION) >= 0);
        assertTrue(NativeGitRunner.compareVersions(new int[]{3, 0, 0}, NativeGitRunner.MINIMUM_VERSION) > 0);
        assertNull(NativeGitRunner.gitUnavailableReason(), "this machine's git is usable");
    }

    @Test
    void theCurrentBranchIsReadWithoutBranchShowCurrent() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        // A tag with the branch's name is what made the short form ambiguous.
        assertTrue(git.run("tag", "main").isOk());
        assertEquals("main", backend.getCurrentBranch());
        assertTrue(git.run("checkout", "--detach", "HEAD").isOk());
        // A detached HEAD is no current branch, on every engine (see BranchOps): blank,
        // where this used to be the short hash.
        assertEquals("", backend.getCurrentBranch());
    }

    // ------------------------------------------------------------------
    // N14: moving a signed tag
    // ------------------------------------------------------------------

    @Test
    void movingASignedTagDoesNotCopyTheSignatureIntoTheMessage() throws Exception {
        write("f.txt", "one\n");
        commitAll("first");
        String first = git.run("rev-parse", "HEAD").trimmedStdout();
        write("f.txt", "two\n");
        commitAll("second");
        String tagObject = "object " + first + "\ntype commit\ntag v1\n"
                + "tagger Test User <test@example.com> 1700000000 +0000\n\n"
                + "Release notes\n-----BEGIN PGP SIGNATURE-----\n\nabcdef\n-----END PGP SIGNATURE-----\n";
        var made = git.runWithInput(tagObject.getBytes(StandardCharsets.UTF_8),
                List.of("hash-object", "-t", "tag", "-w", "--stdin"));
        assertTrue(made.isOk(), made::stderr);
        assertTrue(git.run("update-ref", "refs/tags/v1", made.trimmedStdout()).isOk());

        var moved = backend.moveTag("v1", "HEAD");

        assertTrue(moved.isOk(), moved::getMessage);
        assertTrue(moved.getMessage().contains("signature"), moved.getMessage());
        String message = git.run("for-each-ref", "--format=%(contents)", "refs/tags/v1").trimmedStdout();
        assertEquals("Release notes", message);
    }

    // ------------------------------------------------------------------
    // N13b, O9: submodules
    // ------------------------------------------------------------------

    /** Records a submodule at {@code libs/my lib}, named differently from its path, never initialised. */
    private String recordUninitialisedSubmodule() throws Exception {
        write("base.txt", "b\n");
        commitAll("base");
        String someCommit = git.run("rev-parse", "HEAD").trimmedStdout();
        write(".gitmodules", "[submodule \"renamed\"]\n\tpath = libs/my lib\n"
                + "\turl = " + new File(tempDir.toFile().getParentFile(), "no-such-remote").getAbsolutePath()
                        .replace('\\', '/') + "\n");
        assertTrue(git.run("update-index", "--add", "--cacheinfo",
                "160000," + someCommit + ",libs/my lib").isOk());
        assertTrue(git.run("add", ".gitmodules").isOk());
        assertTrue(backend.commit("add submodule", false).isOk());
        new File(repoDir, "libs/my lib").mkdirs();
        return someCommit;
    }

    @Test
    void aSubmodulePathWithSpacesAndARenamedEntryIsReadWhole() throws Exception {
        String recorded = recordUninitialisedSubmodule();

        List<Submodule> submodules = backend.getSubmodules();

        assertEquals(1, submodules.size(), submodules.toString());
        Submodule sub = submodules.get(0);
        assertEquals("libs/my lib", sub.path());
        assertEquals(SubmoduleState.UNINITIALIZED, sub.state());
        assertEquals(recorded, sub.recorded());
        assertNotNull(sub.url(), "the URL is found by the submodule's name, not its path");
        assertTrue(sub.url().endsWith("no-such-remote"), sub.url());
    }

    @Test
    void theDescribeSuffixIsNotPartOfTheSubmodulePath() {
        String sha = "0123456789012345678901234567890123456789";
        var parsed = backend.parseSubmoduleStatusLine(" " + sha + " libs/my lib (heads/main)");
        assertEquals("libs/my lib", parsed.path());
        assertEquals(sha, parsed.sha());
        assertEquals(' ', parsed.marker());
        assertEquals("libs/my lib", backend.parseSubmoduleStatusLine("-" + sha + " libs/my lib").path());
    }

    @Test
    void automaticSubmoduleUpdateDoesNotInitialiseSubmodules() throws Exception {
        recordUninitialisedSubmodule();
        assertTrue(backend.createBranch("other", null, true).isOk());

        AppPreferences.setAutoUpdateSubmodules(true);
        try {
            assertTrue(backend.checkoutBranch("main").isOk());
        } finally {
            AppPreferences.setAutoUpdateSubmodules(false);
        }

        assertFalse(git.run("config", "--get", "submodule.renamed.url").isOk(),
                "an optional submodule the user never initialised stays uninitialised");
        assertFalse(new File(repoDir, ".git/modules").exists());
    }

    /**
     * Without {@code --init}, an initialised submodule must still follow the branch. Set
     * up by hand with {@code protocol.file.allow} for this one command, because recent git
     * refuses a local-path submodule otherwise (which is why NativeGitBackendTest's own
     * version of this test is skipped on such machines).
     */
    @Test
    void automaticSubmoduleUpdateStillMovesInitialisedSubmodules() throws Exception {
        Path source = Files.createTempDirectory("lyra-hygiene-sub");
        try {
            NativeGitRunner src = new NativeGitRunner(source.toFile());
            assertTrue(src.run("init", "-b", "main").isOk());
            src.run("config", "user.name", "Test User");
            src.run("config", "user.email", "test@example.com");
            Files.writeString(source.resolve("a.txt"), "a");
            assertTrue(src.run("add", "a.txt").isOk());
            assertTrue(src.run("commit", "-m", "A").isOk());
            String commitA = src.run("rev-parse", "HEAD").trimmedStdout();
            Files.writeString(source.resolve("b.txt"), "b");
            assertTrue(src.run("add", "b.txt").isOk());
            assertTrue(src.run("commit", "-m", "B").isOk());
            String commitB = src.run("rev-parse", "HEAD").trimmedStdout();

            write("base.txt", "b\n");
            commitAll("base");
            var added = git.run("-c", "protocol.file.allow=always", "submodule", "add", "--",
                    source.toFile().getAbsolutePath(), "libs/sub");
            assertTrue(added.isOk(), added::stderr);
            commitAll("add submodule at B");

            assertTrue(backend.createBranch("old", null, true).isOk());
            NativeGitRunner sub = new NativeGitRunner(new File(repoDir, "libs/sub"));
            assertTrue(sub.run("checkout", "-q", commitA).isOk());
            commitAll("submodule back at A");

            // A plain checkout leaves the submodule at A while main records B; the
            // automatic update is what has to bring it along.
            AppPreferences.setAutoUpdateSubmodules(true);
            try {
                assertTrue(backend.checkoutBranch("main").isOk());
            } finally {
                AppPreferences.setAutoUpdateSubmodules(false);
            }

            assertEquals(commitB, sub.run("rev-parse", "HEAD").trimmedStdout());
        } finally {
            try (var walk = Files.walk(source)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    p.toFile().setWritable(true);
                    p.toFile().delete();
                });
            }
        }
    }

    // ------------------------------------------------------------------
    // N17: option-like remote arguments
    // ------------------------------------------------------------------

    @Test
    void aRemoteUrlThatLooksLikeAnOptionIsTakenAsAUrl() {
        assertTrue(backend.addRemote("odd", "--upload-pack=touch pwned").isOk());
        assertEquals("--upload-pack=touch pwned", backend.getRemoteUrl("odd"));
        assertTrue(backend.removeRemote("odd").isOk());
        assertTrue(backend.getRemoteNames().isEmpty());
    }

    // ------------------------------------------------------------------
    // O14: the reword amend does not run commit hooks
    // ------------------------------------------------------------------

    @Test
    void rewordingDoesNotRunTheCommitHooks() throws Exception {
        write("a.txt", "a\n");
        commitAll("base");
        write("b.txt", "b\n");
        commitAll("typpo");
        Path hook = new File(repoDir, ".git/hooks/pre-commit").toPath();
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        hook.toFile().setExecutable(true);

        List<CommitInfo> commits = backend.getCommitGraphPage(20, GraphOrder.current()).rows().stream()
                .filter(r -> !r.isSynthetic()).map(r -> r.getCommit()).limit(1).toList();
        var range = backend.getRebaseRange(commits.get(0).getHash());
        assertTrue(range.isUsable(), range::problem);
        RebaseTodo plan = RebaseTodo.fromNewestFirst(range.commits(), range.base())
                .withAction(0, RebaseTodo.Action.REWORD)
                .withMessage(0, "typo fixed");

        var res = backend.rebaseInteractive(plan);

        assertTrue(res.isOk(), res::getMessage);
        assertEquals("typo fixed", headMessage());
    }
}
