package com.jfx.git.ui;

import com.jfx.git.ui.DiffParser.Hunk;
import com.jfx.git.ui.DiffRows.Kind;
import com.jfx.git.ui.DiffRows.Row;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safety net for the single-surface rewrite: every mode's layout is decided here,
 * as data, before any of it reaches a text area.
 */
class DiffRowsTest {

    private static final String TWO_HUNKS = """
            @@ -1,3 +1,3 @@
             context
            -removed
            +added
            @@ -10,2 +10,3 @@
             kept
            +extra
            """;

    private static List<Hunk> hunks(String diff) {
        return DiffParser.parse(diff);
    }

    // ------------------------------------------------------------------
    // Which patch line a row stands for
    // ------------------------------------------------------------------

    /**
     * A replacement, which is where split view stops matching unified.
     *
     * <p>Three deletions against one addition: the right-hand column is padded twice, so a
     * row's distance from the hunk header stops being its index in the patch.
     */
    private static final String REPLACEMENT = """
            @@ -1,5 +1,3 @@
             before
            -old one
            -old two
            -old three
            +new one
             after
            """;

    /**
     * Partial staging is expressed in indices into {@code hunk.lines()}, and this is where
     * those indices come from.
     *
     * <p>The viewer used to work them out by counting paragraphs down from the hunk
     * header. That is correct in unified view and wrong in split, which pads the shorter
     * of each deletion/addition run - so selecting the added line and pressing Stage line
     * staged the deletion above it instead. Each row carries its own patch-line index
     * now, and this pins the two views to the same answer.
     */
    @Test
    void splitRowsRememberWhichPatchLineTheyCameFrom() {
        List<Hunk> parsed = hunks(REPLACEMENT);
        var split = DiffRows.split(parsed);
        List<DiffParser.Line> lines = parsed.get(0).lines();

        // Every code row on either side points at the line it was actually built from.
        for (List<Row> column : List.of(split.left(), split.right())) {
            for (Row row : column) {
                if (row.kind() != Kind.CODE || row.hunkLine() == Row.NOT_A_HUNK_LINE) continue;
                assertEquals(lines.get(row.hunkLine()).text(), row.text(),
                        "row " + row.text() + " claims patch line " + row.hunkLine());
            }
        }

        // The addition sits on the right, opposite the first of the three deletions, and
        // it is patch line 4 - not line 1, which is what counting rows would have said.
        Row addition = split.right().stream()
                .filter(Row::isAdd)
                .findFirst()
                .orElseThrow();
        assertEquals("new one", addition.text());
        assertEquals(4, addition.hunkLine(),
                "the addition's index in the patch, not its position on screen");
        assertTrue(addition.isPatchLine(), "an addition is stageable on its own");

        // The padding opposite the second and third deletions stands for nothing.
        assertTrue(split.right().stream()
                        .filter(r -> r.kind() == Kind.PAD)
                        .allMatch(r -> r.hunkLine() == Row.NOT_A_HUNK_LINE),
                "padding is not a patch line and must never be staged");
    }

    /** Unified rows carry the same identity, so both views stage the same thing. */
    @Test
    void unifiedRowsCarryTheSamePatchLineIndices() {
        List<Hunk> parsed = hunks(REPLACEMENT);
        List<Row> rows = DiffRows.unified(parsed);
        List<DiffParser.Line> lines = parsed.get(0).lines();

        for (Row row : rows) {
            if (row.kind() != Kind.CODE) continue;
            assertEquals(lines.get(row.hunkLine()).text(), row.text());
        }
        Row addition = rows.stream().filter(Row::isAdd).findFirst().orElseThrow();
        assertEquals(4, addition.hunkLine(), "the same patch line as in split view");
    }

    /** Context revealed from the file is not in the patch, so it stages nothing. */
    @Test
    void revealedContextIsNotAPatchLine() {
        List<Row> rows = DiffRows.unified(hunks(FAR_APART), Map.of(1, 2), FILE);

        assertTrue(rows.stream()
                        .filter(r -> r.kind() != Kind.CODE || !r.isPatchLine())
                        .anyMatch(r -> r.kind() == Kind.CODE),
                "the revealed lines should be present but unstageable");
        assertTrue(rows.stream()
                        .filter(Row::isPatchLine)
                        .allMatch(r -> r.isAdd() || r.isDelete()),
                "only real additions and deletions are stageable");
    }

