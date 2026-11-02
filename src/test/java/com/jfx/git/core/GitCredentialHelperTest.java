package com.jfx.git.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R7: the user's own git credential helpers, reached through {@code git credential}.
 *
 * <p>Each test gives git a configuration of its own - no system config, a global config
 * file in a temporary directory naming a tiny shell helper - so the machine's real
 * helper (Git Credential Manager on a Windows developer box) is never consulted.
 */
class GitCredentialHelperTest {

    @TempDir
    Path dir;

    @BeforeEach
    void needsGit() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "git is not installed");
    }

    /**
     * A helper configured to answer every {@code get} with these credentials and to log
     * every action it is given to {@code actions.log} in {@code dir}.
     */
    static GitCredentialHelper helperAnswering(Path dir, String user, String password) throws Exception {
        Path log = dir.resolve("actions.log");
        String logPath = log.toAbsolutePath().toString().replace('\\', '/');
        String helper = "!f() { echo \\\"$1\\\" >> '" + logPath + "'; "
                + "if [ \\\"$1\\\" = get ]; then echo username=" + user + "; echo password=" + password + "; fi; }; f";
        Path global = dir.resolve("global.gitconfig");
        Files.writeString(global, "[credential]\n\thelper = \"" + helper + "\"\n");
        return new GitCredentialHelper(Map.of(
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", global.toAbsolutePath().toString()));
    }

    @Test
    void fillReturnsWhatTheHelperHas() throws Exception {
        GitCredentialHelper helper = helperAnswering(dir, "alice", "s3cret");

        GitCredentialHelper.Credential found = helper.fill("https", "example.invalid", -1, "/repo.git", null, true);

        assertNotNull(found, "the configured helper answered");
        assertEquals("alice", found.username());
        assertEquals("s3cret", found.password());
    }

    /** With no helper at all, fill fails fast instead of prompting on a terminal. */
    @Test
    void fillWithNoHelperReturnsNothingAndDoesNotPrompt() throws Exception {
        Path global = dir.resolve("empty.gitconfig");
        Files.writeString(global, "");
        GitCredentialHelper none = new GitCredentialHelper(Map.of(
                "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", global.toAbsolutePath().toString()));

        long started = System.nanoTime();
        assertNull(none.fill("https", "example.invalid", -1, null, null, true));
        assertTrue(System.nanoTime() - started < 15_000_000_000L, "must not wait on a prompt");
    }

    @Test
    void approveAndRejectReachTheHelper() throws Exception {
        GitCredentialHelper helper = helperAnswering(dir, "alice", "s3cret");
        var login = new GitCredentialHelper.Credential("alice", "typed");

        helper.approve("https", "example.invalid", 8443, "/repo.git", login);
        helper.reject("https", "example.invalid", 8443, "/repo.git", login);

        String log = Files.readString(dir.resolve("actions.log"));
        assertTrue(log.contains("store"), log);
        assertTrue(log.contains("erase"), log);
    }

    /** A newline in a value would let it smuggle in another field of the protocol. */
    @Test
    void aValueWithALineBreakIsRefused() {
        assertThrows(IllegalArgumentException.class, () ->
                GitCredentialHelper.describe("https", "host", -1, null, "alice\npassword=x", null));
        assertEquals("protocol=https\nhost=host:8443\npath=a/b.git\nusername=u\n\n",
                GitCredentialHelper.describe("https", "host", 8443, "/a/b.git", "u", null));
    }
}
