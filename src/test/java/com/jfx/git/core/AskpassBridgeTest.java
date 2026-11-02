package com.jfx.git.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R6: the git CLI asks the user through this application instead of failing.
 *
 * <p>End to end with real git and no network: {@code git credential fill} with no helper
 * configured falls back to {@code GIT_ASKPASS} for the username and the password - the
 * same prompts an HTTPS fetch would make - so running it under the bridge's environment
 * proves the script, the helper JVM, the socket and the token all work together.
 */
class AskpassBridgeTest {

    @TempDir
    Path dir;

    @Test
    void gitsPromptsReachTheResponderAndItsAnswersReachGit() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "git is not installed");
        List<String> asked = new ArrayList<>();
        try (AskpassBridge bridge = new AskpassBridge((kind, prompt) -> {
            synchronized (asked) {
                asked.add(prompt);
            }
            return prompt.startsWith("Username") ? "alice" : "s3cret";
        })) {
            String output = credentialFill(bridge.environment());

            assertTrue(output.contains("username=alice"), output);
            assertTrue(output.contains("password=s3cret"), output);
            assertTrue(asked.stream().anyMatch(p -> p.contains("https://example.invalid")), asked::toString);
        }
    }

    /** Cancelling the dialog fails the prompt; git reports it rather than hanging. */
    @Test
    void aCancelledPromptFailsTheCommand() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "git is not installed");
        try (AskpassBridge bridge = new AskpassBridge((kind, prompt) -> null)) {
            String output = credentialFill(bridge.environment());
            assertFalse(output.contains("password="), output);
        }
    }

    /** Without the per-session token the bridge answers nothing. */
    @Test
    void aCallerWithoutTheTokenIsRefused() throws Exception {
        try (AskpassBridge bridge = new AskpassBridge((kind, prompt) -> "leaked")) {
            Map<String, String> env = bridge.environment();
            try (var socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),
                    Integer.parseInt(env.get(AskpassMain.PORT_ENV)))) {
                var out = new java.io.DataOutputStream(socket.getOutputStream());
                out.writeUTF("not-the-token");
                out.writeUTF("");
                out.writeUTF("Password: ");
                out.flush();
                try (var in = new java.io.DataInputStream(socket.getInputStream())) {
                    assertFalse(in.readBoolean());
                }
            }
        }
    }

    @Test
    void theSilentEnvironmentSwitchesEveryPromptOff() {
        Map<String, String> env = AskpassBridge.silentEnvironment(null, Map.of());

        assertEquals("", env.get("GIT_ASKPASS"), "empty, not unset: unset falls through to SSH_ASKPASS");
        assertEquals("never", env.get("SSH_ASKPASS_REQUIRE"));
        assertEquals("never", env.get("GCM_INTERACTIVE"));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertEquals("ssh -o BatchMode=yes -o ConnectTimeout=30 -o ServerAliveInterval=15 -o ServerAliveCountMax=4", env.get("GIT_SSH_COMMAND"));
    }

    @Test
    void batchModeIsAddedOnlyToAnOpenSshCommand() {
        assertEquals("ssh -i ~/.ssh/work -o BatchMode=yes -o ConnectTimeout=30 -o ServerAliveInterval=15 -o ServerAliveCountMax=4",
                AskpassBridge.batchModeSshCommand("ssh -i ~/.ssh/work", Map.of()));
        assertEquals("\"C:/Windows/System32/OpenSSH/ssh.exe\" -o BatchMode=yes -o ConnectTimeout=30 -o ServerAliveInterval=15 -o ServerAliveCountMax=4",
                AskpassBridge.batchModeSshCommand("\"C:/Windows/System32/OpenSSH/ssh.exe\"", Map.of()));
        assertNull(AskpassBridge.batchModeSshCommand("plink -batch", Map.of()),
                "plink rejects -o, so it is left exactly as configured");
        assertNull(AskpassBridge.batchModeSshCommand(null, Map.of("GIT_SSH", "C:/putty/plink.exe")));
        assertEquals("ssh -F cfg -o BatchMode=yes -o ConnectTimeout=30 -o ServerAliveInterval=15 -o ServerAliveCountMax=4",
                AskpassBridge.batchModeSshCommand("ignored", Map.of("GIT_SSH_COMMAND", "ssh -F cfg")),
                "the environment's GIT_SSH_COMMAND wins, as it does for git");
    }

    // ------------------------------------------------------------------
    // ssh's prompts
    // ------------------------------------------------------------------

    /**
     * ssh's two questions - a key passphrase and an unknown host key - travel from ssh
     * through the script, the helper JVM and the socket to {@link CredentialsManager},
     * and its answers travel back, with git in between exactly as a fetch runs it.
     *
     * <p>No sshd: git's {@code GIT_SSH_COMMAND} names a stand-in that asks both
     * questions through {@code $SSH_ASKPASS} as OpenSSH would, writes down the answers,
     * and then serves an empty repository.
     */
    @Test
    void sshsPassphraseAndHostKeyQuestionsAreAnsweredThroughTheBridge() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "git is not installed");
        List<String> asked = new ArrayList<>();
        CredentialsManager.useAskpassDialogs(recordingDialogs(asked, true));
        try (AskpassBridge bridge = new AskpassBridge(CredentialsManager.getInstance()::answerAskpass, null)) {
            NativeGitRunner.Result res = lsRemoteThroughFakeSsh(bridge);

            assertEquals("passphrase=hunter2\nhostkey=yes\n", Files.readString(dir.resolve("answers.txt")));
            assertTrue(res.isOk(), res.stderr());
            assertTrue(asked.contains("secret:Enter passphrase for key '/k':"), asked::toString);
            assertTrue(asked.stream().anyMatch(q -> q.startsWith("confirm:The authenticity of host")
                    && q.contains("SHA256:fake") && q.endsWith("(yes/no/[fingerprint])?")), asked::toString);
        } finally {
            CredentialsManager.useAskpassDialogs(null);
        }
    }

    /** Declining the host key answers ssh "no", and the command fails as ssh fails it. */
    @Test
    void aDeclinedHostKeyIsAnsweredNo() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "git is not installed");
        CredentialsManager.useAskpassDialogs(recordingDialogs(new ArrayList<>(), false));
        try (AskpassBridge bridge = new AskpassBridge(CredentialsManager.getInstance()::answerAskpass, null)) {
            NativeGitRunner.Result res = lsRemoteThroughFakeSsh(bridge);

            assertEquals("passphrase=hunter2\nhostkey=no\n", Files.readString(dir.resolve("answers.txt")));
            assertFalse(res.isOk());
            assertTrue(res.stderr().contains("Host key verification failed"), res.stderr());
        } finally {
            CredentialsManager.useAskpassDialogs(null);
        }
    }

    @Test
    void windowsOwnOpenSshIsToldApartFromGitForWindowsSsh() {
        assertFalse(AskpassBridge.usesNativeWindowsSsh(null, Map.of()), "a bare ssh is Git for Windows' own");
        assertFalse(AskpassBridge.usesNativeWindowsSsh("ssh -i ~/.ssh/work", Map.of()));
        assertFalse(AskpassBridge.usesNativeWindowsSsh("/usr/bin/ssh", Map.of()));
        assertFalse(AskpassBridge.usesNativeWindowsSsh("\"C:/Program Files/Git/usr/bin/ssh.exe\" -v", Map.of()));
        assertFalse(AskpassBridge.usesNativeWindowsSsh("C:/putty/plink.exe", Map.of()),
                "plink has no SSH_ASKPASS at all");

        assertTrue(AskpassBridge.usesNativeWindowsSsh("C:/Windows/System32/OpenSSH/ssh.exe", Map.of()));
        assertTrue(AskpassBridge.usesNativeWindowsSsh("\"C:\\Windows\\System32\\OpenSSH\\ssh.exe\" -i key", Map.of()));
        assertTrue(AskpassBridge.usesNativeWindowsSsh("'C:/Program Files/OpenSSH/ssh.exe'", Map.of()),
                "the Win32-OpenSSH installer's build");
        assertTrue(AskpassBridge.usesNativeWindowsSsh(null,
                Map.of("GIT_SSH", "C:\\Program Files\\OpenSSH\\ssh.exe")), "GIT_SSH is a bare path, spaces and all");

        assertFalse(AskpassBridge.usesNativeWindowsSsh("C:/Windows/System32/OpenSSH/ssh.exe",
                Map.of("GIT_SSH_COMMAND", "ssh")), "the environment's GIT_SSH_COMMAND wins, as it does for git");
        assertFalse(AskpassBridge.usesNativeWindowsSsh(null,
                Map.of("GIT_SSH_COMMAND", "ssh", "GIT_SSH", "C:/Windows/System32/OpenSSH/ssh.exe")));
    }

    /**
     * Windows' own OpenSSH gets an executable helper when there is one - it cannot start
     * the script - and every other ssh, and git itself, keep the script.
     */
    @Test
    void sshAskpassIsChosenForTheSshThatWillRun() throws Exception {
        Path helper = Files.writeString(dir.resolve("LyraAskpass.exe"), "stand-in");
        String nativeSsh = "C:/Windows/System32/OpenSSH/ssh.exe";
        try (AskpassBridge bridge = new AskpassBridge((kind, prompt) -> null, helper)) {
            Map<String, String> forNative = bridge.environment(nativeSsh, Map.of());
            Map<String, String> forMsys = bridge.environment("ssh -i key", Map.of());

            assertEquals(helper.toAbsolutePath().toString(), forNative.get("SSH_ASKPASS"));
            assertTrue(forNative.get("GIT_ASKPASS").endsWith("/askpass.sh"), forNative::toString);
            assertTrue(forMsys.get("SSH_ASKPASS").endsWith("/askpass.sh"), forMsys::toString);
            assertEquals(forMsys.get("SSH_ASKPASS"), forMsys.get("GIT_ASKPASS"));
        }
        Path scriptDir;
        try (AskpassBridge bridge = new AskpassBridge((kind, prompt) -> null, null)) {
            String askpass = bridge.environment(nativeSsh, Map.of()).get("SSH_ASKPASS");
            assertTrue(askpass.endsWith("/askpass.sh"),
                    "with no executable helper there is nothing better; the prompt fails, and is explained");
            scriptDir = Path.of(askpass).getParent();
            try (var files = Files.list(scriptDir)) {
                assertEquals(List.of("askpass.sh"), files.map(p -> p.getFileName().toString()).toList(),
                        "never a .cmd: cmd.exe would run what a server puts in a prompt");
            }
        }
        assertFalse(Files.exists(scriptDir), "the script and its directory go when the bridge closes");
    }

    /** What Windows' own OpenSSH prints when it cannot start the script gets explained. */
    @Test
    void windowsOpenSshsAskpassFailureIsExplained() {
        String stderr = "CreateProcessW failed error:193\r\nssh_askpass: posix_spawnp: Unknown error\r\n"
                + "Host key verification failed.\r\nfatal: Could not read from remote repository.\n";
        String hint = AskpassBridge.nativeSshHint(stderr);
        assertNotNull(hint);
        assertTrue(hint.contains("core.sshCommand"), hint);
        assertNull(AskpassBridge.nativeSshHint("Host key verification failed.\n"));
        assertNull(AskpassBridge.nativeSshHint(null));
    }

    @Test
    void shellQuotingSurvivesAQuoteInAPath() {
        assertEquals("'it'\\''s'", AskpassBridge.shellQuote("it's"));
    }

    /**
     * Stands in for the user at ssh's two dialogs: types {@code hunter2} for any secret,
     * and accepts or declines the host key; records each question as
     * {@code confirm:<text>} or {@code secret:<text>}.
     */
    private static CredentialsManager.AskpassDialogs recordingDialogs(List<String> asked, boolean acceptHostKey) {
        return new CredentialsManager.AskpassDialogs() {
            @Override
            public Boolean confirm(String title, String message) {
                synchronized (asked) {
                    asked.add("confirm:" + message);
                }
                return acceptHostKey;
            }

            @Override
            public String secret(String title, String prompt) {
                synchronized (asked) {
                    asked.add("secret:" + prompt);
                }
                return "hunter2";
            }
        };
    }

    /**
     * Runs {@code git ls-remote ssh://example.invalid/x} under the bridge's environment,
     * with {@code GIT_SSH_COMMAND} naming a stand-in for ssh that asks what OpenSSH asks -
     * a passphrase, then the multi-line host-key question - through {@code $SSH_ASKPASS},
     * writes the answers to {@code answers.txt}, and fails as ssh would unless they are
     * the passphrase and "yes". Accepted, it serves an empty repository.
     */
    private NativeGitRunner.Result lsRemoteThroughFakeSsh(AskpassBridge bridge) throws Exception {
        Path fakeSsh = dir.resolve("fake-ssh");
        Files.writeString(fakeSsh, """
                #!/bin/sh
                pass=$("$SSH_ASKPASS" "Enter passphrase for key '/k': ") || { echo "askpass failed" >&2; exit 128; }
                host=$("$SSH_ASKPASS" "The authenticity of host 'example.invalid (192.0.2.1)' can't be established.
                ED25519 key fingerprint is SHA256:fake.
                This key is not known by any other names.
                Are you sure you want to continue connecting (yes/no/[fingerprint])? ") || { echo "askpass failed" >&2; exit 128; }
                printf 'passphrase=%s\\nhostkey=%s\\n' "$pass" "$host" > "$(dirname "$0")/answers.txt"
                [ "$pass" = hunter2 ] || { echo "Permission denied (publickey)." >&2; exit 255; }
                [ "$host" = yes ] || { echo "Host key verification failed." >&2; exit 255; }
                printf '0000'
                cat > /dev/null
                exit 0
                """, StandardCharsets.UTF_8);
        if (!isWindows()) {
            Files.setPosixFilePermissions(fakeSsh, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        Path global = dir.resolve("empty.gitconfig");
        Files.writeString(global, "");

        Map<String, String> env = new java.util.HashMap<>(bridge.environment(null, Map.of()));
        env.put("GIT_SSH_COMMAND", AskpassBridge.shellQuote(fakeSsh.toAbsolutePath().toString().replace('\\', '/')));
        // The stand-in is not named ssh, and must not be probed with -G to find out.
        env.put("GIT_SSH_VARIANT", "ssh");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", global.toAbsolutePath().toString());
        return new NativeGitRunner(dir.toFile()).runWithEnvironment(env, List.of("ls-remote", "ssh://example.invalid/x"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * Runs {@code git credential fill} for an invented host with no helper configured,
     * so git has only askpass to ask; returns its standard output.
     */
    private String credentialFill(Map<String, String> bridgeEnvironment) throws Exception {
        Path global = dir.resolve("empty.gitconfig");
        Files.writeString(global, "");
        ProcessBuilder pb = new ProcessBuilder("git", "credential", "fill");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("GIT_CONFIG_GLOBAL", global.toAbsolutePath().toString());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().putAll(bridgeEnvironment);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        try (var in = process.getOutputStream()) {
            in.write("protocol=https\nhost=example.invalid\n\n".getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                process.getInputStream().transferTo(out);
            } catch (Exception ignored) {
                // The process ended.
            }
        });
        reader.start();
        Thread errors = new Thread(() -> {
            try {
                process.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());
            } catch (Exception ignored) {
                // The process ended.
            }
        });
        errors.start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "git credential fill hung");
        reader.join(5_000);
        return out.toString(StandardCharsets.UTF_8);
    }
}
