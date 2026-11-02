package com.jfx.git.ui;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the headless robot can and cannot do.
 *
 * <p>Exists so the shape of the robot tests is explained rather than merely odd. The
 * context menus are opened by firing a {@link ContextMenuEvent} instead of by
 * right-clicking, and this is why: under Monocle Headless a synthesised right-click never
 * becomes a context-menu request, because that mapping happens in the native glass layer
 * the headless platform replaces. Clicking an item in an open popup works normally.
 *
 * <p>If one of these ever fails, Monocle has gained (or lost) an ability and the robot
 * tests can be simplified (or need more care). That is worth being told about.
 */
class MonocleCapabilitiesTest extends ApplicationTest {

    private Rectangle target;
    private ContextMenu menu;
    private final boolean[] itemFired = {false};

    @Override
    public void start(Stage stage) {
        // Built here, not in a field initialiser: a ContextMenu touches the platform
        // stylesheet on construction, and the toolkit does not exist until now.
        menu = new ContextMenu();
        MenuItem item = new MenuItem("PROBE ITEM");
        item.setOnAction(e -> itemFired[0] = true);
        menu.getItems().add(item);

        target = new Rectangle(300, 200, Color.CORNFLOWERBLUE);
        target.setOnContextMenuRequested(e -> {
            menu.hide();
            menu.show(target, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        stage.setScene(new Scene(new StackPane(target), 400, 300));
        stage.show();
    }

    private long popups() {
        return Window.getWindows().stream()
                .filter(w -> w instanceof javafx.stage.PopupWindow && w.isShowing())
                .count();
    }

    @Test
    void aSynthesisedRightClickDoesNotBecomeAContextMenuRequest() {
        rightClickOn(target);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0, popups(),
                "if this starts failing, Monocle can synthesise the gesture and the robot "
                        + "tests can right-click instead of firing the event by hand");
    }

    @Test
    void aFiredContextMenuEventOpensThePopup() {
        interact(() -> Event.fireEvent(target, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, popups(), "this is how the robot tests open a context menu");
    }

    @Test
    void theRobotCanClickAnItemInAnOpenPopup() {
        interact(() -> {
            menu.hide();
            menu.show(target, 100, 100);
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, popups(), "the popup should be open before the click");

        clickOn("PROBE ITEM");
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(itemFired[0], "clicking menu entries is the part that has to work");
    }
}
