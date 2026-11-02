package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.RepoInitTemplates;
import com.jfx.git.ui.RobotWait;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the actual starter choices selected in the New Repository form reach init. */
class IncompleteQaRepositoryInitChoicesRobotTest extends ApplicationTest {
    @TempDir Path tempDir;
    private final CountDownLatch dialogReturned = new CountDownLatch(1);
    private final AtomicReference<RepoInitTemplates> received = new AtomicReference<>();

    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(new StackPane(), 400, 280));
        stage.show();
    }

    @AfterEach
    void closeWindows() {
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    /** Whether {@code node} and everything it sits in, up to {@code top}, is visible. */
    private static boolean shown(javafx.scene.Node node, javafx.scene.Node top) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
            if (n == top) return true;
        }
        return true;
    }

    @Test
    void selectedLanguageLicenseAndReadmeArePassedToRepositoryInitialization() throws Exception {
        Path folder = Files.createDirectory(tempDir.resolve("starter-project"));
        Platform.runLater(() -> {
            var dialog = new NewRepositoryDialog(folder.toFile(), NewRepositoryDialog.Purpose.CREATE,
                    templates -> {
                        received.set(templates);
                        return GitResult.ok();
                    }, () -> { });
            dialog.show();
            dialogReturned.countDown();
        });

        DialogPane pane = RobotWait.untilDialogWithButton(NewRepositoryDialog.CREATE);
        interact(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<String> gitignore = (ComboBox<String>) pane.lookup("#init-gitignore");
            assertNotNull(gitignore);
            String javaTemplate = gitignore.getItems().stream()
                    .filter(value -> value.equalsIgnoreCase("Java"))
                    .findFirst().orElseThrow(() -> new AssertionError("Java template is not offered"));
            gitignore.getSelectionModel().select(javaTemplate);

            @SuppressWarnings("unchecked")
            ComboBox<String> license = (ComboBox<String>) pane.lookupAll(".combo-box").stream()
                    .filter(ComboBox.class::isInstance).map(ComboBox.class::cast)
                    .filter(combo -> combo != gitignore).findFirst()
                    .orElseThrow(() -> new AssertionError("license selector is missing"));
            TextField holder = (TextField) pane.lookup("#" + NewRepositoryDialog.HOLDER_ID);
            assertNotNull(holder, "copyright holder field is missing");
            assertFalse(shown(holder, pane), "no copyright holder to ask for while the license is None");

            int mitLicense = license.getItems().indexOf("MIT License");
            assertTrue(mitLicense >= 0);
            license.getSelectionModel().select(mitLicense);

            assertTrue(shown(holder, pane), "the holder is offered once there is a license for it to go in");
            holder.setText("Synthetic QA Author");
            pane.lookupAll(".check-box").stream().filter(CheckBox.class::isInstance)
                    .map(CheckBox.class::cast).findFirst().orElseThrow().setSelected(true);
        });

        Button create = (Button) pane.lookupButton(pane.getButtonTypes().stream()
                .filter(type -> NewRepositoryDialog.CREATE.equals(type.getText())).findFirst().orElseThrow());
        clickOn(create);
        assertTrue(dialogReturned.await(30, TimeUnit.SECONDS), "dialog did not finish");
        RobotWait.until("starter choices were not delivered to init", () -> received.get() != null);

        RepoInitTemplates choices = received.get();
        assertEquals("Java", choices.gitignoreTemplate());
        assertEquals("MIT License", choices.licenseTemplate());
        assertEquals("Synthetic QA Author", choices.copyrightHolder());
        assertTrue(choices.addReadme());
        assertFalse(Files.exists(folder.resolve(".git")), "this test records form choices only");
    }
}
