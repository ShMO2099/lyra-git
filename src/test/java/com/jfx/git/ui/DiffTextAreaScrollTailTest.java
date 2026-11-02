package com.jfx.git.ui;

import javafx.scene.Node;

import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scrolling beyond the last line: the tail is real padding on the last paragraph's box,
 * it tracks the viewport, and an area that never asked for one is laid out as before.
 *
 * <p>The first test is the proof the whole mechanism rests on - that the area's
 * generated stylesheet reaches {@code -fx-padding} on the last paragraph box and no
 * other. Everything else here assumes it.
 */
class DiffTextAreaScrollTailTest {

    private static final javafx.css.PseudoClass LAST_PARAGRAPH =
            javafx.css.PseudoClass.getPseudoClass("last-paragraph");

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private record Fixture(DiffTextArea area, DiffScrollPane scroll, VBox root) {
        Fixture settled() {
            return DiffTextAreaScrollTailTest.settled(this);
        }
    }

    /**
     * An area of {@code lines} lines in a scroll pane {@code viewportHeight} tall, with
     * the diff stylesheet, in a stage taller than that so the pane's height is its own.
     */
    private static Fixture show(int lines, double viewportHeight, boolean beyond) {
        return FxHarness.call(() -> {
            DiffTextArea area = new DiffTextArea();
            DiffScrollPane scroll = new DiffScrollPane(area);
            scroll.setPrefHeight(viewportHeight);
            scroll.setMaxHeight(Region.USE_PREF_SIZE);
            VBox root = new VBox(scroll);
            root.getStylesheets().add(DiffTextAreaScrollTailTest.class
                    .getResource("/diff-styles.css").toExternalForm());
            Stage stage = FxHarness.show(root, 400, 700);
            area.setLines(IntStream.rangeClosed(1, lines).mapToObj(i -> "line " + i).toList(), false);
            area.setScrollBeyondLastLine(beyond);
            FxHarness.pulse(root);
            FxHarness.pulse(root);
            stage.setUserData("keep-alive");
            return new Fixture(area, scroll, root);
        }).settled();
    }

    /**
     * Lets a deferred tail re-measure land and the stylesheet take effect: the area
     * re-measures after a layout pass, later rather than within it, so a fixture is not
     * final until the queue behind it has run and the next pulses have applied the CSS.
     */
    private static Fixture settled(Fixture f) {
        FxHarness.run(() -> {
            FxHarness.pulse(f.root());
            FxHarness.pulse(f.root());
        });
        FxHarness.run(() -> {
            FxHarness.pulse(f.root());
            FxHarness.pulse(f.root());
        });
        return f;
    }

    private static List<Region> boxes(DiffTextArea area) {
        return area.lookupAll(".paragraph-box").stream().map(n -> (Region) n).toList();
    }

    private static Region lastBox(DiffTextArea area) {
        return boxes(area).stream()
                .filter(b -> b.getPseudoClassStates().contains(LAST_PARAGRAPH))
                .findFirst().orElseThrow(() -> new AssertionError("no last-paragraph box on screen"));
    }

    private static Region aBoxThatIsNotLast(DiffTextArea area) {
        return boxes(area).stream()
                .filter(b -> !b.getPseudoClassStates().contains(LAST_PARAGRAPH))
                .findFirst().orElseThrow(() -> new AssertionError("only the last box is on screen"));
    }

    private static List<String> tailStylesheets(DiffTextArea area) {
        return area.getStylesheets().stream().filter(s -> s.startsWith("data:text/css")).toList();
    }

    @Test
    void theTailStylesheetBecomesTheLastParagraphBoxesBottomPadding() {
        Fixture f = show(3, 300, true);
        FxHarness.run(() -> {
            double tail = f.area().tailPadding();
            assertTrue(tail > 200, "a 300px viewport less one row: " + tail);
            assertEquals(List.of(DiffTextArea.tailStylesheet(tail)), tailStylesheets(f.area()),
                    "one generated sheet on the area, for the current tail");
            assertEquals(tail, lastBox(f.area()).getPadding().getBottom(), 0.01,
                    "the rule reached the last box's -fx-padding");
            assertEquals(-0.5, lastBox(f.area()).getPadding().getTop(), 0.01,
                    "and kept RichTextFX's own top inset");
            assertEquals(-0.5, aBoxThatIsNotLast(f.area()).getPadding().getBottom(), 0.01,
                    "every other box keeps RichTextFX's own padding");
        });
    }

