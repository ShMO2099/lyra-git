package com.jfx.git.ui;

import com.jfx.git.testsupport.MainWindowFixture;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidebar over a repository the size of a real one: hundreds of remote branches and
 * hundreds of tags.
 *
 * <p>Reported against a repository with 457 remote branches and 203 tags:
 * <ul>
 *   <li>opening the {@code origin} folder showed nothing - the tree stayed one row tall and
 *       scrolled its 457 branches inside that row;</li>
 *   <li>closing it again left a blank gap a screen high where the branches had been;</li>
 *   <li>the tag list scrolled inside the sidebar, which was itself scrolling, two
 *       scrollbars side by side.</li>
 * </ul>
 * The first two are one fault: the tree's height was taken from a property JavaFX only
 * brings up to date during layout, so every change arrived one step late.
 */
@Tag("heavy")
class SidebarLargeRepositoryTest {

    private static final int REMOTES = 120;
    private static final int TAGS = 200;

    @TempDir
    Path tempDir;

    private MainWindowFixture open() throws Exception {
        MainWindowFixture window = MainWindowFixture.create(tempDir).withManyRefs(REMOTES, TAGS);
        window.open(1480, 900, ThemeManager.Theme.DARK, false);
        return window;
    }

    private static TitledPane section(MainWindowFixture window, String title) {
        for (Node node : window.scene().getRoot().lookupAll(".titled-pane")) {
            TitledPane pane = (TitledPane) node;
            if (pane.getGraphic() != null && pane.getGraphic().lookupAll(".label").stream()
                    .anyMatch(l -> title.equals(((javafx.scene.control.Label) l).getText()))) {
                return pane;
            }
        }
        throw new AssertionError("no sidebar section titled " + title);
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> remoteTree(MainWindowFixture window) {
        TitledPane remote = section(window, "REMOTE");
        Node tree = remote.getContent().lookup(".branch-tree");
        assertNotNull(tree, "REMOTE should hold the remote branch tree");
        return (TreeView<Object>) tree;
    }

    /** The folder called {@code name} anywhere under the tree's root. */
    private static TreeItem<Object> folder(TreeItem<Object> from, String name) {
        for (TreeItem<Object> child : from.getChildren()) {
            if (!child.isLeaf() && String.valueOf(child.getValue()).contains(name)) return child;
            TreeItem<Object> deeper = folder(child, name);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private static int visibleRows(TreeItem<?> item) {
        int rows = 0;
        for (TreeItem<?> child : item.getChildren()) {
            rows++;
            if (child.isExpanded()) rows += visibleRows(child);
        }
        return rows;
    }

    private static void expandAll(TreeItem<?> item, boolean expanded) {
        for (TreeItem<?> child : item.getChildren()) {
            if (!child.isLeaf()) {
                child.setExpanded(expanded);
                expandAll(child, expanded);
            }
        }
    }

    private static double viewportHeight(MainWindowFixture window) {
        ScrollPane scroll = (ScrollPane) window.scene().lookup(".sidebar-scroll");
        return scroll.getViewportBounds().getHeight();
    }

    @Test
    void openingAFolderMakesRoomForItAtOnceAndClosingItGivesTheRoomBack() throws Exception {
        try (MainWindowFixture window = open()) {
            FxHarness.run(() -> section(window, "REMOTE").setExpanded(true));
            window.layOut();
            TreeView<Object> tree = FxHarness.call(() -> remoteTree(window));

            double closed = FxHarness.call(() -> {
                expandAll(tree.getRoot(), false);
                return tree.getPrefHeight();
            });
            window.layOut();
            int closedRows = FxHarness.call(() -> visibleRows(tree.getRoot()));
            assertTrue(closed <= closedRows * UiScale.ROW_HEIGHT + 10,
                    "with every folder closed the tree is as tall as its " + closedRows
                            + " rows, was " + closed);

            // One step, one result: the height has to follow the folder in the same pulse.
            double opened = FxHarness.call(() -> {
                expandAll(tree.getRoot(), true);
                return tree.getPrefHeight();
            });
            int openRows = FxHarness.call(() -> visibleRows(tree.getRoot()));
            assertTrue(openRows >= REMOTES, "sanity: the folders hold the branches, rows " + openRows);
            assertTrue(opened > closed + 5 * UiScale.ROW_HEIGHT,
                    "opening the folders must make the tree taller straight away: "
                            + closed + " -> " + opened);

            double reclosed = FxHarness.call(() -> {
                expandAll(tree.getRoot(), false);
                return tree.getPrefHeight();
            });
            assertEquals(closed, reclosed, 1.0,
                    "closing the folders must give the height back, not leave a blank gap");
        }
    }

    /**
     * The sidebar never scrolls as a whole: long sections share its height, one scrollbar
     * each, instead of scrolling inside a page that is scrolling as well.
     */
    @Test
    void longSectionsShareTheSidebarsHeightAndTheSidebarItselfDoesNotScroll() throws Exception {
        try (MainWindowFixture window = open()) {
            FxHarness.run(() -> {
                section(window, "REMOTE").setExpanded(true);
                section(window, "TAGS").setExpanded(true);
            });
            window.layOut();
            FxHarness.run(() -> expandAll(remoteTree(window).getRoot(), true));
            // Twice, with the FX thread free in between: the second sharing-out uses the
            // heading heights the first layout measured.
            window.layOut();
            window.layOut();

            FxHarness.run(() -> {
                double viewport = viewportHeight(window);
                assertTrue(viewport > 300, "sanity: the sidebar has a viewport, " + viewport);

                ScrollPane scroll = (ScrollPane) window.scene().lookup(".sidebar-scroll");
                double content = scroll.getContent().getBoundsInLocal().getHeight();
                assertTrue(content <= viewport + 1,
                        "the sections must fit the sidebar between them, not make it scroll: "
                                + content + " in " + viewport);

                TreeView<Object> tree = remoteTree(window);
                ListView<?> tags = (ListView<?>) section(window, "TAGS").getContent().lookup(".list-view");
                assertNotNull(tags);
                assertTrue(tags.getItems().size() >= TAGS, "sanity: the tags are listed");
                for (Node list : List.of(tree, tags)) {
                    double height = ((javafx.scene.layout.Region) list).getHeight();
                    assertTrue(height >= 4 * UiScale.ROW_HEIGHT,
                            "each long list keeps a useful share of the height: " + height);
                }
                double difference = Math.abs(tree.getHeight() - tags.getHeight());
                assertTrue(difference <= UiScale.ROW_HEIGHT,
                        "two lists that are both too long split what is left evenly: "
                                + tree.getHeight() + " and " + tags.getHeight());
            });
        }
    }

    /**
     * The height is all used. A section that is squeezed while there is empty sidebar
     * below it means something that is not on screen was given room - the headings of
     * SUBMODULES and WORKTREES, which are hidden in a repository that has neither.
     */
    @Test
    void noHeightIsHeldBackForSectionsThatAreNotShown() throws Exception {
        try (MainWindowFixture window = open()) {
            FxHarness.run(() -> section(window, "TAGS").setExpanded(true));
            window.layOut();
            window.layOut();
            FxHarness.run(() -> {
                ScrollPane scroll = (ScrollPane) window.scene().lookup(".sidebar-scroll");
                double content = scroll.getContent().getBoundsInLocal().getHeight();
                double viewport = viewportHeight(window);
                assertTrue(content >= viewport - 2,
                        "a list too long for the sidebar should use all of it: " + content + " of " + viewport);
                assertTrue(content <= viewport + 1, "and no more: " + content + " of " + viewport);
                // Sixteen local rows and two hundred tags are both more than half the
                // sidebar, so they split it; neither is squeezed to leave a gap.
                TreeView<?> local = (TreeView<?>) section(window, "LOCAL").getContent().lookup(".branch-tree");
                ListView<?> tags = (ListView<?>) section(window, "TAGS").getContent().lookup(".list-view");
                assertTrue(Math.abs(local.getHeight() - tags.getHeight()) <= UiScale.ROW_HEIGHT,
                        "the two long sections share the height evenly: " + local.getHeight()
                                + " and " + tags.getHeight());
            });
        }
    }

    /** With room for everything, nothing scrolls and nothing is padded out. */
    @Test
    void aSidebarWithRoomShowsEveryRowAndNoScrollbar() throws Exception {
        try (MainWindowFixture window = open()) {
            window.layOut();
            window.layOut();
            FxHarness.run(() -> {
                ScrollPane scroll = (ScrollPane) window.scene().lookup(".sidebar-scroll");
                double content = scroll.getContent().getBoundsInLocal().getHeight();
                assertTrue(content <= viewportHeight(window) + 1,
                        "LOCAL alone fits; the sidebar should not scroll: " + content);
                Node local = section(window, "LOCAL").getContent().lookup(".branch-tree");
                TreeView<?> tree = (TreeView<?>) local;
                assertEquals(visibleRows(tree.getRoot()) * UiScale.ROW_HEIGHT + 8.0, tree.getHeight(), 1.0,
                        "a section that fits is exactly as tall as its rows");
            });
        }
    }

    /** A short section is still exactly as tall as its rows: no slot, no gap. */
    @Test
    void shortSectionsAreStillSizedToTheirRows() throws Exception {
        try (MainWindowFixture window = open()) {
            FxHarness.run(() -> section(window, "STASHES").setExpanded(true));
            window.layOut();
            FxHarness.run(() -> {
                ListView<?> stashes = (ListView<?>) section(window, "STASHES").getContent().lookup(".list-view");
                assertEquals(1, stashes.getItems().size());
                assertTrue(stashes.getHeight() <= UiScale.ROW_HEIGHT * 2 + 10,
                        "one stash should not reserve more than a row or two, was " + stashes.getHeight());
                List<String> gaps = new ArrayList<>();
                for (String title : List.of("LOCAL", "REMOTE", "TAGS", "STASHES")) {
                    TitledPane pane = section(window, title);
                    Bounds bounds = pane.getBoundsInParent();
                    if (!pane.isExpanded() && bounds.getHeight() > 60) {
                        gaps.add(title + " is closed but " + bounds.getHeight() + "px tall");
                    }
                }
                assertTrue(gaps.isEmpty(), "closed sections must not hold space: " + gaps);
            });
        }
    }
}
