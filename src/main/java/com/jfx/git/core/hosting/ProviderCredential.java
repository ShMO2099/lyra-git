package com.jfx.git.core.hosting;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What a connected account authenticates with: a token the user pasted, or an OAuth grant.
 *
 * <p>A pasted token is a string and stays one; an OAuth grant is a short-lived access token
 * plus what is needed to get the next one. Sessions held a bare string, which had no room
 * for the second kind - nowhere to keep the refresh token, nothing to say when the access
 * token runs out.
 *
 * <h2>Stored form</h2>
 * A pasted token is stored exactly as before, as the token itself, so a token remembered by
 * an earlier version still reads back - and one remembered by this version still reads back
 * in an earlier one. An OAuth grant is stored as a small JSON object; {@link #fromStored}
 * tells the two apart by the opening brace, which no provider's token starts with.
 *
 * @param kind         how it was obtained
 * @param accessToken  what requests and git logins carry; never null
 * @param refreshToken how to get the next access token; null for a pasted token, and for an
 *                     OAuth grant that does not expire
 * @param expiresAt    when {@code accessToken} stops working; null when it does not expire
 * @param clientId     the OAuth application that issued it, needed again to refresh; null
 *                     for a pasted token
 * @param scopes       what was granted, as the provider reported it; may be null
 */
public record ProviderCredential(Kind kind, String accessToken, String refreshToken,
                                 Instant expiresAt, String clientId, String scopes) {

    /** How a credential was obtained. */
    public enum Kind {
        /** A personal or project access token the user pasted. */
        TOKEN,
        /** An access token from an OAuth sign-in, possibly with a refresh token. */
        OAUTH
    }

    /** How long before expiry an access token is replaced rather than used. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private static final Gson GSON = new Gson();
    private static final int FORMAT = 1;

    public ProviderCredential {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(accessToken, "accessToken");
    }

    /** A pasted token. */
    public static ProviderCredential token(String token) {
        return new ProviderCredential(Kind.TOKEN, token, null, null, null, null);
    }

    /** An OAuth grant; {@code refreshToken} and {@code expiresAt} may be null. */
    public static ProviderCredential oauth(String accessToken, String refreshToken, Instant expiresAt,
                                           String clientId, String scopes) {
        return new ProviderCredential(Kind.OAUTH, accessToken, refreshToken, expiresAt, clientId, scopes);
    }

    public boolean isOAuth() {
        return kind == Kind.OAUTH;
    }

    /**
     * Whether the access token should be replaced before it is next used: it expires within
     * {@link #REFRESH_MARGIN} and there is a refresh token to replace it with. A grant
     * without one cannot be refreshed, only signed in again.
     */
    public boolean needsRefresh(Clock clock) {
        return refreshToken != null && expiresAt != null
                && !clock.instant().plus(REFRESH_MARGIN).isBefore(expiresAt);
    }

    /** The form {@link com.jfx.git.core.TokenStore} keeps; see the class comment. */
    public String toStored() {
        if (kind == Kind.TOKEN) return accessToken;
        return GSON.toJson(new Stored(FORMAT, kind.name(), accessToken, refreshToken,
                expiresAt == null ? null : expiresAt.getEpochSecond(), clientId, scopes));
    }

    /**
     * Reads what {@link #toStored()} wrote, or a token stored by an earlier version.
     *
     * @return null for a blank value or an object this version cannot read
     */
    public static ProviderCredential fromStored(String stored) {
        if (stored == null || stored.isBlank()) return null;
        String text = stored.trim();
        if (!text.startsWith("{")) return token(text);
        try {
            Stored s = GSON.fromJson(text, Stored.class);
            if (s == null || s.access() == null || s.access().isBlank() || s.kind() == null) return null;
            Kind kind = Kind.valueOf(s.kind());
            Instant expires = s.expiresAt() == null ? null : Instant.ofEpochSecond(s.expiresAt());
            return new ProviderCredential(kind, s.access(), s.refresh(), expires, s.clientId(), s.scopes());
        } catch (JsonParseException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Says what kind it is and when it expires; never the tokens, which end up in logs. */
    @Override
    public String toString() {
        return "ProviderCredential[" + kind
                + (refreshToken != null ? ", refreshable" : "")
                + (expiresAt != null ? ", expires " + expiresAt : "") + "]";
    }

    /** The JSON shape of an OAuth grant. Gson reads private records in this project. */
    private record Stored(int v, String kind, String access, String refresh, Long expiresAt,
                          String clientId, String scopes) {
    }
}
