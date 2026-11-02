package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.BranchIssueKey;
import com.jfx.git.ui.RightPanelManager;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import static com.jfx.git.ui.settings.SettingsLayout.hint;
import static com.jfx.git.ui.settings.SettingsLayout.page;
import static com.jfx.git.ui.settings.SettingsLayout.row;
import static com.jfx.git.ui.settings.SettingsLayout.section;

/**
 * How the commit box behaves: pushing after a commit, the issue key a summary starts
 * with, and the checks run before git is asked to commit.
 *
 * <p>Changes apply as they are made, like every other page: each one tells the commit
 * boxes on screen through {@link RightPanelManager#commitSettingsChanged()}, so the
 * button reads "Commit and push" the moment the box is ticked rather than after the next
 * refresh.
 */
final class CommitPage extends VBox {

    /** The branch the issue-key preview is worked out on. */
    static final String SAMPLE_BRANCH = "feature/ABC-123-login-form";

    CommitPage() {
        VBox content = page("Commit");

        // --- Pushing ---
        CheckBox pushAfter = new CheckBox("Push after each commit");
        pushAfter.setId("commit-push-after");
        pushAfter.setSelected(AppPreferences.pushAfterCommit());
        pushAfter.selectedProperty().addListener((o, was, on) -> {
            AppPreferences.setPushAfterCommit(on);
            RightPanelManager.commitSettingsChanged();
        });

        // --- Issue key ---
        CheckBox issueKey = new CheckBox("Start the summary with the branch's issue key");
        issueKey.setId("commit-issue-key");
        issueKey.setSelected(AppPreferences.issueKeyFromBranch());

        TextField pattern = new TextField(AppPreferences.issueKeyPattern());
        pattern.setId("commit-issue-pattern");
        pattern.setPrefColumnCount(20);
        TextField format = new TextField(AppPreferences.issueKeyFormat());
        format.setId("commit-issue-format");
        format.setPrefColumnCount(12);

        Label preview = hint("");
        preview.setId("commit-issue-preview");
        Runnable showPreview = () -> {
            String typed = pattern.getText();
            if (!BranchIssueKey.isValidPattern(typed)) {
                preview.setText("That is not a regular expression, so the last one that was is still used.");
                return;
            }
            String prefix = BranchIssueKey.prefixFor(SAMPLE_BRANCH, typed, format.getText());
            preview.setText(prefix.isEmpty()
                    ? "On " + SAMPLE_BRANCH + " this finds no key."
                    : "On " + SAMPLE_BRANCH + " the summary starts \"" + prefix + "\".");
        };
        Runnable enableIssueFields = () -> {
            pattern.setDisable(!issueKey.isSelected());
            format.setDisable(!issueKey.isSelected());
        };
        issueKey.selectedProperty().addListener((o, was, on) -> {
            AppPreferences.setIssueKeyFromBranch(on);
            enableIssueFields.run();
            RightPanelManager.commitSettingsChanged();
        });
        pattern.textProperty().addListener((o, was, text) -> {
            // Stored only when it compiles: a half-typed expression would otherwise leave
            // every composer opened meanwhile without its key.
            if (BranchIssueKey.isValidPattern(text)) {
                AppPreferences.setIssueKeyPattern(text);
                RightPanelManager.commitSettingsChanged();
            }
            showPreview.run();
        });
        format.textProperty().addListener((o, was, text) -> {
            AppPreferences.setIssueKeyFormat(text);
            RightPanelManager.commitSettingsChanged();
            showPreview.run();
        });
        enableIssueFields.run();
        showPreview.run();

        // --- Checks ---
        CheckBox secrets = new CheckBox("Look for passwords, tokens and keys in staged changes");
        secrets.setId("commit-check-secrets");
        secrets.setSelected(AppPreferences.checkSecretsBeforeCommit());
        secrets.selectedProperty().addListener((o, was, on) -> AppPreferences.setCheckSecretsBeforeCommit(on));

        CheckBox large = new CheckBox("Warn about large files LFS does not track");
        large.setId("commit-check-large");
        large.setSelected(AppPreferences.checkLargeFilesBeforeCommit());

        TextField threshold = new TextField(Integer.toString(AppPreferences.largeFileThresholdMb()));
        threshold.setId("commit-large-threshold");
        threshold.setPrefColumnCount(6);
        // Digits only, like the General page's commit count: anything else cannot become
        // a size and would only leave the setter parsing garbage.
        threshold.textProperty().addListener((o, was, text) -> {
            if (text != null && !text.matches("\\d*")) {
                threshold.setText(was);
                return;
            }
            try {
                AppPreferences.setLargeFileThresholdMb(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                // Blank or mid-edit; the stored value is left as it was until this parses.
            }
        });
        large.selectedProperty().addListener((o, was, on) -> {
            AppPreferences.setCheckLargeFilesBeforeCommit(on);
            threshold.setDisable(!on);
        });
        threshold.setDisable(!large.isSelected());

        content.getChildren().addAll(
                section("Pushing"),
                pushAfter,
                hint("The commit button then reads \"Commit and push\". Ctrl+Shift+Enter commits "
                        + "and pushes whichever way this is set. An amend of a commit that is "
                        + "already pushed is never pushed for you: that needs a force push."),
                section("Issue key"),
                issueKey,
                row("Pattern", pattern),
                row("Prefix", format),
                hint("The pattern is a regular expression that finds the key in the branch name; "
                        + "with a group in it, the group is the key. {key} in the prefix is "
                        + "replaced by the key. Only an empty summary is filled, and never "
                        + "while amending."),
                preview,
                section("Before committing"),
                secrets,
                hint("Looks at the lines this commit adds for the shapes of AWS, GitHub, GitLab, "
                        + "Slack and Google keys and private keys, and at files named like "
                        + ".env or id_rsa."),
                large,
                row("Large file size (MB)", threshold),
                hint("A file over this size is better kept in Git LFS. Files LFS already "
                        + "tracks are not counted."),
                hint("Either check stops to ask before committing. You can still commit anyway."));
        getChildren().add(content);
    }
}
