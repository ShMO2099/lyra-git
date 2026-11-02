package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.HistoryOps;
import com.jfx.git.core.RepositoryPreferences;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.core.WorkingTreeSnapshots;
import com.jfx.git.model.Snapshot;
import com.jfx.git.ui.views.SnapshotsView;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E28 in {@code manual-test-artifacts/qa-scenario-improvements.md}: two working-tree
 * snapshots of different mixed states, one picked from the snapshot list, compared,
 * restored or deleted, and the other left whole - judged by the repository afterwards: the
 * bytes on disk, the index, the refs and the snapshot refs and trees, read through JGit
 * directly rather than through the engine that did the work.
 *
 * <p><b>How a snapshot is taken.</b> The application has no "take a snapshot" action: it
 * takes them on a timer and just before anything that throws work away. So each one here
 * is taken the way a user gets one - by discarding a throwaway edit to {@code scratch.txt}
 * from its row in the staging list, which snapshots the whole working tree first. A
 * snapshot therefore also holds that throwaway edit, and the state the user is left in is
 * the snapshot's state with {@code scratch.txt} back as committed.
 *
 * <p><b>What a snapshot promises</b> ({@code IntegrationOps.snapshotWorkingTree} and
 * {@code restoreFromSnapshot}, and the caption over the list): the working tree's bytes -
 * tracked files as they are on disk, whether staged or not, and untracked files, but not
 * ignored ones - and a restore that writes those bytes back, leaves the index alone and
 * deletes nothing the snapshot does not have. The staged/unstaged split is not part of a
 * snapshot, so the tests assert that the index is exactly as the restore found it rather
 * than as it was when the snapshot was taken.
 *
 * <p>The fixture is one commit of {@code one.txt}, {@code two.txt}, {@code scratch.txt}
 * and a {@code .gitignore} for {@code *.log}, then:
 * <ul>
 *   <li>state A: {@code one.txt} edited and staged, {@code two.txt} edited and not staged,
 *       {@code notes.txt} untracked, {@code build.log} ignored;</li>
 *   <li>state B: {@code one.txt} edited again and unstaged, {@code two.txt} staged and
 *       then edited again, {@code notes.txt} rewritten, {@code extra.txt} new and
 *       untracked, {@code build.log} rewritten.</li>
 * </ul>
 *
 * <p>JGit is the engine here; {@link IncompleteQaSnapshotFidelityNativeRobotTest} runs the
 * same journeys on the git CLI, which takes and restores snapshots for the default Hybrid
 * engine with git's own plumbing rather than JGit's.
 */
class IncompleteQaSnapshotFidelityRobotTest extends ApplicationTest {

    private static final String IGNORE = "*.log\n";
    private static final String ONE_BASE = "one\n";
    private static final String TWO_BASE = "two\n";
    private static final String SCRATCH_BASE = "scratch\n";

    // Every version of a file is a different length from every other, so no comparison of
    // a file against the index can pass on a racily clean timestamp.
    private static final String ONE_A = "one, staged in A\n";
    private static final String TWO_A = "two, edited but not staged in A\n";
    private static final String NOTES_A = "notes, untracked in A\n";
    private static final String LOG_A = "ignored log from A\n";
    private static final String SCRATCH_A = "throwaway edit in A\n";

    private static final String ONE_B = "one, edited again and unstaged in B\n";
    private static final String TWO_STAGED_B = "two, staged in B\n";
    private static final String TWO_B = "two, staged in B and edited again\n";
    private static final String NOTES_B = "notes, still untracked in B\n";
    private static final String EXTRA_B = "only in B\n";
    private static final String LOG_B = "ignored log from state B\n";
    private static final String SCRATCH_B = "throwaway edit made in B\n";

    private static final String DISCARD_REASON = "Before discarding scratch.txt";

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;

    /** Snapshot A's and B's ids - their refs' names - and commits. */
    private String idA;
    private String idB;
    private String commitA;
    private String commitB;
    /** The repository as the user has it once B is taken: state B, scratch.txt discarded. */
    private RepoSnapshot stateB;

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
                write("one.txt", ONE_BASE);
                write("two.txt", TWO_BASE);
                write("scratch.txt", SCRATCH_BASE);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("base").call();

