package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.model.StashOptions;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The merge dialog, a branch made from a stash, and a stash of only the selected files -
 * each driven through the window, each checked against the real repository afterwards.
 *
 * <p>The repository has "feature" and the default branch diverged from one base, touching
 * different files, so a squash has something to stage and nothing to conflict over.
 *
 * <h2>Reaching context menus under Monocle Headless</h2>
 * As in {@code MergeConflictRobotTest}, a robot right-click does not become a
 * {@code ContextMenuEvent} under Monocle Headless; the event is fired on the row itself.
 */
class MergeAndStashOptionsRobotTest extends ApplicationTest {

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
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());

        // Declared fixture setup: start() is the FX thread, and a JGit commit consults
        // git itself for the effective identity. See ThreadGuard.buildingFixture().
        try (var _ = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            commit("a.txt", "base\n", "base commit");
            String main = service.getCurrentBranch();
            assertTrue(service.createBranch("feature", null, true).isOk());
            commit("c.txt", "from feature\n", "feature: add c");
            assertTrue(service.checkoutBranch(main).isOk());
            commit("d.txt", "from main\n", "main: add d");
        }

        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        interact(() -> tab.refresh());
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

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private String contentOf(String file) {
        try {
            return Files.readString(tempDir.resolve(file));
        } catch (java.io.IOException notThere) {
            return null;
        }
    }

    /** Fires a context-menu request on the row showing {@code label}, once it is on screen. */
    private void openMenuOn(String label) {
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no row labelled \"" + label + "\" appeared", () -> {
            row[0] = lookup(label).tryQuery().orElse(null);
            return row[0] != null;
        });
        interact(() -> Event.fireEvent(row[0], new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
    }

    /** Clicks {@code item} in the context menu that is showing, once it is laid out. */
    private void chooseMenuItem(String item) {
        Node[] found = new Node[1];
        RobotWait.untilOnFx("the menu never offered \"" + item + "\"", () -> {
            java.util.Optional<Node> menu = lookup(".context-menu").tryQuery();
            found[0] = menu.isEmpty() ? null : from(menu.get()).lookup(item).<Node>tryQuery().orElse(null);
            return found[0] != null;
        });
        clickOn(found[0]);
    }

    private static <T extends Node> T inDialog(DialogPane pane, String styleClass, Class<T> type) {
        return type.cast(FxHarness.lookupAll(pane, styleClass).stream()
                .filter(type::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ." + styleClass + " in the dialog")));
    }

    private static Button buttonLabelled(DialogPane pane, String text) {
        return pane.getButtonTypes().stream()
                .map(pane::lookupButton)
                .filter(b -> b instanceof Button button && text.equals(button.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + text + "\" button"));
    }

    /**
     * Selects the WIP row once the reload that puts it on top has landed - selecting index
     * 0 before then selects the newest commit, and the reload keeps that selection.
     */
    private void selectWipRow() {
        interact(() -> tab.refresh());
        RobotWait.untilOnFx("the reload never put a WIP row on top",
                () -> !tab.graphList().getItems().isEmpty()
                        && tab.graphList().getItems().get(0).isWipNode());
        interact(() -> {
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(0);
        });
        var expected = service.getStagingState();
        RobotWait.untilOnFx("the staging area never showed " + expected,
                () -> expected.unstaged().equals(rowsOf("staging-unstaged-list"))
                        && expected.staged().equals(rowsOf("staging-staged-list")));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @SuppressWarnings("unchecked")
    private ListView<String> stagingList(String styleClass) {
        return (ListView<String>) FxHarness.lookupAll(tab.getContent(), styleClass).stream()
                .filter(n -> n instanceof ListView<?>)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ." + styleClass + " yet"));
    }

    /** The rows of one staging list, or null before it exists. Call on the FX thread. */
    private List<String> rowsOf(String styleClass) {
        return FxHarness.lookupAll(tab.getContent(), styleClass).isEmpty()
                ? null
                : List.copyOf(stagingList(styleClass).getItems());
    }

    /** The commit composer's summary - not the stash title, which shares its look. */
    private TextInputControl commitSummary() {
        return FxHarness.lookupAll(tab.getContent(), "composer-summary").stream()
                .filter(n -> !n.getStyleClass().contains("stash-title"))
                .map(TextInputControl.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no commit summary field"));
    }

    // ------------------------------------------------------------------
    // Merge modes
    // ------------------------------------------------------------------

    /**
     * Squash through the dialog: the branch's change is staged, HEAD does not move, and
     * the commit box opens with the message typed in the dialog.
     */
    @Test
    void aSquashFromTheSidebarStagesTheBranchAndFillsTheCommitBox() throws Exception {
        String main = service.getCurrentBranch();
        String before = service.getHeadHash();

        openMenuOn("feature");
        chooseMenuItem("Merge into " + main + " with options…");
        DialogPane dialog = RobotWait.untilDialogWithButton("Merge");
        clickOn(inDialog(dialog, "merge-mode-squash", RadioButton.class));
        TextInputControl message = inDialog(dialog, "merge-message", TextInputControl.class);
        interact(() -> message.setText("Bring in the feature as one commit"));
        clickOn(buttonLabelled(dialog, "Squash"));

        RobotWait.until("the squash never staged the branch's change",
                () -> service.getStagedFiles().contains("c.txt"));
        assertEquals(before, service.getHeadHash(), "a squash does not move HEAD");
        assertFalse(Files.exists(tempDir.resolve(".git/MERGE_HEAD")), "and records no merge");

        selectWipRow();
        RobotWait.untilOnFx("the commit box was never filled from SQUASH_MSG",
                () -> "Bring in the feature as one commit".equals(commitSummary().getText()));
    }

    /** The one-click item next to it still merges straight away, with no dialog. */
    @Test
    void theOneClickMergeIsStillOneClick() {
        String main = service.getCurrentBranch();

        openMenuOn("feature");
        chooseMenuItem("Merge into " + main);

        RobotWait.until("the merge never happened",
                () -> Files.exists(tempDir.resolve("c.txt")) && !service.hasUncommittedChanges());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(lookup(".dialog-pane").tryQuery().isEmpty(), "no dialog for the one-click merge");
    }

    // ------------------------------------------------------------------
    // Stash options
    // ------------------------------------------------------------------

    /** "Create branch from stash…" makes the branch at the stash's base and drops the stash. */
    @Test
    void aBranchIsMadeFromAStashInTheSidebar() throws Exception {
        String base = service.getHeadHash();
        Files.writeString(tempDir.resolve("a.txt"), "stashed work\n");
        assertTrue(service.stash(StashOptions.everything("the work")).isOk());
        interact(() -> tab.refresh());

        // The STASHES section starts collapsed. Its title is a Label in the pane's graphic.
        interact(() -> FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .filter(n -> n instanceof javafx.scene.control.TitledPane tp
                        && tp.getGraphic() instanceof javafx.scene.Parent graphic
                        && graphic.getChildrenUnmodifiable().stream()
                                .anyMatch(c -> c instanceof javafx.scene.control.Label label
                                        && "STASHES".equals(label.getText())))
                .map(javafx.scene.control.TitledPane.class::cast)
                .forEach(tp -> tp.setExpanded(true)));
        var entry = service.getStashEntries().get(0);
        openMenuOn(entry.selector() + "  " + entry.shortDescription());
        chooseMenuItem("Create branch from stash…");
        DialogPane dialog = RobotWait.untilDialogWithButton("Create branch");
        TextField name = inDialog(dialog, "stash-branch-name", TextField.class);
        Button create = buttonLabelled(dialog, "Create branch");
        interact(() -> {
            name.setText("two words");
            assertTrue(create.isDisabled(), "git refuses a branch name with a space in it");
            assertTrue(dialog.lookupAll(".jfx-field-error").stream().anyMatch(Node::isVisible),
                    "and the field says so as it is typed");
        });
        interact(() -> name.setText("rescued"));
        clickOn(buttonLabelled(dialog, "Create branch"));

        RobotWait.until("the branch was never made from the stash",
                () -> "rescued".equals(service.getCurrentBranch()) && service.getStashEntries().isEmpty());
        assertEquals(base, service.getHeadHash());
        assertEquals("stashed work\n", contentOf("a.txt"));
    }

    /** "Selected only" in the stash composer stashes the selected file and leaves the rest. */
    @Test
    void theStashComposerCanStashOnlyTheSelectedFile() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "stash me\n");
        Files.writeString(tempDir.resolve("d.txt"), "leave me\n");
        selectWipRow();

        interact(() -> lookup(".composer-tab-stash").queryAs(ToggleButton.class).fire());
        ListView<String> unstaged = stagingList("staging-unstaged-list");
        interact(() -> {
            unstaged.getSelectionModel().clearSelection();
            unstaged.getSelectionModel().select("a.txt");
        });
        CheckBox onlySelected = lookup(".stash-only-selected").queryAs(CheckBox.class);
        interact(() -> onlySelected.setSelected(true));
        TextField title = lookup(".stash-title").queryAs(TextField.class);
        interact(() -> title.setText("just a"));
        clickOn("Stash changes");

        RobotWait.until("the partial stash never landed", () -> service.getStashEntries().size() == 1
                && "base\n".equals(contentOf("a.txt")));
        assertEquals("leave me\n", contentOf("d.txt"), "the unselected file keeps its change");
        assertEquals("just a", service.getStashEntries().get(0).shortDescription());
    }
}
