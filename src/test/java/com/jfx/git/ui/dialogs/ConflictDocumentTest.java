package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.dialogs.ConflictDocument.Conflict;
import com.jfx.git.ui.dialogs.ConflictDocument.Located;
import com.jfx.git.ui.dialogs.ConflictDocument.Range;
import com.jfx.git.ui.dialogs.ConflictRegions.Resolution;
import com.jfx.git.ui.dialogs.ConflictRegions.Slot;
import com.jfx.git.ui.dialogs.ConflictRegions.State;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge tool's model, without a window: a conflicted file taken apart and put back,
 * where each conflict sits in the three versions, what ticking lines makes of a conflict,
 * and how the conflicts follow edits to the result.
 */
class ConflictDocumentTest {

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------
    // Taking the file apart, and putting it back
    // ------------------------------------------------------------------

    @Test
    void conflictsAreParsedWithTheirMarkerLinesAndLabels() {
        ConflictDocument doc = ConflictDocument.parse(lines(
                "top",
                "<<<<<<< HEAD", "ours one", "=======", "theirs one", ">>>>>>> feature/refunds",
                "middle",
                "<<<<<<< HEAD", "ours", "||||||| merged common ancestors", "ancestor", "=======", "theirs",
                ">>>>>>> 90c5ca7 (Add refund service)",
                "bottom", ""));

        assertEquals(2, doc.conflicts().size());
        Conflict plain = doc.conflicts().get(0);
        assertEquals("<<<<<<< HEAD", plain.open());
        assertEquals(List.of("ours one"), plain.ours());
        assertNull(plain.baseMarker());
        assertNull(plain.base());
        assertEquals(List.of("theirs one"), plain.theirs());
        assertEquals(">>>>>>> feature/refunds", plain.close());

        Conflict diff3 = doc.conflicts().get(1);
        assertEquals("||||||| merged common ancestors", diff3.baseMarker());
        assertEquals(List.of("ancestor"), diff3.base(), "the ancestor's lines belong to neither side");
        assertEquals(List.of("ours"), diff3.ours());
        assertEquals(">>>>>>> 90c5ca7 (Add refund service)", diff3.close());

        assertEquals(List.of("top"), doc.common(0));
        assertEquals(List.of("middle"), doc.common(1));
        assertEquals(List.of("bottom", ""), doc.common(2));
    }

    @Test
    void aHalfWrittenConflictIsNotOneAndASecondOpeningMarkerRestarts() {
        ConflictDocument unfinished = ConflictDocument.parse(lines("<<<<<<< HEAD", "ours", ">>>>>>> x"));
        assertTrue(unfinished.conflicts().isEmpty(), "a region with its separator deleted is not a region");
        assertEquals(List.of("<<<<<<< HEAD", "ours", ">>>>>>> x"), unfinished.common(0));

        ConflictDocument restarted = ConflictDocument.parse(lines(
                "<<<<<<< stale", "x", "<<<<<<< HEAD", "a", "=======", "b", ">>>>>>> f"));
        assertEquals(1, restarted.conflicts().size());
        assertEquals(List.of("<<<<<<< stale", "x"), restarted.common(0));
    }

    /** What was parsed is what goes back, byte for byte, markers and stray lines included. */
    @Test
    void theTextPutsBackExactlyWhatWasParsed() {
        for (String text : List.of(
                lines("a", "<<<<<<< HEAD", "x", "=======", "y", ">>>>>>> b", "z", ""),
                lines("<<<<<<< HEAD", "x", "=======", "y", ">>>>>>> b"),
                lines("=======", "a stray separator", ">>>>>>> and a closer", ""),
                lines("<<<<<<< HEAD", "", "||||||| base", "", "=======", "", ">>>>>>>", "", ""),
                "",
                "no conflict at all\n")) {
            assertEquals(text, ConflictDocument.parse(text).text());
        }
    }

    // ------------------------------------------------------------------
    // Where each conflict sits, and the ancestor's lines that stand for it
    // ------------------------------------------------------------------

    @Test
    void theBaseOfAConflictComesFromItsDiff3Section() {
        String merged = lines("a", "<<<<<<< HEAD", "x", "||||||| base", "b", "=======", "y", ">>>>>>> f", "c", "");
        Located at = ConflictDocument.parse(merged)
                .locate(lines("a", "x", "c", ""), lines("a", "y", "c", ""), lines("a", "b", "c", "")).get(0);

        assertEquals(List.of("b"), at.shown());
        assertEquals(new Range(1, 2), at.ours());
        assertEquals(new Range(1, 2), at.theirs());
        assertEquals(new Range(1, 2), at.base());
    }

