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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connected account through the git CLI: the application is git's credential helper
 * (over the askpass bridge), answers with the account for its own server, steps aside
 * when git refuses it - the command runs once more and the user's own helper answers -
 * and is never offered to a server that is not the connected one.
 */
@Tag("heavy")
class ConnectedAccountCliTransportTest {

    @TempDir
    Path tempDir;

    private FakeSmartHttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(NativeGitRunner.isGitInstalled(), "the git CLI and its http-backend");
        Assumptions.assumeTrue(AskpassBridge.shared() != null, "the CLI's credentials go through the bridge");
        Settings.useFile(tempDir.resolve("settings.properties"));
        CredentialsManager.useHelper(null, true);
        Path served = Files.createDirectories(tempDir.resolve("served"));
        try (Git bare = Git.init().setBare(true).setDirectory(served.resolve("repo.git").toFile())
                .setInitialBranch("main").call()) {
            bare.getRepository().getConfig().setBoolean("http", null, "receivepack", true);
            bare.getRepository().getConfig().save();
        }
        server = new FakeSmartHttpServer(served);
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

    /** Clones with the CLI engine, commits and tags with JGit, pushes the tag with the CLI engine. */
    private GitResult cloneAndPushTag(String url, File into) throws Exception {
        GitResult cloned = NativeGitBackend.cloneRepository(url, into);
        if (!cloned.isOk()) return cloned;
        try (Git git = Git.open(into)) {
            Files.writeString(into.toPath().resolve("a.txt"), "hello");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").setAuthor("T", "t@example.com")
                    .setCommitter("T", "t@example.com").setSign(false).call();
            git.tag().setName("v1").setAnnotated(false).call();
        }
        NativeGitBackend backend = new NativeGitBackend();
        try {
            backend.openRepository(into);
            return backend.pushTag("origin", "v1");
        } finally {
            backend.close();
        }
    }

    private static boolean onlyPassword(List<FakeSmartHttpServer.Login> logins, String password) {
        return !logins.isEmpty() && logins.stream().allMatch(l -> l.password().equals(password));
    }

    @Test
    void aCloneAndPushGoInOnTheConnectedAccount() throws Exception {
        server.accept("octocat", "ghp_token");
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));

        GitResult pushed = cloneAndPushTag(server.url("repo.git"), tempDir.resolve("work").toFile());

        assertTrue(pushed.isOk(), pushed.getMessage());
        assertTrue(onlyPassword(server.received(), "ghp_token"), server.received().toString());
    }

    @Test
    void aRefusedTokenIsFollowedByTheUsersOwnHelper() throws Exception {
        server.accept("me", "my-password");
        GitHubSession.connect("ghp_no_push_rights", new GitHubUser("octocat", null));
        CredentialsManager.useHelper(
                GitCredentialHelperTest.helperAnswering(tempDir, "me", "my-password"), false);

        GitResult pushed = cloneAndPushTag(server.url("repo.git"), tempDir.resolve("work").toFile());

        assertTrue(pushed.isOk(), pushed.getMessage());
        List<FakeSmartHttpServer.Login> logins = server.received();
        assertEquals("ghp_no_push_rights", logins.get(0).password(), "the connected account first");
        assertTrue(logins.stream().skip(1).noneMatch(l -> l.password().equals("ghp_no_push_rights")),
                "and not again once refused: " + logins);
        assertTrue(ProviderSessions.isRejectedForGit(HostingProvider.GITHUB));
        String helperLog = Files.readString(tempDir.resolve("actions.log"));
        assertTrue(helperLog.contains("store"), "the user's helper is told its login worked: " + helperLog);
        assertFalse(helperLog.contains("erase"), "the token's refusal is not the helper's business: " + helperLog);
    }

    @Test
    void aServerThatIsNotTheConnectedOneNeverSeesTheToken() throws Exception {
        // Configured as localhost; reached as 127.0.0.1 - another origin as far as a
        // credential is concerned, however it resolves.
        ProviderHosts.setGithub("http://localhost:" + server.port());
        server.accept("me", "my-password");
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        CredentialsManager.useHelper(
                GitCredentialHelperTest.helperAnswering(tempDir, "me", "my-password"), false);

        GitResult pushed = cloneAndPushTag(server.url("repo.git"), tempDir.resolve("work").toFile());

        assertTrue(pushed.isOk(), pushed.getMessage());
        assertTrue(onlyPassword(server.received(), "my-password"), server.received().toString());
    }

    @Test
    void theHelperArgumentsSurviveASpaceInThePath() {
        List<String> args = AskpassBridge.credentialHelperArgs();
        assertEquals(List.of("-c", "credential.helper="), args.subList(0, 2), "the user's list is reset first");
        String helper = args.get(3);
        assertTrue(helper.startsWith("credential.helper=!'") && helper.endsWith("' --credential"), helper);
        assertEquals("'a b/askpass.sh'", AskpassBridge.shellQuote("a b/askpass.sh"));
    }
}
