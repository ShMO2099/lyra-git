package com.jfx.git.ui;

import com.jfx.git.ui.syntax.HighlightLane;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a render costs the text surface, in the units that used to make it slow: document
 * edits, retained undo history, and work per scroll pulse proportional to the diff
 * rather than to the screen.
 *
 * <p>Counts rather than timings, so these hold on any machine: a six-thousand-line file
 * was rendered as about twelve thousand separate RichTextFX edits - a paragraph style and
 * a style-span replacement per row, each a change event through every subscriber and an
 * undo record kept for the life of the surface - and a scroll pulse walked every hunk and
 * scanned the rows from the top for each. {@code DiffViewerPerformanceTest} keeps the
 * timings.
 */
class DiffViewerRenderCostTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        HighlightLane.setSynchronousForTest(true);
        FxHarness.closeShownStages();
    }

    private static DiffViewer showViewer() {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 1000, 700);
            stage.setUserData("keep-alive");
            return viewer;
        });
    }

    private static DiffTextArea areaOf(DiffViewer viewer) {
        return (DiffTextArea) FxHarness.lookupAll(viewer, "code-area").get(0);
    }

    /** {@code count} lines of Java, every one of them with something to colour. */
    private static String javaFile(int count) {
        StringBuilder out = new StringBuilder("class Big {\n");
        for (int i = 1; i < count - 1; i++) {
            out.append("    String field").append(i).append(" = \"value\"; // note\n");
        }
        return out.append("}\n").toString();
    }

    private static boolean hasToken(DiffTextArea area, int paragraph) {
        for (var span : area.getParagraph(paragraph).getStyleSpans()) {
            for (String style : span.getStyle()) {
                if (style.startsWith("tm-")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void aHighlightedFileIsRenderedAsOneDocumentEdit() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            int[] edits = {0};
            area.richChanges().subscribe(change -> edits[0]++);

            viewer.showFileContent("src/Big.java", javaFile(3000));
            FxHarness.pulse(viewer);

            assertEquals(1, edits[0],
                    "text, paragraph styles and colours should arrive as one edit, not one per row");
            assertTrue(hasToken(area, 1) && hasToken(area, 2998),
                    "and the colours are in it, first line to last");
        });
    }

    @Test
    void aDiffKeepsItsParagraphStylesAndWordDiffInTheOneEdit() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            int[] edits = {0};
            area.richChanges().subscribe(change -> edits[0]++);

            viewer.setFileDiff("src/Main.java", """
                    @@ -1,3 +1,3 @@
                     class Main {
                    -    String name = "old";
                    +    String name = "new";
                     }
                    """);
            FxHarness.pulse(viewer);

            assertEquals(1, edits[0]);
            assertTrue(area.getParagraph(2).getParagraphStyle().contains("diff-deletion"));
            assertTrue(area.getParagraph(3).getParagraphStyle().contains("diff-addition"));
            assertTrue(area.getParagraph(0).getParagraphStyle().contains("diff-hunk-header-row"));
            int changed = area.getParagraph(3).getText().indexOf("new");
            assertTrue(area.getParagraph(3).getStyleOfChar(changed).contains("diff-inline-add"),
                    "the word diff rides in the same edit");
            assertTrue(area.getParagraph(1).getParagraphStyle().isEmpty(),
                    "a context row carries no paragraph style");
        });
    }

    @Test
    void aFileOpeningOnABlankLineDoesNotInheritTheLastDiffsFirstRow() {
        // RichTextFX joins a replacement's empty first paragraph onto the old first one,
        // style and all; per-row restyling used to cover that up.
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            viewer.setFileDiff("x.txt", "@@ -1 +1 @@\n-a\n+b\n");
            assertTrue(areaOf(viewer).getParagraph(0).getParagraphStyle().contains("diff-hunk-header-row"));
            viewer.showFileContent("y.txt", "\nsecond line\n");
            assertTrue(areaOf(viewer).getParagraph(0).getParagraphStyle().isEmpty(),
                    "the blank first line is plain: " + areaOf(viewer).getParagraph(0).getParagraphStyle());
        });
    }

    @Test
    void aReadOnlySurfaceKeepsNoUndoHistory() {
        // Every render replaced the whole document as an undoable change, and a read-only
        // surface kept all of them: every file ever viewed, text and styles, for as long
        // as the viewer lived.
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            viewer.showFileContent("a.txt", "one\ntwo");
            viewer.showFileContent("b.txt", "three\nfour");
            viewer.setFileDiff("c.txt", "@@ -1 +1 @@\n-x\n+y\n");
            assertFalse(area.isUndoAvailable(), "a display surface has nothing to undo");

            area.setEditable(true);
            area.insertText(0, "typed ");
            assertTrue(area.isUndoAvailable(), "an editable one undoes typing");
            area.undo();
            assertFalse(area.getText().startsWith("typed"));

            area.setEditable(false);
            area.insertText(0, "again ");
            assertFalse(area.isUndoAvailable(), "and stops keeping history when it is read-only again");
        });
    }

    @Test
    void aLongFileIsColouredInChunksAfterItsText() throws Exception {
        // Past the old six-thousand-line cap, and off the FX thread: the text goes up as
        // one edit and the colours follow a chunk at a time, each chunk one more edit.
        HighlightLane.setSynchronousForTest(false);
        DiffViewer viewer = showViewer();
        int lines = 8000;
        int[] edits = {0};
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            area.richChanges().subscribe(change -> edits[0]++);
            viewer.showFileContent("src/Big.java", javaFile(lines));
            FxHarness.pulse(viewer);
            assertEquals(1, edits[0], "the text is up before any colour");
        });
        HighlightLane.awaitIdleForTest();
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            assertTrue(hasToken(area, 1), "the top is coloured");
            assertTrue(hasToken(area, lines - 2), "and so is the last line, past the old cap");
            int chunks = 1 + (int) Math.ceil((lines - DiffViewer.FIRST_CHUNK_ROWS)
                    / (double) DiffViewer.CHUNK_ROWS);
            assertTrue(edits[0] > 1 && edits[0] <= 1 + chunks + 1,
                    "one edit for the text, one per chunk, at most one for the screen first: " + edits[0]);
        });
    }

    @Test
    void aDisplayToggleReusesTheRowsAndTheirColours() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            viewer.showFileContent("src/Big.java", javaFile(2000));
            FxHarness.pulse(viewer);
            DiffTextArea area = areaOf(viewer);
            int[] edits = {0};
            area.richChanges().subscribe(change -> edits[0]++);

            ToggleButton wrap = (ToggleButton) viewer.lookupAll(".diff-tool-toggle").stream()
                    .filter(node -> DiffViewer.NAME_WRAP.equals(((ToggleButton) node).getAccessibleText()))
                    .findFirst().orElseThrow();
            wrap.fire();
            FxHarness.pulse(viewer);

            assertEquals(1, edits[0], "the colours this file already had go straight back in");
            assertTrue(hasToken(area, 1998));
        });
    }

    /** {@code hunks} hunks of nine rows each: a header, three context, a change, three context. */
    private static String manyHunks(int hunks) {
        StringBuilder diff = new StringBuilder();
        int line = 1;
        for (int hunk = 0; hunk < hunks; hunk++) {
            diff.append("@@ -").append(line).append(",7 +").append(line).append(",7 @@\n");
            for (int i = 0; i < 3; i++) {
                diff.append(" context ").append(line + i).append('\n');
            }
            diff.append("-before ").append(hunk).append('\n');
            diff.append("+after ").append(hunk).append('\n');
            for (int i = 0; i < 3; i++) {
                diff.append(" trailing ").append(line + i).append('\n');
            }
            line += 20;
        }
        return diff.toString();
    }

    @Test
    void scrollingAThousandHunkDiffPlacesBarsAndCountsFromTheRowsOnScreen() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            viewer.setOnHunkAction(action -> { });
            viewer.setFileDiff(DiffKind.UNSTAGED, "src/Many.java", manyHunks(1000));
            FxHarness.pulse(viewer);
            areaOf(viewer).showParagraphAtTop(9 * 500);
            FxHarness.pulse(viewer);
        });
        // The overlays follow once the pass that scrolled is over.
        FxHarness.run(() -> {
            Matcher counter = Pattern.compile("Change (\\d+) of 1000").matcher(viewer.changeCounterText());
            assertTrue(counter.matches(), viewer.changeCounterText());
            int shown = Integer.parseInt(counter.group(1));
            assertTrue(shown >= 499 && shown <= 503, "the counter follows the viewport: " + shown);

            List<Node> bars = FxHarness.lookupAll(viewer, "diff-hunk-actions").stream()
                    .filter(Node::isVisible).toList();
            assertFalse(bars.isEmpty(), "the hunks on screen carry their bars");
            assertTrue(bars.size() < 20, "and only those: " + bars.size());
        });
    }

    @Test
    void theScrollMapMergesRunsThatShareATrackPixel() {
        List<int[]> runs = new ArrayList<>();
        for (int row = 0; row < 30_000; row += 3) {
            runs.add(new int[]{row, row + 1});
        }
        List<int[]> merged = DiffScrollMap.bucketed(runs, 30_000);
        assertTrue(merged.size() <= DiffScrollMap.TRACK_BUCKETS,
                "ten thousand runs on one strip need no more nodes than it has buckets: " + merged.size());
        assertEquals(0, merged.get(0)[0]);
        assertEquals(29_998, merged.get(merged.size() - 1)[1], "the merged marks still cover every run");

        List<int[]> few = List.of(new int[]{2, 3}, new int[]{5, 6});
        assertEquals(2, DiffScrollMap.bucketed(few, 500).size(),
                "a file with a bucket per row keeps every run apart, as before");
    }

    @Test
    void spansShareOneCollectionPerCombinationOfLayers() {
        var a = DiffTextArea.spans(10, List.of(new DiffTextArea.StyleRange(0, 5, "tm-a"),
                new DiffTextArea.StyleRange(3, 8, "inline")), offset -> offset);
        var b = DiffTextArea.spans(10, List.of(new DiffTextArea.StyleRange(0, 5, "tm-a")), offset -> offset);
        List<Collection<String>> styles = new ArrayList<>();
        a.forEach(span -> styles.add(span.getStyle()));
        assertEquals(List.of(List.of("tm-a"), List.of("tm-a", "inline"), List.of("inline"), List.of()),
                styles, "layers stack in range order, a run per distinct stack");
        assertTrue(styles.get(0) == b.getStyleSpan(0).getStyle(),
                "the same stack is the same collection, which the minimap resolves by identity");
        assertEquals(10, a.length());
    }
}
