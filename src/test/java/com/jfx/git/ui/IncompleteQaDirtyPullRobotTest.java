package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises dirty Pull's real cancel and stash/retry/reapply flow on local repositories. */
class IncompleteQaDirtyPullRobotTest extends ApplicationTest {
    @TempDir Path temp;
    private Path localDir;
    private Path remoteDir;
    private Path peerDir;
    private GitService service;
    private RepoTab tab;
    private String initialHead;
    private String remoteHead;
    private boolean originalAutoStash;

    @Override
    public void start(Stage stage) throws Exception {
        localDir = temp.resolve("local");
        remoteDir = temp.resolve("remote.git");
        peerDir = temp.resolve("peer");
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git bare = Git.init().setDirectory(remoteDir.toFile()).setBare(true)
                    .setInitialBranch("main").call()) { }
            try (Git git = Git.init().setDirectory(localDir.toFile()).setInitialBranch("main").call()) {
                configureIdentity(git);
                Files.writeString(localDir.resolve("shared.txt"), "base line\n");
                git.add().addFilepattern("shared.txt").call();
                git.commit().setMessage("base").call();
            }
            service = new GitService();
            service.openRepository(localDir.toFile());
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new RepositoryCommands(service).push("origin", false, false, true).isOk());
            initialHead = service.getHeadHash();
            try (Git peer = Git.cloneRepository().setURI(remoteDir.toUri().toString())
                    .setDirectory(peerDir.toFile()).call()) {
                configureIdentity(peer);
                Files.writeString(peerDir.resolve("shared.txt"), "remote line\n");
                peer.add().addFilepattern("shared.txt").call();
                peer.commit().setMessage("remote edit").call();
                peer.push().call();
                remoteHead = peer.getRepository().resolve("HEAD").name();
            }
        }
        originalAutoStash = AppPreferences.autoStashOnCheckoutPull();
        AppPreferences.setAutoStashOnCheckoutPull(false);
        tab = new RepoTab(new LyraApp(), "dirty pull", service);
        stage.setScene(new Scene(new TabPane(tab), 1100, 680));
        stage.show();
        interact(tab::refresh);
    }

    @AfterEach
    void cleanup() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        AppPreferences.setAutoStashOnCheckoutPull(originalAutoStash);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    @Test
    void cancelPreservesDirtyFilesThenStashAndPullKeepsConflictingChangesInTheStash() throws Exception {
        Files.writeString(localDir.resolve("shared.txt"), "local uncommitted line\n");
        ButtonBase pull = tab.getToolbar().toolButton("Pull");

        clickOn(pull);
        DialogPane cancelQuestion = RobotWait.untilDialogWithButton("Stash and pull", "Cancel");
        clickOn(cancelQuestion.lookupButton(ButtonType.CANCEL));
        RobotWait.drainGitWork();
        assertEquals(initialHead, service.getHeadHash(), "cancel must not pull");
        assertEquals("local uncommitted line\n", Files.readString(localDir.resolve("shared.txt")));
        assertTrue(service.getStashEntries().isEmpty(), "cancel must not create a stash");

        clickOn(pull);
        DialogPane proceedQuestion = RobotWait.untilDialogWithButton("Stash and pull", "Cancel");
        clickOn(proceedQuestion.lookupButton(
                proceedQuestion.getButtonTypes().stream().filter(type -> type.getText().equals("Stash and pull"))
                        .findFirst().orElseThrow()));
        RobotWait.drainGitWork();

        assertEquals(remoteHead, service.getHeadHash(), "the clean remote commit should have arrived");
        assertFalse(service.getStagingState().conflicting().isEmpty(),
                "re-applying a conflicting local edit should report a conflict instead of losing it");
        assertFalse(service.getStashEntries().isEmpty(), "a failed stash re-apply must retain the stash entry");
    }

    private static void configureIdentity(Git git) throws Exception {
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Synthetic QA");
        config.setString("user", null, "email", "qa@example.test");
        config.setBoolean("commit", null, "gpgsign", false);
        config.setBoolean("core", null, "autocrlf", false);
        config.save();
    }
}
