package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.BranchIssueKey;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.SettingsSection;

import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Commit section of the Settings tab: every control stores what it shows, as it changes. */
class CommitPageTest {

    private SettingsTab tab;

    @BeforeEach
    void start() {
        FxHarness.start();
    }

    @AfterEach
    void restoreDefaults() {
        FxHarness.run(() -> {
            AppPreferences.setPushAfterCommit(false);
            AppPreferences.setIssueKeyFromBranch(true);
            AppPreferences.setIssueKeyPattern(null);
            AppPreferences.setIssueKeyFormat(null);
            AppPreferences.setCheckSecretsBeforeCommit(true);
            AppPreferences.setCheckLargeFilesBeforeCommit(true);
            AppPreferences.setLargeFileThresholdMb(AppPreferences.DEFAULT_LARGE_FILE_MB);
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
    }

    /** The Commit page, on screen in a Settings tab. Call on the FX thread. */
    private Parent page() {
        tab = new SettingsTab();
        SettingsTabs.at(SettingsTabs.show(tab), tab, SettingsSection.COMMIT);
        return tab.page(SettingsSection.COMMIT);
    }

    @Test
    void theCommitSectionComesRightAfterSsh() {
        var sections = java.util.List.of(SettingsSection.values());
        assertEquals(sections.indexOf(SettingsSection.SSH) + 1, sections.indexOf(SettingsSection.COMMIT));
        assertEquals("Commit", SettingsSection.COMMIT.label());
    }

    @Test
    void theDefaultsAreWhatThePlanSays() {
        assertFalse(AppPreferences.pushAfterCommit(), "pushing is chosen, not assumed");
        assertTrue(AppPreferences.issueKeyFromBranch());
        assertEquals(BranchIssueKey.DEFAULT_PATTERN, AppPreferences.issueKeyPattern());
        assertEquals("{key}: ", AppPreferences.issueKeyFormat());
        assertTrue(AppPreferences.checkSecretsBeforeCommit());
        assertTrue(AppPreferences.checkLargeFilesBeforeCommit());
        assertEquals(50, AppPreferences.largeFileThresholdMb());
    }

    @Test
    void everySwitchIsStoredAsItIsChanged() {
        FxHarness.run(() -> {
            Parent page = page();
            ((CheckBox) page.lookup("#commit-push-after")).setSelected(true);
            ((CheckBox) page.lookup("#commit-issue-key")).setSelected(false);
            ((CheckBox) page.lookup("#commit-check-secrets")).setSelected(false);
            ((CheckBox) page.lookup("#commit-check-large")).setSelected(false);
            assertTrue(page.lookup("#commit-issue-pattern").isDisabled(), "no key, so no pattern to edit");
            assertTrue(page.lookup("#commit-large-threshold").isDisabled());
        });
        assertTrue(AppPreferences.pushAfterCommit());
        assertFalse(AppPreferences.issueKeyFromBranch());
        assertFalse(AppPreferences.checkSecretsBeforeCommit());
        assertFalse(AppPreferences.checkLargeFilesBeforeCommit());
    }

    @Test
    void theIssueKeyFieldsPreviewTheirResult() {
        FxHarness.run(() -> {
            Parent page = page();
            Label preview = (Label) page.lookup("#commit-issue-preview");
            assertTrue(preview.getText().contains("\"ABC-123: \""), preview.getText());

            ((TextField) page.lookup("#commit-issue-format")).setText("[{key}] ");
            assertTrue(preview.getText().contains("\"[ABC-123] \""), preview.getText());
        });
        assertEquals("[{key}] ", AppPreferences.issueKeyFormat());
    }

    @Test
    void aPatternThatDoesNotCompileIsNotStored() {
        FxHarness.run(() -> {
            Parent page = page();
            TextField pattern = (TextField) page.lookup("#commit-issue-pattern");
            pattern.setText("[A-Z]+-(\\d+)");
            pattern.setText("[A-Z");
            Label preview = (Label) page.lookup("#commit-issue-preview");
            assertTrue(preview.getText().contains("not a regular expression"), preview.getText());
        });
        assertEquals("[A-Z]+-(\\d+)", AppPreferences.issueKeyPattern(), "the last pattern that compiled is kept");
    }

    @Test
    void theLargeFileSizeTakesDigitsOnly() {
        FxHarness.run(() -> {
            TextField threshold = (TextField) page().lookup("#commit-large-threshold");
            assertEquals("50", threshold.getText());
            threshold.setText("120");
            threshold.setText("12x");
            assertEquals("120", threshold.getText(), "a letter is refused");
        });
        assertEquals(120, AppPreferences.largeFileThresholdMb());
    }
}
