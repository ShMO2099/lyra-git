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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reloading a commit's diff keeps showing that commit.
 *
 * <h2>The bug this exists for</h2>
 * A diff opened from a commit, a stash or a comparison carries two things: the diff, and
 * the whole file as it stood at that revision, which is what File view shows. Reloading
 * replayed only the diff. File view then fell back to the working tree - while the header
 * still named the commit - so a reader looking at history was shown today's file under
 * yesterday's label.
 *
 * <p>The ignore-whitespace toggle had always reloaded this way; the external-change
 * refresh made it happen every time the window regained focus. Both go through the same
 * {@code reloadDiff}, and this drives it through the toggle, which is deterministic.
 */
class ReloadKeepsRevisionRobotTest extends ApplicationTest {

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

        commit("a.txt", "first version\n", "add a");
        commit("a.txt", "committed version\n", "change a");
        // And the working tree moves on again, uncommitted: this is what File view must NOT
        // fall back to once the diff is reloaded.
        Files.writeString(tempDir.resolve("a.txt"), "working tree version\n", StandardCharsets.UTF_8);

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
    }

    @AfterEach
    void tidyUp() {
        // Diffs are read on the git lane; the backend must outlive the last one.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
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

    /** Scoped to this tab, so a leftover window from another test cannot match. */
    private Node findIn(String cssOrText) {
        return from(tab.getContent()).lookup(cssOrText).query();
    }

    /** Whether {@code cssOrText} has appeared in the tab; for things a background load fills in. */
    private boolean shows(String cssOrText) {
        return from(tab.getContent()).lookup(cssOrText).tryQuery().isPresent();
    }

    /**
     * Whether the viewer has finished loading {@code path}. Every diff request, a reload
     * included, first puts the viewer into a loading state that disables Edit, so an
     * enabled Edit under the right header means this request's content has landed.
     */
    private boolean fileLoaded(String path) {
        boolean titled = from(tab.getContent()).lookup(".diff-file-path").queryAll().stream()
                .anyMatch(n -> path.equals(n.getAccessibleText()));
        return titled && from(tab.getContent()).lookup(".diff-tool-button").queryAll().stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Button b
                        && b.getText().startsWith("Edit") && b.isVisible() && !b.isDisabled());
    }

    /** The code surface's text, read on the FX thread. */
    private String shownText() throws Exception {
        return WaitForAsyncUtils.asyncFx(
                () -> ((DiffTextArea) findIn(".code-area")).getText()).get(5, TimeUnit.SECONDS);
    }

    private ToggleButton ignoreWhitespaceToggle() {
        return from(tab.getContent()).lookup(".diff-tool-toggle").queryAll().stream()
                .filter(n -> n instanceof ToggleButton t
                        && DiffViewer.NAME_IGNORE_WHITESPACE.equals(t.getAccessibleText()))
                .map(n -> (ToggleButton) n)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ignore-whitespace toggle"));
    }

    private ToggleButton fileViewToggle() {
        return from(tab.getContent()).lookup(".diff-segment").queryAll().stream()
                .filter(n -> n instanceof ToggleButton t && "File view".equals(t.getText()))
                .map(ToggleButton.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no File view toggle"));
    }

    private boolean isFileView() throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> tab.diffViewer().isFileView())
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void reloadingACommitsDiffKeepsFileViewOnThatCommit() throws Exception {
        // Waited for: the graph is filled by the refresh start() fired.
        RobotWait.untilOnFx("the \"change a\" row never appeared", () -> shows("change a"));
        clickOn(findIn("change a"));
        // The commit's file list is loaded off the FX thread.
        RobotWait.untilOnFx("the commit's file list never showed a.txt", () -> shows("a.txt"));
        clickOn(findIn("a.txt"));
        RobotWait.untilOnFx("a.txt's diff never loaded", () -> fileLoaded("a.txt"));
        assertFalse(isFileView(), "precondition: a commit's selected diff starts in Diff view");

        // The reload itself, through the same reloadDiff the focus-return refresh uses.
        ToggleButton ignore = ignoreWhitespaceToggle();
        interact(ignore::fire);
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the reloaded diff never landed", () -> fileLoaded("a.txt"));

        if (!isFileView()) {
            clickOn(fileViewToggle());
        }
        RobotWait.untilOnFx("the File view toggle did not select File view", () -> tab.diffViewer().isFileView());

        String shown = shownText();
        assertEquals("committed version", shown.strip(),
                "File view is labelled as the commit, so it has to show the commit - "
                        + "not the working tree it fell back to after the reload");
    }
}
