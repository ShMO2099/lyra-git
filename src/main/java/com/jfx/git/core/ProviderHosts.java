package com.jfx.git.core;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which server each provider integration talks to.
 *
 * <p>The clients always took a base URI - it is how their tests point them at a local
 * server - but the no-argument constructor hardcoded the public host, so the only GitHub
 * this application could reach was github.com. That leaves out everyone whose work is on
 * a GitHub Enterprise or self-managed GitLab install, which for GitLab in particular is
 * most of its corporate users.
 *
 * <h2>What is supported, and what is not</h2>
 * <dl>
 *   <dt>GitHub Enterprise Server</dt>
 *   <dd>Speaks the same REST API as github.com under {@code /api/v3}. Supported.</dd>
 *   <dt>Self-managed GitLab</dt>
 *   <dd>Speaks the same REST API as gitlab.com under {@code /api/v4}. Supported.</dd>
 *   <dt>Gitea and Forgejo</dt>
 *   <dd>Self-hosted by nature, with the same {@code /api/v1} on every install. The default
 *       is Codeberg, the largest public server; any other is configured the same way as
 *       a GitHub Enterprise host.</dd>
 *   <dt>Bitbucket Data Center</dt>
 *   <dd><em>Not</em> supported, and deliberately not offered. Its REST API is a different
 *       one from Bitbucket Cloud's - different paths, different pagination, different
 *       payloads - so a base-URL field would only produce confusing failures. Making it
 *       work means teaching {@link com.jfx.git.core.bitbucket.BitbucketClient} a second
 *       API, not changing a URL.</dd>
 * </dl>
 */
public final class ProviderHosts {

    private static final Logger LOGGER = Logger.getLogger(ProviderHosts.class.getName());

    private static final String GITHUB_KEY = "integration.github.baseUrl";
    private static final String GITLAB_KEY = "integration.gitlab.baseUrl";
    private static final String GITEA_KEY = "integration.gitea.baseUrl";

    public static final URI GITHUB_PUBLIC = URI.create("https://api.github.com");
    public static final URI GITLAB_PUBLIC = URI.create("https://gitlab.com/api/v4");
    /**
     * Gitea has no single public server the way GitHub and GitLab do; Codeberg, which runs
     * Forgejo, is the largest public one, so it is what an unconfigured integration means.
     */
    public static final URI GITEA_DEFAULT = URI.create("https://codeberg.org/api/v1");
    /** Bitbucket Cloud has one server; see the class comment for Data Center. */
    public static final URI BITBUCKET_WEB = URI.create("https://bitbucket.org");
    public static final URI BITBUCKET_API = URI.create("https://api.bitbucket.org/2.0");

    private ProviderHosts() {
    }

    /** Where the GitHub client should talk, public unless an Enterprise host is configured. */
    public static URI github() {
        return configured(GITHUB_KEY, GITHUB_PUBLIC, "/api/v3");
    }

    /** Where the GitLab client should talk, public unless a self-managed host is configured. */
    public static URI gitlab() {
        return configured(GITLAB_KEY, GITLAB_PUBLIC, "/api/v4");
    }

    /**
     * Where the Gitea client should talk: Codeberg unless another Gitea or Forgejo server
     * is configured. Both serve their API under {@code /api/v1}.
     */
    public static URI gitea() {
        return configured(GITEA_KEY, GITEA_DEFAULT, "/api/v1");
    }

    /** The stored GitHub host as the user typed it, or {@code ""} for the public one. */
    public static String githubSetting() {
        return Settings.get(GITHUB_KEY, "");
    }

    public static String gitlabSetting() {
        return Settings.get(GITLAB_KEY, "");
    }

    /** The stored Gitea server as the user typed it, or {@code ""} for Codeberg. */
    public static String giteaSetting() {
        return Settings.get(GITEA_KEY, "");
    }

    /**
     * Blank restores the public host.
     *
     * @throws IllegalArgumentException for a URL {@link #problemWith} refuses
     */
    public static void setGithub(String url) {
        store(GITHUB_KEY, url);
    }

    /** @throws IllegalArgumentException for a URL {@link #problemWith} refuses */
    public static void setGitlab(String url) {
        store(GITLAB_KEY, url);
    }

    /**
     * Blank restores Codeberg.
     *
     * @throws IllegalArgumentException for a URL {@link #problemWith} refuses
     */
    public static void setGitea(String url) {
        store(GITEA_KEY, url);
    }

    private static void store(String key, String url) {
        String problem = problemWith(url);
        if (problem != null) throw new IllegalArgumentException(problem);
        Settings.put(key, url == null || url.isBlank() ? null : url.trim());
    }

