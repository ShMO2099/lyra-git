package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphCell;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B21 step 6 in {@code manual-test-artifacts/qa-scenario-improvements.md}: reverting a merge
 * commit from the graph's own commit menu, where the user is asked which parent is the
 * mainline - git's {@code -m} - and the answer decides which side's changes are undone.
 * Judged by the repository afterwards, read through JGit directly rather than through the
 * engine that did the work: the new commit and its one parent, every other ref, the tree it
 * records, the index and the bytes on disk.
 *
 * <p>The fixture: {@code base} writes {@code left.txt} and {@code right.txt}. {@code feature}
 * branches from it, rewrites {@code right.txt} and adds {@code feature.txt}; {@code main}
 * rewrites {@code left.txt} and adds {@code main.txt}. {@code main} then merges
 * {@code feature} with a merge commit (parent 1 is main's side, parent 2 feature's), tagged
 * {@code merged}, and one more commit, {@code after the merge}, adds {@code later.txt} on
 * top - so the merge is an older commit, and a revert that simply checked out a parent's
 * tree would lose {@code later.txt}.
 *
 * <p>Reverting against parent 1 undoes what feature brought in; against parent 2 it undoes
 * what main did on its own side. The two sides touch different files, so both reverts are
 * clean and each result is exactly one parent's tree plus {@code later.txt}.
 *
 * <p>JGit is the engine here; {@link IncompleteQaMergeRevertNativeRobotTest} runs the same
 * journeys on the git CLI, which writes for the default Hybrid engine. The two do a merge
 * revert differently - JGit's {@code RevertCommand} refuses merges outright, so the JGit
 * engine runs its own three-way merge - which is why both are driven.
 */
class IncompleteQaMergeRevertRobotTest extends ApplicationTest {

    private static final String LEFT_BASE = "left base\n";
    private static final String LEFT_FROM_MAIN = "left from main\n";
    private static final String RIGHT_BASE = "right base\n";
    private static final String RIGHT_FROM_FEATURE = "right from feature\n";
    private static final String MAIN_FILE = "added on main\n";
    private static final String FEATURE_FILE = "added on feature\n";
    private static final String LATER_FILE = "after the merge\n";
    private static final String MERGE_SUBJECT = "merge feature into main";

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;
    private Stage stage;
    /** Main's side of the merge: the merge's parent 1. */
    private String mainSide;
    /** Feature's side of the merge: the merge's parent 2. */
    private String featureSide;
    private String merge;
    /** Main's tip when the test starts: one commit after the merge. */
    private String tip;

