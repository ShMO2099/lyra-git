package com.jfx.git;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.Settings;
import com.jfx.git.core.TabSession;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.testsupport.MainWindowFixture;
import com.jfx.git.ui.DetailPanel;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.RepoToolbar;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.SidebarPane;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.ThemeManager;
import com.jfx.git.ui.UiZoom;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The main window, made as small as it allows, still shows the whole repository layout.
 *
 * <p>It used to allow anything: dragged to 180 pixels it kept a slice of the menu bar, the
 * sidebar's headings and nothing else. The window now has a minimum, and this checks what
 * the minimum is for rather than its number - at that size every pane gets at least its
 * own floor, nothing is pushed off an edge, and the bars at the top and bottom are whole.
 * A pane that raises its floor without the window following fails here.
 *
 * <p>A full application on a stage of its own, not TestFX's shared primary stage: a
 * minimum set on that one would outlive this class and clamp every later window.
 */
@Tag("heavy")
class MainWindowMinimumTest {

    private static final String LONG_SUBJECT = "Explain the release process in the details panel";

    @TempDir
    Path temp;

    private LyraApp app;
    private Stage stage;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tearDown() {
        RobotWait.drainGitWork();
        if (app != null) {
            FxHarness.run(() -> app.openRepositories().stream()
                    .map(LyraApp.OpenRepository::tab)
                    .filter(RepoTab.class::isInstance)
                    .map(RepoTab.class::cast)
                    .toList()
                    .forEach(RepoTab::close));
        }
        RobotWait.drainGitWork();
        FxHarness.run(() -> {
            if (stage != null) stage.hide();
            UiZoom.resetForTests();
            ThemeManager.apply(ThemeManager.Theme.DARK);
        });
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", null);
    }

    @ParameterizedTest(name = "{0}% in {1}")
    @CsvSource({"100, DARK", "125, DARK", "100, LIGHT", "125, LIGHT"})
    void atItsMinimumTheWindowStillHoldsEveryPane(int zoom, ThemeManager.Theme theme) throws Exception {
        // A message long enough that the details panel would rather be taller than the
        // window: what pushed the status bar off the bottom at ordinary sizes.
        String message = LONG_SUBJECT + "\n\n" + "A line of the body that wraps in the panel.\n".repeat(20);
        MainWindowFixture fixture = MainWindowFixture.create(temp)
                .commitFiles(message, Map.of("docs/release.md", "steps".getBytes()), List.of());
        // A linked worktree, so the sidebar has all five sections: the WORKTREES heading
        // only appears when there is one, and it is the one that fell under the status bar.
        NativeGitBackend git = new NativeGitBackend();
        try {
            assertTrue(git.openRepository(fixture.workTree().toFile()));
            assertTrue(git.addWorktree(temp.resolve("linked").toFile(), "release-notes", true).isOk());
        } finally {
            git.close();
        }

        // Widths someone dragged are remembered, and the tests share one settings file.
        AppPreferences.setGraphRailWidth(0);
        AppPreferences.setGraphLaneBudget(0);
        AppPreferences.setLayoutSidebarWidth(0);
        AppPreferences.setLayoutDetailWidth(0);
        AppPreferences.setCommitMessageHeight(0);
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", fixture.workTree().toAbsolutePath().toString());

        app = new LyraApp();
        stage = FxHarness.call(() -> {
            UiZoom.set(zoom);
            Stage window = new Stage();
            app.start(window);
            ThemeManager.apply(theme);
            return window;
        });
        RobotWait.untilOnFx("the repository never opened", () -> {
            RepoTab tab = app.getActiveRepoTab();
            return tab != null && tab.getGitService().isOpen() && !tab.getCommitList().getItems().isEmpty();
        });
        RobotWait.drainGitWork();
        FxHarness.run(() -> {
            RepoTab tab = app.getActiveRepoTab();
            GitGraphRow row = tab.getCommitList().getItems().stream()
                    .filter(r -> r.getCommit() != null && LONG_SUBJECT.equals(r.getCommit().getSubject()))
                    .findFirst().orElseThrow();
            tab.getCommitList().getSelectionModel().clearAndSelect(tab.getCommitList().getItems().indexOf(row));
        });
        RobotWait.drainGitWork();

        FxHarness.run(() -> {
            app.installWindowMinimum();
            stage.setWidth(stage.getMinWidth());
            stage.setHeight(stage.getMinHeight());
        });
        assertTrue(FxHarness.call(() -> stage.getMinWidth()) > 0, "the main window must have a minimum width");
        assertTrue(FxHarness.call(() -> stage.getMinHeight()) > 0, "the main window must have a minimum height");
        // The toolbar, the graph and the sidebar each choose their layout from the size they
        // were given, some of them a pass later (the sidebar shares its height out in a
        // runLater): measured until two passes in a row agree.
        Layout[] settled = new Layout[1];
        RobotWait.untilOnFx("the window never settled at its minimum size", () -> {
            FxHarness.pulse(stage.getScene().getRoot());
            Layout now = new Layout(stage, app.getActiveRepoTab());
            boolean same = settled[0] != null && settled[0].signature().equals(now.signature());
            settled[0] = now;
            return same;
        });
        Layout layout = settled[0];
        double z = zoom / 100.0;
        double width = layout.sceneWidth;
        double height = layout.sceneHeight;

        assertWhole("toolbar", layout.toolbar, width, height);
        assertWhole("status bar", layout.statusBar, width, height);
        assertWhole("details panel", layout.details, width, height + 1e6);

        assertTrue(layout.sidebar.getMinX() >= -1,
                "the sidebar was pushed off the left edge, to x=" + layout.sidebar.getMinX());
        assertTrue(layout.sidebar.getWidth() >= (SidebarPane.MIN_WIDTH - 1) * z,
                "sidebar " + layout.sidebar.getWidth() + " is below its floor");
        assertTrue(layout.graph.getWidth() >= (RepoTab.graphFloor() - 1) * z,
                "graph " + layout.graph.getWidth() + " is below its floor " + RepoTab.graphFloor() * z);
        assertTrue(layout.details.getWidth() >= (RepoTab.DETAIL_MIN_WIDTH - 1) * z,
                "details " + layout.details.getWidth() + " is below its floor");
        // Taller than the room it has, the panel loses its foot, never its heading: the
        // split centred it, and cut "Commit 1a2b3c4" off the top along with the bottom.
        assertTrue(layout.details.getMinY() >= layout.toolbar.getMaxY() - 1,
                "the details panel starts at " + layout.details.getMinY() + ", above the toolbar's foot at "
                        + layout.toolbar.getMaxY());
        assertTrue(layout.graph.getMinX() >= layout.sidebar.getMaxX() - 1
                        && layout.graph.getMaxX() <= layout.details.getMinX() + 1,
                "the graph overlaps a neighbour: " + layout.graph);

        // Refs, Commit, Age and SHA: the last heading is still inside the graph.
        assertFalse(layout.columnTitles.isEmpty(), "the graph has no column titles");
        Bounds last = layout.columnTitles.get(layout.columnTitles.size() - 1);
        assertTrue(last.getMaxX() <= layout.graph.getMaxX() + 1,
                "the graph's last column runs out of it, to " + last.getMaxX() + " past " + layout.graph.getMaxX());

        // LOCAL, REMOTE, TAGS, STASHES, WORKTREES: every heading above the status bar.
        assertEquals(5, layout.sectionTitles.size(), "the sidebar's five section headings");
        for (Bounds title : layout.sectionTitles) {
            assertTrue(title.getMaxY() <= layout.statusBar.getMinY() + 1,
                    "a sidebar heading sits at " + title.getMaxY() + ", under the status bar at "
                            + layout.statusBar.getMinY());
        }
    }

