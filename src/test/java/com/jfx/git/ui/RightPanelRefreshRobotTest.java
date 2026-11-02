package com.jfx.git.ui;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.CommitMessages;
import com.jfx.git.core.ExternalTools;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.HunkEdit;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.model.StashOptions;
import com.jfx.git.ui.views.BisectRunView;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the staging panel's operations redraw afterwards: what they changed, and nothing for
 * a failure that changed nothing. The tab is a recording {@link OperationRunner.Screen}, so
 * every refresh an operation asks for - and every change it records as the tab's own - is
 * counted rather than inferred from what the screen happens to show.
 *
 * <p>A refresh is not free when nothing changed: it reads the repository again, puts the
 * staging panel back in front of a commit being looked at, and refreshing the commit box
 * once put the repository's Skip hooks default back over the user's choice.
 */
class RightPanelRefreshRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private RefusingGitService service;
    private DetailPanel panel;
    private RightPanelManager rightPanel;
    /** What each operation asked to redraw, in order. */
    private final List<OperationRunner.Refresh> refreshed = new ArrayList<>();
    /** What each operation recorded as the tab's own change. */
    private final List<OperationRunner.Refresh> recorded = new ArrayList<>();
    /** The view "Run script…" opened. */
    private RepoView opened;

    @Override
    public void start(Stage stage) throws Exception {
        CommitMessages.useFile(tempDir.resolve("commit-messages.json"));
        Path work = Files.createDirectory(tempDir.resolve("work"));
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
            }
            service = new RefusingGitService();
            assertTrue(service.openRepository(work.toFile()));
            commit("a.txt", lines(null), "base");
        }

        panel = new DetailPanel();
        DiffViewer diffViewer = new DiffViewer();
        StatusBar statusBar = new StatusBar();
        OperationRunner operations = new OperationRunner(service, statusBar, new OperationRunner.Screen() {
            // As the tab does, NONE redraws and records nothing.
            @Override
            public void refresh(OperationRunner.Refresh scope) {
                if (scope != OperationRunner.Refresh.NONE) refreshed.add(scope);
            }

            @Override
            public void recordOwnChange(OperationRunner.Refresh scope) {
                if (scope != OperationRunner.Refresh.NONE) recorded.add(scope);
            }
        });
        rightPanel = new RightPanelManager(operations, panel, diffViewer, () -> "main");
        rightPanel.setViewHost((kind, create) -> opened = create.get());
        stage.setScene(new Scene(new VBox(new HBox(panel, diffViewer), statusBar), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        AppPreferences.setMergeToolMode(ExternalTools.Mode.NONE);
        AppPreferences.setMergeToolCommand("");
        service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        CommitMessages.useFile(null);
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A backend whose stash, hunk staging and commit say no while {@link #refuse} is set. */
    static final class RefusingGitService extends GitService {
        volatile boolean refuse;

        private static GitResult refused() {
            return GitResult.failed("Refused for the test");
        }

        @Override
        public GitResult stash(StashOptions options) {
            return refuse ? refused() : super.stash(options);
        }

        @Override
        public GitResult applyToIndex(String path, HunkEdit edit, String action) {
            return refuse ? refused() : super.applyToIndex(path, edit, action);
        }

        @Override
        public GitResult commit(String message, boolean amend, boolean skipHooks) {
            return refuse ? refused() : super.commit(message, amend, skipHooks);
        }
    }

    /** Twenty numbered lines, line 2 replaced by {@code changed} when it is not null. */
    private static String lines(String changed) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            text.append(i == 2 && changed != null ? changed : "line" + i).append('\n');
        }
        return text.toString();
    }

    private Path workTree() {
        return service.getWorkTree().toPath();
    }

    private void write(String path, String content) throws Exception {
        Files.writeString(workTree().resolve(path), content, StandardCharsets.UTF_8);
    }

    private void commit(String path, String content, String message) throws Exception {
        write(path, content);
        assertTrue(service.stageFile(path).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    /** Builds the staging panel and waits for {@code path} to be listed in it. */
    private void showStagingArea(String listClass, String path) {
        interact(() -> rightPanel.showStagingArea());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the staging panel never listed " + path, () -> cell(listClass, path) != null);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The cell showing {@code path} in the list with {@code listClass}, or null. Call on the FX thread. */
    private ListCell<?> cell(String listClass, String path) {
        Node list = root().lookup("." + listClass);
        if (list == null) return null;
        for (Node node : list.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && path.equals(cell.getItem()) && cell.getContextMenu() != null) {
                return cell;
            }
        }
        return null;
    }

    private static MenuItem item(List<MenuItem> items, String text) {
        return items.stream().filter(i -> text.equals(i.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " in the menu"));
    }

    /** Forgets what the panel's own build asked for, so only the operation under test counts. */
    private void startCounting() {
        RobotWait.drainGitWork();
        interact(() -> {
            refreshed.clear();
            recorded.clear();
        });
    }

    /** Waits for the status bar to say {@code text}, then for anything queued behind it. */
    private void awaitStatus(String text) {
        RobotWait.untilOnFx("the status bar never said \"" + text + "\"", () -> lookup(".status-message")
                .tryQuery().map(n -> String.valueOf(((javafx.scene.control.Labeled) n).getText()))
                .orElse("").contains(text));
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void assertNothingRedrawn(String what) {
        interact(() -> {
            assertEquals(List.of(), refreshed, what + " changed nothing, so nothing is redrawn");
            assertEquals(List.of(), recorded, what + " changed nothing, so nothing is recorded");
        });
    }

    private Button button(Node root, String text) {
        return (Button) FxHarness.lookupAll(root, "button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------
    // Committing
    // ------------------------------------------------------------------

    @Test
    void aCommitThatLandsRedrawsEverything() throws Exception {
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());
        showStagingArea("staging-staged-list", "b.txt");
        interact(() -> ((TextField) root().lookup(".composer-summary")).setText("add b"));
        startCounting();

        interact(() -> rightPanel.commitStaged());
        RobotWait.untilOnFx("the commit never redrew the tab", () -> !recorded.isEmpty());
        RobotWait.drainGitWork();
        interact(() -> assertEquals(List.of(OperationRunner.Refresh.EVERYTHING), recorded));
    }

    /** A commit git refused changed nothing; the box is left as it was, options included. */
    @Test
    void aCommitGitRefusesRedrawsNothing() throws Exception {
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());
        showStagingArea("staging-staged-list", "b.txt");
        interact(() -> ((TextField) root().lookup(".composer-summary")).setText("refused"));
        service.refuse = true;
        startCounting();

        interact(() -> rightPanel.commitStaged());
        awaitStatus("Refused for the test");
        assertNothingRedrawn("A refused commit");
    }

    /** Stage all and commit did change the index before the commit was refused; that is drawn. */
    @Test
    void aRefusedCommitAfterStageAllStillRedrawsWhatWasStaged() throws Exception {
        write("b.txt", "b\n");
        showStagingArea("staging-unstaged-list", "b.txt");
        interact(() -> ((TextField) root().lookup(".composer-summary")).setText("refused"));
        service.refuse = true;
        startCounting();

        interact(() -> rightPanel.stageAllAndCommit());
        awaitStatus("Refused for the test");
        interact(() -> assertEquals(List.of(OperationRunner.Refresh.EVERYTHING), recorded));
    }

    // ------------------------------------------------------------------
    // The staging panel's other operations
    // ------------------------------------------------------------------

    @Test
    void aStashThatFailsRedrawsNothing() throws Exception {
        write("a.txt", lines("changed"));
        showStagingArea("staging-unstaged-list", "a.txt");
        interact(() -> ((ToggleButton) root().lookup(".composer-tab-stash")).fire());
        service.refuse = true;
        startCounting();

        interact(() -> rightPanel.submitComposer());
        awaitStatus("Refused for the test");
        assertNothingRedrawn("A refused stash");
    }

    @Test
    void aHunkThatFailsToStageRedrawsNothing() throws Exception {
        write("a.txt", lines("changed"));
        showStagingArea("staging-unstaged-list", "a.txt");
        interact(() -> {
            @SuppressWarnings("unchecked")
            ListView<String> list = (ListView<String>) root().lookup(".staging-unstaged-list");
            list.getSelectionModel().select("a.txt");
        });
        RobotWait.untilOnFx("a.txt's hunk never offered Stage hunk", () -> button(root(), "Stage hunk") != null);
        service.refuse = true;
        startCounting();

        interact(() -> button(root(), "Stage hunk").fire());
        awaitStatus("Refused for the test");
        assertNothingRedrawn("A refused hunk");
    }

    @Test
    void anIgnoreRuleThatCannotBeWrittenRedrawsNothing() throws Exception {
        commit(".gitignore", "# rules\n", "ignore nothing yet");
        Path rules = workTree().resolve(".gitignore");
        assertTrue(rules.toFile().setWritable(false));
        try {
            write("junk.log", "noise\n");
            showStagingArea("staging-unstaged-list", "junk.log");
            startCounting();

            interact(() -> {
                ContextMenu menu = cell("staging-unstaged-list", "junk.log").getContextMenu();
                Menu ignore = (Menu) item(menu.getItems(), "Ignore");
                item(ignore.getItems(), "This file").fire();
            });
            awaitStatus("Could not update .gitignore");
            assertNothingRedrawn("An ignore rule that was never written");
        } finally {
            rules.toFile().setWritable(true);
        }
    }

    /**
     * A merge tool that could not even start changed nothing. Redrawing the staging panel
     * for it took the user away from whatever they had turned to while it was starting.
     */
    @Test
    void aMergeToolThatCannotRunRedrawsNothing() throws Exception {
        assertTrue(service.createBranch("other", null, true).isOk());
        commit("a.txt", lines("theirs"), "their change");
        assertTrue(service.checkoutBranch("main").isOk());
        commit("a.txt", lines("ours"), "our change");
        assertTrue(new RepositoryCommands(service).merge("other", false).isConflict());
        AppPreferences.setMergeToolMode(ExternalTools.Mode.CUSTOM);
        AppPreferences.setMergeToolCommand("lyra-no-such-merge-tool $MERGED");
        showStagingArea("staging-conflicted-list", "a.txt");
        startCounting();

        interact(() -> item(cell("staging-conflicted-list", "a.txt").getContextMenu().getItems(),
                "Resolve in external merge tool").fire());
        awaitStatus("Could not run");
        assertNothingRedrawn("A merge tool that never ran");
    }

    // ------------------------------------------------------------------
    // Bisect with a script
    // ------------------------------------------------------------------

    /**
     * A bisect run moves HEAD from commit to commit. Redrawn without being recorded as the
     * tab's own change, the next return to the window reloaded the whole tab for it again.
     */
    @Test
    void aBisectRunIsRecordedAsTheTabsOwnChange() throws Exception {
        List<String> commits = new ArrayList<>();
        commits.add(service.getHeadHash());
        for (int i = 1; i <= 4; i++) {
            commit("a.txt", lines("step " + i), "step " + i);
            commits.add(service.getHeadHash());
        }
        assertTrue(service.bisectStart().isOk());
        service.bisectMarkBad(commits.get(commits.size() - 1));
        service.bisectMarkGood(commits.get(0));
        interact(() -> rightPanel.updateBisectBanner());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the bisect banner never offered a script", () -> button(root(), "Run script…") != null);
        interact(() -> button(root(), "Run script…").fire());
        BisectRunView view = assertInstanceOf(BisectRunView.class, opened);
        startCounting();

        interact(() -> {
            ((TextField) view.content().lookup("#" + BisectRunView.COMMAND_ID)).setText("exit 0");
            button(view.content(), "Run").fire();
        });
        RobotWait.untilOnFx("the run never finished", () -> !view.isRunning());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> {
            assertEquals(List.of(OperationRunner.Refresh.EVERYTHING), refreshed, "the run moved HEAD");
            assertEquals(List.of(OperationRunner.Refresh.EVERYTHING), recorded,
                    "and moved it as the tab's own change");
        });
    }

    /** Everything on the stage: the panel, the diff viewer and the status bar. Call on the FX thread. */
    private Node root() {
        return panel.getScene().getRoot();
    }
}
