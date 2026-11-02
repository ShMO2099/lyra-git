package com.jfx.git.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The right-hand panel's frame: a header naming what is on screen, and a body.
 *
 * <p>The panel has four modes — the working tree, a commit, a stash, and a comparison of
 * two commits — and each one used to clear the same {@code VBox} and build its own layout
 * into it. Nothing said which mode you were in; the panel simply became different, and
 * telling a stash apart from a commit meant reading the content and inferring it.
 *
 * <p>Having a frame also removes the reason the four builders each had to remember to
 * null out {@code commitMessageArea} and {@code amendCheckBox} on the way in. That was
 * four copies of a rule enforced by nothing, and forgetting it in one of them leaves a
 * keyboard shortcut typing into a control that is no longer on screen.
 */
public class DetailPanel extends VBox {

    /** What the panel is showing, and the mark that goes with it. */
    public enum Mode {
        WORKING_TREE, COMMIT, STASH, COMPARE;

        private javafx.scene.Node icon() {
            return switch (this) {
                case WORKING_TREE -> Icons.edit();
                case COMMIT -> Icons.commit();
                case STASH -> Icons.stash();
                case COMPARE -> Icons.compare();
            };
        }
    }

    /** Holds the mode's icon; a slot, so the stash colour can be put on it and taken off. */
    private final javafx.scene.layout.StackPane glyph = new javafx.scene.layout.StackPane();
    private final Label title = new Label();
    private final Label subject = new Label();
    private final Label count = new Label();
    /** One control belonging to the mode on screen, at the header's trailing end. */
    private final HBox actionSlot = new HBox();
    private final HBox header;
    private final VBox body = new VBox(UiScale.GAP);
    private final javafx.scene.layout.VBox bannerSlot = new VBox();

    /**
     * What sits below the body, running to the panel's edges.
     *
     * <p>A slot rather than another child appended by whoever needs one: the panel is
     * rebuilt on every stage and unstage, and a footer added directly to the children
     * would accumulate a new copy of itself each time.
     */
    private javafx.scene.Node footer;

    public DetailPanel() {
        glyph.getStyleClass().add("mode-header-glyph");
        title.getStyleClass().add("mode-header-title");
        subject.getStyleClass().add("mode-header-subject");
        count.getStyleClass().add("count-badge");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        actionSlot.setAlignment(Pos.CENTER_RIGHT);
        header = new HBox(UiScale.STEP, glyph, title, subject, spacer, count, actionSlot);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mode-header");

        body.setPadding(new Insets(UiScale.GAP, UiScale.EDGE, UiScale.EDGE, UiScale.EDGE));
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, bannerSlot, body);
        showNothing();
    }

    /**
     * A persistent strip between the header and the body, for warnings that must stay
     * above whichever view is currently built — the detached-HEAD warning in
     * particular, which matters most when the working tree is clean and the WIP node
     * (and with it the staging area) does not exist at all. Unlike {@link #body()},
     * nothing clears this; the owner toggles its content.
     */
    public javafx.scene.layout.VBox bannerSlot() {
        return bannerSlot;
    }

    /**
     * Names what the panel is showing.
     *
     * @param subjectText the object itself — a short hash, a stash selector — or null
     * @param countText   what there is of it, or null to hide the badge
     */
    public void setMode(Mode mode, String titleText, String subjectText, String countText) {
        glyph.getChildren().setAll(mode.icon());
        title.setText(titleText);

        subject.setText(subjectText == null ? "" : subjectText);
        show(subject, subjectText != null && !subjectText.isBlank());

        count.setText(countText == null ? "" : countText);
        show(count, countText != null && !countText.isBlank());

        // A stash is the one mode whose mark is not the accent: it borrows the amber the
        // graph already draws stash rows in, so the header agrees with the row you clicked.
        glyph.getStyleClass().remove("mode-header-glyph-stash");
        if (mode == Mode.STASH) {
            glyph.getStyleClass().add("mode-header-glyph-stash");
        }

        header.setVisible(true);
        header.setManaged(true);
    }

    /** Updates only the badge, for a count that arrives after the panel is built. */
    public void setCount(String countText) {
        count.setText(countText == null ? "" : countText);
        show(count, countText != null && !countText.isBlank());
    }

    /** The empty state, before anything has been selected. */
    /** While the repository opens: nothing to select yet, and saying so would be wrong. */
    public void showLoading() {
        header.setVisible(false);
        header.setManaged(false);
        body.getChildren().setAll(hint("Loading…"));
    }

    public void showNothing() {
        header.setVisible(false);
        header.setManaged(false);
        body.getChildren().setAll(hint("Select a commit to see what it changed."));
    }

    /** The content area. Callers own what is in it. */
    public VBox body() {
        return body;
    }

    /** Empties the body and drops any footer and header action, ready for a mode to rebuild itself. */
    public void clearBody() {
        body.getChildren().clear();
        setFooter(null);
        setHeaderAction(null);
    }

    /**
     * Puts one control at the header's trailing end, or takes it away with {@code null}.
     *
     * <p>For a switch that changes what the whole panel shows - the working tree's
     * "All files" - which belongs beside the name of what is on screen rather than on a
     * row of its own inside the content it replaces.
     */
    public void setHeaderAction(javafx.scene.Node node) {
        if (node == null) {
            actionSlot.getChildren().clear();
        } else {
            actionSlot.getChildren().setAll(node);
        }
    }

    /**
     * Puts a node below the body, outside its padding.
     *
     * <p>Used for the commit composer, which reads as a footer on the panel rather than
     * as the last item in the list above it - the same relationship the status bar has to
     * the window.
     */
    public void setFooter(javafx.scene.Node node) {
        if (footer != null) {
            getChildren().remove(footer);
        }
        footer = node;
        if (footer != null) {
            getChildren().add(footer);
        }
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("t-caption");
        label.setWrapText(true);
        return label;
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