    // ------------------------------------------------------------------
    // Unified
    // ------------------------------------------------------------------

    @Test
    void unifiedEmitsAHeaderThenItsLines() {
        List<Row> rows = DiffRows.unified(hunks(TWO_HUNKS));

        assertEquals(Kind.HUNK_HEADER, rows.get(0).kind());
        assertEquals("@@ -1,3 +1,3 @@", rows.get(0).text());
        assertEquals("context", rows.get(1).text());
        assertEquals("removed", rows.get(2).text());
        assertEquals("added", rows.get(3).text());
        assertEquals(Kind.HUNK_HEADER, rows.get(4).kind());
        assertEquals(7, rows.size());
    }

    @Test
    void unifiedCarriesLineNumbersThrough() {
        List<Row> rows = DiffRows.unified(hunks(TWO_HUNKS));

        Row context = rows.get(1);
        assertEquals(1, context.oldLine());
        assertEquals(1, context.newLine());

        Row removed = rows.get(2);
        assertEquals(2, removed.oldLine());
        assertNull(removed.newLine(), "a deleted line has no line number on the new side");

        Row added = rows.get(3);
        assertNull(added.oldLine());
        assertEquals(2, added.newLine());
    }

    @Test
    void unifiedTagsEachRowWithItsHunk() {
        List<Row> rows = DiffRows.unified(hunks(TWO_HUNKS));
        assertEquals(0, rows.get(0).hunkIndex());
        assertEquals(0, rows.get(3).hunkIndex());
        assertEquals(1, rows.get(4).hunkIndex());
        assertEquals(1, rows.get(6).hunkIndex());
    }

    @Test
    void unifiedOfNothingIsEmpty() {
        assertTrue(DiffRows.unified(List.of()).isEmpty());
    }

    // ------------------------------------------------------------------
    // Expanders
    // ------------------------------------------------------------------

    @Test
    void expandersCountTheLinesGitLeftOut() {
        List<Hunk> parsed = hunks(TWO_HUNKS);
        // The first hunk starts at line 1, so nothing is hidden above it.
        assertEquals(0, DiffRows.hiddenBefore(parsed, 0));
        // The first hunk covers lines 1-3; the second starts at 10, so 6 are hidden.
        assertEquals(6, DiffRows.hiddenBefore(parsed, 1));
    }

    @Test
    void expandersAppearOnlyWhereLinesAreActuallyHidden() {
        List<Row> rows = DiffRows.unified(hunks(TWO_HUNKS), true);
        List<Row> expanders = rows.stream().filter(r -> r.kind() == Kind.EXPANDER).toList();

        assertEquals(1, expanders.size());
        assertEquals(6, expanders.get(0).hiddenLines());
        assertEquals("6 hidden lines", expanders.get(0).text());
    }

    @Test
    void aHunkStartingBelowLineOneGetsALeadingExpander() {
        List<Row> rows = DiffRows.unified(hunks("@@ -5,1 +5,1 @@\n context\n"), true);
        assertEquals(Kind.EXPANDER, rows.get(0).kind());
        assertEquals(4, rows.get(0).hiddenLines());
    }

    @Test
    void oneHiddenLineIsSingular() {
        List<Row> rows = DiffRows.unified(hunks("@@ -2,1 +2,1 @@\n context\n"), true);
        assertEquals("1 hidden line", rows.get(0).text());
    }

    @Test
    void expandersAreOffByDefault() {
        assertTrue(DiffRows.unified(hunks(TWO_HUNKS)).stream()
                .noneMatch(r -> r.kind() == Kind.EXPANDER));
    }

    // ------------------------------------------------------------------
    // Context expansion
    // ------------------------------------------------------------------

    /** A ten-line file, of which the diff below shows only the ends. */
    private static final List<String> FILE = List.of(
            "line1", "line2", "line3", "line4", "line5",
            "line6", "line7", "line8", "line9", "line10");

    /** Changes line 2 and line 9, hiding lines 4 to 7 between the hunks. */
    private static final String FAR_APART = """
            @@ -1,3 +1,3 @@
             line1
            -line2
            +CHANGED2
             line3
            @@ -8,3 +8,3 @@
             line8
            -line9
            +CHANGED9
             line10
            """;

