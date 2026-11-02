package com.jfx.git.ui;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.fxmisc.richtext.CaretNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A text area nobody refers to any more can be collected.
 *
 * <p>Each area listened to the editor zoom - a static property - through a strong
 * listener, so every one ever created stayed reachable for the life of the process, with
 * its whole text and style spans. Opening files one after another grew the heap without
 * limit; the diff viewer's performance harness showed it as retained memory climbing from
 * one case to the next.
 */
class DiffTextAreaLifetimeTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @Test
    void anAreaNothingRefersToIsCollected() throws Exception {
        WeakReference<DiffTextArea> ref = FxHarness.call(() -> {
            DiffTextArea area = new DiffTextArea();
            area.setLines(List.of("a line", "another"), false);
            return new WeakReference<>(area);
        });

        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(ref.get(), "the zoom listener must not keep a discarded area alive");
    }

    /**
     * The caret blinks on a timer in the toolkit's animation pulse while the area is
     * focused and editable, whether or not its window is still showing; that timer kept
     * every merge tool closed with its result editor focused - Esc, the window's close
     * button - for the life of the process.
     */
    @Test
    void anAreaWhoseWindowClosedWithTheCaretInItIsCollected() throws Exception {
        WeakReference<DiffTextArea> ref = FxHarness.call(() -> {
            DiffTextArea area = new DiffTextArea();
            area.setLines(List.of("a line", "another"), false);
            area.setEditable(true);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(area), 300, 100));
            stage.show();
            stage.requestFocus();
            area.requestFocus();
            return new WeakReference<>(area);
        });
        // Without focus there is no blink, and this would pass for the wrong reason.
        assertTrue(FxHarness.call(() -> ref.get().isFocused()), "the area should have the caret");

        FxHarness.run(() -> ref.get().getScene().getWindow().hide());

        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(ref.get(), "the caret's blink must not keep an area whose window has closed");
    }

    @Test
    void theCaretBlinksAgainWhenItsWindowComesBack() {
        FxHarness.run(() -> {
            DiffTextArea area = new DiffTextArea();
            area.setEditable(true);
            CaretNode caret = area.getCaretSelectionBind().getUnderlyingCaret();
            Duration blinking = caret.getBlinkRate();
            assertTrue(blinking.greaterThan(Duration.ZERO), "a caret that blinks to begin with");
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(area), 300, 100));
            try {
                stage.show();
                assertEquals(blinking, caret.getBlinkRate());
                stage.hide();
                assertEquals(Duration.ZERO, caret.getBlinkRate(), "no timer for a window that has gone");
                stage.show();
                assertEquals(blinking, caret.getBlinkRate(), "a window shown again blinks again");
            } finally {
                stage.hide();
            }
        });
    }
}
