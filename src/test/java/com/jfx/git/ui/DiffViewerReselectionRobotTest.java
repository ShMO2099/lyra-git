package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Node;
import javafx.scene.Scene;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces: select a changed file, close the diff viewer, then click the same
 * still-selected file again - before the fix, nothing happened, because the file
 * list's {@code selectedItemProperty} does not change when the same row is clicked
 * twice in a row, so the listener that opens the diff viewer never re-fires.
 */
class DiffViewerReselectionRobotTest extends ApplicationTest {

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
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());

        Files.writeString(tempDir.resolve("a.txt"), "original", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("add a", false).isOk());
        Files.writeString(tempDir.resolve("a.txt"), "changed", StandardCharsets.UTF_8);

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() {
        // Each click loads a diff on the git lane; let the last one finish before the
        // backend closes and @TempDir deletes the repository under it.
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

    private Node findIn(String cssOrText) {
        return from(tab.getContent()).lookup(cssOrText).query();
    }

    private boolean diffViewerShowing() {
        // The node RepoTab's filePathProperty listener actually toggles setVisible on.
        return findIn(".diff-viewer").isVisible();
    }

    /**
     * Waits for the viewer to open. It opens when the diff arrives, not when the row is
     * clicked: the diff is read on the git lane and only then handed to the viewer.
     */
    private void untilDiffViewerShowing() {
        RobotWait.untilOnFx("the diff viewer never opened", this::diffViewerShowing);
    }

    @Test
    void reselectingTheSameFileAfterClosingReopensTheDiffViewer() {
        // The changed file is listed by the refresh start() began, off the FX thread.
        RobotWait.untilOnFx("a.txt was never listed", () -> findIn("a.txt") != null);
        clickOn(findIn("a.txt"));
        untilDiffViewerShowing();
        assertTrue(diffViewerShowing(), "selecting a file should show the diff viewer");

        // Closing is immediate, on the FX thread; nothing is loaded.
        clickOn(findIn(".diff-close"));
        settle();
        assertFalse(diffViewerShowing(), "closing should hide the diff viewer");

        // The same, still-selected row: before the fix, this did nothing because the
        // list's selectedItemProperty never changed.
        clickOn(findIn("a.txt"));
        untilDiffViewerShowing();
        assertTrue(diffViewerShowing(), "re-clicking the same file must reopen the diff viewer");
    }
}
