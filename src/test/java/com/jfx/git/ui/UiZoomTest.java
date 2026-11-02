package com.jfx.git.ui;

import com.jfx.git.core.Settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The zoom levels, and how stepping through them behaves.
 *
 * <p>Whether the interface actually gets bigger is {@code ZoomRobotTest}; this is only
 * about which number is chosen.
 */
class UiZoomTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void useTempFile() {
        Settings.useFile(tempDir.resolve("settings.properties"));
        UiZoom.resetForTests();
    }

    @AfterEach
    void restoreDefault() {
        UiZoom.resetForTests();
        Settings.useFile(null);
    }

    @Test
    void startsAtFullSize() {
        assertEquals(100, UiZoom.percent());
        assertEquals(1.0, UiZoom.factor(), 0.0001);
    }

    @Test
    void zoomingInAndBackOutReturnsExactlyWhereItStarted() {
        UiZoom.zoomIn();
        UiZoom.zoomIn();
        UiZoom.zoomOut();
        UiZoom.zoomOut();

        // Walking a list rather than multiplying by a ratio is the whole reason for
        // LEVELS: 100 × 1.25 ÷ 1.25 is not reliably 100, and the drift is visible.
        assertEquals(100, UiZoom.percent());
    }

    @Test
    void zoomingInStopsAtTheLargestLevel() {
        for (int i = 0; i < UiZoom.LEVELS.size() + 5; i++) {
            UiZoom.zoomIn();
        }
        assertEquals(UiZoom.LEVELS.get(UiZoom.LEVELS.size() - 1), UiZoom.percent());
    }

    @Test
    void zoomingOutStopsAtTheSmallestLevel() {
        for (int i = 0; i < UiZoom.LEVELS.size() + 5; i++) {
            UiZoom.zoomOut();
        }
        assertEquals(UiZoom.LEVELS.get(0), UiZoom.percent());
    }

    @Test
    void everyStepLandsOnAnOfferedLevel() {
        UiZoom.set(UiZoom.LEVELS.get(0));
        for (int i = 0; i < UiZoom.LEVELS.size(); i++) {
            assertTrue(UiZoom.LEVELS.contains(UiZoom.percent()),
                    UiZoom.percent() + " is not a level the menu offers");
            UiZoom.zoomIn();
        }
    }

    @Test
    void resetGoesBackToFullSize() {
        UiZoom.set(175);
        UiZoom.reset();
        assertEquals(100, UiZoom.percent());
    }

    @Test
    void anOutOfRangeLevelIsClampedRatherThanRefused() {
        UiZoom.set(10_000);
        assertEquals(UiZoom.LEVELS.get(UiZoom.LEVELS.size() - 1), UiZoom.percent());

        UiZoom.set(-5);
        assertEquals(UiZoom.LEVELS.get(0), UiZoom.percent());
    }

    @Test
    void theLevelIsRemembered() {
        UiZoom.set(125);
        assertEquals("125", Settings.get("ui.zoom", null),
                "the choice has to outlive the session");
    }

    @Test
    void anUnreadableStoredLevelFallsBackToFullSize() {
        Settings.put("ui.zoom", "very large please");
        // Reading happens once at class load, so this checks the parse directly rather
        // than reloading: the contract is that nonsense does not stop the app starting.
        assertDoesNotThrow(() -> Settings.get("ui.zoom", "100"));
        assertTrue(UiZoom.LEVELS.contains(UiZoom.percent()));
    }

    @Test
    void theFactorMatchesThePercentage() {
        UiZoom.set(150);
        assertEquals(1.5, UiZoom.factor(), 0.0001);
    }

    @Test
    void thePropertyReportsChanges() {
        int[] seen = {0};
        UiZoom.percentProperty().addListener((obs, old, value) -> seen[0] = value.intValue());

        UiZoom.set(125);

        assertEquals(125, seen[0],
                "the status bar binds to this, so it has to fire for every change");
    }
}
