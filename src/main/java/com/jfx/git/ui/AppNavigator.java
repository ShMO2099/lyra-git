package com.jfx.git.ui;

/**
 * Opens the application's own tabs - Settings, the activity log - from anywhere.
 *
 * <p>They used to be dialogs anyone could construct and show. As tabs they belong to the
 * main window's tab strip, which only {@code LyraApp} holds; this is how a pull-request
 * list's "Connect GitHub…", the toolbar's "Open integrations" or the command palette reach
 * it without being handed the application.
 *
 * <p>With no application registered (a unit test building one component) the calls do
 * nothing and return false.
 */
public final class AppNavigator {

    /** What the application does when asked; implemented by {@code LyraApp}. */
    public interface Target {
        void openSettings(SettingsSection section);

        /** Settings at its Repository section, showing {@code repository}'s settings. */
        void openRepositorySettings(java.io.File repository);

        void openActivityLog();
    }

    /**
     * Held weakly, as {@link DialogOwner} holds the main window: this outlives every
     * application started in the process - a test JVM starts dozens - and must not keep a
     * finished one, with every tab it built, alive. An application is reachable for as
     * long as it runs; a test registering a stand-in keeps its own reference.
     */
    private static volatile java.lang.ref.WeakReference<Target> target = new java.lang.ref.WeakReference<>(null);

    private AppNavigator() {
    }

    /** Registers the application; called once at startup. */
    public static void use(Target app) {
        target = new java.lang.ref.WeakReference<>(app);
    }

    /** Opens the Settings tab at {@code section}, or selects it there if it is open. */
    public static boolean openSettings(SettingsSection section) {
        Target t = target.get();
        if (t == null) return false;
        t.openSettings(section);
        return true;
    }

    /**
     * Opens the Settings tab at its Repository section, on {@code repository} - the
     * sidebar's "Repository settings…" and the command palette's entry for it.
     */
    public static boolean openRepositorySettings(java.io.File repository) {
        Target t = target.get();
        if (t == null) return false;
        t.openRepositorySettings(repository);
        return true;
    }

    /** Opens the activity log tab, or selects it if it is open. */
    public static boolean openActivityLog() {
        Target t = target.get();
        if (t == null) return false;
        t.openActivityLog();
        return true;
    }
}
