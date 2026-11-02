package com.jfx.git.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Opens a terminal in a directory: the one the user named in Preferences, or the best
 * one this machine has.
 *
 * <p>It used to be {@code cmd.exe} on Windows and {@code x-terminal-emulator} everywhere
 * else, whatever was installed. That is the 1990s prompt on a machine with Windows
 * Terminal and PowerShell 7 on it, and nothing at all on a Linux desktop that is not
 * Debian's.
 *
 * <h2>Choosing one</h2>
 * A command in Preferences wins. {@value #DIR_TOKEN} in it is replaced with the
 * directory; the process is started there as well, so a command without the token still
 * opens in the right place whenever the terminal honours its working directory.
 *
 * <p>Otherwise the first of these that is on the {@code PATH}:
 * <ul>
 *   <li><b>Windows</b> - Windows Terminal, PowerShell 7, Windows PowerShell, and last
 *       {@code cmd}, which is always there.</li>
 *   <li><b>Linux</b> - {@code $TERMINAL} if it is set, then {@code x-terminal-emulator},
 *       GNOME Terminal, Konsole, Xfce Terminal, and {@code xterm}.</li>
 * </ul>
 *
 * <h2>Console programs on Windows</h2>
 * PowerShell and {@code cmd} are console programs. Started directly they attach to the
 * console of whatever started this application - none, from the installed launcher, so
 * they would get a window, but the terminal it was run from during development, where
 * they would not. {@code cmd /c start} asks for a new window either way.
 */
public final class TerminalLauncher {

    /** Replaced with the directory's absolute path in a custom command. */
    public static final String DIR_TOKEN = "{dir}";

    private TerminalLauncher() {
    }

    /** Opens a terminal in {@code directory}. Quick, and touches no repository. */
    public static GitResult open(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return GitResult.failed("There is no folder to open a terminal in");
        }
        String custom = AppPreferences.terminalCommand();
        List<String> command = custom.isBlank()
                ? detect(System.getProperty("os.name", ""), System.getenv(), TerminalLauncher::onPath, directory)
                : resolve(custom, directory);
        if (command.isEmpty()) {
            return GitResult.failed("No terminal was found on this machine. Set the command to use in"
                    + " Settings, under External Tools.");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory);
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            return GitResult.ok("Opened a terminal in " + directory.getName());
        } catch (Exception e) {
            String message = e.getMessage();
            return GitResult.failed("Could not start " + command.get(0) + ": "
                    + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message));
        }
    }

    /**
     * A custom command as arguments, with the directory in place of {@value #DIR_TOKEN}.
     *
     * <p>Substituted after splitting, so a directory with a space in its name stays one
     * argument whether or not the command quoted the token.
     */
    static List<String> resolve(String template, File directory) {
        List<String> command = new ArrayList<>();
        for (String part : AppPreferences.splitCommand(template)) {
            command.add(part.replace(DIR_TOKEN, directory.getAbsolutePath()));
        }
        return command;
    }

    /**
     * The command auto-detection settles on, or an empty list when nothing is installed.
     *
     * @param onPath whether a program name can be found on the {@code PATH}
     */
    static List<String> detect(String osName, Map<String, String> environment, Predicate<String> onPath,
                               File directory) {
        String dir = directory.getAbsolutePath();
        if (osName.toLowerCase(Locale.ROOT).contains("win")) {
            if (onPath.test("wt")) {
                return List.of("wt", "-d", dir);
            }
            // "start" takes its first quoted argument as the window's title, so an empty
            // one is given: without it a quoted program path would become the title.
            for (String shell : List.of("pwsh", "powershell")) {
                if (onPath.test(shell)) {
                    return List.of("cmd", "/c", "start", "", "/d", dir, shell, "-NoExit");
                }
            }
            return List.of("cmd", "/c", "start", "", "/d", dir, "cmd");
        }

        String preferred = environment.get("TERMINAL");
        if (preferred != null && !preferred.isBlank() && onPath.test(preferred.trim())) {
            return List.of(preferred.trim());
        }
        if (onPath.test("x-terminal-emulator")) {
            return List.of("x-terminal-emulator");
        }
        // These three keep one server process and open the window from it, so the
        // directory this process starts in is not the one the window opens in.
        if (onPath.test("gnome-terminal")) {
            return List.of("gnome-terminal", "--working-directory=" + dir);
        }
        if (onPath.test("konsole")) {
            return List.of("konsole", "--workdir", dir);
        }
        if (onPath.test("xfce4-terminal")) {
            return List.of("xfce4-terminal", "--working-directory=" + dir);
        }
        if (onPath.test("xterm")) {
            return List.of("xterm");
        }
        return List.of();
    }

    /** What auto-detection would run here, for Preferences to show; blank when nothing is found. */
    public static String describeDetected() {
        List<String> command = detect(System.getProperty("os.name", ""), System.getenv(),
                TerminalLauncher::onPath, new File(DIR_TOKEN));
        if (command.isEmpty()) {
            return "";
        }
        // Named by the terminal, not by the "cmd /c start" that opens its window.
        if (command.get(0).equals("cmd") && command.size() > 6) {
            return command.get(6);
        }
        return command.get(0);
    }

    /** Whether {@code program} is an executable file in a directory of the {@code PATH}. */
    static boolean onPath(String program) {
        File direct = new File(program);
        if (direct.isAbsolute()) {
            return direct.isFile();
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> suffixes = windows ? List.of(".exe", ".cmd", ".bat", "") : List.of("");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String suffix : suffixes) {
                File candidate = new File(dir, program + suffix);
                if (candidate.isFile() && (windows || candidate.canExecute())) {
                    return true;
                }
            }
        }
        return false;
    }
}
