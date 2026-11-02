package com.jfx.git.ui;

import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One filter for the whole sidebar, as commercial clients have: it narrows every section at once,
 * opens the sections and folders its matches are in, says how much it matched, and puts
 * everything back as it was when cleared.
 */
@Tag("heavy")
class SidebarFilterTest {

    @TempDir
    Path tempDir;

    private MainWindowFixture open() throws Exception {
        MainWindowFixture window = MainWindowFixture.create(tempDir);
        window.open(1180, 690, ThemeManager.Theme.DARK, true);
        RobotWait.drainGitWork();
        return window;
    }

    /** The branch names a tree shows, folders open or not. */
    private static List<String> branches(TreeView<BranchTree.Node> tree) {
        List<String> out = new ArrayList<>();
        collect(tree.getRoot(), out);
        return out;
    }

    private static void collect(TreeItem<BranchTree.Node> item, List<String> out) {
        if (item == null) return;
        for (TreeItem<BranchTree.Node> child : item.getChildren()) {
            if (child.getValue().fullName() != null) out.add(child.getValue().fullName());
            collect(child, out);
        }
    }

    private static boolean allFoldersOpen(TreeItem<BranchTree.Node> item) {
        for (TreeItem<BranchTree.Node> child : item.getChildren()) {
            if (!child.getChildren().isEmpty() && (!child.isExpanded() || !allFoldersOpen(child))) return false;
        }
        return true;
    }

    private static TreeItem<BranchTree.Node> folder(TreeView<BranchTree.Node> tree, String label) {
        return tree.getRoot().getChildren().stream()
                .filter(i -> label.equals(i.getValue().label())).findFirst().orElseThrow();
    }

    /**
     * Types {@code text} into the sidebar's filter and waits for it to be applied. The
     * filter waits for typing to pause before it redraws, so the result is not there the
     * moment the text is.
     */
    private static void type(SidebarPane sidebar, String text) {
        FxHarness.run(() -> sidebar.filterField().setText(text));
        RobotWait.untilOnFx("the sidebar never applied the filter " + text, () -> !sidebar.filterPending());
    }

    private static Label badge(RepoTab tab, String section) {
        return (Label) SidebarRevealTest.section(tab, section).getGraphic().lookup(".count-badge");
    }

