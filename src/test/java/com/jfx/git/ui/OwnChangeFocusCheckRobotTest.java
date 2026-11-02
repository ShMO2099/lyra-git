package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coming back to the window does not reload the tab for a change the tab made itself: each
 * operation has the repository recorded once it has refreshed. A change made elsewhere is
 * still found - including one that landed while an operation that redraws only the staging
 * panel was running, since that operation records only the working tree and the index.
 */
class OwnChangeFocusCheckRobotTest extends ApplicationTest {

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
        write("a.txt", "original\n");
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("add a", false).isOk());

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadAndSettle() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        // The first check records what is on screen, whatever the open left recorded.
        checkForExternalChanges();
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

    private void write(String path, String content) throws Exception {
        Files.writeString(tempDir.resolve(path), content, StandardCharsets.UTF_8);
    }

    /** Runs the focus-gain check and lets it, and any reload it starts, finish. */
    private void checkForExternalChanges() {
        interact(() -> tab.refreshIfChangedExternally());
        for (int roundTrip = 0; roundTrip < 2; roundTrip++) {
            RobotWait.drainGitWork();
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    private int reloads() {
        return FxHarness.call(() -> tab.externalReloadsForTest());
    }

    /** A tag made by another program on the same repository. */
    private void tagElsewhere(String name) throws Exception {
        try (Git other = Git.open(tempDir.toFile())) {
            other.tag().setName(name).call();
        }
    }

    @Test
    void anOperationsOwnChangeIsNotReloadedAgainOnReturningToTheWindow() {
        int before = reloads();
        interact(() -> tab.operations().run("Creating branch topic", OperationRunner.Refresh.EVERYTHING,
                () -> tab.getGitService().createBranch("topic", null, false)));
        RobotWait.drainGitWork();

        checkForExternalChanges();

        assertEquals(before, reloads(), "the branch the tab made itself is already on screen");
    }

    @Test
    void aStagingOnlyOperationIsNotReloadedAgainEither() throws Exception {
        write("a.txt", "changed\n");
        checkForExternalChanges();
        int before = reloads();

        interact(() -> tab.operations().run("Staging a.txt", OperationRunner.Refresh.STAGING,
                () -> tab.getGitService().stageFile("a.txt")));
        RobotWait.drainGitWork();
        checkForExternalChanges();

        assertEquals(before, reloads(), "the stage the tab made itself is already on screen");
    }

    @Test
    void aStagingOnlyOperationStillLeavesARefMovedElsewhereToTheCheck() throws Exception {
        write("a.txt", "changed\n");
        checkForExternalChanges();
        int before = reloads();

        tagElsewhere("from-a-terminal");
        interact(() -> tab.operations().run("Staging a.txt", OperationRunner.Refresh.STAGING,
                () -> tab.getGitService().stageFile("a.txt")));
        RobotWait.drainGitWork();

        checkForExternalChanges();

        assertEquals(before + 1, reloads(), "the tag came from outside, and only the staging panel was redrawn");
    }

    /**
     * An operation that redraws nothing - opening a file, saving a patch - records nothing
     * either: an edit made elsewhere while it ran was recorded as seen, and the staging
     * panel never showed it.
     */
    @Test
    void anOperationThatRedrawsNothingLeavesAnEditMadeElsewhereToTheCheck() throws Exception {
        int before = reloads();

        write("a.txt", "edited in another program\n");
        interact(() -> tab.operations().run("Saving a patch", OperationRunner.Refresh.NONE,
                () -> com.jfx.git.core.GitResult.ok("Saved")));
        RobotWait.drainGitWork();

        checkForExternalChanges();

        assertEquals(before + 1, reloads(), "the edit came from outside, and nothing was redrawn");
    }

    @Test
    void aChangeMadeElsewhereIsStillFound() throws Exception {
        int before = reloads();
        tagElsewhere("v1");

        checkForExternalChanges();

        assertEquals(before + 1, reloads());
    }

    // ------------------------------------------------------------------
    // The editor's saves, and the file under the editor
    // ------------------------------------------------------------------

    /** a.txt open in the built-in editor with edits not yet saved, as a user would have it. */
    private DiffViewer editingWithUnsavedEdits() throws Exception {
        write("a.txt", "original\nmine\n");
        // The graph already has rows, so the WIP row is waited for before it is selected:
        // selected straight away, row 0 is still the commit.
        interact(() -> tab.refresh());
        RobotWait.untilOnFx("the WIP row never appeared", () -> !tab.graphList().getItems().isEmpty()
                && tab.graphList().getItems().get(0).isWipNode());
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("a.txt never appeared in the unstaged list",
                () -> lookup(".staging-unstaged-list").tryQuery().isPresent());
        // The refresh can rebuild the panel more than once; click the list that stays, and
        // the row in it rather than any node reading "a.txt".
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.Node list = lookup(".staging-unstaged-list").query();
        RobotWait.untilOnFx("a.txt never appeared in the unstaged list",
                () -> from(list).lookup("a.txt").match(n -> n.getScene() != null).tryQuery().isPresent());
        clickOn(from(list).lookup("a.txt").match(n -> n.getScene() != null).<javafx.scene.Node>query());

        DiffViewer viewer = tab.diffViewer();
        RobotWait.untilOnFx("a.txt never loaded into the viewer", () -> editableFileLoaded(viewer, "a.txt"));
        interact(viewer::beginEditing);
        RobotWait.untilOnFx("the editor never opened", viewer::isEditing);
        interact(() -> viewer.replaceBuffer("original\nmine, edited in the app\n"));
        assertTrue(FxHarness.call(viewer::hasUnsavedEdits), "precondition: the edits are unsaved");
        return viewer;
    }

    private static boolean editableFileLoaded(DiffViewer viewer, String path) {
        boolean titled = FxHarness.lookupAll(viewer, "diff-file-path").stream()
                .anyMatch(n -> path.equals(n.getAccessibleText()));
        return titled && FxHarness.lookupAll(viewer, "diff-tool-button").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Button b && b.getText().startsWith("Edit")
                        && b.isVisible() && !b.isDisabled());
    }

    /** Whether the editor's badge says the last save failed. FX thread. */
    private static boolean saveFailedShown(DiffViewer viewer) {
        return FxHarness.lookupAll(viewer, "diff-editing-badge").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Label l && l.getText().startsWith("not saved"));
    }

    /** Whether the footer's "changed on disk" warning is up. */
    private static boolean changedOnDiskShown(DiffViewer viewer) {
        return FxHarness.call(() -> FxHarness.lookupAll(viewer, "diff-status-warning").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Label l
                        && "changed on disk".equals(l.getText()) && l.isVisible()));
    }

    /**
     * A save that failed changed nothing, so it neither redraws the tab nor records the
     * repository as seen - recorded, a tag made elsewhere meanwhile was taken as already on
     * screen and never drawn.
     */
    @Test
    void aSaveThatFailedRedrawsAndRecordsNothing() throws Exception {
        DiffViewer viewer = editingWithUnsavedEdits();
        checkForExternalChanges();
        int before = reloads();
        java.io.File file = tempDir.resolve("a.txt").toFile();
        assertTrue(file.setWritable(false), "precondition: a.txt is read-only");
        try {
            tagElsewhere("from-a-terminal");
            interact(viewer::saveEdits);
            RobotWait.untilOnFx("the save never failed", () -> saveFailedShown(viewer));
            RobotWait.drainGitWork();
        } finally {
            file.setWritable(true);
        }

        checkForExternalChanges();

        assertEquals(before + 1, reloads(), "the tag came from outside, and the failed save drew nothing");
    }

    @Test
    void aSaveAndStageWhoseWriteFailedRedrawsAndRecordsNothing() throws Exception {
        DiffViewer viewer = editingWithUnsavedEdits();
        checkForExternalChanges();
        int before = reloads();
        java.io.File file = tempDir.resolve("a.txt").toFile();
        assertTrue(file.setWritable(false), "precondition: a.txt is read-only");
        try {
            tagElsewhere("from-a-terminal");
            interact(viewer::saveAndStage);
            RobotWait.untilOnFx("the save never failed", () -> saveFailedShown(viewer));
            RobotWait.drainGitWork();
        } finally {
            file.setWritable(true);
        }

        checkForExternalChanges();

        assertEquals(before + 1, reloads(), "the tag came from outside, and the failed write drew nothing");
    }

    /** The write landed even though the stage did not: the working tree moved, so it is drawn. */
    @Test
    void aSaveAndStageWhoseStageFailedStillDrawsTheWriteThatLanded() throws Exception {
        DiffViewer viewer = editingWithUnsavedEdits();
        checkForExternalChanges();
        int before = reloads();
        Path lock = tempDir.resolve(".git").resolve("index.lock");
        Files.writeString(lock, "held by another program");
        try {
            interact(viewer::saveAndStage);
            RobotWait.untilOnFx("the stage never failed", () -> saveFailedShown(viewer));
            RobotWait.drainGitWork();
        } finally {
            Files.deleteIfExists(lock);
        }
        assertEquals("original\nmine, edited in the app\n",
                Files.readString(tempDir.resolve("a.txt"), StandardCharsets.UTF_8), "precondition: the write landed");

        checkForExternalChanges();

        assertEquals(before, reloads(), "the file the tab wrote itself is already on screen");
    }

    /**
     * The focus check warns about the file under the editor even when nothing else moved:
     * an operation of this tab records the working tree as it leaves it, and a rewrite of
     * the file being edited that landed first is then part of what was "seen".
     */
    @Test
    void aFileRewrittenUnderTheEditorIsNoticedThoughAnOperationRecordedTheRewrite() throws Exception {
        write("b.txt", "another file\n");
        DiffViewer viewer = editingWithUnsavedEdits();
        checkForExternalChanges();
        assertFalse(changedOnDiskShown(viewer),"precondition: nothing has happened underneath yet");

        // A formatter rewrites the file being edited while the window keeps focus...
        write("a.txt", "original\nmine, reformatted elsewhere\n");
        // ...and the user stages another file, which records the working tree as it is now.
        interact(() -> tab.operations().run("Staging b.txt", OperationRunner.Refresh.STAGING,
                () -> tab.getGitService().stageFile("b.txt")));
        RobotWait.drainGitWork();

        checkForExternalChanges();

        assertTrue(changedOnDiskShown(viewer), "the footer warns before the next Ctrl+S");
        assertTrue(FxHarness.call(viewer::isEditing), "and the buffer is left alone");
    }
}
