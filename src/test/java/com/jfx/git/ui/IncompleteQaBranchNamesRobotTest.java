package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Headless JavaFX entry checks for invalid new branch, rename and tag names. */
class IncompleteQaBranchNamesRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    private Stage mainWindow;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Branch Name QA");
            config.setString("user", null, "email", "branch-name-qa@example.invalid");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            Files.writeString(tempDir.resolve("base.txt"), "base\n");
            assertTrue(service.stageFile("base.txt").isOk());
            assertTrue(service.commit("base", false).isOk());
            assertTrue(service.createBranch("feature", null, false).isOk());
            assertTrue(service.createTag("existing-tag", null, "HEAD").isOk());
        }
        tab = new RepoTab(new LyraApp(), "branch names", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        mainWindow = stage;
        tab.refresh();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void refresh() {
        interact(tab::refresh);
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Set<String> branchNames() {
        return service.getLocalBranches().stream().map(ref -> ref.shortName()).collect(Collectors.toSet());
    }

    private Node localTree() {
        return FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0);
    }

    private void openPaneMenu(Node target) {
        interact(() -> Event.fireEvent(target, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Node inputField(DialogPane pane) {
        return FxHarness.lookupAll(pane, "text-field").stream().findFirst()
                .orElseThrow(() -> new AssertionError("no text field in dialog"));
    }

    /**
     * Types {@code text} and tries to confirm it. A branch prompt checks the name as it is
     * typed, so for a name it refuses the confirming button is disabled and stays so - the
     * refusal happens in the dialog, before anything reaches git - and the click after
     * that check does nothing.
     */
    private void submitRefusedName(DialogPane pane, String text) {
        Node field = inputField(pane);
        interact(() -> ((TextInputControl) field).setText(text));
        Node ok = pane.getButtonTypes().stream().filter(type -> "OK".equals(type.getText()))
                .map(pane::lookupButton).findFirst()
                .orElseThrow(() -> new AssertionError("no exact OK button in dialog"));
        assertTrue(FxHarness.call(ok::isDisabled), "\"" + text + "\" should be refused as it is typed");
        clickOn(ok);
    }

    private TitledPane tagsPane() {
        return FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .filter(TitledPane.class::isInstance).map(TitledPane.class::cast)
                .filter(pane -> pane.getGraphic() instanceof javafx.scene.Parent graphic
                        && graphic.getChildrenUnmodifiable().stream().anyMatch(child ->
                                child instanceof javafx.scene.control.Label label && "TAGS".equals(label.getText())))
                .findFirst().orElseThrow(() -> new AssertionError("no TAGS pane"));
    }

    @Test
    void invalidNewBranchNameCreatesNoRefAndDoesNotMoveHead() {
        String head = service.getHeadHash();
        Set<String> before = branchNames();
        openPaneMenu(localTree());
        clickOn(lookup(".context-menu").lookup("New branch…").queryAs(Node.class));
        DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        submitRefusedName(dialog, "bad name");
        RobotWait.drainGitWork();

        assertEquals(before, branchNames());
        assertEquals(head, service.getHeadHash());
        assertFalse(service.getOperationState().inProgress());
    }

    @Test
    void duplicateRenameNamePreservesBothBranchesAndHead() {
        String head = service.getHeadHash();
        Set<String> before = branchNames();
        Node tree = localTree();
        Node[] row = new Node[1];
        RobotWait.untilOnFx("feature did not appear in the local tree", () -> {
            row[0] = from(tree).lookup("feature").tryQuery().orElse(null);
            return row[0] != null;
        });
        openPaneMenu(row[0]);
        clickOn(lookup(".context-menu").lookup("Rename…").queryAs(Node.class));
        DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        submitRefusedName(dialog, "main");
        RobotWait.drainGitWork();

        assertEquals(before, branchNames());
        assertEquals(head, service.getHeadHash());
        assertEquals("main", service.getCurrentBranch());
    }

    @Test
    void invalidTagNameCreatesNoTagAndLeavesTheCurrentBranchAtTheSameCommit() {
        String head = service.getHeadHash();
        interact(() -> tagsPane().setExpanded(true));
        Node tagList = FxHarness.lookupAll(tagsPane(), "sidebar-list").stream().findFirst()
                .orElseThrow(() -> new AssertionError("no tag list"));
        openPaneMenu(tagList);
        clickOn(lookup(".context-menu").lookup("New tag on HEAD…").queryAs(Node.class));
        DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        Node name = FxHarness.call(() -> dialog.lookup("#" + com.jfx.git.ui.dialogs.NewTagDialog.NAME_ID));
        assertNotNull(name, "no tag name field");
        interact(() -> ((TextInputControl) name).setText("bad name"));
        Node ok = dialog.getButtonTypes().stream().filter(type -> "OK".equals(type.getText()))
                .map(dialog::lookupButton).findFirst().orElseThrow();
        clickOn(ok);
        RobotWait.drainGitWork();

        assertNull(service.getTags().stream().filter(ref -> ref.shortName().equals("bad name")).findFirst().orElse(null));
        assertTrue(service.getTags().stream().anyMatch(ref -> ref.shortName().equals("existing-tag")));
        assertEquals(head, service.getHeadHash());
        assertEquals("main", service.getCurrentBranch());
    }
}
