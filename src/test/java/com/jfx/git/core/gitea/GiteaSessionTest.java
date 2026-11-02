package com.jfx.git.core.gitea;

import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.core.hosting.ProviderSessions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GiteaSession} is a typed view of {@link ProviderSessions}; these check it reads
 * and writes the Gitea entry, and only that one.
 */
class GiteaSessionTest {

    @AfterEach
    void disconnect() {
        GiteaSession.disconnect();
    }

    @Test
    void notConnectedByDefault() {
        assertFalse(GiteaSession.isConnected());
        assertNull(GiteaSession.token());
        assertNull(GiteaSession.credential());
        assertNull(GiteaSession.user());
    }

    @Test
    void connectingRecordsTheTokenUserAndLogin() {
        var user = new GiteaUser("forgejo-fan", "https://codeberg.org/avatars/a");
        GiteaSession.connect("secret-token", user);

        assertTrue(GiteaSession.isConnected());
        assertEquals("secret-token", GiteaSession.token());
        assertEquals(ProviderCredential.Kind.TOKEN, GiteaSession.credential().kind());
        assertEquals(user, GiteaSession.user());
        assertEquals("forgejo-fan", ProviderSessions.login(HostingProvider.GITEA),
                "git logins use the connected account's name");
        assertFalse(ProviderSessions.isConnected(HostingProvider.GITHUB), "only Gitea's entry is written");
    }

    @Test
    void anOAuthGrantIsKeptWhole() {
        var grant = ProviderCredential.oauth("access", "refresh", null, "client", "write:repository");
        GiteaSession.connect(grant, new GiteaUser("u", null));

        assertEquals(grant, GiteaSession.credential());
        assertEquals("access", GiteaSession.token());
    }

    @Test
    void disconnectingForgetsEverything() {
        GiteaSession.connect("secret-token", new GiteaUser("u", null));

        GiteaSession.disconnect();

        assertFalse(GiteaSession.isConnected());
        assertNull(GiteaSession.token());
        assertNull(GiteaSession.user());
        assertNull(ProviderSessions.login(HostingProvider.GITEA));
    }

    @Test
    void reconnectingReplacesThePreviousTokenAndUser() {
        GiteaSession.connect("first-token", new GiteaUser("first-user", null));
        GiteaSession.connect("second-token", new GiteaUser("second-user", null));

        assertEquals("second-token", GiteaSession.token());
        assertEquals("second-user", GiteaSession.user().login());
    }
}
