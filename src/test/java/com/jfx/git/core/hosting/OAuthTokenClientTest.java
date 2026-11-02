package com.jfx.git.core.hosting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OAuthTokenClient} against a local fake token endpoint. Expiry is checked with a
 * fixed clock, so "expires in two hours" is an exact instant rather than a range.
 */
class OAuthTokenClientTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private FakeOAuthServer server;
    private OAuthTokenClient client;

    @BeforeEach
    void start() throws Exception {
        server = new FakeOAuthServer();
        client = new OAuthTokenClient(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private OAuthSpec publicSpec(URI revoke) {
        return new OAuthSpec(OAuthSpec.Flow.LOOPBACK, null, server.uri("/authorize"), server.uri("/token"),
                revoke, "api read_user", "the-client", null, 0, true);
    }

    private OAuthSpec bitbucketSpec() {
        return new OAuthSpec(OAuthSpec.Flow.LOOPBACK, null, server.uri("/authorize"), server.uri("/token"),
                null, "", "bb-key", "bb-secret-value", 47823, false);
    }

    @Test
    void anExchangeSendsTheCodeAndVerifierAndReadsTheExpiry() throws Exception {
        server.on("/token", 200, """
                {"access_token":"at-1","token_type":"Bearer","expires_in":7200,
                 "refresh_token":"rt-1","scope":"api read_user"}""");

        ProviderCredential credential = client.exchange(publicSpec(null), "the-code", "the-verifier",
                "http://127.0.0.1:5000/callback");

        assertTrue(credential.isOAuth());
        assertEquals("at-1", credential.accessToken());
        assertEquals("rt-1", credential.refreshToken());
        assertEquals(NOW.plusSeconds(7200), credential.expiresAt());
        assertEquals("the-client", credential.clientId());
        assertEquals("api read_user", credential.scopes());

        FakeOAuthServer.Request request = server.requestsTo("/token").getFirst();
        assertEquals("POST", request.method());
        assertEquals("application/json", request.accept());
        assertEquals(Map.of("grant_type", "authorization_code", "code", "the-code",
                "code_verifier", "the-verifier", "redirect_uri", "http://127.0.0.1:5000/callback",
                "client_id", "the-client"), request.form());
        assertNull(request.authorization(), "a public client sends no credentials of its own");
    }

    /** GitHub's tokens do not expire unless the app opts in; no expiry means none. */
    @Test
    void aReplyWithoutExpiryGivesACredentialThatNeverNeedsRefreshing() throws Exception {
        server.on("/token", 200, """
                {"access_token":"gho_x","token_type":"bearer","scope":"repo,read:org,workflow"}""");

        ProviderCredential credential = client.exchange(publicSpec(null), "c", null, "http://127.0.0.1:1/callback");

        assertNull(credential.expiresAt());
        assertNull(credential.refreshToken());
        assertFalse(credential.needsRefresh(Clock.fixed(NOW, ZoneOffset.UTC)));
        assertEquals("repo,read:org,workflow", credential.scopes());
        assertFalse(server.requestsTo("/token").getFirst().form().containsKey("code_verifier"));
    }

    /** GitLab rotates refresh tokens: the new one must replace the old. */
    @Test
    void aRefreshReturnsTheRotatedRefreshToken() throws Exception {
        server.on("/token", 200, """
                {"access_token":"at-2","expires_in":7200,"refresh_token":"rt-2"}""");
        ProviderCredential old = ProviderCredential.oauth("at-1", "rt-1", NOW.minusSeconds(10), "the-client", "api");

        ProviderCredential fresh = client.refresh(publicSpec(null), old);

        assertEquals("at-2", fresh.accessToken());
        assertEquals("rt-2", fresh.refreshToken());
        assertEquals(NOW.plusSeconds(7200), fresh.expiresAt());
        assertEquals(Map.of("grant_type", "refresh_token", "refresh_token", "rt-1", "client_id", "the-client"),
                server.requestsTo("/token").getFirst().form());
    }

    @Test
    void aRefreshWithoutANewRefreshTokenKeepsTheOldOne() throws Exception {
        server.on("/token", 200, """
                {"access_token":"at-2","expires_in":3600}""");
        ProviderCredential old = ProviderCredential.oauth("at-1", "rt-1", NOW, "the-client", null);

        assertEquals("rt-1", client.refresh(publicSpec(null), old).refreshToken());
    }

    @Test
    void invalidGrantIsDistinguishableAndLeaksNothing() {
        server.on("/token", 400, """
                {"error":"invalid_grant","error_description":"The refresh token rt-secret-1 is invalid"}""");
        ProviderCredential old = ProviderCredential.oauth("at-1", "rt-secret-1", NOW, "the-client", null);

        OAuthException e = assertThrows(OAuthException.class, () -> client.refresh(publicSpec(null), old));

        assertTrue(e.isInvalidGrant());
        assertEquals(OAuthException.Reason.PROVIDER, e.reason());
        assertEquals(400, e.httpStatus());
        assertFalse(e.getMessage().contains("rt-secret-1"), e.getMessage());
        assertFalse(e.getMessage().contains("at-1"), e.getMessage());
    }

    /** GitHub reports OAuth errors with HTTP 200. */
    @Test
    void anErrorInA200ReplyIsStillAnError() {
        server.on("/token", 200, """
                {"error":"bad_verification_code","error_description":"The code passed is incorrect or expired."}""");

        OAuthException e = assertThrows(OAuthException.class,
                () -> client.exchange(publicSpec(null), "c", null, "http://127.0.0.1:1/callback"));

        assertEquals("bad_verification_code", e.error());
        assertFalse(e.isInvalidGrant());
        assertTrue(e.getMessage().contains("incorrect or expired"), e.getMessage());
    }

    /** Bitbucket's token endpoint wants the consumer key and secret as HTTP Basic. */
    @Test
    void aConfidentialClientAuthenticatesWithBasicAndKeepsTheSecretOutOfMessages() throws Exception {
        server.on("/token", 200, """
                {"access_token":"bb-at","expires_in":7200,"refresh_token":"bb-rt","scopes":"repository"}""");
        server.on("/token", 401, """
                {"error":"invalid_client","error_description":"bad secret bb-secret-value for code bb-code"}""");

        client.exchange(bitbucketSpec(), "bb-code", null, "http://127.0.0.1:47823/callback");

        FakeOAuthServer.Request request = server.requestsTo("/token").getFirst();
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("bb-key:bb-secret-value".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, request.authorization());
        assertFalse(request.form().containsKey("client_id"), "one client authentication method, not two");
        assertFalse(request.form().containsKey("client_secret"));

        OAuthException e = assertThrows(OAuthException.class,
                () -> client.exchange(bitbucketSpec(), "bb-code", null, "http://127.0.0.1:47823/callback"));
        assertEquals("invalid_client", e.error());
        assertFalse(e.getMessage().contains("bb-secret-value"), e.getMessage());
        assertFalse(e.getMessage().contains("bb-code"), e.getMessage());
        assertFalse(bitbucketSpec().toString().contains("bb-secret-value"));
    }

    @Test
    void revokePostsTheClientIdAndToken() throws Exception {
        server.on("/revoke", 200, "{}");

        client.revoke(publicSpec(server.uri("/revoke")), "at-to-revoke");

        assertEquals(Map.of("token", "at-to-revoke", "client_id", "the-client"),
                server.requestsTo("/revoke").getFirst().form());
    }

    @Test
    void revokeIsANoOpWithoutARevocationEndpoint() throws Exception {
        client.revoke(publicSpec(null), "at");

        assertTrue(server.requests.isEmpty());
    }

    /** A proxy's HTML error page is neither shown nor parsed, only its status reported. */
    @Test
    void aNonOAuthHttpErrorReportsTheStatusOnly() {
        server.on("/token", 502, "<html>bad gateway, request was code=leaky</html>");

        OAuthException e = assertThrows(OAuthException.class,
                () -> client.exchange(publicSpec(null), "leaky", null, "http://127.0.0.1:1/callback"));

        assertEquals(OAuthException.Reason.HTTP, e.reason());
        assertEquals(502, e.httpStatus());
        assertTrue(e.isTemporary());
        assertFalse(e.getMessage().contains("leaky"), e.getMessage());
    }

    @Test
    void aReplyWithoutAnAccessTokenIsMalformed() {
        server.on("/token", 200, "{\"token_type\":\"bearer\"}");

        OAuthException e = assertThrows(OAuthException.class,
                () -> client.exchange(publicSpec(null), "c", null, "http://127.0.0.1:1/callback"));

        assertEquals(OAuthException.Reason.MALFORMED, e.reason());
    }

    @Test
    void anUnreachableServerIsATransportFailure() {
        server.close();

        OAuthException e = assertThrows(OAuthException.class,
                () -> client.exchange(publicSpec(null), "c", null, "http://127.0.0.1:1/callback"));

        assertEquals(OAuthException.Reason.TRANSPORT, e.reason());
        assertTrue(e.isTemporary());
    }
}
