package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphCell;
import com.jfx.git.graph.GitGraphRow;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B21 in {@code manual-test-artifacts/qa-scenario-improvements.md}, steps 1-5: "Revert
 * commit" from a row's menu in the graph, on a commit below the tip, and the recovery from a
 * revert that stops on a conflict - the in-progress banner's Abort, cancelled and accepted,
 * and resolving through the staging area's merge tool followed by the banner's Continue.
 * Each is judged by the repository afterwards - commit ids and parents, refs, the index's
 * conflict stages, {@code REVERT_HEAD} and the bytes on disk - read through JGit directly
 * rather than through the engine that did the work.
 *
 * <p>The fixture is four commits on {@code main}, each rewriting {@code story.txt}:
 * <ol>
 *   <li>{@code base} - five plain lines, and {@code keep.txt}, which nothing touches again;</li>
 *   <li>{@code target} - rewrites line 2;</li>
 *   <li>{@code clean} - rewrites line 5 and adds {@code extra.txt};</li>
 *   <li>{@code later} - rewrites line 2 again, differently.</li>
 * </ol>
 * Reverting {@code clean} is a clean inverse patch two commits below the tip. Reverting
 * {@code target} conflicts on line 2, which {@code later} changed since. A tag at
 * {@code base} and a branch at {@code target} are the "other refs" a revert must not move,
 * and an untracked {@code scratch.txt} is the local work it must not sweep up.
 *
 * <p>Reverting a merge commit, and choosing its parent, is step 6 and lives elsewhere.
 *
 * <p>JGit is the engine here; {@link IncompleteQaGraphRevertNativeRobotTest} runs the same
 * journeys on the git CLI, which writes for the default Hybrid engine. Menu items are fired
 * through the row's own {@link ContextMenu}, opened by a context-menu request on the row:
 * what the item does is under test, not where the headless window system puts a popup.
 */
class IncompleteQaGraphRevertRobotTest extends ApplicationTest {

    private static final String BASE = "line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String TARGET = "line 1\ntarget line 2\nline 3\nline 4\nline 5\n";
    private static final String CLEAN = "line 1\ntarget line 2\nline 3\nline 4\nline 5, edited\n";
    private static final String LATER = "line 1\nlater line 2\nline 3\nline 4\nline 5, edited\n";
    /** The tip with only {@code clean}'s edits taken back out. */
    private static final String LATER_WITHOUT_CLEAN = "line 1\nlater line 2\nline 3\nline 4\nline 5\n";
    /** The conflict settled on the revert's side: line 2 as it was before {@code target}. */
    private static final String RESOLVED = "line 1\nline 2\nline 3\nline 4\nline 5, edited\n";
    private static final String KEEP = "never touched again\n";
    private static final String EXTRA = "added alongside line 5's edit\n";
    private static final String SCRATCH = "untracked scratch, never added\n";

    private static final String TARGET_SUBJECT = "target: rewrite line 2";
    private static final String CLEAN_SUBJECT = "clean: rewrite line 5 and add extra.txt";
    private static final String LATER_SUBJECT = "later: rewrite line 2 again";

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;
    private String base;
    private String target;
    private String clean;
    private String later;

    /** The engine under test, opened on {@code workTree}; the CLI subclass swaps it. */
    protected GitBackend openEngine(Path workTree) throws Exception {
        GitService service = new GitService();
        assertTrue(service.openRepository(workTree.toFile()));
        return service;
    }

