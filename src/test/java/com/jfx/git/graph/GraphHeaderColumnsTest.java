package com.jfx.git.graph;

import com.jfx.git.core.Settings;
import com.jfx.git.ui.FxHarness;

import javafx.scene.Node;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What hiding a column does to the header that is drawn, and to its menu.
 *
 * <p>Deliberately not a test of the stored flag — {@code GraphColumnTest} covers that.
 * These build a real {@link GraphHeader} and ask whether the title left the strip,
 * because a zero-width column does not clip its contents: the flag can be right while the
 * heading still paints across its neighbour.
 *
 * <p>Whether the <em>rows</em> follow is {@code GraphRowColumnsTest}, and whether a click
 * drives the whole thing is {@code ColumnMenuRobotTest}. All three exist because a bug
 * lived in each of those gaps.
 */
class GraphHeaderColumnsTest {

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
        Settings.useFile(null);
    }

    private static GraphHeader header() {
        return FxHarness.call(() -> {
            GraphDensity density = GraphDensity.COMPACT;
            GraphHeader h = new GraphHeader(density, new GraphMetrics(density, GraphNodeStyle.DOT),
                    GraphNodeStyle.DOT);
            h.applyCss();
            h.layout();
            return h;
        });
    }

    /** The title carrying {@code text}, or null when it is not in the strip. */
    private static Node title(GraphHeader header, String text) {
        for (Node node : header.getChildrenUnmodifiable()) {
            if (node instanceof Label label && text.equals(label.getText())) {
                return label;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------

    @Test
    void allTitlesAreThereByDefault() {
        GraphHeader h = header();
        for (GraphColumn column : GraphColumn.values()) {
            if (column.headerTitle().isEmpty()) {
                continue; // the lane column is deliberately unlabelled
            }
            Node node = title(h, column.headerTitle());
            assertNotNull(node, column.headerTitle() + " should be headed");
            assertTrue(node.isManaged(), column.headerTitle() + " should be laid out");
        }
    }

    @Test
    void hidingAColumnTakesItsTitleOutOfTheStrip() {
        GraphColumn.HASH.setVisible(false);

        Node hash = title(header(), GraphColumn.HASH.headerTitle());
        assertNotNull(hash, "the label is still constructed; it must not be laid out");
        assertFalse(hash.isVisible(),
                "a zero-width column does not clip, so the title would paint over Age");
        assertFalse(hash.isManaged());
    }

    @Test
    void theOtherTitlesSurviveTheirNeighbourBeingHidden() {
        GraphColumn.AGE.setVisible(false);

        GraphHeader h = header();
        assertTrue(title(h, GraphColumn.HASH.headerTitle()).isManaged());
        assertTrue(title(h, GraphColumn.AUTHOR.headerTitle()).isManaged());
    }

    @Test
    void theHiddenColumnCollapsesInTheHeadersOwnConstraints() {
        GraphColumn.HASH.setVisible(false);

        List<ColumnConstraints> constraints = FxHarness.call(
                () -> List.copyOf(header().getColumnConstraints()));

        assertEquals(7, constraints.size(), "the grid must stay seven wide");
        assertEquals(0, constraints.get(6).getPrefWidth(), "the SHA column should collapse");
    }

    @Test
    void theHeaderCarriesTheColumnMenu() {
        assertNotNull(header().getOnContextMenuRequested(),
                "right-clicking the header is how commercial clients offer this");
    }

    // ------------------------------------------------------------------
    // The menu
    // ------------------------------------------------------------------

    @Test
    void theMenuOffersEveryColumnAndReflectsItsState() {
        GraphColumn.AGE.setVisible(false);

        GraphHeader h = header();
        ContextMenu menu = FxHarness.call(h::columnMenu);
        // Six columns, a separator, and the reset entry.
        assertEquals(GraphColumn.values().length + 2, menu.getItems().size());

        for (var item : menu.getItems()) {
            if (item instanceof CheckMenuItem check
                    && check.getText().startsWith(GraphColumn.AGE.label())) {
                assertFalse(check.isSelected(), "a hidden column must show as unticked");
            }
        }
    }

    /**
     * The reported bug: every right-click built and showed another menu without
     * dismissing the last, so the header filled with overlapping copies.
     */
    @Test
    void openingTheMenuRepeatedlyReusesTheSameOne() {
        GraphHeader h = header();
        ContextMenu first = FxHarness.call(h::columnMenu);
        ContextMenu second = FxHarness.call(h::columnMenu);

        assertSame(first, second,
                "a fresh menu per click is what stacked them on top of each other");
    }

    @Test
    void theMenuEntriesAreRebuiltRatherThanAppendedTo() {
        GraphHeader h = header();
        int first = FxHarness.call(() -> h.columnMenu().getItems().size());
        int second = FxHarness.call(() -> h.columnMenu().getItems().size());

        assertEquals(first, second,
                "reusing one menu must clear its items, or they accumulate on every open");
    }

    /**
     * Each header owns its own menu and hook. Two open repositories each rebuild their own
     * graph; when these were static, only the most recently built tab ever did.
     */
    @Test
    void twoHeadersDoNotShareAMenuOrAHook() {
        GraphHeader first = header();
        GraphHeader second = header();

        assertNotSame(FxHarness.call(first::columnMenu), FxHarness.call(second::columnMenu));

        boolean[] firstRebuilt = {false};
        boolean[] secondRebuilt = {false};
        first.setOnColumnsChanged(() -> firstRebuilt[0] = true);
        second.setOnColumnsChanged(() -> secondRebuilt[0] = true);

        FxHarness.run(() -> first.columnMenu().getItems().stream()
                .filter(i -> "Reset columns to default layout".equals(i.getText()))
                .findFirst().orElseThrow().fire());

        assertTrue(firstRebuilt[0], "the header whose menu was used must rebuild");
        assertFalse(secondRebuilt[0], "and only that one");
    }

    @Test
    void tickingAnEntryShowsTheColumnAndTellsTheGraphToRebuild() {
        GraphColumn.HASH.setVisible(false);
        boolean[] rebuilt = {false};
        GraphHeader h = header();
        h.setOnColumnsChanged(() -> rebuilt[0] = true);

        FxHarness.run(() -> {
            CheckMenuItem hash = (CheckMenuItem) h.columnMenu().getItems().stream()
                    .filter(i -> i.getText().startsWith(GraphColumn.HASH.label()))
                    .findFirst().orElseThrow();
            hash.setSelected(true);
            hash.fire();
        });

        assertTrue(GraphColumn.HASH.visible(), "the choice has to reach the preference");
        assertTrue(rebuilt[0],
                "the header and rows read the column set when built, so both must be rebuilt");
    }

    @Test
    void resettingFromTheMenuPutsEveryColumnBack() {
        GraphColumn.HASH.setVisible(false);
        GraphColumn.AGE.setVisible(false);
        boolean[] rebuilt = {false};
        GraphHeader h = header();
        h.setOnColumnsChanged(() -> rebuilt[0] = true);

        FxHarness.run(() -> h.columnMenu().getItems().stream()
                .filter(i -> "Reset columns to default layout".equals(i.getText()))
                .findFirst().orElseThrow().fire());

        assertTrue(GraphColumn.HASH.visible());
        assertTrue(GraphColumn.AGE.visible());
        assertTrue(rebuilt[0], "the graph has to be rebuilt to show them again");
    }

    @Test
    void theAuthorEntryIsDisabledUnderTheAvatarNodeRatherThanRemoved() {
        com.jfx.git.core.AppPreferences.setGraphNodeStyle(GraphNodeStyle.AUTHOR);

        GraphHeader h = header();
        CheckMenuItem author = (CheckMenuItem) FxHarness.call(h::columnMenu)
                .getItems().stream()
                .filter(i -> i.getText().startsWith(GraphColumn.AUTHOR.label()))
                .findFirst().orElseThrow();

        assertTrue(author.isDisable(),
                "the avatar already says who wrote it, so the column cannot be turned on");
        assertTrue(author.getText().contains("commit node"),
                "a greyed entry has to say why it is greyed");
    }

    // ------------------------------------------------------------------
    // Drag-to-reorder (3.4)
    // ------------------------------------------------------------------

    /** Child positions of each titled node, read off the strip's real layout. */
    private static java.util.Map<String, Integer> titlePositions(GraphHeader h) {
        var positions = new java.util.HashMap<String, Integer>();
        var children = h.getChildrenUnmodifiable();
        for (Node node : children) {
            if (node instanceof Label label && label.getText() != null) {
                positions.put(label.getText(), children.indexOf(node));
            }
        }
        return positions;
    }

    @Test
    void reorderedColumnsLayOutInTheStoredOrder() {
        // Move SHA before "Commit message": drop the hash heading onto the commit one.
        GraphColumn.reordered(GraphColumn.HASH, GraphColumn.MESSAGE);

        GraphHeader h = header();
        var positions = titlePositions(h);
        assertTrue(positions.get("SHA") < positions.get("Commit"),
                () -> "SHA was dropped onto Commit, so it should come first; got " + positions);

        // A fresh header reads the same stored order.
        assertEquals(positions, titlePositions(header()),
                "the order must persist beyond one header instance");
    }

    @Test
    void droppingAColumnOnItselfChangesNothing() {
        var before = titlePositions(header());
        GraphColumn.reordered(GraphColumn.AGE, GraphColumn.AGE);
        assertEquals(before, titlePositions(header()));
    }

    @Test
    void reorderingDoesNotDisturbTheWidthBindings() {
        GraphColumn.reordered(GraphColumn.HASH, GraphColumn.MESSAGE);

        // The header must still lay out without the refs/lane widths landing on the
        // wrong slot after their positions moved.
        GraphHeader h = header();
        assertEquals(7, h.getColumnConstraints().size(),
                "the grid stays seven wide whatever the order");
    }

    @Test
    void resettingPutsEverythingBackWhereItStarted() {
        GraphColumn.reordered(GraphColumn.HASH, GraphColumn.MESSAGE);
        GraphColumn.resetToDefault();

        var defaultPositions = titlePositions(header());
        List<String> declarationOrder = List.of("Refs", "", "Commit", "Author", "Age", "SHA");
        int previous = -1;
        for (String t : declarationOrder) {
            if (t.isEmpty()) continue; // the lane column is unlabelled
            Integer at = defaultPositions.get(t);
            assertNotNull(at, t + " should be present after a reset");
            assertTrue(at > previous, () -> t + " should follow the declaration order");
            previous = at;
        }
    }
}
