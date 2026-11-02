package com.jfx.git.core.hosting;

import com.jfx.git.core.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which OAuth application signs in to which server, and how each provider's sign-in works.
 *
 * <p>The provider facts here were checked against each provider's documentation:
 *
 * <ul>
 *   <li><b>GitHub</b> - an OAuth App (not a GitHub App, which only sees the repositories it
 *       is installed on) with the device flow enabled. No secret, no revocation endpoint a
 *       public client can call. A GitHub Enterprise server serves the same paths on its own
 *       host.</li>
 *   <li><b>GitLab</b> - the device grant on 17.2 and later; older self-managed servers only
 *       have the authorization code with PKCE, on a fixed loopback port because GitLab
 *       matches redirect URIs exactly. Tokens expire after two hours and refresh tokens
 *       rotate. {@code POST /oauth/revoke} revokes.</li>
 *   <li><b>Bitbucket Cloud</b> - the authorization code only, and its consumers cannot be
 *       public: the token endpoint wants the consumer's key and secret as HTTP Basic, so the
 *       secret ships with the application. The consumer's registration decides the scopes,
 *       so none are requested. No revocation endpoint.</li>
 * </ul>
 *
 * <h2>Where the client ids come from</h2>
 * The ids for the public servers ship in {@code oauth-clients.properties}, next to this
 * class, filled in by the maintainer after registering each application. A registration
 * belongs to one server - github.com's application does not exist on a GitHub Enterprise
 * host - so a shipped id is only used for the server it was registered on: {@code
 * <provider>.clientId} for the provider's public host, {@code <provider>.<host>.clientId}
 * for a named one. A self-hosted server's administrator registers an application there,
 * and the user enters its id, which is stored as {@code integration.<provider>.oauthClientId}
 * and wins over anything shipped.
 *
 * <p>Without a client id there is no OAuth sign-in for that server, and {@link #spec}
 * returns empty: the UI offers a pasted token only.
 */
public final class OAuthClients {

    private static final Logger LOGGER = Logger.getLogger(OAuthClients.class.getName());

    /** The shipped registrations, next to this class. */
    static final String RESOURCE = "oauth-clients.properties";

    /**
     * Values that must not be committed - Bitbucket's client secret - read over
     * {@link #RESOURCE} when present.
     *
     * <p>Kept out of the repository ({@code .gitignore}): a release build writes it into
     * the packaged jar from {@code LYRA_BITBUCKET_CLIENT_SECRET} (see the packaging
     * scripts), and a developer may keep a local copy beside {@link #RESOURCE} to try
     * Bitbucket sign-in. The secret still ships inside the application - Bitbucket's token
     * endpoint requires it, as it does Git Credential Manager's - but it is not published
     * in source, indexed and flagged by every secret scanner, for anyone to dress up a
     * sign-in page as this application's.
     */
    static final String SECRETS_RESOURCE = "oauth-secrets.properties";

    /**
     * The loopback port GitLab's and Bitbucket's registered redirect URIs name. Both match
     * the redirect exactly, port included, so it cannot be chosen at run time.
     */
    public static final int LOOPBACK_PORT = 47823;

    private static final String GITHUB_SCOPES = "repo read:org workflow";
    private static final String GITLAB_SCOPES = "api read_user write_repository";

    private static volatile OAuthClients shipped;

    private final Properties registrations;

    OAuthClients(Properties registrations) {
        this.registrations = registrations;
    }

    /** The registrations that ship with the application. */
    public static OAuthClients shipped() {
        OAuthClients s = shipped;
        if (s == null) {
            synchronized (OAuthClients.class) {
                if (shipped == null) shipped = new OAuthClients(load());
                s = shipped;
            }
        }
        return s;
    }

    /** The settings key a server's own client id is stored under. */
    public static String clientIdKey(HostingProvider provider) {
        return "integration." + provider.id() + ".oauthClientId";
    }

    /** {@link #spec(HostingProvider, URI)} for the provider's configured server. */
    public Optional<OAuthSpec> spec(HostingProvider provider) {
        return spec(provider, provider.web());
    }

    /**
     * How to sign in to {@code provider} at {@code web} with the provider's preferred flow,
     * or empty when no client id is known for that server.
     *
     * @param web the server's web address, e.g. {@code https://gitlab.example.com}
     */
    public Optional<OAuthSpec> spec(HostingProvider provider, URI web) {
        return switch (provider) {
            case GITHUB, GITLAB -> spec(provider, web, OAuthSpec.Flow.DEVICE);
            case BITBUCKET, GITEA -> spec(provider, web, OAuthSpec.Flow.LOOPBACK);
            default -> Optional.empty();
        };
    }

    /**
     * How to sign in to {@code provider} at {@code web} with {@code flow}, or empty when the
     * provider does not offer that flow or no client id is known.
     *
     * <p>GitLab offers both: the device grant first, and the loopback flow for a
     * self-managed server too old to have it ({@link OAuthException.Reason#UNSUPPORTED} from
     * {@link OAuthDeviceFlow#start()}).
     */
    public Optional<OAuthSpec> spec(HostingProvider provider, URI web, OAuthSpec.Flow flow) {
        Optional<String> id = clientId(provider, web);
        if (id.isEmpty()) return Optional.empty();
        String clientId = id.get();
        String root = root(web);
        return switch (provider) {
            case GITHUB -> flow != OAuthSpec.Flow.DEVICE ? Optional.empty() : Optional.of(new OAuthSpec(
                    OAuthSpec.Flow.DEVICE,
                    URI.create(root + "/login/device/code"), null,
                    URI.create(root + "/login/oauth/access_token"), null,
                    GITHUB_SCOPES, clientId, null, 0, false));
            case GITLAB -> Optional.of(flow == OAuthSpec.Flow.DEVICE
                    ? new OAuthSpec(OAuthSpec.Flow.DEVICE,
                            URI.create(root + "/oauth/authorize_device"), null,
                            URI.create(root + "/oauth/token"), URI.create(root + "/oauth/revoke"),
                            GITLAB_SCOPES, clientId, null, 0, false)
                    : new OAuthSpec(OAuthSpec.Flow.LOOPBACK,
                            null, URI.create(root + "/oauth/authorize"),
                            URI.create(root + "/oauth/token"), URI.create(root + "/oauth/revoke"),
                            GITLAB_SCOPES, clientId, null, LOOPBACK_PORT, true));
            case BITBUCKET -> {
                String secret = shippedValue(provider, web, "clientSecret");
                if (flow != OAuthSpec.Flow.LOOPBACK || secret == null) yield Optional.empty();
                yield Optional.of(new OAuthSpec(OAuthSpec.Flow.LOOPBACK,
                        null, URI.create(root + "/site/oauth2/authorize"),
                        URI.create(root + "/site/oauth2/access_token"), null,
                        "", clientId, secret, LOOPBACK_PORT, false));
            }
            // A public client with PKCE on any loopback port: Gitea and Forgejo match a
            // 127.0.0.1 redirect without its port. No scopes are asked for - Forgejo has
            // none yet (the grant is the whole account, which the sign-in card says), and
            // Gitea grants everything when none are named, so both behave alike.
            case GITEA -> flow != OAuthSpec.Flow.LOOPBACK ? Optional.empty() : Optional.of(new OAuthSpec(
                    OAuthSpec.Flow.LOOPBACK,
                    null, URI.create(root + "/login/oauth/authorize"),
                    URI.create(root + "/login/oauth/access_token"), null,
                    "", clientId, null, 0, true));
            default -> Optional.empty();
        };
    }

    /**
     * The client id for {@code provider} at {@code web}: the user's own setting when there
     * is one, otherwise a shipped registration for that server.
     */
    public Optional<String> clientId(HostingProvider provider, URI web) {
        String configured = Settings.get(clientIdKey(provider), "").trim();
        if (!configured.isEmpty()) return Optional.of(configured);
        return Optional.ofNullable(shippedValue(provider, web, "clientId"));
    }

    /**
     * {@code <provider>.<host>.<name>}, or {@code <provider>.<name>} when {@code web} is the
     * provider's public server; null when blank or absent.
     */
    private String shippedValue(HostingProvider provider, URI web, String name) {
        String host = web == null || web.getHost() == null ? "" : web.getHost().toLowerCase(Locale.ROOT);
        String value = registrations.getProperty(provider.id() + "." + host + "." + name, "").trim();
        if (value.isEmpty() && host.equals(publicHost(provider))) {
            value = registrations.getProperty(provider.id() + "." + name, "").trim();
        }
        return value.isEmpty() ? null : value;
    }

    /** The host the shipped registration without a host in its key belongs to; null for none. */
    private static String publicHost(HostingProvider provider) {
        return switch (provider) {
            case GITHUB -> "github.com";
            case GITLAB -> "gitlab.com";
            case BITBUCKET -> "bitbucket.org";
            case GITEA -> "codeberg.org";
            default -> null;
        };
    }

    private static String root(URI web) {
        String text = web.toString();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static Properties load() {
        try (InputStream registrations = OAuthClients.class.getResourceAsStream(RESOURCE);
             InputStream secrets = OAuthClients.class.getResourceAsStream(SECRETS_RESOURCE)) {
            return layered(registrations, secrets);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read " + RESOURCE + "; OAuth sign-in is unavailable", e);
            return new Properties();
        }
    }

    /**
     * The registrations, with every non-blank value in {@code secrets} laid over them -
     * a blank one leaves the committed value standing. Either stream may be null.
     */
    static Properties layered(InputStream registrations, InputStream secrets) throws IOException {
        Properties p = new Properties();
        if (registrations != null) p.load(registrations);
        if (secrets != null) {
            Properties overlay = new Properties();
            overlay.load(secrets);
            for (String key : overlay.stringPropertyNames()) {
                String value = overlay.getProperty(key).trim();
                if (!value.isEmpty()) p.setProperty(key, value);
            }
        }
        return p;
    }
}
