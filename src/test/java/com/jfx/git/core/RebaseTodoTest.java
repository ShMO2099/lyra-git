package com.jfx.git.core;

import com.jfx.git.core.RebaseTodo.Action;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of a rebase plan, decided before anything can touch a repository.
 *
 * <p>Real commits rather than stubs, because the plan reasons about parent counts and
 * identity and a stub that lies about either would let a bad plan through.
 */
@Tag("heavy")
class RebaseTodoTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private final List<CommitInfo> commits = new ArrayList<>();

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

        for (int i = 1; i <= 4; i++) {
            Files.writeString(tempDir.resolve("f" + i + ".txt"), "v" + i, StandardCharsets.UTF_8);
            assertTrue(service.stageFile("f" + i + ".txt").isOk());
            assertTrue(service.commit("commit " + i, false).isOk());
        }
        // Newest first, the order the graph hands over.
        commits.addAll(collectLog());
    }

    private List<CommitInfo> collectLog() {
        List<CommitInfo> log = new ArrayList<>();
        for (var row : service.getCommitGraphPage(10, GraphOrder.current()).rows()) {
            if (!row.isWipNode()) {
                log.add(row.getCommit());
            }
        }
        return log;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    private RebaseTodo todo() {
        return RebaseTodo.fromNewestFirst(commits);
    }

    private List<String> messages(RebaseTodo todo) {
        return todo.steps().stream().map(s -> s.commit().getSubject()).toList();
    }

    // ------------------------------------------------------------------
    // Order
    // ------------------------------------------------------------------

    @Test
    void aPlanIsBuiltInReplayOrderOldestFirst() {
        // The graph hands over newest first; replaying in that order would reverse history.
        assertEquals("commit 4", commits.get(0).getSubject(), "fixture assumption");
        assertEquals(List.of("commit 1", "commit 2", "commit 3", "commit 4"), messages(todo()));
    }

    @Test
    void everyCommitStartsAsPick() {
        assertTrue(todo().steps().stream().allMatch(s -> s.action() == Action.PICK));
        assertTrue(todo().isNoop());
        assertTrue(todo().isValid());
    }

    /**
     * A plain "rebase onto" plan (a commercial client's drag-a-branch-onto-another, or
     * SidebarPane's "Rebase current onto this") is every step left as a plain pick -
     * the point is to move the whole range, not edit it. {@link RebaseTodo#isNoop()}
     * used to only look at action/message/order, so this looked identical to a true
     * no-op and left "Start Rebase" permanently disabled - the commits never moved.
     */
    @Test
    void anExplicitBaseDifferentFromTheOriginalParentIsNotANoopEvenWithNoEdits() {
        // "commit 4"'s real parent is "commit 3" - telling the plan to land on
        // "commit 2" instead is the shape of a genuine relocation.
        RebaseTodo relocated = RebaseTodo.fromNewestFirst(
                List.of(commits.get(0)), commits.get(2).getHash());
        assertFalse(relocated.isNoop(),
                "moving the range onto a different commit is a real change, "
                        + "even with every step a plain pick");
    }

    @Test
    void anExplicitBaseThatMatchesTheOriginalParentIsStillANoop() {
        // Same single-commit plan, but the base given is its own real parent - a
        // plain "replay from here" spelled out explicitly rather than left to be
        // derived the way todo() (base == null) does.
        RebaseTodo same = RebaseTodo.fromNewestFirst(
                List.of(commits.get(0)), commits.get(0).getParentHashes().get(0));
        assertTrue(same.isNoop());
    }

    // ------------------------------------------------------------------
    // Edits are non-destructive
    // ------------------------------------------------------------------

    @Test
    void changingAnActionLeavesTheOriginalAlone() {
        RebaseTodo original = todo();
        RebaseTodo edited = original.withAction(1, Action.DROP);

        assertEquals(Action.DROP, edited.step(1).action());
        assertEquals(Action.PICK, original.step(1).action(), "the original must be untouched");
        assertTrue(original.isNoop());
        assertFalse(edited.isNoop());
    }

    @Test
    void movingAStepReordersWithoutLosingAny() {
        RebaseTodo moved = todo().moved(0, 3);
        assertEquals(List.of("commit 2", "commit 3", "commit 4", "commit 1"), messages(moved));
        assertEquals(4, moved.size());
    }

    @Test
    void movingUpwardsWorksToo() {
        RebaseTodo moved = todo().moved(3, 0);
        assertEquals(List.of("commit 4", "commit 1", "commit 2", "commit 3"), messages(moved));
    }

    @Test
    void anOutOfRangeMoveIsIgnoredRatherThanThrowing() {
        RebaseTodo original = todo();
        assertEquals(messages(original), messages(original.moved(0, 99)));
        assertEquals(messages(original), messages(original.moved(-1, 2)));
        assertEquals(messages(original), messages(original.moved(2, 2)));
    }

    @Test
    void reorderingIsDetected() {
        RebaseTodo original = todo();
        assertFalse(original.isReorderOf(original));
        assertTrue(original.moved(0, 3).isReorderOf(original));
        assertFalse(original.withAction(0, Action.DROP).isReorderOf(original),
                "changing an action is not a reorder");
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void aSquashAtTheTopHasNothingToFoldInto() {
        RebaseTodo invalid = todo().withAction(0, Action.SQUASH);
        assertFalse(invalid.isValid());
        assertTrue(invalid.problems().get(0).contains("no commit above it"),
                invalid.problems().toString());
    }

    @Test
    void aFixupAtTheTopIsRejectedTheSameWay() {
        assertFalse(todo().withAction(0, Action.FIXUP).isValid());
    }

    @Test
    void aSquashBelowADroppedFirstCommitStillHasNothingToFoldInto() {
        // The subtle case: something precedes it, but that something is being discarded.
        RebaseTodo invalid = todo().withAction(0, Action.DROP).withAction(1, Action.SQUASH);
        assertFalse(invalid.isValid(), invalid.problems().toString());
    }

    @Test
    void aSquashBelowAKeptCommitIsFine() {
        assertTrue(todo().withAction(1, Action.SQUASH).isValid());
    }

    @Test
    void droppingEverythingIsRefused() {
        RebaseTodo all = todo()
                .withAction(0, Action.DROP).withAction(1, Action.DROP)
                .withAction(2, Action.DROP).withAction(3, Action.DROP);
        assertFalse(all.isValid());
        assertTrue(all.problems().stream().anyMatch(p -> p.contains("discard all")),
                all.problems().toString());
    }

    @Test
    void anEmptyPlanIsRefused() {
        RebaseTodo empty = RebaseTodo.fromOldestFirst(List.of());
        assertFalse(empty.isValid());
        assertTrue(empty.problems().get(0).contains("no commits"));
    }

    @Test
    void aMergeCommitCannotBeReplayed() throws Exception {
        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("side", null, true).isOk());
        Files.writeString(tempDir.resolve("side.txt"), "s", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("side.txt").isOk());
        assertTrue(service.commit("on side", false).isOk());
        assertTrue(service.checkoutBranch(trunk).isOk());
        assertTrue(new RepositoryCommands(service).merge("side", true).isOk());

        RebaseTodo withMerge = RebaseTodo.fromNewestFirst(collectLog());
        assertFalse(withMerge.isValid());
        assertTrue(withMerge.problems().get(0).contains("merge"), withMerge.problems().toString());
        // A cherry-pick plan picks the merge against a chosen parent instead of replaying it.
        assertTrue(withMerge.problems(true).isEmpty(), withMerge.problems(true).toString());
    }

    // ------------------------------------------------------------------
    // What the plan promises
    // ------------------------------------------------------------------

    @Test
    void summaryCountsEachOutcome() {
        RebaseTodo plan = todo()
                .withAction(1, Action.REWORD)
                .withAction(2, Action.FIXUP)
                .withAction(3, Action.DROP);
        assertEquals("1 kept, 1 reworded, 1 folded, 1 dropped", plan.summary());
    }

    @Test
    void resultingCountExcludesDroppedAndFoldedCommits() {
        assertEquals(4, todo().resultingCommitCount());
        assertEquals(3, todo().withAction(3, Action.DROP).resultingCommitCount());
        assertEquals(3, todo().withAction(2, Action.SQUASH).resultingCommitCount(),
                "a squashed commit joins the one above rather than making its own");
        assertEquals(2, todo().withAction(2, Action.SQUASH)
                .withAction(3, Action.DROP).resultingCommitCount());
    }

    @Test
    void aRewordCarriesItsNewMessage() {
        RebaseTodo plan = todo().withAction(0, Action.REWORD).withMessage(0, "better wording");
        assertEquals("better wording", plan.step(0).effectiveMessage());
        assertFalse(plan.isNoop());
    }

    @Test
    void aStepWithNoReplacementKeepsItsOwnMessage() {
        assertEquals("commit 1", todo().step(0).effectiveMessage());
    }

    @Test
    void anEditActionStillProducesACommit() {
        assertEquals(4, todo().withAction(1, Action.EDIT).resultingCommitCount());
        assertTrue(todo().withAction(1, Action.EDIT).isValid());
    }

    // ------------------------------------------------------------------
    // D8. A squash run folds into the step above it
    // ------------------------------------------------------------------

    private String hashOf(String subject) {
        return commits.stream().filter(c -> subject.equals(c.getSubject())).findFirst()
                .orElseThrow().getHash();
    }

    /**
     * "Squash 1 and 3" cannot mean what it says: the squash step folds into whatever is
     * directly above it, so commit 3 would fold into commit 2 - a commit the caller never
     * named - and the caller would be told it worked.
     */
    @Test
    void aNonContiguousSquashIsRefused() {
        assertNull(todo().squashing(java.util.Set.of(hashOf("commit 1"), hashOf("commit 3")), "one and three"));
    }

    @Test
    void aContiguousSquashIsAccepted() {
        RebaseTodo squashed =
                todo().squashing(java.util.Set.of(hashOf("commit 2"), hashOf("commit 3")), "two and three");

        assertNotNull(squashed);
        assertEquals(List.of(Action.PICK, Action.PICK, Action.SQUASH, Action.PICK),
                squashed.steps().stream().map(RebaseTodo.Step::action).toList());
        assertEquals("two and three", squashed.step(1).message(),
                "the combined message goes on the step that survives");
    }

    /** Three in a row is still one run. */
    @Test
    void aWholeContiguousRunIsAccepted() {
        RebaseTodo squashed = todo().squashing(
                java.util.Set.of(hashOf("commit 1"), hashOf("commit 2"), hashOf("commit 3")), null);

        assertNotNull(squashed);
        assertEquals(List.of(Action.PICK, Action.SQUASH, Action.SQUASH, Action.PICK),
                squashed.steps().stream().map(RebaseTodo.Step::action).toList());
    }

    // ------------------------------------------------------------------
    // D5. Where a squash run's message lives
    // ------------------------------------------------------------------

    /**
     * The cherry-pick flow set the message on the squash step itself. JGit reads a run's
     * message from the leader only, so the user's text was dropped and git's default
     * concatenation took its place; the CLI engine amended once per message-bearing step,
     * so the last one won. Same plan, different commits, and nothing said so.
     */
    @Test
    void aMessageLeftOnASquashStepMovesToTheSurvivor() {
        RebaseTodo plan = todo()
                .withAction(2, Action.SQUASH)
                .withMessage(2, "combined");

        RebaseTodo normalized = plan.normalized();

        assertNull(normalized.step(2).message(), "a folding step must not carry a message");
        assertEquals("combined", normalized.step(1).message(),
                "it belongs on the pick the run folds into");
    }

    /** The last of a run wins, which is what the CLI engine already produced. */
    @Test
    void theLastMessageInARunIsTheOneKept() {
        RebaseTodo normalized = todo()
                .withAction(2, Action.SQUASH).withMessage(2, "first")
                .withAction(3, Action.SQUASH).withMessage(3, "second")
                .normalized();

        assertEquals("second", normalized.step(1).message());
        assertNull(normalized.step(2).message());
        assertNull(normalized.step(3).message());
    }

    /**
     * A reword leader has its own message from its own prompt. Overwriting it with the
     * run's would silently drop one of the two.
     */
    @Test
    void arewordLeadersOwnMessageIsNotOverwritten() {
        RebaseTodo normalized = todo()
                .withAction(1, Action.REWORD).withMessage(1, "reworded")
                .withAction(2, Action.SQUASH).withMessage(2, "combined")
                .normalized();

        assertEquals("reworded", normalized.step(1).message());
        assertNull(normalized.step(2).message());
    }

    /**
     * A drop line between two squashes neither ends the run nor leads it: git treats it
     * as a no-op and JGit never sees it, so the second squash still folds into the pick.
     */
    @Test
    void aDropInsideASquashRunDoesNotSplitIt() {
        RebaseTodo normalized = todo()
                .withAction(1, Action.SQUASH).withMessage(1, "first")
                .withAction(2, Action.DROP)
                .withAction(3, Action.SQUASH).withMessage(3, "last")
                .normalized();

        assertEquals("last", normalized.step(0).message(), "the whole run's message lands on its pick");
        assertNull(normalized.step(1).message());
        assertNull(normalized.step(3).message());
    }

    @Test
    void aPlanWithNothingToMoveIsUnchanged() {
        RebaseTodo plan = todo().withAction(2, Action.SQUASH);

        assertSame(plan, plan.normalized(), "no message to move means no new plan");
    }

    // ------------------------------------------------------------------
    // One commit changed in place: reword, drop, move
    // ------------------------------------------------------------------

    /** Steps run oldest first, so step 0 is "commit 1" and step 3 is the tip. */
    private String hashOfStep(int index) {
        return todo().step(index).commit().getHash();
    }

    private static List<String> order(RebaseTodo plan) {
        return plan.steps().stream().map(step -> step.commit().getSubject()).toList();
    }

    @Test
    void rewordingChangesOneStepAndLeavesTheOthersPicked() {
        RebaseTodo plan = todo().rewording(hashOfStep(1), "a better message");

        assertNotNull(plan);
        assertEquals(Action.REWORD, plan.step(1).action());
        assertEquals("a better message", plan.step(1).message());
        for (int i : new int[]{0, 2, 3}) {
            assertEquals(Action.PICK, plan.step(i).action());
            assertNull(plan.step(i).message());
        }
        assertTrue(plan.isValid());
        assertFalse(plan.isNoop());
        assertFalse(plan.isReordered());
    }

    @Test
    void rewordingNeedsACommitOfThePlanAndAMessage() {
        assertNull(todo().rewording("0000000000000000000000000000000000000000", "message"),
                "a commit the plan does not replay");
        assertNull(todo().rewording(hashOfStep(1), "  "), "a commit cannot be reworded to nothing");
        assertNull(todo().rewording(hashOfStep(1), null));
    }

    @Test
    void droppingMarksOneStepAndKeepsTheRest() {
        RebaseTodo plan = todo().dropping(hashOfStep(2));

        assertNotNull(plan);
        assertEquals(Action.DROP, plan.step(2).action());
        assertEquals(3, plan.resultingCommitCount());
        assertTrue(plan.isValid());
        assertNull(todo().dropping("0000000000000000000000000000000000000000"));
    }

    /** Which is why the graph starts the range one commit lower to drop the newest. */
    @Test
    void droppingTheOnlyCommitOfAPlanLeavesNothingToReplay() {
        RebaseTodo single = RebaseTodo.fromNewestFirst(commits.subList(0, 1));

        RebaseTodo plan = single.dropping(single.step(0).commit().getHash());

        assertNotNull(plan);
        assertFalse(plan.isValid(), "a plan that drops every step is refused: " + plan.problems());
    }

    @Test
    void movingNewerSwapsACommitWithTheOneReplayedAfterIt() {
        RebaseTodo plan = todo().movingNewer(hashOfStep(1));

        assertNotNull(plan);
        assertEquals(List.of("commit 1", "commit 3", "commit 2", "commit 4"), order(plan));
        assertTrue(plan.isReordered());
        assertNull(todo().movingNewer(hashOfStep(3)), "the newest commit has nothing above it");
    }

    @Test
    void movingOlderSwapsACommitWithTheOneReplayedBeforeIt() {
        RebaseTodo plan = todo().movingOlder(hashOfStep(2));

        assertNotNull(plan);
        assertEquals(List.of("commit 1", "commit 3", "commit 2", "commit 4"), order(plan));
        assertNull(todo().movingOlder(hashOfStep(0)), "the oldest commit of the plan has nothing below it");
        assertNull(todo().movingOlder("0000000000000000000000000000000000000000"));
    }

    @Test
    void aCommitIsFoundByItsHash() {
        assertEquals(2, todo().indexOf(hashOfStep(2)));
        assertEquals(-1, todo().indexOf("0000000000000000000000000000000000000000"));
    }
}
