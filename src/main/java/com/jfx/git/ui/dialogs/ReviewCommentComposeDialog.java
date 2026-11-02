package com.jfx.git.ui.dialogs;


import com.jfx.git.ui.Icons;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;

/**
 * Composes a review comment for one diff line - shared by all three providers, since
 * the shape is identical: a body, and an optional "suggested change" that GitHub and
 * GitLab both render from a <code>```suggestion</code> fenced markdown block inside
 * the comment body itself, not a separate API field. Bitbucket has no equivalent
 * syntax at all, so the checkbox is disabled outright rather than offered and ignored,
 * and its description says why.
 *
 * <p>Returns the final, already-composed body text ready to post - not a draft record
 * with separate fields - since a suggestion is not a distinct thing on the wire, only
 * markdown folded into the one field every provider's create-comment call already
 * takes.
 *
 * <p>The suggestion is code - the line as it should read - so it is typed in monospace
 * and not wrapped, and the area is only there once "Suggest a change" is ticked.
 */
public class ReviewCommentComposeDialog extends Dialog<String> {

    public ReviewCommentComposeDialog(boolean supportsSuggestions) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Add review comment");
        JfxDialog.header(this, Icons.comment(), JfxDialog.Tone.NEUTRAL, "Add a review comment",
                "On the line you chose, posted with your review");
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(this, ButtonType.OK, "Add comment");

        TextArea body = new TextArea();
        body.setPromptText("Could this return early instead?");
        body.setPrefRowCount(4);
        body.setWrapText(true);
        body.getStyleClass().add("review-comment-body");
        DialogParts.Field bodyField = DialogParts.field("Comment", body);

        CheckBox suggest = new CheckBox("Suggest a change");
        suggest.getStyleClass().add("review-comment-suggest-toggle");
        suggest.setDisable(!supportsSuggestions);
        Node suggestRow = DialogParts.toggle(suggest, supportsSuggestions
                ? "Proposes new text for the line, which the author can accept from the review."
                : "This provider has no suggested-change syntax to show one with.");

        TextArea suggestion = new TextArea();
        suggestion.setPromptText("return total.setScale(2, RoundingMode.HALF_EVEN);");
        suggestion.setPrefRowCount(3);
        suggestion.setWrapText(false);
        suggestion.getStyleClass().addAll("review-comment-suggestion", "t-mono");
        DialogParts.Field suggestionField = DialogParts.field("Suggested change", suggestion)
                .hint("Replaces the line you chose, and may run to several lines.");
        Node suggestionNode = suggestionField.node();
        suggestionNode.visibleProperty().bind(suggest.selectedProperty());
        suggestionNode.managedProperty().bind(suggest.selectedProperty());

        getDialogPane().setContent(DialogParts.stack(bodyField, suggestRow, suggestionField));

        // Either a plain comment or a suggestion is enough to post - the same
        // "unreachable rather than merely discouraged" standard CreatePullRequestDialog
        // holds its own confirm button to, not merely a soft nudge.
        var ok = getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> {
            boolean hasBody = !body.getText().isBlank();
            boolean hasSuggestion = suggest.isSelected() && !suggestion.getText().isBlank();
            ok.setDisable(!hasBody && !hasSuggestion);
        };
        body.textProperty().addListener((o, old, v) -> validate.run());
        suggestion.textProperty().addListener((o, old, v) -> validate.run());
        suggest.selectedProperty().addListener((o, old, v) -> {
            validate.run();
            // The area appears under the toggle: the window grows to it, and the caret
            // goes there, since a suggestion is what ticking the box was for.
            JfxDialog.refit(this);
            if (v) suggestion.requestFocus();
        });
        validate.run();

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            String composed = body.getText().trim();
            if (suggest.isSelected() && !suggestion.getText().isBlank()) {
                String block = "```suggestion\n" + suggestion.getText().stripTrailing() + "\n```";
                composed = composed.isBlank() ? block : composed + "\n\n" + block;
            }
            return composed.isBlank() ? null : composed;
        });
    }
}
