package com.jfx.git.ui;

/**
 * The sections of the Settings tab, in the order its navigation lists them.
 *
 * <p>Preferences, Profiles and Integrations were three separate windows; they are one tab
 * now, the way commercial clients keep them under Preferences. A caller that wants a particular
 * one - "Connect GitHub…" in an empty pull-request list - opens the tab at its section
 * through {@link AppNavigator#openSettings}.
 */
public enum SettingsSection {
    GENERAL("General"),
    APPEARANCE("Appearance"),
    GIT_ENGINE("Git Engine"),
    EXTERNAL_TOOLS("External Tools"),
    DIFF("Diff"),
    SSH("SSH"),
    COMMIT("Commit"),
    PROFILES("Profiles"),
    INTEGRATIONS("Integrations"),
    KEYBOARD_SHORTCUTS("Keyboard shortcuts"),
    /**
     * One open repository's own settings - last, because it is the one section about a
     * repository rather than the application; see {@link AppNavigator#openRepositorySettings}.
     */
    REPOSITORY("Repository");

    private final String label;

    SettingsSection(String label) {
        this.label = label;
    }

    /** The name the navigation shows. */
    public String label() {
        return label;
    }
}