    /** The engine under test, opened on {@code workTree}; the CLI subclass swaps it. */
    protected GitBackend openEngine(Path workTree) throws Exception {
        GitService service = new GitService();
        assertTrue(service.openRepository(workTree.toFile()));
        return service;
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        work = root.resolve("work");
        try (var fixture = ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA");
                config.setString("user", null, "email", "qa@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();

                write("left.txt", LEFT_BASE);
                write("right.txt", RIGHT_BASE);
                commitAll(git, "base");

                git.checkout().setCreateBranch(true).setName("feature").call();
                write("right.txt", RIGHT_FROM_FEATURE);
                write("feature.txt", FEATURE_FILE);
                featureSide = commitAll(git, "feature work");

                git.checkout().setName("main").call();
                write("left.txt", LEFT_FROM_MAIN);
                write("main.txt", MAIN_FILE);
                mainSide = commitAll(git, "main work");

                MergeResult merged = git.merge()
                        .include(git.getRepository().resolve("refs/heads/feature"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .setMessage(MERGE_SUBJECT)
                        .call();
                assertTrue(merged.getMergeStatus().isSuccessful(), "fixture merge: " + merged);
                merge = merged.getNewHead().name();
                git.tag().setName("merged").setObjectId(parse(git.getRepository(), merge)).call();

                write("later.txt", LATER_FILE);
                tip = commitAll(git, "after the merge");
            }
            backend = openEngine(work);
        }
        // The application registers its window so that every dialog belongs to it; a test
        // that never does would see an owner-less dialog whatever the product did.
        DialogOwner.useMainWindow(stage);

        tab = new RepoTab(new LyraApp(), "merge revert", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheGraph() throws Exception {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        rowOf(merge);
        // What both journeys rely on, checked once rather than trusted: the merge's
        // parents are in this order, and each side's tree is what the class says.
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(git.getRepository().resolve(merge));
            assertEquals(List.of(mainSide, featureSide),
                    List.of(commit.getParent(0).name(), commit.getParent(1).name()));
        }
        assertEquals(Map.of("left.txt", LEFT_FROM_MAIN, "right.txt", RIGHT_BASE, "main.txt", MAIN_FILE),
                treeOf(mainSide));
        assertEquals(Map.of("left.txt", LEFT_BASE, "right.txt", RIGHT_FROM_FEATURE, "feature.txt", FEATURE_FILE),
                treeOf(featureSide));
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        DialogOwner.useMainWindow(null);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Journeys
    // ------------------------------------------------------------------

    /**
     * The common case, and the dialog's default: parent 1 is preselected, so OK alone
     * reverts against main's side. What feature brought in - its edit to {@code right.txt}
     * and its new {@code feature.txt} - is undone; main's own side and the later commit stay.
     */
    @Test
    void revertingTheMergeAgainstParentOneUndoesWhatTheMergedBranchBroughtIn() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        DialogPane question = askToRevertTheMerge();
        assertEquals(List.of(true, false),
                FxHarness.call(() -> parentChoices(question).stream().map(RadioButton::isSelected).toList()),
                "parent 1, the branch merged into, is the preselected answer");
        press(question, ButtonType.OK);

        String reverted = awaitNewTip();
        assertOneRevertCommitOnTheOldTip(before, reverted, mainSide);
        Map<String, String> expected = Map.of(
                "left.txt", LEFT_FROM_MAIN,
                "right.txt", RIGHT_BASE,
                "main.txt", MAIN_FILE,
                "later.txt", LATER_FILE);
        assertEquals(withLaterFile(treeOf(mainSide)), expected,
                "precondition: the expected result is parent 1's tree plus the later commit");
        assertEquals(expected, treeOf(reverted), "the revert commit records parent 1's side plus later.txt");
        assertEquals(expected, filesOnDisk(), "the work tree holds exactly those files, byte for byte");
        assertCleanAndSettled();
    }

    /**
     * The rarer case: parent 2 chosen, so the revert is taken relative to feature's side and
     * what main did on its own - its edit to {@code left.txt} and its new {@code main.txt} -
     * is undone, while feature's changes and the later commit stay.
     */
    @Test
    void revertingTheMergeAgainstParentTwoUndoesWhatTheMainlineSideBroughtIn() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        DialogPane question = askToRevertTheMerge();
        interact(() -> parentChoices(question).get(1).setSelected(true));
        press(question, ButtonType.OK);

        String reverted = awaitNewTip();
        assertOneRevertCommitOnTheOldTip(before, reverted, featureSide);
        Map<String, String> expected = Map.of(
                "left.txt", LEFT_BASE,
                "right.txt", RIGHT_FROM_FEATURE,
                "feature.txt", FEATURE_FILE,
                "later.txt", LATER_FILE);
        assertEquals(withLaterFile(treeOf(featureSide)), expected,
                "precondition: the expected result is parent 2's tree plus the later commit");
        assertEquals(expected, treeOf(reverted), "the revert commit records parent 2's side plus later.txt");
        assertEquals(expected, filesOnDisk(), "the work tree holds exactly those files, byte for byte");
        assertCleanAndSettled();
    }

    @Test
    void cancellingTheParentQuestionChangesNothing() throws Exception {
        RepoSnapshot before = RepoSnapshot.of(work);

        DialogPane question = askToRevertTheMerge();
        press(question, ButtonType.CANCEL);
        RobotWait.untilOnFx("the parent question never closed", () -> question.getScene() == null
                || question.getScene().getWindow() == null || !question.getScene().getWindow().isShowing());
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work),
                "cancelling must leave the branch, every ref, the index, the files and HEAD's reflog as they were");
        assertEquals(tip, resolve("refs/heads/main"));
    }

