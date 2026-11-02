package com.jfx.git.graph;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.Settings;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;

import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.GridPane;
import javafx.util.Callback;

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
 * Whether hiding a column actually removes it from the <em>rows</em>.
 *
 * <h2>The bug these exist for</h2>
 * Hiding a column removed its heading and left the data underneath. The header is
 * replaced when the column set changes, so it updated; the rows were only
 * {@code refresh()}ed, and {@link ListView#refresh()} re-runs {@code updateItem} on the
 * cells that already exist rather than building new ones. A {@link GitGraphCell} reads
 * the column set once, in its constructor, so every realised row went on drawing the
 * column the user had just switched off.
 *
 * <p>Testing the stored flag or the header alone cannot see that, which is why these
 * assert against realised cells in a shown list.
 */
class GraphRowColumnsTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @BeforeEach
    void useTempFile() {
        Settings.useFile(tempDir.resolve("settings.properties"));
    }

    @AfterEach
    void restoreDefault() {
        // Each test shows a graph; a window left open keeps it, about 4 MB, for the run.
        FxHarness.closeShownStages();
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
        List<CommitInfo> commits = new ArrayList<>();
        commits.add(new CommitInfo("aaaaaaaaa", "first subject", "Tester", "t@example.com",
                Instant.now(), List.of("bbbbbbbbb"), List.of()));
        commits.add(new CommitInfo("bbbbbbbbb", "second subject", "Tester", "t@example.com",
                Instant.now(), List.of(), List.of()));
        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) rows.add(new GitGraphRow(c));
        return rows;
    }

    /** A shown list whose cells are real {@link GitGraphCell}s. */
    private record Graph(ListView<GitGraphRow> list, Callback<ListView<GitGraphRow>,
            ListCell<GitGraphRow>> factory) {}

    private static Graph graph() {
        return FxHarness.call(() -> {
            GraphDensity density = GraphDensity.COMPACT;
            GraphMetrics metrics = new GraphMetrics(density, GraphNodeStyle.DOT);
            List<GitGraphRow> rows = rows();
            metrics.update(rows, "main");

            Callback<ListView<GitGraphRow>, ListCell<GitGraphRow>> factory =
                    lv -> new GitGraphCell(OperationRunner.of(stubBackend(), new StatusBar(), () -> { }), (a, b) -> { }, metrics);

            ListView<GitGraphRow> list = new ListView<>();
            list.setFixedCellSize(density.rowHeight());
            list.setCellFactory(factory);
            list.getItems().setAll(rows);

            FxHarness.show(new javafx.scene.layout.VBox(list), 900, 300);
            return new Graph(list, factory);
        });
    }

    /** The grid inside the first realised row. */
    private static GridPane firstRow(ListView<GitGraphRow> list) {
        for (Node node : FxHarness.lookupAll(list, "graph-row")) {
            if (node instanceof GridPane grid) {
                return grid;
            }
        }
        throw new AssertionError("no graph row was realised");
    }

    /** Width of column {@code index} in a realised row. */
    private static double columnWidth(ListView<GitGraphRow> list, int index) {
        return firstRow(list).getColumnConstraints().get(index).getPrefWidth();
    }

    @Test
    void aRowDrawsEveryColumnByDefault() {
        Graph g = graph();
        FxHarness.run(() -> {
            assertTrue(columnWidth(g.list(), 6) > 0, "the SHA column should have width");
            assertTrue(columnWidth(g.list(), 5) > 0, "the Age column should have width");
        });
    }

    /**
     * The regression. Hiding a column and refreshing left the data on screen; only
     * replacing the cell factory rebuilds the rows.
     */
    @Test
    void refreshingIsNotEnoughToRemoveAColumnFromTheRows() {
        Graph g = graph();
        GraphColumn.HASH.setVisible(false);

        FxHarness.run(() -> {
            g.list().refresh();
            assertTrue(columnWidth(g.list(), 6) > 0,
                    "refresh() re-runs updateItem on existing cells and cannot pick up a "
                            + "column set the constructor already read - if this ever "
                            + "starts passing, the fix below may no longer be needed");
        });
    }

    @Test
    void replacingTheCellFactoryRemovesTheColumnFromTheRows() {
        Graph g = graph();
        GraphColumn.HASH.setVisible(false);

        FxHarness.run(() -> {
            g.list().setCellFactory(lv -> new GitGraphCell(OperationRunner.of(stubBackend(), new StatusBar(), () -> { }), (a, b) -> { },
                    new GraphMetrics(GraphDensity.COMPACT, GraphNodeStyle.DOT)));
            g.list().applyCss();
            g.list().layout();

            assertEquals(0, columnWidth(g.list(), 6),
                    "the SHA column has to be gone from the row, not just from the header");
        });
    }

    @Test
    void theHiddenColumnsContentIsUnmanagedSoItCannotPaintOverItsNeighbour() {
        GraphColumn.HASH.setVisible(false);
        Graph g = graph();

        FxHarness.run(() -> {
            GridPane row = firstRow(g.list());
            // Column 6 is the hash. A zero-width column does not clip, so the label must
            // be out of the layout as well as out of width.
            for (Node child : row.getChildrenUnmodifiable()) {
                Integer column = GridPane.getColumnIndex(child);
                if (column != null && column == 6) {
                    assertFalse(child.isManaged(), "the hash label should not be laid out");
                    assertFalse(child.isVisible(), "and should not paint");
                }
            }
        });
    }

    @Test
    void hidingTheMessageColumnRemovesItFromTheRows() {
        GraphColumn.MESSAGE.setVisible(false);
        Graph g = graph();

        FxHarness.run(() -> assertEquals(0, columnWidth(g.list(), 3),
                "the message column takes the slack, so hiding it must collapse it"));
    }

    @Test
    void hidingTheRefAndGraphColumnsRemovesThemFromTheRows() {
        GraphColumn.REFS.setVisible(false);
        GraphColumn.GRAPH.setVisible(false);
        Graph g = graph();

        FxHarness.run(() -> {
            assertEquals(0, columnWidth(g.list(), 1),
                    "the rail width is bound to the metrics; hiding must beat the binding");
            assertEquals(0, columnWidth(g.list(), 2),
                    "the lane width is bound to the metrics; hiding must beat the binding");
        });
    }
}
