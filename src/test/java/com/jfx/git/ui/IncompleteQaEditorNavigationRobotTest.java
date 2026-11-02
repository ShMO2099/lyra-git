package com.jfx.git.ui;

import com.jfx.git.core.GitResult;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Dirty built-in editor navigation must explicitly save, discard, or cancel. */
class IncompleteQaEditorNavigationRobotTest extends ApplicationTest {
    @TempDir Path temp;
    private DiffViewer viewer;

    @Override public void start(Stage stage) throws Exception {
        Files.writeString(temp.resolve("a.txt"), "disk original\n", StandardCharsets.UTF_8);
        viewer = new DiffViewer();
        viewer.setEditable(true);
        viewer.setWorkingCopyExistsCheck(path -> true);
        viewer.setFileContentSupplier(path -> {
            try { return Files.readAllBytes(temp.resolve(path)); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
        viewer.setOnSaveFile((path, text, result) -> {
            try {
                Files.write(temp.resolve(path), viewer.getEncoding().encode(text,
                        Files.readAllBytes(temp.resolve(path))));
                result.accept(GitResult.ok("saved"));
            } catch (Exception e) {
                result.accept(GitResult.failed(e.getMessage()));
            }
        });
        viewer.showFileContent("a.txt", "disk original\n");
        stage.setScene(new Scene(viewer, 900, 600));
        stage.show();
        interact(viewer::beginEditing);
        interact(() -> viewer.replaceBuffer("saved edit\n"));
    }

    @AfterEach void tidyUp() {
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void attemptNavigation() { Platform.runLater(() -> viewer.showFileContent("b.txt", "next file\n")); }

    private void answer(String label) {
        DialogPane pane = RobotWait.untilDialogWithButton(label);
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream().filter(b -> label.equals(b.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("missing " + label));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    @Test void cancelKeepsTheCurrentDirtyBufferAndRefusesNavigation() {
        attemptNavigation();
        answer("Cancel");
        assertEquals("a.txt", viewer.filePathProperty().get());
        assertTrue(viewer.isEditing());
        assertTrue(viewer.hasUnsavedEdits());
        assertEquals("disk original\n", read("a.txt"));
    }

    @Test void discardLeavesDiskAloneAndOpensTheRequestedFile() {
        attemptNavigation();
        answer("Discard edits");
        RobotWait.untilOnFx("discarded navigation did not open the next file",
                () -> "b.txt".equals(viewer.filePathProperty().get()));
        assertEquals("disk original\n", read("a.txt"));
        assertFalse(viewer.isEditing());
    }

    @Test void saveWritesTheEditedBytesBeforeOpeningTheRequestedFile() {
        attemptNavigation();
        answer("Save");
        RobotWait.untilOnFx("saved navigation did not open the next file",
                () -> "b.txt".equals(viewer.filePathProperty().get()));
        assertEquals("saved edit\n", read("a.txt"));
        assertFalse(viewer.isEditing());
    }

    private String read(String name) {
        try { return Files.readString(temp.resolve(name), StandardCharsets.UTF_8); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
