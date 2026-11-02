package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.NativeGitRunner;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Sparse choices must include every tracked top-level folder, even unchanged ones. */
class ManualQaSparseFolderChoicesRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private NativeGitBackend backend;
    private RepoTab tab;

    @BeforeEach
    void requireNativeGit() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is required for sparse checkout");
    }

    @Override
    public void start(Stage stage) throws Exception {
        try (var ignored = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            NativeGitRunner git = new NativeGitRunner(tempDir.toFile());
            git.run("init", "-b", "main");
            git.run("config", "user.name", "Test User");
            git.run("config", "user.email", "test@example.com");
            git.run("config", "core.autocrlf", "false");
            write("assets/pixel.png", "blue\n");
            write("docs/guide.md", "guide\n");
            write("src/Main.java", "class Main {}\n");
            git.run("add", "-A");
            git.run("commit", "-m", "all tracked folders");
            write("assets/pixel.png", "orange\n");
            git.run("add", "assets/pixel.png");
            git.run("commit", "-m", "change only assets");
        }

        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            backend = new NativeGitBackend();
            assertTrue(backend.openRepository(tempDir.toFile()));
        }
        tab = new RepoTab(new LyraApp(), "sparse-folders", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        interact(tab::refresh);
    }

    @AfterEach
    void tidyUp() throws InterruptedException {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (backend != null) backend.close();
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
        Thread.sleep(250);
    }

    private void write(String relative, String content) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void sparseDialogOffersTrackedFoldersRegardlessOfWhichFolderTheHeadCommitChanged() {
        RobotWait.drainGitWork();
        Node localTree = FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0);
        interact(() -> Event.fireEvent(localTree, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        RobotWait.untilOnFx("the local tree menu did not open",
                () -> lookup(".context-menu").tryQuery().isPresent());
        Node localMenu = lookup(".context-menu").query();
        clickOn(from(localMenu).lookup("Sparse Checkout…").queryAs(Node.class));
        DialogPane dialog = RobotWait.untilDialogWithButton("Apply", "Turn off sparse checkout");

        Set<String> offered = FxHarness.call(() -> FxHarness.lookupAll(dialog, "check-box").stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .map(CheckBox::getText)
                .collect(Collectors.toSet()));
        assertTrue(offered.containsAll(Set.of("assets", "docs", "src")),
                "sparse choices come from the full tracked tree, not the last commit's diff: " + offered);
    }
}
