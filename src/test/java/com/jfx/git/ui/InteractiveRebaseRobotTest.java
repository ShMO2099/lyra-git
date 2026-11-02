package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RebaseTodo;
import com.jfx.git.graph.GraphOrder;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;

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
 * Interactive rebase, driven from a real right-click through to a real rewritten history.
 *
 * <h2>What this adds over the existing dialog tests</h2>
 * {@code InteractiveRebaseDialogTest} already exercises {@code RebaseTodo} exhaustively —
 * every action, every reorder, every validity rule — by building the dialog directly and
 * calling {@code .fire()} on its controls. That proves the plan logic is right. It cannot
 * prove that right-clicking a real commit in the graph opens this dialog at all, that a
 * mouse click on the action {@link ComboBox} does what {@code .fire()} did, or that
 * confirming the dialog reaches {@link GitService#rebaseInteractive} and rewrites a real
 * repository. This is that seam, covered once per action rather than combinatorially —
 * the combinatorics already have a home.
 *
 * <h2>Reaching the row's context menu under Monocle Headless</h2>
 * As with the graph's column menu, a robot right-click does not become a
 * {@code ContextMenuEvent} under Monocle Headless. The event is fired directly on the row,
 * which is what {@code ListCell.setContextMenu} itself listens for — from there every
 * click, including the one on "Interactive rebase from here…", is a real robot click.
 */
class InteractiveRebaseRobotTest extends ApplicationTest {

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

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // The rebase is a history rewrite on the git lane; it must be finished before the
        // backend closes and @TempDir deletes the repository under it.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(javafx.stage.Window.getWindows())
                .forEach(javafx.stage.Window::hide));
    }

    /**
     * Commits {@code root}, {@code a}, {@code b}, {@code c} in order. {@code root} exists
     * only so {@code a} is not the repository's first commit — an interactive rebase
     * refuses to start at the root, since there is nothing to replay it onto.
     */
    private void seedFourCommits() throws Exception {
        commit("root.txt", "root", "root commit");
        commit("a.txt", "a", "add a");
        commit("b.txt", "b", "add b");
        commit("c.txt", "c", "add c");
        interact(() -> tab.refresh());
        graphRow("add c");
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
     * The graph row showing {@code subject}. Waits for it: the rows come from a refresh
     * that runs off the FX thread.
     */
    private Node graphRow(String subject) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no row showing \"" + subject + "\" appeared", () -> {
            row[0] = lookup(subject).tryQuery().orElse(null);
            return row[0] != null;
        });
        return row[0];
    }

    /**
     * Opens the context menu on the graph row showing {@code subject}, clicks {@code item},
     * and waits for the rebase dialog - the one thing every item this suite chooses opens.
     * It appears only after the commit range has been read on the git lane.
     */
    private void chooseFromRowMenu(String subject, String item) {
        Node row = graphRow(subject);
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
        Node menuItem = from(lookup(".context-menu")).lookup(item).query();
        clickOn(menuItem);
        RobotWait.untilDialogWithButton("Start rebase");
    }

    /**
     * The action {@link ComboBox} for the row whose commit subject is {@code subject}.
     *
     * <p>Rows are laid out oldest-first as a horizontal strip per commit; the message
     * label and the action combo box are siblings within it, so the row is found by its
     * message and the combo box by walking to its sibling.
     */
    @SuppressWarnings("unchecked")
    private ComboBox<RebaseTodo.Action> actionBoxFor(String subject) {
        Node root = lookup(".rebase-heading").query().getScene().getRoot();
        for (Node label : FxHarness.lookupAll(root, "rebase-message")) {
            if (label instanceof Label l && subject.equals(l.getText())) {
                Node rowBox = l.getParent();
                for (Node child : ((javafx.scene.layout.HBox) rowBox).getChildrenUnmodifiable()) {
                    if (child instanceof ComboBox<?> box) {
                        return (ComboBox<RebaseTodo.Action>) box;
                    }
                }
            }
        }
        throw new AssertionError("no rebase row found for " + subject);
    }

    private void setAction(String subject, String actionToken) {
        ComboBox<?> box = actionBoxFor(subject);
        clickOn(box);
        settle();
        // In the open picker: the key legend above the rows has the same words.
        clickOn(from(lookup(".combo-box-popup")).lookup(actionToken).queryAs(Node.class));
        settle();
    }

    /**
     * The row's own up/down buttons, in that order.
     *
     * <p>Scoped to the dialog's scene root, the same way {@link #actionBoxFor} is. The
     * graph the dialog was opened over is a second, still-showing window with a row of
     * its own carrying the same commit subject text — an unscoped {@code lookup(subject)}
     * can find that row instead of the dialog's, and then never find a
     * {@code rebase-move} button because that style class only exists inside the dialog.
     */
    private List<Node> moveButtonsFor(String subject) {
        Node root = lookup(".rebase-heading").query().getScene().getRoot();
        for (Node label : FxHarness.lookupAll(root, "rebase-message")) {
            if (label instanceof Label l && subject.equals(l.getText())) {
                List<Node> moves = new java.util.ArrayList<>();
                for (Node child : ((javafx.scene.layout.HBox) l.getParent())
                        .getChildrenUnmodifiable()) {
                    if (child.getStyleClass().contains("rebase-move")) {
                        moves.add(child);
                    }
                }
                return moves;
            }
        }
        throw new AssertionError("no rebase row found for " + subject);
    }

    /**
     * Confirms the dialog. The rebase then runs on the git lane, so each caller waits for
     * the history it expects: the branch only moves to the rewritten commits when the
     * rebase finishes, so the old history stays visible until it has.
     */
    private void clickStart() {
        clickOn("Start rebase");
    }

    // ------------------------------------------------------------------

    @Test
    void rightClickingACommitOpensTheRebaseDialogWithTheRealRange() throws Exception {
        seedFourCommits();

        chooseFromRowMenu("add a", "Interactive rebase from here…");

        assertNotNull(lookup(".rebase-heading").tryQuery().orElse(null),
                "the dialog should have opened");
        assertNotNull(lookup("add a").tryQuery().orElse(null));
        assertNotNull(lookup("add b").tryQuery().orElse(null));
        assertNotNull(lookup("add c").tryQuery().orElse(null),
                "the range should run from the chosen commit to HEAD");
    }

    @Test
    void cancellingLeavesTheRealHistoryUntouched() throws Exception {
        seedFourCommits();
        List<String> before = logSubjects();

        chooseFromRowMenu("add a", "Interactive rebase from here…");
        clickOn("Cancel");
        // Nothing should run, so there is nothing to wait for; let anything that wrongly
        // started finish before checking that nothing did.
        RobotWait.drainGitWork();

        assertEquals(before, logSubjects(), "cancelling must not touch the repository at all");
    }

    @Test
    void droppingACommitRemovesItFromTheRealHistory() throws Exception {
        seedFourCommits();

        chooseFromRowMenu("add a", "Interactive rebase from here…");
        setAction("add b", "drop");
        clickStart();
        RobotWait.until("the drop never landed", () -> !logSubjects().contains("add b"));

        List<String> after = logSubjects();
        assertFalse(after.contains("add b"), "the dropped commit should be gone: " + after);
        assertTrue(after.contains("add a"));
        assertTrue(after.contains("add c"));
        assertFalse(Files.exists(tempDir.resolve("b.txt")),
                "its change should be gone from the working tree too");
    }

    @Test
    void rewordingChangesTheRealCommitMessage() throws Exception {
        seedFourCommits();

        chooseFromRowMenu("add a", "Interactive rebase from here…");
        setAction("add b", "reword");

        // Choosing reword opens the commit box's editor on the commit's message.
        javafx.scene.control.DialogPane editor = RobotWait.untilDialogWithButton("Reword");
        TextField summary = (TextField) editor.lookup("#" + com.jfx.git.ui.dialogs.RewordDialog.SUMMARY_ID);
        clickOn(summary);
        interact(summary::selectAll);
        write("renamed b");
        interact(() -> editor.getButtonTypes().stream()
                .filter(t -> "Reword".equals(t.getText()))
                .map(t -> (javafx.scene.control.Button) editor.lookupButton(t))
                .findFirst().orElseThrow().fire());
        settle();
        clickStart();
        RobotWait.until("the reword never landed", () -> {
            List<String> subjects = logSubjects();
            return subjects.contains("renamed b") && !subjects.contains("add b");
        });

        List<String> after = logSubjects();
        assertTrue(after.contains("renamed b"), "the new message should be in real history: " + after);
        assertFalse(after.contains("add b"));
    }

    @Test
    void squashingCombinesTwoRealCommitsIntoOne() throws Exception {
        seedFourCommits();
        int before = logSubjects().size();

        chooseFromRowMenu("add a", "Interactive rebase from here…");
        setAction("add c", "squash");
        clickStart();
        RobotWait.until("the squash never landed", () -> logSubjects().size() == before - 1);

        List<String> after = logSubjects();
        assertEquals(before - 1, after.size(), "two commits should have folded into one");
        assertTrue(Files.exists(tempDir.resolve("b.txt")));
        assertTrue(Files.exists(tempDir.resolve("c.txt")),
                "both commits' real changes should survive the fold");
    }

    @Test
    void reorderingReplaysTheRealCommitsInTheNewOrder() throws Exception {
        seedFourCommits();

        chooseFromRowMenu("add a", "Interactive rebase from here…");
        // Move "add c" up twice via its row's move button, to the front of the range.
        // Re-found before each click rather than clicked twice from one reference:
        // ListView.refresh() - which a move triggers - is documented to recreate cells,
        // so the button instance from before the first click is not guaranteed to still
        // be the one in the scene after it.
        clickOn(moveButtonsFor("add c").get(0));
        settle();
        clickOn(moveButtonsFor("add c").get(0));
        settle();
        List<String> before = logSubjects();
        clickStart();
        // A reorder keeps every subject, so it is recognised by the order changing.
        RobotWait.until("the reorder never landed", () -> {
            List<String> subjects = logSubjects();
            return subjects.size() == before.size() && !subjects.equals(before);
        });

        List<String> after = logSubjects();
        // Newest-first log: "add c" replayed first in the range now finishes as the
        // second-oldest of the three, ahead of both "add a" and "add b".
        int indexA = after.indexOf("add a");
        int indexC = after.indexOf("add c");
        assertTrue(indexC > indexA,
                "add c should have replayed before add b and add a in the new order: " + after);
    }
}
