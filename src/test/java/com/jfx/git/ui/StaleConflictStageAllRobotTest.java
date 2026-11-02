package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Unstaged section's Stage all, clicked after a merge started somewhere else - a
 * terminal - and before the panel heard about it.
 *
 * <p>The button stages what the panel lists as plain changes, and the panel's idea of
 * which paths are conflicted is from its last refresh. Believing nothing conflicted, it
 * ran {@code git add -A}, which marked the new conflict resolved with its markers in it.
 * The conflicts are now asked for again when the staging runs, and the panel reads the
 * repository again when they differ from what it showed.
 */
class StaleConflictStageAllRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        // Both branches rewrite a.txt from a shared ancestor: the merge will stop on it.
        commit("base", "base a\n");
        String main = service.getCurrentBranch();
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("feature", "feature a\n");
        assertTrue(service.checkoutBranch(main).isOk());
        commit("main", "main a\n");
        // The change on screen: a file the merge does not touch.
        Files.writeString(tempDir.resolve("notes.txt"), "a note\n", StandardCharsets.UTF_8);

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        tab.refresh();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(String message, String a) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), a, StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private Set<String> unmerged() {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.status().call().getConflicting();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> staged() {
        try (Git git = Git.open(tempDir.toFile())) {
            var status = git.status().call();
            var staged = new java.util.TreeSet<String>(status.getAdded());
            staged.addAll(status.getChanged());
            return staged;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The items of the list carrying {@code styleClass}. Call on FX. */
    private List<String> rows(String styleClass) {
        return FxHarness.lookupAll(tab.getContent(), styleClass).stream()
                .filter(node -> node instanceof ListView<?>)
                .<List<String>>map(node -> ((ListView<?>) node).getItems().stream().map(String::valueOf).toList())
                .findFirst().orElse(List.of());
    }

    /** The Unstaged section header's Stage all. Call on FX. */
    private Button unstagedStageAll() {
        return FxHarness.lookupAll(tab.getContent(), "row-action").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "Stage all".equals(button.getText()) && button.getScene() != null)
                .findFirst().orElse(null);
    }

    @Test
    void stageAllLeavesAConflictThePanelHasNotSeenYet() {
        RobotWait.untilOnFx("the change never produced a WIP row",
                () -> !tab.graphList().getItems().isEmpty() && tab.graphList().getItems().get(0).isWipNode());
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("notes.txt never reached the Unstaged list",
                () -> rows("staging-unstaged-list").equals(List.of("notes.txt"))
                        && unstagedStageAll() != null && !unstagedStageAll().isDisabled());
        RobotWait.drainGitWork();

        interact(() -> {
            // In one go on the FX thread, so no refresh can land between them: the merge
            // stops on a.txt, and Stage all is clicked on a panel that knows nothing of it.
            try (Git git = Git.open(tempDir.toFile())) {
                MergeResult merged = git.merge().include(git.getRepository().resolve("feature")).call();
                assertEquals(MergeResult.MergeStatus.CONFLICTING, merged.getMergeStatus());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            unstagedStageAll().fire();
        });

        RobotWait.until("Stage all never staged notes.txt", () -> staged().contains("notes.txt"));
        RobotWait.drainGitWork();
        assertEquals(Set.of("a.txt"), unmerged(),
                "the conflict the panel had not seen must stay unmerged, not be marked resolved");
        RobotWait.untilOnFx("the panel should read the repository again and list the conflict",
                () -> rows("staging-conflicted-list").equals(List.of("a.txt")));
    }
}
