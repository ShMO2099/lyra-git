package com.jfx.git.core.hosting;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Speaks to a provider's OAuth token endpoint: exchanges an authorization code, refreshes
 * an expiring grant, revokes a token on disconnect.
 *
 * <p>Separate from the flows because a refresh happens hours after the flow that started
 * it is gone, and because the device flow's polling is the same request with a different
 * grant type - one place builds the form, authenticates the client and reads the reply.
 *
 * <h2>Two ways providers say no</h2>
 * RFC 6749 puts an OAuth error in a 400 response. GitHub instead answers 200 with an
 * {@code error} field in the JSON. So the body is checked for {@code error} whatever the
 * status, and only a response without one is judged by its status.
 *
 * <h2>Secrets</h2>
 * Requests carry codes, verifiers, refresh tokens and sometimes a client secret, in the
 * body or the Basic header and never in the URL. None of them appears in an exception
 * message or a log line: the provider's own {@code error_description} is passed through
 * {@link #redact} first, because a misbehaving server echoing a request back is exactly the
 * case nobody would think to check.
 */
public final class OAuthTokenClient {

    private static final Logger LOGGER = Logger.getLogger(OAuthTokenClient.class.getName());

    /** The device grant's {@code grant_type} (RFC 8628 section 3.4). */
    static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    /** Form fields whose values are secrets, and so are scrubbed from any message. */
    private static final List<String> SECRET_FIELDS =
            List.of("code", "code_verifier", "refresh_token", "device_code", "token", "client_secret");

    private final HttpClient http;
    private final Clock clock;
    private final Gson gson = new Gson();

    public OAuthTokenClient() {
        this(Clock.systemUTC());
    }

    /** @param clock what an {@code expires_in} is counted from; a fixed one in tests */
    public OAuthTokenClient(Clock clock) {
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    Clock clock() {
        return clock;
    }

    /**
     * Exchanges an authorization code from a loopback redirect for a grant.
     *
     * @param verifier    the PKCE verifier whose challenge went to the authorize page; null
     *                    when the spec does not use PKCE
     * @param redirectUri exactly the redirect URI the authorize request named - the provider
     *                    compares the two
     */
    public ProviderCredential exchange(OAuthSpec spec, String code, String verifier, String redirectUri)
            throws OAuthException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        if (verifier != null) form.put("code_verifier", verifier);
        return credential(spec, post(spec, spec.tokenUri(), form), null);
    }

    /**
     * Replaces an expiring grant with a fresh one.
     *
     * <p>GitLab rotates refresh tokens - the old one stops working the moment this
     * succeeds - so the result must replace the stored credential, refresh token included.
     * A provider that sends no new refresh token keeps the old one valid, and the old one
     * is carried over.
     *
     * @throws OAuthException with {@link OAuthException#isInvalidGrant()} when the grant is
     *                        gone and the user has to sign in again
     * @throws IllegalArgumentException when {@code credential} has no refresh token; check
     *                        {@link ProviderCredential#needsRefresh} first
     */
    public ProviderCredential refresh(OAuthSpec spec, ProviderCredential credential) throws OAuthException {
        if (credential.refreshToken() == null) {
            throw new IllegalArgumentException("This credential cannot be refreshed");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", credential.refreshToken());
        String clientId = credential.clientId() != null ? credential.clientId() : spec.clientId();
        return credential(spec, post(spec, spec.tokenUri(), form, clientId), credential.refreshToken());
    }

    /**
     * Revokes {@code token} on the provider (RFC 7009), so a disconnected account does not
     * leave a live grant behind. Does nothing when the provider has no revocation endpoint;
     * for those, disconnecting forgets the token and the user is pointed at the provider's
     * own page of authorized applications.
     */
    public void revoke(OAuthSpec spec, String token) throws OAuthException {
        if (spec.revokeUri() == null || token == null || token.isBlank()) return;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", token);
        post(spec, spec.revokeUri(), form);
    }

    /** Polls the token endpoint once for a device code; see {@link OAuthDeviceFlow}. */
    ProviderCredential pollDevice(OAuthSpec spec, String deviceCode) throws OAuthException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", DEVICE_GRANT);
        form.put("device_code", deviceCode);
        return credential(spec, post(spec, spec.tokenUri(), form), null);
    }

    /** {@link #post(OAuthSpec, URI, Map, String)} with the spec's own client id. */
    String post(OAuthSpec spec, URI uri, Map<String, String> form) throws OAuthException {
        return post(spec, uri, form, spec.clientId());
    }

    /**
     * Posts a form and returns the body of a successful reply.
     *
     * <p>A confidential client (Bitbucket) authenticates with HTTP Basic; a public one names
     * itself with {@code client_id} in the form. Not both: RFC 6749 section 2.3 forbids a
     * client using more than one authentication method in a request.
     *
     * @throws OAuthException for an OAuth error in the body (whatever the status), any other
     *                        non-2xx status, or a transport failure
     */
    String post(OAuthSpec spec, URI uri, Map<String, String> form, String clientId) throws OAuthException {
        Map<String, String> body = new LinkedHashMap<>(form);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15));
        if (spec.confidential()) {
            String pair = clientId + ":" + spec.clientSecret();
            request.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        } else {
            body.put("client_id", clientId);
        }
        request.POST(HttpRequest.BodyPublishers.ofString(encode(body)));

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // The exception names the host at most - the secrets are in the body - but it is
            // logged without the stack's message chain all the same.
            LOGGER.log(Level.FINE, "OAuth request to " + uri.getHost() + " failed: " + e.getClass().getSimpleName());
            throw new OAuthException(OAuthException.Reason.TRANSPORT, null, 0,
                    "Could not reach " + uri.getHost() + ": " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException(OAuthException.Reason.CANCELLED, "Sign-in cancelled.");
        }

        int status = response.statusCode();
        ErrorReply error = parse(response.body(), ErrorReply.class);
        if (error != null && error.error() != null && !error.error().isBlank()) {
            throw providerError(error.error(), redact(error.description(), body, spec), status);
        }
        if (status < 200 || status >= 300) {
            // The body is not shown: it is not an OAuth error, so it is likely an HTML page
            // from a proxy or the server's own error template, and could echo anything.
            LOGGER.log(Level.FINE, "OAuth request to " + uri.getHost() + " answered HTTP " + status);
            throw new OAuthException(OAuthException.Reason.HTTP, null, status,
                    uri.getHost() + " answered the sign-in request with HTTP " + status + ".", null);
        }
        return response.body();
    }

    /** Reads a token reply into a credential. */
    private ProviderCredential credential(OAuthSpec spec, String body, String previousRefresh) throws OAuthException {
        TokenReply reply = parse(body, TokenReply.class);
        if (reply == null || reply.accessToken() == null || reply.accessToken().isBlank()) {
            throw new OAuthException(OAuthException.Reason.MALFORMED,
                    "The sign-in server's reply did not contain an access token.");
        }
        Instant expiresAt = reply.expiresIn() == null || reply.expiresIn() <= 0
                ? null
                : clock.instant().plusSeconds(reply.expiresIn());
        String refresh = reply.refreshToken() != null && !reply.refreshToken().isBlank()
                ? reply.refreshToken()
                : previousRefresh;
        String scopes = reply.scope() != null && !reply.scope().isBlank() ? reply.scope() : spec.scopes();
        return ProviderCredential.oauth(reply.accessToken(), refresh, expiresAt, spec.clientId(),
                scopes.isBlank() ? null : scopes);
    }

    /** The exception for an OAuth {@code error} code, with a sentence a user can act on. */
    static OAuthException providerError(String code, String description, int status) {
        OAuthException.Reason reason = switch (code) {
            case "access_denied" -> OAuthException.Reason.DENIED;
            case "expired_token" -> OAuthException.Reason.EXPIRED;
            default -> OAuthException.Reason.PROVIDER;
        };
        String message = switch (code) {
            case "access_denied" -> "The sign-in was declined.";
            case "expired_token" -> "The sign-in code expired before it was approved. Start again.";
            case "invalid_grant" -> "The sign-in is no longer valid. Sign in again.";
            case "invalid_client", "unauthorized_client" ->
                    "The provider did not accept this application's registration (" + code + ").";
            default -> "The provider refused the sign-in (" + code + ").";
        };
        if (description != null && !description.isBlank()) message += " " + description;
        return new OAuthException(reason, code, status, message, null);
    }

    /**
     * {@code description} with every secret value from the request removed, and cut short.
     * Null for a blank description.
     */
    static String redact(String description, Map<String, String> form, OAuthSpec spec) {
        if (description == null || description.isBlank()) return null;
        String text = description.strip();
        List<String> secrets = new ArrayList<>();
        for (String field : SECRET_FIELDS) {
            String value = form.get(field);
            if (value != null && value.length() >= 4) secrets.add(value);
        }
        if (spec.clientSecret() != null) secrets.add(spec.clientSecret());
        for (String secret : secrets) {
            text = text.replace(secret, "[redacted]");
        }
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank() || !body.strip().startsWith("{")) return null;
        try {
            return gson.fromJson(body, type);
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String encode(Map<String, String> form) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() == null) continue;
            if (!out.isEmpty()) out.append('&');
            out.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    /** An OAuth error reply (RFC 6749 section 5.2). */
    private record ErrorReply(String error, @SerializedName("error_description") String description) {
    }

    /** A token reply (RFC 6749 section 5.1). */
    private record TokenReply(@SerializedName("access_token") String accessToken,
                              @SerializedName("refresh_token") String refreshToken,
                              @SerializedName("expires_in") Long expiresIn,
                              String scope) {
    }
}
