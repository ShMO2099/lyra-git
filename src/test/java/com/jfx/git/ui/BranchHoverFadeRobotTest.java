package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.LaneAssigner;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hovering a branch chip previews what that branch contains.
 *
 * <p>Two things are being pinned, and the second is the one that was wrong before this
 * existed. The first is that the pointer fades anything off the hovered branch at all.
 * The second is <em>what</em> recedes: only the subject text. Fading the whole row - which
 * is what the selection fade used to do - takes the lanes, the ref chips and the avatar
 * with it, and the lanes are precisely what a person is reading when they ask which
 * commits a branch holds. The answer has to leave the question legible.
 *
 * <p>{@code GraphHighlightTest} covers which commits count as on the branch;
 * {@code CommitFadeRobotTest} covers the selection fade this sits on top of.
 */
class BranchHoverFadeRobotTest extends ApplicationTest {

    private RepoTab tab;

    private static GitBackend stubBackend() {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getCurrentBranch".equals(method.getName())) return "main";
                    if ("getSignatureStatus".equals(method.getName())) {
                        return com.jfx.git.model.SignatureStatus.NONE;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    private static CommitInfo commit(String hash, String subject,
                                     List<RefInfo> refs, String... parents) {
        return new CommitInfo(hash, subject, "Tester", "t@example.com",
                Instant.now(), List.of(parents), refs);
    }

    private static RefInfo branch(String name, String hash) {
        return new RefInfo("refs/heads/" + name, name, hash);
    }

    private static RefInfo tag(String name, String hash) {
        return new RefInfo("refs/tags/" + name, name, hash);
    }

    /**
     * A trunk and a side branch that never merges, each carrying a branch ref:
     *
     * <pre>
     *   trunk tip [main]        side tip [feature]
     *       |                        |
     *   trunk mid                side base
     *       |                       /
     *      the fork [v1] ----------'
     *       |
     *      root
     * </pre>
     */
    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>(List.of(
                commit("aaaaaaaa1", "trunk tip", List.of(branch("main", "aaaaaaaa1")),
                        "aaaaaaaa2"),
                commit("bbbbbbbb1", "side tip", List.of(branch("feature", "bbbbbbbb1")),
                        "bbbbbbbb2"),
                commit("aaaaaaaa2", "trunk mid", List.of(), "cccccccc1"),
                commit("bbbbbbbb2", "side base", List.of(), "cccccccc1"),
                commit("cccccccc1", "the fork", List.of(tag("v1", "cccccccc1")),
                        "dddddddd1"),
                commit("dddddddd1", "root", List.of())));
        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) {
            rows.add(new GitGraphRow(c));
        }
        return rows;
    }

