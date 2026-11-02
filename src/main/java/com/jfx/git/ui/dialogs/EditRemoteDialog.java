package com.jfx.git.ui.dialogs;


import com.jfx.git.ui.Icons;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;

/**
 * Changes where a remote is fetched from and pushed to.
 *
 * <p>Two URLs because git has two: most remotes use one for both, and the second exists
 * for the remote that is read over HTTPS and written over SSH, or mirrored to somewhere
 * else. Leaving the push URL blank is how the dialog says "the same one", which is also
 * what a blank {@code remote.<name>.pushurl} means.
 *
 * <p>The name is not editable here. Renaming moves every remote-tracking branch and
 * re-points every upstream, which is a different size of change from correcting a URL,
 * and has its own menu item.
 */
public class EditRemoteDialog extends Dialog<EditRemoteDialog.Urls> {

    public static final String FETCH_FIELD_ID = "remote-fetch-url";
    public static final String PUSH_FIELD_ID = "remote-push-url";

    /**
     * What the dialog was closed with.
     *
     * @param fetchUrl never blank
     * @param pushUrl  blank, not {@code null}, when pushes are to use the fetch URL
     */
    public record Urls(String fetchUrl, String pushUrl) {
    }

    /**
     * @param remote   the remote's name, for the heading
     * @param fetchUrl its current URL, or {@code null}
     * @param pushUrl  its separate push URL, or {@code null} when it has none
     */
    public EditRemoteDialog(String remote, String fetchUrl, String pushUrl) {
        JfxDialog.shell(this, JfxDialog.Size.MD, "Edit remote");
        JfxDialog.header(this, Icons.remote(), JfxDialog.Tone.NEUTRAL, "Edit " + remote,
                "Where it is fetched from and pushed to");
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField fetch = new TextField(fetchUrl == null ? "" : fetchUrl);
        fetch.setId(FETCH_FIELD_ID);
        fetch.setPromptText("https://github.com/owner/repo.git");
        TextField push = new TextField(pushUrl == null ? "" : pushUrl);
        push.setId(PUSH_FIELD_ID);
        push.setPromptText("git@github.com:owner/repo.git");

        DialogParts.Field fetchRow = DialogParts.field("URL", fetch)
                .hint("HTTPS or SSH. Fetch and pull read from here.");
        DialogParts.Field pushRow = DialogParts.field("Push URL", push).optional()
                .hint("Only for a remote that is pushed to somewhere else. Leave blank to push to the URL above.");
        getDialogPane().setContent(DialogParts.stack(fetchRow, pushRow));

        // A remote with nothing to fetch from is not one git will accept either. Said once
        // the field has been emptied, not at the start of a remote that never had one.
        Node saveButton = getDialogPane().lookupButton(save);
        boolean[] edited = {false};
        Runnable validate = () -> {
            boolean blank = fetch.getText() == null || fetch.getText().isBlank();
            fetchRow.error(blank && edited[0] ? "A remote has to be fetched from somewhere: give its URL." : null);
            saveButton.setDisable(blank);
        };
        fetch.textProperty().addListener((obs, old, text) -> {
            edited[0] = true;
            validate.run();
        });
        validate.run();
        Platform.runLater(fetch::requestFocus);

        setResultConverter(button -> button == save
                ? new Urls(fetch.getText().trim(), push.getText() == null ? "" : push.getText().trim())
                : null);
    }
}
