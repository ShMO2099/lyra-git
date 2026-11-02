package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.ui.views.SearchInFilesView;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeView;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies search options and path globs are connected to the real search view. */
class IncompleteQaSearchControlsRobotTest extends ApplicationTest {
    @TempDir Path repository;
    private GitService service;
    private RepoTab tab;

    @Override public void start(Stage stage) throws Exception {
        try (var fixture = ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA User");
                config.setString("user", null, "email", "qa-user@example.test");
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
                Files.writeString(repository.resolve("notes.txt"), "Needle\nneedle\nneedles\n", StandardCharsets.UTF_8);
                Files.createDirectories(repository.resolve("src"));
                Files.writeString(repository.resolve("src/code.java"), "Needle\n", StandardCharsets.UTF_8);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("seed").call();
            }
        }
        service = new GitService();
        assertTrue(service.openRepository(repository.toFile()));
        tab = new RepoTab(new LyraApp(), "search-controls", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        tab.refresh();
    }

    @AfterEach void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private TextField query() { return lookup(".search-in-files-query").queryAs(TextField.class); }
    private TextField paths() {
        return FxHarness.lookupAll(tab.currentView().content(), "text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast).filter(field -> field != query()).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private TreeView<SearchInFilesView.Hit> results() {
        return lookup(".search-in-files-results").queryAs(TreeView.class);
    }
    private List<String> foundFiles() {
        var root = results().getRoot();
        return root == null ? List.of() : root.getChildren().stream().map(item -> item.getValue().path()).toList();
    }
    @Test void togglesGlobAndInvalidRegexReachTheSearchBackendAndMessage() {
        interact(() -> tab.openSearchInFiles());
        interact(() -> query().setText("needle"));
        interact(() -> query().fireEvent(new javafx.event.ActionEvent()));
        RobotWait.untilOnFx("initial working-tree search did not finish", () -> foundFiles().size() == 2);

        ToggleButton matchCase = FxHarness.lookupAll(tab.currentView().content(), "find-option").stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast).filter(t -> "Match case".equals(t.getAccessibleText())).findFirst().orElseThrow();
        interact(() -> matchCase.setSelected(true));
        RobotWait.untilOnFx("case-sensitive search did not finish", () ->
                foundFiles().equals(List.of("notes.txt"))
                        && results().getRoot().getChildren().get(0).getChildren().size() == 2);

        interact(() -> matchCase.setSelected(false));
        RobotWait.untilOnFx("case-insensitive search did not finish", () -> foundFiles().size() == 2);
        interact(() -> { paths().setText("*.java"); paths().fireEvent(new javafx.event.ActionEvent()); });
        RobotWait.untilOnFx("Java glob did not narrow results", () -> foundFiles().equals(List.of("src/code.java")));
        interact(() -> { paths().setText("!*.java"); paths().fireEvent(new javafx.event.ActionEvent()); });
        RobotWait.untilOnFx("exclude glob did not omit Java results", () -> foundFiles().equals(List.of("notes.txt")));

        ToggleButton regex = FxHarness.lookupAll(tab.currentView().content(), "find-option").stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast).filter(t -> "Regular expression".equals(t.getAccessibleText())).findFirst().orElseThrow();
        interact(() -> { paths().clear(); query().setText("["); regex.setSelected(true); });
        RobotWait.untilOnFx("invalid regex was not explained", () -> {
            String summary = tab.currentView().content().lookup(".t-caption") instanceof javafx.scene.control.Label l
                    ? l.getText() : "";
            return summary.contains("Unclosed character class");
        });
    }
}
