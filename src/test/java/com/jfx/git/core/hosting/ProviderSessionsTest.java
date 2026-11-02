package com.jfx.git.core.hosting;

import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.bitbucket.BitbucketUser;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.gitlab.GitLabUser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one registry behind the typed provider sessions. */
class ProviderSessionsTest {

    @AfterEach
    void disconnectAll() {
        for (HostingProvider p : HostingProvider.values()) ProviderSessions.disconnect(p);
    }

    @Test
    void eachProviderIsItsOwnSession() {
        GitHubSession.connect("gh", new GitHubUser("octocat", null));
        assertTrue(ProviderSessions.isConnected(HostingProvider.GITHUB));
        assertFalse(ProviderSessions.isConnected(HostingProvider.GITLAB));
        assertFalse(GitLabSession.isConnected());
        assertNull(GitLabSession.token());
    }

    @Test
    void theTypedSessionsReadTheRegistry() {
        var user = new GitLabUser("tanuki", null);
        var grant = ProviderCredential.oauth("access", "refresh", Instant.parse("2026-10-07T12:00:00Z"), "c", null);
        GitLabSession.connect(grant, user);

        assertEquals("access", GitLabSession.token());
        assertSame(grant, GitLabSession.credential());
        assertEquals(user, GitLabSession.user());
        assertEquals("tanuki", ProviderSessions.login(HostingProvider.GITLAB));
    }

    @Test
    void aUserOfAnotherTypeIsNotHandedBack() {
        ProviderSessions.connect(HostingProvider.GITHUB, ProviderCredential.token("t"), "not a GitHubUser", "x");
        assertNull(GitHubSession.user());
        assertNull(ProviderSessions.user(HostingProvider.GITHUB, Integer.class));
        assertEquals("t", GitHubSession.token(), "still connected");
    }

    @Test
    void theLoginIsTheProvidersOwnHandle() {
        BitbucketSession.connect("bb", new BitbucketUser("bbuser", "Bit Bucket"));
        assertEquals("bbuser", ProviderSessions.login(HostingProvider.BITBUCKET));
        GitHubSession.connect("gh", null);
        assertNull(ProviderSessions.login(HostingProvider.GITHUB), "no user, no login");
    }

    @Test
    void disconnectingForgetsTheCredential() {
        GitHubSession.connect("gh", new GitHubUser("octocat", null));
        GitHubSession.disconnect();
        assertFalse(GitHubSession.isConnected());
        assertNull(ProviderSessions.credential(HostingProvider.GITHUB));
        assertNull(ProviderSessions.login(HostingProvider.GITHUB));
    }

    @Test
    void providersAreFoundByTheirStoredName() {
        assertEquals(HostingProvider.GITLAB, HostingProvider.byId("GitLab").orElseThrow());
        assertTrue(HostingProvider.byId("sourcehut").isEmpty());
        assertTrue(HostingProvider.byId(null).isEmpty());
    }
}
