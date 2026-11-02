package com.jfx.git.ui;

import com.jfx.git.core.HunkEdit;
import com.jfx.git.core.TextEncoding;
import com.jfx.git.ui.HunkAction.Type;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a loaded diff shows and what it offers, as plain values: no window, no repository.
 *
 * <p>Took over from {@code DiffViewerHunkGatingTest}, which showed a Stage to look buttons
 * up by style class for what is a rule about the diff's kind and lines.
 */
class LoadedDiffTest {

    private static final String PLAIN = """
            diff --git a/f.txt b/f.txt
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             context
            -removed
            +added
             more
            """;

    private static LoadedDiff read(DiffKind kind, String diff) {
        return LoadedDiff.read(kind, "f.txt", diff.getBytes(StandardCharsets.UTF_8),
                TextEncoding.GUESS, null, false);
    }

    private static List<String> labels(LoadedDiff.Offer offer) {
        return offer.actions().stream().map(HunkAction::label).toList();
    }

    // ------------------------------------------------------------------
    // Which actions each kind offers
    // ------------------------------------------------------------------

    @Test
    void anUnstagedChangeOffersStageAndDiscardForTheHunk() {
        LoadedDiff.Offer offer = read(DiffKind.UNSTAGED, PLAIN).actionsFor(0, Set.of());
        assertEquals(List.of("Stage hunk", "Discard hunk"), labels(offer));
        assertEquals("", offer.note());
    }

    @Test
    void aSelectionAddsTheLineActionsForJustThoseLines() {
        // Lines 1 and 2 of the hunk are the deletion and the addition; 0 is context.
        LoadedDiff.Offer offer = read(DiffKind.UNSTAGED, PLAIN).actionsFor(0, Set.of(0, 2));
        HunkAction stageLines = offer.find(false, Type.STAGE);
        assertNotNull(stageLines, "a selected change offers staging just it");
        assertEquals(Set.of(2), stageLines.lines(), "context selected alongside stages nothing");
        assertEquals("Stage line", stageLines.label());
        assertEquals("Discard line", offer.find(false, Type.DISCARD).label());

        LoadedDiff.Offer both = read(DiffKind.UNSTAGED, PLAIN).actionsFor(0, Set.of(1, 2));
        assertEquals("Stage 2 lines", both.find(false, Type.STAGE).label());
        assertEquals("Discard 2 lines", both.find(false, Type.DISCARD).label());
    }

    @Test
    void aStagedChangeOffersOnlyUnstaging() {
        LoadedDiff.Offer offer = read(DiffKind.STAGED, PLAIN).actionsFor(0, Set.of(1));
        assertEquals(List.of("Unstage hunk", "Unstage line"), labels(offer));
    }

    /**
     * A commit's diff offers Revert hunk, and nothing that writes to the index. A stage
     * handler installed on the viewer for the staging area used to outlive it, and offered
     * "Stage hunk" on a commit opened afterwards.
     */
    @Test
    void aCommitOffersRevertHunkAndNeverStaging() {
        LoadedDiff diff = read(DiffKind.COMMIT, PLAIN);
        LoadedDiff.Offer offer = diff.actionsFor(0, Set.of(1, 2));
        assertEquals(List.of("Revert hunk"), labels(offer));
        assertFalse(diff.offersLineActions(), "a commit's hunk is reverted whole");
    }

    @Test
    void conflictsStashesComparisonsAndSnapshotsOfferNothing() {
        for (DiffKind kind : List.of(DiffKind.CONFLICT, DiffKind.STASH, DiffKind.COMPARISON, DiffKind.SNAPSHOT)) {
            LoadedDiff diff = read(kind, PLAIN);
            assertFalse(diff.offersHunkActions(), kind + " offers no hunk actions");
            assertTrue(diff.actionsFor(0, Set.of(1)).actions().isEmpty(), kind.toString());
        }
    }

    @Test
    void nothingLoadedOffersNothing() {
        assertFalse(LoadedDiff.NONE.offersHunkActions());
        assertTrue(LoadedDiff.NONE.hunks().isEmpty());
        assertTrue(LoadedDiff.NONE.actionsFor(0, Set.of()).actions().isEmpty());
    }

    // ------------------------------------------------------------------
    // Diffs whose lines are not the file a patch would apply to
    // ------------------------------------------------------------------

