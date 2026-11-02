package com.jfx.git.ui;

import com.jfx.git.ui.DiffParser.Hunk;
import com.jfx.git.ui.HunkSelection.Patch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partial staging is where a Git client can quietly lose work: an unselected change that
 * is skipped rather than preserved vanishes from the index. Every case here is about the
 * unselected half surviving.
 */
class HunkSelectionTest {

    private static Hunk hunk(String diff) {
        return DiffParser.parse(diff).get(0);
    }

    /** Two independent edits inside one hunk: line 2 replaced, line 4 added. */
    private static final Hunk MIXED = hunk("""
            @@ -1,3 +1,4 @@
             keep1
            -old2
            +new2
             keep3
            +added4
            """);

    // ------------------------------------------------------------------
    // Whole hunks
    // ------------------------------------------------------------------

    @Test
    void stagingAWholeHunkSearchesTheBeforeSideAndWritesTheAfterSide() {
        Patch patch = HunkSelection.stage(MIXED);
        assertEquals(List.of("keep1", "old2", "keep3"), patch.search());
        assertEquals(List.of("keep1", "new2", "keep3", "added4"), patch.replacement());
        assertEquals(1, patch.hintLine(), "an unstaged diff positions by the old side");
    }

    @Test
    void unstagingAWholeHunkIsTheMirrorImage() {
        Patch patch = HunkSelection.unstage(MIXED);
        assertEquals(List.of("keep1", "new2", "keep3", "added4"), patch.search());
        assertEquals(List.of("keep1", "old2", "keep3"), patch.replacement());
        assertEquals(1, patch.hintLine(), "a staged diff positions by the new side");
    }

    @Test
    void stagingThenUnstagingReturnsToTheStartingPoint() {
        Patch stage = HunkSelection.stage(MIXED);
        Patch unstage = HunkSelection.unstage(MIXED);
        assertEquals(stage.search(), unstage.replacement());
        assertEquals(stage.replacement(), unstage.search());
    }

    // ------------------------------------------------------------------
    // Partial selections
    // ------------------------------------------------------------------

    @Test
    void anUnselectedAdditionStaysOutOfTheIndex() {
        // Take the replacement of line 2 (rows 1 and 2), leave the new line 4 (row 4).
        Patch patch = HunkSelection.stage(MIXED, Set.of(1, 2));
        assertEquals(List.of("keep1", "old2", "keep3"), patch.search());
        assertEquals(List.of("keep1", "new2", "keep3"), patch.replacement());
        assertFalse(patch.replacement().contains("added4"));
    }

    @Test
    void anUnselectedDeletionIsKeptRatherThanDropped() {
        // Take only the added line; the removal of "old2" must not happen.
        Patch patch = HunkSelection.stage(MIXED, Set.of(4));
        assertEquals(List.of("keep1", "old2", "keep3"), patch.search());
        assertEquals(List.of("keep1", "old2", "keep3", "added4"), patch.replacement(),
                "the unselected deletion must survive in the index");
    }

    @Test
    void selectingOnlyTheDeletionHalfOfAReplacement() {
        // Row 1 is "-old2" and row 2 is "+new2"; taking only the deletion removes the
        // line without putting its replacement in.
        Patch patch = HunkSelection.stage(MIXED, Set.of(1));
        assertEquals(List.of("keep1", "keep3"), patch.replacement());
    }

    @Test
    void selectingOnlyTheAdditionHalfOfAReplacement() {
        Patch patch = HunkSelection.stage(MIXED, Set.of(2));
        // Hunk order is preserved, and "-old2" comes before "+new2" in it, so the kept
        // deletion stays above the addition rather than being displaced by it.
        assertEquals(List.of("keep1", "old2", "new2", "keep3"), patch.replacement());
    }

    @Test
    void anUnselectedAdditionStaysStagedWhenUnstagingPartially() {
        // Undo only the line-2 replacement; "added4" remains staged.
        Patch patch = HunkSelection.unstage(MIXED, Set.of(1, 2));
        assertEquals(List.of("keep1", "new2", "keep3", "added4"), patch.search());
        assertEquals(List.of("keep1", "old2", "keep3", "added4"), patch.replacement());
    }

