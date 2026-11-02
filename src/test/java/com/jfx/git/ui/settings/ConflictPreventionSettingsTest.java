package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.ConflictBase;
import com.jfx.git.core.RepositoryPreferences;
import com.jfx.git.testsupport.LayoutAssert;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.SettingsSection;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conflict-prevention controls: the per-repository part the Repository section
 * embeds, and the switches on the General page - each bound to what it stores.
 */
class ConflictPreventionSettingsTest {

    @TempDir
    Path tempDir;

    private SettingsTab tab;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void restore() {
        FxHarness.run(() -> {
            AppPreferences.setWarnAboutConflicts(true);
            AppPreferences.setSnapshotIntervalMinutes(10);
            RepositoryPreferences.clear(tempDir.toFile());
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
    }

    private static <T extends Node> T find(Node root, String id, Class<T> type) {
        Node found = root.lookup("#" + id);
        assertNotNull(found, "no #" + id);
        return type.cast(found);
    }

    @Test
    void withoutARepositoryItSaysSo() {
        String shown = FxHarness.call(() -> {
            Parent box = (Parent) ConflictPreventionSettings.section(null);
            return box.getChildrenUnmodifiable().stream()
                    .filter(Label.class::isInstance).map(n -> ((Label) n).getText())
                    .filter(t -> t.startsWith("Open a repository")).findFirst().orElse(null);
        });
        assertEquals("Open a repository to change its settings.", shown);
    }

    @Test
    void theRepositorySwitchAndPatternsAreStoredForThatRepository() {
        File repository = tempDir.toFile();
        FxHarness.run(() -> {
            Parent box = (Parent) ConflictPreventionSettings.section(repository);
            CheckBox enabled = find(box, ConflictPreventionSettings.REPOSITORY_SWITCH_ID, CheckBox.class);
            TextArea patterns = find(box, ConflictPreventionSettings.PATTERNS_ID, TextArea.class);
            assertTrue(enabled.isSelected(), "on until turned off");
            assertEquals(ConflictBase.DEFAULT_PATTERNS, patterns.getText());

            patterns.setText("origin/main\n!origin/wip/**");
            assertEquals(Optional.of("origin/main\n!origin/wip/**"),
                    RepositoryPreferences.conflictBasePatterns(repository));
            patterns.setText("");
            assertEquals(Optional.empty(), RepositoryPreferences.conflictBasePatterns(repository),
                    "blank goes back to the default");
            assertEquals(ConflictBase.DEFAULT_PATTERNS,
                    RepositoryPreferences.effectiveConflictBasePatterns(repository));

            enabled.setSelected(false);
            assertEquals(Optional.of(false), RepositoryPreferences.conflictWarning(repository));
            assertFalse(RepositoryPreferences.effectiveConflictWarning(repository));
            assertTrue(patterns.isDisabled(), "patterns mean nothing while it is off");
            assertTrue(RepositoryPreferences.hasOverrides(repository));

            enabled.setSelected(true);
            assertEquals(Optional.empty(), RepositoryPreferences.conflictWarning(repository),
                    "back on is no override at all");
        });
    }

    @Test
    void theGlobalSwitchTurnsEveryRepositoryOff() {
        File repository = tempDir.toFile();
        FxHarness.run(() -> {
            tab = new SettingsTab();
            TabPane shown = SettingsTabs.show(tab);
            Parent page = SettingsTabs.at(shown, tab, SettingsSection.GENERAL);
            CheckBox warn = find(page, ConflictPreventionSettings.GLOBAL_SWITCH_ID, CheckBox.class);
            @SuppressWarnings("unchecked")
            ChoiceBox<Integer> interval = find(page, ConflictPreventionSettings.SNAPSHOT_INTERVAL_ID, ChoiceBox.class);
            assertTrue(warn.isSelected());
            assertEquals(10, interval.getValue());

            warn.setSelected(false);
            assertFalse(AppPreferences.warnAboutConflicts());
            assertFalse(RepositoryPreferences.effectiveConflictWarning(repository));
            CheckBox perRepository = find(ConflictPreventionSettings.section(repository),
                    ConflictPreventionSettings.REPOSITORY_SWITCH_ID, CheckBox.class);
            assertTrue(perRepository.isDisabled(), "nothing to turn on here while it is off for all");

            interval.setValue(0);
            assertEquals(0, AppPreferences.snapshotIntervalMinutes(), "0 turns the timer off");
        });
    }

    /** Laid out at the Settings tab's width: nothing cut short, help included. */
    @Test
    void theRepositoryPartShowsItsTextWhole() {
        File repository = tempDir.toFile();
        var problems = FxHarness.call(() -> {
            ThemeManager.apply(ThemeManager.Theme.DARK);
            StackPane root = new StackPane(ConflictPreventionSettings.section(repository));
            Scene scene = new Scene(root, 900, 600);
            ThemeManager.register(scene);
            try {
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.layout();
                return LayoutAssert.truncatedTexts(root);
            } finally {
                ThemeManager.unregister(scene);
            }
        });
        LayoutAssert.failWith("Conflict prevention at 900px", problems);
    }
}
