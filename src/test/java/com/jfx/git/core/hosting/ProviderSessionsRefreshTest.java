package com.jfx.git.core.hosting;

import com.jfx.git.core.Settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An OAuth session's access token is replaced before it expires, once, and remembered again. */
class ProviderSessionsRefreshTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @TempDir
    Path tempDir;

    private final AtomicInteger refreshes = new AtomicInteger();
    private final Map<String, String> stored = new ConcurrentHashMap<>();
    private final List<String> removed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void seams() {
        Settings.useFile(tempDir.resolve("settings.properties"));
        ProviderSessions.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ProviderSessions.store = new ProviderSessions.Store() {
            @Override
            public boolean store(String account, String value) {
                stored.put(account, value);
                return true;
            }

            @Override
            public void remove(String account) {
                removed.add(account);
            }
        };
        ProviderSessions.refresher = (provider, credential) -> {
            int n = refreshes.incrementAndGet();
            return ProviderCredential.oauth("access-" + n, "refresh-" + n, NOW.plus(Duration.ofHours(2)),
                    credential.clientId(), credential.scopes());
        };
    }

    @AfterEach
    void restore() {
        for (HostingProvider p : HostingProvider.values()) ProviderSessions.disconnect(p);
        ProviderSessions.useDefaults();
        Settings.useFile(null);
    }

    private static ProviderCredential expiringIn(Duration d) {
        return ProviderCredential.oauth("old-access", "old-refresh", NOW.plus(d), "client", "api");
    }

    @Test
    void aTokenWithTimeLeftIsHandedOutAsItIs() {
        ProviderSessions.connect(HostingProvider.GITLAB, expiringIn(Duration.ofHours(1)), null, "tanuki");
        assertEquals("old-access", ProviderSessions.accessToken(HostingProvider.GITLAB));
        assertEquals(0, refreshes.get());
    }

    @Test
    void anExpiringTokenIsReplacedFirstAndTheGrantRememberedAgain() {
        ProviderSessions.connect(HostingProvider.GITLAB, expiringIn(Duration.ofMinutes(2)), null, "tanuki");
        ProviderSessions.setRemembered(HostingProvider.GITLAB, true);

        assertEquals("access-1", ProviderSessions.accessToken(HostingProvider.GITLAB));
        assertEquals("access-1", ProviderSessions.accessToken(HostingProvider.GITLAB), "and kept");
        assertEquals(1, refreshes.get());
        String account = HostingProvider.GITLAB.tokenAccount();
        assertEquals("refresh-1", ProviderCredential.fromStored(stored.get(account)).refreshToken(),
                "the rotated refresh token replaces the stored one");
    }

    @Test
    void aGrantThatWasNotRememberedIsNotStored() {
        ProviderSessions.connect(HostingProvider.GITLAB, expiringIn(Duration.ZERO), null, "tanuki");
        assertEquals("access-1", ProviderSessions.accessToken(HostingProvider.GITLAB));
        assertTrue(stored.isEmpty());
    }

    /** Two exchanges racing would spend GitLab's rotating refresh token twice. */
    @Test
    void callersAtTheSameMomentShareOneExchange() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProviderSessions.refresher = (provider, credential) -> {
            refreshes.incrementAndGet();
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ProviderCredential.oauth("shared", "r", NOW.plus(Duration.ofHours(2)), "client", null);
        };
        ProviderSessions.connect(HostingProvider.GITLAB, expiringIn(Duration.ZERO), null, "tanuki");

        String[] answers = new String[2];
        Thread first = new Thread(() -> answers[0] = ProviderSessions.accessToken(HostingProvider.GITLAB));
        first.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        Thread second = new Thread(() -> answers[1] = ProviderSessions.accessToken(HostingProvider.GITLAB));
        second.start();
        Thread.sleep(50); // the second caller is now waiting on the refresh lock
        release.countDown();
        first.join(5_000);
        second.join(5_000);

        assertEquals(1, refreshes.get());
        assertEquals("shared", answers[0]);
        assertEquals("shared", answers[1]);
    }

    @Test
    void aRefusedGrantSignsTheUserOut() {
        ProviderSessions.refresher = (provider, credential) -> {
            throw new OAuthException(OAuthException.Reason.PROVIDER, "invalid_grant", 400, "revoked", null);
        };
        ProviderSessions.connect(HostingProvider.GITLAB, expiringIn(Duration.ZERO), null, "tanuki");
        ProviderSessions.setRemembered(HostingProvider.GITLAB, true);

        assertNull(ProviderSessions.accessToken(HostingProvider.GITLAB));
        assertFalse(ProviderSessions.isConnected(HostingProvider.GITLAB));
        assertEquals(List.of(HostingProvider.GITLAB.tokenAccount()), removed, "and forgets the stored grant");
    }

    /** Offline: the old token is used, and the token endpoint is not asked again on every call. */
    @Test
    void aFailedExchangeKeepsTheOldTokenAndWaitsBeforeTryingAgain() {
        ProviderSessions.refresher = (provider, credential) -> {
            refreshes.incrementAndGet();
            throw new OAuthException(OAuthException.Reason.TRANSPORT, "offline");
        };
        ProviderSessions.connect(HostingProvider.GITLAB, expiringIn(Duration.ofMinutes(1)), null, "tanuki");

        assertEquals("old-access", ProviderSessions.accessToken(HostingProvider.GITLAB));
        assertEquals("old-access", ProviderSessions.accessToken(HostingProvider.GITLAB));
        assertEquals(1, refreshes.get(), "not again straight away");
        assertTrue(ProviderSessions.isConnected(HostingProvider.GITLAB));

        ProviderSessions.clock = Clock.fixed(NOW.plus(Duration.ofSeconds(61)), ZoneOffset.UTC);
        ProviderSessions.accessToken(HostingProvider.GITLAB);
        assertEquals(2, refreshes.get(), "a minute later it tries again");
    }

    @Test
    void aNewTokenIsOfferedToGitAgainAfterTheOldOneWasRefused() {
        ProviderCredential old = expiringIn(Duration.ZERO);
        ProviderSessions.connect(HostingProvider.GITLAB, old, null, "tanuki");
        ProviderSessions.markRejectedForGit(HostingProvider.GITLAB, old);

        ProviderSessions.accessToken(HostingProvider.GITLAB);
        assertFalse(ProviderSessions.isRejectedForGit(HostingProvider.GITLAB));
    }
}
