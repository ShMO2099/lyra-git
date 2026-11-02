package com.jfx.git.ui.dialogs;

import com.jfx.git.model.StashEntry;
import com.jfx.git.ui.Icons;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;

/**
 * Asks for the name of a branch to make from a stash ({@code git stash branch}).
 *
 * <p>Shared by the sidebar's stash list and the graph's stash rows, which offer the same
 * actions for the same entry. The hint says what will happen because the name alone does
 * not: the branch starts at the commit the stash was taken from, not at HEAD, and the
 * stash is dropped once it applies. The name is checked as it is typed, against what git
 * accepts as a branch name.
 */
public final class StashBranchDialog extends Dialog<String> {

    /** The name field, for tests. */
    public static final String NAME_ID = "stash-branch-name";

    private static final ButtonType CREATE = new ButtonType("Create branch", ButtonBar.ButtonData.OK_DONE);

    public StashBranchDialog(StashEntry stash) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Branch from stash");
        JfxDialog.header(this, Icons.branch(), JfxDialog.Tone.NEUTRAL, "Create a branch from a stash",
                "`" + stash.selector() + "`"
                        + (stash.shortDescription().isBlank() ? "" : " · " + stash.shortDescription()));
        getDialogPane().getButtonTypes().addAll(CREATE, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setId(NAME_ID);
        name.getStyleClass().add("stash-branch-name");
        name.setPromptText("fix/rescued-work");
        DialogParts.Field nameField = DialogParts.field("Branch name", name)
                .hint("It starts at the commit the stash was taken from, so the stash applies "
                        + "cleanly there; then it is checked out and the stash dropped.");
        getDialogPane().setContent(DialogParts.stack(nameField));

        Button create = (Button) getDialogPane().lookupButton(CREATE);
        Runnable validate = () -> {
            String text = name.getText() == null ? "" : name.getText().strip();
            String problem = text.isEmpty() ? null : branchNameProblem(text);
            nameField.error(problem);
            create.setDisable(text.isEmpty() || problem != null);
        };
        name.textProperty().addListener((obs, old, text) -> validate.run());
        validate.run();

        setResultConverter(button -> button == CREATE ? name.getText().strip() : null);
    }

    /** What git would refuse about {@code name} as a branch, or null. */
    static String branchNameProblem(String name) {
        return com.jfx.git.model.RefNames.branchNameProblem(name);
    }
}
