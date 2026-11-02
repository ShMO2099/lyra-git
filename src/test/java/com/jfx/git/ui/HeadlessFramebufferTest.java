package com.jfx.git.ui;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Keeps Monocle's upload framebuffer large enough for the largest shown test scenes. */
class HeadlessFramebufferTest {

    @Test
    void headlessScreenFitsLargestRobotScenes() {
        assumeTrue("Monocle".equalsIgnoreCase(System.getProperty("glass.platform"))
                        && "Headless".equalsIgnoreCase(System.getProperty("monocle.platform")),
                "the virtual framebuffer requirement applies only to Monocle Headless");

        Rectangle2D bounds = FxHarness.call(() -> Screen.getPrimary().getBounds());
        assertTrue(bounds.getWidth() >= 1400,
                () -> "Monocle width " + bounds.getWidth()
                        + " cannot render the 1400-pixel graph-width test scene");
        assertTrue(bounds.getHeight() >= 900,
                () -> "Monocle height " + bounds.getHeight()
                        + " cannot render the 900-pixel robot test scene");
    }
}
