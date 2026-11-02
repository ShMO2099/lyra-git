package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.model.CommitInfo;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E14's follow-up in {@code manual-test-artifacts/qa-scenario-improvements.md}: a whole
 * historical version of a file that has been renamed since, restored or opened from each
 * place the UI offers it, judged by which file on disk it reached - read through JGit
 * directly rather than through the engine under test.
 *
 * <p>The fixture is {@link IncompleteQaRenameHistoryRobotTest}'s: {@link #ADD} writes
 * {@code old-name.txt} (twenty NATO words, one a line) beside {@code other.txt} and
 * {@code notes.txt}; {@link #EDIT_OLD} rewrites its third line; {@link #RENAME} moves it to
 * {@code new-name.txt} byte for byte and edits {@code other.txt}; {@link #EDIT_NEW} rewrites
 * the renamed file's sixteenth line. Every journey starts from uncommitted work of every
 * kind beside it; see {@link #makeLocalChanges}.
 *
 * <p>Two kinds of route, two behaviours:
 * <ul>
 *   <li>A commit's own file list - "Restore this file" on its row, "Restore this version" in
 *       the viewer it opens, and a search result opened at that revision - names the file as
 *       the commit had it. Writing the version under that name recreated {@code old-name.txt}
 *       beside the renamed file without a word; writing it into {@code new-name.txt} would be
 *       a guess too. So when HEAD no longer has the name and rename detection finds what it
 *       became, the confirmation offers both, today's name first and as the default. A file
 *       still under its name is asked about exactly as before.</li>
 *   <li>File History follows the file across renames, so everything it opens is today's
 *       file: "Open externally", and Edit with an external editor chosen, are given
 *       {@code new-name.txt} on a pre-rename row. They were given {@code old-name.txt}, which
 *       is not on disk.</li>
 * </ul>
 *
 * <p>Opening a file externally goes through {@link RepoTab#setFileOpenerForTest}: the real
 * opener starts the platform's editor, which a headless test must never do.
 *
 * <p>JGit is the engine here; {@link IncompleteQaRenamedFileRestoreNativeRobotTest} runs the
 * same journeys on the git CLI, which writes for the default Hybrid engine.
 */
class IncompleteQaRenamedFileRestoreRobotTest extends ApplicationTest {

    private static final String OLD = "old-name.txt";
    private static final String NEW = "new-name.txt";

    private static final String ADD = "add old-name.txt";
    private static final String EDIT_OLD = "edit old-name.txt";
    private static final String RENAME = "rename old-name.txt to new-name.txt";
    private static final String EDIT_NEW = "edit new-name.txt";

    /** As {@link #EDIT_OLD} left it, and as {@link #RENAME} moved it. */
    private static final String V2 = lines(List.of("charlie", "charlie, edited before the rename"));
    /** As {@link #EDIT_NEW} left it: HEAD's copy. */
    private static final String V4 = lines(List.of("charlie", "charlie, edited before the rename",
            "papa", "papa, edited after the rename"));
    /** HEAD's copy with an uncommitted edit. */
    private static final String V4_EDITED = V4.replace("juliet\n", "juliet, edited but not committed\n");

    private static final String OTHER_FIRST = "other, as first committed\n";
    private static final String OTHER_RENAMED = "other, edited in the rename commit\n";
    private static final String OTHER_EDITED = "other, edited but not committed\n";
    private static final String NOTES = "notes, as first committed\n";

    /** The two options the rename question offers, as their cards name them. */
    private static final String INTO_NEW = "Into " + NEW;
    private static final String RECREATE_OLD = "Recreate " + OLD;

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;
    private String add;
    private String editOld;
    private String editNew;
    private AppPreferences.EditorMode originalEditorMode;
    /** Every file handed to "open externally", in order. */
    private final List<File> opened = new java.util.concurrent.CopyOnWriteArrayList<>();

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
                write(OLD, lines(List.of()));
                write("other.txt", OTHER_FIRST);
                write("notes.txt", NOTES);
                add = commitAll(git, ADD);
                write(OLD, V2);
                editOld = commitAll(git, EDIT_OLD);
                Files.delete(work.resolve(OLD));
                write(NEW, V2);
                write("other.txt", OTHER_RENAMED);
                commitAll(git, RENAME);
                write(NEW, V4);
                editNew = commitAll(git, EDIT_NEW);
            }
            backend = openEngine(work);
        }
        // "Restore this version" and "Open externally" are the built-in editor's controls.
        originalEditorMode = AppPreferences.editorMode();
        AppPreferences.setEditorMode(AppPreferences.EditorMode.BUILT_IN);
        tab = new RepoTab(new LyraApp(), "renamed file restore", backend);
        tab.setFileOpenerForTest(file -> {
            opened.add(file);
            return GitResult.ok("Opened " + file.getName());
        });
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheGraphOverLocalWork() throws Exception {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the graph never listed the four commits", () -> graphRowOf(editNew) >= 0);
        makeLocalChanges();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        AppPreferences.setEditorMode(originalEditorMode);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    /**
     * Work of every kind beside the file being restored: an unstaged edit to the renamed
     * file, an unstaged edit to another file, a staged edit with a further unstaged edit on
     * top of it, and an untracked file.
     */
    private void makeLocalChanges() throws Exception {
        write(NEW, V4_EDITED);
        write("other.txt", OTHER_EDITED);
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
    // A commit's file list: "Restore this file"
    // ------------------------------------------------------------------

    @Test
    void restoringAPreRenameFileFromACommitsFileListWritesThatVersionIntoTheRenamedFileByDefault()
            throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        DialogPane confirm = restoreThisFile(editOld, OLD, "OK");
        assertOffersBothNames(confirm);
        restoreChoosing(confirm, INTO_NEW);
        awaitBytes(NEW, V2, "the version never reached new-name.txt");
        RobotWait.drainGitWork();

        assertEquals(V2, read(NEW), "the renamed file holds that commit's version, byte for byte");
        assertFalse(Files.exists(work.resolve(OLD)), "and no copy appears under the old name");
        assertOnlyThisFileChanged(before, NEW);
        assertStatus(Set.of(NEW, "other.txt", "notes.txt"), Set.of("scratch.txt"));
    }

    @Test
    void restoringAPreRenameFileFromACommitsFileListCanStillRecreateTheOldNameBesideTheRenamedFile()
            throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        restoreChoosing(restoreThisFile(editOld, OLD, "OK"), RECREATE_OLD);
        awaitBytes(OLD, V2, "old-name.txt was never recreated");
        RobotWait.drainGitWork();

        assertEquals(V2, read(OLD), "the old name comes back with that commit's version");
        assertEquals(V4_EDITED, read(NEW), "the renamed file, uncommitted edit and all, is not touched");
        assertOnlyThisFileChanged(before, OLD);
        assertStatus(Set.of(NEW, "other.txt", "notes.txt"), Set.of(OLD, "scratch.txt"));
    }

    @Test
    void cancellingTheRestoreOfAPreRenameFileFromACommitsFileListChangesNothing() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        press(restoreThisFile(editOld, OLD, "OK"), "Cancel");
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "a cancelled restore must change nothing");
        assertFalse(Files.exists(work.resolve(OLD)));
    }

    /** The rename question is only for a file that has one: everything else asks as it did. */
    @Test
    void aFileThatStillHasItsNameIsRestoredWithoutTheRenameQuestion() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        DialogPane confirm = restoreThisFile(add, "other.txt", "OK");
        assertEquals(List.of("OK", "Cancel"), buttonTexts(confirm), "the plain confirmation, unchanged");
        assertTrue(confirm.getHeaderText().startsWith("Restore other.txt from "), confirm.getHeaderText());
        press(confirm, "OK");
        awaitBytes("other.txt", OTHER_FIRST, "other.txt was never restored");
        RobotWait.drainGitWork();

        assertOnlyThisFileChanged(before, "other.txt");
        assertEquals(V4_EDITED, read(NEW));
        assertFalse(Files.exists(work.resolve(OLD)));
    }

    // ------------------------------------------------------------------
    // The viewer a commit's file opens in: "Restore this version"
    // ------------------------------------------------------------------

    @Test
    void restoreThisVersionOnAPreRenameRevisionWritesIntoTheRenamedFileByDefault() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);
        openFromCommit(editOld, OLD);

        DialogPane confirm = restoreThisVersion("OK");
        assertEquals("Restore this version of " + OLD + "?", confirm.getHeaderText());
        assertOffersBothNames(confirm);
        restoreChoosing(confirm, INTO_NEW);
        awaitBytes(NEW, V2, "the version never reached new-name.txt");
        RobotWait.drainGitWork();

        assertFalse(Files.exists(work.resolve(OLD)), "no copy appears under the old name");
        assertOnlyThisFileChanged(before, NEW);
        assertStatus(Set.of(NEW, "other.txt", "notes.txt"), Set.of("scratch.txt"));
    }

    @Test
    void restoreThisVersionOnAPreRenameRevisionCanStillRecreateTheOldName() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);
        openFromCommit(editOld, OLD);

        restoreChoosing(restoreThisVersion("OK"), RECREATE_OLD);
        awaitBytes(OLD, V2, "old-name.txt was never recreated");
        RobotWait.drainGitWork();

        assertEquals(V4_EDITED, read(NEW), "the renamed file is not touched");
        assertOnlyThisFileChanged(before, OLD);
        assertStatus(Set.of(NEW, "other.txt", "notes.txt"), Set.of(OLD, "scratch.txt"));
    }

    @Test
    void cancellingRestoreThisVersionOnAPreRenameRevisionChangesNothing() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);
        openFromCommit(editOld, OLD);

        press(restoreThisVersion("OK"), "Cancel");
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "a cancelled restore must change nothing");
    }

    // ------------------------------------------------------------------
    // The same viewer: opening and editing the working copy
    // ------------------------------------------------------------------

    /**
     * "Open externally" opens the working copy, and a pre-rename revision's working copy is
     * the renamed file: old-name.txt is not on disk, so handing it to the system opener
     * failed.
     */
    @Test
    void openingAPreRenameFileExternallyFromACommitOpensTheRenamedFile() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);
        openFromCommit(editOld, OLD);

        interact(() -> toolButton(tab.diffViewer(), "Open externally").fire());
        RobotWait.until("nothing was opened", () -> !opened.isEmpty());
        RobotWait.drainGitWork();

        assertEquals(List.of(NEW), openedInTheWorkTree(), "the working copy is under today's name");
        assertEquals(before, RepoSnapshot.of(work), "opening a file changes nothing");
    }

    @Test
    void theExternalEditorOnAPreRenameFileFromACommitIsGivenTheRenamedFile() throws Exception {
        AppPreferences.setEditorMode(AppPreferences.EditorMode.SYSTEM_DEFAULT);
        RepoSnapshot before = RepoSnapshot.of(work);
        // Not openFromCommit: with an external editor there is no "Restore this version"
        // to wait for, so wait for the file and the editor's own button instead.
        selectCommit(editOld, OLD);
        interact(() -> {
            ListView<GitBackend.FileChange> files = changedFiles();
            for (int i = 0; i < files.getItems().size(); i++) {
                if (OLD.equals(files.getItems().get(i).path())) files.getSelectionModel().select(i);
            }
        });
        RobotWait.untilOnFx("old-name.txt never opened with an external editor's Edit offered",
                () -> OLD.equals(tab.diffViewer().headerPath())
                        && toolButton(tab.diffViewer(), "Edit this file").isVisible());
        RobotWait.drainGitWork();

        interact(() -> toolButton(tab.diffViewer(), "Edit this file").fire());
        RobotWait.until("nothing was opened", () -> !opened.isEmpty());
        RobotWait.drainGitWork();

        assertEquals(List.of(NEW), openedInTheWorkTree());
        assertEquals(before, RepoSnapshot.of(work), "opening a file changes nothing");
    }

    /**
     * "Edit working copy" edits the working copy - for a file renamed since, the renamed
     * file, uncommitted work and all - and Save writes it there. It used to find nothing
     * under the old name, start from the commit's version, and recreate old-name.txt on Save.
     */
    @Test
    void editingTheWorkingCopyOfAPreRenameFileFromACommitEditsTheRenamedFile() throws Exception {
        AppPreferences.setEditorMode(AppPreferences.EditorMode.BUILT_IN);
        openFromCommit(editOld, OLD);

        interact(() -> toolButton(tab.diffViewer(), "Edit working copy").fire());
        RobotWait.untilOnFx("the renamed file was never opened for editing",
                () -> tab.diffViewer().isEditing() && NEW.equals(tab.diffViewer().headerPath()));
        assertEquals(V4_EDITED, FxHarness.call(() -> tab.diffViewer().editorText()),
                "editing starts from today's file on disk, its uncommitted edit included");

        String edited = V4_EDITED + "a line added while editing\n";
        DiffTextArea area = FxHarness.call(() -> (DiffTextArea) tab.diffViewer().lookup(".code-area"));
        interact(() -> area.replaceText(edited));
        interact(() -> toolButton(tab.diffViewer(), "Save").fire());
        awaitBytes(NEW, edited, "Save never wrote new-name.txt");
        RobotWait.drainGitWork();

        assertFalse(Files.exists(work.resolve(OLD)), "the old name is not recreated");
    }

    /** A search result opened at a revision is that commit's file too, under that commit's name. */
    @Test
    void aSearchResultAtAPreRenameRevisionRestoresIntoTheRenamedFileToo() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);
        interact(() -> tab.openFileAt(editOld, OLD, 3));
        awaitRestoreThisVersion(OLD);

        restoreChoosing(restoreThisVersion("OK"), INTO_NEW);
        awaitBytes(NEW, V2, "the version never reached new-name.txt");
        RobotWait.drainGitWork();

        assertFalse(Files.exists(work.resolve(OLD)));
        assertOnlyThisFileChanged(before, NEW);
    }

    // ------------------------------------------------------------------
    // File History: opening a pre-rename revision externally
    // ------------------------------------------------------------------

    @Test
    void openingAPreRenameRevisionExternallyFromFileHistoryOpensTheRenamedFile() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);
        FileHistoryView view = historyAtThePreRenameRow();

        interact(() -> toolButton(embeddedViewer(view), "Open externally").fire());
        RobotWait.until("nothing was opened", () -> !opened.isEmpty());
        RobotWait.drainGitWork();

        assertEquals(List.of(NEW), openedInTheWorkTree(),
                "File History follows the file, so it opens the file as it is called today");
        assertEquals(before, RepoSnapshot.of(work), "opening a file changes nothing");
    }

    @Test
    void theExternalEditorOpenedFromFileHistoryOnAPreRenameRevisionIsGivenTheRenamedFile() throws Exception {
        AppPreferences.setEditorMode(AppPreferences.EditorMode.SYSTEM_DEFAULT);
        RepoSnapshot before = RepoSnapshot.of(work);
        FileHistoryView view = historyAtThePreRenameRow();

        // With an external editor chosen, Edit is the button that opens it.
        Button edit = toolButton(embeddedViewer(view), "Edit this file");
        assertTrue(FxHarness.call(edit::isVisible), "an external editor's Edit is offered in File History");
        interact(edit::fire);
        RobotWait.until("nothing was opened", () -> !opened.isEmpty());
        RobotWait.drainGitWork();

        assertEquals(List.of(NEW), openedInTheWorkTree());
        assertEquals(before, RepoSnapshot.of(work), "opening a file changes nothing");
    }

    /**
     * What was opened, as paths in the work tree. Compared through the real path of the
     * folder, because the engine may spell the work tree differently from {@link #work};
     * the file itself may not exist, which is the defect this guards.
     */
    private List<String> openedInTheWorkTree() throws Exception {
        Path workTree = work.toRealPath();
        List<String> names = new ArrayList<>();
        for (File file : opened) {
            Path folder = file.getAbsoluteFile().getParentFile().toPath().toRealPath();
            names.add(folder.equals(workTree) ? file.getName() : file.getPath());
        }
        return names;
    }

    // ------------------------------------------------------------------
    // The routes
    // ------------------------------------------------------------------

    /**
     * Selects {@code commit} in the graph and fires "Restore this file" on {@code path}'s row
     * of its changed files, through the row's own context menu, returning the confirmation
     * once it offers {@code button}.
     */
    private DialogPane restoreThisFile(String commit, String path, String button) {
        selectCommit(commit, path);
        ListCell<?> cell = changedFileCell(path);
        MenuItem[] item = new MenuItem[1];
        interact(() -> {
            ContextMenu menu = cell.getContextMenu();
            // Shown and hidden again: the menu reads the row's file and the commit as it
            // opens, which is the part a bare fire() would skip.
            menu.show(cell, 0, 0);
            menu.hide();
            item[0] = menu.getItems().stream()
                    .filter(i -> "Restore this file".equals(i.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no \"Restore this file\" on " + path));
            assertTrue(item[0].isVisible(), "a commit's file offers Restore this file");
        });
        // Not interact(): the confirmation's nested event loop would hold it open.
        Platform.runLater(item[0]::fire);
        return RobotWait.untilDialogWithButton(button);
    }

    /** Selects {@code commit} in the graph and opens {@code path} from its changed files. */
    private void openFromCommit(String commit, String path) {
        selectCommit(commit, path);
        interact(() -> {
            ListView<GitBackend.FileChange> files = changedFiles();
            for (int i = 0; i < files.getItems().size(); i++) {
                if (path.equals(files.getItems().get(i).path())) {
                    files.getSelectionModel().select(i);
                }
            }
        });
        awaitRestoreThisVersion(path);
    }

    private void awaitRestoreThisVersion(String path) {
        RobotWait.untilOnFx(path + " never opened with Restore this version offered",
                () -> path.equals(tab.diffViewer().headerPath())
                        && toolButton(tab.diffViewer(), "Restore this version").isVisible());
        // The revision itself arrives from the lane: drained, it is what the viewer holds.
        RobotWait.drainGitWork();
    }

    /** Presses the viewer's "Restore this version" and returns its confirmation. */
    private DialogPane restoreThisVersion(String button) {
        Button restore = FxHarness.call(() -> toolButton(tab.diffViewer(), "Restore this version"));
        Platform.runLater(restore::fire);
        return RobotWait.untilDialogWithButton(button);
    }

    /**
     * Opens File History from the newest commit's changed file, the way the rename-history
     * scenario does, and selects the commit made before the rename.
     */
    private FileHistoryView historyAtThePreRenameRow() {
        selectCommit(editNew, NEW);
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
        interact(() -> historyButton().fire());
        RobotWait.untilOnFx("File History never opened",
                () -> FxHarness.lookupAll(tab.getContent(), "file-history").size() == 1);
        FileHistoryView view = (FileHistoryView) FxHarness.call(
                () -> FxHarness.lookupAll(tab.getContent(), "file-history").getFirst());
        RobotWait.untilOnFx("the history never listed the file's four commits",
                () -> commitList(view).getItems().size() == 4);
        interact(() -> {
            ListView<CommitInfo> commits = commitList(view);
            for (int i = 0; i < commits.getItems().size(); i++) {
                if (editOld.equals(commits.getItems().get(i).getHash())) {
                    commits.getSelectionModel().select(i);
                }
            }
        });
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        // headerPath is the path the embedded viewer reads and acts on; see the rename-history test.
        RobotWait.untilOnFx("the pre-rename row never opened under its old name",
                () -> OLD.equals(embeddedViewer(view).headerPath()));
        return view;
    }

    private void selectCommit(String commit, String path) {
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(graphRowOf(commit));
        });
        RobotWait.untilOnFx("the commit's changed files never listed " + path,
                () -> changedFiles() != null && changedFiles().getItems().stream()
                        .anyMatch(change -> path.equals(change.path())));
        RobotWait.drainGitWork();
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

    private ListCell<?> changedFileCell(String path) {
        ListCell<?>[] found = new ListCell<?>[1];
        RobotWait.untilOnFx("no row with a menu for " + path, () -> {
            for (Node node : changedFiles().lookupAll(".list-cell")) {
                if (node instanceof ListCell<?> cell && cell.getItem() instanceof GitBackend.FileChange change
                        && path.equals(change.path()) && cell.getContextMenu() != null) {
                    found[0] = cell;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private Button historyButton() {
        return toolButton(tab.diffViewer(), "File history");
    }

    private static Button toolButton(Node root, String text) {
        return FxHarness.lookupAll(root, "diff-tool-button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + text + "\" button"));
    }

    @SuppressWarnings("unchecked")
    private static ListView<CommitInfo> commitList(FileHistoryView view) {
        return (ListView<CommitInfo>) (ListView<?>) FxHarness.lookupAll(view, "file-history-list").getFirst();
    }

    private static DiffViewer embeddedViewer(FileHistoryView view) {
        return (DiffViewer) FxHarness.lookupAll(view, "diff-viewer").getFirst();
    }

    // ------------------------------------------------------------------
    // The confirmation
    // ------------------------------------------------------------------

    /** Both destinations, named, with today's name as the one Enter picks. */
    private void assertOffersBothNames(DialogPane confirm) {
        assertEquals(List.of(INTO_NEW, RECREATE_OLD), FxHarness.call(() -> options(confirm).stream()
                        .map(RadioButton::getText).toList()),
                "the choice is between the two names, today's first");
        assertEquals(List.of("OK", "Cancel"), buttonTexts(confirm), "or neither");
        assertEquals(List.of(INTO_NEW), FxHarness.call(() -> options(confirm).stream()
                        .filter(RadioButton::isSelected).map(RadioButton::getText).toList()),
                "writing into the file as it is called now is the default");
        assertTrue(FxHarness.call(() -> ((Button) confirm.lookupButton(ButtonType.OK)).isDefaultButton()),
                "and Enter restores into it");
        assertEquals("Restore", FxHarness.call(() -> ((Button) confirm.lookupButton(ButtonType.OK)).getText()));
        String title = confirm.getHeaderText();
        assertTrue(title.contains(OLD), "the question names the file as the commit had it: " + title);
    }

    /** The rename question's options, in the order it lists them. Call on FX. */
    private static List<RadioButton> options(DialogPane pane) {
        return FxHarness.lookupAll(pane, "jfx-option-radio").stream().map(RadioButton.class::cast).toList();
    }

    /** Picks the option named {@code option} and presses Restore. */
    private void restoreChoosing(DialogPane pane, String option) {
        interact(() -> {
            RadioButton chosen = options(pane).stream().filter(radio -> option.equals(radio.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no \"" + option + "\" option; the dialog offers "
                            + options(pane).stream().map(RadioButton::getText).toList()));
            chosen.setSelected(true);
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
        });
    }

    private static List<String> buttonTexts(DialogPane pane) {
        return FxHarness.call(() -> {
            List<String> texts = new ArrayList<>();
            for (ButtonType type : pane.getButtonTypes()) {
                texts.add(type.getText());
            }
            // In the order they are declared, not the platform's button-bar order.
            return texts;
        });
    }

    private static ButtonType type(DialogPane pane, String text) {
        return pane.getButtonTypes().stream()
                .filter(bt -> text.equals(bt.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + text + "\" button; the dialog offers "
                        + pane.getButtonTypes().stream().map(ButtonType::getText).toList()));
    }

    private void press(DialogPane pane, String text) {
        interact(() -> ((Button) pane.lookupButton(type(pane, text))).fire());
    }

    // ------------------------------------------------------------------
    // The repository, read independently of the engine under test
    // ------------------------------------------------------------------

    private void awaitBytes(String file, String expected, String what) {
        RobotWait.until(what, () -> {
            try {
                return expected.equals(read(file));
            } catch (Exception absentOrMidWrite) {
                return false;
            }
        });
    }

    /**
     * Nothing but {@code file}'s bytes moved: every ref, HEAD and its reflog, the index -
     * staged work included - the repository state, and every other file.
     */
    private void assertOnlyThisFileChanged(RepoSnapshot before, String file) throws Exception {
        RepoSnapshot after = RepoSnapshot.of(work);
        assertEquals(before.branch(), after.branch());
        assertEquals(before.refs(), after.refs(), "no commit is made");
        assertEquals(before.headReflog(), after.headReflog(), "HEAD does not move");
        assertEquals(before.index(), after.index(), "the index - staged work included - is untouched");
        assertEquals(before.state(), after.state());
        Map<String, String> othersBefore = new TreeMap<>(before.files());
        Map<String, String> othersAfter = new TreeMap<>(after.files());
        othersBefore.remove(file);
        othersAfter.remove(file);
        assertEquals(othersBefore, othersAfter, "every other file keeps its bytes");
    }

    private void assertStatus(Set<String> modified, Set<String> untracked) throws Exception {
        try (Git git = Git.open(work.toFile())) {
            Status status = git.status().call();
            assertEquals(modified, status.getModified(), "unstaged");
            assertEquals(Set.of("notes.txt"), status.getChanged(), "staged: only what was staged before");
            assertTrue(status.getAdded().isEmpty() && status.getRemoved().isEmpty(),
                    "nothing else is staged: " + status.getAdded() + status.getRemoved());
            assertEquals(untracked, status.getUntracked());
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
