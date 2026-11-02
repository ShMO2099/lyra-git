package com.jfx.git.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the branch and new-path prompts refuse as the name is typed: the rules the
 * operation behind them would refuse anyway, said before the dialog closes rather than
 * after, in the status bar. {@link FxHarness#start()} for the same reason as
 * {@code RepoToolbarProviderResolutionTest}: loading a control class touches the toolkit.
 */
class PromptValidationTest {

    @TempDir
    Path work;

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @Test
    void aBranchNameGitAcceptsAndNobodyHasIsFine() {
        assertNull(RepoToolbar.branchNameProblem("feature/search", List.of("main")));
        assertNull(RepoToolbar.branchNameProblem("  padded  ", List.of("main")), "trimmed, as it is created");
    }

    /**
     * A name git takes but Windows cannot store as a file is refused before the dialog
     * closes, rather than by git's "cannot lock ref" after it.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aNameWindowsCannotStoreIsRefusedAsItIsTyped() {
        for (String bad : List.of("fix|crash", "feature/aux", "con", "nul.txt", "lpt1")) {
            assertNotNull(RepoToolbar.branchNameProblem(bad, List.of()), bad);
            assertNotNull(SidebarPane.worktreeBranchProblem(bad, true, List.of(), List.of(), List.of(), List.of()),
                    "the add-worktree form: " + bad);
        }
    }

    @Test
    void aTakenNameIsRefusedByName() {
        assertEquals("A branch named main already exists.", RepoToolbar.branchNameProblem("main", List.of("main")));
    }

    @Test
    void aNewPathIsFineUntilSomethingIsThere() throws Exception {
        assertNull(WorkspaceTreeView.newPathProblem(work.toFile(), "docs/notes.txt"), "missing folders are created");
        Files.createDirectories(work.resolve("docs"));
        Files.writeString(work.resolve("docs/notes.txt"), "x");
        assertEquals("docs/notes.txt already exists.", WorkspaceTreeView.newPathProblem(work.toFile(), "docs/notes.txt"));
    }

    @Test
    void aPathOutOfTheRepositoryOrIntoGitsStorageIsRefused() {
        assertNotNull(WorkspaceTreeView.newPathProblem(work.toFile(), "../outside.txt"));
        assertNotNull(WorkspaceTreeView.newPathProblem(work.toFile(), ".git/config"));
    }
}
