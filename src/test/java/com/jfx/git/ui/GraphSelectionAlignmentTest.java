package com.jfx.git.ui;

import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.canvas.Canvas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selecting a row does not move its graph. The selection bar was a border on the row,
 * and a border takes room: the selected row's columns shifted two pixels right and every
 * lane line broke at it.
 */
class GraphSelectionAlignmentTest {

    @TempDir
    Path tempDir;

    @Test
    void theSelectedRowsGraphLinesUpWithTheRowsAroundIt() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            FxHarness.run(() -> tab.getCommitList().getSelectionModel().clearAndSelect(2));
            window.layOut();
            FxHarness.run(() -> {
                List<Double> lefts = tab.getCommitList().lookupAll(".list-cell").stream()
                        .filter(Node::isVisible)
                        .flatMap(cell -> cell.lookupAll(".graph-row").stream())
                        .map(row -> row.lookupAll("*").stream().filter(n -> n instanceof Canvas)
                                .findFirst().orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .map(canvas -> canvas.localToScene(canvas.getLayoutBounds()).getMinX())
                        .toList();
                assertTrue(lefts.size() > 3, "rows to compare: " + lefts.size());
                double first = lefts.get(0);
                for (double left : lefts) {
                    assertEquals(first, left, 0.01, "a row's graph starts where the others' do: " + lefts);
                }
                Node bar = tab.getCommitList().lookupAll(".list-cell:selected").stream()
                        .flatMap(cell -> cell.lookupAll(".graph-selection-bar").stream())
                        .findFirst().orElse(null);
                assertTrue(bar != null && bar.isVisible(), "the selected row still has its bar");
            });
        }
    }
}
