package com.jfx.git.core.gitea;

import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.core.hosting.ProviderSessions;

/**
 * Whether this process is connected to a Gitea or Forgejo server right now, and as whom.
 *
 * <p>A typed view of {@link ProviderSessions}, with the same public API as
 * {@link com.jfx.git.core.github.GitHubSession} so the Gitea dialogs, adapted from the
 * GitHub ones, read it the same way. The plaintext token never reaches
 * {@link com.jfx.git.core.Settings}; it outlives the process only where the user asked,
 * through {@link com.jfx.git.core.TokenStore}, keyed by the server that issued it, and
 * {@link com.jfx.git.core.IntegrationSessions} revalidates it at the next launch.
 */
public final class GiteaSession {

    private static final HostingProvider PROVIDER = HostingProvider.GITEA;

    private GiteaSession() {
    }

    /** Whether a credential has been supplied and accepted this session. */
    public static boolean isConnected() {
        return ProviderSessions.isConnected(PROVIDER);
    }

    /** The current access token, or {@code null} if not connected. Never logged. */
    public static String token() {
        return ProviderSessions.accessToken(PROVIDER);
    }

    /** The current credential, pasted token or OAuth grant, or {@code null} if not connected. */
    public static ProviderCredential credential() {
        return ProviderSessions.credential(PROVIDER);
    }

    /** Who the current credential belongs to, or {@code null} if not connected. */
    public static GiteaUser user() {
        return ProviderSessions.user(PROVIDER, GiteaUser.class);
    }

    /** Records a validated pasted token and the user it belongs to. */
    public static void connect(String validatedToken, GiteaUser connectedAs) {
        connect(ProviderCredential.token(validatedToken), connectedAs);
    }

    /** Records a validated credential and the user it belongs to. */
    public static void connect(ProviderCredential validated, GiteaUser connectedAs) {
        ProviderSessions.connect(PROVIDER, validated, connectedAs,
                connectedAs == null ? null : connectedAs.login());
    }

    /** Forgets the credential and the connected user. */
    public static void disconnect() {
        ProviderSessions.disconnect(PROVIDER);
    }
}
