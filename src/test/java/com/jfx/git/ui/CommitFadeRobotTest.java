package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.LaneAssigner;
import com.jfx.git.model.CommitInfo;

import javafx.scene.Node;
import javafx.scene.Scene;
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
 * Selecting a commit fades the commits it has nothing to do with.
 *
 * <p>{@code GraphHighlightTest} decides which commits are related; this decides whether
 * clicking one actually changes what is drawn. The two halves failed independently once
 * already in this feature's neighbours — a correct model behind a row that never
 * repainted — so both are pinned.
 */
class CommitFadeRobotTest extends ApplicationTest {

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

    private static CommitInfo commit(String hash, String subject, String... parents) {
        return new CommitInfo(hash, subject, "Tester", "t@example.com",
                Instant.now(), List.of(parents), List.of());
    }

    /**
     * A trunk and a side branch that never merges, so there is something to fade.
     *
     * <pre>
     *   trunk-tip      side-tip
     *       |             |
     *   trunk-mid     side-base
     *       |            /
     *      fork --------'
     *       |
     *      root
     * </pre>
     */
    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>(List.of(
                commit("aaaaaaaa1", "trunk tip", "aaaaaaaa2"),
                commit("bbbbbbbb1", "side tip", "bbbbbbbb2"),
                commit("aaaaaaaa2", "trunk mid", "cccccccc1"),
                commit("bbbbbbbb2", "side base", "cccccccc1"),
                commit("cccccccc1", "the fork", "dddddddd1"),
                commit("dddddddd1", "root")));
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

    /** Subject text to faded-state, for every row on screen. */
    private Map<String, Boolean> fadedBySubject() {
        WaitForAsyncUtils.waitForFxEvents();
        Map<String, Boolean> faded = new LinkedHashMap<>();
        for (Node node : lookup(".graph-row").queryAll()) {
            if (!(node instanceof GridPane grid) || !onScreen(grid)) {
                continue;
            }
            for (Node child : grid.getChildrenUnmodifiable()) {
                for (Node label : FxHarness.lookupAll(child, "graph-message")) {
                    String text = ((javafx.scene.control.Label) label).getText();
                    if (text != null && !text.isBlank()) {
                        // The fade sits on the message box holding the subject, not on
                        // the row: the lanes and chips stay bright so the graph is still
                        // readable while unrelated history recedes.
                        Node box = label.getParent();
                        faded.put(text, box != null
                                && box.getStyleClass().contains("graph-message-faded"));
                    }
                }
            }
        }
        return faded;
    }

    private static boolean onScreen(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Clicks the row whose message is {@code subject}. */
    private void clickCommit(String subject) {
        Node row = lookup(subject).query();
        clickOn(row);
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------

    @Test
    void nothingIsFadedBeforeAnythingIsTouched() {
        assertTrue(fadedBySubject().values().stream().noneMatch(Boolean::booleanValue),
                "an untouched graph must be fully legible: " + fadedBySubject());
    }

    @Test
    void selectingACommitFadesNothing() {
        clickCommit("trunk mid");

        Map<String, Boolean> faded = fadedBySubject();
        assertTrue(faded.values().stream().noneMatch(Boolean::booleanValue),
                "clicking a commit is navigation, not a question about branch "
                        + "membership, and it must leave every other subject readable: "
                        + faded);
    }

    @Test
    void selectingACommitOnASideBranchAlsoFadesNothing() {
        clickCommit("side base");

        Map<String, Boolean> faded = fadedBySubject();
        assertTrue(faded.values().stream().noneMatch(Boolean::booleanValue),
                "including from a commit whose ancestry excludes most of the graph: "
                        + faded);
    }

    /**
     * Moving between commits must not accumulate a fade either.
     *
     * <p>The old behaviour dimmed everything outside the selection's ancestry, so this
     * walked from one branch to another and back. It now asserts the opposite, which is
     * the regression that matters: something is always selected, so a fade driven by
     * selection is a fade the user never asked for and cannot turn off.
     */
    @Test
    void movingTheSelectionAroundNeverIntroducesAFade() {
        for (String subject : new String[]{"trunk mid", "side tip", "root", "trunk tip"}) {
            clickCommit(subject);
            Map<String, Boolean> faded = fadedBySubject();
            assertTrue(faded.values().stream().noneMatch(Boolean::booleanValue),
                    "selecting " + subject + " faded something: " + faded);
        }
    }

    /**
     * A commit that is not on the checked-out branch is drawn like any other.
     *
     * <p>Off-branch commits used to be painted at 0.55 opacity and italic. In a
     * repository with real branching most commits are not on the current branch, so that
     * left most of the graph dimmed in its resting state - which is what made the
     * hover fade unreadable, since almost everything was already faded before the
     * pointer arrived.
     */
    @Test
    void aCommitOffTheCurrentBranchIsNotDimmed() {
        Map<String, Boolean> faded = fadedBySubject();
        assertEquals(Boolean.FALSE, faded.get("side tip"),
                "the side branch is not the current one, and is drawn no differently");
        assertEquals(Boolean.FALSE, faded.get("side base"));
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
    }
}
