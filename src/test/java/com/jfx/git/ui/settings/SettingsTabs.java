package com.jfx.git.ui.settings;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.SettingsSection;

import javafx.scene.Parent;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;

/**
 * Puts a {@link SettingsTab} on screen the way the application does: in a tab pane, on a
 * stage of the main window's size, with the application's stylesheets.
 *
 * <p>The pages were tested inside the Preferences window; a page that fits a dialog is
 * not thereby known to fit a tab, so they are measured where they now live. Call from the
 * FX thread; close with {@link FxHarness#closeShownStages()}.
 */
final class SettingsTabs {

    /** The main window's default size, less nothing: what a user first sees. */
    static final double WIDTH = 1280;
    static final double HEIGHT = 800;

    private SettingsTabs() {
    }

    /** Shows {@code tab} at the main window's size and returns the scene's root. */
    static TabPane show(SettingsTab tab) {
        return show(tab, WIDTH, HEIGHT);
    }

    static TabPane show(SettingsTab tab, double width, double height) {
        TabPane pane = new TabPane(tab);
        FxHarness.showRegistered(pane, width, height);
        FxHarness.pulse(pane);
        return pane;
    }

    /** Selects {@code section} and lays the scene out again, so its page is measured. */
    static Parent at(TabPane shown, SettingsTab tab, SettingsSection section) {
        tab.select(section);
        FxHarness.pulse(shown);
        // A wrapped label settles its height on the pass after its width is known.
        FxHarness.pulse(shown);
        return shown;
    }

    @SuppressWarnings("unchecked")
    static ListView<SettingsSection> nav(SettingsTab tab) {
        return (ListView<SettingsSection>) FxHarness.lookupAll(tab.getContent(), "nav-list").get(0);
    }
}
