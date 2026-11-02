package com.jfx.git.ui;

import com.jfx.git.core.ActivityLog;
import com.jfx.git.core.GitResult;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status bar as the operation runner sees it: each operation is closed by its own idle,
 * whichever finishes first, and its verdict is given once.
 *
 * <p>Labels carry a random suffix: the activity log is the application's, shared by every
 * test in the run.
 */
class StatusBarOperationsTest {

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    private String label(String name) {
        return name + " " + tag;
    }

    private static ActivityLog.Entry entry(String label) {
        List<ActivityLog.Entry> found = ActivityLog.entries().stream()
                .filter(e -> label.equals(e.label())).toList();
        assertEquals(1, found.size(), "one entry for " + label);
        return found.getFirst();
    }

    private static long entriesMentioning(String text) {
        return ActivityLog.entries().stream()
                .filter(e -> e.label().contains(text) || (e.detail() != null && e.detail().contains(text)))
                .count();
    }

    @Test
    void eachOperationIsClosedByItsOwnIdleWhicheverFinishesFirst() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            OperationRunner.Feedback feedback = bar.operations();
            Object checkout = feedback.busy(label("Checking out"));
            Object stage = feedback.busy(label("Staging"));

            feedback.idle(stage);
            feedback.report(GitResult.failed("stage broke"));
            assertEquals(ActivityLog.Outcome.FAILED, entry(label("Staging")).outcome());
            assertTrue(entry(label("Checking out")).isRunning(), "the one still waiting is not closed by another's idle");
            assertEquals(1, bar.busyDepth());

            feedback.idle(checkout);
            feedback.report(GitResult.ok("Checked out"));
            assertEquals(ActivityLog.Outcome.OK, entry(label("Checking out")).outcome());
            assertEquals(0, bar.busyDepth());
        });
    }

    @Test
    void aCountedIdleDoesNotCloseAnOperation() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            Object operation = bar.operations().busy(label("Fetching"));
            bar.busy(label("Loading older commits"));

            bar.idle();

            assertTrue(entry(label("Fetching")).isRunning());
            assertTrue(entry(label("Loading older commits")).isFinished());
            bar.operations().idle(operation);
            assertEquals(0, bar.busyDepth());
        });
    }

    @Test
    void aVerdictAlreadyGivenIsNotReplacedBySettling() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            Object operation = bar.operations().busy(label("Merging"));
            bar.operations().idle(operation);
            bar.warn("Merged with conflicts " + tag);

            bar.operations().settle(operation, GitResult.ok());

            assertEquals(ActivityLog.Outcome.WARNING, entry(label("Merging")).outcome());
        });
    }

    @Test
    void showingAResultWhoseVerdictIsGivenLogsNothingMore() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            Object operation = bar.operations().busy(label("Adding the worktree"));
            bar.operations().idle(operation);
            bar.operations().settle(operation, GitResult.failed("refused " + tag));

            bar.operations().show(GitResult.failed("refused " + tag));

            assertEquals(ActivityLog.Outcome.FAILED, entry(label("Adding the worktree")).outcome());
            assertEquals(1, entriesMentioning(tag), "the failure is in the log once, on its operation");
        });
    }

    @Test
    void aCancelledOperationIsAWarningAndTheBarComesDown() {
        FxHarness.run(() -> {
            StatusBar bar = new StatusBar();
            Object operation = bar.operations().busy(label("Staging all"));
            bar.operations().idle(operation);

            bar.operations().cancelled(operation);

            assertEquals(ActivityLog.Outcome.WARNING, entry(label("Staging all")).outcome());
            assertEquals(0, bar.busyDepth());
            assertTrue(FxHarness.lookupAll(bar, "status-message").stream()
                    .anyMatch(n -> ((javafx.scene.control.Label) n).getText().equals(label("Staging all") + " cancelled")));
        });
    }
}
