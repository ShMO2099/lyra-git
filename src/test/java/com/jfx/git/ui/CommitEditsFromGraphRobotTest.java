package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.ui.dialogs.RewordDialog;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reword, drop and move, straight from a commit's menu in the graph.
 *
 * <p>Each is checked against the real history it leaves, not against the plan that was
 * built: {@code RebaseTodoTest} already covers the plans, and what that cannot show is
 * that the menu item on a row reaches the right commit, that the range is taken from the
 * right place - one commit lower for "Move down" and for dropping the newest - and that
 * the files the commits carried are still what the working tree holds afterwards.
 */
class CommitEditsFromGraphRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());

        commit("root.txt", "root", "root commit");
        commit("a.txt", "a", "add a");
        commit("b.txt", "b", "add b\n\nWhy b: a body that has to survive.\n");
        commit("c.txt", "c", "add c");

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
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
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private List<String> logSubjects() {
        List<String> subjects = new java.util.ArrayList<>();
        for (var row : service.getCommitGraphPage(50, GraphOrder.current()).rows()) {
            if (!row.isWipNode() && row.getCommit() != null) {
                subjects.add(row.getCommit().getSubject());
            }
        }
        return subjects;
    }

    private boolean logIs(String... subjects) {
        try {
            return List.of(subjects).equals(logSubjects()) && !rebaseInProgress();
        } catch (RuntimeException midRewrite) {
            return false;
        }
    }

    private boolean rebaseInProgress() {
        return Files.isDirectory(tempDir.resolve(".git/rebase-merge"))
                || Files.isDirectory(tempDir.resolve(".git/rebase-apply"));
    }

    private String messageOf(String subject) throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            for (var commit : git.log().call()) {
                if (commit.getShortMessage().equals(subject)) return commit.getFullMessage();
            }
        }
        throw new AssertionError("no commit with the subject " + subject);
    }

    /** The context menu of the row showing {@code subject}, opened so its items are up to date. */
    private ContextMenu menuOn(String subject) {
        RobotWait.untilOnFx("no row for \"" + subject + "\"", () -> lookup(subject).tryQuery().isPresent());
        clickOn(subject);
        Node row = lookup(subject).query();
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
        ContextMenu[] menu = new ContextMenu[1];
        RobotWait.untilOnFx("no context menu opened on \"" + subject + "\"", () -> {
            menu[0] = (ContextMenu) Window.getWindows().stream()
                    .filter(w -> w.isShowing() && w instanceof ContextMenu).findFirst().orElse(null);
            return menu[0] != null;
        });
        // The menu asks the repository where the commit sits when it opens, and enables
        // its items from the answer.
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        return menu[0];
    }

    private static MenuItem item(ContextMenu menu, String text) {
        return menu.getItems().stream().filter(i -> text.equals(i.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + text + "\" among "
                        + menu.getItems().stream().map(MenuItem::getText).toList()));
    }

    /** Fires an item and closes its menu; the dialog it may open is waited for by the caller. */
    private void choose(ContextMenu menu, String text) {
        MenuItem chosen = item(menu, text);
        interact(() -> assertFalse(chosen.isDisable(), text + " should be available here"));
        javafx.application.Platform.runLater(() -> {
            menu.hide();
            chosen.fire();
        });
    }

    /** Presses the button that reads {@code button} - its label, which a confirmation's verb replaces "OK" with. */
    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(((Button) pane.lookupButton(bt)).getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no \"" + button + "\" button; has "
                            + pane.getButtonTypes().stream()
                                    .map(bt -> ((Button) pane.lookupButton(bt)).getText()).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    // ------------------------------------------------------------------

    @Test
    void rewordingACommitChangesItsMessageAndKeepsEverythingElse() throws Exception {
        choose(menuOn("add b"), "Reword…");
        DialogPane dialog = RobotWait.untilDialogWithButton("Reword");
        interact(() -> {
            TextField summary = (TextField) dialog.lookup("#" + RewordDialog.SUMMARY_ID);
            TextArea description = (TextArea) dialog.lookup("#" + RewordDialog.DESCRIPTION_ID);
            assertEquals("add b", summary.getText());
            assertEquals("Why b: a body that has to survive.", description.getText(),
                    "the dialog opens on the whole message, body included, not the subject the row shows");
            ButtonType reword = dialog.getButtonTypes().stream()
                    .filter(bt -> "Reword".equals(bt.getText())).findFirst().orElseThrow();
            assertTrue(dialog.lookupButton(reword).isDisabled(), "nothing to do until the message changes");
            summary.setText("add b, properly");
        });
        press(dialog, "Reword");

        RobotWait.until("the reword never landed", () -> logIs("add c", "add b, properly", "add a", "root commit"));
        assertEquals("add b, properly\n\nWhy b: a body that has to survive.",
                messageOf("add b, properly").strip());
        for (String file : List.of("root.txt", "a.txt", "b.txt", "c.txt")) {
            assertTrue(Files.exists(tempDir.resolve(file)), file + " should be untouched by a reword");
        }
    }

    @Test
    void droppingACommitInTheMiddleRemovesItAndItsChanges() {
        choose(menuOn("add b"), "Drop commit…");
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        press(confirm, "Drop commit");

        RobotWait.until("the drop never landed", () -> logIs("add c", "add a", "root commit"));
        assertFalse(Files.exists(tempDir.resolve("b.txt")), "the dropped commit's file goes with it");
        assertTrue(Files.exists(tempDir.resolve("c.txt")), "the commit above it is replayed");
        assertTrue(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void cancellingTheDropLeavesHistoryAlone() {
        choose(menuOn("add b"), "Drop commit…");
        press(RobotWait.untilDialogWithButton("OK"), "Cancel");
        RobotWait.drainGitWork();

        assertEquals(List.of("add c", "add b", "add a", "root commit"), logSubjects());
    }

    /**
     * The newest commit is alone in its own range, and a plan that drops its only step
     * replays nothing. The range is taken one commit lower instead.
     */
    @Test
    void droppingTheNewestCommitWorksToo() {
        choose(menuOn("add c"), "Drop commit…");
        press(RobotWait.untilDialogWithButton("OK"), "Drop commit");

        RobotWait.until("the newest commit was never dropped", () -> logIs("add b", "add a", "root commit"));
        assertFalse(Files.exists(tempDir.resolve("c.txt")));
        assertTrue(Files.exists(tempDir.resolve("b.txt")));
    }

    @Test
    void movingACommitUpSwapsItWithTheOneAboveIt() {
        choose(menuOn("add a"), "Move up");

        RobotWait.until("the commit never moved up", () -> logIs("add c", "add a", "add b", "root commit"));
        for (String file : List.of("a.txt", "b.txt", "c.txt")) {
            assertTrue(Files.exists(tempDir.resolve(file)), "reordering loses no changes: " + file);
        }
    }

    /** Down means swapping with the parent, so the range has to start at the parent. */
    @Test
    void movingACommitDownSwapsItWithTheOneBelowIt() {
        choose(menuOn("add c"), "Move down");

        RobotWait.until("the commit never moved down", () -> logIs("add b", "add c", "add a", "root commit"));
        for (String file : List.of("a.txt", "b.txt", "c.txt")) {
            assertTrue(Files.exists(tempDir.resolve(file)), "reordering loses no changes: " + file);
        }
    }

    @Test
    void theNewestCommitCannotMoveUpAndSaysSo() {
        choose(menuOn("add c"), "Move up");
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("add c", "add b", "add a", "root commit"), logSubjects(),
                "there is nothing above the newest commit to change places with");
    }

    /**
     * The commit above the root would change places with the root, which cannot be
     * replayed onto anything. Refused, with history as it was.
     */
    @Test
    void aCommitCannotMoveBelowTheFirstCommit() {
        choose(menuOn("add a"), "Move down");
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("add c", "add b", "add a", "root commit"), logSubjects());
    }

    /** The first commit has nothing to be replayed onto, so none of the four is offered for it. */
    @Test
    void theFirstCommitOffersNoneOfThem() {
        ContextMenu menu = menuOn("root commit");
        interact(() -> {
            for (String text : List.of("Reword…", "Drop commit…", "Move up", "Move down")) {
                assertTrue(item(menu, text).isDisable(), text + " cannot apply to the first commit");
            }
            menu.hide();
        });
    }

    /** With several rows selected they would have to guess which one was meant. */
    @Test
    void theyAreHiddenWhileSeveralCommitsAreSelected() {
        clickOn("add c");
        press(KeyCode.SHIFT);
        clickOn("add a");
        release(KeyCode.SHIFT);
        Node row = lookup("add b").query();
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            ContextMenu menu = (ContextMenu) Window.getWindows().stream()
                    .filter(w -> w.isShowing() && w instanceof ContextMenu).findFirst().orElseThrow();
            for (String text : List.of("Reword…", "Drop commit…", "Move up", "Move down")) {
                assertFalse(item(menu, text).isVisible(), text + " acts on one commit");
            }
            menu.hide();
        });
    }

    // ------------------------------------------------------------------
    // As a patch
    // ------------------------------------------------------------------

    /** One commit copied as a patch is that commit's patch, on the clipboard. */
    @Test
    void aCommitIsCopiedAsAPatchFromItsMenu() {
        ContextMenu menu = menuOn("add b");
        interact(() -> {
            assertEquals("Save as patch…", item(menu, "Save as patch…").getText());
            menu.hide();
            item(menu, "Copy as patch").fire();
        });
        RobotWait.drainGitWork();

        RobotWait.untilOnFx("the patch never reached the clipboard", () -> {
            String text = javafx.scene.input.Clipboard.getSystemClipboard().getString();
            return text != null && text.contains("Subject: [PATCH] add b")
                    && text.contains("Why b: a body that has to survive.");
        });
    }

    /** With several selected, the items say how many, and act on all of them. */
    @Test
    void severalSelectedCommitsAreCopiedAsOnePatchInTheOrderTheyWereMade() {
        clickOn("add c");
        press(KeyCode.SHIFT);
        clickOn("add a");
        release(KeyCode.SHIFT);
        Node row = lookup("add b").query();
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            ContextMenu menu = (ContextMenu) Window.getWindows().stream()
                    .filter(w -> w.isShowing() && w instanceof ContextMenu).findFirst().orElseThrow();
            assertTrue(menu.getItems().stream().anyMatch(i -> "Save 3 commits as patch…".equals(i.getText())),
                    menu.getItems().stream().map(MenuItem::getText).toList().toString());
            menu.hide();
            item(menu, "Copy 3 commits as patch").fire();
        });
        RobotWait.drainGitWork();

        RobotWait.untilOnFx("the patches never reached the clipboard", () -> {
            String text = javafx.scene.input.Clipboard.getSystemClipboard().getString();
            if (text == null) return false;
            int a = text.indexOf("Subject: [PATCH] add a");
            int b = text.indexOf("Subject: [PATCH] add b");
            int c = text.indexOf("Subject: [PATCH] add c");
            return a >= 0 && a < b && b < c;
        });
    }
}
