package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticking Amend on a commit that is already on its upstream says so.
 *
 * <p>Amending a pushed commit rewrites history someone else may have, and the push that
 * follows needs a force. The composer names the commit an amend would fold into; when
 * that commit is already published, the name comes with a warning and the
 * {@code amend-target-published} style. The remote here is a real bare repository the
 * branch was pushed to with tracking set, so the answer comes from the same refs and
 * configuration a real clone has - not from numbers fed to the panel.
 */
class AmendPublishedWarningRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        repo = tempDir.resolve("repo");
        Path remote = tempDir.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).call().close();
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(repo.toFile());

        // Declared fixture setup: start() is the FX thread, and a JGit commit consults
        // git itself for the effective identity. See ThreadGuard.buildingFixture().
        try (var _ = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            commit("a.txt", "a\n", "the published commit");
            try (Git git = Git.open(repo.toFile())) {
                String branch = git.getRepository().getBranch();
                git.remoteAdd().setName("origin").setUri(new URIish(remote.toUri().toURL())).call();
                git.push().setRemote("origin").add(branch).call();
                // What `git push -u` records: the branch now tracks what it was pushed to.
                var config = git.getRepository().getConfig();
                config.setString("branch", branch, "remote", "origin");
                config.setString("branch", branch, "merge", "refs/heads/" + branch);
                config.save();
            }
            // An uncommitted change, so the graph has a WIP row and the panel a composer.
            Files.writeString(repo.resolve("wip.txt"), "wip\n", StandardCharsets.UTF_8);
        }

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(repo.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    /** Selects the WIP row - the graph's first - and waits for its composer. */
    private void openStagingArea() {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("the staging area's composer never appeared",
                () -> tab.graphList().getItems().get(0).isWipNode()
                        && lookup(".composer-summary").tryQuery().isPresent());
    }

    private CheckBox amendBox() {
        return lookup(".check-box").queryAll().stream()
                .filter(n -> n instanceof CheckBox cb && "Amend".equals(cb.getText()))
                .map(n -> (CheckBox) n)
                .findFirst().orElseThrow(() -> new AssertionError("no Amend checkbox"));
    }

    private Label amendTarget() {
        return lookup(".amend-target").queryAs(Label.class);
    }

    @Test
    void amendingACommitAlreadyOnItsUpstreamWarnsThatItIsPublished() {
        openStagingArea();
        // Waited for unticked first: the label names HEAD once the worker's read of it
        // lands, and only then does ticking have anything to warn about.
        RobotWait.untilOnFx("the composer never named the commit an amend would change",
                () -> amendTarget().getText().contains("the published commit"));

        CheckBox amend = amendBox();
        interact(amend::fire);

        RobotWait.untilOnFx("ticking Amend on a pushed commit never warned", () ->
                amendTarget().getStyleClass().contains("amend-target-published"));
        String text = amendTarget().getText();
        assertTrue(text.contains("Already on"), "the warning says it is published: " + text);
        assertTrue(text.contains("the published commit"),
                "and still names the commit it would rewrite: " + text);

        // Unticking takes the warning away again: it is about the amend, not the commit.
        interact(amend::fire);
        RobotWait.untilOnFx("unticking Amend left the published warning up", () ->
                !amendTarget().getStyleClass().contains("amend-target-published")
                        && !amendTarget().getText().contains("Already on"));
    }

    @Test
    void amendingALocalCommitAheadOfTheUpstreamDoesNotWarn() throws Exception {
        commit("b.txt", "b\n", "a local commit");
        openStagingArea();
        RobotWait.untilOnFx("the composer never named the local commit",
                () -> amendTarget().getText().contains("a local commit"));

        CheckBox amend = amendBox();
        interact(amend::fire);
        // Ticking fills the empty composer from HEAD; that and the label are one pass.
        RobotWait.untilOnFx("ticking Amend never took effect", () -> amend.isSelected()
                && "a local commit".equals(lookup(".composer-summary")
                        .queryAs(javafx.scene.control.TextField.class).getText()));
        RobotWait.drainGitWork();

        interact(() -> {
            assertFalse(amendTarget().getStyleClass().contains("amend-target-published"),
                    "a commit only this clone has is not published");
            assertFalse(amendTarget().getText().contains("Already on"),
                    "and must not be called published: " + amendTarget().getText());
            assertTrue(amendTarget().getText().contains("a local commit"),
                    "the label still names the commit: " + amendTarget().getText());
        });
    }
}
