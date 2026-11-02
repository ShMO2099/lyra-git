package com.jfx.git.core;

import com.jfx.git.core.ExternalTools.DiffTarget;
import com.jfx.git.core.ExternalTools.MergeOutcome;
import com.jfx.git.core.ExternalTools.MergeResult;
import com.jfx.git.core.ExternalTools.Mode;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * External diff and merge tools, against a real repository and a real child process.
 *
 * <p>{@link FakeExternalTool} is the tool. It records what it was started with, or
 * resolves a conflict the way a person would, so the assertions are about what a tool
 * actually receives and what the repository looks like after it exits.
 */
@Tag("heavy")
class ExternalToolsTest {

    @TempDir
    Path root;

    private Path workTree;
    private GitService service;
    private String firstCommit;
    private String secondCommit;

    @BeforeEach
    void setUp() throws Exception {
        // A directory with a space in it: the path every substitution has to survive.
        workTree = Files.createDirectories(root.resolve("my repo"));
        try (Git git = Git.init().setDirectory(workTree.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(workTree.toFile());
        commit("src/a.txt", "one\n", "first");
        firstCommit = service.getHeadHash();
        commit("src/a.txt", "one\ntwo\n", "second");
        secondCommit = service.getHeadHash();
    }

    @AfterEach
    void tearDown() {
        AppPreferences.setDiffToolMode(Mode.NONE);
        AppPreferences.setDiffToolCommand("");
        AppPreferences.setMergeToolMode(Mode.NONE);
        AppPreferences.setMergeToolCommand("");
        if (service != null) {
            service.close();
        }
        ExternalTools.deleteScratch();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void commit(String path, String content, String message) throws Exception {
        Path file = workTree.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(path).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    /** {@code java -cp <this test's classpath> FakeExternalTool}, quoted for a command template. */
    private static String fakeTool() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return "\"" + java + "\" -cp \"" + System.getProperty("java.class.path") + "\" "
                + FakeExternalTool.class.getName();
    }

    private static List<String> waitForLines(Path file) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (!Files.exists(file)) {
            assertTrue(System.nanoTime() < deadline, "the tool never wrote " + file);
            Thread.sleep(50);
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    /** Two branches that changed {@code src/a.txt} differently, merged, and left conflicted. */
    private void makeConflict() throws Exception {
        assertTrue(service.createBranch("other", null, true).isOk());
        commit("src/a.txt", "one\ntheirs\n", "their change");
        assertTrue(service.checkoutBranch("main").isOk());
        commit("src/a.txt", "one\nours\n", "our change");
        assertTrue(new RepositoryCommands(service).merge("other", false).isConflict(), "the fixture should conflict");
        assertEquals(List.of("src/a.txt"), service.getConflictingFiles());
    }

    // ------------------------------------------------------------------
    // Command lines
    // ------------------------------------------------------------------

    private static Map<String, String> tokens() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put(ExternalTools.LOCAL, "C:\\tmp dir\\a\\x.txt");
        tokens.put(ExternalTools.REMOTE, "C:\\tmp dir\\b\\x.txt");
        tokens.put(ExternalTools.BASE, "C:\\tmp dir\\base\\x.txt");
        tokens.put(ExternalTools.MERGED, "C:\\work tree\\x.txt");
        return tokens;
    }

    @Test
    void placeholdersBecomePathsAndAPathWithSpacesStaysOneArgument() {
        List<String> command = ExternalTools.buildCommand(
                "\"C:\\Program Files\\Tool\\tool.exe\" --left $LOCAL --right=$REMOTE -o \"$MERGED\"",
                tokens(), List.of(ExternalTools.LOCAL, ExternalTools.REMOTE));

        assertEquals(List.of("C:\\Program Files\\Tool\\tool.exe", "--left", "C:\\tmp dir\\a\\x.txt",
                "--right=C:\\tmp dir\\b\\x.txt", "-o", "C:\\work tree\\x.txt"), command);
    }

    /** What a bare {@code meld} expects: the files, in order, after the program. */
    @Test
    void aCommandThatNamesNoPlaceholderGetsThePathsAppended() {
        assertEquals(List.of("meld", "C:\\tmp dir\\a\\x.txt", "C:\\tmp dir\\b\\x.txt"),
                ExternalTools.buildCommand("meld", tokens(), List.of(ExternalTools.LOCAL, ExternalTools.REMOTE)));
        assertEquals(List.of(), ExternalTools.buildCommand("   ", tokens(), List.of(ExternalTools.LOCAL)));
    }

    @Test
    void gitIsToldWhichTwoVersionsToCompare() {
        assertEquals(List.of("difftool", "--no-prompt", "--", "a.txt"),
                ExternalTools.difftoolArguments(DiffTarget.unstaged("a.txt")),
                "the index against the file is what git difftool shows with no revision");
        assertEquals(List.of("difftool", "--no-prompt", "--cached", "--", "a.txt"),
                ExternalTools.difftoolArguments(DiffTarget.staged("a.txt")));
        assertEquals(List.of("difftool", "--no-prompt", "aaa", "bbb", "--", "a.txt"),
                ExternalTools.difftoolArguments(DiffTarget.commit("a.txt", "aaa", "bbb")));
        assertEquals(List.of("difftool", "--no-prompt", "4b825dc642cb6eb9a060e54bf8d69288fbee4904", "bbb",
                        "--", "a.txt"),
                ExternalTools.difftoolArguments(DiffTarget.commit("a.txt", null, "bbb")),
                "a first commit is compared against the empty tree");
    }

    // ------------------------------------------------------------------
    // Conflict markers
    // ------------------------------------------------------------------

    @Test
    void aFileIsConflictedOnlyWhenItHasAllThreeMarkersInOrder() throws Exception {
        Path file = root.resolve("f.txt");

        Files.writeString(file, "a\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> other\nb\n");
        assertTrue(ExternalTools.hasConflictMarkers(file.toFile()));

        Files.writeString(file, "a\r\n<<<<<<< HEAD\r\nours\r\n=======\r\ntheirs\r\n>>>>>>> other\r\n");
        assertTrue(ExternalTools.hasConflictMarkers(file.toFile()), "CRLF files conflict too");

        Files.writeString(file, "Title\n=======\n\nA Markdown heading is not a conflict.\n");
        assertFalse(ExternalTools.hasConflictMarkers(file.toFile()));

        Files.writeString(file, "one\nours\n");
        assertFalse(ExternalTools.hasConflictMarkers(file.toFile()));

        assertTrue(ExternalTools.hasConflictMarkers(root.resolve("missing.txt").toFile()),
                "a file that cannot be read is not one to stage unseen");
    }

    // ------------------------------------------------------------------
    // Diff, with a custom command
    // ------------------------------------------------------------------

    @Test
    void aCommitIsOpenedAsItsTwoVersionsUnderTheFilesOwnName() throws Exception {
        Path out = root.resolve("recorded.txt");
        AppPreferences.setDiffToolMode(Mode.CUSTOM);
        AppPreferences.setDiffToolCommand(fakeTool() + " record \"" + out + "\" $LOCAL $REMOTE");

        GitResult result = ExternalTools.openDiff(service,
                DiffTarget.commit("src/a.txt", firstCommit, secondCommit));
        assertTrue(result.isOk(), result::getMessage);

        List<String> received = waitForLines(out);
        assertEquals(2, received.size(), "two paths, each one argument: " + received);
        File older = new File(received.get(0));
        File newer = new File(received.get(1));
        assertEquals("a.txt", older.getName(), "tools pick their highlighting from the extension");
        assertEquals("a.txt", newer.getName());
        assertEquals("one\n", Files.readString(older.toPath()));
        assertEquals("one\ntwo\n", Files.readString(newer.toPath()));
        assertFalse(older.canWrite(), "a committed version is not something to edit");
    }

    /** The side that is the working tree is the real file, so a tool that edits edits it. */
    @Test
    void anUnstagedChangeIsComparedAgainstTheRealFile() throws Exception {
        Files.writeString(workTree.resolve("src/a.txt"), "one\ntwo\nthree\n");
        Path out = root.resolve("recorded.txt");
        AppPreferences.setDiffToolMode(Mode.CUSTOM);
        AppPreferences.setDiffToolCommand(fakeTool() + " record \"" + out + "\"");

        assertTrue(ExternalTools.openDiff(service, DiffTarget.unstaged("src/a.txt")).isOk());

        List<String> received = waitForLines(out);
        assertEquals(2, received.size(), "with no placeholder both paths are appended: " + received);
        assertEquals("one\ntwo\n", Files.readString(Path.of(received.get(0))), "the staged version");
        assertEquals(workTree.resolve("src/a.txt").toFile().getAbsoluteFile(),
                new File(received.get(1)).getAbsoluteFile());
    }

    @Test
    void aFirstCommitIsComparedAgainstAnEmptyFile() throws Exception {
        Path out = root.resolve("recorded.txt");
        AppPreferences.setDiffToolMode(Mode.CUSTOM);
        AppPreferences.setDiffToolCommand(fakeTool() + " record \"" + out + "\" $LOCAL $REMOTE");

        assertTrue(ExternalTools.openDiff(service, DiffTarget.commit("src/a.txt", null, firstCommit)).isOk());

        List<String> received = waitForLines(out);
        assertEquals(0, Files.size(Path.of(received.get(0))));
        assertEquals("one\n", Files.readString(Path.of(received.get(1))));
    }

    @Test
    void withNoToolChosenOrNoCommandItSaysWhereToSetOne() {
        GitResult none = ExternalTools.openDiff(service, DiffTarget.unstaged("src/a.txt"));
        assertFalse(none.isOk());
        assertTrue(none.getMessage().contains("Settings"), none.getMessage());

        AppPreferences.setDiffToolMode(Mode.CUSTOM);
        GitResult blank = ExternalTools.openDiff(service, DiffTarget.unstaged("src/a.txt"));
        assertFalse(blank.isOk());
        assertTrue(blank.getMessage().contains("no command"), blank.getMessage());

        AppPreferences.setDiffToolCommand("no-such-diff-tool-anywhere $LOCAL $REMOTE");
        GitResult missing = ExternalTools.openDiff(service, DiffTarget.unstaged("src/a.txt"));
        assertFalse(missing.isOk(), "a tool that is not installed is a failure, not a silent nothing");
        assertTrue(missing.getMessage().contains("no-such-diff-tool-anywhere"), missing.getMessage());
    }

    @Test
    void scratchCopiesAreRemovedOnRequest() throws Exception {
        Path out = root.resolve("recorded.txt");
        AppPreferences.setDiffToolMode(Mode.CUSTOM);
        AppPreferences.setDiffToolCommand(fakeTool() + " record \"" + out + "\" $LOCAL $REMOTE");
        assertTrue(ExternalTools.openDiff(service,
                DiffTarget.commit("src/a.txt", firstCommit, secondCommit)).isOk());
        List<String> received = waitForLines(out);
        assertTrue(Files.exists(Path.of(received.get(0))));

        ExternalTools.deleteScratch();

        assertFalse(Files.exists(Path.of(received.get(0))), "read-only copies are deleted too");
        assertEquals(List.of(), ExternalTools.scratchDirectories());
    }

    // ------------------------------------------------------------------
    // Merge, with a custom command
    // ------------------------------------------------------------------

    @Test
    void aToolThatResolvesTheFileLeavesItResolvedButNotYetStaged() throws Exception {
        makeConflict();
        AppPreferences.setMergeToolMode(Mode.CUSTOM);
        AppPreferences.setMergeToolCommand(fakeTool() + " takeLocal $LOCAL $MERGED");

        MergeResult result = ExternalTools.openMerge(service, "src/a.txt");

        assertEquals(MergeOutcome.RESOLVED, result.outcome(), result.message());
        assertEquals("one\nours\n", Files.readString(workTree.resolve("src/a.txt")),
                "the tool was handed our side as $LOCAL and wrote it to $MERGED");
        assertEquals(List.of("src/a.txt"), service.getConflictingFiles(),
                "marking it resolved is the user's decision, so nothing is staged yet");
    }

    @Test
    void theToolIsGivenAllThreeSidesAndTheRealFile() throws Exception {
        makeConflict();
        Path out = root.resolve("recorded.txt");
        AppPreferences.setMergeToolMode(Mode.CUSTOM);
        AppPreferences.setMergeToolCommand(fakeTool() + " record \"" + out + "\" $BASE $LOCAL $REMOTE $MERGED");

        MergeResult result = ExternalTools.openMerge(service, "src/a.txt");

        List<String> received = Files.readAllLines(out);
        assertEquals(4, received.size(), received.toString());
        assertArrayEquals("one\ntwo\n".getBytes(), Files.readAllBytes(Path.of(received.get(0))), "base");
        assertArrayEquals("one\nours\n".getBytes(), Files.readAllBytes(Path.of(received.get(1))), "local");
        assertArrayEquals("one\ntheirs\n".getBytes(), Files.readAllBytes(Path.of(received.get(2))), "remote");
        assertEquals(workTree.resolve("src/a.txt").toFile().getAbsoluteFile(),
                new File(received.get(3)).getAbsoluteFile());
        assertEquals(MergeOutcome.UNRESOLVED, result.outcome(),
                "the tool exited cleanly but the markers are still there: " + result.message());
    }

    @Test
    void aToolThatExitsWithAnErrorLeavesTheFileConflicted() throws Exception {
        makeConflict();
        AppPreferences.setMergeToolMode(Mode.CUSTOM);
        AppPreferences.setMergeToolCommand(fakeTool() + " fail");

        MergeResult result = ExternalTools.openMerge(service, "src/a.txt");

        assertEquals(MergeOutcome.UNRESOLVED, result.outcome());
        assertTrue(result.message().contains("exited with 3"), result.message());
        assertTrue(ExternalTools.hasConflictMarkers(workTree.resolve("src/a.txt").toFile()));
    }

    @Test
    void withNoMergeToolChosenItFailsBeforeTouchingAnything() throws Exception {
        makeConflict();

        MergeResult none = ExternalTools.openMerge(service, "src/a.txt");
        assertEquals(MergeOutcome.FAILED, none.outcome());
        assertTrue(none.message().contains("Settings"), none.message());

        AppPreferences.setMergeToolMode(Mode.CUSTOM);
        assertEquals(MergeOutcome.FAILED, ExternalTools.openMerge(service, "src/a.txt").outcome(),
                "a custom tool with no command");

        AppPreferences.setMergeToolCommand("no-such-merge-tool-anywhere");
        MergeResult missing = ExternalTools.openMerge(service, "src/a.txt");
        assertEquals(MergeOutcome.FAILED, missing.outcome());
        assertTrue(missing.message().contains("no-such-merge-tool-anywhere"), missing.message());
    }

    // ------------------------------------------------------------------
    // The tool git is configured with
    // ------------------------------------------------------------------

    /** The same fake tool, written the way a user writes one into git's configuration. */
    private void configureGitTool(String kind, String arguments) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace('\\', '/');
        String cmd = "'" + java + "' -cp '" + System.getProperty("java.class.path") + "' "
                + FakeExternalTool.class.getName() + " " + arguments;
        try (Git git = Git.open(workTree.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString(kind.equals("merge") ? "merge" : "diff", null, "tool", "fake");
            config.setString(kind + "tool", "fake", "cmd", cmd);
            config.setBoolean(kind + "tool", "fake", "trustExitCode", true);
            config.setBoolean("mergetool", null, "keepBackup", false);
            config.save();
        }
    }

    @Test
    void gitsOwnMergeToolResolvesAndStages() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs a git executable");
        makeConflict();
        configureGitTool("merge", "takeLocal \"$LOCAL\" \"$MERGED\"");
        AppPreferences.setMergeToolMode(Mode.GIT_CONFIG);

        MergeResult result = ExternalTools.openMerge(service, "src/a.txt");

        assertEquals(MergeOutcome.RESOLVED_AND_STAGED, result.outcome(), result.message());
        assertEquals("one\nours\n", Files.readString(workTree.resolve("src/a.txt")));
        assertEquals(List.of(), service.getConflictingFiles(), "git mergetool stages what its tool resolved");
    }

    @Test
    void gitsOwnMergeToolLeavingTheConflictIsReportedAsUnresolved() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs a git executable");
        makeConflict();
        configureGitTool("merge", "fail");
        AppPreferences.setMergeToolMode(Mode.GIT_CONFIG);

        MergeResult result = ExternalTools.openMerge(service, "src/a.txt");

        assertEquals(MergeOutcome.UNRESOLVED, result.outcome(), result.message());
        assertEquals(List.of("src/a.txt"), service.getConflictingFiles());
    }

    @Test
    void gitsOwnDiffToolIsGivenTheTwoVersions() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs a git executable");
        Path out = root.resolve("recorded.txt");
        // Read while git still has its temporary files: the tool copies what it was given.
        configureGitTool("diff", "record '" + out.toString().replace('\\', '/') + "' \"$LOCAL\" \"$REMOTE\"");
        AppPreferences.setDiffToolMode(Mode.GIT_CONFIG);

        GitResult result = ExternalTools.openDiff(service,
                DiffTarget.commit("src/a.txt", firstCommit, secondCommit));
        assertTrue(result.isOk(), result::getMessage);

        List<String> received = waitForLines(out);
        assertEquals(2, received.size(), "git hands its tool the two versions: " + received);
    }
}
