package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitFlow;
import com.jfx.git.core.GitService;
import com.jfx.git.ui.dialogs.GitFlowSetupDialog;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeView;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git Flow from the sidebar: setting a repository up, starting a feature, and finishing
 * it, each through the menu a user would reach for.
 *
 * <p>{@code GitFlowTest} proves the model on every engine. This is that the sidebar
 * shows the section only once there is something to show, lists the branches that are
 * under way, and says what a finish will do before it does it.
 */
class GitFlowSidebarRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
        commit("a.txt", "a\n", "first");

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
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private void refresh() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
    }

    private TitledPane section(String title) {
        return FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .map(n -> (TitledPane) n)
                .filter(pane -> pane.getGraphic() instanceof javafx.scene.Parent graphic
                        && graphic.getChildrenUnmodifiable().stream().anyMatch(c ->
                        c instanceof javafx.scene.control.Label l && title.equals(l.getText())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + title + " section"));
    }

    @SuppressWarnings("unchecked")
    private ListView<String> flowList() {
        return (ListView<String>) section("GIT FLOW").getContent();
    }

    private static MenuItem item(ContextMenu menu, String text) {
        return menu.getItems().stream().filter(i -> text.equals(i.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + text + "\" among "
                        + menu.getItems().stream().map(MenuItem::getText).toList()));
    }

    /**
     * Presses the button that reads {@code button}. A question in the dialog shell keeps
     * OK as its confirming button's type and shows the verb on it ("Finish feature"), so
     * the label on screen is what is matched.
     */
    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(((Button) pane.lookupButton(bt)).getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no \"" + button + "\" button; has "
                            + pane.getButtonTypes().stream()
                                    .map(bt -> ((Button) pane.lookupButton(bt)).getText()).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    /** The rows of a question's framed list: what finishing will do, one step a row. */
    private static List<String> affectedRows(DialogPane pane) {
        return pane.lookupAll(".jfx-affected-row").stream()
                .map(n -> ((javafx.scene.control.Label) n).getText()).toList();
    }

    /** Sets the repository up through the dialog, taking its defaults. */
    private void setUpThroughTheDialog() {
        refresh();
        ContextMenu localMenu = ((TreeView<?>) section("LOCAL").getContent().lookup(".branch-tree"))
                .getContextMenu();
        Platform.runLater(() -> item(localMenu, "Git Flow…").fire());
        DialogPane dialog = RobotWait.untilDialogWithButton("Set up");
        interact(() -> {
            assertEquals("main", ((javafx.scene.control.ChoiceBox<?>) dialog.lookup(
                    "#" + GitFlowSetupDialog.PRODUCTION_ID)).getValue());
            TextField develop = (TextField) dialog.lookup("#" + GitFlowSetupDialog.DEVELOP_ID);
            assertEquals("develop", develop.getText());

            // What setting up would refuse is said under the field as it is typed.
            Button setUp = (Button) dialog.lookupButton(dialog.getButtonTypes().stream()
                    .filter(bt -> "Set up".equals(bt.getText())).findFirst().orElseThrow());
            develop.setText("main");
            assertTrue(setUp.isDisabled(), "develop cannot be the production branch");
            assertTrue(dialog.lookupAll(".jfx-field-error").stream().anyMatch(Node::isVisible));
            develop.setText("develop");
            assertFalse(setUp.isDisabled());
            assertTrue(dialog.lookupAll(".jfx-field-error").stream().noneMatch(Node::isVisible));
        });
        press(dialog, "Set up");
        RobotWait.until("Git Flow was never set up", () -> new GitFlow(service).config() != null);
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the GIT FLOW section never appeared", () -> section("GIT FLOW").isVisible());
    }

    // ------------------------------------------------------------------

    @Test
    void theSectionIsAbsentUntilTheRepositoryIsSetUpAndThenAppears() {
        refresh();
        interact(() -> {
            assertFalse(section("GIT FLOW").isVisible(), "most repositories do not use Git Flow");
            assertFalse(section("GIT FLOW").isManaged(), "and an absent section takes no room");
        });

        setUpThroughTheDialog();

        assertEquals(new GitFlow.Config("main", "develop", "feature/", "release/", "hotfix/", ""),
                new GitFlow(service).config());
        assertTrue(service.getLocalBranches().stream().anyMatch(b -> b.shortName().equals("develop")),
                "develop is created by setting up");
        interact(() -> assertEquals(List.of(), List.copyOf(flowList().getItems()), "nothing is under way yet"));
    }

    @Test
    void aFeatureIsStartedListedAndFinishedFromTheSection() throws Exception {
        setUpThroughTheDialog();

        ContextMenu sectionMenu = flowList().getContextMenu();
        Platform.runLater(() -> item(sectionMenu, "Start feature…").fire());
        DialogPane name = RobotWait.untilDialogWithButton("OK");
        interact(() -> ((TextField) name.lookup(".text-field")).setText("login"));
        press(name, "Start feature");

        RobotWait.until("the feature was never started", () -> "feature/login".equals(service.getCurrentBranch()));
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the feature never appeared in the section",
                () -> List.of("feature/login").equals(List.copyOf(flowList().getItems())));

        commit("login.txt", "login\n", "add login");
        refresh();

        // The row's own menu, once the list has a cell for it.
        ContextMenu[] rowMenu = new ContextMenu[1];
        RobotWait.untilOnFx("the feature's row never got a menu", () -> {
            for (Node node : flowList().lookupAll(".list-cell")) {
                ListCell<?> cell = (ListCell<?>) node;
                if ("feature/login".equals(cell.getItem()) && cell.getContextMenu() != null) {
                    rowMenu[0] = cell.getContextMenu();
                    return true;
                }
            }
            return false;
        });
        Platform.runLater(() -> item(rowMenu[0], "Finish feature…").fire());
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        interact(() -> {
            List<String> steps = affectedRows(confirm);
            assertTrue(steps.contains("1. Merge feature/login into develop"), steps.toString());
            assertTrue(steps.contains("2. Delete feature/login"), steps.toString());
        });
        press(confirm, "Finish feature");

        RobotWait.until("the feature was never finished", () -> service.getLocalBranches().stream()
                .noneMatch(b -> b.shortName().equals("feature/login")));
        RobotWait.drainGitWork();
        assertEquals("develop", service.getCurrentBranch());
        assertTrue(Files.exists(tempDir.resolve("login.txt")), "the feature's work is on develop");
        RobotWait.untilOnFx("the finished feature is still listed", () -> flowList().getItems().isEmpty());
    }

    /**
     * Starting checks the new branch out, so it is a checkout like any other: an edit in the
     * way gets the auto-stash offer, not git's refusal alone, and comes along onto the
     * feature.
     */
    @Test
    void startingAFeatureOverLocalChangesOffersAutoStash() throws Exception {
        boolean alwaysBefore = com.jfx.git.core.AppPreferences.autoStashOnCheckoutPull();
        com.jfx.git.core.AppPreferences.setAutoStashOnCheckoutPull(false);
        try {
            commit("notes.txt", "one\ntwo\nthree\n", "notes");
            assertTrue(service.createBranch("develop", "main", true).isOk());
            commit("notes.txt", "one\ntwo\nthree\nfour\n", "on develop");
            assertTrue(service.checkoutBranch("main").isOk());
            setUpThroughTheDialog();
            Files.writeString(tempDir.resolve("notes.txt"), "zero\none\ntwo\nthree\n", StandardCharsets.UTF_8);

            ContextMenu sectionMenu = flowList().getContextMenu();
            Platform.runLater(() -> item(sectionMenu, "Start feature…").fire());
            DialogPane name = RobotWait.untilDialogWithButton("OK");
            interact(() -> ((TextField) name.lookup(".text-field")).setText("login"));
            press(name, "Start feature");
            press(RobotWait.untilDialogWithButton("Stash and check out", "Cancel"), "Stash and check out");

            RobotWait.until("the feature was never started", () -> "feature/login".equals(service.getCurrentBranch()));
            RobotWait.drainGitWork();
            assertEquals("zero\none\ntwo\nthree\nfour\n",
                    Files.readString(tempDir.resolve("notes.txt"), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                    "the edit is on top of develop's version");
            assertEquals(0, service.getStashEntries().size(), "the stash it made is gone");
        } finally {
            com.jfx.git.core.AppPreferences.setAutoStashOnCheckoutPull(alwaysBefore);
        }
    }

    @Test
    void cancellingAFinishLeavesTheBranchAlone() throws Exception {
        setUpThroughTheDialog();
        assertTrue(new GitFlow(service).start(GitFlow.Kind.FEATURE, "login").isOk());
        refresh();

        interact(() -> tab.getSidebar().confirmFinishFlow("feature/login"));
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        interact(() -> assertEquals("Finish feature",
                ((Button) confirm.lookupButton(ButtonType.OK)).getText(), "the button names what it does"));
        press(confirm, "Cancel");
        RobotWait.drainGitWork();

        assertTrue(service.getLocalBranches().stream().anyMatch(b -> b.shortName().equals("feature/login")));
        assertEquals("feature/login", service.getCurrentBranch());
    }
}
