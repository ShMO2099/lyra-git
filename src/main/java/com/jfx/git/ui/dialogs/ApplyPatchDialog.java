package com.jfx.git.ui.dialogs;

import com.jfx.git.model.PatchApplyMode;
import com.jfx.git.ui.Icons;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.List;

/**
 * Asks where an applied patch should land: as commits, staged, or only in the working
 * tree.
 *
 * <p>Opens on the choice the file itself suggests. A patch saved from a commit carries
 * the author and the message, and the usual reason to have one is to get that commit;
 * a bare diff has neither, so it can only become uncommitted changes, and "as commits"
 * is then shown but not offered - with the reason on its card, rather than left to be
 * discovered as an error from git.
 *
 * <p>Cards, each saying what choosing it does, because the three differ in what is left
 * behind - commits, staged changes, or edits to look at - and the button says which.
 */
public class ApplyPatchDialog extends Dialog<PatchApplyMode> {

    /**
     * @param fileName       the patch's file name, for the heading
     * @param carriesCommits whether the file is in mail form and so can become commits
     */
    public ApplyPatchDialog(String fileName, boolean carriesCommits) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Apply patch");
        JfxDialog.header(this, Icons.file(), JfxDialog.Tone.NEUTRAL, "Apply a patch",
                "`" + fileName + "`");
        ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        DialogParts.OptionCards<PatchApplyMode> modes = new DialogParts.OptionCards<>(
                List.of(PatchApplyMode.AM, PatchApplyMode.INDEX, PatchApplyMode.WORKTREE),
                ApplyPatchDialog::nameOf, mode -> describe(mode, carriesCommits));
        for (PatchApplyMode mode : PatchApplyMode.values()) {
            modes.radioFor(mode).setId(idOf(mode));
        }
        if (!carriesCommits) {
            modes.radioFor(PatchApplyMode.AM).setDisable(true);
        }
        getDialogPane().setContent(DialogParts.stack(modes.node()));

        Button confirm = (Button) getDialogPane().lookupButton(apply);
        modes.selectedProperty().addListener((obs, old, now) -> {
            confirm.setDisable(now == null);
            if (now != null) confirm.setText(verbOf(now));
        });
        modes.select(carriesCommits ? PatchApplyMode.AM : PatchApplyMode.INDEX);

        setResultConverter(button -> button == apply ? modes.selected() : null);
    }

    /** The id of the radio button for {@code mode}, for tests. */
    public static String idOf(PatchApplyMode mode) {
        return "apply-patch-" + mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String nameOf(PatchApplyMode mode) {
        return switch (mode) {
            case AM -> "As commits";
            case INDEX -> "Staged";
            case WORKTREE -> "In the working tree only";
        };
    }

    /** What choosing {@code mode} does; for commits, why it cannot when the file has none. */
    private static String describe(PatchApplyMode mode, boolean carriesCommits) {
        return switch (mode) {
            case AM -> carriesCommits
                    ? "Each patch in the file becomes a commit again, with its original author, date and message."
                    : "Not for this file: it is a plain diff with no author or message, so it cannot "
                            + "become a commit by itself.";
            case INDEX -> "The changes are written and staged, ready to commit under your own name and message.";
            case WORKTREE -> "The changes are written to the files and left unstaged, to look at before "
                    + "deciding anything.";
        };
    }

    /** The button, for the card chosen. */
    static String verbOf(PatchApplyMode mode) {
        return switch (mode) {
            case AM -> "Apply as commits";
            case INDEX -> "Apply and stage";
            case WORKTREE -> "Apply to working tree";
        };
    }
}
