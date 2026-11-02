package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import javafx.application.Platform;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers clone form depth validation before any network or filesystem operation begins. */
class IncompleteQaCloneDepthValidationRobotTest {
    @TempDir Path temp;

    @BeforeAll
    static void startToolkit() { FxHarness.start(); }

    @AfterEach
    void closeWindows() {
        FxHarness.run(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    @Test
    void malformedAndNegativeDepthAreExplainedBeforeTheTargetIsTouched() {
        assertDepthError("not-a-number", "Clone depth must be a whole number.");
        assertDepthError("-2", "Clone depth cannot be negative.");
    }

    private void assertDepthError(String depth, String expected) {
        LyraApp app = new LyraApp();
        Path target = temp.resolve("invalid-depth-" + Math.abs(depth.hashCode()));
        FxHarness.run(() -> Platform.runLater(app::cloneRepoDialog));
        DialogPane form = RobotWait.untilDialogWithButton("OK", "Cancel");
        FxHarness.run(() -> {
            ((TextField) form.lookup("#" + LyraApp.CLONE_URL_ID)).setText("file:///synthetic/source.git");
            ((TextField) form.lookup("#" + LyraApp.CLONE_TARGET_ID)).setText(target.toAbsolutePath().toString());
            ((TextField) form.lookup("#" + LyraApp.CLONE_DEPTH_ID)).setText(depth);
            ((Button) form.lookupButton(ButtonType.OK)).fire();
        });
        // Said under the depth as it is typed, Clone waiting for it, and the form open with
        // the depth there to correct.
        FxHarness.run(() -> {
            assertTrue(form.getScene().getWindow().isShowing(), "the form stays open");
            Label error = ManualQaCloneValidationTest.errorUnder(form, LyraApp.CLONE_DEPTH_ID);
            assertEquals(expected, error.getText());
            assertTrue(error.isVisible());
            assertTrue(form.lookupButton(ButtonType.OK).isDisabled(), "Clone waits for a depth it can use");
        });
        assertFalse(Files.exists(target), "invalid depth must be rejected before target creation");
        FxHarness.run(() -> ((Button) form.lookupButton(ButtonType.CANCEL)).fire());
        RobotWait.untilOnFx("the form never closed", () -> form.getScene() == null
                || form.getScene().getWindow() == null || !form.getScene().getWindow().isShowing());
    }
}
