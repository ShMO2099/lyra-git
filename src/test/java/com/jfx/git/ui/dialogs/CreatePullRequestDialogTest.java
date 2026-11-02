package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.FxHarness;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compose form all three providers' "New pull/merge request…" buttons open. What
 * each provider's client does with a confirmed {@link CreatePullRequestDialog.Draft}
 * is covered separately by each dialog's own {@code createForTest} tests; this class
 * covers the form itself - the OK button tracks whether the draft is even sendable,
 * the same "unreachable rather than merely discouraged" standard {@code
 * InteractiveRebaseDialogTest} holds its own confirm button to.
 */
class CreatePullRequestDialogTest {

    /** Builds and lays out a dialog without showing it - the same technique {@code InteractiveRebaseDialogTest} uses. */
    private CreatePullRequestDialog dialog(List<String> branches, String currentBranch) {
        return FxHarness.call(() -> {
            CreatePullRequestDialog d = new CreatePullRequestDialog("pull request", branches, currentBranch);
            d.getDialogPane().applyCss();
            d.getDialogPane().layout();
            return d;
        });
    }

    private Node okButton(CreatePullRequestDialog dialog) {
        return dialog.getDialogPane().lookupButton(ButtonType.OK);
    }

    private TextField titleField(CreatePullRequestDialog dialog) {
        return (TextField) FxHarness.lookupAll(dialog.getDialogPane(), "pr-title").get(0);
    }

    private TextArea descriptionField(CreatePullRequestDialog dialog) {
        return (TextArea) FxHarness.lookupAll(dialog.getDialogPane(), "pr-description").get(0);
    }

    @SuppressWarnings("unchecked")
    private ComboBox<String> sourceCombo(CreatePullRequestDialog dialog) {
        return (ComboBox<String>) FxHarness.lookupAll(dialog.getDialogPane(), "pr-source").get(0);
    }

    @SuppressWarnings("unchecked")
    private ComboBox<String> targetCombo(CreatePullRequestDialog dialog) {
        return (ComboBox<String>) FxHarness.lookupAll(dialog.getDialogPane(), "pr-target").get(0);
    }

    @Test
    void theSourceComboIsPreselectedToTheCurrentBranch() {
        CreatePullRequestDialog dialog = dialog(List.of("main", "feature"), "feature");
        FxHarness.run(() -> assertEquals("feature", sourceCombo(dialog).getValue()));
    }

    @Test
    void okIsDisabledUntilATitleIsGiven() {
        CreatePullRequestDialog dialog = dialog(List.of("main", "feature"), "feature");
        FxHarness.run(() -> {
            targetCombo(dialog).setValue("main");
            assertTrue(okButton(dialog).isDisabled(), "no title yet - nothing to send");

            titleField(dialog).setText("Add the thing");
            assertFalse(okButton(dialog).isDisabled());
        });
    }

    @Test
    void okIsDisabledWhenSourceAndTargetAreTheSame() {
        CreatePullRequestDialog dialog = dialog(List.of("main", "feature"), "feature");
        FxHarness.run(() -> {
            titleField(dialog).setText("Add the thing");
            targetCombo(dialog).setValue("feature");

            assertTrue(okButton(dialog).isDisabled(), "a branch cannot merge into itself");
            assertEquals(List.of("A branch cannot be proposed into itself."), shownErrors(dialog),
                    "and the form says so under the pickers");

            targetCombo(dialog).setValue("main");
            assertFalse(okButton(dialog).isDisabled());
            assertEquals(List.of(), shownErrors(dialog));
        });
    }

    /** The errors showing under the form's fields. */
    private static List<String> shownErrors(CreatePullRequestDialog dialog) {
        return FxHarness.lookupAll(dialog.getDialogPane(), "jfx-field-error").stream()
                .filter(Node::isVisible)
                .map(n -> ((Label) n).getText())
                .toList();
    }

    /** How many labels in the dialog, the preview's placeholder included, read {@code text}. */
    private static long timesSaid(CreatePullRequestDialog dialog, String text) {
        ListView<?> preview = (ListView<?>) FxHarness.lookupAll(dialog.getDialogPane(), "pr-preview-list").get(0);
        Set<Node> labels = new HashSet<>(dialog.getDialogPane().lookupAll(".label"));
        if (preview.getPlaceholder() != null) {
            labels.addAll(preview.getPlaceholder().lookupAll(".label"));
        }
        return labels.stream().filter(n -> n instanceof Label label && text.equals(label.getText())).count();
    }

    /** Until both sides are picked, the preview's frame says so - once, not above it as well. */
    @Test
    void aMissingSideIsAskedForOnceInsideThePreview() {
        CreatePullRequestDialog dialog = dialog(List.of("main", "feature"), "feature");
        FxHarness.run(() -> assertEquals(1, timesSaid(dialog, "Pick a branch on each side.")));
    }

