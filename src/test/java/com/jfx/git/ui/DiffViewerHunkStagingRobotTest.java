package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hunk-level staging and revert against a real repository - not the mocked
 * {@code onStageHunk}/{@code onRevertHunk} callbacks {@code DiffViewerRenderTest} already
 * exhaustively covers.
 *
 * <h2>Two different fakes, on purpose</h2>
 * {@code DiffViewerRenderTest} proves the bars position themselves correctly and call back
 * with the right hunk and the right line indices - it does that by installing a stub
 * callback that just records what it was given. It does not prove {@link RightPanelManager}
 * turns that callback into a real {@code git} operation. This class is the other half: a
 * real {@link GitService} backing a real {@link RepoTab}, so "Stage hunk" has to survive
 * contact with an actual index.
 *
 * <h2>It really is a click now</h2>
 * This class used to hover the hunk with a synthetic {@code MOUSE_MOVED} and then reach the
 * revealed button with {@code fire()} rather than a real {@code clickOn}, blaming Monocle's
 * idea of where the pointer is. That diagnosis was wrong, and the workaround outlived it by
 * a long way: the single action bar floated above the text, so the click's own pointer
 * movement left the text area, fired its {@code MOUSE_EXITED}, and hid the button before
 * the press landed. It was a real bug in the viewer, reported eventually as "the revert
 * button keeps disappearing when I move the mouse to click it" - not an environment
 * limitation. Now that every visible hunk carries its own permanently-shown bar there is no
 * hover to outrun, and {@code clickOn} lands, which is what this class asserts.
 */
class DiffViewerHunkStagingRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    /** Twenty numbered lines, so two edits far apart land in two separate hunks. */
    private static String numberedLines(int count, int changedLine, String replacement) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(i == changedLine ? replacement : "line" + i).append('\n');
        }
        return sb.toString();
    }

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
        commit("a.txt", numberedLines(20, -1, null), "add a");

        // Two edits, far enough apart that git's default 3-line context keeps them as
        // two separate hunks rather than merging into one.
        Files.writeString(tempDir.resolve("a.txt"),
                numberedLines(20, 2, "CHANGED2").replace("line18\n", "CHANGED18\n"),
                StandardCharsets.UTF_8);

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // Staging and reverting a hunk run on the git lane after the click returns; the
        // backend must not be closed under them.
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

    private void openStagingArea() {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        // The file list is filled by a refresh that reads the repository off the FX thread,
        // and a panel built before that refresh lands is replaced under the next click.
        // Waiting the refresh out leaves only the panel that stays.
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("a.txt never appeared in the file list", () -> fileRow() != null);
    }

    /** a.txt's row in the staging list, if one is on screen. Call on the FX thread. */
    private javafx.scene.Node fileRow() {
        return lookup(".staging-unstaged-list .list-cell").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.ListCell<?> cell
                        && "a.txt".equals(cell.getItem()) && n.getScene() != null && n.isVisible())
                .findFirst()
                .orElse(null);
    }

    /** Selects a.txt's unstaged row, which is what wires the diff viewer to real hunk actions. */
    private void openUnstagedDiff() {
        // The row on screen, not whichever node says "a.txt" first: a recycled cell with
        // no scene is also a match, and clicking it throws.
        javafx.scene.Node[] row = new javafx.scene.Node[1];
        RobotWait.untilOnFx("a.txt never appeared in the file list", () -> (row[0] = fileRow()) != null);
        clickOn(row[0]);
        // The diff is read off the FX thread; its hunks' buttons exist once it has rendered.
        RobotWait.untilOnFx("a.txt's hunks never rendered",
                () -> stageHunkButton() != null && revertHunkButton() != null);
    }

    /** The "Stage hunk"/"Unstage hunk" button - the second of the two staging actions, as in
     *  {@code DiffViewerRenderTest.stageButton}. */
    private javafx.scene.control.Button stageHunkButton() {
        return (javafx.scene.control.Button)
                FxHarness.lookupAll(tab.getContent(), "diff-hunk-action").get(1);
    }

    private javafx.scene.control.Button revertHunkButton() {
        return (javafx.scene.control.Button)
                FxHarness.lookupAll(tab.getContent(), "diff-revert-hunk").get(0);
    }

    // ------------------------------------------------------------------

    @Test
    void stagingTheFirstHunkLeavesTheSecondEditUnstaged() throws Exception {
        openStagingArea();
        openUnstagedDiff();

        // A real robot click, not fire(): the buttons are on every hunk from the moment
        // the diff renders, so there is no hover to outrun any more. This is the half of
        // this class's original premise that could not be tested before.
        clickOn(stageHunkButton());
        RobotWait.until("the hunk never reached the index",
                () -> service.getStagingState().staged().contains("a.txt"));

        var state = service.getStagingState();
        assertTrue(state.staged().contains("a.txt"),
                "a.txt should be partially staged, so it belongs in both lists");
        assertTrue(state.unstaged().contains("a.txt"));

        String staged = new String(service.getStagedDiffBytes("a.txt", false), StandardCharsets.UTF_8);
        assertTrue(staged.contains("CHANGED2"), "the hovered hunk's edit should be in the index: " + staged);
        assertFalse(staged.contains("CHANGED18"), "the other hunk must not have been staged too: " + staged);

        String stillUnstaged = new String(service.getUnstagedDiffBytes("a.txt", false), StandardCharsets.UTF_8);
        assertTrue(stillUnstaged.contains("CHANGED18"),
                "the second edit should still be sitting in the working tree, unstaged: " + stillUnstaged);

        // Staging never touches the working tree - both edits are still on real disk.
        String onDisk = Files.readString(tempDir.resolve("a.txt"));
        assertTrue(onDisk.contains("CHANGED2") && onDisk.contains("CHANGED18"));
    }

    @Test
    void revertingTheFirstHunkRestoresOnlyThatLineOnDisk() throws Exception {
        openStagingArea();
        openUnstagedDiff();

        // Not interact(): firing this button opens a modal Alert.showAndWait(), which
        // does not return until "OK" is clicked below - interact() blocks the test
        // thread waiting for the Runnable it ran to return, so running the fire() that
        // way deadlocks against the very click that would end it.
        javafx.application.Platform.runLater(() -> revertHunkButton().fire());
        // The dialog's own OK, labelled "Discard hunk" like the button that opened it.
        clickOn(RobotWait.untilDialogWithButton("OK").lookupButton(javafx.scene.control.ButtonType.OK));
        RobotWait.until("the hunk was never reverted on disk", () -> {
            try {
                String content = Files.readString(tempDir.resolve("a.txt"));
                return content.contains("line2\n") && !content.contains("CHANGED2");
            } catch (java.io.IOException midWrite) {
                return false;
            }
        });

        String onDisk = Files.readString(tempDir.resolve("a.txt"));
        assertTrue(onDisk.contains("line2\n") && !onDisk.contains("CHANGED2"),
                "the hovered hunk should be reverted to HEAD's content on real disk: " + onDisk);
        assertTrue(onDisk.contains("CHANGED18"),
                "the other, untouched hunk's edit must survive: " + onDisk);
    }

    // ------------------------------------------------------------------
    // Split view: Stage line from either column, checked in the index itself.
    //
    // DiffRowsTest proves each split row remembers its patch line; nothing proved the whole
    // path - select in a column, press the bar's Stage line, read what git actually stored.
    // The bug this guards against staged the deletion above the addition that was selected.

    @Test
    void stagingOnlyTheAddedLineFromTheRightColumnKeepsTheOldLineInTheIndex() throws Exception {
        openStagingArea();
        openUnstagedDiff();
        switchToSplit();

        selectLine(splitAreas()[1], "CHANGED2");
        stageSelectedLines();

        assertEquals(numberedLines(20, -1, null).replace("line2\n", "line2\nCHANGED2\n"), indexContent(),
                "only the addition was selected: the index gains CHANGED2 and keeps line2");
    }

    @Test
    void stagingOnlyTheDeletedLineFromTheLeftColumnRemovesJustThatLineFromTheIndex() throws Exception {
        openStagingArea();
        openUnstagedDiff();
        switchToSplit();

        selectLine(splitAreas()[0], "line2");
        stageSelectedLines();

        assertEquals(numberedLines(20, -1, null).replace("line2\n", ""), indexContent(),
                "only the deletion was selected: line2 leaves the index and CHANGED2 does not arrive");
    }

    private void switchToSplit() {
        interact(() -> FxHarness.lookupAll(tab.getContent(), "toggle-button").stream()
                .filter(n -> n instanceof javafx.scene.control.ToggleButton b
                        && "Split".equals(b.getText()) && b.isVisible())
                .map(javafx.scene.control.ToggleButton.class::cast)
                .findFirst().orElseThrow().fire());
        RobotWait.untilOnFx("the split columns never appeared", () -> visibleTextAreas().size() == 2);
    }

    /** The two split columns, left (old) then right (new). Call on the test thread. */
    private DiffTextArea[] splitAreas() {
        return FxHarness.call(() -> visibleTextAreas().stream()
                .sorted(java.util.Comparator.comparingDouble(
                        (DiffTextArea a) -> a.localToScene(0, 0).getX()))
                .toArray(DiffTextArea[]::new));
    }

    private java.util.List<DiffTextArea> visibleTextAreas() {
        java.util.List<DiffTextArea> found = new java.util.ArrayList<>();
        collectTextAreas(tab.getContent(), found);
        return found;
    }

    private static void collectTextAreas(javafx.scene.Node node, java.util.List<DiffTextArea> found) {
        if (!node.isVisible()) return;
        if (node instanceof DiffTextArea area && area.getScene() != null) {
            found.add(area);
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectTextAreas(child, found);
            }
        }
    }

    /** Selects the whole of the one paragraph in {@code area} that reads {@code text}. */
    private void selectLine(DiffTextArea area, String text) {
        interact(() -> {
            for (int p = 0; p < area.getParagraphs().size(); p++) {
                if (area.getText(p).strip().equals(text)) {
                    area.selectRange(p, 0, p, area.getParagraphLength(p));
                    return;
                }
            }
            fail("no paragraph reads " + text + " in " + area.getText());
        });
    }

    /** Presses the first hunk's Stage line once it offers exactly one line, then waits for git. */
    private void stageSelectedLines() {
        RobotWait.untilOnFx("Stage line was never offered for the selection", () -> {
            var stageLines = (javafx.scene.control.Button)
                    FxHarness.lookupAll(tab.getContent(), "diff-hunk-action").get(0);
            return stageLines.isVisible() && "Stage line".equals(stageLines.getText());
        });
        interact(() -> ((javafx.scene.control.Button)
                FxHarness.lookupAll(tab.getContent(), "diff-hunk-action").get(0)).fire());
        RobotWait.until("the line never reached the index",
                () -> service.getStagingState().staged().contains("a.txt"));
        RobotWait.drainGitWork();
    }

    /** a.txt exactly as the index holds it, read straight from git rather than through the app. */
    private String indexContent() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            var repo = git.getRepository();
            var entry = repo.readDirCache().getEntry("a.txt");
            return new String(repo.open(entry.getObjectId()).getBytes(), StandardCharsets.UTF_8);
        }
    }
}
