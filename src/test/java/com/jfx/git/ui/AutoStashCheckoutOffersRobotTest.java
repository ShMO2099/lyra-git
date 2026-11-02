package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphCell;
import com.jfx.git.ui.views.PullRequestCheckout;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every checkout offers auto-stash when local changes are in the way - including the three
 * that used to answer with git's refusal alone: the graph's "Checkout commit", the
 * toolbar's Undo of a checkout, and checking out a pull request.
 *
 * <p>The fixture: {@code main} with {@code a.txt}; {@code other} one commit ahead adding a
 * line to it, checked out and left again, so Undo's next move is the checkout back to
 * {@code other}; and an {@code origin} whose pull request #7 is {@code other}'s commit. An
 * edit to {@code a.txt} on {@code main} is in the way of each. Answering the question
 * carries the edit across; cancelling it leaves everything as it was.
 */
class AutoStashCheckoutOffersRobotTest extends ApplicationTest {

    private static final String BASE = "one\ntwo\nthree\n";
    private static final String OTHER = BASE + "four\n";
    private static final String EDITED = "zero\n" + BASE;
    private static final String EDITED_ON_OTHER = "zero\n" + OTHER;
    private static final String OTHER_SUBJECT = "on other";

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;
    private boolean alwaysBefore;

    @Override
    public void start(Stage stage) throws Exception {
        work = root.resolve("work");
        Path origin = root.resolve("origin.git");
        try (var fixture = ThreadGuard.buildingFixture()) {
            Git.init().setDirectory(origin.toFile()).setBare(true).setInitialBranch("main").call().close();
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA");
                config.setString("user", null, "email", "qa@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                config.setString("remote", "origin", "url", origin.toUri().toString());
                config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
                config.save();
                write("a.txt", BASE);
                git.add().addFilepattern("a.txt").call();
                git.commit().setMessage("first").call();
                git.checkout().setCreateBranch(true).setName("other").call();
                write("a.txt", OTHER);
                git.add().addFilepattern("a.txt").call();
                git.commit().setMessage(OTHER_SUBJECT).call();
                git.checkout().setName("main").call();
                git.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"),
                        new RefSpec("refs/heads/other:refs/pull/7/head")).call();
            }
            GitService service = new GitService();
            assertTrue(service.openRepository(work.toFile()));
            backend = service;
        }
        alwaysBefore = AppPreferences.autoStashOnCheckoutPull();
        AppPreferences.setAutoStashOnCheckoutPull(false);
        tab = new RepoTab(new LyraApp(), "auto-stash offers", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheTab() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        AppPreferences.setAutoStashOnCheckoutPull(alwaysBefore);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // The graph's "Checkout commit"
    // ------------------------------------------------------------------

    @Test
    void theGraphsCommitCheckoutOffersAutoStashAndCarriesTheEditAcross() throws Exception {
        String commit = resolve("refs/heads/other");
        write("a.txt", EDITED);

        checkoutCommitFromGraph();
        press(RobotWait.untilDialogWithButton("Stash and check out", "Cancel"), "Stash and check out");
        RobotWait.until("the commit was never checked out", () -> commit.equals(head()));
        RobotWait.drainGitWork();

        assertEquals(commit, head(), "HEAD is detached on the commit");
        assertEquals(EDITED_ON_OTHER, read("a.txt"), "the edit is on top of the commit's version");
        assertEquals(0, stashCount(), "the stash it made is gone");
    }

    @Test
    void cancellingTheGraphsOfferLeavesEverythingAsItWas() throws Exception {
        write("a.txt", EDITED);

        checkoutCommitFromGraph();
        press(RobotWait.untilDialogWithButton("Stash and check out", "Cancel"), ButtonType.CANCEL.getText());
        RobotWait.drainGitWork();

        assertEquals("ref: refs/heads/main", head(), "nothing was checked out");
        assertEquals(EDITED, read("a.txt"));
        assertEquals(0, stashCount(), "nothing was stashed");
    }

    // ------------------------------------------------------------------
    // A branch made somewhere else and checked out
    // ------------------------------------------------------------------

    /**
     * "Create branch here…" checks the new branch out, so it is a checkout like any other:
     * an edit in the way gets the offer, not git's refusal alone.
     */
    @Test
    void theGraphsCreateBranchHereOffersAutoStash() throws Exception {
        String commit = resolve("refs/heads/other");
        write("a.txt", EDITED);

        MenuItem branchHere = commitMenuItem("Create branch here…");
        Platform.runLater(branchHere::fire);
        nameTheBranch("topic");
        press(RobotWait.untilDialogWithButton("Stash and check out", "Cancel"), "Stash and check out");
        RobotWait.until("topic was never checked out", () -> "ref: refs/heads/topic".equals(head()));
        RobotWait.drainGitWork();

        assertEquals(commit, resolve("refs/heads/topic"), "topic starts at the commit");
        assertEquals(EDITED_ON_OTHER, read("a.txt"), "the edit came along");
        assertEquals(0, stashCount());
    }

    /** The sidebar's "New branch from here…" on another branch is the same checkout. */
    @Test
    void theSidebarsNewBranchFromHereOffersAutoStash() throws Exception {
        String commit = resolve("refs/heads/other");
        write("a.txt", EDITED);

        MenuItem newFrom = sidebarMenuItem("other", "New branch from here…");
        Platform.runLater(newFrom::fire);
        nameTheBranch("topic");
        press(RobotWait.untilDialogWithButton("Stash and check out", "Cancel"), "Stash and check out");
        RobotWait.until("topic was never checked out", () -> "ref: refs/heads/topic".equals(head()));
        RobotWait.drainGitWork();

        assertEquals(commit, resolve("refs/heads/topic"), "topic starts at other's tip");
        assertEquals(EDITED_ON_OTHER, read("a.txt"), "the edit came along");
        assertEquals(0, stashCount());
    }

    // ------------------------------------------------------------------
    // Undo of a checkout
    // ------------------------------------------------------------------

    @Test
    void undoingACheckoutOffersAutoStashAndCanBeRedoneAfterwards() throws Exception {
        write("a.txt", EDITED);
        RobotWait.untilOnFx("there was nothing to undo", () -> !tab.getToolbar().toolButton("Undo").isDisabled());

        clickOn(tab.getToolbar().toolButton("Undo"));
        press(RobotWait.untilDialogWithButton("Stash and check out", "Cancel"), "Stash and check out");
        RobotWait.until("Undo never went back to other", () -> "ref: refs/heads/other".equals(head()));
        RobotWait.drainGitWork();

        assertEquals(EDITED_ON_OTHER, read("a.txt"), "the edit came along");
        assertEquals(0, stashCount());
        RobotWait.untilOnFx("the undo was not recorded, so there is nothing to redo",
                () -> !tab.getToolbar().toolButton("Redo").isDisabled());
    }

    // ------------------------------------------------------------------
    // A pull request
    // ------------------------------------------------------------------

    @Test
    void checkingOutAPullRequestOffersAutoStash() throws Exception {
        write("a.txt", EDITED);
        CompletableFuture<GitResult> done = new CompletableFuture<>();

        Platform.runLater(() -> PullRequestCheckout.start(OperationRunner.of(backend, tab.getStatusBar(), () -> { }),
                "pr-7", "pull request #7",
                reset -> backend.checkoutPullRequest("origin", 7, reset), done::complete));
        press(RobotWait.untilDialogWithButton("Stash and check out", "Cancel"), "Stash and check out");
        GitResult result = done.get(60, TimeUnit.SECONDS);
        RobotWait.drainGitWork();

        assertTrue(result.isOk(), result::getMessage);
        assertEquals("ref: refs/heads/pr-7", head());
        assertEquals(resolve("refs/heads/other"), resolve("refs/heads/pr-7"), "pr-7 is the pull request's head");
        assertEquals(EDITED_ON_OTHER, read("a.txt"), "the edit came along");
        assertEquals(0, stashCount());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Opens the menu of the row showing {@code other}'s commit and fires "Checkout commit
     * (detached HEAD)", then confirms the checkout it asks about.
     */
    private void checkoutCommitFromGraph() {
        MenuItem checkout = commitMenuItem("Checkout commit (detached HEAD)");
        // runLater, not interact: the item asks first, and its dialog's nested event loop
        // would hold an interact open.
        Platform.runLater(checkout::fire);
        // The question keeps the OK button type and names the button after the action.
        press(RobotWait.untilDialogWithButton(ButtonType.OK.getText()), "Check out commit");
    }

    /** Types {@code name} into the new-branch prompt and presses "Create and check out". */
    private void nameTheBranch(String name) {
        DialogPane prompt = RobotWait.untilDialogWithButton(ButtonType.OK.getText());
        interact(() -> {
            javafx.scene.control.TextInputControl field =
                    (javafx.scene.control.TextInputControl) prompt.lookup(".text-field");
            assertNotNull(field, "the prompt has a name field");
            field.setText(name);
        });
        press(prompt, "Create and check out");
    }

    /**
     * The item reading {@code text} in the menu of the sidebar's LOCAL row showing
     * {@code branch}; the menu is opened and hidden again, so firing it is the caller's.
     */
    private MenuItem sidebarMenuItem(String branch, String text) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no \"" + branch + "\" row appeared in LOCAL", () -> {
            Node tree = FxHarness.lookupAll(tab.getContent(), "branch-tree").getFirst();
            row[0] = from(tree).lookup(branch).query();
            return row[0] != null;
        });
        interact(() -> Event.fireEvent(row[0], new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        ContextMenu menu = FxHarness.call(() -> {
            Node node = row[0];
            while (!(node instanceof javafx.scene.control.Control control && control.getContextMenu() != null)) {
                node = node.getParent();
            }
            return ((javafx.scene.control.Control) node).getContextMenu();
        });
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        MenuItem item = FxHarness.call(() -> menu.getItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " in " + branch + "'s menu")));
        interact(menu::hide);
        return item;
    }

