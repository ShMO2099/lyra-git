package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.FxHarness;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compose form {@code PrReviewView}'s "Add review comment…" action opens. A
 * suggestion is not a distinct field on any provider's create-comment call - it is a
 * <code>```suggestion</code> fenced block folded into the ordinary body text - so what
 * this dialog returns is already the final, ready-to-post string, not a draft record.
 */
class ReviewCommentComposeDialogTest {

    private ReviewCommentComposeDialog dialog(boolean supportsSuggestions) {
        return FxHarness.call(() -> {
            ReviewCommentComposeDialog d = new ReviewCommentComposeDialog(supportsSuggestions);
            d.getDialogPane().applyCss();
            d.getDialogPane().layout();
            return d;
        });
    }

    private Node okButton(ReviewCommentComposeDialog dialog) {
        return dialog.getDialogPane().lookupButton(ButtonType.OK);
    }

    private TextArea bodyField(ReviewCommentComposeDialog dialog) {
        return (TextArea) FxHarness.lookupAll(dialog.getDialogPane(), "review-comment-body").get(0);
    }

    private CheckBox suggestToggle(ReviewCommentComposeDialog dialog) {
        return (CheckBox) FxHarness.lookupAll(dialog.getDialogPane(), "review-comment-suggest-toggle").get(0);
    }

    private TextArea suggestionField(ReviewCommentComposeDialog dialog) {
        return (TextArea) FxHarness.lookupAll(dialog.getDialogPane(), "review-comment-suggestion").get(0);
    }

    @Test
    void okIsDisabledUntilThereIsSomethingToPost() {
        ReviewCommentComposeDialog dialog = dialog(true);
        FxHarness.run(() -> {
            assertTrue(okButton(dialog).isDisabled());
            bodyField(dialog).setText("looks good");
            assertFalse(okButton(dialog).isDisabled());
        });
    }

    @Test
    void aSuggestionAloneWithNoLeadInTextIsEnoughToPost() {
        ReviewCommentComposeDialog dialog = dialog(true);
        FxHarness.run(() -> {
            suggestToggle(dialog).setSelected(true);
            assertTrue(okButton(dialog).isDisabled(), "checking the box alone with nothing typed is not enough");
            suggestionField(dialog).setText("fixed line");
            assertFalse(okButton(dialog).isDisabled());
        });
    }

    /** The suggestion is code: its area is there only once asked for, and set in monospace. */
    @Test
    void theSuggestionAreaAppearsWithTheToggleInMonospace() {
        ReviewCommentComposeDialog dialog = dialog(true);
        FxHarness.run(() -> {
            assertFalse(shown(suggestionField(dialog)), "no suggestion area until one is asked for");
            suggestToggle(dialog).setSelected(true);
            assertTrue(shown(suggestionField(dialog)));
            assertTrue(suggestionField(dialog).getStyleClass().contains("t-mono"));
        });
    }

    /** Whether {@code node} and everything it sits in is visible. */
    private static boolean shown(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    @Test
    void theSuggestionToggleIsDisabledWhenTheProviderDoesNotSupportOne() {
        ReviewCommentComposeDialog dialog = dialog(false);
        FxHarness.run(() -> assertTrue(suggestToggle(dialog).isDisabled()));
    }

    @Test
    void confirmingAPlainCommentReturnsItsTrimmedTextUnchanged() {
        ReviewCommentComposeDialog dialog = dialog(true);
        String result = FxHarness.call(() -> {
            bodyField(dialog).setText("  looks good overall  ");
            return dialog.getResultConverter().call(ButtonType.OK);
        });

        assertEquals("looks good overall", result);
    }

    @Test
    void confirmingASuggestionWrapsItInAFencedSuggestionBlock() {
        ReviewCommentComposeDialog dialog = dialog(true);
        String result = FxHarness.call(() -> {
            suggestToggle(dialog).setSelected(true);
            suggestionField(dialog).setText("fixed line");
            return dialog.getResultConverter().call(ButtonType.OK);
        });

        assertEquals("```suggestion\nfixed line\n```", result);
    }

    @Test
    void confirmingBothLeadInTextAndASuggestionJoinsThemWithABlankLine() {
        ReviewCommentComposeDialog dialog = dialog(true);
        String result = FxHarness.call(() -> {
            bodyField(dialog).setText("this reads better as:");
            suggestToggle(dialog).setSelected(true);
            suggestionField(dialog).setText("fixed line");
            return dialog.getResultConverter().call(ButtonType.OK);
        });

        assertEquals("this reads better as:\n\n```suggestion\nfixed line\n```", result);
    }

    @Test
    void cancellingReturnsNull() {
        ReviewCommentComposeDialog dialog = dialog(true);
        String result = FxHarness.call(() -> {
            bodyField(dialog).setText("looks good");
            return dialog.getResultConverter().call(ButtonType.CANCEL);
        });

        assertNull(result);
    }
}
