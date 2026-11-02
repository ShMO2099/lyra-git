package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitService;
import com.jfx.git.graph.GitGraphRow;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preferences' "Initial commits" and "Show all commits in graph" (§3.1 of
 * {@code PARITY_PLAN.md}), reaching the actual on-screen graph rather than just
 * {@code AppPreferences} and {@code GitService.getCommitGraphPage}, which already have
 * their own coverage. What is new here is {@code RepoTab} actually reading the two
 * preferences when it decides how big the first page is.
 *
 * <p>{@code AppPreferences} is global static state read at construction time, so the
 * preference has to be set before {@code RepoTab} is built. {@code start()} is left
 * empty for that reason: each test sets its own preference and creates its own commits
 * before ever building a tab, in {@link #showTab}, rather than the shared setup
 * {@code ApplicationTest} otherwise expects. The settings file itself is already
 * redirected for the whole suite; {@link #tidyUp} only resets the two preferences these
 * tests actually touch.
 */
class GraphInitialLoadRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private void openTabWith(int commitCount) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        for (int i = 0; i < commitCount; i++) {
            commit("f" + i + ".txt", "v", "commit " + i);
        }
    }

    /**
     * Builds the tab and its stage on the FX thread. {@code Stage} is itself a
     * JavaFX class - constructing one on the test thread before handing it in is what
     * {@code start()} above pointedly does not do for these tests, and doing it here
     * instead hit the exact "Not on FX application thread" {@code TestFX} itself
     * guards {@code ApplicationTest.start} against.
     */
    private void showTab() {
        interact(() -> {
            tab = new RepoTab(new LyraApp(), "test", service);
            TabPane pane = new TabPane(tab);
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 1200, 700));
            stage.show();
            tab.refresh();
        });
        // The first page is read on the git lane and handed to the list in one piece, so
        // the first real commit row on screen means the whole page has arrived.
        RobotWait.untilOnFx("the graph's first page never arrived", () -> realCommitRowCount() > 0);
    }

    @Override
    public void start(Stage stage) {
        // Real construction happens per-test below, once each test has set the
        // preference it wants to exercise and created its commits - a single shared
        // start() would build the tab before either was in place.
    }

    @AfterEach
    void tidyUp() {
        // Selecting the first row after the page lands starts more reads on the git lane;
        // let them finish before the backend closes and @TempDir deletes the repository.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        AppPreferences.setInitialGraphCommits(0);
        AppPreferences.setShowAllCommitsInGraph(false);
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    @SuppressWarnings("unchecked")
    private ListView<GitGraphRow> graphView() {
        return (ListView<GitGraphRow>) lookup(".commit-graph").query();
    }

    private long realCommitRowCount() {
        return graphView().getItems().stream()
                .filter(row -> !row.isWipNode() && row.getCommit() != null)
                .count();
    }

    @Test
    void theFirstPageStopsAtTheConfiguredInitialCommitCount() throws Exception {
        AppPreferences.setInitialGraphCommits(5);
        openTabWith(12);

        showTab();

        assertEquals(5, realCommitRowCount(),
                "the graph's first page should stop at the configured initial count");
    }

    @Test
    void showAllCommitsInGraphIgnoresTheInitialCountAndLoadsEverything() throws Exception {
        AppPreferences.setInitialGraphCommits(5);
        AppPreferences.setShowAllCommitsInGraph(true);
        openTabWith(12);

        showTab();

        assertEquals(12, realCommitRowCount(),
                "Show All Commits in Graph should override the initial-count cap");
    }
}