    @Test
    void theBaseOfAConflictIsWorkedOutFromTheThreeVersionsWithoutDiff3() {
        String base = lines("a", "b", "c", "d", "e", "");
        String ours = lines("a", "B1", "c", "d", "e", "");
        String theirs = lines("a", "B2", "c", "d", "E", "");
        // As git writes it: theirs' change to e merged cleanly into the agreed text.
        String merged = lines("a", "<<<<<<< HEAD", "B1", "=======", "B2", ">>>>>>> f", "c", "d", "E", "");

        Located at = ConflictDocument.parse(merged).locate(ours, theirs, base).get(0);
        assertEquals(List.of("b"), at.shown());
        assertEquals(new Range(1, 2), at.ours(), "B1 is line 2 of ours");
        assertEquals(new Range(1, 2), at.theirs());
        assertEquals(new Range(1, 2), at.base());
    }

    /**
     * Git trims lines both sides agree on from a conflict's edges. The ancestor's lines are
     * still the ones both sides replaced.
     */
    @Test
    void aConflictGitTrimmedStillFindsTheLinesBothSidesReplaced() {
        String base = lines("a", "z", "b", "");
        String ours = lines("a", "x", "P", "y", "b", "");
        String theirs = lines("a", "x", "Q", "y", "b", "");
        String merged = lines("a", "x", "<<<<<<< HEAD", "P", "=======", "Q", ">>>>>>> f", "y", "b", "");

        Located at = ConflictDocument.parse(merged).locate(ours, theirs, base).get(0);
        assertEquals(List.of("z"), at.shown());
        assertEquals(new Range(2, 3), at.ours());
        assertEquals(new Range(2, 3), at.theirs());
    }

    /** The ancestor's lines a side deleted, when the other side changed them. */
    @Test
    void aDeletedSideFindsTheLinesItDeleted() {
        String merged = lines("a", "<<<<<<< HEAD", "=======", "B", ">>>>>>> f", "c", "");
        Located at = ConflictDocument.parse(merged)
                .locate(lines("a", "c", ""), lines("a", "B", "c", ""), lines("a", "b", "c", "")).get(0);

        assertEquals(List.of("b"), at.shown());
        assertEquals(new Range(1, 1), at.ours(), "nothing of ours, where its lines would have been");
        assertEquals(new Range(1, 2), at.theirs());
    }

    /** Both sides added different lines at one place: the ancestor has nothing there. */
    @Test
    void bothSidesAddingHasNoBaseLines() {
        String merged = lines("a", "<<<<<<< HEAD", "X", "=======", "Y", ">>>>>>> f", "c", "");
        Located at = ConflictDocument.parse(merged)
                .locate(lines("a", "X", "c", ""), lines("a", "Y", "c", ""), lines("a", "c", "")).get(0);

        assertTrue(at.shown().isEmpty());
        assertEquals(new Range(1, 1), at.base());
        assertEquals(List.of(""), ConflictRegions.undecidedLines(at.shown()), "a placeholder line stands in");
    }

    @Test
    void aFileWithNoBaseOrAnEditedDraftHasNoBaseLines() {
        String merged = lines("a", "<<<<<<< HEAD", "X", "=======", "Y", ">>>>>>> f", "c", "");
        Located added = ConflictDocument.parse(merged).locate(lines("a", "X", "c", ""), lines("a", "Y", "c", ""), null)
                .get(0);
        assertTrue(added.shown().isEmpty(), "an add/add conflict has no stage 1");

        String draft = lines("a", "<<<<<<< HEAD", "X edited", "=======", "Y", ">>>>>>> f", "c", "");
        Located edited = ConflictDocument.parse(draft)
                .locate(lines("a", "X", "c", ""), lines("a", "Y", "c", ""), lines("a", "b", "c", "")).get(0);
        assertNull(edited.ours(), "lines that are not in ours verbatim are not found there");
        assertTrue(edited.shown().isEmpty());
    }

