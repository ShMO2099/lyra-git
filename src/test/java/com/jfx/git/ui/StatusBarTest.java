package com.jfx.git.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The status bar must stay a bar.
 *
 * <p>A real checkout error names every file it would overwrite — dozens of lines. Put
 * straight into a Label inside an HBox, that grew the bar until it covered most of the
 * window, and with nothing to dismiss it there was no way back.
 */
class StatusBarTest {

    private static final String LONG_ERROR = """
            error: Your local changes to the following files would be overwritten by checkout:
                platforms/android/.gradle/9.4.1/executionHistory/executionHistory.bin
                platforms/android/.gradle/9.4.1/executionHistory/executionHistory.lock
                platforms/android/.gradle/9.4.1/fileHashes/fileHashes.bin
                platforms/android/.gradle/9.4.1/fileHashes/fileHashes.lock
                platforms/android/app/build.gradle.kts
                platforms/android/app/build/generated/source/buildConfig/debug/BuildConfig.java
            Aborting""";

    private static Label message(StatusBar bar) {
        return (Label) FxHarness.lookupAll(bar, "status-message").get(0);
    }

    private static Button button(StatusBar bar, String styleClass) {
        var found = FxHarness.lookupAll(bar, styleClass);
        return found.isEmpty() ? null : (Button) found.get(0);
    }

    @Test
    void aLongErrorIsShownAsOneLine() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            bar.error(LONG_ERROR);

