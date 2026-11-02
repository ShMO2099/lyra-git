package com.jfx.git.ui.dialogs;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.MessageEditor;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.ArrayList;
import java.util.List;

/**
 * The message editor shown before commits are squashed together.
 *
 * <p>Git's own default for a squash is every message concatenated, which is almost never
 * what anyone wants to keep but is the right thing to start from — the parts worth
 * keeping are in there, and deleting is easier than retyping. The editor is pre-filled
 * that way and the user edits it down: the oldest commit's subject becomes the summary,
 * everything after it the description.
 *
 * <p>The commits being combined are listed above the message, so what is being folded
 * does not disappear into the text.
 */
public class SquashMessageDialog extends Dialog<String> {

    /** Past this many commits the list ends in "and N more" rather than growing on. */
    private static final int LISTED = 5;

    public SquashMessageDialog(List<CommitInfo> commits) {
        JfxDialog.shell(this, JfxDialog.Size.MD, "Squash commits");

        JfxDialog.header(this, Icons.commit(), JfxDialog.Tone.NEUTRAL,
                "Squash " + commits.size() + " commits into one",
                "Starts with every message in the range; edit it down to one.");

        MessageEditor editor = new MessageEditor(combinedMessage(commits)).rows(8);
        getDialogPane().setContent(DialogParts.stack(
                Dialogs.affectedList("Combining · " + commits.size(), rows(commits)), editor.node()));

        ButtonType squashType = new ButtonType("Squash", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(squashType, ButtonType.CANCEL);
        JfxDialog.verb(this, squashType, "Squash " + commits.size() + " commits");

        // A squash with no message would produce a commit git refuses to make.
        var squashButton = getDialogPane().lookupButton(squashType);
        Runnable validate = () -> squashButton.setDisable(editor.summary().getText().isBlank());
        editor.onChange(validate);
        validate.run();

        // After the shell has given the window its first focus, which would select the summary.
        setOnShown(e -> javafx.application.Platform.runLater(editor::focus));
        setResultConverter(button -> button == squashType ? editor.message() : null);
    }

    /** "807d3b6  Validate orders before charging", oldest first like the message. */
    private static List<String> rows(List<CommitInfo> commits) {
        List<String> rows = new ArrayList<>();
        for (int i = commits.size() - 1; i >= 0; i--) {
            if (rows.size() == LISTED && i > 0) {
                rows.add("… and " + (i + 1) + " more");
                break;
            }
            rows.add(commits.get(i).getShortHash() + "  " + commits.get(i).getSubject());
        }
        return rows;
    }

    /**
     * Every commit's message, oldest first, separated by blank lines.
     *
     * <p>Oldest first because that is the order the work happened in, and a combined
     * message reads as a narrative rather than a stack.
     *
     * <p>The commits must carry their whole messages. A graph row's {@linkplain
     * CommitInfo#summary summary} does not, and {@link CommitInfo#getFullMessage()}
     * throws for one rather than letting its body vanish from the result.
     */
    public static String combinedMessage(List<CommitInfo> commits) {
        StringBuilder combined = new StringBuilder();
        // The graph hands them over newest first.
        for (int i = commits.size() - 1; i >= 0; i--) {
            String message = commits.get(i).getFullMessage().trim();
            if (message.isEmpty()) continue;
            if (!combined.isEmpty()) combined.append("\n\n");
            combined.append(message);
        }
        return combined.toString();
    }
}