    // ------------------------------------------------------------------
    // The graph and the question it asks
    // ------------------------------------------------------------------

    /**
     * Opens the merge row's commit menu, fires its revert item, and returns the parent
     * question that follows, having checked what it offers: both parents by short hash, in
     * order, parent 1 named as the mainline.
     */
    private DialogPane askToRevertTheMerge() {
        GitGraphCell row = rowOf(merge);
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        ContextMenu menu = row.getContextMenu();
        // The label and enablement are settled on a worker by an ancestry query the menu
        // starts as it opens; until then they come from the row's own flags.
        MenuItem[] revert = new MenuItem[1];
        RobotWait.untilOnFx("the merge row's menu never offered \"Revert merge commit…\"", () -> {
            revert[0] = menu.getItems().stream()
                    .filter(i -> "Revert merge commit…".equals(i.getText()) && !i.isDisable())
                    .findFirst().orElse(null);
            return menu.isShowing() && revert[0] != null;
        });
        RobotWait.drainGitWork();
        // Not interact(): the item opens a modal dialog whose nested event loop an
        // interact() would wait on forever.
        Platform.runLater(() -> {
            menu.hide();
            revert[0].fire();
        });

        DialogPane question = RobotWait.untilDialogWithButton("OK");
        Stage dialog = (Stage) question.getScene().getWindow();
        assertEquals("Revert merge commit", dialog.getTitle());
        assertEquals("Revert " + merge.substring(0, 7) + " relative to which parent?", question.getHeaderText());
        assertEquals(List.of(
                        "Parent 1 · " + mainSide.substring(0, 7) + " (mainline)",
                        "Parent 2 · " + featureSide.substring(0, 7)),
                FxHarness.call(() -> parentChoices(question).stream().map(RadioButton::getText).toList()),
                "both parents are offered, in the merge's own order");
        assertEquals(List.of("main work", "feature work"), FxHarness.call(() -> parentSubjects(question)),
                "each side is named by its commit's subject, not only by its hash");
        assertEquals("Revert merge",
                FxHarness.call(() -> ((Button) question.lookupButton(ButtonType.OK)).getText()),
                "the answer says what it does");
        assertTrue(question.getButtonTypes().contains(ButtonType.CANCEL), "the question can be cancelled");
        assertSame(stage, dialog.getOwner(),
                "the question belongs to the main window, like every other dialog the app opens");
        return question;
    }

