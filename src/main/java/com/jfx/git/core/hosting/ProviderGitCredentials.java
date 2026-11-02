package com.jfx.git.core.hosting;

import com.jfx.git.core.Settings;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers git's HTTPS logins with the account connected to the server being asked.
 *
 * <h2>Why</h2>
 * Connecting GitHub in Integrations used to cover pull requests and nothing else: a push to
 * the same github.com then asked for a password, or was answered by whatever Git Credential
 * Manager last stored - perhaps another account entirely, so the pull request and the push
 * came from different people. The account the user deliberately connected is the one they
 * expect git to use on that server.
 *
 * <h2>When it is not offered</h2>
 * The token is a real secret, so the rules are narrow, and every one of them is a reason to
 * stay out of the way rather than a guess:
 * <ul>
 *   <li>Only to the connected server itself - same scheme, host and port as its configured
 *       web address. Nothing by suffix, nothing to another host a redirect leads to (both
 *       engines ask for the URL they are actually talking to), and over plain http only
 *       when the configured server is itself plain http on this machine (a test server;
 *       {@link com.jfx.git.core.ProviderHosts#problemWith} refuses anything else).</li>
 *   <li>Not when the remote names another user - {@code https://alice@github.com/...} is
 *       the user choosing an identity for that remote.</li>
 *   <li>Not once git has refused it this session ({@link ProviderSessions#markRejectedForGit}),
 *       so a token without push rights falls back to the user's own credentials instead of
 *       failing every time.</li>
 *   <li>Not when the user turned it off for that provider ({@link #setUseForGit}).</li>
 * </ul>
 * SSH remotes are untouched: they never ask for a username and password.
 */
public final class ProviderGitCredentials {

    /**
     * A login for git.
     *
     * @param credential the session credential it came from, to report a refusal against
     */
    public record GitLogin(HostingProvider provider, ProviderCredential credential, String username,
                           String password) {
        /** Names the provider and user; never the password. */
        @Override
        public String toString() {
            return "GitLogin[" + provider.id() + ", " + username + "]";
        }
    }

    private ProviderGitCredentials() {
    }

    /**
     * The connected account's login for {@code scheme://host:port}, if one applies.
     *
     * @param port    the URL's port, or -1/0 when it gives none
     * @param urlUser the username written in the remote URL, or null
     */
    public static Optional<GitLogin> lookup(String scheme, String host, int port, String urlUser) {
        if (scheme == null || host == null) return Optional.empty();
        for (HostingProvider provider : HostingProvider.values()) {
            if (!ProviderSessions.isConnected(provider) || !matches(provider.web(), scheme, host, port)) continue;
            if (ProviderSessions.isRejectedForGit(provider) || !useForGit(provider)) return Optional.empty();
            // Fresh: an OAuth token about to expire is exchanged first, on this worker
            // thread, rather than handed to git to be refused mid-push.
            ProviderCredential credential = ProviderSessions.freshCredential(provider);
            if (credential == null) return Optional.empty();
            String login = ProviderSessions.login(provider);
            String username = gitUsername(provider, credential, login);
            if (urlUser != null && !urlUser.isBlank()
                    && !urlUser.equalsIgnoreCase(username) && !urlUser.equalsIgnoreCase(login)) {
                return Optional.empty();
            }
            return Optional.of(new GitLogin(provider, credential, username, credential.accessToken()));
        }
        return Optional.empty();
    }

    /**
     * The username git sends with the token. Each provider documents its own: GitHub and a
     * GitLab or Gitea personal token accept the account's login; an OAuth token on GitLab
     * or Gitea goes with {@code oauth2}; Bitbucket's tokens with {@code x-token-auth}.
     */
    static String gitUsername(HostingProvider provider, ProviderCredential credential, String login) {
        boolean oauth = credential.isOAuth();
        String fallback = switch (provider) {
            case GITHUB -> "x-access-token";
            case BITBUCKET -> "x-token-auth";
            default -> "oauth2";
        };
        if (provider == HostingProvider.BITBUCKET) return fallback;
        if (oauth && provider != HostingProvider.GITHUB) return fallback;
        return login == null || login.isBlank() ? fallback : login;
    }

    /** Whether the server is {@code web}: same scheme, host and port, http only where {@code web} is. */
    static boolean matches(URI web, String scheme, String host, int port) {
        if (web == null || web.getHost() == null || web.getScheme() == null) return false;
        String webScheme = web.getScheme().toLowerCase(Locale.ROOT);
        if (!webScheme.equals(scheme.toLowerCase(Locale.ROOT))) return false;
        if (!webScheme.equals("https") && !webScheme.equals("http")) return false;
        if (!web.getHost().equalsIgnoreCase(host)) return false;
        return effectivePort(webScheme, web.getPort()) == effectivePort(webScheme, port);
    }

    private static int effectivePort(String scheme, int port) {
        if (port > 0) return port;
        return scheme.equals("https") ? 443 : 80;
    }

    private static String useForGitKey(HostingProvider provider) {
        return "integration." + provider.id() + ".useForGit";
    }

    /** Whether the connected account answers git's logins for its server; on unless turned off. */
    public static boolean useForGit(HostingProvider provider) {
        return Settings.getBoolean(useForGitKey(provider), true);
    }

    public static void setUseForGit(HostingProvider provider, boolean use) {
        Settings.putBoolean(useForGitKey(provider), use);
    }
}
