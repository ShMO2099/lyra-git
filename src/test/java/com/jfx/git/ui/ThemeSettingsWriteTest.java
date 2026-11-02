package com.jfx.git.ui;

import com.jfx.git.core.Settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying the saved theme at startup leaves the settings file alone.
 *
 * <p>{@link ThemeManager#applySaved()} runs the same path as a user picking a theme, and
 * that path stored both theme keys every time - each store schedules a rewrite of the
 * whole file, so every launch rewrote it to say what it already said.
 */
class ThemeSettingsWriteTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void restore() {
        // Static FX state, reset on the FX thread so no later test inherits the light theme.
        FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.DARK));
        Settings.useFile(null);
    }

    @Test
    void reapplyingTheSavedThemeWritesNothing() throws Exception {
        Path file = tempDir.resolve("settings.properties");
        Settings.useFile(file);
        Settings.put("ui.theme", "LIGHT");
        Settings.flush();
        assertTrue(Files.exists(file), "precondition: the saved theme is on disk");
        Files.delete(file);

        FxHarness.run(ThemeManager::applySaved);
        Settings.flush();

        assertEquals(ThemeManager.Theme.LIGHT, FxHarness.call(ThemeManager::current));
        assertFalse(Files.exists(file), "applying what was saved must not rewrite the settings file");
    }

    @Test
    void choosingADifferentThemeIsStillRemembered() {
        Path file = tempDir.resolve("settings.properties");
        Settings.useFile(file);
        Settings.put("ui.theme", "DARK");
        Settings.flush();

        FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.LIGHT));

        assertEquals("LIGHT", Settings.get("ui.theme", null));
    }
}
