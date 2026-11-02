package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How many changed files a folder is holding.
 *
 * <p>The number beside a folder in the repository tree, which is what makes the branch
 * worth opening visible without opening it. The arithmetic is a prefix match, and a prefix
 * match on paths has one classic way to be wrong: {@code src} quietly counting
 * {@code srcgen/}. That is the case this exists for.
 */
class WorkspaceTreeTallyTest {

    @TempDir
    Path workTree;

    private WorkspaceTreeView treeWith(Set<String> changed) {
        GitBackend backend = (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getWorkTree".equals(method.getName())) {
                        return workTree.toFile();
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    if (type == File.class) return workTree.toFile();
                    return null;
                });
        return FxHarness.call(() -> {
            WorkspaceTreeView tree = new WorkspaceTreeView(
                    OperationRunner.of(backend, new StatusBar(), () -> { }), path -> { });
            tree.setChangedPaths(changed);
            return tree;
        });
    }

    @Test
    void aFolderCountsEveryChangedFileBeneathIt() {
        WorkspaceTreeView tree = treeWith(Set.of(
                "src/main/java/App.java",
                "src/main/java/util/Text.java",
                "src/test/java/AppTest.java",
                "README.md"));

        assertEquals(3, tree.changedFilesUnder("src"), "everything under src, at any depth");
        assertEquals(2, tree.changedFilesUnder("src/main"));
        assertEquals(1, tree.changedFilesUnder("src/main/java/util"));
    }

    @Test
    void aSiblingWithASharedPrefixIsNotCounted() {
        WorkspaceTreeView tree = treeWith(Set.of(
                "src/App.java",
                "srcgen/Generated.java",
                "src-tools/build.sh"));

        assertEquals(1, tree.changedFilesUnder("src"),
                "srcgen and src-tools are siblings of src, not folders inside it");
    }

    @Test
    void aFolderWithNothingChangedInsideCountsZero() {
        WorkspaceTreeView tree = treeWith(Set.of("docs/guide.md"));

        assertEquals(0, tree.changedFilesUnder("src"),
                "and a zero is what stops the row from drawing a badge at all");
    }

    /** The repository root holds everything, so it is the whole set rather than a prefix. */
    @Test
    void theRootCountsEverything() {
        WorkspaceTreeView tree = treeWith(Set.of("a.txt", "src/b.txt"));

        assertEquals(2, tree.changedFilesUnder(""));
    }

    @Test
    void aTrailingSlashMakesNoDifference() {
        WorkspaceTreeView tree = treeWith(Set.of("src/a.txt", "src/b.txt"));

        assertEquals(tree.changedFilesUnder("src"), tree.changedFilesUnder("src/"),
                "the caller should not have to know which form the tree stores");
    }
}
