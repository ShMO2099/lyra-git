package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.BranchIssueKey;
import com.jfx.git.core.CommitMessages;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryPreferences;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commit box's friction removers, driven through the real controls: the summary
 * counter, recalling earlier messages, a draft that outlives the tab, the issue key from
 * the branch name, committing and pushing in one go, and the checks that stop a token or
 * a key from being committed by accident.
 *
 * <p>The repository has a real bare remote the branch tracks, so "pushed" is read from
 * that remote's refs, not from what the panel said it did.
 */
class CommitBoxRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private Path remote;
    private Stage stage;
    private GitService service;
    private RepoTab tab;
    private final List<RepoTab> ownedTabs = new ArrayList<>();

    /** Built from parts, so this file does not look like a leaked token to a scanner. */
    private static final String TOKEN = "gh" + "p_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        // Each test its own message store; the drafts and history are per repository and
        // these repositories are temporary, but the file is not.
        CommitMessages.useFile(tempDir.resolve("commit-messages.json"));

        repo = tempDir.resolve("repo");
        remote = tempDir.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).call().close();
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(repo.toFile());

        // Declared fixture setup: start() is the FX thread. See ThreadGuard.buildingFixture().
        try (var _ = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            Files.writeString(repo.resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("a.txt").isOk());
            assertTrue(service.commit("the pushed commit", false).isOk());
            try (Git git = Git.open(repo.toFile())) {
                String branch = git.getRepository().getBranch();
                git.remoteAdd().setName("origin").setUri(new URIish(remote.toUri().toURL())).call();
                git.push().setRemote("origin").add(branch).call();
                var config = git.getRepository().getConfig();
                config.setString("branch", branch, "remote", "origin");
                config.setString("branch", branch, "merge", "refs/heads/" + branch);
                config.save();
            }
        }
        showNewTab();
    }

    /** A fresh tab on the same repository, as reopening it would make. Call on the FX thread. */
    private void showNewTab() {
        tab = new RepoTab(new LyraApp(), "test", service);
        ownedTabs.add(tab);
        stage.setScene(new Scene(new TabPane(tab), 1200, 760));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (!ownedTabs.isEmpty()) {
            interact(() -> ownedTabs.forEach(RepoTab::close));
        } else if (service != null) {
            service.close();
        }
        interact(() -> {
            AppPreferences.setPushAfterCommit(false);
            AppPreferences.setIssueKeyFromBranch(true);
            AppPreferences.setIssueKeyPattern(null);
            AppPreferences.setIssueKeyFormat(null);
            AppPreferences.setCheckSecretsBeforeCommit(true);
            AppPreferences.setCheckLargeFilesBeforeCommit(true);
            // Static FX state, reset on the FX thread; see RightPanelManager.COMMIT_SETTINGS.
            RightPanelManager.commitSettingsChanged();
            new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide);
        });
        CommitMessages.useFile(null);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void write(String file, String content) throws Exception {
        Path path = repo.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /** Selects the WIP row and waits for a composer showing the repository as it is now. */
    private void openStagingArea() {
        var expected = service.getStagingState();
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("the staging area never showed " + expected, () ->
                tab.graphList().getItems().get(0).isWipNode()
                        && lookup(".composer-summary").tryQuery().isPresent()
                        && commitButton().getText().equals(expectedLabel(expected)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** What the commit button reads for {@code state} without push-after-commit. */
    private String expectedLabel(com.jfx.git.model.StagingState state) {
        int staged = state.staged().size();
        if (staged == 0) return "Nothing staged";
        String where = " to " + state.branch();
        return AppPreferences.pushAfterCommit() ? "Commit and push" + where
                : "Commit " + staged + (staged == 1 ? " file" : " files") + where;
    }

    private TextField summary() {
        return lookup(".composer-summary").queryAs(TextField.class);
    }

    private TextArea description() {
        return (TextArea) lookup(".composer-message").queryAll().stream()
                .filter(n -> n instanceof TextArea).findFirst()
                .orElseThrow(() -> new AssertionError("no description area"));
    }

    private Label counter() {
        return lookup(".composer-counter").queryAs(Label.class);
    }

    private Button commitButton() {
        return (Button) lookup(".btn-primary").queryAll().stream()
                .filter(n -> n instanceof Button b && b.getText() != null
                        && (b.getText().startsWith("Commit") || b.getText().startsWith("Nothing")
                        || b.getText().startsWith("Amend")))
                .findFirst().orElseThrow(() -> new AssertionError("no commit button"));
    }

    /** A key press as the summary field receives it. */
    private void pressInSummary(KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        interact(() -> Event.fireEvent(summary(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false)));
    }

    private String head() throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            ObjectId id = git.getRepository().resolve("HEAD");
            return id == null ? null : id.getName();
        }
    }

    private String headMessage() {
        try (Git git = Git.open(repo.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD")).getFullMessage();
        } catch (Exception midCommit) {
            return null;
        }
    }

    /** Where the remote's copy of the current branch points, or null. */
    private String remoteHead() {
        try (Git bare = Git.open(remote.toFile()); Git local = Git.open(repo.toFile())) {
            ObjectId id = bare.getRepository().resolve("refs/heads/" + local.getRepository().getBranch());
            return id == null ? null : id.getName();
        } catch (Exception notYet) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // The summary counter
    // ------------------------------------------------------------------

    @Test
    void theCounterCountsDownFromSeventyTwoAndGoesRedPastIt() throws Exception {
        write("b.txt", "b\n");
        openStagingArea();
        interact(() -> {
            assertEquals("72", counter().getText(), "an empty summary has all 72 left");
            assertTrue(counter().isVisible());

            summary().setText("x".repeat(10));
            assertEquals("62", counter().getText());
            assertFalse(counter().getStyleClass().contains("composer-counter-over"));

            summary().setText("x".repeat(80));
            assertEquals("-8", counter().getText());
            assertTrue(counter().getStyleClass().contains("composer-counter-over"), "past the limit: red");

            summary().setText("x".repeat(72));
            assertEquals("0", counter().getText());
            assertFalse(counter().getStyleClass().contains("composer-counter-over"), "at the limit is fine");
        });
    }

    @Test
    void aLongSummaryIsStillCommitted() throws Exception {
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();
        String longSubject = "y".repeat(90);
        interact(() -> summary().setText(longSubject));
        interact(() -> commitButton().fire());
        RobotWait.until("a summary over 72 characters was not committed",
                () -> longSubject.equals(headMessage()));
    }

    // ------------------------------------------------------------------
    // Message history and drafts
    // ------------------------------------------------------------------

    @Test
    void altUpRecallsEarlierMessagesAndAltDownComesBack() throws Exception {
        CommitMessages.remember(service.getDirectory(), "older message");
        CommitMessages.remember(service.getDirectory(), "newer message\n\nwith a body");
        write("b.txt", "b\n");
        openStagingArea();
        interact(() -> summary().setText("what I was typing"));

        pressInSummary(KeyCode.UP, false, false, true);
        assertEquals("newer message", summary().getText());
        assertEquals("with a body", description().getText());

        pressInSummary(KeyCode.UP, false, false, true);
        assertEquals("older message", summary().getText());
        assertEquals("", description().getText());

        pressInSummary(KeyCode.UP, false, false, true);
        assertEquals("older message", summary().getText(), "the oldest stays put");

        pressInSummary(KeyCode.DOWN, false, false, true);
        pressInSummary(KeyCode.DOWN, false, false, true);
        assertEquals("what I was typing", summary().getText(), "and past the newest is the user's own text");
    }

    @Test
    void theHistoryMenuPutsAPickedMessageInTheComposer() throws Exception {
        CommitMessages.remember(service.getDirectory(), "remembered subject\n\nremembered body");
        write("b.txt", "b\n");
        openStagingArea();

        Button history = lookup(".composer-history").queryAs(Button.class);
        interact(history::fire);
        RobotWait.untilOnFx("the history menu never opened",
                () -> lookup("remembered subject").tryQuery().isPresent());
        clickOn("remembered subject");
        RobotWait.untilOnFx("picking a message never filled the composer",
                () -> "remembered subject".equals(summary().getText())
                        && "remembered body".equals(description().getText()));
    }

    @Test
    void aCommittedMessageJoinsTheHistory() throws Exception {
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();
        interact(() -> summary().setText("add b"));
        interact(() -> commitButton().fire());
        RobotWait.until("the commit never landed", () -> "add b".equals(headMessage()));
        RobotWait.drainGitWork();

        assertEquals("add b", CommitMessages.history(service.getDirectory()).get(0));
        assertEquals("", CommitMessages.draft(service.getDirectory()), "and its draft is gone");
    }

    @Test
    void anUnsentMessageComesBackWhenTheTabIsReopened() throws Exception {
        write("b.txt", "b\n");
        openStagingArea();
        interact(() -> {
            summary().setText("half written");
            description().setText("and its body");
        });
        RobotWait.drainGitWork();
        interact(() -> stage.hide());

        interact(this::showNewTab);
        openStagingArea();
        assertEquals("half written", summary().getText());
        assertEquals("and its body", description().getText());
    }

    /** A commit git refused keeps what was typed: there is nothing to retype it from. */
    @Test
    void aFailedCommitKeepsTheMessage() throws Exception {
        Path hooks = repo.resolve(".git").resolve("hooks");
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);

        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());
        String before = head();
        openStagingArea();
        interact(() -> {
            summary().setText("blocked by the hook");
            description().setText("still here afterwards");
        });
        interact(() -> commitButton().fire());
        RobotWait.drainGitWork();

        assertEquals(before, head(), "the hook refused the commit");
        interact(() -> {
            assertEquals("blocked by the hook", summary().getText());
            assertEquals("still here afterwards", description().getText());
        });
        assertTrue(CommitMessages.history(service.getDirectory()).isEmpty(), "and nothing was remembered as sent");
    }

    /**
     * A repository that skips hooks by default, Skip hooks unticked to run them this once,
     * and a commit they refuse. The refusal refreshed the whole tab and the refresh put the
     * default back, so the box was ticked again and the next press committed past the very
     * hook that had just said no.
     */
    @Test
    void aCommitTheHooksRefusedKeepsSkipHooksUnticked() throws Exception {
        RepositoryPreferences.setSkipHooksByDefault(repo.toFile(), true);
        try {
            Path hooks = repo.resolve(".git").resolve("hooks");
            Files.createDirectories(hooks);
            Path hook = hooks.resolve("pre-commit");
            Files.writeString(hook, "#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8);
            hook.toFile().setExecutable(true);
            write("b.txt", "b\n");
            assertTrue(service.stageFile("b.txt").isOk());
            String before = head();
            openStagingArea();
            interact(() -> {
                assertTrue(skipHooksBox().isSelected(), "the repository's default");
                skipHooksBox().fire();
                summary().setText("run the hooks this time");
            });

            interact(() -> commitButton().fire());
            RobotWait.drainGitWork();
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(before, head(), "the hook refused the commit");
            interact(() -> assertFalse(skipHooksBox().isSelected(), "the refusal left the box as it was"));

            // The user fixes what the hook complained about. The save reaches the panel the
            // way the tab's watcher sends it: the staging panel is read again, in place.
            write("c.txt", "c\n");
            interact(() -> tab.getRightPanel().showStagingArea());
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the save never reached the panel", () -> lookup("c.txt").tryQuery().isPresent());
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> assertFalse(skipHooksBox().isSelected(), "and so did the refresh after the save"));

            interact(() -> commitButton().fire());
            RobotWait.drainGitWork();
            assertEquals(before, head(), "pressing again runs the hooks again");
        } finally {
            RepositoryPreferences.setSkipHooksByDefault(repo.toFile(), false);
        }
    }

    /** The Skip hooks box, found by its text. */
    private javafx.scene.control.CheckBox skipHooksBox() {
        return lookup(".check-box").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.CheckBox cb && "Skip hooks".equals(cb.getText()))
                .map(n -> (javafx.scene.control.CheckBox) n)
                .findFirst().orElseThrow(() -> new AssertionError("no Skip hooks checkbox"));
    }

    // ------------------------------------------------------------------
    // The issue key
    // ------------------------------------------------------------------

    @Test
    void anEmptySummaryStartsWithTheBranchsIssueKey() throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setCreateBranch(true).setName("feature/ABC-123-login").call();
        }
        write("b.txt", "b\n");
        openStagingArea();
        RobotWait.untilOnFx("the summary never started with the issue key",
                () -> "ABC-123: ".equals(summary().getText()));

        // Typed text is never replaced; a different format applies to an empty one.
        interact(() -> {
            AppPreferences.setIssueKeyFormat("[" + BranchIssueKey.KEY_TOKEN + "] ");
            RightPanelManager.commitSettingsChanged();
        });
        assertEquals("[ABC-123] ", summary().getText(), "only the key was there, so it follows the setting");
        interact(() -> summary().setText("[ABC-123] fix the login"));
        interact(() -> {
            AppPreferences.setIssueKeyFromBranch(false);
            RightPanelManager.commitSettingsChanged();
        });
        assertEquals("[ABC-123] fix the login", summary().getText(), "typed text is the user's");
    }

    /**
     * Leaving the working tree and coming back builds the box again from the message it
     * held. That message used to pass through the join that strips it, so the key came back
     * as "ABC-123:" - no longer the text the box put there, so to the box it was typed: the
     * space after it was gone, and ticking Amend no longer filled in HEAD's message.
     */
    @Test
    void theIssueKeyComesBackExactlyAfterLeavingTheWorkingTree() throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setCreateBranch(true).setName("feature/ABC-123-login").call();
        }
        write("b.txt", "b\n");
        openStagingArea();
        RobotWait.untilOnFx("the summary never started with the issue key",
                () -> "ABC-123: ".equals(summary().getText()));

        leaveAndComeBack();
        RobotWait.drainGitWork();
        assertEquals("ABC-123: ", summary().getText(), "the key, space and all, as the box put it there");

        interact(() -> amendBox().fire());
        RobotWait.untilOnFx("ticking Amend over the key alone never filled in HEAD's message",
                () -> "the pushed commit".equals(summary().getText()));
    }

    /**
     * After an amend the box starts again with the branch's key, and the key alone is still
     * an empty box. Unticking Amend after the commit put the key back while the old key was
     * remembered, and the reset that followed forgot it: the next Amend took the key for
     * typed text and filled in nothing.
     */
    @Test
    void afterAnAmendTheKeyAloneIsStillAnEmptyBox() throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setCreateBranch(true).setName("feature/ABC-123-login").call();
        }
        write("a.txt", "a, amended\n");
        assertTrue(service.stageFile("a.txt").isOk());
        // Left unstaged, so the working tree is still there to show after the commit.
        write("c.txt", "c\n");
        openStagingArea();
        RobotWait.untilOnFx("the summary never started with the issue key",
                () -> "ABC-123: ".equals(summary().getText()));
        interact(() -> amendBox().fire());
        RobotWait.untilOnFx("ticking Amend never filled in HEAD's message",
                () -> "the pushed commit".equals(summary().getText()));

        String before = head();
        interact(() -> commitButton().fire());
        RobotWait.until("the amend never landed", () -> !before.equals(headOrNull()));
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the emptied box never got the key back",
                () -> "ABC-123: ".equals(summary().getText()) && !amendBox().isSelected());

        interact(() -> amendBox().fire());
        RobotWait.untilOnFx("ticking Amend over the key alone never filled in HEAD's message",
                () -> "the pushed commit".equals(summary().getText()));
    }

    /** Shows the newest commit, then the working tree again: the commit box is built afresh. */
    private void leaveAndComeBack() {
        interact(() -> tab.getRightPanel().showCommitDetails(tab.graphList().getItems().stream()
                .map(row -> row.getCommit())
                .filter(commit -> commit != null)
                .findFirst().orElseThrow()));
        RobotWait.untilOnFx("the commit's details never replaced the commit box",
                () -> lookup(".composer-summary").tryQuery().isEmpty());
        openStagingArea();
    }

    // ------------------------------------------------------------------
    // Commit and push
    // ------------------------------------------------------------------

    @Test
    void theButtonSaysCommitAndPushWhenTheSettingIsOn() throws Exception {
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();
        String branch = service.getCurrentBranch();
        assertEquals("Commit 1 file to " + branch, commitButton().getText());

        interact(() -> {
            AppPreferences.setPushAfterCommit(true);
            RightPanelManager.commitSettingsChanged();
        });
        assertEquals("Commit and push to " + branch, commitButton().getText(),
                "the setting applies to the box on screen at once");

        interact(() -> {
            AppPreferences.setPushAfterCommit(false);
            RightPanelManager.commitSettingsChanged();
        });
        assertEquals("Commit 1 file to " + branch, commitButton().getText());
    }

    @Test
    void ctrlShiftEnterCommitsAndPushes() throws Exception {
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());
        openStagingArea();
        interact(() -> summary().setText("add b and push it"));

        pressInSummary(KeyCode.ENTER, true, true, false);

        RobotWait.until("the commit never landed", () -> "add b and push it".equals(headMessage()));
        String committed = head();
        RobotWait.until("the commit was never pushed", () -> committed.equals(remoteHead()));
    }

    @Test
    void anAmendOfAPushedCommitIsNotPushed() throws Exception {
        String pushed = remoteHead();
        write("a.txt", "a, amended\n");
        assertTrue(service.stageFile("a.txt").isOk());
        openStagingArea();
        interact(() -> {
            AppPreferences.setPushAfterCommit(true);
            RightPanelManager.commitSettingsChanged();
        });
        interact(() -> amendBox().fire());
        RobotWait.untilOnFx("ticking Amend never filled in HEAD's message",
                () -> "the pushed commit".equals(summary().getText()));
        interact(() -> commitButton().fire());
        RobotWait.until("the amend never landed", () -> !pushed.equals(headOrNull()));
        RobotWait.drainGitWork();

        assertEquals(pushed, remoteHead(), "an amend of a published commit needs a force push, never done for you");
    }

    private String headOrNull() {
        try {
            return head();
        } catch (Exception e) {
            return null;
        }
    }

    /** The Amend box, found by its text. */
    private javafx.scene.control.CheckBox amendBox() {
        return lookup(".check-box").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.CheckBox cb && "Amend".equals(cb.getText()))
                .map(n -> (javafx.scene.control.CheckBox) n)
                .findFirst().orElseThrow(() -> new AssertionError("no Amend checkbox"));
    }

    // ------------------------------------------------------------------
    // Checks before committing
    // ------------------------------------------------------------------

    @Test
    void aStagedTokenStopsTheCommitAndUnstagingItCommitsTheRest() throws Exception {
        write("b.txt", "b\n");
        write("config/secrets.txt", "token=" + TOKEN + "\n");
        assertTrue(service.stageFile("b.txt").isOk());
        assertTrue(service.stageFile("config/secrets.txt").isOk());
        String before = head();
        openStagingArea();
        interact(() -> summary().setText("add b"));
        interact(() -> commitButton().fire());

        var dialog = RobotWait.untilDialogWithButton("Commit anyway");
        assertEquals(before, head(), "nothing is committed while the question is open");
        assertTrue(dialog.getHeaderText().contains("secret"), dialog.getHeaderText());

        ToggleButton unstage = (ToggleButton) dialog.lookup(".commit-checks-unstage");
        assertEquals("Unstage", unstage.getText(), "the row names the file; its button says what it does");
        assertEquals("Unstage config/secrets.txt", unstage.getAccessibleText(),
                "and the remedy offered is for the file holding the token");
        interact(unstage::fire);
        Button commit = (Button) dialog.lookup(".commit-checks-commit");
        assertEquals("Commit", commit.getText(), "every finding is dealt with, so it is no longer \"anyway\"");
        interact(commit::fire);

        RobotWait.until("the commit never landed", () -> "add b".equals(headMessage()));
        RobotWait.drainGitWork();
        try (Git git = Git.open(repo.toFile())) {
            var files = new java.util.ArrayList<String>();
            try (var walk = new org.eclipse.jgit.treewalk.TreeWalk(git.getRepository())) {
                walk.addTree(git.getRepository().parseCommit(git.getRepository().resolve("HEAD")).getTree());
                walk.setRecursive(true);
                while (walk.next()) files.add(walk.getPathString());
            }
            assertEquals(java.util.List.of("a.txt", "b.txt"), files, "the secret stayed out of the commit");
        }
        assertTrue(Files.exists(repo.resolve("config/secrets.txt")), "and on disk");
    }

    @Test
    void cancellingTheChecksCommitsNothingAndKeepsTheMessage() throws Exception {
        write(".env", "API_KEY=1\n");
        assertTrue(service.stageFile(".env").isOk());
        String before = head();
        openStagingArea();
        interact(() -> summary().setText("add env"));
        interact(() -> commitButton().fire());

        var dialog = RobotWait.untilDialogWithButton("Commit anyway");
        interact(() -> ((Button) dialog.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire());
        RobotWait.drainGitWork();

        assertEquals(before, head());
        assertEquals("add env", summary().getText());
    }

    @Test
    void commitAnywayCommitsEverything() throws Exception {
        write(".env", "API_KEY=1\n");
        assertTrue(service.stageFile(".env").isOk());
        openStagingArea();
        interact(() -> summary().setText("add env on purpose"));
        interact(() -> commitButton().fire());

        var dialog = RobotWait.untilDialogWithButton("Commit anyway");
        interact(() -> ((Button) dialog.lookup(".commit-checks-commit")).fire());
        RobotWait.until("Commit anyway never committed", () -> "add env on purpose".equals(headMessage()));
    }

    @Test
    void turnedOffTheChecksDoNotAsk() throws Exception {
        interact(() -> AppPreferences.setCheckSecretsBeforeCommit(false));
        write(".env", "API_KEY=1\n");
        assertTrue(service.stageFile(".env").isOk());
        openStagingArea();
        interact(() -> summary().setText("no questions asked"));
        interact(() -> commitButton().fire());
        RobotWait.until("the commit never landed", () -> "no questions asked".equals(headMessage()));
    }
}
