package com.jfx.git.ui;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The message editor every place that writes a commit message shares with the commit box:
 * git's summary/body split and the 72-character countdown.
 */
class MessageEditorTest {

    @Test
    void joinAndSplitAreInverses() {
        assertEquals("Fix rounding\n\nWhy: banker's rounding.", MessageEditor.join("Fix rounding ", "\nWhy: banker's rounding.\n"));
        assertEquals("Fix rounding", MessageEditor.join("Fix rounding", "  "));
        assertEquals("Only a body", MessageEditor.join("", "Only a body"));

        MessageEditor.Parts parts = MessageEditor.split("Fix rounding\n\nWhy: banker's rounding.\nSecond line.");
        assertEquals("Fix rounding", parts.summary());
        assertEquals("Why: banker's rounding.\nSecond line.", parts.description());
        assertEquals(new MessageEditor.Parts("One line", ""), MessageEditor.split("One line"));
        assertEquals(new MessageEditor.Parts("Subject", "body"), MessageEditor.split("Subject\nbody"),
                "a single newline is tolerated: the first line stays the summary");
    }

    @Test
    void theCounterCountsDownAndTurnsAmberThenRed() {
        FxHarness.run(() -> {
            TextField summary = new TextField();
            Label counter = MessageEditor.counter(summary);
            assertEquals("72", counter.getText());
            summary.setText("x".repeat(51));
            assertEquals("21", counter.getText());
            assertTrue(counter.getStyleClass().contains("composer-counter-warn"));
            summary.setText("x".repeat(80));
            assertEquals("-8", counter.getText());
            assertTrue(counter.getStyleClass().contains("composer-counter-over"));
            assertFalse(counter.getStyleClass().contains("composer-counter-warn"));
        });
    }

    @Test
    void theEditorOpensOnAMessageSplitAndGivesItBackJoined() {
        FxHarness.run(() -> {
            MessageEditor editor = new MessageEditor("add b\n\nWhy b: a body that has to survive.");
            assertEquals("add b", editor.summary().getText());
            assertEquals("Why b: a body that has to survive.", editor.description().getText());
            int[] changes = {0};
            editor.onChange(() -> changes[0]++);
            editor.summary().setText("add b, properly");
            assertEquals("add b, properly\n\nWhy b: a body that has to survive.", editor.message());
            assertEquals(1, changes[0]);
            assertNotNull(editor.node().lookup(".composer-counter"), "the summary carries the countdown");
        });
    }

    /** A co-author goes under the description as a trailer, once, as the commit box adds it. */
    @Test
    void aTrailerJoinsTheDescriptionOnce() {
        FxHarness.run(() -> {
            MessageEditor editor = new MessageEditor("Fix rounding\n\nRefunds were a cent short.");
            editor.addTrailer("Co-authored-by", "Grace Hopper <grace@example.com>");
            editor.addTrailer("Co-authored-by", "Grace Hopper <grace@example.com>");
            assertEquals("Fix rounding\n\nRefunds were a cent short.\n\n"
                    + "Co-authored-by: Grace Hopper <grace@example.com>", editor.message());
        });
    }

    /** Reword offers "Add co-author…" at the left of its footer when it has a repository to read. */
    @Test
    void rewordOffersACoAuthorInItsFooter() {
        FxHarness.run(() -> {
            var plain = new com.jfx.git.ui.dialogs.RewordDialog("a64205a", "Fix typos", "Only the message.");
            plain.getDialogPane().applyCss();
            assertTrue(FxHarness.lookupAll(plain.getDialogPane(), "reword-co-author").isEmpty(),
                    "with nothing to read recent authors from, it is not offered");

            var offered = new com.jfx.git.ui.dialogs.RewordDialog("a64205a", "Fix typos", "Only the message.")
                    .offerCoAuthors(null, message -> { });
            offered.getDialogPane().applyCss();
            offered.getDialogPane().layout();
            var buttons = FxHarness.lookupAll(offered.getDialogPane(), "reword-co-author");
            assertEquals(1, buttons.size());
            assertEquals("Add co-author…", ((javafx.scene.control.Button) buttons.get(0)).getText());
            assertTrue(javafx.scene.control.ButtonBar.getButtonData(buttons.get(0))
                    == javafx.scene.control.ButtonBar.ButtonData.LEFT, "at the left of the footer");
        });
    }
}
