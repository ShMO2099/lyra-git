package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RebaseTodo;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.graph.GraphOrder;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cherry-picking driven from the graph's own right-click menu, single commit and multiple,
 * onto a real second branch.
 *
 * <p>The graph shows every branch's commits (it walks {@code ListMode.ALL}, not just the
 * checked-out one - see {@link com.jfx.git.graph.GhostBranches}), which is what makes a
 * commit that only exists on {@code feature} visible, and right-clickable, while sitting on
 * {@code main}. A cherry-pick has to be a real copy - a new commit with a new hash - not a
 * fast-forward, or the original branch's own history would move; every test here checks the
 * hash changed as well as the content landing.
 */
class CherryPickRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    private String mainBranch;
    private Set<Long> snapshotTimerThreadsBeforeTab = Set.of();
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
        mainBranch = service.getCurrentBranch();
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("feature1.txt", "one", "feature: add feature1");
        commit("feature2.txt", "two", "feature: add feature2");
        assertTrue(service.checkoutBranch(mainBranch).isOk());

        snapshotTimerThreadsBeforeTab = liveThreadIds("snapshot-timer");
        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() {
        // A cherry-pick runs on the git lane; it must be finished before the backend
        // closes and @TempDir deletes the repository under it.
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

    private RevCommit head() throws Exception {
        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD"));
        }
    }

    private String hashOf(String ref) throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.getRepository().resolve(ref).getName();
        }
    }

    /**
     * How many commits {@code ref} can reach.
     *
     * <p>Not a subject-text check: the graph walks every branch ({@code ListMode.ALL}),
     * so "feature: add feature1"'s original commit is always visible there whether or not
     * it was ever cherry-picked. Counting what {@code mainBranch} itself can reach is the
     * only way to tell a real cherry-picked copy from the original sitting on its own
     * branch, untouched, the whole time.
     */
    private int commitCountOn(String ref) throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            int count = 0;
            for (RevCommit _ : git.log().add(git.getRepository().resolve(ref)).call()) {
                count++;
            }
            return count;
        }
    }

    /** {@link #commitCountOn}, as a condition: a repository mid-pick reads as "not yet". */
    private boolean commitCountIs(String ref, int expected) {
        try {
            return commitCountOn(ref) == expected;
        } catch (Exception midPick) {
            return false;
        }
    }

    /** The full message of the commit {@code rev} names, or null while it cannot be read. */
    private String messageOf(String rev) {
        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve(rev)).getFullMessage();
        } catch (Exception midRewrite) {
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

    private static Set<Long> liveThreadIds(String name) {
        Set<Long> ids = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && name.equals(thread.getName())) {
                ids.add(thread.getId());
            }
        }
        return ids;
    }

    /**
     * The graph row showing {@code subject}. Waits for it: the rows come from a refresh
     * that runs off the FX thread, started as the window opened.
     */
    private Node graphRow(String subject) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no row showing \"" + subject + "\" appeared", () -> {
            row[0] = lookup(subject).tryQuery().orElse(null);
            return row[0] != null;
        });
        return row[0];
    }

    private void selectRange(String fromSubject, String toSubject) {
        graphRow(fromSubject);
        clickOn(fromSubject);
        settle();
        press(KeyCode.SHIFT);
        clickOn(toSubject);
        release(KeyCode.SHIFT);
        settle();
    }

    /** Opens the context menu on the row showing {@code subject}, without changing the selection. */
    private void openMenuOn(String subject) {
        Node row = graphRow(subject);
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    private ContextMenu openContextMenu() {
        return (ContextMenu) Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof ContextMenu)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no context menu is currently showing"));
    }

    /**
     * The action {@link ComboBox} for the row whose commit subject is {@code subject}, inside
     * the cherry-pick planning dialog. Mirrors {@code InteractiveRebaseRobotTest.actionBoxFor}:
     * scoped to the dialog's own scene so it cannot find the graph's identically-labelled row
     * sitting behind it in a second, still-showing window.
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
        throw new AssertionError("no cherry-pick row found for " + subject);
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
     * Writes {@code message} in the editor that choosing reword opens - the commit box's
     * summary and description, over the plan - and confirms it.
     */
    private void writeRewordMessage(String message) {
        javafx.scene.control.DialogPane editor = RobotWait.untilDialogWithButton("Reword");
        javafx.scene.control.TextField summary = (javafx.scene.control.TextField)
                editor.lookup("#" + com.jfx.git.ui.dialogs.RewordDialog.SUMMARY_ID);
        clickOn(summary);
        interact(summary::selectAll);
        write(message);
        interact(() -> editor.getButtonTypes().stream()
                .filter(t -> "Reword".equals(t.getText()))
                .map(t -> (javafx.scene.control.Button) editor.lookupButton(t))
                .findFirst().orElseThrow().fire());
        settle();
    }

    // ------------------------------------------------------------------

    @Test
    void tidyUpStopsTheRepositorySnapshotTimer() {
        int previousInterval = com.jfx.git.core.AppPreferences.snapshotIntervalMinutes();
        Set<Long> fixtureTimers;
        try {
            com.jfx.git.core.AppPreferences.setSnapshotIntervalMinutes(60);
            interact(() -> tab.refresh());
            RobotWait.drainGitWork();

            fixtureTimers = liveThreadIds("snapshot-timer");
            fixtureTimers.removeAll(snapshotTimerThreadsBeforeTab);
            assertFalse(fixtureTimers.isEmpty(),
                    "precondition: refreshing this repository started its snapshot timer");

            tidyUp();
            RobotWait.until("tidyUp left this repository's snapshot timer running",
                    () -> fixtureTimers.stream().noneMatch(id ->
                            Thread.getAllStackTraces().keySet().stream()
                                    .anyMatch(thread -> thread.getId() == id && thread.isAlive())));
        } finally {
            // Keep the tab closed even if the assertion itself fails.
            if (tab != null) {
                interact(() -> tab.close());
            }
            com.jfx.git.core.AppPreferences.setSnapshotIntervalMinutes(previousInterval);
        }
    }

    @Test
    void cherryPickingOneCommitCopiesItOntoTheCurrentBranchAsARealNewCommit() throws Exception {
        String originalHash = hashOf("feature");
        List<String> before = logSubjects();

        openMenuOn("feature: add feature1");
        ContextMenu menu = openContextMenu();
        MenuItem item = menu.getItems().stream()
                .filter(i -> "Cherry-pick onto current branch".equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("cherry-pick should be offered for a "
                        + "commit that is not on the current branch"));
        assertFalse(item.isDisable());
        clickOn("Cherry-pick onto current branch");
        RobotWait.until("the cherry-pick never landed",
                () -> logSubjects().size() == before.size() + 1);

        assertEquals(before.size() + 1, logSubjects().size(),
                "exactly one new commit should have landed on " + mainBranch);
        assertEquals("one", Files.readString(tempDir.resolve("feature1.txt")));
        assertNotEquals(originalHash, head().getName(),
                "a cherry-pick copies the change into a new commit - it must not simply fast-"
                        + "forward main's ref onto feature's original commit");
        assertEquals(originalHash, hashOf("feature"),
                "the original commit on feature must be completely untouched");
    }

    @Test
    void cherryPickIsDisabledForACommitAlreadyOnTheCurrentBranch() throws Exception {
        openMenuOn("root commit");
        ContextMenu menu = openContextMenu();
        MenuItem item = menu.getItems().stream()
                .filter(i -> i.getText() != null && i.getText().startsWith("Cherry-pick onto current branch"))
                .findFirst()
                .orElseThrow();

        assertEquals("Cherry-pick onto current branch (already on it)", item.getText());
        assertTrue(item.isDisable(), "cherry-picking a commit already on this branch is a no-op");

        interact(menu::hide);
        settle();
    }

    @Test
    void cherryPickingTwoCommitsAppliesBothInOrderThroughThePlanningDialog() throws Exception {
        int before = commitCountOn(mainBranch);
        selectRange("feature: add feature2", "feature: add feature1");

        openMenuOn("feature: add feature2");
        clickOn("Cherry-pick 2 commits…");
        RobotWait.untilDialogWithButton("Apply 2 commits");

        assertNotNull(lookup(".rebase-heading").tryQuery().orElse(null),
                "cherry-picking several commits should open the same planning dialog an "
                        + "interactive rebase uses");
        // An unedited plan is the ordinary case for a cherry-pick - unlike a rebase, it
        // is never a no-op, since the destination is not where these commits already
        // are - so the confirm button must already be enabled here, with nothing changed.
        clickOn("Apply 2 commits");
        RobotWait.until("both commits never landed", () -> commitCountIs(mainBranch, before + 2));

        assertEquals(before + 2, commitCountOn(mainBranch),
                mainBranch + " should have gained two real new commits, not stayed where it was");
        assertEquals("one", Files.readString(tempDir.resolve("feature1.txt")));
        assertEquals("two", Files.readString(tempDir.resolve("feature2.txt")));
    }

    @Test
    void droppingOneRowInThePlanningDialogAppliesOnlyTheOther() throws Exception {
        int before = commitCountOn(mainBranch);
        selectRange("feature: add feature2", "feature: add feature1");

        openMenuOn("feature: add feature2");
        clickOn("Cherry-pick 2 commits…");
        RobotWait.untilDialogWithButton("Apply 2 commits");

        setAction("feature: add feature1", "drop");
        clickOn("Apply 2 commits");
        RobotWait.until("the kept commit never landed", () -> commitCountIs(mainBranch, before + 1));

        assertEquals(before + 1, commitCountOn(mainBranch),
                mainBranch + " should have gained exactly one commit, not two");
        assertFalse(Files.exists(tempDir.resolve("feature1.txt")),
                "the dropped commit must not have been applied");
        assertTrue(Files.exists(tempDir.resolve("feature2.txt")));
    }

    /**
     * Rewording a cherry-picked commit is the two-phase flow's whole point: the commit
     * cherry-picking creates has a new hash the moment it exists, so proving this means
     * checking the real HEAD's message afterward, not trusting that the plan's own
     * (now-stale) hash was ever touched. Reword and squash are only offered once two or
     * more commits are selected - a single cherry-pick keeps the separate, dialog-free
     * "Cherry-pick onto current branch" item, unchanged by this - so this rewords one
     * row of a two-commit pick and leaves the other alone.
     */
    @Test
    void rewordingARowChangesTheMessageOfTheCherryPickedCommitNotTheOriginal() throws Exception {
        // "feature" resolves to the branch tip (feature2's original commit); feature1's
        // own original hash is that commit's parent.
        String originalHash = hashOf("feature~1");
        selectRange("feature: add feature2", "feature: add feature1");

        openMenuOn("feature: add feature2");
        clickOn("Cherry-pick 2 commits…");
        RobotWait.untilDialogWithButton("Apply 2 commits");

        setAction("feature: add feature1", "reword");
        writeRewordMessage("feature1, reworded during cherry-pick");

        clickOn("Apply 2 commits");
        // Two phases: the pick, then a rebase over the picked copies to reword one. The
        // branch only moves to the reworded history when that rebase finishes, so the
        // reworded message one below the branch tip means both phases are done.
        RobotWait.until("the reworded cherry-pick never landed", () ->
                "feature1, reworded during cherry-pick".equals(messageOf(mainBranch + "~1")));

        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            RevCommit newHead = walk.parseCommit(git.getRepository().resolve("HEAD"));
            RevCommit rewordedParent = walk.parseCommit(newHead.getParent(0));

            assertEquals("feature: add feature2", newHead.getShortMessage(),
                    "the untouched row keeps its own message");
            assertEquals("feature1, reworded during cherry-pick", rewordedParent.getShortMessage());
            assertNotEquals(originalHash, rewordedParent.getName(),
                    "the reworded commit must be a real new commit, not the original");

            // The original, untouched, still has its own message - reword only ever
            // touches the copy this cherry-pick just made.
            RevCommit original = walk.parseCommit(git.getRepository().resolve(originalHash));
            assertEquals("feature: add feature1", original.getShortMessage());
        }
    }

    /**
     * Squashing two cherry-picked commits together must produce exactly one net new
     * commit on the destination branch, carrying the content of both.
     */
    @Test
    void squashingTwoRowsFoldsTheCherryPickedCommitsIntoOne() throws Exception {
        int before = commitCountOn(mainBranch);
        selectRange("feature: add feature2", "feature: add feature1");

        openMenuOn("feature: add feature2");
        clickOn("Cherry-pick 2 commits…");
        RobotWait.untilDialogWithButton("Apply 2 commits");

        // feature1 is row 0 (oldest, replayed first): squash keeps its own pick and
        // folds feature2 (row 1) into it, exactly as RebaseTodo.squashing documents -
        // the oldest of a fold run is the survivor.
        setAction("feature: add feature2", "squash");
        clickOn("Apply 2 commits");
        // Two phases, and the branch passes through before + 1 during the first, after
        // one of the two picks - so the fold is recognised by its combined message too.
        RobotWait.until("the squashed cherry-pick never landed", () -> {
            String message = messageOf(mainBranch);
            return commitCountIs(mainBranch, before + 1) && message != null
                    && message.contains("feature: add feature1")
                    && message.contains("feature: add feature2");
        });

        assertEquals(before + 1, commitCountOn(mainBranch),
                mainBranch + " should have gained exactly one commit: two picked, folded into one");
        assertEquals("one", Files.readString(tempDir.resolve("feature1.txt")),
                "both files' content must survive the fold");
        assertEquals("two", Files.readString(tempDir.resolve("feature2.txt")));

        RevCommit newHead = head();
        assertTrue(newHead.getFullMessage().contains("feature: add feature1"),
                "a squash keeps both messages by default: " + newHead.getFullMessage());
        assertTrue(newHead.getFullMessage().contains("feature: add feature2"),
                "a squash keeps both messages by default: " + newHead.getFullMessage());
    }

    /**
     * A merge in a batch asks which parent is the mainline, as the single-commit action
     * does, and the answer is what gets applied. The batch used to take parent 1 without
     * asking.
     *
     * <p>{@code side} branches off the root and is merged into feature. Picking the side
     * commit and then the merge relative to parent 2 - the side commit - brings feature's
     * own two files across; relative to parent 1 it would re-apply side.txt, which is
     * already there, and bring nothing.
     */
    @Test
    void aMergeInTheBatchIsPickedAgainstTheParentTheUserChose() throws Exception {
        assertTrue(service.createBranch("side", mainBranch, true).isOk());
        commit("side.txt", "side", "side: add side");
        assertTrue(service.checkoutBranch("feature").isOk());
        assertTrue(new RepositoryCommands(service).merge("side", true).isOk());
        String mergeSubject = head().getShortMessage();
        assertEquals(2, head().getParentCount());
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        interact(() -> tab.refresh());

        graphRow(mergeSubject);
        clickOn(mergeSubject);
        press(KeyCode.CONTROL);
        clickOn(graphRow("side: add side"));
        release(KeyCode.CONTROL);
        settle();

        openMenuOn(mergeSubject);
        clickOn("Cherry-pick 2 commits…");
        RobotWait.untilDialogWithButton("Apply 2 commits");
        // The plan takes the merge: which parent it is picked against is asked next, not
        // refused here as it would be for a rebase.
        clickOn("Apply 2 commits");

        javafx.scene.control.DialogPane question = RobotWait.untilDialogWithButton("OK");
        assertTrue(question.getHeaderText().endsWith("relative to which parent?"), question::getHeaderText);
        List<javafx.scene.control.RadioButton> parents = FxHarness.call(() ->
                FxHarness.lookupAll(question, "jfx-option-radio").stream()
                        .map(javafx.scene.control.RadioButton.class::cast).toList());
        assertEquals(2, parents.size(), "one option per parent of the merge");
        interact(() -> parents.get(1).setSelected(true));
        interact(() -> ((javafx.scene.control.Button) question.lookupButton(
                javafx.scene.control.ButtonType.OK)).fire());

        RobotWait.until("feature's files never arrived through the merge's second parent",
                () -> Files.exists(tempDir.resolve("feature2.txt")));
        RobotWait.drainGitWork();
        assertEquals("side", Files.readString(tempDir.resolve("side.txt")));
        assertEquals("one", Files.readString(tempDir.resolve("feature1.txt")));
        assertEquals(mainBranch, service.getCurrentBranch());
    }
}
