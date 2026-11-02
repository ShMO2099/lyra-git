package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.FxHarness;

import javafx.scene.control.Label;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lanes past the budget are visibly clipped, not silently stacked.
 *
 * <p>Every lane past the graph's budget used to be drawn in its last column, one on top
 * of another, with nothing to say so - a crowded graph looked like one confused line. That
 * column now carries a dotted grey marker standing for all of them - the row plan's, and
 * {@link RowPlanTest}'s to assert - and the header says how many there are.
 */
class GraphOverflowTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    /** Eight tips side by side over one root: eight lanes on the root's row. */
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
    void theMetricsCountTheLanesThatDidNotFit() {
        GraphMetrics metrics = new GraphMetrics(GraphDensity.current(), GraphNodeStyle.DOT);
        metrics.setLaneBudget(GraphDensity.MIN_DRAWN_LANES);      // 4 columns
        metrics.update(wide(), "main");
        assertEquals(8, metrics.lanesInPage());
        assertEquals(5, metrics.hiddenLanes(), "three columns of their own, five sharing the fourth");

        metrics.setLaneBudget(12);
        assertEquals(0, metrics.hiddenLanes(), "room for all eight: nothing hidden");
    }

    /**
     * The header counts the lanes sharing the overflow column and explains them on hover.
     * What the column itself draws - one marker in place of the stacked lanes - is the row
     * plan's, asserted as data in {@link RowPlanTest}.
     */
    @Test
    void theHeaderSaysHowManyLanesShareTheOverflowColumn() {
        Object[] result = FxHarness.call(() -> {
            GraphDensity density = GraphDensity.current();
            GraphMetrics metrics = new GraphMetrics(density, GraphNodeStyle.DOT);
            metrics.setLaneBudget(GraphDensity.MIN_DRAWN_LANES);
            metrics.update(wide(), "main");
            GraphHeader header = new GraphHeader(density, metrics, GraphNodeStyle.DOT);
            Stage stage = FxHarness.show(new javafx.scene.layout.VBox(header), 700, 60);
            stage.setUserData("keep-alive");
            Label caption = (Label) FxHarness.lookupAll(header, "graph-lane-overflow").get(0);
            return new Object[]{caption.getText(), caption.getTooltip() != null};
        });
        assertEquals("+5", result[0], "the header counts the lanes sharing the column");
        assertTrue((boolean) result[1], "and explains them on hover");
    }
}
