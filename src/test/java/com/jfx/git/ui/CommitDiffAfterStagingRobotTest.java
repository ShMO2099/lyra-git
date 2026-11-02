package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.model.CommitInfo;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A commit's diff opened after the staging area offers nothing that writes to the index.
 *
 * <p>The diff viewer is shared by the staging area and the commit details. Its hunk
 * actions used to be handlers the host installed on it, and the commit details cleared
 * only the revert one: "Stage hunk", installed for an unstaged file, stayed on the viewer
 * and showed on every commit opened afterwards - offering to apply a past commit's hunk
 * to the index.
 */
class CommitDiffAfterStagingRobotTest extends ApplicationTest {

    private static final String FILE = "a.txt";
    private static final String OTHER = "b.txt";

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;
    private String edit;

    @Override
    public void start(Stage stage) throws Exception {
        work = root.resolve("work");
        try (var fixture = ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA");
                config.setString("user", null, "email", "qa@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
                write(FILE, lines(-1));
                write(OTHER, "other\n");
                commitAll(git, "add the files");
                write(FILE, lines(5));
                edit = commitAll(git, "edit line five");
            }
            // Uncommitted work, so the staging area has an unstaged file to open.
            write(OTHER, "other, edited\n");
            GitService service = new GitService();
            if (!service.openRepository(work.toFile())) {
                throw new IllegalStateException("could not open the fixture");
            }
            backend = service;
        }
        tab = new RepoTab(new LyraApp(), "commit after staging", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheGraph() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the graph never listed the edit", () -> graphRowOf(edit) >= 0);
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    @Test
    void aCommitOpenedAfterAnUnstagedFileOffersNoStaging() {
        // The staging area first: the WIP row, then the unstaged file, whose hunk offers
        // Stage hunk.
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("b.txt never appeared in the unstaged list", () -> unstagedList(OTHER) != null);
        interact(() -> {
            ListView<String> list = unstagedList(OTHER);
            list.getSelectionModel().clearSelection();
            list.getSelectionModel().select(OTHER);
        });
        RobotWait.untilOnFx("the unstaged file's hunk never offered Stage hunk",
                () -> !visibleStageButtons().isEmpty());

        // Then a commit, and its changed file.
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(graphRowOf(edit));
        });
        RobotWait.untilOnFx("the commit's changed files never listed a.txt",
                () -> changedFiles() != null && changedFiles().getItems().stream()
                        .anyMatch(change -> FILE.equals(change.path())));
        interact(() -> {
            ListView<GitBackend.FileChange> files = changedFiles();
            for (int i = 0; i < files.getItems().size(); i++) {
                if (FILE.equals(files.getItems().get(i).path())) {
                    files.getSelectionModel().select(i);
                }
            }
        });
        RobotWait.untilOnFx("a.txt's commit diff never rendered",
                () -> FILE.equals(tab.diffViewer().headerPath()) && !tab.diffViewer().isFileView()
                        && !FxHarness.lookupAll(tab.diffViewer(), "diff-hunk-actions").isEmpty());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of(), FxHarness.call(() -> visibleStageButtons().stream().map(Button::getText).toList()),
                "a past commit's hunk must not be offered to the index");
    }

    // ------------------------------------------------------------------

    private List<Button> visibleStageButtons() {
        return FxHarness.lookupAll(tab.diffViewer(), "diff-hunk-action").stream()
                .filter(n -> n instanceof Button b && b.isVisible() && b.getScene() != null
                        && b.getParent() != null && b.getParent().isVisible())
                .map(Button.class::cast)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private ListView<String> unstagedList(String path) {
        for (Node node : FxHarness.lookupAll(tab.getContent(), "staging-unstaged-list")) {
            for (Node row : node.lookupAll(".list-cell")) {
                if (row instanceof ListCell<?> cell && path.equals(cell.getItem())
                        && cell.getScene() != null && cell.isVisible()) {
                    return (ListView<String>) cell.getListView();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private ListView<GitBackend.FileChange> changedFiles() {
        return FxHarness.lookupAll(tab.getContent(), "changed-files-list").stream()
                .filter(ListView.class::isInstance)
                .map(node -> (ListView<GitBackend.FileChange>) node)
                .findFirst().orElse(null);
    }

    private int graphRowOf(String hash) {
        List<GitGraphRow> rows = tab.graphList().getItems();
        for (int i = 0; i < rows.size(); i++) {
            CommitInfo commit = rows.get(i).getCommit();
            if (commit != null && hash.equals(commit.getHash())) {
                return i;
            }
        }
        return -1;
    }

    private void write(String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    }

    private static String commitAll(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).call().getName();
    }

    /** Twenty numbered lines, line {@code changed} edited. */
    private static String lines(int changed) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            text.append("line ").append(i).append(i == changed ? ", edited" : "").append('\n');
        }
        return text.toString();
    }
}
