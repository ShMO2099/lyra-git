package com.jfx.git.ui;

import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A button is as wide as what it says, and a dialog's buttons are a matched pair.
 *
 * <h2>The bug this pins</h2>
 * The Integrations dialog rendered "Not connec…", "Conne…" and "Serv…" — three
 * truncations in a single row — because every control had been sized to the space left
 * over rather than to what it had to say. The first fix was an 88px floor on every button
 * in the application, which cured that row and made a slab of everything small: nine
 * toolbar buttons needed 800px before drawing an icon, and the sidebar's eye toggle
 * pushed each branch name out of view.
 *
 * <p>So the floor now lives where it belongs - a dialog's button bar, where OK and Cancel
 * should be equal targets - and everywhere else a button takes its label's width. Both
 * halves are asserted here, along with the small controls a floor must never reach.
 */
class ButtonSizingTest {

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

    /**
     * Lays a button out inside a scene carrying the application's own stylesheets.
     *
     * <p>{@link FxHarness#show} attaches only the AtlantaFX user-agent theme, so a scene
     * built from it alone has never seen components.css — every assertion below would
     * pass against a button no rule had touched. Registering the scene is what puts the
     * app stylesheets on it, which is the thing under test.
     */
    private static double laidOutWidth(String... styleClasses) {
        return FxHarness.call(() -> {
            Button button = new Button("Go");
            button.getStyleClass().addAll(styleClasses);
            HBox root = new HBox(button);
            javafx.stage.Stage stage = FxHarness.show(root, 600, 120);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(root);
            return button.getWidth();
        });
    }

    @Test
    void aDialogsButtonsHoldAFloor() {
        double width = FxHarness.call(() -> {
            javafx.scene.control.DialogPane pane = new javafx.scene.control.DialogPane();
            pane.getButtonTypes().add(javafx.scene.control.ButtonType.OK);
            javafx.stage.Stage stage = FxHarness.show(pane, 600, 200);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(pane);
            return ((Button) pane.lookupButton(javafx.scene.control.ButtonType.OK)).getWidth();
        });
        assertTrue(width >= 80, "a dialog's OK should hold the 80px floor, was " + width);
    }

    @Test
    void anOrdinaryButtonIsAsWideAsItsLabel() {
        double width = laidOutWidth();
        assertTrue(width < 80, "a plain \"Go\" outside a dialog should not be padded to a floor, was " + width);
        assertTrue(width > 24, "but it still has to hold its label and padding, was " + width);
    }

    /**
     * A menu button stands as tall as the button and field beside it. AtlantaFX pads its
     * label as a whole button, and padded once more as a button it was 42px against 28.
     */
    @Test
    void aMenuButtonIsAsTallAsAButton() {
        double[] heights = FxHarness.call(() -> {
            Button button = new Button("Go");
            javafx.scene.control.MenuButton menu = new javafx.scene.control.MenuButton("Take",
                    null, new javafx.scene.control.MenuItem("Take ours"));
            javafx.scene.control.SplitMenuButton split = new javafx.scene.control.SplitMenuButton(
                    new javafx.scene.control.MenuItem("Pull (rebase)"));
            split.setText("Pull");
            javafx.scene.control.TextField field = new javafx.scene.control.TextField("x");
            HBox root = new HBox(button, menu, split, field);
            root.setAlignment(javafx.geometry.Pos.TOP_LEFT);
            javafx.stage.Stage stage = FxHarness.show(root, 600, 120);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(root);
            return new double[] {button.prefHeight(-1), menu.prefHeight(-1), split.prefHeight(-1),
                    field.prefHeight(-1)};
        });
        assertTrue(Math.abs(heights[1] - heights[0]) <= 1,
                "a menu button should be as tall as a button: " + heights[1] + " against " + heights[0]);
        assertTrue(Math.abs(heights[2] - heights[0]) <= 1,
                "and a split menu button: " + heights[2] + " against " + heights[0]);
        assertTrue(Math.abs(heights[1] - heights[3]) <= 1,
                "and as a field beside it: " + heights[1] + " against " + heights[3]);
    }

    @Test
    void anIconButtonIsASmallSquare() {
        double[] size = FxHarness.call(() -> {
            Button button = new Button();
            button.setGraphic(Icons.fetch());
            button.getStyleClass().addAll("btn-ghost", "btn-icon");
            HBox root = new HBox(button);
            javafx.stage.Stage stage = FxHarness.show(root, 600, 120);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(root);
            return new double[] {button.getWidth(), button.getHeight()};
        });
        assertTrue(size[0] <= UiScale.CONTROL_H + 2 && size[1] <= UiScale.CONTROL_H + 2,
                "an icon button is at most a " + UiScale.CONTROL_H + "px square, was " + size[0] + "x" + size[1]);
    }

    @Test
    void aLongLabelIsNotClipped() {
        double width = FxHarness.call(() -> {
            Button button = new Button("Fork repository and add remote…");
            HBox root = new HBox(button);
            javafx.stage.Stage stage = FxHarness.show(root, 600, 120);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(root);
            return button.getWidth();
        });
        // A button's width comes from its label, which is the behaviour that stops
        // "Conne…" happening.
        assertTrue(width > 120,
                "a long label must widen the button rather than be cut, was " + width);
    }

    @Test
    void aRowActionStaysSmall() {
        double width = laidOutWidth("btn-ghost", "row-action");
        assertTrue(width < 88,
                "hover actions in a file row must not be slabs, was " + width);
    }

    @Test
    void statusBarGlyphsStaySmall() {
        assertTrue(laidOutWidth("btn-ghost", "status-dismiss") < 88,
                "the status bar dismiss is one glyph wide");
        assertTrue(laidOutWidth("btn-ghost", "graph-columns-gear") < 88,
                "the graph header gear is one glyph wide");
    }

    /** The composer's Commit / Stash switch is a segmented control, not two buttons. */
    @Test
    void composerTabsStaySmall() {
        assertTrue(laidOutWidth("composer-tab") < 88,
                "the Commit/Stash segments must stay tab-sized");
    }
}
