package com.jfx.git.ui;

import com.jfx.git.testsupport.MainWindowFixture;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Shape;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every control in the main window answers the pointer: something about it - its fill,
 * its border, its text or its icon - changes while the pointer is over it.
 *
 * <p>A control that sits still under the pointer reads as disabled, or as a label. That is
 * what the find bar's Aa / Word / .* did, and the selected half of every switch, and the
 * scope chips under the graph search, and every checkbox pointed at by its label: AtlantaFX
 * declares a toggle button's hover colours without a rule that uses them, and lights a
 * checkbox only while the pointer is on its 16px box.
 *
 * <p>Measured, not assumed: the glass robot moves a real pointer onto each control, and the
 * test compares what is painted before and after.
 */
@Tag("heavy")
class HoverFeedbackTest {

    private static final double WIDTH = 1180;
    private static final double HEIGHT = 690;

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(ThemeManager.Theme.class)
    void everyControlInTheHistoryViewAnswersThePointer(ThemeManager.Theme theme) throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(WIDTH, HEIGHT, theme, true);
            assertNoneFlat(window, theme + ", history");
        }
    }

    @ParameterizedTest
    @EnumSource(ThemeManager.Theme.class)
    void everyControlAroundAnOpenFileAndItsFindBarAnswersThePointer(ThemeManager.Theme theme) throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(WIDTH, HEIGHT, theme, true);
            FxHarness.run(() -> window.tab().diffViewer().showFileContent("README.md", "# Demo\n\nhello\n"));
            window.layOut();
            FxHarness.run(() -> {
                window.tab().find(false);
                // One option on and two off, so both states of the toggle are pointed at.
                window.tab().diffViewer().findBar().optionToggles().get(0).setSelected(true);
            });
            window.layOut();
            assertNoneFlat(window, theme + ", file and find");
        }
    }

    private static void assertNoneFlat(MainWindowFixture window, String where) {
        List<ButtonBase> controls = FxHarness.call(() -> {
            List<ButtonBase> found = new ArrayList<>();
            collect(window.scene().getRoot(), found);
            return found;
        });
        assertTrue(controls.size() > 10, "the window should have controls to point at: " + controls.size());
        Robot robot = FxHarness.call(Robot::new);
        List<String> flat = new ArrayList<>();
        for (ButtonBase control : controls) {
            // Off every control first: the bottom-right corner is the status bar's padding.
            FxHarness.run(() -> robot.mouseMove(WIDTH - 2, HEIGHT - 2));
            drain();
            String resting = FxHarness.call(() -> appearance(control));
            Bounds onScreen = FxHarness.call(() -> control.localToScreen(control.getLayoutBounds()));
            FxHarness.run(() -> robot.mouseMove(onScreen.getCenterX(), onScreen.getCenterY()));
            drain();
            String pointed = FxHarness.call(() -> appearance(control));
            if (resting.equals(pointed)) {
                flat.add(FxHarness.call(() -> describe(control)));
            }
        }
        assertTrue(flat.isEmpty(), where + ": controls that do not change under the pointer: " + flat);
    }

    /** Lets the robot's move arrive and the pass after it restyle. */
    private static void drain() {
        for (int i = 0; i < 3; i++) {
            FxHarness.run(() -> { });
        }
    }

    /** Enabled, showing, and wholly inside the window - somewhere the pointer can go. */
    private static void collect(Node node, List<ButtonBase> into) {
        if (!node.isVisible()) return;
        if (node instanceof ButtonBase control && !control.isDisabled()) {
            Bounds onScreen = control.localToScreen(control.getLayoutBounds());
            if (onScreen != null && onScreen.getWidth() > 2 && onScreen.getHeight() > 2
                    && onScreen.getMinX() >= 0 && onScreen.getMinY() >= 0
                    && onScreen.getMaxX() < WIDTH && onScreen.getMaxY() < HEIGHT) {
                into.add(control);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, into);
        }
    }

    /** What is painted: fills, borders, text and icon colours, a few levels down. */
    private static String appearance(Node node) {
        node.applyCss();
        StringBuilder out = new StringBuilder();
        appearance(node, out, 0);
        return out.toString();
    }

    private static void appearance(Node node, StringBuilder out, int depth) {
        if (depth > 4 || !node.isVisible()) return;
        if (node instanceof Region region) {
            if (region.getBackground() != null) {
                region.getBackground().getFills().forEach(fill -> out.append("bg").append(fill.getFill()).append(';'));
            }
            if (region.getBorder() != null) {
                region.getBorder().getStrokes().forEach(stroke -> out.append("bd").append(stroke.getTopStroke()).append(';'));
            }
        }
        if (node instanceof Labeled labeled) out.append("fg").append(labeled.getTextFill()).append(';');
        if (node instanceof Shape shape) out.append("sh").append(shape.getFill()).append('/').append(shape.getStroke()).append(';');
        out.append("op").append(node.getOpacity()).append(';');
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) appearance(child, out, depth + 1);
        }
    }

    private static String describe(ButtonBase control) {
        String name = control.getText();
        if (name == null || name.isEmpty()) name = control.getAccessibleText();
        if (name == null || name.isEmpty()) name = control.getId();
        return control.getClass().getSimpleName() + " \"" + name + "\" " + control.getStyleClass();
    }
}