    /** S9. A -w diff is not the file's content; the actions wait, and say why. */
    @Test
    void ignoringWhitespaceWithholdsTheActionsAndSaysWhy() {
        LoadedDiff diff = LoadedDiff.read(DiffKind.UNSTAGED, "f.txt", PLAIN.getBytes(StandardCharsets.UTF_8),
                TextEncoding.GUESS, null, true);
        assertTrue(diff.offersHunkActions(), "the bar still shows, to carry the note");
        LoadedDiff.Offer offer = diff.actionsFor(0, Set.of(1));
        assertTrue(offer.actions().isEmpty(), "no Stage or Discard on a -w diff");
        assertEquals(LoadedDiff.WHITESPACE_NOTE, offer.note());
    }

    /** S10. A submodule pointer has no lines to stage. */
    @Test
    void aSubmoduleDiffHasNoHunkActions() {
        assertFalse(read(DiffKind.UNSTAGED, """
                diff --git a/lib b/lib
                index 1111111..2222222 160000
                --- a/lib
                +++ b/lib
                @@ -1 +1 @@
                -Subproject commit 1111111111111111111111111111111111111111
                +Subproject commit 2222222222222222222222222222222222222222
                """).offersHunkActions());
    }

    /** S4. JGit's diff of an unmerged path is one section per stage - never patchable. */
    @Test
    void aMultiSectionDiffHasNoHunkActions() {
        LoadedDiff diff = read(DiffKind.UNSTAGED, PLAIN + PLAIN);
        assertEquals(2, diff.sections());
        assertFalse(diff.offersHunkActions());
    }

    /** S4. Nor is a combined diff. */
    @Test
    void aCombinedDiffHasNoHunkActions() {
        assertFalse(read(DiffKind.UNSTAGED, """
                diff --cc f.txt
                index 1,2..3
                --- a/f.txt
                +++ b/f.txt
                @@@ -1,1 -1,1 +1,1 @@@
                - ours
                 -theirs
                ++merged
                """).offersHunkActions());
    }

    @Test
    void aHunkWhoseLinesDoNotAddUpIsRefusedWithANote() {
        // The header promises three lines a side; the body holds two.
        LoadedDiff.Offer offer = read(DiffKind.UNSTAGED, """
                @@ -1,3 +1,3 @@
                 context
                -removed
                """).actionsFor(0, Set.of());
        assertTrue(offer.actions().isEmpty());
        assertEquals(LoadedDiff.UNREADABLE_NOTE, offer.note());
    }

    // ------------------------------------------------------------------
    // The edits the actions carry
    // ------------------------------------------------------------------

    /**
     * Reverting a commit that took a line's CR away puts the CR back: the edit is built
     * from each line's own content, CR included, the way every hunk action's is.
     */
    @Test
    void aRevertCarriesEachLinesOwnEnding() {
        LoadedDiff diff = read(DiffKind.COMMIT, "@@ -1,3 +1,3 @@\n one\n-two\r\n+two\n three\n");
        HunkEdit edit = diff.actionsFor(0, Set.of()).find(true, Type.REVERT).edit();
        assertEquals(List.of("one", "two", "three"), edit.search(), "what the commit left");
        assertEquals(List.of("one", "two\r", "three"), edit.replacement(), "what it took away, CR and all");
        assertEquals(diff.lineCharset(), edit.lineCharset());
    }

    @Test
    void stagingANewExecutableFileCarriesItsMode() {
        LoadedDiff diff = read(DiffKind.UNSTAGED, """
                diff --git a/run.sh b/run.sh
                new file mode 100755
                index 0000000..1111111
                --- /dev/null
                +++ b/run.sh
                @@ -0,0 +1,2 @@
                +#!/bin/sh
                +echo hi
                """);
        HunkEdit stage = diff.actionsFor(0, Set.of()).find(true, Type.STAGE).edit();
        assertEquals("100755", stage.createMode());
        assertFalse(stage.removesPath());
    }

    @Test
    void unstagingAllOfAnAddedFileTakesThePathOutOfTheIndex() {
        LoadedDiff diff = read(DiffKind.STAGED, """
                diff --git a/n.txt b/n.txt
                new file mode 100644
                index 0000000..1111111
                --- /dev/null
                +++ b/n.txt
                @@ -0,0 +1 @@
                +only line
                """);
        assertTrue(diff.actionsFor(0, Set.of()).find(true, Type.UNSTAGE).edit().removesPath());
    }

