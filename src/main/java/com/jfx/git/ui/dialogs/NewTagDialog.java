package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.Icons;
import com.jfx.git.ui.MessageEditor;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;

/**
 * Prompts for a tag name and an optional message - blank makes a lightweight tag,
 * filled makes an annotated one, matching how {@code GitBackend#createTag} already
 * branches on the message itself.
 *
 * <p>Shared by {@code GitGraphCell}'s "Create tag here…" and {@code SidebarPane}'s
 * "New tag on HEAD…", which previously each opened a bare, name-only
 * {@code TextInputDialog} - annotated tags had no UI at all despite the backend
 * already fully supporting them (Wave 6's re-audit against a commercial client's {@code tags}
 * article). The two call sites differ only in what commit they tag and how the
 * confirmed result is described in the status bar, so one dialog class serves both.
 *
 * <p>The message is written as a commit's is, a summary over a description: an annotated
 * tag's message has a body like a commit's, and a one-line field could not hold one. The
 * button says which kind of tag it makes.
 */
public class NewTagDialog extends Dialog<NewTagDialog.Result> {

    public static final String NAME_ID = "new-tag-name";
    /** The message's summary line; the description under it has {@link #DESCRIPTION_ID}. */
    public static final String MESSAGE_ID = "new-tag-message";
    public static final String DESCRIPTION_ID = "new-tag-description";

    /** @param message the raw field text - blank, not {@code null}, when left empty */
    public record Result(String name, String message) {
        /** {@code null} rather than blank, ready to pass straight to {@code createTag}. */
        public String messageOrNull() {
            return message == null || message.isBlank() ? null : message;
        }
    }

    /**
     * @param target where the tag goes, as the header's subtitle: {@code "On HEAD"}, or
     *               {@code "On `a64205a` · Fix typos"} with the SHA in backticks
     */
    public NewTagDialog(String target) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "New tag");
        JfxDialog.header(this, Icons.tag(), JfxDialog.Tone.NEUTRAL, "Create a tag", target);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(this, ButtonType.OK, "Create tag");

        TextField name = new TextField();
        name.setId(NAME_ID);
        name.setPromptText("v1.4.0");
        MessageEditor message = new MessageEditor(null).rows(3).optional()
                .placeholder("Release 1.4: rounding fixes and refund docs")
                .hint("With a message the tag is annotated: it records who tagged it, when, "
                        + "and why. Without one it is lightweight.");
        message.summary().setId(MESSAGE_ID);
        message.description().setId(DESCRIPTION_ID);

        DialogParts.Field nameField = DialogParts.field("Tag name", name);
        getDialogPane().setContent(DialogParts.stack(nameField, message.node()));

        Button create = (Button) getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> {
            String text = name.getText() == null ? "" : name.getText().trim();
            String problem = text.isEmpty() ? null : tagNameProblem(text);
            nameField.error(problem);
            create.setDisable(text.isEmpty() || problem != null);
            create.setText(message.message().isBlank() ? "Create tag" : "Create annotated tag");
        };
        name.textProperty().addListener((obs, old, text) -> validate.run());
        message.onChange(validate);
        validate.run();

        setResultConverter(button -> button == ButtonType.OK
                ? new Result(name.getText().trim(), message.message())
                : null);
    }

    /** What git would refuse about {@code name} as a tag, or null. */
    static String tagNameProblem(String name) {
        return org.eclipse.jgit.lib.Repository.isValidRefName("refs/tags/" + name) ? null
                : "Not a tag name git accepts: no spaces, and none of ~ ^ : ? * [ \\ or \"..\".";
    }
}
