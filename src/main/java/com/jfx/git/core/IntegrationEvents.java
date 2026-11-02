package com.jfx.git.core;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;

/**
 * What the screens listen to so a provider connection shows up the moment it happens.
 *
 * <p>The sessions are plain fields, and every screen used to read them once, when it was
 * built. A remembered token is restored in the background after the window opens, so
 * anything opened in those first seconds - Manage Cloud Integrations, the pull request
 * list, the clone dialog's account browsers - said "Not connected" and kept saying it,
 * which read as "the token was not remembered". Connecting from a list's own prompt did
 * not reload the list either. Now the sessions bump {@link #changesProperty()} on every
 * connect and disconnect, and {@link #restoringProperty()} says a restore is still on its
 * way, so a screen can say "Reconnecting..." rather than "Not connected".
 *
 * <p>Both properties change on the FX thread, wherever the change came from.
 */
public final class IntegrationEvents {

    private static final ReadOnlyLongWrapper CHANGES = new ReadOnlyLongWrapper(0);
    private static final ReadOnlyBooleanWrapper RESTORING = new ReadOnlyBooleanWrapper(false);
    private static final javafx.beans.property.ReadOnlyObjectWrapper<com.jfx.git.core.hosting.HostingProvider>
            GIT_LOGIN_REFUSED = new javafx.beans.property.ReadOnlyObjectWrapper<>();

    private IntegrationEvents() {
    }

    /** Counts connects and disconnects of any provider; listen to it, the value means nothing. */
    public static ReadOnlyLongProperty changesProperty() {
        return CHANGES.getReadOnlyProperty();
    }

    /** True while remembered tokens are being restored at startup. */
    public static ReadOnlyBooleanProperty restoringProperty() {
        return RESTORING.getReadOnlyProperty();
    }

    public static boolean isRestoring() {
        return RESTORING.get();
    }

    /** Called by the sessions on connect and disconnect. */
    public static void changed() {
        onFx(() -> CHANGES.set(CHANGES.get() + 1));
    }

    /**
     * The provider whose connected account git just refused as a login, for the status bar
     * to say that the user's own credentials were used instead. Set to null and back, so
     * a second refusal of the same provider is still a change.
     */
    public static javafx.beans.property.ReadOnlyObjectProperty<com.jfx.git.core.hosting.HostingProvider>
            gitLoginRefusedProperty() {
        return GIT_LOGIN_REFUSED.getReadOnlyProperty();
    }

    /** Called by the credentials manager when git refuses a connected account. */
    public static void gitLoginRefused(com.jfx.git.core.hosting.HostingProvider provider) {
        onFx(() -> {
            GIT_LOGIN_REFUSED.set(null);
            GIT_LOGIN_REFUSED.set(provider);
        });
    }

    static void setRestoring(boolean restoring) {
        onFx(() -> RESTORING.set(restoring));
    }

    /**
     * On the FX thread: now if already there, otherwise queued. A plain JVM with no
     * toolkit - a core unit test - just sets the value, since nothing could be listening
     * on a thread that does not exist.
     */
    private static void onFx(Runnable change) {
        if (Platform.isFxApplicationThread()) {
            change.run();
            return;
        }
        try {
            Platform.runLater(change);
        } catch (IllegalStateException noToolkit) {
            change.run();
        }
    }
}