    @Test
    void selectingNothingChangesNothing() {
        Patch patch = HunkSelection.stage(MIXED, Set.of());
        assertEquals(patch.search(), patch.replacement());
        assertTrue(patch.isNoop());
    }

    @Test
    void aWholeHunkIsNeverANoop() {
        assertFalse(HunkSelection.stage(MIXED).isNoop());
        assertFalse(HunkSelection.unstage(MIXED).isNoop());
    }

    // ------------------------------------------------------------------
    // Shapes with no context
    // ------------------------------------------------------------------

    @Test
    void aPureInsertionSearchesForItsContextOnly() {
        Patch patch = HunkSelection.stage(hunk("@@ -1,1 +1,2 @@\n context\n+fresh\n"));
        assertEquals(List.of("context"), patch.search());
        assertEquals(List.of("context", "fresh"), patch.replacement());
    }

    @Test
    void aPureDeletionRemovesTheLine() {
        Patch patch = HunkSelection.stage(hunk("@@ -1,2 +1,1 @@\n context\n-gone\n"));
        assertEquals(List.of("context", "gone"), patch.search());
        assertEquals(List.of("context"), patch.replacement());
    }

    @Test
    void aHunkAtTheTopOfAnEmptyFileHasAnEmptySearch() {
        Patch patch = HunkSelection.stage(hunk("@@ -0,0 +1,2 @@\n+first\n+second\n"));
        assertTrue(patch.search().isEmpty(), "there is nothing in the index to find");
        assertEquals(List.of("first", "second"), patch.replacement());
    }

    @Test
    void changedLinesAreEnumeratedWithoutContext() {
        assertEquals(Set.of(1, 2, 4), HunkSelection.allChangedLines(MIXED));
    }

    // ------------------------------------------------------------------
    // The no-newline marker (D2/D3)
    // ------------------------------------------------------------------

    /** An edit to the last line of a file that has no final newline. */
    private static final DiffParser.Hunk NO_NEWLINE = DiffParser.parse(
            "@@ -1,2 +1,2 @@\n one\n-two\n\\ No newline at end of file\n+TWO\n\\ No newline at end of file\n")
            .get(0);

    /** Removing the final newline, which is the whole of the change. */
    private static final DiffParser.Hunk NEWLINE_REMOVED = DiffParser.parse(
            "@@ -1,2 +1,2 @@\n one\n-two\n+two\n\\ No newline at end of file\n")
            .get(0);

    @Test
    void theMarkerNeverReachesTheBlocks() {
        var staged = HunkSelection.stage(NO_NEWLINE);

        assertEquals(List.of("one", "two"), staged.search());
        assertEquals(List.of("one", "TWO"), staged.replacement());
    }

    @Test
    void unstagingLeavesTheMarkerOutToo() {
        var unstaged = HunkSelection.unstage(NO_NEWLINE);

        assertEquals(List.of("one", "TWO"), unstaged.search());
        assertEquals(List.of("one", "two"), unstaged.replacement());
    }

    @Test
    void theResultingFinalNewlineIsCarried() {
        assertEquals(Boolean.FALSE, HunkSelection.stage(NO_NEWLINE).endsWithNewline());
        assertEquals(Boolean.FALSE, HunkSelection.unstage(NO_NEWLINE).endsWithNewline());
    }

    /**
     * Identical blocks are not a no-op here: the entire change is the missing newline, and
     * skipping it would report "nothing selected" for an edit the user can plainly see.
     */
    @Test
    void removingOnlyTheFinalNewlineIsNotANoop() {
        var patch = HunkSelection.stage(NEWLINE_REMOVED);

        assertEquals(patch.search(), patch.replacement(), "the lines themselves are unchanged");
        assertEquals(Boolean.FALSE, patch.endsWithNewline());
        assertFalse(patch.isNoop(), "the change is the newline, and it must not be skipped");
    }

