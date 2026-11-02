package com.jfx.git.ui.dialogs;


import com.jfx.git.ui.Icons;

import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Pushes the current branch to a remote of the user's choosing.
 *
 * <p>The toolbar's Push goes where the branch is configured to go, which is right until
 * the day it is not: publishing to a fork as well as to the shared remote, or moving a
 * branch from one to the other. That used to mean a terminal.
 *
 * <p>The branch keeps its own name on the remote. Tracking is offered rather than
 * assumed for a branch that already tracks something - clear by default, since pushing a
 * copy to a fork should not re-point where Pull comes from. A branch that tracks nothing
 * is not given the choice: {@code GitBackend.push} records the upstream of a branch's
 * first push whatever it is asked, so the box is ticked and says why instead of offering
 * an answer that would be ignored.
 *
 * <p>A drop-down rather than cards for the remote: the remotes are names the user gave
 * them, with nothing to say about each that the name does not, and the button already
 * says which one the push goes to.
 */
public class PushToDialog extends Dialog<PushToDialog.Target> {

    public static final String REMOTE_ID = "push-to-remote";
    public static final String TRACK_ID = "push-to-track";

    /** @param setUpstream whether the pushed branch becomes the local branch's upstream */
    public record Target(String remote, boolean setUpstream) {
    }

    /**
     * @param branch   the branch being pushed
     * @param remotes  the configured remotes; never empty
     * @param upstream what the branch tracks now, as {@code origin/main}, or {@code null}
     */
    public PushToDialog(String branch, List<String> remotes, String upstream) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Push to");
        JfxDialog.header(this, Icons.push(), JfxDialog.Tone.NEUTRAL, "Push " + branch + " to a remote",
                upstream == null ? "It tracks nothing yet" : "It tracks `" + upstream + "` now");
        ButtonType push = new ButtonType("Push", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(push, ButtonType.CANCEL);

        ChoiceBox<String> remote = new ChoiceBox<>(FXCollections.observableArrayList(remotes));
        remote.setId(REMOTE_ID);
        DialogParts.Field remoteField = DialogParts.field("Remote", remote)
                .hint("The branch is pushed under its own name, " + branch + ".");

        CheckBox track = new CheckBox("Track it there");
        track.setId(TRACK_ID);
        track.setSelected(upstream == null);
        track.setDisable(upstream == null);
        VBox trackRow = DialogParts.toggle(track, upstream == null
                ? branch + " tracks nothing yet, so it will track the branch this push creates."
                : "Ticked, Pull and the ahead and behind counts follow the copy on that remote instead. "
                        + "Leave clear to keep tracking " + upstream + ".");

        getDialogPane().setContent(DialogParts.stack(remoteField, trackRow));

        // The button names the remote chosen.
        Button confirm = (Button) getDialogPane().lookupButton(push);
        remote.valueProperty().addListener((obs, old, now) ->
                confirm.setText(now == null ? "Push" : "Push to " + now));
        // The first remote the branch does not already track: that is the one this
        // dialog was opened to reach.
        String tracked = upstream == null ? null : com.jfx.git.model.RefNames.remoteOf(upstream, remotes);
        remote.setValue(remotes.stream().filter(name -> !name.equals(tracked)).findFirst()
                .orElse(remotes.get(0)));

        setResultConverter(button -> button == push
                ? new Target(remote.getValue(), track.isSelected())
                : null);
    }
}