    @Override
    public void start(Stage stage) throws Exception {
        work = root.resolve("work");
        try (var fixture = ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA");
                config.setString("user", null, "email", "qa@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                // What the conflicted file looks like is asserted, so neither a global
                // diff3/zdiff3 style nor a recorded resolution may change it.
                config.setString("merge", null, "conflictStyle", "merge");
                config.setBoolean("rerere", null, "enabled", false);
                config.save();
                write("keep.txt", KEEP);
                write("story.txt", BASE);
                base = commitAll(git, "base: five lines");
                git.tag().setName("v1").setObjectId(parse(git, base)).setAnnotated(false).call();
                write("story.txt", TARGET);
                target = commitAll(git, TARGET_SUBJECT);
                git.branchCreate().setName("side").setStartPoint(target).call();
                write("story.txt", CLEAN);
                write("extra.txt", EXTRA);
                clean = commitAll(git, CLEAN_SUBJECT);
                write("story.txt", LATER);
                later = commitAll(git, LATER_SUBJECT);
            }
            write("scratch.txt", SCRATCH);
            backend = openEngine(work);
        }
        tab = new RepoTab(new LyraApp(), "graph revert", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheGraph() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the graph never showed the tip", () -> cellShowing(LATER_SUBJECT) != null);
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // A clean revert of an older commit
    // ------------------------------------------------------------------

    @Test
    void revertingAnOlderCommitAddsOneInverseCommitOnTheTipAndMovesNothingElse() throws Exception {
        Map<String, String> otherRefs = refsOtherThanMain();

        revertFromGraph(CLEAN_SUBJECT);
        RobotWait.until("the revert never moved main", () -> !later.equals(resolve("refs/heads/main")));
        RobotWait.drainGitWork();

        assertEquals("refs/heads/main", RepoSnapshot.of(work).branch(), "the branch stays checked out");
        RevCommit revert = commit("refs/heads/main");
        assertEquals(1, revert.getParentCount());
        assertEquals(later, revert.getParent(0).name(), "the revert goes on top of the old tip");
        assertEquals(5, commitCount("refs/heads/main"), "exactly one commit was added");
        assertEquals("Revert \"" + CLEAN_SUBJECT + "\"", revert.getShortMessage());
        assertTrue(revert.getFullMessage().contains("This reverts commit " + clean + "."),
                "the message names the reverted commit: " + revert.getFullMessage());

        assertEquals(LATER_WITHOUT_CLEAN, read("story.txt"),
                "only line 5 goes back; the later rewrite of line 2 stays");
        assertEquals(LATER_WITHOUT_CLEAN, blobAt(revert.name(), "story.txt"), "and that is what was committed");
        assertFalse(Files.exists(work.resolve("extra.txt")), "the file the reverted commit added is gone");
        assertNull(blobAt(revert.name(), "extra.txt"));
        assertEquals(KEEP, read("keep.txt"));
        assertOnlyScratchIsLeftUncommitted();

        assertEquals(clean, resolve("refs/heads/main~2"), "the reverted commit stays where it was in history");
        assertEquals(CLEAN, blobAt(clean, "story.txt"), "and is itself unchanged");
        assertEquals(EXTRA, blobAt(clean, "extra.txt"));
        assertEquals(otherRefs, refsOtherThanMain(), "the tag and the other branch do not move");
        assertEquals(RepositoryState.SAFE, state(), "nothing is left in progress");
        assertEquals(later + " " + revert.name(), lastHeadMove(), "the revert is a recorded move of HEAD");
    }

    // ------------------------------------------------------------------
    // A revert that stops on a conflict
    // ------------------------------------------------------------------

    @Test
    void aConflictingRevertStopsWithRevertHeadTheConflictStagesAndTheBanner() throws Exception {
        Map<String, String> otherRefs = refsOtherThanMain();

        stopOnConflictingRevert();

        assertEquals(later, resolve("HEAD"), "nothing is committed while the revert is stopped");
        assertEquals(target, firstLine(".git/REVERT_HEAD"), "REVERT_HEAD names the commit being reverted");
        assertEquals(RepositoryState.REVERTING, state());
        assertTrue(read(".git/MERGE_MSG").startsWith("Revert \"" + TARGET_SUBJECT + "\""),
                "the prepared message is the revert's: " + read(".git/MERGE_MSG"));

        // A revert merges the commit's parent into HEAD with the commit itself as the base.
        assertEquals(Map.of(1, TARGET, 2, LATER, 3, BASE), stages("story.txt"),
                "base is the reverted commit, ours is HEAD, theirs is the commit's parent");
        assertEquals(Map.of(0, KEEP), stages("keep.txt"));
        assertEquals(Map.of(0, EXTRA), stages("extra.txt"));

        List<String> lines = read("story.txt").lines().toList();
        assertTrue(read("story.txt").endsWith("\n"));
        assertEquals(9, lines.size(), "the file is the agreed lines around one conflict: " + lines);
        assertEquals("line 1", lines.get(0));
        assertTrue(lines.get(1).startsWith("<<<<<<< "), lines.get(1));
        assertEquals("later line 2", lines.get(2), "ours is HEAD's line");
        assertEquals("=======", lines.get(3));
        assertEquals("line 2", lines.get(4), "theirs is the line as it was before the reverted commit");
        assertTrue(lines.get(5).startsWith(">>>>>>> ") && lines.get(5).contains(target.substring(0, 7)),
                "the incoming side names the reverted commit: " + lines.get(5));
        assertEquals(List.of("line 3", "line 4", "line 5, edited"), lines.subList(6, 9),
                "the part only HEAD changed merges cleanly");
        assertEquals(EXTRA, read("extra.txt"));
        assertEquals(KEEP, read("keep.txt"));
        assertEquals(SCRATCH, read("scratch.txt"));
        assertEquals(otherRefs, refsOtherThanMain());

        // What the user sees: a warning, and the banner that knows this is a revert.
        RobotWait.untilOnFx("the stop was never reported", () -> statusMessage().getStyleClass()
                .contains("status-warn") && statusMessage().getText().contains("conflict"));
        String banner = FxHarness.call(this::bannerText);
        assertEquals("Reverting " + target.substring(0, 7), FxHarness.call(this::bannerHeadline),
                "the banner's headline names the operation and the reverted commit");
        assertTrue(banner.startsWith("1 conflicting file - "), banner);
        assertTrue(FxHarness.call(() -> bannerButton("Continue").isDisabled()),
                "Continue waits until the conflict is resolved");
        assertFalse(FxHarness.call(() -> bannerButton("Abort revert").isDisabled()));
        assertNotNull(FxHarness.call(() -> bannerButton("Skip")));

        // And the staging area offers the conflicted file for resolving.
        openStagingArea();
        assertNotNull(FxHarness.call(() -> resolveButtonFor("story.txt")),
                "the conflicted file is listed with its Resolve action");
    }

    @Test
    void cancellingAbortKeepsTheStoppedRevertExactlyAsItWas() throws Exception {
        stopOnConflictingRevert();
        RepoSnapshot before = RepoSnapshot.of(work);
        String revertHead = read(".git/REVERT_HEAD");
        String mergeMessage = read(".git/MERGE_MSG");
        String conflicted = read("story.txt");

        DialogPane confirm = pressAbort();
        assertTrue(confirm.getContentText().contains("go back to where it started"), confirm.getContentText());
        press(confirm, ButtonType.CANCEL);
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work),
                "Cancel leaves HEAD, refs, the index's conflict stages, the files and the reflog alone");
        assertEquals(RepositoryState.REVERTING, state(), "the revert is still in progress");
        assertEquals(revertHead, read(".git/REVERT_HEAD"));
        assertEquals(mergeMessage, read(".git/MERGE_MSG"));
        assertEquals(conflicted, read("story.txt"), "the conflict markers are still on disk");
        assertNotNull(FxHarness.call(() -> bannerButton("Abort revert")), "and the banner still offers Abort");
    }

