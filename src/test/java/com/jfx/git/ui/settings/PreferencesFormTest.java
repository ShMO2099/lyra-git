package com.jfx.git.ui.settings;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.SettingsSection;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A preference label is never cut short.
 *
 * <h2>The bug this pins</h2>
 * "Default branch name" reached the user as "Default branch ..." in a dialog with 380px
 * of empty space to its right, because each row was an HBox whose label carried
 * {@code setPrefWidth(110)}. A Label given less room than its text needs does not push
 * its neighbour aside — it ellipsizes, quietly.
 *
 * <p>The fix is structural rather than a wider number: a grid column sizes to the widest
 * preferred width among its children, floored at a shared minimum. So the assertions here
 * are about the mechanism as well as the symptom — a label longer than any in the dialog
 * today must also survive, or the next one added brings the bug back.
 */
class PreferencesFormTest {

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

    /** Every label sitting in a form row's first column, across every page. */
    private static List<Label> formLabels(Node root) {
        List<Label> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Node node, List<Label> into) {
        if (node instanceof GridPane grid) {
            for (Node child : grid.getChildren()) {
                if (GridPane.getColumnIndex(child) != null
                        && GridPane.getColumnIndex(child) == 0
                        && child instanceof Label label) {
                    into.add(label);
                }
            }
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, into);
            }
        }
    }

    /**
     * Every form label on every Settings page, each read while its page is the one on
     * screen - at a narrower size than the main window opens at, so a label that only
     * fits with room to spare is caught too.
     */
    private static List<Label> everyPagesLabels(List<String> cutShort) {
        SettingsTab tab = new SettingsTab();
        TabPane shown = SettingsTabs.show(tab, 900, 700);
        List<Label> all = new ArrayList<>();
        for (SettingsSection section : SettingsSection.values()) {
            SettingsTabs.at(shown, tab, section);
            for (Label label : formLabels(tab.page(section))) {
                all.add(label);
                if (label.getWidth() + 0.5 < label.prefWidth(-1)) {
                    cutShort.add(section.label() + ": \"" + label.getText() + "\" was given "
                            + label.getWidth() + "px but needs " + label.prefWidth(-1) + "px");
                }
            }
        }
        tab.dispose();
        return all;
    }

    @Test
    void noFormLabelIsTruncated() {
        FxHarness.run(() -> {
            List<String> cutShort = new ArrayList<>();
            List<Label> labels = everyPagesLabels(cutShort);
            assertFalse(labels.isEmpty(), "the pages should have built some form rows");
            assertTrue(cutShort.isEmpty(), () -> "these will ellipsize: " + cutShort);
        });
    }

    @Test
    void theLongestLabelInSettingsFits() {
        FxHarness.run(() -> {
            SettingsTab tab = new SettingsTab();
            TabPane shown = SettingsTabs.show(tab, 900, 700);
            Label longest = null;
            for (SettingsSection section : SettingsSection.values()) {
                SettingsTabs.at(shown, tab, section);
                for (Label label : formLabels(tab.page(section))) {
                    if (longest == null || label.prefWidth(-1) > longest.prefWidth(-1)) {
                        longest = label;
                    }
                }
                // Measured while its page is on screen: a page not shown is not laid out.
                if (longest != null && longest.getScene() != null) {
                    Label widest = longest;
                    assertTrue(widest.getWidth() + 0.5 >= widest.prefWidth(-1),
                            () -> "the widest label \"" + widest.getText() + "\" must still fit:"
                                    + " given " + widest.getWidth()
                                    + "px, needs " + widest.prefWidth(-1) + "px");
                }
            }
            assertTrue(longest != null, "some page should have a form");
            tab.dispose();
        });
    }

    /**
     * The floor is a minimum, not a cap.
     *
     * <p>This is the part that makes the fix structural: a label wider than every label
     * the dialog ships with still has to be given its full width. Asserted against a
     * grid built the same way rather than against the dialog, because the point is the
     * mechanism, not today's strings.
     */
    @Test
    void aLabelWiderThanTheFloorWidensItsColumn() {
        FxHarness.run(() -> {
            GridPane grid = new GridPane();
            javafx.scene.layout.ColumnConstraints labels =
                    new javafx.scene.layout.ColumnConstraints();
            labels.setMinWidth(152);
            labels.setHgrow(javafx.scene.layout.Priority.NEVER);
            javafx.scene.layout.ColumnConstraints controls =
                    new javafx.scene.layout.ColumnConstraints();
            controls.setHgrow(javafx.scene.layout.Priority.ALWAYS);
            grid.getColumnConstraints().addAll(labels, controls);

            Label absurd = new Label("A preference label considerably longer than the floor");
            grid.add(absurd, 0, 0);
            grid.add(new javafx.scene.control.TextField(), 1, 0);

            Stage stage = FxHarness.show(new VBox(grid), 900, 200);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(grid);

            assertTrue(absurd.getWidth() + 0.5 >= absurd.prefWidth(-1),
                    () -> "a label past the floor must widen its column, got "
                            + absurd.getWidth() + " for " + absurd.prefWidth(-1));
            assertTrue(absurd.getWidth() > 152,
                    "and the column must have grown past the floor to do it");
        });
    }
}
