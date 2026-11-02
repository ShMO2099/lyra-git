package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.ui.views.BranchCleanupView;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Branch cleanup from the sidebar: the view lists the merged branches and nothing it must
 * keep, deletes the ticked ones, and one Undo brings all of them back.
 */
class BranchCleanupRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            for (String content : List.of("one\n", "one\ntwo\n")) {
                Files.writeString(tempDir.resolve("a.txt"), content, StandardCharsets.UTF_8);
                git.add().addFilepattern("a.txt").call();
                git.commit().setMessage("main " + content.length()).call();
            }
            git.branchCreate().setName("done-1").setStartPoint("main~1").call();
            git.branchCreate().setName("done-2").call();
            git.checkout().setCreateBranch(true).setName("wip").call();
            Files.writeString(tempDir.resolve("b.txt"), "b\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("b.txt").call();
            git.commit().setMessage("not merged").call();
            git.checkout().setName("main").call();
            service = new GitService();
            service.openRepository(tempDir.toFile());
        }
        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 760));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private List<String> branches() {
        return service.getLocalBranches().stream().map(r -> r.shortName()).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private TableView<Object> table() {
        return lookup(".branch-cleanup-table").queryAs(TableView.class);
    }

    private Button button(String text) {
        return lookup(".branch-cleanup .button").queryAll().stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(Button.class::cast).findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " button"));
    }

    private Node undo() {
        return lookup(".toast-action").queryAll().stream()
                .filter(n -> n instanceof Button b && "Undo".equals(b.getText()))
                .findFirst().orElse(null);
    }

    @Test
    void deletesTheMergedBranchesAndOneUndoBringsThemBack() {
        interact(() -> tab.getSidebar().openBranchCleanup());
        RobotWait.untilOnFx("the cleanup never opened", () -> tab.currentView() instanceof BranchCleanupView);
        RobotWait.untilOnFx("the merged branches were never listed", () -> table().getItems().size() == 2);
        assertEquals("Clean up branches", tab.currentView().title());

        clickOn(button("Delete selected"));
        RobotWait.until("the merged branches were never deleted",
                () -> branches().equals(List.of("main", "wip")));
        RobotWait.untilOnFx("the list never emptied", () -> table().getItems().isEmpty());

        RobotWait.untilOnFx("no Undo was offered", () -> undo() != null);
        clickOn(undo());
        RobotWait.until("Undo never brought them back",
                () -> branches().equals(List.of("done-1", "done-2", "main", "wip")));
        RobotWait.untilOnFx("the list never showed them again", () -> table().getItems().size() == 2);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(tab.currentView() instanceof BranchCleanupView, "the view stays open throughout");
    }
}
