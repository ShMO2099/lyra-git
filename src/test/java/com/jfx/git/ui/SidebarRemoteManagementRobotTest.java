package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.ui.dialogs.EditRemoteDialog;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Managing remotes from the sidebar: the remote's own row and its menu, deleting a branch
 * on a remote, upstreams, and tags going to the remote that was chosen.
 *
 * <p>Two real bare repositories stand in for the remotes. {@code origin} is pushed to;
 * {@code backup} is a copy of it that this clone has never fetched - the remote with no
 * branches yet, which used to have no row in the sidebar at all and so nothing to hang
 * "Fetch" from.
 *
 * <p>Menu items are fired through the cell's own {@link ContextMenu} rather than by
 * clicking the popup: what is under test is what each item does, and a popup placed by
 * the headless window system is the one part of that which is not this application's.
 * Items that open a dialog are fired with a bare {@link Platform#runLater}, because
 * {@code interact} would wait for a {@code showAndWait} that never returns.
 */
class SidebarRemoteManagementRobotTest extends ApplicationTest {

    @TempDir
    Path root;

    private Path workTree;
    private Path originDir;
    private Path backupDir;
    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        workTree = Files.createDirectory(root.resolve("work"));
        originDir = root.resolve("origin.git");
        backupDir = root.resolve("backup.git");

        try (Git git = Git.init().setDirectory(workTree.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        Git.init().setDirectory(originDir.toFile()).setBare(true).setInitialBranch("main").call().close();

        service = new GitService();
        service.openRepository(workTree.toFile());
        Files.writeString(workTree.resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("add a", false).isOk());
        assertTrue(service.addRemote("origin", originDir.toString()).isOk());
        assertTrue(new RepositoryCommands(service).push("origin", false, false, true).isOk());

        // A second remote that already has main, and that this clone has not fetched.
        Git.cloneRepository().setBare(true).setURI(originDir.toUri().toString())
                .setDirectory(backupDir.toFile()).call().close();
        assertTrue(service.addRemote("backup", backupDir.toString()).isOk());

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheSidebar() {
        refreshAndOpenRemotes();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Finding things
    // ------------------------------------------------------------------

    private TitledPane section(String title) {
        return FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .map(n -> (TitledPane) n)
                .filter(pane -> pane.getGraphic() instanceof javafx.scene.Parent graphic
                        && graphic.getChildrenUnmodifiable().stream().anyMatch(c ->
                        c instanceof javafx.scene.control.Label l && title.equals(l.getText())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + title + " section"));
    }

    @SuppressWarnings("unchecked")
    private TreeView<BranchTree.Node> tree(String sectionTitle) {
        return (TreeView<BranchTree.Node>) section(sectionTitle).getContent().lookup(".branch-tree");
    }

    @SuppressWarnings("unchecked")
    private ListView<String> tagList() {
        return (ListView<String>) section("TAGS").getContent().lookup(".list-view");
    }

    /** The labels of the rows directly under the REMOTE tree's root: the remotes. */
    private List<String> remoteRows() {
        TreeItem<BranchTree.Node> treeRoot = tree("REMOTE").getRoot();
        return treeRoot == null ? List.of()
                : treeRoot.getChildren().stream().map(item -> item.getValue().label()).toList();
    }

    private static void expandAll(TreeItem<?> item) {
        for (TreeItem<?> child : item.getChildren()) {
            child.setExpanded(true);
            expandAll(child);
        }
    }

    /**
     * Reloads the sidebar and opens REMOTE and every folder in it, so its rows have cells.
     * A refresh rebuilds the tree with its folders closed, so this follows every change.
     */
    private void refreshAndOpenRemotes() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the REMOTE tree was never populated",
                () -> tree("REMOTE").getRoot() != null && !tree("REMOTE").getRoot().getChildren().isEmpty());
        openRemotes();
    }

    private void openRemotes() {
        interact(() -> {
            section("REMOTE").setExpanded(true);
            expandAll(tree("REMOTE").getRoot());
        });
    }

    /** The menu of the row showing {@code path} in a section's tree, once that row has a cell. */
    private ContextMenu menuOfTreeRow(String sectionTitle, String path) {
        ContextMenu[] menu = new ContextMenu[1];
        RobotWait.untilOnFx("no row for " + path + " in " + sectionTitle, () -> {
            for (Node node : tree(sectionTitle).lookupAll(".tree-cell")) {
                @SuppressWarnings("unchecked")
                javafx.scene.control.TreeCell<BranchTree.Node> cell =
                        (javafx.scene.control.TreeCell<BranchTree.Node>) node;
                if (cell.getTreeItem() != null && path.equals(BranchTree.pathOf(cell.getTreeItem()))
                        && cell.getContextMenu() != null) {
                    menu[0] = cell.getContextMenu();
                    return true;
                }
            }
            return false;
        });
        return menu[0];
    }

    private ContextMenu menuOfTag(String tag) {
        ContextMenu[] menu = new ContextMenu[1];
        RobotWait.untilOnFx("no row for the tag " + tag, () -> {
            for (Node node : tagList().lookupAll(".list-cell")) {
                IndexedCell<?> cell = (IndexedCell<?>) node;
                if (tag.equals(cell.getItem()) && cell.getContextMenu() != null) {
                    menu[0] = cell.getContextMenu();
                    return true;
                }
            }
            return false;
        });
        return menu[0];
    }

    private static List<String> labels(ContextMenu menu) {
        return menu.getItems().stream()
                .filter(item -> !(item instanceof javafx.scene.control.SeparatorMenuItem))
                .map(MenuItem::getText).toList();
    }

    /** The item reached by {@code path}: a label, or a submenu's label and then one inside it. */
    private static MenuItem item(ContextMenu menu, String... path) {
        List<MenuItem> items = menu.getItems();
        MenuItem found = null;
        for (String label : path) {
            found = items.stream().filter(i -> label.equals(i.getText())).findFirst().orElse(null);
            if (found == null) {
                throw new AssertionError("no \"" + label + "\" among "
                        + items.stream().map(MenuItem::getText).toList());
            }
            items = found instanceof Menu sub ? sub.getItems() : List.of();
        }
        return found;
    }

    /** Fires an item that opens a modal dialog, without waiting for the dialog to close. */
    private static void fireOpeningADialog(MenuItem item) {
        Platform.runLater(item::fire);
    }

    /**
     * Presses the button that reads {@code button}. A dialog in the shell keeps OK as the
     * confirming button's type and shows its verb ("Remove backup") on it, so the label
     * on screen is what is matched, as a user would.
     */
    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(((Button) pane.lookupButton(bt)).getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no \"" + button + "\" button; dialog offers "
                            + pane.getButtonTypes().stream()
                                    .map(bt -> ((Button) pane.lookupButton(bt)).getText()).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    private static boolean hasRef(Path bareRepository, String ref) {
        try (Git bare = Git.open(bareRepository.toFile())) {
            return bare.getRepository().getRefDatabase().exactRef(ref) != null;
        } catch (Exception e) {
            throw new AssertionError("could not read " + bareRepository, e);
        }
    }

    private List<String> remoteBranches() {
        return service.getRemoteBranches().stream().map(ref -> ref.shortName()).sorted().toList();
    }

    // ------------------------------------------------------------------
    // The remote's own row
    // ------------------------------------------------------------------

    @Test
    void aRemoteWithNothingFetchedStillHasARowAndItsMenuFetchesIt() {
        assertEquals(List.of("backup", "origin"), remoteRows(),
                "backup has no branches here yet, and is the one that most needs a row");

        ContextMenu menu = menuOfTreeRow("REMOTE", "backup");
        assertEquals(List.of("Fetch backup", "Edit URL…", "Rename…", "Remove…"), labels(menu));

        interact(() -> item(menu, "Fetch backup").fire());
        RobotWait.until("backup was never fetched", () -> remoteBranches().contains("backup/main"));
        assertEquals(List.of("backup/main", "origin/main"), remoteBranches(),
                "fetching one remote leaves the other as it was");
    }

    @Test
    void aFolderThatIsNotARemoteHasNoRemoteMenu() throws Exception {
        assertTrue(service.createBranch("feature/one", null, true).isOk());
        assertTrue(new RepositoryCommands(service).push("origin", false, false, true).isOk());
        assertTrue(service.checkoutBranch("main").isOk());
        refreshAndOpenRemotes();

        assertEquals(List.of("Fetch origin", "Edit URL…", "Rename…", "Remove…"),
                labels(menuOfTreeRow("REMOTE", "origin")));
        RobotWait.untilOnFx("origin/feature never got a row", () -> tree("REMOTE").lookupAll(".tree-cell")
                .stream().anyMatch(node -> {
                    var cell = (javafx.scene.control.TreeCell<?>) node;
                    return cell.getTreeItem() != null && "feature".equals(cell.getText());
                }));
        interact(() -> {
            for (Node node : tree("REMOTE").lookupAll(".tree-cell")) {
                var cell = (javafx.scene.control.TreeCell<?>) node;
                if ("feature".equals(cell.getText())) {
                    assertNull(cell.getContextMenu(),
                            "origin/feature is a folder of branches, not a remote");
                }
            }
        });
    }

    @Test
    void renamingARemoteRenamesItsBranchesAndKeepsTheUpstream() {
        fireOpeningADialog(item(menuOfTreeRow("REMOTE", "origin"), "Rename…"));
        DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        interact(() -> ((TextField) dialog.lookup(".text-field")).setText("upstream"));
        press(dialog, "Rename remote");

        RobotWait.until("origin was never renamed", () -> service.getRemoteNames().contains("upstream"));
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the sidebar still lists origin", () -> remoteRows().equals(List.of("backup", "upstream")));
        assertEquals(List.of("upstream/main"), remoteBranches());
        assertEquals("upstream/main", service.getUpstream("main"),
                "main tracked origin/main, so it tracks the same branch under its new name");
    }

    @Test
    void removingARemoteAsksAndCancellingKeepsIt() {
        fireOpeningADialog(item(menuOfTreeRow("REMOTE", "backup"), "Remove…"));
        DialogPane asked = RobotWait.untilDialogWithButton("OK");
        // The way back is shown: the URL the remote can be added again with.
        String url = service.getRemoteUrl("backup");
        interact(() -> assertTrue(asked.lookupAll(".jfx-code-text").stream()
                        .anyMatch(n -> url.equals(((javafx.scene.control.Label) n).getText())),
                "the question shows backup's URL, " + url));
        press(asked, "Cancel");
        RobotWait.drainGitWork();
        assertEquals(List.of("backup", "origin"), service.getRemoteNames().stream().sorted().toList(),
                "cancelling must leave the remote configured");

        fireOpeningADialog(item(menuOfTreeRow("REMOTE", "backup"), "Remove…"));
        press(RobotWait.untilDialogWithButton("OK"), "Remove backup");
        RobotWait.until("backup was never removed", () -> service.getRemoteNames().equals(List.of("origin")));
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the sidebar still lists backup", () -> remoteRows().equals(List.of("origin")));
    }

    @Test
    void editingARemoteChangesTheUrlThatWasEditedAndLeavesTheOther() {
        String fetchUrl = service.getRemoteUrl("backup");
        String pushUrl = "ssh://git@example.com/team/backup.git";

        fireOpeningADialog(item(menuOfTreeRow("REMOTE", "backup"), "Edit URL…"));
        DialogPane dialog = RobotWait.untilDialogWithButton("Save");
        interact(() -> {
            assertEquals(fetchUrl, ((TextField) dialog.lookup("#" + EditRemoteDialog.FETCH_FIELD_ID)).getText(),
                    "the dialog opens on the URL the remote has");
            ((TextField) dialog.lookup("#" + EditRemoteDialog.PUSH_FIELD_ID)).setText(pushUrl);
        });
        press(dialog, "Save");

        RobotWait.until("the push URL was never set", () -> pushUrl.equals(service.getRemotePushUrl("backup")));
        assertEquals(fetchUrl, service.getRemoteUrl("backup"), "the fetch URL was not edited");

        // And back: a blank push URL means "the same one" again.
        RobotWait.drainGitWork();
        openRemotes();
        fireOpeningADialog(item(menuOfTreeRow("REMOTE", "backup"), "Edit URL…"));
        DialogPane again = RobotWait.untilDialogWithButton("Save");
        interact(() -> {
            TextField push = (TextField) again.lookup("#" + EditRemoteDialog.PUSH_FIELD_ID);
            assertEquals(pushUrl, push.getText(), "the dialog shows the push URL it was given");
            push.setText("");
        });
        press(again, "Save");
        RobotWait.until("the push URL was never cleared", () -> service.getRemotePushUrl("backup") == null);
        assertEquals(fetchUrl, service.getRemoteUrl("backup"));
    }

    @Test
    void anEditedRemoteCannotBeSavedWithoutAUrl() {
        fireOpeningADialog(item(menuOfTreeRow("REMOTE", "backup"), "Edit URL…"));
        DialogPane dialog = RobotWait.untilDialogWithButton("Save");
        interact(() -> {
            ((TextField) dialog.lookup("#" + EditRemoteDialog.FETCH_FIELD_ID)).setText("   ");
            ButtonType save = dialog.getButtonTypes().stream()
                    .filter(bt -> "Save".equals(bt.getText())).findFirst().orElseThrow();
            assertTrue(dialog.lookupButton(save).isDisabled(),
                    "a remote has to be fetched from somewhere");
        });
        press(dialog, "Cancel");
    }

    // ------------------------------------------------------------------
    // Branches on a remote, and upstreams
    // ------------------------------------------------------------------

    @Test
    void deletingABranchFromItsRemoteRemovesItThereAndKeepsTheLocalBranch() throws Exception {
        assertTrue(service.createBranch("side", null, true).isOk());
        assertTrue(new RepositoryCommands(service).push("origin", false, false, true).isOk());
        assertTrue(service.checkoutBranch("main").isOk());
        assertTrue(hasRef(originDir, "refs/heads/side"), "sanity: side is on the remote");
        refreshAndOpenRemotes();

        ContextMenu menu = menuOfTreeRow("REMOTE", "origin/side");
        fireOpeningADialog(item(menu, "Delete from remote…"));
        press(RobotWait.untilDialogWithButton("OK"), "Delete from origin");

        RobotWait.until("side was never deleted from origin", () -> !hasRef(originDir, "refs/heads/side"));
        RobotWait.drainGitWork();
        assertTrue(hasRef(originDir, "refs/heads/main"), "and nothing else went with it");
        assertEquals(List.of("origin/main"), remoteBranches());
        assertTrue(service.getLocalBranches().stream().anyMatch(b -> b.shortName().equals("side")),
                "the local branch is not what was asked for");
    }

    @Test
    void aRemoteBranchBecomesTheCurrentBranchsUpstreamAndTheLocalMenuStopsTrackingIt() throws Exception {
        assertTrue(service.createBranch("side", null, true).isOk());
        assertNull(service.getUpstream("side"), "sanity: a new branch tracks nothing");
        refreshAndOpenRemotes();

        ContextMenu local = menuOfTreeRow("LOCAL", "side");
        interact(() -> {
            assertEquals("Set upstream…", item(local, "Set upstream…").getText());
            assertTrue(item(local, "Stop tracking").isDisable(), "there is nothing to stop tracking");
        });

        ContextMenu remote = menuOfTreeRow("REMOTE", "origin/main");
        interact(() -> item(remote, "Set as upstream of side").fire());
        RobotWait.until("side never tracked origin/main", () -> "origin/main".equals(service.getUpstream("side")));
        RobotWait.drainGitWork();

        // The refresh that follows rebuilds the local rows with what they now track.
        ContextMenu[] tracked = new ContextMenu[1];
        RobotWait.untilOnFx("side's menu never named its upstream", () -> {
            tracked[0] = menuOfTreeRowOrNull("LOCAL", "side");
            return tracked[0] != null && labels(tracked[0]).contains("Stop tracking origin/main");
        });
        interact(() -> {
            assertTrue(labels(tracked[0]).contains("Change upstream…"));
            item(tracked[0], "Stop tracking origin/main").fire();
        });
        RobotWait.until("side never stopped tracking", () -> service.getUpstream("side") == null);
    }

    private ContextMenu menuOfTreeRowOrNull(String sectionTitle, String path) {
        for (Node node : tree(sectionTitle).lookupAll(".tree-cell")) {
            @SuppressWarnings("unchecked")
            javafx.scene.control.TreeCell<BranchTree.Node> cell =
                    (javafx.scene.control.TreeCell<BranchTree.Node>) node;
            if (cell.getTreeItem() != null && path.equals(BranchTree.pathOf(cell.getTreeItem()))) {
                return cell.getContextMenu();
            }
        }
        return null;
    }

    @Test
    void theBranchAlreadyTrackedIsNotOfferedAsAnUpstreamAgain() {
        ContextMenu menu = menuOfTreeRow("REMOTE", "origin/main");
        interact(() -> assertTrue(item(menu, "Set as upstream of main").isDisable(),
                "main already tracks origin/main"));
    }

    @Test
    void theUpstreamDialogOpensOnTheSameNamedBranchAndSetsWhatIsChosen() throws Exception {
        assertTrue(service.createBranch("side", null, true).isOk());
        assertTrue(new RepositoryCommands(service).push("origin", false, false, true).isOk());
        assertTrue(service.unsetUpstream("side").isOk());
        refreshAndOpenRemotes();

        fireOpeningADialog(item(menuOfTreeRow("LOCAL", "side"), "Set upstream…"));
        DialogPane dialog = RobotWait.untilDialogWithButton("Set upstream");
        interact(() -> {
            @SuppressWarnings("unchecked")
            ListView<String> list = (ListView<String>) dialog.lookup(
                    "#" + com.jfx.git.ui.dialogs.SetUpstreamDialog.LIST_ID);
            assertEquals(List.of("origin/main", "origin/side"), List.copyOf(list.getItems()));
            assertEquals("origin/side", list.getSelectionModel().getSelectedItem(),
                    "the branch of the same name is the likely answer, so it is the one offered");
        });
        press(dialog, "Set upstream");
        RobotWait.until("side never tracked origin/side", () -> "origin/side".equals(service.getUpstream("side")));
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    @Test
    void withSeveralRemotesATagIsPushedToAndDeletedFromTheOneChosen() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        interact(() -> section("TAGS").setExpanded(true));

        ContextMenu menu = menuOfTag("v1");
        interact(() -> {
            assertFalse(labels(menu).contains("Push tag"),
                    "with two remotes \"Push tag\" would have to guess which");
            // In whatever order the engine lists its remotes: git keeps the configuration's,
            // JGit sorts them.
            assertEquals(List.of("backup", "origin"),
                    ((Menu) item(menu, "Push tag to")).getItems().stream().map(MenuItem::getText)
                            .sorted().toList());
            item(menu, "Push tag to", "backup").fire();
        });
        RobotWait.until("the tag never reached backup", () -> hasRef(backupDir, "refs/tags/v1"));
        assertFalse(hasRef(originDir, "refs/tags/v1"), "and it went nowhere else");

        RobotWait.drainGitWork();
        fireOpeningADialog(item(menuOfTag("v1"), "Delete tag from", "backup…"));
        press(RobotWait.untilDialogWithButton("OK"), "Delete from backup");
        RobotWait.until("the tag was never deleted from backup", () -> !hasRef(backupDir, "refs/tags/v1"));
        assertTrue(service.getTags().stream().anyMatch(t -> t.shortName().equals("v1")),
                "the local tag is untouched");
    }
}
