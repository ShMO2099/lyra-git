package com.jfx.git.ui;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The graph filter bar's row: the options beside the box while both fit, on a line of their
 * own when not, wrapped there when even that is too narrow - and decided again whenever the
 * options change width, not only when the row does.
 *
 * <p>The bar used to decide in a listener on its own width. Typing brought a clear button
 * into the options, the options outgrew the room beside the box, the bar's width did not
 * change, and "Search all history" was cut to "Search all hi…".
 */
class StackingRowTest {

    private static final double COMFORTABLE = 220;
    private static final double HGAP = 8;
    private static final double VGAP = 4;

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    /** A lead, a trail of 60-pixel chips, and a region under the row to show its height. */
    private record Laid(StackingRow row, Region lead, FlowPane trail, Region below) {
    }

    private static Laid show(double width, int chips) {
        return FxHarness.call(() -> {
            Region lead = new Region();
            lead.setPrefSize(100, 28);
            FlowPane trail = new StackingRow.Flow(4, 4);
            for (int i = 0; i < chips; i++) {
                trail.getChildren().add(chip());
            }
            StackingRow row = new StackingRow(lead, trail, COMFORTABLE, HGAP, VGAP);
            Region below = new Region();
            below.setPrefHeight(50);
            VBox root = new VBox(row, below);
            FxHarness.show(root, width, 400);
            return new Laid(row, lead, trail, below);
        });
    }

    private static Region chip() {
        Region chip = new Region();
        chip.setPrefSize(60, 20);
        return chip;
    }

    private static Bounds inRow(Laid laid, Node node) {
        return laid.row().sceneToLocal(node.localToScene(node.getLayoutBounds()));
    }

    /** The trail on one line at the row's end, and the lead taking the rest. On the FX thread. */
    private static void assertBeside(Laid laid, double rowWidth) {
        FlowPane trail = laid.trail();
        assertFalse(laid.row().isStacked());
        assertEquals(rowWidth, trail.getLayoutX() + trail.getWidth(), 0.5, "the trail ends the row");
        assertEquals(trail.getLayoutX() - HGAP, laid.lead().getWidth(), 0.5, "the lead takes the rest");
        assertTrue(laid.lead().getWidth() >= COMFORTABLE, "and keeps its comfortable width");
        for (Node chip : trail.getChildren()) {
            assertEquals(0, chip.getLayoutY(), 0.5, "every chip on the one line");
        }
        assertEquals(28, laid.row().getHeight(), 0.5, "one line high");
    }

    @Test
    void theTrailSitsBesideTheLeadWhileTheLeadKeepsItsComfortableWidth() {
        // Three chips and two gaps: 188. 220 + 8 + 188 = 416.
        Laid laid = show(600, 3);
        FxHarness.run(() -> assertBeside(laid, 600));
    }

    @Test
    void theTrailMovesUnderTheLeadWhenItGrowsAndBackWhenItShrinks() {
        Laid laid = show(450, 3);
        FxHarness.run(() -> assertBeside(laid, 450));

        // A fourth chip makes 252, and 220 + 8 + 252 = 480: the row has not changed width,
        // only what it holds.
        FxHarness.run(() -> {
            laid.trail().getChildren().add(chip());
            FxHarness.pulse(laid.row().getParent());
        });
        FxHarness.run(() -> {
            assertTrue(laid.row().isStacked());
            assertEquals(0, laid.trail().getLayoutX(), 0.5, "at the start of a line of its own");
            assertEquals(28 + VGAP, laid.trail().getLayoutY(), 0.5, "under the lead");
            assertEquals(450, laid.lead().getWidth(), 0.5, "the lead has the whole width");
            assertTrue(laid.below().getLayoutY() >= laid.trail().getLayoutY() + laid.trail().getHeight(),
                    "the row grew by the trail's line rather than drawing it over what follows");
        });

        FxHarness.run(() -> {
            laid.trail().getChildren().remove(3);
            FxHarness.pulse(laid.row().getParent());
        });
        FxHarness.run(() -> assertBeside(laid, 450));
    }

    @Test
    void aTrailWiderThanItsOwnLineWrapsThereWithEveryChipInsideTheRow() {
        // 188 of chips in 150: two on the first line, one on the second.
        Laid laid = show(150, 3);
        FxHarness.run(() -> {
            assertTrue(laid.row().isStacked());
            assertEquals(150, laid.trail().getWidth(), 0.5);
            assertEquals(20 + 4 + 20, laid.trail().getHeight(), 0.5, "two lines of chips");
            for (Node chip : laid.trail().getChildren()) {
                Bounds bounds = inRow(laid, chip);
                assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= 150.5,
                        "a chip ran past the row: " + bounds);
            }
            assertEquals(28 + VGAP + 44, laid.row().getHeight(), 0.5);
            assertTrue(laid.below().getLayoutY() >= laid.row().getHeight() - 0.5);
        });
    }
}
