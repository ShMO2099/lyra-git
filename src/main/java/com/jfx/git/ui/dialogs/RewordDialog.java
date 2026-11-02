package com.jfx.git.ui.dialogs;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.MessageEditor;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

/**
 * Rewrites one commit's message.
 *
 * <p>Opens on the whole message, subject and body, because rewording is nearly always a
 * correction to what is there rather than a replacement for it. The commit handed in has
 * to carry that whole message: a graph row holds only the subject, and rewording from
 * one would cut the body off.
 *
 * <p>The message is written in the commit box's shape - a summary with its countdown, a
 * description under it - so a message looks the same reworded as it did committed. The
 * interactive rebase plan opens this too, for a step set to reword.
 */
public class RewordDialog extends Dialog<String> {

    public static final String SUMMARY_ID = "reword-summary";
    public static final String DESCRIPTION_ID = "reword-description";

    private final MessageEditor editor;

    /** @param commit the commit to reword, with its full message loaded */
    public RewordDialog(CommitInfo commit) {
        this(commit.getShortHash(), commit.getFullMessage(),
                "Only the message changes. This commit and every commit after it get new hashes.");
    }

    /**
     * @param shortHash the commit, for the title
     * @param message   what the editor opens on
     * @param subtitle  what happens to the message once written
     */
    public RewordDialog(String shortHash, String message, String subtitle) {
        JfxDialog.shell(this, JfxDialog.Size.MD, "Reword commit");

        JfxDialog.header(this, Icons.edit(), JfxDialog.Tone.NEUTRAL, "Reword `" + shortHash + "`", subtitle);

        editor = new MessageEditor(message).rows(8);
        editor.summary().setId(SUMMARY_ID);
        editor.description().setId(DESCRIPTION_ID);
        getDialogPane().setContent(editor.node());

        ButtonType reword = new ButtonType("Reword", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(reword, ButtonType.CANCEL);

        // Nothing to do for the message it already has - compared as the editor holds it,
        // so a body joined by a single newline is not an edit - and a commit needs a summary.
        String opened = editor.message();
        var button = getDialogPane().lookupButton(reword);
        Runnable validate = () -> button.setDisable(editor.summary().getText().isBlank()
                || editor.message().equals(opened));
        editor.onChange(validate);
        validate.run();

        // After the shell has given the window its first focus, which would select the summary.
        setOnShown(e -> javafx.application.Platform.runLater(editor::focus));
        setResultConverter(pressed -> pressed == reword ? editor.message() : null);
    }

    /**
     * "Add co-author…" at the left of the footer, as the commit box has it: credits
     * someone with a {@code Co-authored-by:} trailer in the description, chosen from the
     * repository's recent authors or typed.
     *
     * @param backend the repository, read for recent authors to offer
     * @param failed  told why they could not be read
     */
    public RewordDialog offerCoAuthors(com.jfx.git.core.GitBackend backend,
                                       java.util.function.Consumer<String> failed) {
        Button coAuthor = new Button("Add co-author…");
        coAuthor.getStyleClass().addAll("btn-ghost", "reword-co-author");
        coAuthor.setMinWidth(Region.USE_PREF_SIZE);
        coAuthor.setTooltip(new Tooltip("Adds a Co-authored-by: trailer, which GitHub and GitLab "
                + "use to credit a second author"));
        coAuthor.setOnAction(e -> CoAuthorDialog.ask(backend,
                person -> editor.addTrailer("Co-authored-by", person), failed));
        JfxDialog.footerLeft(this, coAuthor);
        return this;
    }
}
