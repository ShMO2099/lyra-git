package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActionStackTest {

    private static ReflogItem entry(int index, String hash, String comment) {
        return new ReflogItem(index, null, hash, comment, "Tester", Instant.EPOCH);
    }

    /** Newest first, which is the order the reflog itself is in. */
    private static List<ReflogItem> reflog(String... hashAndComment) {
        List<ReflogItem> items = new ArrayList<>();
        for (int i = 0; i < hashAndComment.length; i += 2) {
            items.add(entry(i / 2, hashAndComment[i], hashAndComment[i + 1]));
        }
        return items;
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    @Test
    void checkoutLabelNamesTheDestination() {
        assertEquals("checkout feature",
                ActionStack.labelFromReflog("checkout: moving from main to feature"));
    }

    @Test
    void commitLabelCarriesTheSubject() {
        assertEquals("commit add the parser",
                ActionStack.labelFromReflog("commit: add the parser"));
    }

    @Test
    void amendIsDistinguishedFromAnOrdinaryCommit() {
        assertEquals("amend commit", ActionStack.labelFromReflog("commit (amend): fix typo"));
    }

    @Test
    void rebaseCollapsesToOneWord() {
        assertEquals("rebase",
                ActionStack.labelFromReflog("rebase (finish): returning to refs/heads/main"));
    }

    @Test
    void unknownCommentsStillProduceSomethingReadable() {
        assertEquals("last action", ActionStack.labelFromReflog(""));
        assertEquals("last action", ActionStack.labelFromReflog(null));
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    @Test
    void freshStackHasNothingToUndoOrRedo() {
        ActionStack stack = new ActionStack();
        assertFalse(stack.canUndo());
        assertFalse(stack.canRedo());
        assertEquals("Nothing to undo", stack.undoDescription());
    }

    @Test
    void aSingleReflogEntryLeavesNowhereToGoBackTo() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog("aaa", "commit (initial): first"));
        assertFalse(stack.canUndo(), "there is no earlier state the reflog can name");
    }

    @Test
    void undoWalksBackOneStepAtATime() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));

        assertTrue(stack.canUndo());
        assertEquals("Undo: commit third", stack.undoDescription());
        assertEquals("bbb", stack.undoMove().resetHash());

        stack.undoApplied();
        assertEquals("Undo: commit second", stack.undoDescription());
        assertEquals("aaa", stack.undoMove().resetHash());

        stack.undoApplied();
        assertFalse(stack.canUndo(), "reached the start of recorded history");
    }

    @Test
    void redoRetracesTheStepsUndoTook() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));

        stack.undoApplied();
        assertTrue(stack.canRedo());
        assertEquals("Redo: commit third", stack.redoDescription());
        assertEquals("ccc", stack.redoMove().resetHash());

        stack.redoApplied();
        assertFalse(stack.canRedo());
        assertEquals("bbb", stack.undoMove().resetHash());
    }

    /**
     * An undo is itself a reset and lands in the reflog. Rebuilding the snapshot from
     * that would make the next undo step back onto the undo, so the refresh triggered by
     * navigating is ignored.
     */
    @Test
    void theRefreshCausedByAnUndoDoesNotResetTheCursor() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));

        stack.undoApplied();
        assertEquals(1, stack.cursor());

        boolean replaced = stack.refreshFrom(reflog(
                "bbb", "reset: moving to bbb",
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));

        assertFalse(replaced, "the reset this class issued is not a user action");
        assertEquals(1, stack.cursor(), "cursor must survive its own reset");
        assertEquals("aaa", stack.undoMove().resetHash(), "next undo continues backwards");
    }

    /**
     * Refreshes come from elsewhere too - a background fetch, a file-system change - so
     * one can arrive after the undo but before git's reflog entry is readable, still
     * showing the state the undo moved away from. Absorbing "the next refresh" by count
     * consumed that one and then folded the undo's own entry into a rebuilt snapshot with
     * the cursor back at zero, which silently broke the redo chain.
     */
    @Test
    void aRefreshThatRacedAheadOfTheReflogWriteDoesNotCorruptTheCursor() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));

        stack.undoApplied();

        // Still the pre-undo reflog: this read started before git wrote the reset entry.
        boolean staleReplaced = stack.refreshFrom(reflog(
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));
        // And now the undo's own entry arrives.
        boolean ownReplaced = stack.refreshFrom(reflog(
                "bbb", "reset: moving to bbb",
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));

        assertFalse(staleReplaced, "a read that predates the reset is not a user action either");
        assertFalse(ownReplaced, "and neither is the reset itself");
        assertEquals(1, stack.cursor(), "the cursor must survive both");
        assertTrue(stack.canRedo(), "the redo chain must survive both");
        assertEquals("aaa", stack.undoMove().resetHash(), "next undo continues backwards");
    }

    @Test
    void aRealUserActionReplacesTheSnapshotAndDropsRedo() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));

        stack.undoApplied();
        // Consume the navigation refresh, as the UI would.
        stack.refreshFrom(reflog("bbb", "reset: moving to bbb"));
        assertTrue(stack.canRedo());

        boolean replaced = stack.refreshFrom(reflog(
                "ddd", "commit: a new direction",
                "bbb", "reset: moving to bbb",
                "ccc", "commit: third"));

        assertTrue(replaced);
        assertFalse(stack.canRedo(), "a new action makes the forward history unreachable");
        assertEquals("Undo: commit a new direction", stack.undoDescription());
    }

    // ------------------------------------------------------------------
    // Checkout moves
    // ------------------------------------------------------------------

    @Test
    void checkoutEndsAreReadFromTheComment() {
        assertArrayEquals(new String[] {"main", "feature"},
                ActionStack.checkoutEnds("checkout: moving from main to feature"));
    }

    @Test
    void aBranchNamedLikeThePrepositionDoesNotConfuseTheParser() {
        assertArrayEquals(new String[] {"main", "to-do"},
                ActionStack.checkoutEnds("checkout: moving from main to to-do"));
    }

    @Test
    void nonCheckoutCommentsHaveNoEnds() {
        assertNull(ActionStack.checkoutEnds("commit: add the parser"));
        assertNull(ActionStack.checkoutEnds("rebase (finish): returning to refs/heads/main"));
        assertNull(ActionStack.checkoutEnds(null));
    }

    /**
     * The bug this pins: resetting to the earlier hash would move <em>feature</em> back
     * onto main's commit, stranding the work that was on it. Undoing a checkout has to
     * check the other branch out instead.
     */
    @Test
    void undoingACheckoutChecksTheOtherBranchOutRatherThanResetting() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "bbb", "checkout: moving from main to feature",
                "aaa", "commit: work on main"));

        ActionStack.Move move = stack.undoMove();
        assertTrue(move.isCheckout());
        assertEquals("main", move.checkoutRef());
        assertNull(move.resetHash(), "a checkout must not be undone by moving a branch");
    }

    @Test
    void redoingACheckoutReturnsToTheBranchItArrivedAt() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "bbb", "checkout: moving from main to feature",
                "aaa", "commit: work on main"));
        stack.undoApplied();

        ActionStack.Move move = stack.redoMove();
        assertTrue(move.isCheckout());
        assertEquals("feature", move.checkoutRef());
    }

    @Test
    void checkingOutADetachedCommitIsUndoneByNameAndRedoneByHash() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "bbb", "checkout: moving from main to 1a2b3c4",
                "aaa", "commit: work on main"));

        assertEquals("main", stack.undoMove().checkoutRef());
        stack.undoApplied();
        assertEquals("1a2b3c4", stack.redoMove().checkoutRef(),
                "returning to a detached head means checking that commit out again");
    }

    @Test
    void ordinaryMovesAreStillUndoneByResetting() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "ccc", "commit: third",
                "bbb", "commit: second"));

        ActionStack.Move move = stack.undoMove();
        assertFalse(move.isCheckout());
        assertEquals("bbb", move.resetHash());
    }

    @Test
    void resetClearsEverything() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog("bbb", "commit: second", "aaa", "commit: first"));
        assertTrue(stack.canUndo());

        stack.reset();
        assertFalse(stack.canUndo());
        assertEquals(0, stack.depth());
    }

    @Test
    void upcomingStepsListWhatUndoWouldWalkThrough() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "ddd", "commit: fourth",
                "ccc", "commit: third",
                "bbb", "commit: second",
                "aaa", "commit (initial): first"));

        var steps = stack.upcomingUndoSteps(10);
        assertEquals(3, steps.size(), "the oldest state is a destination, not an undoable action");
        assertEquals("commit fourth", steps.get(0).label());
        assertEquals("commit second", steps.get(2).label());
    }

    // ------------------------------------------------------------------
    // O2: a rebase is one step; a move that moved nothing is none
    // ------------------------------------------------------------------

    /**
     * After {@code git rebase main}, undo used to take four steps: a no-op, a half-rebased
     * branch, the branch parked on main's tip, and only then the original. One undo now
     * returns to the pre-rebase tip.
     */
    @Test
    void aWholeRebaseIsOneUndoStep() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "p2", "rebase (finish): returning to refs/heads/feature",
                "p2", "rebase (pick): second change",
                "p1", "rebase (pick): first change",
                "mm", "rebase (start): checkout main",
                "ff", "commit: second change",
                "ee", "commit: first change"));

        assertEquals("Undo: rebase", stack.undoDescription());
        assertEquals("ff", stack.undoMove().resetHash(), "one undo returns to the pre-rebase tip");
        assertEquals(3, stack.depth());
    }

    /** The same for the reflog JGit writes, whose start is a plain checkout of a hash. */
    @Test
    void aJGitRebaseIsOneUndoStepToo() {
        String onto = "0123456789abcdef0123456789abcdef01234567";
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "p2", "rebase finished: returning to refs/heads/feature",
                "p2", "rebase: second change",
                "p1", "rebase: first change",
                onto, "checkout: moving from feature to " + onto,
                "ff", "commit: second change"));

        assertEquals("ff", stack.undoMove().resetHash());
        assertEquals(2, stack.depth());
    }

    /** An interactive rebase, old-style {@code rebase -i} labels included. */
    @Test
    void anInteractiveRebaseIsOneUndoStep() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "r2", "rebase -i (finish): returning to refs/heads/main",
                "r2", "rebase -i (squash): combined",
                "r1", "rebase -i (reword): renamed",
                "bb", "rebase -i (start): checkout bb",
                "cc", "commit: third"));

        assertEquals("cc", stack.undoMove().resetHash());
    }

    /**
     * {@code git stash} writes "reset: moving to HEAD" with the hash unchanged, and an
     * aborted rebase returns to where it began. Undoing either did nothing and said it
     * had succeeded.
     */
    @Test
    void movesThatMovedNothingAreNotUndoSteps() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "cc", "reset: moving to HEAD",
                "cc", "rebase (abort): returning to refs/heads/main",
                "p1", "rebase (pick): first change",
                "mm", "rebase (start): checkout other",
                "cc", "commit: third",
                "bb", "commit: second"));

        assertEquals("Undo: commit third", stack.undoDescription());
        assertEquals("bb", stack.undoMove().resetHash());
    }

    /** Switching between two branches on the same commit moves no hash, and is still an action. */
    @Test
    void aCheckoutBetweenBranchesOnOneCommitIsKept() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "aa", "checkout: moving from main to feature",
                "aa", "commit: first"));

        assertTrue(stack.undoMove().isCheckout());
        assertEquals("main", stack.undoMove().checkoutRef());
    }

    // ------------------------------------------------------------------
    // O12: redo goes back to the commit, not to text re-resolved now
    // ------------------------------------------------------------------

    /**
     * The reflog records what was typed. Redoing {@code checkout HEAD~1} by re-resolving
     * "HEAD~1" after the undo lands one commit further back than the original move.
     */
    @Test
    void redoingACheckoutOfARelativeRefGoesToTheCommitItReached() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "bbb", "checkout: moving from main to HEAD~1",
                "ccc", "commit: work on main"));
        stack.undoApplied();

        ActionStack.Move redo = stack.redoMove();

        assertEquals("HEAD~1", redo.checkoutRef());
        assertEquals("bbb", redo.checkoutTarget(List.of("main", "feature")),
                "not a local branch, so the recorded commit");
    }

    @Test
    void redoingACheckoutOfALocalBranchStillGoesByName() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "bbb", "checkout: moving from main to feature",
                "aaa", "commit: work on main"));
        stack.undoApplied();

        assertEquals("feature", stack.redoMove().checkoutTarget(List.of("main", "feature")),
                "a branch is re-entered wherever it points now");
    }

    @Test
    void undoingACheckoutFromARemoteRefGoesByTheRecordedCommit() {
        ActionStack stack = new ActionStack();
        stack.refreshFrom(reflog(
                "bbb", "checkout: moving from origin/x to main",
                "aaa", "checkout: moving from main to origin/x"));

        assertEquals("aaa", stack.undoMove().checkoutTarget(List.of("main")));
    }

    /** Against a real repository: one undo after a real rebase restores the branch. */
    @Test
    void aRealRebaseUndoesInOneStep(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(dir.toFile())
                .setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "T");
            config.setString("user", null, "email", "t@example.com");
            config.save();
        }
        GitService service = new GitService();
        assertTrue(service.openRepository(dir.toFile()));
        try {
            commitFile(service, dir, "base.txt", "base");
            assertTrue(service.createBranch("feature", null, true).isOk());
            commitFile(service, dir, "f1.txt", "f1");
            commitFile(service, dir, "f2.txt", "f2");
            String beforeRebase = service.getHeadHash();
            assertTrue(service.checkoutBranch("main").isOk());
            commitFile(service, dir, "m.txt", "m");
            assertTrue(service.checkoutBranch("feature").isOk());
            assertTrue(service.rebase("main").isOk());

            ActionStack stack = new ActionStack();
            stack.refreshFrom(service.getReflog(ActionStack.DEPTH));

            assertEquals(beforeRebase, stack.undoMove().resetHash(),
                    () -> "one undo returns to the pre-rebase tip: " + service.getReflog(10));
        } finally {
            service.close();
        }
    }

    private static void commitFile(GitService service, java.nio.file.Path dir, String file, String content)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve(file), content);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit("add " + file, false).isOk());
    }
}
