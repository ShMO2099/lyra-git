package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R17 in {@code manual-test-artifacts/qa-scenario-improvements.md}: a remote-tracking
 * branch's own "Merge into current" and "Checkout as local branch", started from its row in
 * the sidebar's REMOTE section and judged by the repository afterwards - commits and their
 * parents, refs, upstream configuration, the index, file bytes and the stash - read through
 * JGit directly rather than through the engine that did the work.
 *
 * <p>The fixture is a local bare {@code origin} and a clone of it whose {@code main} is one
 * local commit ahead. {@code origin} has three branches, all fetched:
 * <ul>
 *   <li>{@code feature/remote-only} adds {@code remote.txt} and touches nothing else;</li>
 *   <li>{@code feature/remote-edit} adds {@code remote.txt} too, and edits line 1 of
 *       {@code shared.txt};</li>
 *   <li>{@code feature/reviewed} adds {@code review.txt} and edits line 1 of
 *       {@code shared.txt}.</li>
 * </ul>
 *
 * <p>A merge that local changes are in the way of is offered stash and merge, as checkout
 * and pull are offered stash and retry ({@link com.jfx.git.core.AutoStash}): asked from "Merge into
 * current", from "… with options…" under "Always do this", and from the drop menu, then
 * judged by the merge commit, the bytes in the work tree and the stash list - or, for the
 * merge that conflicts, by what the stash holds and what the app says about it.
 *
 * <p>JGit is the engine here. What each engine refuses, and how the stash and retry comes
 * out, is {@code RefusalConformanceTest}'s, {@code AutoStashTest}'s and
 * {@code BackendConformanceTest}'s on every engine: the screen reads the typed refusal, not
 * git's words, so these journeys need running on one engine only. Menu items are
 * fired through the row's own {@link ContextMenu}, as in {@code
 * SidebarRemoteManagementRobotTest}: what each item does is under test, not where the
 * headless window system puts a popup.
 */
class IncompleteQaRemoteBranchMenuRobotTest extends ApplicationTest {

    private static final String BASE = "line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String REMOTE_EDIT = "remote line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String REVIEWED = "reviewed line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String LOCAL_EDIT = "line 1\nline 2\nline 3\nline 4\nlocal line 5\n";
    /** The reviewed branch's line 1 with the local line 5 re-applied on top of it. */
    private static final String REVIEWED_WITH_LOCAL_EDIT =
            "reviewed line 1\nline 2\nline 3\nline 4\nlocal line 5\n";
    /** The remote edit's line 1 with the local line 5 re-applied on top of it. */
    private static final String REMOTE_EDIT_WITH_LOCAL_EDIT =
            "remote line 1\nline 2\nline 3\nline 4\nlocal line 5\n";
    private static final String REMOTE_FILE = "added on the remote\n";
    private static final String REVIEW_FILE = "review notes\n";
    private static final String LOCAL_FILE = "committed locally only\n";
    private static final String UNTRACKED_NOTES = "my own notes, never added\n";
    private static final String SCRATCH = "untracked scratch\n";

    @TempDir
    Path root;

    private Path work;
    private Path origin;
    private GitBackend backend;
    private RepoTab tab;
    private boolean originalAutoStash;

    /** The engine under test, opened on {@code workTree}; the CLI subclass swaps it. */
    protected GitBackend openEngine(Path workTree) throws Exception {
        GitService service = new GitService();
        assertTrue(service.openRepository(workTree.toFile()));
        return service;
    }

