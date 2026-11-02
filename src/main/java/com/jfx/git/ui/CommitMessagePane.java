package com.jfx.git.ui;

import com.jfx.git.core.AppPreferences;

import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * A commit message in the details panel, at a height the user can drag - the commercial
 * clients' message box with the divider under it.
 *
 * <p>The message used to be bare labels in the panel's column, beside a changed-files
 * list that claims every spare pixel; the column squeezed the labels to their minimum
 * height and a long message ended in "...". Here the message sits in its own scroller,
 * as tall as it needs up to {@link #AUTO_CAP}, scrolling past that, and the handle below
 * sets any other height. A dragged height is remembered for every commit; a double click
 * on the handle goes back to fitting the message.
 */
public final class CommitMessagePane extends VBox {

    /** Tallest a message gets on its own before it scrolls; a drag can go past it. */
    static final double AUTO_CAP = 240;
    /** Shortest a drag can make it: two lines of the subject. */
    static final double MIN_HEIGHT = 44;
    /** What a drag must leave below for the changed files: a few rows and their header. */
    static final double LEAVE_BELOW = 140;

    private final Region content;
    private final ScrollPane scroll;
    private final Region handle = new Region();
    private double dragStartY;
    private double dragStartHeight;

    public CommitMessagePane(Region content) {
        this.content = content;
        getStyleClass().add("commit-message-pane");

        scroll = new ScrollPane(content);
        scroll.getStyleClass().add("commit-message-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Held at its preferred height: the column it sits in shrinks children to their
        // minimum before it shrinks the files list, which is how the message was clipped.
        scroll.setMinHeight(Region.USE_PREF_SIZE);
        scroll.setMaxHeight(Region.USE_PREF_SIZE);

        handle.getStyleClass().add("commit-message-handle");
        handle.setCursor(Cursor.V_RESIZE);
        handle.setMinHeight(7);
        handle.setPrefHeight(7);
        handle.setMaxHeight(7);
        handle.setMaxWidth(Double.MAX_VALUE);
        handle.setAccessibleText("Drag to resize the commit message; double-click to fit it");
        handle.addEventHandler(MouseEvent.MOUSE_PRESSED, this::pressed);
        handle.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
        handle.addEventHandler(MouseEvent.MOUSE_RELEASED, this::released);
        handle.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                AppPreferences.setCommitMessageHeight(0);
                applyHeight();
            }
        });

        setMinHeight(Region.USE_PREF_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        getChildren().addAll(scroll, handle);

        // The message grows when its body arrives, and rewraps when the panel narrows.
        content.heightProperty().addListener(obs -> applyHeight());
        applyHeight();
    }

    /** The scroller around the message, for tests. */
    ScrollPane scroller() {
        return scroll;
    }

    /** The drag handle, for tests. */
    Region handle() {
        return handle;
    }

    /** What fitting the message needs: its own height plus the scroller's frame. */
    private double fittedHeight() {
        double frame = scroll.snappedTopInset() + scroll.snappedBottomInset();
        // A pixel over, so rounding never shows a scrollbar for a message that fits.
        return Math.ceil(content.getHeight()) + frame + 1;
    }

    private void applyHeight() {
        double stored = AppPreferences.commitMessageHeight();
        double height = stored > 0 ? stored : Math.min(fittedHeight(), AUTO_CAP);
        scroll.setPrefHeight(Math.max(MIN_HEIGHT, height));
    }

    /** The tallest a drag may make it: the column, less room for the files below. */
    private double maxDragHeight() {
        Parent parent = getParent();
        if (parent == null) {
            return Double.MAX_VALUE;
        }
        Bounds column = parent.getLayoutBounds();
        return Math.max(MIN_HEIGHT, column.getHeight() - getLayoutY() - LEAVE_BELOW);
    }

    private void pressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragStartY = e.getScreenY();
        dragStartHeight = scroll.getHeight();
        handle.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("dragging"), true);
        e.consume();
    }

    private void dragged(MouseEvent e) {
        double height = dragStartHeight + (e.getScreenY() - dragStartY);
        scroll.setPrefHeight(Math.max(MIN_HEIGHT, Math.min(maxDragHeight(), height)));
        e.consume();
    }

    private void released(MouseEvent e) {
        handle.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("dragging"), false);
        if (e.getButton() == MouseButton.PRIMARY && Math.abs(e.getScreenY() - dragStartY) > 1) {
            AppPreferences.setCommitMessageHeight(scroll.getPrefHeight());
        }
        e.consume();
    }

    /** Builds the pane around any node; for callers that do not hold a Region. */
    static CommitMessagePane of(Node content) {
        return new CommitMessagePane(content instanceof Region region ? region : new VBox(content));
    }
}
