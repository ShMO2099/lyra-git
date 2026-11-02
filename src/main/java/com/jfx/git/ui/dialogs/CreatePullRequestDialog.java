package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Composes a new pull/merge request - title, optional description, and the local
 * branch to open it from and into. Shared by all three providers: GitHub's, GitLab's,
 * and Bitbucket's create-PR/MR endpoints all take the same four fields, just under
 * different field names, which each provider's client already maps.
 *
 * <p>The branch pickers offer local branches only, on the assumption the one being
 * proposed is already pushed - the same assumption {@code checkoutPullRequest} and
 * friends make in the other direction. A fork's cross-repository head (GitHub's {@code
 * "owner:branch"} form) is out of scope for this first pass.
 *
 * <h2>What the request would carry</h2>
 * Under the two pickers is the list of commits the request would actually propose. Two
 * branch names in a form look equally plausible whether the range between them is three
 * commits or three hundred, and the usual cause of the second is a base branch chosen by
 * habit rather than on purpose. Showing the range turns picking a base from a guess into
 * a check.
 */
public class CreatePullRequestDialog extends Dialog<CreatePullRequestDialog.Draft> {

    /** As many commits as the preview will draw before it stops counting. */
    private static final int PREVIEW_LIMIT = 50;

    /** How a field shows it is wrong; the same pseudo-class {@code DialogParts.Field#error} sets. */
    private static final javafx.css.PseudoClass DANGER = javafx.css.PseudoClass.getPseudoClass("danger");

    private final TextField title = new TextField();
    private final TextArea description = new TextArea();
    private final ComboBox<String> source = new ComboBox<>();
    private final ComboBox<String> target = new ComboBox<>();

    public record Draft(String title, String description, String sourceBranch, String targetBranch) {
        /** {@code null} rather than blank, ready to pass straight to a create call. */
        public String descriptionOrNull() {
            return description == null || description.isBlank() ? null : description;
        }
    }

    /**
     * The branches the form offers and the one checked out, read together on the
     * repository lane before the form opens - reading them on the FX thread froze the UI
     * for as long as git took.
     */
    public record Branches(List<String> names, String current) {

        /** The read itself, for {@code GitTaskRunner.query}. */
        public static java.util.function.Supplier<Branches> reader(com.jfx.git.core.GitBackend git) {
            return () -> new Branches(
                    git.getLocalBranches().stream().map(com.jfx.git.model.RefInfo::shortName).toList(),
                    git.getCurrentBranch());
        }
    }

    /**
     * Reads {@code excludeRef..includeRef} - what the request would contain.
     *
     * <p>A function rather than the backend itself so the dialog stays a form: it needs
     * one answer, and a test can give it one without opening a repository.
     */
    @FunctionalInterface
    public interface RangeLookup {
        List<CommitInfo> commitsInRange(String excludeRef, String includeRef, int limit);
    }

    /** Without a way to read the range; the preview says so and the form still works. */
    public CreatePullRequestDialog(String subject, List<String> branches, String currentBranch) {
        this(subject, branches, currentBranch, null, null);
    }