    @Test
    void anActionNamesItsFileHunkAndLines() {
        HunkAction stage = read(DiffKind.UNSTAGED, PLAIN).actionsFor(0, Set.of()).find(true, Type.STAGE);
        assertEquals("f.txt", stage.path());
        assertEquals(0, stage.hunkIndex());
        assertEquals(Set.of(1, 2), stage.lines(), "the whole hunk is every changed line");
        assertEquals("2 lines", stage.linesPhrase());
    }

    // ------------------------------------------------------------------
    // Reading, re-reading, and continuity
    // ------------------------------------------------------------------

    @Test
    void theTextDropsLineEndingsWhileTheHunksKeepThem() {
        LoadedDiff diff = read(DiffKind.UNSTAGED, "@@ -1 +1 @@\r\n-a\r\n+b\r\n");
        assertFalse(diff.text().contains("\r"), "shown and copied without CRs");
        assertTrue(diff.hunks().get(0).lines().get(0).cr(), "the hunk remembers each CR");
    }

    @Test
    void anotherEncodingIsANewLoadedDiffAndTheSameOneIsNot() {
        LoadedDiff diff = read(DiffKind.UNSTAGED, PLAIN);
        assertSame(diff, diff.withEncoding(TextEncoding.GUESS));
        LoadedDiff utf16 = diff.withEncoding(TextEncoding.UTF_16LE);
        assertNotSame(diff, utf16);
        assertEquals(TextEncoding.UTF_16LE, utf16.encoding());
        assertEquals(diff.kind(), utf16.kind());
    }

    @Test
    void aReloadOfTheSameLiveChangeContinuesItAndAnotherCommitDoesNot() {
        String afterStagingOne = PLAIN.replace("+added", "+added\n+and more");
        assertTrue(read(DiffKind.UNSTAGED, afterStagingOne).continues(read(DiffKind.UNSTAGED, PLAIN)),
                "the same unstaged change, after part of it was staged");
        assertTrue(read(DiffKind.STAGED, PLAIN).continues(read(DiffKind.STAGED, PLAIN)));
        assertFalse(read(DiffKind.STAGED, PLAIN).continues(read(DiffKind.UNSTAGED, PLAIN)),
                "staged after unstaged is another change");
        assertTrue(read(DiffKind.COMMIT, PLAIN).continues(read(DiffKind.COMMIT, PLAIN)),
                "a commit's diff read again is the same diff");
        assertFalse(read(DiffKind.COMMIT, afterStagingOne).continues(read(DiffKind.COMMIT, PLAIN)),
                "File history steps from commit to commit under one path");
        assertFalse(read(DiffKind.UNSTAGED, PLAIN).continues(null));
    }

    // ------------------------------------------------------------------
    // The newer side, rows and marks
    // ------------------------------------------------------------------

    private static final String FILE = String.join("\n",
            "line1", "line2", "line3", "line4", "line5", "line6",
            "line7", "line8", "line9", "line10", "line11", "line12");

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

    private static LoadedDiff withNewSide(Supplier<byte[]> newSide) {
        return LoadedDiff.read(DiffKind.UNSTAGED, "f.txt", FAR_APART.getBytes(StandardCharsets.UTF_8),
                TextEncoding.GUESS, newSide, false);
    }

    @Test
    void theNewerSideIsReadOnlyWhenContextIsWantedAndThenOnlyOnce() {
        AtomicInteger reads = new AtomicInteger();
        LoadedDiff diff = withNewSide(() -> {
            reads.incrementAndGet();
            return FILE.getBytes(StandardCharsets.UTF_8);
        });
        assertTrue(diff.canExpand());

        diff.unified(false, 0, Map.of());
        assertEquals(0, reads.get(), "git's own lines need no file");

        List<DiffRows.Row> whole = diff.unified(true, 0, Map.of()).list();
        assertTrue(whole.stream().anyMatch(r -> "line12".equals(r.text())), "the tail is filled in");
        diff.split(true, 0);
        diff.unified(false, 7, Map.of());
        assertEquals(1, reads.get(), "read once, however often it is used");
    }

    @Test
    void withoutANewerSideThereIsNothingToExpand() {
        LoadedDiff diff = withNewSide(null);
        assertFalse(diff.canExpand());
        assertEquals(diff.unified(false, 0, Map.of()).list(), diff.unified(true, 7, Map.of()).list(),
                "Whole file and extra context fall back to git's own lines");
    }

