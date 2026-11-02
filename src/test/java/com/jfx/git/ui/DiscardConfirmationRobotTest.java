package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryPreferences;

import javafx.application.Platform;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Ask before discarding a file's changes" governs the staging area's discards - a row's,
 * a selection's, a hunk's - and not "Discard all changes" on the graph's WIP row, which
 * asks whatever it says.
 *
 * <p>With the question off, a discard that reached the disk is itself the proof that no
 * question was asked: the confirmation is modal, and nothing here ever answers it, so a
 * discard waiting on one never happens.
 */
class DiscardConfirmationRobotTest extends ApplicationTest {

    private static final String A_BASE = numberedLines(20, -1, null);

    @TempDir
    Path tempDir;

    private Path repo;
    private GitService service;
    private RepoTab tab;

    /** {@code count} numbered lines, line {@code changedLine} replaced. */
    private static String numberedLines(int count, int changedLine, String replacement) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(i == changedLine ? replacement : "line" + i).append('\n');
        }
        return sb.toString();
    }

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
            write("a.txt", A_BASE);
            write("b.txt", "b\n");
            write("c.txt", "c\n");
            for (String file : List.of("a.txt", "b.txt", "c.txt")) {
                assertTrue(service.stageFile(file).isOk());
            }
            assertTrue(service.commit("first", false).isOk());
            write("a.txt", numberedLines(20, 2, "CHANGED2"));
            write("b.txt", "b edited\n");
            write("c.txt", "c edited\n");
        }
        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        AppPreferences.setConfirmDiscard(true);
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        RepositoryPreferences.clear(repo.toFile());
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    // ------------------------------------------------------------------
    // With the question off
    // ------------------------------------------------------------------

    @Test
    void withTheQuestionOffARowsDiscardGoesAheadAndOffersUndo() {
        AppPreferences.setConfirmDiscard(false);
        openStagingArea();

        MenuItem discard = rowMenuItem("b.txt", "Discard changes");
        Platform.runLater(discard::fire);

        RobotWait.until("b.txt was never discarded", () -> "b\n".equals(read("b.txt")));
        RobotWait.untilOnFx("the discard never offered Undo", () -> toastButton("Undo") != null);
        assertNull(FxHarness.call(this::showingDialog), "nothing was asked");
        assertEquals("c edited\n", read("c.txt"), "only the row's own file");

        interact(() -> toastButton("Undo").fire());
        RobotWait.until("Undo never put b.txt back", () -> "b edited\n".equals(read("b.txt")));
    }

    @Test
    void withTheQuestionOffDiscardingASelectionGoesAhead() {
        AppPreferences.setConfirmDiscard(false);
        openStagingArea();

        interact(() -> {
            ListView<String> list = unstagedListHolding("b.txt");
            list.getSelectionModel().clearSelection();
            list.getSelectionModel().select("b.txt");
            list.getSelectionModel().select("c.txt");
        });
        MenuItem discard = rowMenuItem("b.txt", "Discard selected (2 files)");
        Platform.runLater(discard::fire);

        RobotWait.until("the selection was never discarded",
                () -> "b\n".equals(read("b.txt")) && "c\n".equals(read("c.txt")));
        assertNull(FxHarness.call(this::showingDialog), "nothing was asked");
        assertEquals(numberedLines(20, 2, "CHANGED2"), read("a.txt"), "only the selected files");
    }

    @Test
    void withTheQuestionOffDiscardingAHunkGoesAhead() {
        AppPreferences.setConfirmDiscard(false);
        openStagingArea();
        openUnstagedDiff("a.txt");

        Platform.runLater(() -> discardHunkButton().fire());

        RobotWait.until("the hunk was never discarded", () -> A_BASE.equals(read("a.txt")));
        assertNull(FxHarness.call(this::showingDialog), "nothing was asked");
    }

    @Test
    void withTheQuestionOffDiscardAllOnTheWipRowStillAsks() {
        AppPreferences.setConfirmDiscard(false);
        // The refresh in here is what puts the WIP row in the graph.
        openStagingArea();
        Button[] wipDiscard = new Button[1];
        RobotWait.untilOnFx("the WIP row never offered Discard all changes",
                () -> (wipDiscard[0] = visibleWipDiscard()) != null);

        // Not interact(): the confirmation is modal.
        Platform.runLater(wipDiscard[0]::fire);
        press(RobotWait.untilDialogWithButton("Cancel"), "Cancel");

        RobotWait.drainGitWork();
        assertEquals("b edited\n", read("b.txt"), "Cancel kept the work");
        assertEquals("c edited\n", read("c.txt"));
    }

    // ------------------------------------------------------------------
    // With the question on, as it is by default
    // ------------------------------------------------------------------

    @Test
    void byDefaultARowsDiscardAsksAndCancelKeepsTheWork() {
        openStagingArea();

        MenuItem discard = rowMenuItem("b.txt", "Discard changes");
        Platform.runLater(discard::fire);
        DialogPane question = RobotWait.untilDialogWithButton("Cancel");
        assertEquals("Discard all changes to b.txt?", FxHarness.call(question::getHeaderText));
        assertTrue(FxHarness.call(question::getContentText).contains("Undo"),
                "the question names the way back: " + FxHarness.call(question::getContentText));
        press(question, "Cancel");

        RobotWait.drainGitWork();
        assertEquals("b edited\n", read("b.txt"), "Cancel kept the work");
        assertTrue(AppPreferences.confirmDiscard(), "and the question stays on");
    }

    /** "Don't ask again" is the same setting: ticked, the discard goes ahead and the question is off. */
    @Test
    void dontAskAgainTurnsTheSettingOffAsTheDiscardGoesAhead() {
        openStagingArea();

        MenuItem discard = rowMenuItem("b.txt", "Discard changes");
        Platform.runLater(discard::fire);
        DialogPane question = RobotWait.untilDialogWithButton("OK", "Cancel");
        interact(() -> ((CheckBox) question.lookup(".discard-dont-ask")).setSelected(true));
        press(question, "OK");

        RobotWait.until("b.txt was never discarded", () -> "b\n".equals(read("b.txt")));
        assertFalse(AppPreferences.confirmDiscard(), "the next discard does not ask");
    }

    // ------------------------------------------------------------------

    private void openStagingArea() {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        // A panel built before the refresh lands is replaced under the next lookup.
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("b.txt never appeared in the unstaged list", () -> unstagedRow("b.txt") != null);
    }

    /** {@code path}'s row in the unstaged list, if one is on screen. Call on the FX thread. */
    private ListCell<?> unstagedRow(String path) {
        for (Node node : FxHarness.lookupAll(tab.getContent(), "staging-unstaged-list")) {
            for (Node row : node.lookupAll(".list-cell")) {
                if (row instanceof ListCell<?> cell && path.equals(cell.getItem())
                        && cell.getScene() != null && cell.isVisible() && cell.getContextMenu() != null) {
                    return cell;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private ListView<String> unstagedListHolding(String path) {
        return (ListView<String>) unstagedRow(path).getListView();
    }

    /**
     * The item labelled {@code label} in {@code path}'s row menu, read with the menu open:
     * opening it is what settles which file it acts on and labels "Discard selected".
     */
    private MenuItem rowMenuItem(String path, String label) {
        MenuItem[] found = new MenuItem[1];
        List<String> offered = new ArrayList<>();
        interact(() -> {
            ListCell<?> row = unstagedRow(path);
            ContextMenu menu = row.getContextMenu();
            menu.show(row, Side.BOTTOM, 0, 0);
            for (MenuItem item : menu.getItems()) {
                offered.add(item.getText());
                if (label.equals(item.getText()) && item.isVisible()) found[0] = item;
            }
            menu.hide();
        });
        if (found[0] == null) {
            throw new AssertionError("no \"" + label + "\" on " + path + "'s row; it offers " + offered);
        }
        return found[0];
    }

    /** Opens {@code path}'s unstaged diff, which is what gives its hunks a Discard hunk. */
    private void openUnstagedDiff(String path) {
        interact(() -> {
            ListView<String> list = unstagedListHolding(path);
            list.getSelectionModel().clearSelection();
            list.getSelectionModel().select(path);
        });
        RobotWait.untilOnFx(path + "'s hunk never offered Discard hunk", () -> discardHunkButton() != null);
    }

    /** The first hunk's "Discard hunk", when it is showing. Call on the FX thread. */
    private Button discardHunkButton() {
        return FxHarness.lookupAll(tab.getContent(), "diff-revert-hunk").stream()
                .filter(n -> n instanceof Button b && "Discard hunk".equals(b.getText())
                        && b.isVisible() && b.getScene() != null)
                .map(Button.class::cast)
                .findFirst().orElse(null);
    }

    /** The WIP row's trash icon, when it is showing. Call on the FX thread. */
    private Button visibleWipDiscard() {
        return FxHarness.lookupAll(tab.getContent(), "wip-discard").stream()
                .filter(n -> n instanceof Button && n.isVisible() && n.getScene() != null)
                .map(Button.class::cast)
                .findFirst().orElse(null);
    }

    /** A showing dialog, if any. Call on the FX thread. */
    private DialogPane showingDialog() {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.isShowing() && window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane pane) {
                return pane;
            }
        }
        return null;
    }

    private Button toastButton(String label) {
        return lookup(".toast-action").queryAllAs(Button.class).stream()
                .filter(b -> label.equals(b.getText()))
                .findFirst().orElse(null);
    }

    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(bt.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no \"" + button + "\" button; the dialog offers "
                            + pane.getButtonTypes().stream().map(ButtonType::getText).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

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
}