    @Test
    void confirmingReturnsTheTrimmedDraft() {
        CreatePullRequestDialog dialog = dialog(List.of("main", "feature"), "feature");
        CreatePullRequestDialog.Draft draft = FxHarness.call(() -> {
            titleField(dialog).setText("  Add the thing  ");
            descriptionField(dialog).setText("  because reasons  ");
            targetCombo(dialog).setValue("main");
            return dialog.getResultConverter().call(ButtonType.OK);
        });

        assertEquals("Add the thing", draft.title());
        assertEquals("because reasons", draft.description());
        assertEquals("feature", draft.sourceBranch());
        assertEquals("main", draft.targetBranch());
    }

    @Test
    void cancellingReturnsNoDraft() {
        CreatePullRequestDialog dialog = dialog(List.of("main", "feature"), "feature");
        CreatePullRequestDialog.Draft draft = FxHarness.call(() ->
                dialog.getResultConverter().call(ButtonType.CANCEL));

        assertEquals(null, draft);
    }

    @Test
    void descriptionOrNullIsNullForABlankDescription() {
        assertNull(new CreatePullRequestDialog.Draft("t", "", "a", "b").descriptionOrNull());
        assertNull(new CreatePullRequestDialog.Draft("t", "   ", "a", "b").descriptionOrNull());
        assertNull(new CreatePullRequestDialog.Draft("t", null, "a", "b").descriptionOrNull());
        assertEquals("why", new CreatePullRequestDialog.Draft("t", "why", "a", "b").descriptionOrNull());
    }

    // ------------------------------------------------------------------
    // The commit preview
    // ------------------------------------------------------------------

    /**
     * The heading is pure arithmetic over the count, so it is worth pinning directly:
     * every other assertion about the preview has to wait for a background read, and the
     * wording is the part a user actually acts on.
     */
    @Test
    void theHeadingNamesTheBaseWhenTheRangeIsEmpty() {
        assertEquals("Nothing to propose - main already has these commits.",
                CreatePullRequestDialog.describeRange(0, "main"),
                "an empty range is nearly always a base chosen by habit, so the message "
                        + "names the branch rather than just saying zero");
    }

    @Test
    void theHeadingCountsInWholeCommits() {
        assertEquals("1 commit", CreatePullRequestDialog.describeRange(1, "main"));
        assertEquals("7 commits", CreatePullRequestDialog.describeRange(7, "main"));
    }

    /** A full page stopped at the limit, so the exact number is not known. */
    @Test
    void aFullPageIsReportedAsMoreThanTheLimit() {
        assertEquals("50+ commits", CreatePullRequestDialog.describeRange(50, "main"),
                "the read stopped at the limit, and printing a bare 50 would state a "
                        + "guess as a fact");
    }

    /** "Create pull request" after a first push opens the form filled in, ready to send. */
    @Test
    void aDraftFillsTheFormIn() {
        CreatePullRequestDialog d = dialog(List.of("feature/x"), "feature/x");
        FxHarness.run(() -> {
            d.prefill(new CreatePullRequestDialog.Draft("Tidy the parser", "## Why\n", "feature/x", "main"));

            assertEquals("Tidy the parser", titleField(d).getText());
            assertEquals("## Why\n", descriptionField(d).getText());
            assertEquals("feature/x", sourceCombo(d).getValue());
            assertEquals("main", targetCombo(d).getValue(),
                    "a default branch that exists only on the remote is added to the pickers");
            assertFalse(okButton(d).isDisabled(), "nothing left to fill in");
        });
    }

    @Test
    void aDraftLeavesWhatItDoesNotSay() {
        CreatePullRequestDialog d = dialog(List.of("main", "feature"), "feature");
        FxHarness.run(() -> {
            titleField(d).setText("Typed");
            d.prefill(new CreatePullRequestDialog.Draft("", "", null, "main"));
            assertEquals("Typed", titleField(d).getText());
            assertEquals("feature", sourceCombo(d).getValue());
            d.prefill(null);
        });
    }

    @Test
    void withoutARangeLookupTheFormStillWorks() {
        CreatePullRequestDialog d = dialog(List.of("main", "feature"), "feature");

        assertFalse(FxHarness.lookupAll(d.getDialogPane(), "pr-preview-list").isEmpty(),
                "the preview is present even when nothing can fill it");
        FxHarness.run(() -> {
            titleField(d).setText("A title");
            targetCombo(d).getSelectionModel().select("main");
        });
        assertFalse(okButton(d).isDisabled(),
                "a dialog with no way to read the range must still be able to send");
    }
}
