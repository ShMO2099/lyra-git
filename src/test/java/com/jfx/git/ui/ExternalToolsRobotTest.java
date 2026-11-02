package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.ExternalTools;
import com.jfx.git.core.FakeExternalTool;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External tools from where the user reaches them: the diff header's button, and a
 * conflicted file's menu.
 *
 * <p>{@code ExternalToolsTest} proves what a tool is started with. This is the wiring
 * above it - that the button appears only once a tool is chosen, that it hands over the
 * comparison actually on screen, and that a conflict resolved outside comes back in and
 * is staged only when the user says so.
 */
class ExternalToolsRobotTest extends ApplicationTest {

    @TempDir
    Path root;

    private Path workTree;
    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        workTree = Files.createDirectory(root.resolve("work"));
        try (Git git = Git.init().setDirectory(workTree.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(workTree.toFile());
        commit("a.txt", "one\n", "add a");

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        AppPreferences.setDiffToolMode(ExternalTools.Mode.NONE);
        AppPreferences.setDiffToolCommand("");
        AppPreferences.setMergeToolMode(ExternalTools.Mode.NONE);
        AppPreferences.setMergeToolCommand("");
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void commit(String path, String content, String message) throws Exception {
        Files.writeString(workTree.resolve(path), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(path).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private static String fakeTool() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return "\"" + java + "\" -cp \"" + System.getProperty("java.class.path") + "\" "
                + FakeExternalTool.class.getName();
    }

    /** Refreshes, then selects {@code path} in the unstaged list and waits for its diff. */
    private DiffViewer openUnstaged(String path) {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx(path + " never appeared in the file list",
                () -> lookup(path).tryQuery().isPresent());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        Node list = lookup(".staging-unstaged-list").query();
        clickOn(from(list).lookup(path).match(n -> n.getScene() != null).<Node>query());
        DiffViewer viewer = lookup(".diff-viewer").query();
        RobotWait.untilOnFx(path + " never loaded into the viewer",
                () -> path.equals(viewer.filePathProperty().get()));
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        return viewer;
    }

    private Button externalDiffButton(DiffViewer viewer) {
        return (Button) FxHarness.lookupAll(viewer, "button").stream()
                .filter(n -> n instanceof Button b
                        && DiffViewer.NAME_EXTERNAL_DIFF.equals(b.getAccessibleText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the diff header has no external-diff button"));
    }

    private static List<String> waitForLines(Path file) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (!Files.exists(file)) {
            assertTrue(System.nanoTime() < deadline, "the tool never wrote " + file);
            Thread.sleep(50);
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------

    /** Most people never set a tool, and a dead icon in every diff header is noise. */
    @Test
    void theHeaderHasNoExternalDiffButtonUntilAToolIsChosen() throws Exception {
        Files.writeString(workTree.resolve("a.txt"), "one\ntwo\n");
        DiffViewer viewer = openUnstaged("a.txt");

        interact(() -> assertFalse(externalDiffButton(viewer).isVisible()));
    }

    @Test
    void theButtonOpensTheComparisonOnScreenInTheTool() throws Exception {
        Path out = root.resolve("recorded.txt");
        AppPreferences.setDiffToolMode(ExternalTools.Mode.CUSTOM);
        AppPreferences.setDiffToolCommand(fakeTool() + " record \"" + out + "\" $LOCAL $REMOTE");
        Files.writeString(workTree.resolve("a.txt"), "one\ntwo\n");
        DiffViewer viewer = openUnstaged("a.txt");

        Button button = externalDiffButton(viewer);
        interact(() -> {
            assertTrue(button.isVisible(), "a tool is chosen and an unstaged change is on screen");
            button.fire();
        });

        List<String> received = waitForLines(out);
        assertEquals(2, received.size(), received.toString());
        assertEquals("one\n", Files.readString(Path.of(received.get(0))), "what is staged");
        assertEquals(workTree.resolve("a.txt").toFile().getAbsoluteFile(),
                new File(received.get(1)).getAbsoluteFile(),
                "the unstaged side is the real file, so the tool can edit it");
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    private void makeConflict() throws Exception {
        assertTrue(service.createBranch("other", null, true).isOk());
        commit("a.txt", "theirs\n", "their change");
        assertTrue(service.checkoutBranch("main").isOk());
        commit("a.txt", "ours\n", "our change");
        assertTrue(new RepositoryCommands(service).merge("other", false).isConflict());
    }

    /** The menu of the Conflicted files row for {@code path}. */
    private ContextMenu rowMenu(String path) {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        RobotWait.drainGitWork();
        ContextMenu[] menu = new ContextMenu[1];
        RobotWait.untilOnFx("no conflicted row for " + path, () -> {
            Node list = lookup(".staging-conflicted-list").tryQuery().orElse(null);
            if (list == null) return false;
            for (Node node : list.lookupAll(".list-cell")) {
                ListCell<?> cell = (ListCell<?>) node;
                if (path.equals(cell.getItem()) && cell.getContextMenu() != null) {
                    menu[0] = cell.getContextMenu();
                    return true;
                }
            }
            return false;
        });
        return menu[0];
    }

    private static List<String> labels(ContextMenu menu) {
        return menu.getItems().stream().map(MenuItem::getText).filter(t -> t != null).toList();
    }

    @Test
    void aConflictedFileOffersTheExternalToolOnlyWhenOneIsChosen() throws Exception {
        makeConflict();
        ContextMenu without = rowMenu("a.txt");
        interact(() -> assertFalse(labels(without).contains("Resolve in external merge tool"),
                labels(without).toString()));

        AppPreferences.setMergeToolMode(ExternalTools.Mode.CUSTOM);
        AppPreferences.setMergeToolCommand(fakeTool() + " leave");
        ContextMenu with = rowMenu("a.txt");
        interact(() -> assertTrue(labels(with).contains("Resolve in external merge tool"),
                labels(with).toString()));
    }

    @Test
    void aConflictResolvedInTheToolIsStagedOnceTheUserSaysSo() throws Exception {
        makeConflict();
        AppPreferences.setMergeToolMode(ExternalTools.Mode.CUSTOM);
        AppPreferences.setMergeToolCommand(fakeTool() + " takeLocal $LOCAL $MERGED");

        ContextMenu menu = rowMenu("a.txt");
        interact(() -> menu.getItems().stream()
                .filter(i -> "Resolve in external merge tool".equals(i.getText())).findFirst().orElseThrow()
                .fire());

        DialogPane confirm = RobotWait.untilDialogWithButton("Mark as resolved");
        assertEquals("ours\n", Files.readString(workTree.resolve("a.txt")), "the tool wrote its result");
        assertEquals(List.of("a.txt"), service.getConflictingFiles(),
                "nothing is staged until the question is answered");

        interact(() -> {
            ButtonType mark = confirm.getButtonTypes().stream()
                    .filter(bt -> "Mark as resolved".equals(bt.getText())).findFirst().orElseThrow();
            ((Button) confirm.lookupButton(mark)).fire();
        });
        RobotWait.until("the file was never marked resolved", () -> service.getConflictingFiles().isEmpty());
    }

    @Test
    void aToolThatLeavesTheMarkersAsksNothingAndStagesNothing() throws Exception {
        makeConflict();
        AppPreferences.setMergeToolMode(ExternalTools.Mode.CUSTOM);
        AppPreferences.setMergeToolCommand(fakeTool() + " leave");

        ContextMenu menu = rowMenu("a.txt");
        interact(() -> menu.getItems().stream()
                .filter(i -> "Resolve in external merge tool".equals(i.getText())).findFirst().orElseThrow()
                .fire());

        RobotWait.untilOnFx("the status bar never said the file is still conflicted",
                () -> lookup(".status-message").tryQuery()
                        .map(n -> String.valueOf(((javafx.scene.control.Labeled) n).getText()))
                        .orElse("").contains("still has conflict markers"));
        assertEquals(List.of("a.txt"), service.getConflictingFiles());
        interact(() -> assertTrue(Window.getWindows().stream()
                        .noneMatch(w -> w.isShowing() && w.getScene() != null
                                && w.getScene().getRoot() instanceof DialogPane),
                "there is nothing to confirm when nothing was resolved"));
    }
}
