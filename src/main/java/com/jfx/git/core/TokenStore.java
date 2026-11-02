package com.jfx.git.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provider access tokens that survive a restart, kept where the operating system keeps
 * secrets rather than in {@link Settings}.
 *
 * <h2>Why not just a settings key</h2>
 * {@code Settings} is a plain properties file the user is invited to edit and hand to a
 * colleague. A GitHub personal access token in it is a credential in a file nobody treats
 * as a credential store, backed up and synced as an ordinary config file. So tokens were
 * memory-only, and the user re-pasted them every launch - which is its own security
 * problem, because a token you retype weekly is a token you keep somewhere convenient.
 *
 * <h2>What this uses</h2>
 * The platform's own facility, through the tooling it already ships. No new dependency,
 * and nothing here invents cryptography:
 *
 * <dl>
 *   <dt>Windows</dt>
 *   <dd>DPAPI, which encrypts with a key derived from the logged-in user's credentials -
 *       called directly ({@link WindowsDpapi}), or through PowerShell's
 *       {@code ConvertFrom-SecureString} where that cannot be done, in the same stored
 *       format either way. The ciphertext is stored in this application's own file;
 *       another user on the same machine, or the same file copied elsewhere, cannot
 *       decrypt it.</dd>
 *   <dt>Linux and other Unix</dt>
 *   <dd>The freedesktop Secret Service, through {@code secret-tool} - which is what
 *       GNOME Keyring and KWallet both answer.</dd>
 * </dl>
 *
 * <p>When the platform's tool is missing - {@code secret-tool} is not installed
 * everywhere - {@link #isAvailable()} is false, nothing is written, and the application
 * behaves as it did before: tokens live for the session and are asked for again next
 * launch. Storing a secret in a worse place than the user expects would be the wrong
 * answer to a missing keychain.
 *
 * <h2>Opt-in</h2>
 * Nothing here is called unless the user ticks "Remember". Writing a credential anywhere
 * is the user's decision, not a default.
 */
public final class TokenStore {

    private static final Logger LOGGER = Logger.getLogger(TokenStore.class.getName());

    /** What the entry is called wherever the platform lists stored secrets. */
    private static final String SERVICE = "Lyra";

    /**
     * What the entry was called before the application was renamed. A token found only
     * under this name is moved to {@link #SERVICE} the first time it is read.
     */
    private static final String LEGACY_SERVICE = "JFxGit";

    /** Long enough for a keychain prompt the user has to answer, short enough to fail. */
    private static final int TIMEOUT_SECONDS = 30;

    /** Where the Windows ciphertext lives; the plaintext never reaches this file. */
    private static final String WINDOWS_SETTINGS_PREFIX = "integration.token.";

    private TokenStore() {
    }

    private enum Platform { WINDOWS, SECRET_SERVICE, NONE }

    private static volatile Platform platform;
    /** Whether a NONE answer has already been given its one second chance. */
    private static boolean retried;

    /**
     * Synchronised so the warm-up and a first caller arriving during it share one
     * detection: unsynchronised, both ran the PowerShell probe, and the caller paid for it
     * in full despite the warm-up having started first.
     *
     * <p>A "no store here" answer is checked once more on the next call. The probe has a
     * timeout, and a machine still busy starting up - antivirus scanning PowerShell on its
     * first launch - could miss it, after which the remembered tokens were not even
     * looked at for the rest of that session.
     */
    private static synchronized Platform platform() {
        if (platform == null || (platform == Platform.NONE && !retried)) {
            if (platform == Platform.NONE) {
                retried = true;
            }
            platform = detect();
            LOGGER.fine("Token storage: " + platform);
        }
        return platform;
    }

    private static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // DPAPI in-process first: a round trip costs microseconds, where the
            // PowerShell probe below is two process launches.
            if (WindowsDpapi.isAvailable()
                    && CAPABILITY_PROBE.equals(WindowsDpapi.unprotect(
                            String.valueOf(WindowsDpapi.protect(CAPABILITY_PROBE))))) {
                return Platform.WINDOWS;
            }
            return windowsEncryptionWorks() ? Platform.WINDOWS : Platform.NONE;
        }
        return canRun("secret-tool", "--version") ? Platform.SECRET_SERVICE : Platform.NONE;
    }

    /**
     * Whether DPAPI actually round-trips here, rather than whether PowerShell starts.
     *
     * <p>{@code powershell -Command "exit 0"} succeeds on a machine where
     * {@code Microsoft.PowerShell.Security} will not load - and that module is where
     * {@code ConvertFrom-SecureString} lives, so the check passed, "Remember" was offered,
     * and every store silently did nothing. The probe runs the two commands that storing
     * a token actually runs, on a value that is not a secret, and insists on getting it
     * back.
     */
    private static boolean windowsEncryptionWorks() {
        try {
            String cipher = run(CAPABILITY_PROBE, windowsEncryptCommand());
            if (cipher == null || cipher.isBlank()) return false;
            String plain = run(cipher.trim(), windowsDecryptCommand());
            return plain != null && CAPABILITY_PROBE.equals(plain.trim());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "DPAPI is not usable here", e);
            return false;
        }
    }

    /** Not a secret: it only has to survive an encrypt/decrypt round trip. */
    private static final String CAPABILITY_PROBE = "lyra-token-store-probe";

    /**
     * Whether this machine can keep a token past the end of the session.
     *
     * <p>The first call starts subprocesses, so it can take a moment - call it off the FX
     * thread, or after {@link #warmUp()} has already settled the answer. Later calls are
     * cached and free.
     */
    public static boolean isAvailable() {
        return platform() != Platform.NONE;
    }

    /**
     * Works out what this machine can do, off the calling thread.
     *
     * <p>The detection can run a helper process - {@code secret-tool}, or PowerShell
     * where DPAPI cannot be called directly - and the first thing to ask used to be a
     * dialog being built on the FX thread. The application no longer calls this at
     * startup: {@code IntegrationSessions.restoreInBackground} asks the same question on
     * the provider pool at the same moment, and on Windows the answer is now in-process.
     */
    public static void warmUp() {
        Thread thread = new Thread(TokenStore::platform, "token-store-detect");
        thread.setDaemon(true);
        thread.start();
    }

    /** How the UI should describe where a remembered token goes, for the checkbox's hint. */
    public static String describeLocation() {
        return switch (platform()) {
            case WINDOWS -> "encrypted for your Windows account (DPAPI)";
            case SECRET_SERVICE -> "your desktop keyring";
            case NONE -> "nowhere - this machine has no keychain this app can use";
        };
    }

    /**
     * Remembers {@code token} under {@code account}, replacing anything stored before.
     *
     * @return whether it was actually stored
     */
    public static boolean store(String account, String token) {
        if (token == null || token.isBlank() || !isAvailable()) return false;
        try {
            return switch (platform()) {
                case WINDOWS -> storeWindows(account, token);
                case SECRET_SERVICE -> run(token, "secret-tool", "store", "--label=" + SERVICE,
                        "service", SERVICE, "account", account) != null;
                case NONE -> false;
            };
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not store a token for " + account, e);
            return false;
        }
    }

    /** The token remembered for {@code account}, if there is one and it can be read. */
    public static Optional<String> load(String account) {
        if (!isAvailable()) return Optional.empty();
        try {
            String value = switch (platform()) {
                case WINDOWS -> loadWindows(account);
                case SECRET_SERVICE -> loadSecretService(account);
                case NONE -> null;
            };
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read a token for " + account, e);
            return Optional.empty();
        }
    }

    /** Forgets the token for {@code account}. Not an error when there was none. */
    public static void remove(String account) {
        if (!isAvailable()) return;
        try {
            switch (platform()) {
                case WINDOWS -> Settings.remove(WINDOWS_SETTINGS_PREFIX + account);
                case SECRET_SERVICE -> {
                    run(null, "secret-tool", "clear", "service", SERVICE, "account", account);
                    run(null, "secret-tool", "clear", "service", LEGACY_SERVICE, "account", account);
                }
                case NONE -> { }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not remove the token for " + account, e);
        }
    }

    /** The keyring's token, moving one stored under {@link #LEGACY_SERVICE} across. */
    private static String loadSecretService(String account) {
        String value = run(null, "secret-tool", "lookup", "service", SERVICE, "account", account);
        if (value != null && !value.isBlank()) return value;
        String legacy = run(null, "secret-tool", "lookup", "service", LEGACY_SERVICE, "account", account);
        if (legacy == null || legacy.isBlank()) return value;
        // Cleared only once the copy is in place: a failed store keeps the old entry,
        // which is read again next time.
        if (run(legacy.trim(), "secret-tool", "store", "--label=" + SERVICE,
                "service", SERVICE, "account", account) != null) {
            run(null, "secret-tool", "clear", "service", LEGACY_SERVICE, "account", account);
        }
        return legacy;
    }

    // ------------------------------------------------------------------
    // Windows: DPAPI ciphertext in this application's own settings file
    // ------------------------------------------------------------------

    /** DPAPI-encrypts whatever arrives on standard input. */
    private static String[] windowsEncryptCommand() {
        return new String[]{"powershell", "-NoProfile", "-NonInteractive", "-Command",
                "$plain = [Console]::In.ReadToEnd().Trim();"
                        + "ConvertTo-SecureString $plain -AsPlainText -Force |"
                        + " ConvertFrom-SecureString"};
    }

    /** The inverse, for whatever ciphertext arrives on standard input. */
    private static String[] windowsDecryptCommand() {
        return new String[]{"powershell", "-NoProfile", "-NonInteractive", "-Command",
                "$cipher = [Console]::In.ReadToEnd().Trim();"
                        + "$secure = ConvertTo-SecureString $cipher;"
                        + "[Runtime.InteropServices.Marshal]::PtrToStringAuto("
                        + "[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))"};
    }

    /**
     * Encrypts with DPAPI and keeps the result in {@link Settings}.
     *
     * <p>The Credential Manager would be the tidier home, but reading a password back out
     * of it needs {@code CredRead} - a native call, and {@code cmdkey} can write a
     * credential without being able to read one. DPAPI gives the property that matters,
     * which is that the stored bytes are useless to anyone but this Windows user, and it
     * is reachable from a command line that ships with the OS.
     *
     * <p>The token is passed on standard input, never as a command-line argument, because
     * arguments are visible to any process that can list them.
     */
    private static boolean storeWindows(String account, String token) throws Exception {
        // Trimmed as the PowerShell command trims what it reads, so either path stores
        // the same thing. See WindowsDpapi for why the two formats are one.
        String cipher = WindowsDpapi.protect(token.trim());
        if (cipher == null) {
            cipher = run(token, windowsEncryptCommand());
        }
        if (cipher == null || cipher.isBlank()) return false;
        Settings.put(WINDOWS_SETTINGS_PREFIX + account, cipher.trim());
        Settings.flush();
        return true;
    }

    private static String loadWindows(String account) throws Exception {
        String cipher = Settings.get(WINDOWS_SETTINGS_PREFIX + account, null);
        if (cipher == null || cipher.isBlank()) return null;
        String plain = WindowsDpapi.unprotect(cipher);
        return plain != null ? plain : run(cipher, windowsDecryptCommand());
    }

    /** For tests: encrypts through PowerShell, as every version before the direct call did. */
    static String encryptWithPowerShell(String plain) {
        String cipher = run(plain, windowsEncryptCommand());
        return cipher == null ? null : cipher.trim();
    }

    /** For tests: decrypts through PowerShell, the fallback where DPAPI cannot be called directly. */
    static String decryptWithPowerShell(String cipher) {
        String plain = run(cipher, windowsDecryptCommand());
        return plain == null ? null : plain.trim();
    }

    // ------------------------------------------------------------------
    // Running the platform's tool
    // ------------------------------------------------------------------

    private static boolean canRun(String... command) {
        try {
            return run(null, command) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs a helper, writing {@code input} to its standard input, and returns its output -
     * or {@code null} when it failed, was killed, or ran out of time.
     *
     * <h4>Why all three pipes get a thread</h4>
     * This used to read standard output to end-of-stream on the calling thread and only
     * then call {@code waitFor} with a timeout. A helper that never closes its output
     * therefore never reaches the timeout at all: the read blocks first, and the deadline
     * that was supposed to bound the whole thing is never consulted. Writing the input
     * could stall the same way, on a helper that does not read it.
     *
     * <p>So the deadline goes on {@code waitFor}, which is the one call that really is
     * bounded, and the pipes are serviced by threads that cannot hold it up. Killing the
     * process is what unblocks them.
     *
     * <h4>Why standard error is read and thrown away</h4>
     * It must be read: a helper that fills the stderr pipe buffer blocks writing to it and
     * never exits, so leaving it undrained is another way to hang. It must not be logged:
     * these tools put the secret's own label, and sometimes prompts about it, on stderr,
     * and a log file is exactly where none of that should end up.
     */
    private static String run(String input, String... command) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException cannotStart) {
            LOGGER.log(Level.FINE, "Could not start " + command[0], cannotStart);
            return null;
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Thread writer = pump("token-store-stdin", () -> {
            try (var out = process.getOutputStream()) {
                if (input != null) {
                    out.write(input.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException closedEarly) {
                LOGGER.log(Level.FINE, "The helper closed its input early", closedEarly);
            }
        });
        Thread reader = pump("token-store-stdout", () -> copy(process.getInputStream(), stdout));
        Thread errors = pump("token-store-stderr", () -> copy(process.getErrorStream(), null));

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.fine(command[0] + " did not finish within " + TIMEOUT_SECONDS + "s");
                return null;
            }
            // The process has exited, so its pipes are at end-of-stream and these joins
            // return almost at once; the bound is there so a wedged pump thread cannot
            // become the new way for this to hang.
            settle(reader);
            settle(errors);
            settle(writer);
            if (process.exitValue() != 0) return null;
            return stdout.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            // Every exit path, including the timeout and the interrupt: a helper left
            // running holds the pipes open, keeps its pump threads blocked, and on Windows
            // leaves a PowerShell process nobody asked for.
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** A daemon thread for one end of a subprocess pipe. */
    private static Thread pump(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Waits briefly for a pump thread to notice its pipe has closed. */
    private static void settle(Thread thread) throws InterruptedException {
        thread.join(PIPE_SETTLE_MILLIS);
    }

    /** How long a pump thread gets to finish after the process it was serving has exited. */
    private static final long PIPE_SETTLE_MILLIS = 2_000;

    /** Reads {@code in} to the end, into {@code into} or into nothing. */
    private static void copy(InputStream in, ByteArrayOutputStream into) {
        try (in) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (into != null) {
                    synchronized (into) {
                        into.write(chunk, 0, read);
                    }
                }
            }
        } catch (IOException e) {
            // Expected when the process is destroyed out from under the read.
            LOGGER.log(Level.FINEST, "Helper pipe closed", e);
        }
    }
}
