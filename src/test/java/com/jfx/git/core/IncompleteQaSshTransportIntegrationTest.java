package com.jfx.git.core;

import com.jfx.git.model.PullMode;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** R19: real SSH packets and Git pack transfers, using only disposable loopback identities. */
@Timeout(90)
class IncompleteQaSshTransportIntegrationTest {
    @TempDir Path root;

    private SshServer server;
    private SshSessionFactory previousFactory;
    private SshdSessionFactory isolatedFactory;
    private Git remote;
    private Path knownHosts;
    private Path sshDirectory;
    private KeyPair hostKey;
    private KeyPair clientKey;
    private String url;
    private final List<GitService> opened = new ArrayList<>();
    private final AtomicInteger commands = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @BeforeEach
    void startDisposableServer() throws Exception {
        // Existing helper hooks prevent these tests consulting or updating the user's GCM.
        CredentialsManager.useHelper(null, true);
        previousFactory = SshSessionFactory.getInstance();
        sshDirectory = Files.createDirectories(root.resolve("ssh"));
        knownHosts = sshDirectory.resolve("known_hosts");
        hostKey = keyPair();
        clientKey = keyPair();
        Path seed = Files.createDirectories(root.resolve("seed"));
        try (Git git = Git.init().setDirectory(seed.toFile()).setInitialBranch("main").call()) {
            identity(git);
            Files.writeString(seed.resolve(".gitattributes"), "* text eol=lf\n");
            Files.writeString(seed.resolve("base.txt"), "shared base\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("shared base").call();
        }
        remote = Git.cloneRepository().setURI(seed.toString()).setBare(true)
                .setDirectory(root.resolve("remote.git").toFile()).call();
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setPublickeyAuthenticator((username, key, session) ->
                "qa".equals(username) && KeyUtils.compareKeys(clientKey.getPublic(), key));
        server.setPasswordAuthenticator((username, password, session) -> false);
        // Never run a shell or an arbitrary client-supplied path: one synthetic Git repository.
        server.setCommandFactory((channel, command) -> {
            List<String> arguments = CommandFactory.split(command);
            if (arguments.size() != 2 || !"/repo.git".equals(arguments.get(1))
                    || !("git-upload-pack".equals(arguments.get(0))
                    || "git-receive-pack".equals(arguments.get(0)))) {
                throw new java.io.IOException("Unexpected test Git command");
            }
            return new PackCommand("git-receive-pack".equals(arguments.get(0)));
        });
        server.start();
        assertTrue(server.getPort() > 0);
        url = "ssh://qa@127.0.0.1:" + server.getPort() + "/repo.git";
        writeKnownHost(hostKey);
        Files.writeString(sshDirectory.resolve("config"), "Host *\n"
                + "  StrictHostKeyChecking yes\n  PreferredAuthentications publickey\n");
        installIsolatedFactory(clientKey);
    }

    @AfterEach
    void closeOnlyTestResources() throws Exception {
        try {
            for (GitService service : opened) service.close();
            SshSessionFactory.setInstance(previousFactory);
            if (isolatedFactory != null) isolatedFactory.close();
            if (server != null) server.stop(true);
            if (remote != null) remote.close();
            assertNull(serverFailure.get(), "The fixture's Git pack server failed");
        } finally {
            CredentialsManager.getInstance().clear();
            CredentialsManager.useHelper(null, false);
            org.eclipse.jgit.lib.RepositoryCache.clear();
        }
    }

    @Test
    void cloneCommitPushAndPeerFetchPullUseTheSshRemote() throws Exception {
        Path local = root.resolve("developer");
        GitResult clone = GitService.cloneRepository(url, local.toFile());
        assertTrue(clone.isOk(), clone::getMessage);
        GitService service = new GitService();
        opened.add(service);
        assertTrue(service.openRepository(local.toFile()));
        try (Git git = Git.open(local.toFile())) { identity(git); }
        assertEquals("origin/main", service.getUpstream("main"));
        Files.writeString(local.resolve("developer.txt"), "developer work\n");
        assertTrue(service.stageFile("developer.txt").isOk());
        assertTrue(service.commit("developer work", false).isOk());
        String developerTip = service.getHeadHash();
        GitResult push = new RepositoryCommands(service).push("origin", false, false, false);
        assertTrue(push.isOk(), push::getMessage);
        assertEquals(developerTip, remoteHead());

        String peerTip;
        try (Git peer = Git.cloneRepository().setURI(remote.getRepository().getDirectory().toString())
                .setDirectory(root.resolve("peer").toFile()).call()) {
            identity(peer);
            Files.writeString(root.resolve("peer/colleague.txt"), "colleague work\n");
            peer.add().addFilepattern("colleague.txt").call();
            peerTip = peer.commit().setMessage("colleague work").call().name();
            peer.push().call();
        }
        assertTrue(service.fetchAll().isOk());
        assertEquals(developerTip, service.getHeadHash(), "Fetch changes tracking, not checked-out HEAD");
        GitResult pull = service.pull(PullMode.FF_ONLY);
        assertTrue(pull.isOk(), pull::getMessage);
        assertEquals(peerTip, service.getHeadHash());
        assertEquals(peerTip, remoteHead());
        assertEquals("developer work\n", Files.readString(local.resolve("developer.txt")));
        assertEquals("colleague work\n", Files.readString(local.resolve("colleague.txt")));
        assertFalse(service.hasUncommittedChanges());
        assertTrue(commands.get() >= 4, "Clone, Push, Fetch and Pull reached the SSH pack server");
    }

    @Test
    void aRejectedClientKeyCannotCloneOrChangeRemoteHistory() throws Exception {
        installIsolatedFactory(keyPair());
        String initial = remoteHead();
        GitResult result = silentClone(root.resolve("rejected-key"));
        assertFalse(result.isOk(), "The server must reject a different synthetic identity");
        assertEquals(initial, remoteHead());
        assertEquals(0, commands.get(), "No Git pack command runs before authentication succeeds");
    }

    @ParameterizedTest(name = "changed recorded host key: {0}")
    @ValueSource(booleans = {false, true})
    void unknownAndChangedHostKeysAreRefusedWithoutPublishing(boolean changed) throws Exception {
        if (changed) writeKnownHost(keyPair());
        else Files.writeString(knownHosts, "");
        installIsolatedFactory(clientKey); // fresh host database, no prior connection cache
        byte[] before = Files.readAllBytes(knownHosts);
        String initial = remoteHead();
        GitResult result = silentClone(root.resolve("untrusted-host"));
        assertFalse(result.isOk(), "Strict host checking must refuse unknown/mismatched test hosts");
        assertEquals(initial, remoteHead());
        assertEquals(0, commands.get());
        assertArrayEquals(before, Files.readAllBytes(knownHosts), "Refusal must not silently trust a host");
    }

    private GitResult silentClone(Path destination) {
        AtomicReference<GitResult> result = new AtomicReference<>();
        CredentialsManager.runSilently(() -> result.set(GitService.cloneRepository(url, destination.toFile())));
        return result.get();
    }

    private void installIsolatedFactory(KeyPair key) throws Exception {
        if (isolatedFactory != null) isolatedFactory.close();
        isolatedFactory = new SshdSessionFactoryBuilder().setHomeDirectory(root.toFile())
                .setSshDirectory(sshDirectory.toFile()).setConfigFile(dir -> sshDirectory.resolve("config").toFile())
                .setPreferredAuthentications("publickey")
                .setDefaultIdentities(dir -> List.of())
                .setDefaultKeysProvider(dir -> List.of(key))
                .setDefaultKnownHostsFiles(dir -> List.of(knownHosts))
                .build(new JGitKeyCache());
        SshSessionFactory.setInstance(isolatedFactory);
    }

    private void writeKnownHost(KeyPair key) throws Exception {
        Files.writeString(knownHosts, "[127.0.0.1]:" + server.getPort() + " "
                + PublicKeyEntry.toString(key.getPublic()) + "\n", StandardCharsets.UTF_8);
    }

    private String remoteHead() throws Exception {
        return remote.getRepository().resolve("refs/heads/main").name();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static void identity(Git git) throws Exception {
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Synthetic SSH QA");
        config.setString("user", null, "email", "ssh-qa@example.test");
        config.setBoolean("core", null, "autocrlf", false);
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
    }

    private final class PackCommand implements Command {
        private final boolean receive;
        private InputStream input;
        private OutputStream output;
        private OutputStream error;
        private ExitCallback callback;
        private Thread worker;

        private PackCommand(boolean receive) { this.receive = receive; }
        @Override public void setInputStream(InputStream stream) { input = stream; }
        @Override public void setOutputStream(OutputStream stream) { output = stream; }
        @Override public void setErrorStream(OutputStream stream) { error = stream; }
        @Override public void setExitCallback(ExitCallback exit) { callback = exit; }
        @Override public void start(ChannelSession channel, Environment environment) {
            worker = new Thread(() -> {
                commands.incrementAndGet();
                try {
                    Repository repository = remote.getRepository();
                    if (receive) new ReceivePack(repository).receive(input, output, error);
                    else new UploadPack(repository).upload(input, output, error);
                    callback.onExit(0);
                } catch (Throwable failure) {
                    serverFailure.compareAndSet(null, failure);
                    callback.onExit(1, "Synthetic Git pack server failed");
                }
            }, "qa-ssh-git-pack");
            worker.setDaemon(true);
            worker.start();
        }
        @Override public void destroy(ChannelSession channel) throws Exception {
            if (worker != null && worker.isAlive()) {
                input.close();
                worker.interrupt();
                worker.join(2_000);
            }
        }
    }
}
