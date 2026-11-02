package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.NativeGitRunner;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The LOCAL pane's "Sparse Checkout…" menu item end to end, against a real native
 * backend - JGit has no sparse-checkout support at all (see {@code GitBackend.
 * isSparseCheckoutEnabled}'s own javadoc), so this is the one engine that can actually
 * prove the working tree changes for real. {@code SparseCheckoutDialogTest} already
 * covers the form's own wiring in isolation; this class exists to prove the button
 * click at the end of it really does narrow and restore a real working tree.
 */
class SidebarSparseCheckoutRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private NativeGitBackend backend;
    private RepoTab tab;

    @BeforeEach
    void checkNativeGitAvailable() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");
    }

    @Override
    public void start(Stage stage) throws Exception {
        // start() runs on the FX thread, so building the repository here is git on the
        // thread that draws - which is exactly what ThreadGuard exists to refuse. Saying
        // so is better than relying on the guard noticing this class is named ...Test:
        // a fixture helper without that in its name would otherwise be read as product.
        try (var _ = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            NativeGitRunner init = new NativeGitRunner(tempDir.toFile());
            var initRes = init.run("init", "-b", "main");
            if (!initRes.isOk()) init.run("init");
            init.run("config", "user.name", "Test User");
            init.run("config", "user.email", "test@example.com");
            init.run("config", "core.autocrlf", "false");

            Files.writeString(tempDir.resolve("root.txt"), "root", StandardCharsets.UTF_8);
            Files.createDirectories(tempDir.resolve("dir1"));
            Files.createDirectories(tempDir.resolve("dir2"));
            Files.writeString(tempDir.resolve("dir1/a.txt"), "a", StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("dir2/b.txt"), "b", StandardCharsets.UTF_8);
            init.run("add", "-A");
            init.run("commit", "-m", "initial");
        }

        backend = new NativeGitBackend();
        assertTrue(backend.openRepository(tempDir.toFile()));

        tab = new RepoTab(new LyraApp(), "test", backend);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() throws InterruptedException {
        // The real race: a test can finish while the sparse-checkout command it started is
        // still running on the git lane, and @TempDir then tries to delete a repository a
        // live subprocess is writing to. Waiting for the lane removes that.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
        // Kept as a margin, not as the fix: Windows can hold an exited subprocess's handles
        // on the directory for a moment after ProcessBuilder's waitFor() returns, and
        // @TempDir's own deletion is not something this test can hook into or retry.
        Thread.sleep(300);
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Waits for the sparse-checkout dialog to be on screen.
     *
     * <p>The three reads that fill this dialog - a whole-tree {@code diff-tree} and two
     * {@code sparse-checkout} calls - happen on the worker, so the dialog appears one
     * round trip after the menu item is clicked rather than during the click.
     *
     * <p>This class had its own poll for it, which still failed now and then: its
     * condition ran TestFX's global {@code lookup()} from the test thread while windows
     * were opening and closing, the walk threw {@code IndexOutOfBoundsException}, and
     * {@code WaitForAsyncUtils.waitFor} treated the throw as the answer. {@link RobotWait}
     * asks on the FX thread and treats a throw as "not yet".
     */
    private void waitForTheSparseCheckoutDialog() {
        RobotWait.untilDialogWithButton("Apply", "Turn off sparse checkout");
    }

    /** The LOCAL section's tree, first among the two ".branch-tree" controls the sidebar builds. */
    private Node localTreeNode() {
        return FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0);
    }

    private void openLocalPaneMenu() {
        interact(() -> Event.fireEvent(localTreeNode(), new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    @Test
    void applyingSparseCheckoutFromTheDialogReallyNarrowsTheWorkingTree() {
        openLocalPaneMenu();
        clickOn("Sparse Checkout…");
        waitForTheSparseCheckoutDialog();
        settle();

        clickOn("dir1");
        // The footer's verb counts what is ticked.
        clickOn("Check out 1 folder");
        settle();
        RobotWait.until("the working tree was never narrowed",
                () -> !new File(tempDir.toFile(), "dir2").exists());
        // Applying is three git commands, and the first, `sparse-checkout init --cone`,
        // narrows to the top-level files alone - dir1 goes with dir2 until `set` brings it
        // back. dir2's going only says the first has run; this waits for the last. Without
        // it the assertion below read the tree in between, about one run in five on master
        // and more often on a busy machine.
        RobotWait.drainGitWork();

        assertTrue(new File(tempDir.toFile(), "dir1").isDirectory(), "dir1 was asked for");
        assertFalse(new File(tempDir.toFile(), "dir2").exists(), "dir2 was not asked for");
        assertTrue(new File(tempDir.toFile(), "root.txt").exists(),
                "cone mode always keeps top-level files");
        assertTrue(backend.isSparseCheckoutEnabled());
    }

    @Test
    void disablingSparseCheckoutFromTheDialogRestoresTheFullWorkingTree() {
        assertTrue(backend.setSparseCheckoutPaths(java.util.List.of("dir1")).isOk());
        interact(() -> tab.refresh());
        settle();

        openLocalPaneMenu();
        clickOn("Sparse Checkout…");
        waitForTheSparseCheckoutDialog();
        settle();

        clickOn("Turn off sparse checkout");
        settle();
        RobotWait.until("the full tree never came back",
                () -> new File(tempDir.toFile(), "dir2").exists());
        // The question below is a git command of its own; let the operation and the refresh
        // it queues finish first, as above.
        RobotWait.drainGitWork();

        assertTrue(new File(tempDir.toFile(), "dir2").exists(), "the full tree should be back");
        assertFalse(backend.isSparseCheckoutEnabled());
    }
}
