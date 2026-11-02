package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparison of two commits: which way round it reads, and what "Swap direction" does.
 *
 * <p>The comparison used to mark every file {@code M}, an added file included, because it
 * asked the engine for paths alone. Swapping rebuilt the file list without its selection
 * while the viewer kept the old direction's diff - an addition still drawn as an addition
 * under a header that now read the other way round. And a graph selection picked its
 * older end by author date, which a rebased commit can carry from long before its parent.
 */
class ComparisonSwapTest {

    @TempDir
    Path tempDir;

    @Test
    void swappingReopensTheFileReversedAndSwappingBackRestoresIt() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)
                .commitFiles("Before the notes", Map.of("other.txt", "x\n".getBytes()), List.of())
                .commitFiles("Add the notes", Map.of("notes.txt", "one\ntwo\n".getBytes()), List.of())) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();

            CommitInfo before = commit(tab, "Before the notes");
            CommitInfo after = commit(tab, "Add the notes");
            FxHarness.run(() -> tab.getRightPanel().showComparison(before, after));
            RobotWait.untilOnFx("the comparison never listed notes.txt",
                    () -> row(window, "notes.txt") != null);

            assertEquals('A', FxHarness.call(() -> row(window, "notes.txt").status()),
                    "a file the later commit created is an addition, not a modification");
            assertTrue(FxHarness.call(() -> !swapButton(window).getStyleClass()
                            .contains("compare-swap-reversed")),
                    "older → newer is the usual reading, and is not marked");

            FxHarness.run(() -> changedFiles(window).getSelectionModel()
                    .select(row(window, "notes.txt")));
            RobotWait.drainGitWork();
            awaitOpenAs(window, tab, 'A', DiffParser.Change.ADDED,
                    "notes.txt never opened as an addition");

            FxHarness.run(() -> swapButton(window).fire());
            RobotWait.drainGitWork();
            awaitOpenAs(window, tab, 'D', DiffParser.Change.DELETED,
                    "after swapping, the open file never reloaded the other way round");
            assertTrue(FxHarness.call(() -> swapButton(window).getStyleClass()
                            .contains("compare-swap-reversed")),
                    "a reversed comparison says so for as long as it lasts");

            FxHarness.run(() -> swapButton(window).fire());
            RobotWait.drainGitWork();
            awaitOpenAs(window, tab, 'A', DiffParser.Change.ADDED,
                    "swapping back never reopened the file the usual way round");
            assertTrue(FxHarness.call(() -> !swapButton(window).getStyleClass()
                            .contains("compare-swap-reversed")),
                    "swapped back, the marker goes");
        }
    }

    /**
     * The newer commit here was authored a month before its parent - as a rebased or
     * cherry-picked one can be. Ordered by date it would be the "from" end, and the file it
     * adds would read as deleted.
     */
    @Test
    void aGraphSelectionReadsDownTheGraphNotByDate() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)
                .commitFiles("Before the notes", Map.of("other.txt", "x\n".getBytes()), List.of())
                .commitFiles("Add the notes", Map.of("notes.txt", "one\ntwo\n".getBytes()),
                        List.of(), 30)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();

            FxHarness.run(() -> {
                var graph = tab.getCommitList();
                graph.getSelectionModel().clearSelection();
                for (int i = 0; i < graph.getItems().size(); i++) {
                    var commit = graph.getItems().get(i).getCommit();
                    if (commit != null && (commit.getSubject().equals("Before the notes")
                            || commit.getSubject().equals("Add the notes"))) {
                        graph.getSelectionModel().select(i);
                    }
                }
            });
            RobotWait.untilOnFx("the selection never opened a comparison listing notes.txt",
                    () -> row(window, "notes.txt") != null);

            assertEquals('A', FxHarness.call(() -> row(window, "notes.txt").status()),
                    "the lower row is the older end, whatever its author date says");
        }
    }

    private static void awaitOpenAs(MainWindowFixture window, RepoTab tab, char letter,
                                    DiffParser.Change change, String failure) {
        RobotWait.untilOnFx(failure, () -> {
            var listed = row(window, "notes.txt");
            var selected = changedFiles(window).getSelectionModel().getSelectedItem();
            var summary = tab.diffViewer().loadedDiff().summary();
            return listed != null && listed.status() == letter
                    && selected != null && "notes.txt".equals(selected.path())
                    && "notes.txt".equals(tab.diffViewer().filePathProperty().get())
                    && summary != null && summary.change() == change;
        });
    }

    private static CommitInfo commit(RepoTab tab, String subject) {
        return FxHarness.call(() -> tab.getCommitList().getItems().stream()
                .map(row -> row.getCommit())
                .filter(commit -> commit != null && subject.equals(commit.getSubject()))
                .findFirst().orElseThrow());
    }

    private static GitBackend.FileChange row(MainWindowFixture window, String path) {
        ListView<GitBackend.FileChange> files = changedFiles(window);
        if (files == null) return null;
        return files.getItems().stream().filter(f -> path.equals(f.path())).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static ListView<GitBackend.FileChange> changedFiles(MainWindowFixture window) {
        Node list = window.scene().lookup(".changed-files-list");
        return list instanceof ListView<?> view ? (ListView<GitBackend.FileChange>) view : null;
    }

    private static Button swapButton(MainWindowFixture window) {
        Node found = window.scene().lookup(".compare-swap");
        return found instanceof Button button ? button : null;
    }
}
