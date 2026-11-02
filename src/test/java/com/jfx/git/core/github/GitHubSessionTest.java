package com.jfx.git.core.github;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GitHubSession} is process-wide static state, like {@link
 * com.jfx.git.core.AppPreferences} - unlike that class, nothing here touches {@link
 * com.jfx.git.core.Settings}, so there is no temp-file isolation to set up, only the
 * connection to clear so one test cannot see another's.
 */
class GitHubSessionTest {

    @AfterEach
    void disconnect() {
        GitHubSession.disconnect();
    }

    @Test
    void notConnectedByDefault() {
        assertFalse(GitHubSession.isConnected());
        assertNull(GitHubSession.token());
        assertNull(GitHubSession.user());
    }

    @Test
    void connectingRecordsTheTokenAndUser() {
        var user = new GitHubUser("octocat", "https://example.com/a.png");
        GitHubSession.connect("secret-token", user);

        assertTrue(GitHubSession.isConnected());
        assertEquals("secret-token", GitHubSession.token());
        assertEquals(user, GitHubSession.user());
    }

    @Test
    void disconnectingForgetsBoth() {
        GitHubSession.connect("secret-token", new GitHubUser("octocat", null));

        GitHubSession.disconnect();

        assertFalse(GitHubSession.isConnected());
        assertNull(GitHubSession.token());
        assertNull(GitHubSession.user());
    }

    @Test
    void reconnectingReplacesThePreviousTokenAndUser() {
        GitHubSession.connect("first-token", new GitHubUser("first-user", null));
        GitHubSession.connect("second-token", new GitHubUser("second-user", null));

        assertEquals("second-token", GitHubSession.token());
        assertEquals("second-user", GitHubSession.user().login());
    }
}
