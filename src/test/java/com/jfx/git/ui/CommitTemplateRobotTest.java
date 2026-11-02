package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code commit.template} reaching the composer, end to end: the repository's config
 * names a file, the WIP row is opened, and the summary and description fields show it.
 *
 * <p>The backend read has its own tests; what only the window can show is the composer's
 * side of it - that {@code #} lines are dropped, that a template arriving after the user
 * has started typing leaves their words alone, and that a commit makes the next empty
 * composer a new commit that gets the template again.
 *
 * <p>The template read is held at a gate until each test lets it through. The graph
 * selects its WIP row on its own as soon as it loads, so without the gate the template
 * could land before a test had the chance to type - and "typed text survives a late
 * template" would pass without the template ever having been late.
 */
class CommitTemplateRobotTest extends ApplicationTest {

    /** What the template file holds: a summary, a blank line, and instructions to drop. */
    private static final String TEMPLATE = """
            Summary from the template

            # Why was this change needed?
            Describe the change.
            # Lines starting with '#' are instructions, not message.
            """;
    private static final String TEMPLATE_SUMMARY = "Summary from the template";
    private static final String TEMPLATE_BODY = "Describe the change.";

    @TempDir
    Path tempDir;

    private Path repo;
    private GitService service;
    private RepoTab tab;

    /** Opened by a test when it wants the template read to answer. */
    private final CountDownLatch templateGate = new CountDownLatch(1);

    @Override
    public void start(Stage stage) throws Exception {
        repo = tempDir.resolve("repo");
        // Outside the work tree, so the template is not itself an untracked change.
        Path template = tempDir.resolve("commit-template.txt");
        Files.writeString(template, TEMPLATE, StandardCharsets.UTF_8);
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.setString("commit", null, "template", template.toAbsolutePath().toString().replace('\\', '/'));
            config.save();
        }
        service = new GitService() {
            @Override
            public String getCommitTemplate() {
                try {
                    if (!templateGate.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the template");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.getCommitTemplate();
            }
        };
        service.openRepository(repo.toFile());

        // Declared fixture setup: start() is the FX thread, and a JGit commit consults
        // git itself for the effective identity. See ThreadGuard.buildingFixture().
        try (var _ = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            Files.writeString(repo.resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("a.txt").isOk());
            assertTrue(service.commit("add a", false).isOk());
            // A staged change to commit, and an untracked one that keeps the WIP row -
            // and so a composer - on screen after that commit.
            Files.writeString(repo.resolve("a.txt"), "a changed\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("a.txt").isOk());
            Files.writeString(repo.resolve("untracked.txt"), "later\n", StandardCharsets.UTF_8);
        }

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // A test that failed before opening the gate would otherwise leave the lane
        // blocked, and the drain below waiting on it.
        templateGate.countDown();
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
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

    private TextField summary() {
        return lookup(".composer-summary").queryAs(TextField.class);
    }

    private TextArea description() {
        return lookup(".composer-message").queryAll().stream()
                .filter(n -> n instanceof TextArea)
                .map(n -> (TextArea) n)
                .findFirst().orElseThrow(() -> new AssertionError("no description area"));
    }

    /** Whether the live composer shows the template, instructions removed. Call on the FX thread. */
    private boolean composerShowsTemplate() {
        return TEMPLATE_SUMMARY.equals(summary().getText())
                && TEMPLATE_BODY.equals(description().getText());
    }

    private String headMessage() {
        try (Git git = Git.open(repo.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD")).getFullMessage();
        } catch (Exception midCommit) {
            return null;
        }
    }

    @Test
    void theNextEmptyComposerAfterACommitIsOfferedTheTemplateAgain() {
        templateGate.countDown();
        openStagingArea();
        RobotWait.untilOnFx("the composer never showed the template",
                this::composerShowsTemplate);

        Button commit = lookup(".button").queryAll().stream()
                .filter(n -> n instanceof Button b && b.getText() != null
                        && b.getText().startsWith("Commit"))
                .map(n -> (Button) n)
                .findFirst().orElseThrow(() -> new AssertionError("no Commit button"));
        interact(commit::fire);
        String committed = TEMPLATE_SUMMARY + "\n\n" + TEMPLATE_BODY;
        RobotWait.until("the commit with the template's message never landed",
                () -> committed.equals(headMessage()));
        RobotWait.drainGitWork();

        // The untracked file keeps the WIP row, so the refresh after the commit rebuilt a
        // composer - emptied by the commit, and a new commit's, so the template is back.
        openStagingArea();
        RobotWait.untilOnFx("the composer after the commit was not offered the template again",
                this::composerShowsTemplate);
    }
}
