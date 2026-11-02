package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for a data-loss bug: File view's whole-file content came from a
 * {@code fileContentSupplier} wired to {@code GitService.getWorkingTreeContent}, which
 * hard-coded UTF-8 regardless of what the encoding dropdown showed. A non-UTF-8 file's
 * File view was always mojibake, no matter which encoding the user picked.
 *
 * <p>Uses a real {@link GitService} and a real {@link RepoTab}, driving the exact
 * production path ({@link RightPanelManager}'s unstaged-file selection, then the "File
 * View" toggle) rather than calling {@code DiffViewer} methods directly - the bug lived
 * entirely in that wiring, not in {@code TextEncoding} itself, which already has its own
 * passing unit tests.
 */
class DiffViewerEncodingRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    /** {@code "café\n"} encoded as Windows-1252, where 'é' is the single byte 0xE9. */
    private static byte[] cafeWindows1252() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("caf".getBytes(StandardCharsets.US_ASCII));
        out.write(0xE9);
        out.write('\n');
        return out.toByteArray();
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

        // Commit an initial version, then modify it on disk - still Windows-1252 bytes -
        // so the file has a pending unstaged diff when the window opens.
        Files.write(tempDir.resolve("greeting.txt"), cafeWindows1252());
        assertTrue(service.stageFile("greeting.txt").isOk());
        assertTrue(service.commit("add greeting", false).isOk());

        ByteArrayOutputStream modified = new ByteArrayOutputStream();
        modified.write(cafeWindows1252());
        modified.write("more\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(tempDir.resolve("greeting.txt"), modified.toByteArray());

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // The panel and the diff load on the git lane; let them finish before the backend
        // closes and @TempDir deletes the repository under them.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
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

    /** Opens the staging area and waits for it: the graph reload and the status query behind it run off the FX thread. */
    private void openStagingArea() {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("the staging area never listed greeting.txt",
                () -> FxHarness.lookupAll(tab.getContent(), "staging-unstaged-list").stream()
                        .anyMatch(n -> n instanceof javafx.scene.control.ListView<?> list
                                && list.getItems().contains("greeting.txt"))
                        && lookup("greeting.txt").tryQuery().isPresent());
    }

    /** The viewer the staging panel shows its diffs in. */
    private DiffViewer diffViewer() {
        return (DiffViewer) FxHarness.lookupAll(tab.getContent(), "diff-viewer").get(0);
    }

    /** Finds and fires a segmented toggle button by its label, as {@code DiffViewerRenderTest}'s
     *  own {@code toggle()} helper does - {@code fire()} rather than {@code clickOn()}, since
     *  this suite has already established that a real click's implied pointer movement is
     *  unreliable against these controls under Monocle Headless. */
    private void toggleFileView() {
        for (Node node : FxHarness.lookupAll(tab.getContent(), "diff-segment")) {
            if (node instanceof ToggleButton button && "File view".equals(button.getText())) {
                interact(button::fire);
                settle();
                return;
            }
        }
        throw new AssertionError("File view toggle not found");
    }

    private DiffTextArea codeArea() {
        return (DiffTextArea) FxHarness.lookupAll(tab.getContent(), "code-area").get(0);
    }

    @Test
    void fileViewDecodesNonUtf8ContentCorrectlyThroughTheRealSelectionPath() {
        openStagingArea();

        // Selects greeting.txt's unstaged row - RightPanelManager's showDiff() helper,
        // which calls setFileDiffBytes() directly, the exact path Bug B lived in.
        // The row in the list, once the panel has settled: the refresh can rebuild it more
        // than once, and a text match can also be the viewer's header or a recycled cell
        // with no scene.
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        Node list = lookup(".staging-unstaged-list").query();
        Node row = from(list).lookup("greeting.txt").match(n -> n.getScene() != null).query();
        clickOn(row);
        // The diff is read on the git lane. The viewer takes on the file's path when the
        // bytes arrive - the header alone says it while they are still loading - and File
        // View has nothing to show before then.
        RobotWait.untilOnFx("greeting.txt's diff never loaded",
                () -> "greeting.txt".equals(diffViewer().filePathProperty().get()));

        toggleFileView();

        String shown = codeArea().getText();
        assertTrue(shown.contains("café"),
                "File view should decode Windows-1252 content correctly: " + shown);
        assertFalse(shown.contains("�"), "no replacement characters expected: " + shown);
    }
}