    @Test
    void anAreaThatNeverAskedKeepsRichTextFXsOwnPadding() {
        // The merge tool's panes share diff-styles.css and never ask for a tail.
        Fixture f = show(3, 300, false);
        FxHarness.run(() -> {
            assertEquals(0, f.area().tailPadding());
            assertEquals(List.of(), tailStylesheets(f.area()));
            for (Region box : boxes(f.area())) {
                assertEquals(-0.5, box.getPadding().getBottom(), 0.01, "unchanged, last box included");
            }
        });
    }

    @Test
    void withTheTailTheLastLineCanBeScrolledToTheTop() {
        Fixture on = show(3, 300, true);
        Fixture off = show(3, 300, false);
        FxHarness.run(() -> {
            on.area().showParagraphAtTop(2);
            off.area().showParagraphAtTop(2);
            FxHarness.pulse(on.root());
            FxHarness.pulse(off.root());
            assertEquals(2, on.area().firstVisibleParToAllParIndex(),
                    "three lines in a 300px viewport, and the third is at the top");
            assertEquals(0, off.area().firstVisibleParToAllParIndex(),
                    "without the tail there is nowhere to scroll to");
        });
    }

    @Test
    void onAShortFileTheHeightEstimateGrowsByTheTail() {
        // Three lines, all on screen, so every cell is measured and Flowless's total is a
        // sum rather than an estimate. Never assert this on a long document: there the
        // total is cells x the average of the cells seen so far, and one tall cell moves
        // it by far more than its own height.
        Fixture f = show(3, 300, true);
        FxHarness.run(() -> {
            double row = f.area().rowHeight();
            double expected = 3 * row + f.area().tailPadding();
            assertEquals(expected, f.area().getTotalHeightEstimate(), 2,
                    "three rows plus the blank tail");
        });
    }

    @Test
    void theTailTracksTheViewportHeight() {
        Fixture f = show(3, 200, true);
        double before = FxHarness.call(() -> f.area().tailPadding());
        FxHarness.run(() -> f.scroll().setPrefHeight(400));
        f.settled();
        FxHarness.run(() -> {
            double after = f.area().tailPadding();
            assertEquals(200, after - before, 8, "200px more viewport, 200px more tail (to a 4px step)");
        });
    }

    @Test
    void turningTheTailOffRemovesTheStylesheetAndTheLastBoxShrinksBack() {
        Fixture f = show(3, 300, true);
        FxHarness.run(() -> f.area().setScrollBeyondLastLine(false));
        f.settled();
        FxHarness.run(() -> {
            assertEquals(0, f.area().tailPadding());
            assertEquals(List.of(), tailStylesheets(f.area()), "nothing left behind");
            assertEquals(-0.5, lastBox(f.area()).getPadding().getBottom(), 0.01);
            assertTrue(f.area().getStyle().contains("-fx-font-size"), "the font size is untouched");
        });
    }

    @Test
    void zoomingReMeasuresTheTailAgainstTheLargerRows() {
        Fixture f = show(3, 300, true);
        try {
            double before = FxHarness.call(() -> f.area().tailPadding());
            FxHarness.run(() -> EditorZoom.set(150));
            f.settled();
            FxHarness.run(() -> {
                assertTrue(f.area().getStyle().contains("-fx-font-size: 19.5px"), f.area().getStyle());
                double after = f.area().tailPadding();
                assertTrue(after > 0 && after <= before,
                        "bigger rows leave the same viewport less room: " + before + " -> " + after);
                // Measured against the zoomed rows, not the unstyled ones the cells are
                // rebuilt with first: the pass after the zoom has to re-measure.
                double row = f.area().rowHeight();
                assertEquals(Math.ceil((f.area().getHeight() - row) / 4) * 4, after, 0.01,
                        "a viewport less one " + row + "px row, to a 4px step");
                assertEquals(1, tailStylesheets(f.area()).size(), "still exactly one sheet");
            });
        } finally {
            FxHarness.run(EditorZoom::resetForTests);
        }
    }

    /** The row model the scrollbar is built on reads the unpadded rows, never the tall last one. */
    @Test
    void theRowHeightIgnoresThePaddedLastBox() {
        Fixture f = show(3, 300, true);
        FxHarness.run(() -> {
            Node notLast = aBoxThatIsNotLast(f.area());
            assertEquals(((Region) notLast).getHeight(), f.area().rowHeight(), 0.01);
            assertTrue(lastBox(f.area()).getHeight() > f.area().rowHeight() + 100,
                    "the last box carries the tail");
        });
    }
}
