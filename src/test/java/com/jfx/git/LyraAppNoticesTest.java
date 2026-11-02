package com.jfx.git;

import com.jfx.git.core.CrashReporter;
import com.jfx.git.core.UpdateChecker;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application's own notices: the update check answered from the Help menu - in a
 * dialog, or in the status line when there is nothing to answer - and the crash report.
 */
class LyraAppNoticesTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeWindows() {
        FxHarness.run(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    /**
     * A notice relabels its confirming button "Close"; the release page's button is the
     * confirming one here, and must keep its own name rather than make a second Close.
     */
    @Test
    void anAvailableUpdateOffersItsReleasePageByName() {
        var found = new UpdateChecker.Result(UpdateChecker.Status.UPDATE_AVAILABLE, "9.9.9",
                "https://example.invalid/releases/9.9.9", "Version 9.9.9 is available.");
        FxHarness.run(() -> Platform.runLater(() -> LyraApp.showUpdateCheck(found,
                said -> { throw new AssertionError("an available update asks; it is not a status line"); })));

        DialogPane notice = RobotWait.untilDialogWithButton("Open release page");
        FxHarness.run(() -> {
            assertEquals("Lyra 9.9.9 is available", notice.getHeaderText());
            ButtonType open = notice.getButtonTypes().stream()
                    .filter(t -> "Open release page".equals(t.getText())).findFirst().orElseThrow();
            assertEquals("Open release page", ((Button) notice.lookupButton(open)).getText());
            assertEquals("Close", ((Button) notice.lookupButton(ButtonType.CLOSE)).getText());
            ((Button) notice.lookupButton(ButtonType.CLOSE)).fire();
        });
    }

    /** Being up to date needs no answer: it is said in the status line, and no dialog opens. */
    @Test
    void beingUpToDateIsSaidWithoutADialog() {
        var latest = new UpdateChecker.Result(UpdateChecker.Status.UP_TO_DATE, "1.4.0", "",
                "You have the latest version, 1.4.0.");
        java.util.List<String> said = new ArrayList<>();
        int windowsBefore = FxHarness.call(() -> Window.getWindows().size());

        FxHarness.run(() -> LyraApp.showUpdateCheck(latest, said::add));

        assertEquals(1, said.size(), "said once");
        assertTrue(said.get(0).startsWith("Lyra ") && said.get(0).endsWith(" is the latest version"),
                said.get(0));
        assertEquals(windowsBefore, (int) FxHarness.call(() -> Window.getWindows().size()), "and no dialog opened");
    }

    /** Copying the report used to be a button type: it copied and closed the dialog in one. */
    @Test
    void copyingACrashReportLeavesItOpenAndSaysSo() {
        LyraApp app = new LyraApp();
        FxHarness.run(() -> app.showCrashReport(new CrashReporter.Report(
                "IllegalStateException: boom", "Lyra crash report\njava.lang.IllegalStateException: boom")));

        DialogPane crash = RobotWait.untilDialogWithButton("OK");
        Button copy = FxHarness.call(() -> FxHarness.lookupAll(crash, "button").stream()
                .filter(n -> n instanceof Button b && "Copy report".equals(b.getText()))
                .map(Button.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no Copy report button")));
        FxHarness.run(() -> {
            assertEquals("Something went wrong", crash.getHeaderText());
            copy.fire();
        });
        WaitForAsyncUtils.waitForFxEvents();

        FxHarness.run(() -> {
            assertTrue(crash.getScene().getWindow().isShowing(), "copying is not an answer: the report stays open");
            assertEquals("Copied", copy.getText());
            assertEquals("Lyra crash report\njava.lang.IllegalStateException: boom",
                    javafx.scene.input.Clipboard.getSystemClipboard().getString(), "the whole report");
            ((Button) crash.lookupButton(ButtonType.OK)).fire();
        });
    }
}