            String shown = message(bar).getText();
            assertFalse(shown.contains("\n"), "the bar must never render a multi-line message");
            assertTrue(shown.startsWith("error: Your local changes"), shown);
        });
    }

    @Test
    void theBarDoesNotGrowToFitItsMessage() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            double before = bar.getMaxHeight();
            bar.error(LONG_ERROR);

            assertEquals(before, bar.getMaxHeight(),
                    "a long message must not change the bar's height");
            assertTrue(bar.getMaxHeight() < 60,
                    "the bar should be one line tall, was " + bar.getMaxHeight());
        });
    }

    @Test
    void aMultiLineErrorOffersDetails() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            bar.error(LONG_ERROR);

            Button details = button(bar, "status-details");
            assertNotNull(details);
            assertTrue(details.isVisible(), "the rest of the message must be reachable");
            assertTrue(details.isManaged());
        });
    }

    @Test
    void aShortMessageDoesNotOfferDetails() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            bar.error("Could not read HEAD");

            Button details = button(bar, "status-details");
            assertNotNull(details);
            assertFalse(details.isVisible(), "there is nothing more to show");
        });
    }

    /** The bug: an error that does not clear itself and cannot be closed. */
    @Test
    void anErrorCanBeDismissed() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            bar.error(LONG_ERROR);

            Button dismiss = button(bar, "status-dismiss");
            assertNotNull(dismiss);
            assertTrue(dismiss.isVisible(), "an error that does not auto-clear needs a way out");

            bar.clearMessage();
            assertEquals("", message(bar).getText());
            assertFalse(dismiss.isVisible());
        });
    }

    @Test
    void anInfoMessageClearsItselfSoNeedsNoDismiss() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            bar.info("Fetched all remotes");

            Button dismiss = button(bar, "status-dismiss");
            assertNotNull(dismiss);
            assertFalse(dismiss.isVisible(), "it disappears on its own");
        });
    }

    @Test
    void theFullTextStaysReachableOnHover() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            bar.error(LONG_ERROR);

            var tooltip = message(bar).getTooltip();
            assertNotNull(tooltip, "the detail should not be reachable only through a dialog");
            assertTrue(tooltip.getText().contains("build.gradle.kts"), tooltip.getText());
        });
    }

    @Test
    void aWarningBehavesLikeAnErrorRatherThanFadingAway() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            bar.warn("Merge stopped on conflicts\nsrc/a.txt\nsrc/b.txt");

            assertFalse(message(bar).getText().contains("\n"));
            assertTrue(button(bar, "status-dismiss").isVisible());
            assertTrue(button(bar, "status-details").isVisible());
        });
    }

    /**
     * After a step that worked, the line says so first: git's "error: failed to push some
     * refs" on its own read as though the commit before the push had failed too.
     */
    @Test
    void anErrorAfterAStepThatWorkedLeadsWithWhatWorkedThenGitsReason() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            bar.errorAfter("Committed locally, but the push failed", """
                    To file:///tmp/origin.git
                     ! [remote rejected] main -> main (failed to update ref)
                    error: failed to push some refs to 'file:///tmp/origin.git'""");

            assertEquals("Committed locally, but the push failed: ! [remote rejected] main -> main"
                    + " (failed to update ref)", message(bar).getText());
            assertTrue(message(bar).getTooltip().getText().contains("[remote rejected]"),
                    "git's whole output stays reachable");
            assertTrue(button(bar, "status-details").isVisible());
            assertTrue(button(bar, "status-dismiss").isVisible(), "still an error, kept until dismissed");
        });
    }

    // ------------------------------------------------------------------
    // D9. The spinner has to tell the truth about two lanes
    // ------------------------------------------------------------------

    /**
     * A fetch on the network lane and a stage on the repository lane overlap routinely.
     * With a plain show/hide, whichever finished first hid the bar while the other was
     * still working - an indicator reporting "done" over work in progress, which is the
     * one thing it must never do.
     */
    @Test
    void theSpinnerStaysUpUntilTheLastOperationFinishes() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();

            bar.busy("Fetching");
            bar.busy("Staging");
            assertEquals(2, bar.busyDepth());

            bar.idle();
            assertEquals(1, bar.busyDepth(), "one is still running");

            bar.idle();
            assertEquals(0, bar.busyDepth());
        });
    }

    /**
     * An unpaired idle - a failure path reporting twice - must not push the count
     * negative, or the next real operation would never show a bar at all.
     */
    @Test
    void anExtraIdleDoesNotBreakTheNextOperation() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();

            bar.idle();
            bar.idle();
            assertEquals(0, bar.busyDepth());

            bar.busy("Pulling");
            assertEquals(1, bar.busyDepth(), "a stray idle must not have gone below zero");
        });
    }

    // ------------------------------------------------------------------
    // The activity log
    // ------------------------------------------------------------------
    //
    // The bar is where every operation in the application already announces itself, so
    // it is where the log is written from. What matters is that the two stay honest
    // about each other: a message the bar has already shown and forgotten is exactly the
    // thing the log exists to still be holding.

    /** The bar's busy/report pair has to arrive in the log as one timed, judged entry. */
    @Test
    void anOperationIsLoggedOnceWithItsDurationAndItsOutcome() {
        FxHarness.run(() -> {
            com.jfx.git.core.ActivityLog.clear();
            StatusBar bar = new StatusBar();
            bar.setLoggedRepository("demo");

            bar.busy("Staging 3 files");
            bar.idle();
            bar.report(com.jfx.git.core.GitResult.ok("Staged 3 files"));

            var entries = com.jfx.git.core.ActivityLog.entries(
                    com.jfx.git.core.ActivityLog.Scope.REPOSITORY, "demo");
            assertEquals(1, entries.size(),
                    "one operation is one line, not one for starting and one for reporting");
            var entry = entries.get(0);
            assertEquals("Staging 3 files", entry.label());
            assertTrue(entry.isTimed(), "the duration is the point of logging from here");
            assertEquals(com.jfx.git.core.ActivityLog.Outcome.OK, entry.outcome());
        });
    }

    /** A failure reported after the fact has to reach the entry it belongs to. */
    @Test
    void aFailedOperationIsLoggedAsFailed() {
        FxHarness.run(() -> {
            com.jfx.git.core.ActivityLog.clear();
            StatusBar bar = new StatusBar();
            bar.setLoggedRepository("demo");

            bar.busy("Pushing");
            bar.idle();
            bar.report(com.jfx.git.core.GitResult.failed("rejected: non-fast-forward"));

            var entries = com.jfx.git.core.ActivityLog.entries(
                    com.jfx.git.core.ActivityLog.Scope.REPOSITORY, "demo");
            assertEquals(1, entries.size());
            assertEquals(com.jfx.git.core.ActivityLog.Outcome.FAILED, entries.get(0).outcome());
            assertEquals("rejected: non-fast-forward", entries.get(0).detail());
        });
    }

    /**
     * Not every error follows an operation - a dialog that could not read a file reports
     * here too - and dropping those would lose exactly the lines someone opens the log
     * looking for.
     */
    @Test
    void anErrorWithNoOperationBehindItIsStillLogged() {
        FxHarness.run(() -> {
            com.jfx.git.core.ActivityLog.clear();
            StatusBar bar = new StatusBar();
            bar.setLoggedRepository("demo");

            bar.error("Could not read .gitmodules");

            var entries = com.jfx.git.core.ActivityLog.entries(
                    com.jfx.git.core.ActivityLog.Scope.REPOSITORY, "demo");
            assertEquals(1, entries.size());
            assertEquals(com.jfx.git.core.ActivityLog.Outcome.FAILED, entries.get(0).outcome());
            assertFalse(entries.get(0).isTimed(),
                    "nothing was timed, so no duration should be invented");
        });
    }

    /**
     * An operation still in flight stays in flight in the log. A log that reports work
     * as finished while it runs is worse than no log: it is the state a hang looks like,
     * rendered as success.
     */
    @Test
    void anOperationInFlightIsLoggedAsStillRunning() {
        FxHarness.run(() -> {
            com.jfx.git.core.ActivityLog.clear();
            StatusBar bar = new StatusBar();
            bar.setLoggedRepository("demo");

            bar.busy("Cloning");

            var entries = com.jfx.git.core.ActivityLog.entries(
                    com.jfx.git.core.ActivityLog.Scope.REPOSITORY, "demo");
            assertEquals(1, entries.size());
            assertTrue(entries.get(0).isRunning());
            assertFalse(entries.get(0).isTimed());
        });
    }

    /**
     * Two overlapping operations both get closed. They are a counted pair rather than an
     * identified one, so the log closes them oldest-first; what must never happen is one
     * of them being left open forever because the other's idle consumed it twice.
     */
    @Test
    void overlappingOperationsAreBothClosed() {
        FxHarness.run(() -> {
            com.jfx.git.core.ActivityLog.clear();
            StatusBar bar = new StatusBar();
            bar.setLoggedRepository("demo");

            bar.busy("Fetching");
            bar.busy("Staging");
            bar.idle();
            bar.idle();

            var entries = com.jfx.git.core.ActivityLog.entries(
                    com.jfx.git.core.ActivityLog.Scope.REPOSITORY, "demo");
            assertEquals(2, entries.size());
            assertTrue(entries.stream().noneMatch(com.jfx.git.core.ActivityLog.Entry::isRunning),
                    "neither may be left hanging open");
        });
    }

    // ------------------------------------------------------------------
    // The update notice
    // ------------------------------------------------------------------

    private static javafx.scene.control.Hyperlink updateLink(StatusBar bar) {
        return (javafx.scene.control.Hyperlink) FxHarness.lookupAll(bar, "status-update").get(0);
    }

    /**
     * Every tab has a status bar of its own, and an update belongs to the application:
     * the notice has to be in all of them, including one made after the check came back.
     */
    @Test
    void anAvailableUpdateIsALinkInEveryStatusBarAndNothingUntilThen() {
        FxHarness.run(() -> {
            StatusBar.announceUpdate(null, null);
            StatusBar first = new StatusBar();
            assertFalse(updateLink(first).isVisible(), "nothing to say, so nothing shown");
            assertFalse(updateLink(first).isManaged(), "and no room held for it");

            StatusBar.announceUpdate("v1.4.0", "https://example.com/releases/v1.4.0");
            StatusBar second = new StatusBar();

            for (StatusBar bar : new StatusBar[]{first, second}) {
                assertTrue(updateLink(bar).isVisible());
                assertEquals("Version v1.4.0 available", updateLink(bar).getText());
            }

            StatusBar.announceUpdate(null, null);
            assertFalse(updateLink(first).isVisible());
        });
    }

    /** The conflict warning is a pill only while there is something to warn about. */
    @Test
    void theConflictWarningCountsTheFilesAndHidesWhenCleared() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            assertNull(bar.conflictWarningText(), "nothing to warn about yet");
            assertFalse(bar.conflictPill().isManaged(), "and no room held for it");

            bar.showConflictWarning("origin/main", java.util.List.of("a.txt", "b.txt", "c.txt"),
                    java.util.List::of);
            assertEquals("Conflicts with origin/main · 3 files", bar.conflictWarningText());
            String tip = bar.conflictPill().getTooltip().getText();
            assertTrue(tip.contains("a.txt") && tip.contains("c.txt"), tip);
            assertTrue(bar.conflictPill().getStyleClass().contains("status-conflict"));

            bar.showConflictWarning("origin/develop", java.util.List.of("a.txt"), java.util.List::of);
            assertEquals("Conflicts with origin/develop · 1 file", bar.conflictWarningText());

            bar.showConflictWarning("origin/main", java.util.List.of(), java.util.List::of);
            assertNull(bar.conflictWarningText(), "no files, no warning");
            bar.showConflictWarning("origin/main", java.util.List.of("a.txt"), java.util.List::of);
            bar.clearConflictWarning();
            assertNull(bar.conflictWarningText());
        });
    }

    /** After a discard: Undo when there is a snapshot to undo from, Snapshots always. */
    @Test
    void aDiscardOffersUndoOnlyWithASnapshotAndTheSnapshotListAlways() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            ToastHost toasts = new ToastHost();
            bar.setToastHost(toasts);
            bar.setSnapshotsOpener(() -> { });

            bar.offerDiscardRecovery("Discarded a.txt", () -> { }, null);
            assertEquals(java.util.List.of("Undo", "Snapshots"), actions(toasts.getChildren().getLast()));

            bar.offerDiscardRecovery("Discarded b.txt", null, null);
            assertEquals(java.util.List.of("Snapshots"), actions(toasts.getChildren().getLast()));
        });
    }

    /**
     * Details: the line the bar showed as the title, all of the message under it in a
     * read-only monospace text, Copy beside the footer's Close, at the medium width.
     */
    @Test
    void detailsShowTheWholeMessageUnderItsSummaryWithCopy() {
        FxHarness.run(() -> {
            javafx.scene.control.Alert details = StatusBar.detailsDialog(LONG_ERROR,
                    "error: Your local changes to the following files would be overwritten by checkout:");
            assertEquals("Details", details.getTitle());
            assertEquals("error: Your local changes to the following files would be overwritten by checkout:",
                    com.jfx.git.ui.dialogs.JfxDialog.headerOf(details).titleText());

            var text = (javafx.scene.control.TextArea) details.getDialogPane().lookup(".jfx-details");
            assertNotNull(text, "the message is in the monospace details text");
            assertFalse(text.isEditable());
            assertEquals(LONG_ERROR.strip(), text.getText());

            assertEquals("Close", ((Button) details.getDialogPane()
                    .lookupButton(javafx.scene.control.ButtonType.OK)).getText());
            // Not shown, the footer's bar has not placed its buttons in the scene yet: asked directly.
            var bar = (javafx.scene.control.ButtonBar) details.getDialogPane().lookup(".button-bar");
            Button copy = (Button) bar.getButtons().stream()
                    .filter(n -> n.getStyleClass().contains("status-details-copy")).findFirst().orElse(null);
            assertNotNull(copy, "Copy is at the left of the footer");
            assertEquals(javafx.scene.control.ButtonBar.ButtonData.LEFT, javafx.scene.control.ButtonBar.getButtonData(copy));
            assertEquals("Copy", copy.getText());
            assertEquals(com.jfx.git.ui.dialogs.JfxDialog.Size.MD.width(), details.getDialogPane().getPrefWidth());
        });
    }

    private static java.util.List<String> actions(javafx.scene.Node card) {
        return ((javafx.scene.Parent) card).getChildrenUnmodifiable().stream()
                .filter(n -> n.getStyleClass().contains("toast-action"))
                .map(n -> ((Button) n).getText()).toList();
    }
}
