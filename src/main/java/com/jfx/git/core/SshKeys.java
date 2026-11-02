package com.jfx.git.core;

import com.jfx.git.model.Profile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * SSH keys that belong to a profile rather than to the machine.
 *
 * <p>A profile already says who commits are from. Someone with a work account and a
 * personal one on the same host needs it to say which key talks to the remote as well:
 * with both keys in {@code ~/.ssh}, ssh offers the first the server accepts, and a push
 * from the personal repository lands - or is refused - as the work account. Naming the
 * key per profile, and offering only that one, is what makes "this repository is my
 * personal one" true for the network as well as for the commit.
 *
 * <p>The key is not written into the repository's configuration. It is looked up from
 * the profile that is active on the repository each time something talks to a remote,
 * so changing the profile's key changes it everywhere the profile is used, and removing
 * the profile leaves nothing behind in any repository.
 */
public final class SshKeys {

    private SshKeys() {
    }

    /**
     * The private key of the profile active on the repository at {@code workTree}, or
     * {@code null} when there is no such profile, it names no key, or the file has gone.
     *
     * <p>A missing file is "no key" rather than an error: ssh then offers what it would
     * have offered anyway, which fails with ssh's own message if nothing else fits,
     * where naming a file that is not there fails every connection outright.
     */
    public static Path keyFor(File workTree) {
        if (workTree == null) return null;
        try {
            Profile profile = ProfileStore.profile(ProfileStore.activeProfileName(ProfileStore.key(workTree)));
            if (profile == null || profile.sshKeyPath() == null || profile.sshKeyPath().isBlank()) {
                return null;
            }
            Path key = Path.of(profile.sshKeyPath().trim());
            return Files.isRegularFile(key) ? key : null;
        } catch (RuntimeException e) {
            // A path the platform cannot parse, or settings that cannot be read.
            return null;
        }
    }

    /** {@code ~/.ssh}, where a new key is offered a place by default. */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".ssh");
    }

    /** The public half's file for {@code privateKey}: the same path with {@code .pub}. */
    public static Path publicKeyFile(Path privateKey) {
        return privateKey.resolveSibling(privateKey.getFileName() + ".pub");
    }

    /**
     * The public key that goes with {@code privateKey}, as the one line a hosting site's
     * "add SSH key" form wants, or {@code null} when there is no {@code .pub} beside it.
     */
    public static String publicKey(Path privateKey) {
        if (privateKey == null) return null;
        try {
            Path pub = publicKeyFile(privateKey);
            if (!Files.isRegularFile(pub)) return null;
            String text = Files.readString(pub, StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Generates an Ed25519 key pair with {@code ssh-keygen}.
     *
     * <p>Ed25519 because it is what every current host accepts and recommends, and the
     * keys are short enough to read. {@code ssh-keygen} rather than a library because
     * it is the program whose output every ssh already reads, and it comes with both
     * Windows and Git for Windows as well as every Linux that can use an SSH remote.
     *
     * <p>Refuses to write over an existing file: a private key is not something to
     * replace by accident, and {@code ssh-keygen} would otherwise stop to ask on a
     * terminal nobody is looking at.
     *
     * @param privateKey where to write the private key; {@code .pub} is written beside it
     * @param comment    what the public key is labelled with, usually an email address
     * @param passphrase the key's passphrase; blank or {@code null} for none
     */
    public static GitResult generate(Path privateKey, String comment, String passphrase) {
        if (privateKey == null) {
            return GitResult.failed("No file was chosen for the key");
        }
        if (Files.exists(privateKey) || Files.exists(publicKeyFile(privateKey))) {
            return GitResult.failed(privateKey.getFileName() + " already exists. Choose another name,"
                    + " or use the existing key.");
        }
        String keygen = findKeygen();
        if (keygen == null) {
            return GitResult.failed("ssh-keygen was not found on this machine. It comes with OpenSSH and"
                    + " with Git for Windows; generate the key with either and choose it here.");
        }
        try {
            Path parent = privateKey.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> command = new ArrayList<>(List.of(keygen, "-t", "ed25519", "-q",
                    "-f", privateKey.toAbsolutePath().toString(),
                    "-C", comment == null ? "" : comment.trim(),
                    "-N", passphrase == null ? "" : passphrase));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return GitResult.failed("ssh-keygen did not finish");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(privateKey)) {
                return GitResult.failed("ssh-keygen could not create the key"
                        + (output.isEmpty() ? "" : ": " + output));
            }
            return GitResult.ok("Created " + privateKey.getFileName() + " and "
                    + publicKeyFile(privateKey).getFileName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GitResult.failed("Interrupted while generating the key");
        } catch (Exception e) {
            String message = e.getMessage();
            return GitResult.failed("Could not generate the key: "
                    + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message));
        }
    }

    /**
     * {@code ssh-keygen} on the {@code PATH}, or in the two places Windows keeps one
     * without always putting it there; {@code null} when there is none.
     */
    static String findKeygen() {
        if (TerminalLauncher.onPath("ssh-keygen")) {
            return "ssh-keygen";
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            List<String> candidates = new ArrayList<>();
            String windows = System.getenv("SystemRoot");
            if (windows != null) candidates.add(windows + "\\System32\\OpenSSH\\ssh-keygen.exe");
            for (String programs : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramW6432")}) {
                if (programs != null) candidates.add(programs + "\\Git\\usr\\bin\\ssh-keygen.exe");
            }
            for (String candidate : candidates) {
                if (new File(candidate).isFile()) return candidate;
            }
        }
        return null;
    }
}
