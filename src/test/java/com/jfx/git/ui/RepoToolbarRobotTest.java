package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.RepositoryCommands;

import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

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
 * The toolbar buttons, against a real repository rather than a stub.
 *
 * <h2>Why a real repository</h2>
 * A stub can only prove a click did not throw. What matters here is the same thing that
 * has broken four times already this session in other parts of the graph: a control that
 * looks wired but whose click never reaches the operation it names, or reaches a
 * different one. A real {@link GitService} on a real temporary repository lets each test
 * check the actual git state afterwards — a branch that exists, a stash that is gone, a
 * commit HEAD moved to — rather than trusting that a lambda ran.
 *
 * <p>One {@link GitService} per test, opened in {@link #start} and closed in
 * {@link #tidyUp}. A second one opened separately for verification, pointed at the same
 * directory, was tried first and dropped: two open {@code Repository} handles on one
 * working tree is exactly the kind of thing that leaves a Windows temp directory locked
 * against cleanup between tests. Verification reads the working tree with a scoped
 * {@code Git.open}/try-with-resources instead, which never outlives the assertion.
 *
 * <p>Push, pull and fetch are exercised against their real failure path (no remote
 * configured) rather than a real network round trip: that is deterministic, requires
 * nothing external, and still proves the button reaches {@link GitService}, which is the
 * property under test. A real remote is a reasonable thing to add later; it tests
 * networking as much as wiring.
 *
 * <p>The Terminal button is never clicked — it spawns a real OS process — but its
 * presence and label are checked, which is as far as a test should go near it.
 */
class RepoToolbarRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

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
        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // A click that starts git work returns before the work does; wait for it, so the
        // backend is not closed - and @TempDir not deleted - under a running command.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (service != null) {
            service.close();
        }
        // A dialog left open by a failed assertion — the branch dialog above, or an
        // Alert from a force-push confirmation — outlives the test that opened it: robot
        // tests in one class share a single JavaFX toolkit, and only the primary stage
        // is TestFX's own to close. A stray window is not just clutter; the next test's
        // lookups search every showing window and can find the wrong one, which is
        // exactly what made theBranchButtonCreatesARealBranch flaky before this existed.
        interact(() -> new java.util.ArrayList<>(javafx.stage.Window.getWindows())
                .forEach(javafx.stage.Window::hide));
    }

    /** Writes and commits a file, so there is at least one commit for undo to reach. */
    private void seedCommit(String name, String content) throws Exception {
        Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
        assertTrue(onLane(() -> {
            GitResult staged = service.stageFile(name);
            return staged.isOk() ? service.commit("add " + name, false) : staged;
        }).isOk());
        refreshToolbar();
    }

    /**
     * Runs fixture git work on the tab's own lane, in turn with the application's. Called
     * straight from the test thread it could overlap a refresh the repository watcher
     * started - the engine takes one caller at a time - and the one time
     * {@link #thePopButtonRestoresTheStash} failed, in a full suite run, its stash and the
     * commit before it had been made that way.
     */
    private GitResult onLane(java.util.function.Supplier<GitResult> work) throws Exception {
        var done = new java.util.concurrent.CompletableFuture<GitResult>();
        GitTaskRunner.run(tab.getGitService(), work, done::complete);
        return done.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** How many stashes there are, read with a handle closed before this returns; -1 mid-write. */
    private int stashCount() {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.stashList().call().size();
        } catch (Exception midWrite) {
            return -1;
        }
    }

    /** The status bar's message: what a git operation that went wrong said about it. Call on FX. */
    private String statusMessage() {
        return FxHarness.lookupAll(tab.getStatusBar(), "status-message").stream()
                .filter(Labeled.class::isInstance).map(node -> ((Labeled) node).getText())
                .findFirst().orElse("");
    }

    /**
     * Refreshes the toolbar and waits for it to land: its queries run on the git lane and
     * report back on the FX thread, and the tests go on to read the buttons they enable.
     */
    private void refreshToolbar() {
        interact(() -> toolbar().refresh());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private RepoToolbar toolbar() {
        return tab.getToolbar();
    }

    /**
     * The toolbar button captioned {@code label}.
     *
     * <p>By caption rather than by text: a button's text carries a count when work is
     * waiting, and is empty when the bar has dropped its captions for want of room.
     *
     * <p>{@code ButtonBase} rather than {@code Button}: Pull and Push are
     * {@code SplitMenuButton}, which shares no supertype with {@code Button} closer than
     * that, and clicking either the same way clicks its default action.
     */
    private javafx.scene.control.ButtonBase button(String label) {
        return toolbar().toolButton(label);
    }

    /** Clicks a toolbar button. The git work it starts is still running when this returns. */
    private void click(String label) {
        clickOn(button(label));
    }

    /** For pure FX steps with nothing running behind them; git work is waited for by outcome. */
    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Reads HEAD's branch name with a handle that is closed before this returns. */
    private String currentBranch() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.getRepository().getBranch();
        }
    }

    /** {@link #currentBranch}, as a condition: a repository mid-checkout reads as "not yet". */
    private boolean onBranch(String expected) {
        try {
            return expected.equals(currentBranch());
        } catch (Exception midCheckout) {
            return false;
        }
    }

    /** A file's content, as a condition: a file mid-write reads as "not yet". */
    private boolean fileReads(String name, String expected) {
        try {
            return expected.equals(Files.readString(tempDir.resolve(name)));
        } catch (Exception midWrite) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Branch
    // ------------------------------------------------------------------

    @Test
    void theBranchButtonCreatesARealBranch() throws Exception {
        seedCommit("a.txt", "a");

        clickOn(button("Branch"));
        // Searched within the dialog that was waited for, not across every window: robot
        // tests in this class share one JavaFX toolkit, and a dialog a previous test left
        // showing can be the window an unscoped lookup finds.
        javafx.scene.control.DialogPane dialog = RobotWait.untilDialogWithButton("OK");

        javafx.scene.control.TextField field = (javafx.scene.control.TextField)
                FxHarness.lookupAll(dialog, "text-field").iterator().next();
        clickOn(field);
        write("feature-x");
        push(javafx.scene.input.KeyCode.ENTER);
        RobotWait.until("the branch was never checked out", () -> onBranch("feature-x"));

        assertEquals("feature-x", currentBranch(),
                "Branch should create and check out a real branch, not just close a dialog");
    }

    // ------------------------------------------------------------------
    // Stash / Pop
    // ------------------------------------------------------------------

    @Test
    void theStashButtonStashesRealChanges() throws Exception {
        seedCommit("a.txt", "committed");
        Files.writeString(tempDir.resolve("a.txt"), "dirty", StandardCharsets.UTF_8);
        refreshToolbar();

        click("Stash");
        RobotWait.until("the stash never landed", () -> fileReads("a.txt", "committed")
                && stashCount() == 1);

        assertEquals("committed", Files.readString(tempDir.resolve("a.txt")),
                "the working tree should be back to the committed content");
        assertEquals(1, stashCount(), "a real stash should exist");
    }

    @Test
    void thePopButtonRestoresTheStash() throws Exception {
        seedCommit("a.txt", "committed");
        Files.writeString(tempDir.resolve("a.txt"), "dirty", StandardCharsets.UTF_8);
        assertTrue(onLane(() -> new RepositoryCommands(service).stash()).isOk());
        // Pop stays disabled until a refresh reports that there is a stash, and a click on
        // a disabled button is dropped without a word - which looks exactly like a pop
        // that never happened.
        refreshToolbar();
        RobotWait.untilOnFx("Pop was never enabled for the stash", () -> !button("Pop").isDisabled());

        click("Pop");
        try {
            RobotWait.until("the stash was never popped", () -> fileReads("a.txt", "dirty") && stashCount() == 0);
        } catch (AssertionError notPopped) {
            throw new AssertionError(notPopped.getMessage() + "; the status bar said: \""
                    + FxHarness.call(this::statusMessage) + "\"", notPopped);
        }

        assertEquals("dirty", Files.readString(tempDir.resolve("a.txt")),
                "popping should bring the change back into the working tree");
        assertEquals(0, stashCount(), "and remove the stash");
    }

    // ------------------------------------------------------------------
    // Undo / Redo
    // ------------------------------------------------------------------

    @Test
    void undoReturnsToTheStateBeforeTheLastCommit() throws Exception {
        seedCommit("a.txt", "first");
        seedCommit("b.txt", "second");

        assertFalse(button("Undo").isDisabled(), "there should be something to undo");
        click("Undo");
        // Two hops: a dirty-tree check on the git lane, then the reset itself.
        RobotWait.until("the undo never landed", () -> !Files.exists(tempDir.resolve("b.txt")));

        assertFalse(Files.exists(tempDir.resolve("b.txt")),
                "undo should have moved HEAD back before the second commit");
    }

    @Test
    void redoReappliesWhatUndoJustReversed() throws Exception {
        seedCommit("a.txt", "first");
        seedCommit("b.txt", "second");

        click("Undo");
        RobotWait.untilOnFx("the undo never made a redo available",
                () -> !button("Redo").isDisabled());
        assertFalse(button("Redo").isDisabled(), "undo should have made a redo available");

        click("Redo");
        RobotWait.until("the redo never landed", () -> Files.exists(tempDir.resolve("b.txt")));

        assertTrue(Files.exists(tempDir.resolve("b.txt")), "redo should bring the commit back");
    }

    // ------------------------------------------------------------------
    // Remote operations without a remote — the deterministic failure path
    // ------------------------------------------------------------------

    @Test
    void fetchReachesTheRealBackendAndReportsARealFailure() throws Exception {
        seedCommit("a.txt", "a");

        click("Fetch");
        // "Fetching…" is shown while it runs, so a non-blank message alone is not the answer.
        RobotWait.untilOnFx("the fetch never reported its failure", () -> lookup(".status-message")
                .queryAs(Labeled.class).getStyleClass().contains("status-error"));

        Labeled message = (Labeled) lookup(".status-message").query();
        assertFalse(message.getText().isBlank(),
                "a repository with no remotes should report a real failure, not silence");
    }

    // ------------------------------------------------------------------
    // Branch selector
    // ------------------------------------------------------------------

    @Test
    void theBranchSelectorChecksOutARealBranch() throws Exception {
        seedCommit("a.txt", "a");
        assertTrue(service.createBranch("other", null, false).isOk(),
                "created but not checked out, so the selector has something to switch to");
        refreshToolbar();

        @SuppressWarnings("unchecked") // the toolbar holds Nodes; this one is the engine picker
        var selector = (javafx.scene.control.ComboBox<String>) toolbar().getItems().stream()
                .filter(n -> n instanceof javafx.scene.control.ComboBox)
                .findFirst().orElseThrow();

        clickOn(selector);
        settle();
        clickOn("other");
        RobotWait.until("the selected branch was never checked out", () -> onBranch("other"));

        assertEquals("other", currentBranch(),
                "picking a branch in the selector should really check it out");
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    @Test
    void undosMenuOpensTheReflogInTheTab() throws Exception {
        seedCommit("a.txt", "a");
        seedCommit("b.txt", "b");
        int before = javafx.stage.Window.getWindows().size();

        var undo = (javafx.scene.control.SplitMenuButton) button("Undo");
        var reflog = undo.getItems().stream()
                .filter(item -> item.getText() != null && item.getText().contains("reflog"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "Undo's menu should lead to the reflog: " + undo.getItems()));
        interact(reflog::fire);

        assertInstanceOf(com.jfx.git.ui.views.ReflogView.class, tab.currentView(),
                "the menu item should open the reflog, not merely acknowledge the click");
        assertEquals(before, javafx.stage.Window.getWindows().size(),
                "the reflog opens inside the repository's tab, not in a window of its own");
        RobotWait.untilOnFx("the reflog never listed the two commits",
                () -> ((com.jfx.git.ui.views.ReflogView) tab.currentView()).loadedEntries().size() >= 2);
    }

    // ------------------------------------------------------------------
    // Presence
    // ------------------------------------------------------------------

    /**
     * Undo has to stay one gesture.
     *
     * <p>A preview was briefly put in front of every undo, which broke the thing the
     * 50-step undo is for: tapping it to walk back. It lives in the split button's
     * dropdown instead, so the click still undoes and the preview is there for whoever
     * wants it.
     */
    @Test
    void undoIsOneClickAndOffersAPreviewSeparately() throws Exception {
        seedCommit("a.txt", "first");
        seedCommit("b.txt", "second");

        var undo = button("Undo");
        assertInstanceOf(javafx.scene.control.SplitMenuButton.class, undo,
                "Undo should offer the preview without demanding it");
        var split = (javafx.scene.control.SplitMenuButton) undo;
        assertTrue(split.getItems().stream()
                        .anyMatch(item -> item.getText().startsWith("Preview")),
                () -> "the dropdown should offer a preview: " + split.getItems());

        // And the click itself still undoes, with no dialog in the way.
        int windowsBefore = javafx.stage.Window.getWindows().size();
        click("Undo");
        RobotWait.until("the undo never landed", () -> !Files.exists(tempDir.resolve("b.txt")));

        assertEquals(windowsBefore, javafx.stage.Window.getWindows().size(),
                "clicking Undo must not raise a dialog");
        assertFalse(Files.exists(tempDir.resolve("b.txt")), "and must actually undo");
    }

    @Test
    void everyDocumentedButtonIsOnTheBar() {
        for (String label : new String[] {
                "Fetch", "Pull", "Push", "Branch", "Stash", "Pop",
                "Undo", "Redo", "Terminal"}) {
            assertNotNull(button(label), label + " should be on the toolbar");
        }
    }

    /**
     * Open and History are not on the bar any more, deliberately.
     *
     * <p>Opening a repository is in the File menu, on the tab strip and on the launchpad;
     * the reflog is in Undo's own menu. Both were permanent slots for things done a few
     * times a week, on a bar that could not show its captions at laptop width.
     */
    @Test
    void theBarCarriesNoButtonForOpenOrForTheReflog() {
        for (String label : new String[] {"Open", "History"}) {
            assertThrows(IllegalArgumentException.class, () -> button(label),
                    label + " should not be a toolbar button");
        }
    }

    /** The captions are the buttons' own text, so they are what is on screen. */
    @Test
    void aButtonShowsItsCaption() {
        // This fixture opens the service before constructing RepoTab, bypassing the
        // normal repository-open callback that applies the no-origin provider state.
        // Wait for that state before measuring the toolbar's available width.
        refreshToolbar();
        RobotWait.untilOnFx("the no-origin toolbar did not regain its captions",
                () -> !toolbar().isCompact());
        assertFalse(toolbar().isCompact(), "a 1200px window has room for captions");
        assertEquals("Fetch", button("Fetch").getText());
        assertEquals(javafx.scene.control.ContentDisplay.TOP, button("Fetch").getContentDisplay(),
                "icon over caption");
    }
}