    @Test
    void expandingRevealsTheLinesBehindTheExpander() {
        List<Hunk> parsed = hunks(FAR_APART);
        assertEquals(4, DiffRows.hiddenBefore(parsed, 1), "lines 4 to 7 are hidden");

        List<Row> rows = DiffRows.unified(parsed, Map.of(1, 2), FILE);
        // The first hunk is five rows; what follows is the expander, the two revealed
        // lines, then the second hunk's header.
        List<Row> between = rows.subList(5, 9);
        assertEquals(Kind.EXPANDER, between.get(0).kind());
        assertEquals(2, between.get(0).hiddenLines(), "two of the four are still hidden");
        assertEquals("line6", between.get(1).text());
        assertEquals("line7", between.get(2).text());
        assertEquals(Kind.HUNK_HEADER, between.get(3).kind());
    }

    @Test
    void revealedLinesCarryBothLineNumbers() {
        List<Row> rows = DiffRows.unified(hunks(FAR_APART), Map.of(1, 2), FILE);
        Row revealed = rows.get(6);
        assertEquals("line6", revealed.text());
        assertEquals(6, revealed.oldLine());
        // Both hunks are the same size, so the sides have not drifted apart.
        assertEquals(6, revealed.newLine());
    }

    // The first hunk below adds a line, so everything after it sits one line further
    // down the new/current file than it did on the old side - this is genuine new-side
    // content for that drift (unlike FILE, which only ever stands in for a file where
    // old and new agree), needed because a revealed line must now be looked up by its
    // position on the new side, not the old one (see DiffRows.addContextAbove).
    private static final String DRIFTING = """
            @@ -1,2 +1,3 @@
             line1
            +inserted
             line2
            @@ -8,2 +9,2 @@
             line8
            -line9
            +CHANGED9
            """;
    private static final List<String> DRIFTED_NEW_FILE = List.of(
            "line1", "inserted", "line2", "line3", "line4",
            "line5", "line6", "line7", "line8", "CHANGED9");

    @Test
    void revealedLineNumbersFollowTheDriftBetweenSides() {
        List<Row> rows = DiffRows.unified(hunks(DRIFTING), Map.of(1, 1), DRIFTED_NEW_FILE);
        Row revealed = rows.stream().filter(r -> "line7".equals(r.text())).findFirst().orElseThrow();
        assertEquals(7, revealed.oldLine());
        assertEquals(8, revealed.newLine(), "the new side is one line further on");
    }

    @Test
    void revealedTextComesFromTheNewSideNotTheOldWhenSidesHaveDrifted() {
        // Two lines revealed above the second hunk: old lines 6 and 7, which sit at new
        // lines 7 and 8 once the first hunk's inserted line is accounted for. A lookup
        // that (incorrectly) indexed by the old line number would fetch "line5"/"line6"
        // here instead - the exact off-by-one-hunk's-worth-of-drift this pins.
        List<Row> rows = DiffRows.unified(hunks(DRIFTING), Map.of(1, 2), DRIFTED_NEW_FILE);
        // Row 0: hunk0 header. 1-3: line1/inserted/line2. 4: the still-hidden remainder
        // as an expander. 5-6: the two newly revealed lines. 7: hunk1 header.
        assertEquals(Kind.EXPANDER, rows.get(4).kind());
        Row first = rows.get(5);
        Row second = rows.get(6);
        assertEquals("line6", first.text());
        assertEquals(6, first.oldLine());
        assertEquals(7, first.newLine());
        assertEquals("line7", second.text());
        assertEquals(7, second.oldLine());
        assertEquals(8, second.newLine());
        assertEquals(Kind.HUNK_HEADER, rows.get(7).kind());
    }

    @Test
    void expandingFullyRemovesTheExpanderSoTheHunksMeet() {
        List<Row> rows = DiffRows.unified(hunks(FAR_APART), Map.of(1, 4), FILE);
        assertTrue(rows.stream().noneMatch(r -> r.kind() == Kind.EXPANDER),
                "nothing is hidden any more");

        // The seam: line3 ends the first hunk, then 4-7 revealed, then line8 starts the
        // second. No line may be repeated or skipped across that join.
        List<String> text = rows.stream()
                .filter(r -> r.kind() == Kind.CODE && r.oldLine() != null)
                .map(Row::text).toList();
        assertEquals(List.of("line1", "line2", "line3", "line4", "line5", "line6",
                "line7", "line8", "line9", "line10"), text);
    }

