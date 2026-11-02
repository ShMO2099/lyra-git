package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.graph.GraphOrder;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Squashing driven the way commercial clients do it: shift-click a contiguous run of rows in the
 * graph, right-click, "Squash N commits into one…", edit or accept the message, land on a
 * real single commit. {@code InteractiveRebaseDialogTest} and {@code
 * InteractiveRebaseRobotTest} already prove {@code RebaseTodo.squashing} and the reword
 * combo box work; this is the seam neither covers — that a real multi-selection in the
 * graph's own {@code ListView} reaches {@link GitGraphCell#squashSelected} and comes out
 * the other side as a real rewritten history, and that the contiguous-selection rule is
 * actually enforced by the menu rather than only by {@code RebaseTodo}.
 */
class SquashFromGraphRobotTest extends ApplicationTest {

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

        commit("root.txt", "root", "root commit");
        commit("a.txt", "a", "add a");
        commit("b.txt", "b", "add b");
        commit("c.txt", "c", "add c");
        commit("d.txt", "d", "add d");

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() {
        // The squash is a history rewrite on the git lane; it must be finished before the
        // backend closes and @TempDir deletes the repository under it.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(javafx.stage.Window.getWindows())
                .forEach(javafx.stage.Window::hide));
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

    /** HEAD's full commit message - unlike the subject line, this shows whether a squash's
     *  several messages actually landed in the one resulting commit. */
    private String headMessage() throws Exception {
        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD")).getFullMessage();
        }
    }

    /** {@link #headMessage}, as a condition: a repository mid-rewrite reads as "not yet". */
    private boolean headMessageIs(String expected) {
        try {
            return expected.equals(headMessage());
        } catch (Exception midRewrite) {
            return false;
        }
    }

    /** Whether a rebase is still part-way through - it leaves its state directory until done. */
    private boolean rebaseInProgress() {
        return Files.isDirectory(tempDir.resolve(".git/rebase-merge"))
                || Files.isDirectory(tempDir.resolve(".git/rebase-apply"));
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

    /** Shift-clicks from {@code fromSubject} to {@code toSubject}, selecting the run between them. */
    private void selectRange(String fromSubject, String toSubject) {
        clickOn(fromSubject);
        settle();
        press(KeyCode.SHIFT);
        clickOn(toSubject);
        release(KeyCode.SHIFT);
        settle();
    }

    /** Ctrl-clicks {@code subject} onto whatever is already selected, without touching the run between. */
    private void addToSelection(String subject) {
        press(KeyCode.CONTROL);
        clickOn(subject);
        release(KeyCode.CONTROL);
        settle();
    }

    /** Opens the context menu on the row showing {@code subject}, without changing the selection. */
    private void openMenuOn(String subject) {
        Node row = lookup(subject).query();
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    /** The context menu {@code openMenuOn} just opened, as a real object rather than a rendered Node. */
    private ContextMenu openContextMenu() {
        return (ContextMenu) Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof ContextMenu)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no context menu is currently showing"));
    }

    // ------------------------------------------------------------------

    @Test
    void squashingThreeContiguousCommitsFoldsThemIntoOneRealCommit() throws Exception {
        selectRange("add d", "add b");

        openMenuOn("add d");
        clickOn("Squash 3 commits into one…");
        RobotWait.untilDialogWithButton("Squash");

        // The dialog pre-fills the combined message and the button starts enabled, so
        // accepting it as-is is itself the case worth proving: the default is usable.
        clickOn("Squash 3 commits");
        RobotWait.until("the squash never landed",
                () -> List.of("add b", "add a", "root commit").equals(logSubjects()));

        List<String> after = logSubjects();
        assertEquals(List.of("add b", "add a", "root commit"), after,
                "b, c and d should have folded into one commit, leaving one entry where "
                        + "three used to be: " + after);
        assertEquals("add b\n\nadd c\n\nadd d", headMessage(),
                "the resulting commit's full message - not just its subject line - should "
                        + "carry every folded commit's message, oldest first");
        assertTrue(Files.exists(tempDir.resolve("b.txt")));
        assertTrue(Files.exists(tempDir.resolve("c.txt")));
        assertTrue(Files.exists(tempDir.resolve("d.txt")),
                "all three folded commits' real changes should survive in the working tree");
    }

    @Test
    void editingTheMessageBeforeConfirmingUsesTheEditedMessage() throws Exception {
        selectRange("add d", "add c");

        openMenuOn("add d");
        clickOn("Squash 2 commits into one…");
        // Waited for, and searched within: the dialog opens a round trip after the click,
        // and a global lookup(".text-area") ran before it existed.
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("Squash");

        Node summary = dialog.lookup(".message-summary");
        Node description = dialog.lookup(".message-description");
        clickOn(summary);
        // Clear the pre-filled combined message and replace it, the way a user editing
        // it down to what the single commit should say actually would.
        interact(() -> {
            ((javafx.scene.control.TextInputControl) summary).clear();
            ((javafx.scene.control.TextInputControl) description).clear();
        });
        write("squash c and d together");
        clickOn("Squash 2 commits");
        // The finished state, not just the message: the rebase words the surviving commit
        // first and folds the next one into it afterwards, so a detached HEAD already
        // carries the new message mid-rebase - while the branch still points at the old
        // history the assertions below would then read.
        RobotWait.until("the edited squash never landed",
                () -> headMessageIs("squash c and d together") && !rebaseInProgress());

        assertEquals("squash c and d together", headMessage(),
                "editing the dialog's pre-filled message must actually reach the real "
                        + "commit, not just JGit's own auto-generated concatenation of the "
                        + "original messages");
        List<String> after = logSubjects();
        assertFalse(after.contains("add c"));
        assertFalse(after.contains("add d"));
    }

    /**
     * G15. A graph row carries only its subject, so the squash message has to come from
     * somewhere that has the whole messages - and a body that never arrived must not read
     * as an empty one, or the combined message quietly loses it.
     */
    @Test
    void squashingCommitsWithBodiesKeepsEveryBody() throws Exception {
        commit("e.txt", "e", "add e\n\nWhy e: the first reason.\nAnd a second line.\n");
        commit("f.txt", "f", "add f\n\nWhy f: another reason.\n");
        interact(() -> tab.refresh());
        RobotWait.until("the new commits never reached the graph",
                () -> lookup("add f").tryQuery().isPresent());

        selectRange("add f", "add e");
        openMenuOn("add f");
        clickOn("Squash 2 commits into one…");
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("Squash");

        String expected = "add e\n\nWhy e: the first reason.\nAnd a second line.\n\n"
                + "add f\n\nWhy f: another reason.";
        String prefilled = com.jfx.git.ui.MessageEditor.join(
                ((javafx.scene.control.TextInputControl) dialog.lookup(".message-summary")).getText(),
                ((javafx.scene.control.TextInputControl) dialog.lookup(".message-description")).getText());
        assertEquals(expected, prefilled,
                "the dialog should start from every folded commit's whole message, bodies included");

        clickOn("Squash 2 commits");
        RobotWait.until("the squash never landed",
                () -> headMessageIs(expected) && !rebaseInProgress());
        assertEquals(expected, headMessage());
    }

    @Test
    void aGapInTheSelectionLeavesSquashDisabledAndHistoryUntouched() throws Exception {
        List<String> before = logSubjects();

        // "add d" and "add b" without "add c" in between: a real gap, not a run.
        clickOn("add d");
        settle();
        addToSelection("add b");

        openMenuOn("add d");
        ContextMenu menu = openContextMenu();
        MenuItem squash = menu.getItems().stream()
                .filter(i -> "Squash (select commits with no gaps between them)".equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the menu should explain why squash is "
                        + "unavailable rather than just hiding it, since more than one commit "
                        + "is genuinely selected"));
        assertTrue(squash.isDisable(), "a gapped selection must not be squashable");

        // Close without acting, and confirm nothing about the real repository moved.
        interact(menu::hide);
        settle();

        assertEquals(before, logSubjects(), "a disabled menu item must not have changed real history");
    }
}
