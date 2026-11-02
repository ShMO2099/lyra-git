package com.jfx.git.ui.dialogs;

import com.jfx.git.model.MergeMode;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.MessageEditor;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

/**
 * How to merge one branch into the current one: the mode, and optionally the message.
 *
 * <h2>Why a dialog</h2>
 * "Merge into main" only ever did what {@code merge.ff} said - usually a fast-forward -
 * and the three things people reach for the command line to get instead ({@code
 * --ff-only}, {@code --no-ff}, {@code --squash}) plus a message of their own had no way
 * in. The one-click item stays next to this for the common case; this is the item with
 * the ellipsis.
 *
 * <p>Cards rather than a drop-down: there are four choices and the difference between
 * them is the point, so all four are on screen at once, each saying what it does.
 */
public final class MergeDialog extends Dialog<MergeDialog.Choice> {

    /**
     * What the user chose.
     *
     * @param message the text typed, blank when none; {@link #messageOrNull()} is what
     *                goes to the backend
     */
    public record Choice(MergeMode mode, String message) {
        /** {@code null} rather than blank, or when the mode makes no commit to put it on. */
        public String messageOrNull() {
            return message == null || message.isBlank() || !mode.takesMessage() ? null : message.strip();
        }
    }

    /** The confirm button, relabelled "Squash" when that is what it will do. */
    private static final ButtonType MERGE = new ButtonType("Merge", ButtonBar.ButtonData.OK_DONE);

    /**
     * @param source the branch being merged in
     * @param target the branch it goes into; blank when HEAD is detached
     */
    public MergeDialog(String source, String target) {
        String into = target == null || target.isBlank() ? "the current commit" : target;
        JfxDialog.shell(this, JfxDialog.Size.MD, "Merge");
        JfxDialog.header(this, Icons.merge(), JfxDialog.Tone.NEUTRAL, "Merge " + source + " into " + into,
                target == null || target.isBlank()
                        ? "HEAD is detached, so the result lands on the current commit"
                        : "`" + target + "` is checked out, so the result lands there").ruled();
        getDialogPane().getButtonTypes().addAll(MERGE, ButtonType.CANCEL);

        DialogParts.OptionCards<MergeMode> modes = new DialogParts.OptionCards<>(
                java.util.List.of(MergeMode.values()), MergeMode::label, MergeMode::explanation).twoColumns();
        for (MergeMode mode : MergeMode.values()) {
            modes.radioFor(mode).getStyleClass().add("merge-mode-" + mode.name().toLowerCase(java.util.Locale.ROOT));
        }

        // The commit box's shape, as every message is written here: a summary over a
        // description, so a merge's body is no longer one undifferentiated box.
        MessageEditor message = new MessageEditor(null).rows(3).optional()
                .hint("Left blank, the merge commit gets git's own \"Merge branch\" message; a squash "
                        + "gets \"Squash merge branch\" above the list of the commits it combines.");
        message.summary().getStyleClass().add("merge-message");
        getDialogPane().setContent(DialogParts.stack(modes.node(), message.node()));

        Button confirm = (Button) getDialogPane().lookupButton(MERGE);
        modes.selectedProperty().addListener((_, _, now) -> {
            MergeMode mode = now == null ? MergeMode.DEFAULT : now;
            message.setDisable(!mode.takesMessage());
            message.placeholder(!mode.takesMessage()
                    ? "A fast-forward makes no commit to put a message on"
                    : mode == MergeMode.SQUASH
                            ? "Squash merge branch '" + source + "'"
                            : "Merge branch '" + source + "'");
            confirm.setText(mode == MergeMode.SQUASH ? "Squash" : "Merge");
        });
        modes.select(MergeMode.DEFAULT);

        setResultConverter(button -> {
            if (button != MERGE || modes.selected() == null) return null;
            return new Choice(modes.selected(), message.message());
        });
    }
}
