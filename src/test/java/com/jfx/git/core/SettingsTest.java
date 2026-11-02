package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The settings file that replaced {@code java.util.prefs}.
 *
 * <p>Every test points the store at a temporary file first. That is not politeness — it is
 * the property the whole change exists to establish: before this, anything reading a
 * setting during a test read the developer's own machine, and a graph-ordering preference
 * clicked in the GUI decided whether an unrelated lane assertion passed.
 */
class SettingsTest {

    @TempDir
    Path tempDir;

    private Path file;

    @BeforeEach
    void useTempFile() {
        file = tempDir.resolve("nested").resolve("settings.properties");
        Settings.useFile(file);
    }

    @AfterEach
    void restoreDefault() {
        // Back to whatever the surefire system property names, so one test cannot
        // strand the rest of the suite pointing at a deleted temp directory.
        Settings.useFile(null);
    }

    @Test
    void valuesSurviveBeingWrittenAndReadBack() {
        Settings.put("editor.command", "code --wait $FILE");
        Settings.flush();         // writes are coalesced, so ask for the file now
        Settings.useFile(file);   // drops the in-memory copy, forcing a real read

        assertEquals("code --wait $FILE", Settings.get("editor.command", "fallback"));
    }

    /** The directory from before the rename moves, with everything kept beside the settings. */
    @Test
    void theSettingsDirectoryFromBeforeTheRenameIsMovedAcross(@TempDir Path base) throws IOException {
        Path legacy = Files.createDirectories(base.resolve("JFxGit"));
        Files.writeString(legacy.resolve("settings.properties"), "theme=DARK\n");
        Files.writeString(legacy.resolve("commit-messages.json"), "[]");

        Path current = Settings.adoptLegacyDirectory(legacy, base.resolve("Lyra"));

        assertEquals(base.resolve("Lyra"), current);
        assertEquals("theme=DARK\n", Files.readString(current.resolve("settings.properties")));
        assertTrue(Files.exists(current.resolve("commit-messages.json")));
        assertFalse(Files.exists(legacy));
    }

    /** Once the new directory exists the old one is left alone, whatever it holds. */
    @Test
    void anExistingSettingsDirectoryWinsOverTheOldOne(@TempDir Path base) throws IOException {
        Path legacy = Files.createDirectories(base.resolve("JFxGit"));
        Path current = Files.createDirectories(base.resolve("Lyra"));

        assertEquals(current, Settings.adoptLegacyDirectory(legacy, current));
        assertTrue(Files.exists(legacy));
    }

    /**
     * Writes are coalesced, so a burst of them - what a drag on the graph's column
     * headers produces - is one rewrite of the file rather than one per key.
     */
    @Test
    void aBurstOfWritesIsNotOneFileWritePerKey() throws IOException {
        for (int i = 0; i < 50; i++) {
            Settings.put("graph.column." + i, String.valueOf(i));
        }

        assertFalse(Files.exists(file),
                "nothing should have been written yet - the burst has not settled");

        Settings.flush();
        assertTrue(Files.exists(file));
        String contents = Files.readString(file);
        assertTrue(contents.contains("graph.column.0=0"), contents);
        assertTrue(contents.contains("graph.column.49=49"), contents);
    }

    /** A value is readable the moment it is written, flushed or not. */
    @Test
    void aPendingValueIsStillReadable() {
        Settings.put("graph.lanes", "12");

        assertEquals("12", Settings.get("graph.lanes", null));
    }

    @Test
    void anAbsentKeyGivesTheFallback() {
        assertEquals("fallback", Settings.get("nothing.here", "fallback"));
        assertTrue(Settings.getBoolean("nothing.here", true));
        assertFalse(Settings.getBoolean("nothing.here", false));
    }

    @Test
    void writingCreatesTheDirectoryAndTheFile() {
        assertFalse(Files.exists(file));
        Settings.put("diff.split", "true");
        Settings.flush();

        assertTrue(Files.exists(file), "the parent directory should be created as needed");
    }

    @Test
    void theFileIsPlainTextAPersonCanRead() throws IOException {
        Settings.put("graph.order", "CHRONOLOGICAL");
        Settings.flush();
        String contents = Files.readString(file);

        // The whole complaint about the registry was that "CHRONOLOGICAL" came back as
        // "/C/H/R/O/N/O/L/O/G/I/C/A/L". It has to be legible here.
        assertTrue(contents.contains("graph.order=CHRONOLOGICAL"), contents);
    }

    @Test
    void removingAKeyLeavesTheRest() {
        Settings.put("a", "1");
        Settings.put("b", "2");
        Settings.remove("a");
        Settings.flush();
        Settings.useFile(file);

        assertNull(Settings.get("a", null));
        assertEquals("2", Settings.get("b", null));
    }

    @Test
    void enumsRoundTrip() {
        Settings.putEnum("graph.order", com.jfx.git.graph.GraphOrder.CHRONOLOGICAL);
        Settings.flush();
        Settings.useFile(file);

        assertEquals(com.jfx.git.graph.GraphOrder.CHRONOLOGICAL,
                Settings.getEnum("graph.order", com.jfx.git.graph.GraphOrder.class,
                        com.jfx.git.graph.GraphOrder.BY_BRANCH));
    }

    /** The file is meant to be hand-edited, so it will sometimes be hand-edited wrongly. */
    @Test
    void anUnrecognisedEnumValueFallsBackInsteadOfThrowing() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "graph.order=SIDEWAYS\n");
        Settings.useFile(file);

        assertEquals(com.jfx.git.graph.GraphOrder.BY_BRANCH,
                Settings.getEnum("graph.order", com.jfx.git.graph.GraphOrder.class,
                        com.jfx.git.graph.GraphOrder.BY_BRANCH));
    }

    /** A corrupt file must not stop the application starting. */
    @Test
    void anUnreadableFileGivesDefaults() throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01});
        Settings.useFile(file);

        assertEquals("fallback", Settings.get("anything", "fallback"));
    }

    /**
     * Paths keep their separators.
     *
     * <p>The recent-repository list packs several paths into one value separated by
     * newlines, which is exactly the shape a properties file has to escape correctly.
     */
    @Test
    void multiLineValuesRoundTrip() {
        String joined = "C:\\Users\\NOON\\one\nC:\\Users\\NOON\\two";
        Settings.put("repo.recent", joined);
        Settings.flush();
        Settings.useFile(file);

        assertEquals(joined, Settings.get("repo.recent", null));
    }

    @Test
    void theLocationFollowsTheSystemProperty() {
        Settings.useFile(null);
        String override = System.getProperty(Settings.LOCATION_PROPERTY);

        assumeSurefireConfigured(override);
        assertEquals(Path.of(override), Settings.file(),
                "the test suite must never touch the real settings file");
    }

    private static void assumeSurefireConfigured(String override) {
        org.junit.jupiter.api.Assumptions.assumeTrue(override != null && !override.isBlank(),
                "run through maven, where lyra.settings is set");
    }

    /** Settings written by one part of the app are visible to another immediately. */
    @Test
    void readsSeeWritesWithoutReloading() {
        Settings.put("ui.theme", "LIGHT");
        assertEquals("LIGHT", Settings.get("ui.theme", "DARK"));
    }
}