    @Test
    void theRowIndexAnswersByPositionFromTheRowsThemselves() {
        LoadedDiff.Rows rows = withNewSide(null).unified(false, 0, Map.of());
        int second = rows.firstRowOf(1);
        assertEquals("@@ -8,3 +8,3 @@", rows.list().get(second).text().trim().replaceAll(" +", " "));
        assertEquals(-1, rows.firstRowOf(5));
        // The second hunk's rows: header, line8, -line9, +CHANGED9, line10.
        assertEquals(Set.of(1, 2), rows.changedLines(1, second, second + 4));
        assertEquals(Set.of(), rows.changedLines(0, second, second + 4), "a neighbour's rows are its own");
        assertEquals(10, rows.lastNewLine());
        assertEquals(second + 3, rows.rowOfNewLine(9), "CHANGED9 is line 9 of the new side");
    }

    @Test
    void marksFallOnTheNewerSidesLines() {
        LoadedDiff.Marks marks = withNewSide(null).marks(12);
        assertEquals(Set.of(1, 8), marks.additions(), "CHANGED2 and CHANGED9, 0-based");
        assertEquals(Set.of(1, 8), marks.deletions(), "each deletion where its text was cut out");
    }

    /**
     * The sticky header's definition is read from the newer side's lines, by the row's
     * newer-side line number. Below a hunk that added lines the old number points at
     * whatever sits that many lines higher.
     */
    /** alpha gains three lines, pushing gamma from line 8 down to 11, where it changes. */
    private static LoadedDiff gammaChange() {
        String newFile = String.join("\n",
                "def alpha():", "    x = 1", "    a = 1", "    a = 2", "    a = 3", "    return x",
                "", "def beta():", "    return 2", "", "def gamma():", "    z = 4", "    return z") + "\n";
        String diff = String.join("\n",
                "@@ -1,3 +1,6 @@",
                " def alpha():",
                "     x = 1",
                "+    a = 1",
                "+    a = 2",
                "+    a = 3",
                "     return x",
                "@@ -8,3 +11,3 @@",
                " def gamma():",
                "-    z = 3",
                "+    z = 4",
                "     return z",
                "");
        return LoadedDiff.read(DiffKind.UNSTAGED, "app.py", diff.getBytes(StandardCharsets.UTF_8),
                TextEncoding.GUESS, () -> newFile.getBytes(StandardCharsets.UTF_8), false);
    }

    @Test
    void theEnclosingDefinitionIsLookedUpOnTheNewerSide() {
        LoadedDiff loaded = gammaChange();
        LoadedDiff.Rows rows = loaded.unified(true, 0, Map.of());
        int returnZ = rows.rowOfNewLine(13);
        assertEquals("    return z", rows.list().get(returnZ).text());
        assertTrue(loaded.enclosingSymbol(rows, returnZ).contains("gamma"),
                loaded.enclosingSymbol(rows, returnZ));
        int deletion = returnZ - 2;
        assertTrue(rows.list().get(deletion).isDelete(), "precondition: -    z = 3");
        assertTrue(loaded.enclosingSymbol(rows, deletion).contains("gamma"),
                "a deletion stands where its text was cut out of the newer side");
    }

    @Test
    void noDefinitionIsNamedBeforeTheNewerSideHasBeenRead() {
        LoadedDiff loaded = gammaChange();
        LoadedDiff.Rows rows = loaded.unified(false, 0, Map.of());
        int returnZ = rows.rowOfNewLine(13);
        assertNull(loaded.enclosingSymbol(rows, returnZ), "naming a definition is not worth reading the file for");
        loaded.unified(true, 0, Map.of());
        assertNotNull(loaded.enclosingSymbol(rows, returnZ), "once read for Whole file, it is named");
    }

    // ------------------------------------------------------------------
    // The model stays testable as plain values
    // ------------------------------------------------------------------

    /** Nothing from JavaFX in the diff model, so all of the above runs without a toolkit. */
    @Test
    void theDiffModelImportsNothingFromJavaFx() throws Exception {
        for (String name : List.of("LoadedDiff", "DiffKind", "HunkAction", "DiffParser", "DiffRows",
                "HunkSelection", "InlineDiff", "WhitespaceMapping")) {
            Path source = Path.of("src/main/java/com/jfx/git/ui", name + ".java");
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("javafx."), name + " reaches into JavaFX");
        }
    }
}
