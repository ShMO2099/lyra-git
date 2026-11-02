package com.jfx.git.ui;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hunk buttons' words are actually green and red on screen, on a solid bar.
 *
 * <p>Worth testing against what was resolved and painted rather than by asserting a style
 * class, because the failure this guards against is invisible any other way: a mistyped
 * or renamed {@code -color-*} variable does not throw, it logs a warning nobody reads and
 * leaves the control with no colour at all. Every structural assertion - node exists, has
 * the right class, is visible, is the right size - passes just as happily over an
 * invisible control. So the theme is loaded for real and the result is read back.
 *
 * <p>The colour is on the words. The buttons used to be solid green and solid red, which
 * on a long diff was a column of saturated blocks louder than the changes beside it; the
 * bar is neutral now and the label carries the meaning.
 *
 * <p>Channels are compared against each other rather than against fixed values, so the
 * test still means "green words, red words" after any theme tweak, and does not have to
 * be rewritten every time a shade moves.
 */
class HunkActionColourTest {

    /**
     * Closes the off-screen stages this class opened.
     *
     * <p>Not housekeeping. A stage left showing goes on taking part in focus and layout
     * for the rest of the run, and the failures that causes land in whichever robot test
     * happens to follow - a click that reaches nothing, a dialog that "never opened".
     */
    @org.junit.jupiter.api.AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private static final String DIFF = """
            @@ -1,2 +1,2 @@
             context
            -removed
            +added
            """;

    /**
     * A viewer with the real AtlantaFX theme on its own scene.
     *
     * <p>Added to this one scene rather than through {@code Application
     * .setUserAgentStylesheet}, which is global: these tests share a JVM with every other
     * FxHarness test, and quietly giving all of them a real theme would change font
     * metrics and padding underneath assertions written without one.
     */
    private static DiffViewer themedViewer() {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 900, 600);
            stage.getScene().getStylesheets().addAll(
                    new atlantafx.base.theme.PrimerDark().getUserAgentStylesheet(),
                    DiffViewer.class.getResource("/css/theme-dark.css").toExternalForm());
            viewer.setOnHunkAction(action -> { });
            viewer.setFileDiff(DiffKind.UNSTAGED, "a.txt", DIFF);
            FxHarness.pulse(viewer);
            stage.setUserData("keep-alive");
            return viewer;
        });
    }

    /** The colour actually painted in a corner of {@code node}, clear of its text. */
    private static Color backgroundPixel(Node node) {
        WritableImage image = node.snapshot(new SnapshotParameters(), null);
        return image.getPixelReader().getColor(3, (int) (image.getHeight() / 2));
    }

    /** The colour {@code button}'s label resolved to, once the stylesheets have been applied. */
    private static Color textColour(Button button) {
        return (Color) button.getTextFill();
    }

    private static Button buttonWith(DiffViewer viewer, String styleClass, String text) {
        List<Node> found = FxHarness.lookupAll(viewer, styleClass);
        return found.stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()) && n.isVisible())
                .map(n -> (Button) n)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no visible \"" + text + "\" button"));
    }

    @Test
    void stagingIsGreenAndRevertingIsRed() {
        DiffViewer viewer = themedViewer();
        FxHarness.run(() -> {
            Color stage = textColour(buttonWith(viewer, "diff-hunk-action", "Stage hunk"));
            Color revert = textColour(buttonWith(viewer, "diff-revert-hunk", "Discard hunk"));

            assertTrue(stage.getGreen() > stage.getRed() + 0.1,
                    "Stage should read as green, was " + stage);
            assertTrue(stage.getGreen() > stage.getBlue() + 0.1,
                    "Stage should read as green, was " + stage);

            assertTrue(revert.getRed() > revert.getGreen() + 0.1,
                    "Revert should read as red, was " + revert);
            assertTrue(revert.getRed() > revert.getBlue() + 0.1,
                    "Revert should read as red, was " + revert);
        });
    }

    @Test
    void theButtonsAreActuallyPaintedAtAll() {
        // The specific shape of a mistyped variable: no background is applied, so the
        // button renders as whatever is behind it - and these sit over the diff's text,
        // which would then run through their labels. Any real fill is opaque.
        DiffViewer viewer = themedViewer();
        FxHarness.run(() -> {
            for (String text : List.of("Stage hunk", "Discard hunk")) {
                String styleClass = text.startsWith("Stage") ? "diff-hunk-action" : "diff-revert-hunk";
                Color painted = backgroundPixel(buttonWith(viewer, styleClass, text));
                assertEquals(1.0, painted.getOpacity(), 0.01,
                        text + " has no solid background - a colour variable probably did "
                                + "not resolve, which never throws and is invisible to every "
                                + "other kind of assertion");
            }
        });
    }

    /** At rest the bar is neutral: the colour is in the words, not in a fill. */
    @Test
    void theBarIsNotAFilledBlockAtRest() {
        DiffViewer viewer = themedViewer();
        FxHarness.run(() -> {
            for (String text : List.of("Stage hunk", "Discard hunk")) {
                String styleClass = text.startsWith("Stage") ? "diff-hunk-action" : "diff-revert-hunk";
                Color painted = backgroundPixel(buttonWith(viewer, styleClass, text));
                assertTrue(painted.getSaturation() < 0.35,
                        text + " should not be a saturated block until it is pointed at, painted "
                                + painted);
            }
        });
    }

    @Test
    void theColourblindPaletteReplacesRedAgainstGreen() {
        // Red versus green is exactly what this palette exists to avoid, and these two
        // buttons are the most consequential pair in the app: one stages, one destroys.
        DiffViewer viewer = themedViewer();
        FxHarness.run(() -> {
            viewer.setColorblindPalette(true);
            FxHarness.pulse(viewer);

            Color stage = textColour(buttonWith(viewer, "diff-hunk-action", "Stage hunk"));
            Color revert = textColour(buttonWith(viewer, "diff-revert-hunk", "Discard hunk"));

            assertTrue(stage.getBlue() > stage.getGreen(),
                    "staging should turn blue under the colourblind palette, was " + stage);
            assertTrue(revert.getRed() > revert.getBlue() && revert.getGreen() > revert.getBlue(),
                    "reverting should turn amber under the colourblind palette, was " + revert);
        });
    }
}
