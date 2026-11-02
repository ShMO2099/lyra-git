package com.jfx.git.ui;

import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.lang.ref.WeakReference;

/**
 * Makes every dialog belong to the application's main window.
 *
 * <h2>Why</h2>
 * A window without an owner is a top-level window of its own: on Windows it gets its own
 * button on the taskbar, it can fall behind the main window, and it opens wherever the
 * platform likes. Almost every dialog here was created that way - a confirmation, a
 * rename, a merge-message editor each turned up as one more Lyra on the taskbar. Owned
 * by the main window, a dialog stays in front of it, opens over it, minimises with it and
 * never appears on the taskbar.
 *
 * <p>Called from the two places nearly every dialog already passes through -
 * {@link com.jfx.git.ui.dialogs.JfxDialog#shell} and {@link ThemeManager#style} - and
 * directly by the few that pass through neither. {@code DialogOwnershipTest} keeps it that
 * way.
 *
 * <p>Before the main window exists (startup prompts), and in tests that never register
 * one, a dialog is left without an owner, exactly as before.
 */
public final class DialogOwner {

    private static volatile WeakReference<Window> main = new WeakReference<>(null);

    private DialogOwner() {
    }

    /** Names the window every dialog from now on belongs to. */
    public static void useMainWindow(Window window) {
        main = new WeakReference<>(window);
    }

    /** The main window, or null when none is registered or it is not showing. */
    public static Window mainWindow() {
        Window window = main.get();
        return window != null && window.isShowing() ? window : null;
    }

    /**
     * Gives {@code dialog} the main window as its owner, unless it has one already or is
     * already on screen (an owner can only be set before a window first shows). Returns
     * the dialog, so it can wrap a construction.
     */
    public static <D extends Dialog<?>> D adopt(D dialog) {
        if (dialog == null || dialog.getOwner() != null || dialog.isShowing()) return dialog;
        Window owner = mainWindow();
        if (owner != null) {
            try {
                dialog.initOwner(owner);
            } catch (IllegalStateException alreadyShown) {
                // Shown once before and reused: it keeps whatever it had.
            }
        }
        return dialog;
    }

    /** {@link #adopt(Dialog)} for a window built as a {@link Stage}. */
    public static <S extends Stage> S adopt(S stage) {
        if (stage == null || stage.getOwner() != null || stage.isShowing()) return stage;
        Window owner = mainWindow();
        if (owner != null && owner != stage) {
            try {
                stage.initOwner(owner);
            } catch (IllegalStateException alreadyShownOrPrimary) {
                // A stage that has been shown, or the primary stage, keeps what it had.
            }
        }
        return stage;
    }
}
