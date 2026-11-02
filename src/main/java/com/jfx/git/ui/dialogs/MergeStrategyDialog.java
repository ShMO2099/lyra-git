package com.jfx.git.ui.dialogs;


import com.jfx.git.ui.Icons;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

/**
 * Picks a merge strategy before merging a pull/merge request through a provider's own
 * API - GitHub's, Gitea's and Bitbucket's merge endpoints each take one of a few named
 * strategies, unlike a local {@code git merge}, which has no such choice to offer here.
 *
 * <p>Cards rather than a drop-down: the strategies differ in what the base branch's
 * history looks like afterwards, which is the whole decision, so each card says it. The
 * button names the strategy chosen.
 *
 * <p>GitLab has no per-request strategy the way the others do (which of merge,
 * rebase-merge, or fast-forward-only happens is a project-level GitLab setting, not
 * something this API call chooses) - {@code GitLabMergeRequestsView} does not use this
 * class, only a plain "Squash commits" confirmation.
 */
public class MergeStrategyDialog extends Dialog<String> {

    /**
     * One offered strategy: what the user sees, the exact value the provider's API
     * expects, and what choosing it does.
     *
     * @param description what the strategy leaves on the base branch; {@code null} for
     *                    none
     */
    public record Choice(String label, String value, String description) {

        /** A strategy described by what its value means to every provider that has it. */
        public Choice(String label, String value) {
            this(label, value, describe(value));
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public MergeStrategyDialog(String subject, java.util.List<Choice> choices) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Merge");
        JfxDialog.header(this, Icons.pullRequest(), JfxDialog.Tone.NEUTRAL, "Merge " + subject,
                "On the server, with the strategy you choose");
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(this, ButtonType.OK, "Merge");

        DialogParts.OptionCards<Choice> strategies = new DialogParts.OptionCards<>(
                choices, Choice::label, Choice::description);
        getDialogPane().setContent(DialogParts.stack(strategies.node()));

        // The button says the strategy, the way the providers' own merge buttons do:
        // "Squash and merge", "Rebase and merge".
        Button confirm = (Button) getDialogPane().lookupButton(ButtonType.OK);
        strategies.selectedProperty().addListener((obs, old, now) -> {
            confirm.setDisable(now == null);
            confirm.setText(now == null ? "Merge" : now.label());
        });
        if (!choices.isEmpty()) {
            strategies.select(choices.get(0));
        }

        setResultConverter(button -> button == ButtonType.OK && strategies.selected() != null
                ? strategies.selected().value()
                : null);
    }

    /**
     * What a provider's strategy value does. The values mean the same on every provider
     * that offers them - GitHub's {@code rebase} and Gitea's are both "replay, no merge
     * commit" - so one description serves each; a value not known here gets none.
     */
    static String describe(String value) {
        if (value == null) return null;
        return switch (value) {
            case "merge", "merge_commit" ->
                    "Keeps every commit as it is and joins the two histories with a merge commit.";
            case "squash" -> "Combines the request's commits into one new commit on the base branch.";
            case "rebase" -> "Replays each commit on top of the base branch, one after another, "
                    + "with no merge commit.";
            case "rebase-merge" -> "Replays the commits on top of the base branch, then joins them "
                    + "with a merge commit.";
            case "fast-forward-only", "fast_forward" -> "Moves the base branch up to the request's last "
                    + "commit. Refused when the base has commits the request does not.";
            default -> null;
        };
    }
}
