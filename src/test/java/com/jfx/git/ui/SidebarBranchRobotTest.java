package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The LOCAL branch tree's own actions - checkout, create, rename, delete, rebase-onto -
 * driven from the sidebar rather than the toolbar or the graph, which already have their
 * own coverage ({@code RepoToolbarRobotTest}, {@code InteractiveRebaseRobotTest}). This is
 * the one place these particular gestures - double-click to check out, drag aside, "New
 * branch from here…" starting at a chosen commit rather than HEAD - actually get exercised.
 *
 * <h2>Reaching the row's context menu under Monocle Headless</h2>
 * As with every other context menu this session, a robot right-click does not become a
 * {@code ContextMenuEvent} under Monocle Headless. The event is fired directly on the row,
 * which is what {@code TreeCell.setContextMenu} itself listens for.
 *
 * <p>Remote-branch checkout is deliberately not here: it needs a second, bare repository to
 * fetch from, the same machinery {@code RepoToolbarRemoteRobotTest} already built - that
 * scenario belongs alongside it, not duplicated with a lighter setup that would prove less.
 *
 * <h2>Waiting for outcomes, not for time</h2>
 * The three tests that confirm a {@code TextInputDialog} - {@code
 * creatingABranchFromThePaneMenuMakesARealBranch}, {@code
 * newBranchFromHereStartsAtThatRowsCommitNotHead}, {@code renamingABranchRenamesItForReal}
 * - used to fail now and then as part of the full class, never alone, and were put down to
 * Monocle and to file locking. Every wait in them was a fixed 400ms {@code settle()}: after
 * the refresh, to open the dialog, after OK - and then the assertion, whether or not the
 * git write had finished on the lane. The one poll that was tried, {@code WaitForAsyncUtils
 * .waitFor}, fails outright the first time its condition throws, and a read of a
 * repository mid-write can throw - which fits "polling made it worse" at least as well as
 * contention does. Each step now waits for its own outcome through {@link RobotWait}. If
 * one of these fails with that in place, treat it as a real regression.
 */
class SidebarBranchRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    /** The application window, so a dialog lookup can exclude everything it contains. */
    private Stage mainWindow;
    private String mainBranch;

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
        commit("root.txt", "root", "root commit");
        mainBranch = service.getCurrentBranch();
        assertTrue(service.createBranch("feature", null, false).isOk(),
                "a branch not checked out, sitting at the same commit as " + mainBranch);

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        mainWindow = stage;
        interact(() -> tab.refresh());
        settle();
        expandLocalSection();
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

    private RevCommit head() throws Exception {
        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD"));
        }
    }

    private String hashOf(String ref) throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.getRepository().resolve(ref).getName();
        }
    }

    private List<String> localBranchNames() {
        return service.getLocalBranches().stream().map(r -> r.shortName()).toList();
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Refreshes, and waits for everything the refresh queued on the git lane, then for its
     * results to be applied on the FX thread. The sidebar and the graph reload as two
     * separate queries; waiting for the lane covers both, where a fixed sleep could land
     * between them and find a row just before the rebuild replaced it.
     */
    private void refreshAndWait() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Waits for a real, visible text field to appear - a fixed sleep after opening a
     * dialog was not consistently enough for a {@code TextInputDialog} specifically when
     * running after several other tests in the same class. Polled on the FX thread by
     * {@link RobotWait}, which treats a lookup that throws mid-open as "not yet" rather
     * than failing outright as the {@code WaitForAsyncUtils.waitFor} poll here used to.
     */
    private void waitForVisibleTextField() {
        RobotWait.untilOnFx("no visible text field appeared",
                () -> dialogFields().stream().anyMatch(Node::isVisible));
    }

    /** Waits for a showing dialog whose title or message says {@code fragment}. */
    private void untilDialogSaying(String fragment) {
        RobotWait.untilOnFx("no dialog saying \"" + fragment + "\" appeared", () -> dialogSays(fragment));
    }

    /**
     * Whether a showing dialog's title (its header text, which the dialog shell keeps as
     * the title's words) or its message (the content text) says {@code fragment}. FX thread.
     */
    private static boolean dialogSays(String fragment) {
        return new java.util.ArrayList<>(Window.getWindows()).stream()
                .filter(w -> w.isShowing() && w.getScene() != null
                        && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
                .map(w -> (javafx.scene.control.DialogPane) w.getScene().getRoot())
                .anyMatch(pane -> pane.getHeaderText() != null && pane.getHeaderText().contains(fragment)
                        || pane.getContentText() != null && pane.getContentText().contains(fragment));
    }

    /** Whether HEAD's first parent is {@code ref}'s tip; throws while either cannot be read. */
    private boolean headSitsOn(String ref) {
        try {
            return head().getParent(0).getName().equals(hashOf(ref));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @see FxHarness#dialogFields(javafx.stage.Window) */
    private java.util.Set<Node> dialogFields() {
        return new java.util.LinkedHashSet<>(FxHarness.dialogFields(mainWindow));
    }

    /**
     * Fills the dialog's text field and confirms it - real clicks on the field and the
     * button, but the text itself is set directly on the FX thread rather than typed.
     *
     * <p>{@code write(...)} depends on the field genuinely holding keyboard focus at the
     * moment each character arrives; against this specific {@code TextInputDialog}, that
     * held reliably alone but not consistently several tests into this class, on a shared
     * FX Application Thread carrying more accumulated layout work than a fresh JVM has.
     * What these tests are actually proving - that confirming the dialog reaches a real
     * git call - does not depend on keystroke-by-keystroke delivery working, which
     * {@code RebaseRewordProbeTest} already exists to prove separately.
     *
     * @param verb the confirming button's label: the prompt names its action
     */
    private void fillDialogFieldAndConfirm(String text, String verb) {
        // A ContextMenu opened by firing its own request event directly, rather than by
        // a real mouse press, is not reliably auto-hidden by JavaFX once the item that
        // opens this dialog is clicked - proven by Window.getWindows() still listing it
        // as showing afterwards, across several of this class's tests, non-deterministically
        // depending on run order. Left open, it is one more showing window a lookup can
        // land in ahead of the dialog actually being interacted with. Its job is done by
        // the time this method runs, so closing it here is always safe.
        closeStrayContextMenus();
        waitForVisibleTextField();
        Node field = dialogFields().stream().findFirst()
                .orElseThrow(() -> new AssertionError("dialog field not found"));
        clickOn(field);
        interact(() -> ((javafx.scene.control.TextInputControl) field).setText(text));
        settle();
        clickOn(verb);
        // No wait here: the verb starts the git write on the lane, and each caller waits
        // for the outcome it is about to assert on.
    }

    private void closeStrayContextMenus() {
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof ContextMenu)
                .forEach(Window::hide));
        settle();
    }

    /** LOCAL starts expanded per {@code SidebarPane}'s constructor, but a fresh click never hurts. */
    private void expandLocalSection() {
        Node local = lookup("LOCAL").tryQuery().orElse(null);
        if (local != null) {
            clickOn(local);
            settle();
        }
    }

    /** The LOCAL section's tree, first among the two ".branch-tree" controls the sidebar builds. */
    private Node localTreeNode() {
        return FxHarness.lookupAll(tab.getContent(), "branch-tree").get(0);
    }

    /**
     * Opens the context menu on the branch row showing {@code label}, without a real
     * right-click.
     *
     * <p>Scoped to the local tree rather than an unscoped {@code lookup(label)}: the
     * current branch's name is also shown as plain text in the toolbar's branch
     * selector, and an unscoped query can find that node - which has no context menu of
     * its own - instead of the sidebar row.
     */
    private void openMenuOn(String label) {
        Node row = localRow(label);
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    /**
     * The LOCAL tree's row showing {@code label}. Waits for it: the rows come from a
     * refresh that runs off the FX thread, including the one {@code start()} began.
     */
    private Node localRow(String label) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no \"" + label + "\" row appeared in LOCAL", () -> {
            row[0] = from(localTreeNode()).lookup(label).query();
            return row[0] != null;
        });
        return row[0];
    }

    /**
     * Waits for the row's own menu to offer {@code item}. Hide and Show rebuild the tree
     * through a refresh off the FX thread, and until that lands the row still carries the
     * menu it was drawn with - the one offering the opposite.
     */
    private void untilRowOffers(String label, String item) {
        RobotWait.untilOnFx("the \"" + label + "\" row never offered \"" + item + "\"", () -> {
            Node node = from(localTreeNode()).lookup(label).query();
            while (!(node instanceof javafx.scene.control.Control control
                    && control.getContextMenu() != null)) {
                node = node.getParent();
            }
            return ((javafx.scene.control.Control) node).getContextMenu().getItems().stream()
                    .anyMatch(i -> item.equals(i.getText()));
        });
    }

    private ContextMenu openContextMenu() {
        return (ContextMenu) Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof ContextMenu)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no context menu is currently showing"));
    }

    // ------------------------------------------------------------------

    @Test
    void doubleClickingABranchChecksItOutForReal() throws Exception {
        assertEquals(mainBranch, service.getCurrentBranch());

        // Not doubleClickOn(): TestFX's own double-click sends two separate synthetic
        // clicks, and Monocle Headless does not reliably fold them into one clickCount
        // == 2 event the way a real double-click would - the cell's own handler checks
        // getClickCount() == 2 on a single MOUSE_CLICKED, so that is dispatched directly.
        Node row = localRow("feature");
        interact(() -> Event.fireEvent(row, new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 10, 10, 100, 100,
                javafx.scene.input.MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, true, false, true, null)));
        RobotWait.until("feature was never checked out",
                () -> "feature".equals(service.getCurrentBranch()));

        assertEquals("feature", service.getCurrentBranch());
    }

    @Test
    void checkoutFromTheContextMenuSwitchesBranchesToo() throws Exception {
        openMenuOn("feature");
        clickOn("Checkout");
        RobotWait.until("feature was never checked out",
                () -> "feature".equals(service.getCurrentBranch()));

        assertEquals("feature", service.getCurrentBranch());
    }

    /**
     * An edit in the way of a checkout is offered stash and retry: the question is asked,
     * and agreeing stashes, checks out and re-applies, so the edit arrives on the branch.
     */
    @Test
    void checkingOutOverAnEditStashesChecksOutAndReapplies() throws Exception {
        commit("multi.txt", "1\n2\n3\n4\n5\n", "five lines");
        assertTrue(service.deleteBranch("feature", true).isOk());
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("multi.txt", "1\n2\n3\n4\n5\nfeature\n", "feature adds a line");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        Files.writeString(tempDir.resolve("multi.txt"), "zero\n1\n2\n3\n4\n5\n", StandardCharsets.UTF_8);
        refreshAndWait();

        openMenuOn("feature");
        clickOn("Checkout");
        javafx.scene.control.DialogPane ask = RobotWait.untilDialogWithButton("Stash and check out");
        assertEquals("Stash your changes and check out?", ask.getHeaderText());
        assertTrue(ask.getContent().lookupAll(".label").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Label l
                        && l.getText().startsWith("They are stashed, the checkout runs, and they are put back.")),
                "the question says what will happen");
        interact(() -> ((javafx.scene.control.Button) ask.getButtonTypes().stream()
                .filter(type -> type.getText().equals("Stash and check out"))
                .map(ask::lookupButton).findFirst().orElseThrow()).fire());

        RobotWait.until("feature was never checked out", () -> "feature".equals(service.getCurrentBranch()));
        RobotWait.until("the edit was never re-applied", () -> readQuietly("multi.txt")
                .replace("\r\n", "\n").equals("zero\n1\n2\n3\n4\n5\nfeature\n"));
        RobotWait.until("the stash it made was never dropped", () -> service.getStashEntries().isEmpty());
    }

    private String readQuietly(String file) {
        try {
            return Files.readString(tempDir.resolve(file));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A branch whose upstream has been deleted on the remote says so on its row. */
    @Test
    void aBranchWhoseUpstreamHasGoneIsMarked() throws Exception {
        assertTrue(service.addRemote("origin", tempDir.resolve("elsewhere").toString()).isOk());
        assertTrue(service.setLocalConfig("branch.feature.remote", "origin").isOk());
        assertTrue(service.setLocalConfig("branch.feature.merge", "refs/heads/feature").isOk());
        refreshAndWait();

        RobotWait.untilOnFx("feature's row never said its upstream had gone",
                () -> from(localTreeNode()).lookup(".branch-gone").queryAll().size() == 1);
        Node mark = from(localTreeNode()).lookup(".branch-gone").query();
        assertEquals("gone", ((javafx.scene.control.Label) mark).getText());
    }

    @Test
    void checkoutIsDisabledForTheAlreadyCurrentBranch() {
        openMenuOn(mainBranch);
        ContextMenu menu = openContextMenu();
        MenuItem checkout = menu.getItems().stream()
                .filter(i -> "Checkout".equals(i.getText()))
                .findFirst().orElseThrow();

        assertTrue(checkout.isDisable(), "checking out the branch you are already on is a no-op");
        interact(menu::hide);
        settle();
    }

    @Test
    void creatingABranchFromThePaneMenuMakesARealBranch() throws Exception {
        // The pane-level menu is attached to the tree control itself, not to the
        // TitledPane's own title label sitting above it - firing on the title finds
        // nothing to trigger.
        interact(() -> Event.fireEvent(localTreeNode(), new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
        clickOn("New branch…");
        fillDialogFieldAndConfirm("from-pane", "Create and check out");
        RobotWait.until("branch from-pane was never created",
                () -> localBranchNames().contains("from-pane"));

        assertEquals(hashOf(mainBranch), hashOf("from-pane"),
                "created with no start point chosen, it should sit at the current branch's tip");
    }

    @Test
    void newBranchFromHereStartsAtThatRowsCommitNotHead() throws Exception {
        // Give "feature" a commit main does not have, so starting from feature is a real,
        // checkable difference from starting at HEAD.
        assertTrue(service.checkoutBranch("feature").isOk());
        commit("feature-only.txt", "x", "feature-only commit");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        // Two async round trips, not one: refresh() reloads the sidebar and the graph
        // as two separate background queries. A fixed wait landing between them meant a
        // row could be found and its context menu opened just before the rebuild
        // replaced it; refreshAndWait waits for both.
        refreshAndWait();

        openMenuOn("feature");
        clickOn("New branch from here…");
        fillDialogFieldAndConfirm("from-feature", "Create and check out");
        RobotWait.until("branch from-feature was never created",
                () -> localBranchNames().contains("from-feature"));

        assertEquals(hashOf("feature"), hashOf("from-feature"),
                "should start at feature's tip");
        assertNotEquals(hashOf(mainBranch), hashOf("from-feature"),
                "not at the current branch's tip, which is what a start point of HEAD would give");
    }

    /**
     * This used to fail now and then as part of the full class, never alone: "feature"
     * still present, or "renamed-feature" missing. The assertions ran a fixed 400ms after
     * OK, so they could not tell a rename that landed late from one that never landed -
     * the question the earlier investigation (in git history, alongside the sibling note on
     * {@code SidebarStashTagRobotTest.newBranchFromHereOnATagStartsAtThatTagsCommitNotHead})
     * never answered. It now waits up to 30s for the rename, so a failure here means it
     * never landed.
     */
    @Test
    void renamingABranchRenamesItForReal() throws Exception {
        openMenuOn("feature");
        clickOn("Rename…");
        // The prompt pre-fills the field with the current name; setText replaces it
        // outright, same as clearing then typing would.
        fillDialogFieldAndConfirm("renamed-feature", "Rename branch");
        RobotWait.until("feature was never renamed", () -> {
            List<String> names = localBranchNames();
            return names.contains("renamed-feature") && !names.contains("feature");
        });

        assertFalse(localBranchNames().contains("feature"));
        assertTrue(localBranchNames().contains("renamed-feature"));
    }

    /**
     * Hide and Solo are kept by full ref name, and only a remote's rename carried them
     * across. A hidden branch renamed came back into the graph under its new name, the old
     * name kept for ever; a soloed one left the graph soloed to a ref that no longer
     * existed - only HEAD's history on screen, and no row anywhere marked soloed or
     * offering "Clear solo".
     */
    @Test
    void renamingAHiddenBranchKeepsItHidden() throws Exception {
        com.jfx.git.core.AppPreferences.setGraphHiddenBranches(java.util.Set.of("refs/heads/feature"));
        refreshAndWait();
        untilRowOffers("feature", "Show in graph");

        openMenuOn("feature");
        clickOn("Rename…");
        fillDialogFieldAndConfirm("renamed-feature", "Rename branch");
        RobotWait.until("feature was never renamed", () -> localBranchNames().contains("renamed-feature"));
        RobotWait.drainGitWork();

        assertEquals(java.util.Set.of("refs/heads/renamed-feature"),
                com.jfx.git.core.AppPreferences.graphHiddenBranches(), "hidden under its new name, and only that");
    }

    /** As {@link #renamingAHiddenBranchKeepsItHidden}, for Solo. */
    @Test
    void renamingASoloedBranchKeepsTheSolo() throws Exception {
        com.jfx.git.core.AppPreferences.setGraphSoloBranch("refs/heads/feature");
        refreshAndWait();
        untilRowOffers("feature", "Clear solo");

        openMenuOn("feature");
        clickOn("Rename…");
        fillDialogFieldAndConfirm("renamed-feature", "Rename branch");
        RobotWait.until("feature was never renamed", () -> localBranchNames().contains("renamed-feature"));
        RobotWait.drainGitWork();

        assertEquals("refs/heads/renamed-feature", com.jfx.git.core.AppPreferences.graphSoloBranch());
        untilRowOffers("renamed-feature", "Clear solo");
    }

    @Test
    void deletingAMergedBranchNeedsOnlyOneConfirmation() throws Exception {
        assertTrue(localBranchNames().contains("feature"));

        openMenuOn("feature");
        clickOn("Delete…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Delete branch");
        RobotWait.until("feature was never deleted", () -> !localBranchNames().contains("feature"));

        assertFalse(localBranchNames().contains("feature"),
                "feature sits at the same commit as " + mainBranch + " - deleting it is never "
                        + "blocked, so one confirmation should be enough");
    }

    @Test
    void deletingAnUnmergedBranchAsksForAForce() throws Exception {
        assertTrue(service.checkoutBranch("feature").isOk());
        commit("unmerged.txt", "x", "commit only feature has");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        refreshAndWait();

        openMenuOn("feature");
        clickOn("Delete…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Delete branch");
        // The force prompt opens from the refused delete's own result, so once it is
        // showing the plain delete has already run - and failed.
        untilDialogSaying("Delete feature anyway?");

        // git refused the plain delete, so a second, force-worded confirmation should
        // have appeared instead of the branch quietly vanishing or the click doing nothing.
        assertTrue(localBranchNames().contains("feature"),
                "an unmerged branch must survive a plain delete");
        clickOn("Force delete");
        RobotWait.until("feature was never force-deleted", () -> !localBranchNames().contains("feature"));

        assertFalse(localBranchNames().contains("feature"),
                "confirming the force prompt should delete it for real");
    }

    /**
     * Detaches HEAD onto a fresh commit that no branch or tag reaches - the exact state
     * {@link com.jfx.git.core.GitService#isDetachedHeadOrphanRisk()} exists to flag - so
     * checking out {@code mainBranch} next would strand it.
     */
    private String detachOntoAnOrphanCandidate() throws Exception {
        String start = hashOf(mainBranch);
        assertTrue(service.checkoutBranch(start).isOk());
        commit("orphan-candidate.txt", "x", "made only while detached");
        refreshAndWait();
        assertTrue(service.isHeadDetached());
        return start;
    }

    @Test
    void checkingOutAwayFromAnOrphanRiskCommitAsksFirst() throws Exception {
        detachOntoAnOrphanCandidate();
        String atRisk = hashOf("HEAD");

        openMenuOn(mainBranch);
        clickOn("Checkout");
        // The orphan check runs on the git lane before the prompt can open.
        untilDialogSaying("Leave this commit behind?");

        assertEquals(atRisk, hashOf("HEAD"),
                "the warning must appear before the checkout, not after");
        assertTrue(lookup("Check out anyway").tryQuery().isPresent(),
                "the button that goes ahead says it does so anyway");

        clickOn("Cancel");
        // Nothing to wait for when nothing should happen; let any work that would have
        // run finish, then check it did not.
        RobotWait.drainGitWork();

        assertEquals(atRisk, hashOf("HEAD"), "cancelling must leave HEAD exactly where it was");
        assertTrue(service.isHeadDetached());
    }

    @Test
    void confirmingLeavesTheOrphanRiskCommitAndChecksOut() throws Exception {
        detachOntoAnOrphanCandidate();

        openMenuOn(mainBranch);
        clickOn("Checkout");
        untilDialogSaying("Leave this commit behind?");
        clickOn("Check out anyway");
        RobotWait.until(mainBranch + " was never checked out",
                () -> mainBranch.equals(service.getCurrentBranch()));

        assertEquals(mainBranch, service.getCurrentBranch());
        assertFalse(service.isHeadDetached());
    }

    @Test
    void checkingOutABranchWhileDetachedButStillReachableAsksNothing() throws Exception {
        // Detach onto the current tip itself: nothing new was committed, so main's tip
        // still contains this exact commit and leaving it costs nothing.
        String tip = hashOf(mainBranch);
        assertTrue(service.checkoutBranch(tip).isOk());
        refreshAndWait();
        assertTrue(service.isHeadDetached());

        openMenuOn("feature");
        clickOn("Checkout");
        RobotWait.until("feature was never checked out",
                () -> "feature".equals(service.getCurrentBranch()));

        assertEquals("feature", service.getCurrentBranch(),
                "reachable through main's own tip - no warning should have blocked this");
        assertFalse(FxHarness.call(() -> dialogSays("Leave this commit behind?")),
                "no warning should be showing");
    }

    /**
     * "Rebase current onto this" used to call {@code gitService.rebase(...)} directly -
     * an instant, unreviewable rebase. It now opens the same interactive-rebase editor
     * {@code GitGraphCell.startInteractiveRebase} does from the graph, matching
     * the commercial clients' documented behaviour (drag/branch-menu/graph-commit all open one
     * todo-plan editor) - so this test now confirms the plan via "Start rebase" before
     * checking the real outcome, the same way {@code InteractiveRebaseRobotTest} does.
     */
    @Test
    void rebasingCurrentOntoThisReplaysRealCommits() throws Exception {
        // Diverge: a commit only main has, and a commit only feature has, from the shared root.
        commit("main-only.txt", "m", "main-only commit");
        assertTrue(service.checkoutBranch("feature").isOk());
        commit("feature-only.txt", "f", "feature-only commit");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        refreshAndWait();

        openMenuOn("feature");
        clickOn("Rebase current onto this");
        // The editor opens only once the commits to replay have been read on the lane.
        RobotWait.untilDialogWithButton("Start rebase");

        assertNotNull(lookup(".rebase-heading").tryQuery().orElse(null),
                "the interactive rebase editor should open, not run the rebase silently");
        clickOn("Start rebase");
        RobotWait.until("the rebase never landed on feature", () -> headSitsOn("feature"));

        RevCommit newHead = head();
        assertEquals("main-only commit", newHead.getShortMessage(),
                "main's own commit should have been replayed on top, not discarded");
        assertEquals(hashOf("feature"), newHead.getParent(0).getName(),
                "it should now sit directly on feature's tip");
        assertTrue(Files.exists(tempDir.resolve("feature-only.txt")));
        assertTrue(Files.exists(tempDir.resolve("main-only.txt")));
    }

    @Test
    void cancellingRebaseCurrentOntoThisLeavesRealHistoryUntouched() throws Exception {
        commit("main-only.txt", "m", "main-only commit");
        assertTrue(service.checkoutBranch("feature").isOk());
        commit("feature-only.txt", "f", "feature-only commit");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        refreshAndWait();
        String before = head().getName();

        openMenuOn("feature");
        clickOn("Rebase current onto this");
        RobotWait.untilDialogWithButton("Start rebase");
        clickOn("Cancel");
        RobotWait.drainGitWork();

        assertEquals(before, head().getName(), "cancelling must not touch the repository at all");
    }

    // ------------------------------------------------------------------
    // Hide & Solo
    // ------------------------------------------------------------------

    @AfterEach
    void clearHideAndSoloState() {
        // Global AppPreferences state, not scoped to this test's throwaway repository -
        // left set, it would leak into whichever test runs next in this class.
        com.jfx.git.core.AppPreferences.setGraphHiddenBranches(null);
        com.jfx.git.core.AppPreferences.setGraphSoloBranch(null);
    }

    @Test
    void hidingABranchFromItsContextMenuRemovesItsCommitsFromTheGraph() throws Exception {
        assertTrue(service.checkoutBranch("feature").isOk());
        commit("feature-only.txt", "f", "feature-only commit");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        refreshAndWait();

        openMenuOn("feature");
        clickOn("Hide from graph");
        // The preference is written at once; the graph follows from a refresh off the FX
        // thread. The commit was on screen before (refreshAndWait), so its going is real.
        RobotWait.untilOnFx("feature's commit never left the graph",
                () -> !commitSubjectsInGraph().contains("feature-only commit"));

        assertTrue(com.jfx.git.core.AppPreferences.graphHiddenBranches()
                        .contains("refs/heads/feature"),
                "the hidden set should remember this branch by its full ref name");
        assertFalse(commitSubjectsInGraph().contains("feature-only commit"),
                "feature's own commit should no longer be in the graph");
    }

    @Test
    void soloingABranchStoresItAndRefreshesTheGraph() throws Exception {
        assertTrue(service.checkoutBranch("feature").isOk());
        commit("feature-only.txt", "f", "feature-only commit");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        refreshAndWait();

        openMenuOn("feature");
        clickOn("Solo (show only this branch)");
        settle();

        assertEquals("refs/heads/feature", com.jfx.git.core.AppPreferences.graphSoloBranch());
    }

    @Test
    void theCurrentBranchOffersNeitherHideNorSolo() {
        openMenuOn(mainBranch);
        ContextMenu menu = openContextMenu();

        boolean hasHideOrSolo = menu.getItems().stream()
                .anyMatch(i -> i.getText() != null
                        && (i.getText().contains("Hide") || i.getText().contains("Solo")
                            || i.getText().contains("Show in graph")));
        assertFalse(hasHideOrSolo,
                "the branch you are standing on cannot be hidden or solo'd from its own graph");
        interact(menu::hide);
        settle();
    }

    /** Subjects of every real (non-WIP) commit currently in the on-screen graph. */
    @SuppressWarnings("unchecked")
    private List<String> commitSubjectsInGraph() {
        var graph = (javafx.scene.control.ListView<com.jfx.git.graph.GitGraphRow>)
                lookup(".commit-graph").query();
        return graph.getItems().stream()
                .filter(row -> !row.isWipNode() && row.getCommit() != null)
                .map(row -> row.getCommit().getSubject())
                .toList();
    }

    /**
     * The force prompt names why git refused: the engine's refusal says the branch is not
     * fully merged, and the prompt says so, rather than reading git's words for it.
     */
    @Test
    void theForcePromptSaysTheBranchIsNotFullyMerged() throws Exception {
        assertTrue(service.checkoutBranch("feature").isOk());
        commit("unmerged.txt", "x", "commit only feature has");
        assertTrue(service.checkoutBranch(mainBranch).isOk());
        refreshAndWait();

        openMenuOn("feature");
        clickOn("Delete…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Delete branch");
        untilDialogSubtitled("Not fully merged");

        clickOn("Force delete");
        RobotWait.until("feature was never force-deleted", () -> !localBranchNames().contains("feature"));
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