    @Test
    void oneFilterNarrowsEverySectionAndSaysHowMuch() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            SidebarPane sidebar = tab.getSidebar();
            String allLocal = FxHarness.call(() -> badge(tab, "LOCAL").getText());
            type(sidebar, "pay");
            FxHarness.run(() -> {
                TreeView<BranchTree.Node> local = SidebarRevealTest.tree(tab, 0);
                assertEquals(List.of("feature/payments/invoices", "feature/payments/refunds"),
                        branches(local).stream().sorted().toList());
                assertTrue(allFoldersOpen(local.getRoot()), "every folder on the way to a match is open");
                assertEquals("2", badge(tab, "LOCAL").getText());
                assertTrue(badge(tab, "LOCAL").getStyleClass().contains("count-filtered"));
                assertFalse(SidebarRevealTest.section(tab, "REMOTE").isExpanded(), "no match there: closed");
                assertTrue(FxHarness.lookupAll(tab.getContent(), "sidebar-filter-status").stream()
                        .anyMatch(n -> ((Label) n).getText().startsWith("Filtering 2 / ")));
            });
            FxHarness.run(() -> sidebar.filterField().clear());
            FxHarness.run(() -> {
                assertEquals(allLocal, badge(tab, "LOCAL").getText(), "the count is the whole again");
                assertFalse(badge(tab, "LOCAL").getStyleClass().contains("count-filtered"));
                assertTrue(SidebarRevealTest.section(tab, "LOCAL").isExpanded());
                assertFalse(SidebarRevealTest.section(tab, "REMOTE").isExpanded(), "as the user had it");
            });
        }
    }

    @Test
    void aMatchInsideARemoteFolderIsShownWithoutOpeningAnything() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            type(tab.getSidebar(), "mai");
            FxHarness.run(() -> {
                assertTrue(SidebarRevealTest.section(tab, "REMOTE").isExpanded(), "a section with a match opens");
                TreeView<BranchTree.Node> remote = SidebarRevealTest.tree(tab, 1);
                assertEquals(List.of("origin/main"), branches(remote));
                assertTrue(allFoldersOpen(remote.getRoot()), "origin is open, so its match can be seen");
            });
            // Typing on does not close anything again.
            type(tab.getSidebar(), "main");
            FxHarness.run(() -> assertTrue(allFoldersOpen(SidebarRevealTest.tree(tab, 1).getRoot())));
        }
    }

    @Test
    void foldersStayAsTheUserLeftThemThroughRefreshesAndFilters() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            FxHarness.run(() -> folder(SidebarRevealTest.tree(tab, 0), "feature").setExpanded(false));
            FxHarness.run(tab::refresh);
            RobotWait.drainGitWork();
            FxHarness.run(() -> assertFalse(folder(SidebarRevealTest.tree(tab, 0), "feature").isExpanded(),
                    "a refresh no longer reopens a folder the user closed"));
            type(tab.getSidebar(), "cart");
            FxHarness.run(() -> assertTrue(folder(SidebarRevealTest.tree(tab, 0), "feature").isExpanded(),
                    "the filter opens it to show its matches"));
            FxHarness.run(() -> tab.getSidebar().filterField().clear());
            FxHarness.run(() -> assertFalse(folder(SidebarRevealTest.tree(tab, 0), "feature").isExpanded(),
                    "and clearing it puts the user's choice back"));
        }
    }

    @Test
    void theTypedTextIsMarkedInEachMatchingName() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            type(tab.getSidebar(), "refund");
            window.layOut();
            FxHarness.run(() -> {
                Node cell = SidebarRevealTest.treeCell(SidebarRevealTest.tree(tab, 0), "feature/payments/refunds");
                List<Node> hits = FxHarness.lookupAll(cell, "filter-match");
                assertEquals(1, hits.size());
                assertEquals("refund", ((Label) hits.get(0)).getText());
            });
        }
    }

    @Test
    void aRefreshWhileFilteringLeavesTheSectionsAsTheUserPutThem() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            type(tab.getSidebar(), "main");
            FxHarness.run(() -> {
                assertTrue(SidebarRevealTest.section(tab, "LOCAL").isExpanded());
                assertTrue(SidebarRevealTest.section(tab, "REMOTE").isExpanded());
                SidebarRevealTest.section(tab, "LOCAL").setExpanded(false);
            });
            FxHarness.run(tab::refresh);
            RobotWait.drainGitWork();
            FxHarness.run(() -> assertFalse(SidebarRevealTest.section(tab, "LOCAL").isExpanded(),
                    "a refresh under the filter does not pop a section the user closed back open"));
            FxHarness.run(() -> tab.getSidebar().filterField().clear());
            FxHarness.run(() -> assertTrue(SidebarRevealTest.section(tab, "LOCAL").isExpanded(),
                    "clearing the filter puts back how it was before filtering"));
        }
    }

    @Test
    void theShortcutShowsAHiddenSidebarBeforeFocusingItsFilter() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            FxHarness.run(tab::toggleSidebar);
            FxHarness.run(() -> assertFalse(tab.getSidebar().isManaged(), "precondition: hidden"));
            FxHarness.run(tab::focusSidebarFilter);
            window.layOut();
            FxHarness.run(() -> {
                assertTrue(tab.getSidebar().isManaged(), "shown again, so the filter can be typed in");
                assertTrue(tab.getSidebar().filterField().isFocused()
                        || window.scene().getFocusOwner() == tab.getSidebar().filterField());
            });
        }
    }
}
