package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.NativeGitRunner;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Git LFS from a changed file's menu, on the engine that can run it.
 *
 * <p>Skipped where there is no git or no git-lfs: the menu is not offered there at all,
 * and what an engine without LFS answers is covered by the conformance suite.
 */
class LfsFileMenuRobotTest extends ApplicationTest {

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tempDir;

    private NativeGitBackend backend;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        if (!NativeGitRunner.isGitInstalled() || !NativeGitRunner.isGitLfsInstalled()) {
            return;
        }
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(tempDir.resolve("a.txt"), "a\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
        }
        Files.writeString(tempDir.resolve("design.psd"), "not really a photoshop file\n");
        Files.writeString(tempDir.resolve("Makefile"), "all:\n");

        backend = new NativeGitBackend();
        assertTrue(backend.openRepository(tempDir.toFile()));
        tab = new RepoTab(new LyraApp(), "test", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (backend != null) backend.close();
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    /** The "Git LFS" submenu of the unstaged row for {@code path}. */
    private Menu lfsMenu(String path) {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.drainGitWork();
        Menu[] menu = new Menu[1];
        RobotWait.untilOnFx("no Git LFS menu on the row for " + path, () -> {
            Node list = lookup(".staging-unstaged-list").tryQuery().orElse(null);
            if (list == null) return false;
            for (Node node : list.lookupAll(".list-cell")) {
                ListCell<?> cell = (ListCell<?>) node;
                ContextMenu rowMenu = cell.getContextMenu();
                if (!path.equals(cell.getItem()) || rowMenu == null) continue;
                for (MenuItem item : rowMenu.getItems()) {
                    if (item instanceof Menu sub && "Git LFS".equals(sub.getText())) {
                        menu[0] = sub;
                        return true;
                    }
                }
            }
            return false;
        });
        return menu[0];
    }

    private static List<String> labels(Menu menu) {
        return menu.getItems().stream().map(MenuItem::getText).filter(t -> t != null).toList();
    }

    @Test
    void aFilesMenuOffersToTrackItOrItsTypeAndToLockIt() {
        assumeTrue(tab != null, "needs git and git-lfs");

        Menu psd = lfsMenu("design.psd");
        Menu makefile = lfsMenu("Makefile");

        interact(() -> {
            assertEquals(List.of("Track this file", "Track all *.psd files", "Lock", "Unlock", "Refresh locks"),
                    labels(psd));
            MenuItem byType = makefile.getItems().get(1);
            assertEquals("Track all files of this type (no extension)", byType.getText());
            assertTrue(byType.isDisable(), "a file with no extension has no type to track");
        });
    }

    @Test
    void trackingAFilesTypeWritesThePatternToGitattributes() throws Exception {
        assumeTrue(tab != null, "needs git and git-lfs");
        Menu psd = lfsMenu("design.psd");

        interact(() -> psd.getItems().stream()
                .filter(i -> "Track all *.psd files".equals(i.getText())).findFirst().orElseThrow().fire());

        RobotWait.until("the pattern was never written",
                () -> backend.lfsTrackedPatterns().contains("*.psd"));
        assertTrue(Files.readString(tempDir.resolve(".gitattributes")).contains("*.psd filter=lfs"));
    }
}
