package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.core.WorkspaceFiles;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;
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
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Cancel and ignore behavior from the all-files workspace tree. */
class IncompleteQaWorkspaceRobotTest extends ApplicationTest {
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
                Files.createDirectories(repository.resolve("docs"));
                Files.writeString(repository.resolve("docs/keep.txt"), "keep me\n", StandardCharsets.UTF_8);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("seed").call();
            }
        }
        Files.writeString(repository.resolve("working.txt"), "synthetic untracked work\n", StandardCharsets.UTF_8);
        service = new GitService();
        assertTrue(service.openRepository(repository.toFile()));
        tab = new RepoTab(new LyraApp(), "workspace-actions", service);
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

    @SuppressWarnings("unchecked") private TreeView<?> tree() {
        return lookup(".sidebar-list").queryAll().stream().filter(TreeView.class::isInstance)
                .map(n -> (TreeView<?>) n).filter(WorkspaceTreeView.class::isInstance)
                .filter(t -> t.getScene() != null).findFirst().orElseThrow();
    }

    private Node row(String text) {
        Node[] found = new Node[1];
        RobotWait.untilOnFx("workspace row did not appear: " + text, () -> {
            found[0] = lookup(text).tryQuery().orElse(null);
            return found[0] != null;
        });
        return found[0];
    }

    private void menuOn(String text) {
        Node cell = row(text);
        interact(() -> Event.fireEvent(cell, new javafx.scene.input.ContextMenuEvent(
                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void answer(String label) {
        DialogPane pane = RobotWait.untilDialogWithButton(label);
        interact(() -> {
            ButtonType choice = pane.getButtonTypes().stream().filter(button -> label.equals(button.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("missing " + label));
            ((Button) pane.lookupButton(choice)).fire();
        });
    }

    @Test void createCancelDeleteCancelAndIgnoreActOnTheSelectedWorkspacePath() throws Exception {
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("the WIP staging panel did not load", () ->
                lookup(".all-files-toggle").tryQuery().isPresent());
        clickOn(lookup(".all-files-toggle").queryAs(Node.class));
        RobotWait.untilOnFx("workspace tree did not load the tracked file", () ->
                tree().getRoot() != null && tree().getRoot().getChildren().size() > 0);
        interact(() -> tree().getRoot().getChildren().stream()
                .filter(item -> item.getValue() instanceof WorkspaceFiles.Entry entry
                        && "docs".equals(entry.name()))
                .findFirst().orElseThrow(() -> new AssertionError("docs folder missing from workspace tree"))
                .setExpanded(true));
        RobotWait.untilOnFx("docs folder did not load its child", () -> lookup("keep.txt").tryQuery().isPresent());
        menuOn("keep.txt");
        clickOn("New file…");
        DialogPane create = RobotWait.untilDialogWithButton("Cancel");
        interact(() -> {
            TextField name = (TextField) create.lookup(".text-field");
            name.setText("cancelled.txt");
        });
        answer("Cancel");
        assertFalse(Files.exists(repository.resolve("docs/cancelled.txt")));

        menuOn("keep.txt");
        clickOn("Delete…");
        answer("Cancel");
        assertTrue(Files.exists(repository.resolve("docs/keep.txt")), "Cancel preserves the selected file");

        menuOn("keep.txt");
        clickOn("Ignore");
        clickOn("This file");
        RobotWait.until("the workspace ignore action did not write its rule", () -> {
            try { return Files.readString(repository.resolve(".gitignore")).contains("/docs/keep.txt"); }
            catch (Exception absentYet) { return false; }
        });
        assertTrue(Files.exists(repository.resolve("docs/keep.txt")), "ignoring a path must not delete it");
    }
}
