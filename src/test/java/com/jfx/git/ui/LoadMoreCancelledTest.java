package com.jfx.git.ui;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A "load more" cancelled from the status bar before it started leaves the bar idle and
 * "load more" working.
 *
 * <p>The bar follows the task submitted after its busy, so its Cancel - shown for a fetch
 * reporting progress - reaches a "load more" queued behind that fetch. Cancelled before it
 * started, the task was dropped without calling anything back: the graph's busy span never
 * ended, and every later "load more" was ignored while it waited on the request.
 */
class LoadMoreCancelledTest {

    @TempDir
    Path tempDir;

    private static long commits(RepoTab tab) {
        return tab.getCommitList().getItems().stream().filter(row -> row.getCommit() != null).count();
    }

    private static void selectLoadMore(RepoTab tab) {
        var items = tab.getCommitList().getItems();
        GitGraphRow last = items.get(items.size() - 1);
        assertTrue(last.isLoadMore(), "precondition: older history follows the page");
        tab.getCommitList().getSelectionModel().clearAndSelect(items.size() - 1);
    }

    @Test
    void aLoadMoreCancelledBeforeItStartsLeavesTheBarIdleAndLoadMoreWorking() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RobotWait.drainGitWork();
            RepoTab tab = window.tab();
            FxHarness.run(() -> {
                tab.setGraphLimitForTest(2);
                tab.refresh();
            });
            RobotWait.drainGitWork();
            long shownBefore = FxHarness.call(() -> commits(tab));

            // Work ahead of it on the tab's lane, as a fetch would be: the load more queues
            // behind it. The tab's own backend, which wraps the fixture's - another lane.
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            GitTaskRunner.run(tab.getGitService(), () -> {
                running.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return GitResult.ok();
            }, result -> { });
            try {
                assertTrue(running.await(30, TimeUnit.SECONDS), "precondition: the lane is busy");
                FxHarness.run(() -> {
                    selectLoadMore(tab);
                    ((Button) tab.getStatusBar().lookup(".status-cancel")).fire();
                });
            } finally {
                release.countDown();
            }
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the status bar stayed busy after the cancelled load more",
                    () -> tab.getStatusBar().busyDepth() == 0);

            FxHarness.run(() -> {
                tab.getCommitList().getSelectionModel().clearAndSelect(0);
                selectLoadMore(tab);
            });
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("load more stopped working after the cancelled one",
                    () -> commits(tab) > shownBefore);
        }
    }
}