    /** The graph row showing {@code hash}, once the graph has drawn it. */
    private GitGraphCell rowOf(String hash) {
        GitGraphCell[] found = new GitGraphCell[1];
        RobotWait.untilOnFx("the graph never showed a row for " + hash, () -> {
            for (Node node : FxHarness.lookupAll(tab.getContent(), "list-cell")) {
                // Visible only: the list keeps spare cells, hidden, still holding old rows.
                if (node instanceof GitGraphCell cell && cell.isVisible()
                        && cell.getParent() != null && cell.getParent().isVisible()
                        && cell.getItem() != null && cell.getItem().getCommit() != null
                        && hash.equals(cell.getItem().getCommit().getHash())
                        && cell.getContextMenu() != null) {
                    found[0] = cell;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    /** The question's options, one card per parent, in the order it lists them. Call on FX. */
    private static List<RadioButton> parentChoices(DialogPane question) {
        return FxHarness.lookupAll(question, "jfx-option-radio").stream().map(RadioButton.class::cast).toList();
    }

    /** The line under each option, which names that parent by its subject. Call on FX. */
    private static List<String> parentSubjects(DialogPane question) {
        return FxHarness.lookupAll(question, "jfx-option-card").stream()
                .map(card -> FxHarness.lookupAll(card, "t-caption").stream()
                        .map(caption -> ((javafx.scene.control.Label) caption).getText())
                        .findFirst().orElse(null))
                .toList();
    }

    private void press(DialogPane pane, ButtonType type) {
        interact(() -> ((Button) pane.lookupButton(type)).fire());
    }

    // ------------------------------------------------------------------
    // The repository, read independently of the engine under test
    // ------------------------------------------------------------------

    /** Waits for main to move off the starting tip, and returns where it went. */
    private String awaitNewTip() {
        RobotWait.until("the revert never moved main", () -> !tip.equals(resolve("refs/heads/main")));
        RobotWait.drainGitWork();
        return resolve("refs/heads/main");
    }

    /**
     * Exactly one new commit, on the old tip, saying which merge it reverts and against which
     * parent - git's own wording, which both engines write - with every other ref, the
     * merge's tag and feature among them, where it was.
     */
    private void assertOneRevertCommitOnTheOldTip(RepoSnapshot before, String reverted, String parent)
            throws Exception {
        RepoSnapshot after = RepoSnapshot.of(work);
        assertEquals("refs/heads/main", after.branch(), "the revert leaves main checked out");
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(git.getRepository().resolve(reverted));
            assertEquals(1, commit.getParentCount(), "a revert is an ordinary commit, not another merge");
            assertEquals(tip, commit.getParent(0).name(), "the revert commit sits on the old tip");
            assertEquals("Revert \"" + MERGE_SUBJECT + "\"", commit.getShortMessage());
            assertTrue(commit.getFullMessage().contains("This reverts commit " + merge + ", reversing\n"
                            + "changes made to " + parent + "."),
                    "the message names the merge and the parent it was reverted against: "
                            + commit.getFullMessage());
        }
        Map<String, String> refsBefore = new TreeMap<>(before.refs());
        Map<String, String> refsAfter = new TreeMap<>(after.refs());
        refsBefore.remove("refs/heads/main");
        refsAfter.remove("refs/heads/main");
        assertEquals(refsBefore, refsAfter, "only main moves; feature and the merge's tag stay put");
        assertEquals(before.headReflog().size() + 1, after.headReflog().size(),
                "HEAD moved once, straight to the revert: " + after.headReflog());
        assertTrue(after.headReflog().getFirst().startsWith(tip + " " + reverted + " "),
                after.headReflog().getFirst());
    }

    /** No revert left in progress, and the index and work tree both match the new commit. */
    private void assertCleanAndSettled() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState(),
                    "a clean revert leaves no REVERT_HEAD behind");
            var status = git.status().call();
            assertTrue(status.isClean(), "the index and work tree match the revert commit: "
                    + status.getUncommittedChanges() + " untracked " + status.getUntracked());
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

    /** Every file {@code revision} records, path to content. */
    private Map<String, String> treeOf(String revision) throws Exception {
        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository());
             TreeWalk tree = new TreeWalk(git.getRepository())) {
            tree.addTree(walk.parseCommit(git.getRepository().resolve(revision)).getTree());
            tree.setRecursive(true);
            Map<String, String> files = new TreeMap<>();
            while (tree.next()) {
                files.put(tree.getPathString(), new String(
                        git.getRepository().open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
            }
            return files;
        }
    }

    /** The files in the work tree, outside {@code .git}. */
    private Map<String, String> filesOnDisk() throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (var listing = Files.list(work)) {
            for (Path file : listing.filter(Files::isRegularFile).toList()) {
                files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static Map<String, String> withLaterFile(Map<String, String> files) {
        Map<String, String> with = new TreeMap<>(files);
        with.put("later.txt", LATER_FILE);
        return with;
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private void write(String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content, StandardCharsets.UTF_8);
    }

    private static String commitAll(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).call().name();
    }

    private static RevCommit parse(Repository repository, String id) throws Exception {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(repository.resolve(id));
        }
    }
}