    /**
     * The item reading {@code text} in the menu of the row showing {@code other}'s
     * commit; the menu is opened and hidden again, so firing it is the caller's.
     */
    private MenuItem commitMenuItem(String text) {
        RobotWait.untilOnFx("the graph never showed " + OTHER_SUBJECT, () -> cellShowing(OTHER_SUBJECT) != null);
        GitGraphCell cell = FxHarness.call(() -> cellShowing(OTHER_SUBJECT));
        assertNotNull(cell);
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(cell.getItem());
            Event.fireEvent(cell, new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null));
        });
        ContextMenu menu = FxHarness.call(cell::getContextMenu);
        RobotWait.untilOnFx("the commit menu never opened", menu::isShowing);
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        MenuItem item = FxHarness.call(() -> menu.getItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " in the commit menu")));
        interact(menu::hide);
        return item;
    }

    /** The graph cell currently showing the commit with {@code subject}, or null. Call on FX. */
    private GitGraphCell cellShowing(String subject) {
        for (Node node : tab.graphList().lookupAll(".list-cell")) {
            if (node instanceof GitGraphCell cell && cell.getItem() != null && !cell.getItem().isSynthetic()
                    && cell.getItem().getCommit() != null
                    && subject.equals(cell.getItem().getCommit().getSubject())) {
                return cell;
            }
        }
        return null;
    }

    /** Presses the button that reads {@code button}. */
    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(((Button) pane.lookupButton(bt)).getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no \"" + button + "\" button; has "
                            + pane.getButtonTypes().stream()
                                    .map(bt -> ((Button) pane.lookupButton(bt)).getText()).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    /** What {@code .git/HEAD} says: {@code ref: refs/heads/x}, or a commit when detached. */
    private String head() {
        try {
            return Files.readString(work.resolve(".git/HEAD"), StandardCharsets.UTF_8).trim();
        } catch (Exception midWrite) {
            return "";
        }
    }

    private String resolve(String revision) {
        try (Git git = Git.open(work.toFile())) {
            var id = git.getRepository().resolve(revision);
            return id == null ? null : id.name();
        } catch (Exception e) {
            throw new AssertionError("could not resolve " + revision, e);
        }
    }

    private int stashCount() {
        try (Git git = Git.open(work.toFile())) {
            return git.stashList().call().size();
        } catch (Exception e) {
            throw new AssertionError("could not read the stash list", e);
        }
    }

    private String read(String file) throws Exception {
        return Files.readString(work.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private void write(String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    }
}
