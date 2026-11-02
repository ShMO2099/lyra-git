package com.jfx.git.ui;

import com.jfx.git.core.GitResult;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders a real viewer on a real toolkit.
 *
 * <p>The row tests prove the model; these prove the model reaches the screen. They are
 * what stands in for the throwaway spike the plan called for: the open question was
 * whether a hunk action can be positioned over a single scrolling text area, and
 * {@link #hunkActionAppearsOverTheHunkUnderThePointer()} answers it against the real
 * control rather than by argument.
 */
class DiffViewerRenderTest {

    /**
     * Closes the off-screen stages this class opened.
     *
     * <p>Not housekeeping. A stage left showing goes on taking part in focus and layout
     * for the rest of the run, and the failures that causes land in whichever robot test
     * happens to follow - a click that reaches nothing, a dialog that "never opened".
     */
    @org.junit.jupiter.api.AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private static final String DIFF = """
            @@ -1,3 +1,3 @@
             context
            -removed
            +added
            @@ -10,2 +10,3 @@
             kept
            +extra
            """;

    /**
     * {@link #DIFF} with each hunk's lines adding up to its header, as git's always do: a
     * hunk that does not is refused hunk actions, so the tests of those actions use this.
     */
    private static final String ACTIONABLE_DIFF = """
            @@ -1,3 +1,3 @@
             context
            -removed
            +added
             more
            @@ -10,2 +10,3 @@
             kept
            +extra
             end
            """;

    /** Builds a viewer showing {@link #DIFF} on a laid-out, off-screen stage. */
    private static DiffViewer showViewer() {
        return viewerShowing(DiffKind.COMPARISON, DIFF);
    }

    /** A viewer showing {@link #ACTIONABLE_DIFF}, loaded as {@code kind}. */
    private static DiffViewer showViewer(DiffKind kind) {
        return viewerShowing(kind, ACTIONABLE_DIFF);
    }

    private static DiffViewer viewerShowing(DiffKind kind, String diff) {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 900, 600);
            viewer.setFileDiff(kind, "src/Main.java", diff);
            FxHarness.pulse(viewer);
            stage.setUserData("keep-alive");
            return viewer;
        });
    }

    private static DiffTextArea areaOf(DiffViewer viewer) {
        List<Node> areas = FxHarness.lookupAll(viewer, "code-area");
        assertFalse(areas.isEmpty(), "the viewer rendered no text surface");
        return (DiffTextArea) areas.get(0);
    }

    // ------------------------------------------------------------------
    // One surface
    // ------------------------------------------------------------------

    @Test
    void everyHunkRendersIntoOneSurface() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            assertEquals(1, FxHarness.lookupAll(viewer, "code-area").size(),
                    "unified view must be a single area, not one per hunk");

            DiffTextArea area = areaOf(viewer);
            // Two headers, three lines in the first hunk, two in the second.
            assertEquals(7, area.getParagraphs().size());
            assertEquals("@@ -1,3 +1,3 @@", area.getParagraph(0).getText());
            assertEquals("removed", area.getParagraph(2).getText());
            assertEquals("@@ -10,2 +10,3 @@", area.getParagraph(4).getText());
        });
    }

    @Test
    void selectionSpansHunkBoundaries() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            area.selectRange(0, area.getLength());
            String selected = area.getSelectedText();
            // A selection that reaches both hunks is the thing per-hunk areas could not do.
            assertTrue(selected.contains("removed"), selected);
            assertTrue(selected.contains("extra"), selected);
        });
    }

    @Test
    void everyRowGetsAGutter() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            List<Node> gutters = FxHarness.lookupAll(viewer, "diff-gutter");
            assertFalse(gutters.isEmpty(), "no gutter was rendered");
        });
    }

    // ------------------------------------------------------------------
    // The spike: floating a hunk action over the surface
    // ------------------------------------------------------------------

    @Test
    void everyVisibleHunkCarriesItsOwnActionsWithNoHoverNeeded() {
        // Commercial clients show Stage/Discard on every hunk permanently. Following the pointer
        // instead made the buttons close to unclickable: they float above the text, so
        // moving towards one left the text area and hid it before the click landed.
        DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
        FxHarness.run(() -> {
            viewer.setOnHunkAction(action -> { });
            FxHarness.pulse(viewer);

            // DIFF has two hunks and the test stage is tall enough for both.
            List<Node> bars = FxHarness.lookupAll(viewer, "diff-hunk-actions").stream()
                    .filter(Node::isVisible).toList();
            assertEquals(2, bars.size(),
                    "each hunk on screen should carry its own bar, without any hovering");

            for (Node actions : bars) {
                assertTrue(actions.getLayoutX() > 0, "a bar was not positioned horizontally");
                assertTrue(actions.getLayoutY() >= 0, "a bar was placed above the viewport");
                assertTrue(actions.getLayoutX() + actions.getBoundsInLocal().getWidth()
                                <= viewer.getWidth() + 1,
                        "a bar was pushed outside the viewer");
            }
            assertNotEquals(bars.get(0).getLayoutY(), bars.get(1).getLayoutY(),
                    "the two bars should sit at their own hunks, not on top of each other");
        });
    }

    @Test
    void characterBoundsAreAvailableForPositioning() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            // This is the API the overlay positions against; if it returns nothing for a
            // visible row, the whole overlay approach is unworkable.
            assertTrue(area.getCharacterBoundsOnScreen(0, 1).isPresent(),
                    "no screen bounds for a visible character");
        });
    }

    @Test
    void noHunkActionWithoutAHandler() {
        DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
        FxHarness.run(() -> {
            // A historical diff has nothing to revert into, so no bar should be placed
            // at all - "always visible" must not mean "visible even when inapplicable".
            assertTrue(FxHarness.lookupAll(viewer, "diff-hunk-actions").stream()
                            .noneMatch(Node::isVisible),
                    "no handler means there is nothing to offer, so no bars");
        });
    }

    // ------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------

    @Test
    void splitViewRendersTwoAlignedColumns() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);

            List<Node> areas = FxHarness.lookupAll(viewer, "code-area");
            assertEquals(2, areas.size(), "split view must render both columns");
            DiffTextArea left = (DiffTextArea) areas.get(0);
            DiffTextArea right = (DiffTextArea) areas.get(1);
            assertEquals(left.getParagraphs().size(), right.getParagraphs().size(),
                    "the columns must stay row for row in step");
        });
    }

    @Test
    void fileViewReplacesTheDiffWithTheWholeFile() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            viewer.showFileContent("src/Main.java", "one\ntwo\nthree");
            FxHarness.pulse(viewer);

            assertTrue(viewer.isFileView());
            DiffTextArea area = areaOf(viewer);
            assertEquals(3, area.getParagraphs().size());
            assertEquals("two", area.getParagraph(1).getText());
        });
    }

    /**
     * Regression test for a data-loss bug: {@code setFileDiffBytes} - the method every
     * production caller in RightPanelManager uses directly, bypassing {@code showDiffBytes}
     * - never cleared {@code fileContentOverride} or reset {@code mode}. A file opened whole
     * in File view stayed on screen, under a different file's path label, when the user then
     * selected a second file with a pending diff.
     */
    @Test
    void switchingToADirectSetFileDiffBytesCallDoesNotLeakThePreviousFilesContent() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.showFileBytes("A.txt", "AAAA-CONTENT".getBytes(StandardCharsets.UTF_8));
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            assertTrue(viewer.isFileView(), "showFileBytes should switch into File view");
            assertTrue(areaOf(viewer).getText().contains("AAAA-CONTENT"));

            // The exact call RightPanelManager's showDiff()/openWorkingTreeFile() make
            // directly, bypassing showDiffBytes.
            viewer.setFileDiffBytes("B.txt", ("""
                    @@ -1,1 +1,1 @@
                    -old
                    +BBBB-CHANGE
                    """).getBytes(StandardCharsets.UTF_8));
            FxHarness.pulse(viewer);

            assertFalse(viewer.isFileView(), "setFileDiffBytes must leave File view");
            String shown = areaOf(viewer).getText();
            assertFalse(shown.contains("AAAA-CONTENT"), "A's content leaked into B's view: " + shown);
            assertTrue(shown.contains("BBBB-CHANGE"), shown);
        });
    }

    @Test
    void blameRendersOneRowPerAnnotatedLine() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            viewer.showBlame("src/Main.java", List.of(
                    new com.jfx.git.core.BlameLine("abc1234", "Ada", "first", java.time.Instant.parse("2020-01-01T12:00:00Z"), "one"),
                    new com.jfx.git.core.BlameLine("abc1234", "Ada", "first", java.time.Instant.parse("2020-01-01T12:00:00Z"), "two")));
            FxHarness.pulse(viewer);

            DiffTextArea area = areaOf(viewer);
            assertEquals(2, area.getParagraphs().size());
            assertEquals("one", area.getParagraph(0).getText());
            assertFalse(FxHarness.lookupAll(viewer, "blame-message").isEmpty(),
                    "blame rows need their commit gutter");
        });
    }

    @Test
    void binaryEncodingShowsAHexDumpInsteadOfText() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            selectEncoding(viewer, com.jfx.git.core.TextEncoding.BINARY);
            FxHarness.pulse(viewer);

            DiffTextArea area = areaOf(viewer);
            assertTrue(area.getParagraph(0).getText().startsWith("00000000  "),
                    area.getParagraph(0).getText());
        });
    }

    @Test
    void switchingBackFromBinaryRestoresTheDiff() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            selectEncoding(viewer, com.jfx.git.core.TextEncoding.BINARY);
            selectEncoding(viewer, com.jfx.git.core.TextEncoding.UTF_8);
            FxHarness.pulse(viewer);

            assertEquals("@@ -1,3 +1,3 @@", areaOf(viewer).getParagraph(0).getText());
        });
    }

    // ------------------------------------------------------------------
    // LFS pointer files
    // ------------------------------------------------------------------

    private static final String POINTER_CONTENT =
            "version https://git-lfs.github.com/spec/v1\n"
                    + "oid sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e\n"
                    + "size 2000\n";

    private static final String POINTER_DIFF =
            "diff --git a/asset.psd b/asset.psd\n"
                    + "index 022056b..dc755ad 100644\n"
                    + "--- a/asset.psd\n"
                    + "+++ b/asset.psd\n"
                    + "@@ -1,3 +1,3 @@\n"
                    + " version https://git-lfs.github.com/spec/v1\n"
                    + "-oid sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e\n"
                    + "-size 2000\n"
                    + "+oid sha256:98175799ea197b92416ed7d9693f27dc8169a575680e6c08effc9f1d38e05d8f\n"
                    + "+size 500\n";

    private static Label placeholderOf(DiffViewer viewer) {
        List<Node> found = FxHarness.lookupAll(viewer, "diff-empty");
        assertFalse(found.isEmpty(), "the viewer has no placeholder label");
        return (Label) found.get(0);
    }

    @Test
    void aPointerFileOpenedInFileViewShowsTheLfsNoticeNotTheRawPointerText() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.showFileContent("asset.psd", POINTER_CONTENT);
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            String notice = placeholderOf(viewer).getText();
            assertTrue(notice.contains("Tracked by Git LFS"), notice);
            assertTrue(notice.contains("2.0 KB"), notice);
            assertFalse(areaOf(viewer).getText().contains("oid sha256"),
                    "the raw pointer text must not reach the text surface: "
                            + areaOf(viewer).getText());
        });
    }

    @Test
    void aModifiedPointerFileDiffShowsBothSidesSizesNotTheOidLines() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.showDiff("asset.psd", POINTER_DIFF);
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            String notice = placeholderOf(viewer).getText();
            assertTrue(notice.contains("2.0 KB"), notice);
            assertTrue(notice.contains("500 B"), notice);
            assertFalse(areaOf(viewer).getText().contains("oid sha256"),
                    "the confusing oid-line diff must not reach the text surface: "
                            + areaOf(viewer).getText());
        });
    }

    @Test
    void anOrdinaryFileIsUnaffectedByTheLfsCheck() {
        // showViewer()'s DIFF is plain text with no LFS-shaped content anywhere in it -
        // confirms the check is not somehow always firing.
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            assertEquals("", placeholderOf(viewer).getText());
            assertEquals("@@ -1,3 +1,3 @@", areaOf(viewer).getParagraph(0).getText());
        });
    }

    // ------------------------------------------------------------------
    // Intra-line highlighting
    // ------------------------------------------------------------------

    @Test
    void changedWordsAreStyledInsideTheChangedLine() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiff("x", "@@ -1,1 +1,1 @@\n-int count = 0;\n+int total = 0;\n");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            // Row 1 is the deletion: "int count = 0;", with "count" at [4, 9).
            assertTrue(area.getStyleOfChar(area.getAbsolutePosition(1, 5))
                            .contains("diff-inline-delete"),
                    "the changed word was not highlighted");
            assertFalse(area.getStyleOfChar(area.getAbsolutePosition(1, 1))
                            .contains("diff-inline-delete"),
                    "unchanged text on the same line must stay plain");
            assertTrue(area.getStyleOfChar(area.getAbsolutePosition(2, 5))
                            .contains("diff-inline-add"),
                    "the replacement word was not highlighted");
        });
    }

    @Test
    void inlineHighlightingCanBeSwitchedOff() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiff("x", "@@ -1,1 +1,1 @@\n-int count = 0;\n+int total = 0;\n");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            viewer.setInlineDiffEnabled(false);
            FxHarness.pulse(viewer);
            DiffTextArea area = areaOf(viewer);
            assertTrue(area.getStyleOfChar(area.getAbsolutePosition(1, 5)).isEmpty(),
                    "switching it off must leave plain line colouring behind");
        });
    }

    // ------------------------------------------------------------------
    // Hunk and line staging
    // ------------------------------------------------------------------

    @Test
    void stagingTheFirstHunksButtonStagesThatHunksLines() {
        DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
        java.util.concurrent.atomic.AtomicReference<java.util.Set<Integer>> staged =
                new java.util.concurrent.atomic.AtomicReference<>();

        FxHarness.run(() -> {
            viewer.setOnHunkAction(action -> staged.set(action.lines()));
            FxHarness.pulse(viewer);

            // No hover: the bars sit on their hunks from the moment the diff renders.
            Button stage = stageButton(viewer);
            assertTrue(stage.isVisible(), "the first hunk's Stage button should just be there");
            assertEquals("Stage hunk", stage.getText());

            stage.fire();
            // The first hunk has one deletion and one addition.
            assertEquals(java.util.Set.of(1, 2), staged.get());
        });
    }

    @Test
    void selectingLinesOffersToStageJustThose() {
        DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
        java.util.concurrent.atomic.AtomicReference<java.util.Set<Integer>> staged =
                new java.util.concurrent.atomic.AtomicReference<>();

        FxHarness.run(() -> {
            viewer.setOnHunkAction(action -> staged.set(action.lines()));
            DiffTextArea area = areaOf(viewer);

            // Select paragraph 2 only - the "-removed" line of the first hunk. The
            // selection alone must bring the button up; there is no hover to rely on.
            area.selectRange(area.getAbsolutePosition(2, 0), area.getAbsolutePosition(2, 5));
            FxHarness.pulse(viewer);

            Button lines = (Button) FxHarness.lookupAll(viewer, "diff-hunk-action").get(0);
            assertTrue(lines.isVisible(), "a selection should offer line staging");
            assertEquals("Stage line", lines.getText());

            lines.fire();
            assertEquals(java.util.Set.of(1), staged.get(),
                    "only the selected deletion, by index within the hunk");
        });
    }

    @Test
    void noLineButtonWithoutASelection() {
        DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
        FxHarness.run(() -> {
            viewer.setOnHunkAction(action -> { });
            DiffTextArea area = areaOf(viewer);
            area.deselect();
            FxHarness.pulse(viewer);

            Button lines = (Button) FxHarness.lookupAll(viewer, "diff-hunk-action").get(0);
            assertFalse(lines.isVisible());
        });
    }

    /**
     * A diff read with whitespace changes left out shows the note in place of the buttons:
     * the rule is {@link LoadedDiff}'s, and this is that it reaches the bar.
     */
    @Test
    void aWhitespaceHiddenDiffShowsItsNoteInPlaceOfTheButtons() {
        DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
        FxHarness.run(() -> {
            viewer.setOnHunkAction(action -> { });
            ToggleButton ignore = (ToggleButton) FxHarness.lookupAll(viewer, "diff-tool-toggle").stream()
                    .filter(n -> n instanceof ToggleButton t
                            && DiffViewer.NAME_IGNORE_WHITESPACE.equals(t.getAccessibleText()))
                    .findFirst().orElseThrow();
            ignore.fire();
            // As a host would, the diff read again now that whitespace is ignored.
            viewer.setFileDiff(DiffKind.UNSTAGED, "src/Main.java", ACTIONABLE_DIFF);
            FxHarness.pulse(viewer);

            List<Node> shown = FxHarness.lookupAll(viewer, "diff-hunk-actions").stream()
                    .filter(Node::isVisible).toList();
            assertFalse(shown.isEmpty(), "the bars stay, to carry the note");
            assertTrue(FxHarness.lookupAll(viewer, "diff-hunk-action").stream().noneMatch(Node::isVisible),
                    "no Stage hunk on a -w diff");
            assertTrue(FxHarness.lookupAll(viewer, "diff-revert-hunk").stream().noneMatch(Node::isVisible),
                    "no Discard hunk on a -w diff");
            assertTrue(FxHarness.lookupAll(viewer, "diff-hunk-note").stream()
                            .anyMatch(n -> n.isVisible() && LoadedDiff.WHITESPACE_NOTE.equals(((Label) n).getText())),
                    "the bar says how to get them back");
        });
    }

    /** The "Stage hunk" button, which is the second of the two staging actions. */
    private static Button stageButton(DiffViewer viewer) {
        return (Button) FxHarness.lookupAll(viewer, "diff-hunk-action").get(1);
    }

    // ------------------------------------------------------------------
    // Context expansion
    // ------------------------------------------------------------------

    @Test
    void clickingAnExpanderRevealsTheHiddenContext() {
        String file = String.join("\n",
                "line1", "line2", "line3", "line4", "line5",
                "line6", "line7", "line8", "line9", "line10");

        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(path -> file.getBytes(StandardCharsets.UTF_8));
            v.setFileDiff("src/Main.java", """
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
                    """);
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            assertEquals("4 hidden lines", area.getParagraph(5).getText(),
                    "the gap between the hunks should offer to open");

            // Pressing the expander's control: one step reveals everything behind it
            // here. A real Button rather than a Label with a mouse handler, so that it is
            // reachable from the keyboard - which also means a bare synthetic
            // MOUSE_CLICKED no longer activates it, as JavaFX's own button behaviour
            // watches for a press and a release.
            List<Node> carets = FxHarness.lookupAll(viewer, "diff-expander-toggle");
            assertEquals(1, carets.size(), "the expander needs a control to press");
            assertInstanceOf(javafx.scene.control.Button.class, carets.get(0),
                    "a control only a mouse can reach is not a control");
            ((javafx.scene.control.Button) carets.get(0)).fire();
            FxHarness.pulse(viewer);

            String all = area.getText();
            assertTrue(all.contains("line5"), "revealed context is missing: " + all);
            assertTrue(all.contains("line7"), all);
            assertFalse(all.contains("hidden lines"), "the expander should be gone");
        });
    }

    @Test
    void noExpandersWithoutSomethingToExpandFrom() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> assertFalse(areaOf(viewer).getText().contains("hidden lines"),
                "a viewer with no file source cannot offer expansion"));
    }

    // ------------------------------------------------------------------
    // Whole file toggle
    // ------------------------------------------------------------------

    private static final String TWELVE_LINE_FILE = String.join("\n",
            "line1", "line2", "line3", "line4", "line5", "line6",
            "line7", "line8", "line9", "line10", "line11", "line12");

    /** Two hunks with a gap between them and, unlike {@code FAR_APART} elsewhere,
     *  a real tail after the last one - line11/line12 - so a single test exercises
     *  both the leading-gap reveal and the new trailing-context fill. */
    private static final String FAR_APART_WITH_A_TAIL = """
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

    private static DiffViewer viewerWithAFileSource() {
        return FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(path -> TWELVE_LINE_FILE.getBytes(StandardCharsets.UTF_8));
            v.setFileDiff("src/Main.java", FAR_APART_WITH_A_TAIL);
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
    }

    private static ToggleButton wholeFileToggleButton(DiffViewer viewer) {
        return FxHarness.lookupAll(viewer, "diff-segment").stream()
                .map(n -> (ToggleButton) n)
                .filter(b -> "All".equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Context: All toggle found"));
    }

    @Test
    void wholeFileToggleShowsEveryLineOfTheFileInUnified() {
        DiffViewer viewer = viewerWithAFileSource();
        FxHarness.run(() -> {
            toggle(viewer, "All");
            FxHarness.pulse(viewer);

            String all = areaOf(viewer).getText();
            assertTrue(all.contains("line5"), "the gap between hunks should be revealed: " + all);
            assertTrue(all.contains("line12"), "the tail past the last hunk should be revealed: " + all);
            assertFalse(all.contains("hidden line"), "whole file mode never shows a collapsed section");
        });
    }

    @Test
    void wholeFileToggleShowsEveryLineOfTheFileInSplit() {
        DiffViewer viewer = viewerWithAFileSource();
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            toggle(viewer, "All");
            FxHarness.pulse(viewer);

            List<Node> areas = FxHarness.lookupAll(viewer, "code-area");
            assertEquals(2, areas.size());
            DiffTextArea left = (DiffTextArea) areas.get(0);
            DiffTextArea right = (DiffTextArea) areas.get(1);
            assertEquals(left.getParagraphs().size(), right.getParagraphs().size(),
                    "the columns must stay row for row in step even when fully revealed");
            assertTrue(right.getText().contains("line12"),
                    "the tail should reach the new-side column: " + right.getText());
            assertTrue(left.getText().contains("line12"),
                    "revealed context is unchanged, so it appears on both sides: " + left.getText());
        });
    }

    @Test
    void wholeFileToggleIsDisabledWithoutAFileSource() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> assertTrue(wholeFileToggleButton(viewer).isDisabled(),
                "no file source means nothing to reveal from"));
    }

    @Test
    void wholeFileToggleIsDisabledInFileViewAndBlame() {
        DiffViewer viewer = viewerWithAFileSource();
        FxHarness.run(() -> {
            assertFalse(wholeFileToggleButton(viewer).isDisabled(),
                    "enabled in Diff view once a file source exists");

            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertTrue(wholeFileToggleButton(viewer).isDisabled(),
                    "File view already shows the whole file with no diff markers");

            toggle(viewer, "Diff view");
            viewer.showBlame("src/Main.java", List.of());
            FxHarness.pulse(viewer);
            assertTrue(wholeFileToggleButton(viewer).isDisabled(),
                    "blame has no hunks to reveal in the first place");
        });
    }

    // ------------------------------------------------------------------
    // Scrollbar diff-map
    // ------------------------------------------------------------------

    private static Node scrollMapOf(DiffViewer viewer) {
        List<Node> found = FxHarness.lookupAll(viewer, "diff-scroll-map");
        assertFalse(found.isEmpty(), "the viewer has no scrollbar diff-map");
        return found.get(0);
    }

    @Test
    void scrollMapShowsOneMarkPerContiguousChangeRun() {
        // DIFF has one deletion ("removed") and two separately-placed additions
        // ("added" in the first hunk, "extra" in the second) - three runs, not merged
        // into one just because they are all "a change".
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            Node map = scrollMapOf(viewer);
            assertEquals(1, FxHarness.lookupAll(map, "diff-scroll-map-delete").size(),
                    "one contiguous deletion run");
            assertEquals(2, FxHarness.lookupAll(map, "diff-scroll-map-add").size(),
                    "two separate addition runs, not merged across the hunk boundary");
        });
    }

    @Test
    void scrollMapMarksUseTheStrongerEmphasisTierNotTheGuttersSubtleOne() {
        // The gutter's own diff-gutter-add/delete classes read as too faint on a strip
        // this thin - the map must carry its own, distinct classes so it can be styled
        // with the diff rows' own saturated "emphasis" colour instead.
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            Node map = scrollMapOf(viewer);
            assertTrue(FxHarness.lookupAll(map, "diff-gutter-add").isEmpty(),
                    "the map must not reuse the gutter's own (paler) style classes");
            assertTrue(FxHarness.lookupAll(map, "diff-gutter-delete").isEmpty());
        });
    }

    @Test
    void scrollMapHasNoMarksForAnUnchangedFile() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.showFileContent("a.txt", "one\ntwo\nthree");
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
        FxHarness.run(() -> {
            Node map = scrollMapOf(viewer);
            assertTrue(FxHarness.lookupAll(map, "diff-scroll-map-add").isEmpty());
            assertTrue(FxHarness.lookupAll(map, "diff-scroll-map-delete").isEmpty());
        });
    }

    @Test
    void scrollMapMergesLeftAndRightColumnsInSplitMode() {
        // A pure deletion is a real row only on the left column in split layout (the
        // right side pads it) - the map must still show it, by checking both columns.
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiff("x", "@@ -1,2 +1,1 @@\n context\n-gone\n");
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);

            Node map = scrollMapOf(viewer);
            assertEquals(1, FxHarness.lookupAll(map, "diff-scroll-map-delete").size(),
                    "a delete-only row, real only on the left column, must still show on the map");
        });
    }

    /**
     * A diff long enough that it cannot possibly fit in the test stage's viewport at
     * once - short diffs elsewhere in this file are exactly why a dedicated fixture is
     * needed here for the click-to-jump test below.
     */
    private static DiffViewer tallDiffViewer() {
        return tallDiffViewer(-1);
    }

    /** The same, with line {@code added} an addition - one mark, at a known row. */
    private static DiffViewer tallDiffViewer(int added) {
        StringBuilder diff = new StringBuilder("@@ -1," + (added > 0 ? 119 : 120) + " +1,120 @@\n");
        for (int i = 1; i <= 120; i++) {
            diff.append(i == added ? '+' : ' ').append("line").append(i).append('\n');
        }
        return FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiff("x", diff.toString());
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
    }

    @Test
    void scrollMapWidthFollowsThePreference() {
        com.jfx.git.core.AppPreferences.setDiffScrollMapWidth(30);
        try {
            DiffViewer viewer = showViewer();
            FxHarness.run(() -> assertEquals(30, scrollMapOf(viewer).getBoundsInLocal().getWidth(), 0.1,
                    "a new viewer should pick up the configured width"));
        } finally {
            com.jfx.git.core.AppPreferences.setDiffScrollMapWidth(0);
        }
    }

    @Test
    void applyPreferencesUpdatesAnAlreadyOpenViewersScrollMapWidth() {
        DiffViewer viewer = showViewer();
        com.jfx.git.core.AppPreferences.setDiffScrollMapWidth(24);
        try {
            FxHarness.run(() -> {
                viewer.applyPreferences();
                FxHarness.pulse(viewer);
                assertEquals(24, scrollMapOf(viewer).getBoundsInLocal().getWidth(), 0.1);
            });
        } finally {
            com.jfx.git.core.AppPreferences.setDiffScrollMapWidth(0);
        }
    }

    @Test
    void pressingTheScrollMapTrackJumpsToThatPartOfTheFile() {
        DiffViewer viewer = tallDiffViewer();

        FxHarness.run(() -> {
            assertEquals(0, areaOf(viewer).firstVisibleParToAllParIndex());
            Node map = scrollMapOf(viewer);
            // A press near the bottom of the track should land on a paragraph well past
            // the very first one, not leave the view where it started - and a press, not
            // a click, because the same press carries on as a drag.
            press(map, map.getBoundsInLocal().getWidth() / 2, map.getBoundsInLocal().getHeight() - 1);
            release(map, map.getBoundsInLocal().getWidth() / 2, map.getBoundsInLocal().getHeight() - 1);
            FxHarness.pulse(viewer);

            assertTrue(areaOf(viewer).firstVisibleParToAllParIndex() > 0,
                    "pressing near the bottom of the map should scroll the view down");
            Node thumb = thumbOf(viewer);
            assertTrue(thumb.getLayoutY() > map.getBoundsInLocal().getHeight() / 2,
                    "and the thumb followed the text down: " + thumb.getLayoutY());
        });
    }

    // ------------------------------------------------------------------
    // The scroll map as the scrollbar
    //
    // The strip is the surface's one vertical scrollbar: the native bar is off, the
    // thumb is drawn over the marks, and both are placed from the same row model, so a
    // mark sits under the thumb exactly when its row is on screen.
    // ------------------------------------------------------------------

    private static Node thumbOf(DiffViewer viewer) {
        List<Node> found = FxHarness.lookupAll(scrollMapOf(viewer), "diff-scroll-map-thumb");
        assertFalse(found.isEmpty(), "the scroll map has no thumb");
        return found.get(0);
    }

    /** Every text scroll pane in the viewer: one in unified and file view, two in split. */
    private static List<DiffScrollPane> scrollPanesOf(DiffViewer viewer) {
        return viewer.lookupAll("*").stream()
                .filter(n -> n instanceof DiffScrollPane)
                .map(n -> (DiffScrollPane) n)
                .toList();
    }

    /**
     * A mouse event at ({@code x}, {@code y}) in {@code on}'s own coordinates. A synthetic
     * event's x/y are taken as scene coordinates when it is dispatched and re-expressed
     * for each node on the way, so they are converted here first.
     */
    private static MouseEvent mouse(Node on, javafx.event.EventType<MouseEvent> type, double x, double y) {
        javafx.geometry.Point2D scene = on.localToScene(x, y);
        return new MouseEvent(type, scene.getX(), scene.getY(), scene.getX(), scene.getY(), MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null);
    }

    private static void press(Node on, double x, double y) {
        Event.fireEvent(on, mouse(on, MouseEvent.MOUSE_PRESSED, x, y));
    }

    private static void drag(Node on, double x, double y) {
        Event.fireEvent(on, mouse(on, MouseEvent.MOUSE_DRAGGED, x, y));
    }

    private static void release(Node on, double x, double y) {
        Event.fireEvent(on, mouse(on, MouseEvent.MOUSE_RELEASED, x, y));
    }

    @Test
    void theDiffSurfaceHasNoNativeVerticalScrollbar() {
        DiffViewer viewer = tallDiffViewer();
        FxHarness.run(() -> {
            List<DiffScrollPane> panes = scrollPanesOf(viewer);
            assertEquals(1, panes.size(), "unified view is one surface");
            assertNull(panes.get(0).lookup(".scroll-bar:vertical"),
                    "the scroll map is the scrollbar; a native one, even hidden, would write positions back");
        });
    }

    @Test
    void splitViewHidesBothColumnsNativeBars() {
        DiffViewer viewer = tallDiffViewer();
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);
            List<DiffScrollPane> panes = scrollPanesOf(viewer).stream()
                    .filter(Node::isVisible).toList();
            assertEquals(2, panes.size(), "split view is two columns");
            for (DiffScrollPane pane : panes) {
                assertNull(pane.lookup(".scroll-bar:vertical"));
            }
            assertTrue(thumbOf(viewer).isVisible(), "the one thumb is the map's, and it is there "
                    + "in the same pulse split view appears - not after some later relayout");
        });
    }

    @Test
    void aTallDiffShowsAThumbAndAShortOneDoesNot() {
        DiffViewer tall = tallDiffViewer();
        DiffViewer shortWithTail = showViewer();
        com.jfx.git.core.AppPreferences.setScrollBeyondLastLine(false);
        try {
            DiffViewer shortOne = showViewer();
            FxHarness.run(() -> {
                // The tail is measured after the first layout, later rather than in it;
                // one more pulse lets the strips lay out against it.
                FxHarness.pulse(tall);
                FxHarness.pulse(shortWithTail);
                FxHarness.pulse(shortOne);
                assertTrue(thumbOf(tall).isVisible(),
                        "120 rows do not fit a 600px stage: there is somewhere to scroll");
                assertFalse(thumbOf(shortOne).isVisible(), "seven rows fit: nothing to scroll, no thumb");
                assertTrue(thumbOf(shortWithTail).isVisible(),
                        "but with scroll-beyond-last-line on, even seven rows can scroll: the last to the top");
            });
        } finally {
            com.jfx.git.core.AppPreferences.setScrollBeyondLastLine(true);
        }
    }

    @Test
    void theThumbSitsAtTheTopOfTheStripBeforeScrolling() {
        DiffViewer viewer = tallDiffViewer();
        FxHarness.run(() -> {
            Node thumb = thumbOf(viewer);
            assertTrue(thumb.getLayoutY() < 1, "a diff opens at its top: " + thumb.getLayoutY());
            assertTrue(thumb.getBoundsInLocal().getHeight() >= 20, "never shorter than a grabbable minimum");
        });
    }

    @Test
    void scrollingTheAreaMovesTheThumbDownWithIt() {
        DiffViewer viewer = tallDiffViewer();
        FxHarness.run(() -> {
            double before = thumbOf(viewer).getLayoutY();
            areaOf(viewer).showParagraphAtTop(60);
            FxHarness.pulse(viewer);
            double after = thumbOf(viewer).getLayoutY();
            assertTrue(after > before + 10,
                    "the thumb follows the text, not the other way round: " + before + " -> " + after);

            // And at the very end - the last row at the top, which the tail allows - the
            // thumb reaches the bottom of the track, which is the bottom of the text.
            DiffTextArea area = areaOf(viewer);
            int last = area.getParagraphs().size() - 1;
            area.showParagraphAtTop(last);
            FxHarness.pulse(viewer);
            FxHarness.pulse(viewer);
            assertEquals(last, area.firstVisibleParToAllParIndex(), "the last row can be scrolled to the top");
            Node thumb = thumbOf(viewer);
            assertEquals(area.localToScene(area.getBoundsInLocal()).getMaxY(),
                    thumb.localToScene(thumb.getBoundsInLocal()).getMaxY(), 1.5,
                    "the thumb's bottom edge meets the text's");
        });
    }

    @Test
    void theThumbCoversExactlyTheMarksOfTheRowsOnScreen() {
        // The invariant every overview ruler promises: a mark is under the thumb when,
        // and only when, its row is on screen. Placing marks by row and the thumb by
        // Flowless's pixel estimate breaks it at the end of a long file.
        DiffViewer viewer = tallDiffViewer(60);
        FxHarness.run(() -> {
            areaOf(viewer).showParagraphAtTop(60);
            FxHarness.pulse(viewer);
            FxHarness.pulse(viewer);

            Node map = scrollMapOf(viewer);
            Node mark = FxHarness.lookupAll(map, "diff-scroll-map-add").get(0);
            javafx.geometry.Bounds markBounds = mark.localToScene(mark.getBoundsInLocal());
            Node thumb = thumbOf(viewer);
            javafx.geometry.Bounds thumbBounds = thumb.localToScene(thumb.getBoundsInLocal());
            assertTrue(markBounds.getMinY() >= thumbBounds.getMinY() - 1
                            && markBounds.getMaxY() <= thumbBounds.getMaxY() + 1,
                    "row 60 is at the top of the viewport, so its mark " + markBounds
                            + " must sit at the top of the thumb " + thumbBounds);
        });
    }

    @Test
    void draggingTheThumbScrollsTheArea() {
        DiffViewer viewer = tallDiffViewer();
        FxHarness.run(() -> {
            Node map = scrollMapOf(viewer);
            Node thumb = thumbOf(viewer);
            double x = map.getBoundsInLocal().getWidth() / 2;
            double grab = thumb.getLayoutY() + thumb.getBoundsInLocal().getHeight() / 2;
            press(map, x, grab);
            drag(map, x, grab + 200);
            release(map, x, grab + 200);
            FxHarness.pulse(viewer);

            assertTrue(areaOf(viewer).firstVisibleParToAllParIndex() > 0,
                    "dragging the thumb down must scroll the text down");
        });
    }

    @Test
    void wheelingOverTheStripScrollsTheArea() {
        DiffViewer viewer = tallDiffViewer();
        FxHarness.run(() -> {
            Node map = scrollMapOf(viewer);
            Event.fireEvent(map, new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
                    5, 5, 0, 0, false, false, false, false, false, false,
                    0, -120, 0, -120,
                    javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            FxHarness.pulse(viewer);

            assertTrue(areaOf(viewer).firstVisibleParToAllParIndex() > 0,
                    "the wheel over the bar scrolls the text, as over any scrollbar");
        });
    }

    @Test
    void theTrackStartsLevelWithTheTextItScrolls() {
        // Unified: the text starts at the top of the row, and so does the thumb.
        DiffViewer viewer = tallDiffViewer();
        FxHarness.run(() -> {
            Node thumb = thumbOf(viewer);
            DiffTextArea area = areaOf(viewer);
            assertEquals(area.localToScene(area.getBoundsInLocal()).getMinY(),
                    thumb.localToScene(thumb.getBoundsInLocal()).getMinY(), 1,
                    "a thumb at scroll 0 sits level with the first line");
        });
        // Split: a column header sits above the text, and the track moves down with it
        // rather than hanging from the top of the row beside the header.
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);
        });
        FxHarness.run(() -> {
            FxHarness.pulse(viewer);
            Node thumb = thumbOf(viewer);
            DiffTextArea right = FxHarness.lookupAll(viewer, "code-area").stream()
                    .map(n -> (DiffTextArea) n).filter(Node::isVisible)
                    .reduce((a, b) -> b).orElseThrow();
            double textTop = right.localToScene(right.getBoundsInLocal()).getMinY();
            assertTrue(textTop > viewer.localToScene(viewer.getBoundsInLocal()).getMinY() + 10,
                    "precondition: the split column header pushes the text down");
            assertEquals(textTop, thumb.localToScene(thumb.getBoundsInLocal()).getMinY(), 1,
                    "the thumb starts where the column's text starts, below its header");
        });
    }

    // ------------------------------------------------------------------
    // Scrollbar diff-map in the whole-file views
    //
    // File and blame views render every line as plain context, so their rows carry no
    // +/- for the map to read off. Both used to leave the strip completely blank as a
    // result - the whole file on screen, and no indication of which part of it the
    // selected commit actually touched.
    // ------------------------------------------------------------------

    /** Eight lines, of which line 3 is this commit's own replacement of an older line. */
    private static final String WHOLE_FILE = "one\ntwo\nTHREE-NEW\nfour\nfive\nsix\nseven\neight\n";

    private static final String REPLACING_DIFF = """
            @@ -1,4 +1,4 @@
             one
             two
            -three
            +THREE-NEW
             four
            """;

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A viewer holding both the diff and the whole file it produced, as RepoTab supplies them. */
    private static DiffViewer viewerWithFileAndDiff() {
        return FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiffBytes("src/Main.java", bytes(REPLACING_DIFF), bytes(WHOLE_FILE));
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
    }

    @Test
    void fileViewMarksWhereThisCommitsChangesFallInTheWholeFile() {
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            toggle(viewer, "File view");
            FxHarness.pulse(viewer);

            Node map = scrollMapOf(viewer);
            assertEquals(1, FxHarness.lookupAll(map, "diff-scroll-map-add").size(),
                    "the replaced line's new text should be marked in the additions lane");
            assertEquals(1, FxHarness.lookupAll(map, "diff-scroll-map-delete").size(),
                    "and the text it replaced marked in the deletions lane, at the line it was cut from");
        });
    }

    @Test
    void blameMarksTheBlamedCommitsChangesWhenGivenItsDiff() {
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            List<com.jfx.git.core.BlameLine> blame = new java.util.ArrayList<>();
            for (String line : WHOLE_FILE.stripTrailing().split("\n")) {
                blame.add(new com.jfx.git.core.BlameLine("abc12345", "A", "msg", java.time.Instant.parse("2026-01-01T12:00:00Z"), line));
            }
            viewer.showBlame("src/Main.java", blame, bytes(REPLACING_DIFF));
            FxHarness.pulse(viewer);

            Node map = scrollMapOf(viewer);
            assertEquals(1, FxHarness.lookupAll(map, "diff-scroll-map-add").size(),
                    "blame should still show which lines the commit being blamed changed");
            assertEquals(1, FxHarness.lookupAll(map, "diff-scroll-map-delete").size());
        });
    }

    @Test
    void blameWithoutADiffSimplyDrawsNoMarks() {
        // The two-argument showBlame stays valid and must not guess: with no diff behind
        // it there is genuinely nothing to mark, and marking the previous file's hunks
        // would be worse than marking nothing.
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            viewer.showBlame("src/Main.java",
                    List.of(new com.jfx.git.core.BlameLine("abc12345", "A", "m", java.time.Instant.parse("2026-01-01T12:00:00Z"), "one")));
            FxHarness.pulse(viewer);

            Node map = scrollMapOf(viewer);
            assertTrue(FxHarness.lookupAll(map, "diff-scroll-map-add").isEmpty());
            assertTrue(FxHarness.lookupAll(map, "diff-scroll-map-delete").isEmpty());
        });
    }

    // ------------------------------------------------------------------
    // Gutter column order across the blame toggle
    // ------------------------------------------------------------------

    /** Style classes of the first gutter's children, in order, as rendered right now. */
    private static List<String> gutterColumns(DiffViewer viewer) {
        List<Node> gutters = FxHarness.lookupAll(viewer, "diff-gutter");
        assertFalse(gutters.isEmpty(), "no gutter was rendered");
        List<String> columns = new java.util.ArrayList<>();
        for (Node child : ((javafx.scene.Parent) gutters.get(0)).getChildrenUnmodifiable()) {
            // Each gutter column carries exactly one meaningful class of its own.
            child.getStyleClass().stream()
                    .filter(c -> c.startsWith("diff-line-number") || c.startsWith("blame-")
                            || c.startsWith("diff-marker"))
                    .findFirst()
                    .ifPresent(columns::add);
        }
        return columns;
    }

    @Test
    void theLineNumberStaysInTheSamePlaceWhenBlameIsToggled() {
        // Commercial clients pin the number to the far left in both states and only insert the
        // blame prose between it and the code. Ordered band-first/number-last, the number
        // jumped the width of two text columns on every toggle, so the whole gutter read
        // as being rebuilt rather than one column being added to it.
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertEquals("diff-line-number", gutterColumns(viewer).get(0),
                    "with blame off, the line number leads the gutter");

            viewer.showBlame("src/Main.java",
                    List.of(new com.jfx.git.core.BlameLine("abc12345", "A", "m", java.time.Instant.parse("2026-01-01T12:00:00Z"), "one")));
            FxHarness.pulse(viewer);
            assertEquals(List.of("diff-line-number", "blame-message", "blame-date", "blame-band"),
                    gutterColumns(viewer),
                    "with blame on, the number still leads and the colour band sits next to the code");
        });
    }

    @Test
    void theBlameLineNumberColumnHasAFixedWidthSoTheProseLinesUp() {
        // A bare Label sizes to its own text, so line 9's column would be narrower than
        // line 10's and every message after it would start at a different x.
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            List<com.jfx.git.core.BlameLine> blame = new java.util.ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                blame.add(new com.jfx.git.core.BlameLine(
                        "hash" + i, "A", "m" + i, java.time.Instant.parse("2026-01-01T12:00:00Z"), "line" + i));
            }
            viewer.showBlame("src/Main.java", blame);
            FxHarness.pulse(viewer);

            List<Node> gutters = FxHarness.lookupAll(viewer, "diff-line-number");
            assertTrue(gutters.size() >= 2, "expected several numbered blame rows");
            double first = ((javafx.scene.control.Label) gutters.get(0)).getPrefWidth();
            for (Node n : gutters) {
                assertEquals(first, ((javafx.scene.control.Label) n).getPrefWidth(), 0.01,
                        "every blame line number must claim the same width");
            }
        });
    }

    /**
     * Whether the user can actually see this node. {@code isVisible()} alone reports only
     * the node's own flag, which stays true when an ancestor is hidden - and the diff
     * tools are hidden as a group, so asking the button directly would always say yes.
     */
    private static boolean onScreen(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void diffOnlyToolsDisappearInTheWholeFileViews() {
        // The commercial clients' File view offers only Blame and wrap; everything describing a diff
        // - hunk navigation, unified/split, whitespace, copy-the-diff - is gone, not
        // merely greyed out. Controls that can never apply in the chosen mode are noise.
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            assertFalse(FxHarness.lookupAll(viewer, "diff-segment").isEmpty(),
                    "precondition: Diff view shows the layout toggles");
            Node unified = FxHarness.lookupAll(viewer, "diff-segment").stream()
                    .filter(n -> n instanceof Labeled l && "Unified".equals(l.getText()))
                    .findFirst().orElseThrow();
            assertTrue(onScreen(unified), "precondition: Unified is on show in Diff view");

            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertFalse(onScreen(unified), "the layout toggle means nothing over a whole file");
            assertFalse(onScreen(wholeFileToggleButton(viewer)),
                    "nor does Whole file, which File view already is");

            toggle(viewer, "Diff view");
            FxHarness.pulse(viewer);
            assertTrue(onScreen(unified), "and they come back with the diff");
        });
    }

    @Test
    void wrapStaysAvailableInEveryView() {
        // Wrap is the one tool that applies to a diff and a whole file alike, so it must
        // survive the hiding above rather than being swept away with the diff controls.
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            Node wrap = FxHarness.lookupAll(viewer, "diff-tool-toggle").stream()
                    .filter(n -> n instanceof Labeled l && DiffViewer.NAME_WRAP.equals(l.getAccessibleText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no wrap toggle found"));

            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertTrue(onScreen(wrap), "wrap applies to a whole file too");

            viewer.showBlame("src/Main.java",
                    List.of(new com.jfx.git.core.BlameLine("abc12345", "A", "m", java.time.Instant.parse("2026-01-01T12:00:00Z"), "one")));
            FxHarness.pulse(viewer);
            assertTrue(onScreen(wrap), "and to a blame annotation");
        });
    }

    @Test
    void eachCommitInABlameGetsItsOwnBandAndKeepsIt() {
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            // Two commits, interleaved, so the run of "a" rows is broken by a "b" row.
            List<com.jfx.git.core.BlameLine> blame = List.of(
                    new com.jfx.git.core.BlameLine("aaaa1111", "A", "first", java.time.Instant.parse("2026-01-01T12:00:00Z"), "one"),
                    new com.jfx.git.core.BlameLine("bbbb2222", "B", "second", java.time.Instant.parse("2026-01-02T12:00:00Z"), "two"),
                    new com.jfx.git.core.BlameLine("aaaa1111", "A", "first", java.time.Instant.parse("2026-01-01T12:00:00Z"), "three"));
            viewer.showBlame("src/Main.java", blame);
            FxHarness.pulse(viewer);

            List<Node> bands = FxHarness.lookupAll(viewer, "blame-band");
            assertTrue(bands.size() >= 3, "expected a band on every blame row");
            String first = bands.get(0).getStyle();
            String second = bands.get(1).getStyle();
            String third = bands.get(2).getStyle();

            assertNotEquals(first, second, "two different commits must not share a band colour");
            assertEquals(first, third,
                    "the same commit must keep its band when it appears again further down");
        });
    }

    @Test
    void fileContentReplacedOnItsOwnIsNeverMarkedFromAnotherRevisionsHunks() {
        // FileHistoryView in File view loads each commit's content without refetching the
        // diff, so the held hunks describe the commit clicked before this one. Marking
        // from them would confidently point at lines that never changed in what is shown.
        DiffViewer viewer = viewerWithFileAndDiff();
        FxHarness.run(() -> {
            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertFalse(FxHarness.lookupAll(scrollMapOf(viewer), "diff-scroll-map-add").isEmpty(),
                    "precondition: the in-step revision does get marked");

            viewer.showFileBytes("src/Main.java", bytes("wholly\ndifferent\ncontent\n"));
            FxHarness.pulse(viewer);

            Node map = scrollMapOf(viewer);
            assertTrue(FxHarness.lookupAll(map, "diff-scroll-map-add").isEmpty(),
                    "content replaced on its own must not be marked from the old revision's hunks");
            assertTrue(FxHarness.lookupAll(map, "diff-scroll-map-delete").isEmpty());
        });
    }

    // ------------------------------------------------------------------
    // Syntax highlighting
    // ------------------------------------------------------------------

    @Test
    void syntaxAndDiffLayersBothApplyToTheSameCharacters() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiff("src/Main.java",
                    "@@ -1,1 +1,1 @@\n-int count = 0;\n+int total = 0;\n");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            // "int" is a type keyword and unchanged: syntax only. The Java grammar
            // scopes it as storage.type.primitive.
            assertTrue(area.getStyleOfChar(area.getAbsolutePosition(1, 0)).stream()
                            .anyMatch(c -> c.startsWith("tm-storage")),
                    () -> area.getStyleOfChar(area.getAbsolutePosition(1, 0)).toString());
            // "total" changed and is not a keyword: word-diff only.
            var changed = area.getStyleOfChar(area.getAbsolutePosition(1, 5));
            assertTrue(changed.contains("diff-inline-delete"), changed.toString());
            assertFalse(changed.stream().anyMatch(c -> c.startsWith("tm-storage")),
                    changed.toString());
        });
    }

    @Test
    void markdownIsLexedAsMarkdownNotJava() {
        // .md is grammar-backed now; the point of this test used to be "md must not be
        // lexed as Java". It still must not — and the grammar proves it by colouring
        // with markdown scopes rather than anything Java-shaped.
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiff("NOTES.md", "@@ -1,1 +1,1 @@\n+int count = 0;\n");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            var style = area.getStyleOfChar(area.getAbsolutePosition(1, 2));
            assertTrue(style.stream().noneMatch(c -> c.contains("java")),
                    "markdown must never carry java scopes, got " + style);
        });
    }

    @Test
    void unknownFileTypesGetNoSyntaxColour() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiff("NOTES.unknownext", "@@ -1,1 +1,1 @@\n+int count = 0;\n");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            var unknownStyle = area.getStyleOfChar(area.getAbsolutePosition(1, 0));
            assertTrue(unknownStyle.stream().noneMatch(c -> c.startsWith("tm-")),
                    "an extension no grammar claims must stay unhighlighted, got "
                            + unknownStyle);
        });
    }

    @Test
    void grammarBackedLanguageRendersRealHighlightingInFileView() {
        // Every language is grammar-backed now; this file's extension resolves
        // through GrammarRegistry's TextMateTokenizer, proving that path is wired
        // all the way from DiffViewer.syntaxFor through to the rendered DiffTextArea,
        // not just unit-tested in isolation.
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.showFileContent("config.yaml", "key: value");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            assertTrue(viewer.isFileView());
            var keyStyle = area.getStyleOfChar(area.getAbsolutePosition(0, 0));
            assertTrue(keyStyle.stream().anyMatch(c -> c.startsWith("tm-")),
                    "expected a tm-* class on the YAML key, got " + keyStyle);
        });
    }

    // ------------------------------------------------------------------
    // Editing content that is not the current working-tree copy
    // ------------------------------------------------------------------

    private static Button editButton(DiffViewer viewer) {
        return FxHarness.lookupAll(viewer, "diff-tool-button").stream()
                .map(n -> (Button) n)
                .filter(b -> b.getText().startsWith("Edit"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Edit button found"));
    }

    @Test
    void editButtonReadsEditThisFileForTheCurrentWorkingCopy() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setOnSaveFile((path, text, onResult) -> onResult.accept(GitResult.ok()));
            v.setEditable(true);
            v.showFileContent("a.txt", "hello");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() ->
                assertEquals("Edit this file", editButton(viewer).getText()));
    }

    @Test
    void editButtonReadsEditWorkingCopyForHistoricalContent() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setOnSaveFile((path, text, onResult) -> onResult.accept(GitResult.ok()));
            v.setEditable(false);
            v.showFileContent("a.txt", "hello");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() ->
                assertEquals("Edit working copy", editButton(viewer).getText()));
    }

    /**
      * An embedded viewer with no content supplier has no working copy to swap in, so
      * "edit" can only mean the revision on screen. Where there <em>is</em> a supplier,
      * {@code beginEditing} loads the working copy instead - see
      * {@code DiffViewer.loadWorkingCopyForEditing}, and the separate Restore button for
      * writing the displayed revision back on purpose.
      */
    @Test
    void beginEditingWorksForHistoricalContentNotJustTheWorkingCopy() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setOnSaveFile((path, text, onResult) -> onResult.accept(GitResult.ok()));
            v.setEditable(false);
            v.showFileContent("a.txt", "hello");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            assertFalse(viewer.isEditing());
            viewer.beginEditing();
            assertTrue(viewer.isEditing(), "editing must not require the current working copy");
        });
    }

    @Test
    void beginEditingFromDiffViewEntersEditModeInOnePressWhenContentIsAlreadyAvailable() {
        // The common case after this session's fix: setFileDiffBytes's 3-arg overload
        // already supplies whole-file bytes, so File view has real content the instant
        // the toggle switches to it - beginEditing should not need a second press.
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setOnSaveFile((path, text, onResult) -> onResult.accept(GitResult.ok()));
            v.setEditable(true);
            v.setFileDiffBytes("a.txt",
                    "@@ -1,1 +1,1 @@\n-old\n+new\n".getBytes(StandardCharsets.UTF_8),
                    "new".getBytes(StandardCharsets.UTF_8));
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            assertFalse(viewer.isFileView(), "should start in Diff view");
            assertFalse(viewer.isEditing());

            viewer.beginEditing();

            assertTrue(viewer.isFileView(), "should have switched to File view");
            assertTrue(viewer.isEditing(),
                    "one press should be enough once whole-file content is already available");
        });
    }

    @Test
    void beginEditingFromDiffViewWaitsForASecondPressWhenContentIsNotYetAvailable() {
        // FileHistoryView's embedded viewer: setOnViewModeChanged does an asynchronous
        // re-fetch, so nothing is available the instant the toggle fires. This must
        // still require a second press once the content lands, not silently misbehave.
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setOnSaveFile((path, text, onResult) -> onResult.accept(GitResult.ok()));
            v.setEditable(true);
            // No fileContentSupplier and no showFileBytes/showFileContent ever called:
            // fileContent() returns null until the host supplies it, standing in for
            // an async fetch that has not landed yet.
            v.setFileDiff("a.txt", "@@ -1,1 +1,1 @@\n-old\n+new\n");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            viewer.beginEditing();

            assertTrue(viewer.isFileView(), "should still switch to File view");
            assertFalse(viewer.isEditing(),
                    "must not enter edit mode before content actually arrives");
        });
    }

    @Test
    void setFileDiffBytesWithWholeFileShowsThatRevisionNotTheGenericSupplier() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            // Stands in for RepoTab's global working-tree supplier: wrong for
            // historical content, which is exactly the bug this overload exists to
            // avoid (RightPanelManager's stash/compare/commit-detail selections used
            // to fall through to this for File view before the fix).
            v.setFileContentSupplier(path -> "WRONG-CURRENT-CONTENT".getBytes(StandardCharsets.UTF_8));
            v.setFileDiffBytes("a.txt",
                    "@@ -1,1 +1,1 @@\n-old\n+new\n".getBytes(StandardCharsets.UTF_8),
                    "RIGHT-HISTORICAL-CONTENT".getBytes(StandardCharsets.UTF_8));
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            toggle(viewer, "File view");
            FxHarness.pulse(viewer);

            String shown = areaOf(viewer).getText();
            assertTrue(shown.contains("RIGHT-HISTORICAL-CONTENT"), shown);
            assertFalse(shown.contains("WRONG-CURRENT-CONTENT"), shown);
        });
    }

    /** Twelve lines that are not {@link #TWELVE_LINE_FILE}: what the working tree holds. */
    private static final String WORKING_TREE_FILE = String.join("\n",
            "work1", "work2", "work3", "work4", "work5", "work6",
            "work7", "work8", "work9", "work10", "work11", "work12");

    /**
     * Whole file on a commit's diff reveals that commit's file, not today's.
     *
     * <p>Expansion read only the content supplier - the working tree, in the application -
     * so every commit, stash and comparison diff filled its gaps with lines from a
     * different version of the file. Whole file is switched on before the diff arrives,
     * as a saved preference would have it: the revision's content used to be set only
     * after the first render, which had already filled from the working tree.
     */
    @Test
    void wholeFileOnARevisionDiffRevealsThatRevisionNotTheWorkingTree() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(path -> WORKING_TREE_FILE.getBytes(StandardCharsets.UTF_8));
            v.setFileDiff("src/Main.java", FAR_APART_WITH_A_TAIL);
            FxHarness.pulse(v);
            toggle(v, "All");
            FxHarness.pulse(v);
            v.setFileDiffBytes("src/Main.java",
                    FAR_APART_WITH_A_TAIL.getBytes(StandardCharsets.UTF_8),
                    TWELVE_LINE_FILE.getBytes(StandardCharsets.UTF_8));
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
        FxHarness.run(() -> {
            String all = areaOf(viewer).getText();
            assertTrue(all.contains("line5"), "the gap must come from the revision: " + all);
            assertTrue(all.contains("line12"), "and so must the tail: " + all);
            assertFalse(all.contains("work"), "nothing from the working tree: " + all);
        });
    }

    /**
     * A staged diff reveals the index between its hunks, while File view - what the user
     * reads and edits from there - stays on the working tree.
     */
    @Test
    void aStagedDiffRevealsTheIndexButFileViewStaysOnTheWorkingTree() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(path -> WORKING_TREE_FILE.getBytes(StandardCharsets.UTF_8));
            v.setStagedDiffBytes("src/Main.java",
                    FAR_APART_WITH_A_TAIL.getBytes(StandardCharsets.UTF_8),
                    TWELVE_LINE_FILE.getBytes(StandardCharsets.UTF_8));
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
        FxHarness.run(() -> {
            if (!wholeFileToggleButton(viewer).isSelected()) {
                toggle(viewer, "All");
            }
            FxHarness.pulse(viewer);
            String diff = areaOf(viewer).getText();
            assertTrue(diff.contains("line5"), "the gap is the staged copy: " + diff);
            assertFalse(diff.contains("work"), "not the working tree: " + diff);
            toggle(viewer, "3");

            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            String file = areaOf(viewer).getText();
            assertTrue(file.contains("work5"), "File view is the working copy: " + file);
        });
    }

    // ------------------------------------------------------------------
    // Whitespace display versus what gets copied
    // ------------------------------------------------------------------

    @Test
    void whitespaceMarkersShowButDoNotReachTheClipboard() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileDiff("x", "@@ -1,1 +1,1 @@\n+\tindented  line\n");
            stage.setUserData("keep-alive");
            return v;
        });

        FxHarness.run(() -> {
            toggle(viewer, DiffViewer.NAME_SHOW_WHITESPACE);
            FxHarness.pulse(viewer);

            DiffTextArea area = areaOf(viewer);
            String displayed = area.getParagraph(1).getText();
            assertTrue(displayed.contains("·"), "whitespace markers were not drawn: " + displayed);

            // The buffer holds markers; the source behind it must still hold real
            // whitespace, or copying a diff silently corrupts what is pasted.
            assertEquals("\tindented  line", area.sourceLine(1));
        });
    }

    // ------------------------------------------------------------------
    // Clipboard and context menu
    // ------------------------------------------------------------------

    private static DiffViewer editableViewer(String path, String content) {
        return FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setOnSaveFile((p, text, onResult) -> onResult.accept(GitResult.ok()));
            v.setEditable(true);
            v.showFileContent(path, content);
            FxHarness.pulse(v);
            v.beginEditing();
            stage.setUserData("keep-alive");
            return v;
        });
    }

    @Test
    void editableAreaHasAContextMenu() {
        DiffViewer viewer = editableViewer("a.txt", "hello world");
        FxHarness.run(() -> assertNotNull(areaOf(viewer).getContextMenu(),
                "RichTextFX supplies no default menu; this app must wire its own"));
    }

    @Test
    void copyMenuItemPutsTheSelectionOnTheClipboard() {
        DiffViewer viewer = editableViewer("a.txt", "hello world");
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            area.selectRange(0, 5);

            MenuItem copy = menuItem(area, "Copy");
            copy.getOnAction().handle(null);

            assertEquals("hello", Clipboard.getSystemClipboard().getString());
        });
    }

    @Test
    void cutMenuItemRemovesTheSelectionAndCopiesIt() {
        DiffViewer viewer = editableViewer("a.txt", "hello world");
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            area.selectRange(0, 5);

            MenuItem cut = menuItem(area, "Cut");
            cut.getOnAction().handle(null);

            assertEquals("hello", Clipboard.getSystemClipboard().getString());
            assertEquals(" world", area.getText());
        });
    }

    @Test
    void pasteMenuItemInsertsClipboardContent() {
        DiffViewer viewer = editableViewer("a.txt", "hello world");
        FxHarness.run(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString("XYZ");
            Clipboard.getSystemClipboard().setContent(content);

            DiffTextArea area = areaOf(viewer);
            area.moveTo(0);

            MenuItem paste = menuItem(area, "Paste");
            paste.getOnAction().handle(null);

            assertEquals("XYZhello world", area.getText());
        });
    }

    @Test
    void selectAllMenuItemSelectsTheWholeBuffer() {
        DiffViewer viewer = editableViewer("a.txt", "hello world");
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            MenuItem selectAll = menuItem(area, "Select All");
            selectAll.getOnAction().handle(null);

            assertEquals("hello world", area.getSelectedText());
        });
    }

    @Test
    void cutAndPasteAreDisabledOnAReadOnlyView() {
        // Diff/historical content: setEditable(false), never entered edit mode.
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            area.selectRange(0, area.getLength());
            ContextMenu menu = area.getContextMenu();
            menu.show(area, 0, 0);

            assertTrue(menuItem(area, "Cut").isDisable());
            assertTrue(menuItem(area, "Paste").isDisable());
            assertFalse(menuItem(area, "Copy").isDisable(), "copy must still work while only viewing");
            menu.hide();
        });
    }

    @Test
    void ctrlCCopiesTheSelectionToTheClipboard() {
        DiffViewer viewer = editableViewer("a.txt", "hello world");
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            area.selectRange(6, 11);
            area.copy();

            assertEquals("world", Clipboard.getSystemClipboard().getString(),
                    "Ctrl+C is bound to the same copy() this menu item uses");
        });
    }

    private static MenuItem menuItem(DiffTextArea area, String text) {
        return area.getContextMenu().getItems().stream()
                .filter(item -> text.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no menu item labelled " + text));
    }

    // ------------------------------------------------------------------
    // Live syntax highlighting while typing
    // ------------------------------------------------------------------

    @Test
    void typingAKeywordColoursItWithoutMovingTheCaret() throws InterruptedException {
        DiffViewer viewer = editableViewer("a.java", "int x = 1;\n");

        int caretAfterTyping = FxHarness.call(() -> {
            DiffTextArea area = areaOf(viewer);
            area.insertText(0, "final ");
            return area.getCaretPosition();
        });

        // Past the 150ms debounce, plus slack for the test environment.
        Thread.sleep(400);
        FxHarness.run(() -> FxHarness.pulse(viewer));

        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            assertEquals("final int x = 1;\n", area.getText());
            var style = area.getStyleOfChar(0);
            assertTrue(style.stream().anyMatch(c -> c.startsWith("tm-storage")),
                    "expected the newly typed \"final\" to be coloured, got " + style);
            // The debounced re-highlight (reapplyHighlightingWhileEditing) must not
            // have moved the caret from where insertText left it.
            assertEquals(caretAfterTyping, area.getCaretPosition(),
                    "the re-highlight must not move the caret");
        });
    }

    // ------------------------------------------------------------------
    // Editor-only zoom (Ctrl+scroll)
    // ------------------------------------------------------------------

    @Test
    void ctrlScrollUpIncreasesTheEditorsOwnFontSize() {
        FxHarness.run(EditorZoom::resetForTests);
        DiffViewer viewer = showViewer();
        try {
            FxHarness.run(() -> {
                DiffTextArea area = areaOf(viewer);
                double before = fontSizePx(area);

                boolean consumed = area.handleZoomScroll(true, 40);
                FxHarness.pulse(viewer);

                assertTrue(consumed, "a Ctrl+scroll gesture must be consumed");
                assertTrue(fontSizePx(area) > before,
                        "Ctrl+scroll up should increase the editor's font size");
            });
        } finally {
            FxHarness.run(EditorZoom::resetForTests);
        }
    }

    @Test
    void plainScrollDoesNotChangeTheEditorsFontSize() {
        FxHarness.run(EditorZoom::resetForTests);
        DiffViewer viewer = showViewer();
        try {
            FxHarness.run(() -> {
                DiffTextArea area = areaOf(viewer);
                double before = fontSizePx(area);

                boolean consumed = area.handleZoomScroll(false, 40);
                FxHarness.pulse(viewer);

                assertFalse(consumed, "a plain scroll must not be treated as a zoom gesture");
                assertEquals(before, fontSizePx(area),
                        "scrolling without Ctrl must not zoom the editor");
            });
        } finally {
            FxHarness.run(EditorZoom::resetForTests);
        }
    }

    @Test
    void editorZoomIsSharedAcrossEveryDiffTextAreaInstance() {
        FxHarness.run(EditorZoom::resetForTests);
        try {
            EditorZoom.zoomIn();
            DiffTextArea freshInstance = FxHarness.call(DiffTextArea::new);
            assertEquals(EditorZoom.factor() * 13, fontSizePx(freshInstance), 0.01,
                    "a newly constructed area must pick up the current zoom level");
        } finally {
            FxHarness.run(EditorZoom::resetForTests);
        }
    }

    private static double fontSizePx(DiffTextArea area) {
        String style = area.getStyle();
        var matcher = java.util.regex.Pattern.compile("-fx-font-size:\\s*([0-9.]+)px").matcher(style);
        assertTrue(matcher.find(), "no -fx-font-size in style: " + style);
        return Double.parseDouble(matcher.group(1));
    }

    private static void toggle(DiffViewer viewer, String label) {
        for (Node node : FxHarness.lookupAll(viewer, "diff-segment")) {
            if (node instanceof ToggleButton button && label.equals(button.getText())) {
                button.fire();
                return;
            }
        }
        for (Node node : FxHarness.lookupAll(viewer, "diff-tool-toggle")) {
            if (node instanceof ToggleButton button && label.equals(button.getAccessibleText())) {
                // fire() flips the selection itself; setting it first would undo the flip.
                button.fire();
                return;
            }
        }
        throw new AssertionError("no toggle labelled " + label);
    }

    /**
     * The encoding picker sits in the footer now, not the title bar.
     *
     * <p>Which matters because {@code setEmbedded(true)} hides the title bar: the control
     * used to be re-parented into {@code FileHistoryView}'s own header to survive that,
     * and the footer is what removed the need for two homes.
     */
    @Test
    void theEncodingSelectorLivesInTheFooter() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.showFileContent("a.txt", "hello");
            stage.setUserData("keep-alive");
            FxHarness.pulse(v);
            return v;
        });

        FxHarness.run(() -> {
            Node selector = viewer.encodingSelector();
            boolean inFooter = false;
            for (Node at = selector; at != null; at = at.getParent()) {
                if (at.getStyleClass().contains("diff-status-footer")) inFooter = true;
            }
            assertTrue(inFooter, "the encoding picker belongs with the document facts");
        });
    }

    @SuppressWarnings("unchecked")
    private static void selectEncoding(DiffViewer viewer, com.jfx.git.core.TextEncoding encoding) {
        ((javafx.scene.control.ComboBox<com.jfx.git.core.TextEncoding>) viewer.encodingSelector())
                .setValue(encoding);
    }

    @Test
    void hunkActionsStayClearOfTheDiffScrollbar() {
        // The overlay spans the whole viewport, which is wider than the text: the scroll
        // map - the surface's scrollbar - runs down the right of it. A bar reaching over
        // it is close enough to grab when aiming for the thumb, and a misfired "Discard
        // hunk" throws work away.
        DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
        FxHarness.run(() -> {
            viewer.setOnHunkAction(action -> { });
            FxHarness.pulse(viewer);

            Node map = scrollMapOf(viewer);
            double mapLeftInViewer = map.localToScene(map.getBoundsInLocal()).getMinX();

            List<Node> bars = FxHarness.lookupAll(viewer, "diff-hunk-actions").stream()
                    .filter(Node::isVisible).toList();
            assertFalse(bars.isEmpty(), "precondition: some bars are on screen");

            for (Node bar : bars) {
                double barRight = bar.localToScene(bar.getBoundsInLocal()).getMaxX();
                assertTrue(barRight < mapLeftInViewer,
                        "a bar's right edge (" + barRight + ") reaches the diff-map strip ("
                                + mapLeftInViewer + "), so it would be grabbed when reaching for the thumb");
            }
        });
    }

    @Test
    void widerScrollMapPushesTheHunkActionsFurtherIn() {
        // The inset is measured, not a constant, so making the strip wider has to move
        // the bars rather than letting the strip grow underneath them.
        com.jfx.git.core.AppPreferences.setDiffScrollMapWidth(40);
        try {
            DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
            FxHarness.run(() -> {
                viewer.setOnHunkAction(action -> { });
                FxHarness.pulse(viewer);

                Node map = scrollMapOf(viewer);
                double mapLeftInViewer = map.localToScene(map.getBoundsInLocal()).getMinX();
                Node bar = FxHarness.lookupAll(viewer, "diff-hunk-actions").stream()
                        .filter(Node::isVisible).findFirst().orElseThrow();
                assertTrue(bar.localToScene(bar.getBoundsInLocal()).getMaxX() < mapLeftInViewer,
                        "a 40px map should have pushed the bars in with it");
            });
        } finally {
            com.jfx.git.core.AppPreferences.setDiffScrollMapWidth(0);
        }
    }

    @Test
    void hunkActionsGoAwayInTheWholeFileViews() {
        // There are no hunks to act on once the pane shows a whole file or a blame, so
        // "always visible" must still stop at the edge of where the buttons make sense.
        DiffViewer viewer = showViewer(DiffKind.UNSTAGED);
        FxHarness.run(() -> {
            viewer.setOnHunkAction(action -> { });
            FxHarness.pulse(viewer);
            assertTrue(FxHarness.lookupAll(viewer, "diff-hunk-actions").stream()
                            .anyMatch(Node::isVisible),
                    "precondition: the diff shows its hunk bars");

            viewer.showFileContent("src/Main.java", "one\ntwo\nthree");
            FxHarness.pulse(viewer);
            assertTrue(FxHarness.lookupAll(viewer, "diff-hunk-actions").stream()
                            .noneMatch(Node::isVisible),
                    "a whole file has no hunks, so no bars");
        });
    }

    // ------------------------------------------------------------------
    // Header: file name, folder, compared sides
    // ------------------------------------------------------------------

    @Test
    void theHeaderLeadsWithTheFileNameAndKeepsTheWholePathForReaders() {
        DiffViewer viewer = showViewer();
        FxHarness.run(() -> {
            viewer.setFileDiff("src/main/java/App.java", "@@ -1 +1 @@\n-a\n+b\n");
            FxHarness.pulse(viewer);
            List<String> labels = FxHarness.lookupAll(viewer, "diff-file-name").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText()).toList();
            assertEquals(List.of("App.java"), labels, "the name is what is read first");
            List<String> dirs = FxHarness.lookupAll(viewer, "diff-file-dir").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText()).toList();
            assertEquals(List.of("src/main/java/"), dirs);
            assertEquals("src/main/java/App.java", viewer.headerPath(),
                    "a screen reader gets the whole path, whatever is elided on screen");
        });
    }

    @Test
    void theHeaderNamesTheTwoSidesAndSplitColumnsCarryThem() {
        DiffViewer viewer = viewerWithAFileSource();
        FxHarness.run(() -> {
            viewer.setComparedSides(DiffViewer.DiffSides.STAGED);
            viewer.setFileDiff("src/Main.java", FAR_APART_WITH_A_TAIL);
            FxHarness.pulse(viewer);
            assertEquals("HEAD → Index", viewer.comparedSidesText());

            toggle(viewer, "Split");
            FxHarness.pulse(viewer);
            assertEquals(List.of("HEAD", "Index"), viewer.splitColumnHeadersForTest(),
                    "each column says which version it is");

            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertEquals("Working tree", viewer.comparedSidesText(),
                    "File view of a staged change is the working copy, not the index");

            viewer.close();
            FxHarness.pulse(viewer);
            assertEquals("", viewer.comparedSidesText(), "a closed file compares nothing");
        });
    }

    // ------------------------------------------------------------------
    // Context: 3 / 10 / All
    // ------------------------------------------------------------------

    /** Thirty lines, changed at 3 and at 25, so git's own context leaves a wide gap. */
    private static final String THIRTY_LINE_FILE = java.util.stream.IntStream.rangeClosed(1, 30)
            .mapToObj(i -> i == 3 ? "NEW3" : i == 25 ? "NEW25" : "line" + i)
            .collect(java.util.stream.Collectors.joining("\n"));
    private static final String TWO_CHANGES_FAR_APART = """
            @@ -1,6 +1,6 @@
             line1
             line2
            -old3
            +NEW3
             line4
             line5
             line6
            @@ -22,7 +22,7 @@
             line22
             line23
             line24
            -old25
            +NEW25
             line26
             line27
             line28
            """;

    @Test
    void tenLinesOfContextRevealsSevenMoreOnEachSideOfEveryChange() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(path -> THIRTY_LINE_FILE.getBytes(StandardCharsets.UTF_8));
            v.setFileDiff("src/Main.java", TWO_CHANGES_FAR_APART);
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
        FxHarness.run(() -> {
            String three = areaOf(viewer).getText();
            assertFalse(three.contains("line13"), "git's own three lines stop at line6: " + three);

            toggle(viewer, "10");
            FxHarness.pulse(viewer);
            String ten = areaOf(viewer).getText();
            assertTrue(ten.contains("line13"), "seven more under the first change: " + ten);
            assertTrue(ten.contains("line15"), "seven more above the second: " + ten);
            assertFalse(ten.contains("line14\n"), "and the lines between stay folded: " + ten);
            assertTrue(ten.contains("1 hidden line"), "behind an expander: " + ten);
            assertTrue(ten.contains("line30"), "and the tail after the last change: " + ten);

            toggle(viewer, "3");
            FxHarness.pulse(viewer);
            assertFalse(areaOf(viewer).getText().contains("line13"), "3 goes back");
        });
    }

    // ------------------------------------------------------------------
    // Change N of M
    // ------------------------------------------------------------------

    @Test
    void theCounterSaysWhichChangeIsOnScreenAndStepsWithTheButtons() {
        DiffViewer viewer = viewerWithAFileSource();
        FxHarness.run(() -> {
            FxHarness.pulse(viewer);
            assertEquals("Change 1 of 2", viewer.changeCounterText());
            viewer.stepChange(1);
            FxHarness.pulse(viewer);
            assertEquals("Change 2 of 2", viewer.changeCounterText());
            viewer.stepChange(1);
            FxHarness.pulse(viewer);
            assertEquals("Change 1 of 2", viewer.changeCounterText(), "and wraps round");
            viewer.stepChange(-1);
            FxHarness.pulse(viewer);
            assertEquals("Change 2 of 2", viewer.changeCounterText(), "both ways");
        });
    }

    // ------------------------------------------------------------------
    // Ignored whitespace is visible
    // ------------------------------------------------------------------

    @Test
    void ignoringWhitespaceShowsAChipThatTurnsItBackOff() {
        List<Boolean> reloads = new java.util.ArrayList<>();
        DiffViewer viewer = viewerWithAFileSource();
        FxHarness.run(() -> {
            viewer.setOnIgnoreWhitespaceChanged(reloads::add);
            assertFalse(viewer.whitespaceIgnoredChipShowing(), "nothing is hidden yet");

            toggle(viewer, DiffViewer.NAME_IGNORE_WHITESPACE);
            // The host reloads the diff on a change; standing in for that reload.
            viewer.setFileDiff("src/Main.java", FAR_APART_WITH_A_TAIL);
            FxHarness.pulse(viewer);
            assertTrue(viewer.whitespaceIgnoredChipShowing(),
                    "a setting that hides changes has to say so where the changes are");

            viewer.pressWhitespaceIgnoredChipForTest();
            viewer.setFileDiff("src/Main.java", FAR_APART_WITH_A_TAIL);
            FxHarness.pulse(viewer);
            assertFalse(viewer.isIgnoreWhitespace(), "the chip turns it off");
            assertFalse(viewer.whitespaceIgnoredChipShowing());
            assertEquals(List.of(true, false), reloads, "through the same reload the toggle uses");
        });
    }

    @Test
    void anEmptyDiffWithWhitespaceIgnoredSaysThatIsWhy() {
        DiffViewer viewer = viewerWithAFileSource();
        FxHarness.run(() -> {
            viewer.setOnIgnoreWhitespaceChanged(on -> { });
            toggle(viewer, DiffViewer.NAME_IGNORE_WHITESPACE);
            viewer.setFileDiff("src/Main.java", "");
            FxHarness.pulse(viewer);
            String message = FxHarness.lookupAll(viewer, "diff-empty").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText())
                    .findFirst().orElse("");
            assertTrue(message.contains("whitespace is ignored"), message);
        });
    }

    // ------------------------------------------------------------------
    // Split view keeps rows level when Wrap makes them different heights
    // ------------------------------------------------------------------

    @Test
    void splitColumnsStayOnTheSameRowWhenOneSideWraps() {
        StringBuilder diff = new StringBuilder("@@ -1,41 +1,41 @@\n");
        diff.append("-").append("a very long deleted line that has to wrap ".repeat(30)).append("\n");
        diff.append("+short\n");
        for (int i = 2; i <= 41; i++) {
            diff.append(" context").append(i).append("\n");
        }
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 400);
            v.setFileDiff("src/Main.java", diff.toString());
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            toggle(viewer, DiffViewer.NAME_WRAP);
            FxHarness.pulse(viewer);
        });
        List<Node> areas = FxHarness.call(() -> FxHarness.lookupAll(viewer, "code-area"));
        DiffTextArea left = (DiffTextArea) areas.get(0);
        DiffTextArea right = (DiffTextArea) areas.get(1);

        FxHarness.run(() -> right.showParagraphAtTop(25));
        for (int i = 0; i < 4; i++) {
            FxHarness.run(() -> FxHarness.pulse(viewer));
        }

        FxHarness.run(() -> {
            int anchor = right.firstVisibleParToAllParIndex() + 1;
            double rightTop = right.getParagraphBoundsOnScreen(anchor).orElseThrow().getMinY();
            double leftTop = left.getParagraphBoundsOnScreen(anchor)
                    .orElseThrow(() -> new AssertionError(
                            "row " + anchor + " is not even on screen on the left"))
                    .getMinY();
            assertEquals(rightTop, leftTop, 1.0,
                    "the same row sits at the same height in both columns");
        });
    }

    // ------------------------------------------------------------------
    // Highlighting: bounded, and off the FX thread
    // ------------------------------------------------------------------

    private static boolean hasSyntaxColour(DiffTextArea area, int paragraph) {
        return area.getStyleSpans(paragraph).stream()
                .anyMatch(span -> span.getStyle().stream().anyMatch(c -> c.startsWith("tm-")));
    }

    /**
     * A minified one-line file is shown at once and left uncoloured.
     *
     * <p>The budget was lines only, so a single megabyte-long line was well inside it and
     * went to the grammar whole.
     */
    @Test
    void aMinifiedLineIsLeftPlainInsteadOfLexed() {
        String minified = "var a=1;".repeat(20_000);
        DiffViewer viewer = showViewer();
        long started = System.nanoTime();
        FxHarness.run(() -> {
            viewer.showFileBytes("app.min.js", (minified + "\nvar b = 2;\n").getBytes(StandardCharsets.UTF_8));
            FxHarness.pulse(viewer);
        });
        long millis = (System.nanoTime() - started) / 1_000_000;
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            assertFalse(hasSyntaxColour(area, 0), "the minified line is not lexed");
            assertTrue(hasSyntaxColour(area, 1), "the ordinary line after it still is");
        });
        assertTrue(millis < 5_000, "showing it took " + millis + "ms");
    }

    /**
     * Colours worked out for one file never land on the next.
     *
     * <p>With lexing off the FX thread, a slow answer can arrive after the user has moved
     * on; it is dropped rather than painted over whatever is on screen now.
     */
    @Test
    void aSlowHighlightForTheLastFileIsNotPaintedOnTheNextOne() throws Exception {
        com.jfx.git.ui.syntax.HighlightLane.setSynchronousForTest(false);
        try {
            DiffViewer viewer = showViewer();
            FxHarness.run(() -> {
                viewer.showFileBytes("First.java",
                        "class First { int x = 1; }\n".getBytes(StandardCharsets.UTF_8));
                viewer.showFileBytes("notes.txt",
                        "class First { int x = 1; }\n".getBytes(StandardCharsets.UTF_8));
            });
            com.jfx.git.ui.syntax.HighlightLane.awaitIdleForTest();
            FxHarness.run(() -> FxHarness.pulse(viewer));
            FxHarness.run(() -> assertFalse(hasSyntaxColour(areaOf(viewer), 0),
                    "Java colours on a text file: the first file's answer was applied late"));

            FxHarness.run(() -> viewer.showFileBytes("Second.java",
                    "class Second { int y = 2; }\n".getBytes(StandardCharsets.UTF_8)));
            com.jfx.git.ui.syntax.HighlightLane.awaitIdleForTest();
            FxHarness.run(() -> FxHarness.pulse(viewer));
            FxHarness.run(() -> assertTrue(hasSyntaxColour(areaOf(viewer), 0),
                    "and the current file's own answer is applied when it arrives"));
        } finally {
            com.jfx.git.ui.syntax.HighlightLane.setSynchronousForTest(true);
        }
    }

    /**
     * Context opened with an expander survives a reload of the same file - a whitespace
     * toggle, an encoding change, the repository changing underneath - instead of coming
     * back as blank rows under real line numbers.
     */
    @Test
    void openedContextIsStillThereAfterTheSameFileReloads() {
        String file = String.join("\n",
                "line1", "line2", "line3", "line4", "line5",
                "line6", "line7", "line8", "line9", "line10");
        String diff = """
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
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(path -> file.getBytes(StandardCharsets.UTF_8));
            v.setFileDiff("src/Main.java", diff);
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
        FxHarness.run(() -> {
            ((javafx.scene.control.Button) FxHarness.lookupAll(viewer, "diff-expander-toggle").get(0)).fire();
            FxHarness.pulse(viewer);
            assertTrue(areaOf(viewer).getText().contains("line5"), "precondition: context opened");

            viewer.setFileDiff("src/Main.java", diff);
            FxHarness.pulse(viewer);
            String reloaded = areaOf(viewer).getText();
            assertTrue(reloaded.contains("line5") && reloaded.contains("line6"),
                    "the opened lines must still have their text: " + reloaded);
        });
    }
}
