package com.jfx.git.core.github;

import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.core.hosting.ProviderSessions;

/**
 * Whether this process is connected to GitHub right now, and as whom.
 *
 * <p>Held for the lifetime of the process. The plaintext token never reaches
 * {@link com.jfx.git.core.Settings}, which stores values in clear text - a personal
 * access token is a real secret, not a preference. Every connect and disconnect is
 * announced through {@link com.jfx.git.core.IntegrationEvents}, so open screens follow it.
 *
 * <p>It can outlive the session, but only where the user asked and the platform has
 * somewhere proper to put it: {@code com.jfx.git.ui.settings.IntegrationsPane} offers
 * "Remember this token", {@link com.jfx.git.core.TokenStore} writes it to the keychain
 * (or DPAPI-encrypted on Windows), and {@link com.jfx.git.core.IntegrationSessions}
 * revalidates it at the next launch before calling this connected. Without that tick,
 * the row asks for the token again next launch as it always did.
 */
public final class GitHubSession {

    private static final HostingProvider PROVIDER = HostingProvider.GITHUB;

    private GitHubSession() {
    }

    /** Whether a credential has been supplied and accepted this session. */
    public static boolean isConnected() {
        return ProviderSessions.isConnected(PROVIDER);
    }

    /** The current access token, or {@code null} if not connected. Never logged; persisted only when the user asks - see the class comment. */
    public static String token() {
        return ProviderSessions.accessToken(PROVIDER);
    }

    /** The current credential, pasted token or OAuth grant, or {@code null} if not connected. */
    public static ProviderCredential credential() {
        return ProviderSessions.credential(PROVIDER);
    }

    /** Who the current credential belongs to, or {@code null} if not connected. */
    public static GitHubUser user() {
        return ProviderSessions.user(PROVIDER, GitHubUser.class);
    }

    /** Records a validated pasted token and the user it belongs to. */
    public static void connect(String validatedToken, GitHubUser connectedAs) {
        connect(ProviderCredential.token(validatedToken), connectedAs);
    }

    /** Records a validated credential and the user it belongs to. */
    public static void connect(ProviderCredential validated, GitHubUser connectedAs) {
        ProviderSessions.connect(PROVIDER, validated, connectedAs,
                connectedAs == null ? null : connectedAs.login());
    }

    /** Forgets the credential and the connected user. */
    public static void disconnect() {
        ProviderSessions.disconnect(PROVIDER);
    }
}
