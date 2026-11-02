package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unsaved buffers surviving on disk.
 *
 * <p>Every test points the store at a temporary directory first, for the reason
 * {@link SettingsTest} gives about the settings file: without it these would write the
 * contents of test fixtures into the developer's real profile and leave them there.
 *
 * <p>The writes are asynchronous, so each test drains the writer thread before looking.
 */
class EditorDraftsTest {

    @TempDir
    Path tempDir;

    private File repository;

    @BeforeEach
    void useTempDirectory() throws Exception {
        EditorDrafts.useDirectory(tempDir.resolve("drafts"));
        AppPreferences.setEditorDraftsEnabled(true);
        repository = Files.createDirectories(tempDir.resolve("repo")).toFile();
    }

    @AfterEach
    void restoreDefaults() {
        EditorDrafts.useDirectory(null);
        AppPreferences.setEditorDraftsEnabled(true);
    }

    /** Waits for the single writer thread to work through what has been queued. */
    private static void drain() {
        EditorDrafts.awaitQuiet();
    }

    private Optional<EditorDrafts.Draft> saveAndRead(String path, String text) {
        EditorDrafts.save(repository, path, text, "baseline", "UTF-8");
        drain();
        return EditorDrafts.find(repository, path);
    }

    // ------------------------------------------------------------------

    @Test
    void aDraftComesBackExactlyAsItWentIn() {
        String text = "line one\nline two\n";

        Optional<EditorDrafts.Draft> draft = saveAndRead("src/App.java", text);

        assertTrue(draft.isPresent());
        assertEquals(text, draft.get().text());
        assertEquals("src/App.java", draft.get().path());
        assertEquals("UTF-8", draft.get().encoding());
    }

    /** Trailing newlines are part of the file, and losing one is a real diff. */
    @Test
    void trailingNewlinesSurvive() {
        assertEquals("a\n\n\n", saveAndRead("a.txt", "a\n\n\n").orElseThrow().text());
        assertEquals("no terminator", saveAndRead("b.txt", "no terminator").orElseThrow().text());
    }

    @Test
    void carriageReturnsSurvive() {
        assertEquals("a\r\nb\r\n", saveAndRead("crlf.txt", "a\r\nb\r\n").orElseThrow().text());
    }

    @Test
    void nonAsciiSurvives() {
        String text = "héllo — ☃\nвторая строка\n";
        assertEquals(text, saveAndRead("utf8.txt", text).orElseThrow().text());
    }

    /**
     * The one way a hand-rolled header format eats data.
     *
     * <p>The parser stops at the first blank line, so a body that happens to contain a line
     * reading {@code path: something} cannot be read back as a header - which is exactly
     * why the format needs no escaping for the part that is someone's work. This file's own
     * source would trip a parser that kept looking.
     */
    @Test
    void aBodyThatLooksLikeAHeaderIsStillJustABody() {
        String text = "first line\n\npath: not-really\nrepository: nor this\nsaved: 1999\n";

        EditorDrafts.Draft draft = saveAndRead("tricky.txt", text).orElseThrow();

        assertEquals(text, draft.text(), "the body must come back untouched");
        assertEquals("tricky.txt", draft.path(), "and the real header must still be the header");
    }

    @Test
    void aDraftIsKeyedByRepositoryAndPathTogether() throws Exception {
        File other = Files.createDirectories(tempDir.resolve("other-repo")).toFile();

        EditorDrafts.save(repository, "a.txt", "from repo one", "", "UTF-8");
        EditorDrafts.save(repository, "b.txt", "also repo one", "", "UTF-8");
        EditorDrafts.save(other, "a.txt", "from repo two", "", "UTF-8");
        drain();

        assertEquals("from repo one", EditorDrafts.find(repository, "a.txt").orElseThrow().text());
        assertEquals("also repo one", EditorDrafts.find(repository, "b.txt").orElseThrow().text());
        assertEquals("from repo two", EditorDrafts.find(other, "a.txt").orElseThrow().text());
    }

    @Test
    void discardingRemovesIt() {
        saveAndRead("a.txt", "something");
        assertTrue(EditorDrafts.find(repository, "a.txt").isPresent());

        EditorDrafts.discard(repository, "a.txt");
        drain();

        assertTrue(EditorDrafts.find(repository, "a.txt").isEmpty());
    }

    /** The temp file is an implementation detail and must not be left behind. */
    @Test
    void noTemporaryFileSurvivesAWrite() throws Exception {
        saveAndRead("a.txt", "something");

        try (var entries = Files.list(EditorDrafts.directory())) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void aDraftForARepositoryThatNoLongerExistsIsSweptAway() throws Exception {
        File doomed = Files.createDirectories(tempDir.resolve("doomed")).toFile();
        EditorDrafts.save(doomed, "a.txt", "orphan", "", "UTF-8");
        EditorDrafts.save(repository, "a.txt", "keep me", "", "UTF-8");
        drain();

        assertTrue(Files.deleteIfExists(doomed.toPath()), "the repository goes away");
        assertEquals(1, EditorDrafts.sweep());

        assertTrue(EditorDrafts.find(doomed, "a.txt").isEmpty());
        assertTrue(EditorDrafts.find(repository, "a.txt").isPresent(),
                "and the live repository's draft is untouched");
    }

    /** A draft saved before the application was renamed carries the old marker and still opens. */
    @Test
    void aDraftFromBeforeTheRenameIsStillRead() throws Exception {
        saveAndRead("a.txt", "kept");
        try (var entries = Files.list(EditorDrafts.directory())) {
            for (Path file : entries.toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Files.writeString(file, content.replaceFirst("^lyra-draft:", "jfxgit-draft:"),
                        StandardCharsets.UTF_8);
            }
        }

        assertEquals("kept", EditorDrafts.find(repository, "a.txt").orElseThrow().text());
    }

    @Test
    void anOldDraftIsSweptAndAFreshOneIsNot() throws Exception {
        saveAndRead("old.txt", "ancient");
        saveAndRead("new.txt", "recent");

        // Backdate one past the cutoff by rewriting its saved header.
        Path dir = EditorDrafts.directory();
        try (var entries = Files.list(dir)) {
            for (Path file : entries.toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.contains("path: old.txt")) {
                    Files.writeString(file, content.replaceAll("saved: .*",
                            "saved: " + java.time.Instant.now()
                                    .minus(Duration.ofDays(60))), StandardCharsets.UTF_8);
                }
            }
        }

        assertEquals(1, EditorDrafts.sweep());
        assertTrue(EditorDrafts.find(repository, "old.txt").isEmpty());
        assertTrue(EditorDrafts.find(repository, "new.txt").isPresent());
    }

