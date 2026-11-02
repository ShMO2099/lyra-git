package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.DelegatingGitBackend;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.Worktrees;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.graph.GraphVisibility;
import com.jfx.git.model.Submodule;
import com.jfx.git.model.SubmoduleState;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the sidebar does when git refuses one of its operations, or the operation never
 * starts.
 *
 * <p>A refusal that changed nothing redraws nothing, as before the operation runner: a
 * branch git will not delete without force, a forced delete that fails, a submodule or a
 * worktree it will not remove, a submodule or a worktree it will not add. The tab is read
 * again only once something did change - the forced delete the question offers, say.
 *
 * <p>And a form whose work is cancelled before it starts - from the status bar, while
 * other work holds the repository - is handed back with what was typed, rather than left
 * locked with "Cloning…" in its footer: such an operation never reaches the form's own
 * way of showing a failure.
 *
 * <p>The fixture: {@code main}, and {@code loose} one commit ahead of it, so git refuses
 * to delete {@code loose} without force. The other refusals are said by a wrapper around
 * the JGit engine, which also counts the graph pages read - a refresh of the tab reads one.
 */
class SidebarRefusalRobotTest extends ApplicationTest {

    @TempDir
    Path dir;

    @TempDir
    Path elsewhere;

    private RefusingBackend backend;
    private RepoTab tab;
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(dir.resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.checkout().setCreateBranch(true).setName("loose").call();
            Files.writeString(dir.resolve("b.txt"), "b\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("b.txt").call();
            git.commit().setMessage("only on loose").call();
            git.checkout().setName("main").call();
        }
        // Off for this repository: the check reads the branches on its own schedule.
        com.jfx.git.core.RepositoryPreferences.setConflictWarning(dir.toFile(), false);

        GitService engine = new GitService();
        engine.openRepository(dir.toFile());
        backend = new RefusingBackend(engine);
        tab = new RepoTab(new LyraApp(), "refusals", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheTab() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        backend.pages.set(0);
    }

    @AfterEach
    void tidyUp() {
        release.countDown();
        RobotWait.drainGitWork();
        interact(() -> new ArrayList<>(Window.getWindows()).stream()
                .filter(w -> w.getScene() != null && w.getScene().getRoot() instanceof DialogPane)
                .forEach(Window::hide));
        if (tab != null) interact(() -> tab.close());
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        com.jfx.git.core.RepositoryPreferences.setConflictWarning(dir.toFile(), null);
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // A refusal redraws nothing
    // ------------------------------------------------------------------

    @Test
    void aDeleteRefusedForWantOfForceRedrawsNothingWhenNotForced() {
        Platform.runLater(() -> tab.getSidebar().deleteBranchWithUndo("loose", false));
        press(untilDialogWithButtonText("Force delete"), "Cancel");
        RobotWait.drainGitWork();

        assertEquals(0, backend.pages.get(), "nothing was deleted, so nothing is read again");
        assertTrue(backend.getLocalBranches().stream().anyMatch(b -> b.shortName().equals("loose")));
    }

    @Test
    void aForcedDeleteThatFailsRedrawsNothing() {
        backend.refusing = true;
        interact(() -> tab.getSidebar().deleteBranchWithUndo("loose", true));
        RobotWait.drainGitWork();

        assertEquals(0, backend.pages.get(), "the refusal is reported, and nothing is read again");
    }

    @Test
    void aDeinitRefusedRedrawsNothingWhenNotForced() {
        backend.refusing = true;
        Submodule submodule = new Submodule("libs/sub", "https://example.com/sub.git", "r", "r",
                SubmoduleState.CURRENT);
        Platform.runLater(() -> invoke(tab.getSidebar(), "confirmDeinit", submodule));
        press(untilDialogWithButtonText("Deinitialise"), "Deinitialise");
        press(untilDialogWithButtonText("Deinitialise anyway"), "Cancel");
        RobotWait.drainGitWork();

        assertEquals(0, backend.pages.get());
    }

    @Test
    void aWorktreeRemovalRefusedRedrawsNothingWhenNotForced() {
        backend.refusing = true;
        Worktrees.Worktree worktree = new Worktrees.Worktree("side", elsewhere.resolve("side").toFile(), "side",
                false, false, false);
        Platform.runLater(() -> invoke(tab.getSidebar(), "confirmRemoveWorktree", worktree));
        press(untilDialogWithButtonText("Remove worktree"), "Remove worktree");
        press(untilDialogWithButtonText("Remove anyway"), "Cancel");
        RobotWait.drainGitWork();

        assertEquals(0, backend.pages.get());
    }

    @Test
    void aSubmoduleGitWillNotAddRedrawsNothing() {
        backend.refusing = true;
        DialogPane form = openAddSubmodule();

        add(form);
        RobotWait.untilOnFx("the refusal never came back to the form", () -> !addButton(form).isDisabled());
        RobotWait.drainGitWork();

        assertEquals(1, backend.adds.get());
        assertEquals(0, backend.pages.get(), "nothing was added, so nothing is read again");
    }

    @Test
    void aWorktreeGitWillNotAddRedrawsNothing() {
        backend.refusing = true;
        DialogPane form = openAddWorktree();

        add(form);
        RobotWait.untilOnFx("the refusal never came back to the form", () -> !addButton(form).isDisabled());
        RobotWait.drainGitWork();

        assertEquals(1, backend.adds.get());
        assertEquals(0, backend.pages.get(), "nothing was added, so nothing is read again");
    }

    // ------------------------------------------------------------------
    // Cancelled before it started
    // ------------------------------------------------------------------

    @Test
    void aSubmoduleAddCancelledBeforeItStartsHandsTheFormBack() {
        // Refused, were it to run after all: nothing reaches a network.
        backend.refusing = true;
        DialogPane form = openAddSubmodule();
        holdTheLane();

        add(form);
        interact(() -> assertTrue(addButton(form).isDisabled(), "adding: the button is the add's"));
        cancelFromTheStatusBar();

        RobotWait.untilOnFx("the form was never handed back",
                () -> !addButton(form).isDisabled() && !form.getContent().isDisabled());
        interact(() -> {
            assertFalse(footer(form).startsWith("Cloning"), "the footer still says " + footer(form));
            assertEquals("https://example.com/library.git",
                    ((TextField) form.lookup("#" + SidebarPane.SUBMODULE_URL_ID)).getText(), "what was typed is kept");
        });
        release.countDown();
        RobotWait.drainGitWork();
        assertEquals(0, backend.adds.get(), "the add never ran");
    }

    @Test
    void aWorktreeAddCancelledBeforeItStartsHandsTheFormBack() {
        backend.refusing = true;
        DialogPane form = openAddWorktree();
        holdTheLane();

        add(form);
        interact(() -> assertTrue(addButton(form).isDisabled(), "adding: the button is the add's"));
        cancelFromTheStatusBar();

        RobotWait.untilOnFx("the form was never handed back",
                () -> !addButton(form).isDisabled() && !form.getContent().isDisabled());
        interact(() -> assertFalse(footer(form).startsWith("Checking out"), "the footer still says " + footer(form)));
        release.countDown();
        RobotWait.drainGitWork();
        assertEquals(0, backend.adds.get(), "the add never ran");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Opens the add-submodule form and fills it in with a repository to clone. */
    private DialogPane openAddSubmodule() {
        interact(() -> tab.getSidebar().promptAddSubmodule());
        DialogPane form = untilDialogWithButtonText("Add submodule");
        interact(() -> ((TextField) form.lookup("#" + SidebarPane.SUBMODULE_URL_ID))
                .setText("https://example.com/library.git"));
        interact(() -> assertEquals("library",
                ((TextField) form.lookup("#" + SidebarPane.SUBMODULE_PATH_ID)).getText(), "the folder follows the URL"));
        return form;
    }

    /** Opens the add-worktree form and fills it in: a new branch, in a new folder. */
    private DialogPane openAddWorktree() {
        interact(() -> invoke(tab.getSidebar(), "promptAddWorktree", "topic"));
        DialogPane form = untilDialogWithButtonText("Add worktree");
        interact(() -> {
            ((CheckBox) form.lookup("#" + SidebarPane.WORKTREE_CREATE_ID)).setSelected(true);
            ((TextField) form.lookup("#" + SidebarPane.WORKTREE_PATH_ID))
                    .setText(elsewhere.resolve("topic-tree").toString());
            assertFalse(addButton(form).isDisabled(), "the form takes it");
        });
        return form;
    }

    private static Button addButton(DialogPane form) {
        return (Button) form.lookupButton(ButtonType.OK);
    }

    /** Presses the form's own button, which runs its work with the form open. */
    private void add(DialogPane form) {
        interact(() -> addButton(form).fire());
    }

    /** What the form's footer says now. */
    private static String footer(DialogPane form) {
        Node status = form.lookup(".jfx-footer-status");
        assertNotNull(status, "the form has a footer status");
        for (Node child : ((javafx.scene.Parent) status).getChildrenUnmodifiable()) {
            if (child instanceof Label label) return label.getText() == null ? "" : label.getText();
        }
        return "";
    }

    /**
     * Holds this repository's lane until {@link #release}: whatever is queued after it waits.
     * The lane of the tab's own backend, which wraps this test's.
     */
    private void holdTheLane() {
        GitTaskRunner.run(tab.getGitService(), () -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GitResult.ok("held");
        }, result -> { });
    }

    /** The status bar's Cancel, which stops the task it is showing - here, one still queued. */
    private void cancelFromTheStatusBar() {
        interact(() -> {
            Button cancel = (Button) tab.getStatusBar().lookup(".status-cancel");
            assertNotNull(cancel, "the status bar has a Cancel");
            cancel.fire();
        });
    }

    /** Waits for a showing dialog with a button reading {@code text}, as it is shown. */
    private static DialogPane untilDialogWithButtonText(String text) {
        DialogPane[] found = new DialogPane[1];
        RobotWait.untilOnFx("no dialog with a \"" + text + "\" button appeared", () -> {
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (!window.isShowing() || window.getScene() == null
                        || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                    continue;
                }
                for (ButtonType type : pane.getButtonTypes()) {
                    if (pane.lookupButton(type) instanceof Button button && text.equals(button.getText())) {
                        found[0] = pane;
                        return true;
                    }
                }
            }
            return false;
        });
        return found[0];
    }

    /** Presses the button reading {@code text}. */
    private void press(DialogPane pane, String text) {
        interact(() -> pane.getButtonTypes().stream()
                .map(pane::lookupButton)
                .filter(node -> node instanceof Button button && text.equals(button.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + text + "\" button"))
                .fire());
    }

    /** Calls the sidebar's own method {@code name}, private ones too: what a menu item would. */
    private static void invoke(Object target, String name, Object... args) {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                try {
                    method.invoke(target, args);
                    return;
                } catch (InvocationTargetException e) {
                    throw new AssertionError(name + " threw", e.getCause());
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        throw new AssertionError("SidebarPane has no " + name);
    }

    /**
     * The JGit engine, refusing on request what this test has git refuse, and counting the
     * graph pages read and the adds asked for.
     */
    private static final class RefusingBackend extends DelegatingGitBackend {
        private final GitBackend engine;
        final AtomicInteger pages = new AtomicInteger();
        final AtomicInteger adds = new AtomicInteger();
        volatile boolean refusing;

        RefusingBackend(GitBackend engine) {
            this.engine = engine;
        }

        private static GitResult refused() {
            return GitResult.failed("fatal: refused for this test");
        }

        @Override protected GitBackend reader() { return engine; }
        @Override protected GitBackend writer() { return engine; }

        @Override public boolean openRepository(java.io.File directory) throws com.jfx.git.core.GitOperationException {
            return engine.openRepository(directory);
        }
        @Override public boolean isOpen() { return engine.isOpen(); }
        @Override public void close() { engine.close(); }
        @Override public java.io.File getWorkTree() { return engine.getWorkTree(); }
        @Override public java.io.File getDirectory() { return engine.getDirectory(); }

        @Override public GitResult deleteBranch(String name, boolean force) {
            return refusing ? refused() : super.deleteBranch(name, force);
        }
        @Override public GitResult deinitSubmodule(String path, boolean force) {
            return refusing ? refused() : super.deinitSubmodule(path, force);
        }
        @Override public GitResult removeWorktree(java.io.File path, boolean force) {
            return refusing ? refused() : super.removeWorktree(path, force);
        }
        @Override public GitResult addSubmodule(String url, String path) {
            adds.incrementAndGet();
            return refusing ? refused() : super.addSubmodule(url, path);
        }
        @Override public GitResult addWorktree(java.io.File path, String branch, boolean create) {
            adds.incrementAndGet();
            return refusing ? refused() : super.addWorktree(path, branch, create);
        }

        @Override public GraphPage getCommitGraphPage(int limit, GraphOrder order) {
            pages.incrementAndGet();
            return super.getCommitGraphPage(limit, order);
        }
        @Override public GraphPage getCommitGraphPage(int limit, GraphOrder order, GraphVisibility visibility) {
            pages.incrementAndGet();
            return super.getCommitGraphPage(limit, order, visibility);
        }
        @Override public GraphPage getCommitGraphPageVisible(int limit) {
            pages.incrementAndGet();
            return super.getCommitGraphPageVisible(limit);
        }
    }
}
