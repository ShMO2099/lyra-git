package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.MergeMode;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Click-level coverage for Git operation controls using disposable repositories. */
class ManualQaGitOperationControlsRobotTest extends ApplicationTest {

    @TempDir Path tempDir;
    @TempDir Path remoteRoot;

    private GitService service;
    private RepoTab tab;

    static {
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @Override
    public void start(Stage stage) throws Exception {
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Manual QA Robot");
                config.setString("user", null, "email", "manual-qa@example.test");
                config.setString("core", null, "autocrlf", "false");
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
            }
            service = new GitService();
            service.openRepository(tempDir.toFile());
        }
        tab = new RepoTab(new LyraApp(), "operation-controls", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
        System.gc();
    }

    private String commit(String path, String contents, String subject) throws Exception {
        Files.writeString(tempDir.resolve(path), contents, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(path).isOk());
        assertTrue(service.commit(subject, false).isOk());
        return service.getHeadHash();
    }

    private void refreshTab() {
        interact(tab::refresh);
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Node graphRow(String subject) {
        Node[] found = new Node[1];
        RobotWait.untilOnFx("no graph row for " + subject, () -> {
            found[0] = lookup(subject).tryQuery().orElse(null);
            return found[0] != null;
        });
        return found[0];
    }

    private void clickContextItem(String subject, String label) {
        Node row = graphRow(subject);
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
        ContextMenu menu = (ContextMenu) Window.getWindows().stream()
                .filter(Window::isShowing).filter(window -> window instanceof ContextMenu)
                .findFirst().orElseThrow(() -> new AssertionError("no graph context menu appeared"));
        assertTrue(menu.getItems().stream().anyMatch(item -> label.equals(item.getText())),
                "graph menu should offer " + label);
        clickOn(lookup(".context-menu").lookup(label).queryAs(Node.class));
    }

    private List<String> subjects() {
        return service.getCommitGraphPage(50, GraphOrder.current()).rows().stream()
                .filter(row -> !row.isWipNode() && row.getCommit() != null)
                .map(row -> row.getCommit().getSubject()).toList();
    }

    private String ref(String name) throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            ObjectId id = git.getRepository().resolve(name);
            return id == null ? null : id.getName();
        }
    }

    private int parentCount(String revision) throws Exception {
        try (Git git = Git.open(tempDir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve(revision)).getParentCount();
        }
    }

    private String firstParent(String revision) throws Exception {
        try (Git git = Git.open(tempDir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve(revision)).getParent(0).getName();
        }
    }

    private Node rebaseMoveButton(String subject) {
        Node root = lookup(".rebase-heading").query().getScene().getRoot();
        for (Node label : FxHarness.lookupAll(root, "rebase-message")) {
            if (label instanceof Label message && subject.equals(message.getText())) {
                return ((javafx.scene.layout.HBox) message.getParent()).getChildrenUnmodifiable().stream()
                        .filter(node -> node.getStyleClass().contains("rebase-move"))
                        .findFirst().orElseThrow(() -> new AssertionError("no move button for " + subject));
            }
        }
        throw new AssertionError("no rebase row for " + subject);
    }

    private void seedThreeConflictingEdits() throws Exception {
        commit("base.txt", "base\n", "base");
        commit("shared.txt", "version one\n", "first edit");
        commit("shared.txt", "version two\n", "second edit");
        commit("shared.txt", "version three\n", "third edit");
        refreshTab();
        graphRow("third edit");
    }

    @Test
    void skipControlCompletesAConflictedInteractiveRebase() throws Exception {
        seedThreeConflictingEdits();
        String branch = service.getCurrentBranch();

        clickContextItem("first edit", "Interactive rebase from here…");
        RobotWait.untilDialogWithButton("Start rebase");
        clickOn(rebaseMoveButton("third edit"));
        clickOn(rebaseMoveButton("third edit"));
        clickOn("Start rebase");
        RobotWait.untilOnFx("the conflict operation banner never appeared", () ->
                lookup(".operation-banner").tryQuery().isPresent());
        assertTrue(service.getOperationState().inProgress());
        clickOn(lookup(".operation-banner").lookup("Skip").queryAs(Node.class));
        RobotWait.drainGitWork();

        assertFalse(service.getOperationState().inProgress());
        assertEquals(branch, service.getCurrentBranch());
        assertEquals(List.of("second edit", "first edit", "base"), subjects());
        assertEquals("version two\n", Files.readString(tempDir.resolve("shared.txt")));
    }

    @Test
    void skipControlLeavesTheMainBranchUntouchedAfterAConflictedCherryPick() throws Exception {
        commit("shared.txt", "base\n", "base");
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("shared.txt", "feature version\n", "feature change");
        String featureTip = service.getHeadHash();
        assertTrue(service.checkoutBranch("main").isOk());
        commit("shared.txt", "main version\n", "main change");
        String mainTip = service.getHeadHash();
        refreshTab();

        clickContextItem("feature change", "Cherry-pick onto current branch");
        RobotWait.untilOnFx("the cherry-pick conflict banner never appeared", () ->
                lookup(".operation-banner").tryQuery().isPresent());
        assertTrue(service.getOperationState().inProgress());
        clickOn(lookup(".operation-banner").lookup("Skip").queryAs(Node.class));
        RobotWait.drainGitWork();

        assertFalse(service.getOperationState().inProgress());
        assertEquals(mainTip, service.getHeadHash());
        assertEquals(featureTip, ref("feature"));
        assertEquals("main version\n", Files.readString(tempDir.resolve("shared.txt")));
    }

    @Test
    void revertCommitControlCreatesAnInverseCommitAndKeepsTheOriginal() throws Exception {
        commit("stable.txt", "stable\n", "base");
        String base = service.getHeadHash();
        String target = commit("new.txt", "new content\n", "add new file");
        refreshTab();

        clickContextItem("add new file", "Revert commit");
        RobotWait.drainGitWork();

        String reverted = service.getHeadHash();
        assertNotEquals(target, reverted);
        assertEquals(target, firstParent(reverted));
        assertEquals(1, parentCount(reverted));
        assertFalse(Files.exists(tempDir.resolve("new.txt")));
        assertEquals(base, firstParent(target));
        assertTrue(subjects().stream().anyMatch(subject -> subject.startsWith("Revert ")));
        assertNotNull(ref(target), "the original commit remains in the object database");
    }

    @Test
    void resetMenuModesPreserveIndexAndWorkingTreeAndHardCancelChangesNothing() throws Exception {
        commit("settings.txt", "base\n", "base");
        String base = service.getHeadHash();
        commit("settings.txt", "work\n", "work");
        commit("extra.txt", "extra\n", "add extra");
        refreshTab();

        clickContextItem("base", "Reset current branch here (soft)");
        RobotWait.drainGitWork();
        assertEquals(base, service.getHeadHash());
        assertEquals(Set.of("settings.txt", "extra.txt"), Set.copyOf(service.getStagedFiles()));
        assertEquals("work\n", Files.readString(tempDir.resolve("settings.txt")));
        assertEquals("extra\n", Files.readString(tempDir.resolve("extra.txt")));

        assertTrue(service.commit("recreate work", false).isOk());
        refreshTab();
        clickContextItem("base", "Reset current branch here (mixed)");
        RobotWait.drainGitWork();
        assertEquals(base, service.getHeadHash());
        assertTrue(service.getStagedFiles().isEmpty());
        assertTrue(service.getStagingState().unstaged().containsAll(List.of("settings.txt", "extra.txt")));
        assertEquals("work\n", Files.readString(tempDir.resolve("settings.txt")));

        assertTrue(service.stageAll().isOk());
        assertTrue(service.commit("recreate work for hard reset", false).isOk());
        Files.writeString(tempDir.resolve("settings.txt"), "dirty before cancel\n");
        String beforeCancel = service.getHeadHash();
        refreshTab();
        clickContextItem("base", "Reset current branch here (hard)");
        DialogPane warning = RobotWait.untilDialogWithButton("Cancel");
        clickOn(warning.lookupButton(ButtonType.CANCEL));
        RobotWait.drainGitWork();
        assertEquals(beforeCancel, service.getHeadHash());
        assertEquals("dirty before cancel\n", Files.readString(tempDir.resolve("settings.txt")));

        refreshTab();
        clickContextItem("base", "Reset current branch here (hard)");
        DialogPane secondWarning = RobotWait.untilDialogWithButton("Cancel");
        assertTrue(secondWarning.getButtonTypes().contains(ButtonType.OK),
                "GitGraphCell confirms hard reset only for ButtonType.OK");
        clickOn(secondWarning.lookupButton(ButtonType.OK));
        RobotWait.drainGitWork();
        assertEquals(base, service.getHeadHash());
        assertEquals("base\n", Files.readString(tempDir.resolve("settings.txt")));
        assertFalse(Files.exists(tempDir.resolve("extra.txt")));
        assertTrue(service.getStagedFiles().isEmpty());
        assertTrue(service.getStagingState().unstaged().isEmpty());
    }

    @Test
    void toolbarUndoAndRedoRestoreTheExactMergeTip() throws Exception {
        commit("base.txt", "base\n", "base");
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("feature.txt", "feature\n", "feature work");
        assertTrue(service.checkoutBranch("main").isOk());
        commit("main.txt", "main\n", "main work");
        assertTrue(service.merge("feature", MergeMode.NO_FF, "merge feature").isOk());
        String mergeTip = service.getHeadHash();
        refreshTab();

        clickOn(tab.getToolbar().toolButton("Undo"));
        RobotWait.drainGitWork();
        assertNotEquals(mergeTip, service.getHeadHash());
        assertFalse(Files.exists(tempDir.resolve("feature.txt")));
        String beforeMerge = service.getHeadHash();
        RobotWait.untilOnFx("Redo did not become available", () ->
                !tab.getToolbar().toolButton("Redo").isDisabled());

        clickOn(tab.getToolbar().toolButton("Redo"));
        RobotWait.drainGitWork();
        assertEquals(mergeTip, service.getHeadHash());
        assertEquals(2, parentCount(mergeTip));
        assertTrue(Files.exists(tempDir.resolve("feature.txt")));
        assertEquals(beforeMerge, firstParent(mergeTip));
    }

    @Test
    void detachedHeadPushControlReportsTheGuardAndLeavesRemoteUnchanged() throws Exception {
        commit("base.txt", "base\n", "base");
        Path remotePath = remoteRoot.resolve("origin");
        Files.createDirectories(remotePath);
        Git.init().setDirectory(remotePath.toFile()).setBare(true).setInitialBranch("main").call().close();
        assertTrue(service.addRemote("origin", remotePath.toString()).isOk());
        assertTrue(new RepositoryCommands(service).push("origin", false, false, true).isOk());
        String remoteTip = remoteRef(remotePath);

        try (Git git = Git.open(tempDir.toFile())) {
            git.checkout().setName(service.getHeadHash()).call();
        }
        service.close();
        assertTrue(service.openRepository(tempDir.toFile()));
        refreshTab();

        clickOn(tab.getToolbar().toolButton("Push"));
        RobotWait.untilOnFx("detached push did not report its guard", () ->
                FxHarness.lookupAll(tab.getStatusBar(), "status-error").stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .anyMatch(label -> label.getText().toLowerCase().contains("detached")));

        assertTrue(service.isHeadDetached());
        assertEquals(remoteTip, remoteRef(remotePath));
    }

    private static String remoteRef(Path remotePath) throws Exception {
        try (Git git = Git.open(remotePath.toFile())) {
            ObjectId id = git.getRepository().resolve("refs/heads/main");
            return id == null ? null : id.getName();
        }
    }
}
