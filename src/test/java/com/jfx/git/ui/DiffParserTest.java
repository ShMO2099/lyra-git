package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the hunk splitting and line-number bookkeeping behind the diff viewer. */
class DiffParserTest {

    @Test
    void emptyInputProducesNoHunks() {
        assertTrue(DiffParser.parse("").isEmpty());
        assertTrue(DiffParser.parse(null).isEmpty());
    }

    @Test
    void fileHeadersBeforeTheFirstHunkAreIgnored() {
        String diff = """
                diff --git a/a.txt b/a.txt
                index 1234567..89abcde 100644
                --- a/a.txt
                +++ b/a.txt
                @@ -1,2 +1,2 @@
                 context
                -old
                +new
                """;
        List<DiffParser.Hunk> hunks = DiffParser.parse(diff);
        assertEquals(1, hunks.size());
        assertEquals(3, hunks.get(0).lines().size());
    }

    @Test
    void parsesHunkRanges() {
        DiffParser.Hunk hunk = DiffParser.parse("@@ -397,6 +397,11 @@\n context\n").get(0);
        assertEquals(397, hunk.oldStart());
        assertEquals(6, hunk.oldCount());
        assertEquals(397, hunk.newStart());
        assertEquals(11, hunk.newCount());
    }

    /** A range without a comma means a single line. */
    @Test
    void omittedCountDefaultsToOne() {
        DiffParser.Hunk hunk = DiffParser.parse("@@ -5 +5 @@\n context\n").get(0);
        assertEquals(5, hunk.oldStart());
        assertEquals(1, hunk.oldCount());
        assertEquals(1, hunk.newCount());
    }

    @Test
    void assignsLineNumbersPerSide() {
        String diff = """
                @@ -10,3 +10,4 @@
                 keep
                -removed
                +added one
                +added two
                 tail
                """;
        List<DiffParser.Line> lines = DiffParser.parse(diff).get(0).lines();

        assertEquals(5, lines.size());

        // Context advances both sides.
        assertEquals(10, lines.get(0).oldLine());
        assertEquals(10, lines.get(0).newLine());

        // A deletion only consumes an old-side number.
        assertEquals(11, lines.get(1).oldLine());
        assertEquals(null, lines.get(1).newLine());

        // Additions only consume new-side numbers.
        assertEquals(null, lines.get(2).oldLine());
        assertEquals(11, lines.get(2).newLine());
        assertEquals(12, lines.get(3).newLine());

        // Trailing context resumes from both counters.
        assertEquals(12, lines.get(4).oldLine());
        assertEquals(13, lines.get(4).newLine());
    }

    @Test
    void stripsTheLeadingMarkerFromLineText() {
        List<DiffParser.Line> lines = DiffParser.parse("@@ -1,2 +1,2 @@\n context\n-gone\n+here\n")
                .get(0).lines();
        assertEquals("context", lines.get(0).text());
        assertEquals("gone", lines.get(1).text());
        assertEquals("here", lines.get(2).text());
    }

    @Test
    void splitsMultipleHunks() {
        String diff = """
                @@ -1,2 +1,2 @@
                 a
                -b
                +c
                @@ -20,2 +20,2 @@
                 d
                -e
                +f
                """;
        List<DiffParser.Hunk> hunks = DiffParser.parse(diff);
        assertEquals(2, hunks.size());
        assertEquals(1, hunks.get(0).newStart());
        assertEquals(20, hunks.get(1).newStart());
    }

    /** The "before" side that a hunk revert writes back: context plus deletions. */
    @Test
    void oldLinesExcludeAdditions() {
        String diff = """
                @@ -1,3 +1,3 @@
                 keep
                -removed
                +added
                 tail
                """;
        assertEquals(List.of("keep", "removed", "tail"), DiffParser.parse(diff).get(0).oldLines());
    }

    /** The "after" side a hunk revert searches for: context plus additions. */
    @Test
    void newLinesExcludeDeletions() {
        String diff = """
                @@ -1,3 +1,3 @@
                 keep
                -removed
                +added
                 tail
                """;
        assertEquals(List.of("keep", "added", "tail"), DiffParser.parse(diff).get(0).newLines());
    }

    /** A pure-addition hunk still yields the surrounding context on the old side. */
    @Test
    void pureAdditionSidesDiffer() {
        String diff = """
                @@ -1,2 +1,3 @@
                 first
                +inserted
                 second
                """;
        DiffParser.Hunk hunk = DiffParser.parse(diff).get(0);
        assertEquals(List.of("first", "second"), hunk.oldLines());
        assertEquals(List.of("first", "inserted", "second"), hunk.newLines());
    }

    @Test
    void noNewlineMarkerDoesNotConsumeALineNumber() {
        String diff = """
                @@ -1,1 +1,1 @@
                -old
                \\ No newline at end of file
                +new
                """;
        List<DiffParser.Line> lines = DiffParser.parse(diff).get(0).lines();
        assertEquals(null, lines.get(1).oldLine());
        assertEquals(null, lines.get(1).newLine());
        assertEquals(1, lines.get(2).newLine());
    }

