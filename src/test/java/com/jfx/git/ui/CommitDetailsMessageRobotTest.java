package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The commit detail panel's message, now that a graph row carries only its subject (G15).
 *
 * <p>The subject is on the row and shows at once; the body is fetched on the git lane and
 * added when it arrives. The lane is held shut here with a task that waits on a latch, so
 * "before the body arrives" is a state the test can look at rather than a race it hopes
 * to catch.
 */
class CommitDetailsMessageRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    /** Holds the git lane shut while open; released in {@link #tidyUp} whatever happens. */
    private final CountDownLatch lane = new CountDownLatch(1);

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
        commit("a.txt", "a", "first with body\n\nThe first body.\nIt has two lines.\n");
        commit("b.txt", "b", "second with body\n\nThe second body.\n");

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        tab.refresh();
    }

    /** Waits, off the FX thread, for the page {@link #start} asked for. */
    private void graphLoaded() {
        RobotWait.until("the graph never loaded",
                () -> from(tab.getContent()).lookup("second with body").tryQuery().isPresent());
    }

    @AfterEach
    void tidyUp() {
        lane.countDown();
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

    /** Queues a task that keeps the tab's git lane busy until {@link #lane} is released. */
    private void holdTheGitLane() {
        CountDownLatch started = new CountDownLatch(1);
        GitTaskRunner.query(tab.getGitService(), () -> {
            started.countDown();
            try {
                return lane.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, held -> { }, error -> { });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "the git lane never picked up the hold");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The texts of the detail panel's body labels. */
    private List<String> bodies() {
        return from(tab.getContent()).lookup(".commit-body").queryAll().stream()
                .map(n -> ((Label) n).getText())
                .toList();
    }

    /** The panel's title-sized labels, which include the commit's subject. */
    private boolean panelShowsSubject(String subject) {
        return from(tab.getContent()).lookup(".t-title").queryAll().stream()
                .anyMatch(n -> n instanceof Label l && subject.equals(l.getText()));
    }

    @Test
    void theSubjectShowsAtOnceAndTheBodyFollows() {
        graphLoaded();
        holdTheGitLane();
        clickOn(from(tab.getContent()).lookup("first with body").<Node>query());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(panelShowsSubject("first with body"),
                "the subject is on the row, so it needs no git call to show");
        assertEquals(List.of(), bodies(), "the body has not been read yet - and nothing stands in for it");

        lane.countDown();
        RobotWait.until("the body never arrived",
                () -> List.of("The first body.\nIt has two lines.").equals(bodies()));
    }

    @Test
    void aBodyThatArrivesAfterTheSelectionMovedOnIsNotShown() {
        graphLoaded();
        holdTheGitLane();
        clickOn(from(tab.getContent()).lookup("first with body").<Node>query());
        clickOn(from(tab.getContent()).lookup("second with body").<Node>query());
        WaitForAsyncUtils.waitForFxEvents();

        // Both requests are queued behind the hold; the first commit's answers first.
        lane.countDown();
        RobotWait.drainGitWork();
        RobotWait.until("the second commit's body never arrived",
                () -> bodies().contains("The second body."));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(panelShowsSubject("second with body"));
        assertEquals(List.of("The second body."), bodies(),
                "only the selected commit's body belongs in the panel");
    }
}