    /**
     * @param repository the repository {@code range} reads, whose lane the lookup runs on
     */
    public CreatePullRequestDialog(String subject, List<String> branches, String currentBranch,
                                   com.jfx.git.core.GitBackend repository, RangeLookup range) {
        JfxDialog.shell(this, JfxDialog.Size.MD, "New " + subject);
        JfxDialog.header(this, Icons.pullRequest(), JfxDialog.Tone.NEUTRAL, "Open a " + subject,
                "From one of this repository's branches into another").ruled();
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(this, ButtonType.OK, "Create " + subject);

        title.setPromptText("Round totals to whole cents");
        title.getStyleClass().add("pr-title");
        description.setPrefRowCount(3);
        description.setWrapText(true);
        description.getStyleClass().add("pr-description");
        DialogParts.Field titleField = DialogParts.field("Title", title);
        DialogParts.Field descriptionField = DialogParts.field("Description", description).optional();

        source.getItems().setAll(branches);
        source.getStyleClass().add("pr-source");
        if (currentBranch != null) {
            source.getSelectionModel().select(currentBranch);
        }
        target.getItems().setAll(branches);
        target.getStyleClass().add("pr-target");
        // One sentence, "From x into y": the two pickers split the row evenly whatever
        // the branch names, so a long one does not push the other off the end.
        for (ComboBox<String> picker : List.of(source, target)) {
            picker.setPrefWidth(120);
            picker.setMinWidth(80);
            picker.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(picker, Priority.ALWAYS);
        }
        Label from = new Label("From");
        Label into = new Label("into");
        for (Label word : List.of(from, into)) {
            word.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox branchRow = new HBox(UiScale.STEP, from, source, into, target);
        branchRow.setAlignment(Pos.CENTER_LEFT);
        DialogParts.Field branchesField = DialogParts.field("Branches", branchRow);

        Label previewHeading = DialogParts.section("Commits");
        previewHeading.getStyleClass().add("pr-preview-heading");
        ListView<CommitInfo> preview = new ListView<>();
        preview.getStyleClass().add("pr-preview-list");
        DialogParts.framed(preview, commit -> new DialogParts.Row(Icons.commit(), commit.getSubject(),
                commit.getShortHash() + " · " + commit.getAuthorName(), List.of()), 3, 5, true);
        VBox previewBox = new VBox(UiScale.SNUG, previewHeading, preview);
        VBox.setVgrow(preview, Priority.ALWAYS);

        getDialogPane().setContent(DialogParts.stack(titleField, descriptionField, branchesField, previewBox));

        // A pull request with no title, or one merging a branch into itself, is not a
        // request anyone downstream could act on - disabled rather than rejected after
        // the fact, the same "unreachable rather than merely discouraged" standard
        // InteractiveRebaseDialog holds its own confirm button to. The second has a rule
        // to break, so it says so under the pickers.
        var ok = getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> {
            boolean same = source.getValue() != null && source.getValue().equals(target.getValue());
            branchesField.error(same ? "A branch cannot be proposed into itself." : null);
            // The field outlines its row, which draws nothing; the pickers are what is wrong.
            for (ComboBox<String> picker : List.of(source, target)) {
                picker.pseudoClassStateChanged(DANGER, same);
            }
            ok.setDisable(title.getText().isBlank()
                    || source.getValue() == null || target.getValue() == null || same);
        };

        // Guards the preview the way the banner queries elsewhere do: two quick changes
        // of branch leave two reads in flight, and the slower one must not overwrite the
        // newer answer just because it finished last. What the list cannot show - no
        // branch on one side yet, a read under way, nothing to propose - it says inside
        // its frame, once.
        long[] previewRequest = {0};
        Runnable refreshPreview = () -> {
            String fromBranch = source.getValue();
            String intoBranch = target.getValue();
            final long request = ++previewRequest[0];
            preview.getItems().clear();
            previewHeading.setText("Commits");
            if (fromBranch == null || intoBranch == null) {
                preview.setPlaceholder(DialogParts.listMessage("Pick a branch on each side.",
                        DialogParts.ListState.EMPTY));
                return;
            }
            if (fromBranch.equals(intoBranch)) {
                // The reason is under the pickers already.
                preview.setPlaceholder(DialogParts.listMessage("Nothing to compare.", DialogParts.ListState.EMPTY));
                return;
            }
            if (range == null || repository == null) {
                preview.setPlaceholder(DialogParts.listMessage("The commits between them are not read here.",
                        DialogParts.ListState.EMPTY));
                return;
            }
            preview.setPlaceholder(DialogParts.listMessage("Reading " + fromBranch + " into " + intoBranch + "…",
                    DialogParts.ListState.LOADING));
            GitTaskRunner.query(repository,
                    () -> range.commitsInRange(intoBranch, fromBranch, PREVIEW_LIMIT),
                    commits -> {
                        if (request != previewRequest[0]) {
                            return;
                        }
                        if (commits.isEmpty()) {
                            preview.setPlaceholder(DialogParts.listMessage(describeRange(0, intoBranch),
                                    DialogParts.ListState.EMPTY));
                        } else {
                            previewHeading.setText(describeRange(commits.size(), intoBranch));
                            preview.getItems().setAll(commits);
                        }
                    },
                    // The form works without the preview, so a failed read says so in the
                    // frame rather than putting an error over a working dialog.
                    message -> {
                        if (request == previewRequest[0]) {
                            preview.setPlaceholder(DialogParts.listMessage(
                                    "Could not read the commits between them.", DialogParts.ListState.FAILED));
                        }
                    });
        };

        title.textProperty().addListener((obs, old, val) -> validate.run());
        source.valueProperty().addListener((obs, old, val) -> {
            validate.run();
            refreshPreview.run();
        });
        target.valueProperty().addListener((obs, old, val) -> {
            validate.run();
            refreshPreview.run();
        });
        validate.run();
        refreshPreview.run();

        setResultConverter(button -> button == ButtonType.OK
                ? new Draft(title.getText().trim(), description.getText().trim(),
                        source.getValue(), target.getValue())
                : null);
    }

    /**
     * Fills the form in from {@code draft} - what {@link com.jfx.git.ui.PullRequestPrefill} worked out
     * for a branch that was just published. A blank field in the draft leaves the form's
     * own value; a branch the pickers do not list yet is added to them, since a default
     * branch may exist only on the remote.
     */
    public void prefill(Draft draft) {
        if (draft == null) return;
        if (draft.title() != null && !draft.title().isBlank()) title.setText(draft.title());
        if (draft.description() != null && !draft.description().isBlank()) {
            description.setText(draft.description());
            // A template is long; the form grows to show more of it.
            description.setPrefRowCount(10);
        }
        select(source, draft.sourceBranch());
        select(target, draft.targetBranch());
    }

    private void select(ComboBox<String> picker, String branch) {
        if (branch == null || branch.isBlank()) return;
        if (!picker.getItems().contains(branch)) {
            for (ComboBox<String> both : List.of(source, target)) {
                if (!both.getItems().contains(branch)) both.getItems().add(branch);
            }
        }
        picker.getSelectionModel().select(branch);
    }

    /**
     * How the commit count reads above the list - or, for none, inside its empty frame.
     *
     * <p>A full page is reported as "50+" rather than "50": the read stopped at the limit,
     * so the exact number is not known and stating it would be a guess presented as a
     * fact. An empty range names the branch it is empty against, because that is the
     * case where the base is usually the mistake.
     */
    static String describeRange(int count, String into) {
        if (count == 0) {
            return "Nothing to propose - " + into + " already has these commits.";
        }
        if (count >= PREVIEW_LIMIT) {
            return PREVIEW_LIMIT + "+ commits";
        }
        return count == 1 ? "1 commit" : count + " commits";
    }
}
