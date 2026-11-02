package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.HistoryOps;
import com.jfx.git.core.RepositoryPreferences;
import com.jfx.git.model.Snapshot;
import com.jfx.git.ui.views.SnapshotsView;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safety nets in a real tab: a discard snapshotted first and undone from its toast,
 * the snapshot list read against the working tree and restored from, and the status bar
 * warning that merging the base branch would conflict.
 */
class SafetyNetRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        repo = tempDir.resolve("repo");
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.com");
                config.setString("core", null, "autocrlf", "false");
                config.save();
            }
            service = new GitService();
            service.openRepository(repo.toFile());
            write("f.txt", "one\n");
            assertTrue(service.stageFile("f.txt").isOk());
            assertTrue(service.commit("first", false).isOk());
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
        RepositoryPreferences.clear(repo.toFile());
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    // ------------------------------------------------------------------

    private void write(String path, String content) {
        try {
            Files.writeString(repo.resolve(path), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(String path) {
        try {
            Path file = repo.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Button toastButton(String label) {
        return lookup(".toast-action").queryAllAs(Button.class).stream()
                .filter(b -> label.equals(b.getText()))
                .findFirst().orElse(null);
    }

    private <T extends Node> T inView(String id, Class<T> type) {
        return type.cast(tab.currentView().content().lookup("#" + id));
    }

    // ------------------------------------------------------------------

    @Test
    void aDiscardIsSnapshottedFirstAndItsToastUndoesIt() {
        write("f.txt", "work in progress\n");

        interact(() -> SnapshotGuard.discard(OperationRunner.of(service, tab.getStatusBar(), () -> { }),
                "Discarding f.txt", "Before discarding f.txt", List.of("f.txt"), () -> service.discardChanges("f.txt"),
                null, OperationRunner.Refresh.NONE));

        RobotWait.until("the discard never happened", () -> "one\n".equals(read("f.txt")));
        RobotWait.untilOnFx("the toast never offered Undo", () -> toastButton("Undo") != null);
        assertNotNull(toastButton("Snapshots"), "the tab's snapshot list is offered too");
        List<Snapshot> kept = service.listSnapshots();
        assertEquals(1, kept.size());
        assertEquals("Before discarding f.txt", kept.get(0).reason());

        interact(() -> toastButton("Undo").fire());
        RobotWait.until("Undo never put the work back", () -> "work in progress\n".equals(read("f.txt")));
        assertEquals(List.of(), service.getStagedFiles(), "and it is not staged");
    }

    @Test
    void theSnapshotListComparesWithTheWorkingTreeAndRestores() {
        write("f.txt", "edited\n");
        write("notes.txt", "my notes\n");
        assertNotNull(service.snapshotWorkingTree("By hand"));
        assertTrue(service.discardAllChanges().isOk());

        interact(() -> tab.openSnapshots());
        assertInstanceOf(SnapshotsView.class, tab.currentView(), "it opens in the tab");
        SnapshotsView view = (SnapshotsView) tab.currentView();
        RobotWait.untilOnFx("the snapshot was never listed", () -> view.loadedSnapshots().size() == 1);
        RobotWait.untilOnFx("the snapshot was never compared",
                () -> view.shownChanges().size() == 2);
        List<String> paths = FxHarness.call(() -> view.shownChanges().stream().map(HistoryOps.FileChange::path).toList());
        assertEquals(List.of("f.txt", "notes.txt"), paths);
        assertEquals('D', view.shownChanges().get(1).status(), "notes.txt is only in the snapshot");

        @SuppressWarnings("unchecked")
        ListView<HistoryOps.FileChange> files = inView(SnapshotsView.FILES_ID, ListView.class);
        interact(() -> files.getSelectionModel().select(1));
        Button restoreFile = inView(SnapshotsView.RESTORE_FILE_ID, Button.class);
        assertFalse(restoreFile.isDisabled());
        interact(restoreFile::fire);
        RobotWait.until("the file was never restored", () -> "my notes\n".equals(read("notes.txt")));
        assertEquals("one\n", read("f.txt"), "only the file asked for");

        Button restoreAll = inView(SnapshotsView.RESTORE_ALL_ID, Button.class);
        RobotWait.untilOnFx("Restore all never came back", () -> !restoreAll.isDisabled());
        // Not interact(): the confirmation is modal.
        Platform.runLater(restoreAll::fire);
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        interact(() -> ((Button) confirm.lookupButton(ButtonType.OK)).fire());
        RobotWait.until("Restore all never wrote f.txt", () -> "edited\n".equals(read("f.txt")));
        RobotWait.untilOnFx("the restore was not snapshotted first", () -> view.loadedSnapshots().size() == 2);
    }

    @Test
    void theStatusBarWarnsWhenTheBaseBranchWouldConflict() throws Exception {
        // origin/main moves f.txt one way, the feature branch another.
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setCreateBranch(true).setName("feature").call();
            write("f.txt", "feature side\n");
            git.add().addFilepattern("f.txt").call();
            git.commit().setMessage("feature change").call();
            git.checkout().setName("main").call();
            write("f.txt", "main side\n");
            git.add().addFilepattern("f.txt").call();
            var mainTip = git.commit().setMessage("main change").call();
            RefUpdate remote = git.getRepository().updateRef("refs/remotes/origin/main");
            remote.setNewObjectId(mainTip);
            remote.update();
            git.checkout().setName("feature").call();
        }
        StatusBar bar = tab.getStatusBar();

        interact(() -> tab.checkConflicts());
        RobotWait.untilOnFx("the conflict was never reported",
                () -> "Conflicts with origin/main · 1 file".equals(bar.conflictWarningText()));
        String tip = FxHarness.call(() -> bar.conflictPill().getTooltip().getText());
        assertTrue(tip.contains("f.txt"), tip);

        clickOn(bar.conflictPill());
        RobotWait.untilOnFx("the pill's menu never opened",
                () -> lookup(".context-menu").tryQuery().isPresent());
        List<String> offered = lookup(".menu-item .label").queryAllAs(javafx.scene.control.Label.class).stream()
                .map(javafx.scene.control.Label::getText).toList();
        assertTrue(offered.stream().anyMatch(t -> t.startsWith("Merge origin/main into")), offered.toString());
        assertTrue(offered.contains("Rebase onto origin/main…"), offered.toString());
        assertTrue(offered.contains("Settings…"), offered.toString());
        type(javafx.scene.input.KeyCode.ESCAPE);

        RepositoryPreferences.setConflictWarning(repo.toFile(), false);
        interact(() -> tab.checkConflicts());
        RobotWait.untilOnFx("turning it off never hid the warning", () -> bar.conflictWarningText() == null);
        assertNull(bar.conflictWarningText());
    }
}
