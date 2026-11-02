package com.jfx.git.ui;

import com.jfx.git.core.ActivityLog;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The activity log tab has to render what the log holds.
 *
 * <p>The rendering is where the two can drift apart: an operation becomes two lines, a
 * note becomes one, and a failure has to still look like a failure after the scope filter
 * and the text filter have both had a go at it.
 */
class ActivityLogTabTest {

    private ActivityLogTab tab;

    @BeforeEach
    void clearTheLog() {
        ActivityLog.clear();
    }

    @AfterEach
    void closeTheTab() {
        FxHarness.run(() -> {
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
    }

    /** Opens the log tab on screen and hands back every rendered line, in order. */
    private List<String> rows(String repository) {
        return FxHarness.call(() -> {
            tab = new ActivityLogTab(repository);
            FxHarness.show(new TabPane(tab), 1000, 600);
            return visibleRows();
        });
    }

    /** The lines of the tab's log lists, flattened. */
    private List<String> visibleRows() {
        List<String> lines = new ArrayList<>();
        for (Node node : FxHarness.lookupAll(tab.getContent(), "activity-log-list")) {
            if (!(node instanceof ListView<?> list)) continue;
            for (Object item : list.getItems()) {
                lines.add(String.valueOf(item));
            }
        }
        return lines;
    }

    /** Every label the open tab carries with {@code styleClass}, as text. */
    private List<String> labelTexts(String styleClass) {
        FxHarness.pulse((javafx.scene.Parent) tab.getContent());
        List<String> texts = new ArrayList<>();
        for (Node node : FxHarness.lookupAll(tab.getContent(), styleClass)) {
            if (node instanceof Label label && label.getText() != null
                    && !label.getText().isEmpty() && label.isVisible() && isOnShownView(label)) {
                texts.add(label.getText());
            }
        }
        return texts;
    }

    /**
     * Whether the label is on the inner view that is selected. Both views' lists stay in
     * the scene graph, but only the selected tab's content is shown.
     */
    private static boolean isOnShownView(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    @Test
    void aFinishedOperationRendersAsAStartedAndAFinishedLine() {
        var entry = ActivityLog.begin(ActivityLog.Scope.REPOSITORY, "demo", "Staging 3 files");
        ActivityLog.finish(entry, ActivityLog.Outcome.OK, null);

        rows("demo");

        // Rendered rows are records; the cell factory turns them into labels, so assert
        // on what those labels say rather than on the row objects' toString.
        FxHarness.run(() -> {
            List<String> text = labelTexts("activity-log-text");
            assertTrue(text.contains("Staging 3 files: started."),
                    () -> "expected a started line, got " + text);
            assertTrue(text.contains("Staging 3 files: finished."),
                    () -> "expected a finished line, got " + text);

            List<String> durations = labelTexts("activity-log-duration");
            assertEquals(1, durations.size(),
                    "only the finished line carries a duration: " + durations);
            assertTrue(durations.get(0).endsWith("ms"), durations.get(0));
        });
    }

    @Test
    void aRunningOperationHasNoFinishedLineAndNoDuration() {
        ActivityLog.begin(ActivityLog.Scope.REPOSITORY, "demo", "Cloning");

        rows("demo");

        FxHarness.run(() -> {
            List<String> text = labelTexts("activity-log-text");
            assertEquals(List.of("Cloning: started."), text,
                    "work in flight must not be drawn as work that finished");
            assertTrue(labelTexts("activity-log-duration").isEmpty(),
                    "there is no duration yet to report");
        });
    }

    @Test
    void aNoteIsOneLine() {
        ActivityLog.note("Lyra started");

        rows(null);

        FxHarness.run(() -> assertTrue(labelTexts("activity-log-text").contains("Lyra started"),
                "an application note should be on the Application tab"));
    }

    @Test
    void oneRepositorysWorkDoesNotAppearUnderAnother() {
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "alpha", "Pulled alpha",
                ActivityLog.Outcome.OK);
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "beta", "Pulled beta",
                ActivityLog.Outcome.OK);

        rows("alpha");

        FxHarness.run(() -> {
            List<String> text = labelTexts("activity-log-text");
            assertTrue(text.contains("Pulled alpha"), () -> "alpha's own work: " + text);
            assertFalse(text.contains("Pulled beta"),
                    () -> "beta's work belongs on beta's tab: " + text);
        });
    }

    @Test
    void theFilterNarrowsToMatchingLines() {
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "demo", "Staged a.txt",
                ActivityLog.Outcome.OK);
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "demo", "Pushed to origin",
                ActivityLog.Outcome.OK);

        rows("demo");

        FxHarness.run(() -> {
            TextField filter = (TextField) FxHarness.lookupAll(
                    tab.getContent(), "file-filter-field").get(0);
            filter.setText("push");
        });

        FxHarness.run(() -> {
            List<String> text = labelTexts("activity-log-text");
            assertTrue(text.contains("Pushed to origin"), () -> "the match should stay: " + text);
            assertFalse(text.contains("Staged a.txt"), () -> "the rest should go: " + text);
        });
    }

    @Test
    void aFailureIsStyledAsOne() {
        var entry = ActivityLog.begin(ActivityLog.Scope.REPOSITORY, "demo", "Pushing");
        ActivityLog.finish(entry, ActivityLog.Outcome.FAILED, "rejected: non-fast-forward");

        rows("demo");

        FxHarness.run(() -> {
            List<String> failed = labelTexts("activity-log-failed");
            assertEquals(1, failed.size(), "the finished line is the one that failed: " + failed);
            assertTrue(failed.get(0).contains("Pushing: failed."), failed.get(0));
            assertTrue(failed.get(0).contains("rejected: non-fast-forward"),
                    "the reason belongs on the line: " + failed.get(0));
        });
    }

    /**
     * Asked for again from another repository, the one log tab follows it: the repository
     * view shows that repository's work, and not the one it was opened on.
     */
    @Test
    void askingAgainFromAnotherRepositoryPointsTheLogThere() {
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "alpha", "Pulled alpha", ActivityLog.Outcome.OK);
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "beta", "Pulled beta", ActivityLog.Outcome.OK);

        rows("alpha");
        FxHarness.run(() -> {
            tab.showRepository("beta");
            assertEquals("beta", tab.repository());
            List<String> text = labelTexts("activity-log-text");
            assertTrue(text.contains("Pulled beta"), () -> "beta's work once pointed at beta: " + text);
            assertFalse(text.contains("Pulled alpha"), () -> "and not alpha's: " + text);

            tab.showRepository(null);
            assertEquals("beta", tab.repository(), "asked with no repository in front, it stays where it was");
        });
    }

    /** Closing the tab stops it listening: a log written afterwards reaches no list. */
    @Test
    void disposingStopsFollowingTheLog() {
        rows("demo");
        FxHarness.run(() -> tab.dispose());
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "demo", "After close", ActivityLog.Outcome.OK);
        FxHarness.run(() -> { });
        FxHarness.run(() -> assertFalse(visibleRows().stream().anyMatch(r -> r.contains("After close")),
                "a disposed tab no longer repaints"));
        tab = null;
    }
}
