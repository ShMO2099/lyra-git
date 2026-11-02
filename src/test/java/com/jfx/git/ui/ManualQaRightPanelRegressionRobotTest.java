package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end assertions for staging refresh and complete stash inspection. */
class ManualQaRightPanelRegressionRobotTest extends ApplicationTest {

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
                config.save();
            }
            service = new GitService();
            service.openRepository(tempDir.toFile());
            commit("README.md", "base readme\n", "base readme");
            commit("src/Main.java", "class Main { int value = 1; }\n", "base source");
        }

        tab = new RepoTab(new LyraApp(), "manual-ui-regression", service);
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

    private void commit(String path, String content, String message) throws Exception {
        Path file = tempDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(path).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private void write(String path, String content) throws Exception {
        Path file = tempDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void showWip() {
        interact(tab::refresh);
        RobotWait.drainGitWork();
        var state = service.getStagingState();
        interact(() -> {
            int wip = -1;
            for (int i = 0; i < tab.graphList().getItems().size(); i++) {
                if (tab.graphList().getItems().get(i).isWipNode()) {
                    wip = i;
                    break;
                }
            }
            assertTrue(wip >= 0, "dirty working tree must have a WIP row");
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(wip);
        });
        RobotWait.untilOnFx("staging panel did not reflect Git status",
                () -> stagingRows("staging-unstaged-list").equals(state.unstaged())
                        && stagingRows("staging-staged-list").equals(state.staged()));
    }

    private List<?> stagingRows(String style) {
        return FxHarness.lookupAll(tab.getContent(), style).stream()
                .filter(ListView.class::isInstance)
                .map(node -> ((ListView<?>) node).getItems())
                .findFirst().orElseThrow(() -> new AssertionError("missing ." + style));
    }

    private Set<String> stagingBadgeTexts() {
        javafx.scene.Node header = tab.getContent().lookup(".mode-header");
        assertNotNull(header, "the right panel header must be present");
        return FxHarness.lookupAll(header.getParent(), "count-badge").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .filter(text -> !text.isBlank())
                .collect(Collectors.toSet());
    }

    @Test
    void stageAllInTheEmptyStagedPlaceholderRefreshesCountsAndCommitAvailability() throws Exception {
        write("new.txt", "ready to stage\n");
        showWip();
        assertEquals(List.of("new.txt"), stagingRows("staging-unstaged-list"));
        assertTrue(stagingRows("staging-staged-list").isEmpty());

        Button placeholderStageAll = FxHarness.lookupAll(tab.getContent(), "staged-empty-action")
                .stream().filter(Button.class::isInstance).map(Button.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("empty Staged Stage all missing"));
        interact(placeholderStageAll::fire);

        RobotWait.untilOnFx("the staged lists, counts and commit button never followed the placeholder action",
                () -> stagingRows("staging-staged-list").contains("new.txt")
                        && stagingRows("staging-unstaged-list").isEmpty()
                        && stagingBadgeTexts().equals(Set.of("1 change", "0", "1"))
                        && FxHarness.lookupAll(tab.getContent(), "btn-primary").stream()
                            .filter(Button.class::isInstance).map(Button.class::cast)
                            .anyMatch(button -> !button.isDisabled()
                                    && button.getText().startsWith("Commit 1 file")));
        assertEquals(List.of("new.txt"), service.getStagingState().staged());
        assertEquals(List.of(), service.getStagingState().unstaged());
        byte[] expected = "ready to stage\n".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, Files.readAllBytes(tempDir.resolve("new.txt")),
                "staging keeps the working file bytes");
        assertArrayEquals(expected, service.getStagedBytes("new.txt"),
                "the independent index read contains the file bytes that were staged");
    }

    @Test
    void stashInspectorListsTheSavedUntrackedThirdParent() throws Exception {
        write("README.md", "staged readme change\n");
        assertTrue(service.stageFile("README.md").isOk());
        write("src/Main.java", "class Main { int value = 2; }\n");
        write("notes with spaces.txt", "untracked stash payload\n");
        showWip();

        interact(() -> FxHarness.lookupAll(tab.getContent(), "composer-tab-stash").stream()
                .filter(javafx.scene.control.ToggleButton.class::isInstance)
                .map(javafx.scene.control.ToggleButton.class::cast)
                .findFirst().orElseThrow().fire());
        TextField title = lookup(".stash-title").queryAs(TextField.class);
        interact(() -> title.setText("include all three stash parents"));
        clickOn("Stash changes");
        RobotWait.until("the named stash was not created or the work tree was not cleared",
                () -> service.getStashEntries().size() == 1 && !service.hasUncommittedChanges());

        String stashHash = service.getStashEntries().get(0).hash();
        byte[] expectedUntracked = "untracked stash payload\n".getBytes(StandardCharsets.UTF_8);
        String untrackedCommitHash;
        try (Git git = Git.open(tempDir.toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit stash = walk.parseCommit(ObjectId.fromString(stashHash));
            assertEquals(3, stash.getParentCount(), "-u stash has an untracked-files third parent");
            RevCommit untrackedParent = walk.parseCommit(stash.getParent(2).getId());
            untrackedCommitHash = untrackedParent.getName();
            assertEquals(0, untrackedParent.getParentCount(),
                    "the untracked snapshot is a root commit, so its diff is against the empty tree");
            try (TreeWalk tree = TreeWalk.forPath(git.getRepository(),
                    "notes with spaces.txt", untrackedParent.getTree())) {
                assertNotNull(tree, "the untracked file is stored in the third parent's tree");
                assertArrayEquals(expectedUntracked,
                        git.getRepository().open(tree.getObjectId(0)).getBytes(),
                        "the stash third parent retains the exact untracked bytes");
            }
        }
        String untrackedPatch = new String(service.getDiffBytesForFileInCommit(
                untrackedCommitHash, "notes with spaces.txt", false), StandardCharsets.UTF_8);
        assertTrue(untrackedPatch.contains("+untracked stash payload"),
                "the third-parent diff is an actual added-file patch: " + untrackedPatch);
        assertFalse(untrackedPatch.isBlank(), "the third-parent addition must have a patch");

        interact(tab::refresh);
        RobotWait.drainGitWork();
        interact(() -> {
            int stashRow = -1;
            for (int i = 0; i < tab.graphList().getItems().size(); i++) {
                if (tab.graphList().getItems().get(i).isStash()) {
                    stashRow = i;
                    break;
                }
            }
            assertTrue(stashRow >= 0, "the saved stash must appear in graph history");
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(stashRow);
        });

        RobotWait.untilOnFx("the stash inspector did not load all saved paths", () -> {
            ListView<?> files = FxHarness.lookupAll(tab.getContent(), "changed-files-list").stream()
                    .filter(ListView.class::isInstance).map(ListView.class::cast)
                    .findFirst().orElse(null);
            if (files == null) return false;
            Map<String, Character> statuses = new java.util.LinkedHashMap<>();
            files.getItems().forEach(item -> {
                var change = (com.jfx.git.core.GitBackend.FileChange) item;
                statuses.put(change.path(), change.status());
            });
            return statuses.equals(Map.of("README.md", 'M', "src/Main.java", 'M',
                    "notes with spaces.txt", 'A'));
        });

        FxHarness.run(() -> {
            ListView<?> files = FxHarness.lookupAll(tab.getContent(), "changed-files-list").stream()
                    .filter(ListView.class::isInstance).map(ListView.class::cast)
                    .findFirst().orElseThrow();
            int untrackedIndex = -1;
            for (int i = 0; i < files.getItems().size(); i++) {
                var change = (com.jfx.git.core.GitBackend.FileChange) files.getItems().get(i);
                if ("notes with spaces.txt".equals(change.path())) {
                    untrackedIndex = i;
                    break;
                }
            }
            assertTrue(untrackedIndex >= 0, "the third-parent path must be selectable in the stash UI");
            files.getSelectionModel().select(untrackedIndex);
        });
        RobotWait.untilOnFx("the untracked stash file did not open in the diff viewer",
                () -> "notes with spaces.txt".equals(tab.diffViewer().filePathProperty().get()));
        FxHarness.run(() -> {
            if (!tab.diffViewer().isFileView()) {
                ToggleButton fileView = FxHarness.lookupAll(tab.diffViewer(), "diff-segment").stream()
                        .filter(ToggleButton.class::isInstance)
                        .map(ToggleButton.class::cast)
                        .filter(button -> "File view".equals(button.getText()))
                        .findFirst().orElseThrow(() -> new AssertionError("File view toggle missing"));
                fileView.fire();
            }
        });
        RobotWait.untilOnFx("the stash File view did not show the third-parent payload", () ->
                FxHarness.lookupAll(tab.diffViewer(), "code-area").stream()
                        .filter(DiffTextArea.class::isInstance)
                        .map(DiffTextArea.class::cast)
                        .anyMatch(area -> area.getText().stripTrailing()
                                .equals("untracked stash payload")));
        assertFalse(Files.exists(tempDir.resolve("notes with spaces.txt")),
                "the untracked file was saved in the stash and removed from the working tree");
    }
}
