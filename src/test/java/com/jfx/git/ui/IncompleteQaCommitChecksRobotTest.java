package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitService;
import com.jfx.git.core.Settings;
import com.jfx.git.core.ThreadGuard;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The staged-secret warning preserves the commit on Cancel and offers a safe remedy. */
class IncompleteQaCommitChecksRobotTest extends ApplicationTest {
    @TempDir Path repository;
    private GitService service;
    private RepoTab tab;
    private String headBefore;

    @Override public void start(Stage stage) throws Exception {
        Settings.useFile(repository.resolve("qa-settings.json"));
        AppPreferences.setCheckSecretsBeforeCommit(true);
        AppPreferences.setCheckLargeFilesBeforeCommit(false);
        try (var fixture = ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA User");
                config.setString("user", null, "email", "qa-user@example.test");
                config.setString("core", null, "autocrlf", "false");
                config.setString("core", null, "excludesfile", repository.resolve("qa-global-ignore").toString());
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
                Files.writeString(repository.resolve("base.txt"), "base\n", StandardCharsets.UTF_8);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("seed").call();
                Files.writeString(repository.resolve(".env"), "synthetic=value\n", StandardCharsets.UTF_8);
                Files.writeString(repository.resolve("safe.txt"), "ordinary text\n", StandardCharsets.UTF_8);
                git.add().addFilepattern(".env").addFilepattern("safe.txt").call();
                headBefore = git.getRepository().resolve("HEAD").name();
            }
        }
        service = new GitService();
        assertTrue(service.openRepository(repository.toFile()));
        tab = new RepoTab(new LyraApp(), "commit-check-cancel", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        tab.refresh();
    }

    @AfterEach void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        Settings.useFile(null);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void showStaging() {
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("staging view did not show both staged paths", () -> {
            List<?> items = lookup(".staging-staged-list").queryAs(ListView.class).getItems();
            return items.containsAll(List.of(".env", "safe.txt"));
        });
    }

    private void openFindingDialog() {
        interact(() -> ((TextInputControl) lookup(".composer-message").query()).setText("safe commit"));
        clickOn("Commit 2 files to " + service.getCurrentBranch());
    }

    private DialogPane dialog(String button) { return RobotWait.untilDialogWithButton(button); }

    private void answer(DialogPane pane, String label) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream().filter(t -> label.equals(t.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("missing dialog button " + label));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    private String currentHeadMessage() {
        try (Git git = Git.open(repository.toFile())) {
            return git.getRepository().parseCommit(git.getRepository().resolve("HEAD")).getFullMessage();
        } catch (Exception inProgress) { return ""; }
    }

    @Test void cancelPreservesIndexThenUnstageRemedyCommitsOnlyTheSafeFile() throws Exception {
        showStaging();
        openFindingDialog();
        DialogPane warning = dialog("Cancel");
        assertTrue(FxHarness.lookupAll(warning, "label").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Label l && l.getText().contains(".env")));
        answer(warning, "Cancel");
        RobotWait.drainGitWork();
        assertEquals(headBefore, service.getHeadHash(), "Cancel must not commit around the warning");
        assertEquals(List.of(".env", "safe.txt"), service.getStagingState().staged());
        assertEquals("synthetic=value\n", Files.readString(repository.resolve(".env")));

        openFindingDialog();
        DialogPane remedy = dialog("Cancel");
        interact(() -> {
            ToggleButton unstage = FxHarness.lookupAll(remedy, "commit-checks-unstage").stream()
                    .filter(ToggleButton.class::isInstance).map(ToggleButton.class::cast)
                    .filter(b -> "Unstage .env".equals(b.getAccessibleText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no .env remedy"));
            unstage.fire();
            Button commit = FxHarness.lookupAll(remedy, "commit-checks-commit").stream()
                    .filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow();
            assertEquals("Commit", commit.getText(), "handling the finding changes the dialog action label");
            commit.fire();
        });
        RobotWait.until("the safe file was not committed after unstaging .env", () ->
                "safe commit".equals(currentHeadMessage()));
        RobotWait.drainGitWork();
        assertEquals(List.of(), service.getStagingState().staged());
        assertEquals("synthetic=value\n", Files.readString(repository.resolve(".env")),
                "unstaging preserves the file on disk");
        assertTrue(Files.exists(repository.resolve("safe.txt")));
    }
}
