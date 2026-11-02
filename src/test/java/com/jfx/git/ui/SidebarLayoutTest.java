package com.jfx.git.ui;

import com.jfx.git.testsupport.LayoutAssert;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TreeView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidebar's branch list as it is laid out.
 *
 * <p>The fixture's eleven branches sit in four folders, so the tree shows fifteen rows.
 * The section used to be sized for the branch count, capped at ten, and so scrolled both
 * ways inside a box while most of the sidebar stood empty; and each row put its eye
 * button between the branch mark and the name, in a button 88 pixels wide, which pushed
 * the names out of view.
 */
class SidebarLayoutTest {

    @TempDir
    Path tempDir;

    @Test
    void theBranchTreeIsAsTallAsItsRowsAndDoesNotScrollInsideItself() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                TreeView<?> tree = localTree(window);
                assertTrue(tree.getExpandedItemCount() >= 14,
                        "sanity: the fixture shows branches and their folders, saw "
                                + tree.getExpandedItemCount() + " rows");
                List<String> showing = new ArrayList<>();
                for (Node node : tree.lookupAll(".scroll-bar")) {
                    ScrollBar bar = (ScrollBar) node;
                    if (bar.isVisible() && bar.getVisibleAmount() < bar.getMax() - bar.getMin()) {
                        showing.add(bar.getOrientation().toString());
                    }
                }
                assertTrue(showing.isEmpty(), "the branch tree is scrolling inside itself: " + showing);
                assertTrue(tree.getHeight() >= tree.getExpandedItemCount() * UiScale.ROW_HEIGHT,
                        "every row should be on screen without scrolling the tree: height "
                                + tree.getHeight() + " for " + tree.getExpandedItemCount() + " rows");
            });
        }
    }

    @Test
    void aBranchRowReadsMarkThenNameThenCountsThenEye() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                Parent row = rowNamed(window, "login-redesign");
                assertNotNull(row, "the fixture's long branch should have a row");

                Label name = (Label) row.lookup(".branch-name");
                Node eye = row.lookup(".branch-eye-toggle");
                assertNotNull(eye, "a branch you are not on can be hidden from the graph");
                LayoutAssert.assertNotTruncated(name);
                assertTrue(sceneX(name) < sceneX(eye),
                        "the eye comes after the name, not before it");
                assertTrue(eye.getLayoutBounds().getWidth() <= 24,
                        "the eye is an icon, not a slab: " + eye.getLayoutBounds().getWidth());
                assertEquals(0.0, eye.getOpacity(),
                        "and it stays out of the way until the row is pointed at");
            });
        }
    }

    /** The checked-out branch says how far it is from its upstream, after its name. */
    @Test
    void theCurrentBranchShowsItsCountsAtTheTrailingEdge() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1100, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                Parent row = rowNamed(window, "main");
                assertNotNull(row);
                Label name = (Label) row.lookup(".branch-name");
                Label ahead = (Label) row.lookup(".track-ahead");
                Label behind = (Label) row.lookup(".track-behind");
                assertNotNull(ahead, "main is one commit ahead of origin");
                assertNotNull(behind, "and two behind");
                assertEquals("↑1", ahead.getText());
                assertEquals("↓2", behind.getText());
                assertTrue(sceneX(name) < sceneX(ahead) && sceneX(ahead) < sceneX(behind));
                assertFalse(row.lookupAll(".branch-eye-toggle").iterator().hasNext(),
                        "the branch you are on cannot be hidden from its own graph");
            });
        }
    }

    private static TreeView<?> localTree(MainWindowFixture window) {
        Node tree = window.scene().lookup(".branch-tree");
        assertNotNull(tree, "the sidebar should show the LOCAL branch tree");
        return (TreeView<?>) tree;
    }

    /** The row whose branch name label reads {@code label}. */
    private static Parent rowNamed(MainWindowFixture window, String label) {
        for (Node node : localTree(window).lookupAll(".branch-row")) {
            Node name = node.lookup(".branch-name");
            if (node.isVisible() && name instanceof Label l && label.equals(l.getText())) {
                return (Parent) node;
            }
        }
        return null;
    }

    private static double sceneX(Node node) {
        return node.localToScene(node.getBoundsInLocal()).getMinX();
    }
}