    /** Unstaging that edit puts the newline back. */
    @Test
    void unstagingANewlineRemovalRestoresIt() {
        var patch = HunkSelection.unstage(NEWLINE_REMOVED);

        assertEquals(Boolean.TRUE, patch.endsWithNewline());
        assertFalse(patch.isNoop());
    }

    /** A hunk that says nothing about the end of the file leaves it alone. */
    @Test
    void anOrdinaryHunkSaysNothingAboutTheFinalNewline() {
        assertNull(HunkSelection.stage(MIXED).endsWithNewline());
        assertNull(HunkSelection.unstage(MIXED).endsWithNewline());
    }

    /** S1. Each block line carries the ending the diff showed for it. */
    @Test
    void blocksCarryEachLinesOwnCr() {
        Patch patch = HunkSelection.stage(hunk("@@ -1,2 +1,3 @@\n a\r\n+new\n b\r\n"));

        assertEquals(List.of("a\r", "b\r"), patch.search());
        assertEquals(List.of("a\r", "new", "b\r"), patch.replacement());
    }

    /** S1. An EOL-only change is not "nothing selected". */
    @Test
    void anEolOnlyChangeIsNotANoop() {
        Patch patch = HunkSelection.stage(hunk("@@ -1 +1 @@\n-a\r\n+a\n"));

        assertFalse(patch.isNoop());
        assertEquals(List.of("a\r"), patch.search());
        assertEquals(List.of("a"), patch.replacement());
    }

    /** Discarding lines finds the working tree's side and restores only what was chosen. */
    @Test
    void discardingOneLineKeepsTheOtherEdit() {
        // Lines of MIXED: 0 keep1, 1 -old2, 2 +new2, 3 keep3, 4 +added4.
        Patch patch = HunkSelection.discard(MIXED, Set.of(4));

        assertEquals(List.of("keep1", "new2", "keep3", "added4"), patch.search());
        assertEquals(List.of("keep1", "new2", "keep3"), patch.replacement(),
                "the unchosen replacement of line 2 stays; only the added line goes");
        assertEquals(1, patch.hintLine());
    }

    /** S3/S12. The preamble decides removal and the mode of a path new to the index. */
    @Test
    void theIndexEditLearnsTheWholeFileFromThePreamble() {
        Patch patch = HunkSelection.stage(hunk("@@ -0,0 +1 @@\n+#!/bin/sh\n"));
        DiffParser.Summary added = DiffParser.summarize(
                "diff --git a/run b/run\nnew file mode 100755\n@@ -0,0 +1 @@\n+#!/bin/sh\n");
        DiffParser.Summary deleted = DiffParser.summarize(
                "diff --git a/run b/run\ndeleted file mode 100755\n@@ -1 +0,0 @@\n-#!/bin/sh\n");

        var staged = patch.toIndexEdit(com.jfx.git.core.TextEncoding.GUESS, null, added, true);
        assertEquals("100755", staged.createModeOrRegular());
        assertFalse(staged.removesPath(), "staging an addition creates the path");

        assertTrue(patch.toIndexEdit(com.jfx.git.core.TextEncoding.GUESS, null, deleted, true)
                .removesPath(), "staging all of a deletion removes it");
        assertTrue(patch.toIndexEdit(com.jfx.git.core.TextEncoding.GUESS, null, added, false)
                .removesPath(), "unstaging all of an addition removes it");
        assertEquals("100755", patch.toIndexEdit(com.jfx.git.core.TextEncoding.GUESS, null, deleted, false)
                .createModeOrRegular(), "unstaging a deletion recreates the old mode");
    }

    /** Staging only the deletion, with the addition left for later. */
    @Test
    void aPartialSelectionStillReportsTheRightFinalNewline() {
        var patch = HunkSelection.stage(NO_NEWLINE, java.util.Set.of(1));

        assertEquals(List.of("one", "two"), patch.search());
        assertEquals(List.of("one"), patch.replacement());
        assertEquals(Boolean.TRUE, patch.endsWithNewline(),
                "'one' is followed by a line, not by a marker, so it keeps its newline");
    }
}