    @Override
    public void start(Stage stage) {
        tab = new RepoTab(new LyraApp(), "test", stubBackend());
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 640));
        stage.show();
        tab.showRows(rows());
    }

    // ------------------------------------------------------------------
    // Reading the graph
    // ------------------------------------------------------------------

    /** Subject text to whether that subject is drawn faded. */
    private Map<String, Boolean> fadedBySubject() {
        WaitForAsyncUtils.waitForFxEvents();
        Map<String, Boolean> faded = new LinkedHashMap<>();
        for (Node node : lookup(".graph-row").queryAll()) {
            if (!(node instanceof GridPane grid) || !onScreen(grid)) {
                continue;
            }
            for (Node label : FxHarness.lookupAll(grid, "graph-message")) {
                String text = ((Label) label).getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                Node box = label.getParent();
                faded.put(text, box != null
                        && box.getStyleClass().contains("graph-message-faded"));
            }
        }
        return faded;
    }

    /** The chip labelled {@code name}, or a failure naming what was on screen. */
    private Node chip(String name) {
        WaitForAsyncUtils.waitForFxEvents();
        List<String> seen = new ArrayList<>();
        for (Node node : lookup(".ref-chip").queryAll()) {
            if (!onScreen(node) || !(node instanceof Label label)) {
                continue;
            }
            String text = label.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            seen.add(text);
            if (text.contains(name)) {
                return node;
            }
        }
        return fail("no ref chip for " + name + "; on screen: " + seen);
    }

    private static boolean onScreen(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------

    @Test
    void hoveringABranchFadesTheCommitsThatAreNotOnIt() {
        moveTo(chip("feature"));

        Map<String, Boolean> faded = fadedBySubject();
        assertEquals(Boolean.FALSE, faded.get("side tip"), "the branch tip");
        assertEquals(Boolean.FALSE, faded.get("side base"), "its parent");
        assertEquals(Boolean.FALSE, faded.get("the fork"), "the fork is on the branch");
        assertEquals(Boolean.FALSE, faded.get("root"), "and so is everything below it");
        assertEquals(Boolean.TRUE, faded.get("trunk mid"),
                "trunk built past the fork is not on feature: " + faded);
        assertEquals(Boolean.TRUE, faded.get("trunk tip"), "nor is the trunk tip");
    }

    /**
     * The point of the whole change. The report was that commercial clients fade only the commit
     * messages and nothing else - the graph itself has to survive the hover, or the fade
     * hides the thing it was asked about.
     */
    @Test
    void nothingButTheMessageColumnDims() {
        moveTo(chip("feature"));
        WaitForAsyncUtils.waitForFxEvents();

        for (Node node : lookup(".graph-row").queryAll()) {
            if (!(node instanceof GridPane grid) || !onScreen(grid)) {
                continue;
            }
            assertFalse(grid.getStyleClass().contains("graph-message-faded"),
                    "the row itself must stay bright");
            for (Node refChip : FxHarness.lookupAll(grid, "ref-chip")) {
                assertFalse(refChip.getStyleClass().contains("graph-message-faded"),
                        "ref chips must stay bright: " + ((Label) refChip).getText());
            }
            for (Node canvas : FxHarness.lookupAll(grid, "graph-canvas")) {
                assertFalse(canvas.getStyleClass().contains("graph-message-faded"),
                        "the lanes must stay bright - they are what the hover is about");
            }
        }
    }

    @Test
    void movingOffTheChipRestoresEveryCommit() {
        moveTo(chip("feature"));
        assertTrue(fadedBySubject().containsValue(Boolean.TRUE), "sanity: it faded");

        moveTo(chip("main"));
        moveTo(0, 0);

        Map<String, Boolean> faded = fadedBySubject();
        assertTrue(faded.values().stream().noneMatch(Boolean::booleanValue),
                "leaving the rail hands the graph back unfaded: " + faded);
    }

    @Test
    void hoveringTheOtherBranchFadesTheOppositeSide() {
        moveTo(chip("main"));

        Map<String, Boolean> faded = fadedBySubject();
        assertEquals(Boolean.FALSE, faded.get("trunk tip"));
        assertEquals(Boolean.FALSE, faded.get("trunk mid"));
        assertEquals(Boolean.FALSE, faded.get("the fork"), "shared history stays lit");
        assertEquals(Boolean.TRUE, faded.get("side tip"), "the side branch recedes");
        assertEquals(Boolean.TRUE, faded.get("side base"));
    }

    /**
     * A tag marks a point, not a line of work, so its ancestry is an accident of wherever
     * it was placed. Dimming the graph for one answers a question nobody asked.
     */
    @Test
    void hoveringATagFadesNothing() {
        moveTo(chip("v1"));

        Map<String, Boolean> faded = fadedBySubject();
        assertTrue(faded.values().stream().noneMatch(Boolean::booleanValue),
                "a tag is not a branch: " + faded);
    }

    /**
     * A refresh recycles the chip out from under the pointer, and recycling sends no
     * mouse-exited event. Without the cell ending its own hover the graph would stay
     * faded with nothing under the cursor to explain it, and no way back except hovering
     * some other chip and leaving it.
     */
    @Test
    void refreshingTheGraphUnderThePointerDoesNotStrandTheFade() {
        moveTo(chip("feature"));
        assertTrue(fadedBySubject().containsValue(Boolean.TRUE), "sanity: it faded");

        interact(() -> tab.showRows(rows()));
        WaitForAsyncUtils.waitForFxEvents();

        Map<String, Boolean> faded = fadedBySubject();
        assertTrue(faded.values().stream().noneMatch(Boolean::booleanValue),
                "the hover ends with the cell that was driving it: " + faded);
    }

    /**
     * The sidebar asks by name, and the graph is what answers. Driven through the
     * highlight rather than by moving the pointer onto a sidebar row, because what is
     * being pinned is that a name reaches the same fade a chip does - the sidebar's own
     * hover handlers are one line each and would only be testing TestFX.
     */
    @Test
    void aBranchNameFromTheSidebarFadesTheSameCommits() {
        interact(() -> tab.graphHighlightForTest().hoverBranchNamed("feature"));
        WaitForAsyncUtils.waitForFxEvents();

        Map<String, Boolean> faded = fadedBySubject();
        assertEquals(Boolean.FALSE, faded.get("side tip"), "the named branch stays lit");
        assertEquals(Boolean.FALSE, faded.get("the fork"));
        assertEquals(Boolean.TRUE, faded.get("trunk tip"),
                "and the unrelated trunk recedes, exactly as hovering the chip does");

        interact(() -> tab.graphHighlightForTest().hoverBranchNamed(null));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(fadedBySubject().values().stream().noneMatch(Boolean::booleanValue),
                "leaving the sidebar row hands the graph back");
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
    }
}
