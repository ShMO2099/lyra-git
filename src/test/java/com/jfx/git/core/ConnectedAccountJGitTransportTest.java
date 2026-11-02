package com.jfx.git.core;

import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.ProviderSessions;
import com.jfx.git.testsupport.FakeSmartHttpServer;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connected account over a real smart-HTTP exchange, through the JGit engine's
 * credentials: a clone and a push are let in on the token, and a server that refuses the
 * token gets the user's own login on JGit's retry - within the same operation.
 */
class ConnectedAccountJGitTransportTest {

    @TempDir
    Path tempDir;

    private FakeSmartHttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(NativeGitRunner.isGitInstalled(), "the fake server runs git http-backend");
        Settings.useFile(tempDir.resolve("settings.properties"));
        CredentialsManager.useHelper(null, true);
        Path served = Files.createDirectories(tempDir.resolve("served"));
        try (Git bare = Git.init().setBare(true).setDirectory(served.resolve("repo.git").toFile())
                .setInitialBranch("main").call()) {
            bare.getRepository().getConfig().setBoolean("http", null, "receivepack", true);
            bare.getRepository().getConfig().save();
        }
        server = new FakeSmartHttpServer(served);
        // The provider's server is this machine's fake one; ProviderHosts allows plain http
        // only for loopback, which is exactly this case.
        ProviderHosts.setGithub(server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        for (HostingProvider p : HostingProvider.values()) ProviderSessions.disconnect(p);
        CredentialsManager.getInstance().clear();
        CredentialsManager.useHelper(null, false);
        Settings.useFile(null);
    }

    /** Clones and pushes one commit, silently: nothing may prompt. */
    private void cloneAndPush(Path into) throws Exception {
        Exception[] failure = new Exception[1];
        CredentialsManager.runSilently(() -> {
            try (Git git = Git.cloneRepository().setURI(server.url("repo.git"))
                    .setDirectory(into.toFile())
                    .setCredentialsProvider(CredentialsManager.getInstance().jgit()).call()) {
                Files.writeString(into.resolve("a.txt"), "hello");
                git.add().addFilepattern("a.txt").call();
                git.commit().setMessage("first").setAuthor("T", "t@example.com")
                        .setCommitter("T", "t@example.com").setSign(false).call();
                git.push().setRemote("origin").add("HEAD:refs/heads/main")
                        .setCredentialsProvider(CredentialsManager.getInstance().jgit()).call()
                        .forEach(result -> result.getRemoteUpdates().forEach(update ->
                                assertEquals(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK,
                                        update.getStatus(), update.toString())));
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
    }

    @Test
    void aCloneAndPushGoInOnTheConnectedAccount() throws Exception {
        server.accept("octocat", "ghp_token");
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));

        cloneAndPush(tempDir.resolve("work"));

        List<FakeSmartHttpServer.Login> logins = server.received();
        assertTrue(!logins.isEmpty(), "the server was asked to authenticate");
        assertTrue(logins.stream().allMatch(l -> l.password().equals("ghp_token")), logins.toString());
    }

    @Test
    void aRefusedTokenFallsBackToTheUsersLoginInTheSameOperation() throws Exception {
        server.accept("me", "my-password");
        GitHubSession.connect("ghp_no_push_rights", new GitHubUser("octocat", null));
        CredentialsManager.useHelper(
                GitCredentialHelperTest.helperAnswering(tempDir, "me", "my-password"), false);

        cloneAndPush(tempDir.resolve("work"));

        List<FakeSmartHttpServer.Login> logins = server.received();
        assertEquals("ghp_no_push_rights", logins.get(0).password(), "the connected account first");
        assertTrue(logins.stream().skip(1).allMatch(l -> l.password().equals("my-password")),
                "then the user's own login, and never the token again: " + logins);
        assertTrue(ProviderSessions.isRejectedForGit(HostingProvider.GITHUB));
    }
}