    /**
     * A conflict written by hand rather than by git - the dialog tour's - still finds its
     * ancestor from what each side's lines replaced.
     */
    @Test
    void aHandWrittenConflictStillFindsItsAncestor() {
        String base = lines("import a;", "import b;", "", "class X {", "  total();", "  charge();", "}", "");
        String ours = lines("import a;", "import b;", "import audit;", "", "class X {", "  validate();",
                "  total();", "  audit();", "  charge();", "}", "");
        String theirs = lines("import a;", "import round;", "import b;", "", "class X {", "  round();",
                "  receipt();", "}", "");
        String merged = lines("import a;",
                "<<<<<<< main", "import b;", "import audit;", "=======", "import round;", "import b;", ">>>>>>> f",
                "", "class X {",
                "<<<<<<< main", "  validate();", "  total();", "  audit();", "  charge();", "=======", "  round();",
                "  receipt();", ">>>>>>> f",
                "}", "");

        List<Located> located = ConflictDocument.parse(merged).locate(ours, theirs, base);
        assertEquals(List.of("import b;"), located.get(0).shown());
        assertEquals(List.of("  total();", "  charge();"), located.get(1).shown());
        assertEquals(new Range(5, 9), located.get(1).ours());
        assertEquals(new Range(5, 7), located.get(1).theirs());
    }

    // ------------------------------------------------------------------
    // Ticked lines, and what a conflict's lines amount to
    // ------------------------------------------------------------------

    private static final Conflict CONFLICT = new Conflict("<<<<<<< HEAD", List.of("o0", "o1", "o2"), null, null,
            "=======", List.of("t0", "t1"), ">>>>>>> f");

    @Test
    void theTickedLinesMakeTheConflictsText() {
        assertEquals(List.of("o0", "o2", "t1"),
                ConflictRegions.compose(CONFLICT, Resolution.picked(Set.of(2, 0), Set.of(1))),
                "ticked ours lines in order, then ticked theirs lines in order");
        assertEquals(List.of("t0", "t1"),
                ConflictRegions.compose(CONFLICT, Resolution.choose(MergeToolDialog.Choice.THEIRS, CONFLICT)));
        assertEquals(List.of("o0", "o1", "o2", "t0", "t1"),
                ConflictRegions.compose(CONFLICT, Resolution.choose(MergeToolDialog.Choice.BOTH, CONFLICT)));
        assertEquals(2, ConflictRegions.oursLinesIn(CONFLICT, Resolution.picked(Set.of(0, 2), Set.of(1))));
        assertEquals(Resolution.UNDECIDED, Resolution.picked(Set.of(0), Set.of()).toggled(true, 0),
                "nothing ticked is nothing decided");
    }

    @Test
    void aConflictsStateIsReadOffItsLines() {
        List<String> base = List.of("b");
        Resolution ours = Resolution.choose(MergeToolDialog.Choice.OURS, CONFLICT);
        assertEquals(State.UNRESOLVED, ConflictRegions.state(CONFLICT, base, Resolution.UNDECIDED, List.of("b")));
        assertEquals(State.UNRESOLVED, ConflictRegions.state(CONFLICT, List.of(), Resolution.UNDECIDED, List.of("")),
                "the placeholder of a conflict with no ancestor lines");
        assertEquals(State.OURS, ConflictRegions.state(CONFLICT, base, ours, List.of("o0", "o1", "o2")));
        assertEquals(State.THEIRS, ConflictRegions.state(CONFLICT, base,
                Resolution.choose(MergeToolDialog.Choice.THEIRS, CONFLICT), List.of("t0", "t1")));
        assertEquals(State.BOTH, ConflictRegions.state(CONFLICT, base,
                Resolution.choose(MergeToolDialog.Choice.BOTH, CONFLICT), List.of("o0", "o1", "o2", "t0", "t1")));
        assertEquals(State.PICKED, ConflictRegions.state(CONFLICT, base,
                Resolution.picked(Set.of(1), Set.of(0)), List.of("o1", "t0")));
        assertEquals(State.EDITED, ConflictRegions.state(CONFLICT, base, ours, List.of("o0", "typed", "o2")),
                "typed over");
        assertEquals(State.EDITED, ConflictRegions.state(CONFLICT, base, Resolution.UNDECIDED, List.of("b", "more")));

        Conflict deleted = new Conflict("<<<<<<< HEAD", List.of(), null, null, "=======", List.of("t"), ">>>>>>> f");
        Resolution takeDeletion = Resolution.choose(MergeToolDialog.Choice.OURS, deleted);
        assertTrue(takeDeletion.decided(), "taking the side that deleted the lines is a decision");
        assertEquals(State.OURS, ConflictRegions.state(deleted, base, takeDeletion, List.of()));
    }