    @Override
    public void start(Stage stage) throws Exception {
        origin = root.resolve("origin.git");
        work = root.resolve("work");
        Path seed = root.resolve("seed");
        try (var fixture = ThreadGuard.buildingFixture()) {
            Git.init().setBare(true).setDirectory(origin.toFile()).setInitialBranch("main").call().close();
            try (Git git = Git.init().setDirectory(seed.toFile()).setInitialBranch("main").call()) {
                configure(git);
                write(seed, "shared.txt", BASE);
                commitAll(git, "base");
                git.remoteAdd().setName("origin").setUri(new URIish(origin.toUri().toString())).call();
                git.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
                pushBranch(git, seed, "feature/remote-only", Map.of("remote.txt", REMOTE_FILE));
                pushBranch(git, seed, "feature/remote-edit",
                        Map.of("remote.txt", REMOTE_FILE, "shared.txt", REMOTE_EDIT));
                pushBranch(git, seed, "feature/reviewed",
                        Map.of("review.txt", REVIEW_FILE, "shared.txt", REVIEWED));
            }
            // Not Git.cloneRepository(): the checkout would happen before this repository's
            // own core.autocrlf could be set, and a global autocrlf would change the bytes.
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                configure(git);
                git.remoteAdd().setName("origin").setUri(new URIish(origin.toUri().toString())).call();
                git.fetch().setRemote("origin").call();
                git.checkout().setCreateBranch(true).setName("main").setStartPoint("origin/main")
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call();
                write(work, "local.txt", LOCAL_FILE);
                commitAll(git, "local only");
            }
            backend = openEngine(work);
        }
        originalAutoStash = AppPreferences.autoStashOnCheckoutPull();
        AppPreferences.setAutoStashOnCheckoutPull(false);

        tab = new RepoTab(new LyraApp(), "remote branch menu", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheSidebar() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the REMOTE tree was never populated",
                () -> remoteTree().getRoot() != null && !remoteTree().getRoot().getChildren().isEmpty());
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        AppPreferences.setAutoStashOnCheckoutPull(originalAutoStash);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Merge into current
    // ------------------------------------------------------------------

