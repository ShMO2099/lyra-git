package com.jfx.git.ui;

import com.jfx.git.core.SingleInstance;
import com.jfx.git.ui.dialogs.Dialogs;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.nio.file.Path;

/**
 * What a second copy of the application says before it exits: that one is running
 * already, and to use that one. See {@link SingleInstance} for why only one may run.
 *
 * <p>It opens no repository, reads no session and writes no setting - all of that belongs
 * to the copy already running - and uses the saved theme only to look like the rest of
 * the application. When it is closed, the running copy is asked to bring its window
 * forward, so the user lands where they meant to go.
 */
public final class AlreadyRunningNotice {

    private AlreadyRunningNotice() {
    }

    /** The dialog, built and not shown. */
    static Alert build() {
        return Dialogs.info("Lyra")
                .icon(Icons.warning())
                .tone(JfxDialog.Tone.CAUTION)
                .title("Lyra is already running")
                .message("Only one copy runs at a time, so that two windows never write over each "
                        + "other's settings and open tabs. Use the window that is already open.")
                .build();
    }

    /**
     * Shows the notice, then asks the copy holding {@code lockFile} to show itself and ends
     * this one. Returns at once; the toolkit keeps the process alive until the notice closes.
     */
    public static void showAndExit(Path lockFile) {
        Runnable notice = () -> {
            try {
                ThemeManager.applySavedWithoutSaving();
                build().showAndWait();
                SingleInstance.requestShow(lockFile);
            } finally {
                Platform.exit();
            }
        };
        try {
            Platform.startup(notice);
        } catch (IllegalStateException alreadyStarted) {
            // Launched with JavaFX on the module path, the JDK's launcher has started the
            // toolkit before main runs.
            Platform.runLater(notice);
        }
    }
}
