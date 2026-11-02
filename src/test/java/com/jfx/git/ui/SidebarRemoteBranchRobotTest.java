package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hide & Solo on the REMOTE section's tree - Wave 5's extension of a feature
 * {@code SidebarBranchRobotTest} already proves for LOCAL branches. {@code
 * GraphVisibility}/{@code AppPreferences} were never branch-specific (both key by any
 * ref's {@code fullName}); only the sidebar's own menu wiring was, so this exercises
 * that wiring specifically for a remote-tracking branch's row.
 *
 * <p>The remote-tracking ref is written directly via JGit's {@link RefUpdate} rather
 * than a real push+fetch round trip against a bare remote - {@code SidebarPane}'s
 * REMOTE section only cares that {@code refs/remotes/origin/<branch>} exists, not how
 * it got there.
 *
 * <h2>Waiting for the sidebar to populate must happen in the {@code @Test} method, not {@code start()}</h2>
 * {@code SidebarPane.refresh()} loads its model on {@code GitTaskRunner}'s background
 * thread and only sets {@code remoteTree}'s root once that callback lands on the FX
 * thread via {@code Platform.runLater}. That callback is provably unable to run while
 * {@code ApplicationTest.start(Stage)} itself is still executing - a loop of twenty
 * {@code waitForFxEvents()}/sleep cycles from inside {@code start()} never once saw it
 * land, yet the very next line of the actual {@code @Test} method (after TestFX's own
 * setup machinery regains control once {@code start()} returns) always sees it already
 * populated. This is not specific to a remote-tracking ref or any of this class's own
 * setup - the same thing was confirmed with a from-scratch minimal reproduction using
 * only LOCAL branches, matching {@code SidebarBranchRobotTest}'s own setup line for
 * line. So the refresh-and-expand step runs from {@link #refreshAndExpandRemote}, a
 * {@code @BeforeEach} that JUnit runs after {@code start()} returns, not from
 * {@code start()} itself.
 */
class SidebarRemoteBranchRobotTest extends ApplicationTest {

    @TempDir
    Path localDir;

    private GitService service;
    private RepoTab tab;
    /** Whatever JGit actually names a fresh repository's first branch - not assumed. */
    private String mainBranch;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(localDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }

        service = new GitService();
        service.openRepository(localDir.toFile());
        Files.writeString(localDir.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("add a", false).isOk());
        mainBranch = service.getCurrentBranch();

        try (Git git = Git.open(localDir.toFile())) {
            RevCommit head = git.log().call().iterator().next();
            RefUpdate update = git.getRepository().updateRef("refs/remotes/origin/" + mainBranch);
            update.setNewObjectId(head.getId());
            update.update();
        }

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @BeforeEach
    void refreshAndExpandRemote() {
        interact(() -> tab.refresh());
        // The sidebar reloads off the FX thread and resizes every section when it lands, so
        // wait for REMOTE to have its folder before clicking a header that could move.
        RobotWait.untilOnFx("the REMOTE tree was never populated", () -> {
            javafx.scene.control.TreeView<?> tree = (javafx.scene.control.TreeView<?>) remoteTreeNode();
            return tree.getRoot() != null && !tree.getRoot().getChildren().isEmpty();
        });
        expandRemoteSection();
        expandOriginFolder();
    }

    /**
     * Every remote branch name contains a {@code /} ("origin/master"), so {@link
     * BranchTree#build} always wraps it in a folder node - unlike LOCAL, where a
     * single-segment name like "master" has no folder ancestor at all. That folder
     * starts collapsed (both call sites in {@code SidebarPane} build the REMOTE tree
     * with {@code expanded=false}), so its own leaf cells are never realized - and
     * therefore never findable by a text lookup - until it is opened.
     */
    private void expandOriginFolder() {
        javafx.scene.control.TreeView<?> tree = (javafx.scene.control.TreeView<?>) remoteTreeNode();
        interact(() -> tree.getRoot().getChildren().forEach(item -> item.setExpanded(true)));
        settle();
    }

    @AfterEach
    void tidyUp() {
        // Every toggle starts a refresh on the git lane; let it finish before the backend
        // closes and @TempDir deletes the repository under it.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
        com.jfx.git.core.AppPreferences.setGraphHiddenBranches(null);
        com.jfx.git.core.AppPreferences.setGraphSoloBranch(null);
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Expands REMOTE by clicking its {@code TitledPane} header directly.
     *
     * <p>Not a plain text lookup: REMOTE carries its title as an explicit {@code Label}
     * inside a custom graphic (see {@code SidebarPane.sectionTitle}), and TestFX's text
     * matching does not reliably reach a {@code TitledPane}'s own internally-rendered
     * title the way it reaches that Label directly - the same finding {@code
     * SidebarStashTagRobotTest.expandSection} documents for TAGS, which uses the same
     * graphic shape. REMOTE starts collapsed ({@code remotePane.setExpanded(false)}), so
     * unlike LOCAL this cannot lean on the default expanded state instead.
     */
    private void expandRemoteSection() {
        Node header = FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .filter(n -> n instanceof javafx.scene.control.TitledPane tp && graphicSaysText(tp, "REMOTE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no REMOTE section found"));
        clickOn(header);
        settle();
    }

    private boolean graphicSaysText(javafx.scene.control.TitledPane pane, String title) {
        if (!(pane.getGraphic() instanceof javafx.scene.Parent graphic)) {
            return false;
        }
        return graphic.getChildrenUnmodifiable().stream()
                .anyMatch(c -> c instanceof javafx.scene.control.Label l && title.equals(l.getText()));
    }

    /** REMOTE is the second ".branch-tree" the sidebar builds, LOCAL being the first. */
    private Node remoteTreeNode() {
        return FxHarness.lookupAll(tab.getContent(), "branch-tree").get(1);
    }

    /**
     * Every Hide/Solo toggle calls {@code onRefreshRequested}, which rebuilds the
     * REMOTE tree from scratch with its "origin" folder collapsed again (both call
     * sites in {@code SidebarPane} build it with {@code expanded=false}) - so a second
     * toggle in the same test needs the folder re-opened, not just the first.
     */
    private void openMenuOn(String label) {
        expandOriginFolder();
        // The leaf cells are only realised once the opened folder has been laid out.
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no \"" + label + "\" row appeared in REMOTE", () -> {
            row[0] = from(remoteTreeNode()).lookup(label).query();
            return row[0] != null;
        });
        interact(() -> Event.fireEvent(row[0], new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    /**
     * Waits for the refresh a toggle starts to reach the REMOTE tree's rows: the row for
     * {@code branch} offers {@code item}. Not a new root - a refresh whose branches are
     * unchanged now redraws the rows it has instead of rebuilding the tree under them.
     */
    private void untilRowOffers(String branch, String item) {
        RobotWait.untilOnFx("the row for " + branch + " never offered " + item, () -> {
            for (Node node : remoteTreeNode().lookupAll(".tree-cell")) {
                if (node instanceof javafx.scene.control.TreeCell<?> cell
                        && cell.getItem() instanceof BranchTree.Node row
                        && ("origin/" + branch).equals(row.fullName())
                        && cell.getContextMenu() != null
                        && cell.getContextMenu().getItems().stream().anyMatch(i -> item.equals(i.getText()))) {
                    return true;
                }
            }
            return false;
        });
    }

    @Test
    void hidingThenShowingARemoteBranchAgainClearsTheHiddenSet() {
        openMenuOn(mainBranch);
        clickOn("Hide from graph");
        // Until the rebuild lands, the row still carries the menu offering "Hide".
        untilRowOffers(mainBranch, "Show in graph");
        assertTrue(com.jfx.git.core.AppPreferences.graphHiddenBranches()
                        .contains("refs/remotes/origin/" + mainBranch),
                "the hidden set remembers this remote branch by its full ref name");
        openMenuOn(mainBranch);
        clickOn("Show in graph");
        settle();

        assertTrue(com.jfx.git.core.AppPreferences.graphHiddenBranches().isEmpty(),
                "toggling the same remote branch again should clear the hidden set");
    }

    @Test
    void soloingARemoteBranchStoresItByFullRefName() {
        openMenuOn(mainBranch);
        clickOn("Solo (show only this branch)");
        settle();

        assertEquals("refs/remotes/origin/" + mainBranch, com.jfx.git.core.AppPreferences.graphSoloBranch());
    }
}
