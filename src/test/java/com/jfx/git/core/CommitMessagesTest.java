package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commit box's memory: recent messages and the unsent draft, per repository, on disk.
 *
 * <p>Every test points the store at its own file first, for the reason {@link SettingsTest}
 * gives: otherwise these would write fixtures into the developer's real profile. "After a
 * restart" is a {@link CommitMessages#useFile} back to the same file, which drops what was
 * in memory and reads the disk again.
 */
class CommitMessagesTest {

    @TempDir
    Path tempDir;

    private Path file;
    private File repo;
    private File other;

    @BeforeEach
    void useTempFile() throws Exception {
        file = tempDir.resolve("commit-messages.json");
        CommitMessages.useFile(file);
        repo = Files.createDirectories(tempDir.resolve("repo")).toFile();
        other = Files.createDirectories(tempDir.resolve("other")).toFile();
    }

    @AfterEach
    void restoreDefault() {
        CommitMessages.useFile(null);
    }

    /** Writes what is pending and forgets it, as closing and reopening the application would. */
    private void restart() {
        CommitMessages.flush();
        CommitMessages.useFile(file);
    }

    @Test
    void theHistoryIsNewestFirstAndSurvivesARestart() {
        CommitMessages.remember(repo, "first");
        CommitMessages.remember(repo, "second\n\nwith a body");
        restart();

        assertEquals(List.of("second\n\nwith a body", "first"), CommitMessages.history(repo));
    }

    @Test
    void theHistoryKeepsTheLastTwenty() {
        for (int i = 1; i <= 25; i++) {
            CommitMessages.remember(repo, "message " + i);
        }
        restart();

        List<String> history = CommitMessages.history(repo);
        assertEquals(CommitMessages.HISTORY_LIMIT, history.size());
        assertEquals("message 25", history.get(0));
        assertEquals("message 6", history.get(19));
    }

    @Test
    void committingTheSameMessageAgainMovesItToTheFront() {
        CommitMessages.remember(repo, "fix typo");
        CommitMessages.remember(repo, "add tests");
        CommitMessages.remember(repo, "fix typo");

        assertEquals(List.of("fix typo", "add tests"), CommitMessages.history(repo));
    }

    @Test
    void eachRepositoryHasItsOwn() {
        CommitMessages.remember(repo, "in repo");
        CommitMessages.saveDraft(other, "half written in other");
        restart();

        assertEquals(List.of("in repo"), CommitMessages.history(repo));
        assertEquals("", CommitMessages.draft(repo));
        assertEquals(List.of(), CommitMessages.history(other));
        assertEquals("half written in other", CommitMessages.draft(other));
    }

    @Test
    void aDraftSurvivesARestartUntilItIsCommitted() {
        CommitMessages.saveDraft(repo, "Half a thought\n\nand its body");
        restart();
        assertEquals("Half a thought\n\nand its body", CommitMessages.draft(repo));

        CommitMessages.remember(repo, "Half a thought, finished");
        restart();
        assertEquals("", CommitMessages.draft(repo), "committing sends the draft");
    }

    @Test
    void aClearedDraftStaysCleared() {
        CommitMessages.saveDraft(repo, "something");
        CommitMessages.flush();
        CommitMessages.clearDraft(repo);
        restart();

        assertEquals("", CommitMessages.draft(repo));
    }

    @Test
    void draftsAreWrittenAfterAPauseNotAtEveryKey() throws Exception {
        CommitMessages.saveDraft(repo, "t");
        CommitMessages.saveDraft(repo, "ty");
        CommitMessages.saveDraft(repo, "typed");
        assertFalse(Files.exists(file), "nothing is written while the user is still typing");

        long deadline = System.currentTimeMillis() + 10_000;
        while (!Files.exists(file) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"typed\""),
                "and the last text is what reaches the disk");
    }

    /** A second window's history is not erased by this one's save. */
    @Test
    void aSaveKeepsWhatAnotherWindowWrote() throws Exception {
        CommitMessages.remember(repo, "from this window");
        CommitMessages.flush();

        // Another instance: its own memory, the same file.
        String disk = Files.readString(file, StandardCharsets.UTF_8);
        String key = other.getCanonicalPath().replace("\\", "\\\\");
        String withOther = disk.replaceFirst("\"repositories\": \\{",
                "\"repositories\": {\n    \"" + java.util.regex.Matcher.quoteReplacement(key)
                        + "\": {\"history\": [\"from the other window\"], \"draft\": \"\", \"touched\": 1},");
        Files.writeString(file, withOther, StandardCharsets.UTF_8);

        CommitMessages.remember(repo, "again from this window");
        restart();

        assertEquals(List.of("from the other window"), CommitMessages.history(other));
        assertEquals(List.of("again from this window", "from this window"), CommitMessages.history(repo));
    }

    @Test
    void anUnreadableFileIsStartedAfresh() throws Exception {
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        CommitMessages.useFile(file);

        assertEquals(List.of(), CommitMessages.history(repo));
        CommitMessages.remember(repo, "works anyway");
        restart();
        assertEquals(List.of("works anyway"), CommitMessages.history(repo));
    }

    @Test
    void theFileLivesBesideTheSettings() {
        CommitMessages.useFile(null);
        assertEquals(Settings.file().resolveSibling("commit-messages.json"), CommitMessages.file());
    }
}