    @Test
    void expandingAboveTheFirstHunkRevealsTheTopOfTheFile() {
        List<Row> rows = DiffRows.unified(hunks("@@ -5,1 +5,1 @@\n line5\n"), Map.of(0, 2), FILE);
        assertEquals(Kind.EXPANDER, rows.get(0).kind());
        assertEquals(2, rows.get(0).hiddenLines(), "lines 1 and 2 are still hidden");
        assertEquals("line3", rows.get(1).text());
        assertEquals("line4", rows.get(2).text());
    }

    @Test
    void expandingStepsUpwardAndStopsAtTheTop() {
        List<Hunk> parsed = hunks(FAR_APART);
        Map<Integer, Integer> state = DiffRows.expand(Map.of(), parsed, 1, 3);
        assertEquals(3, state.get(1));

        state = DiffRows.expand(state, parsed, 1, 3);
        assertEquals(4, state.get(1), "expansion cannot go past what was hidden");
    }

    @Test
    void expandingWithoutTheFileYieldsBlankRowsRatherThanCrashing() {
        List<Row> rows = DiffRows.unified(hunks(FAR_APART), Map.of(1, 2), List.of());
        assertEquals("", rows.get(6).text());
        assertEquals(6, rows.get(6).oldLine(), "the numbering is still right");
    }

    // ------------------------------------------------------------------
    // Whole file, with diff markers
    // ------------------------------------------------------------------

    @Test
    void wholeFileUnifiedRevealsEveryGapAndTheTrailingLines() {
        List<Row> rows = DiffRows.wholeFileUnified(hunks(FAR_APART), FILE);
        assertTrue(rows.stream().noneMatch(r -> r.kind() == Kind.EXPANDER),
                "a fully revealed file never shows a collapsed section");

        // What the new/current file actually reads as, straight through: every CODE row
        // except deletions (which exist only on the old side and vanish from the new one).
        List<String> codeText = rows.stream()
                .filter(r -> r.kind() == Kind.CODE && r.marker() != '-')
                .map(Row::text).toList();
        // FAR_APART's two hunks cover lines 1-3 and 8-10 of a ten-line file; the whole
        // file should read straight through with nothing skipped at either end.
        assertEquals(List.of("line1", "CHANGED2", "line3", "line4", "line5", "line6",
                "line7", "line8", "CHANGED9", "line10"), codeText);
    }

    @Test
    void wholeFileUnifiedOfNoHunksIsEmpty() {
        assertTrue(DiffRows.wholeFileUnified(List.of(), FILE).isEmpty());
    }

    @Test
    void wholeFileUnifiedWithNothingAfterTheLastHunkAddsNoTrailingRows() {
        List<Row> rows = DiffRows.wholeFileUnified(
                hunks("@@ -1,1 +1,1 @@\n-old\n+new\n"), List.of("new"));
        // Header, deletion, addition - nothing more, since the last hunk already reaches EOF.
        assertEquals(3, rows.size());
        assertEquals("new", rows.get(2).text());
    }

    @Test
    void trailingContextFillsToEndOfFile() {
        List<Row> rows = DiffRows.wholeFileUnified(
                hunks("@@ -1,1 +1,1 @@\n context\n"), List.of("context", "after1", "after2"));

        assertEquals(4, rows.size(), "header, the hunk's own line, then two trailing lines");
        Row first = rows.get(2);
        Row second = rows.get(3);
        assertEquals("after1", first.text());
        assertEquals(2, first.oldLine());
        assertEquals(2, first.newLine());
        assertEquals("after2", second.text());
        assertEquals(3, second.oldLine());
        assertEquals(3, second.newLine());
        assertEquals(0, first.hunkIndex(), "trailing rows are tagged with the hunk they trail from");
    }

    @Test
    void trailingContextIsEmptyWhenLastHunkAlreadyReachesEof() {
        List<Row> rows = DiffRows.wholeFileUnified(
                hunks("@@ -1,1 +1,1 @@\n context\n"), List.of("context"));
        assertEquals(2, rows.size(), "header plus the one line - nothing left to trail");
    }

    // ------------------------------------------------------------------
    // Split
    // ------------------------------------------------------------------

