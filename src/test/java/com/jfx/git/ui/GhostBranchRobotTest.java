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
import javafx.stage.Stage;

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
 * Hovering a commit says which branch it is part of.
 *
 * <p>{@code GhostBranchesTest} decides which branch that is; this decides whether the
 * pointer actually produces the label, and whether it goes away again.
 */
class GhostBranchRobotTest extends ApplicationTest {

    private RepoTab tab;

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

    private static CommitInfo commit(String hash, String subject, List<RefInfo> refs,
                                     String... parents) {
        return new CommitInfo(hash, subject, "Tester", "t@example.com",
                Instant.now(), List.of(parents), refs);
    }

    /** A trunk with a tip, and a side branch with its own, so "nearest" has a meaning. */
    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>(List.of(
                commit("aaaaaaaa1", "trunk tip",
                        List.of(new RefInfo("refs/heads/main", "main", "")), "aaaaaaaa2"),
                commit("bbbbbbbb1", "side tip",
                        List.of(new RefInfo("refs/heads/feature", "feature", "")), "bbbbbbbb2"),
                commit("aaaaaaaa2", "trunk mid", List.of(), "cccccccc1"),
                commit("bbbbbbbb2", "side base", List.of(), "cccccccc1"),
                commit("cccccccc1", "the fork", List.of())));
        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) {
            rows.add(new GitGraphRow(c));
        }
        return rows;
    }

    @Override
    public void init() {
        com.jfx.git.core.AppPreferences.setGraphDensity(com.jfx.git.graph.GraphDensity.COMPACT);
        com.jfx.git.graph.GraphColumn.resetToDefault();
    }

    @Override
    public void start(Stage stage) {
        tab = new RepoTab(new LyraApp(), "test", stubBackend());
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        tab.showRows(rows());
    }

    /** The ghost label currently on screen, or null when none is shown. */
    private String visibleGhost() {
        WaitForAsyncUtils.waitForFxEvents();
        for (Node node : lookup(".ref-chip-ghost").queryAll()) {
            if (node instanceof Label label && label.isVisible() && label.isManaged()) {
                return label.getText();
            }
        }
        return null;
    }

    private void hover(String subject) {
        Node row = lookup(subject).query();
        moveTo(row);
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------

    @Test
    void nothingIsLabelledUntilSomethingIsHovered() {
        assertNull(visibleGhost(), "the rail should be quiet until the pointer asks");
    }

    @Test
    void hoveringAnUnlabelledCommitNamesItsNearestBranch() {
        hover("trunk mid");
        assertEquals("main", visibleGhost());
    }

    @Test
    void theOtherBranchIsNamedForItsOwnCommits() {
        hover("side base");
        assertEquals("feature", visibleGhost(),
                "feature's tip is nearer to this commit than main's");
    }

    @Test
    void movingAwayTakesTheLabelWithIt() {
        hover("trunk mid");
        assertNotNull(visibleGhost());

        // Onto the header, which is outside every row.
        Node header = lookup(".graph-header").query();
        moveTo(header);
        WaitForAsyncUtils.waitForFxEvents();

        assertNull(visibleGhost(), "a label left behind would be pointing at nothing");
    }

    /**
     * A tip already wears a real chip. Printing the same name again beside it would read
     * as two branches meeting at that commit.
     */
    @Test
    void aCommitThatAlreadyHasAChipGetsNoGhost() {
        hover("trunk tip");
        assertNull(visibleGhost());
    }

    @Test
    void movingBetweenRowsUpdatesTheLabelRatherThanKeepingTheOldOne() {
        hover("trunk mid");
        assertEquals("main", visibleGhost());

        hover("side base");
        assertEquals("feature", visibleGhost(),
                "a recycled row must not keep the previous row's branch");
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
    }
}
