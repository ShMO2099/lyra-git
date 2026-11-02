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
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What happens when the repository changes behind the window's back.
 *
 * <h2>The bug this exists for</h2>
 * Every refresh in this application used to hang off one of its own operations, so a
 * repository staged from another git client left the panel showing the file lists from
 * whenever it last rebuilt. The lists were stale and the diffs were live, which is the
 * worst of the two: a row still listed as unstaged ran a real {@code git diff}, git
 * correctly reported no difference between the work tree and the index, and the viewer
 * announced "Select a file to view its diff" - about a file that was selected. Every
 * component was working and the window looked broken.
 */
class ExternalChangeRobotTest extends ApplicationTest {

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
        // Refreshes and diffs are read on the git lane; the backend must outlive them.
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

    /** Selects the WIP row, which builds the staging panel, and waits for it to fill. */
    private void openStagingArea() {
        // What the panel should list once it has loaded - read first, and on this thread.
        java.util.Set<String> unstaged = new java.util.HashSet<>(service.getStagingState().unstaged());
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        if (unstaged.isEmpty()) {
            // A clean tree has no WIP row: row 0 is the commit, and there is no staging
            // list to wait for - only the refresh and the panel it builds.
            RobotWait.drainGitWork();
            return;
        }
        // The lists are filled by a refresh that reads the repository off the FX thread.
        RobotWait.untilOnFx("the staging panel never listed the working tree",
                () -> lookup(".staging-unstaged-list").tryQuery().isPresent()
                        && unstaged.equals(new java.util.HashSet<>(rowsOf("staging-unstaged-list"))));
    }

    /**
     * Runs the focus-gain check and lets it finish. The check reads a snapshot on the git
     * lane, and when the repository has moved its callback refreshes, which reads it again
     * - two round trips, each drained and then applied on the FX thread. A check still in
     * flight also swallows the next one, so each has to land before the test goes on.
     */
    private void checkForExternalChanges() {
        interact(() -> tab.refreshIfChangedExternally());
        for (int roundTrip = 0; roundTrip < 2; roundTrip++) {
            RobotWait.drainGitWork();
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /**
     * Stages from outside this window.
     *
     * <p>A separate backend on the same directory, which is what another git client is:
     * the index changes on disk and nothing tells the open tab about it.
     */
    private void stageElsewhere(String path) throws Exception {
        GitService other = new GitService();
        try {
            other.openRepository(tempDir.toFile());
            assertTrue(other.stageFile(path).isOk());
        } finally {
            other.close();
        }
    }

    private String placeholderText() {
        return lookup(".diff-empty").queryAll().stream()
                .filter(node -> node instanceof javafx.scene.control.Label)
                .map(node -> ((javafx.scene.control.Label) node).getText())
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("");
    }

    @Test
    void anEmptyDiffForASelectedFileSaysWhyRatherThanAskingForASelection() throws Exception {
        write("a.txt", "changed\n");
        openStagingArea();
        assertTrue(service.getStagingState().unstaged().contains("a.txt"));

        // The panel now lists a.txt as unstaged. Stage it from another client, leaving
        // the list stale and the diff genuinely empty.
        stageElsewhere("a.txt");

        clickOn("a.txt");
        // The diff is read off the FX thread; a loading message naming the file is shown until it lands.
        RobotWait.untilOnFx("the empty diff for a.txt never explained itself", () -> {
            String text = placeholderText();
            return text.contains("a.txt") && (text.contains("staged") || text.contains("outside"));
        });

        String shown = placeholderText();
        assertFalse(shown.contains("Select a file"),
                "a file IS selected; asking for a selection is the bug: " + shown);
        assertTrue(shown.contains("a.txt"), () -> "the message should name the file: " + shown);
        assertTrue(shown.contains("staged") || shown.contains("outside"),
                () -> "the message should say why there is nothing to show: " + shown);
    }

    @Test
    void nothingSelectedStillAsksForASelection() {
        openStagingArea();

        // The original message is right in the case it was written for, and this is it.
        assertEquals("Select a file to view its diff", placeholderText());
    }

    @Test
    void returningToTheWindowPicksUpAStageMadeElsewhere() throws Exception {
        write("a.txt", "changed\n");
        openStagingArea();

        // The panel's own view of the world, before anything external happens.
        checkForExternalChanges();

        stageElsewhere("a.txt");

        checkForExternalChanges();
        RobotWait.untilOnFx("the panel never moved a.txt into the staged list",
                () -> rowsOf("staging-staged-list").contains("a.txt")
                        && !rowsOf("staging-unstaged-list").contains("a.txt"));

        assertTrue(service.getStagingState().staged().contains("a.txt"),
                "git really did stage it");
        // The point of the check: the panel was rebuilt, so the staged/unstaged split it
        // shows now comes from the state git is actually in.
        assertTrue(rowsOf("staging-staged-list").contains("a.txt"),
                "the refresh should have moved it into the staged list");
        assertFalse(rowsOf("staging-unstaged-list").contains("a.txt"),
                "a.txt is staged now, so it must not still be listed as unstaged");
    }

    /** The rows of the named staging list, or empty when it is not on screen. */
    private java.util.List<?> rowsOf(String styleClass) {
        return lookup("." + styleClass).queryAll().stream()
                .filter(node -> node instanceof javafx.scene.control.ListView<?>)
                .map(node -> (javafx.scene.control.ListView<?>) node)
                .findFirst()
                .<java.util.List<?>>map(javafx.scene.control.ListView::getItems)
                .orElse(java.util.List.of());
    }

    @Test
    void anUntouchedRepositoryIsNotReloadedOnEveryCheck() throws Exception {
        write("a.txt", "changed\n");
        openStagingArea();

        checkForExternalChanges();
        String before = service.getStagingState().toString();

        // Nothing changed on disk, so the second check has nothing to do. What is being
        // pinned is that it is safe and cheap to call repeatedly - it runs on every
        // focus gain and every tab switch.
        checkForExternalChanges();

        assertEquals(before, service.getStagingState().toString(),
                "a check must never itself change the repository");
        assertTrue(rowsOf("staging-unstaged-list").contains("a.txt"),
                "and must not lose what was on screen");
    }
}
