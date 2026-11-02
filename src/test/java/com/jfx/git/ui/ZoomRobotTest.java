package com.jfx.git.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whether the interface actually changes size, and fills the window while doing it.
 *
 * <p>{@code UiZoomTest} covers which level is chosen. This covers the half that a
 * preference alone cannot: a scale transform changes how a node is <em>drawn</em> but not
 * the size it was laid out at, so it is entirely possible to have the right percentage
 * stored, the right label showing, and an interface rendered large inside its original
 * rectangle with a strip of dead window beside it.
 */
class ZoomRobotTest extends ApplicationTest {

    private static final double WIDTH = 800;
    private static final double HEIGHT = 600;

    private BorderPane content;
    private Pane host;

    @Override
    public void init() {
        UiZoom.resetForTests();
    }

    @AfterEach
    void tidyUp() {
        UiZoom.resetForTests();
    }

    @Override
    public void start(Stage stage) {
        Label body = new Label("zoom me");
        body.setId("body");
        content = new BorderPane(body);
        host = new Pane(content);
        UiZoom.install(host, content);

        stage.setScene(new Scene(host, WIDTH, HEIGHT));
        stage.show();
    }

    private double scaleX() {
        return content.getTransforms().stream()
                .filter(t -> t instanceof javafx.scene.transform.Scale)
                .map(t -> ((javafx.scene.transform.Scale) t).getX())
                .findFirst().orElseThrow();
    }

    private void setZoom(int percent) {
        interact(() -> UiZoom.set(percent));
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------

    @Test
    void atFullSizeTheContentIsTheSizeOfTheWindow() {
        assertEquals(1.0, scaleX(), 0.0001);
        assertEquals(WIDTH, content.getWidth(), 1.0);
        assertEquals(HEIGHT, content.getHeight(), 1.0);
    }

    @Test
    void zoomingInScalesTheContent() {
        setZoom(150);
        assertEquals(1.5, scaleX(), 0.0001);
    }

    /**
     * The half a stored percentage cannot prove. At 150% the content must be laid out at
     * two thirds of the window so that, once scaled, it covers it exactly.
     */
    @Test
    void theZoomedContentStillFillsTheWindow() {
        setZoom(150);

        assertEquals(WIDTH / 1.5, content.getWidth(), 1.0,
                "scaled up, the content has to be laid out smaller to still cover the window");
        assertEquals(HEIGHT / 1.5, content.getHeight(), 1.0);

        // What is drawn is the laid-out size times the scale, which is the window again.
        assertEquals(WIDTH, content.getWidth() * scaleX(), 1.0);
        assertEquals(HEIGHT, content.getHeight() * scaleX(), 1.0);
    }

    @Test
    void zoomingOutAlsoFillsTheWindow() {
        setZoom(75);

        assertEquals(0.75, scaleX(), 0.0001);
        assertEquals(WIDTH, content.getWidth() * scaleX(), 1.0,
                "shrunk, the content has to be laid out larger or the window is cropped");
    }

    @Test
    void resizingTheWindowKeepsTheContentFilling() {
        setZoom(125);
        interact(() -> {
            host.resize(1000, 500);
            host.layout();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1000, content.getWidth() * scaleX(), 1.0);
        assertEquals(500, content.getHeight() * scaleX(), 1.0);
    }

    @Test
    void resettingReturnsToTheWindowsOwnSize() {
        setZoom(200);
        setZoom(100);

        assertEquals(1.0, scaleX(), 0.0001);
        assertEquals(WIDTH, content.getWidth(), 1.0);
    }

    // ------------------------------------------------------------------
    // The status bar selector
    // ------------------------------------------------------------------

    @Test
    void theStatusBarShowsTheCurrentLevelAndFollowsChanges() {
        StatusBar bar = new StatusBar();
        Node badge = FxHarness.call(() -> {
            bar.applyCss();
            bar.layout();
            return FxHarness.lookupAll(bar, "pill").stream()
                    .filter(n -> n instanceof Label label
                            && label.getText() != null && label.getText().endsWith("%"))
                    .findFirst().orElseThrow();
        });

        assertEquals("100%", ((Label) badge).getText());

        setZoom(125);
        assertEquals("125%", ((Label) badge).getText(),
                "the badge is bound, so a shortcut or another tab's menu updates it too");
    }
}