    @Test
    void splitColumnsAreAlwaysTheSameLength() {
        DiffRows.Split split = DiffRows.split(hunks(TWO_HUNKS));
        assertEquals(split.left().size(), split.right().size());
    }

    @Test
    void splitPairsAReplacementOnOneRow() {
        DiffRows.Split split = DiffRows.split(hunks("@@ -1,1 +1,1 @@\n-before\n+after\n"));

        // Row 0 is the hunk header on both sides.
        assertEquals("before", split.left().get(1).text());
        assertEquals("after", split.right().get(1).text());
        assertEquals(2, split.left().size());
    }

    @Test
    void splitPadsTheShorterSideOfAnUnevenChange() {
        DiffRows.Split split = DiffRows.split(hunks("@@ -1,1 +1,3 @@\n-one\n+one\n+two\n+three\n"));

        assertEquals("one", split.left().get(1).text());
        assertEquals(Kind.PAD, split.left().get(2).kind());
        assertEquals(Kind.PAD, split.left().get(3).kind());
        assertEquals("three", split.right().get(3).text());
    }

    @Test
    void splitShowsContextOnBothSides() {
        DiffRows.Split split = DiffRows.split(hunks("@@ -1,2 +1,2 @@\n shared\n-gone\n+new\n"));
        assertEquals("shared", split.left().get(1).text());
        assertEquals("shared", split.right().get(1).text());
    }

    @Test
    void splitKeepsAdditionsOffTheOldSide() {
        DiffRows.Split split = DiffRows.split(hunks("@@ -1,1 +1,2 @@\n kept\n+added\n"));
        assertEquals(Kind.PAD, split.left().get(2).kind());
        assertTrue(split.right().get(2).isAdd());
    }

    @Test
    void splitWithRevealedAboveKeepsColumnsEqualLength() {
        DiffRows.Split split = DiffRows.split(hunks(FAR_APART), Map.of(1, 2), FILE);
        assertEquals(split.left().size(), split.right().size());
    }

    @Test
    void splitRevealedContextIsIdenticalOnBothColumns() {
        DiffRows.Split split = DiffRows.split(hunks(FAR_APART), Map.of(1, 2), FILE);
        // Same slice shape as expandingRevealsTheLinesBehindTheExpander: five rows for
        // the first hunk, then the expander and two revealed lines.
        assertEquals(split.left().subList(5, 8).stream().map(Row::text).toList(),
                split.right().subList(5, 8).stream().map(Row::text).toList());
        assertEquals("line6", split.left().get(5).text());
    }

    @Test
    void splitWithoutRevealedAboveMatchesThePlainOverload() {
        DiffRows.Split withNull = DiffRows.split(hunks(TWO_HUNKS), null, List.of());
        DiffRows.Split plain = DiffRows.split(hunks(TWO_HUNKS));
        assertEquals(plain.left().stream().map(Row::text).toList(),
                withNull.left().stream().map(Row::text).toList());
    }

    // ------------------------------------------------------------------
    // Whole file split, with diff markers
    // ------------------------------------------------------------------

    @Test
    void wholeFileSplitRevealsLeadingGapsAndTail() {
        DiffRows.Split split = DiffRows.wholeFileSplit(hunks(FAR_APART), FILE);
        assertEquals(split.left().size(), split.right().size());
        assertTrue(split.left().stream().noneMatch(r -> r.kind() == Kind.EXPANDER));

        List<String> newSideText = split.right().stream()
                .filter(r -> r.kind() == Kind.CODE)
                .map(Row::text).toList();
        assertEquals(List.of("line1", "CHANGED2", "line3", "line4", "line5", "line6",
                "line7", "line8", "CHANGED9", "line10"), newSideText);
    }

    @Test
    void wholeFileSplitOfNoHunksIsEmpty() {
        DiffRows.Split split = DiffRows.wholeFileSplit(List.of(), FILE);
        assertTrue(split.left().isEmpty());
        assertTrue(split.right().isEmpty());
    }

    // ------------------------------------------------------------------
    // Whole file and blame
    // ------------------------------------------------------------------

