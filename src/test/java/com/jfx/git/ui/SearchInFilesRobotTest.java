package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.ui.views.SearchInFilesView;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.fxmisc.richtext.GenericStyledArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Search in files, end to end: the view opens in the tab with its query field ready, finds
 * the matches - an untracked file's among them - grouped by file, and Enter on one opens
 * that file at that line. Ctrl+Shift+F brings the same results back.
 *
 * <p>On the CLI engine where there is one, so {@code ThreadGuard} proves the search runs
 * off the FX thread.
 */
class SearchInFilesRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitBackend backend;
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
            Files.writeString(tempDir.resolve("a.txt"), "alpha\nneedle one\n", StandardCharsets.UTF_8);
            Files.createDirectories(tempDir.resolve("dir"));
            Files.writeString(tempDir.resolve("dir/b.txt"), "x\ny\nneedle two\nz\n", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("files").call();
        }
        Files.writeString(tempDir.resolve("u.txt"), "needle three\n", StandardCharsets.UTF_8);

        try (var fixture = ThreadGuard.buildingFixture()) {
            if (NativeGitRunner.isGitInstalled()) {
                NativeGitBackend nativeBackend = new NativeGitBackend();
                assertTrue(nativeBackend.openRepository(tempDir.toFile()));
                backend = nativeBackend;
            } else {
                GitService service = new GitService();
                assertTrue(service.openRepository(tempDir.toFile()));
                backend = service;
            }
        }
        tab = new RepoTab(new LyraApp(), "test", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        tab.refresh();
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
    }

    private TextField queryField() {
        return lookup(".search-in-files-query").queryAs(TextField.class);
    }

    @SuppressWarnings("unchecked")
    private TreeView<SearchInFilesView.Hit> results() {
        return lookup(".search-in-files-results").queryAs(TreeView.class);
    }

    private List<String> filesFound() {
        TreeItem<SearchInFilesView.Hit> root = results().getRoot();
        return root == null ? List.of()
                : root.getChildren().stream().map(item -> item.getValue().path()).toList();
    }

    private void searchFor(String text) {
        interact(() -> queryField().setText(text));
        clickOn(queryField());
        type(KeyCode.ENTER);
    }

    @Test
    void findsMatchesAndOpensTheFileAtTheLine() {
        interact(() -> tab.openSearchInFiles());
        RobotWait.untilOnFx("the query field never took the focus",
                () -> queryField().getScene().getFocusOwner() == queryField());

        searchFor("needle");
        RobotWait.untilOnFx("the search never found the three files",
                () -> filesFound().equals(List.of("a.txt", "dir/b.txt", "u.txt")));

        TreeItem<SearchInFilesView.Hit> line = interactCall(() -> results().getRoot().getChildren().get(1)
                .getChildren().get(0));
        assertEquals(3, line.getValue().line());
        interact(() -> {
            results().getSelectionModel().select(line);
            results().requestFocus();
        });
        type(KeyCode.ENTER);

        RobotWait.untilOnFx("dir/b.txt never opened",
                () -> "dir/b.txt".equals(tab.diffViewer().filePathProperty().get()));
        RobotWait.untilOnFx("the caret never reached line 3", () -> {
            Node focus = tab.diffViewer().getScene().getFocusOwner();
            return focus instanceof GenericStyledArea<?, ?, ?> area && area.getCurrentParagraph() == 2;
        });
    }

    @Test
    void theSameResultsComeBackAndHeadLeavesUntrackedFilesOut() {
        interact(() -> tab.openSearchInFiles());
        searchFor("needle");
        RobotWait.untilOnFx("the first search never finished", () -> filesFound().size() == 3);
        RepoView first = tab.currentView();

        interact(() -> tab.closeViews());
        // What Ctrl+Shift+F calls; no shortcuts are installed on this bare scene.
        interact(() -> tab.openSearchInFiles());
        assertSame(first, tab.currentView(), "the same view, not a new empty one");
        assertEquals(3, filesFound().size(), "with its results");

        @SuppressWarnings("unchecked")
        ComboBox<SearchInFilesView.Scope> scope = lookup(".search-in-files .combo-box").queryAs(ComboBox.class);
        interact(() -> scope.setValue(SearchInFilesView.Scope.HEAD));
        RobotWait.untilOnFx("searching HEAD never left the untracked file out",
                () -> filesFound().equals(List.of("a.txt", "dir/b.txt")));
    }

    private <T> T interactCall(java.util.concurrent.Callable<T> work) {
        Object[] result = new Object[1];
        interact(() -> {
            try {
                result[0] = work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }
}
