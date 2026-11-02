package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphRow;

import javafx.event.EventHandler;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Searching the whole history from the filter box, and jumping to a result the loaded
 * page does not reach.
 *
 * <p>The graph's first page is cut to five commits and the matches are made twenty and
 * more commits back, so a search that only looked at loaded rows - which is all the filter
 * box used to do - finds nothing, and picking a result has to page the graph in before it
 * can select it.
 *
 * <p>Run against the CLI engine when there is one: {@code ThreadGuard} fires at the
 * subprocess spawn, so only that engine proves the search and the jump keep git off the
 * FX thread. JGit, which has no subprocess to catch, stands in otherwise.
 */
class HistorySearchRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitBackend backend;
    private RepoTab tab;
    private Stage stage;
    /** The commit whose body alone mentions "zanzibar", 25 commits below the top. */
    private String bodyMatch;
    /** The commit that added the line "secret_flag = on", 20 commits below the top. */
    private String contentMatch;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            for (int i = 0; i < 30; i++) {
                String message = "commit " + i;
                if (i == 4) message += "\n\nThe body mentions zanzibar, the subject does not.";
                Path file = tempDir.resolve("f" + i + ".txt");
                String content = i == 9 ? "secret_flag = on\n" : "v" + i + "\n";
                Files.writeString(file, content, StandardCharsets.UTF_8);
                git.add().addFilepattern(file.getFileName().toString()).call();
                String hash = git.commit().setMessage(message).call().getName();
                if (i == 4) bodyMatch = hash;
                if (i == 9) contentMatch = hash;
            }
        }

        AppPreferences.setInitialGraphCommits(5);
        if (NativeGitRunner.isGitInstalled()) {
            // Opening probes the repository with git; this is setup, not the code under test.
            try (var _ = ThreadGuard.buildingFixture()) {
                NativeGitBackend nativeBackend = new NativeGitBackend();
                assertTrue(nativeBackend.openRepository(tempDir.toFile()));
                backend = nativeBackend;
            }
        } else {
            GitService service = new GitService();
            assertTrue(service.openRepository(tempDir.toFile()));
            backend = service;
        }

        tab = new RepoTab(new LyraApp(), "test", backend);
        TabPane pane = new TabPane(tab);
        this.stage = stage;
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        tab.refresh();
    }

    @AfterEach
    void tidyUp() {
        // A search, a jump and the detail reads a selection starts all run on the lane;
        // they must be finished before the backend closes under them.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (backend != null) {
            backend.close();
        }
        AppPreferences.setInitialGraphCommits(0);
        // The stage is TestFX's primary stage, shared by every ApplicationTest in the JVM,
        // and the width a test gives it outlives the test: sizeToScene, while the window
        // still shows, hands the size back to the next class's scene.
        if (stage != null) interact(stage::sizeToScene);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private long loadedCommits() {
        return tab.graphList().getItems().stream()
                .filter(row -> !row.isWipNode() && row.getCommit() != null)
                .count();
    }

    private boolean loaded(String hash) {
        return tab.graphList().getItems().stream()
                .anyMatch(row -> row.getCommit() != null && hash.equals(row.getCommit().getHash()));
    }

    private String selectedHash() {
        GitGraphRow row = tab.graphList().getSelectionModel().getSelectedItem();
        return row == null || row.getCommit() == null ? null : row.getCommit().getHash();
    }

    private void awaitFirstPage() {
        RobotWait.untilOnFx("the graph's first page never arrived", () -> loadedCommits() == 5);
    }

    private void searchByEnter(String text) {
        TextField search = lookup(".search-field").query();
        clickOn(search);
        interact(() -> search.setText(text));
        type(KeyCode.ENTER);
    }

    private void awaitSummary(String prefix) {
        HistorySearchPanel panel = tab.historySearchPanel();
        RobotWait.untilOnFx("the history search never reported \"" + prefix + "\"",
                () -> panel.isVisible() && panel.summaryText().startsWith(prefix));
    }

    @Test
    void enterSearchesHistoryTheGraphHasNotLoadedAndPickingAResultJumpsToIt() {
        awaitFirstPage();
        assertFalse(loaded(bodyMatch), "the match is older than the first page");

        searchByEnter("zanzibar");
        awaitSummary("1 result");
        assertEquals(bodyMatch, tab.historySearchPanel().resultsList().getItems().get(0).getHash());

        // The summary is set when the search returns; the row is a list cell, created by
        // the layout pass after it. Querying in between found no row to click.
        RobotWait.untilOnFx("the result's row never rendered",
                () -> lookup(".history-search-subject").tryQuery().isPresent());
        javafx.scene.Node result = lookup(".history-search-subject").query();
        clickOn(result);

        RobotWait.untilOnFx("picking the result never selected it in the graph",
                () -> bodyMatch.equals(selectedHash()));
        assertTrue(loadedCommits() > 5, "the graph was paged in far enough to reach it");
    }

    /**
     * A content search needs the repository, so the instant filter leaves the loaded rows
     * alone rather than claiming none of them match; Enter finds the commit that changed
     * the line. The button does the same as Enter.
     */
    @Test
    void theButtonRunsAContentSearchWithoutTheInstantFilterHidingRows() {
        awaitFirstPage();
        long before = tab.graphList().getItems().size();

        TextField search = lookup(".search-field").query();
        interact(() -> search.setText("content:secret_flag"));
        assertEquals(before, tab.graphList().getItems().size(),
                "the loaded rows cannot answer a content query, so none are removed");

        clickOn("Search all history");
        awaitSummary("1 result");
        assertEquals(contentMatch, tab.historySearchPanel().resultsList().getItems().get(0).getHash());
    }

    /**
     * With text in the box, "Search all history" keeps its whole label inside the graph
     * column, and a click on it searches - in a window 1200 pixels wide, and in one 800
     * wide, where the column between the sidebar and the details is narrower than the
     * filter's options need even on a line of their own.
     *
     * <p>At 800 the row gave way by squeezing the button to "…" past the column's clip,
     * under the details panel, which is where the robot's click landed. At 1200 the ✕ that
     * typing brings in cut it to "Search all hi…": the options were placed when the bar
     * changed width, and not again when they grew.
     */
    @ParameterizedTest(name = "{0} pixels wide")
    @ValueSource(ints = {800, 1200})
    void withTextInTheFilterTheSearchButtonIsWholeAndAClickOnItSearches(int windowWidth) {
        interact(() -> stage.setWidth(windowWidth));
        awaitFirstPage();
        TextField search = lookup(".search-field").query();
        interact(() -> search.setText("content:secret_flag"));

        Button button = lookup(".history-search-button").queryButton();
        RobotWait.untilOnFx("\"Search all history\" never showed whole in a "
                + windowWidth + "-pixel window", () -> {
            assertEquals(windowWidth, stage.getWidth(), "the window's width");
            assertFalse(stage.getScene().getRoot().isNeedsLayout(), "a layout pass is still to come");
            assertShownWhole(button);
            return true;
        });

        assertClickReaches(button);
        awaitSummary("1 result");
        assertEquals(contentMatch, tab.historySearchPanel().resultsList().getItems().get(0).getHash());
    }

    /**
     * Fails, saying how, unless {@code button} is laid out at its whole width, inside the
     * scene and inside every clip above it. On the FX thread.
     */
    private static void assertShownWhole(Button button) {
        assertTrue(button.isVisible() && button.getScene() != null, "the button is not showing");
        assertTrue(!button.isTextTruncated() && button.getWidth() >= button.prefWidth(-1) - 0.5,
                () -> "its label is cut short: " + button.getWidth() + " of "
                        + button.prefWidth(-1) + " pixels");
        Bounds shown = button.localToScene(button.getLayoutBounds());
        Scene scene = button.getScene();
        assertTrue(covers(new BoundingBox(0, 0, scene.getWidth(), scene.getHeight()), shown),
                () -> "it lies outside the window: " + shown);
        for (Node node = button.getParent(); node != null; node = node.getParent()) {
            if (node.getClip() == null) continue;
            Bounds clip = node.localToScene(node.getClip().getBoundsInParent());
            Node clipped = node;
            assertTrue(covers(clip, shown), () -> "it lies outside the clip of "
                    + describe(clipped) + ": " + shown + " against " + clip);
        }
    }

    private static boolean covers(Bounds outer, Bounds inner) {
        double slack = 0.5;
        return inner.getMinX() >= outer.getMinX() - slack && inner.getMaxX() <= outer.getMaxX() + slack
                && inner.getMinY() >= outer.getMinY() - slack && inner.getMaxY() <= outer.getMaxY() + slack;
    }

    /**
     * Clicks {@code button} with the robot, and fails - naming what was hit - unless the
     * click reached the button rather than something laid over it.
     */
    private void assertClickReaches(Button button) {
        Scene scene = button.getScene();
        Node[] hit = new Node[1];
        EventHandler<MouseEvent> recordHit = e -> hit[0] = e.getPickResult().getIntersectedNode();
        interact(() -> scene.addEventFilter(MouseEvent.MOUSE_PRESSED, recordHit));
        try {
            clickOn(button);
            RobotWait.untilOnFx("the click never reached the window", () -> hit[0] != null);
        } finally {
            interact(() -> scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, recordHit));
        }
        Node target = hit[0];
        boolean onTheButton = false;
        for (Node node = target; node != null && !onTheButton; node = node.getParent()) {
            onTheButton = node == button;
        }
        assertTrue(onTheButton, "the click meant for the button landed on " + describe(target));
    }

    /** A node, and the nearest of the application's own classes around it: "Label in DetailPanel". */
    private static String describe(Node node) {
        String name = node.getClass().getSimpleName();
        for (Node around = node; around != null; around = around.getParent()) {
            if (around.getClass().getName().startsWith("com.jfx.git.")) {
                return around == node ? name : name + " in " + around.getClass().getSimpleName();
            }
        }
        return name;
    }

    /** A newer search replaces the older one's results, and ✕ closes the panel. */
    @Test
    void aNewSearchReplacesTheLastAndTheFilterClearCloses() {
        awaitFirstPage();

        searchByEnter("zanzibar");
        awaitSummary("1 result");
        searchByEnter("commit");
        awaitSummary("30 results");

        interact(() -> {
            // The filter bar's clear button: the one beside the box, not the panel's own.
            lookup(".row-action").queryAll().stream()
                    .filter(node -> node instanceof javafx.scene.control.Button b
                            && b.getTooltip() != null
                            && "Clear the filter".equals(b.getTooltip().getText()))
                    .findFirst()
                    .map(javafx.scene.control.Button.class::cast)
                    .orElseThrow()
                    .fire();
        });
        RobotWait.untilOnFx("clearing the filter should close the history search",
                () -> !tab.historySearchPanel().isVisible());
    }

    @Test
    void aCappedSearchSaysSo() {
        assertEquals("No commits match", HistorySearchPanel.summaryFor(0, false));
        assertEquals("1 result", HistorySearchPanel.summaryFor(1, false));
        assertEquals("500 results (limit reached)", HistorySearchPanel.summaryFor(500, true));
    }
}
