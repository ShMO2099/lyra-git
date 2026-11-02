package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ReflogItem;
import com.jfx.git.ui.views.BisectRunView;
import com.jfx.git.ui.views.LfsView;
import com.jfx.git.ui.views.ReflogView;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository's tools - the reflog, Git LFS and a scripted bisect - open inside its
 * tab in place of the graph, through the same entry points the menus use, rather than as
 * windows of their own. Its settings, hooks and housekeeping are the Settings tab's
 * Repository section, which the sidebar's entry opens; see {@code RepositoryPageTest}.
 */
class RepoToolViewsRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        repo = tempDir.resolve("repo");
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.com");
                config.setString("core", null, "autocrlf", "false");
                config.save();
            }
            service = new GitService();
            service.openRepository(repo.toFile());
        }
        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    // ------------------------------------------------------------------

    private String commit(String content) {
        try {
            Files.writeString(repo.resolve("f.txt"), content, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assertTrue(service.stageFile("f.txt").isOk());
        assertTrue(service.commit("commit " + content, false).isOk());
        return head();
    }

    private String head() {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().resolve("HEAD").getName();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private boolean showing(Node node) {
        if (node.getScene() == null) return false;
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    private Button backButton() {
        return lookup(".repo-view-header .button").queryAs(Button.class);
    }

    private <T extends Node> T inView(String id, Class<T> type) {
        return type.cast(tab.currentView().content().lookup("#" + id));
    }

    private Button viewButton(String text) {
        return lookup(".repo-view .button").queryAllAs(Button.class).stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("no \"" + text + "\" button in the view"));
    }

    // ------------------------------------------------------------------

    @Test
    void eachToolOpensInTheTabAndBackReturnsToTheGraph() {
        commit("one");
        Map<Class<? extends RepoView>, Runnable> openers = new java.util.LinkedHashMap<>();
        openers.put(ReflogView.class, () -> tab.getToolbar().openReflog());
        openers.put(LfsView.class, () -> tab.getSidebar().promptLfs());
        int windows = Window.getWindows().size();

        openers.forEach((kind, open) -> {
            interact(open);
            assertInstanceOf(kind, tab.currentView(), kind.getSimpleName() + " should open in the tab");
            assertFalse(showing(tab.graphList()), kind.getSimpleName() + ": the graph gives way");
            assertTrue(showing(tab.getToolbar()), kind.getSimpleName() + ": the toolbar stays");
            assertTrue(showing(tab.currentView().content()));
            assertEquals(windows, Window.getWindows().size(),
                    kind.getSimpleName() + " should not open a window of its own");
            // Each reads the repository as it opens; let that land before leaving it.
            RobotWait.drainGitWork();

            clickOn(backButton());
            assertNull(tab.currentView(), kind.getSimpleName() + ": Back closes it");
            assertTrue(showing(tab.graphList()), kind.getSimpleName() + ": and the graph is back");
        });
    }

    @Test
    void openingAToolAgainBringsItForwardRatherThanStackingASecond() {
        commit("one");
        interact(() -> tab.getToolbar().openReflog());
        RepoView reflog = tab.currentView();
        interact(() -> tab.getSidebar().promptLfs());
        interact(() -> tab.getToolbar().openReflog());

        assertSame(reflog, tab.currentView(), "the same reflog, not a second one");
        assertEquals("← Back to Git LFS", backButton().getText());
        clickOn(backButton());
        assertInstanceOf(LfsView.class, tab.currentView());
        clickOn(backButton());
        assertNull(tab.currentView(), "only the two were ever open");
    }

    @Test
    void restoringFromTheReflogMovesTheBranchBack() {
        String first = commit("one");
        commit("two");
        commit("three");

        interact(() -> tab.getToolbar().openReflog());
        ReflogView view = (ReflogView) tab.currentView();
        RobotWait.untilOnFx("the reflog never listed the three commits",
                () -> view.loadedEntries().size() >= 3);

        @SuppressWarnings("unchecked")
        ListView<ReflogItem> entries = inView(ReflogView.ENTRIES_ID, ListView.class);
        interact(() -> {
            int index = 0;
            for (ReflogItem item : entries.getItems()) {
                if (first.startsWith(item.shortNewHash())) break;
                index++;
            }
            entries.getSelectionModel().select(index);
        });
        Button restore = inView(ReflogView.RESTORE_ID, Button.class);
        assertFalse(restore.isDisabled());
        // Not interact(): the confirmation is a modal alert, whose nested event loop an
        // interact() block would wait on forever.
        Platform.runLater(restore::fire);
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        interact(() -> ((Button) confirm.lookupButton(ButtonType.OK)).fire());

        RobotWait.until("the branch never moved back", () -> first.equals(head()));
        RobotWait.untilOnFx("the restore itself never showed in the history",
                () -> view.loadedEntries().size() >= 4);
        assertSame(view, tab.currentView(), "the view stays open on the history");
    }

    /**
     * "Repository settings…" is the Settings tab's Repository section now, asked for on
     * this repository - nothing opens in the repository's own tab.
     */
    @Test
    void repositorySettingsOpenTheSettingsTabOnThisRepository() {
        commit("one");
        List<File> asked = new ArrayList<>();
        // In a local, kept reachable to the end: the navigator holds what it is given weakly.
        AppNavigator.Target standIn = new AppNavigator.Target() {
            @Override
            public void openSettings(SettingsSection section) {
            }

            @Override
            public void openRepositorySettings(File repository) {
                asked.add(repository);
            }

            @Override
            public void openActivityLog() {
            }
        };
        AppNavigator.use(standIn);
        try {
            interact(() -> tab.getSidebar().openRepositorySettings());

            assertEquals(List.of(repo.toFile().getAbsoluteFile()),
                    asked.stream().map(File::getAbsoluteFile).toList());
            assertNull(tab.currentView(), "the repository's tab keeps its graph");
        } finally {
            AppNavigator.use(null);
            java.lang.ref.Reference.reachabilityFence(standIn);
        }
    }

    /**
     * The run holds the repository's git lane, so leaving its view stops it rather than
     * leaving every other action in the tab queued behind something nobody can see.
     */
    @Test
    void aBisectRunOpensFromTheBannerAndLeavingItsViewStopsTheRun() throws Exception {
        List<String> commits = new ArrayList<>();
        for (int i = 0; i < 6; i++) commits.add(commit("c" + i));
        assertTrue(service.bisectStart().isOk());
        service.bisectMarkBad(commits.get(commits.size() - 1));
        service.bisectMarkGood(commits.get(0));
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the bisect banner never offered a script",
                () -> lookup("Run script…").tryQuery().isPresent());

        clickOn("Run script…");
        BisectRunView view = assertInstanceOf(BisectRunView.class, tab.currentView());

        // Busy inside the shell itself, not in a child process: killing the shell then
        // ends it, and nothing outlives the test holding the temporary directory open.
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String busy = windows
                ? "echo x> started.txt & for /L %i in (1,1,2000000000) do @rem"
                : "touch started.txt; while :; do :; done";
        interact(() -> inView(BisectRunView.COMMAND_ID, TextField.class).setText(busy));
        interact(() -> viewButton("Run").fire());
        RobotWait.until("the command never started", () -> Files.exists(repo.resolve("started.txt")));
        assertTrue(view.isRunning());

        clickOn(backButton());
        assertNull(tab.currentView());
        RobotWait.untilOnFx("leaving the view never stopped the run", () -> !view.isRunning());
        RobotWait.drainGitWork();
        assertTrue(service.bisectStatus().active(),
                "the session is left where the run stopped, not reset");
    }
}
