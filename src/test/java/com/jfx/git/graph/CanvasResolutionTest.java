package com.jfx.git.graph;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.Settings;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.UiZoom;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ListView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How many real pixels the lane drawing is given.
 *
 * <h2>What was wrong</h2>
 * The commit graph is painted onto a {@link Canvas}, whose buffer JavaFX sizes for the
 * display's density but not for anything in the scene graph above it. The zoom is a scale
 * transform, so at 150% the lanes and the author discs were drawn into a logical-sized
 * buffer and then magnified — visibly soft against text the toolkit had rendered at full
 * resolution.
 *
 * <p>These are about the buffer, not about appearance, because the buffer is the part that
 * can be measured. A row drawn into 50 pixels and stretched over 75 cannot be sharp,
 * whatever else is true.
 */
class CanvasResolutionTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @BeforeEach
    void useTempFile() {
        Settings.useFile(tempDir.resolve("settings.properties"));
        UiZoom.resetForTests();
    }

    @AfterEach
    void restoreDefault() {
        // Each test shows a graph; a window left open keeps it, about 4 MB, for the run.
        FxHarness.closeShownStages();
        UiZoom.resetForTests();
        Settings.useFile(null);
    }

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

    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>(List.of(
                new CommitInfo("aaaaaaaa1", "first", "Ada Lovelace", "ada@example.com",
                        Instant.now(), List.of("aaaaaaaa2"), List.of()),
                new CommitInfo("aaaaaaaa2", "second", "Ada Lovelace", "ada@example.com",
                        Instant.now(), List.of(), List.of())));
        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) {
            rows.add(new GitGraphRow(c));
        }
        return rows;
    }

    /** Builds and shows a graph, and hands back the first realised lane canvas. */
    private static Canvas laneCanvas() {
        return FxHarness.call(() -> {
            GraphDensity density = GraphDensity.COMPACT;
            GraphMetrics metrics = new GraphMetrics(density, GraphNodeStyle.DOT);
            List<GitGraphRow> rows = rows();
            metrics.update(rows, "main");

            ListView<GitGraphRow> list = new ListView<>();
            list.setFixedCellSize(density.rowHeight());
            list.setCellFactory(lv -> new GitGraphCell(OperationRunner.of(stubBackend(), new StatusBar(), () -> { }), (a, b) -> { }, metrics));
            list.getItems().setAll(rows);

            FxHarness.show(new javafx.scene.layout.VBox(list), 900, 300);
            list.applyCss();
            list.layout();

            for (Node node : FxHarness.lookupAll(list, "lane-canvas")) {
                if (node instanceof Canvas canvas && canvas.getWidth() > 0) {
                    return canvas;
                }
            }
            throw new AssertionError("no lane canvas was realised");
        });
    }

    // ------------------------------------------------------------------

    @Test
    void atFullSizeTheBufferIsTheLogicalSize() {
        Canvas canvas = laneCanvas();
        double logicalWidth = FxHarness.call(() -> canvas.getBoundsInParent().getWidth());

        assertEquals(logicalWidth, FxHarness.call(canvas::getWidth), 1.0,
                "unzoomed, there is nothing to compensate for");
    }

    /**
     * The fix. At 150% the buffer has to hold half again as many pixels, or the row is
     * drawn small and stretched.
     */
    @Test
    void zoomingInBuysTheCanvasMorePixels() {
        double atFullSize = FxHarness.call(laneCanvas()::getWidth);

        UiZoom.set(150);
        double zoomed = FxHarness.call(laneCanvas()::getWidth);

        assertEquals(atFullSize * 1.5, zoomed, 2.0,
                "the buffer must grow with the zoom, not be magnified after the fact");
    }

    @Test
    void theCanvasStillOccupiesItsLogicalWidthInTheRow() {
        UiZoom.set(200);
        Canvas canvas = laneCanvas();

        double buffer = FxHarness.call(canvas::getWidth);
        double onScreen = FxHarness.call(() -> canvas.getBoundsInParent().getWidth());

        assertEquals(buffer / 2.0, onScreen, 1.0,
                "the oversized buffer has to be scaled back down, or the lane column "
                        + "claims twice the width it draws");
    }

    @Test
    void theCanvasIsWrappedSoTheGridMeasuresItAfterTheShrink() {
        UiZoom.set(200);
        Canvas canvas = laneCanvas();

        assertInstanceOf(Group.class, FxHarness.call(canvas::getParent),
                "a GridPane measures a non-resizable child by its untransformed bounds, "
                        + "so the shrink has to happen inside something that reports it");
    }

    @Test
    void zoomingBackOutReturnsTheBufferToTheLogicalSize() {
        double atFullSize = FxHarness.call(laneCanvas()::getWidth);

        UiZoom.set(200);
        UiZoom.set(100);

        assertEquals(atFullSize, FxHarness.call(laneCanvas()::getWidth), 1.0,
                "a buffer left oversized would waste memory on every row");
    }
}
