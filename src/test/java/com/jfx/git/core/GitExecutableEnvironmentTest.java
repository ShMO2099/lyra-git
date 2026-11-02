package com.jfx.git.core;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Windows environment variables are case-insensitive, even when inherited as {@code Path}. */
class GitExecutableEnvironmentTest {

    @Test
    void applyingGitForWindowsPreservesAnInheritedPathRegardlessOfItsCasing() {
        String prefix = "C:\\Git\\ucrt64\\bin" + File.pathSeparator + "C:\\Git\\usr\\bin";
        // Resolution is covered by GitExecutableTest. Exercise the environment contract
        // directly so this Windows-key regression is portable to other build hosts.
        var resolved = new GitExecutable.Resolved("git.exe", prefix, "UCRT64");
        String inheritedPath = "C:\\Windows;C:\\Tools";

        for (String inheritedKey : List.of("Path", "PATH")) {
            Map<String, String> environment = new HashMap<>(Map.of(inheritedKey, inheritedPath));

            resolved.applyTo(environment);

            var pathEntries = environment.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                    .toList();
            assertEquals(1, pathEntries.size(), "PATH must remain one Windows environment variable: " + environment);
            assertTrue(environment.containsKey(inheritedKey), "preserve the inherited key spelling: " + environment);
            assertEquals(prefix + File.pathSeparator + inheritedPath, pathEntries.getFirst().getValue(),
                    "prepend Git for Windows paths once and retain the inherited entries exactly: " + environment);
        }
    }
}
