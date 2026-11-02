package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitService;
import com.jfx.git.core.RebaseTodo;
import com.jfx.git.core.RebaseTodo.Action;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.ui.FxHarness;

import com.jfx.git.model.CommitInfo;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jfx.git.model.RebaseEligibility;

/**
 * The editor's job is to make an unrunnable plan unreachable. These check that the
 * confirm button reflects the plan rather than the user's optimism.
 */
class InteractiveRebaseDialogTest {

    @TempDir
    Path tempDir;

    private GitService service;

    @BeforeEach
    void setUp() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        for (String name : List.of("base", "a", "b", "c")) {
            Files.writeString(tempDir.resolve(name + ".txt"), name, StandardCharsets.UTF_8);
            assertTrue(service.stageFile(name + ".txt").isOk());
            assertTrue(service.commit("add " + name, false).isOk());
        }
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    /** The commit HEAD points at, as opposed to the newest row in the graph. */
    private String headCommit() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    private List<CommitInfo> log() {
        List<CommitInfo> log = new ArrayList<>();
        for (var row : service.getCommitGraphPage(50, GraphOrder.current()).rows()) {
            if (!row.isWipNode()) {
                log.add(row.getCommit());
            }
        }
        return log;
    }

    /** Shows a dialog over the newest three commits and lays it out. */
    private InteractiveRebaseDialog dialog() {
        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 3));
        return FxHarness.call(() -> {
            InteractiveRebaseDialog d = new InteractiveRebaseDialog(plan);
            d.getDialogPane().applyCss();
            d.getDialogPane().layout();
            return d;
        });
    }

    private Node startButton(InteractiveRebaseDialog dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(t -> "Start rebase".equals(t.getText()))
                .map(t -> dialog.getDialogPane().lookupButton(t))
                .findFirst().orElseThrow();
    }

    private Button resetButton(InteractiveRebaseDialog dialog) {
        return (Button) FxHarness.lookupAll(dialog.getDialogPane(), "rebase-reset").get(0);
    }

    // ------------------------------------------------------------------
    // Range
    // ------------------------------------------------------------------

    @Test
    void theRangeRunsFromTheChosenCommitToHead() {
        String from = log().get(2).getHash(); // "add a"
        var range = service.getRebaseRange(from);

        assertTrue(range.isUsable(), String.valueOf(range.problem()));
        assertEquals(3, range.commits().size());
        assertEquals("add c", range.commits().get(0).getSubject(), "newest first");
        assertEquals("add a", range.commits().get(2).getSubject(),
                "the chosen commit is included");
    }

    @Test
    void aCommitOutsideTheRepositoryIsRefusedWithAReason() {
        var range = service.getRebaseRange("0000000000000000000000000000000000000000");
        assertFalse(range.isUsable());
        assertTrue(range.commits().isEmpty());
        assertFalse(range.problem().isBlank(), "a refusal has to say why");
    }

    @Test
    void aCommitOnAnotherBranchBecomesTheBaseInstead() throws Exception {
        String trunk = service.getCurrentBranch();
        // Branch from "add a" so the two genuinely diverge: rebasing onto a commit that
        // is merely ahead of this branch would leave nothing of ours to replay.
        assertTrue(service.createBranch("side", log().get(2).getHash(), true).isOk());
        Files.writeString(tempDir.resolve("side.txt"), "s", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("side.txt").isOk());
        assertTrue(service.commit("only on side", false).isOk());
        String sideCommit = service.getRebaseRange("HEAD").commits().get(0).getHash();

        assertTrue(service.checkoutBranch(trunk).isOk());

        // A commit on another branch is not a refusal: it means "put this branch on top
        // of that commit", which is the other half of what a rebase does.
        assertEquals(RebaseEligibility.ONTO, RebaseEligibility.from(service.getCommitPosition(sideCommit)));

        var range = service.getRebaseRange(sideCommit);
        assertTrue(range.isUsable(), String.valueOf(range.problem()));
        assertEquals(sideCommit, range.base(),
                "the chosen commit becomes the base rather than being replayed");
        // Only this branch's own commits are replayed, not the side branch's.
        assertTrue(range.commits().stream()
                .noneMatch(c -> c.getSubject().equals("only on side")));
        assertTrue(range.commits().stream()
                .anyMatch(c -> c.getSubject().equals("add c")));
    }

    @Test
    void replayingFromACommitOnThisBranchUsesItsParentAsTheBase() {
        var range = service.getRebaseRange(log().get(2).getHash()); // "add a"
        assertTrue(range.isUsable(), String.valueOf(range.problem()));
        assertEquals(log().get(3).getHash(), range.base(),
                "the commit itself is replayed, so the base is its parent");
        assertEquals(3, range.commits().size());
    }

    @Test
    void positionDrivesCherryPickAndRevertToo() throws Exception {
        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("side", null, true).isOk());
        Files.writeString(tempDir.resolve("side.txt"), "s", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("side.txt").isOk());
        assertTrue(service.commit("only on side", false).isOk());
        String offBranch = log().get(0).getHash();
        assertTrue(service.checkoutBranch(trunk).isOk());
        // Deliberately not log().get(0): that reads the graph, which draws every branch,
        // so the newest row is the side commit - the very confusion this guards against.
        String onBranch = headCommit();

        // Cherry-pick and revert are mirror images: one needs the commit off this
        // branch, the other needs it on.
        assertTrue(service.getCommitPosition(onBranch).onCurrentBranch());
        assertFalse(service.getCommitPosition(offBranch).onCurrentBranch());

        assertTrue(service.getCommitPosition(onBranch).known());
        assertFalse(service.getCommitPosition("0000000000000000000000000000000000000000").known());
    }

    @Test
    void eligibilityNamesTheReasonForEachKindOfCommit() throws Exception {
        assertEquals(RebaseEligibility.OK,
                RebaseEligibility.from(service.getCommitPosition(log().get(1).getHash())));

        // The root commit has nothing to replay onto.
        assertEquals(RebaseEligibility.ROOT_COMMIT,
                RebaseEligibility.from(service.getCommitPosition(log().get(3).getHash())));

        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("merge-src", null, true).isOk());
        Files.writeString(tempDir.resolve("m.txt"), "m", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("m.txt").isOk());
        assertTrue(service.commit("for merging", false).isOk());
        assertTrue(service.checkoutBranch(trunk).isOk());
        assertTrue(new RepositoryCommands(service).merge("merge-src", true).isOk());

        assertEquals(RebaseEligibility.MERGE_COMMIT,
                RebaseEligibility.from(service.getCommitPosition(log().get(0).getHash())));
    }

    // ------------------------------------------------------------------
    // The confirm button tracks the plan
    // ------------------------------------------------------------------

    @Test
    void anUntouchedPlanCannotBeStarted() {
        InteractiveRebaseDialog dialog = dialog();
        FxHarness.run(() -> assertTrue(startButton(dialog).isDisabled(),
                "a plan that changes nothing has nothing to run"));
    }

    @Test
    void anEditedPlanCanBeStarted() {
        InteractiveRebaseDialog dialog = dialog();
        FxHarness.run(() -> {
            setAction(dialog, 1, Action.DROP);
            assertFalse(startButton(dialog).isDisabled());
        });
    }

    @Test
    void anInvalidPlanCannotBeStartedAndSaysWhy() {
        InteractiveRebaseDialog dialog = dialog();
        FxHarness.run(() -> {
            setAction(dialog, 0, Action.SQUASH);

            assertTrue(startButton(dialog).isDisabled(),
                    "a squash at the top has nothing to fold into");
            Label problem = (Label) FxHarness.lookupAll(dialog.getDialogPane(), "rebase-problem").get(0);
            assertTrue(problem.isVisible());
            assertTrue(problem.getText().contains("no commit above it"), problem.getText());
        });
    }

    @Test
    void fixingAnInvalidPlanReEnablesTheButton() {
        InteractiveRebaseDialog dialog = dialog();
        FxHarness.run(() -> {
            setAction(dialog, 0, Action.SQUASH);
            assertTrue(startButton(dialog).isDisabled());

            setAction(dialog, 0, Action.DROP);
            assertFalse(startButton(dialog).isDisabled(), "the plan is runnable again");
        });
    }

    @Test
    void theSummarySaysWhatTheRebaseWillProduce() {
        InteractiveRebaseDialog dialog = dialog();
        FxHarness.run(() -> {
            setAction(dialog, 2, Action.DROP);
            Label summary = (Label) FxHarness.lookupAll(dialog.getDialogPane(), "rebase-summary").get(0);
            assertTrue(summary.getText().startsWith("3 commits become 2"), summary.getText());
            assertTrue(summary.getText().contains(currentPlan(dialog).step(2).shortId() + " dropped"),
                    summary.getText());
            assertTrue(summary.getText().endsWith("rewrites history"), summary.getText());
        });
    }

    @Test
    void resetDiscardsAnActionEditAndDisablesStartAgain() {
        InteractiveRebaseDialog dialog = dialog();
        FxHarness.run(() -> {
            setAction(dialog, 1, Action.DROP);
            assertFalse(startButton(dialog).isDisabled(), "the edit should have made the plan runnable");

            resetButton(dialog).fire();

            assertTrue(startButton(dialog).isDisabled(),
                    "back to the untouched plan, which changes nothing");
            assertEquals(Action.PICK, currentPlan(dialog).step(1).action(),
                    "the dropped row should be a plain pick again");
        });
    }

    @Test
    void resetDiscardsAReorderToo() {
        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 3));
        RebaseTodo result = FxHarness.call(() -> {
            InteractiveRebaseDialog dialog = new InteractiveRebaseDialog(plan);
            dialog.setX(-4000);
            dialog.setY(-4000);
            dialog.show();
            FxHarness.pulse(dialog.getDialogPane());

            List<Node> moves = FxHarness.lookupAll(dialog.getDialogPane(), "rebase-move");
            ((Button) moves.get(4)).fire(); // last row's "up"
            FxHarness.pulse(dialog.getDialogPane());
            assertTrue(currentPlan(dialog).isReordered(), "the move should have taken effect first");

            resetButton(dialog).fire();
            FxHarness.pulse(dialog.getDialogPane());

            RebaseTodo backToOriginal = currentPlan(dialog);
            dialog.close();
            return backToOriginal;
        });

        assertFalse(result.isReordered(), "reset should have undone the move");
        assertEquals("add c", result.step(2).commit().getSubject(), "back to newest-last order");
    }

    @Test
    void movingACommitIsReflectedInTheResult() {
        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 3));
        RebaseTodo result = FxHarness.call(() -> {
            InteractiveRebaseDialog dialog = new InteractiveRebaseDialog(plan);
            // Rows only have cells - and therefore buttons - once the dialog is on a
            // window. A Dialog owns its own scene, so it has to be its own window;
            // showing it off-screen keeps the test from flashing over the desktop.
            dialog.setX(-4000);
            dialog.setY(-4000);
            dialog.show();
            FxHarness.pulse(dialog.getDialogPane());

            List<Node> moves = FxHarness.lookupAll(dialog.getDialogPane(), "rebase-move");
            assertEquals(6, moves.size(), "two move buttons for each of the three rows");

            // The last row's "up", pressed twice, walks "add c" to the top.
            Button lastUp = (Button) moves.get(4);
            lastUp.fire();
            FxHarness.pulse(dialog.getDialogPane());
            ((Button) FxHarness.lookupAll(dialog.getDialogPane(), "rebase-move").get(2)).fire();

            RebaseTodo edited = currentPlan(dialog);
            dialog.close();
            return edited;
        });

        assertEquals("add c", result.step(0).commit().getSubject(),
                "the moved commit should now replay first");
        assertTrue(result.isReordered());
    }

    // ------------------------------------------------------------------
    // Restricted action sets
    // ------------------------------------------------------------------

    /**
     * Cherry-picking applies commits one at a time, so it can honour order and omission
     * but not folding or rewording. The editor must not offer what the caller discards.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aCherryPickEditorOffersOnlyPickAndDrop() {
        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 3), "HEAD");
        InteractiveRebaseDialog dialog = FxHarness.call(() -> {
            var d = new InteractiveRebaseDialog(plan, "Cherry-pick commits", "Apply",
                    InteractiveRebaseDialog.PICK_OR_DROP);
            d.getDialogPane().applyCss();
            d.getDialogPane().layout();
            return d;
        });

        List<Node> boxes = FxHarness.lookupAll(dialog.getDialogPane(), "combo-box");
        assertFalse(boxes.isEmpty(), "the plan should have rendered rows");
        for (Node box : boxes) {
            assertEquals(List.of(Action.PICK, Action.DROP),
                    ((ComboBox<Action>) box).getItems(),
                    "a cherry-pick cannot squash or reword, so it must not offer to");
        }
    }

    @Test
    void theKeyHintNamesOnlyTheActionsOnOffer() {
        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 3), "HEAD");
        InteractiveRebaseDialog dialog = FxHarness.call(() -> {
            var d = new InteractiveRebaseDialog(plan, "Cherry-pick commits", "Apply",
                    InteractiveRebaseDialog.PICK_OR_DROP);
            d.getDialogPane().applyCss();
            d.getDialogPane().layout();
            return d;
        });

        // The legend is key chips and words; read as one line, it is what it says.
        String hint = String.join(" ", FxHarness.lookupAll(
                        FxHarness.lookupAll(dialog.getDialogPane(), "rebase-keys-hint").get(0), "label").stream()
                .map(n -> ((Label) n).getText()).toList());
        assertTrue(hint.contains("P pick"), hint);
        assertTrue(hint.contains("D drop"), hint);
        assertTrue(hint.contains("Alt ↑ ↓ move"), hint);
        assertFalse(hint.contains("squash"), "S does nothing here");
        assertFalse(hint.contains("reword"), "R does nothing here");
    }

    /**
     * The dialog is as tall as its plan: three commits no longer sit above 200px of empty
     * list, and a long plan stops growing at ten rows and scrolls.
     */
    @Test
    void theDialogIsAsTallAsItsPlan() {
        RebaseTodo shortPlan = RebaseTodo.fromNewestFirst(log().subList(0, 3));
        double[] heights = FxHarness.call(() -> {
            InteractiveRebaseDialog dialog = new InteractiveRebaseDialog(shortPlan);
            dialog.setX(-4000);
            dialog.setY(-4000);
            dialog.show();
            FxHarness.pulse(dialog.getDialogPane());
            ListView<?> list = (ListView<?>) FxHarness.lookupAll(dialog.getDialogPane(), "rebase-steps").get(0);
            double[] measured = {dialog.getDialogPane().getHeight(), list.getHeight()};
            dialog.close();
            return measured;
        });
        assertTrue(heights[0] < 480, "shorter than the 480px it was pinned at: " + heights[0]);
        assertTrue(heights[1] >= 3 * 36 + 4 && heights[1] < 4 * 36,
                "room for its three rows and little more: " + heights[1]);
    }

    /** The summary names what happens to each commit the plan changes, by its hash. */
    @Test
    void theSummaryNamesWhatIsFoldedAndDropped() {
        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 3));
        RebaseTodo edited = plan.withAction(1, Action.SQUASH).withAction(2, Action.DROP);
        String first = plan.step(0).shortId();
        String second = plan.step(1).shortId();
        String third = plan.step(2).shortId();

        assertEquals("3 commits become 1 · " + second + " squashed into " + first + " · " + third
                        + " dropped · rewrites history",
                InteractiveRebaseDialog.outcome(edited, true));
        assertEquals("3 commits become 1 · " + second + " squashed into " + first + " · " + third
                        + " dropped",
                InteractiveRebaseDialog.outcome(edited, false),
                "a cherry-pick makes new commits and rewrites nothing");
        assertEquals("3 commits in a new order · rewrites history",
                InteractiveRebaseDialog.outcome(plan.moved(2, 0), true));
    }

    /** A row dragged by its handle lands against the edge of the row it is dropped on. */
    @Test
    void aDraggedRowLandsAtTheEdgeItIsDroppedOn() {
        assertEquals(2, InteractiveRebaseDialog.dropTarget(0, 2, true, 3), "first row, below the last: last");
        assertEquals(0, InteractiveRebaseDialog.dropTarget(2, 0, false, 3), "last row, above the first: first");
        assertEquals(0, InteractiveRebaseDialog.dropTarget(0, 1, false, 3), "above the row under it: no move");
        assertEquals(1, InteractiveRebaseDialog.dropTarget(2, 0, true, 3), "below the first: second");
        assertEquals(2, InteractiveRebaseDialog.dropTarget(0, null, false, 3), "under every row: last");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRebaseEditorStillOffersEveryAction() {
        List<Node> boxes = FxHarness.lookupAll(dialog().getDialogPane(), "combo-box");
        assertEquals(List.of(Action.values()), ((ComboBox<Action>) boxes.get(0)).getItems());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void setAction(InteractiveRebaseDialog dialog, int row, Action action) {
        List<Node> boxes = FxHarness.lookupAll(dialog.getDialogPane(), "combo-box");
        ((ComboBox<Action>) boxes.get(row)).setValue(action);
    }

    /** The plan the dialog would hand back if confirmed now. */
    private RebaseTodo currentPlan(InteractiveRebaseDialog dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(t -> "Start rebase".equals(t.getText()))
                .findFirst()
                .map(t -> dialog.getResultConverter().call(t))
                .orElseThrow();
    }

    // ------------------------------------------------------------------
    // R5-1. Filling a row from the plan is not an edit to the plan
    // ------------------------------------------------------------------

    /**
     * A reorder refills every visible row - each cell's action box and message field are
     * set from whichever step now sits at that index. Neither programmatic fill may
     * register as a user edit.
     *
     * <p>This pins the property rather than reproducing a failure: with the action
     * handler unguarded it re-applied the action the cell had just been given, which is
     * a no-op plan edit and so invisible from here. That is exactly why it survived - and
     * why the guard is worth having before {@code apply} ever does more than it does now.
     */
    @Test
    void refillingRowsDoesNotChangeAnyAction() {
        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 3));
        RebaseTodo result = FxHarness.call(() -> {
            InteractiveRebaseDialog dialog = new InteractiveRebaseDialog(plan);
            dialog.setX(-4000);
            dialog.setY(-4000);
            dialog.show();
            FxHarness.pulse(dialog.getDialogPane());

            // One real edit, so there is something a stray re-apply could disturb.
            setAction(dialog, 1, Action.DROP);
            FxHarness.pulse(dialog.getDialogPane());

            // Moving refills every row from the reordered plan.
            List<Node> moves = FxHarness.lookupAll(dialog.getDialogPane(), "rebase-move");
            ((Button) moves.get(4)).fire();
            FxHarness.pulse(dialog.getDialogPane());

            RebaseTodo current = currentPlan(dialog);
            dialog.close();
            return current;
        });

        assertEquals(1, result.steps().stream().filter(s -> s.action() == Action.DROP).count(),
                "the one action the user set, and no others picked up from refilling rows");
    }
}
