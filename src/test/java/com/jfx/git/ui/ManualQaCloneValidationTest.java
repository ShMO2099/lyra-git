package com.jfx.git.ui;

import com.jfx.git.LyraApp;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R03: the real clone form rejects missing required fields and lets the user cancel. */
class ManualQaCloneValidationTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeWindows() {
        FxHarness.run(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    /**
     * Said under each field left empty, with the form still open: an alert after it closed
     * lost what was typed.
     */
    @Test
    void emptyUrlAndTargetAreRejectedWithAnActionableMessage() {
        openCloneDialog();
        DialogPane clone = RobotWait.untilDialogWithButton("OK", "Cancel");

        FxHarness.run(() -> ((Button) clone.lookupButton(ButtonType.OK)).fire());
        WaitForAsyncUtils.waitForFxEvents();

        FxHarness.run(() -> {
            assertTrue(clone.getScene().getWindow().isShowing(), "the form stays open to be finished");
            Label url = errorUnder(clone, LyraApp.CLONE_URL_ID);
            assertEquals(LyraApp.CLONE_URL_MISSING, url.getText());
            assertTrue(url.isVisible(), "and the problem is shown, not only set");
            Label target = errorUnder(clone, LyraApp.CLONE_TARGET_ID);
            assertEquals(LyraApp.CLONE_TARGET_MISSING, target.getText());
            assertTrue(target.isVisible());
            ((Button) clone.lookupButton(ButtonType.CANCEL)).fire();
        });
    }

    /**
     * A clone that fails says why with the form open and everything typed still in it: on
     * the field the failure is about, and in red at the left of the footer.
     */
    @Test
    void aCloneThatFailsKeepsTheFormWithTheReasonOnTheUrl(@TempDir Path temp) {
        String missing = temp.resolve("no-such-repository").toUri().toString();
        String into = temp.resolve("copy").toAbsolutePath().toString();
        openCloneDialog();
        DialogPane clone = RobotWait.untilDialogWithButton("OK", "Cancel");
        FxHarness.run(() -> {
            ((TextField) clone.lookup("#" + LyraApp.CLONE_URL_ID)).setText(missing);
            ((TextField) clone.lookup("#" + LyraApp.CLONE_TARGET_ID)).setText(into);
            ((Button) clone.lookupButton(ButtonType.OK)).fire();
        });
        RobotWait.untilOnFx("the clone never said it failed",
                () -> clone.lookup(".jfx-footer-status.failed") != null);

        FxHarness.run(() -> {
            assertTrue(clone.getScene().getWindow().isShowing(), "the form stays open to try again");
            assertFalse(errorUnder(clone, LyraApp.CLONE_URL_ID).getText().isEmpty(),
                    "a repository that is not there is the URL's problem, said under it");
            assertEquals(missing, ((TextField) clone.lookup("#" + LyraApp.CLONE_URL_ID)).getText(),
                    "nothing typed is lost");
            assertEquals(into, ((TextField) clone.lookup("#" + LyraApp.CLONE_TARGET_ID)).getText());
            assertFalse(clone.getContent().isDisabled(), "the form is unlocked again");
            assertFalse(clone.lookupButton(ButtonType.OK).isDisabled(), "and Clone can be pressed again");
            ((Button) clone.lookupButton(ButtonType.CANCEL)).fire();
        });
    }

    /** The error line under the field whose control has {@code id}. */
    static Label errorUnder(DialogPane pane, String id) {
        Node node = pane.lookup("#" + id);
        while (node != null && !node.getStyleClass().contains("jfx-field")) {
            node = node.getParent();
        }
        if (node == null) {
            throw new AssertionError("no field around #" + id);
        }
        return (Label) node.lookup(".jfx-field-error");
    }

    @Test
    void cancellingTheCloneFormClosesItWithoutStartingAnotherDialog() {
        openCloneDialog();
        DialogPane clone = RobotWait.untilDialogWithButton("OK", "Cancel");

        FxHarness.run(() -> ((Button) clone.lookupButton(ButtonType.CANCEL)).fire());
        WaitForAsyncUtils.waitForFxEvents();

        FxHarness.run(() -> {
            boolean dialogStillShowing = Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .map(Window::getScene)
                    .filter(java.util.Objects::nonNull)
                    .map(scene -> scene.getRoot())
                    .anyMatch(root -> root instanceof DialogPane);
            assertFalse(dialogStillShowing, "Cancel closes the form without showing validation or clone progress");
        });
    }

    private static void openCloneDialog() {
        LyraApp app = new LyraApp();
        FxHarness.run(() -> Platform.runLater(app::cloneRepoDialog));
    }
}
