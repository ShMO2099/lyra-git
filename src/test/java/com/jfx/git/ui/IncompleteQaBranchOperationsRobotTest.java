package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** JavaFX operation paths missing from the partial manual-QA coverage. */
class IncompleteQaBranchOperationsRobotTest extends ApplicationTest {
    @BeforeAll static void toolkitAndTheme() { FxHarness.start(); }

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Branch QA Robot");
                config.setString("user", null, "email", "branch-qa@example.invalid");
                config.setString("core", null, "autocrlf", "false");
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
            }
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        tab = new RepoTab(new LyraApp(), "branch operations", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
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

    private String commit(String path, String contents, String subject) throws Exception {
        Files.writeString(tempDir.resolve(path), contents, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(path).isOk());
        assertTrue(service.commit(subject, false).isOk());
        return service.getHeadHash();
    }

    private void refresh() {
        interact(tab::refresh);
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void independentMergeFixture() throws Exception {
        commit("base.txt", "base\n", "base");
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("feature.txt", "feature work\n", "feature work");
        assertTrue(service.checkoutBranch("main").isOk());
        commit("main.txt", "main work\n", "main work");
        refresh();
    }

    private String cherryPickConflictFixture() throws Exception {
        commit("shared.txt", "base\n", "base");
        assertTrue(service.createBranch("feature", null, true).isOk());
        String featureTip = commit("shared.txt", "feature version\n", "feature conflict");
        assertTrue(service.checkoutBranch("main").isOk());
        commit("shared.txt", "main version\n", "main conflict");
        refresh();
        return featureTip;
    }

    private void twoFileMergeConflictFixture() throws Exception {
        commit("one.conf", "base one\n", "base");
        commit("two.conf", "base two\n", "base second file");
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("one.conf", "feature one\n", "feature first file");
        commit("two.conf", "feature two\n", "feature second file");
        assertTrue(service.checkoutBranch("main").isOk());
        commit("one.conf", "main one\n", "main first file");
        commit("two.conf", "main two\n", "main second file");
        refresh();
    }

    private Node graphRow(String subject) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no graph row for " + subject, () -> {
            row[0] = lookup(subject).tryQuery().orElse(null);
            return row[0] != null;
        });
        return row[0];
    }

    private void openGraphMenu(String subject) {
        Node row = graphRow(subject);
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void openBranchMenu(String branch) {
        Node tree = FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0);
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no local branch row for " + branch, () -> {
            row[0] = from(tree).lookup(branch).tryQuery().orElse(null);
            return row[0] != null;
        });
        interact(() -> Event.fireEvent(row[0], new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void selectWipRow() {
        RobotWait.untilOnFx("the WIP row never appeared at the top of the graph",
                () -> !tab.graphList().getItems().isEmpty() && tab.graphList().getItems().get(0).isWipNode());
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("the WIP row never opened the staging view",
                () -> !FxHarness.lookupAll(tab.getContent(), "staging-unstaged-list").isEmpty());
    }

    private void resolveConflict(String path, String choice) {
        RobotWait.untilOnFx("no conflicted file row for " + path,
                () -> from(lookup(".staging-conflicted-list")).lookup(path).tryQuery().isPresent());
        // A click on a conflicted row opens the merge editor on it.
        clickOn(from(lookup(".staging-conflicted-list")).lookup(path).queryAs(Node.class));
        DialogPane tool = RobotWait.untilDialogWithButton("Mark resolved");
        clickOn(from(tool).lookup(choice).queryAs(Node.class));
        // Inside the editor: the selected row's own action carries the same words.
        clickOn(from(tool).lookup("Mark resolved").queryAs(Node.class));
        RobotWait.until("the chosen conflict was not staged", () -> !service.getConflictingFiles().contains(path));
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean headHasParent(String expected) {
        try (Git git = Git.open(tempDir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit head = walk.parseCommit(git.getRepository().resolve("HEAD"));
            return head.getParentCount() > 0 && expected.equals(head.getParent(0).getName());
        } catch (Exception notReady) {
            return false;
        }
    }

    private Node dialogButton(DialogPane dialog, String label) {
        return dialog.getButtonTypes().stream()
                .filter(type -> label.equals(type.getText()))
                .map(dialog::lookupButton)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dialog button labelled " + label));
    }

    @Test
    void optionsMergeCreatesTheRequestedTwoParentMessage() throws Exception {
        independentMergeFixture();
        String mainBefore = service.getHeadHash();
        String featureBefore = service.getLocalBranches().stream()
                .filter(ref -> ref.shortName().equals("feature")).findFirst().orElseThrow().targetHash();

        openBranchMenu("feature");
        clickOn("Merge into main with options…");
        DialogPane dialog = RobotWait.untilDialogWithButton("Merge");
        var message = (javafx.scene.control.TextInputControl) dialog.lookup(".merge-message");
        interact(() -> message.setText("Integrate independent feature work"));
        clickOn(dialogButton(dialog, "Merge"));

        RobotWait.until("the options merge did not create a merge commit", () -> {
            try {
                return parentCount("HEAD") == 2;
            } catch (Exception pending) {
                return false;
            }
        });
        try (Git git = Git.open(tempDir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit merge = walk.parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(mainBefore, merge.getParent(0).getName());
            assertEquals("Integrate independent feature work", merge.getFullMessage().trim());
        }
        assertEquals(featureBefore, service.getLocalBranches().stream()
                .filter(ref -> ref.shortName().equals("feature")).findFirst().orElseThrow().targetHash());
        assertEquals("main work\n", Files.readString(tempDir.resolve("main.txt")));
        assertEquals("feature work\n", Files.readString(tempDir.resolve("feature.txt")));
        assertFalse(service.getOperationState().inProgress());
    }

    private int parentCount(String revision) throws Exception {
        try (Git git = Git.open(tempDir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve(revision)).getParentCount();
        }
    }

    @Test
    void cherryPickAbortCancelPreservesTheConflictAndAcceptedAbortRestoresTheBase() throws Exception {
        String featureTip = cherryPickConflictFixture();
        String mainTip = service.getHeadHash();

        openGraphMenu("feature conflict");
        clickOn(lookup(".context-menu").lookup("Cherry-pick onto current branch").queryAs(Node.class));
        RobotWait.untilOnFx("the cherry-pick banner did not appear",
                () -> lookup(".operation-banner").tryQuery().isPresent());
        RobotWait.until("the cherry-pick conflict did not reach the index",
                () -> service.getConflictingFiles().contains("shared.txt"));
        assertEquals(mainTip, service.getHeadHash());

        clickOn(lookup(".operation-banner").lookup("Abort cherry-pick").queryAs(Node.class));
        DialogPane cancelDialog = RobotWait.untilDialogWithButton("OK");
        clickOn(dialogButton(cancelDialog, "Cancel"));
        RobotWait.untilOnFx("cancel did not close the abort prompt", () ->
                cancelDialog.getScene() == null || cancelDialog.getScene().getWindow() == null
                        || !cancelDialog.getScene().getWindow().isShowing());
        assertTrue(service.getOperationState().inProgress(), "Cancel keeps the stopped operation active");
        assertEquals(mainTip, service.getHeadHash());

        clickOn(lookup(".operation-banner").lookup("Abort cherry-pick").queryAs(Node.class));
        DialogPane acceptDialog = RobotWait.untilDialogWithButton("OK");
        clickOn(dialogButton(acceptDialog, "OK"));
        RobotWait.until("accepted abort did not restore the original branch", () ->
                !service.getOperationState().inProgress() && mainTip.equals(service.getHeadHash()));

        assertEquals(featureTip, service.getLocalBranches().stream()
                .filter(ref -> ref.shortName().equals("feature")).findFirst().orElseThrow().targetHash());
        assertEquals("main version\n", Files.readString(tempDir.resolve("shared.txt")));
        assertTrue(service.getConflictingFiles().isEmpty());
    }

    @Test
    void resolvedCherryPickContinuesAsOneNewCommitAndLeavesItsSourceUntouched() throws Exception {
        String featureTip = cherryPickConflictFixture();
        String mainTip = service.getHeadHash();

        openGraphMenu("feature conflict");
        clickOn(lookup(".context-menu").lookup("Cherry-pick onto current branch").queryAs(Node.class));
        RobotWait.untilOnFx("the cherry-pick banner did not appear",
                () -> lookup(".operation-banner").tryQuery().isPresent());
        RobotWait.until("the cherry-pick conflict did not reach the index",
                () -> service.getConflictingFiles().contains("shared.txt"));
        selectWipRow();
        resolveConflict("shared.txt", "Use theirs");
        assertTrue(service.getOperationState().inProgress(), "resolution leaves cherry-pick waiting to continue");
        clickOn(lookup(".operation-banner").lookup("Continue").queryAs(Node.class));

        RobotWait.until("Continue did not finish the cherry-pick", () ->
                !service.getOperationState().inProgress() && headHasParent(mainTip));
        try (Git git = Git.open(tempDir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit result = walk.parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals(1, result.getParentCount());
        }
        assertEquals("feature version\n", Files.readString(tempDir.resolve("shared.txt")));
        assertEquals(featureTip, service.getLocalBranches().stream()
                .filter(ref -> ref.shortName().equals("feature")).findFirst().orElseThrow().targetHash());
    }

    @Test
    void useBothResolvesOneFileAndASecondConflictCanBeResolvedIndependently() throws Exception {
        twoFileMergeConflictFixture();
        openBranchMenu("feature");
        clickOn("Merge into main");
        RobotWait.until("merge did not produce both file conflicts", () ->
                service.getConflictingFiles().containsAll(java.util.Set.of("one.conf", "two.conf")));
        selectWipRow();

        resolveConflict("one.conf", "Use both");
        assertEquals("main one\nfeature one\n", Files.readString(tempDir.resolve("one.conf")),
                "Use both preserves ours then theirs");
        assertTrue(service.getConflictingFiles().contains("two.conf"), "the other conflict remains active");

        selectWipRow();
        resolveConflict("two.conf", "Use theirs");
        assertEquals("feature two\n", Files.readString(tempDir.resolve("two.conf")));
        assertTrue(service.getConflictingFiles().isEmpty());

        RobotWait.untilOnFx("resolved merge did not enable its finish control", () ->
                from(lookup(".operation-banner")).lookup("Commit merge").tryQuery()
                        .filter(node -> !node.isDisabled()).isPresent());
        clickOn(lookup(".operation-banner").lookup("Commit merge").queryAs(Node.class));
        RobotWait.until("the resolved merge did not finish", () -> {
            try { return parentCount("HEAD") == 2 && !service.getOperationState().inProgress(); }
            catch (Exception pending) { return false; }
        });
        assertEquals("main one\nfeature one\n", Files.readString(tempDir.resolve("one.conf")));
        assertEquals("feature two\n", Files.readString(tempDir.resolve("two.conf")));
    }
}
