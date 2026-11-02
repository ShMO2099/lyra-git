package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.model.CommitInfo;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.treewalk.TreeWalk;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E14 in {@code manual-test-artifacts/qa-scenario-improvements.md}: following a renamed
 * file's history back past the rename, and restoring one of its pre-rename changes into the
 * file as it is called today - judged by what File History shows and by the repository
 * afterwards, read through JGit directly rather than through the engine under test.
 *
 * <p>The fixture is four commits on {@code main}. {@link #ADD} writes {@code old-name.txt}
 * (twenty NATO words, one a line) beside {@code other.txt} and {@code notes.txt};
 * {@link #EDIT_OLD} rewrites its third line; {@link #RENAME} moves it to
 * {@code new-name.txt} byte for byte - a pure rename - and edits {@code other.txt};
 * {@link #EDIT_NEW} rewrites the renamed file's sixteenth line.
 *
 * <p>What File History offers for an older revision, and so what is tested: each hunk of
 * the selected commit's diff carries "Revert hunk", which undoes that commit's change in
 * today's working copy after an OK/Cancel confirmation. The history view offers no
 * whole-file "Restore this version" (its embedded viewer has no save handler) and no
 * line-level discard, so neither is exercised here.
 *
 * <p>Where the old name shows: File History's title names today's file for every row, and
 * the embedded viewer's own path header is hidden there, so a pre-rename revision's path is
 * checked on the path the viewer reads and acts on; the renaming commit says both names on
 * screen, in its "Renamed from" line.
 *
 * <p>JGit is the engine here; {@link IncompleteQaRenameHistoryNativeRobotTest} runs the same
 * journeys on the git CLI, whose {@code log --follow} reads the history in place of JGit's
 * {@code FollowFilter}.
 */
class IncompleteQaRenameHistoryRobotTest extends ApplicationTest {

    private static final String OLD = "old-name.txt";
    private static final String NEW = "new-name.txt";

    private static final String ADD = "add old-name.txt";
    private static final String EDIT_OLD = "edit old-name.txt";
    private static final String RENAME = "rename old-name.txt to new-name.txt";
    private static final String EDIT_NEW = "edit new-name.txt";

    /** As {@link #ADD} wrote it. */
    private static final String V1 = lines(List.of());
    /** As {@link #EDIT_OLD} left it, and as {@link #RENAME} moved it. */
    private static final String V2 = lines(List.of("charlie", "charlie, edited before the rename"));
    /** As {@link #EDIT_NEW} left it: HEAD's copy. */
    private static final String V4 = lines(List.of("charlie", "charlie, edited before the rename",
            "papa", "papa, edited after the rename"));
    /** HEAD's copy with an uncommitted edit well away from the pre-rename hunk. */
    private static final String V4_EDITED = V4.replace("juliet\n", "juliet, edited but not committed\n");

    private static final String OTHER_FIRST = "other, as first committed\n";
    private static final String OTHER_RENAMED = "other, edited in the rename commit\n";
    private static final String NOTES = "notes, as first committed\n";

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;
    private String add;
    private String editOld;
    private String rename;
    private String editNew;

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
                write(OLD, V1);
                write("other.txt", OTHER_FIRST);
                write("notes.txt", NOTES);
                add = commitAll(git, ADD);
                write(OLD, V2);
                editOld = commitAll(git, EDIT_OLD);
                Files.delete(work.resolve(OLD));
                write(NEW, V2);
                write("other.txt", OTHER_RENAMED);
                rename = commitAll(git, RENAME);
                write(NEW, V4);
                editNew = commitAll(git, EDIT_NEW);
            }
            backend = openEngine(work);
        }
        tab = new RepoTab(new LyraApp(), "rename history", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheGraph() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the graph never listed the four commits", () -> graphRowOf(editNew) >= 0);
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
    // Reading history across the rename
    // ------------------------------------------------------------------

    @Test
    void historyOfTheRenamedFileReachesItsPreRenameRevisionsUnderTheOldNameWithTheirExactBytes()
            throws Exception {
        // The fixture itself: before the rename the file existed only as old-name.txt.
        assertEquals(V2, blobAt(editOld, OLD));
        assertNull(blobAt(editOld, NEW));
        RepoSnapshot before = RepoSnapshot.of(work);

        FileHistoryView view = openHistoryOfTheRenamedFile();

        ListView<CommitInfo> commits = commitList(view);
        List<String> listed = FxHarness.call(() -> commits.getItems().stream().map(CommitInfo::getHash).toList());
        assertEquals(List.of(editNew, rename, editOld, add), listed,
                "the history carries on past the rename to the commit that created the file");
        List<String> paths = FxHarness.call(() -> commits.getItems().stream().map(CommitInfo::getHistoryPath).toList());
        assertEquals(List.of(NEW, NEW, OLD, OLD), paths,
                "each commit knows what the file was called there");

        // The pre-rename change, in Diff view: read from old-name.txt, where it was made.
        // headerPath is the path the embedded viewer reads and acts on; its header itself
        // is hidden inside File History, whose own title names today's file for every row.
        select(view, editOld);
        RobotWait.untilOnFx("the pre-rename commit's diff never rendered",
                () -> OLD.equals(embeddedViewer(view).headerPath())
                        && renderedText(view).contains("charlie, edited before the rename"));
        awaitRender();
        assertTrue(renderedText(view).contains("charlie\n"),
                "the diff shows the line as it was before that commit too: " + renderedText(view));

        // File view: the revision itself, exactly as that commit stored it.
        interact(() -> segment(view, "File view").fire());
        awaitContent(view, V2, "File view never showed the pre-rename revision");
        assertEquals(OLD, FxHarness.call(() -> embeddedViewer(view).headerPath()),
                "the content is the file under its old name");

        select(view, add);
        awaitContent(view, V1, "File view never showed the first revision");
        assertEquals(OLD, FxHarness.call(() -> embeddedViewer(view).headerPath()));

        select(view, editNew);
        awaitContent(view, V4, "File view never came back to today's revision");
        assertEquals(NEW, FxHarness.call(() -> embeddedViewer(view).headerPath()));
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "reading history changes nothing");
    }

    /**
     * Narrowed to the new name alone, the renaming commit's diff had nothing to compare
     * against: it read as the whole file appearing from nowhere, and its one hunk - every
     * line, added - carried a revert that would empty the file.
     */
    @Test
    void theRenamingCommitIsShownAsARenameRatherThanAsAWholeNewFile() throws Exception {
        FileHistoryView view = openHistoryOfTheRenamedFile();

        select(view, rename);
        RobotWait.untilOnFx("the renaming commit's diff never rendered",
                () -> NEW.equals(embeddedViewer(view).headerPath()) && !embeddedViewer(view).isFileView());
        awaitRender();

        DiffParser.Summary summary = FxHarness.call(() -> embeddedViewer(view).loadedDiff().summary());
        assertEquals(DiffParser.Change.RENAMED, summary.change(),
                "the commit moved the file; it did not create it: " + summary + "\n" + renderedText(view));
        assertEquals(OLD, summary.oldPath(), "the rename names where the file came from");
        assertEquals(NEW, summary.newPath());
        assertEquals("Renamed from " + OLD + " to " + NEW, FxHarness.call(() -> emptyStateText(view)),
                "a pure rename has no lines to show, and says so with both names");
        assertTrue(FxHarness.call(() -> revertHunkButtons(view)).isEmpty(),
                "with no line changed there is no hunk to undo");
    }

    // ------------------------------------------------------------------
    // Restoring a pre-rename hunk
    // ------------------------------------------------------------------

    @Test
    void cancellingTheRestoreOfAPreRenameHunkChangesNothing() throws Exception {
        makeLocalChanges();
        RepoSnapshot before = RepoSnapshot.of(work);
        FileHistoryView view = openHistoryOfTheRenamedFile();

        DialogPane confirm = revertTheOnlyHunkOf(view, editOld);
        assertTrue(confirm.getHeaderText().contains(NEW),
                "the confirmation names the file that would change, which is today's: "
                        + confirm.getHeaderText());
        press(confirm, ButtonType.CANCEL);
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "a cancelled restore must change nothing");
        assertFalse(Files.exists(work.resolve(OLD)), "and must not bring the old name back");
    }

    @Test
    void restoringOnePreRenameHunkChangesOnlyThoseLinesOfTheRenamedFileAndKeepsOtherWork()
            throws Exception {
        makeLocalChanges();
        RepoSnapshot before = RepoSnapshot.of(work);
        FileHistoryView view = openHistoryOfTheRenamedFile();

        press(revertTheOnlyHunkOf(view, editOld), ButtonType.OK);
        RobotWait.until("the hunk never reached new-name.txt on disk", () -> {
            try {
                return read(NEW).startsWith("alpha\nbravo\ncharlie\n");
            } catch (Exception midWrite) {
                return false;
            }
        });
        RobotWait.untilOnFx("the restore was never reported",
                () -> statusMessage().getText().contains("Reverted hunk in " + NEW));
        RobotWait.drainGitWork();

        // The working copy: the pre-rename edit undone, the later committed edit and the
        // uncommitted edit both kept.
        String expected = lines(List.of("papa", "papa, edited after the rename",
                "juliet", "juliet, edited but not committed"));
        assertEquals(expected, read(NEW));
        assertEquals(List.of(3), changedLines(V4_EDITED, read(NEW)),
                "exactly the one line the pre-rename commit had changed moves");
        assertFalse(Files.exists(work.resolve(OLD)), "the change lands in today's file, not under the old name");

        // Everything else: the index, every ref, HEAD's reflog and the other files' bytes.
        RepoSnapshot after = RepoSnapshot.of(work);
        assertEquals(before.branch(), after.branch());
        assertEquals(before.refs(), after.refs(), "no commit is made");
        assertEquals(before.headReflog(), after.headReflog(), "HEAD does not move");
        assertEquals(before.index(), after.index(), "the index - staged work included - is untouched");
        assertEquals(before.state(), after.state());
        Map<String, String> othersBefore = new TreeMap<>(before.files());
        Map<String, String> othersAfter = new TreeMap<>(after.files());
        othersBefore.remove(NEW);
        othersAfter.remove(NEW);
        assertEquals(othersBefore, othersAfter, "every other file keeps its bytes");

        try (Git git = Git.open(work.toFile())) {
            var status = git.status().call();
            assertEquals(Set.of(NEW, "other.txt", "notes.txt"), status.getModified(),
                    "unstaged: the restored file and the work that was already there");
            assertEquals(Set.of("notes.txt"), status.getChanged(), "staged: only what was staged before");
            assertEquals(Set.of("scratch.txt"), status.getUntracked());
        }
    }

    /**
     * The commit details panel offers Revert hunk on a commit's diff, as File history does:
     * the same question, the same snapshot, and the commit's change taken out of the working
     * copy as an uncommitted edit.
     */
    @Test
    void theCommitDetailsRevertAHunkOfTheSelectedCommitIntoTheWorkingCopy() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);
        showTheRenamedFileOfTheNewestCommit();
        RobotWait.untilOnFx("the commit's diff never offered Revert hunk",
                () -> revertHunkButtons(tab.diffViewer()).size() == 1);
        assertTrue(FxHarness.call(() -> FxHarness.lookupAll(tab.diffViewer(), "diff-hunk-action").stream()
                        .noneMatch(n -> n.isVisible() && n.getParent() != null && n.getParent().isVisible())),
                "a commit's diff offers nothing that writes to the index");

        Button revert = FxHarness.call(() -> revertHunkButtons(tab.diffViewer()).getFirst());
        // Not interact(): the confirmation's nested event loop would hold it open.
        Platform.runLater(revert::fire);
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        assertTrue(confirm.getHeaderText().contains(NEW), confirm.getHeaderText());
        press(confirm, ButtonType.OK);
        RobotWait.until("the commit's edit never left the working copy", () -> {
            try {
                return V2.equals(read(NEW));
            } catch (Exception midWrite) {
                return false;
            }
        });
        RobotWait.drainGitWork();

        RepoSnapshot after = RepoSnapshot.of(work);
        assertEquals(before.refs(), after.refs(), "no commit is made");
        assertEquals(before.headReflog(), after.headReflog(), "HEAD does not move");
        assertEquals(before.index(), after.index(), "the index is untouched");
    }

    /**
     * Work of every kind beside the file being restored: an unstaged edit to that file well
     * away from the hunk, an unstaged edit to another file, a staged edit with a further
     * unstaged edit on top of it, and an untracked file.
     */
    private void makeLocalChanges() throws Exception {
        write(NEW, V4_EDITED);
        write("other.txt", "other, edited but not committed\n");
        write("notes.txt", "notes, staged\n");
        try (Git git = Git.open(work.toFile())) {
            git.add().addFilepattern("notes.txt").call();
        }
        write("notes.txt", "notes, staged and then edited again\n");
        write("scratch.txt", "untracked scratch\n");
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
    }

    // ------------------------------------------------------------------
    // The UI route: a commit's changed file, then File history beside its path
    // ------------------------------------------------------------------

    /**
     * Selects the newest commit in the graph, picks {@code new-name.txt} from its changed
     * files, and presses "File history" beside the path in the viewer - where a user who has
     * just looked at the file's latest change would find it.
     */
    private FileHistoryView openHistoryOfTheRenamedFile() {
        showTheRenamedFileOfTheNewestCommit();
        interact(() -> historyButton().fire());

        RobotWait.untilOnFx("File History never opened",
                () -> FxHarness.lookupAll(tab.getContent(), "file-history").size() == 1);
        FileHistoryView view = (FileHistoryView) FxHarness.call(
                () -> FxHarness.lookupAll(tab.getContent(), "file-history").getFirst());
        RobotWait.untilOnFx("the history never listed the file's four commits",
                () -> commitList(view).getItems().size() == 4);
        awaitRender();
        return view;
    }

    /** Selects the newest commit in the graph and opens {@code new-name.txt} from its changed files. */
    private void showTheRenamedFileOfTheNewestCommit() {
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(graphRowOf(editNew));
        });
        RobotWait.untilOnFx("the commit's changed files never listed new-name.txt",
                () -> changedFiles() != null && changedFiles().getItems().stream()
                        .anyMatch(change -> NEW.equals(change.path())));
        interact(() -> {
            ListView<GitBackend.FileChange> files = changedFiles();
            for (int i = 0; i < files.getItems().size(); i++) {
                if (NEW.equals(files.getItems().get(i).path())) {
                    files.getSelectionModel().select(i);
                }
            }
        });
        RobotWait.untilOnFx("new-name.txt never opened in the viewer",
                () -> NEW.equals(tab.diffViewer().headerPath()) && !historyButton().isDisabled());
        awaitRender();
    }

    private int graphRowOf(String hash) {
        List<GitGraphRow> rows = tab.graphList().getItems();
        for (int i = 0; i < rows.size(); i++) {
            CommitInfo commit = rows.get(i).getCommit();
            if (commit != null && hash.equals(commit.getHash())) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private ListView<GitBackend.FileChange> changedFiles() {
        return FxHarness.lookupAll(tab.getContent(), "changed-files-list").stream()
                .filter(ListView.class::isInstance)
                .map(node -> (ListView<GitBackend.FileChange>) node)
                .findFirst().orElse(null);
    }

    private Button historyButton() {
        return FxHarness.lookupAll(tab.diffViewer(), "diff-tool-button").stream()
                .filter(n -> n instanceof Button b && "File history".equals(b.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the viewer has no File history button"));
    }

    // ------------------------------------------------------------------
    // The history view
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ListView<CommitInfo> commitList(FileHistoryView view) {
        return (ListView<CommitInfo>) (ListView<?>) FxHarness.lookupAll(view, "file-history-list").getFirst();
    }

    private static DiffViewer embeddedViewer(FileHistoryView view) {
        return (DiffViewer) FxHarness.lookupAll(view, "diff-viewer").getFirst();
    }

    /** Selects the row for {@code hash} and waits for what that selection reads to be drawn. */
    private void select(FileHistoryView view, String hash) {
        interact(() -> {
            ListView<CommitInfo> commits = commitList(view);
            for (int i = 0; i < commits.getItems().size(); i++) {
                if (hash.equals(commits.getItems().get(i).getHash())) {
                    commits.getSelectionModel().select(i);
                    return;
                }
            }
            throw new AssertionError("the history does not list " + hash);
        });
        awaitRender();
    }

    /** The selection reads on the lane and draws in a callback: drain one, flush the other. */
    private void awaitRender() {
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ToggleButton segment(FileHistoryView view, String label) {
        return FxHarness.lookupAll(view, "diff-segment").stream()
                .filter(n -> n instanceof Labeled l && label.equals(l.getText()))
                .map(ToggleButton.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + label + "\" toggle found"));
    }

    private static String renderedText(FileHistoryView view) {
        return ((DiffTextArea) FxHarness.lookupAll(view, "code-area").getFirst()).getText();
    }

    /** What File view shows, as file content. */
    private static String contentShown(FileHistoryView view) {
        return renderedText(view);
    }

    /** Waits for File view to show {@code expected}; on timeout, fails showing what it does show. */
    private void awaitContent(FileHistoryView view, String expected, String what) {
        try {
            RobotWait.untilOnFx(what, () -> embeddedViewer(view).isFileView() && contentShown(view).equals(expected));
        } catch (AssertionError timedOut) {
            assertEquals(expected.replace("\n", "\\n"),
                    FxHarness.call(() -> contentShown(view)).replace("\n", "\\n"), what);
            throw timedOut;
        }
    }

    /** The message drawn in place of rows when a diff has no lines to show. */
    private static String emptyStateText(FileHistoryView view) {
        return FxHarness.lookupAll(view, "diff-empty").stream()
                .filter(n -> n instanceof Label l && l.isVisible())
                .map(n -> ((Label) n).getText())
                .findFirst().orElse("");
    }

    /** Every Revert hunk button {@code within} is showing. */
    private static List<Button> revertHunkButtons(javafx.scene.Node within) {
        return FxHarness.lookupAll(within, "diff-revert-hunk").stream()
                .filter(n -> n instanceof Button b && "Revert hunk".equals(b.getText())
                        && b.isVisible() && b.getParent() != null && b.getParent().isVisible())
                .map(Button.class::cast)
                .toList();
    }

    /**
     * Selects {@code hash} in Diff view and presses the Revert hunk of its one hunk,
     * returning the confirmation it raises.
     */
    private DialogPane revertTheOnlyHunkOf(FileHistoryView view, String hash) {
        select(view, hash);
        RobotWait.untilOnFx("the commit's hunk never offered Revert hunk",
                () -> revertHunkButtons(view).size() == 1);
        Button revert = FxHarness.call(() -> revertHunkButtons(view).getFirst());
        // Not interact(): the confirmation's nested event loop would hold it open.
        Platform.runLater(revert::fire);
        return RobotWait.untilDialogWithButton("OK");
    }

    private void press(DialogPane pane, ButtonType type) {
        interact(() -> ((Button) pane.lookupButton(type)).fire());
    }

    private Label statusMessage() {
        return (Label) FxHarness.lookupAll(tab.getContent(), "status-message").getFirst();
    }

    // ------------------------------------------------------------------
    // The repository, read independently of the engine under test
    // ------------------------------------------------------------------

    /** {@code path}'s bytes in {@code commit}, or null where the commit has no such path. */
    private String blobAt(String commit, String path) throws Exception {
        try (Git git = Git.open(work.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            var tree = walk.parseCommit(git.getRepository().resolve(commit)).getTree();
            try (TreeWalk entry = TreeWalk.forPath(git.getRepository(), path, tree)) {
                return entry == null ? null
                        : new String(git.getRepository().open(entry.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** The 1-based numbers of the lines that differ between two texts of equal length. */
    private static List<Integer> changedLines(String from, String to) {
        String[] a = from.split("\n", -1);
        String[] b = to.split("\n", -1);
        assertEquals(a.length, b.length, "a hunk restore here replaces lines one for one");
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i])) changed.add(i + 1);
        }
        return changed;
    }

    private String read(String file) throws Exception {
        return Files.readString(work.resolve(file), StandardCharsets.UTF_8);
    }

    private void write(String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    }

    private static String commitAll(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        // A second pass with setUpdate stages the deletion half of the rename.
        git.add().setUpdate(true).addFilepattern(".").call();
        return git.commit().setMessage(message).call().getName();
    }

    /**
     * Twenty NATO words, one a line, with each {@code from, to} pair in {@code edits}
     * replacing the line {@code from}.
     */
    private static String lines(List<String> edits) {
        List<String> words = new ArrayList<>(List.of("alpha", "bravo", "charlie", "delta", "echo",
                "foxtrot", "golf", "hotel", "india", "juliet", "kilo", "lima", "mike", "november",
                "oscar", "papa", "quebec", "romeo", "sierra", "tango"));
        for (int i = 0; i < edits.size(); i += 2) {
            words.set(words.indexOf(edits.get(i)), edits.get(i + 1));
        }
        return String.join("\n", words) + "\n";
    }
}
