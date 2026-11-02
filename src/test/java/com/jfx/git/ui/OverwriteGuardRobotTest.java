package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saving over a file that something else changed first.
 *
 * <p>The editor already survives a write that <em>fails</em>. This is the other half: a
 * write that succeeds and destroys somebody's work on the way, because the file had moved
 * since the buffer was loaded and nothing looked.
 *
 * <h2>Driving the prompt</h2>
 * The action that opens the modal is fired through a bare {@link Platform#runLater} rather
 * than through {@code interact()}. {@code interact} waits for the FX thread to go idle,
 * and a thread sitting inside {@code showAndWait} never does - the test would deadlock
 * rather than fail. The dialog is then answered by finding the showing {@link DialogPane}
 * and firing its button, the idiom {@code CommitPanelRobotTest} established.
 */
class OverwriteGuardRobotTest extends ApplicationTest {

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
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        write("a.txt", "original\n");
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("add a", false).isOk());

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // The editor writes through the git lane; the backend must outlive the last write.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void write(String path, String content) throws Exception {
        Files.writeString(tempDir.resolve(path), content, StandardCharsets.UTF_8);
    }

    private String onDisk(String path) throws Exception {
        return Files.readString(tempDir.resolve(path), StandardCharsets.UTF_8);
    }

    /** {@link #onDisk}, as a condition: a file mid-write reads as "not yet". */
    private boolean onDiskIs(String path, String expected) {
        try {
            return expected.equals(onDisk(path));
        } catch (Exception midWrite) {
            return false;
        }
    }

    /** Whether the viewer has finished loading {@code path} and can edit it. */
    private static boolean editableFileLoaded(DiffViewer viewer, String path) {
        boolean titled = FxHarness.lookupAll(viewer, "diff-file-path").stream()
                .anyMatch(n -> path.equals(n.getAccessibleText()));
        return titled && FxHarness.lookupAll(viewer, "diff-tool-button").stream()
                .anyMatch(n -> n instanceof Button b && b.getText().startsWith("Edit")
                        && b.isVisible() && !b.isDisabled());
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Opens a.txt in the editor with unsaved changes in the buffer. */
    private DiffViewer editorWithUnsavedEdits() throws Exception {
        write("a.txt", "original\nmine\n");
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        // The file list is filled by a refresh that reads the repository off the FX thread.
        RobotWait.untilOnFx("a.txt never appeared in the file list",
                () -> lookup("a.txt").tryQuery().isPresent());
        // The row in the list, not any node reading "a.txt": the viewer's header says it
        // too, and a recycled cell can match with no scene at all.
        // The refresh can rebuild the panel more than once; click the list that stays.
        RobotWait.drainGitWork();
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.Node list = lookup(".staging-unstaged-list").query();
        javafx.scene.Node row = from(list).lookup("a.txt")
                .match(n -> n.getScene() != null).query();
        clickOn(row);

        DiffViewer viewer = lookup(".diff-viewer").query();
        // And its content is read off the FX thread too; editing needs it on screen first.
        RobotWait.untilOnFx("a.txt never loaded into the viewer",
                () -> editableFileLoaded(viewer, "a.txt"));
        // beginEditing switches to File view itself on the first press, so this is the
        // one call needed.
        interact(viewer::beginEditing);
        settle();
        assertTrue(viewer.isEditing(), "precondition: the editor opened");

        interact(() -> viewer.replaceBuffer("original\nmine, edited in the app\n"));
        settle();
        return viewer;
    }

    /**
     * Answers the dialog offering {@code label} by firing that button. Waited for, because
     * the action that opens it was fired by a {@code runLater} this test did not wait on -
     * and, for the second of two prompts, from inside the first one's answer. Every label
     * this class passes is a button's exact text, which is what the wait matches.
     */
    private void answerDialog(String label) {
        DialogPane pane = RobotWait.untilDialogWithButton(label);
        interact(() -> {
            ButtonType button = pane.getButtonTypes().stream()
                    .filter(bt -> bt.getText().startsWith(label))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no \"" + label + "\" button; dialog offers "
                                    + pane.getButtonTypes().stream().map(ButtonType::getText).toList()));
            ((Button) pane.lookupButton(button)).fire();
        });
    }

    /**
     * For an answer that must leave the file alone. Lets the action the dialog interrupted
     * run to its end on the FX thread, then lets anything it queued on the git lane finish,
     * so "nothing was written" is checked after a write would have landed, not before.
     */
    private void awaitNothingWritten() {
        WaitForAsyncUtils.waitForFxEvents();
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Fires {@code action} without waiting for the FX thread to go idle afterwards. The
     * dialog it opens is waited for by {@link #answerDialog}.
     */
    private void withoutWaiting(Runnable action) {
        Platform.runLater(action);
    }

    // ------------------------------------------------------------------

    @Test
    void overwritingWritesTheBufferAsAsked() throws Exception {
        DiffViewer viewer = editorWithUnsavedEdits();
        write("a.txt", "original\nsomebody else\n");

        withoutWaiting(viewer::saveEdits);
        answerDialog("Overwrite");
        // The write runs on the git lane, and the buffer is marked clean in its callback.
        RobotWait.until("the buffer was never written",
                () -> onDiskIs("a.txt", "original\nmine, edited in the app\n"));
        RobotWait.untilOnFx("the save never reported back", () -> !viewer.hasUnsavedEdits());

        assertEquals("original\nmine, edited in the app\n", onDisk("a.txt"),
                "the user asked for their version, so their version is what lands");
        assertFalse(viewer.hasUnsavedEdits(), "and the buffer is on disk now");
    }

    @Test
    void reloadingTakesWhatIsOnDiskAndDropsTheBuffer() throws Exception {
        DiffViewer viewer = editorWithUnsavedEdits();
        write("a.txt", "original\nsomebody else\n");

        withoutWaiting(viewer::saveEdits);
        answerDialog("Discard my edits and reload");
        awaitNothingWritten();

        assertEquals("original\nsomebody else\n", onDisk("a.txt"),
                "nothing was written; the file is as the other writer left it");
        assertTrue(viewer.editorText().contains("somebody else"),
                "and the buffer now shows it");
        assertFalse(viewer.hasUnsavedEdits(), "which leaves nothing pending");
    }

    /**
     * The unguarded path. {@code saveEditedFileSync} writes straight past the editor's own
     * checks, and it runs at the moment the user is least likely to be watching.
     */
    @Test
    void closingTheTabAsksBeforeOverwritingAndCancellingVetoesTheClose() throws Exception {
        DiffViewer viewer = editorWithUnsavedEdits();
        write("a.txt", "original\nsomebody else\n");

        boolean[] closed = new boolean[1];
        withoutWaiting(() -> closed[0] = tab.confirmClose());
        // Two prompts in sequence: the unsaved-edits question, then the overwrite one.
        answerDialog("Save and close");
        answerDialog("Cancel");
        // Also what lets confirmClose() return, and so set closed[0].
        awaitNothingWritten();

        assertFalse(closed[0], "a cancelled overwrite must veto the close");
        assertEquals("original\nsomebody else\n", onDisk("a.txt"),
                "and nothing may have been written");
        assertTrue(viewer.hasUnsavedEdits(), "the edits are still there to act on");
    }
}