    /** Writing megabytes every couple of seconds is not a safety feature. */
    @Test
    void aBufferTooLargeToDraftIsNotDrafted() {
        String huge = "x".repeat(EditorDrafts.MAX_DRAFT_CHARS + 1);

        EditorDrafts.save(repository, "huge.txt", huge, "", "UTF-8");
        drain();

        assertTrue(EditorDrafts.find(repository, "huge.txt").isEmpty());
    }

    /**
     * Turning the preference off has to stop the writing, not merely hide the feature -
     * the whole reason it exists is that some repositories must not be copied anywhere.
     */
    @Test
    void nothingIsWrittenWhenThePreferenceIsOff() {
        AppPreferences.setEditorDraftsEnabled(false);

        EditorDrafts.save(repository, "secret.java", "confidential", "", "UTF-8");
        drain();

        assertTrue(EditorDrafts.find(repository, "secret.java").isEmpty());
        assertFalse(Files.isDirectory(EditorDrafts.directory())
                        && EditorDrafts.all().size() > 0,
                "and nothing may have reached the directory at all");
    }

    /** A write that never completed leaves a .tmp nothing will ever read. */
    @Test
    void aTemporaryFileLeftByACrashIsSwept() throws Exception {
        Files.createDirectories(EditorDrafts.directory());
        Path leftover = EditorDrafts.directory().resolve("abc.draft.tmp");
        Files.writeString(leftover, "half a draft");

        EditorDrafts.sweep();

        assertFalse(Files.exists(leftover));
    }

    /** "Delete them" in Preferences has to mean the crash leftovers too. */
    @Test
    void discardingEverythingIncludesTemporaryFiles() throws Exception {
        saveAndRead("a.txt", "something");
        Path leftover = EditorDrafts.directory().resolve("abc.draft.tmp");
        Files.writeString(leftover, "half a draft");

        EditorDrafts.discardAll();

        try (var entries = Files.list(EditorDrafts.directory())) {
            assertEquals(0, entries.count(), "nothing of anyone's source should be left");
        }
    }

    /**
     * A repository on a drive that is not mounted is out of reach, not gone. Sweeping its
     * drafts at startup would destroy the work the feature exists to keep.
     */
    @Test
    void aDraftOnAnUnmountedVolumeIsKept() {
        File offline = tempDir.resolve("offline-volume").resolve("repo").toFile();
        EditorDrafts.save(offline, "a.txt", "keep me", "", "UTF-8");
        drain();

        assertEquals(0, EditorDrafts.sweep());
        assertTrue(EditorDrafts.find(offline, "a.txt").isPresent(),
                "the volume itself is missing, so there is no telling the repository is gone");
    }

    /**
     * The draft written is the latest buffer with the latest encoding, not the latest
     * text under whichever encoding was queued first.
     */
    @Test
    void theLatestBufferIsWrittenWithItsOwnEncoding() {
        var release = EditorDrafts.holdWriterForTest();
        EditorDrafts.save(repository, "a.txt", "first", "base", "UTF-8");
        EditorDrafts.save(repository, "a.txt", "second", "base", "UTF-16LE");
        release.countDown();
        drain();

        EditorDrafts.Draft draft = EditorDrafts.find(repository, "a.txt").orElseThrow();
        assertEquals("second", draft.text());
        assertEquals("UTF-16LE", draft.encoding(),
                "a draft restored under the wrong encoding is re-encoded wrongly on save");
    }

    /**
     * A write already under way when drafts are deleted cannot bring one back.
     *
     * <p>Stood in for by a file landing after the immediate sweep: that is what a write
     * that had already taken its text did.
     */
    @Test
    void aWriteFinishingAfterDeleteAllDoesNotSurviveIt() throws Exception {
        saveAndRead("a.txt", "something");
        var release = EditorDrafts.holdWriterForTest();
        EditorDrafts.discardAll();
        Path late = EditorDrafts.directory().resolve("late.draft");
        Files.writeString(late, "written by a write in flight");
        release.countDown();
        drain();

        assertFalse(Files.exists(late), "deleted means deleted");
    }

    /** The offer to delete counts what deleting removes, crash leftovers included. */
    @Test
    void theStoredCountIncludesTemporaryFiles() throws Exception {
        Files.createDirectories(EditorDrafts.directory());
        Files.writeString(EditorDrafts.directory().resolve("abc.draft.tmp"), "half a draft");
        assertEquals(1, EditorDrafts.storedFileCount());
    }
}
