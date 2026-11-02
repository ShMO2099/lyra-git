package com.jfx.git.core;

import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.OAuthException;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.core.hosting.ProviderSessions;

import java.util.logging.Logger;

/**
 * Reconnects the provider integrations a user asked to be remembered.
 *
 * <h2>Why it revalidates rather than trusting the stored token</h2>
 * A token that was good three weeks ago may have expired, been revoked, or had its scopes
 * narrowed. Marking the session connected on the strength of a stored string would put
 * the UI into a state where every provider action fails one at a time with a confusing
 * error. So each token is checked against the provider's own {@code /user} endpoint
 * first, exactly as the Connect dialog does, and a rejected one is forgotten - the user
 * sees "Not connected" and reconnects, which is the honest outcome.
 *
 * <h2>Why only a rejection forgets it</h2>
 * "Did not validate" and "was rejected" are not the same thing, and treating them as one
 * meant that starting the application on a train deleted three working logins. A failure
 * only discards the token when the provider actually said the credential is no good - see
 * {@link ApiFailure#keepsCredentials()}. Anything else leaves it in place, disconnected
 * for this session, to be retried on the next launch.
 *
 * <h2>Why it runs in the background</h2>
 * Three HTTPS round trips on the way to a window would be three round trips of blank
 * screen, and on a machine with no network they would be three timeouts. Restoring
 * happens on the provider pool after the UI is up. Until it lands
 * {@link IntegrationEvents#restoringProperty()} is true, so screens say "Reconnecting..."
 * and pick the session up when it connects, rather than reading "Not connected" for good.
 */
public final class IntegrationSessions {

    private static final Logger LOGGER = Logger.getLogger(IntegrationSessions.class.getName());

    private IntegrationSessions() {
    }

    /**
     * Restores whatever was remembered, off the calling thread.
     *
     * <p>Safe to call when nothing was ever stored, and when the platform has no store at
     * all: both simply do nothing.
     */
    public static void restoreInBackground() {
        // Said before anything is queued, so a screen opened in the next instant already
        // knows to say "Reconnecting..." rather than "Not connected".
        IntegrationEvents.setRestoring(true);
        // Asked on the provider pool, not here: the first answer runs the keychain
        // detection - two PowerShell launches on Windows - and this is called on the way
        // to the window. Not the network lane either, where it queued behind any update
        // check or clone.
        GitTaskRunner.queryProvider(() -> {
                    if (!TokenStore.isAvailable()) {
                        LOGGER.warning("Remembered integrations were not restored: "
                                + TokenStore.describeLocation() + ".");
                        return 0;
                    }
                    return restore();
                },
                restored -> {
                    IntegrationEvents.setRestoring(false);
                    if (restored > 0) {
                        LOGGER.info("Restored " + restored + " remembered integration(s)");
                    }
                },
                message -> {
                    IntegrationEvents.setRestoring(false);
                    LOGGER.warning("Could not restore integrations: " + message);
                });
    }

    /** One provider's restore; {@code true} when it reconnected. */
    @FunctionalInterface
    interface Restorer {
        boolean restore();
    }

    /** @return how many sessions were reconnected */
    private static Integer restore() {
        java.util.List<java.util.Map.Entry<String, Restorer>> restorers = new java.util.ArrayList<>();
        for (HostingProvider provider : HostingProvider.values()) {
            restorers.add(java.util.Map.entry(provider.displayName(), () -> restore(provider)));
        }
        return restoreEach(restorers);
    }