    /**
     * What goes back to disk: an undecided conflict as git wrote it, everything else as it
     * stands. Opened and saved with nothing chosen, the file is what it was.
     */
    @Test
    void undecidedConflictsGoBackWithTheirMarkersAndTheRestAsItStands() {
        String original = lines("top", "<<<<<<< HEAD", "x", "=======", "y", ">>>>>>> f",
                "mid", "<<<<<<< HEAD", "p", "=======", "q", ">>>>>>> f", "end", "");
        ConflictDocument doc = ConflictDocument.parse(original);
        List<Integer> starts = new ArrayList<>();
        List<String> clean = doc.interleave(List.of(List.of("b1"), List.of("")), starts);
        assertEquals(List.of("top", "b1", "mid", "", "end", ""), clean);
        List<Slot> slots = List.of(new Slot(0, starts.get(0), 1, Resolution.UNDECIDED),
                new Slot(1, starts.get(1), 1, Resolution.UNDECIDED));

        assertEquals(original, String.join("\n", ConflictRegions.output(clean, slots,
                List.of(State.UNRESOLVED, State.UNRESOLVED), doc.conflicts())));

        List<String> oneTaken = List.of("top", "y", "mid", "", "end", "");
        assertEquals(lines("top", "y", "mid", "<<<<<<< HEAD", "p", "=======", "q", ">>>>>>> f", "end", ""),
                String.join("\n", ConflictRegions.output(oneTaken, slots,
                        List.of(State.THEIRS, State.UNRESOLVED), doc.conflicts())));
    }

    // ------------------------------------------------------------------
    // Following edits
    // ------------------------------------------------------------------

    /** A conflict on lines 2-3 of a five-line result. */
    private static final List<Slot> ONE = List.of(new Slot(0, 2, 2, Resolution.UNDECIDED));

    private static Slot after(List<Slot> slots, int p0, int c0, int p1, String inserted, int paragraphs) {
        return ConflictRegions.afterEdit(slots, p0, c0, p1, inserted, paragraphs).get(0);
    }

    @Test
    void typingInsideAConflictGrowsItAndOutsideMovesIt() {
        assertEquals(new Slot(0, 2, 3, Resolution.UNDECIDED), after(ONE, 3, 4, 3, "\n", 6),
                "a line break at the end of its last line is a line of the conflict");
        assertEquals(new Slot(0, 2, 3, Resolution.UNDECIDED), after(ONE, 2, 0, 2, "\n", 6),
                "and so is one at the start of its first");
        assertEquals(ONE.get(0), after(ONE, 4, 0, 4, "\n", 6),
                "one at the start of the line after it is not");
        assertEquals(new Slot(0, 3, 2, Resolution.UNDECIDED), after(ONE, 0, 1, 0, "x\n", 6),
                "a line added above moves it down");
        assertEquals(new Slot(0, 1, 2, Resolution.UNDECIDED), after(ONE, 0, 3, 1, "", 4),
                "a line joined above moves it up");
    }

    @Test
    void deletingAConflictsLinesLeavesAnAnchorAndTheNextLineInPlace() {
        assertEquals(new Slot(0, 2, 0, Resolution.UNDECIDED), after(ONE, 2, 0, 4, "", 3),
                "its lines deleted with their line break: empty, before the line that followed");
        assertEquals(new Slot(0, 2, 0, Resolution.UNDECIDED), after(ONE, 1, 3, 3, "", 3),
                "or deleted from the end of the line before");
        assertEquals(new Slot(0, 1, 0, Resolution.UNDECIDED), after(ONE, 0, 1, 4, "", 1),
                "swallowed by a bigger deletion: an anchor after it");
        assertEquals(new Slot(0, 2, 1, Resolution.UNDECIDED), after(ONE, 3, 0, 4, "", 4),
                "its last line deleted with its break");

        List<Slot> anchored = List.of(new Slot(0, 2, 0, Resolution.UNDECIDED));
        assertEquals(new Slot(0, 3, 0, Resolution.UNDECIDED), after(anchored, 0, 0, 0, "new\n", 5));
        assertEquals(new Slot(0, 2, 0, Resolution.UNDECIDED), after(anchored, 2, 0, 2, "typed\n", 5),
                "a line typed on the line after an anchor goes after it");
    }

    /** Replace all never spans a line break, and must not hand one conflict's lines to another. */
    @Test
    void anEditThatKeepsTheLineCountMovesNothing() {
        List<Slot> two = List.of(new Slot(0, 1, 1, Resolution.UNDECIDED), new Slot(1, 3, 1, Resolution.UNDECIDED));
        assertEquals(two, ConflictRegions.afterEdit(two, 1, 2, 3, "mine\nmiddle\nmine", 5));
    }
}
