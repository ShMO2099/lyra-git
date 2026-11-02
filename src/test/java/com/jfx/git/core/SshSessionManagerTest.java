package com.jfx.git.core;

import org.eclipse.jgit.transport.SshSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SSH transport itself can only be exercised against a real server, so these
 * cover what is decidable locally: that installing wires a factory into JGit, and
 * that the key/config discovery reports what is actually on disk.
 */
class SshSessionManagerTest {

    @Test
    void installingRegistersAnSshTransportWithJGit() {
        SshSessionManager.install();
        try {
            assertTrue(SshSessionManager.isInstalled());
            // The real proof: JGit now has something to hand a git@host: URI to.
            assertNotNull(SshSessionFactory.getInstance());
            assertTrue(SshSessionManager.status().startsWith("SSH ready"),
                    "unexpected status: " + SshSessionManager.status());
        } finally {
            SshSessionManager.shutdown();
        }
    }

    @Test
    void installingTwiceIsHarmless() {
        SshSessionManager.install();
        try {
            Object first = SshSessionFactory.getInstance();
            SshSessionManager.install();
            assertEquals(first, SshSessionFactory.getInstance());
        } finally {
            SshSessionManager.shutdown();
        }
    }

    /**
     * Startup builds the transport on a thread of its own. A remote operation that
     * arrives first waits for it rather than going on without it: a profile's key asked
     * for before the install finished used to come back null, and the fetch connected
     * with the wrong key.
     */
    @Test
    void aBackgroundInstallIsWaitedForByWhatNeedsIt(@TempDir Path dir) {
        SshSessionManager.installInBackground();
        try {
            var forKey = SshSessionManager.factoryForKey(dir.resolve("id_work"));
            assumeTrue(SshSessionManager.isInstalled(), "the SSH transport could not be installed here");
            assertNotNull(forKey, "the key's transport, once the install it waited for had finished");
            assertTrue(SshSessionFactory.getInstance() instanceof org.eclipse.jgit.transport.sshd.SshdSessionFactory,
                    "JGit's own factory is the real one once the install is done, not the stand-in");
        } finally {
            SshSessionManager.shutdown();
        }
    }

    @Test
    void shutdownClearsTheReportedStatus() {
        SshSessionManager.install();
        SshSessionManager.shutdown();
        assertFalse(SshSessionManager.isInstalled());
        assertEquals("SSH transport not installed", SshSessionManager.status());
    }

    @Test
    void keysAreReportedNewestAlgorithmFirst(@TempDir Path sshDir) throws Exception {
        writeKey(sshDir, "id_rsa");
        writeKey(sshDir, "id_ed25519");

        assertEquals(List.of("id_ed25519", "id_rsa"),
                SshSessionManager.defaultKeys(sshDir.toFile()));
    }

    @Test
    void aPrivateKeyWithNoPublicHalfIsNotAnIdentity(@TempDir Path sshDir) throws Exception {
        Files.writeString(sshDir.resolve("id_rsa"), "private", StandardCharsets.UTF_8);

        assertTrue(SshSessionManager.defaultKeys(sshDir.toFile()).isEmpty());
    }

    @Test
    void anEmptySshDirectoryYieldsNoKeys(@TempDir Path sshDir) {
        assertTrue(SshSessionManager.defaultKeys(sshDir.toFile()).isEmpty());
        assertEquals(0, SshSessionManager.sshDirEntryCount(sshDir.toFile()));
    }

    @Test
    void aMissingSshDirectoryIsNotAnError() {
        File absent = new File("does-not-exist-" + System.nanoTime());
        assertTrue(SshSessionManager.defaultKeys(absent).isEmpty());
        assertFalse(SshSessionManager.hasConfig(absent));
        assertEquals(0, SshSessionManager.sshDirEntryCount(absent));
    }

    @Test
    void theConfigFileIsDetected(@TempDir Path sshDir) throws Exception {
        assertFalse(SshSessionManager.hasConfig(sshDir.toFile()));
        Files.writeString(sshDir.resolve("config"), "Host github.com\n", StandardCharsets.UTF_8);
        assertTrue(SshSessionManager.hasConfig(sshDir.toFile()));
    }

    @Test
    void theDescriptionNamesEverySourceOfIdentity(@TempDir Path sshDir) throws Exception {
        writeKey(sshDir, "id_ed25519");
        Files.writeString(sshDir.resolve("config"), "Host github.com\n", StandardCharsets.UTF_8);

        String description = SshSessionManager.describe(sshDir.toFile());
        assertTrue(description.startsWith("SSH ready:"), description);
        assertTrue(description.contains("id_ed25519"), description);
        assertTrue(description.contains("~/.ssh/config"), description);
    }

    @Test
    void anEmptyDirectorySaysSoRatherThanClaimingReadiness(@TempDir Path sshDir) {
        String description = SshSessionManager.describe(sshDir.toFile());
        if (SshSessionManager.hasAgent()) {
            // A machine with an agent running genuinely is ready without a key file.
            assertTrue(description.contains("ssh-agent"), description);
        } else {
            assertTrue(description.contains("no agent or key found"), description);
        }
    }

    private static void writeKey(Path sshDir, String name) throws Exception {
        Files.writeString(sshDir.resolve(name), "private", StandardCharsets.UTF_8);
        Files.writeString(sshDir.resolve(name + ".pub"), "public", StandardCharsets.UTF_8);
    }
}
