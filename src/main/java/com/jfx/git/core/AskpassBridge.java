package com.jfx.git.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lets the git CLI ask the user for a password, a passphrase or a host key decision
 * through this application's own dialogs.
 *
 * <h2>Why it exists</h2>
 * The CLI engines - the default ones - ran git with {@code GIT_TERMINAL_PROMPT=0} and
 * nothing else, so anything git or ssh needed to ask simply failed: HTTPS with no
 * credential helper (the Linux default) could never authenticate, the first contact with
 * an SSH host failed with "Host key verification failed", and a passphrase-protected key
 * without an agent was unusable. Launched from a terminal it was worse: ssh prompted on
 * the terminal and the single git worker waited for an answer nobody could see.
 *
 * <h2>How it works</h2>
 * git and OpenSSH both ask through an external program when one is named -
 * {@code GIT_ASKPASS} for git's own HTTPS prompts, {@code SSH_ASKPASS} for ssh's.
 * This class writes a tiny shell script naming {@link AskpassMain}, runs a loopback-only
 * server with a random per-session token, and hands git an environment pointing at both.
 * When a prompt is needed, git or ssh runs the script, which starts a JVM that forwards
 * the prompt here; a {@link Responder} - {@link CredentialsManager} in the application -
 * shows the dialog and the answer goes back the same way.
 *
 * <p>A shell script rather than a direct command because ssh runs its askpass as a
 * single executable path with the prompt as its only argument - there is nowhere to put
 * {@code java -cp ...} - and git, and the msys ssh bundled with Git for Windows, run a
 * {@code #!/bin/sh} script through their own shell.
 *
 * <h2>Windows' own OpenSSH</h2>
 * {@code C:\Windows\System32\OpenSSH\ssh.exe} - which people name in
 * {@code core.sshCommand} or {@code GIT_SSH} to use the Windows ssh-agent service -
 * starts its askpass with {@code CreateProcessW}, which cannot run the script
 * ({@code CreateProcessW failed error:193}): its host-key question and passphrase
 * prompts simply fail. A {@code .cmd} wrapper is <em>not</em> the fix, and must never be
 * written here. Measured against both ssh builds and a local test server:
 * <ul>
 *   <li>cmd.exe ends the command line at the first line break, so the helper sees
 *       only the first line of the host-key question - no fingerprint, no
 *       "yes/no" - and ssh asks again, forever;</li>
 *   <li>cmd.exe parses the prompt it is handed, and ssh escapes a {@code "} in it the
 *       C runtime's way ({@code \"}), which cmd.exe does not understand. A
 *       keyboard-interactive prompt is chosen by the <em>server</em>, so a server that
 *       sends {@code x" & command & "} runs that command on this machine.</li>
 * </ul>
 * Only a real executable gets the prompt intact and uninterpreted. When one is
 * available - see {@link #NATIVE_HELPER_NAME} - it is handed to that ssh; otherwise the
 * prompt fails as before, and {@link #nativeSshHint} tells the user why and what to do.
 *
 * <h2>When it cannot start</h2>
 * No usable {@code java} executable, no code-source location to put on a classpath, or no
 * temporary directory: {@link #interactiveEnvironment()} returns an empty map and the CLI
 * behaves exactly as it did before. Nothing here may stop a fetch that did not need a
 * prompt from working.
 *
 * <h2>What the token protects</h2>
 * The server listens on the loopback interface only, and answers only a caller that
 * presents the token, which exists only in the environment of the git processes this
 * application starts. Another local user cannot use it to put up a credential dialog in
 * this session.
 */
public final class AskpassBridge implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(AskpassBridge.class.getName());

    /** Set to {@code false} to run the CLI with no prompt bridge at all, as before. */
    static final String DISABLE_PROPERTY = "lyra.askpass";

    /**
     * A console launcher for {@link AskpassMain} that a packaged Windows build can carry
     * next to its own launcher ({@code jpackage --add-launcher} with
     * {@code win-console=true}): the executable Windows' own OpenSSH needs.
     */
    static final String NATIVE_HELPER_NAME = "LyraAskpass.exe";

    /** Names such an executable explicitly, wherever it is; it wins over the sibling. */
    static final String NATIVE_HELPER_PROPERTY = "lyra.askpass.exe";

    /** Answers one prompt; {@code null} means the user cancelled. */
    @FunctionalInterface
    public interface Responder {
        /**
         * @param kind   OpenSSH's {@code SSH_ASKPASS_PROMPT} ({@code "confirm"},
         *               {@code "none"}) or blank for an ordinary question
         * @param prompt the prompt text exactly as git or ssh wrote it
         */
        String answer(String kind, String prompt);
    }

    private static AskpassBridge shared;
    private static boolean sharedFailed;

    private final Responder responder;
    private final String token;
    private final ServerSocket server;
    private final Path script;
    /** An executable helper for Windows' own OpenSSH, or null; see the class comment. */
    private final Path nativeHelper;
    private volatile boolean closed;

    /**
     * Starts a bridge answered by {@code responder}.
     *
     * @throws IOException when it cannot listen, or cannot write its script
     * @throws IllegalStateException when there is no java executable or classpath to run
     *                               the helper with
     */
    public AskpassBridge(Responder responder) throws IOException {
        this(responder, findNativeHelper());
    }

    /** As above, with the Windows OpenSSH helper given rather than looked for. */
    AskpassBridge(Responder responder, Path nativeHelper) throws IOException {
        this.responder = responder;
        this.nativeHelper = nativeHelper;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.token = HexFormat.of().formatHex(random);
        String java = javaExecutable();
        String classpath = helperClasspath();
        if (java == null || classpath == null) {
            throw new IllegalStateException("No java executable or classpath for the askpass helper");
        }
        this.script = writeScript(java, classpath);
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "askpass-bridge");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * The bridge the application uses, started on first need and answered by
     * {@link CredentialsManager}; {@code null} when it cannot run here.
     */
    public static synchronized AskpassBridge shared() {
        if (shared != null || sharedFailed) return shared;
        if ("false".equalsIgnoreCase(System.getProperty(DISABLE_PROPERTY))) {
            sharedFailed = true;
            return null;
        }
        try {
            shared = new AskpassBridge(CredentialsManager.getInstance()::answerAskpass);
        } catch (Exception | LinkageError e) {
            // Robustness over features: without the bridge the CLI still works for every
            // remote that needs no prompt, exactly as it always did.
            LOGGER.log(Level.INFO, "Credential prompts for the git CLI are unavailable", e);
            sharedFailed = true;
        }
        return shared;
    }

    /**
     * What to add to an interactive git command's environment so its prompts reach the
     * user, or an empty map when the bridge is not available.
     */
    public static Map<String, String> interactiveEnvironment() {
        return interactiveEnvironment(() -> null);
    }

    /**
     * As {@link #interactiveEnvironment()}, choosing ssh's askpass for the ssh git will
     * actually run.
     *
     * @param configuredSshCommand the repository's {@code core.sshCommand}, or null;
     *                             asked for only when the answer can change anything, so
     *                             a caller may look it up with a git process of its own
     */
    public static Map<String, String> interactiveEnvironment(Supplier<String> configuredSshCommand) {
        AskpassBridge bridge = shared();
        if (bridge == null) return Map.of();
        String configured = bridge.nativeHelper == null ? null : configuredSshCommand.get();
        return bridge.environment(configured, System.getenv());
    }

    /**
     * The global options that make the application git's only credential helper for one
     * command, or none when the bridge is not running here.
     *
     * <p>An empty {@code credential.helper} clears the list git built from the system,
     * global and repository configuration; the second names this bridge. Command-line
     * values come after every configuration file, so this is the whole list - and git
     * hands {@code -c} values on to its children ({@code GIT_CONFIG_PARAMETERS}), so a
     * submodule fetch and git-lfs use it too.
     *
     * <p>Why replace the user's helpers rather than add to them: git asks its helpers in
     * order and stops at the first answer, and an added helper comes last - after Git
     * Credential Manager, which always answers. The connected account would never be
     * used. The user's helpers are not lost: the application asks them itself, after the
     * connected account (see {@link CredentialsManager#answerCredential}).
     *
     * <p>Never without a running bridge: the reset alone would leave git with no helper.
     */
    public static java.util.List<String> credentialHelperArgs() {
        AskpassBridge bridge = shared();
        return bridge == null ? java.util.List.of() : bridge.helperArgs();
    }

    java.util.List<String> helperArgs() {
        String command = "!" + shellQuote(forwardSlashes(script.toAbsolutePath().toString())) + " --credential";
        return java.util.List.of("-c", "credential.helper=", "-c", "credential.helper=" + command);
    }

    /**
     * What a command nobody is watching needs on top of {@link #silentEnvironment} for its
     * credential lookups to reach the application, which then answers without prompting.
     * Empty when the bridge is not running.
     */
    public static Map<String, String> silentCredentialEnvironment() {
        AskpassBridge bridge = shared();
        if (bridge == null) return Map.of();
        return Map.of(AskpassMain.PORT_ENV, Integer.toString(bridge.server.getLocalPort()),
                AskpassMain.TOKEN_ENV, bridge.token,
                AskpassMain.CREDENTIAL_SILENT_ENV, "1");
    }

    /** This bridge's environment for a git process; see {@link #interactiveEnvironment()}. */
    public Map<String, String> environment() {
        return environment(null, System.getenv());
    }

    /**
     * This bridge's environment for a git process that will run the ssh named by
     * {@code configuredSshCommand} and {@code processEnvironment}, as git would choose it.
     */
    Map<String, String> environment(String configuredSshCommand, Map<String, String> processEnvironment) {
        Map<String, String> env = new HashMap<>();
        String path = forwardSlashes(script.toAbsolutePath().toString());
        env.put(AskpassMain.PORT_ENV, Integer.toString(server.getLocalPort()));
        env.put(AskpassMain.TOKEN_ENV, token);
        // git's own prompts go through the script whatever ssh is in use: git runs a
        // #!/bin/sh script itself, on every platform.
        env.put("GIT_ASKPASS", path);
        env.put("SSH_ASKPASS", nativeHelper != null && usesNativeWindowsSsh(configuredSshCommand, processEnvironment)
                ? nativeHelper.toAbsolutePath().toString()
                : path);
        // "prefer": use the askpass program even when ssh has a terminal. Launched from a
        // shell, ssh would otherwise prompt on that terminal and hold the git worker.
        env.put("SSH_ASKPASS_REQUIRE", "prefer");
        // OpenSSH only honours "prefer" when it believes a display exists; without one
        // the msys ssh fails the prompt, and Windows' own ssh waits on the console, which
        // nobody can see. The value is never used to draw anything - the helper has no
        // UI - so a placeholder serves where there is none: always on Windows, and in a
        // Wayland-only Linux session.
        if (isBlank(System.getenv("DISPLAY")) && isBlank(System.getenv("WAYLAND_DISPLAY"))) {
            env.put("DISPLAY", "lyra:0");
        }
        return env;
    }

    /**
     * Whether the ssh git will run is a native Windows build - Windows' own OpenSSH, or
     * the Win32-OpenSSH installer's - rather than the msys ssh that comes with Git for
     * Windows, which is what a bare {@code ssh} means to git there.
     *
     * <p>Resolved the way git resolves it: {@code GIT_SSH_COMMAND}, then
     * {@code core.sshCommand}, then {@code GIT_SSH}. A native one is an ssh executable
     * named by a Windows path ({@code C:\...}, {@code \\server\...}) outside a
     * {@code usr/bin} directory, where the msys builds live.
     *
     * @param configuredSshCommand the repository's {@code core.sshCommand}, or null
     * @param processEnvironment   the environment git would otherwise inherit
     */
    static boolean usesNativeWindowsSsh(String configuredSshCommand, Map<String, String> processEnvironment) {
        String program;
        String command = processEnvironment.get("GIT_SSH_COMMAND");
        if (isBlank(command)) command = configuredSshCommand;
        if (!isBlank(command)) {
            program = programOf(command.trim());
        } else {
            // GIT_SSH names the program itself, with no quoting: a space is part of it.
            program = processEnvironment.get("GIT_SSH");
            if (isBlank(program)) return false;
            program = program.trim();
        }
        if (!isSshProgram(program)) return false;
        String path = forwardSlashes(program).toLowerCase(Locale.ROOT);
        boolean windowsPath = path.matches("^[a-z]:/.*") || path.startsWith("//");
        return windowsPath && !path.contains("/usr/bin/");
    }

    /**
     * What to tell the user when Windows' own OpenSSH could not start the askpass
     * script, or null when {@code stderr} shows nothing of the kind.
     */
    static String nativeSshHint(String stderr) {
        if (stderr == null
                || !(stderr.contains("ssh_askpass: posix_spawnp") || stderr.contains("CreateProcessW failed error:193"))) {
            return null;
        }
        return "Windows' own OpenSSH, named in core.sshCommand or GIT_SSH, cannot show Lyra's"
                + " prompts, so ssh could not ask about the host key or the key passphrase."
                + " Answer it once in a terminal (for example: ssh -T git@your-host), load the key"
                + " into the Windows ssh-agent with ssh-add, or remove that setting to use the ssh"
                + " that comes with Git for Windows.";
    }

    /**
     * The environment for a git command nobody is watching - a background fetch - under
     * which nothing may prompt at all.
     *
     * <p>git's askpass is switched off with an empty {@code GIT_ASKPASS} (empty stops
     * git's lookup, where unset would fall through to {@code core.askPass} and
     * {@code SSH_ASKPASS}); ssh's with {@code SSH_ASKPASS_REQUIRE=never}; Git Credential
     * Manager's own windows with {@code GCM_INTERACTIVE=never}; and ssh's terminal and
     * host-key prompts with {@code BatchMode=yes} where the ssh command allows adding it.
     *
     * @param configuredSshCommand the repository's {@code core.sshCommand}, or null
     * @param processEnvironment   the environment git would otherwise inherit
     */
    public static Map<String, String> silentEnvironment(String configuredSshCommand,
                                                        Map<String, String> processEnvironment) {
        Map<String, String> env = new HashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "");
        env.put("SSH_ASKPASS", "");
        env.put("SSH_ASKPASS_REQUIRE", "never");
        env.put("GCM_INTERACTIVE", "never");
        // A background fetch runs on its repository's lane, so a transfer that stalls -
        // a VPN that dropped, a server that accepted the connection and went quiet -
        // would hold every commit and checkout in that repository behind it until the
        // network timeout. git
        // aborts an HTTP transfer slower than this for this long; ssh gets the matching
        // connect and keep-alive limits in batchModeSshCommand.
        env.put("GIT_HTTP_LOW_SPEED_LIMIT", "1000");
        env.put("GIT_HTTP_LOW_SPEED_TIME", "60");
        String ssh = batchModeSshCommand(configuredSshCommand, processEnvironment);
        if (ssh != null) env.put("GIT_SSH_COMMAND", ssh);
        return env;
    }

    /**
     * The ssh command git would use, with {@code -o BatchMode=yes} added, or {@code null}
     * when the configured program is not OpenSSH-compatible (PuTTY's plink rejects
     * {@code -o}) and must be left alone.
     */
    static String batchModeSshCommand(String configuredSshCommand, Map<String, String> processEnvironment) {
        String base = processEnvironment.get("GIT_SSH_COMMAND");
        if (isBlank(base)) base = configuredSshCommand;
        if (isBlank(base)) {
            // GIT_SSH names a bare program git runs with its own arguments; it may well be
            // plink, and there is no way to add an option without knowing which it is.
            if (!isBlank(processEnvironment.get("GIT_SSH"))) return null;
            base = "ssh";
        }
        base = base.trim();
        if (!isOpenSsh(base)) return null;
        // ServerAlive: a connection that goes silent is dropped after about a minute
        // instead of waiting on TCP, which can take far longer to notice.
        return base + " -o BatchMode=yes -o ConnectTimeout=30"
                + " -o ServerAliveInterval=15 -o ServerAliveCountMax=4";
    }

    /**
     * The ssh command git would use, made to offer {@code key} and nothing else, or
     * {@code null} when there is no key or the configured program is not
     * OpenSSH-compatible and so cannot be given OpenSSH's options.
     *
     * <p>{@code IdentitiesOnly} is the half that matters. {@code -i} alone adds the key
     * to what ssh offers; an agent holding the user's other key still gets to offer it
     * first, and the host accepts whichever account that one belongs to.
     *
     * <p>Forward slashes in the path, and double quotes round it: git runs this through
     * a shell, where a backslash is an escape and a space ends the argument.
     *
     * @param configuredSshCommand the repository's {@code core.sshCommand}, or null
     * @param processEnvironment   the environment git would otherwise inherit
     */
    static String identitySshCommand(String configuredSshCommand, Map<String, String> processEnvironment,
                                     java.nio.file.Path key) {
        if (key == null) return null;
        String base = processEnvironment.get("GIT_SSH_COMMAND");
        if (isBlank(base)) base = configuredSshCommand;
        if (isBlank(base)) {
            if (!isBlank(processEnvironment.get("GIT_SSH"))) return null;
            base = "ssh";
        }
        base = base.trim();
        if (!isOpenSsh(base)) return null;
        return base + " -i \"" + forwardSlashes(key.toAbsolutePath().toString()) + "\" -o IdentitiesOnly=yes";
    }

    private static boolean isOpenSsh(String command) {
        return isSshProgram(programOf(command));
    }

    /** The program a shell command line starts: its first word, or its quoted first word. */
    private static String programOf(String command) {
        if (command.startsWith("\"") || command.startsWith("'")) {
            int close = command.indexOf(command.charAt(0), 1);
            return close > 0 ? command.substring(1, close) : command.substring(1);
        }
        int space = command.indexOf(' ');
        return space > 0 ? command.substring(0, space) : command;
    }

    private static boolean isSshProgram(String program) {
        String name = forwardSlashes(program);
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("ssh") || name.equals("ssh.exe");
    }

    // ------------------------------------------------------------------
    // Server
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                Thread handler = new Thread(() -> handle(socket), "askpass-request");
                handler.setDaemon(true);
                handler.start();
            } catch (SocketException e) {
                if (closed) return;
                LOGGER.log(Level.FINE, "askpass accept failed", e);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "askpass accept failed", e);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            // The request is three short strings; a caller that sends nothing is not a
            // helper, and must not hold a thread forever.
            socket.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String presented = in.readUTF();
            String kind = in.readUTF();
            String prompt = in.readUTF();
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                out.writeBoolean(false);
                return;
            }
            // Waiting for the user has no deadline: they may be reading a fingerprint.
            socket.setSoTimeout(0);
            String answer;
            try {
                answer = responder.answer(kind, prompt);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The askpass responder failed", e);
                answer = null;
            }
            if (answer == null) {
                out.writeBoolean(false);
            } else {
                out.writeBoolean(true);
                out.writeUTF(answer);
            }
            out.flush();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "askpass request failed", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing on the way out; nothing to recover.
        }
        try {
            Files.deleteIfExists(script);
            Files.deleteIfExists(script.getParent());
        } catch (IOException ignored) {
            // A temporary file left behind is harmless; it holds no secret.
        }
    }

    // ------------------------------------------------------------------
    // The helper script
    // ------------------------------------------------------------------

    /**
     * The java launcher to run the helper with: this runtime's own. {@code java.home}
     * first, because a packaged application's process is its native launcher, not java;
     * the process's own command second, when it is a java launcher itself.
     */
    static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path home = Path.of(System.getProperty("java.home", ""), "bin", windows ? "java.exe" : "java");
        if (Files.isRegularFile(home)) return home.toString();
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null) {
            String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.equals("java") || name.equals("java.exe")) return command;
            if (name.equals("javaw.exe")) {
                // javaw has no console handles; its sibling does.
                Path sibling = Path.of(command).resolveSibling("java.exe");
                if (Files.isRegularFile(sibling)) return sibling.toString();
            }
        }
        return null;
    }

    /** The directory or jar {@link AskpassMain} was loaded from. */
    static String helperClasspath() {
        try {
            var source = AskpassMain.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            Path location = Path.of(source.getLocation().toURI());
            return Files.exists(location) ? location.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The executable askpass helper for Windows' own OpenSSH, or null: the one named by
     * {@link #NATIVE_HELPER_PROPERTY}, else {@link #NATIVE_HELPER_NAME} beside this
     * process's own launcher - which is where a packaged build's second launcher lands.
     */
    static Path findNativeHelper() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        String named = System.getProperty(NATIVE_HELPER_PROPERTY);
        if (!isBlank(named)) {
            Path path = Path.of(named.trim());
            return Files.isRegularFile(path) ? path : null;
        }
        String launcher = ProcessHandle.current().info().command().orElse(null);
        if (launcher == null) return null;
        Path sibling = Path.of(launcher).resolveSibling(NATIVE_HELPER_NAME);
        return Files.isRegularFile(sibling) ? sibling : null;
    }

    private static Path writeScript(String java, String classpath) throws IOException {
        Path dir = Files.createTempDirectory("lyra-askpass");
        Path script = dir.resolve("askpass.sh");
        String text = "#!/bin/sh\n"
                + "# Written by Lyra for this session: forwards git/ssh prompts to the app.\n"
                + "exec " + shellQuote(forwardSlashes(java))
                + " -Xshare:auto -XX:TieredStopAtLevel=1 -XX:+UseSerialGC"
                + " -cp " + shellQuote(forwardSlashes(classpath))
                + " " + AskpassMain.class.getName() + " \"$@\"\n";
        Files.writeString(script, text, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException windows) {
            // No POSIX permissions; git for Windows decides by the #! line instead.
            File file = script.toFile();
            file.setExecutable(true, true);
        }
        script.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        return script;
    }

    /** {@code C:\x} becomes {@code C:/x}, which both a POSIX shell and Windows accept. */
    private static String forwardSlashes(String path) {
        return path.replace('\\', '/');
    }

    /** Single-quotes for /bin/sh: the only character needing care inside is ' itself. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
