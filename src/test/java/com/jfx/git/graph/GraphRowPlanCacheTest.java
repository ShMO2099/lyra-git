package com.jfx.git.graph;

import com.jfx.git.core.GitBackend;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graph cell paints from the plan kept on the row: worked out on the first paint,
 * reused by every repaint that changes nothing it depends on - a theme switch included -
 * and replaced when the lane budget moves under it.
 */
class GraphRowPlanCacheTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.DARK));
        FxHarness.closeShownStages();
    }

    private static GitBackend stubBackend() {
        return (GitBackend) Proxy.newProxyInstance(GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class}, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    /** Eight tips side by side over one root: eight lanes arrive on the root's row. */
    private static List<GitGraphRow> wide() {
        List<CommitInfo> commits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            commits.add(new CommitInfo("tip" + i, "tip " + i, "T", "t@e.com",
                    Instant.ofEpochSecond(100 - i), List.of("root"), List.of()));
        }
        commits.add(new CommitInfo("root", "root", "T", "t@e.com", Instant.ofEpochSecond(1), List.of(), List.of()));
        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo commit : commits) rows.add(new GitGraphRow(commit));
        return rows;
    }

    @Test
    void theCellKeepsOnePlanPerRowAndBudget() {
        Object[] result = FxHarness.call(() -> {
            List<GitGraphRow> rows = wide();
            GitGraphRow root = rows.get(rows.size() - 1);
            GraphMetrics metrics = new GraphMetrics(GraphDensity.current(), GraphNodeStyle.DOT);
            metrics.update(rows, "main");
            ListView<GitGraphRow> list = new ListView<>();
            list.getItems().setAll(rows);
            GitGraphCell cell = new GitGraphCell(OperationRunner.of(stubBackend(), new StatusBar(), () -> { }), (a, b) -> { }, metrics);
            StackPane pane = new StackPane(list, cell);
            Stage stage = FxHarness.show(pane, 700, 300);
            stage.setUserData("keep-alive");
            cell.updateListView(list);

            cell.updateIndex(rows.size() - 1);
            FxHarness.pulse(pane);
            RowPlan first = root.plan();
            int budget = metrics.maxLanes();

            // Scrolled away and back, as recycling does.
            cell.updateIndex(0);
            cell.updateIndex(rows.size() - 1);
            RowPlan again = root.plan();

            ThemeManager.apply(ThemeManager.Theme.LIGHT);
            cell.updateIndex(0);
            cell.updateIndex(rows.size() - 1);
            RowPlan themed = root.plan();

            // Dragging the graph narrower repaints the canvas alone, through the lane width.
            metrics.setLaneBudget(GraphDensity.MIN_DRAWN_LANES);
            RowPlan narrowed = root.plan();
            return new Object[]{first, budget, again, themed, narrowed};
        });
        RowPlan first = (RowPlan) result[0];
        RowPlan narrowed = (RowPlan) result[4];

        assertNotNull(first, "the first paint works the plan out and keeps it on the row");
        assertTrue(first.isFor((int) result[1], GraphNodeStyle.DOT, 8));
        assertFalse(first.hasMarker(), "eight lanes fit the default budget");
        assertSame(first, result[2], "a repaint reuses it");
        assertSame(first, result[3], "so does a theme switch: the plan holds palette indices, not colours");
        assertEquals(GraphDensity.MIN_DRAWN_LANES, narrowed.budget(),
                "a new budget paints from a new plan");
        assertTrue(narrowed.hasMarker(), "which now shows the overflow marker");
    }
}
