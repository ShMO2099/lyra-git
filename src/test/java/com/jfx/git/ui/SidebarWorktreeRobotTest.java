package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.Worktrees;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The WORKTREES section's own actions - add, lock/unlock, remove, prune - driven from the
 * sidebar against a real second working tree.
 *
 * <h2>These shell out to the real {@code git} executable</h2>
 * Unlike everything else the sidebar drives through JGit, {@code git worktree} has no JGit
 * equivalent - {@link Worktrees} runs the real {@code git} command line directly (see its
 * own {@code cliAvailable()}/{@code run(...)}). {@code NativeGitBackendTest} and {@code
 * NativeHunkAndRebaseTest} already depend on {@code git} being on PATH in this environment,
 * so this does not introduce a new requirement, only a new user of an existing one.
 *
 * <h2>Reaching context menus under Monocle Headless</h2>
 * As elsewhere this session, a robot right-click does not become a {@code ContextMenuEvent}
 * under Monocle Headless. The event is fired directly on the row, which is what {@code
 * ListCell.setContextMenu} itself listens for.
 */
class SidebarWorktreeRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    /** The application window, so a dialog lookup can exclude everything it contains. */
    private Stage mainWindow;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        commit("a.txt", "original a", "add a");

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        mainWindow = stage;
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() {
        // A click that starts git work returns before the work does; wait for it, so the
        // backend is not closed - and @TempDir not deleted - under a running command.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
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
     * The sidebar section headed {@code title}.
     *
     * <p>Every section carries its heading as a graphic - a label plus a count badge -
     * rather than as the pane's text. Some of them used to set both, which drew the
     * heading twice ("STASHES 0 STASHES"); now that only the graphic is set, a lookup by
     * {@code getText()} alone finds nothing.
     */
    private javafx.scene.control.TitledPane section(String title) {
        return FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .filter(n -> n instanceof javafx.scene.control.TitledPane tp
                        && headingOf(tp).startsWith(title))
                .map(n -> (javafx.scene.control.TitledPane) n)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + title + "\" section found"));
    }

    /** A pane's heading, wherever it keeps it: its text, or the label in its graphic. */
    private static String headingOf(javafx.scene.control.TitledPane pane) {
        if (pane.getText() != null && !pane.getText().isBlank()) {
            return pane.getText();
        }
        if (pane.getGraphic() instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                if (child instanceof javafx.scene.control.Label label
                        && label.getText() != null && !label.getText().isBlank()) {
                    return label.getText();
                }
            }
        }
        return "";
    }

    /**
     * Expands a section once the refresh that fills it has landed. The WORKTREES section
     * is hidden until a linked worktree exists, and the refresh that reveals it runs off
     * the FX thread.
     */
    private void expandSection(String title) {
        RobotWait.untilOnFx("the " + title + " section never appeared",
                () -> section(title).isVisible());
        clickOn(section(title));
        settle();
    }

    /**
     * The section's own list control - not an unscoped {@code lookup(".sidebar-list")},
     * which several sections' lists all share and which can find the wrong one.
     */
    private Node sectionList(String title) {
        return section(title).getContent();
    }

    private void openMenuOn(Node row) {
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    private void closeStrayContextMenus() {
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof javafx.scene.control.ContextMenu)
                .forEach(Window::hide));
        settle();
    }

    /** The add-worktree form's control with {@code id}: {@link SidebarPane#WORKTREE_BRANCH_ID} and the rest. */
    private static Node control(javafx.scene.control.DialogPane dialog, String id) {
        Node found = FxHarness.call(() -> dialog.lookup("#" + id));
        assertNotNull(found, "the form has no #" + id);
        return found;
    }

    /** The red line under the add-worktree form's field with {@code id}; empty while nothing is wrong. */
    private static String errorUnder(javafx.scene.control.DialogPane dialog, String id) {
        return FxHarness.call(() -> ManualQaCloneValidationTest.errorUnder(dialog, id).getText());
    }

    /** Opens "New worktree from this branch…" on the branch checked out now, and waits for the form. */
    private javafx.scene.control.DialogPane openFormOnCurrentBranch() {
        Node localTree = FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0);
        Node branchRow = from(localTree).lookup(service.getCurrentBranch()).query();
        openMenuOn(branchRow);
        clickOn("New worktree from this branch…");
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("OK");
        closeStrayContextMenus();
        return dialog;
    }

    private static boolean addDisabled(javafx.scene.control.DialogPane dialog) {
        return FxHarness.call(() -> dialog.lookupButton(javafx.scene.control.ButtonType.OK).isDisabled());
    }

    private void setText(Node field, String text) {
        interact(() -> ((javafx.scene.control.TextInputControl) field).setText(text));
    }

    /**
     * The list row whose text contains {@code fragment} - a worktree's admin name is
     * unique. Waits for it: the rows come from a refresh that runs off the FX thread.
     */
    private Node rowContaining(String fragment) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no row containing \"" + fragment + "\" appeared", () -> {
            row[0] = FxHarness.lookupAll(tab.getContent(), "list-cell").stream()
                    .filter(n -> n instanceof javafx.scene.control.ListCell<?> cell
                            && cell.getText() != null && cell.getText().contains(fragment))
                    .findFirst()
                    .orElse(null);
            return row[0] != null;
        });
        return row[0];
    }

    // ------------------------------------------------------------------

    /**
     * Not the WORKTREES pane's own "Add worktree…" - that section is hidden entirely
     * (see {@code SidebarPane.setVisible(linked)}) until a linked worktree already
     * exists, so there is nothing to right-click there for the very first one. The real
     * entry point is on a local branch row instead: "New worktree from this branch…".
     *
     * <p>This used to fail now and then under full-suite load, and was put down to the
     * environment. It was a race: the assertions ran a fixed 300ms after OK, while the
     * add was still running on the git lane. It now waits for the checked-out file.
     */
    @Test
    void addingAWorktreeCreatesARealSecondCheckout() throws Exception {
        Path worktreeDir = tempDir.resolveSibling(tempDir.getFileName() + "-wt");

        javafx.scene.control.DialogPane dialog = openFormOnCurrentBranch();
        // Pre-filled with the current branch's own name and a suggested sibling path;
        // this test wants its own path and a real new branch, not a second checkout of
        // the branch already checked out in the main worktree.
        setText(control(dialog, SidebarPane.WORKTREE_PATH_ID), worktreeDir.toString());
        setText(control(dialog, SidebarPane.WORKTREE_BRANCH_ID), "feature-wt");
        clickOn("Create this branch");
        settle();
        clickOn("Add worktree");
        // The add runs on the git lane with the form open, which closes once it worked. The
        // checked-out file is the last thing it produces - git sets HEAD before writing the
        // tree - so its presence means the whole command has finished, not merely that the
        // directory was made.
        RobotWait.until("the worktree was never checked out at " + worktreeDir,
                () -> Files.isRegularFile(worktreeDir.resolve("a.txt")));
        RobotWait.untilOnFx("the form never closed after the worktree was added",
                () -> dialog.getScene() == null || !dialog.getScene().getWindow().isShowing());

        assertTrue(Files.isDirectory(worktreeDir), "a real directory should have been created: " + worktreeDir);
        assertEquals("original a", Files.readString(worktreeDir.resolve("a.txt")),
                "the new worktree should be a real checkout of the repository's content");

        try (Git git = Git.open(worktreeDir.toFile())) {
            assertEquals("feature-wt", git.getRepository().getBranch(),
                    "the new worktree should be on the newly created branch");
        }
    }

    /**
     * What git would refuse is said under the branch as it is typed, and Add waits: the
     * branch checked out here already, then - ticked - a branch to create that exists. A new
     * name clears it, and the folder follows the name.
     */
    @Test
    void aBranchGitWouldRefuseIsSaidAsItIsTyped() {
        String current = service.getCurrentBranch();
        javafx.scene.control.DialogPane dialog = openFormOnCurrentBranch();

        String checkedOut = errorUnder(dialog, SidebarPane.WORKTREE_BRANCH_ID);
        assertTrue(checkedOut.startsWith(current + " is checked out in "), checkedOut);
        assertTrue(addDisabled(dialog), "Add waits for a branch git will check out");

        clickOn("Create this branch");
        RobotWait.untilOnFx("ticking Create did not say the branch exists", () ->
                ManualQaCloneValidationTest.errorUnder(dialog, SidebarPane.WORKTREE_BRANCH_ID).getText()
                        .equals("A branch named " + current + " exists already. Untick \"Create this branch\" to"
                                + " check it out."));
        assertTrue(addDisabled(dialog));

        setText(control(dialog, SidebarPane.WORKTREE_BRANCH_ID), "feature-typed");
        assertEquals("", errorUnder(dialog, SidebarPane.WORKTREE_BRANCH_ID));
        assertFalse(addDisabled(dialog), "a new branch in a new folder can be added");
        String folder = FxHarness.call(() ->
                ((javafx.scene.control.TextField) control(dialog, SidebarPane.WORKTREE_PATH_ID)).getText());
        assertTrue(folder.endsWith(tempDir.getFileName() + "-feature-typed"),
                "the suggested folder follows the branch: " + folder);

        interact(() -> ((javafx.scene.control.Button) dialog.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire());
    }

    @Test
    void lockingAndUnlockingAWorktreeIsReal() throws Exception {
        Path worktreeDir = tempDir.resolveSibling(tempDir.getFileName() + "-lockwt");
        assertTrue(service.addWorktree(worktreeDir.toFile(), "lock-wt", true).isOk());
        interact(() -> tab.refresh());
        settle();
        expandSection("WORKTREES");

        String adminName = worktreeAdminName(worktreeDir);
        Node row = rowContaining(adminName);
        openMenuOn(row);
        clickOn("Lock");
        RobotWait.until("the worktree was never locked", () -> isLocked(worktreeDir));

        assertTrue(isLocked(worktreeDir), "the worktree should be locked in real git state");

        interact(() -> tab.refresh());
        settle();
        Node lockedRow = rowContaining(adminName);
        openMenuOn(lockedRow);
        clickOn("Unlock");
        RobotWait.until("the worktree was never unlocked", () -> !isLocked(worktreeDir));

        assertFalse(isLocked(worktreeDir), "the worktree should be unlocked in real git state");
    }

    @Test
    void removingAWorktreeDeletesItForReal() throws Exception {
        Path worktreeDir = tempDir.resolveSibling(tempDir.getFileName() + "-removewt");
        assertTrue(service.addWorktree(worktreeDir.toFile(), "remove-wt", true).isOk());
        interact(() -> tab.refresh());
        settle();
        expandSection("WORKTREES");

        String adminName = worktreeAdminName(worktreeDir);
        Node row = rowContaining(adminName);
        openMenuOn(row);
        clickOn("Remove…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Remove worktree");
        RobotWait.until("the worktree was never removed", () -> !Files.exists(worktreeDir)
                && service.getWorktrees().size() == 1);

        assertFalse(Files.exists(worktreeDir), "the real directory should be gone: " + worktreeDir);
        assertEquals(1, service.getWorktrees().size(), "only the main worktree should be left");
    }

    @Test
    void pruningRemovesAStaleEntryAfterManualDeletion() throws Exception {
        Path worktreeDir = tempDir.resolveSibling(tempDir.getFileName() + "-prunewt");
        assertTrue(service.addWorktree(worktreeDir.toFile(), "prune-wt", true).isOk());
        // Deleted by hand, not through git - the administrative entry is left dangling,
        // which is exactly the state "missing - prune it" describes.
        deleteRecursively(worktreeDir);
        assertEquals(2, service.getWorktrees().size(), "the entry should still be registered");

        interact(() -> tab.refresh());
        settle();
        expandSection("WORKTREES");

        Node worktreeList = sectionList("WORKTREES");
        interact(() -> Event.fireEvent(worktreeList, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
        clickOn("Prune missing worktrees");
        RobotWait.until("the stale entry was never pruned",
                () -> service.getWorktrees().size() == 1);

        assertEquals(1, service.getWorktrees().size(),
                "pruning should have dropped the real, now-dangling administrative entry");
    }

    private String worktreeAdminName(Path worktreeDir) {
        return service.getWorktrees().stream()
                .filter(w -> !w.main())
                .filter(w -> w.path().equals(worktreeDir.toFile()) || w.path().toPath().equals(worktreeDir))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no linked worktree found for " + worktreeDir))
                .name();
    }

    private boolean isLocked(Path worktreeDir) {
        return service.getWorktrees().stream()
                .filter(w -> !w.main())
                .filter(w -> w.path().toPath().equals(worktreeDir))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no linked worktree found for " + worktreeDir))
                .locked();
    }

    private void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // Best effort - a leftover handle on Windows should not fail the test.
                }
            });
        }
    }

    /**
     * git refuses to remove a worktree with changes in it; the second question says that
     * is why - from the engine's refusal - and forcing it removes the folder.
     */
    @Test
    void aWorktreeWithChangesIsRemovedOnlyAfterSayingSo() throws Exception {
        Path worktreeDir = tempDir.resolveSibling(tempDir.getFileName() + "-dirtywt");
        assertTrue(service.addWorktree(worktreeDir.toFile(), "dirty-wt", true).isOk());
        Files.writeString(worktreeDir.resolve("a.txt"), "changed in the worktree", StandardCharsets.UTF_8);
        interact(() -> tab.refresh());
        settle();
        expandSection("WORKTREES");

        openMenuOn(rowContaining(worktreeAdminName(worktreeDir)));
        clickOn("Remove…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Remove worktree");
        untilDialogSubtitled("It has changes that are not committed");
        assertTrue(Files.exists(worktreeDir.resolve("a.txt")), "nothing goes before the second yes");

        clickOn("Remove anyway");
        RobotWait.until("the worktree was never removed", () -> !Files.exists(worktreeDir));
    }

    /**
     * Waits for a showing dialog whose subtitle - the line under its title, which says
     * why it is asking - reads {@code text}.
     */
    private static void untilDialogSubtitled(String text) {
        RobotWait.untilOnFx("no dialog subtitled \"" + text + "\" appeared", () ->
                new java.util.ArrayList<>(Window.getWindows()).stream()
                        .filter(w -> w.isShowing() && w.getScene() != null
                                && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
                        .map(w -> w.getScene().getRoot().lookup(".jfx-dialog-subtitle"))
                        .anyMatch(flow -> flow instanceof javafx.scene.text.TextFlow runs
                                && text.equals(runs.getChildren().stream()
                                        .filter(javafx.scene.text.Text.class::isInstance)
                                        .map(run -> ((javafx.scene.text.Text) run).getText())
                                        .collect(java.util.stream.Collectors.joining()))));
    }
}
