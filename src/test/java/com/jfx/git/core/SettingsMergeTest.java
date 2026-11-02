package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Two running instances share one settings file without erasing each other's keys.
 *
 * <p>A save used to write this process's whole in-memory copy, so a second instance that
 * had loaded the file earlier wiped out a token the first had just remembered, the next
 * time it saved its tab order. "The other instance" here is a direct write to the file
 * behind this process's back, which is exactly what it looks like from inside.
 */
class SettingsMergeTest {

    @TempDir
    Path dir;

    private Path file;

    @BeforeEach
    void useTempFile() {
        file = dir.resolve("settings.properties");
        Settings.useFile(file);
    }

    @AfterEach
    void restoreDefault() {
        Settings.useFile(null);
    }

    private Properties onDisk() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return p;
    }

    /** What another instance does: read the file, change it, write it back. */
    private void otherInstance(java.util.function.Consumer<Properties> change) throws IOException {
        Properties p = Files.exists(file) ? onDisk() : new Properties();
        change.accept(p);
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, null);
        }
    }

    @Test
    void aKeyAnotherInstanceAddedSurvivesThisOnesSave() throws IOException {
        Settings.put("tabs.active", "0");
        Settings.flush();
        assertEquals("0", Settings.get("tabs.active", null), "loaded");

        otherInstance(p -> p.setProperty("integration.token.github@github.com", "ciphertext"));

        Settings.put("tabs.active", "1");
        Settings.flush();

        Properties disk = onDisk();
        assertEquals("1", disk.getProperty("tabs.active"), "this instance's change is written");
        assertEquals("ciphertext", disk.getProperty("integration.token.github@github.com"),
                "and the other instance's token is still there");
        assertEquals("ciphertext", Settings.get("integration.token.github@github.com", null),
                "and readable here from now on");
    }

    @Test
    void aRemovalHereIsNotUndoneAndNothingElseIsLost() throws IOException {
        Settings.put("a", "1");
        Settings.put("b", "2");
        Settings.flush();

        otherInstance(p -> p.setProperty("c", "3"));
        Settings.remove("a");
        Settings.flush();

        Properties disk = onDisk();
        assertNull(disk.getProperty("a"), "removed here");
        assertEquals("2", disk.getProperty("b"));
        assertEquals("3", disk.getProperty("c"), "the other instance's key survives");
    }

    @Test
    void removingAKeyOnlyAnotherInstanceWroteRemovesIt() throws IOException {
        Settings.put("x", "1");
        Settings.flush();
        otherInstance(p -> p.setProperty("token", "secret"));

        Settings.remove("token");   // e.g. Disconnect, in a window opened before the token
        Settings.flush();

        assertFalse(onDisk().containsKey("token"), "Disconnect means gone, wherever it was written");
        assertEquals("1", onDisk().getProperty("x"));
    }
}
