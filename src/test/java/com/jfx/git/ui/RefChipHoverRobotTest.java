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
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hovered ref chip says its name once.
 *
 * <h2>What was wrong</h2>
 * Every chip carried both a lifted overlay and a tooltip, fired from the same hover. For a
 * remote chip the two were byte-identical - the tooltip text was the branch name and
 * nothing else - so the same string appeared twice, once over the row and once under the
 * pointer. For a chip whose name already fitted, the overlay drew the chip again on top of
 * itself. And for the "+N" chip the redundancy ran the other way: the overlay showed
 * "+2", which says nothing, while its tooltip was the only way to see what was hidden.
 *
 * <p>So the rule is one rendering per chip, and which mechanism carries it depends on
 * whether the chip has a name to expand.
 */
class RefChipHoverRobotTest extends ApplicationTest {

    private RepoTab tab;

    @AfterEach
    void tidyUp() {
        if (tab != null) interact(tab::close);
    }

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

    private static RefInfo remote(String name, String hash) {
        return new RefInfo("refs/remotes/" + name, name, hash);
    }

    private static RefInfo local(String name, String hash) {
        return new RefInfo("refs/heads/" + name, name, hash);
    }

    /**
     * One commit with a very long remote name (so the rail must truncate it), one with a
     * short local name (so it fits), and one carrying enough refs to overflow.
     */
    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>(List.of(
                new CommitInfo("aaaaaaaa1", "long remote", "T", "t@example.com",
                        Instant.now(), List.of("aaaaaaaa2"),
                        List.of(remote("origin/wentao-reduce-additional-clone-kimi",
                                "aaaaaaaa1"))),
                new CommitInfo("aaaaaaaa2", "short local", "T", "t@example.com",
                        Instant.now(), List.of("aaaaaaaa3"), List.of(local("dev", "aaaaaaaa2"))),
                new CommitInfo("aaaaaaaa3", "many refs", "T", "t@example.com",
                        Instant.now(), List.of(),
                        List.of(local("one", "aaaaaaaa3"), local("two", "aaaaaaaa3"),
                                local("three", "aaaaaaaa3"), local("four", "aaaaaaaa3"),
                                local("five", "aaaaaaaa3"), local("six", "aaaaaaaa3")))));
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
        stage.setScene(new Scene(pane, 1000, 500));
        stage.show();
        tab.showRows(rows());
    }

    /** The chip whose text contains {@code fragment}. */
    private Label chip(String fragment) {
        WaitForAsyncUtils.waitForFxEvents();
        List<String> seen = new ArrayList<>();
        for (Node node : lookup(".ref-chip").queryAll()) {
            if (!(node instanceof Label label) || !label.isVisible()) {
                continue;
            }
            String text = label.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            seen.add(text);
            if (text.contains(fragment)) {
                return label;
            }
        }
        return fail("no chip containing '" + fragment + "'; saw " + seen);
    }

    /** The lifted overlay, if it is currently showing something. */
    private Label visibleOverlay() {
        WaitForAsyncUtils.waitForFxEvents();
        for (Node node : lookup(".ref-chip-expanded").queryAll()) {
            if (node instanceof Label label && label.isVisible()) {
                return label;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------

    /** Reads whatever tooltip is installed on a node, or null. */
    private static Tooltip tooltipOf(Node node) {
        Object property = node.getProperties().get("javafx.scene.control.Tooltip");
        return property instanceof Tooltip tip ? tip : null;
    }

    @Test
    void aTruncatedNameIsExpandedOnceAndNotAlsoTooltipped() {
        Label longRemote = chip("wentao");
        assertNull(tooltipOf(longRemote),
                "the overlay already shows the full name; a tooltip would repeat it");

        moveTo(longRemote);
        Label overlay = visibleOverlay();

        assertNotNull(overlay, "a truncated chip must be expanded on hover");
        assertEquals(longRemote.getText(), overlay.getText(),
                "the overlay is the same chip, shown in full");
    }

    @Test
    void aNameThatAlreadyFitsIsNotDrawnTwice() {
        Label shortLocal = chip("dev");
        assertTrue(shortLocal.getWidth() >= shortLocal.prefWidth(-1) - 0.5,
                "sanity: this chip is not truncated");

        moveTo(shortLocal);

        assertNull(visibleOverlay(),
                "the chip is fully readable where it is, so lifting a copy of it over "
                        + "the row shows the same text twice for no reason");
    }

    /**
     * The overflow chip is the one case where the tooltip is the useful half: its own
     * text is "+2", and what a reader wants is the names it stands for.
     */
    @Test
    void theOverflowChipKeepsItsTooltipAndGetsNoOverlay() {
        Label overflow = chip("+");
        assertTrue(overflow.getText().startsWith("+"), "sanity: " + overflow.getText());

        Tooltip tip = tooltipOf(overflow);
        assertNotNull(tip, "the hidden refs are only readable through this");
        assertFalse(tip.getText().isBlank());
        assertTrue(tip.getText().contains("\n") || !tip.getText().equals(overflow.getText()),
                "it lists what is hidden rather than repeating '+N': " + tip.getText());

        moveTo(overflow);
        assertNull(visibleOverlay(),
                "expanding '+2' into '+2' tells the reader nothing");
    }
}
