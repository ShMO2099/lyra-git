package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.Settings;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.LaneAssigner;
import com.jfx.git.model.CommitInfo;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dragging the graph's trailing edge to set how wide it may grow.
 *
 * <p>{@code GraphLaneBudgetTest} covers what the budget does; this covers whether a drag
 * reaches it, and whether the width is still there after a restart.
 */
class LaneResizeRobotTest extends ApplicationTest {

    private RepoTab tab;

    private static GitBackend stubBackend() {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getCurrentBranch".equals(method.getName())) return "main";
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    /**
     * A history wide enough to have lanes worth hiding.
     *
     * <p>Eight branches side by side, so the budget has something to clamp: on a linear
     * history the column is one lane wide whatever is dragged, and the test would be
     * measuring nothing.
     */
    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>();
        // Every tip first, then every middle, then the shared root. The branches are all
        // open at the same time this way, which is when lanes are actually needed —
        // eight branches one after another would reuse a single lane and the graph would
        // be one column wide however far the handle was dragged.
        for (int branch = 0; branch < 8; branch++) {
            commits.add(new CommitInfo("t" + branch, "tip " + branch, "Tester", "t@example.com",
                    Instant.now(), List.of("m" + branch), List.of()));
        }
        for (int branch = 0; branch < 8; branch++) {
            commits.add(new CommitInfo("m" + branch, "mid " + branch, "Tester", "t@example.com",
                    Instant.now(), List.of("root"), List.of()));
        }
        commits.add(new CommitInfo("root", "the root", "Tester", "t@example.com",
                Instant.now(), List.of(), List.of()));

        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) {
            rows.add(new GitGraphRow(c));
        }
        return rows;
    }

    @Override
    public void init() {
        com.jfx.git.core.AppPreferences.setGraphDensity(com.jfx.git.graph.GraphDensity.COMPACT);
        com.jfx.git.core.AppPreferences.setGraphNodeStyle(com.jfx.git.graph.GraphNodeStyle.DOT);
        com.jfx.git.core.AppPreferences.setGraphLaneBudget(0);
        com.jfx.git.graph.GraphColumn.resetToDefault();
    }

    @AfterEach
    void tidyUp() {
        if (tab != null) interact(tab::close);
        com.jfx.git.core.AppPreferences.setGraphLaneBudget(0);
    }

    @Override
    public void start(Stage stage) {
        tab = new RepoTab(new LyraApp(), "test", stubBackend());
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        tab.showRows(rows());
    }

    private Node handle() {
        return lookup(".graph-lane-resizer").query();
    }

    /** Width of the lane column in the header, which the rows share. */
    private double laneColumnWidth() {
        WaitForAsyncUtils.waitForFxEvents();
        Node header = lookup(".graph-header").query();
        return ((javafx.scene.layout.GridPane) header)
                .getColumnConstraints().get(2).getPrefWidth();
    }

    // ------------------------------------------------------------------

    @Test
    void theHandleIsThere() {
        assertNotNull(handle(), "the graph's trailing edge should be draggable");
    }

    /**
     * The reported complaint. It first lived in the column header, twenty-two pixels of a
     * window hundreds deep, so the boundary had to be aimed at rather than simply taken.
     */
    @Test
    void theHandleRunsTheFullHeightOfTheGraph() {
        double handleHeight = handle().getBoundsInParent().getHeight();
        double graphHeight = lookup(".commit-graph").query().getBoundsInParent().getHeight();

        assertTrue(handleHeight >= graphHeight,
                "the boundary runs the whole way down, so the grip should too: "
                        + handleHeight + " vs a graph " + graphHeight + " tall");
    }

    /** Grabbing it level with a commit far below the heading has to work. */
    @Test
    void theHandleCanBeTakenLowDownTheGraph() {
        double before = laneColumnWidth();
        javafx.geometry.Bounds grip = handle().localToScreen(handle().getBoundsInLocal());

        // Three-quarters of the way down, which is nowhere near the header.
        double x = (grip.getMinX() + grip.getMaxX()) / 2;
        double y = grip.getMinY() + grip.getHeight() * 0.75;
        moveTo(new javafx.geometry.Point2D(x, y));
        press(javafx.scene.input.MouseButton.PRIMARY);
        moveTo(new javafx.geometry.Point2D(x - 40, y));
        release(javafx.scene.input.MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(laneColumnWidth() < before,
                "dragging from low down should resize just as the top does");
    }

    /**
     * The reported complaint, at its cause.
     *
     * <p>Applying the lane-rounded width on every event made the boundary jump between
     * lanes and stand still in between, which read as lag. Moving only the line and
     * leaving the graph until release cured the lag but left a boundary that visibly
     * bounded nothing. Both the line and the column it divides have to follow the pointer.
     */
    @Test
    void theLineAndTheGraphBothFollowThePointerByLessThanALane() {
        javafx.geometry.Bounds grip = handle().localToScreen(handle().getBoundsInLocal());
        double x = (grip.getMinX() + grip.getMaxX()) / 2;
        double y = grip.getMinY() + grip.getHeight() / 2;

        moveTo(new javafx.geometry.Point2D(x, y));
        press(javafx.scene.input.MouseButton.PRIMARY);
        double lineBefore = handle().getTranslateX();
        double graphBefore = laneColumnWidth();

        // Five pixels: less than a lane, so anything that quantises mid-drag cannot move.
        moveTo(new javafx.geometry.Point2D(x - 5, y));
        WaitForAsyncUtils.waitForFxEvents();

        double lineDuring = handle().getTranslateX();
        double graphDuring = laneColumnWidth();
        release(javafx.scene.input.MouseButton.PRIMARY);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(lineBefore - 5, lineDuring, 1.0,
                "the line has to track the pointer, not the nearest lane");
        assertEquals(graphBefore - 5, graphDuring, 1.0,
                "and the column has to travel with it, or the line divides nothing");
    }

    @Test
    void theGraphSnapsToALaneOnRelease() {
        double before = laneColumnWidth();

        drag(handle()).dropBy(-40, 0);
        WaitForAsyncUtils.waitForFxEvents();

        double after = laneColumnWidth();
        assertTrue(after < before, "the release should have applied the new width");
        assertEquals(0, (after - 6) % com.jfx.git.graph.GraphDensity.COMPACT
                        .laneWidth(com.jfx.git.graph.GraphNodeStyle.DOT), 0.01,
                "and it should land on a whole number of lanes: " + after);
    }

    @Test
    void aPlainClickDoesNotPinTheWidth() {
        clickOn(handle());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0, com.jfx.git.core.AppPreferences.graphLaneBudget(),
                "clicking the boundary without moving it should decide nothing");
    }

    @Test
    void draggingInwardNarrowsTheGraph() {
        double before = laneColumnWidth();

        drag(handle()).dropBy(-40, 0);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(laneColumnWidth() < before,
                "dragging toward the messages should give them the room: "
                        + before + " -> " + laneColumnWidth());
    }

    @Test
    void draggingBackOutWidensItAgain() {
        drag(handle()).dropBy(-40, 0);
        WaitForAsyncUtils.waitForFxEvents();
        double narrowed = laneColumnWidth();

        drag(handle()).dropBy(40, 0);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(laneColumnWidth() > narrowed, "the graph should come back");
    }

    @Test
    void aDragIsRememberedForNextTime() {
        drag(handle()).dropBy(-40, 0);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(com.jfx.git.core.AppPreferences.graphLaneBudget() > 0,
                "a width someone deliberately set should not have to be set again");
        assertNotNull(Settings.file());
    }

    @Test
    void doubleClickingTheHandleHandsTheWidthBackToTheWindow() {
        drag(handle()).dropBy(-40, 0);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(com.jfx.git.core.AppPreferences.graphLaneBudget() > 0);

        doubleClickOn(handle());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0, com.jfx.git.core.AppPreferences.graphLaneBudget(),
                "double-click should clear the override, not set another width");
    }
}
