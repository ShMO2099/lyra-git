package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dialog's window cannot be made smaller than the floor its pane declares.
 *
 * <p>The shell gives each preset a floor of three quarters of its size, so a dialog dragged
 * small does not cut off its content. JavaFX's dialog window never reads it: the window
 * went down to nothing while the pane stuck out of it.
 */
class DialogWindowFloorTest {

    private Dialog<?> shown;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void close() {
        FxHarness.run(() -> {
            if (shown != null) {
                shown.setResult(null);
                shown.close();
            }
        });
    }

    @Test
    void aResizableDialogsWindowKeepsToItsPanesFloor() {
        Alert alert = FxHarness.call(() -> {
            Alert built = Dialogs.confirm("Floor").title("Make me small").message("Some text to hold.")
                    .size(JfxDialog.Size.MD).build();
            built.show();
            return built;
        });
        shown = alert;
        RobotWait.untilOnFx("the dialog never showed", alert::isShowing);

        double[] seen = FxHarness.call(() -> {
            Stage window = (Stage) alert.getDialogPane().getScene().getWindow();
            window.setWidth(100);
            window.setHeight(80);
            return new double[]{window.getMinWidth(), window.getMinHeight(), window.getWidth(),
                    alert.getDialogPane().minWidth(-1)};
        });
        assertTrue(alert.getDialogPane().getButtonTypes().contains(ButtonType.OK));
        assertTrue(seen[0] >= seen[3], "the window's minimum width " + seen[0]
                + " must hold the pane's floor " + seen[3]);
        assertTrue(seen[1] > 0, "the window must have a minimum height");
    }
}
