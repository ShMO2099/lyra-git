package com.jfx.git.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a second copy of the application says before it exits: a notice in the dialog
 * shell, nothing to decide, one way out.
 */
class AlreadyRunningNoticeTest {

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @Test
    void itSaysTheApplicationIsAlreadyRunningAndOffersOnlyClose() {
        record Seen(String window, String title, String message, List<ButtonType> buttons,
                    String closeLabel, List<String> styles) { }
        Seen seen = FxHarness.call(() -> {
            Alert alert = AlreadyRunningNotice.build();
            Button close = (Button) alert.getDialogPane().lookupButton(ButtonType.OK);
            return new Seen(alert.getTitle(), alert.getHeaderText(), alert.getContentText(),
                    List.copyOf(alert.getButtonTypes()), close.getText(),
                    List.copyOf(alert.getDialogPane().getStyleClass()));
        });

        assertEquals("Lyra", seen.window());
        assertEquals("Lyra is already running", seen.title());
        assertTrue(seen.message().contains("Use the window that is already open"), seen.message());
        assertEquals(List.of(ButtonType.OK), seen.buttons(), "a notice: nothing to choose between");
        assertEquals("Close", seen.closeLabel());
        assertTrue(seen.styles().contains("jfx-dialog"), "in the application's dialog shell: " + seen.styles());
    }
}
