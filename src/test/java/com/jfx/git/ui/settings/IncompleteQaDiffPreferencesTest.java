package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.Settings;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.SettingsSection;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies Diff preference controls write their values and reject a malformed width. */
class IncompleteQaDiffPreferencesTest {
    @TempDir Path temp;
    private SettingsTab tab;

    @AfterEach
    void cleanup() {
        FxHarness.run(() -> {
            if (tab != null) tab.dispose();
            Settings.flush();
            Settings.useFile(null);
        });
        FxHarness.closeShownStages();
    }

    @Test
    void diffControlsPersistLiveAndWidthAcceptsOnlyDigits() throws Exception {
        Settings.useFile(temp.resolve("isolated-settings.properties"));
        FxHarness.start();
        final Parent[] page = new Parent[1];
        FxHarness.run(() -> {
            tab = new SettingsTab();
            TabPane tabs = SettingsTabs.show(tab);
            SettingsTabs.at(tabs, tab, SettingsSection.DIFF);
            page[0] = tab.page(SettingsSection.DIFF);
            ((CheckBox) page[0].lookup(".check-box")).setSelected(true);
            ((CheckBox) FxHarness.lookupAll(page[0], "check-box").stream()
                    .filter(CheckBox.class::isInstance)
                    .map(CheckBox.class::cast)
                    .filter(box -> box.getText().equals("Show the whole file by default"))
                    .findFirst().orElseThrow()).setSelected(true);
            TextField width = (TextField) page[0].lookupAll(".text-field").stream()
                    .filter(TextField.class::isInstance).findFirst().orElseThrow();
            width.setText("23");
        });

        assertTrue(AppPreferences.splitDiffByDefault());
        assertTrue(AppPreferences.wholeFileByDefault());
        assertEquals(23, AppPreferences.diffScrollMapWidth());
        FxHarness.run(() -> {
            TextField width = (TextField) page[0].lookupAll(".text-field").stream()
                    .filter(TextField.class::isInstance).findFirst().orElseThrow();
            width.setText("2x");
            assertEquals("23", width.getText(), "invalid edits are rolled back in the actual field");
        });
        assertEquals(23, AppPreferences.diffScrollMapWidth());
        Settings.flush();
        Settings.useFile(temp.resolve("isolated-settings.properties"));
        assertTrue(AppPreferences.splitDiffByDefault());
        assertTrue(AppPreferences.wholeFileByDefault());
        assertEquals(23, AppPreferences.diffScrollMapWidth());
    }
}
