package com.jfx.git.core.bitbucket;

import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.core.hosting.ProviderSessions;

/**
 * Whether this process is connected to Bitbucket right now, and as whom. Mirrors
 * {@link com.jfx.git.core.github.GitHubSession} exactly, including its security
 * posture: in memory for the process's lifetime, never in
 * {@link com.jfx.git.core.Settings} (which stores values in clear text), and outliving
 * the session only through {@link com.jfx.git.core.TokenStore} when the user ticks
 * "Remember this token".
 */
public final class BitbucketSession {

    private static final HostingProvider PROVIDER = HostingProvider.BITBUCKET;

    private BitbucketSession() {
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
    public static BitbucketUser user() {
        return ProviderSessions.user(PROVIDER, BitbucketUser.class);
    }

    /** Records a validated pasted token and the user it belongs to. */
    public static void connect(String validatedToken, BitbucketUser connectedAs) {
        connect(ProviderCredential.token(validatedToken), connectedAs);
    }

    /** Records a validated credential and the user it belongs to. */
    public static void connect(ProviderCredential validated, BitbucketUser connectedAs) {
        ProviderSessions.connect(PROVIDER, validated, connectedAs,
                connectedAs == null ? null : connectedAs.username());
    }

    /** Forgets the credential and the connected user. */
    public static void disconnect() {
        ProviderSessions.disconnect(PROVIDER);
    }
}
