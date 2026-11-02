package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.LaneAssigner;
import com.jfx.git.model.CommitInfo;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Catches a one-frame flash: a row painted as selected while showing a commit that is not.
 *
 * <h2>How a transient can be tested at all</h2>
 * The reported symptom is a row that lights up as though selected and goes out again
 * while the selection moves between two other commits. It is gone by the time any
 * assertion after the click could look, so this samples every frame instead: an
 * {@link AnimationTimer} runs for the duration of the interaction and records any cell
 * whose {@code selected} state disagrees with the item it is displaying. One bad frame is
 * enough to fail.
 *
 * <p>The invariant is simple and should hold at every instant, not merely at rest: a cell
 * drawn selected must be showing a commit the selection model actually contains.
 */
class SelectionFlashRobotTest extends ApplicationTest {

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
     * Two branches that fork from a shared root and never rejoin.
     *
     * <p>The topology is the point. A linear chain cannot express this bug at all: every
     * commit is an ancestor or a descendant of every other, so nothing is ever unrelated
     * and nothing ever fades — a detector run against one is guaranteed to find nothing
     * whatever the code does. Interleaving two independent branches means clicking from
     * one to the other changes the fade of most rows on screen, which is precisely the
     * gesture that flashes.
     */
    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String left = i == 7 ? "root00000" : String.format("L%08d", i + 1);
            String right = i == 7 ? "root00000" : String.format("R%08d", i + 1);
            // Interleaved, so both branches are on screen together.
            commits.add(commit(String.format("L%08d", i), "left " + i, left));
            commits.add(commit(String.format("R%08d", i), "right " + i, right));
        }
        commits.add(commit("root00000", "the root"));

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
        stage.setScene(new Scene(pane, 1200, 900));
        stage.show();
        tab.showRows(rows());
    }

    @AfterEach
    void closeTab() {
        if (tab != null) {
            interact(() -> tab.close());
        }
    }

    /** Every realised graph cell, selected state included. */
    private List<ListCell<GitGraphRow>> cells() {
        List<ListCell<GitGraphRow>> found = new ArrayList<>();
        for (Node node : lookup(".list-cell").queryAll()) {
            if (node instanceof ListCell<?> cell && cell.getListView() == tab.graphList()) {
                @SuppressWarnings("unchecked")
                ListCell<GitGraphRow> typed = (ListCell<GitGraphRow>) cell;
                found.add(typed);
            }
        }
        return found;
    }

    /**
     * Runs {@code interaction}, watching every frame for a cell drawn selected while
     * showing an unselected commit.
     *
     * @return one description per bad frame, empty when the paint never lied
     */
    private List<String> sampleWhile(Runnable interaction) {
        List<String> violations = new CopyOnWriteArrayList<>();
        int[] frames = {0};

        AnimationTimer sampler = new AnimationTimer() {
            @Override
            public void handle(long now) {
                frames[0]++;
                Set<GitGraphRow> selected =
                        Set.copyOf(tab.graphList().getSelectionModel().getSelectedItems());

                for (ListCell<GitGraphRow> cell : cells()) {
                    GitGraphRow item = cell.getItem();
                    if (item == null || item.getCommit() == null) {
                        continue;
                    }
                    String subject = item.getCommit().getSubject();

                    if (cell.isSelected() && !selected.contains(item)) {
                        violations.add("painted selected while showing " + subject);
                    }

                    // Moving the selection must not fade anything, in any frame. This
                    // used to compare against the ancestry the selection implied; the
                    // selection no longer drives the fade at all, so the expectation is
                    // simply that no frame ever dims a subject. Sampled per frame rather
                    // than after settling because the failure this class exists for was
                    // a single wrong frame, which a settled assertion cannot see.
                    if (!FxHarness.lookupAll(cell, "graph-message-faded").isEmpty()) {
                        violations.add("faded while only the selection moved: " + subject);
                    }
                }
            }
        };

        interact(sampler::start);
        try {
            interaction.run();
            WaitForAsyncUtils.waitForFxEvents();
        } finally {
            interact(sampler::stop);
        }
        System.out.println("SAMPLER frames=" + frames[0] + " violations=" + violations.size());
        return violations;
    }

    private void clickCommit(String subject) {
        Node row = lookup(subject).query();
        clickOn(row);
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------

    @Test
    void movingTheSelectionNeverPaintsAnUnselectedCommitAsSelected() {
        // Walk the selection around, which is the gesture that produced the flash.
        List<String> violations = sampleWhile(() -> {
            clickCommit("left 0");
            clickCommit("right 0");
            clickCommit("left 2");
            clickCommit("right 3");
            clickCommit("left 1");
        });

        assertEquals(Collections.<String>emptyList(), violations,
                "a row must never be drawn selected while showing a commit that is not "
                        + "selected, not even for one frame");
    }
}
