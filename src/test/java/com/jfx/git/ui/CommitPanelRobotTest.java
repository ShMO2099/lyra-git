package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.core.GitService;

import javafx.scene.Node;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The staging area driven the way a user actually touches it: hover a changed file to reveal
 * its Stage/Unstage button, right-click for Discard, type a message and click Commit, tick
 * Amend and commit again. Every assertion reads real git state afterwards - the index via
 * {@link GitService#getStagingState()}, the working tree via the file on disk, history via a
 * fresh {@code Git.open} - rather than trusting the panel's own optimistic UI update, which
 * moves a row before the background git call it started has even returned.
 */
class CommitPanelRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        // Declared fixture setup: start() is the FX thread, and a JGit commit consults git
        // for the effective identity. See ThreadGuard.buildingFixture().
        try (var _ = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            commit("a.txt", "original a", "add a");
        }

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // A click that starts git work returns before the work does; wait for it, so the
        // backend is not closed - and @TempDir not deleted - under a running command.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private void writeFile(String file, String content) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
    }

    private RevCommit head() throws Exception {
        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD"));
        }
    }

    /** HEAD's full message, as a condition: a repository mid-commit reads as "not yet". */
    private boolean headMessageIs(String expected) {
        try {
            return expected.equals(head().getFullMessage());
        } catch (Exception midCommit) {
            return false;
        }
    }

    /** Whether HEAD has moved off {@code hash}; a repository mid-commit reads as "not yet". */
    private boolean headMovedFrom(String hash) {
        try {
            return !hash.equals(head().getName());
        } catch (Exception midCommit) {
            return false;
        }
    }

    /** A file's content, or null while it is missing or mid-write. */
    private String contentOf(String file) {
        try {
            return Files.readString(tempDir.resolve(file));
        } catch (Exception notThereYet) {
            return null;
        }
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

    /**
     * Opens the staging area by selecting the WIP row through the list's own model rather
     * than a text lookup - the same reasoning {@code MergeConflictRobotTest.selectWipNode}
     * documents: a real null -> row transition is what reliably fires the listener that
     * builds this panel, and the row may not even be realised yet for a text lookup to find.
     *
     * <p>Waits for the panel to show the repository as it is now. The graph reload and the
     * status query behind the panel both run off the FX thread, and matching the lists
     * against real git state, rather than asking whether a staging panel exists at all,
     * is what keeps a panel left over from before the last change from passing for it.
     */
    private void openStagingArea() {
        selectFirstRow();
        var expected = service.getStagingState();
        RobotWait.untilOnFx("the staging area never showed " + expected,
                () -> stagingPanelShows(expected));
        // Built in a callback; laid out on the pulse after it, before anything clicks into it.
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Reloads the graph and selects its first row, whatever that row turns out to be. */
    private void selectFirstRow() {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
    }

    /** Whether both staging lists hold exactly what {@code state} says. Call on the FX thread. */
    private boolean stagingPanelShows(com.jfx.git.model.StagingState state) {
        return java.util.List.copyOf(stagingRows("staging-unstaged-list")).equals(state.unstaged())
                && java.util.List.copyOf(stagingRows("staging-staged-list")).equals(state.staged());
    }

    private java.util.List<?> stagingRows(String styleClass) {
        return FxHarness.lookupAll(tab.getContent(), styleClass).stream()
                .filter(n -> n instanceof ListView<?>)
                .map(n -> ((ListView<?>) n).getItems())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ." + styleClass + " yet"));
    }

    /** The node showing {@code path}, once the list it belongs to has realised it. */
    private Node fileRow(String path) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no row showing " + path + " appeared", () -> {
            row[0] = lookup(path).tryQuery().orElse(null);
            return row[0] != null;
        });
        return row[0];
    }

    /** Selects the row for {@code path} - a real click, revealing its hover-only action button. */
    private void selectFileRow(String path) {
        fileRow(path);
        clickOn(path);
        settle();
    }

    /** Whether a {@code .conflict-banner} label mentioning {@code text} is showing. Call on the FX thread. */
    private boolean bannerShowing(String text) {
        return lookup(".conflict-banner").queryAll().stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Label l
                        && l.getText().contains(text));
    }

    // ------------------------------------------------------------------

    @Test
    void stagingAFileMovesItIntoTheRealIndex() throws Exception {
        writeFile("a.txt", "changed a");
        openStagingArea();
        assertTrue(service.getStagingState().unstaged().contains("a.txt"));

        selectFileRow("a.txt");
        clickOn("Stage");
        RobotWait.until("a.txt was never staged",
                () -> service.getStagingState().staged().contains("a.txt"));

        assertTrue(service.getStagingState().staged().contains("a.txt"),
                "the real index should show a.txt staged");
        assertFalse(service.getStagingState().unstaged().contains("a.txt"));
    }

    @Test
    void detachingHeadShowsTheBannerAndCreateBranchHereRecovers() throws Exception {
        // Detach onto the first commit: real detached state, no branch holding it.
        String first;
        try (Git git = Git.open(tempDir.toFile())) {
            first = git.getRepository().resolve("HEAD").getName();
        }
        assertTrue(service.checkoutBranch(first).isOk());
        interact(() -> tab.refresh());
        // The tree is clean, so there is no WIP row and no staging area to wait for: the
        // first row is a commit. What this test needs is the banner, answered off the FX thread.
        selectFirstRow();

        interact(() -> tab.getRightPanel().updateDetachedHeadBanner());
        RobotWait.untilOnFx("the detached-HEAD banner never appeared",
                () -> bannerShowing("detached HEAD"));
        var banner = lookup(".conflict-banner").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.Label l
                        && l.getText().contains("detached HEAD"))
                .findFirst();
        assertTrue(banner.isPresent(), "the detached-HEAD banner must be showing");

        clickOn("Create branch here");
        RobotWait.untilDialogWithButton("OK");
        // Set the dialog's field directly: typing into a freshly shown dialog window
        // races focus in headless mode (same pattern as SidebarStashTagRobotTest).
        // Target the dialog window itself: the main scene also has .text-field nodes
        // (the graph filter box), and a global lookup grabs those first.
        interact(() -> {
            var dialogRoot = javafx.stage.Window.getWindows().stream()
                    .filter(w -> w.isShowing()
                            && w.getScene() != null
                            && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
                    .map(w -> (javafx.scene.control.DialogPane) w.getScene().getRoot())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no dialog showing"));
            javafx.scene.control.TextInputControl field =
                    (javafx.scene.control.TextInputControl) dialogRoot.lookup(".text-field");
            assertNotNull(field, "dialog has a name field");
            field.setText("rescued-here");
            dialogRoot.getButtonTypes().stream()
                    .filter(bt -> "OK".equals(bt.getText()))
                    .findFirst()
                    .ifPresent(bt -> {
                        var okButton = (javafx.scene.control.Button)
                                dialogRoot.lookupButton(bt);
                        okButton.fire();
                    });
        });
        RobotWait.until("the branch was never created and checked out",
                () -> !service.isHeadDetached() && service.getLocalBranches().stream()
                        .anyMatch(b -> "rescued-here".equals(b.shortName())));

        assertFalse(service.isHeadDetached(), "branching re-attaches HEAD");
        assertTrue(service.getLocalBranches().stream()
                        .anyMatch(b -> "rescued-here".equals(b.shortName())),
                () -> "branches: " + service.getLocalBranches());
    }

    /**
     * {@code service} in this test class is always the JGit engine, which answers
     * {@code canMaterializeLfsContent()} unconditionally false - the deterministic way
     * to trigger the "at risk" condition without depending on this machine's own
     * git-lfs setup, unlike a real check against the native engine.
     */
    @Test
    void anLfsTrackedRepositoryOnAnEngineThatCannotSmudgeShowsTheWarningBanner() throws Exception {
        writeFile(".gitattributes", "*.psd filter=lfs diff=lfs merge=lfs -text\n");
        interact(() -> tab.refresh());
        openStagingArea();

        interact(() -> tab.getRightPanel().updateLfsBanner());
        RobotWait.untilOnFx("the LFS banner never appeared", () -> bannerShowing("Git LFS"));

        var banner = lookup(".conflict-banner").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.Label l
                        && l.getText().contains("Git LFS"))
                .findFirst();
        assertTrue(banner.isPresent(), "the LFS warning banner must be showing");
    }

    @Test
    void aRepositoryWithNoLfsRulesShowsNoLfsBanner() throws Exception {
        writeFile("a.txt", "ordinary file");
        interact(() -> tab.refresh());
        openStagingArea();

        interact(() -> tab.getRightPanel().updateLfsBanner());
        // Nothing to wait for when the answer is "no banner": let the query finish, then
        // let its answer reach the scene, before looking.
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        var banner = lookup(".conflict-banner").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.Label l
                        && l.getText().contains("Git LFS"))
                .findFirst();
        assertFalse(banner.isPresent(), "no .gitattributes rule exists, so nothing is at risk");
    }

    @Test
    void ignoreFromTheChangedFilesMenuWritesTheGitignoreRule() throws Exception {
        writeFile("junk.log", "noisy output");
        openStagingArea();
        assertTrue(service.getStagingState().unstaged().contains("junk.log"));

        // The established context-menu gesture (see SidebarBranchRobotTest): fire the
        // request at the row rather than synthesising a real right-click.
        Node row = fileRow("junk.log");
        interact(() -> Event.fireEvent(row, new javafx.scene.input.ContextMenuEvent(
                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                10, 10, 100, 100, false, null)));
        settle();
        clickOn("Ignore");
        settle();
        clickOn("This file");
        // The rule is written on the git lane.
        RobotWait.until("the .gitignore rule was never written", () -> {
            String rules = contentOf(".gitignore");
            return rules != null && rules.contains("/junk.log");
        });

        Path gitignore = tempDir.resolve(".gitignore");
        assertTrue(Files.exists(gitignore), "the rule should land in the root .gitignore");
        String gitignoreContent;
        try {
            gitignoreContent = Files.readString(gitignore);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertTrue(gitignoreContent.contains("/junk.log"),
                () -> "gitignore: " + gitignoreContent);
    }

    @Test
    void unstagingAFileMovesItBackOutOfTheRealIndex() throws Exception {
        writeFile("a.txt", "changed a");
        assertTrue(service.stageFile("a.txt").isOk());
        openStagingArea();
        assertTrue(service.getStagingState().staged().contains("a.txt"));

        selectFileRow("a.txt");
        clickOn("Unstage");
        RobotWait.until("a.txt was never unstaged",
                () -> !service.getStagingState().staged().contains("a.txt"));

        assertFalse(service.getStagingState().staged().contains("a.txt"),
                "the real index should no longer show a.txt staged");
        assertTrue(service.getStagingState().unstaged().contains("a.txt"));
    }

    @Test
    void discardingChangesRevertsTheRealWorkingTreeFile() throws Exception {
        writeFile("a.txt", "changed a");
        openStagingArea();

        Node row = fileRow("a.txt");
        interact(() -> javafx.event.Event.fireEvent(row, new javafx.scene.input.ContextMenuEvent(
                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
        clickOn("Discard changes");
        // The dialog's own OK, now labelled with its verb - the same words as the menu item.
        clickOn(RobotWait.untilDialogWithButton("OK").lookupButton(javafx.scene.control.ButtonType.OK));
        RobotWait.until("a.txt was never restored", () -> "original a".equals(contentOf("a.txt")));

        assertEquals("original a", Files.readString(tempDir.resolve("a.txt")),
                "discarding should have restored the file to HEAD's content on real disk");
        assertTrue(service.getStagingState().unstaged().isEmpty());
    }

    @Test
    void committingStagedFilesCreatesARealCommit() throws Exception {
        writeFile("b.txt", "b");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();

        Node message = lookup(".composer-message").query();
        clickOn(message);
        write("add b");
        clickOn("Commit 1 file to " + service.getCurrentBranch());
        RobotWait.until("the commit never landed", () -> headMessageIs("add b"));

        RevCommit newHead = head();
        assertEquals("add b", newHead.getFullMessage());
        assertEquals(1, newHead.getParentCount());
        assertEquals("b", Files.readString(tempDir.resolve("b.txt")));
    }

    @Test
    void commitWithABlankMessageDoesNotTouchRealHistory() throws Exception {
        String before = head().getName();
        writeFile("b.txt", "b");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();

        clickOn("Commit 1 file to " + service.getCurrentBranch());
        // Nothing should happen, so there is nothing to wait for; let any commit that
        // wrongly started finish before checking that none did.
        RobotWait.drainGitWork();

        assertEquals(before, head().getName(),
                "an empty commit message must not have let a commit through");
    }

    /** A real, executable {@code pre-commit} hook that always blocks the commit. */
    private void installBlockingPreCommitHook() throws Exception {
        Path hooksDir = tempDir.resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);
        Path hook = hooksDir.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);
    }

    @Test
    void tickingSkipHooksBypassesARealBlockingPreCommitHook() throws Exception {
        installBlockingPreCommitHook();
        writeFile("b.txt", "b");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();

        CheckBox skipHooks = lookup(".check-box").queryAll().stream()
                .map(n -> (CheckBox) n)
                .filter(cb -> "Skip hooks".equals(cb.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("no \"Skip hooks\" checkbox found"));
        clickOn(skipHooks);
        settle();

        Node message = lookup(".composer-message").query();
        clickOn(message);
        write("add b");
        clickOn("Commit 1 file to " + service.getCurrentBranch());
        RobotWait.until("the commit never got past the hook", () -> headMessageIs("add b"));

        RevCommit newHead = head();
        assertEquals("add b", newHead.getFullMessage(),
                "ticking Skip hooks should have let the commit through the real blocking hook");
    }

    @Test
    void amendingReplacesTheLastRealCommitInstead() throws Exception {
        String originalHash = head().getName();
        int parentCount = head().getParentCount();
        writeFile("a-extra.txt", "extra");
        assertTrue(service.stageFile("a-extra.txt").isOk());
        openStagingArea();

        CheckBox amend = (CheckBox) lookup(".check-box").query();
        clickOn(amend);
        settle();
        // Ticking amend with a blank box should have pulled HEAD's own message in rather
        // than leaving the box empty - real evidence the checkbox actually reached the
        // commit path, not just its own visual state.
        Node message = lookup(".composer-message").query();
        assertEquals("add a", ((javafx.scene.control.TextInputControl) message).getText().strip());

        clickOn("Amend commit to " + service.getCurrentBranch());
        // The amended commit keeps HEAD's message, so it is recognised by its new hash.
        RobotWait.until("the amend never landed", () -> headMovedFrom(originalHash));

        RevCommit amended = head();
        assertNotEquals(originalHash, amended.getName(),
                "amending must produce a new commit object, not silently no-op");
        assertEquals(parentCount, amended.getParentCount(),
                "amend replaces HEAD - it must not add a second commit on top");
        assertEquals("extra", Files.readString(tempDir.resolve("a-extra.txt")));
        assertEquals("original a", Files.readString(tempDir.resolve("a.txt")),
                "amending should not disturb a file the amend never touched");
    }

    /**
     * Amend and Skip hooks are one-shot (C1). Both used to survive the commit through
     * the staging snapshot, so the next commit was folded into this one and every later
     * commit skipped the team's hooks.
     */
    @Test
    void amendAndSkipHooksAreClearedAfterASuccessfulCommit() throws Exception {
        String originalHash = head().getName();
        writeFile("a-extra.txt", "extra");
        assertTrue(service.stageFile("a-extra.txt").isOk());
        openStagingArea();

        CheckBox amend = lookup(".check-box").queryAll().stream()
                .map(n -> (CheckBox) n).filter(cb -> "Amend".equals(cb.getText()))
                .findFirst().orElseThrow();
        CheckBox skipHooks = lookup(".check-box").queryAll().stream()
                .map(n -> (CheckBox) n).filter(cb -> "Skip hooks".equals(cb.getText()))
                .findFirst().orElseThrow();
        clickOn(amend);
        clickOn(skipHooks);
        RobotWait.untilOnFx("amend never pulled HEAD's message in", () ->
                !((javafx.scene.control.TextInputControl) lookup(".composer-message").query())
                        .getText().isBlank());

        clickOn("Amend commit to " + service.getCurrentBranch());
        RobotWait.until("the amend never landed", () -> headMovedFrom(originalHash));
        RobotWait.drainGitWork();

        RobotWait.untilOnFx("the rebuilt composer still has Amend or Skip hooks ticked", () ->
                lookup(".check-box").queryAll().stream().map(n -> (CheckBox) n)
                        .filter(cb -> "Amend".equals(cb.getText()) || "Skip hooks".equals(cb.getText()))
                        .noneMatch(CheckBox::isSelected));
    }

    /**
     * Sign off adds git's {@code Signed-off-by:} trailer for whoever commits, after one
     * blank line - and, unlike Amend and Skip hooks, stays ticked for the next commit.
     */
    @Test
    void signOffAddsTheTrailerAndStaysOn() throws Exception {
        writeFile("b.txt", "b");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();

        clickOn("Sign off");
        TextField summary = lookup(".composer-summary").query();
        interact(() -> summary.setText("add b"));
        clickOn("Commit 1 file to " + service.getCurrentBranch());
        String expected = "add b" + NL + NL + "Signed-off-by: Test User <test@example.com>";
        RobotWait.until("the signed-off commit never landed", () -> headMessageIs(expected));
        // As in summaryAndDescriptionFieldsJoinIntoOneCommitMessage: the reload after the
        // commit drops the WIP row, and has to land before the next change brings it back.
        RobotWait.untilOnFx("the reload after the commit never landed",
                () -> !tab.graphList().getItems().get(0).isWipNode());

        writeFile("c.txt", "c");
        assertTrue(service.stageFile("c.txt").isOk());
        interact(() -> tab.refresh());
        RobotWait.untilOnFx("the WIP row never came back",
                () -> tab.graphList().getItems().get(0).isWipNode());
        openStagingArea();
        RobotWait.untilOnFx("Sign off did not survive the commit", () ->
                lookup(".check-box").queryAll().stream().map(n -> (CheckBox) n)
                        .anyMatch(cb -> "Sign off".equals(cb.getText()) && cb.isSelected()));
    }

    /**
     * "Add co-author…" offers people from history - not the committer themselves - and
     * writes the choice into the description as a trailer the user can see.
     */
    @Test
    void addingACoAuthorFromHistoryWritesTheTrailer() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            Files.writeString(tempDir.resolve("pair.txt"), "pair", StandardCharsets.UTF_8);
            git.add().addFilepattern("pair.txt").call();
            git.commit().setMessage("paired work")
                    .setAuthor(new org.eclipse.jgit.lib.PersonIdent("Pat Pair", "pat@example.com"))
                    .call();
        }
        writeFile("b.txt", "b");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();
        TextField summary = lookup(".composer-summary").query();
        TextArea description = lookup(".composer-message").queryAll().stream()
                .filter(n -> n instanceof TextArea).map(n -> (TextArea) n)
                .findFirst().orElseThrow();
        interact(() -> {
            summary.setText("add b");
            description.setText("Worked on together.");
        });

        clickOn("Add co-author…");
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<String> picker =
                (javafx.scene.control.ComboBox<String>) dialog.lookup(".co-author-picker");
        assertEquals(java.util.List.of("Pat Pair <pat@example.com>"), picker.getItems(),
                "the other author is offered; the committer is not");
        interact(() -> picker.getSelectionModel().select(0));
        interact(() -> ((Button) dialog.lookupButton(javafx.scene.control.ButtonType.OK)).fire());
        RobotWait.untilOnFx("the trailer never reached the description",
                () -> description.getText().contains("Co-authored-by"));

        assertEquals("Worked on together." + NL + NL + "Co-authored-by: Pat Pair <pat@example.com>",
                description.getText());
        clickOn("Commit 1 file to " + service.getCurrentBranch());
        String expected = "add b" + NL + NL + "Worked on together." + NL + NL
                + "Co-authored-by: Pat Pair <pat@example.com>";
        RobotWait.until("the co-authored commit never landed", () -> headMessageIs(expected));
    }

    @Test
    void stageAllMovesEveryUnstagedFileIntoTheRealIndex() throws Exception {
        writeFile("a.txt", "changed a");
        writeFile("c.txt", "c");
        openStagingArea();

        clickOn("Stage all");
        RobotWait.until("stage all never reached the index",
                () -> service.getStagingState().unstaged().isEmpty());

        var state = service.getStagingState();
        assertTrue(state.staged().containsAll(java.util.List.of("a.txt", "c.txt")));
        assertTrue(state.unstaged().isEmpty());
    }

    @Test
    void unstageAllMovesEverythingBackOutOfTheRealIndex() throws Exception {
        writeFile("a.txt", "changed a");
        assertTrue(service.stageFile("a.txt").isOk());
        openStagingArea();

        clickOn("Unstage all");
        RobotWait.until("unstage all never reached the index",
                () -> service.getStagingState().staged().isEmpty());

        var state = service.getStagingState();
        assertTrue(state.staged().isEmpty());
        assertTrue(state.unstaged().contains("a.txt"));
    }

    // ------------------------------------------------------------------
    // Panel gestures: multi-select discard, trash-all, file filter,
    // summary/description split
    // ------------------------------------------------------------------

    /** The line separator git messages use, spelled without escapes so this file stays simple. */
    private static final String NL = "" + (char) 10;

    @Test
    void discardSelectedFromTheUnstagedListRestoresEverySelectedFile() throws Exception {
        writeFile("a.txt", "changed a");
        writeFile("new-file.txt", "brand new");
        openStagingArea();
        var state = service.getStagingState();
        assertTrue(state.unstaged().contains("a.txt"));
        assertTrue(state.unstaged().contains("new-file.txt"));

        @SuppressWarnings("unchecked") // the lookup yields Nodes; the list's type is known
        ListView<String> unstagedList = lookup(".sidebar-list").queryAll().stream()
                .map(n -> (ListView<String>) n)
                .filter(lv -> lv.getItems().contains("a.txt"))
                .findFirst().orElseThrow(() -> new AssertionError("unstaged list not found"));
        interact(() -> unstagedList.getSelectionModel().selectRange(0, unstagedList.getItems().size()));
        settle();

        Node row = fileRow("a.txt");
        interact(() -> Event.fireEvent(row, new javafx.scene.input.ContextMenuEvent(
                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                10, 10, 100, 100, false, null)));
        settle();

        clickOn("Discard selected (2 files)");
        clickOn(RobotWait.untilDialogWithButton("OK").lookupButton(javafx.scene.control.ButtonType.OK));
        // The two files are discarded one after the other on the git lane.
        RobotWait.until("the selected files were never discarded",
                () -> !Files.exists(tempDir.resolve("new-file.txt"))
                        && "original a".equals(contentOf("a.txt")));

        assertEquals("original a", Files.readString(tempDir.resolve("a.txt")),
                "the tracked modification must be restored");
        assertFalse(Files.exists(tempDir.resolve("new-file.txt")),
                "the untracked file must be deleted, as single-file discard would");
        assertTrue(service.getStagingState().unstaged().isEmpty(),
                () -> "still unstaged: " + service.getStagingState().unstaged());
    }

    @Test
    void trashIconBesideTheWipNodeDiscardsEverything() throws Exception {
        writeFile("a.txt", "changed a");
        writeFile("scratch.tmp", "junk");
        openStagingArea();
        assertFalse(service.getStagingState().unstaged().isEmpty());

        clickOn(".wip-discard");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Discard all");
        RobotWait.until("the working tree was never discarded",
                () -> !Files.exists(tempDir.resolve("scratch.tmp")) && !service.hasUncommittedChanges());

        assertEquals("original a", Files.readString(tempDir.resolve("a.txt")),
                "tracked changes must be restored");
        assertFalse(Files.exists(tempDir.resolve("scratch.tmp")),
                "untracked files must be removed");
        assertFalse(service.hasUncommittedChanges(), "the work tree must end up clean");
    }

    @Test
    void filterBarNarrowsTheWorkspaceTreeToMatchingFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("deep/leaf"));
        Files.writeString(tempDir.resolve("deep/leaf/needle-file.txt"), "content");
        assertTrue(service.stageFile("deep/leaf/needle-file.txt").isOk());
        assertTrue(service.commit("add needle", false).isOk());
        // A pending change keeps the WIP row alive, so the staging area (with its
        // All-files toggle) is what row 0 selects.
        writeFile("a.txt", "changed a");
        openStagingArea();

        clickOn("All files");
        settle();
        // .workspace-filter, not .file-filter-field: the working-tree lists have their
        // own filter now and share the styling class with this one.
        TextField filter = lookup(".workspace-filter").query();
        assertNotNull(filter, "the filter field must exist once the tree is showing");

        interact(() -> filter.setText("needle-fil"));
        settle();
        var filteredCells = treeCellTexts();
        assertTrue(filteredCells.stream().anyMatch(t -> t.contains("needle-file.txt")),
                () -> "the matching file must stay visible; cells: " + filteredCells);
        assertTrue(filteredCells.stream().noneMatch(t -> t.endsWith("a.txt")),
                () -> "non-matching files must be filtered out; cells: " + filteredCells);
        var matchBadge = lookup(".count-badge").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.Label l
                        && l.getText() != null && l.getText().contains("match"))
                .findFirst();
        assertTrue(matchBadge.isPresent(), "a match count must be displayed");

        interact(() -> filter.setText(""));
        // Clearing puts the lazy tree back, whose top level is read on the git lane.
        RobotWait.untilOnFx("the full tree never came back",
                () -> treeCellTexts().stream().anyMatch(t -> t.endsWith("a.txt")));
        var restoredCells = treeCellTexts();
        assertTrue(restoredCells.stream().anyMatch(t -> t.endsWith("a.txt")),
                () -> "clearing the filter restores the full tree; cells: " + restoredCells);
    }

    /** Cell texts of the workspace tree, glyph prefix and all. */
    private java.util.List<String> treeCellTexts() {
        return lookup(".tree-cell").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.Labeled)
                .map(n -> ((javafx.scene.control.Labeled) n).getText())
                .map(t -> t == null ? "" : t)
                .toList();
    }

    @Test
    void selectingSeveralCommitsShowsTheirEndToEndComparison() throws Exception {
        commit("b.txt", "b", "add b");
        commit("c.txt", "c", "add c");
        interact(() -> tab.refresh());
        awaitGraphTop("add c");
        // Clean tree: no WIP row, so rows 0..2 are the three commits, newest first.
        interact(() -> {
            var sm = tab.graphList().getSelectionModel();
            sm.clearSelection();
            sm.selectRange(0, 3);
        });
        settle();

        var title = lookup(".mode-header-title").queryAll().stream()
                .map(n -> ((javafx.scene.control.Labeled) n).getText())
                .filter(t -> t != null)
                .findFirst().orElse("");
        assertEquals("Comparing", title, () -> "panel header after multi-select: " + title);
        var subtitle = lookup(".mode-header-subject").queryAll().stream()
                .map(n -> ((javafx.scene.control.Labeled) n).getText())
                .filter(t -> t != null)
                .findFirst().orElse("");
        assertTrue(subtitle.contains("3 commits"),
                () -> "three commits selected, header should say so; was: " + subtitle);

        // Back to one commit: details as before, no comparison view left behind.
        interact(() -> {
            var sm = tab.graphList().getSelectionModel();
            sm.clearSelection();
            sm.select(0);
        });
        settle();
        var singleTitle = lookup(".mode-header-title").queryAll().stream()
                .map(n -> ((javafx.scene.control.Labeled) n).getText())
                .filter(t -> t != null)
                .findFirst().orElse("");
        assertNotEquals("Comparing", singleTitle,
                "a single selection must not keep the comparison view");
    }

    @Test
    void searchHighlightsMatchesInPlaceInsteadOfFilteringByDefault() throws Exception {
        commit("b.txt", "x", "gibberish zebra subject");
        interact(() -> tab.refresh());
        awaitGraphTop("gibberish zebra subject");
        String newest = head().getName();
        String oldest;
        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            oldest = walk.parseCommit(head().getParent(0)).getId().getName();
        }

        int before = tab.graphList().getItems().size();
        TextField search = lookup(".search-field").query();
        interact(() -> search.setText("zebra"));
        settle();

        assertEquals(before, tab.graphList().getItems().size(),
                "highlight mode keeps every commit in the graph");
        assertTrue(tab.graphHighlightForTest().isActive(), "the fade must be live");
        assertFalse(tab.graphHighlightForTest().isFaded(newest), "the match stays bright");
        assertTrue(tab.graphHighlightForTest().isFaded(oldest), "non-matches dim");

        // Opt in to the old behaviour: the list actually narrows.
        clickOn("filter");
        settle();
        int narrowed = tab.graphList().getItems().size();
        assertTrue(narrowed < before,
                () -> "filter mode narrows the list; before=" + before + " after=" + narrowed);
        assertFalse(tab.graphHighlightForTest().isFaded(oldest),
                "with filtering there is nothing left to dim");

        // Clearing the search hands the fade back entirely.
        clickOn("filter");
        interact(() -> search.clear());
        settle();
        assertFalse(tab.graphHighlightForTest().isActive(),
                "no query, no selection-driven state change: fade follows the selection");
    }

    @Test
    void graphKeyboardNavigationMovesAlongRealCommitsOnly() throws Exception {
        commit("b.txt", "b", "second");
        commit("c.txt", "c", "third");
        interact(() -> tab.refresh());
        awaitGraphTop("third");
        var lv = tab.graphList();
        int rowCount = lv.getItems().size();
        assertTrue(rowCount >= 3, () -> "three commits expected, got " + rowCount);

        String[] current = new String[1];

        interact(() -> lv.getSelectionModel().clearAndSelect(0));
        settle();
        String newest = hashAt(0);
        String oldest = hashAt(rowCount - 1);
        assertNotNull(oldest);

        // J/K stepping: down one real commit at a time, never off the ends.
        interact(() -> tab.navigateRows(1));
        interact(() -> current[0] = selectedGraphHash());
        assertEquals(hashAt(1), current[0], "one step down lands on the next commit");
        interact(() -> tab.navigateRows(-1));
        interact(() -> current[0] = selectedGraphHash());
        assertEquals(newest, current[0], "one step up returns to the newest");

        // Ctrl+Home / Ctrl+End equivalents land on the extremes.
        interact(() -> tab.navigateToEdge(false));
        interact(() -> current[0] = selectedGraphHash());
        assertEquals(oldest, current[0]);
        interact(() -> tab.navigateToEdge(true));
        interact(() -> current[0] = selectedGraphHash());
        assertEquals(newest, current[0]);

        // H/L walk edges, not rows: from the root commit H has nowhere to go,
        // L walks straight up the chain to its nearest child.
        interact(() -> lv.getSelectionModel().clearAndSelect(rowCount - 1));
        settle();
        interact(() -> tab.navigateToChild());
        interact(() -> current[0] = selectedGraphHash());
        assertEquals(hashAt(rowCount - 2), current[0],
                "the child of the oldest commit is the one above it");

        // Named-point jumps: only the newest commit carries a branch here.
        interact(() -> tab.navigateRows(1));
        interact(() -> tab.navigateToNextRef(false));
        interact(() -> current[0] = selectedGraphHash());
        assertEquals(newest, current[0], "jumping to the previous named commit finds the branch");
    }

    /** Waits for a reload to put {@code subject} at the top of the graph; it loads off the FX thread. */
    private void awaitGraphTop(String subject) {
        RobotWait.untilOnFx("the graph never showed \"" + subject + "\" at the top", () -> {
            GitGraphRow top = tab.graphList().getItems().get(0);
            return top.getCommit() != null && subject.equals(top.getCommit().getSubject());
        });
    }

    private String selectedGraphHash() {
        GitGraphRow row = tab.graphList().getSelectionModel().getSelectedItem();
        return row != null && row.getCommit() != null ? row.getCommit().getHash() : "";
    }

    /** The hash of the real commit at {@code idx}, or null for a synthetic row. */
    private String hashAt(int idx) {
        GitGraphRow row = tab.graphList().getItems().get(idx);
        return row != null && row.getCommit() != null ? row.getCommit().getHash() : null;
    }
}
