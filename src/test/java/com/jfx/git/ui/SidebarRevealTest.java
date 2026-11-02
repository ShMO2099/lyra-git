package com.jfx.git.ui;

import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One click on a branch, tag or stash in the sidebar selects it in the graph, as in
 * commercial clients: the branch's tip, the tag's commit, the stash's own row - paging older
 * history in when it is not loaded yet.
 */
@Tag("heavy")
class SidebarRevealTest {

    @TempDir
    Path tempDir;

    static void click(Node cell) {
        Event.fireEvent(cell, new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, false, null));
    }

    static TitledPane section(RepoTab tab, String title) {
        return FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .map(n -> (TitledPane) n)
                .filter(p -> p.getGraphic() != null && FxHarness.lookupAll(p.getGraphic(), "label").stream()
                        .anyMatch(l -> title.equals(((javafx.scene.control.Label) l).getText())))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    static TreeView<BranchTree.Node> tree(RepoTab tab, int index) {
        return (TreeView<BranchTree.Node>) FxHarness.lookupAll(tab.getContent(), "branch-tree").get(index);
    }

    static Node treeCell(TreeView<BranchTree.Node> tree, String fullName) {
        return tree.lookupAll(".tree-cell").stream()
                .filter(n -> n instanceof TreeCell<?> c && c.getItem() instanceof BranchTree.Node node
                        && fullName.equals(node.fullName()))
                .findFirst().orElseThrow(() -> new AssertionError("no row for " + fullName));
    }

    private static String hashOf(MainWindowFixture window, String ref) throws Exception {
        try (Git git = Git.open(window.workTree().toFile())) {
            var repo = git.getRepository();
            var id = repo.resolve(ref);
            return repo.parseCommit(id).getName();
        }
    }

    private static GitGraphRow selected(RepoTab tab) {
        return tab.getCommitList().getSelectionModel().getSelectedItem();
    }

    private static Predicate<GitGraphRow> commit(String hash) {
        return row -> row != null && row.getCommit() != null && hash.equals(row.getCommit().getHash());
    }

    private MainWindowFixture open() throws Exception {
        MainWindowFixture window = MainWindowFixture.create(tempDir);
        window.open(1180, 690, ThemeManager.Theme.DARK, true);
        RobotWait.drainGitWork();
        return window;
    }

    @Test
    void aLocalBranchSelectsItsTip() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            String tip = hashOf(window, "refs/heads/feature/search");
            FxHarness.run(() -> click(treeCell(tree(tab, 0), "feature/search")));
            RobotWait.untilOnFx("the branch's tip was never selected", () -> commit(tip).test(selected(tab)));
        }
    }

    @Test
    void aRemoteBranchSelectsItsTip() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            String tip = hashOf(window, "refs/remotes/origin/main");
            FxHarness.run(() -> {
                section(tab, "REMOTE").setExpanded(true);
                tree(tab, 1).getRoot().getChildren().forEach(item -> item.setExpanded(true));
            });
            window.layOut();
            FxHarness.run(() -> click(treeCell(tree(tab, 1), "origin/main")));
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the remote branch's tip was never selected", () -> commit(tip).test(selected(tab)));
        }
    }

    @Test
    void aTagSelectsItsCommitOnOneClick() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            String commit = hashOf(window, "refs/tags/v1.0^{}");
            FxHarness.run(() -> section(tab, "TAGS").setExpanded(true));
            window.layOut();
            FxHarness.run(() -> click(section(tab, "TAGS").getContent().lookupAll(".list-cell").stream()
                    .filter(n -> n instanceof ListCell<?> c && "v1.0".equals(c.getItem()))
                    .findFirst().orElseThrow()));
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the tag's commit was never selected", () -> commit(commit).test(selected(tab)));
        }
    }

    @Test
    void aStashSelectsItsOwnRowAndShowsIt() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            String stash = hashOf(window, "refs/stash");
            FxHarness.run(() -> section(tab, "STASHES").setExpanded(true));
            window.layOut();
            FxHarness.run(() -> click(section(tab, "STASHES").getContent().lookupAll(".list-cell").stream()
                    .filter(n -> n instanceof ListCell<?> c && c.getItem() != null)
                    .findFirst().orElseThrow()));
            RobotWait.untilOnFx("the stash's row was never selected", () -> {
                GitGraphRow row = selected(tab);
                return row != null && row.isStash() && stash.equals(row.getStash().hash());
            });
        }
    }

    /**
     * The case that did nothing: a stash made on a commit since rewritten away. Only the
     * stash reaches that commit, so it - and the stash row beside it - used to be missing
     * from the graph, and the click had nothing to select.
     */
    @Test
    void aStashOnRewrittenHistoryIsStillSelected() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            String stash;
            try (Git git = Git.open(window.workTree().toFile())) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("wip soon rewritten").setSign(false).call();
                java.nio.file.Files.writeString(window.workTree().resolve("README.md"), "changed again\n");
                stash = git.stashCreate().setWorkingDirectoryMessage("on rewritten wip").call().getName();
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("HEAD~1").call();
            }
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            RobotWait.drainGitWork();
            FxHarness.run(() -> section(tab, "STASHES").setExpanded(true));
            window.layOut();
            FxHarness.run(() -> click(section(tab, "STASHES").getContent().lookupAll(".list-cell").stream()
                    .filter(n -> n instanceof ListCell<?> c && c.getItem() instanceof com.jfx.git.model.StashEntry s
                            && stash.equals(s.hash()))
                    .findFirst().orElseThrow()));
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the stash on rewritten history was never selected", () -> {
                GitGraphRow row = selected(tab);
                return row != null && row.isStash() && stash.equals(row.getStash().hash());
            });
        }
    }

    @Test
    void aTipOlderThanTheLoadedHistoryIsPagedIn() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            String tip = hashOf(window, "refs/heads/feature/login-redesign");
            FxHarness.run(() -> {
                tab.setGraphLimitForTest(2);
                tab.refresh();
            });
            RobotWait.drainGitWork();
            FxHarness.run(() -> assertTrue(tab.getCommitList().getItems().stream().noneMatch(commit(tip)),
                    "precondition: the tip is beyond the loaded page"));
            FxHarness.run(() -> click(treeCell(tree(tab, 0), "feature/login-redesign")));
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("older history was never paged in to reach the tip",
                    () -> commit(tip).test(selected(tab)));
            FxHarness.run(() -> assertTrue(tab.graphLimitForTest() > 2));
        }
    }

    /**
     * A branch the user hid with the eye: its tip is not a start of the walk, so paging
     * history in to find it would read all of it, take seconds, and leave the graph at its
     * cap for the rest of the session. The click says it is hidden instead.
     */
    @Test
    void aHiddenBranchIsNotHuntedForThroughAllOfHistory() throws Exception {
        java.util.Set<String> before = com.jfx.git.core.AppPreferences.graphHiddenBranches();
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            com.jfx.git.core.AppPreferences.setGraphHiddenBranches(
                    java.util.Set.of("refs/heads/" + MainWindowFixture.LONG_BRANCH));
            FxHarness.run(() -> {
                tab.setGraphLimitForTest(2);
                tab.refresh();
            });
            RobotWait.drainGitWork();
            GitGraphRow selectedBefore = FxHarness.call(() -> selected(tab));
            FxHarness.run(() -> click(treeCell(tree(tab, 0), MainWindowFixture.LONG_BRANCH)));
            RobotWait.drainGitWork();
            FxHarness.run(() -> {
                assertEquals(2, tab.graphLimitForTest(), "no paging in search of a tip the walk leaves out");
                assertEquals(selectedBefore, selected(tab));
            });
        } finally {
            com.jfx.git.core.AppPreferences.setGraphHiddenBranches(before);
        }
    }

    /**
     * A tag Solo excludes whose commit is on the soloed branch's history all the same: the
     * graph does reach it, further down, so the click pages history in and selects it -
     * rather than saying it is hidden, as it did for every ref Hide/Solo excluded.
     */
    @Test
    void anExcludedTagOnAShownBranchIsStillFound() throws Exception {
        String soloBefore = com.jfx.git.core.AppPreferences.graphSoloBranch();
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            String tagged = hashOf(window, "refs/tags/v1.0^{}");
            com.jfx.git.core.AppPreferences.setGraphSoloBranch("refs/heads/main");
            FxHarness.run(() -> {
                tab.setGraphLimitForTest(2);
                tab.refresh();
            });
            RobotWait.drainGitWork();
            FxHarness.run(() -> {
                assertTrue(tab.getCommitList().getItems().stream().noneMatch(commit(tagged)),
                        "precondition: the tag's commit is beyond the page");
                section(tab, "TAGS").setExpanded(true);
            });
            window.layOut();
            FxHarness.run(() -> click(section(tab, "TAGS").getContent().lookupAll(".list-cell").stream()
                    .filter(n -> n instanceof ListCell<?> c && "v1.0".equals(c.getItem()))
                    .findFirst().orElseThrow()));
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("a tag on main's history was not found under Solo main",
                    () -> commit(tagged).test(selected(tab)) && tab.graphLimitForTest() > 2);
        } finally {
            com.jfx.git.core.AppPreferences.setGraphSoloBranch(soloBefore);
        }
    }

    /**
     * A refresh queued while a click's paging was running used to land after it with the
     * old, smaller limit, and throw the just-revealed commit away with the rows round it.
     */
    @Test
    void aRefreshQueuedBehindARevealKeepsTheRevealedCommit() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            String tip = hashOf(window, "refs/heads/" + MainWindowFixture.LONG_BRANCH);
            FxHarness.run(() -> {
                tab.setGraphLimitForTest(2);
                tab.refresh();
            });
            RobotWait.drainGitWork();
            FxHarness.run(() -> {
                click(treeCell(tree(tab, 0), MainWindowFixture.LONG_BRANCH));
                // The second click of a double-click, or the watcher: a reload behind the paging.
                tab.refresh();
            });
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the refresh behind the reveal threw the revealed commit away",
                    () -> commit(tip).test(selected(tab)) && tab.graphLimitForTest() > 2);
        }
    }

    @Test
    void theEyeButtonDoesNotAlsoRevealTheBranch() throws Exception {
        try (MainWindowFixture window = open()) {
            RepoTab tab = window.tab();
            GitGraphRow before = FxHarness.call(() -> selected(tab));
            FxHarness.run(() -> {
                Node cell = treeCell(tree(tab, 0), "feature/search");
                Node eye = cell.lookup(".branch-eye-toggle");
                Event.fireEvent(eye, new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, MouseButton.PRIMARY, 1,
                        false, false, false, false, false, false, false, false, false, false, null));
            });
            RobotWait.drainGitWork();
            FxHarness.run(() -> assertEquals(before, selected(tab), "clicking the eye is not clicking the branch"));
        }
    }
}
