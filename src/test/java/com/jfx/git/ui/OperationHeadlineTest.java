package com.jfx.git.ui;

import com.jfx.git.model.OperationState;
import com.jfx.git.model.OperationState.Kind;
import com.jfx.git.ui.OperationHeadline.Ref;
import com.jfx.git.ui.OperationHeadline.RefKind;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words the conflict panel and the graph's strip put to a stopped operation, for each
 * kind, with names and with only hashes to go on.
 */
class OperationHeadlineTest {

    private static final String HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";

    private static OperationState state(Kind kind, String incoming, String target) {
        return new OperationState(kind, false, null, false, incoming, target, 0, 0);
    }

    @Test
    void aMergeNamesBothBranches() {
        var headline = OperationHeadline.of(state(Kind.MERGING, "feature/simple", "main"));

        assertEquals("Merging feature/simple into main", headline.text());
        assertEquals(RefKind.LOCAL, headline.subject().kind());
        assertEquals(RefKind.HEAD, headline.target().kind(), "where a merge lands is the branch HEAD is on");
    }

    @Test
    void aRebaseNamesItsBranchItsOntoAndItsStep() {
        var rebase = new OperationState(Kind.REBASING, false, HASH, false, "feature", "main", 3, 7);

        assertEquals("Rebasing feature onto main (step 3 of 7)", OperationHeadline.of(rebase).text());
        assertEquals(RefKind.LOCAL, OperationHeadline.of(rebase).target().kind(),
                "a rebase replays onto a branch HEAD is not on");
    }

    @Test
    void aRebaseWithNoCountersLeavesTheStepOut() {
        var rebase = new OperationState(Kind.REBASING_APPLY, false, null, false, "feature", "main", 0, 0);

        assertEquals("Rebasing feature onto main", OperationHeadline.of(rebase).text());
    }

    @Test
    void aCherryPickNamesItsCommitByShortHash() {
        var headline = OperationHeadline.of(state(Kind.CHERRY_PICKING, HASH, "main"));

        assertEquals("Cherry-picking a1b2c3d onto main", headline.text());
        assertEquals(RefKind.COMMIT, headline.subject().kind());
        assertEquals("ref-chip-commit", headline.subject().styleClass());
    }

    @Test
    void aRevertNamesOnlyItsCommit() {
        assertEquals("Reverting a1b2c3d", OperationHeadline.of(state(Kind.REVERTING, HASH, "main")).text());
    }

    @Test
    void aPatchSeriesNamesWhereItLandsAndWhichPatch() {
        var am = new OperationState(Kind.APPLYING_MAILBOX, false, null, false, null, "main", 2, 5);

        assertEquals("Applying patches onto main (patch 2 of 5)", OperationHeadline.of(am).text());
    }

    @Test
    void hashesFallBackWhereNoNameIsKnown() {
        assertEquals("Merging a1b2c3d into main", OperationHeadline.of(state(Kind.MERGING, HASH, "main")).text());
        assertEquals("Merging a1b2c3d into a1b2c3d",
                OperationHeadline.of(state(Kind.MERGING, HASH, HASH)).text(), "a detached HEAD");
        assertEquals("Rebasing feature", OperationHeadline.of(
                new OperationState(Kind.REBASING, false, null, false, "feature", null, 0, 0)).text(),
                "nothing recorded to say where");
    }

    @Test
    void refsResolvedByThePanelAreUsedAsGiven() {
        var headline = OperationHeadline.of(state(Kind.MERGING, HASH, "main"),
                new Ref("origin/feature", RefKind.REMOTE), new Ref("main", RefKind.HEAD));

        assertEquals("Merging origin/feature into main", headline.text());
        assertEquals("ref-chip-remote", headline.subject().styleClass());
    }

    @Test
    void nothingStoppedHasNoHeadline() {
        assertNull(OperationHeadline.of(OperationState.none()));
        assertNull(OperationHeadline.of(OperationState.of(Kind.BISECTING)));
    }

    // ------------------------------------------------------------------
    // The sentences around it
    // ------------------------------------------------------------------

    @Test
    void theAdviceSaysHowManyConflictAndHowToFinish() {
        assertEquals("1 conflicting file - resolve it and mark it resolved, then commit the merge.",
                OperationHeadline.advice(state(Kind.MERGING, "feature", "main"), 1));
        assertEquals("Stopped at a1b2c3d - 2 conflicting files - resolve each one and mark them resolved,"
                        + " then continue the rebase.",
                OperationHeadline.advice(new OperationState(Kind.REBASING, false, HASH, false,
                        "feature", "main", 1, 1), 2));
        assertEquals("All conflicts are resolved - commit the merge.",
                OperationHeadline.advice(state(Kind.MERGING, "feature", "main"), 0));
        assertTrue(OperationHeadline.advice(state(Kind.CHERRY_PICKING, HASH, "main"), 0)
                .startsWith("Nothing is conflicting."));
    }

    @Test
    void theGraphStripSaysWhatStoppedAndWhy() {
        var merge = state(Kind.MERGING, "feature", "main");
        assertEquals("Merge of feature into main stopped: 2 conflicting files",
                OperationHeadline.graphBanner(merge, OperationHeadline.of(merge), 2));

        var rebase = new OperationState(Kind.REBASING, true, HASH, true, "feature", "main", 2, 4);
        assertEquals("Rebase of feature onto main stopped at a1b2c3d: amend the commit, then continue",
                OperationHeadline.graphBanner(rebase, OperationHeadline.of(rebase), 0));

        assertEquals("Merge of feature into main stopped: all conflicts resolved - commit the merge",
                OperationHeadline.graphBanner(merge, OperationHeadline.of(merge), 0));
    }

    @Test
    void theLastConflictResolvedSaysHowToFinish() {
        assertEquals("All conflicts resolved - commit the merge",
                OperationHeadline.allResolved(state(Kind.MERGING, "feature", "main")));
        assertEquals("All conflicts resolved - continue the rebase",
                OperationHeadline.allResolved(state(Kind.REBASING, "feature", "main")));
        assertEquals("All conflicts resolved", OperationHeadline.allResolved(OperationState.none()),
                "a stash that conflicted has nothing to finish");
    }

    @Test
    void theNextConflictIsTheOneAfterInPathOrderWrappingRound() {
        List<String> left = List.of("a.txt", "c.txt", "e.txt");
        assertEquals("c.txt", RightPanelManager.nextConflict("b.txt", left));
        assertEquals("a.txt", RightPanelManager.nextConflict("z.txt", left));
    }
}