    /**
     * Why {@code url} cannot be a provider server, or {@code null} when it can (blank
     * included - blank means the public host).
     *
     * <p>Plain {@code http://} is refused: every request carries the access token in a
     * header, and over http that is the token in clear text to anyone on the path. The
     * one exception is the machine itself - a local test server - where there is no path
     * to be on.
     */
    public static String problemWith(String url) {
        if (url == null || url.isBlank()) return null;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return "Not a URL: " + url.trim();
        }
        if (uri.getHost() == null || uri.getScheme() == null) {
            return "Give the whole address, including https:// - for example https://git.example.com";
        }
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (scheme.equals("https")) return null;
        if (scheme.equals("http") && isLoopback(uri.getHost())) return null;
        if (scheme.equals("http")) {
            return "Use https://. Over plain http the access token would travel in clear text.";
        }
        return "Only https:// server addresses are supported.";
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    /** The GitHub server's web address - what its remotes' URLs are made of. */
    public static URI githubWeb() {
        return web(github(), "/api/v3", URI.create("https://github.com"));
    }

    /** The GitLab server's web address - what its remotes' URLs are made of. */
    public static URI gitlabWeb() {
        return web(gitlab(), "/api/v4", URI.create("https://gitlab.com"));
    }

    /**
     * The Gitea server's web address - what its remotes' URLs are made of. Codeberg's
     * {@code https://codeberg.org} unless another server is configured.
     */
    public static URI giteaWeb() {
        return web(gitea(), "/api/v1", URI.create("https://codeberg.org"));
    }

    private static URI web(URI api, String apiPath, URI publicWeb) {
        if (api.equals(GITHUB_PUBLIC) || api.equals(GITLAB_PUBLIC) || api.equals(GITEA_DEFAULT)) return publicWeb;
        String text = trimTrailingSlash(api.toString());
        if (text.endsWith(apiPath)) text = text.substring(0, text.length() - apiPath.length());
        return URI.create(text);
    }

    /**
     * The name a remembered token for {@code provider} ({@code github}, {@code gitlab},
     * {@code bitbucket}, {@code gitea}) is stored under: the provider <em>and</em> the server that
     * issued it.
     *
     * <p>Stored under the provider alone, a token remembered for one server was offered
     * to whichever server was configured next - a GitHub Enterprise token sent to a
     * different host because the setting changed while nobody was connected. Keyed by
     * server, a token can only ever go back to the server it came from.
     */
    public static String tokenAccount(String provider) {
        return com.jfx.git.core.hosting.HostingProvider.byId(provider)
                .map(ProviderHosts::tokenAccount)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider " + provider));
    }

    /** {@link #tokenAccount(String)} for {@code provider}. */
    public static String tokenAccount(com.jfx.git.core.hosting.HostingProvider provider) {
        URI web = web(provider);
        String host = web.getHost() == null ? "" : web.getHost().toLowerCase(java.util.Locale.ROOT);
        return provider.id() + "@" + host + (web.getPort() > 0 ? ":" + web.getPort() : "");
    }

    /** {@code provider}'s configured server's web address. */
    public static URI web(com.jfx.git.core.hosting.HostingProvider provider) {
        return switch (provider) {
            case GITHUB -> githubWeb();
            case GITLAB -> gitlabWeb();
            case BITBUCKET -> BITBUCKET_WEB;
            case GITEA -> giteaWeb();
        };
    }

    /** {@code provider}'s configured API root. */
    public static URI api(com.jfx.git.core.hosting.HostingProvider provider) {
        return switch (provider) {
            case GITHUB -> github();
            case GITLAB -> gitlab();
            case BITBUCKET -> BITBUCKET_API;
            case GITEA -> gitea();
        };
    }

    /**
     * Reads a configured host, adding the API path when the user gave only a server.
     *
     * <p>People paste what they see in the browser - {@code https://ghe.example.com} -
     * far more often than the API root, and an integration that silently fails because
     * the {@code /api/v3} was missing is a bad first experience. A URL that already names
     * a path is left exactly as given, so an install behind a prefix still works.
     */
    private static URI configured(String key, URI fallback, String apiPath) {
        String stored = Settings.get(key, "");
        if (stored == null || stored.isBlank()) return fallback;
        try {
            URI uri = URI.create(stored.trim());
            if (uri.getHost() == null) {
                LOGGER.warning("Ignoring " + key + "=" + stored + ": no host in it");
                return fallback;
            }
            // A hand-edited settings file can still say http://; the token is not sent
            // there in clear text on its say-so.
            String problem = problemWith(stored);
            if (problem != null) {
                LOGGER.warning("Ignoring " + key + "=" + stored + ": " + problem);
                return fallback;
            }
            String path = uri.getPath();
            if (path == null || path.isBlank() || path.equals("/")) {
                return URI.create(trimTrailingSlash(uri.toString()) + apiPath);
            }
            return URI.create(trimTrailingSlash(uri.toString()));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Ignoring unusable " + key + "=" + stored, e);
            return fallback;
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
