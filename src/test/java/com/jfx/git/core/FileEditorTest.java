package com.jfx.git.core;


import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-place editor. The thing that has to hold is the round trip: what the viewer
 * decodes for display must encode back to the same bytes, or a one-line edit shows up
 * as a rewrite of the whole file.
 */
class FileEditorTest {

    @TempDir
    Path repo;

    private GitService service;

    @BeforeEach
    void setUp() throws Exception {
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(repo.toFile());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    // ------------------------------------------------------------------
    // Encoding round trips
    // ------------------------------------------------------------------

    @Test
    void plainAsciiRoundTrips() {
        byte[] original = "line one\nline two\n".getBytes(StandardCharsets.UTF_8);
        String decoded = TextEncoding.GUESS.decode(original);

        assertArrayEquals(original, TextEncoding.GUESS.encode(decoded, original));
    }

    @Test
    void crlfLineEndingsSurviveAnEdit() {
        byte[] original = "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8);
        String decoded = TextEncoding.GUESS.decode(original);
        // The viewer never strips CR, so the editor buffer still carries it.
        assertTrue(decoded.contains("\r\n"));

        byte[] saved = TextEncoding.GUESS.encode(decoded.replace("two", "three"), original);
        assertEquals("one\r\nthree\r\n", new String(saved, StandardCharsets.UTF_8));
    }

    @Test
    void aByteOrderMarkIsPutBackWhereItWas() {
        byte[] original = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        String decoded = TextEncoding.GUESS.decode(original);
        assertEquals("hi", decoded, "the BOM is not part of the text");

        assertArrayEquals(original, TextEncoding.GUESS.encode(decoded, original));
    }

    @Test
    void aFileWithNoMarkDoesNotGainOne() {
        byte[] original = "hi".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(original, TextEncoding.UTF_8.encode("hi", original));
    }

    @Test
    void choosingUtf8BomAddsOneEvenWhenTheOriginalHadNone() {
        byte[] saved = TextEncoding.UTF_8_BOM.encode("hi", "hi".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'}, saved);
    }

    @Test
    void aWideEncodingRoundTripsWithItsMark() {
        byte[] original = new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0};
        String decoded = TextEncoding.UTF_16LE.decode(original);
        assertEquals("hi", decoded);

        assertArrayEquals(original, TextEncoding.UTF_16LE.encode(decoded, original));
    }

    @Test
    void anExplicitEncodingIsUsedRatherThanGuessed() {
        byte[] saved = TextEncoding.UTF_16BE.encode("hi", new byte[0]);
        assertArrayEquals(new byte[]{0, 'h', 0, 'i'}, saved);
    }

    @Test
    void binaryContentCannotBeEncodedBack() {
        // hexDump is a rendering, not a decoding, so there is nothing to invert.
        assertThrows(IllegalStateException.class,
                () -> TextEncoding.BINARY.encode("00 01", new byte[]{0, 1}));
    }

    @Test
    void nullTextEncodesToAnEmptyFileRatherThanFailing() {
        assertArrayEquals(new byte[0], TextEncoding.UTF_8.encode(null, new byte[0]));
    }

    // ------------------------------------------------------------------
    // Writing to the working tree
    // ------------------------------------------------------------------

    @Test
    void savingReplacesTheWorkingTreeFile() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "before\n", StandardCharsets.UTF_8);

        GitResult result = service.writeWorkingTreeFile("a.txt",
                "after\n".getBytes(StandardCharsets.UTF_8));

        assertTrue(result.isOk(), result.getMessage());
        assertEquals("after\n", Files.readString(repo.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void anEditedFileShowsUpAsAnUnstagedChange() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "before\n", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("add a", false).isOk());

        assertTrue(service.writeWorkingTreeFile("a.txt",
                "edited\n".getBytes(StandardCharsets.UTF_8)).isOk());

        assertTrue(service.getStagingState().unstaged().contains("a.txt"),
                "the edit should be visible as a working-tree change");
    }

    @Test
    void savingAFileThatIsNotThereCreatesIt() throws Exception {
        // Editing a historical, stashed, or compared revision has to be able to save
        // somewhere even when that path was since deleted or renamed - see DiffViewer's
        // "Edit in Working Directory" button.
        GitResult result = service.writeWorkingTreeFile("nope.txt", new byte[]{1});
        assertTrue(result.isOk(), result.getMessage());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(repo.resolve("nope.txt")));
    }

    @Test
    void savingCreatesMissingParentDirectoriesToo() throws Exception {
        GitResult result = service.writeWorkingTreeFile("a/b/c.txt", "content".getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isOk(), result.getMessage());
        assertEquals("content", Files.readString(repo.resolve("a/b/c.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void savingWithNoRepositoryOpenIsRefused() {
        try (GitService closed = new GitService()) {
            assertFalse(closed.writeWorkingTreeFile("a.txt", new byte[0]).isOk());
            assertFalse(closed.writeWorkingTreeFile(null, new byte[0]).isOk());
        }
    }

    @Test
    void afullRoundTripThroughTheEncodingLeavesTheFileByteIdentical() throws Exception {
        byte[] original = "α β γ\nδ\n".getBytes(StandardCharsets.UTF_8);
        Files.write(repo.resolve("greek.txt"), original);

        // Exactly what the viewer does: read bytes, decode, hand back unchanged text.
        byte[] read = service.getWorkingTreeBytes("greek.txt");
        String shown = TextEncoding.GUESS.decode(read);
        assertTrue(service.writeWorkingTreeFile("greek.txt",
                TextEncoding.GUESS.encode(shown, read)).isOk());

        assertArrayEquals(original, Files.readAllBytes(repo.resolve("greek.txt")));
    }

    // ------------------------------------------------------------------
    // Opening externally
    // ------------------------------------------------------------------

    @Test
    void theWindowsLauncherIsNotAConsoleProgram() {
        List<String> command = FileOpener.command("Windows 11", "C:\\x\\y.java");
        // cmd.exe would flash a console window every time; rundll32 does not.
        assertEquals("rundll32", command.get(0));
        assertFalse(command.contains(""), "an empty argument confuses the Windows launcher");
        assertTrue(command.contains("C:\\x\\y.java"));
    }

    @Test
    void eachPlatformGetsItsOwnLauncher() {
        assertEquals("rundll32", FileOpener.command("Windows 11", "C:\\a.txt").get(0));
        assertEquals("xdg-open", FileOpener.command("Linux", "/tmp/a").get(0));
    }

    @Test
    void openingSomethingThatIsNotAFileIsReportedRatherThanAttempted() {
        GitResult missing = FileOpener.open(repo.resolve("absent.txt").toFile());
        assertFalse(missing.isOk());
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());

        assertFalse(FileOpener.open(null).isOk());
        // A directory is not a file to edit.
        assertFalse(FileOpener.open(repo.toFile()).isOk());
    }
}
