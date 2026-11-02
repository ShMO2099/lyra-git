package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ReflogItem;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.ui.views.ReflogView;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B24 in {@code manual-test-artifacts/qa-scenario-improvements.md}: going back through a
 * branch's history from the toolbar's Undo menu - "Preview undo…" for the next step, and
 * "Branch history (reflog)…" for any earlier one - judged by the repository afterwards:
 * the branch tip, HEAD's reflog, the index and the bytes on disk, read through JGit directly
 * rather than through the engine that did the work.
 *
 * <p>The fixture is three commits on {@code main}: {@code one} writes {@code story.txt},
 * {@code keep.txt} and a {@code .gitignore} for {@code *.log}; {@code two} rewrites the
 * story; {@code three} rewrites it again and adds {@code change-d.txt}. An ignored
 * {@code build.log} sits beside them, uncommitted, so a restore that swept the directory
 * clean would show.
 *
 * <p>Branch history's Restore asks with the same preview as "Preview undo…": the commits
 * that leave the branch, the commits that come back to it, and the files that change on
 * disk. Restoring forward - back to the tip after restoring an older entry - is the case
 * where nothing leaves and commits come back, so both directions are read exactly. A dirty
 * tree is refused before either asks, in the same words, because the restore itself would
 * refuse it after the user had said yes.
 *
 * <p>JGit is the engine here; {@link IncompleteQaBranchHistoryNativeRobotTest} runs the same
 * journeys on the git CLI.
 */
class IncompleteQaBranchHistoryRobotTest extends ApplicationTest {

    private static final String KEEP = "kept in every commit\n";
    private static final String IGNORE = "*.log\n";
    private static final String BUILD_LOG = "ignored build output\n";
    private static final String CHANGE_D = "only in three\n";

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;
    private String one;
    private String two;
    private String three;

    /** The engine under test, opened on {@code workTree}; the CLI subclass swaps it. */
    protected GitBackend openEngine(Path workTree) throws Exception {
        GitService service = new GitService();
        assertTrue(service.openRepository(workTree.toFile()));
        return service;
    }

    @Override
    public void start(Stage stage) throws Exception {
        work = root.resolve("work");
        try (var fixture = ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA");
                config.setString("user", null, "email", "qa@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
                write(".gitignore", IGNORE);
                write("keep.txt", KEEP);
                write("story.txt", "one\n");
                one = commitAll(git, "one");
                write("story.txt", "two\n");
                two = commitAll(git, "two");
                write("story.txt", "three\n");
                write("change-d.txt", CHANGE_D);
                three = commitAll(git, "three");
            }
            write("build.log", BUILD_LOG);
            backend = openEngine(work);
        }
        tab = new RepoTab(new LyraApp(), "branch history", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheToolbar() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("Undo never became available", () -> !undoButton().isDisabled());
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Preview undo
    // ------------------------------------------------------------------

    @Test
    void previewUndoNamesWhatItWouldDiscardAndCancellingChangesNothing() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        fireUndoMenu("Preview undo…");
        DialogPane preview = RobotWait.untilDialogWithButton("OK");
        List<String> listed = FxHarness.call(() -> listedIn(preview));
        assertTrue(listed.contains("three"), "the commit leaving the branch is named: " + listed);
        assertTrue(listed.contains("change-d.txt") && listed.contains("story.txt"),
                "the files that change on disk are named: " + listed);
        assertFalse(listed.contains("keep.txt") || listed.contains("two"),
                "nothing that stays is listed: " + listed);
        press(preview, ButtonType.CANCEL);
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "a cancelled preview must change nothing");
    }

    @Test
    void aPreviewedUndoThatIsAcceptedMovesTheBranchBackOneStep() throws Exception {
        fireUndoMenu("Preview undo…");
        press(RobotWait.untilDialogWithButton("OK"), ButtonType.OK);
        RobotWait.until("the undo never moved main", () -> two.equals(resolve("refs/heads/main")));
        RobotWait.drainGitWork();

        assertEquals("refs/heads/main", RepoSnapshot.of(work).branch(), "the branch stays checked out");
        assertEquals("two\n", read("story.txt"));
        assertFalse(Files.exists(work.resolve("change-d.txt")), "what only three added is gone");
        assertCommonFilesKept();
        assertTrue(status().isClean(), "a hard reset leaves nothing uncommitted: " + status().getUncommittedChanges());
        assertEquals(three + " " + two, lastHeadMove(), "the undo is itself a recorded move");
        assertTrue(objectExists(three), "the commit undone stays readable, for Redo");
    }

