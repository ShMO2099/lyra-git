package com.jfx.git.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The user's git credential helpers - Git Credential Manager, osxkeychain, libsecret,
 * {@code store} - reached through {@code git credential fill/approve/reject}.
 *
 * <h2>Why</h2>
 * JGit has no notion of credential helpers, and clone always goes through JGit, so the
 * credentials a user has already saved for git were never used here: every clone of a
 * private HTTPS repository asked for a password the machine already knew, and what was
 * typed was never saved back - while Preferences told users to "use a git credential
 * helper". Asking git itself is the only way to honour whatever helper chain they have
 * configured, exactly as the CLI does.
 *
 * <h2>Never prompting from here</h2>
 * {@code git credential fill} prompts on a terminal, or through {@code GIT_ASKPASS}, when
 * no helper answers. Both are switched off for this call, so it either returns what a
 * helper had or fails fast - the application's own dialog is the prompt, and it runs
 * next. A helper's own window (GCM's sign-in) is allowed in an interactive call and
 * forbidden with {@code GCM_INTERACTIVE=never} in a silent one.
 */
public final class GitCredentialHelper {

    private static final Logger LOGGER = Logger.getLogger(GitCredentialHelper.class.getName());

    /** A helper answered with these. */
    public record Credential(String username, String password) { }

    private static final GitCredentialHelper DEFAULT = new GitCredentialHelper(Map.of());

    private final Map<String, String> extraEnvironment;

    /** @param extraEnvironment added to every {@code git credential} process; for tests */
    GitCredentialHelper(Map<String, String> extraEnvironment) {
        this.extraEnvironment = Map.copyOf(extraEnvironment);
    }

    /** The helper chain as the user configured it, or null when git is not installed. */
    public static GitCredentialHelper systemDefault() {
        return NativeGitRunner.isGitInstalled() ? DEFAULT : null;
    }

    /**
     * What a configured helper has for this URL, or {@code null} when none answers.
     *
     * @param silent forbid a helper's own interactive UI as well
     */
    public Credential fill(String protocol, String host, int port, String path, String username,
                           boolean silent) {
        return fill(protocol, host, port, path, username, silent, null);
    }

    /**
     * {@link #fill(String, String, int, String, String, boolean)} run in {@code directory},
     * so a repository's own {@code credential.*} configuration applies, as it does when git
     * asks its helpers from inside that repository; null for none.
     */
    public Credential fill(String protocol, String host, int port, String path, String username,
                           boolean silent, java.io.File directory) {
        String input = describe(protocol, host, port, path, username, null);
        String output = run("fill", input, silent, silent ? 20 : 300, directory);
        if (output == null) return null;
        Map<String, String> answer = parse(output);
        String user = answer.get("username");
        String password = answer.get("password");
        if (password == null) return null;
        return new Credential(user == null ? username : user, password);
    }

    /** Tells the helpers these credentials worked, so a storing helper keeps them. */
    public void approve(String protocol, String host, int port, String path, Credential credential) {
        approve(protocol, host, port, path, credential, null);
    }

    /** {@link #approve(String, String, int, String, Credential)} run in {@code directory}. */
    public void approve(String protocol, String host, int port, String path, Credential credential,
                        java.io.File directory) {
        run("approve", describe(protocol, host, port, path, credential.username(), credential.password()),
                true, 20, directory);
    }

    /** Tells the helpers these credentials were rejected, so a storing helper drops them. */
    public void reject(String protocol, String host, int port, String path, Credential credential) {
        reject(protocol, host, port, path, credential, null);
    }

    /** {@link #reject(String, String, int, String, Credential)} run in {@code directory}. */
    public void reject(String protocol, String host, int port, String path, Credential credential,
                       java.io.File directory) {
        run("reject", describe(protocol, host, port, path, credential.username(), credential.password()),
                true, 20, directory);
    }

    /**
     * The {@code key=value} block {@code git credential} reads. A value containing a
     * newline would let one field inject another, so such a value is refused outright.
     */
    static String describe(String protocol, String host, int port, String path,
                           String username, String password) {
        StringBuilder text = new StringBuilder();
        line(text, "protocol", protocol);
        line(text, "host", host == null ? null : (port > 0 ? host + ":" + port : host));
        if (path != null && !path.isBlank()) {
            line(text, "path", path.startsWith("/") ? path.substring(1) : path);
        }
        line(text, "username", username);
        line(text, "password", password);
        return text.append('\n').toString();
    }

    private static void line(StringBuilder text, String key, String value) {
        if (value == null || value.isEmpty()) return;
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A credential " + key + " cannot contain a line break");
        }
        text.append(key).append('=').append(value).append('\n');
    }

    static Map<String, String> parse(String output) {
        Map<String, String> values = new HashMap<>();
        for (String line : output.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return values;
    }

    /** Runs {@code git credential <action>}; its stdout, or null when it failed. */
    private String run(String action, String input, boolean silent, int timeoutSeconds, java.io.File directory) {
        List<String> command = new ArrayList<>(List.of("git", "credential", action));
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (directory != null && directory.isDirectory()) pb.directory(directory);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            // Empty stops git's askpass lookup, so an unanswered fill fails rather than
            // prompting through a program nobody is watching for.
            pb.environment().put("GIT_ASKPASS", "");
            pb.environment().put("SSH_ASKPASS", "");
            if (silent) pb.environment().put("GCM_INTERACTIVE", "never");
            pb.environment().putAll(extraEnvironment);
            process = pb.start();
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(Level.FINE, "Could not run git credential " + action, e);
            return null;
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Thread reader = drain(process.getInputStream(), stdout);
        Thread errors = drain(process.getErrorStream(), null);
        try (var in = process.getOutputStream()) {
            in.write(input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "git credential closed its input early", e);
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            reader.join(2_000);
            errors.join(2_000);
            return process.exitValue() == 0 ? stdout.toString(StandardCharsets.UTF_8) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return null;
        }
    }

    /** Reads a pipe to its end; stderr is read and dropped - helpers can echo secrets there. */
    private static Thread drain(InputStream in, ByteArrayOutputStream into) {
        Thread thread = new Thread(() -> {
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
            } catch (IOException ignored) {
                // The process went away; what was read is what there is.
            }
        }, "git-credential-pipe");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