    @Test
    void fileContentNumbersEveryLineFromOne() {
        List<Row> rows = DiffRows.fileContent("alpha\nbeta\ngamma");
        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).newLine());
        assertEquals(3, rows.get(2).newLine());
        assertEquals("gamma", rows.get(2).text());
    }

    @Test
    void fileContentKeepsTrailingBlankLine() {
        // A file ending in a newline has a final empty line; dropping it would make the
        // row count disagree with the blame result rendered beside it.
        assertEquals(2, DiffRows.fileContent("only\n").size());
    }

    @Test
    void emptyFileIsOneEmptyRow() {
        List<Row> rows = DiffRows.fileContent("");
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).text());
    }

    @Test
    void blameRowsLineUpWithTheirAnnotations() {
        List<Row> rows = DiffRows.plain(List.of("first", "second"));
        assertEquals(2, rows.size());
        assertEquals(Kind.CODE, rows.get(0).kind());
        assertEquals(2, rows.get(1).newLine());
        assertEquals(-1, rows.get(1).hunkIndex(), "file rows belong to no hunk");
    }

    // ------------------------------------------------------------------
    // Styling
    // ------------------------------------------------------------------

    @Test
    void styleClassFollowsTheRowKind() {
        List<Row> rows = DiffRows.unified(hunks(TWO_HUNKS), true);
        assertEquals("diff-hunk-header-row", rows.get(0).styleClass());
        assertNull(rows.get(1).styleClass(), "context rows take the plain background");
        assertEquals("diff-deletion", rows.get(2).styleClass());
        assertEquals("diff-addition", rows.get(3).styleClass());
        assertEquals("diff-expander-row", rows.get(4).styleClass());
    }

    /**
     * Extra context fills from both ends of a gap and leaves an expander for the middle,
     * and adds a tail after the last hunk - but never a line twice, however far the
     * expander has already been opened.
     */
    @Test
    void extraContextFillsBothEndsOfAGapWithoutOverlap() {
        List<String> file = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "line" + i).toList();
        List<Hunk> hunks = DiffParser.parse("""
                @@ -1,3 +1,3 @@
                 line1
                -old2
                +line2
                 line3
                @@ -19,3 +19,3 @@
                 line19
                -old20
                +line20
                 line21
                """);
        // Gap between them: old lines 4..18 (15 lines).
        List<Row> rows = DiffRows.unified(hunks, java.util.Map.of(), file, 7);
        List<Integer> shown = rows.stream()
                .filter(r -> r.kind() == Kind.CODE && r.marker() == ' ')
                .map(Row::newLine).toList();
        assertEquals(shown.stream().distinct().count(), shown.size(),
                "no line appears twice: " + shown);
        long expanders = rows.stream().filter(r -> r.kind() == Kind.EXPANDER).count();
        assertEquals(1, expanders, "one line of the fifteen stays behind an expander");
        assertTrue(shown.contains(10) && shown.contains(12) && !shown.contains(11),
                "seven under the first change, seven above the second: " + shown);

        List<Row> opened = DiffRows.unified(hunks, java.util.Map.of(1, 100), file, 7);
        List<Integer> openedLines = opened.stream()
                .filter(r -> r.kind() == Kind.CODE && r.marker() == ' ')
                .map(Row::newLine).toList();
        assertEquals(openedLines.stream().distinct().count(), openedLines.size(),
                "a fully opened gap is not doubled by the extra context: " + openedLines);
        assertTrue(openedLines.contains(28) && !openedLines.contains(29),
                "and seven lines of tail after the last change: " + openedLines);
    }

    /**
     * Under Context 10 an expander still opens a whole step: what it revealed is added to
     * the extra context, not merged as the larger of the two.
     */
    @Test
    void anExpanderUnderExtraContextOpensAFullStep() {
        List<String> file = java.util.stream.IntStream.rangeClosed(1, 60)
                .mapToObj(i -> "line" + i).toList();
        List<Hunk> hunks = DiffParser.parse("""
                @@ -1,3 +1,3 @@
                 line1
                -old2
                +line2
                 line3
                @@ -49,3 +49,3 @@
                 line49
                -old50
                +line50
                 line51
                """);
        // A gap of 45 hidden lines: 7 under the first change, 7 + 20 opened above the
        // second, and 11 still behind the expander.
        List<Row> rows = DiffRows.unified(hunks, Map.of(1, 20), file, 7);
        int hidden = rows.stream().filter(r -> r.kind() == Kind.EXPANDER)
                .mapToInt(Row::hiddenLines).sum();
        assertEquals(11, hidden, "the step of twenty comes on top of the extra seven");
    }
}
