package com.jfx.git.ui.dialogs;


import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks which of the repository's top-level folders stay checked out - the commercial clients'
 * Sparse Checkout feature, cone-mode's own restriction (only whole top-level
 * directories, not arbitrary sub-paths). Top-level files are not offered here: cone
 * mode always keeps them regardless of which directories are chosen, so a checkbox for
 * one would be either always-on or misleading.
 *
 * <p>Two distinct outcomes, not one - {@link Apply} narrows the tree to the checked
 * directories, {@link Disable} restores the full tree - so this returns a small sealed
 * result rather than a single {@code List<String>}, the same way {@code
 * NewTagDialog.Result} carries more than one meaning in its own field. The caller (who
 * already owns the real {@code GitBackend} and the async plumbing every other mutation
 * in this app goes through) performs whichever call the result asks for; this dialog
 * only gathers the choice.
 *
 * <p>The folders are ticked in a frame, one row each, and the footer's verb counts them:
 * "Check out 2 folders". Turning sparse checkout off is only offered while it is on - a
 * button that turned off what was not on would do nothing.
 */
public class SparseCheckoutDialog extends Dialog<SparseCheckoutDialog.Result> {

    public sealed interface Result permits Apply, Disable {
    }

    public record Apply(List<String> paths) implements Result {
    }

    public record Disable() implements Result {
    }

    /** Shared instances rather than built per-dialog, so a test can look either up by identity. */
    public static final ButtonType APPLY = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
    public static final ButtonType DISABLE = new ButtonType("Turn off sparse checkout", ButtonBar.ButtonData.OTHER);

    /** The folder rows' frame, and the ghost action above it that ticks all or none. */
    public static final String FOLDERS_ID = "sparse-folders";
    public static final String SELECT_ALL_ID = "sparse-select-all";

    /** The most rows the frame shows before it scrolls. */
    private static final int MAX_ROWS = 10;

    private final List<CheckBox> boxes = new ArrayList<>();

    public SparseCheckoutDialog(List<String> topLevelDirectories, List<String> currentlySelected,
                                 boolean currentlyEnabled) {
        JfxDialog.shell(this, JfxDialog.Size.MD, "Sparse checkout");
        JfxDialog.header(this, Icons.folder(), JfxDialog.Tone.NEUTRAL,
                currentlyEnabled ? "Choose the folders to keep checked out"
                        : "Check out only some folders",
                currentlyEnabled ? "Sparse checkout is on"
                        : "Sparse checkout is off: every folder is checked out").ruled();

        getDialogPane().getButtonTypes().add(APPLY);
        if (currentlyEnabled) {
            getDialogPane().getButtonTypes().add(DISABLE);
        }
        getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        // What is kept now but is not a top-level folder - a nested directory set from the
        // command line, or a non-cone pattern - is offered too, ticked. Left out, as it
        // was, Apply silently dropped it: the dialog only sent back what it had shown.
        List<String> offered = new ArrayList<>(topLevelDirectories);
        if (currentlyEnabled) {
            for (String kept : currentlySelected) {
                if (!offered.contains(kept)) offered.add(kept);
            }
        }

        VBox rows = new VBox();
        for (String dir : offered) {
            CheckBox box = new CheckBox(dir);
            box.setGraphic(Icons.folder());
            box.setSelected(currentlySelected.contains(dir));
            // The whole row is the target, not only the box and its name.
            box.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(box, Priority.ALWAYS);
            HBox row = new HBox(box);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(0, 12, 0, 12));
            row.setMinHeight(DialogParts.ROW);
            row.setPrefHeight(DialogParts.ROW);
            row.getStyleClass().add("jfx-row");
            boxes.add(box);
            rows.getChildren().add(row);
        }

        // Ticked in a frame rather than DialogParts.framed: a ListView redraws its rows as
        // it scrolls, and a checkbox redrawn is a checkbox whose state has to be put back.
        ScrollPane frame = new ScrollPane(offered.isEmpty()
                ? DialogParts.listMessage("This repository has no top-level folders to restrict.",
                        DialogParts.ListState.EMPTY)
                : rows);
        frame.setId(FOLDERS_ID);
        frame.getStyleClass().add("jfx-framed-list");
        frame.setFitToWidth(true);
        frame.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // As tall as the folders it lists, up to ten; past that it scrolls.
        frame.setPrefViewportHeight(offered.isEmpty() ? 2 * DialogParts.ROW
                : Math.min(offered.size(), MAX_ROWS) * DialogParts.ROW);

        Button selectAll = new Button("Select all");
        selectAll.setId(SELECT_ALL_ID);
        selectAll.getStyleClass().addAll("btn-ghost", "btn-sm");
        selectAll.setOnAction(e -> {
            boolean every = boxes.stream().allMatch(CheckBox::isSelected);
            boxes.forEach(box -> box.setSelected(!every));
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox above = new HBox(UiScale.STEP, DialogParts.section("Folders"), gap, selectAll);
        above.setAlignment(Pos.BASELINE_LEFT);
        above.setVisible(!offered.isEmpty());
        above.setManaged(!offered.isEmpty());

        Label hint = new Label("A folder left unticked leaves the working tree until sparse checkout "
                + "is turned off; it stays in the repository. Files at the top level are always kept.");
        hint.getStyleClass().add("t-caption");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        hint.setVisible(!offered.isEmpty());
        hint.setManaged(!offered.isEmpty());

        getDialogPane().setContent(DialogParts.stack(new VBox(UiScale.SNUG, above, frame), hint));

        Button applyButton = (Button) getDialogPane().lookupButton(APPLY);
        // Narrowing to nothing is not a lesser sparse checkout, it is an unreachable
        // repository - the same "unreachable rather than merely discouraged" standard
        // CreatePullRequestDialog's own OK button holds itself to.
        Runnable validate = () -> {
            long ticked = boxes.stream().filter(CheckBox::isSelected).count();
            applyButton.setDisable(ticked == 0);
            applyButton.setText(ticked == 0 ? "Check out folders"
                    : "Check out " + ticked + (ticked == 1 ? " folder" : " folders"));
            selectAll.setText(!boxes.isEmpty() && ticked == boxes.size() ? "Select none" : "Select all");
        };
        boxes.forEach(box -> box.selectedProperty().addListener((o, old, sel) -> validate.run()));
        validate.run();

        setResultConverter(button -> {
            if (button == APPLY) {
                return new Apply(boxes.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList());
            }
            if (button == DISABLE) {
                return new Disable();
            }
            return null;
        });
    }

    /** The folder checkboxes, in the order offered; for tests. */
    List<CheckBox> foldersForTest() {
        return List.copyOf(boxes);
    }
}