    @Test
    void mergingARemoteBranchMakesAMergeCommitOnTheCurrentBranchAndMovesNoRemoteRef() throws Exception {
        String localTip = resolve("refs/heads/main");
        String remoteTip = resolve("refs/remotes/origin/feature/remote-only");
        Map<String, String> upstream = upstreamOf("main");
        Map<String, String> remoteTracking = refs(work, Constants.R_REMOTES);
        Map<String, String> originRefs = refs(origin, "");

        fire("origin/feature/remote-only", "Merge into current");
        RobotWait.until("the merge never moved main", () -> !localTip.equals(resolve("refs/heads/main")));
        RobotWait.drainGitWork();

        assertEquals("main", currentBranch(), "the merge must leave the current branch checked out");
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit merge = walk.parseCommit(git.getRepository().resolve("refs/heads/main"));
            assertEquals(2, merge.getParentCount(), "a diverged remote branch needs a merge commit");
            assertEquals(localTip, merge.getParent(0).name(), "the first parent is the old local tip");
            assertEquals(remoteTip, merge.getParent(1).name(), "the second parent is the fetched remote tip");
            assertTrue(merge.getShortMessage().startsWith(
                            "Merge remote-tracking branch 'origin/feature/remote-only'"),
                    "git's own default message for a remote-tracking branch: " + merge.getShortMessage());
        }
        assertEquals(LOCAL_FILE, read("local.txt"));
        assertEquals(REMOTE_FILE, read("remote.txt"));
        assertEquals(BASE, read("shared.txt"));
        assertTrue(status().isClean(), "the merge leaves nothing uncommitted: " + status().getUncommittedChanges());
        assertEquals(upstream, upstreamOf("main"), "the current branch's upstream is untouched");
        assertEquals(remoteTracking, refs(work, Constants.R_REMOTES), "merging moves no remote-tracking ref");
        assertEquals(originRefs, refs(origin, ""), "merging locally pushes nothing");
    }

    // ------------------------------------------------------------------
    // Merge into current, with local changes in the way: stash and merge
    // ------------------------------------------------------------------

    /**
     * An edit to a file the merge changes and an untracked file where it adds one: git
     * refuses, and the refusal comes back as the stash-and-merge question. Cancelling it
     * leaves git's refusal on the status bar and everything as it was.
     */
    @Test
    void aMergeThatWouldOverwriteLocalChangesAsksAndCancellingChangesNothing() throws Exception {
        write(work, "shared.txt", LOCAL_EDIT);
        write(work, "remote.txt", UNTRACKED_NOTES);
        Snapshot before = snapshot();

        fire("origin/feature/remote-edit", "Merge into current");
        DialogPane question = RobotWait.untilDialogWithButton("Stash and merge");
        assertEquals("Stash your changes and merge?", FxHarness.call(question::getHeaderText));
        assertTrue(questionText(question).startsWith("They are stashed, the merge runs, and they are put back."),
                "the question says what will happen: " + questionText(question));
        press(question, "Cancel");
        RobotWait.untilOnFx("the refused merge was never reported",
                () -> statusMessage().getStyleClass().contains("status-error")
                        && statusMessage().getText().contains("would be overwritten"));
        RobotWait.drainGitWork();

        assertEquals(before, snapshot(),
                "cancelling must leave the current branch, refs, index, stash and files as they were");
        assertEquals(LOCAL_EDIT, read("shared.txt"));
        assertEquals(UNTRACKED_NOTES, read("remote.txt"));
    }

    /**
     * Agreeing stashes both changes - the untracked file too, since the refusal names it -
     * merges, and re-applies them: the merge commit holds exactly the two branches, and the
     * work tree holds the user's bytes again, the untracked file's over the merged one.
     */
    @Test
    void stashingAndMergingMakesTheMergeCommitAndReappliesTheLocalChangesByteForByte() throws Exception {
        String localTip = resolve("refs/heads/main");
        String remoteTip = resolve("refs/remotes/origin/feature/remote-edit");
        Map<String, String> upstream = upstreamOf("main");
        Map<String, String> remoteTracking = refs(work, Constants.R_REMOTES);
        Map<String, String> originRefs = refs(origin, "");
        write(work, "shared.txt", LOCAL_EDIT);
        write(work, "remote.txt", UNTRACKED_NOTES);

        fire("origin/feature/remote-edit", "Merge into current");
        press(RobotWait.untilDialogWithButton("Stash and merge"), "Stash and merge");
        RobotWait.until("the merge never moved main", () -> !localTip.equals(resolve("refs/heads/main")));
        RobotWait.untilOnFx("the merge was never reported as re-applying the changes",
                () -> statusMessage().getStyleClass().contains("status-info")
                        && statusMessage().getText().contains("re-applied"));
        RobotWait.drainGitWork();

        assertMergedRemoteEditWithLocalChangesBack(localTip, remoteTip);
        assertTrue(lastCommitMessage().startsWith("Merge remote-tracking branch 'origin/feature/remote-edit'"),
                "git's own default message: " + lastCommitMessage());
        assertFalse(AppPreferences.autoStashOnCheckoutPull(), "approving once must not turn on \"Always\"");
        assertEquals(upstream, upstreamOf("main"), "the current branch's upstream is untouched");
        assertEquals(remoteTracking, refs(work, Constants.R_REMOTES), "merging moves no remote-tracking ref");
        assertEquals(originRefs, refs(origin, ""), "merging locally pushes nothing");
    }

    /**
     * "Always do this" covers merges as it covers checkout and pull: the options dialog is
     * still asked - it is the merge itself - but the stash question is not, and the message
     * typed in the dialog is the one the merge commit gets.
     */
    @Test
    void withAlwaysOnAMergeWithOptionsStashesAndReappliesWithoutAsking() throws Exception {
        AppPreferences.setAutoStashOnCheckoutPull(true);
        String localTip = resolve("refs/heads/main");
        String remoteTip = resolve("refs/remotes/origin/feature/remote-edit");
        write(work, "shared.txt", LOCAL_EDIT);
        write(work, "remote.txt", UNTRACKED_NOTES);

        fireLater("origin/feature/remote-edit", "Merge into main with options…");
        DialogPane options = RobotWait.untilDialogWithButton("Merge");
        interact(() -> ((javafx.scene.control.TextInputControl) options.lookup(".merge-message"))
                .setText("Bring in the remote edit"));
        press(options, "Merge");
        // With the question asked, the merge would wait on it and main would never move.
        RobotWait.until("the merge never moved main", () -> !localTip.equals(resolve("refs/heads/main")));
        RobotWait.drainGitWork();

        assertTrue(FxHarness.call(() -> Window.getWindows().stream().noneMatch(w -> w.isShowing()
                        && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane)),
                "no question was left open");
        assertMergedRemoteEditWithLocalChangesBack(localTip, remoteTip);
        assertEquals("Bring in the remote edit", lastCommitMessage());
    }

    /**
     * The drop menu a sidebar row and a graph chip share: dropping the remote branch on
     * {@code main} offers to merge it there, and local changes in the way are the same
     * stash-and-merge question, not the checkout one.
     */
    @Test
    void droppingTheRemoteBranchOnMainAsksToStashAndMergeAndDoesSo() throws Exception {
        String localTip = resolve("refs/heads/main");
        String remoteTip = resolve("refs/remotes/origin/feature/remote-edit");
        write(work, "shared.txt", LOCAL_EDIT);
        write(work, "remote.txt", UNTRACKED_NOTES);

        RefDropMenu drops = new RefDropMenu(OperationRunner.of(backend, tab.getStatusBar(), () -> tab.refresh()));
        interact(() -> drops.build(RefDropMenu.Ref.remote("origin/feature/remote-edit"),
                        RefDropMenu.Ref.local("main"), "main").getItems().stream()
                .filter(i -> "Merge origin/feature/remote-edit into main".equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no merge offered for the drop"))
                .fire());
        press(RobotWait.untilDialogWithButton("Stash and merge"), "Stash and merge");
        RobotWait.until("the merge never moved main", () -> !localTip.equals(resolve("refs/heads/main")));
        RobotWait.drainGitWork();

        assertMergedRemoteEditWithLocalChangesBack(localTip, remoteTip);
    }

    /**
     * A merge that conflicts once the changes are stashed stops there, with the changes
     * still in the stash - popping them onto a half-finished merge would mix the two - and
     * both the status bar and the conflict banner say that is where they are.
     */
    @Test
    void aMergeThatConflictsAfterTheStashSaysTheLocalChangesAreInTheStash() throws Exception {
        try (var fixture = ThreadGuard.buildingFixture(); Git git = Git.open(work.toFile())) {
            // main takes the reviewed line 1 first, so the remote edit's line 1 conflicts.
            git.merge().include(git.getRepository().resolve("refs/remotes/origin/feature/reviewed"))
                    .setMessage("take the review").call();
        }
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        String localTip = resolve("refs/heads/main");
        String remoteTip = resolve("refs/remotes/origin/feature/remote-edit");
        write(work, "shared.txt", REVIEWED_WITH_LOCAL_EDIT);

        fire("origin/feature/remote-edit", "Merge into current");
        press(RobotWait.untilDialogWithButton("Stash and merge"), "Stash and merge");
        RobotWait.until("the merge never stopped on its conflict",
                () -> Files.exists(work.resolve(".git/MERGE_HEAD")));
        RobotWait.untilOnFx("the status bar never said where the changes are",
                () -> statusMessage().getStyleClass().contains("status-warn")
                        && statusMessage().getText().contains("kept in the stash"));
        RobotWait.untilOnFx("the conflict banner never said where the changes are",
                () -> operationBannerText().contains("changes from before it are in the stash (stash@{0})"));
        RobotWait.drainGitWork();

        assertEquals(localTip, resolve("refs/heads/main"), "a conflicted merge has not committed");
        assertEquals(remoteTip, Files.readString(work.resolve(".git/MERGE_HEAD")).strip());
        List<String> stashes = stashes();
        assertEquals(1, stashes.size(), "the stash waits for the merge: " + stashes);
        assertEquals(REVIEWED_WITH_LOCAL_EDIT, stashedFile("shared.txt"),
                "the local edit is in the stash byte for byte");
    }

    /**
     * What every accepted stash-and-merge of {@code feature/remote-edit} leaves: one merge
     * commit of the two tips whose tree is the plain merge, and the user's changes back in
     * the work tree, unstaged, with no stash left behind.
     */
    private void assertMergedRemoteEditWithLocalChangesBack(String localTip, String remoteTip) throws Exception {
        assertEquals("main", currentBranch(), "the merge must leave the current branch checked out");
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit merge = walk.parseCommit(git.getRepository().resolve("refs/heads/main"));
            assertEquals(2, merge.getParentCount(), "a diverged remote branch needs a merge commit");
            assertEquals(localTip, merge.getParent(0).name(), "the first parent is the old local tip");
            assertEquals(remoteTip, merge.getParent(1).name(), "the second parent is the fetched remote tip");
            assertEquals(REMOTE_EDIT, blob(git, merge, "shared.txt"), "no local edit was committed");
            assertEquals(REMOTE_FILE, blob(git, merge, "remote.txt"), "the merged file is the branch's");
            assertEquals(LOCAL_FILE, blob(git, merge, "local.txt"));
        }
        assertEquals(REMOTE_EDIT_WITH_LOCAL_EDIT, read("shared.txt"),
                "the local edit is re-applied on top of the merged file");
        assertEquals(UNTRACKED_NOTES, read("remote.txt"),
                "the user's own remote.txt is back byte for byte; the merged one is in the commit");
        Status status = status();
        assertEquals(Set.of("shared.txt", "remote.txt"), status.getModified(), "both are back, unstaged");
        assertTrue(status.getChanged().isEmpty() && status.getAdded().isEmpty(),
                "nothing was staged that the user had not staged: " + status.getChanged() + status.getAdded());
        assertTrue(status.getUntracked().isEmpty(), "nothing is left lying around: " + status.getUntracked());
        assertTrue(stashes().isEmpty(), "a clean re-apply leaves no stash behind: " + stashes());
    }

    // ------------------------------------------------------------------
    // Checkout as local branch, with local changes in the way
    // ------------------------------------------------------------------

    @Test
    void checkingOutARemoteBranchOverLocalChangesAsksAndCancellingChangesNothing() throws Exception {
        write(work, "shared.txt", LOCAL_EDIT);
        write(work, "scratch.txt", SCRATCH);
        Snapshot before = snapshot();

        fire("origin/feature/reviewed", "Checkout as local branch");
        DialogPane question = RobotWait.untilDialogWithButton("Stash and check out");
        press(question, "Cancel");
        RobotWait.drainGitWork();

        assertEquals(before, snapshot(),
                "cancelling must leave the current branch, refs, index, stash and files as they were");
        assertEquals(LOCAL_EDIT, read("shared.txt"));
        assertEquals(SCRATCH, read("scratch.txt"));
    }

    @Test
    void stashingAndCheckingOutARemoteBranchTracksItAndReappliesTheLocalChanges() throws Exception {
        String mainTip = resolve("refs/heads/main");
        String reviewedTip = resolve("refs/remotes/origin/feature/reviewed");
        Map<String, String> originRefs = refs(origin, "");
        write(work, "shared.txt", LOCAL_EDIT);
        write(work, "scratch.txt", SCRATCH);

        fire("origin/feature/reviewed", "Checkout as local branch");
        DialogPane question = RobotWait.untilDialogWithButton("Stash and check out");
        press(question, "Stash and check out");
        RobotWait.until("the remote branch was never checked out",
                () -> "feature/reviewed".equals(currentBranch()));
        RobotWait.drainGitWork();

        assertEquals(reviewedTip, resolve("HEAD"), "the new local branch starts at the fetched remote tip");
        assertEquals(Map.of("remote", "origin", "merge", "refs/heads/feature/reviewed"),
                upstreamOf("feature/reviewed"), "the new local branch tracks the remote branch");
        assertEquals(mainTip, resolve("refs/heads/main"), "the branch left behind keeps its commit");
        assertEquals(REVIEWED_WITH_LOCAL_EDIT, read("shared.txt"),
                "the local edit is re-applied on top of the checked-out branch");
        assertEquals(REVIEW_FILE, read("review.txt"));
        assertEquals(SCRATCH, read("scratch.txt"), "the untracked file is kept byte for byte");
        Status status = status();
        assertEquals(Set.of("shared.txt"), status.getModified(), "the edit is back, unstaged");
        assertTrue(status.getChanged().isEmpty() && status.getAdded().isEmpty(),
                "nothing was staged that the user had not staged: " + status.getChanged() + status.getAdded());
        assertEquals(Set.of("scratch.txt"), status.getUntracked());
        assertTrue(stashes().isEmpty(), "a clean re-apply leaves no stash behind: " + stashes());
        assertFalse(AppPreferences.autoStashOnCheckoutPull(), "approving once must not turn on \"Always\"");
        assertEquals(originRefs, refs(origin, ""), "checking out pushes nothing");
    }

    // ------------------------------------------------------------------
    // The sidebar
    // ------------------------------------------------------------------

    private TitledPane remoteSection() {
        return FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .map(n -> (TitledPane) n)
                .filter(pane -> pane.getGraphic() instanceof javafx.scene.Parent graphic
                        && graphic.getChildrenUnmodifiable().stream().anyMatch(c ->
                        c instanceof Label l && "REMOTE".equals(l.getText())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no REMOTE section"));
    }

    @SuppressWarnings("unchecked")
    private TreeView<BranchTree.Node> remoteTree() {
        return (TreeView<BranchTree.Node>) remoteSection().getContent().lookup(".branch-tree");
    }

    private static void expandAll(TreeItem<?> item) {
        for (TreeItem<?> child : item.getChildren()) {
            child.setExpanded(true);
            expandAll(child);
        }
    }

    /** Opens REMOTE and every folder in it, then fires {@code label} on {@code path}'s row. */
    private void fire(String path, String label) {
        interact(() -> {
            remoteSection().setExpanded(true);
            expandAll(remoteTree().getRoot());
        });
        ContextMenu[] menu = new ContextMenu[1];
        RobotWait.untilOnFx("no row for " + path + " in REMOTE", () -> {
            for (Node node : remoteTree().lookupAll(".tree-cell")) {
                @SuppressWarnings("unchecked")
                javafx.scene.control.TreeCell<BranchTree.Node> cell =
                        (javafx.scene.control.TreeCell<BranchTree.Node>) node;
                if (cell.getTreeItem() != null && path.equals(BranchTree.pathOf(cell.getTreeItem()))
                        && cell.getContextMenu() != null) {
                    menu[0] = cell.getContextMenu();
                    return true;
                }
            }
            return false;
        });
        MenuItem item = menu[0].getItems().stream()
                .filter(i -> label.equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + label + "\" in the menu of " + path));
        interact(item::fire);
    }

    /**
     * {@link #fire}, for an item that opens a modal dialog straight away: fired from
     * {@code Platform.runLater}, since {@code interact} would wait on the dialog's nested
     * event loop.
     */
    private void fireLater(String path, String label) {
        interact(() -> {
            remoteSection().setExpanded(true);
            expandAll(remoteTree().getRoot());
        });
        MenuItem[] item = new MenuItem[1];
        RobotWait.untilOnFx("no \"" + label + "\" for " + path + " in REMOTE", () -> {
            for (Node node : remoteTree().lookupAll(".tree-cell")) {
                @SuppressWarnings("unchecked")
                javafx.scene.control.TreeCell<BranchTree.Node> cell =
                        (javafx.scene.control.TreeCell<BranchTree.Node>) node;
                if (cell.getTreeItem() != null && path.equals(BranchTree.pathOf(cell.getTreeItem()))
                        && cell.getContextMenu() != null) {
                    item[0] = cell.getContextMenu().getItems().stream()
                            .filter(i -> label.equals(i.getText())).findFirst().orElse(null);
                    return item[0] != null;
                }
            }
            return false;
        });
        javafx.application.Platform.runLater(item[0]::fire);
    }

    /** The question's own words, from the label in its content. */
    private static String questionText(DialogPane pane) {
        return FxHarness.call(() -> pane.getContent().lookupAll(".label").stream()
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText())
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(""));
    }

    /** The in-progress banner's text, or "" while there is none. */
    private String operationBannerText() {
        return FxHarness.lookupAll(tab.getContent(), "operation-banner").stream()
                .flatMap(banner -> banner.lookupAll(".conflict-banner").stream())
                .map(n -> ((Label) n).getText())
                .findFirst()
                .orElse("");
    }

    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(bt.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no \"" + button + "\" button; the dialog offers "
                            + pane.getButtonTypes().stream().map(ButtonType::getText).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    private Label statusMessage() {
        return (Label) FxHarness.lookupAll(tab.getContent(), "status-message").getFirst();
    }

    // ------------------------------------------------------------------
    // The repository, read independently of the engine under test
    // ------------------------------------------------------------------

    /** The work tree's {@link RepoSnapshot}, and the bare origin's refs with it. */
    private record Snapshot(RepoSnapshot work, Map<String, String> originRefs) {
    }

    private Snapshot snapshot() throws Exception {
        return new Snapshot(RepoSnapshot.of(work), refs(origin, ""));
    }

    private String currentBranch() {
        try (Git git = Git.open(work.toFile())) {
            return git.getRepository().getBranch();
        } catch (Exception e) {
            throw new AssertionError("could not read HEAD", e);
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

    private Map<String, String> upstreamOf(String branch) throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return RepoSnapshot.upstreamOf(git.getRepository(), branch);
        }
    }

    private static Map<String, String> refs(Path repository, String prefix) throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            Map<String, String> refs = new TreeMap<>();
            for (Ref ref : git.getRepository().getRefDatabase().getRefsByPrefix(prefix)) {
                refs.put(ref.getName(), ref.getObjectId() == null ? "" : ref.getObjectId().name());
            }
            return refs;
        }
    }

    private Status status() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return git.status().call();
        }
    }

    private List<String> stashes() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            List<String> stashes = new ArrayList<>();
            git.stashList().call().forEach(c -> stashes.add(c.getShortMessage()));
            return stashes;
        }
    }

    private String read(String file) throws Exception {
        return Files.readString(work.resolve(file), StandardCharsets.UTF_8);
    }

    /** {@code main}'s tip's full message, stripped. */
    private String lastCommitMessage() throws Exception {
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("refs/heads/main")).getFullMessage().strip();
        }
    }

    /** {@code path} as {@code commit} records it. */
    private static String blob(Git git, RevCommit commit, String path) throws Exception {
        try (TreeWalk walk = TreeWalk.forPath(git.getRepository(), path, commit.getTree())) {
            if (walk == null) throw new AssertionError(path + " is not in " + commit.name());
            return new String(git.getRepository().open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        }
    }

    /** {@code path} as the newest stash's work-tree commit records it. */
    private String stashedFile(String path) throws Exception {
        try (Git git = Git.open(work.toFile())) {
            RevCommit stash = git.stashList().call().iterator().next();
            return blob(git, stash, path);
        }
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private static void configure(Git git) throws Exception {
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Synthetic QA");
        config.setString("user", null, "email", "qa@example.test");
        config.setBoolean("core", null, "autocrlf", false);
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
    }

    private static void write(Path dir, String file, String content) throws Exception {
        Files.writeString(dir.resolve(file), content, StandardCharsets.UTF_8);
    }

    private static void commitAll(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).call();
    }

    /** A branch off main with {@code files} written and committed, pushed to origin. */
    private static void pushBranch(Git git, Path dir, String branch, Map<String, String> files) throws Exception {
        git.checkout().setCreateBranch(true).setName(branch).setStartPoint("main").call();
        for (var file : files.entrySet()) {
            write(dir, file.getKey(), file.getValue());
        }
        commitAll(git, branch);
        git.push().setRemote("origin")
                .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch)).call();
        git.checkout().setName("main").call();
    }
}
