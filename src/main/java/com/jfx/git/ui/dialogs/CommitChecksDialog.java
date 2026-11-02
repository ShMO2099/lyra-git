package com.jfx.git.ui.dialogs;

import com.jfx.git.core.CommitChecks;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What the pre-commit checks found, and what to do about each file.
 *
 * <p>One row per file, with its reasons and the two ways out: take the file out of this
 * commit, or - for a large one, when LFS is installed - have LFS hold it instead. Neither
 * happens until the commit does, so changing one's mind is a second click on the same
 * row rather than an undo. The commit button reads "Commit anyway" while anything is left
 * as it is, and "Commit" once every file has been dealt with. A file with conflict markers
 * has neither way out: it is fixed in the merge editor, so its row only says why.
 *
 * <p>The result is {@code null} for Cancel; otherwise a {@link Resolution}, possibly empty.
 */
public final class CommitChecksDialog extends Dialog<CommitChecksDialog.Resolution> {

    /** What to do with one file before committing. */
    public enum Action { UNSTAGE, TRACK_WITH_LFS }

    /** The choice made per path; a path not in it is committed as it is. */
    public record Resolution(Map<String, Action> actions) {
    }

    /**
     * @param findings     what the checks found, in the order they were found
     * @param lfsAvailable whether git-lfs is installed, so "Track with LFS" can be offered
     * @param lfsPattern   the pattern a file would be tracked under, e.g. {@code *.zip}
     */
    public CommitChecksDialog(List<CommitChecks.Finding> findings, boolean lfsAvailable,
                              Function<String, String> lfsPattern) {
        JfxDialog.shell(this, JfxDialog.Size.MD, "Before you commit");
        JfxDialog.header(this, Icons.shield(), JfxDialog.Tone.CAUTION, headline(findings),
                "Nothing is committed until you choose");

        ButtonType commit = new ButtonType("Commit anyway", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(commit, ButtonType.CANCEL);
        Button commitButton = (Button) getDialogPane().lookupButton(commit);
        commitButton.getStyleClass().add("commit-checks-commit");
        // Red, not the filled primary: going past a secret is the risky choice here.
        JfxDialog.danger(this, commit);
        // Cancel is what Enter does here: committing past a secret should be a decision.
        commitButton.setDefaultButton(false);
        ((Button) getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);

        Map<String, List<CommitChecks.Finding>> byPath = new LinkedHashMap<>();
        for (CommitChecks.Finding finding : findings) {
            byPath.computeIfAbsent(finding.path(), p -> new ArrayList<>()).add(finding);
        }

        Map<String, ToggleGroup> choices = new LinkedHashMap<>();
        VBox rows = new VBox();
        for (Map.Entry<String, List<CommitChecks.Finding>> entry : byPath.entrySet()) {
            String path = entry.getKey();
            ToggleGroup choice = new ToggleGroup();
            choices.put(path, choice);
            rows.getChildren().add(row(path, entry.getValue(), choice, lfsAvailable, lfsPattern));
        }

        Runnable relabel = () -> {
            boolean allHandled = choices.values().stream().allMatch(g -> g.getSelectedToggle() != null);
            commitButton.setText(allHandled ? "Commit" : "Commit anyway");
        };
        choices.values().forEach(g -> g.selectedToggleProperty().addListener((o, was, now) -> relabel.run()));

        // The findings in a frame, one row each. Not DialogParts.framed: that is a ListView,
        // whose rows are a fixed height and are redrawn as it scrolls, and these hold their
        // own buttons and a reason that wraps.
        ScrollPane frame = new ScrollPane(rows);
        frame.setFitToWidth(true);
        frame.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        frame.getStyleClass().addAll("jfx-framed-list", "commit-checks-findings");
        Label note = new Label(note(findings));
        note.getStyleClass().add("t-caption");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = DialogParts.stack(frame, note);
        VBox.setVgrow(frame, Priority.ALWAYS);
        getDialogPane().setContent(content);

        setResultConverter(button -> {
            if (button != commit) return null;
            Map<String, Action> actions = new LinkedHashMap<>();
            choices.forEach((path, group) -> {
                if (group.getSelectedToggle() != null) {
                    actions.put(path, (Action) group.getSelectedToggle().getUserData());
                }
            });
            return new Resolution(actions);
        });
    }

    private static String headline(List<CommitChecks.Finding> findings) {
        boolean secret = has(findings, CommitChecks.Kind.SECRET);
        boolean large = has(findings, CommitChecks.Kind.LARGE_FILE);
        if (has(findings, CommitChecks.Kind.CONFLICT_MARKERS)) {
            return secret || large ? "Some files in this commit need a second look"
                    : "This commit still has conflict markers in it";
        }
        if (secret && large) return "This commit may include a secret and a large file";
        return secret ? "This commit may include a secret" : "This commit includes a large file";
    }

    /** What the ways out do, for the kinds found. */
    private static String note(List<CommitChecks.Finding> findings) {
        List<String> parts = new ArrayList<>();
        if (has(findings, CommitChecks.Kind.CONFLICT_MARKERS)) {
            parts.add("Cancel and open a file with conflict markers in the merge editor to choose what it"
                    + " keeps. Commit anyway only if the marker lines belong in the file.");
        }
        if (has(findings, CommitChecks.Kind.SECRET) || has(findings, CommitChecks.Kind.LARGE_FILE)) {
            parts.add("Unstaging keeps the file on disk. A secret that has been pushed "
                    + "should be revoked, not only removed from the next commit.");
        }
        return String.join(" ", parts);
    }

    private static boolean has(List<CommitChecks.Finding> findings, CommitChecks.Kind kind) {
        return findings.stream().anyMatch(f -> f.kind() == kind);
    }

    /**
     * One finding: the warning mark, the path, why under it, and the ways out at the end of
     * the row. The buttons say only what they do - the path they act on is the row's own,
     * at its start - and name it to assistive technology, which reads a button by itself.
     */
    private static HBox row(String path, List<CommitChecks.Finding> reasons, ToggleGroup choice,
                            boolean lfsAvailable, Function<String, String> lfsPattern) {
        Label name = new Label(path);
        name.getStyleClass().add("jfx-row-primary");
        name.setMinWidth(0);
        name.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        name.setTooltip(new Tooltip(path));

        Label why = new Label(describe(reasons));
        why.getStyleClass().add("jfx-row-secondary");
        why.setWrapText(true);
        why.setMinHeight(Region.USE_PREF_SIZE);
        VBox text = new VBox(UiScale.TIGHT, name, why);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(UiScale.STEP + 2, Icons.warning(), text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setMinHeight(DialogParts.ROW_TWO_LINES);
        row.getStyleClass().addAll("jfx-row", "commit-checks-row");
        // A file with conflict markers has no way out here. Unstaging it in the middle of
        // a merge puts HEAD's version in the index, and the commit then takes that side
        // without a word - a quieter way to lose the other one. It is fixed in the merge
        // editor, after Cancel, or committed as it is.
        if (has(reasons, CommitChecks.Kind.CONFLICT_MARKERS)) {
            row.getStyleClass().add("commit-checks-markers");
            return row;
        }

        ToggleButton unstage = new ToggleButton("Unstage");
        unstage.getStyleClass().addAll("btn-sm", "commit-checks-unstage");
        unstage.setAccessibleText("Unstage " + path);
        unstage.setUserData(Action.UNSTAGE);
        unstage.setToggleGroup(choice);
        unstage.setMinWidth(Region.USE_PREF_SIZE);
        row.getChildren().add(unstage);
        // LFS for a file that is large and nothing worse: moving a secret into LFS still
        // publishes it.
        if (has(reasons, CommitChecks.Kind.LARGE_FILE) && !has(reasons, CommitChecks.Kind.SECRET) && lfsAvailable) {
            ToggleButton track = new ToggleButton("Track with LFS");
            track.getStyleClass().addAll("btn-sm", "commit-checks-lfs");
            track.setUserData(Action.TRACK_WITH_LFS);
            track.setToggleGroup(choice);
            track.setMinWidth(Region.USE_PREF_SIZE);
            String pattern = lfsPattern.apply(path);
            track.setTooltip(new Tooltip("Has Git LFS track " + pattern
                    + ", stages .gitattributes and stages the file again as an LFS pointer"));
            track.setAccessibleText("Track " + path + " with LFS");
            row.getChildren().add(track);
        }
        return row;
    }

    /** "Looks like a GitHub token (line 12)", "64 MB" and "Still has conflict markers (line 3)", joined. */
    private static String describe(List<CommitChecks.Finding> reasons) {
        List<String> parts = new ArrayList<>();
        for (CommitChecks.Finding finding : reasons) {
            if (finding.kind() == CommitChecks.Kind.LARGE_FILE) {
                parts.add(finding.what() + ", which is large for a git repository");
            } else if (finding.kind() == CommitChecks.Kind.CONFLICT_MARKERS) {
                parts.add("Still has " + finding.what() + (finding.line() > 0 ? " (line " + finding.line() + ")" : ""));
            } else if (finding.line() > 0) {
                parts.add("Looks like " + finding.what() + " (line " + finding.line() + ")");
            } else {
                parts.add("Looks like " + finding.what());
            }
        }
        return String.join(". ", parts);
    }
}
