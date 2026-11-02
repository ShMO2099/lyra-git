package com.jfx.git.core.hosting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OAuthDeviceFlow} against a fake token endpoint, with a sleeper that records and
 * advances a fake clock instead of waiting - so the intervals are asserted exactly and the
 * tests take milliseconds.
 *
 * <p>Both providers' ways of saying "not yet" are covered: GitHub answers 200 with an
 * {@code error} field, GitLab answers 400.
 */
class OAuthDeviceFlowTest {

    private static final Instant START = Instant.parse("2026-10-07T12:00:00Z");

    private FakeOAuthServer server;
    private MutableClock clock;
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private OAuthDeviceFlow.Sleeper sleeper;

    @BeforeEach
    void start() throws Exception {
        server = new FakeOAuthServer();
        clock = new MutableClock(START);
        sleeper = d -> {
            sleeps.add(d);
            clock.advance(d);
        };
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private OAuthSpec spec() {
        return new OAuthSpec(OAuthSpec.Flow.DEVICE, server.uri("/device"), null, server.uri("/token"), null,
                "repo read:org workflow", "dev-client", null, 0, false);
    }

    private OAuthDeviceFlow flow() {
        return new OAuthDeviceFlow(spec(), new OAuthTokenClient(clock), sleeper);
    }

    private void deviceCode(int expiresIn, int interval) {
        server.on("/device", 200, """
                {"device_code":"dc-secret","user_code":"WDJB-MJHT",
                 "verification_uri":"https://example.test/device",
                 "expires_in":%d,"interval":%d}""".formatted(expiresIn, interval));
    }

    @Test
    void startRequestsACodeWithTheScopesAndClientId() throws Exception {
        server.on("/device", 200, """
                {"device_code":"dc-secret-xyz","user_code":"ABCD-EFGH","verification_uri":"https://gitlab.test/oauth/device",
                 "verification_uri_complete":"https://gitlab.test/oauth/device?user_code=ABCD-EFGH",
                 "expires_in":300,"interval":5}""");

        OAuthDeviceFlow.DeviceCode code = flow().start();

        assertEquals("ABCD-EFGH", code.userCode());
        assertEquals(URI.create("https://gitlab.test/oauth/device"), code.verificationUri());
        assertEquals(URI.create("https://gitlab.test/oauth/device?user_code=ABCD-EFGH"), code.browserUri());
        assertEquals(START.plusSeconds(300), code.expiresAt());
        assertEquals(Duration.ofSeconds(5), code.interval());
        assertFalse(code.toString().contains("dc-secret-xyz"), "the device code is as good as a token: " + code);

        FakeOAuthServer.Request request = server.requestsTo("/device").getFirst();
        assertEquals("application/json", request.accept(), "GitHub answers form-encoded without it");
        assertEquals(Map.of("scope", "repo read:org workflow", "client_id", "dev-client"), request.form());
    }

    /** GitHub: errors arrive with HTTP 200. */
    @Test
    void pendingThenSlowDownThenSuccessGitHubStyle() throws Exception {
        deviceCode(900, 5);
        server.on("/token", 200, "{\"error\":\"authorization_pending\"}")
                .on("/token", 200, "{\"error\":\"slow_down\",\"interval\":10}")
                .on("/token", 200, "{\"error\":\"authorization_pending\"}")
                .on("/token", 200, "{\"access_token\":\"gho_token\",\"token_type\":\"bearer\",\"scope\":\"repo\"}");
        OAuthDeviceFlow flow = flow();

        ProviderCredential credential = flow.poll(flow.start(), () -> false);

        assertEquals("gho_token", credential.accessToken());
        assertEquals("dev-client", credential.clientId());
        // 5 s before each poll until slow_down, 10 s from then on: the step sticks.
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(10)), sleeps);
        List<FakeOAuthServer.Request> polls = server.requestsTo("/token");
        assertEquals(4, polls.size());
        assertEquals(Map.of("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                "device_code", "dc-secret", "client_id", "dev-client"), polls.getFirst().form());
    }

    /** GitLab: errors arrive with HTTP 400, as RFC 6749 says. */
    @Test
    void pendingThenSlowDownThenSuccessGitLabStyle() throws Exception {
        deviceCode(300, 5);
        server.on("/token", 400, "{\"error\":\"authorization_pending\",\"error_description\":\"pending\"}")
                .on("/token", 400, "{\"error\":\"slow_down\"}")
                .on("/token", 200, "{\"access_token\":\"glo\",\"expires_in\":7200,\"refresh_token\":\"glr\"}");
        OAuthDeviceFlow flow = flow();

        ProviderCredential credential = flow.poll(flow.start(), () -> false);

        assertEquals("glo", credential.accessToken());
        assertEquals("glr", credential.refreshToken());
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10)), sleeps);
        assertEquals(START.plusSeconds(20 + 7200), credential.expiresAt(),
                "expiry counts from when the token was issued, after the waits");
    }

    @Test
    void anExpiredTokenErrorEndsPolling() throws Exception {
        deviceCode(900, 5);
        server.on("/token", 400, "{\"error\":\"expired_token\"}");
        OAuthDeviceFlow flow = flow();

        OAuthException e = assertThrows(OAuthException.class, () -> flow.poll(flow.start(), () -> false));

        assertEquals(OAuthException.Reason.EXPIRED, e.reason());
        assertEquals(1, server.requestsTo("/token").size());
    }

    /** The code's own lifetime ends polling even when the server keeps saying pending. */
    @Test
    void pollingStopsWhenTheCodeOutlivesItsLifetime() throws Exception {
        deviceCode(12, 5);
        server.on("/token", 200, "{\"error\":\"authorization_pending\"}");
        OAuthDeviceFlow flow = flow();

        OAuthException e = assertThrows(OAuthException.class, () -> flow.poll(flow.start(), () -> false));

        assertEquals(OAuthException.Reason.EXPIRED, e.reason());
        assertEquals(2, server.requestsTo("/token").size(), "polls at 5 s and 10 s; 15 s is past expiry");
    }

    @Test
    void aDeclinedSignInIsDenied() throws Exception {
        deviceCode(900, 5);
        server.on("/token", 200, "{\"error\":\"access_denied\"}");
        OAuthDeviceFlow flow = flow();

        OAuthException e = assertThrows(OAuthException.class, () -> flow.poll(flow.start(), () -> false));

        assertEquals(OAuthException.Reason.DENIED, e.reason());
    }

    @Test
    void cancellingStopsPollingAtTheNextCheck() throws Exception {
        deviceCode(900, 5);
        server.on("/token", 200, "{\"error\":\"authorization_pending\"}");
        AtomicBoolean cancelled = new AtomicBoolean();
        OAuthDeviceFlow flow = new OAuthDeviceFlow(spec(), new OAuthTokenClient(clock), d -> {
            sleeps.add(d);
            if (sleeps.size() == 3) cancelled.set(true);
        });

        OAuthException e = assertThrows(OAuthException.class, () -> flow.poll(flow.start(), cancelled::get));

        assertEquals(OAuthException.Reason.CANCELLED, e.reason());
        assertEquals(2, server.requestsTo("/token").size(), "no poll after the cancel");
    }

    @Test
    void interruptingTheWaitCancels() throws Exception {
        deviceCode(900, 5);
        OAuthDeviceFlow flow = new OAuthDeviceFlow(spec(), new OAuthTokenClient(clock), d -> {
            throw new InterruptedException();
        });

        OAuthException e = assertThrows(OAuthException.class, () -> flow.poll(flow.start(), () -> false));

        assertEquals(OAuthException.Reason.CANCELLED, e.reason());
        assertTrue(Thread.interrupted(), "the interrupt is kept for the caller (and cleared here)");
    }

    /** A GitLab older than 17.2 has no device endpoint; the UI then falls back to loopback. */
    @Test
    void aServerWithoutTheDeviceEndpointIsUnsupported() {
        OAuthException e = assertThrows(OAuthException.class, () -> flow().start());

        assertEquals(OAuthException.Reason.UNSUPPORTED, e.reason());
    }

    @Test
    void aDisabledDeviceFlowIsAProviderError() {
        server.on("/device", 400, "{\"error\":\"device_flow_disabled\"}");

        OAuthException e = assertThrows(OAuthException.class, () -> flow().start());

        assertEquals("device_flow_disabled", e.error());
    }

    /** A clock the sleeper moves forward. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