    private static void assertWhole(String what, Bounds bounds, double width, double height) {
        assertTrue(bounds.getMinX() >= -1 && bounds.getMinY() >= -1
                        && bounds.getMaxX() <= width + 1 && bounds.getMaxY() <= height + 1,
                "the " + what + " is not wholly in the " + width + "x" + height + " window: " + bounds);
    }

    /** Where the parts of the repository layout ended up, in scene coordinates. */
    private static final class Layout {
        final double sceneWidth;
        final double sceneHeight;
        final Bounds toolbar;
        final Bounds statusBar;
        final Bounds sidebar;
        final Bounds graph;
        final Bounds details;
        final List<Bounds> columnTitles = new ArrayList<>();
        final List<Bounds> sectionTitles = new ArrayList<>();

        Layout(Stage stage, RepoTab tab) {
            sceneWidth = stage.getScene().getWidth();
            sceneHeight = stage.getScene().getHeight();
            Parent content = (Parent) tab.getContent();
            toolbar = bounds(only(content, RepoToolbar.class));
            statusBar = bounds(only(content, StatusBar.class));
            SidebarPane sidebarPane = only(content, SidebarPane.class);
            sidebar = bounds(sidebarPane);
            graph = bounds(FxHarness.lookupAll(content, "commit-graph").get(0));
            details = bounds(only(content, DetailPanel.class));
            for (Node title : FxHarness.lookupAll(content, "graph-header-title")) {
                if (title.isVisible() && title.getLayoutBounds().getWidth() > 0) {
                    columnTitles.add(bounds(title));
                }
            }
            columnTitles.sort(java.util.Comparator.comparingDouble(Bounds::getMaxX));
            for (TitledPane section : all(sidebarPane, TitledPane.class)) {
                Node title = section.lookup(".title");
                if (section.isVisible() && title != null) {
                    sectionTitles.add(bounds(title));
                }
            }
        }

        /** Everything measured, as text, to tell whether the layout has stopped moving. */
        String signature() {
            return List.of(sceneWidth, sceneHeight, toolbar, statusBar, sidebar, graph, details,
                    columnTitles, sectionTitles).toString();
        }

        private static Bounds bounds(Node node) {
            return node.localToScene(node.getLayoutBounds());
        }

        private static <T extends Node> T only(Parent root, Class<T> type) {
            List<T> found = all(root, type);
            assertEquals(1, found.size(), "expected one " + type.getSimpleName());
            return found.get(0);
        }

        private static <T extends Node> List<T> all(Parent root, Class<T> type) {
            List<T> found = new ArrayList<>();
            collect(root, type, found);
            return found;
        }

        private static <T extends Node> void collect(Node node, Class<T> type, List<T> found) {
            if (type.isInstance(node) && node.isVisible()) {
                found.add(type.cast(node));
            }
            if (node instanceof Parent parent) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    collect(child, type, found);
                }
            }
        }
    }
}
