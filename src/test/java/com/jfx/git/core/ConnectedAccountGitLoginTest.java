package com.jfx.git.core;

import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.bitbucket.BitbucketUser;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.gitlab.GitLabUser;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.core.hosting.ProviderGitCredentials;
import com.jfx.git.core.hosting.ProviderSessions;

import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account connected in Integrations answers git's HTTPS logins for its own server - and
 * only there, only until git refuses it, and never into the user's credential store.
 *
 * <p>Through the JGit engine's provider ({@link CredentialsManager#get}); no toolkit, so a
 * prompt can never be what answered (see {@link CredentialsManagerTest}).
 */
class ConnectedAccountGitLoginTest {

    @TempDir
    Path tempDir;

    private final CredentialsManager manager = CredentialsManager.getInstance();

    @BeforeEach
    void isolate() {
        Settings.useFile(tempDir.resolve("settings.properties"));
        CredentialsManager.useHelper(null, true);
    }

    @AfterEach
    void reset() {
        for (HostingProvider p : HostingProvider.values()) ProviderSessions.disconnect(p);
        manager.clear();
        CredentialsManager.useHelper(null, false);
        Settings.useFile(null);
    }

    private static String[] login(CredentialsManager manager, String url) throws Exception {
        CredentialItem.Username user = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        boolean[] answered = new boolean[1];
        Exception[] failure = new Exception[1];
        // Silent: nothing may prompt, so only the token, the cache or a helper can answer.
        CredentialsManager.runSilently(() -> {
            try {
                answered[0] = manager.get(new URIish(url), user, password);
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        return answered[0] ? new String[]{user.getValue(), new String(password.getValue())} : null;
    }

    @Test
    void theConnectedAccountAnswersForItsServer() throws Exception {
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        String[] answer = login(manager, "https://github.com/octocat/hello.git");
        assertEquals("octocat", answer[0]);
        assertEquals("ghp_token", answer[1]);
    }

    @Test
    void itComesBeforeALoginCachedThisSession() throws Exception {
        seedCache(new URIish("https://github.com/x/y.git"), "someone-else", "old-password");
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        assertEquals("ghp_token", login(manager, "https://github.com/x/y.git")[1]);
    }

    @Test
    void itIsNeverOfferedToAnotherServer() throws Exception {
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        assertEquals(null, login(manager, "http://github.com/x/y.git"), "plain http");
        assertEquals(null, login(manager, "https://github.com:8443/x/y.git"), "another port");
        assertEquals(null, login(manager, "https://github.com.evil.invalid/x/y.git"), "a lookalike host");
        assertEquals(null, login(manager, "https://gist.github.com/x.git"), "nothing by suffix");
        assertEquals("ghp_token", login(manager, "https://GitHub.com:443/x/y.git")[1],
                "the same server, written differently");
    }

    @Test
    void aRemoteThatNamesAnotherUserKeepsThatUsersLogin() throws Exception {
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        assertEquals(null, login(manager, "https://alice@github.com/x/y.git"));
        assertEquals("ghp_token", login(manager, "https://octocat@github.com/x/y.git")[1],
                "naming the connected account itself is fine");
    }

    @Test
    void eachProviderSendsTheUsernameItsServersExpect() throws Exception {
        Settings.put("integration.gitlab.baseUrl", "https://gitlab.example.invalid");
        GitLabSession.connect(ProviderCredential.oauth("glo_access", "refresh",
                Instant.now().plusSeconds(3600), "client", "api"), new GitLabUser("tanuki", null));
        assertEquals("oauth2", login(manager, "https://gitlab.example.invalid/g/p.git")[0],
                "GitLab's OAuth tokens go with oauth2");

        BitbucketSession.connect("bb_token", new BitbucketUser("bbuser", "Bit Bucket"));
        assertEquals("x-token-auth", login(manager, "https://bitbucket.org/w/r.git")[0]);
    }

    @Test
    void onceRefusedItStepsAsideForTheRestOfTheSession() throws Exception {
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        URIish uri = new URIish("https://github.com/x/y.git");
        assertEquals("ghp_token", login(manager, uri.toString())[1]);

        manager.reset(uri);
        assertTrue(ProviderSessions.isRejectedForGit(HostingProvider.GITHUB));
        seedCache(uri, "me", "my-password");
        assertEquals("my-password", login(manager, uri.toString())[1],
                "JGit's retry is answered by the user's own login");
        manager.reset(uri);
        assertEquals(null, login(manager, uri.toString()), "and the token does not come back round");

        GitHubSession.connect("ghp_new", new GitHubUser("octocat", null));
        assertEquals("ghp_new", login(manager, uri.toString())[1], "connecting again clears the refusal");
    }

    @Test
    void aRefusalOfAReplacedTokenDoesNotCountAgainstTheNewOne() {
        ProviderCredential old = ProviderCredential.token("old");
        GitHubSession.connect(old, new GitHubUser("octocat", null));
        GitHubSession.connect("new", new GitHubUser("octocat", null));
        ProviderSessions.markRejectedForGit(HostingProvider.GITHUB, old);
        assertFalse(ProviderSessions.isRejectedForGit(HostingProvider.GITHUB));
    }

    @Test
    void turningItOffLeavesGitToTheUsersOwnCredentials() throws Exception {
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        ProviderGitCredentials.setUseForGit(HostingProvider.GITHUB, false);
        assertEquals(null, login(manager, "https://github.com/x/y.git"));
    }

    @Test
    void sshRemotesAreNotItsBusiness() throws Exception {
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        assertEquals(null, login(manager, "ssh://git@github.com/x/y.git"));
    }

    /** The app owns the token: storing it in Git Credential Manager would outlive a disconnect. */
    @Test
    void itIsNeverHandedToTheUsersCredentialHelper() throws Exception {
        Assumptions.assumeTrue(NativeGitRunner.isGitInstalled());
        CredentialsManager.useHelper(GitCredentialHelperTest.helperAnswering(tempDir, "helper-user", "helper-pass"), false);
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));

        assertEquals("ghp_token", login(manager, "https://github.com/x/y.git")[1], "ahead of the helper's answer");
        manager.networkOperationFinished(true);
        manager.reset(new URIish("https://github.com/x/y.git"));

        Path log = tempDir.resolve("actions.log");
        String actions = Files.exists(log) ? Files.readString(log) : "";
        assertFalse(actions.contains("get"), "the helper was not even asked: " + actions);
        assertFalse(actions.contains("store"), actions);
        assertFalse(actions.contains("erase"), "a refused token is not the helper's to forget: " + actions);

        assertEquals("helper-pass", login(manager, "https://github.com/x/y.git")[1],
                "after the refusal, the helper answers");
    }

    /** A server forbidding what the connected account was used for says why, and what to do. */
    @Test
    void aForbiddenPushAfterTheAccountWasUsedSaysWhatToDo() throws Exception {
        String forbidden = "remote: Permission to octo/repo.git denied to octocat.\n"
                + "fatal: unable to access 'https://github.com/octo/repo.git/': The requested URL returned error: 403";
        String unrelated = "fatal: repository 'https://github.com/octo/missing.git/' not found";
        GitHubSession.connect("ghp_token", new GitHubUser("octocat", null));
        login(manager, "https://github.com/octo/repo.git");

        assertTrue(manager.withAccountHint(forbidden).contains("turn off \"Use for git\""),
                manager.withAccountHint(forbidden));
        assertEquals(unrelated, manager.withAccountHint(unrelated), "only a refusal of permission gets it");
    }

    private void seedCache(URIish uri, String user, String pass) throws ReflectiveOperationException {
        Field field = CredentialsManager.class.getDeclaredField("cache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String[]> cache = (Map<String, String[]>) field.get(manager);
        synchronized (cache) {
            cache.put(CredentialsManager.loginCacheKey(uri), new String[]{user, pass});
        }
    }
}
