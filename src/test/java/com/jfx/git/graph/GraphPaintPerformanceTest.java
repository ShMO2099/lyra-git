package com.jfx.git.graph;

import com.jfx.git.core.GitBackend;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;

import javafx.scene.control.ListView;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What painting one graph row costs, so scrolling smoothness is measured rather than
 * guessed.
 *
 * <p>A cell repaints its whole row - lanes, edges, node, avatar - every time a row scrolls
 * into it, so the per-row paint is the unit that decides how a fast scroll feels. This
 * builds a 1000-commit history with branches, merges and up to a dozen lanes, paints
 * every row three times in each node style through one cell (as the list's recycling
 * does), and reports the median and 95th percentile per row. The budget is generous for
 * a headless software renderer; the printed numbers are the point.
 *
 * <p>What it found (2026-10-04): about 0.25 ms per row median, painting and rendering
 * included, in either node style. An image cache for author avatars was tried against it
 * and changed nothing measurable - the initials are not the expensive part - so it was
 * not kept, and the old reason for drawing avatars as vectors (a cached bitmap goes
 * blocky under zoom) still stands.
 */
@Tag("slow")
class GraphPaintPerformanceTest {

    /** Per-row paint and render, 95th percentile, headless software rendering. */
    private static final double BUDGET_MILLIS = 5.0;

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
                    if ("getCurrentBranch".equals(method.getName())) return "main";
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    /**
     * A history with a trunk and short-lived branches that fork and merge back, a dozen
     * authors and a few refs: the shapes the painter has to draw, in realistic mixture.
     */
    static List<GitGraphRow> history(int size) {
        Random random = new Random(42);
        String[] authors = {"Ada Lovelace", "Grace Hopper", "Linus Torvalds", "Margaret Hamilton",
                "Ken Thompson", "Barbara Liskov", "Dennis Ritchie", "Frances Allen",
                "Edsger Dijkstra", "Radia Perlman", "Donald Knuth", "Sophie Wilson"};
        List<CommitInfo> commits = new ArrayList<>(size);
        // Built newest first; each commit's parents are later in the list.
        List<String> open = new ArrayList<>();   // branch tips awaiting a parent
        Instant when = Instant.now();
        for (int i = 0; i < size; i++) {
            String hash = String.format("%040x", i + 1);
            String next = String.format("%040x", i + 2);
            List<String> parents = new ArrayList<>();
            if (i < size - 1) parents.add(next);
            if (i < size - 30 && random.nextInt(6) == 0 && open.size() < 11) {
                // A merge: the second parent starts a side branch a few commits down.
                String side = String.format("%040x", size + i + 1);
                parents.add(side);
                open.add(side);
            }
            List<RefInfo> refs = i % 97 == 0
                    ? List.of(new RefInfo("refs/heads/topic-" + i, "topic-" + i, hash)) : List.of();
            String author = authors[random.nextInt(authors.length)];
            commits.add(new CommitInfo(hash, "commit " + i, author, author.replace(' ', '.') + "@example.com",
                    when.minusSeconds(i * 600L), parents, refs));
            // Side branches: a few commits each, joining back onto the trunk.
            if (!open.isEmpty() && random.nextInt(3) == 0) {
                String side = open.remove(0);
                commits.add(new CommitInfo(side, "side work", author, "side@example.com",
                        when.minusSeconds(i * 600L + 300), List.of(next), List.of()));
            }
        }
        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) rows.add(new GitGraphRow(c));
        return rows;
    }

    private static double[] measure(GraphNodeStyle style, List<GitGraphRow> rows) {
        return FxHarness.call(() -> {
            GraphDensity density = GraphDensity.current();
            GraphMetrics metrics = new GraphMetrics(density, style);
            metrics.update(rows, "main");
            ListView<GitGraphRow> list = new ListView<>();
            GitGraphCell cell = new GitGraphCell(OperationRunner.of(stubBackend(), new StatusBar(), () -> { }), (a, b) -> { }, metrics);
            list.getItems().setAll(rows);
            Stage stage = FxHarness.show(new javafx.scene.layout.StackPane(list, cell), 900, 300);
            stage.setUserData("keep-alive");
            cell.updateListView(list);
            javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
            parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
            javafx.scene.image.WritableImage[] target = {null};
            // Canvas calls are only recorded when made; the drawing itself happens when the
            // canvas is rendered. A snapshot renders it now, so the cost of what was
            // recorded - text in particular - is in the measurement.
            Runnable render = () -> {
                javafx.scene.canvas.Canvas canvas = (javafx.scene.canvas.Canvas) cell.lookup(".lane-canvas");
                if (canvas == null) return;
                if (target[0] == null || target[0].getWidth() != canvas.getWidth()
                        || target[0].getHeight() != canvas.getHeight()) {
                    target[0] = new javafx.scene.image.WritableImage(
                            (int) Math.max(1, canvas.getWidth()), (int) Math.max(1, canvas.getHeight()));
                }
                canvas.snapshot(parameters, target[0]);
            };
            // Warm-up: class loading, font lookup and any first-use caches.
            for (int i = 0; i < Math.min(200, rows.size()); i++) {
                cell.updateIndex(i);
                render.run();
            }
            double[] samples = new double[rows.size() * 3];
            int n = 0;
            for (int pass = 0; pass < 3; pass++) {
                for (int i = 0; i < rows.size(); i++) {
                    long start = System.nanoTime();
                    cell.updateIndex(i);
                    render.run();
                    samples[n++] = (System.nanoTime() - start) / 1_000_000.0;
                    // Alternate so updateIndex never short-circuits on an unchanged index.
                    cell.updateIndex(-1);
                }
            }
            Arrays.sort(samples);
            return new double[]{samples[samples.length / 2], samples[(int) (samples.length * 0.95)],
                    samples[samples.length - 1]};
        });
    }

    @Test
    void paintingARowStaysCheap() {
        List<GitGraphRow> rows = history(1000);
        int lanes = rows.get(0).getCommit().getTotalLanes();
        for (GraphNodeStyle style : GraphNodeStyle.values()) {
            double[] result = measure(style, rows);
            System.out.printf("graph-paint,%s,rows=%d,lanes=%d,p50=%.3fms,p95=%.3fms,max=%.3fms%n",
                    style, rows.size(), lanes, result[0], result[1], result[2]);
            assertTrue(result[1] < BUDGET_MILLIS,
                    style + ": painting a row costs " + result[1] + " ms at the 95th percentile");
        }
    }
}
