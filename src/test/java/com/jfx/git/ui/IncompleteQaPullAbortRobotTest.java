package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.model.MergeMode;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the confirmation and accepted abort path for a real conflicting merge. */
class IncompleteQaPullAbortRobotTest extends ApplicationTest {
    @TempDir Path repository;

    private GitService service;
    private RepoTab tab;
    private String mainTip;
    private String featureTip;

    @Override
    public void start(Stage stage) throws Exception {
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (var git = org.eclipse.jgit.api.Git.init().setDirectory(repository.toFile())
                    .setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "QA Synthetic");
                config.setString("user", null, "email", "qa@example.test");
                config.setBoolean("commit", null, "gpgsign", false);
                config.setBoolean("core", null, "autocrlf", false);
                config.save();
            }
            service = new GitService();
            service.openRepository(repository.toFile());
            commit("base\n", "base");
            assertTrue(service.createBranch("feature", null, true).isOk());
            commit("incoming\n", "feature edit");
            featureTip = service.getHeadHash();
            assertTrue(service.checkoutBranch("main").isOk());
            commit("local\n", "main edit");
            mainTip = service.getHeadHash();
        }

        tab = new RepoTab(new LyraApp(), "abort fixture", service);
        stage.setScene(new Scene(new TabPane(tab), 1100, 680));
        stage.show();
        interact(tab::refresh);
    }

    @AfterEach
    void closeFixture() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(String content, String subject) throws Exception {
        Files.writeString(repository.resolve("shared.txt"), content);
        assertTrue(service.stageFile("shared.txt").isOk());
        assertTrue(service.commit(subject, false).isOk());
    }

    @Test
    void cancellingAbortKeepsTheConflictAndAcceptingRestoresThePreMergeState() throws Exception {
        service.merge("feature", MergeMode.DEFAULT, "synthetic merge");
        assertTrue(service.getOperationState().inProgress(), "the divergent rewrite must conflict");
        assertEquals(mainTip, service.getHeadHash());
        assertFalse(service.getStagingState().conflicting().isEmpty());
        interact(tab::refresh);
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the merge operation banner did not appear",
                () -> lookup(".operation-banner").tryQuery().isPresent());

        clickOn(lookup(".operation-banner").lookup("Abort merge").queryAs(javafx.scene.Node.class));
        DialogPane cancelDialog = RobotWait.untilDialogWithButton("OK", "Cancel");
        clickOn(cancelDialog.lookupButton(ButtonType.CANCEL));
        RobotWait.drainGitWork();
        assertTrue(service.getOperationState().inProgress(), "Cancel must preserve the merge");
        assertEquals(mainTip, service.getHeadHash());
        assertFalse(service.getStagingState().conflicting().isEmpty());

        interact(tab::refresh);
        // The banner never left, so waiting for it alone is over at once - and the refresh
        // then rebuilt the panel under the click: the old button had no scene by the time
        // TestFX asked where it was. Let the refresh land first.
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the merge operation banner did not return",
                () -> lookup(".operation-banner").tryQuery().isPresent());
        clickOn(lookup(".operation-banner").lookup("Abort merge").queryAs(javafx.scene.Node.class));
        DialogPane accepted = RobotWait.untilDialogWithButton("OK", "Cancel");
        clickOn(accepted.lookupButton(ButtonType.OK));
        RobotWait.drainGitWork();

        assertFalse(service.getOperationState().inProgress());
        assertEquals(mainTip, service.getHeadHash());
        assertEquals(featureTip, ref("feature"));
        assertEquals("local\n", Files.readString(repository.resolve("shared.txt")));
        assertTrue(service.getStagingState().conflicting().isEmpty());
    }

    private String ref(String name) throws Exception {
        try (var git = org.eclipse.jgit.api.Git.open(repository.toFile())) {
            return git.getRepository().resolve("refs/heads/" + name).getName();
        }
    }
}