    // ------------------------------------------------------------------
    // Branch history
    // ------------------------------------------------------------------

    @Test
    void cancellingARestoreFromBranchHistoryChangesNothing() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);
        ReflogView view = openBranchHistory();

        DialogPane confirm = restore(view, one);
        String header = FxHarness.call(confirm::getHeaderText);
        assertTrue(header != null && header.contains("main") && header.contains(shortHash(one)),
                "the confirmation names the branch and the commit it goes back to: " + header);
        assertEquals(List.of(
                        "This resets the branch to " + shortHash(one) + ", discarding what came after.",
                        "2 commits will no longer be on this branch",
                        "  two",
                        "  three",
                        "They stay in this history, so this can be undone in turn.",
                        "2 files will change on disk",
                        "  change-d.txt",
                        "  story.txt"),
                FxHarness.call(() -> previewOf(confirm)),
                "going back names the commits leaving main and the files changing - and nothing else");
        press(confirm, ButtonType.CANCEL);
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "a cancelled restore must change nothing");
    }

    @Test
    void restoringForwardFromBranchHistoryPreviewsTheCommitsComingBackAndCancellingChangesNothing()
            throws Exception {
        ReflogView view = openBranchHistory();
        press(restore(view, one), ButtonType.OK);
        RobotWait.until("the restore never moved main", () -> one.equals(resolve("refs/heads/main")));
        RobotWait.untilOnFx("the restore never showed in the history",
                () -> one.equals(view.loadedEntries().getFirst().newHash()));
        RobotWait.drainGitWork();
        RepoSnapshot before = RepoSnapshot.of(work);

        // Forward, to the entry where three was made: nothing leaves main, two and three
        // come back, and the same two files change - the other way round.
        DialogPane confirm = restore(view, three);
        String header = FxHarness.call(confirm::getHeaderText);
        assertTrue(header != null && header.contains(shortHash(three)),
                "the confirmation names the commit it goes to: " + header);
        assertEquals(List.of(
                        "This moves the branch forward to " + shortHash(three) + ". No commit leaves it.",
                        "2 commits will be back on this branch",
                        "  two",
                        "  three",
                        "2 files will change on disk",
                        "  change-d.txt",
                        "  story.txt"),
                FxHarness.call(() -> previewOf(confirm)),
                "going forward names the commits coming back, and none as leaving");
        press(confirm, ButtonType.CANCEL);
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "a cancelled forward restore must change nothing");
    }

    @Test
    void restoringPastAnAmendFromBranchHistoryPreviewsTheCommitLeavingAndTheOneComingBack()
            throws Exception {
        // What Branch history is for: undoing a rewrite. The amend puts main on a line of
        // its own, so going back to where three was made is neither backwards nor forwards.
        try (Git git = Git.open(work.toFile())) {
            write("story.txt", "three, amended\n");
            git.add().addFilepattern("story.txt").call();
            git.commit().setAmend(true).setMessage("three, amended").call();
        }
        RepoSnapshot before = RepoSnapshot.of(work);
        ReflogView view = openBranchHistory();

        DialogPane confirm = restore(view, three);
        assertEquals(List.of(
                        "This resets the branch to " + shortHash(three) + ", on a different line of"
                                + " history: some commits leave it and others come back.",
                        "1 commit will no longer be on this branch",
                        "  three, amended",
                        "They stay in this history, so this can be undone in turn.",
                        "1 commit will be back on this branch",
                        "  three",
                        "1 file will change on disk",
                        "  story.txt"),
                FxHarness.call(() -> previewOf(confirm)),
                "the amended commit leaves, the original comes back, and only the story changes");
        press(confirm, ButtonType.CANCEL);
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "a cancelled restore must change nothing");
    }

    @Test
    void restoringAnOlderEntryAndThenTheOriginalTipBringsBackEachStateExactly() throws Exception {
        ReflogView view = openBranchHistory();

        press(restore(view, one), ButtonType.OK);
        RobotWait.until("the restore never moved main", () -> one.equals(resolve("refs/heads/main")));
        RobotWait.untilOnFx("the restore never showed in the history",
                () -> one.equals(view.loadedEntries().getFirst().newHash()));
        RobotWait.drainGitWork();

        assertEquals("refs/heads/main", RepoSnapshot.of(work).branch());
        assertEquals("one\n", read("story.txt"));
        assertFalse(Files.exists(work.resolve("change-d.txt")), "what came later is gone from disk");
        assertCommonFilesKept();
        assertTrue(status().isClean(), "nothing uncommitted is left behind: " + status().getUncommittedChanges());
        assertEquals(three + " " + one, lastHeadMove(), "the restore is recorded as a move from three");
        assertTrue(objectExists(three) && objectExists(two), "the commits restored past stay readable");

        // The way back is the same view: the entry where three was made is still listed.
        press(restore(view, three), ButtonType.OK);
        RobotWait.until("restoring three never moved main", () -> three.equals(resolve("refs/heads/main")));
        RobotWait.drainGitWork();

        assertEquals("three\n", read("story.txt"));
        assertEquals(CHANGE_D, read("change-d.txt"), "three's own file comes back byte for byte");
        assertCommonFilesKept();
        assertTrue(status().isClean(), status().getUncommittedChanges().toString());
        assertEquals(one + " " + three, lastHeadMove(), "and that restore is recorded too");
    }

    // ------------------------------------------------------------------
    // With local changes
    // ------------------------------------------------------------------

    @Test
    void restoringFromBranchHistoryWithLocalChangesIsRefusedAndKeepsThem() throws Exception {
        makeLocalChanges();
        RepoSnapshot before = RepoSnapshot.of(work);
        ReflogView view = openBranchHistory();
        int windows = FxHarness.call(() -> Window.getWindows().size());

        select(view, one);
        pressRestore();
        RobotWait.untilOnFx("the refusal was never reported", () -> statusMessage().getStyleClass()
                .contains("status-error") && statusMessage().getText().contains("uncommitted changes"));
        RobotWait.drainGitWork();

        assertEquals("Restoring main to " + shortHash(one) + " resets the working tree, so it is"
                        + " refused while there are uncommitted changes. Commit or stash them first.",
                FxHarness.call(() -> statusMessage().getText()),
                "refused in the words Undo uses, naming what was refused");
        assertEquals(windows, FxHarness.call(() -> Window.getWindows().size()),
                "Branch history refuses up front rather than asking a question whose OK does nothing");
        assertEquals(before, RepoSnapshot.of(work),
                "the refused restore leaves the branch, index, reflog and every file as they were");
    }

    @Test
    void undoWithLocalChangesIsRefusedAndKeepsThem() throws Exception {
        makeLocalChanges();
        RepoSnapshot before = RepoSnapshot.of(work);
        int windows = FxHarness.call(() -> Window.getWindows().size());

        clickOn(undoButton());
        RobotWait.untilOnFx("the refusal was never reported", () -> statusMessage().getStyleClass()
                .contains("status-error") && statusMessage().getText().contains("uncommitted changes"));
        RobotWait.drainGitWork();

        assertEquals(windows, FxHarness.call(() -> Window.getWindows().size()),
                "Undo refuses up front rather than asking first");
        assertEquals(before, RepoSnapshot.of(work),
                "the refused undo leaves the branch, index, reflog and every file as they were");
    }

    /** A staged edit, an unstaged edit and an untracked file - one of each kind of work. */
    private void makeLocalChanges() throws Exception {
        write("keep.txt", "staged edit\n");
        try (Git git = Git.open(work.toFile())) {
            git.add().addFilepattern("keep.txt").call();
        }
        write("story.txt", "unstaged edit\n");
        write("notes.txt", "untracked notes\n");
    }

    // ------------------------------------------------------------------
    // The toolbar and the view
    // ------------------------------------------------------------------

    private SplitMenuButton undoButton() {
        return (SplitMenuButton) tab.getToolbar().toolButton("Undo");
    }

    /** Fires an item of Undo's dropdown; one that raises a dialog does so after this returns. */
    private void fireUndoMenu(String label) {
        MenuItem item = undoButton().getItems().stream()
                .filter(i -> label.equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + label + "\" in Undo's menu"));
        interact(item::fire);
    }

    private ReflogView openBranchHistory() {
        fireUndoMenu("Branch history (reflog)…");
        ReflogView view = (ReflogView) FxHarness.call(() -> tab.currentView());
        RobotWait.untilOnFx("the branch history never listed the three commits",
                () -> view.loadedEntries().size() >= 3);
        return view;
    }

    /**
     * Selects the newest entry that left HEAD at {@code commit} and presses Restore,
     * returning the confirmation it raises.
     */
    private DialogPane restore(ReflogView view, String commit) {
        select(view, commit);
        pressRestore();
        return RobotWait.untilDialogWithButton("OK");
    }

    /** Selects the newest entry that left HEAD at {@code commit}. */
    private void select(ReflogView view, String commit) {
        @SuppressWarnings("unchecked")
        ListView<ReflogItem> entries = (ListView<ReflogItem>) FxHarness.call(
                () -> tab.getContent().lookup("#" + ReflogView.ENTRIES_ID));
        interact(() -> {
            int index = 0;
            for (ReflogItem item : entries.getItems()) {
                if (commit.equals(item.newHash())) break;
                index++;
            }
            if (index == entries.getItems().size()) {
                throw new AssertionError("no history entry reaches " + commit + ": " + entries.getItems());
            }
            entries.getSelectionModel().select(index);
        });
    }

    /** Presses Restore; a confirmation it raises appears after this returns. */
    private void pressRestore() {
        Button restore = (Button) FxHarness.call(() -> tab.getContent().lookup("#" + ReflogView.RESTORE_ID));
        // Not interact(): the confirmation's nested event loop would hold it open.
        Platform.runLater(restore::fire);
    }

    private void press(DialogPane pane, ButtonType type) {
        interact(() -> ((Button) pane.lookupButton(type)).fire());
    }

    /**
     * The confirmation as the user reads it: each line of its text in order, with the rows
     * of a list indented beneath the heading they follow. A plain alert is its one sentence.
     * A heading and its list may sit in a group of their own; the groups are read in turn.
     */
    private static List<String> previewOf(DialogPane pane) {
        List<String> lines = new ArrayList<>();
        if (pane.getContent() == null) {
            lines.add(pane.getContentText());
            return lines;
        }
        readInOrder((Parent) pane.getContent(), lines);
        return lines;
    }

    private static void readInOrder(Parent parent, List<String> lines) {
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Label label) {
                lines.add(label.getText());
            } else if (node instanceof ListView<?> list) {
                list.getItems().forEach(item -> lines.add("  " + rowText(item)));
            } else if (node instanceof Parent group) {
                readInOrder(group, lines);
            }
        }
    }

    private static String rowText(Object item) {
        return item instanceof com.jfx.git.model.CommitInfo commit ? commit.getSubject() : item.toString();
    }

    private static String shortHash(String id) {
        return id.substring(0, 7);
    }

    /** The text of every row of every list in {@code pane}. */
    private static List<String> listedIn(DialogPane pane) {
        List<String> rows = new ArrayList<>();
        for (var node : pane.lookupAll(".list-view")) {
            for (Object item : ((ListView<?>) node).getItems()) {
                rows.add(rowText(item));
            }
        }
        return rows;
    }

    private Label statusMessage() {
        return (Label) FxHarness.lookupAll(tab.getContent(), "status-message").getFirst();
    }

    // ------------------------------------------------------------------
    // The repository, read independently of the engine under test
    // ------------------------------------------------------------------

    private void assertCommonFilesKept() throws Exception {
        assertEquals(KEEP, read("keep.txt"));
        assertEquals(IGNORE, read(".gitignore"));
        assertEquals(BUILD_LOG, read("build.log"), "an ignored file is left alone");
    }

    private String resolve(String revision) {
        try (Git git = Git.open(work.toFile())) {
            var id = git.getRepository().resolve(revision);
            return id == null ? null : id.name();
        } catch (Exception e) {
            throw new AssertionError("could not resolve " + revision, e);
        }
    }

    private boolean objectExists(String id) throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return git.getRepository().getObjectDatabase().has(org.eclipse.jgit.lib.ObjectId.fromString(id));
        }
    }

    /** HEAD's newest reflog entry, as "old new". */
    private String lastHeadMove() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            var entry = git.getRepository().getRefDatabase().getReflogReader(Constants.HEAD).getLastEntry();
            return entry.getOldId().name() + " " + entry.getNewId().name();
        }
    }

    private org.eclipse.jgit.api.Status status() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return git.status().call();
        }
    }

    private String read(String file) throws Exception {
        return Files.readString(work.resolve(file), StandardCharsets.UTF_8);
    }

    private void write(String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    }

    private static String commitAll(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).call().getName();
    }
}
