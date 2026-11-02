package com.jfx.git.ui;

import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stylesheets actually parse, and their rules actually reach a node.
 *
 * <h2>Why "the class is applied" is not enough</h2>
 * Every other test about the fade asserts that {@code graph-message-faded} is on the right
 * node. That assertion holds whether or not any stylesheet defines the class - a style
 * class is just a string on a list. A comment left unterminated in {@code lyra.css} once
 * made the parser abandon the rest of the file, so the fade rule never loaded and nothing
 * dimmed on screen, while every fade test stayed green.
 *
 * <p>So this reads the rendered value instead. It deliberately checks rules from more than
 * one point in the file: a CSS parse error discards everything after it, so a later rule
 * failing while an earlier one passes is the signature of a truncated stylesheet rather
 * than a wrong value.
 */
class StylesheetEffectiveTest {

    /**
     * Builds a node on the FX thread, lays it out in a themed scene, and measures it.
     *
     * <p>The node is built inside the call rather than passed in: constructing a
     * {@code Label} needs the toolkit running, which it is not on the test thread.
     */
    private static <T> T measure(Supplier<javafx.scene.Node> build,
                                 java.util.function.Function<javafx.scene.Node, T> read) {
        return FxHarness.call(() -> {
            javafx.scene.Node node = build.get();
            HBox root = new HBox(node);
            Scene scene = new Scene(new Group(root), 400, 100);
            ThemeManager.register(scene);
            root.applyCss();
            root.layout();
            return read.apply(node);
        });
    }

    @Test
    void theHoverFadeRuleReachesTheMessageBox() {
        double opacity = measure(() -> {
            Label subject = new Label("a commit subject");
            subject.getStyleClass().add("graph-message");
            HBox box = new HBox(subject);
            box.getStyleClass().add("graph-message-faded");
            return box;
        }, javafx.scene.Node::getOpacity);

        assertEquals(0.35, opacity, 0.001,
                "the fade is the whole feature: at 1.0 the rule did not load, and "
                        + "nothing dims on screen no matter what the code does");
    }

    /** And an unfaded row is left alone, so the rule is not applying to everything. */
    @Test
    void anUnfadedMessageBoxKeepsFullOpacity() {
        double opacity = measure(
                () -> new HBox(new Label("a commit subject")),
                javafx.scene.Node::getOpacity);

        assertEquals(1.0, opacity, 0.001, "only the faded class dims; a plain row must not");
    }

    /**
     * A rule defined after the fade rule in the same file.
     *
     * <p>The point is its position, not the rule itself: if the parser gave up partway,
     * an earlier rule can still work while this one silently does not.
     */
    @Test
    void aRuleLaterInTheSameFileAlsoLoads() {
        String family = measure(() -> {
            Label reflog = new Label("HEAD@{2}");
            reflog.getStyleClass().add("reflog-ids");
            return reflog;
        }, node -> ((Label) node).getFont().getFamily());

        String lower = family.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.contains("mono") || lower.contains("consol"),
                "expected the monospace family lyra.css sets for .reflog-ids, got "
                        + family + " - which is what a stylesheet truncated before this "
                        + "rule would leave behind");
    }
}