    @Test
    void malformedHeaderStillYieldsAHunk() {
        List<DiffParser.Hunk> hunks = DiffParser.parse("@@ not a range @@\n context\n");
        assertEquals(1, hunks.size());
        assertEquals(0, hunks.get(0).newStart());
    }

    // ------------------------------------------------------------------
    // The no-newline marker (D2)
    // ------------------------------------------------------------------

    private static final String NO_NEWLINE = "@@ -1,2 +1,2 @@\n one\n-two\n\\ No newline at end of file\n+TWO\n\\ No newline at end of file\n";

    /**
     * The marker describes the line above it; it is not a line of the file. Carried into
     * the reconstructed sides it becomes a line the patcher goes looking for and never
     * finds, which is how every no-newline file lost hunk staging.
     */
    @Test
    void theNoNewlineMarkerIsNotContent() {
        var hunk = DiffParser.parse(NO_NEWLINE).get(0);

        assertEquals(List.of("one", "two"), hunk.oldLines());
        assertEquals(List.of("one", "TWO"), hunk.newLines());
    }

    @Test
    void theMarkerIsRecognisedAndTheLinesAroundItAreNot() {
        var lines = DiffParser.parse(NO_NEWLINE).get(0).lines();

        assertEquals(List.of(false, false, true, false, true),
                lines.stream().map(DiffParser.Line::isNoNewlineMarker).toList());
    }

    /**
     * A file line that genuinely begins with a backslash arrives space-prefixed like any
     * context line, so text alone would misread it. Its line numbers are what tell them
     * apart.
     */
    @Test
    void aFileLineStartingWithABackslashIsNotAMarker() {
        String diff = "@@ -1,2 +1,2 @@\n \\path\\to\\thing\n-two\n+TWO\n";

        var hunk = DiffParser.parse(diff).get(0);

        assertFalse(hunk.lines().get(0).isNoNewlineMarker());
        assertEquals(List.of("\\path\\to\\thing", "two"), hunk.oldLines());
    }

    // ------------------------------------------------------------------
    // What the marker says about the final newline (D3)
    // ------------------------------------------------------------------

    @Test
    void bothSidesLackAFinalNewlineWhenBothAreMarked() {
        var hunk = DiffParser.parse(NO_NEWLINE).get(0);

        assertEquals(Boolean.FALSE, hunk.oldEndsWithNewline());
        assertEquals(Boolean.FALSE, hunk.newEndsWithNewline());
    }

    /** Removing the final newline: the old side had one, the new side does not. */
    @Test
    void onlyTheMarkedSideLosesItsFinalNewline() {
        String diff = "@@ -1,2 +1,2 @@\n one\n-two\n+two\n\\ No newline at end of file\n";

        var hunk = DiffParser.parse(diff).get(0);

        assertEquals(Boolean.TRUE, hunk.oldEndsWithNewline());
        assertEquals(Boolean.FALSE, hunk.newEndsWithNewline());
    }

    /** Adding one: the marker sits on the old side instead. */
    @Test
    void theOtherDirectionIsReadTheSameWay() {
        String diff = "@@ -1,2 +1,2 @@\n one\n-two\n\\ No newline at end of file\n+two\n";

        var hunk = DiffParser.parse(diff).get(0);

        assertEquals(Boolean.FALSE, hunk.oldEndsWithNewline());
        assertEquals(Boolean.TRUE, hunk.newEndsWithNewline());
    }

    /**
     * With no marker at all the hunk may simply end mid-file, so it says nothing rather
     * than "yes" - a caller that assumed yes would append a newline to the middle of a
     * file it never touched.
     */
    @Test
    void aHunkWithNoMarkerSaysNothingAboutTheFinalNewline() {
        var hunk = DiffParser.parse("@@ -1,2 +1,2 @@\n one\n-two\n+TWO\n").get(0);

        assertNull(hunk.oldEndsWithNewline());
        assertNull(hunk.newEndsWithNewline());
    }

    // ------------------------------------------------------------------
    // S1. A line's CR is recorded, not lost
    // ------------------------------------------------------------------

    @Test
    void eachLineRecordsItsOwnCr() {
        var lines = DiffParser.parse("@@ -1,2 +1,3 @@\r\n a\r\n+new\n b\r\n").get(0).lines();

        assertEquals(List.of("a", "new", "b"), lines.stream().map(DiffParser.Line::text).toList(),
                "the text never shows a CR");
        assertEquals(List.of(true, false, true), lines.stream().map(DiffParser.Line::cr).toList());
        assertEquals(List.of("a\r", "new", "b\r"), lines.stream().map(DiffParser.Line::content).toList());
    }

