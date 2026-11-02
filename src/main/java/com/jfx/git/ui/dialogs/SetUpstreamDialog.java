package com.jfx.git.ui.dialogs;


import com.jfx.git.ui.Icons;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Locale;

/**
 * Picks the remote branch a local branch tracks.
 *
 * <p>A filtered list rather than a drop-down: the repository this was written against
 * has 457 remote branches, and a combo box of 457 rows is a scrollbar with no way to
 * type towards the one wanted.
 *
 * <p>Opens on the branch already tracked, or failing that on a remote branch of the same
 * name - the answer nine times in ten, since it is what publishing the branch would have
 * set.
 */
public class SetUpstreamDialog extends Dialog<String> {

    public static final String FILTER_ID = "upstream-filter";
    public static final String LIST_ID = "upstream-list";

    /**
     * @param branch         the local branch whose upstream is being chosen
     * @param remoteBranches every remote-tracking branch, short names: {@code origin/main}
     * @param current        what {@code branch} tracks now, or {@code null}
     */
    public SetUpstreamDialog(String branch, List<String> remoteBranches, String current) {
        this(branch, remoteBranches, List.of(), current);
    }

    /**
     * @param branch         the local branch whose upstream is being chosen
     * @param remoteBranches every remote-tracking branch, short names: {@code origin/main}
     * @param remoteNames    the configured remotes, which decide each remote branch's branch
     *                       part - the one named like {@code branch} is offered first
     * @param current        what {@code branch} tracks now, or {@code null}
     */
    public SetUpstreamDialog(String branch, List<String> remoteBranches, List<String> remoteNames, String current) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Set upstream");
        JfxDialog.header(this, Icons.remoteBranch(), JfxDialog.Tone.NEUTRAL,
                (current == null ? "Set the upstream of " : "Change the upstream of ") + branch,
                current == null ? "It tracks nothing yet" : "It tracks `" + current + "` now");
        ButtonType set = new ButtonType("Set upstream", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(set, ButtonType.CANCEL);

        TextField filter = new TextField();
        filter.setId(FILTER_ID);
        filter.setPromptText("Filter remote branches");

        FilteredList<String> shown = new FilteredList<>(FXCollections.observableArrayList(remoteBranches));
        ListView<String> list = new ListView<>(shown);
        list.setId(LIST_ID);
        // Framed, with the one tracked now marked. As tall as the branches it offers,
        // between three rows and eight; filtering then leaves the frame where it is
        // rather than the window shrinking under the pointer.
        DialogParts.framed(list, name -> new DialogParts.Row(Icons.remoteBranch(), name, null,
                name.equals(current) ? List.of("Tracked") : List.of()), 3, 8, false);
        javafx.scene.layout.VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
        list.setPlaceholder(DialogParts.listMessage("No remote branch matches.", DialogParts.ListState.EMPTY));
        filter.textProperty().addListener((obs, old, text) -> {
            String wanted = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            shown.setPredicate(name -> wanted.isEmpty() || name.toLowerCase(Locale.ROOT).contains(wanted));
            if (list.getSelectionModel().isEmpty() && !shown.isEmpty()) {
                list.getSelectionModel().selectFirst();
            }
        });

        String preferred = current != null && remoteBranches.contains(current)
                ? current
                : remoteBranches.stream()
                        .filter(name -> branch.equals(com.jfx.git.model.RefNames.branchOf(name, remoteNames)))
                        .findFirst().orElse(null);
        if (preferred != null) {
            list.getSelectionModel().select(preferred);
            list.scrollTo(preferred);
        }

        getDialogPane().setContent(DialogParts.stack(filter, list));

        getDialogPane().lookupButton(set).disableProperty().bind(
                list.getSelectionModel().selectedItemProperty().isNull());
        // A double-click is the choice made, with no second trip to the button.
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !list.getSelectionModel().isEmpty()) {
                setResult(list.getSelectionModel().getSelectedItem());
                close();
            }
        });
        Platform.runLater(filter::requestFocus);

        setResultConverter(button -> button == set ? list.getSelectionModel().getSelectedItem() : null);
    }
}