    /**
     * Runs each provider's restore on its own: one that throws is logged and the rest
     * still run. They ran in a row inside one call, so a surprise in GitHub's left GitLab
     * and Bitbucket disconnected too.
     */
    static int restoreEach(java.util.List<java.util.Map.Entry<String, Restorer>> restorers) {
        int restored = 0;
        for (var entry : restorers) {
            try {
                restored += entry.getValue().restore() ? 1 : 0;
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING,
                        "Could not restore the remembered " + entry.getKey() + " integration", e);
            }
        }
        return restored;
    }

    /** Reconnects {@code provider} from what was remembered for its current server. */
    private static boolean restore(HostingProvider provider) {
        String account = provider.tokenAccount();
        String stored;
        if (provider == HostingProvider.BITBUCKET) {
            stored = TokenStore.load(account).orElse(null);
            if (stored == null) {
                // Bitbucket has only ever had one server, so a token stored under the old
                // provider-only name can only have come from it: moved, not dropped.
                stored = TokenStore.load("bitbucket").orElse(null);
                if (stored == null) return false;
                if (TokenStore.store(account, stored)) TokenStore.remove("bitbucket");
            }
        } else {
            dropUnscopedToken(provider.id());
            stored = TokenStore.load(account).orElse(null);
        }
        ProviderCredential credential = ProviderCredential.fromStored(stored);
        if (credential == null) return false;
        if (credential.needsRefresh(java.time.Clock.systemUTC())) {
            // A remembered OAuth sign-in's access token has usually expired since the last
            // launch; its refresh token gets a new one, which replaces the stored grant -
            // GitLab's old refresh token stops working the moment the new one is issued.
            try {
                credential = ProviderSessions.refreshDetached(provider, credential);
            } catch (OAuthException e) {
                return failed(account, e.isInvalidGrant() ? ApiFailure.AUTH_REJECTED : ApiFailure.TRANSPORT,
                        e.getMessage());
            }
            if (!TokenStore.store(account, credential.toStored())) {
                LOGGER.warning("The refreshed " + account + " sign-in could not be remembered again.");
            }
        }
        Refusal refusal = validateAndConnect(provider, credential);
        if (refusal != null) return failed(account, refusal.kind(), refusal.message());
        ProviderSessions.setRemembered(provider, true);
        return true;
    }

    /** Why a provider did not accept a credential. */
    private record Refusal(ApiFailure kind, String message) {
    }

    /**
     * Asks {@code provider} who {@code credential} belongs to and, when it answers,
     * connects its session.
     *
     * @return null when connected, otherwise why not
     */
    private static Refusal validateAndConnect(HostingProvider provider, ProviderCredential credential) {
        String token = credential.accessToken();
        switch (provider) {
            case GITHUB -> {
                var result = new GitHubClient().fetchAuthenticatedUser(token);
                if (result.isOk()) {
                    GitHubSession.connect(credential, result.orNull());
                    return null;
                }
                return new Refusal(result.failureKind(), result.messageOrNull());
            }
            case GITLAB -> {
                var result = new GitLabClient().fetchAuthenticatedUser(token);
                if (result.isOk()) {
                    GitLabSession.connect(credential, result.orNull());
                    return null;
                }
                return new Refusal(result.failureKind(), result.messageOrNull());
            }
            case BITBUCKET -> {
                var result = new BitbucketClient().fetchAuthenticatedUser(token);
                if (result.isOk()) {
                    BitbucketSession.connect(credential, result.orNull());
                    return null;
                }
                return new Refusal(result.failureKind(), result.messageOrNull());
            }
            case GITEA -> {
                var result = new GiteaClient().fetchAuthenticatedUser(token);
                if (result.isOk()) {
                    GiteaSession.connect(credential, result.orNull());
                    return null;
                }
                return new Refusal(result.failureKind(), result.messageOrNull());
            }
        }
        throw new IllegalStateException("No validation for " + provider);
    }

    /**
     * Forgets a GitHub or GitLab token remembered under the provider name alone, as
     * tokens were before they were keyed by server.
     *
     * <p>Such a token does not say which server issued it, and sending it to whichever
     * server is configured now is the leak keying by server exists to stop - a GitHub
     * Enterprise token handed to github.com, say. So it is not used; the user connects
     * once more and the new token is remembered under its server.
     *
     * <p>Gitea was keyed by server from its first version, so for it this finds nothing;
     * it runs anyway so that no provider is the exception that a stray entry slips past.
     */
    private static void dropUnscopedToken(String provider) {
        if (TokenStore.load(provider).isPresent()) {
            LOGGER.info("Forgetting the remembered " + provider + " token stored without its"
                    + " server; connect again to remember one for the current server.");
            TokenStore.remove(provider);
        }
    }

    /**
     * Records a failed restore, and decides whether the stored token survives it.
     *
     * <p>The decision belongs to {@link ApiFailure}, not to this method: a token is
     * dropped only when the provider said the credential itself is bad. Being offline, a
     * rate limit, and a provider outage all leave it exactly where it is.
     *
     * @return {@code false} always - nothing was restored either way
     */
    private static boolean failed(String account, ApiFailure kind, String why) {
        // A null kind means a result type that predates the classification; treated as
        // unclassified rather than as a rejection, because the safe answer is to keep.
        ApiFailure failure = kind == null ? ApiFailure.OTHER : kind;
        if (failure.keepsCredentials()) {
            LOGGER.warning("Could not reconnect " + account + " this time (" + failure + "): "
                    + why + ". The remembered token has been kept.");
            return false;
        }
        LOGGER.warning("The remembered " + account + " token was rejected and has been"
                + " forgotten: " + why);
        TokenStore.remove(account);
        return false;
    }
}
