package com.jfx.git.core;

import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.transport.sshd.agent.ConnectorFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Installs the SSH transport used for {@code git@host:repo} remotes.
 *
 * <p>JGit ships no SSH implementation by default, so before this existed every SSH
 * remote failed with "Transport not supported". The transport here is Apache MINA
 * sshd rather than the older JSch binding: JSch is unmaintained and rejects the
 * ed25519 and {@code rsa-sha2} key types that GitHub and GitLab now require.
 *
 * <p>Two things make this behave the way a user expects from a desktop client:
 *
 * <ul>
 *   <li><b>ssh-agent</b> - {@code withDefaultConnectorFactory()} lets the session ask
 *       a running agent (or Pageant / the Windows OpenSSH service) to sign the
 *       challenge. A key already unlocked in the agent never prompts, which is the
 *       whole point of running one.</li>
 *   <li><b>~/.ssh/config</b> - the factory reads it, so per-host {@code IdentityFile},
 *       {@code User}, {@code Port} and {@code ProxyJump} entries are honoured rather
 *       than silently ignored.</li>
 * </ul>
 *
 * <p>Passphrases for keys the agent does not hold are prompted through
 * {@link CredentialsManager}, the same provider the HTTPS transport uses.
 */
public final class SshSessionManager {

    /** Key filenames OpenSSH itself tries, newest algorithm first. */
    private static final List<String> DEFAULT_KEYS =
            List.of("id_ed25519", "id_ecdsa", "id_rsa", "id_dsa");

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(SshSessionManager.class.getName());

    private static SshdSessionFactory factory;
    private static String status = "SSH transport not installed";

    /** One factory per profile key; see {@link #factoryForKey}. */
    private static final java.util.Map<Path, SshdSessionFactory> keyFactories = new java.util.HashMap<>();

    private SshSessionManager() {
    }

    /** Set by {@link #installInBackground} until its install has finished; see {@link #awaitInstall}. */
    private static volatile java.util.concurrent.CountDownLatch installing;

    /** How long a remote operation waits for a background install before going on without it. */
    private static final long INSTALL_WAIT_SECONDS = 60;

