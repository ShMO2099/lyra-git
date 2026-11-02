package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.ThemeManager;
import com.jfx.git.ui.dialogs.ConflictRegions.State;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge tool in each of its two roles.
 *
 * <p>The dialog resolves git conflicts and reconciles an editor buffer with a file that
 * changed on disk. The two want different starting points when the merged text is empty,
 * and getting the second one wrong saved text the user had deliberately deleted. As a
 * conflict editor it shows a clean result - the ancestor's lines where nothing is decided
 * - and writes git's markers back only for the conflicts nobody decided.
 */
class MergeToolDialogTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    /** Closes the windows the theme tests showed their dialogs over. */
    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    /**
     * Every jump to a conflict focuses the result, so the tool is normally closed with the
     * caret in it - Esc, or the window's close button. RichTextFX kept blinking that caret
     * after the window had gone, and the blink kept the dialog and its four editors: 17 MB
     * each time, for the life of the process.
     */
    @Test
    void aToolClosedWithTheCaretInTheResultIsCollected() throws Exception {
        java.lang.ref.WeakReference<MergeToolDialog> ref = FxHarness.call(() -> {
            MergeToolDialog shown = new MergeToolDialog("a.txt", "ours\n", "theirs\n", "base\n",
                    "<<<<<<< ours\nours\n=======\ntheirs\n>>>>>>> theirs\n", "main", "feature");
            shown.show();
            return new java.lang.ref.WeakReference<>(shown);
        });
        // The caret in the result, as a click there or a jump to a conflict puts it: the one
        // editable pane. Without it there is no blink, and this would pass for the wrong reason.
        String focus = FxHarness.call(() -> {
            FxHarness.lookupAll(ref.get().getDialogPane(), "code-area").stream()
                    .filter(n -> n instanceof com.jfx.git.ui.DiffTextArea a && a.isEditable())
                    .findFirst().orElseThrow().requestFocus();
            javafx.scene.Scene scene = ref.get().getDialogPane().getScene();
            Node owner = scene.getFocusOwner();
            return (owner == null ? "null" : owner.getClass().getSimpleName() + " focused=" + owner.isFocused()
                    + (owner instanceof com.jfx.git.ui.DiffTextArea a ? " editable=" + a.isEditable() : ""))
                    + " windowFocused=" + scene.getWindow().isFocused();
        });
        assertTrue(focus.startsWith("DiffTextArea focused=true editable=true"), "the result should have the caret: " + focus);

        FxHarness.run(() -> {
            ref.get().setResult(null);
            ref.get().close();
        });

        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(ref.get(), "a closed merge tool must not stay in memory");
    }

    /** A conflict with no working copy starts from the ancestor, as it always has. */
    @Test
    void aConflictWithNoMergedTextStartsFromTheBase() {
        String result = FxHarness.call(() -> new MergeToolDialog(
                "a.txt", "ours\n", "theirs\n", "base\n", "", "main", "feature")
                .resultTextForTest());

        assertEquals("base\n", result);
    }

    /**
     * Reconciling, the result starts as the user's buffer - and an emptied buffer is an
     * edit, not an absence. Pre-filling the ancestor meant accepting without looking wrote
     * back the text they had just removed.
     */
    @Test
    void aReconciliationStartsFromTheBufferEvenWhenItIsEmpty() {
        String result = FxHarness.call(() -> new MergeToolDialog(
                "", "on disk\n", "what it was\n", "",
                new MergeToolDialog.Labels("Compare", "My edits", "On disk now",
                        "Use this text", false))
                .resultTextForTest());

        assertEquals("", result, "the user emptied the file; that is the starting point");
    }

    /**
     * A comparison's toolbar says how far apart the versions are and starts the result from
     * either one, and each side marks the lines that differ. It had only Take and Base, on
     * an otherwise empty row, over two panes with nothing marked.
     */
    @Test
    void aComparisonMarksItsDifferencesAndStartsFromEitherVersion() {
        assertEquals(1, MergeToolDialog.lineEdits("a\nb\nc\n", "a\nB\nc\n").size());
        assertTrue(MergeToolDialog.lineEdits("same\r\n", "same\n").isEmpty(), "line endings are not a difference");

        MergeToolDialog dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            MergeToolDialog shown = new MergeToolDialog("a\nb\nc", "a\nB\nc", "a\nb\nc", "a\nb\nc",
                    new MergeToolDialog.Labels("Compare", "My edits", "On disk now", "Use this text", false));
            shown.initOwner(owner);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the differing lines were never marked",
                    () -> !FxHarness.lookupAll(dialog.getDialogPane(), "merge-theirs-line").isEmpty()
                            && !FxHarness.lookupAll(dialog.getDialogPane(), "merge-ours-line").isEmpty());
            FxHarness.run(() -> {
                Label count = (Label) FxHarness.lookupAll(dialog.getDialogPane(), "merge-difference-count").get(0);
                assertEquals("1 difference", count.getText());
                assertTrue(FxHarness.lookupAll(dialog.getDialogPane(), "merge-start-from").stream()
                        .anyMatch(n -> n instanceof Label l && "Start result from".equals(l.getText())));
                assertTrue(FxHarness.lookupAll(dialog.getDialogPane(), "merge-base-toggle").stream()
                                .noneMatch(Node::isVisible),
                        "the original is the same text as my edits here, so there is no pane to show");
                FxHarness.lookupAll(dialog.getDialogPane(), "button").stream()
                        .filter(n -> n instanceof javafx.scene.control.Button b && "On disk now".equals(b.getText()))
                        .map(javafx.scene.control.Button.class::cast)
                        .findFirst().orElseThrow().fire();
            });
            assertEquals("a\nB\nc", FxHarness.call(dialog::resultTextForTest),
                    "an unedited result is replaced without asking");
        } finally {
            FxHarness.run(() -> dialog.getDialogPane().getScene().getWindow().hide());
        }
    }

    // ------------------------------------------------------------------
    // Conflict regions, one at a time
    // ------------------------------------------------------------------

    /** Three conflicts with no sides to find them in: each shows a placeholder line. */
    private static final String THREE_CONFLICTS = String.join("\n",
            "top",
            "<<<<<<< HEAD", "ours one", "=======", "theirs one", ">>>>>>> feature",
            "middle",
            "<<<<<<< HEAD", "ours two", "=======", "theirs two", ">>>>>>> feature",
            "lower",
            "<<<<<<< HEAD", "ours three", "=======", "theirs three", ">>>>>>> feature",
            "bottom");

    /** The same three conflicts as git writes them, with the three versions they came from. */
    private static final String BASE = String.join("\n",
            "top", "one", "middle", "two", "lower", "three", "bottom", "");
    private static final String OURS = String.join("\n",
            "top", "ours one", "middle", "ours two", "lower", "ours three", "bottom", "");
    private static final String THEIRS = String.join("\n",
            "top", "theirs one", "middle", "theirs two", "lower", "theirs three", "bottom", "");
    private static final String MERGED = THREE_CONFLICTS + "\n";

    private static MergeToolDialog threeConflicts() {
        return FxHarness.call(() -> new MergeToolDialog("f.txt", OURS, THEIRS, BASE, MERGED, "main", "feature"));
    }

    /**
     * The result is the file without git's markers: the agreed text, and each conflict's
     * common-ancestor lines standing in for it while nobody has decided.
     */
    @Test
    void theResultShowsTheAncestorInPlaceOfEachConflictAndNoMarkers() {
        MergeToolDialog dialog = threeConflicts();

        assertEquals(BASE, FxHarness.call(dialog::resultTextForTest));
        assertEquals(List.of(State.UNRESOLVED, State.UNRESOLVED, State.UNRESOLVED),
                FxHarness.call(dialog::statesForTest));
        assertEquals("1 of 3 conflicts", FxHarness.call(dialog::conflictSummaryForTest),
                "it opens on the first conflict");
        FxHarness.run(() -> assertEquals(List.of("merge-unresolved-line"),
                List.copyOf(dialog.lineStyleForTest("result", 1)), "an undecided conflict is tinted as one"));
    }

    /** Opened and saved with nothing chosen, the file goes back exactly as git wrote it. */
    @Test
    void savingWithNothingChosenGivesBackTheFileByteForByte() {
        assertEquals(MERGED, FxHarness.call(threeConflicts()::returnedTextForTest));
        assertEquals(THREE_CONFLICTS, FxHarness.call(() ->
                new MergeToolDialog("f.txt", "", "", "", THREE_CONFLICTS).returnedTextForTest()), "with no sides");

        String diff3 = String.join("\n", "a", "<<<<<<< HEAD", "x", "||||||| base", "b", "=======", "y",
                ">>>>>>> f", "c", "");
        assertEquals(diff3, FxHarness.call(() -> new MergeToolDialog("f.txt", "a\nx\nc\n", "a\ny\nc\n", "a\nb\nc\n",
                diff3).returnedTextForTest()));

        // The editor holds LF; the caller puts a CRLF file's carriage returns back.
        String crlf = MERGED.replace("\n", "\r\n");
        String returned = FxHarness.call(() -> new MergeToolDialog("f.txt", OURS, THEIRS, BASE, crlf)
                .returnedTextForTest());
        assertEquals(MERGED, returned);
        assertEquals(crlf, com.jfx.git.core.LineEndings.matchConvention(crlf, returned));
    }

    @Test
    void resolvingOneConflictLeavesTheOthersAlone() {
        MergeToolDialog dialog = threeConflicts();
        FxHarness.run(() -> {
            dialog.moveCaretForTest(3);
            dialog.resolveActiveForTest(MergeToolDialog.Choice.THEIRS);
        });
        String result = FxHarness.call(dialog::resultTextForTest);
        assertEquals(String.join("\n", "top", "one", "middle", "theirs two", "lower", "three", "bottom", ""), result);
        String returned = FxHarness.call(dialog::returnedTextForTest);
        assertTrue(returned.contains("middle\ntheirs two\nlower"), returned);
        assertTrue(returned.contains("<<<<<<< HEAD\nours one\n=======\ntheirs one\n>>>>>>> feature"),
                "the first conflict is still git's, markers and all: " + returned);
        assertTrue(returned.contains("<<<<<<< HEAD\nours three\n=======\ntheirs three\n>>>>>>> feature"),
                "and so is the third: " + returned);
        assertEquals("3 of 3 conflicts · 2 left", FxHarness.call(dialog::conflictSummaryForTest),
                "on to the next conflict still to decide");

        FxHarness.run(() -> dialog.resolveActiveForTest(MergeToolDialog.Choice.BOTH));
        assertTrue(FxHarness.call(dialog::resultTextForTest).contains("lower\nours three\ntheirs three\nbottom"),
                "Both keeps ours then theirs, on the next conflict");
        assertEquals(List.of(State.UNRESOLVED, State.THEIRS, State.BOTH), FxHarness.call(dialog::statesForTest));

        FxHarness.run(dialog::undoForTest);
        assertEquals(result, FxHarness.call(dialog::resultTextForTest), "each choice is one undoable edit");
        assertEquals(List.of(State.UNRESOLVED, State.THEIRS, State.UNRESOLVED), FxHarness.call(dialog::statesForTest),
                "and undoing it leaves the conflict undecided again");
        FxHarness.run(dialog::redoForTest);
        assertEquals(State.BOTH, FxHarness.call(dialog::statesForTest).get(2));

        FxHarness.run(() -> {
            dialog.moveCaretForTest(1);
            dialog.resolveActiveForTest(MergeToolDialog.Choice.OURS);
        });
        assertEquals("All conflicts resolved", FxHarness.call(dialog::conflictSummaryForTest));
        assertEquals(String.join("\n", "top", "ours one", "middle", "theirs two", "lower", "ours three",
                "theirs three", "bottom", ""), FxHarness.call(dialog::returnedTextForTest));
    }

    /**
     * Picking lines one by one: a conflict's result is its ticked ours lines, then its
     * ticked theirs lines. Nothing ticked is undecided again.
     */
    @Test
    void tickingLinesBuildsTheConflictsResult() {
        String base = "a\nb\nc\n";
        String ours = "a\no1\no2\nc\n";
        String theirs = "a\nt1\nt2\nc\n";
        String merged = "a\n<<<<<<< HEAD\no1\no2\n=======\nt1\nt2\n>>>>>>> f\nc\n";
        MergeToolDialog dialog = FxHarness.call(() -> new MergeToolDialog("f.txt", ours, theirs, base, merged));

        assertEquals(java.util.Map.of(1, false, 2, false), FxHarness.call(() -> dialog.ticksForTest(true)),
                "a tick box beside each of the conflict's lines in ours");
        FxHarness.run(() -> dialog.tickForTest(false, 0, 1));
        assertEquals("a\nt2\nc\n", FxHarness.call(dialog::resultTextForTest));
        FxHarness.run(() -> dialog.tickForTest(true, 0, 0));
        assertEquals("a\no1\nt2\nc\n", FxHarness.call(dialog::resultTextForTest),
                "ours first, whichever was ticked first");
        assertEquals(State.PICKED, FxHarness.call(dialog::statesForTest).get(0));
        assertEquals(java.util.Map.of(1, true, 2, false), FxHarness.call(() -> dialog.ticksForTest(true)));
        assertEquals("a\no1\nt2\nc\n", FxHarness.call(dialog::returnedTextForTest), "picked lines are resolved");

        FxHarness.run(() -> dialog.resolveActiveForTest(MergeToolDialog.Choice.OURS));
        assertEquals(java.util.Map.of(1, true, 2, true), FxHarness.call(() -> dialog.ticksForTest(true)),
                "Use ours is every line of ours ticked");
        assertEquals(java.util.Map.of(1, false, 2, false), FxHarness.call(() -> dialog.ticksForTest(false)));

        FxHarness.run(dialog::undoForTest);
        assertEquals(java.util.Map.of(1, true, 2, false), FxHarness.call(() -> dialog.ticksForTest(true)),
                "undo puts the ticks back with the text");
        assertEquals(java.util.Map.of(1, false, 2, true), FxHarness.call(() -> dialog.ticksForTest(false)));

        FxHarness.run(() -> {
            dialog.tickForTest(true, 0, 0);
            dialog.tickForTest(false, 0, 1);
        });
        assertEquals(base, FxHarness.call(dialog::resultTextForTest), "nothing ticked shows the ancestor again");
        assertEquals(State.UNRESOLVED, FxHarness.call(dialog::statesForTest).get(0));
        assertEquals(merged, FxHarness.call(dialog::returnedTextForTest), "and goes back with its markers");
    }

    /** A pane header's tick takes that side in every conflict, and clears it again. */
    @Test
    void aHeaderTickTakesThatSideInEveryConflict() {
        MergeToolDialog dialog = threeConflicts();
        FxHarness.run(() -> dialog.tickAllForTest(true));
        assertEquals(OURS, FxHarness.call(dialog::resultTextForTest));
        assertEquals(List.of(State.OURS, State.OURS, State.OURS), FxHarness.call(dialog::statesForTest));
        FxHarness.run(() -> dialog.tickAllForTest(false));
        assertEquals(List.of(State.BOTH, State.BOTH, State.BOTH), FxHarness.call(dialog::statesForTest),
                "theirs ticked too: both");
        FxHarness.run(() -> {
            dialog.tickAllForTest(true);
            dialog.tickAllForTest(false);
        });
        assertEquals(BASE, FxHarness.call(dialog::resultTextForTest), "nothing ticked anywhere: undecided again");
        FxHarness.run(dialog::undoForTest);
        assertEquals(List.of(State.THEIRS, State.THEIRS, State.THEIRS), FxHarness.call(dialog::statesForTest),
                "one header press is one undo");
    }

    /**
     * Typing in a conflict's lines resolves it with what was typed, and its ticks clear;
     * typing the ancestor's lines back makes it undecided again.
     */
    @Test
    void typingInAConflictResolvesItWithWhatWasTyped() {
        MergeToolDialog dialog = threeConflicts();
        FxHarness.run(() -> dialog.resolveActiveForTest(MergeToolDialog.Choice.OURS));
        FxHarness.run(() -> dialog.typeForTest(1, 0, 0, "my "));
        assertEquals(State.EDITED, FxHarness.call(dialog::statesForTest).get(0));
        assertEquals(java.util.Map.of(1, false, 3, false, 5, false), FxHarness.call(() -> dialog.ticksForTest(true)),
                "typed over, the conflict has no ticks");
        assertTrue(FxHarness.call(dialog::returnedTextForTest).startsWith("top\nmy ours one\nmiddle\n<<<<<<<"));

        FxHarness.run(() -> dialog.typeForTest(3, 0, 3, "two\nand more"));
        assertEquals(State.EDITED, FxHarness.call(dialog::statesForTest).get(1));
        assertEquals(new ConflictRegions.Slot(1, 3, 2, ConflictRegions.Resolution.UNDECIDED),
                FxHarness.call(dialog::slotsForTest).get(1), "a line typed into a conflict is one of its lines");
        FxHarness.run(() -> dialog.typeForTest(3, 3, 9, ""));
        assertEquals(State.UNRESOLVED, FxHarness.call(dialog::statesForTest).get(1),
                "typed back to the ancestor's lines, it is undecided again");
        assertEquals(new ConflictRegions.Slot(2, 5, 1, ConflictRegions.Resolution.UNDECIDED),
                FxHarness.call(dialog::slotsForTest).get(2), "and the next conflict moved with the lines");
    }

    /**
     * Taking the side that deleted a conflict's lines leaves nothing in the result - and
     * the conflict can still be changed: its lines come back where they were.
     */
    @Test
    void aConflictResolvedToNothingCanBeChangedAgain() {
        String base = "a\nb\nc\n";
        String merged = "a\n<<<<<<< HEAD\n=======\nB\n>>>>>>> f\nc\n";
        MergeToolDialog dialog = FxHarness.call(() -> new MergeToolDialog("f.txt", "a\nc\n", "a\nB\nc\n", base, merged));
        assertEquals(base, FxHarness.call(dialog::resultTextForTest));

        FxHarness.run(() -> dialog.resolveActiveForTest(MergeToolDialog.Choice.OURS));
        assertEquals("a\nc\n", FxHarness.call(dialog::resultTextForTest));
        assertEquals("a\nc\n", FxHarness.call(dialog::returnedTextForTest));
        assertEquals(List.of(State.OURS), FxHarness.call(dialog::statesForTest));
        assertEquals("All conflicts resolved", FxHarness.call(dialog::conflictSummaryForTest));

        FxHarness.run(() -> dialog.resolveActiveForTest(MergeToolDialog.Choice.THEIRS));
        assertEquals("a\nB\nc\n", FxHarness.call(dialog::resultTextForTest));
        FxHarness.run(() -> {
            dialog.undoForTest();
            dialog.undoForTest();
        });
        assertEquals(base, FxHarness.call(dialog::resultTextForTest));
        assertEquals(List.of(State.UNRESOLVED), FxHarness.call(dialog::statesForTest));
        assertEquals(merged, FxHarness.call(dialog::returnedTextForTest));
    }

    /** Both sides added different lines at one place: an empty line stands in, captioned. */
    @Test
    void aConflictWithNoAncestorLinesShowsACaptionedPlaceholder() {
        String merged = "a\n<<<<<<< HEAD\nX\n=======\nY\n>>>>>>> f\nc\n";
        MergeToolDialog dialog = FxHarness.call(() ->
                new MergeToolDialog("f.txt", "a\nX\nc\n", "a\nY\nc\n", "a\nc\n", merged));
        assertEquals("a\n\nc\n", FxHarness.call(dialog::resultTextForTest));
        assertEquals(MergeToolDialog.PLACEHOLDER_CAPTION, FxHarness.call(() -> dialog.captionForTest(1)));
        assertEquals(merged, FxHarness.call(dialog::returnedTextForTest), "the placeholder is never written");

        FxHarness.run(() -> dialog.resolveActiveForTest(MergeToolDialog.Choice.BOTH));
        assertNull(FxHarness.call(() -> dialog.captionForTest(1)));
        assertEquals("a\nX\nY\nc\n", FxHarness.call(dialog::returnedTextForTest));
    }

    /** The side panes carry the alternatives: each conflict's lines in that side's colour. */
    @Test
    void theSidePanesTintEachConflictsLines() {
        MergeToolDialog dialog = threeConflicts();
        FxHarness.run(() -> {
            for (int line : List.of(1, 3, 5)) {
                assertEquals(List.of("merge-ours-line"), List.copyOf(dialog.lineStyleForTest("ours", line)));
                assertEquals(List.of("merge-theirs-line"), List.copyOf(dialog.lineStyleForTest("theirs", line)));
                assertEquals(List.of("merge-base-line"), List.copyOf(dialog.lineStyleForTest("base", line)));
            }
            assertTrue(dialog.lineStyleForTest("ours", 2).isEmpty(), "agreed lines are not a conflict's");
            dialog.resolveActiveForTest(MergeToolDialog.Choice.BOTH);
        });
        FxHarness.run(() -> {
            assertEquals(List.of("merge-ours-line"), List.copyOf(dialog.lineStyleForTest("result", 1)));
            assertEquals(List.of("merge-theirs-line"), List.copyOf(dialog.lineStyleForTest("result", 2)),
                    "in the result, each kept line in the colour of the side it came from");
        });
    }

    /** Moving to a conflict scrolls the side panes to it. */
    @Test
    void theSidePanesScrollToTheConflictBeingWorkedOn() {
        List<String> base = new ArrayList<>();
        for (int i = 0; i < 200; i++) base.add("line " + i);
        List<String> ours = new ArrayList<>(base);
        List<String> theirs = new ArrayList<>(base);
        List<String> merged = new ArrayList<>(base);
        ours.set(20, "ours 20");
        theirs.set(20, "theirs 20");
        ours.set(150, "ours 150");
        theirs.set(150, "theirs 150");
        merged.set(150, "<<<<<<< HEAD\nours 150\n=======\ntheirs 150\n>>>>>>> f");
        merged.set(20, "<<<<<<< HEAD\nours 20\n=======\ntheirs 20\n>>>>>>> f");
        MergeToolDialog dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            MergeToolDialog shown = new MergeToolDialog("f.txt", String.join("\n", ours), String.join("\n", theirs),
                    String.join("\n", base), String.join("\n", merged));
            shown.initOwner(owner);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the side panes never showed the first conflict",
                    () -> dialog.firstVisibleForTest("ours") <= 20 && dialog.firstVisibleForTest("theirs") <= 20);
            FxHarness.run(() -> dialog.resolveActiveForTest(MergeToolDialog.Choice.OURS));
            RobotWait.untilOnFx("the side panes never scrolled to the second conflict",
                    () -> Math.abs(dialog.firstVisibleForTest("ours") - 147) <= 1
                            && Math.abs(dialog.firstVisibleForTest("theirs") - 147) <= 1);
        } finally {
            FxHarness.run(() -> dialog.getDialogPane().getScene().getWindow().hide());
        }
    }

    @Test
    void theBasePaneIsThereWhenAskedFor() {
        MergeToolDialog dialog = FxHarness.call(() ->
                new MergeToolDialog("f.txt", "ours", "theirs", "base", THREE_CONFLICTS));
        assertEquals(2, (int) FxHarness.call(dialog::sidePaneCountForTest));
        FxHarness.run(() -> dialog.showBaseForTest(true));
        assertEquals(3, (int) FxHarness.call(dialog::sidePaneCountForTest));
        FxHarness.run(() -> dialog.showBaseForTest(false));
        assertEquals(2, (int) FxHarness.call(dialog::sidePaneCountForTest));
    }

    /**
     * Open in external tool closes the window without a result - nothing is written - and
     * then starts the tool.
     */
    @Test
    void theExternalToolClosesTheEditorWithoutWritingAndThenOpens() {
        java.util.concurrent.atomic.AtomicBoolean opened = new java.util.concurrent.atomic.AtomicBoolean();
        MergeToolDialog dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            MergeToolDialog shown = new MergeToolDialog("f.txt", OURS, THEIRS, BASE, MERGED);
            shown.offerExternalTool(() -> opened.set(true));
            shown.initOwner(owner);
            shown.show();
            return shown;
        });
        FxHarness.run(() -> FxHarness.lookupAll(dialog.getDialogPane(), "merge-external").stream()
                .map(javafx.scene.control.Button.class::cast).findFirst().orElseThrow().fire());
        RobotWait.untilOnFx("the external tool never started", opened::get);
        FxHarness.run(() -> {
            assertFalse(dialog.isShowing());
            assertNull(dialog.getResult(), "nothing to write");
        });
    }

    @Test
    void theIncomingSideIsNamedFromAMergesMarker() {
        String merged = "a\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> feature/refunds\nb\n";

        assertEquals("feature/refunds", MergeToolDialog.incomingLabel(merged));
    }

    @Test
    void theIncomingSideIsTheReplayedCommitDuringARebase() {
        String merged = "<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> 90c5ca7 (Add refund service)\r\n";

        assertEquals("90c5ca7 (Add refund service)", MergeToolDialog.incomingLabel(merged),
                "a CRLF file's marker must not keep its carriage return");
    }

    @Test
    void anUnlabelledOrMissingMarkerLeavesTheGenericWording() {
        assertNull(MergeToolDialog.incomingLabel("<<<<<<<\nx\n=======\ny\n>>>>>>>\n"));
        assertNull(MergeToolDialog.incomingLabel("no conflict here\n"));
        assertNull(MergeToolDialog.incomingLabel(null));
        assertEquals("Theirs (incoming)", MergeToolDialog.sideTitle("Theirs", null, "incoming"));
    }

    // ------------------------------------------------------------------
    // Find
    // ------------------------------------------------------------------

    private static javafx.scene.input.KeyEvent shortcut(javafx.scene.input.KeyCode code) {
        return new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code,
                false, true, false, false);
    }

    /**
     * Ctrl+H anywhere in the dialog replaces in the result, the one pane that is an editor
     * - in its clean text, and across conflicts without handing one's lines to another.
     */
    @Test
    void findAndReplaceWorkOnTheResult() {
        MergeToolDialog dialog = threeConflicts();
        FxHarness.run(() -> {
            com.jfx.git.ui.FindBar bar = dialog.findBarForTest();
            assertTrue(!bar.isVisible(), "closed until asked for");
            dialog.getDialogPane().fireEvent(shortcut(javafx.scene.input.KeyCode.H));
            assertTrue(bar.isFinding(), "Ctrl+H opens the bar");
            javafx.scene.control.TextField query =
                    (javafx.scene.control.TextField) bar.lookup("#" + com.jfx.git.ui.FindBar.QUERY_ID);
            javafx.scene.control.TextField with =
                    (javafx.scene.control.TextField) bar.lookup("#" + com.jfx.git.ui.FindBar.REPLACE_ID);
            assertTrue(with.isVisible(), "with replace, since the result can be edited");
            query.setText("o");
            with.setText("0");
            javafx.scene.control.Button replaceAll = bar.lookupAll(".button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b && "Replace all".equals(b.getText()))
                    .map(n -> (javafx.scene.control.Button) n)
                    .findFirst().orElseThrow();
            replaceAll.fire();
        });
        String result = FxHarness.call(dialog::resultTextForTest);
        assertEquals(BASE.replace('o', '0'), result);
        assertEquals(List.of(1, 3, 5), FxHarness.call(dialog::slotsForTest).stream()
                .map(ConflictRegions.Slot::start).toList(), "each conflict keeps its own line");
        assertEquals(List.of(State.EDITED, State.EDITED, State.UNRESOLVED), FxHarness.call(dialog::statesForTest),
                "a replacement in a conflict's lines resolves it; \"three\" has no o, and stays undecided");
        FxHarness.run(dialog::undoForTest);
        assertEquals(BASE, FxHarness.call(dialog::resultTextForTest), "the whole replace is one undo");
        assertEquals(List.of(State.UNRESOLVED, State.UNRESOLVED, State.UNRESOLVED),
                FxHarness.call(dialog::statesForTest));
    }

    @Test
    void goToLineMovesTheResultsCaret() {
        MergeToolDialog dialog = threeConflicts();
        FxHarness.run(() -> {
            dialog.getDialogPane().fireEvent(shortcut(javafx.scene.input.KeyCode.G));
            com.jfx.git.ui.FindBar bar = dialog.findBarForTest();
            assertTrue(bar.isVisible() && !bar.isFinding(), "Ctrl+G opens go to line, not find");
            javafx.scene.control.TextField line =
                    (javafx.scene.control.TextField) bar.lookup("#" + com.jfx.git.ui.FindBar.LINE_ID);
            line.setText("4");
            line.fireEvent(new javafx.event.ActionEvent());
            assertTrue(!bar.isVisible(), "done, the bar closes");
        });
        FxHarness.run(() -> dialog.resolveActiveForTest(MergeToolDialog.Choice.THEIRS));
        String result = FxHarness.call(dialog::resultTextForTest);
        assertTrue(result.contains("middle\ntheirs two\nlower"),
                "line 4 is the second conflict, so that is the one resolved: " + result);
    }

    // ------------------------------------------------------------------
    // Theme
    // ------------------------------------------------------------------

    /**
     * The incoming side of a conflict is tinted. Its rule named {@code -color-attention-*},
     * which neither Primer nor the token files define, so nothing was painted - and JavaFX
     * logged a ClassCastException per row - while the ours side was tinted beside it.
     */
    @Test
    void theirsLinesAreTinted() {
        inTheme(ThemeManager.Theme.DARK, dialog -> {
            List<Node> theirs = FxHarness.lookupAll(dialog.getDialogPane(), "merge-theirs-line");
            assertFalse(theirs.isEmpty(), "three conflicts, each with a theirs line");
            for (Node row : theirs) {
                assertTrue(fill(row) != null && fill(row).getOpacity() > 0,
                        () -> "a theirs line has no tint: " + ((Region) row).getBackground());
            }
        });
    }

    /**
     * An undecided conflict's lines are tinted in the "done" violet, a token of the
     * application's own that resolves in both themes.
     */
    @Test
    void unresolvedLinesAreTintedInBothThemes() {
        for (ThemeManager.Theme theme : List.of(ThemeManager.Theme.DARK, ThemeManager.Theme.LIGHT)) {
            inTheme(theme, dialog -> {
                List<Node> rows = FxHarness.lookupAll(dialog.getDialogPane(), "merge-unresolved-line");
                assertFalse(rows.isEmpty(), "three undecided conflicts");
                for (Node row : rows) {
                    Color tint = fill(row);
                    assertTrue(tint != null && tint.getOpacity() > 0 && tint.getHue() > 240 && tint.getHue() < 320,
                            () -> theme + ": an undecided line is not violet: " + tint);
                }
            });
        }
    }

    /**
     * The gutter follows the theme. RichTextFX's stock one paints {@code #ddd} in code,
     * which no stylesheet reaches, so every pane had a light-grey strip down its edge in
     * the dark theme.
     */
    @Test
    void theGutterIsDarkInTheDarkTheme() {
        inTheme(ThemeManager.Theme.DARK, dialog -> {
            List<Node> gutters = FxHarness.lookupAll(dialog.getDialogPane(), "diff-gutter");
            assertFalse(gutters.isEmpty(), "every pane has a gutter");
            for (Node gutter : gutters) {
                Color ground = fill(gutter);
                assertTrue(ground != null && ground.getBrightness() < 0.3,
                        () -> "a light gutter in the dark theme: " + ground);
                Label number = (Label) FxHarness.lookupAll(gutter, "diff-line-number").get(0);
                Color ink = (Color) number.getTextFill();
                assertTrue(ink.getBrightness() > ground.getBrightness() + 0.3,
                        () -> "line numbers should read against their gutter: " + ink + " on " + ground);
            }
        });
    }

    /**
     * Shows a conflict over a themed window, as the application does, and runs {@code check}
     * against it on the FX thread. The theme is switched and put back on the FX thread too:
     * a theme change made off it surfaces later, as some other test's failure.
     */
    private static void inTheme(ThemeManager.Theme theme, Consumer<MergeToolDialog> check) {
        ThemeManager.Theme before = FxHarness.call(ThemeManager::current);
        MergeToolDialog dialog = FxHarness.call(() -> {
            ThemeManager.apply(theme);
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            MergeToolDialog shown = new MergeToolDialog("f.txt", OURS, THEIRS, BASE, MERGED);
            shown.initOwner(owner);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the merge tool never drew its result",
                    () -> !FxHarness.lookupAll(dialog.getDialogPane(), "merge-theirs-line").isEmpty()
                            && !FxHarness.lookupAll(dialog.getDialogPane(), "merge-unresolved-line").isEmpty());
            FxHarness.run(() -> check.accept(dialog));
        } finally {
            FxHarness.run(() -> {
                dialog.getDialogPane().getScene().getWindow().hide();
                ThemeManager.apply(before);
            });
        }
    }

    /** The colour a node's background is painted, or null for none. */
    private static Color fill(Node node) {
        Background background = ((Region) node).getBackground();
        return background == null || background.getFills().isEmpty()
                ? null
                : (Color) background.getFills().get(0).getFill();
    }

    /**
     * The header names the file and puts its folder underneath, rather than repeating a
     * whole path that the window title already carries.
     */
    @Test
    void theHeaderSplitsThePathIntoFileAndFolder() {
        assertArrayEquals(new String[]{"Resolve conflict `PaymentService.java`",
                        "In `src/main/java/com/shop/payments`"},
                MergeToolDialog.headingFor("Resolve conflict - src/main/java/com/shop/payments/PaymentService.java"));
        assertArrayEquals(new String[]{"Recovered draft `README.md`", null},
                MergeToolDialog.headingFor("Recovered draft - README.md"));
        assertArrayEquals(new String[]{"Resolve conflict", null},
                MergeToolDialog.headingFor("Resolve conflict"));
    }
}
