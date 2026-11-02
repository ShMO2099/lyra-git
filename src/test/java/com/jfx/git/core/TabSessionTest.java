package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The record of which repositories were open and what they are called.
 *
 * <p>Points the store at a temporary file first, for the reason {@code SettingsTest}
 * gives: otherwise these read and overwrite the developer's own open tabs.
 */
class TabSessionTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void useTempFile() {
        Settings.useFile(tempDir.resolve("settings.properties"));
    }

    @AfterEach
    void restoreDefault() {
        Settings.useFile(null);
    }

    // ------------------------------------------------------------------
    // Remember Tabs
    // ------------------------------------------------------------------

    @Test
    void rememberingIsOnUntilTurnedOff() {
        assertTrue(TabSession.remember(), "someone who left four repositories open expects them back");
        TabSession.setRemember(false);
        assertFalse(TabSession.remember());
    }

    @Test
    void nothingIsRestoredWhileRememberingIsOff() {
        TabSession.setOpenPaths(List.of("/a", "/b"), 0);
        TabSession.setRemember(false);
        assertTrue(TabSession.openPaths().isEmpty(),
                "the setting has to gate the read, not only the write");
    }

    // ------------------------------------------------------------------
    // The open set
    // ------------------------------------------------------------------

    @Test
    void pathsRoundTripInStripOrder() {
        TabSession.setOpenPaths(List.of("/one", "/two", "/three"), 1);

        assertEquals(List.of("/one", "/two", "/three"), TabSession.openPaths());
        assertEquals(1, TabSession.activeIndex());
    }

    @Test
    void aWindowsPathSurvivesTheRoundTrip() {
        String path = "C:\\Users\\someone\\Projects\\my repo";
        TabSession.setOpenPaths(List.of(path), 0);
        assertEquals(List.of(path), TabSession.openPaths(),
                "backslashes are the escape character in a properties file");
    }

    @Test
    void anEmptyListClearsTheRecord() {
        TabSession.setOpenPaths(List.of("/one"), 0);
        TabSession.setOpenPaths(List.of(), 0);

        assertTrue(TabSession.openPaths().isEmpty(),
                "closing every tab and quitting must not reopen the session before");
    }

    @Test
    void theActiveIndexIsClampedToWhatWasStored() {
        TabSession.setOpenPaths(List.of("/one", "/two"), 9);
        assertEquals(1, TabSession.activeIndex(), "past the end is the last tab, not a crash");
    }

    @Test
    void anUnreadableActiveIndexFallsBackToTheFirstTab() {
        TabSession.setOpenPaths(List.of("/one", "/two"), 1);
        Settings.put("tabs.active", "not a number");
        assertEquals(0, TabSession.activeIndex());
    }

    // ------------------------------------------------------------------
    // Aliases
    // ------------------------------------------------------------------

    @Test
    void anAliasRoundTrips() {
        TabSession.setAlias("/work/repo", "work");
        assertEquals("work", TabSession.alias("/work/repo"));
    }

    @Test
    void anAliasOutlivesTheTabBeingClosed() {
        TabSession.setAlias("/work/repo", "work");
        TabSession.setOpenPaths(List.of(), 0);

        assertEquals("work", TabSession.alias("/work/repo"),
                "a name that vanishes when the tab closes is a note, not a name");
    }

    @Test
    void severalAliasesCoexist() {
        TabSession.setAlias("/a", "first");
        TabSession.setAlias("/b", "second");

        assertEquals("first", TabSession.alias("/a"));
        assertEquals("second", TabSession.alias("/b"));
        assertEquals(2, TabSession.aliases().size());
    }

    @Test
    void aBlankAliasForgetsTheNameRatherThanStoringNothing() {
        TabSession.setAlias("/a", "first");
        TabSession.setAlias("/a", "   ");

        assertNull(TabSession.alias("/a"));
        assertTrue(TabSession.aliases().isEmpty(), "the file must not accumulate empty records");
    }

    @Test
    void anUnnamedRepositoryHasNoAlias() {
        assertNull(TabSession.alias("/never/named"));
        assertNull(TabSession.alias(null));
    }

    @Test
    void aMalformedRecordIsDroppedRatherThanGuessedAt() {
        Settings.put("tabs.aliases", "/good\tname\nno-separator-here\n\tleading-tab");

        assertEquals(1, TabSession.aliases().size(),
                "guessing at a half-written path opens the wrong repository");
        assertEquals("name", TabSession.alias("/good"));
    }

    @Test
    void aNameContainingTheSeparatorIsRefusedRatherThanCorruptingTheFile() {
        TabSession.setAlias("/a", "first");
        TabSession.setAlias("/a", "two\tfields");

        assertEquals("first", TabSession.alias("/a"), "the earlier name must survive intact");
    }

    // ------------------------------------------------------------------
    // N8-1. Paths carrying the separators the format is built from
    // ------------------------------------------------------------------

    /**
     * Records are newline-separated and their fields tab-separated, so a path containing
     * either would make one record read as two - or as a path and an alias that were
     * never written together.
     *
     * <p>It does not: such a path is refused on the way in, so the file cannot be
     * corrupted by one. What is lost is that one tab's place in the session, and only for
     * a path with a newline or tab in it, which on Windows is impossible and on Linux is
     * pathological. These pin that behaviour, which was easier to mistake for corruption
     * than to read.
     */
    @Test
    void aPathContainingARecordSeparatorCannotCorruptTheOthers() {
        TabSession.setOpenPaths(List.of("/home/dev/one", "/home/dev/od\nd", "/home/dev/two"), 0);

        assertEquals(List.of("/home/dev/one", "/home/dev/two"), TabSession.openPaths(),
                "the newline-bearing path is dropped; the others are untouched");
    }

    @Test
    void aPathContainingAFieldSeparatorIsRefusedToo() {
        TabSession.setOpenPaths(List.of("/home/dev/one", "/home/dev/o\td", "/home/dev/two"), 0);

        assertEquals(List.of("/home/dev/one", "/home/dev/two"), TabSession.openPaths());
    }

    /** An alias is user-typed, so it is the likelier of the two to carry a tab. */
    @Test
    void anAliasContainingASeparatorIsNotStored() {
        TabSession.setAlias("/home/dev/one", "good name");
        TabSession.setAlias("/home/dev/one", "bad\tname");

        assertEquals("good name", TabSession.alias("/home/dev/one"),
                "the rejected alias must not have replaced or split the stored one");
    }

    /** Every ordinary path still round-trips, separators being the only exclusion. */
    @Test
    void ordinaryPathsWithSpacesAndUnicodeRoundTrip() {
        List<String> paths = List.of("/home/dev/my repo", "/home/dev/pr\u00f3jekt", "C:\\work\\repo");

        TabSession.setOpenPaths(paths, 1);

        assertEquals(paths, TabSession.openPaths());
        assertEquals(1, TabSession.activeIndex());
    }
}
