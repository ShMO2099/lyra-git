package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The editor must preserve both versions until the user reconciles an outside write. */
class ManualQaExternalEditWarningTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.com");
                config.setString("core", null, "autocrlf", "false");
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
            }
            service = new GitService();
            service.openRepository(tempDir.toFile());
            write("a.txt", "original\n");
            assertTrue(service.stageFile("a.txt").isOk());
            assertTrue(service.commit("add a", false).isOk());
            write("staged-only.txt", "preserve this staged blob\n");
            assertTrue(service.stageFile("staged-only.txt").isOk());
        }

        tab = new RepoTab(new LyraApp(), "external-edit-warning", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        interact(tab::refresh);
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void write(String path, String content) throws Exception {
        Files.writeString(tempDir.resolve(path), content, StandardCharsets.UTF_8);
    }

    private String diskText() throws Exception {
        return Files.readString(tempDir.resolve("a.txt"), StandardCharsets.UTF_8);
    }

    private DiffViewer editFileWithUnsavedDraft() throws Exception {
        write("a.txt", "original\napp draft\n");
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("a.txt never appeared in the unstaged list",
                () -> lookup("a.txt").tryQuery().isPresent());
        RobotWait.drainGitWork();
        Node list = lookup(".staging-unstaged-list").query();
        clickOn(from(list).lookup("a.txt").match(node -> node.getScene() != null).queryAs(Node.class));

        DiffViewer viewer = lookup(".diff-viewer").queryAs(DiffViewer.class);
        RobotWait.untilOnFx("a.txt never loaded into the diff viewer",
                () -> "a.txt".equals(viewer.filePathProperty().get()));
        interact(viewer::beginEditing);
        interact(() -> viewer.replaceBuffer("original\napp draft, edited\n"));
        assertTrue(FxHarness.call(viewer::isEditing));
        assertTrue(FxHarness.call(viewer::hasUnsavedEdits));
        return viewer;
    }

    private void fireSaveWithoutWaiting(DiffViewer viewer) {
        Platform.runLater(viewer::saveEdits);
    }

    private void answerDialog(String buttonText) {
        DialogPane pane = RobotWait.untilDialogWithButton(buttonText);
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(candidate -> candidate.getText().startsWith(buttonText))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "missing dialog button " + buttonText + ": "
                                    + pane.getButtonTypes().stream().map(ButtonType::getText).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    private static List<String> dialogLabels(DialogPane pane) {
        return FxHarness.lookupAll(pane, "label").stream()
                .filter(javafx.scene.control.Label.class::isInstance)
                .map(javafx.scene.control.Label.class::cast)
                .map(javafx.scene.control.Label::getText)
                .toList();
    }

    @Test
    void cancellingThenComparingPreservesTheExternalWriteAndLetsMeAcceptIt() throws Exception {
        DiffViewer viewer = editFileWithUnsavedDraft();
        String external = "original\nexternal worker\n";
        write("a.txt", external);
        List<String> stagedBefore = service.getStagingState().staged();
        String stagedBlobBefore = stagedOnlyBlob();

        fireSaveWithoutWaiting(viewer);
        answerDialog("Cancel");
        WaitForAsyncUtils.waitForFxEvents();
        RobotWait.drainGitWork();
        assertEquals(external, diskText(), "Cancel must not overwrite the other editor's bytes");
        assertTrue(FxHarness.call(viewer::isEditing), "the editor remains open after Cancel");
        assertTrue(FxHarness.call(viewer::hasUnsavedEdits), "the app draft remains available after Cancel");
        assertTrue(FxHarness.call(viewer::editorText).contains("app draft, edited"));
        assertEquals(stagedBefore, service.getStagingState().staged(),
                "the warning flow leaves the index alone");
        assertEquals(stagedBlobBefore, stagedOnlyBlob(),
                "cancelling the warning must preserve the existing index bytes");

        fireSaveWithoutWaiting(viewer);
        answerDialog("Compare…");
        DialogPane comparison = RobotWait.untilDialogWithButton("Use this text");
        List<String> labels = FxHarness.call(() -> dialogLabels(comparison));
        assertTrue(labels.contains("My edits"), labels.toString());
        assertTrue(labels.contains("On disk now"), labels.toString());
        assertTrue(labels.contains("Result") && labels.contains("editable"), labels.toString());

        interact(() -> {
            // A comparison's whole-file choice is "Start result from", one button per version.
            Button onDisk = FxHarness.lookupAll(comparison, "button").stream()
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> "On disk now".equals(button.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "comparison dialog cannot start the result from the file on disk"));
            onDisk.fire();
        });
        answerDialog("Use this text");
        RobotWait.until("the accepted disk version was not saved",
                () -> {
                    try {
                        return external.equals(diskText());
                    } catch (Exception inProgress) {
                        return false;
                    }
                });
        RobotWait.untilOnFx("the editor did not finish saving the accepted comparison",
                () -> !viewer.hasUnsavedEdits());
        assertEquals(external, diskText());
        assertEquals(stagedBefore, service.getStagingState().staged(),
                "accepting the disk text must preserve the prior staged blob");
        assertEquals(stagedBlobBefore, stagedOnlyBlob(),
                "the bytes already in the index must remain untouched");
    }

    private String stagedOnlyBlob() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            var repository = git.getRepository();
            var cache = repository.readDirCache();
            int entryIndex = cache.findEntry("staged-only.txt");
            assertTrue(entryIndex >= 0, "the pre-existing staged file must stay in the index");
            var entry = cache.getEntry(entryIndex);
            return new String(repository.getObjectDatabase().open(entry.getObjectId()).getBytes(),
                    StandardCharsets.UTF_8);
        }
    }
}
