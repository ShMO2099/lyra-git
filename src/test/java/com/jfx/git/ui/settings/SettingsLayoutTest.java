package com.jfx.git.ui.settings;

import com.jfx.git.testsupport.LayoutAssert;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.SettingsSection;
import com.jfx.git.ui.Shortcuts;
import com.jfx.git.ui.ThemeManager;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings as it opens: every section, in a tab of the main window's size, laid out with
 * the application's stylesheets.
 *
 * <p>The Preferences window once opened at a size where the first page was cut off
 * mid-sentence, with its labels truncated against a scrollbar; the Integrations window
 * cut "Not connected" to "Not connec…". Each section is checked here for the two things a
 * settings page must not do: cut a label short, or scroll sideways.
 */
class SettingsLayoutTest {

    private SettingsTab tab;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void close() {
        FxHarness.run(() -> {
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
    }

    /** A tab with a keyboard map of its own, so the shortcut page has rows to measure. */
    private SettingsTab tabWithShortcuts() {
        Shortcuts keys = new Shortcuts();
        keys.add(KeyCode.P, "Command palette", () -> { });
        keys.add(KeyCode.COMMA, "Settings", () -> { });
        keys.section("Git actions");
        keys.add(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                "Stage all and commit", () -> { });
        return new SettingsTab(null, null, keys::bindings);
    }

    @Test
    void everySectionShowsItsLabelsWholeAndNeverScrollsSideways() {
        List<String> problems = FxHarness.call(() -> {
            ThemeManager.apply(ThemeManager.Theme.DARK);
            tab = tabWithShortcuts();
            TabPane shown = SettingsTabs.show(tab);

            List<String> found = new ArrayList<>();
            for (SettingsSection section : SettingsSection.values()) {
                Parent root = SettingsTabs.at(shown, tab, section);
                for (String cut : LayoutAssert.truncatedTexts(root)) {
                    found.add(section.label() + ": cut off: " + cut);
                }
                for (Node node : LayoutAssert.visibleNodes(root, n -> n instanceof ScrollBar)) {
                    ScrollBar bar = (ScrollBar) node;
                    if (bar.getOrientation() == Orientation.HORIZONTAL
                            && bar.getVisibleAmount() < bar.getMax() - bar.getMin()) {
                        found.add(section.label() + ": scrolls sideways");
                    }
                }
            }
            return found;
        });

        LayoutAssert.failWith("Settings sections at " + (int) SettingsTabs.WIDTH + "x" + (int) SettingsTabs.HEIGHT,
                problems);
    }

    /**
     * Wrapped text cannot be checked by width - it fails by height - but a non-wrapping
     * label or button given less than it needs is cut, and the cards on the Integrations
     * page were where that happened: a name, a host, a status chip and up to three buttons
     * in one row.
     */
    @Test
    void noControlIsGivenLessWidthThanItsTextNeeds() {
        List<String> problems = FxHarness.call(() -> {
            tab = tabWithShortcuts();
            TabPane shown = SettingsTabs.show(tab);
            List<String> found = new ArrayList<>();
            for (SettingsSection section : SettingsSection.values()) {
                Parent root = SettingsTabs.at(shown, tab, section);
                for (Node node : LayoutAssert.visibleNodes(root,
                        n -> n instanceof javafx.scene.control.Label || n instanceof javafx.scene.control.Button)) {
                    javafx.scene.control.Labeled part = (javafx.scene.control.Labeled) node;
                    if (part.getText() == null || part.getText().isBlank() || part.isWrapText()) continue;
                    if (part.getWidth() + 0.5 < part.prefWidth(-1)) {
                        found.add(section.label() + ": \"" + part.getText() + "\" given " + part.getWidth()
                                + "px, needs " + part.prefWidth(-1) + "px");
                    }
                }
            }
            return found;
        });
        LayoutAssert.failWith("Settings controls cut short", problems);
    }
}
