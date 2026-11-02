package com.jfx.git.core;

import java.awt.Desktop;
import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Hands a working-tree file to whichever application the platform associates with it.
 *
 * <p>The obvious implementation on Windows - {@code cmd.exe /c start "" <path>} - has
 * two problems that made "Edit this file" look broken. {@code cmd.exe} is a console
 * program, so launching it from a windowed application flashes a console on screen for
 * a fraction of a second; and {@code start} exits 0 whatever happens, so a file with no
 * associated application produced no message at all. Neither is recoverable by checking
 * the exit code, because there is nothing wrong with it.
 *
 * <p>So the desktop API comes first - it opens nothing of its own and therefore cannot
 * flash - and the per-platform commands are only a fallback, chosen so that none of
 * them spawns a console either.
 */
public final class FileOpener {

    private FileOpener() {
    }

    /**
     * Opens {@code target}, blocking until the launch has been handed off.
     *
     * <p>Call this off the FX thread: starting an editor can take seconds, and on the
     * FX thread that is a frozen window.
     */
    public static GitResult open(File target) {
        if (target == null) {
            return GitResult.failed("No file to open");
        }
        if (!target.isFile()) {
            return GitResult.failed(target.getName() + " does not exist in the working tree");
        }

        String viaDesktop = openWithDesktop(target);
        if (viaDesktop == null) {
            return GitResult.ok("Opened " + target.getName());
        }

        String viaCommand = openWithCommand(target);
        if (viaCommand == null) {
            return GitResult.ok("Opened " + target.getName());
        }

        // Both routes failed: report the desktop failure, which is the more specific of
        // the two, and name the fallback so the cause is not hidden.
        return GitResult.failed("Could not open " + target.getName()
                + ": " + viaDesktop + " (and " + viaCommand + ")");
    }

    /**
     * Opens a folder in the platform's file manager, creating it first if it is not there.
     *
     * <p>{@link #open} is for files, and refuses anything else: "Open drafts folder" in
     * Preferences called it with a directory, was told the directory "does not exist in
     * the working tree", and did nothing, for as long as the button has existed. A
     * folder is opened by the same two routes - the desktop integration, then the
     * platform's launcher - and never by the user's editor command, which is for files.
     *
     * <p>Blocks until the launch is handed off; call it off the FX thread.
     */
    public static GitResult openFolder(File folder) {
        String problem = ensureFolder(folder);
        if (problem != null) {
            return GitResult.failed(problem);
        }
        String viaDesktop = openWithDesktop(folder);
        if (viaDesktop == null) {
            return GitResult.ok("Opened " + folder.getName());
        }
        String viaCommand = openWithCommand(folder);
        if (viaCommand == null) {
            return GitResult.ok("Opened " + folder.getName());
        }
        return GitResult.failed("Could not open " + folder.getName()
                + ": " + viaDesktop + " (and " + viaCommand + ")");
    }

    /**
     * Makes sure {@code folder} is a directory that exists, creating it if need be.
     *
     * @return why it is not and could not be made, or {@code null} when it is there
     */
    static String ensureFolder(File folder) {
        if (folder == null) {
            return "No folder to open";
        }
        if (folder.isDirectory() || folder.mkdirs()) {
            return null;
        }
        return "The folder " + folder.getName() + " does not exist and could not be created";
    }

    /**
     * Opens {@code target} the way the user's preferences say to.
     *
     * <p>{@link AppPreferences.EditorMode#BUILT_IN} is not handled here - that one
     * never leaves the application - so it is treated as the system default, which is
     * what a caller asking to open externally means by it.
     */
    public static GitResult openPreferred(File target) {
        if (AppPreferences.editorMode() == AppPreferences.EditorMode.CUSTOM) {
            String template = AppPreferences.editorCommand();
            if (template.isBlank()) {
                return GitResult.failed(
                        "No custom editor command is configured - set one in Settings");
            }
            return runEditor(AppPreferences.buildEditorCommand(template, target), target);
        }
        return open(target);
    }

    /** Launches a configured editor command. */
    private static GitResult runEditor(List<String> command, File target) {
        if (command.isEmpty()) {
            return GitResult.failed("The custom editor command is empty");
        }
        try {
            // No waitFor: an editor is expected to keep running, and waiting on one
            // would block the caller until the user closed it.
            new ProcessBuilder(command).start();
            return GitResult.ok("Opened " + target.getName() + " in " + command.get(0));
        } catch (Exception e) {
            return GitResult.failed("Could not run " + command.get(0) + ": " + describe(e));
        }
    }

    /**
     * @return null on success, otherwise why it could not be used
     */
    private static String openWithDesktop(File target) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return "no desktop integration on this platform";
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                return "this platform cannot open files";
            }
            desktop.open(target);
            return null;
        } catch (Throwable t) {
            // Throwable: a headless or trimmed JRE surfaces this as an Error, and an
            // unopenable file must not take the application down with it.
            return describe(t);
        }
    }

    /** @return null on success, otherwise why it failed */
    private static String openWithCommand(File target) {
        List<String> command = command(System.getProperty("os.name", ""), target.getAbsolutePath());
        try {
            Process process = new ProcessBuilder(command).start();
            // These launchers all hand off and exit immediately; the application they
            // start keeps running on its own.
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "the launcher did not return";
            }
            return process.exitValue() == 0
                    ? null
                    : command.get(0) + " exited with " + process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return describe(e);
        }
    }

    /**
     * The launcher for a platform.
     *
     * <p>Windows uses {@code rundll32} rather than {@code cmd /c start}: it consults the
     * same file associations without being a console program, so nothing flashes.
     */
    static List<String> command(String osName, String path) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", path);
        }
        return List.of("xdg-open", path);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
