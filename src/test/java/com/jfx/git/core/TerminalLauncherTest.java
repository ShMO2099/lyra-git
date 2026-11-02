package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which terminal is opened, and with what.
 *
 * <p>Detection is tested as a function of the operating system, the environment and
 * what is installed, so both platforms are covered from either. The launch itself is
 * tested with a real child process standing in for the terminal.
 */
class TerminalLauncherTest {

    @TempDir
    Path root;

    @AfterEach
    void restore() {
        AppPreferences.setTerminalCommand("");
    }

    private static List<String> detect(String os, Map<String, String> env, Set<String> installed, File dir) {
        return TerminalLauncher.detect(os, env, installed::contains, dir);
    }

    // ------------------------------------------------------------------
    // Windows
    // ------------------------------------------------------------------

    @Test
    void windowsTerminalIsPreferredWhenItIsInstalled() {
        File dir = new File("C:\\my repo");
        assertEquals(List.of("wt", "-d", dir.getAbsolutePath()),
                detect("Windows 11", Map.of(), Set.of("wt", "pwsh", "powershell"), dir));
    }

    /** A console program gets a window of its own, whatever started this application. */
    @Test
    void powerShellIsOpenedInItsOwnWindow() {
        File dir = new File("C:\\my repo");
        assertEquals(List.of("cmd", "/c", "start", "", "/d", dir.getAbsolutePath(), "pwsh", "-NoExit"),
                detect("Windows 11", Map.of(), Set.of("pwsh", "powershell"), dir),
                "PowerShell 7 before Windows PowerShell");
        assertEquals(List.of("cmd", "/c", "start", "", "/d", dir.getAbsolutePath(), "powershell", "-NoExit"),
                detect("Windows 10", Map.of(), Set.of("powershell"), dir));
    }

    @Test
    void cmdIsTheLastResortOnWindowsAndIsAlwaysThere() {
        File dir = new File("C:\\repo");
        assertEquals(List.of("cmd", "/c", "start", "", "/d", dir.getAbsolutePath(), "cmd"),
                detect("Windows 10", Map.of(), Set.of(), dir));
    }

    // ------------------------------------------------------------------
    // Linux
    // ------------------------------------------------------------------

    @Test
    void theUsersOwnTerminalVariableWinsOnLinux() {
        File dir = new File("/home/me/repo");
        assertEquals(List.of("alacritty"),
                detect("Linux", Map.of("TERMINAL", "alacritty"), Set.of("alacritty", "gnome-terminal"), dir));
        assertEquals(List.of("gnome-terminal", "--working-directory=" + dir.getAbsolutePath()),
                detect("Linux", Map.of("TERMINAL", "not-installed"), Set.of("gnome-terminal"), dir),
                "a variable naming something that is not installed is not followed off a cliff");
    }

    /**
     * GNOME Terminal, Konsole and Xfce Terminal open their window from a server process,
     * so the directory has to be passed: the one this process starts in is not theirs.
     */
    @Test
    void terminalsThatIgnoreTheWorkingDirectoryAreToldIt() {
        File dir = new File("/home/me/my repo");
        assertEquals(List.of("x-terminal-emulator"),
                detect("Linux", Map.of(), Set.of("x-terminal-emulator", "konsole"), dir));
        assertEquals(List.of("konsole", "--workdir", dir.getAbsolutePath()),
                detect("Linux", Map.of(), Set.of("konsole", "xterm"), dir));
        assertEquals(List.of("xfce4-terminal", "--working-directory=" + dir.getAbsolutePath()),
                detect("Linux", Map.of(), Set.of("xfce4-terminal"), dir));
        assertEquals(List.of("xterm"), detect("Linux", Map.of(), Set.of("xterm"), dir));
    }

    @Test
    void aMachineWithNoTerminalAtAllHasNothingToRun() {
        assertEquals(List.of(), detect("Linux", Map.of(), Set.of(), new File("/repo")));
    }

    // ------------------------------------------------------------------
    // A custom command
    // ------------------------------------------------------------------

    @Test
    void theDirectoryReplacesItsTokenAndStaysOneArgument() {
        File dir = root.resolve("my repo").toFile();

        assertEquals(List.of("wt", "-d", dir.getAbsolutePath(), "--title", "git"),
                TerminalLauncher.resolve("wt -d {dir} --title git", dir));
        assertEquals(List.of("C:\\Program Files\\Term\\term.exe", "--cwd=" + dir.getAbsolutePath()),
                TerminalLauncher.resolve("\"C:\\Program Files\\Term\\term.exe\" --cwd={dir}", dir));
        assertEquals(List.of("xterm"), TerminalLauncher.resolve("xterm", dir),
                "a command without the token is run as written, in the directory");
    }

    private static String fakeTool() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return "\"" + java + "\" -cp \"" + System.getProperty("java.class.path") + "\" "
                + FakeExternalTool.class.getName();
    }

    @Test
    void theCustomCommandIsStartedWithTheDirectory() throws Exception {
        Path dir = Files.createDirectories(root.resolve("my repo"));
        Path out = root.resolve("recorded.txt");
        AppPreferences.setTerminalCommand(fakeTool() + " record \"" + out + "\" {dir}");

        GitResult result = TerminalLauncher.open(dir.toFile());
        assertTrue(result.isOk(), result::getMessage);

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (!Files.exists(out)) {
            assertTrue(System.nanoTime() < deadline, "the command was never run");
            Thread.sleep(50);
        }
        assertEquals(List.of(dir.toFile().getAbsolutePath()), Files.readAllLines(out, StandardCharsets.UTF_8));
    }

    @Test
    void aCommandThatCannotBeStartedIsAFailureThatNamesIt() {
        AppPreferences.setTerminalCommand("no-such-terminal-anywhere {dir}");

        GitResult result = TerminalLauncher.open(root.toFile());

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("no-such-terminal-anywhere"), result.getMessage());
    }

    @Test
    void aFolderThatIsNotThereIsRefusedBeforeAnythingIsStarted() {
        assertFalse(TerminalLauncher.open(root.resolve("gone").toFile()).isOk());
        assertFalse(TerminalLauncher.open(null).isOk());
    }

    // ------------------------------------------------------------------
    // What is installed
    // ------------------------------------------------------------------

    @Test
    void aProgramIsFoundOnThePathOrNot() {
        assertTrue(TerminalLauncher.onPath(Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString()),
                "an absolute path to a real program");
        assertFalse(TerminalLauncher.onPath("no-such-program-anywhere-at-all"));
    }
}
