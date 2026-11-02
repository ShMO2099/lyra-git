package com.jfx.git.graph;

import com.jfx.git.core.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javafx.scene.layout.ColumnConstraints;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which of the graph's columns are drawn.
 *
 * <p>Points the settings store at a temporary file, so these neither read nor overwrite
 * the columns the developer has chosen in the application.
 */
class GraphColumnTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void useTempFile() {
        Settings.useFile(tempDir.resolve("settings.properties"));
    }

    @AfterEach
    void restoreDefault() {
        Settings.useFile(null);
    }

    // ------------------------------------------------------------------
    // The stored set
    // ------------------------------------------------------------------

    @Test
    void everyOptionalColumnStartsOn() {
        for (GraphColumn column : GraphColumn.values()) {
            assertTrue(column.visible(), column + " should default to shown");
        }
    }

    @Test
    void hidingOneColumnLeavesTheOthersAlone() {
        GraphColumn.AGE.setVisible(false);

        assertFalse(GraphColumn.AGE.visible());
        assertTrue(GraphColumn.HASH.visible());
        assertTrue(GraphColumn.AUTHOR.visible());
    }

    @Test
    void aColumnStaysHiddenAcrossAReload() {
        GraphColumn.HASH.setVisible(false);
        // Settings coalesces writes, and dropping a column header is exactly the kind of
        // rapid change that exists for; the session ending is what flushes it for real.
        Settings.flush();
        Settings.useFile(tempDir.resolve("settings.properties"));

        assertFalse(GraphColumn.HASH.visible(), "the choice has to outlive the session");
    }

    // ------------------------------------------------------------------
    // The node style's effect
    // ------------------------------------------------------------------

    @Test
    void theAvatarNodeRetiresTheAuthorColumnWhateverThePreferenceSays() {
        GraphColumn.AUTHOR.setVisible(true);

        assertFalse(GraphColumn.visibleFor(GraphNodeStyle.AUTHOR).contains(GraphColumn.AUTHOR),
                "the avatar already says who wrote it; a second empty column headed "
                        + "Author would be nothing but a gap");
        assertTrue(GraphColumn.visibleFor(GraphNodeStyle.DOT).contains(GraphColumn.AUTHOR));
    }

    @Test
    void hidingTheAuthorColumnIsHonouredUnderTheDotNode() {
        GraphColumn.AUTHOR.setVisible(false);
        assertFalse(GraphColumn.visibleFor(GraphNodeStyle.DOT).contains(GraphColumn.AUTHOR));
    }

    // ------------------------------------------------------------------
    // What the layout does with them
    // ------------------------------------------------------------------

    @Test
    void theGridStaysSevenColumnsWideWhateverIsHidden() {
        for (GraphDensity density : GraphDensity.values()) {
            assertEquals(7, density.columnConstraints(GraphNodeStyle.DOT, Set.of()).size(),
                    "hiding a column must not move the indices the header and rows share");
            assertEquals(7, density.columnConstraints(GraphNodeStyle.DOT,
                    EnumSet.allOf(GraphColumn.class)).size());
        }
    }

    @Test
    void aHiddenColumnIsZeroWideAndAShownOneIsNot() {
        GraphDensity density = GraphDensity.COMPACT;

        List<ColumnConstraints> hidden = density.columnConstraints(GraphNodeStyle.DOT, Set.of());
        List<ColumnConstraints> shown = density.columnConstraints(
                GraphNodeStyle.DOT, EnumSet.allOf(GraphColumn.class));

        // Columns 4, 5 and 6 are author, age and hash.
        for (int i : new int[] {4, 5, 6}) {
            assertEquals(0, hidden.get(i).getPrefWidth(), "column " + i + " should collapse");
            assertTrue(shown.get(i).getPrefWidth() > 0, "column " + i + " should have width");
        }
    }

    @Test
    void theMessageColumnStillTakesWhatIsLeftOver() {
        List<ColumnConstraints> constraints = GraphDensity.COMPACT.columnConstraints(
                GraphNodeStyle.DOT, Set.of(GraphColumn.MESSAGE));

        // While it is shown, the message column must never ask for width up front: a grid
        // whose preferred width is the longest commit message grows a horizontal
        // scrollbar and pushes the right-hand columns off the window.
        assertEquals(0, constraints.get(3).getPrefWidth());
        assertEquals(javafx.scene.layout.Priority.ALWAYS, constraints.get(3).getHgrow());
    }

    @Test
    void everyColumnCanBeTurnedOff() {
        // A commercial client's header menu lists all six and unticking any one removes it, so
        // nothing here may be privileged as always-on.
        assertEquals(6, GraphColumn.values().length);
        for (GraphColumn column : GraphColumn.values()) {
            column.setVisible(false);
            assertFalse(column.visible(), column + " must be switchable off");
        }
    }

    @Test
    void resettingPutsEveryColumnBack() {
        for (GraphColumn column : GraphColumn.values()) {
            column.setVisible(false);
        }
        GraphColumn.resetToDefault();
        for (GraphColumn column : GraphColumn.values()) {
            assertTrue(column.visible(), column + " should be back");
        }
    }

    @Test
    void hidingTheMessageColumnCollapsesItRatherThanLettingItGrow() {
        // It is the column that takes the slack, so leaving it growing while hidden would
        // hide the text and keep every pixel of the space it was using.
        List<ColumnConstraints> constraints = GraphDensity.COMPACT.columnConstraints(
                GraphNodeStyle.DOT, EnumSet.complementOf(EnumSet.of(GraphColumn.MESSAGE)));

        assertEquals(0, constraints.get(3).getPrefWidth());
        assertEquals(0, constraints.get(3).getMaxWidth());
        assertNotEquals(javafx.scene.layout.Priority.ALWAYS, constraints.get(3).getHgrow());
    }

    @Test
    void theRefAndLaneColumnsCollapseWhenHidden() {
        List<ColumnConstraints> constraints = GraphDensity.COMPACT.columnConstraints(
                GraphNodeStyle.DOT,
                EnumSet.complementOf(EnumSet.of(GraphColumn.REFS, GraphColumn.GRAPH)));

        assertEquals(0, constraints.get(1).getPrefWidth(), "the ref rail should collapse");
        assertEquals(0, constraints.get(2).getPrefWidth(), "the lane column should collapse");
    }

    @Test
    void eachColumnCarriesBothItsNames() {
        // The commercial clients' wording in the menu; a short form above the column, where
        // "Branch / Tag" does not fit a sixty-four pixel rail.
        assertEquals("Branch / Tag", GraphColumn.REFS.label());
        assertEquals("Refs", GraphColumn.REFS.headerTitle());
        assertEquals("Date / Time", GraphColumn.AGE.label());
        assertEquals("Age", GraphColumn.AGE.headerTitle());
        assertTrue(GraphColumn.GRAPH.headerTitle().isEmpty(),
                "the lane column is too narrow for any word, so it is left unlabelled");
    }

    @Test
    void reorderedMovesTheDraggedColumnToTheTargetPositionAndPersists() {
        var moved = GraphColumn.reordered(GraphColumn.HASH, GraphColumn.MESSAGE);

        assertEquals(GraphColumn.HASH, moved.get(2), "HASH takes MESSAGE's slot");
        assertFalse(moved.contains(GraphColumn.MESSAGE) && moved.indexOf(GraphColumn.MESSAGE) < 3,
                "MESSAGE shifts right to make room");

        // The new order is what a later reader sees, not just this list.
        assertEquals(moved, GraphColumn.ordered());
    }

    @Test
    void reorderedIsANoOpWhenDroppedInPlace() {
        var before = GraphColumn.ordered();
        assertEquals(before, GraphColumn.reordered(GraphColumn.AGE, GraphColumn.AGE));
    }

    @Test
    void aStoredOrderMissingAColumnAppendsIt() throws Exception {
        // Simulate a settings file written by an older build that pre-dates a column.
        var path = tempDir.resolve("settings.properties");
        Files.writeString(path,
                "graph.columnOrder=" + GraphColumn.HASH.name() + "," + GraphColumn.REFS.name());
        Settings.useFile(path);

        var order = GraphColumn.ordered();
        assertEquals(List.of(GraphColumn.HASH, GraphColumn.REFS), order.subList(0, 2));
        assertEquals(GraphColumn.values().length, order.size(),
                "columns absent from the stored list keep their place at the end");
    }
}