    /**
     * {@link #install}, on a thread of its own.
     *
     * <p>Building the factory loads Apache MINA and, through the agent connector, JNA and
     * its native library - work that held the window back from appearing when it ran
     * before {@code show()}. Nothing needs SSH until a remote operation, which happens on
     * a git lane, so those wait for it instead: {@link #factoryForKey} - asked by every
     * JGit command on a repository - waits for the install, and so does JGit's
     * process-wide factory, which stands in as {@link AwaitingFactory} until the real
     * one is ready (a clone, which has no repository yet to ask about keys).
     */
    public static void installInBackground() {
        java.util.concurrent.CountDownLatch latch;
        synchronized (SshSessionManager.class) {
            if (factory != null || installing != null) {
                return;
            }
            latch = new java.util.concurrent.CountDownLatch(1);
            installing = latch;
        }
        Thread thread = new Thread(() -> {
            try {
                // First, so the window in which JGit could still hand out its own
                // unconfigured default is as short as this thread takes to start.
                SshSessionFactory.setInstance(AwaitingFactory.INSTANCE);
                install();
            } finally {
                latch.countDown();
                installing = null;
            }
        }, "ssh-setup");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Waits for a background {@link #installInBackground install} still running, if any.
     * A no-op once it has finished, and when the install was synchronous.
     */
    static void awaitInstall() {
        java.util.concurrent.CountDownLatch latch = installing;
        if (latch == null) {
            return;
        }
        try {
            if (!latch.await(INSTALL_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warning("SSH setup did not finish within " + INSTALL_WAIT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * JGit's process-wide SSH factory while the real one is being built: it waits for
     * {@link #install} and hands every request to whatever that installed.
     *
     * <p>Transports capture the factory when they are created, so one made during the
     * install keeps this object - which is why it delegates rather than only waiting.
     * Sessions are released through the base class, which only disconnects them; the
     * MINA factory does not override that.
     */
    private static final class AwaitingFactory extends SshSessionFactory {

        static final AwaitingFactory INSTANCE = new AwaitingFactory();

        @Override
        public org.eclipse.jgit.transport.RemoteSession getSession(
                org.eclipse.jgit.transport.URIish uri,
                org.eclipse.jgit.transport.CredentialsProvider credentialsProvider,
                org.eclipse.jgit.util.FS fs, int tms)
                throws org.eclipse.jgit.errors.TransportException {
            awaitInstall();
            SshSessionFactory real = SshSessionFactory.getInstance();
            if (real == this) {
                throw new org.eclipse.jgit.errors.TransportException(uri, status());
            }
            return real.getSession(uri, credentialsProvider, fs, tms);
        }

        @Override
        public String getType() {
            SshSessionFactory real = SshSessionFactory.getInstance();
            return real == this || real == null ? "sshd" : real.getType();
        }
    }

    /**
     * Makes SSH remotes usable process-wide. Safe to call more than once.
     *
     * <p>Never throws: a broken SSH setup must not stop the application starting,
     * because HTTPS remotes still work without it.
     */
    public static synchronized void install() {
        if (factory != null) {
            return;
        }
        try {
            File home = new File(System.getProperty("user.home", "."));
            File sshDir = new File(home, ".ssh");

            factory = new SshdSessionFactoryBuilder()
                    // publickey first so an agent-held key is tried before we ever
                    // consider prompting; password last as the fallback for hosts
                    // that allow it.
                    .setPreferredAuthentications("publickey,keyboard-interactive,password")
                    .setHomeDirectory(home)
                    .setSshDirectory(sshDir)
                    .withDefaultConnectorFactory()
                    .build(new JGitKeyCache());

            SshSessionFactory.setInstance(factory);
            status = describe(sshDir);
            LOG.info(status);
        } catch (Throwable t) {
            // Throwable, not Exception: a missing optional agent module surfaces as
            // NoClassDefFoundError, and that must stay non-fatal.
            factory = null;
            status = "SSH unavailable: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " - " + t.getMessage());
            // Back to whatever JGit finds for itself, as before there was a background
            // install, rather than leaving the stand-in that can only refuse.
            if (SshSessionFactory.getInstance() == AwaitingFactory.INSTANCE) {
                SshSessionFactory.setInstance(null);
            }
        }
    }

    /**
     * The transport that offers {@code key} as its only default identity, for a
     * repository whose profile names one - see {@link SshKeys} - or {@code null} when
     * there is no key or no transport.
     *
     * <p>A factory of its own rather than a setting on the shared one: the shared
     * factory reads its default identities once and keeps them, and it serves every
     * repository in the application. {@code ~/.ssh/config} and a running agent are
     * still consulted, as they are for the CLI's {@code -i}; what changes is which key
     * file is tried when neither names one.
     *
     * <p>Waits for a background install first: answering {@code null} because the shared
     * factory was not built yet would quietly connect with the wrong key.
     */
    public static SshSessionFactory factoryForKey(Path key) {
        awaitInstall();
        return factoryForKeyInstalled(key);
    }

    private static synchronized SshSessionFactory factoryForKeyInstalled(Path key) {
        if (key == null || factory == null) {
            return null;
        }
        Path absolute = key.toAbsolutePath();
        SshdSessionFactory existing = keyFactories.get(absolute);
        if (existing != null) {
            return existing;
        }
        try {
            File home = new File(System.getProperty("user.home", "."));
            SshdSessionFactory built = new SshdSessionFactoryBuilder()
                    .setPreferredAuthentications("publickey,keyboard-interactive,password")
                    .setHomeDirectory(home)
                    .setSshDirectory(new File(home, ".ssh"))
                    .setDefaultIdentities(sshDir -> List.of(absolute))
                    .withDefaultConnectorFactory()
                    .build(new JGitKeyCache());
            keyFactories.put(absolute, built);
            return built;
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.WARNING, "Could not prepare SSH for " + absolute, t);
            return null;
        }
    }

    /** Releases cached keys. Called when the application shuts down. */
    public static synchronized void shutdown() {
        for (SshdSessionFactory perKey : keyFactories.values()) {
            try {
                perKey.close();
            } catch (Exception ignored) {
                // As below: nothing useful to do while exiting.
            }
        }
        keyFactories.clear();
        if (factory != null) {
            try {
                factory.close();
            } catch (Exception ignored) {
                // Nothing useful to do while exiting.
            }
            factory = null;
            status = "SSH transport not installed";
        }
    }

    /**
     * Drops every private key the SSH transport has already decrypted.
     *
     * <p>JGit's key cache holds a key unlocked for the life of the factory, so forgetting
     * the passphrase alone did not forget the key: the next fetch still signed with it.
     * The factory is rebuilt - closing the old one closes its key cache - so the next
     * connection has to unlock the key again. Keys held by an ssh-agent are the agent's
     * business and are not affected.
     */
    public static synchronized void forgetUnlockedKeys() {
        if (factory == null) return;
        shutdown();
        install();
    }

    /** One line describing what SSH will authenticate with, for the About/status UI. */
    public static synchronized String status() {
        return status;
    }

    public static synchronized boolean isInstalled() {
        return factory != null;
    }

    /** Whether a usable ssh-agent connector was found on this machine. */
    public static boolean hasAgent() {
        try {
            ConnectorFactory connectors = ConnectorFactory.getDefault();
            return connectors != null && connectors.isSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The private keys OpenSSH would offer from {@code ~/.ssh}.
     *
     * <p>Only the conventional filenames are reported. Keys named by an
     * {@code IdentityFile} line in {@code ~/.ssh/config} are still used by the
     * transport - they just do not show up in this summary, which exists to tell the
     * user "we can see a key" rather than to enumerate the configuration.
     */
    static List<String> defaultKeys(File sshDir) {
        List<String> found = new ArrayList<>();
        for (String name : DEFAULT_KEYS) {
            File key = new File(sshDir, name);
            // A private key with no matching .pub is usually a stray file, not an identity.
            if (key.isFile() && new File(sshDir, name + ".pub").isFile()) {
                found.add(name);
            }
        }
        return found;
    }

    /** True when {@code ~/.ssh/config} exists and can be read. */
    static boolean hasConfig(File sshDir) {
        File config = new File(sshDir, "config");
        return config.isFile() && config.canRead();
    }

    static String describe(File sshDir) {
        List<String> parts = new ArrayList<>();
        if (hasAgent()) {
            parts.add("ssh-agent");
        }
        List<String> keys = defaultKeys(sshDir);
        if (!keys.isEmpty()) {
            parts.add(String.join(", ", keys));
        }
        if (hasConfig(sshDir)) {
            parts.add("~/.ssh/config");
        }
        if (parts.isEmpty()) {
            return "SSH ready, but no agent or key found in " + sshDir;
        }
        return "SSH ready: " + String.join(" + ", parts);
    }

    /**
     * Counts readable entries in {@code ~/.ssh}, used only by the diagnostics text.
     * Returns 0 rather than failing when the directory is absent.
     */
    static int sshDirEntryCount(File sshDir) {
        Path dir = sshDir.toPath();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return (int) entries.count();
        } catch (Exception e) {
            return 0;
        }
    }
}
