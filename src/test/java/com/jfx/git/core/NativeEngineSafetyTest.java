package com.jfx.git.core;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.CommitSearch;
import com.jfx.git.model.MergeMode;
import com.jfx.git.model.ResetMode;
import com.jfx.git.model.StashEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native engine's review findings of 2026-10-05, each against a real repository:
 * the failure it reproduces is described on the test, and each fails on the code before
 * its fix.
 */
@Tag("heavy")
class NativeEngineSafetyTest {

    @TempDir
    Path tempDir;

    private File repoDir;
    private NativeGitRunner git;
    private NativeGitBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");
        repoDir = tempDir.resolve("repo").toFile();
        assertTrue(repoDir.mkdirs());
        git = new NativeGitRunner(repoDir);
        assertTrue(git.run("init", "-b", "main").isOk());
        configure(git);
        backend = new NativeGitBackend(repoDir);
    }

    @AfterEach
    void tearDown() {
        if (backend != null) backend.close();
    }

    private static void configure(NativeGitRunner runner) {
        runner.run("config", "user.name", "Test User");
        runner.run("config", "user.email", "test@example.com");
        runner.run("config", "core.autocrlf", "false");
        runner.run("config", "commit.gpgsign", "false");
    }

    private void write(String path, String content) throws Exception {
        File file = new File(repoDir, path);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    private String commitAll(String message) {
        assertTrue(git.run("add", "-A").isOk());
        var res = git.runWithInput(message.getBytes(StandardCharsets.UTF_8), List.of("commit", "-q", "-F", "-"));
        assertTrue(res.isOk(), res::stderr);
        return head();
    }

    private String head() {
        return git.run("rev-parse", "HEAD").trimmedStdout();
    }

    private List<String> indexPaths() {
        String out = git.run("ls-files", "-z").stdout();
        List<String> paths = new ArrayList<>();
        for (String p : out.split("\0")) if (!p.isEmpty()) paths.add(p);
        return paths;
    }

    // ------------------------------------------------------------------
    // 2: --end-of-options before revisions the UI did not validate
    // ------------------------------------------------------------------

    /** "reset --mixed x" with x a file used to unstage the file and report a reset to x. */
    @Test
    void aResetToSomethingThatIsNotARevisionIsRefusedNotTakenAsAPath() throws Exception {
        write("a.txt", "1\n");
        commitAll("base");
        write("notes", "staged\n");
        assertTrue(git.run("add", "notes").isOk());

        GitResult result = backend.reset("notes", ResetMode.MIXED);

        assertFalse(result.isOk(), "notes is a file, not a revision: " + result.getMessage());
        assertTrue(backend.getStagedFiles().contains("notes"), "the staged file was left staged");
    }

    @Test
    void aBranchNamedLikeAnOptionIsNeverReadAsOne() throws Exception {
        write("a.txt", "1\n");
        String base = commitAll("base");

        assertFalse(backend.merge("--no-ff", MergeMode.DEFAULT, null).isOk());
        assertFalse(backend.rebase("--root").isOk());
        assertFalse(backend.reset("--hard", ResetMode.SOFT).isOk());
        assertEquals(base, head());
    }

    // ------------------------------------------------------------------
    // 3: tracking and upstreams beside a tag of the same name
    // ------------------------------------------------------------------

    /** With a tag called main, :short wrote heads/main and the sidebar lost the badge. */
    @Test
    void trackingAndUpstreamsSurviveATagWithTheBranchesName() throws Exception {
        write("a.txt", "1\n");
        commitAll("base");
        assertTrue(git.run("checkout", "-q", "-b", "feature").isOk());
        write("b.txt", "2\n");
        commitAll("ahead");
        git.run("config", "branch.feature.remote", ".");
        git.run("config", "branch.feature.merge", "refs/heads/main");
        assertTrue(git.run("tag", "feature").isOk());
        assertTrue(git.run("tag", "main", "feature").isOk());

        int[] tracking = backend.getBranchTracking().get("feature");
        assertNotNull(tracking, "keyed by the branch's own name: " + backend.getBranchTracking().keySet());
        assertArrayEquals(new int[]{1, 0}, tracking);
        assertEquals("main", backend.getUpstream("feature"));
        assertEquals(Map.of("feature", "main"), backend.getUpstreams());
    }

    // ------------------------------------------------------------------
    // 4: locale
    // ------------------------------------------------------------------

    @Test
    void messagesStayEnglishWhileCharacterClassesAreUtf8() {
        Map<String, String> german = new HashMap<>(Map.of(
                "LC_ALL", "de_DE.UTF-8", "LANGUAGE", "de", "LC_TIME", "de_DE.UTF-8", "LANG", "fr_FR.UTF-8",
                "PATH", "/bin"));
        NativeGitRunner.applyLocale(german);
        assertEquals(Map.of("LANG", "C", "LC_CTYPE", "de_DE.UTF-8", "LC_MESSAGES", "C", "PATH", "/bin"), german,
                "the user's own UTF-8 locale, for character classes only");

        boolean mac = System.getProperty("os.name", "").toLowerCase().startsWith("mac");
        Map<String, String> latin1 = new HashMap<>(Map.of("LANG", "de_DE.ISO-8859-1"));
        NativeGitRunner.applyLocale(latin1);
        assertEquals(Map.of("LANG", "C", "LC_CTYPE", mac ? "UTF-8" : "C.UTF-8", "LC_MESSAGES", "C"), latin1);
    }

    /** On Linux, LC_ALL=C folded ASCII only, and this search found nothing. */
    @Test
    void aCaseInsensitiveSearchFoldsAccentedLetters() throws Exception {
        write("a.txt", "1\n");
        String summer = commitAll("ÉTÉ summer");
        write("a.txt", "2\n");
        commitAll("plain");

        List<CommitInfo> found = backend.searchCommits(CommitSearch.parse("été"), 10);

        assertEquals(List.of(summer), found.stream().map(CommitInfo::getHash).toList());
        var refused = git.run("checkout", "no-such-branch", "--");
        assertTrue(refused.stderr().contains("invalid reference"), refused::stderr);
    }

    // ------------------------------------------------------------------
    // 5: LFS paths on stdin
    // ------------------------------------------------------------------

    /** Over Windows' command-line limit, every file used to come back as "not LFS". */
    @Test
    void lfsTrackedPathsAnswersForAListLongerThanACommandLine() throws Exception {
        write(".gitattributes", "*.bin filter=lfs diff=lfs merge=lfs -text\n");
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            paths.add("assets/textures/a-fairly-long-folder-name/file-number-" + i + ".bin");
        }
        paths.add("readme.txt");

        var tracked = backend.lfsTrackedPaths(paths);

        assertEquals(1500, tracked.size());
        assertFalse(tracked.contains("readme.txt"));
    }

    // ------------------------------------------------------------------
    // 6: configuration that changed what is parsed
    // ------------------------------------------------------------------

    /** A Latin-1 commit came back Latin-1 and was decoded as UTF-8. */
    @Test
    void aMessageInAnotherEncodingIsReadAsUtf8() throws Exception {
        git.run("config", "i18n.commitEncoding", "ISO-8859-1");
        write("a.txt", "1\n");
        assertTrue(git.run("add", "-A").isOk());
        assertTrue(git.runWithInput("café au lait".getBytes(StandardCharsets.ISO_8859_1),
                List.of("commit", "-q", "-F", "-")).isOk());

        assertEquals("café au lait\n", backend.getCommitMessage(head()));
    }

    @Test
    void colourConfiguredAlwaysDoesNotReachParsedOutput() throws Exception {
        git.run("config", "color.ui", "always");
        write("a.txt", "1\n");
        commitAll("base");
        write("a.txt", "2\n");

        String diff = git.run("diff").stdout();

        assertTrue(diff.contains("+2"), diff);
        assertFalse(diff.contains("\u001b["), "no escape sequences: " + diff);
    }

    /** format.* settings made a mail of it: sign-offs, addresses, a footer, numbers. */
    @Test
    void aPatchIsTheSameWhateverTheMailSettingsSay() throws Exception {
        write("a.txt", "1\n");
        String first = commitAll("first");
        write("a.txt", "2\n");
        commitAll("second");
        write("a.txt", "3\n");
        String third = commitAll("third");
        for (String[] setting : new String[][]{{"format.signOff", "true"}, {"format.to", "list@mail.invalid"},
                {"format.cc", "cc@mail.invalid"}, {"format.headers", "X-Leak: yes"},
                {"format.signature", "MY-FOOTER"}, {"format.numbered", "true"},
                {"format.subjectPrefix", "PFX"}, {"format.coverLetter", "true"},
                {"diff.noprefix", "true"}, {"format.thread", "deep"}}) {
            git.run("config", setting[0], setting[1]);
        }
        File out = tempDir.resolve("series.patch").toFile();

        GitResult result = backend.createPatch(List.of(third, first), out);

        assertTrue(result.isOk(), result.getMessage());
        String patch = Files.readString(out.toPath());
        assertFalse(patch.contains("Signed-off-by"), patch);
        assertFalse(patch.contains("mail.invalid"), patch);
        assertFalse(patch.contains("X-Leak"), patch);
        assertFalse(patch.contains("MY-FOOTER"), patch);
        assertFalse(patch.contains("PFX"), patch);
        assertFalse(patch.contains("Message-ID") || patch.contains("Message-Id"), patch);
        assertTrue(patch.contains("--- a/a.txt"), "the a/ and b/ prefixes: " + patch);
        int firstAt = patch.indexOf("Subject: [PATCH] first");
        int thirdAt = patch.indexOf("Subject: [PATCH] third");
        assertTrue(firstAt >= 0 && thirdAt > firstAt, "oldest first, one [PATCH] each: " + patch);
        assertEquals(2, patch.split("\nFrom [0-9a-f]{40} ", -1).length, "two commits: " + patch);
    }

    /** One revision alone means "since" to format-patch: the patch of HEAD came out empty. */
    @Test
    void aPatchOfOneCommitIsThatCommitWhereverItIs() throws Exception {
        write("a.txt", "1\n");
        String first = commitAll("first");
        write("a.txt", "2\n");
        String second = commitAll("second");
        File out = tempDir.resolve("one.patch").toFile();

        for (String hash : List.of(second, first)) {
            assertTrue(backend.createPatch(List.of(hash), out).isOk());
            String patch = Files.readString(out.toPath());
            assertEquals(1, patch.split("\nSubject: ", -1).length - 1, patch);
            assertTrue(patch.contains("Subject: [PATCH] " + (hash.equals(first) ? "first" : "second")), patch);
        }
    }

    /** diff.renames decided whether a staged rename was one row or two. */
    @Test
    void lineCountsDoNotDependOnRenameConfiguration() throws Exception {
        write("old.txt", "a\nb\nc\nd\ne\n");
        commitAll("base");
        git.run("config", "diff.renames", "true");
        assertTrue(git.run("mv", "old.txt", "new.txt").isOk());

        Map<String, int[]> counts = backend.getWorkingTreeLineCounts(true, List.of("old.txt", "new.txt"));

        assertArrayEquals(new int[]{5, 0}, counts.get("new.txt"), "as the JGit engine counts: no renames");
        assertArrayEquals(new int[]{0, 5}, counts.get("old.txt"));
    }

    // ------------------------------------------------------------------
    // 7: the diff of one file in a commit
    // ------------------------------------------------------------------

    @Test
    void aCommitsMessageIsNotPartOfItsFileDiff() throws Exception {
        write("a.txt", "1\n");
        commitAll("base");
        write("a.txt", "2\n");
        String hash = commitAll("tricky\n\nBinary files a/x and b/x differ\n@@ -1 +1 @@");
        git.run("config", "format.pretty", "fuller");

        String diff = new String(backend.getDiffBytesForFileInCommit(hash, "a.txt", false), StandardCharsets.UTF_8);

        assertTrue(diff.startsWith("diff --git"), diff);
        assertFalse(diff.contains("Binary files"), diff);
        assertEquals(1, diff.split("\n@@ ", -1).length - 1, "one hunk: " + diff);
    }

    // ------------------------------------------------------------------
    // 8: stash positions from git, not from counting
    // ------------------------------------------------------------------

    /** A separator in a message shifted every later index, and drop(hash) dropped another. */
    @Test
    void stashIndexesComeFromGitEvenWithSeparatorsInAMessage() throws Exception {
        write("a.txt", "0\n");
        commitAll("base");
        for (String message : List.of("oldest", "has \u001e and \u001f inside", "newest")) {
            write("a.txt", message.length() + "\n");
            assertTrue(git.run("stash", "push", "-q", "-m", message).isOk());
        }

        List<StashEntry> entries = backend.getStashEntries();

        assertEquals(3, entries.size(), entries.toString());
        for (StashEntry entry : entries) {
            assertEquals(git.run("rev-parse", "stash@{" + entry.index() + "}").trimmedStdout(), entry.hash());
        }
        assertTrue(entries.get(1).message().contains("\u001e and \u001f"), entries.get(1).message());
        String oldest = git.run("rev-parse", "stash@{2}").trimmedStdout();
        assertTrue(new RepositoryCommands(backend).stashDrop(oldest).isOk());
        assertFalse(git.run("stash", "list", "--format=%H").stdout().contains(oldest));
        assertEquals(2, backend.getStashEntries().size());
    }

    /** As the stash list did: a separator in a subject split its entry and shifted HEAD@{n}. */
    @Test
    void reflogPositionsSurviveSeparatorsInACommitSubject() throws Exception {
        write("a.txt", "1\n");
        commitAll("base");
        write("a.txt", "2\n");
        commitAll("has \u001e and \u001f inside");
        write("a.txt", "3\n");
        commitAll("third");

        List<ReflogItem> items = backend.getReflog(10);

        assertEquals(3, items.size(), items.toString());
        for (ReflogItem item : items) {
            assertEquals(git.run("rev-parse", "HEAD@{" + item.index() + "}").trimmedStdout(), item.newHash());
        }
        assertTrue(items.get(1).comment().contains("\u001e and \u001f"), items.get(1).comment());
    }

    @Test
    void blameRefusesACommitNamedLikeAnOption() throws Exception {
        write("a.txt", "1\n");
        commitAll("base");

        assertThrows(GitOperationException.class, () -> backend.getBlameLines("a.txt", "--reverse"));
        assertEquals(1, backend.getBlameLines("a.txt", "HEAD").size());
    }

    // ------------------------------------------------------------------
    // 9: the cheaper dirty check, worktree labels
    // ------------------------------------------------------------------

    @Test
    void theDirtyCheckSeesUntrackedFilesAndIgnoresHeaders() throws Exception {
        write("a.txt", "1\n");
        commitAll("base");
        write(".git/info/exclude", "*.log\n");
        git.run("config", "status.showStash", "true");
        git.run("config", "status.branch", "true");
        write("a.txt", "2\n");
        assertTrue(git.run("stash", "push", "-q").isOk());
        assertFalse(backend.hasUncommittedChanges(), "a stash header is not a change");

        write("logs/run.log", "x\n");
        assertFalse(backend.hasUncommittedChanges(), "a folder of ignored files is not a change");

        write("deep/er/new.txt", "x\n");
        assertTrue(backend.hasUncommittedChanges());
    }

    @Test
    void aDetachedWorktreeIsLabelledByItsCommit() throws Exception {
        write("a.txt", "1\n");
        String base = commitAll("base");
        File linked = tempDir.resolve("linked").toFile();
        assertTrue(git.run("worktree", "add", "--detach", linked.getAbsolutePath(), base).isOk());

        var worktrees = backend.getWorktrees();

        assertEquals(2, worktrees.size());
        assertEquals(base.substring(0, 7), worktrees.get(1).branch());
        assertTrue(worktrees.get(1).detached());
    }

    // ------------------------------------------------------------------
    // 11: fallbacks that fired on any failure
    // ------------------------------------------------------------------

    /** A broken HEAD used to end in "rm --cached -r .": every file staged for deletion. */
    @Test
    void unstagingWithABrokenHeadFailsInsteadOfStagingADeletionOfEverything() throws Exception {
        write("a.txt", "1\n");
        write("b.txt", "1\n");
        commitAll("base");
        Files.writeString(new File(repoDir, ".git/refs/heads/main").toPath(), "garbage\n");

        assertFalse(backend.unstageAll().isOk());
        assertFalse(backend.unstageFile("a.txt").isOk());
        assertEquals(List.of("a.txt", "b.txt"), indexPaths());
    }

    @Test
    void unstagingBeforeTheFirstCommitTakesFilesOutOfTheIndex() throws Exception {
        write("a.txt", "1\n");
        write("b.txt", "1\n");
        assertTrue(git.run("add", "-A").isOk());

        assertTrue(backend.unstageFile("a.txt").isOk());
        assertEquals(List.of("b.txt"), indexPaths());
        assertTrue(backend.unstageAll().isOk());
        assertEquals(List.of(), indexPaths());
        assertTrue(new File(repoDir, "a.txt").isFile(), "unstaging leaves the file on disk");
    }

    // ------------------------------------------------------------------
    // 12: blobs, and whether they exist
    // ------------------------------------------------------------------

    @Test
    void aFolderIsNotAFilesContent() throws Exception {
        write("dir/inner.txt", "1\n");
        String base = commitAll("base");

        assertEquals(0, backend.getFileBytesAtCommit(base, "dir").length, "git's tree listing is not content");
        assertEquals(0, backend.getFileBytesAtCommit(base, "missing.txt").length);
        assertEquals("1\n", new String(backend.getFileBytesAtCommit(base, "dir/inner.txt"), StandardCharsets.UTF_8));
    }

    /** Emptying a file and staging that showed HEAD's copy as "before", as if unstaged. */
    @Test
    void aFileStagedEmptyIsComparedWithItsEmptyIndexCopy() throws Exception {
        write("a.txt", "content\n");
        commitAll("base");
        write("a.txt", "");
        assertTrue(git.run("add", "a.txt").isOk());
        write("a.txt", "new\n");

        byte[][] sides = backend.getWorkingTreeSides("a.txt");

        assertEquals("", new String(sides[0], StandardCharsets.UTF_8));
        assertEquals("new\n", new String(sides[1], StandardCharsets.UTF_8));
    }

    /** Both sides from one cat-file, as stored - a textconv driver configured or not. */
    @Test
    void commitSidesAreEachSidesStoredBytes() throws Exception {
        write(".gitattributes", "*.dat diff=shout\n");
        write("x.dat", "quiet\n");
        String base = commitAll("base");
        write("x.dat", "still quiet\n");
        String next = commitAll("next");
        git.run("config", "diff.shout.textconv", "tr a-z A-Z <");

        assertEquals("quiet\n", new String(backend.getFileBytesAtCommit(base, "x.dat"), StandardCharsets.UTF_8));
        CommitInfo commit = backend.getCommitsInRange(base, next, 1).get(0);
        byte[][] sides = backend.getCommitSides(commit, "x.dat");
        assertEquals("quiet\n", new String(sides[0], StandardCharsets.UTF_8));
        assertEquals("still quiet\n", new String(sides[1], StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 13: close while a call is in flight
    // ------------------------------------------------------------------

    @Test
    void aClosedBackendRefusesRatherThanThrowingNullPointers() {
        backend.close();

        assertFalse(backend.isOpen());
        assertNull(backend.getWorkTree());
        assertNull(backend.getDirectory());
        assertThrows(IllegalStateException.class, backend::getStagedFiles);
        var refused = NativeGitRunner.refusing(repoDir, "the repository was closed").run("status");
        assertFalse(refused.isOk());
        assertTrue(refused.stderr().contains("the repository was closed"), refused::stderr);
    }

    // ------------------------------------------------------------------
    // 1: submodule updates through the network path
    // ------------------------------------------------------------------

    /**
     * The update after a checkout fetched with no prompt handling at all: ssh's question
     * went to a terminal nobody could see and the worker waited for ever. It now runs
     * with everything that could prompt switched off, as a background fetch does.
     */
    @Test
    void theAutomaticSubmoduleUpdateCannotPrompt() throws Exception {
        boolean before = AppPreferences.autoUpdateSubmodules();
        AppPreferences.setAutoUpdateSubmodules(true);
        try {
            File log = submoduleWhoseFetchGoesThroughAFakeSsh();

            assertTrue(backend.checkoutBranch("next").isOk());

            String seen = Files.exists(log.toPath()) ? Files.readString(log.toPath()) : "";
            assertTrue(seen.contains("require=never"), "ssh ran with SSH_ASKPASS_REQUIRE=never: " + seen);
            assertTrue(seen.contains("prompt=0"), seen);
        } finally {
            AppPreferences.setAutoUpdateSubmodules(before);
        }
    }

    /** As on the JGit engine: no folder to open until the submodule is initialised. */
    @Test
    void anUninitialisedSubmoduleHasNoWorkTree() throws Exception {
        submoduleWhoseFetchGoesThroughAFakeSsh();
        File lib = backend.submoduleWorkTree("lib");
        assertNotNull(lib);
        assertEquals(new File(repoDir, "lib").getCanonicalFile(), lib.getCanonicalFile());

        assertTrue(git.run("submodule", "deinit", "-q", "-f", "--", "lib").isOk());

        assertNull(backend.submoduleWorkTree("lib"));
        assertNull(backend.submoduleWorkTree("no-such-folder"));
    }

    /** An explicit update gets the prompt bridge, as a fetch does. */
    @Test
    void anExplicitSubmoduleUpdateCanAsk() throws Exception {
        assumeTrue(AskpassBridge.shared() != null, "the prompt bridge cannot run here");
        File log = submoduleWhoseFetchGoesThroughAFakeSsh();
        assertTrue(git.run("checkout", "-q", "next").isOk());

        backend.updateSubmodules(List.of("lib"));

        String seen = Files.exists(log.toPath()) ? Files.readString(log.toPath()) : "";
        assertTrue(seen.contains("require=prefer"), "ssh ran with the bridge's askpass: " + seen);
    }

    /**
     * A superproject on main recording a submodule commit the submodule has, and a branch
     * "next" recording one it has not - so updating to it has to fetch, from an ssh://
     * remote whose "ssh" writes down the environment it was started with and fails.
     *
     * @return the file the fake ssh writes to
     */
    private File submoduleWhoseFetchGoesThroughAFakeSsh() throws Exception {
        File lib = tempDir.resolve("lib").toFile();
        assertTrue(lib.mkdirs());
        NativeGitRunner libGit = new NativeGitRunner(lib);
        assertTrue(libGit.run("init", "-b", "main").isOk());
        configure(libGit);
        Files.writeString(new File(lib, "l.txt").toPath(), "1\n");
        libGit.run("add", "-A");
        assertTrue(libGit.run("commit", "-q", "-m", "one").isOk());

        write("a.txt", "1\n");
        commitAll("base");
        var added = git.run("-c", "protocol.file.allow=always", "submodule", "add", "-q",
                lib.getAbsolutePath(), "lib");
        assertTrue(added.isOk(), added::stderr);
        commitAll("add lib");

        Files.writeString(new File(lib, "l.txt").toPath(), "2\n");
        libGit.run("add", "-A");
        assertTrue(libGit.run("commit", "-q", "-m", "two").isOk());
        String two = libGit.run("rev-parse", "HEAD").trimmedStdout();
        assertTrue(git.run("checkout", "-q", "-b", "next").isOk());
        assertTrue(git.run("update-index", "--cacheinfo", "160000," + two + ",lib").isOk());
        assertTrue(git.run("commit", "-q", "-m", "lib two").isOk());
        assertTrue(git.run("checkout", "-q", "main").isOk());
        assertTrue(git.run("submodule", "update", "-q").isOk());

        File log = tempDir.resolve("ssh.log").toFile();
        File fakeSsh = tempDir.resolve("fake-ssh.sh").toFile();
        Files.writeString(fakeSsh.toPath(), "#!/bin/sh\n"
                + "echo \"require=$SSH_ASKPASS_REQUIRE prompt=$GIT_TERMINAL_PROMPT\" >> '"
                + log.getAbsolutePath().replace('\\', '/') + "'\nexit 1\n");
        NativeGitRunner sub = new NativeGitRunner(new File(repoDir, "lib"));
        assertTrue(sub.run("remote", "set-url", "origin", "ssh://jfx.invalid/lib.git").isOk());
        // In both: the submodule's own config is what its fetch reads, and the
        // superproject's is what the engine reads to build GIT_SSH_COMMAND - which, set,
        // would win over the submodule's. Not an ssh program, so the engine leaves it be.
        String command = "sh '" + fakeSsh.getAbsolutePath().replace('\\', '/') + "'";
        assertTrue(sub.run("config", "core.sshCommand", command).isOk());
        assertTrue(git.run("config", "core.sshCommand", command).isOk());
        return log;
    }

    // ------------------------------------------------------------------
    // 14: clone and init on the CLI
    // ------------------------------------------------------------------

    @Test
    void initNamesTheBranchFromPreferencesAndWritesTemplatesUnstaged() throws Exception {
        AppPreferences.setDefaultBranchName("trunk");
        try {
            File dir = tempDir.resolve("fresh").toFile();
            var templates = new RepoInitTemplates("Java", "MIT License", "Ada Lovelace", true);

            GitResult result = NativeGitBackend.initRepository(dir, templates);

            assertTrue(result.isOk(), result.getMessage());
            NativeGitRunner fresh = new NativeGitRunner(dir);
            assertEquals("refs/heads/trunk", fresh.run("symbolic-ref", "HEAD").trimmedStdout());
            assertTrue(new File(dir, "README.md").isFile());
            assertTrue(fresh.run("status", "--porcelain").stdout().contains("?? README.md"));
        } finally {
            AppPreferences.setDefaultBranchName(null);
        }
    }

    @Test
    void initOnAnExistingRepositoryLeavesItsBranchAlone() throws Exception {
        write("a.txt", "1\n");
        commitAll("base");

        assertTrue(NativeGitBackend.initRepository(repoDir).isOk());

        assertEquals("refs/heads/main", git.run("symbolic-ref", "HEAD").trimmedStdout());
    }

    /** A source with three commits on main and a fourth on feature. */
    private File cloneSource() throws Exception {
        File source = tempDir.resolve("source").toFile();
        assertTrue(source.mkdirs());
        NativeGitRunner src = new NativeGitRunner(source);
        assertTrue(src.run("init", "-b", "main").isOk());
        configure(src);
        for (String name : List.of("one", "two", "three")) {
            Files.writeString(new File(source, name + ".txt").toPath(), name);
            src.run("add", "-A");
            assertTrue(src.run("commit", "-q", "-m", "add " + name).isOk());
        }
        assertTrue(src.run("checkout", "-q", "-b", "feature").isOk());
        Files.writeString(new File(source, "feature.txt").toPath(), "feature");
        src.run("add", "-A");
        assertTrue(src.run("commit", "-q", "-m", "add feature").isOk());
        assertTrue(src.run("checkout", "-q", "main").isOk());
        return source;
    }

    private static int commitCount(File dir) {
        return Integer.parseInt(new NativeGitRunner(dir).run("rev-list", "--count", "HEAD").trimmedStdout());
    }

    @Test
    void cloneCarriesTheFullHistoryByDefault() throws Exception {
        File source = cloneSource();
        File target = tempDir.resolve("clones/full").toFile();

        GitResult result = NativeGitBackend.cloneRepository(source.getAbsolutePath(), target);

        assertTrue(result.isOk(), result.getMessage());
        assertEquals(3, commitCount(target));
    }

    @Test
    void cloneChecksOutTheBranchAskedFor() throws Exception {
        File source = cloneSource();
        File target = tempDir.resolve("clones/branch").toFile();

        GitResult result = NativeGitBackend.cloneRepository(source.getAbsolutePath(), target, "feature", 0);

        assertTrue(result.isOk(), result.getMessage());
        assertEquals("refs/heads/feature", new NativeGitRunner(target).run("symbolic-ref", "HEAD").trimmedStdout());
        assertTrue(new File(target, "feature.txt").exists());
    }

    /** git ignores a depth for a local path unless told to use the transport. */
    @Test
    void aDepthLimitsTheHistoryButKeepsEveryBranch() throws Exception {
        File source = cloneSource();
        File target = tempDir.resolve("clones/shallow").toFile();

        GitResult result = NativeGitBackend.cloneRepository(source.getAbsolutePath(), target, null, 1);

        assertTrue(result.isOk(), result.getMessage());
        assertEquals(1, commitCount(target));
        assertTrue(new NativeGitRunner(target).run("rev-parse", "--verify", "--quiet",
                "refs/remotes/origin/feature").isOk(), "every branch, as the JGit engine clones");
    }

    @Test
    void cloningABranchThatDoesNotExistFailsAndLeavesNothing() throws Exception {
        File source = cloneSource();
        File target = tempDir.resolve("clones/missing").toFile();

        GitResult result = NativeGitBackend.cloneRepository(source.getAbsolutePath(), target, "no-such-branch", 0);

        assertFalse(result.isOk());
        assertFalse(target.exists(), "git removes the folder it made");
    }

    @Test
    void aCancelledCloneRemovesWhatItWrote() throws Exception {
        File source = cloneSource();
        File target = tempDir.resolve("clones/cancelled").toFile();

        GitResult result = NativeGitBackend.cloneRepository(source.getAbsolutePath(), target, null, 0, () -> true);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().startsWith("Clone cancelled"), result.getMessage());
        assertFalse(target.exists(), result.getMessage());
    }

    @Test
    void cloneRefusesAFolderThatIsNotEmpty() throws Exception {
        File source = cloneSource();
        File target = tempDir.resolve("clones/busy").toFile();
        assertTrue(target.mkdirs());
        Files.writeString(new File(target, "keep.txt").toPath(), "mine");

        assertFalse(NativeGitBackend.cloneRepository(source.getAbsolutePath(), target).isOk());
        assertTrue(new File(target, "keep.txt").isFile());
    }
}