                // State A.
                write("one.txt", ONE_A);
                git.add().addFilepattern("one.txt").call();
                write("two.txt", TWO_A);
                write("notes.txt", NOTES_A);
                write("build.log", LOG_A);
            }
            backend = openEngine(work);
        }
        tab = new RepoTab(new LyraApp(), "snapshot fidelity", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheTab() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        RepositoryPreferences.clear(work.toFile());
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    @Test
    void theOlderSnapshotIsComparedWithTheFilesNowAndCancellingItsRestoreChangesNothing() throws Exception {
        takeSnapshotsAAndB();
        SnapshotsView view = openTheListFromTheToast();
        ListView<Snapshot> list = listOf(view);

        // Both are listed newest first, with what tells them apart: the same reason, but
        // their own file counts and, on hover, their own commits.
        assertEquals(List.of(idB, idA), FxHarness.call(() -> ids(list.getItems())));
        String rowA = rowText(list, idA);
        String rowB = rowText(list, idB);
        assertTrue(rowA.startsWith(DISCARD_REASON + " | main · 4 files · "), rowA);
        assertTrue(rowA.endsWith(" · " + commitA.substring(0, 7)), rowA);
        assertTrue(rowB.startsWith(DISCARD_REASON + " | main · 5 files · "), rowB);
        assertTrue(rowB.endsWith(" · " + commitB.substring(0, 7)), rowB);

        select(list, idA);
        RobotWait.untilOnFx("snapshot A was never compared with the files now", () -> describe(view.shownChanges())
                .equals(List.of("extra.txt A +1 -0", "notes.txt M +1 -1", "one.txt M +1 -1",
                        "scratch.txt M +1 -1", "two.txt M +1 -1")));
        // extra.txt is only on disk: there is nothing of it in A to put back.
        assertTrue(FxHarness.call(() -> button(view, SnapshotsView.RESTORE_FILE_ID).isDisabled()),
                "a file the snapshot does not have cannot be restored from it");

        showFile(view, "two.txt");
        DiffViewer diff = diffViewerOf(view);
        RobotWait.untilOnFx("two.txt was never shown as snapshot A against the working tree",
                () -> "two.txt".equals(diff.filePathProperty().get())
                        && diff.editorText().contains(TWO_A.strip())
                        && diff.editorText().contains(TWO_B.strip()));
        assertFalse(FxHarness.call(() -> button(view, SnapshotsView.RESTORE_FILE_ID).isDisabled()));

        RepoSnapshot before = RepoSnapshot.of(work);
        Map<String, String> snapshotsBefore = snapshotRefs();
        Button restoreAll = button(view, SnapshotsView.RESTORE_ALL_ID);
        RobotWait.untilOnFx("Restore all never offered itself for A", () -> !restoreAll.isDisabled());
        // Not interact(): the confirmation is modal.
        Platform.runLater(restoreAll::fire);
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        String question = FxHarness.call(confirm::getContentText);
        assertTrue(question.contains("files the snapshot does not have are left alone"), question);
        press(confirm, "Cancel");
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work),
                "a cancelled restore leaves the files, the index, the refs and HEAD's reflog as they were");
        assertEquals(snapshotsBefore, snapshotRefs(), "and takes no snapshot of its own");
        assertEquals(stateB, before, "state B is what was on screen when the restore was cancelled");
    }

    @Test
    void restoringTheOlderSnapshotWritesBackItsFilesAndLeavesTheIndexAsItWas() throws Exception {
        takeSnapshotsAAndB();
        SnapshotsView view = openTheListFromTheToast();

        restore(view, idA);
        RobotWait.until("snapshot A was never written back", () -> ONE_A.equals(read("one.txt"))
                && TWO_A.equals(read("two.txt")) && SCRATCH_A.equals(read("scratch.txt")));
        RobotWait.drainGitWork();

        Map<String, String> files = new TreeMap<>(stateB.files());
        files.putAll(Map.of("one.txt", ONE_A, "two.txt", TWO_A, "notes.txt", NOTES_A, "scratch.txt", SCRATCH_A));
        // extra.txt is not in A and build.log is in no snapshot: both are left as B has them.
        assertEquals(EXTRA_B, files.get("extra.txt"));
        assertEquals(LOG_B, files.get("build.log"));
        assertEquals(new RepoSnapshot(stateB.branch(), stateB.refs(), stateB.upstream(), stateB.index(),
                        files, stateB.state(), stateB.headReflog()), RepoSnapshot.of(work),
                "A's bytes are back, and nothing else moved: not the index, not a ref, not HEAD");

        // So A's staging is not: one.txt, staged in A, comes back as an unstaged edit on
        // top of the index B left, and two.txt keeps B's staged version under A's bytes.
        Status status = status();
        assertEquals(Set.of("two.txt"), status.getChanged(), "only what B staged is staged");
        assertEquals(Set.of("one.txt", "two.txt", "scratch.txt"), status.getModified());
        assertEquals(Set.of("notes.txt", "extra.txt"), status.getUntracked());
        assertEquals(Set.of("build.log"), status.getIgnoredNotInIndex());

        // The restore was snapshotted first, so it can itself be undone from the list.
        Map<String, String> snapshots = snapshotRefs();
        assertEquals(3, snapshots.size(), snapshots.toString());
        assertEquals(commitA, snapshots.get(idA), "A is still there, unchanged");
        assertEquals(commitB, snapshots.get(idB), "B is still there, unchanged");
        String guard = newestId(snapshots);
        assertEquals("Before restoring a snapshot", reasonOf(snapshots.get(guard)));
        assertEquals(Map.of(".gitignore", IGNORE, "one.txt", ONE_B, "two.txt", TWO_B, "notes.txt", NOTES_B,
                        "extra.txt", EXTRA_B, "scratch.txt", SCRATCH_BASE),
                filesIn(snapshots.get(guard)), "it holds the files as they were just before the restore");
        RobotWait.untilOnFx("the list never showed the restore's own snapshot",
                () -> ids(view.loadedSnapshots()).equals(List.of(guard, idB, idA)));
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    @Test
    void cancellingTheDeleteOfTheOlderSnapshotKeepsBothSnapshots() throws Exception {
        takeSnapshotsAAndB();
        SnapshotsView view = openTheListFromTheToast();
        select(listOf(view), idA);
        RepoSnapshot before = RepoSnapshot.of(work);
        Map<String, String> snapshotsBefore = snapshotRefs();

        Button delete = button(view, SnapshotsView.DELETE_ID);
        RobotWait.untilOnFx("Delete never offered itself for A", () -> !delete.isDisabled());
        Platform.runLater(delete::fire);
        DialogPane confirm = RobotWait.untilDialogWithButton("Cancel");
        String question = FxHarness.call(confirm::getHeaderText);
        assertTrue(question.contains(DISCARD_REASON), "the question names the snapshot: " + question);
        press(confirm, "Cancel");
        RobotWait.drainGitWork();

        assertEquals(snapshotsBefore, snapshotRefs(), "a cancelled delete keeps every snapshot ref");
        assertEquals(List.of(idB, idA), FxHarness.call(() -> ids(view.loadedSnapshots())),
                "and the list still shows both");
        assertEquals(before, RepoSnapshot.of(work), "and touches nothing else");
    }

    @Test
    void deletingTheOlderSnapshotKeepsTheNewerOneWhichStillRestoresItsFiles() throws Exception {
        takeSnapshotsAAndB();
        SnapshotsView view = openTheListFromTheToast();
        // Away from B first, so restoring B afterwards has every file to put back.
        restore(view, idA);
        RobotWait.until("snapshot A was never written back", () -> ONE_A.equals(read("one.txt")));
        RobotWait.drainGitWork();
        String guard = newestId(snapshotRefs());
        RobotWait.untilOnFx("the list never settled after the restore",
                () -> ids(view.loadedSnapshots()).equals(List.of(guard, idB, idA)));

        select(listOf(view), idA);
        Button delete = button(view, SnapshotsView.DELETE_ID);
        RobotWait.untilOnFx("Delete never offered itself for A", () -> !delete.isDisabled());
        Platform.runLater(delete::fire);
        press(RobotWait.untilDialogWithButton("OK"), "OK");
        RobotWait.until("snapshot A was never deleted", () -> !snapshotRefs().containsKey(idA));
        RobotWait.drainGitWork();

        Map<String, String> snapshots = snapshotRefs();
        assertEquals(Set.of(guard, idB), snapshots.keySet(), "only the selected snapshot is deleted");
        assertEquals(commitB, snapshots.get(idB), "B's ref still names the same commit");
        assertEquals(Map.of(".gitignore", IGNORE, "one.txt", ONE_B, "two.txt", TWO_B, "notes.txt", NOTES_B,
                "extra.txt", EXTRA_B, "scratch.txt", SCRATCH_B), filesIn(commitB), "and B's files are intact");
        RobotWait.untilOnFx("the list still showed A, or lost B",
                () -> ids(view.loadedSnapshots()).equals(List.of(guard, idB)));

        restore(view, idB);
        RobotWait.until("snapshot B was never written back", () -> ONE_B.equals(read("one.txt"))
                && TWO_B.equals(read("two.txt")) && SCRATCH_B.equals(read("scratch.txt")));
        RobotWait.drainGitWork();

        Map<String, String> files = new TreeMap<>(stateB.files());
        files.put("scratch.txt", SCRATCH_B);
        assertEquals(new RepoSnapshot(stateB.branch(), stateB.refs(), stateB.upstream(), stateB.index(),
                        files, stateB.state(), stateB.headReflog()), RepoSnapshot.of(work),
                "B's bytes are back - its throwaway edit too - and the index is still the one B left");
    }

    // ------------------------------------------------------------------
    // Taking the snapshots
    // ------------------------------------------------------------------

    /**
     * Takes A from state A, moves the repository to state B and takes B - each by
     * discarding a throwaway edit to scratch.txt - and checks each snapshot holds the
     * working tree it was taken from.
     */
    private void takeSnapshotsAAndB() throws Exception {
        write("scratch.txt", SCRATCH_A);
        RepoSnapshot a = RepoSnapshot.of(work);
        idA = discardScratch();
        commitA = snapshotRefs().get(idA);
        assertEquals(Map.of(".gitignore", IGNORE, "one.txt", ONE_A, "two.txt", TWO_A, "notes.txt", NOTES_A,
                        "scratch.txt", SCRATCH_A), filesIn(commitA),
                "A holds the working tree's bytes, staged or not, and untracked files, but no ignored one");
        assertEquals(a.index(), RepoSnapshot.of(work).index(), "taking it, and the discard, left the index alone");

        // State B: a different split between index and working tree, different bytes, a
        // new untracked file. Edited from outside, as another tool would.
        try (Git git = Git.open(work.toFile())) {
            write("one.txt", ONE_B);
            git.reset().addPath("one.txt").call();
            write("two.txt", TWO_STAGED_B);
            git.add().addFilepattern("two.txt").call();
            write("two.txt", TWO_B);
            write("notes.txt", NOTES_B);
            write("extra.txt", EXTRA_B);
            write("build.log", LOG_B);
            write("scratch.txt", SCRATCH_B);
        }
        RepoSnapshot b = RepoSnapshot.of(work);
        idB = discardScratch();
        commitB = snapshotRefs().get(idB);
        assertNotEquals(idA, idB);
        assertEquals(Map.of(".gitignore", IGNORE, "one.txt", ONE_B, "two.txt", TWO_B, "notes.txt", NOTES_B,
                        "extra.txt", EXTRA_B, "scratch.txt", SCRATCH_B), filesIn(commitB),
                "B holds two.txt as it is on disk, not as it is staged");
        assertEquals(commitA, snapshotRefs().get(idA), "taking B leaves A alone");

        stateB = RepoSnapshot.of(work);
        Map<String, String> expected = new TreeMap<>(b.files());
        expected.put("scratch.txt", SCRATCH_BASE);
        assertEquals(new RepoSnapshot(b.branch(), b.refs(), b.upstream(), b.index(), expected, b.state(),
                b.headReflog()), stateB, "the discard put scratch.txt back and changed nothing else");
    }

    /**
     * Discards scratch.txt's edit from its row's own menu in the staging list, confirms,
     * and returns the id of the snapshot the discard took first.
     */
    private String discardScratch() {
        Set<String> before = snapshotRefs().keySet();
        RobotWait.drainGitWork();
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        ContextMenu[] menu = new ContextMenu[1];
        List<String> seen = new ArrayList<>();
        try {
            RobotWait.untilOnFx("no row for scratch.txt in the unstaged list", () -> {
                seen.clear();
                for (Node node : FxHarness.lookupAll(tab.getContent(), "staging-unstaged-list")) {
                    @SuppressWarnings("unchecked")
                    ListView<String> list = (ListView<String>) node;
                    seen.add(String.valueOf(list.getItems()));
                    for (Node row : list.lookupAll(".list-cell")) {
                        ListCell<?> cell = (ListCell<?>) row;
                        if ("scratch.txt".equals(cell.getItem()) && cell.getContextMenu() != null) {
                            menu[0] = cell.getContextMenu();
                            return true;
                        }
                    }
                    // Listed but below the fold, as the user would find it: scrolled to.
                    if (list.getItems().contains("scratch.txt")) list.scrollTo("scratch.txt");
                }
                return false;
            });
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "; the unstaged lists held " + seen
                    + ", git status says " + statusLine(), e);
        }
        MenuItem discard = menu[0].getItems().stream()
                .filter(item -> "Discard changes".equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"Discard changes\" on scratch.txt's row"));
        // Not interact(): the confirmation is modal.
        Platform.runLater(discard::fire);
        press(RobotWait.untilDialogWithButton("OK"), "OK");
        RobotWait.until("scratch.txt was never discarded", () -> SCRATCH_BASE.equals(read("scratch.txt")));
        RobotWait.until("the discard took no snapshot first", () -> snapshotRefs().size() == before.size() + 1);
        RobotWait.drainGitWork();
        Set<String> added = new HashSet<>(snapshotRefs().keySet());
        added.removeAll(before);
        assertEquals(1, added.size(), added.toString());
        return added.iterator().next();
    }

    // ------------------------------------------------------------------
    // The snapshot list
    // ------------------------------------------------------------------

    /** Follows the last discard's "Snapshots" toast action to the list, with A and B read. */
    private SnapshotsView openTheListFromTheToast() {
        Button[] action = new Button[1];
        RobotWait.untilOnFx("the discard never offered its snapshots", () -> {
            action[0] = lookup(".toast-action").queryAllAs(Button.class).stream()
                    .filter(b -> "Snapshots".equals(b.getText()))
                    .findFirst().orElse(null);
            return action[0] != null;
        });
        interact(action[0]::fire);
        RobotWait.untilOnFx("the snapshot list never opened in the tab",
                () -> tab.currentView() instanceof SnapshotsView);
        SnapshotsView view = FxHarness.call(() -> (SnapshotsView) tab.currentView());
        RobotWait.untilOnFx("the list never showed B and A",
                () -> ids(view.loadedSnapshots()).equals(List.of(idB, idA)));
        return view;
    }

    /** Picks {@code id} in the list, asks to restore everything in it, and says OK. */
    private void restore(SnapshotsView view, String id) {
        select(listOf(view), id);
        Button restoreAll = button(view, SnapshotsView.RESTORE_ALL_ID);
        RobotWait.untilOnFx("Restore all never offered itself", () -> !restoreAll.isDisabled());
        Platform.runLater(restoreAll::fire);
        press(RobotWait.untilDialogWithButton("OK"), "OK");
    }

    private void select(ListView<Snapshot> list, String id) {
        interact(() -> list.getSelectionModel().select(list.getItems().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("snapshot " + id + " is not listed"))));
    }

    private void showFile(SnapshotsView view, String path) {
        @SuppressWarnings("unchecked")
        ListView<HistoryOps.FileChange> files = FxHarness.call(() ->
                (ListView<HistoryOps.FileChange>) view.content().lookup("#" + SnapshotsView.FILES_ID));
        interact(() -> files.getSelectionModel().select(files.getItems().stream()
                .filter(change -> change.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError(path + " is not among the differences"))));
    }

    @SuppressWarnings("unchecked")
    private static ListView<Snapshot> listOf(SnapshotsView view) {
        return FxHarness.call(() -> (ListView<Snapshot>) view.content().lookup("#" + SnapshotsView.LIST_ID));
    }

    private static Button button(SnapshotsView view, String id) {
        return FxHarness.call(() -> (Button) view.content().lookup("#" + id));
    }

    private static DiffViewer diffViewerOf(SnapshotsView view) {
        return FxHarness.call(() -> find(view.content(), DiffViewer.class));
    }

    private static <T> T find(Node node, Class<T> type) {
        if (type.isInstance(node)) return type.cast(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * A snapshot row as the user reads it: reason | branch, files, age | its tooltip.
     *
     * <p>Waits for the row first: the list can hold the snapshot before a layout pass has
     * given it a cell, and in the full suite the lookup sometimes ran in between.
     */
    private static String rowText(ListView<Snapshot> list, String id) {
        RobotWait.untilOnFx("no row ever showed snapshot " + id, () -> list.lookupAll(".list-cell").stream()
                .anyMatch(node -> node instanceof ListCell<?> cell && cell.getItem() instanceof Snapshot shown
                        && id.equals(shown.id())));
        return FxHarness.call(() -> {
            for (Node node : list.lookupAll(".list-cell")) {
                @SuppressWarnings("unchecked")
                ListCell<Snapshot> cell = (ListCell<Snapshot>) node;
                if (cell.getItem() == null || !id.equals(cell.getItem().id())) continue;
                Label reason = (Label) cell.lookup(".snapshot-reason");
                Label meta = (Label) cell.lookup(".snapshot-meta");
                return reason.getText() + " | " + meta.getText() + " | " + cell.getTooltip().getText();
            }
            throw new AssertionError("no row shows snapshot " + id);
        });
    }

    private static List<String> ids(List<Snapshot> snapshots) {
        return snapshots.stream().map(Snapshot::id).toList();
    }

    private static List<String> describe(List<HistoryOps.FileChange> changes) {
        return changes.stream()
                .map(c -> c.path() + " " + c.status() + " +" + c.added() + " -" + c.deleted())
                .toList();
    }

    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(bt.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no \"" + button + "\" button; the dialog offers "
                            + pane.getButtonTypes().stream().map(ButtonType::getText).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    // ------------------------------------------------------------------
    // The repository, read independently of the engine under test
    // ------------------------------------------------------------------

    /** Every snapshot ref, by id, and the commit it names. */
    private Map<String, String> snapshotRefs() {
        try (Git git = Git.open(work.toFile())) {
            Map<String, String> refs = new TreeMap<>();
            for (Ref ref : git.getRepository().getRefDatabase().getRefsByPrefix(WorkingTreeSnapshots.REF_PREFIX)) {
                refs.put(ref.getName().substring(WorkingTreeSnapshots.REF_PREFIX.length()),
                        ref.getObjectId().name());
            }
            return refs;
        } catch (Exception e) {
            throw new AssertionError("could not read the snapshot refs", e);
        }
    }

    /** The newest snapshot's id: ids are the times they were taken. */
    private static String newestId(Map<String, String> snapshots) {
        return snapshots.keySet().stream().max(java.util.Comparator.comparingLong(Long::parseLong)).orElseThrow();
    }

    /** Every file in {@code commit}'s tree and its content. */
    private Map<String, String> filesIn(String commit) throws Exception {
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository());
             TreeWalk tree = new TreeWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            tree.addTree(walk.parseCommit(ObjectId.fromString(commit)).getTree());
            tree.setRecursive(true);
            Map<String, String> files = new TreeMap<>();
            while (tree.next()) {
                files.put(tree.getPathString(),
                        new String(repo.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
            }
            return files;
        }
    }

    private String reasonOf(String commit) throws Exception {
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit parsed = walk.parseCommit(ObjectId.fromString(commit));
            return parsed.getShortMessage();
        }
    }

    private Status status() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return git.status().call();
        }
    }

    /** JGit's status in one line, for a failure message. */
    private String statusLine() {
        try {
            Status s = status();
            return "changed " + s.getChanged() + " modified " + s.getModified() + " untracked " + s.getUntracked();
        } catch (Exception e) {
            return "unreadable: " + e;
        }
    }

    /** A file's content, or null while it is missing. */
    private String read(String file) {
        try {
            Path path = work.resolve(file);
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (Exception midWrite) {
            return null;
        }
    }

    private void write(String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    }
}
