package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces, end to end through the real UI, the scenario that motivated this fix:
 * a file modified in an old commit and later deleted from the working tree. Before this
 * fix, the only option there was "Open externally", which failed outright ({@link
 * com.jfx.git.core.FileOpener} needs a real file on disk); the built-in editor's Edit
 * button did not even appear, because {@code DiffViewer.canEdit()} required the content
 * on screen to be the current working-tree copy.
 */
class EditHistoricalFileRobotTest extends ApplicationTest {

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

        commit("root.txt", "root", "root commit");
        commit("secret.txt", "original secret content", "add secret");
        // Delete it in a later commit, so the working tree no longer has this path -
        // exactly the state the screenshot that prompted this fix was in.
        Files.delete(tempDir.resolve("secret.txt"));
        assertTrue(service.stageFile("secret.txt").isOk());
        assertTrue(service.commit("remove secret", false).isOk());

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() {
        // Save writes through the git lane; the backend must outlive the write.
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

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Scoped to {@code tab}'s own descendants rather than TestFX's global {@code
     * lookup(...)}, which searches every currently-open window - when the full suite
     * runs many {@code ApplicationTest} classes in one fork, a generic selector like
     * {@code .code-area} or {@code .diff-tool-button} can otherwise match a leftover
     * window from an entirely different test.
     */
    private Node findIn(String cssOrText) {
        return from(tab.getContent()).lookup(cssOrText).query();
    }

    /** Whether {@code cssOrText} has appeared in the tab; for things a background load fills in. */
    private boolean shows(String cssOrText) {
        return from(tab.getContent()).lookup(cssOrText).tryQuery().isPresent();
    }

    /** Whether the viewer has finished loading {@code path}: named in the header, and editable. */
    private boolean fileLoaded(String path) {
        boolean titled = from(tab.getContent()).lookup(".diff-file-path").queryAll().stream()
                .anyMatch(n -> path.equals(n.getAccessibleText()));
        return titled && from(tab.getContent()).lookup(".diff-tool-button").queryAll().stream()
                .anyMatch(n -> n instanceof Button b && b.getText().startsWith("Edit")
                        && b.isVisible() && !b.isDisabled());
    }

    /** The working-tree file's content, as a condition: absent or mid-write reads as "not yet". */
    private boolean workingCopyReads(String name, String expected) {
        try {
            return expected.equals(Files.readString(tempDir.resolve(name), StandardCharsets.UTF_8));
        } catch (java.io.IOException notYet) {
            return false;
        }
    }

    private Button editButton() {
        return from(tab.getContent()).lookup(".diff-tool-button").queryAll().stream()
                .map(n -> (Button) n)
                .filter(b -> b.getText().startsWith("Edit"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Edit button found"));
    }

    @Test
    void editingTheCommitThatAddedADeletedFileRecreatesItInTheWorkingTree() throws Exception {
        // Selects the "add secret" commit's row in the graph, which fires
        // RightPanelManager.showCommitDetails - the real production path, not a
        // test-only shortcut. Waited for: the graph is filled by the refresh start() fired.
        RobotWait.untilOnFx("the \"add secret\" row never appeared", () -> shows("add secret"));
        clickOn(findIn("add secret"));
        // The commit's file list is loaded off the FX thread.
        RobotWait.untilOnFx("the commit's file list never showed secret.txt", () -> shows("secret.txt"));

        clickOn(findIn("secret.txt"));
        // And so is the file's content.
        RobotWait.untilOnFx("secret.txt never loaded into the viewer", () -> fileLoaded("secret.txt"));

        // beginEditing() switches to File view on its first press rather than editing
        // immediately when a diff is showing (a separate, pre-existing quirk); starting
        // from File view here keeps this test about the edit-availability fix, not that
        // one.
        clickOn(findIn("File view"));
        settle();

        Button edit = editButton();
        assertEquals("Edit working copy", edit.getText(),
                "the button must say, before the click, which copy it is about to open - "
                        + "the old wording promised the working directory and delivered "
                        + "the historical revision");

        interact(edit::fire);
        settle();

        DiffTextArea area = (DiffTextArea) findIn(".code-area");
        assertEquals("original secret content", area.getText());

        interact(() -> area.replaceText("edited secret content"));
        settle();

        clickOn(findIn("Save"));
        // The write runs on the git lane.
        RobotWait.until("Save never recreated secret.txt",
                () -> workingCopyReads("secret.txt", "edited secret content"));

        assertEquals("edited secret content",
                Files.readString(tempDir.resolve("secret.txt"), StandardCharsets.UTF_8),
                "Save must recreate the file in the working tree with the edited content");
    }
}
