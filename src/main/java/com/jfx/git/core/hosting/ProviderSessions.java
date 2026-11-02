package com.jfx.git.core.hosting;

import com.jfx.git.core.IntegrationEvents;
import com.jfx.git.core.TokenStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which providers this process is connected to right now, as whom, and with what.
 *
 * <p>Held for the lifetime of the process. The credential never reaches
 * {@link com.jfx.git.core.Settings}, which stores values in clear text; it outlives the
 * process only where the user ticked "Remember", through {@link com.jfx.git.core.TokenStore},
 * and {@link com.jfx.git.core.IntegrationSessions} revalidates it at the next launch.
 * Every connect and disconnect is announced through {@link IntegrationEvents}, so open
 * screens follow it.
 *
 * <p>{@code GitHubSession}, {@code GitLabSession}, {@code BitbucketSession} and
 * {@code GiteaSession} are typed views of this, kept so their many callers did not have
 * to change.
 *
 * <h2>Expiring OAuth tokens</h2>
 * An OAuth sign-in's access token lasts an hour or two on most providers. Rather than have
 * every caller notice a 401 and recover, {@link #accessToken} hands out a token that will
 * still be good: within five minutes of expiry it is exchanged for a new one first (see
 * {@link #freshCredential}). One exchange at a time per provider, because GitLab rotates
 * refresh tokens - two exchanges racing would spend the same refresh token twice and the
 * loser would sign the user out. A remembered grant is stored again after each exchange,
 * or the next launch would hold a refresh token that no longer works.
 */
public final class ProviderSessions {

    private static final Logger LOGGER = Logger.getLogger(ProviderSessions.class.getName());

    /**
     * One provider's connection. The user object is the provider's own record type.
     *
     * @param rejectedForGit whether a git server refused {@code credential} as a login;
     *                       see {@link #markRejectedForGit}
     * @param remembered     whether the credential is kept in the {@link TokenStore}, so a
     *                       refreshed one has to be stored again
     */
    private record Session(ProviderCredential credential, Object user, String login, boolean rejectedForGit,
                           boolean remembered) {
        Session with(ProviderCredential next) {
            return new Session(next, user, login, false, remembered);
        }
    }

    private static final Map<HostingProvider, Session> SESSIONS = new EnumMap<>(HostingProvider.class);

    /** Exchanges an expiring grant for a new one; replaced by tests. */
    @FunctionalInterface
    interface Refresher {
        ProviderCredential refresh(HostingProvider provider, ProviderCredential credential) throws OAuthException;
    }

    /** Where a remembered credential goes; replaced by tests. */
    interface Store {
        boolean store(String account, String value);

        void remove(String account);
    }

    private static final Refresher OAUTH_REFRESHER = (provider, credential) -> {
        OAuthSpec spec = OAuthClients.shipped().spec(provider, provider.web()).orElseThrow(() ->
                new OAuthException(OAuthException.Reason.UNSUPPORTED,
                        "No OAuth application is configured for " + provider.displayName() + " any more"));
        return new OAuthTokenClient().refresh(spec, credential);
    };
    private static final Store TOKEN_STORE = new Store() {
        @Override
        public boolean store(String account, String value) {
            return TokenStore.store(account, value);
        }

        @Override
        public void remove(String account) {
            TokenStore.remove(account);
        }
    };

    static volatile Refresher refresher = OAUTH_REFRESHER;
    static volatile Store store = TOKEN_STORE;
    static volatile Clock clock = Clock.systemUTC();

    /** Puts the real refresher, store and clock back; for tests. */
    static void useDefaults() {
        refresher = OAUTH_REFRESHER;
        store = TOKEN_STORE;
        clock = Clock.systemUTC();
        synchronized (RETRY_AFTER) {
            RETRY_AFTER.clear();
        }
    }

    /** One lock per provider, so refreshing GitHub never waits on GitLab's token endpoint. */
    private static final Map<HostingProvider, Object> REFRESH_LOCKS = new EnumMap<>(HostingProvider.class);

    static {
        for (HostingProvider p : HostingProvider.values()) REFRESH_LOCKS.put(p, new Object());
    }

    /**
     * When a provider's token endpoint may next be tried, after a failure that was not a
     * refusal - offline, or the provider down. Without it every API call made while
     * offline would be one more doomed request to the token endpoint first.
     */
    private static final Map<HostingProvider, Instant> RETRY_AFTER = new EnumMap<>(HostingProvider.class);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private ProviderSessions() {
    }

    /**
     * Records a validated credential and who it belongs to.
     *
     * @param user  the provider's own user record, for its typed session to hand back
     * @param login the account's login name, or null when the provider did not give one
     */
    public static void connect(HostingProvider provider, ProviderCredential credential, Object user, String login) {
        if (credential == null) throw new IllegalArgumentException("credential");
        synchronized (SESSIONS) {
            SESSIONS.put(provider, new Session(credential, user, login, false, false));
        }
        IntegrationEvents.changed();
    }

    /**
     * Says whether the current credential is kept in the {@link TokenStore} - set once it
     * has been stored, so a refreshed credential is stored again in its place.
     */
    public static void setRemembered(HostingProvider provider, boolean remembered) {
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(provider);
            if (s == null || s.remembered() == remembered) return;
            SESSIONS.put(provider, new Session(s.credential(), s.user(), s.login(), s.rejectedForGit(), remembered));
        }
    }

    /** Forgets the credential and the connected user. */
    public static void disconnect(HostingProvider provider) {
        synchronized (SESSIONS) {
            SESSIONS.remove(provider);
        }
        IntegrationEvents.changed();
    }

    public static boolean isConnected(HostingProvider provider) {
        return session(provider) != null;
    }

    /** The current credential, or null when not connected. */
    public static ProviderCredential credential(HostingProvider provider) {
        Session s = session(provider);
        return s == null ? null : s.credential();
    }

    /**
     * An access token that will still work, or null when not connected. Never logged.
     *
     * <p>Refreshed first when it is about to expire - see {@link #freshCredential}, which
     * this blocks on except on the FX thread.
     */
    public static String accessToken(HostingProvider provider) {
        ProviderCredential credential = freshCredential(provider);
        return credential == null ? null : credential.accessToken();
    }

    /**
     * The current credential, exchanged for a new one first when it is about to expire;
     * null when not connected, or when the provider has just refused the grant (which
     * disconnects it).
     *
     * <p>On the FX thread this never waits on the network: it returns what there is and
     * refreshes in the background, and the work that needs the token - which runs on a
     * worker - gets the new one. A failure that is not a refusal keeps the old credential
     * and tries again a minute later.
     */
    public static ProviderCredential freshCredential(HostingProvider provider) {
        Session s = session(provider);
        if (s == null) return null;
        ProviderCredential current = s.credential();
        if (!current.needsRefresh(clock) || refreshDeferred(provider)) return current;
        if (onFxThread()) {
            com.jfx.git.core.GitTaskRunner.queryProvider(() -> refresh(provider, current), done -> { }, failed -> { });
            return current;
        }
        return refresh(provider, current);
    }

    /**
     * Exchanges a grant read back from the {@link TokenStore} before its session exists -
     * at startup, when the remembered access token has long expired.
     *
     * @throws OAuthException as {@link OAuthTokenClient#refresh}
     */
    public static ProviderCredential refreshDetached(HostingProvider provider, ProviderCredential credential)
            throws OAuthException {
        return refresher.refresh(provider, credential);
    }

    private static ProviderCredential refresh(HostingProvider provider, ProviderCredential stale) {
        synchronized (REFRESH_LOCKS.get(provider)) {
            Session s = session(provider);
            if (s == null) return null;
            // Someone else refreshed while this waited for the lock.
            if (s.credential() != stale || !s.credential().needsRefresh(clock)) return s.credential();
            if (refreshDeferred(provider)) return s.credential();
            ProviderCredential next;
            try {
                next = refresher.refresh(provider, stale);
            } catch (OAuthException e) {
                if (e.isInvalidGrant()) {
                    LOGGER.warning(provider.displayName() + " no longer accepts the sign-in; connect again."
                            + " (" + e.getMessage() + ")");
                    if (s.remembered()) store.remove(provider.tokenAccount());
                    disconnect(provider);
                    return null;
                }
                LOGGER.log(Level.INFO, "Could not refresh the " + provider.displayName() + " sign-in: "
                        + e.getMessage() + "; trying again shortly");
                synchronized (RETRY_AFTER) {
                    RETRY_AFTER.put(provider, clock.instant().plus(RETRY_DELAY));
                }
                return stale;
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Refreshing the " + provider.displayName() + " sign-in failed", e);
                synchronized (RETRY_AFTER) {
                    RETRY_AFTER.put(provider, clock.instant().plus(RETRY_DELAY));
                }
                return stale;
            }
            synchronized (SESSIONS) {
                Session now = SESSIONS.get(provider);
                // Disconnected or reconnected meanwhile: the exchange was for a session that
                // is gone, and its result belongs to nobody.
                if (now == null || now.credential() != stale) return now == null ? null : now.credential();
                SESSIONS.put(provider, now.with(next));
            }
            synchronized (RETRY_AFTER) {
                RETRY_AFTER.remove(provider);
            }
            if (s.remembered() && !store.store(provider.tokenAccount(), next.toStored())) {
                LOGGER.warning("The refreshed " + provider.displayName() + " sign-in could not be remembered;"
                        + " the next launch will ask to connect again.");
            }
            return next;
        }
    }

    private static boolean refreshDeferred(HostingProvider provider) {
        synchronized (RETRY_AFTER) {
            Instant after = RETRY_AFTER.get(provider);
            return after != null && clock.instant().isBefore(after);
        }
    }

    private static boolean onFxThread() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (Throwable noToolkit) {
            return false;
        }
    }

    /** The connected user as {@code type}, or null when not connected or of another type. */
    public static <U> U user(HostingProvider provider, Class<U> type) {
        Session s = session(provider);
        return s != null && type.isInstance(s.user()) ? type.cast(s.user()) : null;
    }

    /** The connected account's login name, or null. */
    public static String login(HostingProvider provider) {
        Session s = session(provider);
        return s == null ? null : s.login();
    }

    /**
     * Records that a git server refused {@code refused} as a login, so it is not offered to
     * git again this session and the user's own credentials answer instead.
     *
     * <p>Only when it is still the current credential: a refusal of a token that has since
     * been replaced - refreshed, or the account reconnected - says nothing about the new
     * one. Connecting again clears it, which is how a user who fixed the token's scopes
     * gets it used again.
     */
    public static void markRejectedForGit(HostingProvider provider, ProviderCredential refused) {
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(provider);
            if (s == null || s.credential() != refused || s.rejectedForGit()) return;
            SESSIONS.put(provider, new Session(s.credential(), s.user(), s.login(), true, s.remembered()));
        }
    }

    /** Whether git refused the current credential; see {@link #markRejectedForGit}. */
    public static boolean isRejectedForGit(HostingProvider provider) {
        Session s = session(provider);
        return s != null && s.rejectedForGit();
    }

    private static Session session(HostingProvider provider) {
        synchronized (SESSIONS) {
            return SESSIONS.get(provider);
        }
    }
}
