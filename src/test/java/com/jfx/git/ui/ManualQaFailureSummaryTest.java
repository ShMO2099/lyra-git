package com.jfx.git.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R14: transport progress must not hide the Git refusal that caused a failure. */
class ManualQaFailureSummaryTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeWindows() {
        FxHarness.run(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    @Test
    void aPullFailureSummarisesTheFastForwardRefusalAndDetailsKeepAllOutput() {
        assertFailureSummaryAndDetails("""
                remote: Total 3 (delta 1), reused 2 (delta 0), pack-reused 0
                From https://example.invalid/team/repo
                 * branch            main       -> FETCH_HEAD
                fatal: Not possible to fast-forward, aborting.
                """, "Not possible to fast-forward", "remote: Total 3",
                "fatal: Not possible to fast-forward, aborting.", "remote: Total 3");
    }

    @Test
    void aStaleLeaseFailureSummarisesTheRejectionAndDetailsKeepAllOutput() {
        assertFailureSummaryAndDetails("""
                To https://example.invalid/team/repo
                 ! [rejected]        main -> main (stale info)
                error: failed to push some refs to 'https://example.invalid/team/repo'
                hint: the remote contains work that this clone has not seen
                """, "stale info", "To https://example.invalid/team/repo",
                "failed to push some refs", "To https://example.invalid/team/repo");
    }

    /**
     * Refused on the remote's side - a hook, a lock, a protected branch - git marks the ref
     * "[remote rejected]" with the reason, and follows it with the same generic line as any
     * other failed push. The line with the reason is the one worth showing.
     */
    @Test
    void aRemoteRejectionSummarisesTheRemotesReasonAndDetailsKeepAllOutput() {
        assertFailureSummaryAndDetails("""
                To file:///tmp/origin.git
                 ! [remote rejected] main -> main (pre-receive hook declined)
                error: failed to push some refs to 'file:///tmp/origin.git'
                """, "pre-receive hook declined", "To file:///tmp/origin.git",
                "failed to push some refs", "failed to push some refs");
    }

    @Test
    void aGenericMultilineFailureFallsBackToItsFirstUsefulLineAndKeepsDetails() {
        String output = """

                helper could not complete the requested operation
                Check the local configuration and try again.
                """;
        assertFailureSummaryAndDetails(output, "helper could not complete the requested operation",
                "helper could not complete the requested operation",
                "Check the local configuration and try again.", null);
    }

    private static void assertFailureSummaryAndDetails(String output, String summaryNeedle,
                                                       String firstOutputNeedle, String causeNeedle,
                                                       String excludedSummaryNeedle) {
        Observation observation = FxHarness.call(() -> {
            StatusBar bar = new StatusBar();
            bar.error(output);
            Label summary = (Label) FxHarness.lookupAll(bar, "status-message").getFirst();
            Button details = (Button) FxHarness.lookupAll(bar, "status-details").getFirst();
            boolean detailsVisible = details.isVisible();

            // Details is modal. Queue inspection before opening it; the FX nested event
            // loop runs this callback while showAndWait is active. Capture the UI first so
            // a bad one-line summary cannot prevent the independent Details assertions.
            String[] detailsText = new String[1];
            Platform.runLater(() -> {
                for (Window window : new ArrayList<>(Window.getWindows())) {
                    Scene scene = window.getScene();
                    if (!window.isShowing() || scene == null
                            || !(scene.getRoot() instanceof DialogPane pane)
                            || !(pane.lookup(".jfx-details") instanceof TextArea body)) {
                        continue;
                    }
                    detailsText[0] = body.getText();
                    // Details has one button, Close; whatever its type, it is the first.
                    Button close = (Button) pane.lookupButton(pane.getButtonTypes().getFirst());
                    close.fire();
                    return;
                }
            });
            details.fire();
            return new Observation(summary.getText(), detailsVisible, detailsText[0]);
        });

        assertTrue(observation.detailsVisible(),
                "the complete Git output must remain available in Details");
        assertNotNull(observation.detailsText(), "the Details dialog should have opened");
        assertTrue(observation.detailsText().contains(firstOutputNeedle), observation.detailsText());
        assertTrue(observation.detailsText().contains(causeNeedle), observation.detailsText());
        assertEquals(output, observation.detailsText(), "Details keeps the complete output unchanged");

        assertTrue(observation.summary().toLowerCase(java.util.Locale.ROOT)
                        .contains(summaryNeedle.toLowerCase(java.util.Locale.ROOT)),
                "the visible failure summary must name its cause: " + observation.summary());
        if (excludedSummaryNeedle != null) {
            assertFalse(observation.summary().contains(excludedSummaryNeedle),
                    "transport progress must not replace the failure cause: " + observation.summary());
        }
    }

    private record Observation(String summary, boolean detailsVisible, String detailsText) {
    }
}
