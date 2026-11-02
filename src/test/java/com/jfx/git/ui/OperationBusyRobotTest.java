package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.ActivityLog;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphCell;
import com.jfx.git.graph.GitGraphRow;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operations started from the tab end what they started: the status bar's busy indicator
 * comes down however the operation went, and the activity log records what happened to it.
 *
 * <p>The fixture: {@code main} with one commit; {@code side} two commits ahead of it, each
 * adding a file; {@code topic} one commit ahead that is merged nowhere.
 */
class OperationBusyRobotTest extends ApplicationTest {

    @TempDir
    Path root;

    private Path work;
    private GitService service;
    private RepoTab tab;
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void start(Stage stage) throws Exception {
        work = root.resolve("work");
        try (var fixture = ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Synthetic QA");
            config.setString("user", null, "email", "qa@example.test");
            config.setBoolean("core", null, "autocrlf", false);
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            commit(git, "a.txt", "first");
            git.checkout().setCreateBranch(true).setName("side").call();
            commit(git, "b.txt", "side one");
            commit(git, "c.txt", "side two");
            git.checkout().setName("main").call();
            git.checkout().setCreateBranch(true).setName("topic").call();
            commit(git, "d.txt", "topic work");
            git.checkout().setName("main").call();
        }
        service = new GitService();
        service.openRepository(work.toFile());
        tab = new RepoTab(new LyraApp(), "operations", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    private void commit(Git git, String file, String subject) throws Exception {
        Files.writeString(work.resolve(file), subject + "\n", StandardCharsets.UTF_8);
        git.add().addFilepattern(file).call();
        git.commit().setMessage(subject).call();
    }

    @BeforeEach
    void loadTheTab() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
    }

    @AfterEach
    void tidyUp() {
        release.countDown();
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Cherry-picking several commits with a follow-up
    // ------------------------------------------------------------------

    /**
     * Picking two commits and folding the second into the first is two phases - the pick,
     * then a rebase over what it made - and each used to raise the busy indicator, while
     * only the second lowered it: the bar went on saying something was running.
     */
    @Test
    void aCherryPickWithAFollowUpRebaseLeavesNothingBusy() {
        RobotWait.untilOnFx("the graph never showed the side commits",
                () -> cellShowing("side one") != null && cellShowing("side two") != null);
        GitGraphCell cell = FxHarness.call(() -> cellShowing("side two"));
        interact(() -> {
            var selection = tab.graphList().getSelectionModel();
            selection.clearSelection();
            selection.select(rowShowing("side one"));
            selection.select(rowShowing("side two"));
            Event.fireEvent(cell, new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null));
        });
        ContextMenu menu = FxHarness.call(cell::getContextMenu);
        RobotWait.untilOnFx("the commit menu never opened", menu::isShowing);
        RobotWait.drainGitWork();
        MenuItem pickBoth = FxHarness.call(() -> menu.getItems().stream()
                .filter(item -> "Cherry-pick 2 commits…".equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Cherry-pick 2 commits in the menu")));
        // runLater, not interact: the item opens the editor, whose nested event loop would
        // hold an interact open.
        Platform.runLater(() -> {
            menu.hide();
            pickBoth.fire();
        });
        DialogPane editor = RobotWait.untilDialogWithButton("Apply 2 commits");
        interact(() -> {
            @SuppressWarnings("unchecked")
            ListView<Integer> steps = (ListView<Integer>) editor.lookup(".rebase-steps");
            assertNotNull(steps, "the editor lists the commits");
            // Oldest first: the second is folded into the first.
            steps.getSelectionModel().select(1);
            Event.fireEvent(steps, new KeyEvent(KeyEvent.KEY_PRESSED, "s", "s", KeyCode.S,
                    false, false, false, false));
        });
        press(editor, "Apply 2 commits");

        RobotWait.until("the picked commits never landed on main",
                () -> Files.exists(work.resolve("b.txt")) && Files.exists(work.resolve("c.txt")));
        RobotWait.until("the follow-up never folded the two into one", () -> headSubject().contains("side one"));
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the cherry-pick left the bar busy", () -> tab.getStatusBar().busyDepth() == 0);
    }

    // ------------------------------------------------------------------
    // Cancelled before it started
    // ------------------------------------------------------------------

    /**
     * Cancel stops the work the status bar shows, and work still waiting behind another is
     * then dropped without running - which also meant without the callback that lowers the
     * busy indicator, so the bar spun for the rest of the session.
     */
    @Test
    void anOperationCancelledBeforeItStartsLeavesNothingBusy() throws Exception {
        Files.writeString(work.resolve("new.txt"), "new\n", StandardCharsets.UTF_8);
        holdTheLane();
        interact(() -> tab.getRightPanel().stageAllFiles());
        interact(() -> {
            assertEquals(1, tab.getStatusBar().busyDepth(), "staging is waiting behind the held lane");
            ((Button) tab.getStatusBar().lookup(".status-cancel")).fire();
        });
        release.countDown();
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the cancelled operation left the bar busy", () -> tab.getStatusBar().busyDepth() == 0);
    }

    /**
     * The toolbar runs one thing at a time and refuses the rest until it is done; done
     * never came for work dropped before it started, so the toolbar said "Fetching is
     * still running" to every button for the rest of the session.
     */
    @Test
    void aToolbarOperationCancelledBeforeItStartsFreesTheToolbar() throws Exception {
        holdTheLane();
        interact(() -> tab.getToolbar().fetchAll());
        interact(() -> {
            assertTrue(tab.getToolbar().isRunning(), "the fetch is waiting behind the held lane");
            ((Button) tab.getStatusBar().lookup(".status-cancel")).fire();
        });
        release.countDown();
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the toolbar still refuses everything", () -> !tab.getToolbar().isRunning());
    }

    /** Holds the tab's repository lane until {@link #release}. */
    private void holdTheLane() throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        GitTaskRunner.run(tab.getGitService(), () -> {
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GitResult.ok("held");
        }, r -> { });
        held.await(10, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // A failure shown in a dialog
    // ------------------------------------------------------------------

    /**
     * Deleting a branch merged nowhere is refused, and the refusal is shown as the question
     * whether to force it. Declined, nothing was deleted: the log must not say it was.
     */
    @Test
    void aRefusalAnsweredInADialogIsLoggedAsFailed() {
        interact(() -> tab.getSidebar().deleteBranchWithUndo("topic", false));
        // The question keeps the OK button type and names the button after the action.
        DialogPane question = RobotWait.untilDialogWithButton(ButtonType.OK.getText());
        press(question, ButtonType.CANCEL.getText());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the delete never finished", () -> tab.getStatusBar().busyDepth() == 0);
        ActivityLog.Entry delete = FxHarness.call(() -> {
            List<ActivityLog.Entry> deletes = ActivityLog.entries().stream()
                    .filter(entry -> "Deleting topic".equals(entry.label())).toList();
            return deletes.isEmpty() ? null : deletes.getLast();
        });
        assertNotNull(delete, "the delete is in the activity log");
        assertEquals(ActivityLog.Outcome.FAILED, delete.outcome(), "git refused it, and nothing was deleted");
    }

    // ------------------------------------------------------------------
    // A branch made from the detached-HEAD banner
    // ------------------------------------------------------------------

    /**
     * "Create branch here" on the detached-HEAD banner makes a branch and checks it out,
     * which moves HEAD: the whole tab has to follow. It refreshed the staging panel alone,
     * which redraws the rest only when the tree is clean - with work in progress the banner
     * went on saying HEAD was detached, beside a graph and a sidebar without the branch.
     */
    @Test
    void aBranchMadeFromTheDetachedHeadBannerRedrawsTheTab() throws Exception {
        String first;
        try (Git git = Git.open(work.toFile())) {
            first = git.getRepository().resolve("main").getName();
        }
        assertTrue(service.checkoutBranch(first).isOk());
        Files.writeString(work.resolve("a.txt"), "work in progress\n", StandardCharsets.UTF_8);
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
        });
        RobotWait.drainGitWork();
        interact(() -> tab.graphList().getSelectionModel().select(0));
        RobotWait.untilOnFx("the detached-HEAD banner never appeared", () -> detachedBannerShowing());

        interact(() -> FxHarness.lookupAll(tab.getContent(), "button").stream()
                .filter(n -> n instanceof Button b && "Create branch here".equals(b.getText()))
                .map(n -> (Button) n)
                .findFirst().orElseThrow(() -> new AssertionError("no Create branch here"))
                .fire());
        DialogPane prompt = RobotWait.untilDialogWithButton(ButtonType.OK.getText());
        interact(() -> {
            javafx.scene.control.TextInputControl field =
                    (javafx.scene.control.TextInputControl) prompt.lookup(".text-field");
            assertNotNull(field, "the prompt has a name field");
            field.setText("rescued");
        });
        press(prompt, "Create and check out");

        RobotWait.until("the branch was never made", () -> !service.isHeadDetached());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the banner still says HEAD is detached", () -> !detachedBannerShowing());
    }

    /**
     * Ending a bisect checks the original branch out again, and the banner, the graph and
     * the sidebar all have to follow - not only the staging panel, which redraws the rest
     * only when the tree is clean.
     */
    @Test
    void endingABisectRedrawsTheTab() throws Exception {
        String head;
        try (Git git = Git.open(work.toFile())) {
            head = git.getRepository().resolve("main").getName();
        }
        assertTrue(service.bisectStart().isOk());
        service.bisectMarkBad(head);
        Files.writeString(work.resolve("a.txt"), "work in progress\n", StandardCharsets.UTF_8);
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the bisect banner never appeared", () -> buttonLabelled("End bisect") != null);

        interact(() -> buttonLabelled("End bisect").fire());

        RobotWait.until("the bisect never ended", () -> !service.bisectStatus().active());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the bisect banner is still up", () -> buttonLabelled("End bisect") == null);
    }

    /** The button on the tab reading {@code text}, or null. Call on FX. */
    private Button buttonLabelled(String text) {
        return FxHarness.lookupAll(tab.getContent(), "button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(n -> (Button) n)
                .findFirst().orElse(null);
    }

    /** Whether the detached-HEAD banner is on screen. Call on FX. */
    private boolean detachedBannerShowing() {
        return FxHarness.lookupAll(tab.getContent(), "conflict-banner").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Label l && l.isVisible()
                        && l.getScene() != null && l.getText() != null && l.getText().contains("detached HEAD"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The graph cell currently showing the commit with {@code subject}, or null. Call on FX. */
    private GitGraphCell cellShowing(String subject) {
        for (Node node : tab.graphList().lookupAll(".list-cell")) {
            if (node instanceof GitGraphCell cell && cell.getItem() != null && !cell.getItem().isSynthetic()
                    && cell.getItem().getCommit() != null
                    && subject.equals(cell.getItem().getCommit().getSubject())) {
                return cell;
            }
        }
        return null;
    }

    /** The graph row of the commit with {@code subject}. Call on FX. */
    private GitGraphRow rowShowing(String subject) {
        return tab.graphList().getItems().stream()
                .filter(row -> !row.isSynthetic() && row.getCommit() != null
                        && subject.equals(row.getCommit().getSubject()))
                .findFirst().orElseThrow(() -> new AssertionError("no row for " + subject));
    }

    /** HEAD's whole message, or "" while git is writing it. */
    private String headSubject() {
        try (Git git = Git.open(work.toFile())) {
            var head = git.getRepository().resolve("HEAD");
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                return walk.parseCommit(head).getFullMessage();
            }
        } catch (Exception midWrite) {
            return "";
        }
    }

    /** Presses the button that reads {@code button}. */
    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(((Button) pane.lookupButton(bt)).getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no \"" + button + "\" button; has "
                            + pane.getButtonTypes().stream()
                                    .map(bt -> ((Button) pane.lookupButton(bt)).getText()).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();
    }
}
