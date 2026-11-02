package com.jfx.git.core.hosting;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a connected account's credential is remembered, read back and judged stale. */
class ProviderCredentialTest {

    private static Clock at(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    @Test
    void aPastedTokenIsStoredAsItAlwaysWas() {
        // Earlier versions stored the bare token; they must still read what this one writes.
        assertEquals("ghp_abc123", ProviderCredential.token("ghp_abc123").toStored());
    }

    @Test
    void aTokenStoredByAnEarlierVersionReadsBackAsAPastedToken() {
        ProviderCredential read = ProviderCredential.fromStored("  glpat-xyz \n");
        assertEquals(ProviderCredential.Kind.TOKEN, read.kind());
        assertEquals("glpat-xyz", read.accessToken());
        assertNull(read.refreshToken());
    }

    @Test
    void anOAuthGrantRoundTrips() {
        Instant expires = Instant.parse("2026-10-07T12:00:00Z");
        ProviderCredential grant = ProviderCredential.oauth("access", "refresh", expires, "client", "api read_user");
        ProviderCredential read = ProviderCredential.fromStored(grant.toStored());
        assertEquals(grant, read);
        assertTrue(grant.toStored().startsWith("{"), "an object, told apart from a bare token by its brace");
    }

    @Test
    void anUnreadableOrBlankValueReadsAsNothing() {
        assertNull(ProviderCredential.fromStored(null));
        assertNull(ProviderCredential.fromStored("   "));
        assertNull(ProviderCredential.fromStored("{not json"));
        assertNull(ProviderCredential.fromStored("{\"v\":1,\"kind\":\"OAUTH\"}"), "no access token");
        assertNull(ProviderCredential.fromStored("{\"v\":1,\"kind\":\"SOMETHING\",\"access\":\"a\"}"));
    }

    @Test
    void refreshIsDueFiveMinutesBeforeExpiry() {
        Instant expires = Instant.parse("2026-10-07T12:00:00Z");
        ProviderCredential grant = ProviderCredential.oauth("a", "r", expires, "c", null);
        assertFalse(grant.needsRefresh(at(expires.minusSeconds(301))));
        assertTrue(grant.needsRefresh(at(expires.minusSeconds(300))));
        assertTrue(grant.needsRefresh(at(expires.plusSeconds(60))), "and after it, of course");
    }

    @Test
    void onlyAGrantWithARefreshTokenAndAnExpiryIsEverRefreshed() {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        Clock now = at(Instant.parse("2026-10-07T12:00:00Z"));
        assertFalse(ProviderCredential.token("t").needsRefresh(now));
        assertFalse(ProviderCredential.oauth("a", null, past, "c", null).needsRefresh(now), "nothing to refresh with");
        assertFalse(ProviderCredential.oauth("a", "r", null, "c", null).needsRefresh(now), "does not expire");
    }

    @Test
    void toStringNeverShowsTheTokens() {
        String text = ProviderCredential.oauth("secret-access", "secret-refresh",
                Instant.parse("2026-10-07T12:00:00Z"), "client", null).toString();
        assertFalse(text.contains("secret"), text);
        assertTrue(text.contains("OAUTH"), text);
    }
}
