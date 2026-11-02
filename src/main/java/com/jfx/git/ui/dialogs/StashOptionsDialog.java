package com.jfx.git.ui.dialogs;

import com.jfx.git.model.StashOptions;
import com.jfx.git.ui.Icons;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;

import java.util.List;

/**
 * A stash of the whole working tree with its options: a name, whether untracked files go
 * too ({@code -u}, on by default as the toolbar button has always done), and whether what
 * is staged stays staged ({@code --keep-index}).
 *
 * <p>The toolbar's Stash button keeps its one click; this is its drop-down item. Stashing
 * only some files starts from the staging panel instead, where the files are selected -
 * the stash half of the composer has that option.
 */
public final class StashOptionsDialog extends Dialog<StashOptions> {

    private static final ButtonType STASH = new ButtonType("Stash", ButtonBar.ButtonData.OK_DONE);

    public StashOptionsDialog() {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Stash");
        JfxDialog.header(this, Icons.stash(), JfxDialog.Tone.NEUTRAL, "Stash changes",
                "Set the working tree's changes aside to bring back later");
        getDialogPane().getButtonTypes().addAll(STASH, ButtonType.CANCEL);

        TextField name = new TextField();
        name.getStyleClass().add("stash-options-name");
        name.setPromptText("Half-done checkout form");
        DialogParts.Field nameField = DialogParts.field("Name", name).optional()
                .hint("Left blank, git names it \"WIP on <branch>\", followed by the commit it was taken on.");

        CheckBox untracked = new CheckBox("Include untracked files");
        untracked.getStyleClass().add("stash-options-untracked");
        untracked.setSelected(true);
        CheckBox keepIndex = new CheckBox("Keep staged changes in the index");
        keepIndex.getStyleClass().add("stash-options-keep-index");

        getDialogPane().setContent(DialogParts.stack(nameField,
                DialogParts.toggle(untracked, "New files git does not track yet go into the stash "
                        + "too, and leave the working tree with the rest."),
                DialogParts.toggle(keepIndex, "What is staged is saved in the stash as well, but "
                        + "stays staged and in the working tree - to test or commit just that while "
                        + "the rest is set aside.")));

        setResultConverter(button -> button == STASH
                ? new StashOptions(name.getText(), untracked.isSelected(),
                        keepIndex.isSelected(), List.of())
                : null);
    }
}