    @Test
    void anEolOnlyChangeIsTwoDifferentLines() {
        var lines = DiffParser.parse("@@ -1 +1 @@\n-a\r\n+a\n").get(0).lines();

        assertEquals("a\r", lines.get(0).content());
        assertEquals("a", lines.get(1).content());
    }

    // ------------------------------------------------------------------
    // S4. Where a hunk ends
    // ------------------------------------------------------------------

    /**
     * JGit's diff of an unmerged path is several sections. The second section's headers
     * used to be read into the first hunk as context, a deletion and an addition.
     */
    @Test
    void aHunkEndsAtTheNextDiffSection() {
        String diff = """
                diff --git a/f b/f
                index 1..2 100644
                --- a/f
                +++ b/f
                @@ -1,2 +1,2 @@
                 one
                -two
                +TWO
                diff --git a/f b/f
                index 3..4 100644
                --- a/f
                +++ b/f
                @@ -1 +1 @@
                -x
                +y
                """;

        List<DiffParser.Hunk> hunks = DiffParser.parse(diff);

        assertEquals(2, hunks.size());
        assertEquals(3, hunks.get(0).lines().size(), "nothing from the second section leaks in");
        assertTrue(hunks.get(0).isConsistent());
        assertTrue(hunks.get(1).isConsistent());
        assertEquals(2, DiffParser.sectionCount(diff));
    }

    /** Even without a {@code diff } line, a hunk ends when its declared lines are read. */
    @Test
    void aHunkEndsWhenItsCountsAreUsedUp() {
        String diff = "@@ -1 +1 @@\n-a\n+b\n--- a/next\n+++ b/next\n";

        var hunk = DiffParser.parse(diff).get(0);

        assertEquals(2, hunk.lines().size());
        assertTrue(hunk.isConsistent());
    }

    /** The no-newline marker follows the hunk's last line, after the counts run out. */
    @Test
    void theMarkerIsReadAfterTheCountsAreUsedUp() {
        var hunk = DiffParser.parse("@@ -1 +1 @@\n-a\n+b\n\\ No newline at end of file\n").get(0);

        assertEquals(Boolean.FALSE, hunk.newEndsWithNewline());
    }

    /** S8. {@code diff.suppressBlankEmpty} prints a blank context line with no space. */
    @Test
    void aBlankContextLineWithoutItsSpaceIsStillALine() {
        var hunk = DiffParser.parse("@@ -1,3 +1,3 @@\n a\n\n-b\n+B\n").get(0);

        assertEquals(4, hunk.lines().size());
        assertEquals("", hunk.lines().get(1).text());
        assertEquals(Integer.valueOf(2), hunk.lines().get(1).oldLine());
        assertTrue(hunk.isConsistent());
    }

    @Test
    void aTruncatedHunkIsNotConsistent() {
        assertFalse(DiffParser.parse("@@ -1,3 +1,3 @@\n a\n").get(0).isConsistent());
    }

    /**
     * S10. Git prints a submodule bump as a hunk; the preamble alone said nothing, so the
     * viewer offered hunk actions that could only fail.
     */
    @Test
    void aSubmoduleBumpIsRecognisedFromItsHunk() {
        var summary = DiffParser.summarize("""
                diff --git a/lib b/lib
                index 1111111..2222222 160000
                --- a/lib
                +++ b/lib
                @@ -1 +1 @@
                -Subproject commit 1111111111111111111111111111111111111111
                +Subproject commit 2222222222222222222222222222222222222222
                """);

        assertTrue(summary.isSubmodule());
        assertEquals("1111111111111111111111111111111111111111", summary.submoduleFrom());
        assertEquals("2222222222222222222222222222222222222222", summary.submoduleTo());
        assertFalse(DiffParser.summarize("diff --git a/f b/f\nindex 1..2 100644\n@@ -1 +1 @@\n"
                + "-Subproject commit 1\n+Subproject commit 2\n").isSubmodule(),
                "a text file that happens to contain those words is not a submodule");
    }

    /** A combined header used to parse as all zeroes. */
    @Test
    void aCombinedHeaderIsReadAndTheDiffMarkedCombined() {
        String diff = """
                diff --cc f
                index 1,2..3
                --- a/f
                +++ b/f
                @@@ -1,2 -1,2 +1,2 @@@
                  common
                - ours
                 -theirs
                ++merged
                """;

        var hunk = DiffParser.parse(diff).get(0);

        assertEquals(1, hunk.oldStart());
        assertEquals(2, hunk.oldCount());
        assertEquals(2, hunk.newCount());
        assertEquals(List.of("common", "ours", "theirs", "merged"),
                hunk.lines().stream().map(DiffParser.Line::text).toList());
        assertEquals(List.of(' ', '-', '-', '+'),
                hunk.lines().stream().map(DiffParser.Line::kind).toList());
        assertTrue(DiffParser.summarize(diff).combined());
        assertFalse(DiffParser.summarize("diff --git a/f b/f\n@@ -1 +1 @@\n-a\n+b\n").combined());
    }
}
