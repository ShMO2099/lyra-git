package com.jfx.git.core.hosting;

import com.jfx.git.core.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class OAuthClientsTest {

    private static final URI GITHUB = URI.create("https://github.com");
    private static final URI GITLAB = URI.create("https://gitlab.com");
    private static final URI BITBUCKET = URI.create("https://bitbucket.org");

    @TempDir
    Path tempDir;

    @BeforeEach
    void useATempSettingsFile() {
        Settings.useFile(tempDir.resolve("settings.properties"));
    }

    @AfterEach
    void restoreDefault() {
        Settings.useFile(null);
    }

    private static OAuthClients registered(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) p.setProperty(keyValues[i], keyValues[i + 1]);
        return new OAuthClients(p);
    }

    /**
     * Until the maintainer registers the applications, every provider offers tokens only -
     * and the shipped file is read at all, from where the class looks for it.
     */
    @Test
    void theShippedBlankRegistrationsOfferNoSignIn() {
        for (HostingProvider p : HostingProvider.values()) {
            assertEquals(Optional.empty(), OAuthClients.shipped().spec(p), p.id());
        }
        assertNotNull(OAuthClients.class.getResourceAsStream(OAuthClients.RESOURCE), "resource present");
        assertEquals(Optional.empty(), registered("github.clientId", "  ").spec(HostingProvider.GITHUB, GITHUB));
    }

    @Test
    void gitHubUsesTheDeviceFlowWithoutASecret() {
        OAuthSpec spec = registered("github.clientId", "gh-id").spec(HostingProvider.GITHUB, GITHUB).orElseThrow();

        assertEquals(OAuthSpec.Flow.DEVICE, spec.flow());
        assertEquals(URI.create("https://github.com/login/device/code"), spec.deviceUri());
        assertEquals(URI.create("https://github.com/login/oauth/access_token"), spec.tokenUri());
        assertEquals("repo read:org workflow", spec.scopes());
        assertEquals("gh-id", spec.clientId());
        assertNull(spec.clientSecret());
        assertNull(spec.revokeUri());
    }

    /**
     * The secret comes from the uncommitted overlay, laid over the committed file - and a
     * blank value there does not erase a committed one.
     */
    @Test
    void theUncommittedSecretsFileIsLaidOverTheRegistrations() throws Exception {
        java.util.Properties p = OAuthClients.layered(
                stream("bitbucket.clientId=bb-key\nbitbucket.clientSecret=\ngithub.clientId=gh-id\n"),
                stream("bitbucket.clientSecret= bb-secret \ngithub.clientId=\n"));
        assertEquals("bb-secret", p.getProperty("bitbucket.clientSecret"));
        assertEquals("gh-id", p.getProperty("github.clientId"));

        OAuthSpec spec = new OAuthClients(p).spec(HostingProvider.BITBUCKET, BITBUCKET).orElseThrow();
        assertEquals("bb-secret", spec.clientSecret());
        assertEquals("bb-key", spec.clientId());
        assertEquals("bb-key", OAuthClients.layered(stream("bitbucket.clientId=bb-key\n"), null)
                .getProperty("bitbucket.clientId"), "no secrets file at all is fine");
    }

    private static java.io.InputStream stream(String text) {
        return new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Codeberg's shipped app, and a public PKCE client on any port, asking for no scopes. */
    @Test
    void giteaSignsInThroughTheBrowserWithPkceOnAnyPort() {
        OAuthClients clients = registered("gitea.codeberg.org.clientId", "cb-id");
        OAuthSpec spec = clients.spec(HostingProvider.GITEA, URI.create("https://codeberg.org")).orElseThrow();

        assertEquals(OAuthSpec.Flow.LOOPBACK, spec.flow());
        assertEquals(URI.create("https://codeberg.org/login/oauth/authorize"), spec.authorizeUri());
        assertEquals(URI.create("https://codeberg.org/login/oauth/access_token"), spec.tokenUri());
        assertTrue(spec.pkce());
        assertEquals(0, spec.fixedPort());
        assertNull(spec.clientSecret());
        assertEquals("", spec.scopes());
        assertEquals(Optional.empty(), clients.spec(HostingProvider.GITEA, URI.create("https://gitea.corp")),
                "Codeberg's registration is Codeberg's");
    }

    /** github.com's registration does not exist on an Enterprise server. */
    @Test
    void aShippedIdIsOnlyUsedForTheServerItWasRegisteredOn() {
        OAuthClients clients = registered("github.clientId", "gh-id", "gitlab.clientId", "gl-id");

        assertEquals(Optional.empty(), clients.spec(HostingProvider.GITHUB, URI.create("https://ghe.corp")));
        assertEquals(Optional.empty(), clients.spec(HostingProvider.GITLAB, URI.create("https://gitlab.corp")));
    }

    @Test
    void theUsersOwnClientIdWinsAndServesTheirServer() {
        Settings.put(OAuthClients.clientIdKey(HostingProvider.GITHUB), " ghe-app ");
        OAuthClients clients = registered("github.clientId", "gh-id");

        OAuthSpec spec = clients.spec(HostingProvider.GITHUB, URI.create("https://ghe.corp/")).orElseThrow();
        assertEquals("ghe-app", spec.clientId());
        assertEquals(URI.create("https://ghe.corp/login/device/code"), spec.deviceUri());
        assertEquals("ghe-app", clients.spec(HostingProvider.GITHUB, GITHUB).orElseThrow().clientId());
        assertEquals("integration.github.oauthClientId", OAuthClients.clientIdKey(HostingProvider.GITHUB));
    }

    @Test
    void gitLabPrefersTheDeviceGrantAndOffersLoopbackForOlderServers() {
        OAuthClients clients = registered("gitlab.clientId", "gl-id");

        OAuthSpec device = clients.spec(HostingProvider.GITLAB, GITLAB).orElseThrow();
        assertEquals(OAuthSpec.Flow.DEVICE, device.flow());
        assertEquals(URI.create("https://gitlab.com/oauth/authorize_device"), device.deviceUri());
        assertEquals(URI.create("https://gitlab.com/oauth/token"), device.tokenUri());
        assertEquals(URI.create("https://gitlab.com/oauth/revoke"), device.revokeUri());
        assertEquals("api read_user write_repository", device.scopes());

        OAuthSpec loopback = clients.spec(HostingProvider.GITLAB, GITLAB, OAuthSpec.Flow.LOOPBACK).orElseThrow();
        assertEquals(URI.create("https://gitlab.com/oauth/authorize"), loopback.authorizeUri());
        assertEquals(47823, loopback.fixedPort());
        assertTrue(loopback.pkce());
        assertNull(loopback.clientSecret());
        assertEquals("http://127.0.0.1:47823/callback", OAuthSpec.redirectUri(loopback.fixedPort()));
    }

    @Test
    void bitbucketNeedsItsSecretAndUsesTheFixedLoopbackPort() {
        assertEquals(Optional.empty(), registered("bitbucket.clientId", "bb-key")
                .spec(HostingProvider.BITBUCKET, BITBUCKET), "no secret, no sign-in");

        OAuthSpec spec = registered("bitbucket.clientId", "bb-key", "bitbucket.clientSecret", "bb-secret")
                .spec(HostingProvider.BITBUCKET, BITBUCKET).orElseThrow();
        assertEquals(OAuthSpec.Flow.LOOPBACK, spec.flow());
        assertEquals(URI.create("https://bitbucket.org/site/oauth2/authorize"), spec.authorizeUri());
        assertEquals(URI.create("https://bitbucket.org/site/oauth2/access_token"), spec.tokenUri());
        assertEquals("bb-secret", spec.clientSecret());
        assertTrue(spec.confidential());
        assertFalse(spec.pkce());
        assertEquals(47823, spec.fixedPort());
        assertEquals("", spec.scopes(), "the consumer's registration decides the scopes");
        assertNull(spec.revokeUri());
        assertFalse(spec.toString().contains("bb-secret"));
    }

    @Test
    void aProviderWithoutAFlowAnswersEmpty() {
        OAuthClients clients = registered("github.clientId", "gh-id", "bitbucket.clientId", "k",
                "bitbucket.clientSecret", "s");

        assertEquals(Optional.empty(), clients.spec(HostingProvider.GITHUB, GITHUB, OAuthSpec.Flow.LOOPBACK));
        assertEquals(Optional.empty(), clients.spec(HostingProvider.BITBUCKET, BITBUCKET, OAuthSpec.Flow.DEVICE));
    }
}
