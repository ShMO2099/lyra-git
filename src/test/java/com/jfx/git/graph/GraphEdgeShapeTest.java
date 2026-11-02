package com.jfx.git.graph;

import com.jfx.git.core.GitBackend;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ListView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape an edge is painted in, read off the pixels.
 *
 * <p>The layout says which lanes meet on a row; this checks the painter turns them the
 * way commercial clients do. On the parent's row, a side branch arriving from the lane to its
 * right comes straight down that lane to the node's height and turns into the node - it
 * does not run across the top of the row. On the merge's row, the edge leaving for the
 * side branch runs across at the node's height and turns down.
 */
class GraphEdgeShapeTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
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

    private static CommitInfo c(String hash, long t, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "T", "t@e.com",
                Instant.ofEpochSecond(t), List.of(parents), List.of());
    }

    private record Painted(PixelReader pixels, int width, int height, int lane0, int lane1) {
        boolean ink(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) return false;
            Color c = pixels.getColor(x, y);
            return c.getOpacity() > 0.5 && c.getSaturation() > 0.25;
        }
    }

    /** Paints row {@code index} of merge / side / trunk in Dot style and reads its pixels. */
    private static Painted paint(int index) {
        return FxHarness.call(() -> {
            CommitInfo merge = c("merge", 30, "trunk", "side");
            CommitInfo side = c("side", 20, "trunk");
            CommitInfo trunk = c("trunk", 10);
            List<CommitInfo> commits = List.of(merge, side, trunk);
            LaneAssigner.assign(commits);
            List<GitGraphRow> rows = new ArrayList<>();
            for (CommitInfo commit : commits) rows.add(new GitGraphRow(commit));

            GraphDensity density = GraphDensity.current();
            GraphMetrics metrics = new GraphMetrics(density, GraphNodeStyle.DOT);
            metrics.update(rows, "main");
            ListView<GitGraphRow> list = new ListView<>();
            list.getItems().setAll(rows);
            GitGraphCell cell = new GitGraphCell(OperationRunner.of(stubBackend(), new StatusBar(), () -> { }), (a, b) -> { }, metrics);
            Stage stage = FxHarness.show(new javafx.scene.layout.StackPane(list, cell), 600, 200);
            stage.setUserData("keep-alive");
            cell.updateListView(list);
            cell.updateIndex(index);
            Canvas canvas = (Canvas) cell.lookup(".lane-canvas");
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            WritableImage image = canvas.snapshot(parameters, null);
            int lanes = metrics.maxLanes();
            return new Painted(image.getPixelReader(), (int) image.getWidth(), (int) image.getHeight(),
                    density.laneX(0, GraphNodeStyle.DOT, lanes), density.laneX(1, GraphNodeStyle.DOT, lanes));
        });
    }

    @Test
    void aSideBranchArrivesDownItsOwnLaneAndTurnsIntoTheParent() {
        Painted row = paint(2);   // the trunk commit, receiving the side branch
        int mid = row.height() / 2;
        int between = (row.lane0() + row.lane1()) / 2;

        assertTrue(IntStream.range(1, mid - 7).allMatch(y -> row.ink(row.lane1(), y)),
                "the side lane comes straight down its own column through the top half");
        assertFalse(row.ink(between, 1) || row.ink(between, 2),
                "and does not run across the top of the row");
        assertTrue(row.ink(between, mid) || row.ink(between, mid - 1) || row.ink(between, mid + 1),
                "it turns across into the node at the node's height");
    }
}
