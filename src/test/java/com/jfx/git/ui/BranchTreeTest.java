package com.jfx.git.ui;

import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the nesting of slash-separated branch names into folders. */
class BranchTreeTest {

    private static List<TreeItem<BranchTree.Node>> childrenOf(TreeItem<BranchTree.Node> item) {
        return item.getChildren();
    }

    @Test
    void flatNamesStayFlat() {
        TreeItem<BranchTree.Node> root = BranchTree.build(List.of("main", "develop"), true);

        assertEquals(2, childrenOf(root).size());
        for (TreeItem<BranchTree.Node> child : childrenOf(root)) {
            assertFalse(child.getValue().isFolder());
            assertEquals(child.getValue().label(), child.getValue().fullName());
        }
    }

    @Test
    void slashSeparatedNamesNestUnderOneFolder() {
        TreeItem<BranchTree.Node> root = BranchTree.build(
                List.of("agent/rust-chat-template", "agent/symm-mem-nvshmem"), true);

        assertEquals(1, childrenOf(root).size());

        TreeItem<BranchTree.Node> folder = childrenOf(root).get(0);
        assertEquals("agent", folder.getValue().label());
        assertTrue(folder.getValue().isFolder());
        assertNull(folder.getValue().fullName(), "a folder is not itself a branch");

        assertEquals(2, childrenOf(folder).size());
        assertEquals("rust-chat-template", childrenOf(folder).get(0).getValue().label());
        assertEquals("agent/rust-chat-template", childrenOf(folder).get(0).getValue().fullName());
    }

    @Test
    void nestsDeeply() {
        TreeItem<BranchTree.Node> root = BranchTree.build(
                List.of("origin/bugfix/37931-nvfp4-batched-all2all"), true);

        TreeItem<BranchTree.Node> origin = childrenOf(root).get(0);
        assertEquals("origin", origin.getValue().label());

        TreeItem<BranchTree.Node> bugfix = childrenOf(origin).get(0);
        assertEquals("bugfix", bugfix.getValue().label());

        TreeItem<BranchTree.Node> leaf = childrenOf(bugfix).get(0);
        assertEquals("37931-nvfp4-batched-all2all", leaf.getValue().label());
        assertEquals("origin/bugfix/37931-nvfp4-batched-all2all", leaf.getValue().fullName());
        assertFalse(leaf.getValue().isFolder());
    }

    @Test
    void foldersSortBeforeBranches() {
        TreeItem<BranchTree.Node> root = BranchTree.build(
                List.of("zebra", "acc-rate", "agent/one"), true);

        List<TreeItem<BranchTree.Node>> children = childrenOf(root);
        assertEquals("agent", children.get(0).getValue().label());
        assertTrue(children.get(0).getValue().isFolder());
        assertEquals("acc-rate", children.get(1).getValue().label());
        assertEquals("zebra", children.get(2).getValue().label());
    }

    @Test
    void expansionFlagIsApplied() {
        assertTrue(childrenOf(BranchTree.build(List.of("a/b"), true)).get(0).isExpanded());
        assertFalse(childrenOf(BranchTree.build(List.of("a/b"), false)).get(0).isExpanded());
    }

    @Test
    void emptyInputYieldsAnEmptyRoot() {
        assertTrue(childrenOf(BranchTree.build(List.of(), true)).isEmpty());
    }

    /**
     * git allows a branch and a folder to share a prefix only in separate refs, but the
     * tree must not lose a branch that is also a path prefix of another name.
     */
    @Test
    void siblingsUnderTheSamePrefixAreGrouped() {
        TreeItem<BranchTree.Node> root = BranchTree.build(
                List.of("feature/a", "feature/b", "feature/c"), true);

        assertEquals(1, childrenOf(root).size());
        assertEquals(3, childrenOf(childrenOf(root).get(0)).size());
    }

    /**
     * A remote that has not been fetched has no branches to build a folder from, and is
     * the one a user needs a row for - to fetch it.
     */
    @Test
    void aNamedFolderIsShownEvenWithNothingInIt() {
        TreeItem<BranchTree.Node> root = BranchTree.build(
                List.of("origin/main"), false, List.of("origin", "upstream"));

        assertEquals(List.of("origin", "upstream"),
                childrenOf(root).stream().map(item -> item.getValue().label()).toList());
        TreeItem<BranchTree.Node> empty = childrenOf(root).get(1);
        assertTrue(empty.getValue().isFolder(), "an empty remote is still a folder, not a branch");
        assertNull(empty.getValue().fullName());
        assertTrue(childrenOf(empty).isEmpty());
        assertEquals(1, childrenOf(childrenOf(root).get(0)).size(),
                "naming a folder that has branches adds nothing to it");
    }

    @Test
    void anEmptyFolderSortsWithTheFoldersNotWithTheBranches() {
        TreeItem<BranchTree.Node> root = BranchTree.build(
                List.of("alpha", "zeta/one"), false, List.of("middle"));

        assertEquals(List.of("middle", "zeta", "alpha"),
                childrenOf(root).stream().map(item -> item.getValue().label()).toList(),
                "folders first, then branches, as everywhere else in the tree");
    }

    @Test
    void aNodesPathIsItsLabelsFromTheRootDown() {
        TreeItem<BranchTree.Node> root = BranchTree.build(List.of("origin/feature/login"), true);
        TreeItem<BranchTree.Node> origin = childrenOf(root).get(0);
        TreeItem<BranchTree.Node> feature = childrenOf(origin).get(0);
        TreeItem<BranchTree.Node> leaf = childrenOf(feature).get(0);

        assertEquals("origin", BranchTree.pathOf(origin));
        assertEquals("origin/feature", BranchTree.pathOf(feature));
        assertEquals("origin/feature/login", BranchTree.pathOf(leaf));
        assertEquals("", BranchTree.pathOf(root), "the hidden root has no path of its own");
    }
}
