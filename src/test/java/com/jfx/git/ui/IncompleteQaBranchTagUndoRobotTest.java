package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Deleting an annotated tag from the sidebar and using its live Undo restores its tag object. */
class IncompleteQaBranchTagUndoRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Tag QA Robot");
            config.setString("user", null, "email", "tag-qa@example.invalid");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            Files.writeString(tempDir.resolve("tagged.txt"), "tagged content\n");
            assertTrue(service.stageFile("tagged.txt").isOk());
            assertTrue(service.commit("tagged commit", false).isOk());
        }
        tab = new RepoTab(new LyraApp(), "tag undo", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        tab.refresh();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void refreshAndWait() {
        interact(tab::refresh);
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void expandTags() {
        Node pane = FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .filter(n -> n instanceof TitledPane titled
                        && ("TAGS".equals(titled.getText())
                                || titled.getGraphic() instanceof javafx.scene.Parent graphic
                                        && graphic.getChildrenUnmodifiable().stream().anyMatch(child ->
                                                child instanceof javafx.scene.control.Label label
                                                        && "TAGS".equals(label.getText()))))
                .findFirst().orElseThrow(() -> new AssertionError("no TAGS section"));
        interact(() -> ((TitledPane) pane).setExpanded(true));
        RobotWait.untilOnFx("the tag row did not appear", () -> lookup("qa-annotated").tryQuery().isPresent());
    }

    private void openTagMenu() {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no qa-annotated sidebar row appeared", () -> {
            row[0] = lookup("qa-annotated").tryQuery().orElse(null);
            return row[0] != null;
        });
        interact(() -> Event.fireEvent(row[0], new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private String rawTagObject() {
        try (Git git = Git.open(tempDir.toFile())) {
            var ref = git.getRepository().exactRef("refs/tags/qa-annotated");
            return ref == null ? null : ref.getObjectId().name();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] rawTagObjectBytes() {
        try (Git git = Git.open(tempDir.toFile())) {
            var ref = git.getRepository().exactRef("refs/tags/qa-annotated");
            return ref == null ? null : git.getRepository().open(ref.getObjectId()).getBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void liveUndoRestoresTheExactAnnotatedTagObjectAndMetadata() throws Exception {
        assertTrue(service.createTag("qa-annotated", "release notes for QA", "HEAD").isOk());
        String originalObject = rawTagObject();
        byte[] originalObjectBytes = rawTagObjectBytes();
        String commitTarget;
        try (Git git = Git.open(tempDir.toFile())) {
            commitTarget = git.getRepository().resolve("qa-annotated^{commit}").name();
        }
        assertNotNull(originalObject);
        assertNotNull(originalObjectBytes);
        assertNotEquals(commitTarget, originalObject, "an annotated ref points to a tag object");
        refreshAndWait();
        expandTags();

        openTagMenu();
        clickOn(lookup(".context-menu").lookup("Delete tag").queryAs(Node.class));
        RobotWait.until("the tag was not deleted", () -> rawTagObject() == null);

        Button[] undo = new Button[1];
        RobotWait.untilOnFx("the delete toast did not offer Undo", () -> {
            undo[0] = lookup(".toast-action").queryAll().stream()
                    .filter(node -> node instanceof Button button && "Undo".equals(button.getText()))
                    .map(Button.class::cast).findFirst().orElse(null);
            return undo[0] != null;
        });
        interact(undo[0]::fire);
        RobotWait.until("Undo did not restore the tag ref", () -> rawTagObject() != null);
        assertEquals(originalObject, rawTagObject(),
                "Undo must retain the annotated tag object, not recreate a lightweight commit ref");
        assertArrayEquals(originalObjectBytes, rawTagObjectBytes(),
                "Undo must preserve every raw tag object byte, including tagger metadata and any signature");

        try (Git git = Git.open(tempDir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            var ref = git.getRepository().exactRef("refs/tags/qa-annotated");
            var tag = walk.parseTag(ref.getObjectId());
            assertEquals("release notes for QA", tag.getFullMessage().trim());
            assertEquals(commitTarget, tag.getObject().getId().name());
        }
        assertEquals("tagged content\n", Files.readString(tempDir.resolve("tagged.txt")));
        assertEquals("main", service.getCurrentBranch());
    }
}
