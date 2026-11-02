package com.jfx.git.ui.settings;

import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The few shapes every Settings page is built from: a page with its heading, a section
 * title, a line of explanation, and a label-and-control row.
 *
 * <p>Shared rather than copied per page because the pages used to live in three windows
 * that each had their own idea of a heading and a label column; side by side in one tab,
 * any difference between them reads as a mistake.
 */
final class SettingsLayout {

    /** Wide enough for a sentence of explanation, narrow enough to read in one sweep. */
    static final double HINT_WIDTH = 560;

    private SettingsLayout() {
    }

    /** A page: its heading, then whatever the caller adds below it. */
    static VBox page(String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("preferences-heading");
        VBox box = new VBox(10, heading);
        box.setPadding(new Insets(0, 0, 16, 0));
        return box;
    }

    static Label section(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("preferences-section");
        VBox.setMargin(label, new Insets(12, 0, 0, 0));
        return label;
    }

    static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("preferences-hint");
        label.setWrapText(true);
        label.setMaxWidth(HINT_WIDTH);
        return label;
    }

    /**
     * One label-and-control row, from the shared form grid.
     *
     * <p>Was an {@code HBox} whose label had {@code setPrefWidth(110)}, which is how
     * "Default branch name" reached the user as "Default branch ...". A Label asked for
     * less room than its text needs does not push its neighbour aside - it ellipsizes,
     * quietly. The column definition lives in {@link JfxDialog#form()} so that every form
     * in the application answers that the same way.
     */
    static Region row(String label, Node control) {
        GridPane grid = JfxDialog.form();
        JfxDialog.addRow(grid, label, control);
        return grid;
    }
}