    @Test
    void acceptingAbortPutsTheTipIndexAndFilesBackAsTheyWereBeforeTheRevert() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        stopOnConflictingRevert();
        press(pressAbort(), ButtonType.OK);
        RobotWait.until("the abort never ended the revert",
                () -> !Files.exists(work.resolve(".git/REVERT_HEAD")));
        RobotWait.drainGitWork();

        RepoSnapshot after = RepoSnapshot.of(work);
        assertEquals(withoutReflog(before), withoutReflog(after),
                "the branch, its tip, every ref, the index and the files are as they were");
        assertEquals(LATER, read("story.txt"));
        assertEquals(SCRATCH, read("scratch.txt"), "local work the revert never touched is kept");
        assertEquals(RepositoryState.SAFE, after.state());
        assertFalse(Files.exists(work.resolve(".git/MERGE_MSG")),
                "the revert's prepared message is gone with it, so no later commit picks it up");
        // git records its reset to HEAD in the reflog; whatever is recorded, HEAD stays put.
        int added = after.headReflog().size() - before.headReflog().size();
        assertEquals(before.headReflog(), after.headReflog().subList(added, after.headReflog().size()));
        for (String entry : after.headReflog().subList(0, added)) {
            assertTrue(entry.startsWith(later + " " + later + " "), "HEAD never left the tip: " + entry);
        }
        RobotWait.untilOnFx("the banner outlived the revert", () -> banner() == null);
    }

    @Test
    void resolvingTheConflictAndContinuingMakesExactlyOneRevertCommit() throws Exception {
        Map<String, String> otherRefs = refsOtherThanMain();
        stopOnConflictingRevert();

        openStagingArea();
        Button resolve = FxHarness.call(() -> resolveButtonFor("story.txt"));
        interact(resolve::fire);
        DialogPane tool = RobotWait.untilDialogWithButton("Mark resolved");
        // The one conflict, settled on the revert's side - line 2 as it was before.
        interact(() -> buttonIn(tool, "Use theirs").fire());
        ButtonType markResolved = tool.getButtonTypes().stream()
                .filter(type -> "Mark resolved".equals(type.getText())).findFirst().orElseThrow();
        // Not interact(): a result still carrying markers would raise a nested question.
        Platform.runLater(() -> ((Button) tool.lookupButton(markResolved)).fire());
        RobotWait.until("the resolution was never staged", () -> stages("story.txt").equals(Map.of(0, RESOLVED)));
        RobotWait.drainGitWork();

        assertEquals(RESOLVED, read("story.txt"), "the chosen content is what is on disk");
        assertEquals(later, resolve("HEAD"), "resolving commits nothing by itself");
        assertEquals(RepositoryState.REVERTING_RESOLVED, state(), "the revert is still waiting to be finished");

        RobotWait.untilOnFx("Continue never became available",
                () -> banner() != null && !bannerButton("Continue").isDisabled());
        interact(() -> bannerButton("Continue").fire());
        RobotWait.until("Continue never committed the revert", () -> !later.equals(resolve("refs/heads/main")));
        RobotWait.drainGitWork();

        RevCommit revert = commit("refs/heads/main");
        assertEquals(1, revert.getParentCount());
        assertEquals(later, revert.getParent(0).name(), "the revert commit goes on top of the old tip");
        assertEquals(5, commitCount("refs/heads/main"), "exactly one commit was added");
        assertEquals("Revert \"" + TARGET_SUBJECT + "\"", revert.getShortMessage());
        assertTrue(revert.getFullMessage().contains("This reverts commit " + target + "."),
                "the message names the reverted commit: " + revert.getFullMessage());
        assertFalse(revert.getFullMessage().contains("onflict"),
                "the conflict notes in the prepared message are not committed: " + revert.getFullMessage());

        assertEquals(RESOLVED, blobAt(revert.name(), "story.txt"), "the resolution is what was committed");
        assertEquals(RESOLVED, read("story.txt"));
        assertEquals(EXTRA, read("extra.txt"));
        assertEquals(KEEP, read("keep.txt"));
        assertOnlyScratchIsLeftUncommitted();
        assertEquals(RepositoryState.SAFE, state());
        assertFalse(Files.exists(work.resolve(".git/REVERT_HEAD")));
        assertFalse(Files.exists(work.resolve(".git/MERGE_MSG")));
        assertEquals(target, resolve("refs/heads/main~3"), "the reverted commit stays where it was");
        assertEquals(TARGET, blobAt(target, "story.txt"));
        assertEquals(otherRefs, refsOtherThanMain(), "the tag and the other branch do not move");
        RobotWait.untilOnFx("the banner outlived the revert", () -> banner() == null);
    }

    // ------------------------------------------------------------------
    // The graph
    // ------------------------------------------------------------------

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

    /**
     * Selects the row showing {@code subject}, opens its menu with a context-menu request,
     * and fires "Revert commit" once the menu has decided from the repository that it
     * applies.
     */
    private void revertFromGraph(String subject) {
        GitGraphCell cell = FxHarness.call(() -> cellShowing(subject));
        assertNotNull(cell, "no row shows " + subject);
        interact(() -> {
            GitGraphRow row = cell.getItem();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(row);
            Event.fireEvent(cell, new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null));
        });
        ContextMenu menu = FxHarness.call(cell::getContextMenu);
        RobotWait.untilOnFx("the commit menu never opened", menu::isShowing);
        // The menu asks the repository where the commit sits when it opens, and enables
        // Revert from the answer.
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        MenuItem revert = FxHarness.call(() -> menu.getItems().stream()
                .filter(item -> item.getText() != null && item.getText().startsWith("Revert"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Revert in the commit menu")));
        assertEquals("Revert commit", FxHarness.call(revert::getText), "a commit on this branch can be reverted");
        assertFalse(FxHarness.call(revert::isDisable));
        // runLater, not interact: if the item ever asks first, its dialog's nested event
        // loop would hold an interact open.
        Platform.runLater(() -> {
            menu.hide();
            revert.fire();
        });
    }

    /** Reverts {@code target} from the graph and waits for git to stop on the conflict. */
    private void stopOnConflictingRevert() {
        revertFromGraph(TARGET_SUBJECT);
        RobotWait.until("the revert never stopped", () -> Files.exists(work.resolve(".git/REVERT_HEAD")));
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the in-progress banner never appeared", () -> banner() != null);
    }

    // ------------------------------------------------------------------
    // The banner, the staging area and the merge tool
    // ------------------------------------------------------------------

    private Node banner() {
        List<Node> banners = FxHarness.lookupAll(tab.getContent(), "operation-banner");
        return banners.isEmpty() ? null : banners.getFirst();
    }

    private String bannerText() {
        return FxHarness.lookupAll(banner(), "conflict-banner").stream()
                .map(node -> ((Label) node).getText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the banner has no text"));
    }

    /** The banner's headline - "Reverting a1b2c3d" - as its accessible text. Call on FX. */
    private String bannerHeadline() {
        return FxHarness.lookupAll(banner(), "operation-headline").stream()
                .map(Node::getAccessibleText)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the banner has no headline"));
    }

    /** The banner's button labelled {@code text}, or null. Call on FX. */
    private Button bannerButton(String text) {
        return banner() == null ? null : FxHarness.lookupAll(banner(), "button").stream()
                .filter(node -> node instanceof Button)
                .map(node -> (Button) node)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElse(null);
    }

    /** Presses the banner's Abort and returns the confirmation it raises. */
    private DialogPane pressAbort() {
        Button abort = FxHarness.call(() -> bannerButton("Abort revert"));
        assertNotNull(abort, "the banner offers Abort");
        // Not interact(): the confirmation's nested event loop would hold it open.
        Platform.runLater(abort::fire);
        return RobotWait.untilDialogWithButton("OK", "Cancel");
    }

    /**
     * Selects the uncommitted-changes row, which opens the staging area, and waits for the
     * conflicted file's row there.
     */
    private void openStagingArea() {
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        assertTrue(FxHarness.call(() -> tab.graphList().getSelectionModel().getSelectedItem().isWipNode()),
                "the top row is the uncommitted changes");
        RobotWait.untilOnFx("the staging area never offered to resolve story.txt",
                () -> resolveButtonFor("story.txt") != null);
    }

    /**
     * The Resolve button on {@code path}'s row in the Conflicted files list, after selecting
     * the row - the row's actions show for the selected row or under the pointer. Null while
     * the row or its button is not there yet. Call on FX.
     */
    private Button resolveButtonFor(String path) {
        @SuppressWarnings("unchecked")
        ListView<String> unstaged = (ListView<String>) FxHarness.lookupAll(tab.getContent(), "staging-conflicted-list")
                .stream().filter(node -> node instanceof ListView<?>).findFirst().orElse(null);
        if (unstaged == null || !unstaged.getItems().contains(path)) return null;
        unstaged.getSelectionModel().select(path);
        for (Node node : unstaged.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && path.equals(cell.getItem())) {
                return FxHarness.lookupAll(cell, "button").stream()
                        .filter(child -> child instanceof Button)
                        .map(child -> (Button) child)
                        .filter(button -> "Resolve".equals(button.getText()))
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    private static Button buttonIn(DialogPane pane, String text) {
        return pane.lookupAll(".button").stream()
                .filter(node -> node instanceof Button)
                .map(node -> (Button) node)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + text + "\" button in " + pane.getHeaderText()));
    }

    private void press(DialogPane pane, ButtonType type) {
        interact(() -> ((Button) pane.lookupButton(type)).fire());
    }

    private Label statusMessage() {
        return (Label) FxHarness.lookupAll(tab.getContent(), "status-message").getFirst();
    }

    // ------------------------------------------------------------------
    // The repository, read independently of the engine under test
    // ------------------------------------------------------------------

    /** Everything but untracked {@code scratch.txt} is committed, and it is still untracked. */
    private void assertOnlyScratchIsLeftUncommitted() throws Exception {
        Status status = status();
        assertTrue(status.getUncommittedChanges().isEmpty(), "nothing uncommitted: " + status.getUncommittedChanges());
        assertTrue(status.getConflicting().isEmpty());
        assertEquals(Set.of("scratch.txt"), status.getUntracked(), "the untracked file was not swept in");
        assertEquals(SCRATCH, read("scratch.txt"));
    }

    private static RepoSnapshot withoutReflog(RepoSnapshot snapshot) {
        return new RepoSnapshot(snapshot.branch(), snapshot.refs(), snapshot.upstream(), snapshot.index(),
                snapshot.files(), snapshot.state(), List.of());
    }

    /** Every branch, tag and stash ref except {@code main}, which a revert is meant to move. */
    private Map<String, String> refsOtherThanMain() throws Exception {
        Map<String, String> refs = new TreeMap<>(RepoSnapshot.of(work).refs());
        refs.remove("refs/heads/main");
        return refs;
    }

    /** {@code path}'s index entries, stage to content. */
    private Map<Integer, String> stages(String path) {
        try (Git git = Git.open(work.toFile())) {
            Map<Integer, String> stages = new TreeMap<>();
            DirCache cache = git.getRepository().readDirCache();
            for (int i = 0; i < cache.getEntryCount(); i++) {
                var entry = cache.getEntry(i);
                if (entry.getPathString().equals(path)) {
                    stages.put(entry.getStage(), new String(git.getRepository().open(entry.getObjectId())
                            .getBytes(), StandardCharsets.UTF_8));
                }
            }
            return stages;
        } catch (Exception midWrite) {
            return Map.of();
        }
    }

    /** {@code path}'s content in {@code revision}'s tree, or null when it has none. */
    private String blobAt(String revision, String path) throws Exception {
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(git.getRepository().resolve(revision));
            try (TreeWalk tree = TreeWalk.forPath(git.getRepository(), path, commit.getTree())) {
                return tree == null ? null
                        : new String(git.getRepository().open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private RevCommit commit(String revision) throws Exception {
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(git.getRepository().resolve(revision));
            walk.parseBody(commit);
            return commit;
        }
    }

    private int commitCount(String revision) throws Exception {
        try (Git git = Git.open(work.toFile())) {
            int count = 0;
            for (RevCommit ignored : git.log().add(git.getRepository().resolve(revision)).call()) {
                count++;
            }
            return count;
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

    private RepositoryState state() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return git.getRepository().getRepositoryState();
        }
    }

    /** HEAD's newest reflog entry, as "old new". */
    private String lastHeadMove() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            var entry = git.getRepository().getRefDatabase().getReflogReader(Constants.HEAD).getLastEntry();
            return entry.getOldId().name() + " " + entry.getNewId().name();
        }
    }

    private Status status() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            return git.status().call();
        }
    }

    private String firstLine(String file) throws Exception {
        return read(file).lines().findFirst().orElse("").strip();
    }

    private String read(String file) throws Exception {
        return Files.readString(work.resolve(file), StandardCharsets.UTF_8);
    }

    private void write(String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    }

    private static org.eclipse.jgit.revwalk.RevObject parse(Git git, String id) throws Exception {
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseAny(git.getRepository().resolve(id));
        }
    }

    private static String commitAll(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).call().getName();
    }
}
