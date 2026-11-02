package com.jfx.git.ui;

import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text's own viewport: a horizontal bar only when a line is wider than the view,
 * kept in step with the text one way at a time, and no vertical bar at all - the
 * {@link DiffScrollMap} is that, and a hidden native one is what used to write scroll
 * positions back into the text and set it shaking.
 */
class DiffScrollPaneTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private static DiffScrollPane shown(List<String> lines) {
        return FxHarness.call(() -> {
            DiffTextArea area = new DiffTextArea();
            DiffScrollPane pane = new DiffScrollPane(area);
            Stage stage = FxHarness.show(new StackPane(pane), 300, 200);
            area.setLines(lines, false);
            FxHarness.pulse(pane);
            FxHarness.pulse(pane);
            stage.setUserData("keep-alive");
            return pane;
        });
    }

    @Test
    void thereIsNoVerticalBarAtAll() {
        DiffScrollPane pane = shown(List.of("one", "two", "three"));
        FxHarness.run(() -> assertNull(pane.lookup(".scroll-bar:vertical")));
    }

    @Test
    void theHorizontalBarAppearsOnlyForLinesWiderThanTheView() {
        DiffScrollPane narrow = shown(List.of("short", "lines"));
        DiffScrollPane wide = shown(List.of("x".repeat(400), "short"));
        FxHarness.run(() -> {
            assertFalse(narrow.horizontalBar().isVisible(), "everything fits: no bar");
            assertTrue(wide.horizontalBar().isVisible(), "a 400-character line in a 300px view: a bar");
            assertEquals(narrow.getHeight(), narrow.getContent().getHeight(), 0.5,
                    "with no bar the text has the whole height");
            assertTrue(wide.getContent().getHeight() < wide.getHeight() - 5,
                    "with a bar the text gives up the bar's height");
        });
    }

    @Test
    void draggingTheBarScrollsTheTextAndTheTextMovesTheBar() {
        DiffScrollPane pane = shown(List.of("x".repeat(400), "short"));
        FxHarness.run(() -> {
            pane.horizontalBar().setValue(pane.horizontalBar().getMax() / 2);
            FxHarness.pulse(pane);
            FxHarness.pulse(pane);
            assertTrue(pane.getContent().getEstimatedScrollX() > 10, "the bar drove the text");

            pane.getContent().scrollXToPixel(0);
            FxHarness.pulse(pane);
            FxHarness.pulse(pane);
            assertEquals(0, pane.horizontalBar().getValue(), 0.5, "the text drove the bar back");
        });
    }
}
