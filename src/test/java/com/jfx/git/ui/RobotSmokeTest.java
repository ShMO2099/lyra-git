package com.jfx.git.ui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the robot can actually press a button in this environment.
 *
 * <p>Kept deliberately trivial. When a TestFX test fails, the first question is whether
 * the toolkit and the robot are working at all or whether the thing under test is broken;
 * this answers it without involving any of the application.
 */
class RobotSmokeTest extends ApplicationTest {

    private final int[] clicks = {0};

    @Override
    public void start(Stage stage) {
        Button button = new Button("press me");
        button.setId("smoke-button");
        button.setOnAction(e -> clicks[0]++);
        stage.setScene(new Scene(new StackPane(button), 200, 120));
        stage.show();
    }

    @Test
    void theRobotCanClickAButton() {
        clickOn("#smoke-button");
        assertEquals(1, clicks[0], "the robot should have pressed the button once");
    }
}
