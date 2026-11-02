package com.jfx.git.ui;

import com.jfx.git.core.AppPreferences;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commit message in the details panel: never clipped to "...", fitted when short,
 * scrolling when long, and resizable from the handle under it, as in commercial clients.
 *
 * <p>Each fixture puts the pane in a column beside a sibling that claims every spare
 * pixel, which is what the changed-files list does and what used to squeeze the message.
 */
class CommitMessagePaneTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tidyUp() {
        FxHarness.closeShownStages();
        AppPreferences.setCommitMessageHeight(0);
    }

    private record Fixture(VBox column, CommitMessagePane pane, Label body) {
    }

    private static Fixture show(int bodyLines) {
        return FxHarness.call(() -> {
            StringBuilder text = new StringBuilder();
            for (int i = 1; i <= bodyLines; i++) {
                text.append("- body line ").append(i).append(" of a message that says what changed\n");
            }
            Label subject = new Label("fix: the subject");
            subject.setWrapText(true);
            Label body = new Label(text.toString().strip());
            body.setWrapText(true);
            CommitMessagePane pane = new CommitMessagePane(new VBox(4, subject, body));
            Region greedy = new Region();
            greedy.setPrefHeight(2000);
            VBox.setVgrow(greedy, Priority.ALWAYS);
            VBox column = new VBox(8, pane, greedy);
            Stage stage = FxHarness.showRegistered(column, 400, 700);
            FxHarness.pulse(column);
            FxHarness.pulse(column);
            stage.setUserData("keep-alive");
            return new Fixture(column, pane, body);
        });
    }

    private static boolean verticalBarShowing(CommitMessagePane pane) {
        return pane.scroller().lookupAll(".scroll-bar").stream()
                .anyMatch(n -> n instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL
                        && bar.isVisible());
    }

    private static boolean clipped(Label label) {
        // A wrapping label shorter than it asks to be is drawn with "..." on its last line.
        return label.getHeight() + 0.5 < label.prefHeight(label.getWidth());
    }

    @Test
    void aShortMessageFitsWithNoScrollbarAndNothingCut() {
        Fixture f = show(3);
        FxHarness.run(() -> {
            assertFalse(clipped(f.body()), "the body is shown whole");
            assertFalse(verticalBarShowing(f.pane()), "a message that fits does not scroll");
            assertTrue(f.pane().scroller().getHeight() < CommitMessagePane.AUTO_CAP, "and takes only its own height");
        });
    }

    @Test
    void aLongMessageIsCappedAndScrollsRatherThanEndingInAnEllipsis() {
        Fixture f = show(40);
        FxHarness.run(() -> {
            assertEquals(CommitMessagePane.AUTO_CAP, f.pane().scroller().getHeight(), 1,
                    "a long message stops at the cap, beside a list that wants everything");
            assertTrue(verticalBarShowing(f.pane()), "and scrolls for the rest");
            assertFalse(clipped(f.body()), "every line is there to scroll to - no ...");
        });
    }

    private static void mouse(Node on, javafx.event.EventType<MouseEvent> type, double y, int clicks) {
        Point2D scene = on.localToScene(on.getLayoutBounds().getWidth() / 2, y);
        Point2D screen = on.localToScreen(on.getLayoutBounds().getWidth() / 2, y);
        Event.fireEvent(on, new MouseEvent(type, scene.getX(), scene.getY(), screen.getX(), screen.getY(),
                MouseButton.PRIMARY, clicks, false, false, false, false, true, false, false, false, false, false, null));
    }

    @Test
    void draggingTheHandleSetsAndRemembersTheHeight() {
        Fixture f = show(40);
        FxHarness.run(() -> {
            Region handle = f.pane().handle();
            mouse(handle, MouseEvent.MOUSE_PRESSED, 3, 1);
            mouse(handle, MouseEvent.MOUSE_DRAGGED, 3 + 100, 1);
            mouse(handle, MouseEvent.MOUSE_RELEASED, 3 + 100, 1);
            FxHarness.pulse(f.column());
            assertEquals(CommitMessagePane.AUTO_CAP + 100, f.pane().scroller().getHeight(), 1,
                    "the message is as tall as it was dragged");
            assertEquals(CommitMessagePane.AUTO_CAP + 100, AppPreferences.commitMessageHeight(), 1,
                    "and the next commit opens at that height");
        });
    }

    @Test
    void aDragStopsShortOfPushingTheFilesOffThePanel() {
        Fixture f = show(40);
        FxHarness.run(() -> {
            Region handle = f.pane().handle();
            mouse(handle, MouseEvent.MOUSE_PRESSED, 3, 1);
            mouse(handle, MouseEvent.MOUSE_DRAGGED, 3 + 5000, 1);
            mouse(handle, MouseEvent.MOUSE_RELEASED, 3 + 5000, 1);
            FxHarness.pulse(f.column());
            double bottom = f.pane().getBoundsInParent().getMaxY();
            assertTrue(f.column().getHeight() - bottom >= CommitMessagePane.LEAVE_BELOW - 10,
                    "room is left below for the changed files: " + (f.column().getHeight() - bottom));
        });
    }

    @Test
    void doubleClickingTheHandleGoesBackToFittingTheMessage() {
        AppPreferences.setCommitMessageHeight(400);
        Fixture f = show(3);
        FxHarness.run(() -> {
            assertEquals(400, f.pane().scroller().getHeight(), 1, "the remembered height applies");
            mouse(f.pane().handle(), MouseEvent.MOUSE_CLICKED, 3, 2);
            FxHarness.pulse(f.column());
            assertTrue(f.pane().scroller().getHeight() < 120, "back to the message's own height");
            assertEquals(0, AppPreferences.commitMessageHeight(), "and nothing remembered");
        });
    }
}
