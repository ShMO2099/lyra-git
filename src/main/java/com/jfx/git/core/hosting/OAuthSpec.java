package com.jfx.git.core.hosting;

import java.net.URI;
import java.util.Objects;

/**
 * Everything one provider's OAuth sign-in needs to know, in one place.
 *
 * <p>The flows ({@link OAuthDeviceFlow}, {@link OAuthLoopbackFlow}) and the token client
 * know the protocol; this says where it is spoken and with which application. Keeping the
 * two apart is what lets a self-hosted GitLab, a GitHub Enterprise server or a Gitea
 * instance reuse the same flow code with nothing but a different spec - and lets the tests
 * point a real flow at a local fake server.
 *
 * <p>Built by {@link OAuthClients}, which is where the per-provider facts live.
 *
 * @param flow         how the user approves the sign-in
 * @param deviceUri    where a device code is requested; null for {@link Flow#LOOPBACK}
 * @param authorizeUri the page the browser opens; null for {@link Flow#DEVICE}
 * @param tokenUri     where codes and refresh tokens are exchanged for access tokens
 * @param revokeUri    where a token is revoked on disconnect; null when the provider offers
 *                     no revocation endpoint, and disconnecting only forgets the token
 * @param scopes       space-separated scopes to request; blank to request none (Bitbucket
 *                     takes its scopes from the consumer's registration)
 * @param clientId     the registered application's public identifier
 * @param clientSecret the application's secret, for the one provider that cannot do without
 *                     it (Bitbucket); null for a public client. It ships in the application,
 *                     so it is not a secret in any strong sense - but it is still kept out of
 *                     {@link #toString()} and every message
 * @param fixedPort    the loopback port the registered redirect URI names; 0 when the
 *                     provider accepts any port on {@code 127.0.0.1}
 * @param pkce         whether to send a PKCE challenge with the authorization request
 */
public record OAuthSpec(Flow flow, URI deviceUri, URI authorizeUri, URI tokenUri, URI revokeUri,
                        String scopes, String clientId, String clientSecret, int fixedPort, boolean pkce) {

    /** How the user approves a sign-in. */
    public enum Flow {
        /**
         * The user types a short code into the provider's own page (RFC 8628). Nothing
         * listens on this machine, so it works behind any firewall and needs no registered
         * redirect - the reason GitHub and GitLab use it.
         */
        DEVICE,
        /**
         * The browser is sent to the provider's authorize page and redirected back to a
         * one-shot server on {@code 127.0.0.1} (RFC 8252). For providers without a device
         * grant.
         */
        LOOPBACK
    }

    /** The path the loopback server answers on; part of every registered redirect URI. */
    public static final String CALLBACK_PATH = "/callback";

    public OAuthSpec {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(tokenUri, "tokenUri");
        Objects.requireNonNull(clientId, "clientId");
        if (flow == Flow.DEVICE) Objects.requireNonNull(deviceUri, "deviceUri");
        if (flow == Flow.LOOPBACK) Objects.requireNonNull(authorizeUri, "authorizeUri");
        if (fixedPort < 0 || fixedPort > 65535) throw new IllegalArgumentException("port " + fixedPort);
        scopes = scopes == null ? "" : scopes.trim();
        if (clientSecret != null && clientSecret.isBlank()) clientSecret = null;
    }

    /** Whether the client authenticates itself with a secret (HTTP Basic) rather than by id alone. */
    public boolean confidential() {
        return clientSecret != null;
    }

    /**
     * The redirect URI for a loopback server listening on {@code port}.
     *
     * <p>{@code 127.0.0.1} rather than {@code localhost}: RFC 8252 section 8.3 recommends
     * the literal address, because {@code localhost} may resolve to {@code ::1} first, or
     * be remapped, and the server here binds the IPv4 loopback only.
     */
    public static String redirectUri(int port) {
        return "http://127.0.0.1:" + port + CALLBACK_PATH;
    }

    /** Never shows the client secret, which ends up in logs like everything else. */
    @Override
    public String toString() {
        return "OAuthSpec[" + flow + ", token " + tokenUri + ", client " + clientId
                + (clientSecret != null ? ", confidential" : "")
                + (fixedPort != 0 ? ", port " + fixedPort : "")
                + (pkce ? ", pkce" : "") + "]";
    }
}
