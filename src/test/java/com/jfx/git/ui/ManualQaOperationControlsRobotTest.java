package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.HybridGitBackend;
import com.jfx.git.core.NativeGitRunner;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jgit.api.Git;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Operation banners and the composer must follow the operation Git actually stopped in. */
class ManualQaOperationControlsRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private HybridGitBackend service;
    private RepoTab tab;
    private String mainBranch;
    private String mainHead;
    private String featureHead;

    @BeforeEach
    void requireNativeGit() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Hybrid operation UI coverage requires native Git");
    }

    @Override
    public void start(Stage stage) throws Exception {
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.com");
                config.setString("core", null, "autocrlf", "false");
                config.save();
            }
            service = new HybridGitBackend();
            service.openRepository(tempDir.toFile());
            commit("conflict.txt", "base\n", "base");
            mainBranch = service.getCurrentBranch();
            assertTrue(service.createBranch("feature", null, false).isOk());
            assertTrue(service.checkoutBranch("feature").isOk());
            commit("conflict.txt", "feature side\n", "feature change");
            featureHead = service.getHeadHash();
            assertTrue(service.checkoutBranch(mainBranch).isOk());
            commit("conflict.txt", "main side\n", "main change");
            mainHead = service.getHeadHash();
        }

        tab = new RepoTab(new LyraApp(), "operation-controls", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        interact(tab::refresh);
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(String path, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(path), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(path).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void chooseBranchAction(String branch, String action) {
        RobotWait.untilOnFx(branch + " branch did not appear in the sidebar",
                () -> from(localTreeNode()).lookup(branch).tryQuery().isPresent());
        Node row = from(localTreeNode()).lookup(branch).query();
        javafx.scene.control.Control owner = rowOwnerWithContextMenu(row);
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
        assertTrue(owner.getContextMenu().getItems().stream()
                        .anyMatch(item -> action.equals(item.getText())),
                "LOCAL row for " + branch + " did not offer " + action + "; menu was "
                        + owner.getContextMenu().getItems().stream()
                                .map(javafx.scene.control.MenuItem::getText).toList());
        clickOn(from(lookup(".context-menu")).lookup(action).queryAs(Node.class));
    }

    private Node localTreeNode() {
        return FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0);
    }

    private javafx.scene.control.Control rowOwnerWithContextMenu(Node row) {
        Node node = row;
        while (!(node instanceof javafx.scene.control.Control control
                && control.getContextMenu() != null)) {
            node = node.getParent();
            if (node == null) throw new AssertionError("LOCAL branch row has no context menu owner");
        }
        return (javafx.scene.control.Control) node;
    }

    private void selectWip() {
        RobotWait.untilOnFx("the conflicted operation did not add a WIP row",
                () -> !tab.graphList().getItems().isEmpty()
                        && tab.graphList().getItems().get(0).isWipNode());
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
    }

    private void openResolveDialog() {
        RobotWait.untilOnFx("the conflicted files list never loaded conflict.txt",
                () -> from(lookup(".staging-conflicted-list"))
                        .lookup("conflict.txt").tryQuery().isPresent());
        Node conflictRow = from(lookup(".staging-conflicted-list"))
                .lookup("conflict.txt").queryAs(Node.class);
        // A click on a conflicted row opens the merge editor; the caller waits for it.
        clickOn(conflictRow);
    }

    private void takeTheirsAndMarkResolved() {
        takeSideAndMarkResolved("Take theirs");
    }

    private void takeSideAndMarkResolved(String side) {
        DialogPane dialog = RobotWait.untilDialogWithButton("Mark resolved");
        // The whole-file choices are in the toolbar's Take menu.
        clickOn(from(dialog).lookup("Take").queryAs(Node.class));
        clickOn(from(lookup(".context-menu")).lookup(side).queryAs(Node.class));
        settle();
        clickOn(from(dialog).lookup("Mark resolved").queryAs(Node.class));
    }

    private void startConflictingRebase() {
        RobotWait.drainGitWork();
        // The manual reproduction is one feature commit replayed onto main. Start on
        // feature, then invoke the operation from main's branch menu so its direction is
        // explicit and the UI gets the same operation state as the saved evidence.
        assertTrue(service.checkoutBranch("feature").isOk());
        interact(tab::refresh);
        RobotWait.drainGitWork();
        chooseBranchAction(mainBranch, "Rebase current onto this");
        DialogPane rebaseDialog = RobotWait.untilDialogWithButton("Start rebase");
        clickOn(from(rebaseDialog).lookup("Start rebase").queryAs(Node.class));
        RobotWait.until("the rebase did not stop on the conflict",
                () -> service.getOperationState().kind() == com.jfx.git.model.OperationState.Kind.REBASING
                        && !service.getStagingState().conflicting().isEmpty());
        selectWip();
    }

    @Test
    void resolvingARebaseConflictRefreshesTheConflictCountAndEnablesContinue() {
        startConflictingRebase();
        openResolveDialog();
        takeTheirsAndMarkResolved();
        RobotWait.until("Git did not clear the rebase conflict", () ->
                service.getStagingState().conflicting().isEmpty());
        assertNoNativeUnmergedPaths();
        // Exercise the manual F5/reselect path after Git has cleared its unmerged index.
        interact(tab::refresh);
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        selectWip();
        RobotWait.drainGitWork();

        RobotWait.untilOnFx("operation banner continued to describe the resolved conflict", () -> {
            Node banner = FxHarness.lookupAll(tab.getContent(), "operation-banner").stream()
                    .findFirst().orElse(null);
            if (!(banner instanceof javafx.scene.Parent parent)) return false;
            boolean accurate = FxHarness.lookupAll(parent, "conflict-banner").stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .anyMatch(label -> label.getText().contains("Nothing is conflicting"));
            boolean canContinue = from(lookup(".operation-banner")).lookup("Continue")
                    .tryQuery().filter(Button.class::isInstance).map(Button.class::cast)
                    .map(button -> !button.isDisabled()).orElse(false);
            return accurate && canContinue;
        });
        assertTrue(service.getStagingState().conflicting().isEmpty());

        fireCurrentEnabledContinue();
        RobotWait.until("the one-Pick rebase did not finish", () ->
                service.getOperationState().kind() == com.jfx.git.model.OperationState.Kind.NONE);
        RobotWait.drainGitWork();
        assertEquals("feature", service.getCurrentBranch(), "rebase keeps its source branch checked out");
        assertNotEquals(featureHead, service.getHeadHash(), "the replay creates the rebased feature commit");
        NativeGitRunner.Result parent = nativeGit().run("rev-parse", "HEAD^");
        assertTrue(parent.isOk(), parent.stderr());
        assertEquals(mainHead, parent.trimmedStdout(), "the rebased commit is based on main");
        NativeGitRunner.Result subject = nativeGit().run("log", "-1", "--format=%s");
        assertTrue(subject.isOk(), subject.stderr());
        assertEquals("feature change", subject.trimmedStdout());
    }

    @Test
    void resolvingARebaseConflictEnablesContinueWithoutAnExplicitRefresh() {
        startConflictingRebase();
        openResolveDialog();
        takeTheirsAndMarkResolved();

        RobotWait.until("Git did not clear the rebase conflict", () ->
                service.getStagingState().conflicting().isEmpty());
        assertNoNativeUnmergedPaths();
        // Let the normal Mark resolved -> staging refresh finish. Deliberately do not
        // call tab.refresh() or reselect WIP: the operation banner should track the new
        // index state as part of the same in-window resolution flow.
        RobotWait.drainGitWork();

        OperationBannerObservation banner = FxHarness.call(() -> {
            Node found = FxHarness.lookupAll(tab.getContent(), "operation-banner").stream()
                    .findFirst().orElse(null);
            if (!(found instanceof javafx.scene.Parent parent)) {
                return new OperationBannerObservation("", false);
            }
            String text = FxHarness.lookupAll(parent, "conflict-banner").stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .map(Label::getText).findFirst().orElse("");
            boolean canContinue = FxHarness.lookupAll(parent, "button").stream()
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .anyMatch(button -> button.getText().equals("Continue") && !button.isDisabled());
            return new OperationBannerObservation(text, canContinue);
        });
        assertTrue(banner.text().contains("Nothing is conflicting"),
                "the banner must read the resolved Git index without F5: " + banner.text());
        assertTrue(banner.continueEnabled(),
                "Continue must become available after Mark resolved without F5");
    }

    @Test
    void aMergeWithNoStagedContentKeepsAnEnabledCommitMergeComposerAction() {
        RobotWait.drainGitWork();
        assertEquals(mainBranch, service.getCurrentBranch());
        chooseBranchAction("feature", "Merge into " + mainBranch);
        RobotWait.until("the merge did not stop on the conflict", () ->
                service.getOperationState().kind() == com.jfx.git.model.OperationState.Kind.MERGING
                        && !service.getStagingState().conflicting().isEmpty());
        selectWip();
        openResolveDialog();
        takeSideAndMarkResolved("Take ours");

        RobotWait.until("Git did not clear the merge conflict", () ->
                service.getStagingState().conflicting().isEmpty());
        assertNoNativeUnmergedPaths();
        RobotWait.drainGitWork();
        assertTrue(service.getStagingState().staged().isEmpty(),
                "taking ours reproduces HEAD, so the merge has no staged content");

        RobotWait.untilOnFx("the empty-content merge composer did not render Commit merge", () ->
                FxHarness.lookupAll(tab.getContent(), "btn-primary").stream()
                        .filter(Button.class::isInstance).map(Button.class::cast)
                        .anyMatch(button -> button.isVisible() && !button.isDisabled()
                                && button.getText().startsWith("Commit merge")));
        Button commitMerge = FxHarness.call(() -> FxHarness.lookupAll(tab.getContent(), "btn-primary").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.isVisible() && button.getText().startsWith("Commit merge"))
                .findFirst().orElse(null));
        assertNotNull(commitMerge);
        TextField message = lookup(".composer-summary").queryAs(TextField.class);
        interact(() -> message.setText("merge feature"));
        RobotWait.untilOnFx("the merge message did not reach the composer",
                () -> "merge feature".equals(message.getText()));
        interact(commitMerge::fire);

        RobotWait.until("the no-content merge did not create a merge commit", () -> {
            if (service.getOperationState().kind() != com.jfx.git.model.OperationState.Kind.NONE) return false;
            NativeGitRunner.Result parents = nativeGit().run("rev-list", "--parents", "-n", "1", "HEAD");
            return parents.isOk() && parents.trimmedStdout().split("\\s+").length == 3;
        });
        NativeGitRunner.Result parents = nativeGit().run("rev-list", "--parents", "-n", "1", "HEAD");
        assertTrue(parents.isOk(), parents.stderr());
        String[] fields = parents.trimmedStdout().split("\\s+");
        assertEquals(3, fields.length, "the merge commit has exactly two parents");
        assertEquals(mainHead, fields[1], "first parent remains main");
        assertEquals(featureHead, fields[2], "second parent is the merged feature");
        assertEquals(mainBranch, service.getCurrentBranch());
    }

    @Test
    void rebaseConflictComposerDoesNotOfferAnOrdinaryMergeCommit() {
        startConflictingRebase();

        RobotWait.untilOnFx("the unresolved conflict and commit composer did not load", () -> {
            boolean conflictLoaded = from(lookup(".staging-conflicted-list"))
                    .lookup("conflict.txt").tryQuery().isPresent();
            boolean composerLoaded = FxHarness.lookupAll(tab.getContent(), "btn-primary").stream()
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .anyMatch(button -> button.isVisible() && button.getText() != null
                            && !button.getText().isBlank());
            return conflictLoaded && composerLoaded;
        });

        RobotWait.untilOnFx("the rebase continuation banner was not shown", () -> {
            Node banner = FxHarness.lookupAll(tab.getContent(), "operation-banner").stream()
                    .findFirst().orElse(null);
            if (!(banner instanceof javafx.scene.Parent parent)) return false;
            String text = FxHarness.lookupAll(parent, "conflict-banner").stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .map(Label::getText).findFirst().orElse("");
            return text.toLowerCase(java.util.Locale.ROOT).contains("rebase")
                    && text.contains("mark it resolved, then continue the rebase");
        });

        java.util.List<String> composerActions = FxHarness.call(() ->
                FxHarness.lookupAll(tab.getContent(), "btn-primary").stream()
                        .filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(Button::isVisible).map(Button::getText).toList());
        assertFalse(composerActions.isEmpty(), "the WIP commit composer must have rendered an action");
        assertFalse(composerActions.stream().anyMatch(text -> text.startsWith("Commit merge")),
                "a stopped rebase's composer cannot invite an ordinary merge commit");
        Button continueButton = from(lookup(".operation-banner")).lookup("Continue")
                .queryAs(Button.class);
        assertTrue(continueButton.isDisabled(), "Git still has an unresolved conflict");
    }

    private void assertNoNativeUnmergedPaths() {
        NativeGitRunner.Result unmerged = nativeGit().run("diff", "--name-only", "--diff-filter=U");
        assertTrue(unmerged.isOk(), unmerged.stderr());
        assertEquals("", unmerged.trimmedStdout(), "Git itself reports no unresolved index entries");
    }

    private NativeGitRunner nativeGit() {
        return new NativeGitRunner(tempDir.toFile());
    }

    private void fireCurrentEnabledContinue() {
        FxHarness.run(() -> {
            Node banner = FxHarness.lookupAll(tab.getContent(), "operation-banner").stream()
                    .findFirst().orElseThrow(() -> new AssertionError("operation banner disappeared"));
            Button current = FxHarness.lookupAll(banner, "button").stream()
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> "Continue".equals(button.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("Continue control disappeared"));
            assertFalse(current.isDisabled(), "the current Continue control is enabled");
            current.fire();
        });
    }

    private record OperationBannerObservation(String text, boolean continueEnabled) {
    }
}
