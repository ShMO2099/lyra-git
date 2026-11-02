package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.LaneAssigner;
import com.jfx.git.model.CommitInfo;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The subject keeps the message column; the body takes what is left.
 *
 * <h2>What this is guarding</h2>
 * The two are laid out side by side, and a Label's default preferred width is its whole
 * text - so the dimmed body was competing with the subject for the column. A commit
 * carrying a long trailer, and {@code Co-authored-by:} is the common one, squeezed its own
 * subject down to a bare "..." with the dim body filling the row. Read from across the
 * panel that looked like a faded row rather than a truncated one, which is how it got
 * reported as the hover fade misfiring.
 *
 * <p>So the assertion is about widths, not about the fade: the subject label must actually
 * be wide enough to show its text when there is room for it.
 */
class GraphSubjectWidthRobotTest extends ApplicationTest {

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

    private static final String SHORT_SUBJECT = "Fix the parser";

    /**
     * One commit with a long trailer and one without, both with the same short subject,
     * so any difference in how much of the subject is drawn is down to the body alone.
     */
    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>(List.of(
                new CommitInfo("aaaaaaaa1", SHORT_SUBJECT + "\n\n"
                        + "Co-authored-by: Herman S. <42923213+herman@users.noreply.example>\n"
                        + "Co-authored-by: Someone Else <other@example.com>",
                        "Tester", "t@example.com", Instant.now(), List.of("aaaaaaaa2"), List.of()),
                new CommitInfo("aaaaaaaa2", SHORT_SUBJECT,
                        "Tester", "t@example.com", Instant.now(), List.of(), List.of())));
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
        stage.setScene(new Scene(pane, 1400, 640));
        stage.show();
        tab.showRows(rows());
    }

    /** Every visible subject label, with the width it was actually given. */
    private List<Label> subjectLabels() {
        WaitForAsyncUtils.waitForFxEvents();
        List<Label> found = new ArrayList<>();
        for (Node node : lookup(".graph-row").queryAll()) {
            if (!(node instanceof GridPane grid) || !onScreen(grid)) {
                continue;
            }
            for (Node label : FxHarness.lookupAll(grid, "graph-message")) {
                Label subject = (Label) label;
                if (subject.getText() != null && !subject.getText().isBlank()) {
                    found.add(subject);
                }
            }
        }
        return found;
    }

    private static boolean onScreen(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void aLongTrailerDoesNotSqueezeTheSubjectOut() {
        List<Label> subjects = subjectLabels();
        assertEquals(2, subjects.size(), "both commits should be drawn");

        for (Label subject : subjects) {
            assertEquals(SHORT_SUBJECT, subject.getText(),
                    "the subject text itself is always the full string");
            // The label has to be wide enough to render it. A squeezed label still
            // reports its full text and draws "..." - which is exactly why the bug was
            // invisible to any assertion that only read getText().
            double needed = subject.prefWidth(-1);
            assertTrue(subject.getWidth() >= needed - 1.0,
                    "the subject was given " + subject.getWidth() + "px but needs "
                            + needed + "px, so it is drawing an ellipsis where the "
                            + "commit title should be");
        }
    }

    /** Both rows should give the subject the same room; the body must not change it. */
    @Test
    void theBodyDoesNotChangeHowMuchOfTheSubjectIsShown() {
        List<Label> subjects = subjectLabels();
        assertEquals(2, subjects.size());

        double withTrailer = subjects.get(0).getWidth();
        double withoutTrailer = subjects.get(1).getWidth();
        assertEquals(withoutTrailer, withTrailer, 1.0,
                "a commit with a long trailer and one without carry the same subject, "
                        + "so the subject column must come out the same width");
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
    }
}
