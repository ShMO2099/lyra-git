package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.Settings;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.GraphColumn;
import com.jfx.git.graph.LaneAssigner;
import com.jfx.git.model.CommitInfo;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The column menu, driven the way a person drives it.
 *
 * <h2>Why a robot and not another layout test</h2>
 * Two bugs reached the user here. The menu was rebuilt and shown on every right-click
 * without dismissing the last, so copies stacked up; and unticking a column removed its
 * heading while the rows kept drawing it, because the rows were refreshed rather than
 * rebuilt. Neither is visible to a test that asks the model what it holds — the model was
 * right both times. They only appear when something actually clicks.
 */
class ColumnMenuRobotTest extends ApplicationTest {

    private RepoTab tab;

    /** A backend that answers everything harmlessly; the graph is populated directly. */
    private static GitBackend stubBackend() {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getCurrentBranch".equals(method.getName())) return "main";
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>();
        commits.add(new CommitInfo("aaaaaaaaa", "first subject", "Tester", "t@example.com",
                Instant.now(), List.of("bbbbbbbbb"), List.of()));
        commits.add(new CommitInfo("bbbbbbbbb", "second subject", "Tester", "t@example.com",
                Instant.now(), List.of(), List.of()));
        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) rows.add(new GitGraphRow(c));
        return rows;
    }

    /**
     * Puts every column back before the UI is built.
     *
     * <p>In {@code init} rather than a {@code @BeforeEach}: JUnit does not order two
     * {@code @BeforeEach} methods against each other, and TestFX builds the stage from one
     * of its own. Resetting after the stage was built left the graph showing the previous
     * test's columns, which is how this class first failed.
     */
    @Override
    public void init() {
        GraphColumn.resetToDefault();
    }

    @AfterEach
    void tidyUp() {
        GraphColumn.resetToDefault();
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
    }

    @Override
    public void start(Stage stage) {
        // The settings file surefire points at is shared by the run; these tests write
        // column preferences, so they reset them around each case rather than isolating.
        assertNotNull(Settings.file());

        tab = new RepoTab(new LyraApp(), "test", stubBackend());
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 640));
        stage.show();
        tab.showRows(rows());
    }

    /** The header strip in the graph. */
    private Node header() {
        return lookup(".graph-header").query();
    }

    /**
     * Opens the column menu the way a right-click does.
     *
     * <p>Fires the context-menu request rather than asking the robot to right-click.
     * Under Monocle Headless a synthesised right-click never becomes a context-menu
     * request — that mapping lives in the native layer the headless platform replaces —
     * so a robot right-click would silently test nothing. {@code MonocleCapabilitiesTest}
     * pins that, and will fail if it ever stops being true.
     *
     * <p>Everything after this point is a real robot click on a real menu entry, which
     * is the half that matters and the half that works.
     */
    private void rightClickHeader() {
        Node header = header();
        interact(() -> Event.fireEvent(header, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Clicks the entry named {@code text} in the open menu.
     *
     * <p>Scoped to the popup, which is not fussiness. A bare {@code clickMenuItem("SHA")}
     * matches the <em>column heading</em> of the same name first and clicks the header
     * instead — so the test pressed nothing, the preference never changed, and the
     * failure looked like a broken menu. Only SHA collides today, which is exactly the
     * kind of coincidence that makes a whole suite untrustworthy.
     */
    private void clickMenuItem(String text) {
        Node item = from(lookup(".context-menu")).lookup(text).query();
        clickOn(item);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Preferred width of column {@code index}, agreed across every row on screen.
     *
     * <p>Reads all of them rather than the first one found. A {@link javafx.scene.control.ListView}
     * keeps discarded cells parented in its pile, so a lookup can return a row built by
     * the previous cell factory — which is exactly the state a column change leaves
     * behind. Taking the first match made this test depend on which one that was; if the
     * live rows ever disagree, that is a bug worth failing on rather than sampling past.
     */
    private double rowColumnWidth(int index) {
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        java.util.Set<Double> widths = new java.util.LinkedHashSet<>();
        for (Node node : lookup(".graph-row").queryAll()) {
            if (node instanceof GridPane grid && onScreen(grid)) {
                widths.add(grid.getColumnConstraints().get(index).getPrefWidth());
            }
        }
        if (widths.isEmpty()) {
            throw new AssertionError("no graph row was realised");
        }
        assertEquals(1, widths.size(),
                "rows on screen disagree about column " + index + ": " + widths);
        return widths.iterator().next();
    }

    /** True when {@code node} and every ancestor is visible, so it is really being drawn. */
    private static boolean onScreen(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Popup windows currently on screen — a context menu is one of these. */
    private long openPopups() {
        return Window.getWindows().stream()
                .filter(w -> w instanceof javafx.stage.PopupWindow && w.isShowing())
                .count();
    }

    // ------------------------------------------------------------------

    @Test
    void rightClickingTheHeaderOpensTheColumnMenu() {
        rightClickHeader();

        assertEquals(1, openPopups(), "one right-click should open exactly one menu");
        assertNotNull(from(lookup(".context-menu")).lookup("SHA").tryQuery().orElse(null),
                "the menu should list the columns");
    }

    /**
     * The reported bug: each right-click stacked another menu on the last, filling the
     * header with overlapping copies.
     */
    @Test
    void rightClickingRepeatedlyNeverStacksMenus() {
        rightClickHeader();
        rightClickHeader();
        rightClickHeader();

        assertEquals(1, openPopups(),
                "showing a fresh menu without hiding the previous one is what stacked them");
    }

    /**
     * The other reported bug: the heading went and the data stayed.
     */
    @Test
    void untickingAColumnRemovesItFromTheRowsAndNotOnlyTheHeader() {
        assertTrue(rowColumnWidth(6) > 0, "the SHA column should start out drawn");

        rightClickHeader();
        clickMenuItem("SHA");

        assertFalse(GraphColumn.HASH.visible(), "the click should reach the preference");
        assertEquals(0, rowColumnWidth(6),
                "refreshing the list repopulates existing cells and leaves the column "
                        + "drawn; the rows have to be rebuilt");
    }

    @Test
    void untickingTheMessageColumnRemovesItFromTheRows() {
        rightClickHeader();
        clickMenuItem("Commit message");

        assertEquals(0, rowColumnWidth(3),
                "the message column takes the slack, so hiding it must collapse it");
    }

    @Test
    void aHiddenColumnComesBackWhenTickedAgain() {
        rightClickHeader();
        clickMenuItem("SHA");
        assertEquals(0, rowColumnWidth(6));

        rightClickHeader();
        clickMenuItem("SHA");

        assertTrue(GraphColumn.HASH.visible());
        assertTrue(rowColumnWidth(6) > 0, "ticking it again has to bring the column back");
    }

    @Test
    void resettingFromTheMenuRestoresEveryColumn() {
        rightClickHeader();
        clickMenuItem("SHA");
        rightClickHeader();
        clickMenuItem("Date / Time");
        assertEquals(0, rowColumnWidth(6));
        assertEquals(0, rowColumnWidth(5));

        rightClickHeader();
        clickMenuItem("Reset columns to default layout");

        assertTrue(rowColumnWidth(6) > 0, "SHA should be back");
        assertTrue(rowColumnWidth(5) > 0, "Date / Time should be back");
    }

    @Test
    void theGearOpensTheSameMenu() {
        Node gear = lookup(".graph-columns-gear").query();
        clickOn(gear);

        assertEquals(1, openPopups());
        assertNotNull(from(lookup(".context-menu")).lookup("Reset columns to default layout")
                .tryQuery().orElse(null));
    }
}
