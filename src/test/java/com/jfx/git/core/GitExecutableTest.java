package com.jfx.git.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Starting the real git rather than Git for Windows' launcher, and doing the launcher's work. */
class GitExecutableTest {

    @TempDir
    Path root;

    private Path install(String environment) throws Exception {
        Path git = root.resolve("Git");
        Files.createDirectories(git.resolve("cmd"));
        Files.writeString(git.resolve("cmd/git.exe"), "launcher");
        Files.createDirectories(git.resolve("usr/bin"));
        Files.createDirectories(git.resolve(environment + "/bin"));
        Files.writeString(git.resolve(environment + "/bin/git.exe"), "git");
        return git;
    }

    @Test
    void theLauncherOnPathIsReplacedByTheGitBehindIt() throws Exception {
        Path git = install("ucrt64");
        String path = root.resolve("elsewhere") + File.pathSeparator + git.resolve("cmd");

        var resolved = GitExecutable.resolve(path, true);

        assertEquals(git.resolve("ucrt64/bin/git.exe").toFile().getAbsolutePath(), resolved.command());
        assertEquals("UCRT64", resolved.msystem());
        Map<String, String> env = new HashMap<>(Map.of("PATH", "C:\\Windows"));
        resolved.applyTo(env);
        assertTrue(env.get("PATH").startsWith(git.resolve("ucrt64/bin").toFile().getAbsolutePath()),
                "git's own folders come first, as the launcher puts them: " + env.get("PATH"));
        assertTrue(env.get("PATH").contains(git.resolve("usr/bin").toFile().getAbsolutePath()));
        assertTrue(env.get("PATH").endsWith("C:\\Windows"), "and the rest of PATH is kept");
    }

    @Test
    void anOlderMingw64InstallIsFoundToo() throws Exception {
        Path git = install("mingw64");

        var resolved = GitExecutable.resolve(git.resolve("cmd").toString(), true);

        assertEquals("MINGW64", resolved.msystem());
        assertTrue(resolved.command().endsWith("mingw64" + File.separator + "bin" + File.separator + "git.exe"));
    }

    /** A git that is not behind a launcher is started as it always was. */
    @Test
    void aPlainGitOnPathIsLeftAlone() throws Exception {
        Path dir = Files.createDirectories(root.resolve("tools/git/bin"));
        Files.writeString(dir.resolve("git.exe"), "git");

        assertSame(GitExecutable.Resolved.PLAIN, GitExecutable.resolve(dir.toString(), true));
    }

    @Test
    void elsewhereThanWindowsNothingChanges() throws Exception {
        Path git = install("ucrt64");

        assertSame(GitExecutable.Resolved.PLAIN, GitExecutable.resolve(git.resolve("cmd").toString(), false));
    }

    /** The first git on PATH decides; a launcher further down is not what "git" would start. */
    @Test
    void onlyTheFirstGitOnPathCounts() throws Exception {
        Path plain = Files.createDirectories(root.resolve("first"));
        Files.writeString(plain.resolve("git.exe"), "git");
        Path git = install("ucrt64");

        var resolved = GitExecutable.resolve(plain + File.pathSeparator + git.resolve("cmd"), true);

        assertSame(GitExecutable.Resolved.PLAIN, resolved);
    }
}
