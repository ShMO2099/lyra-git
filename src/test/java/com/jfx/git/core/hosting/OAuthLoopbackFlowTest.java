package com.jfx.git.core.hosting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OAuthLoopbackFlow} with a fake browser: the injected opener reads the authorize
 * URL the way a provider would, then requests the redirect URL the way the browser would
 * after the user approves. The token endpoint is a {@link FakeOAuthServer}.
 *
 * <p>The browser acts on its own thread, as a real one does: the flow is still inside the
 * opener when it is called, and the redirect's reply waits for the code exchange.
 */
@Timeout(30)
class OAuthLoopbackFlowTest {

    private FakeOAuthServer tokenServer;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<HttpResponse<String>> browserSaw = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() throws Exception {
        tokenServer = new FakeOAuthServer();
    }

    @AfterEach
    void stop() {
        tokenServer.close();
    }

    private OAuthSpec spec(int port, boolean pkce, String scopes) {
        return new OAuthSpec(OAuthSpec.Flow.LOOPBACK, null, URI.create("https://provider.test/oauth/authorize"),
                tokenServer.uri("/token"), null, scopes, "loop-client", null, port, pkce);
    }

    private OAuthLoopbackFlow flow(OAuthSpec spec, Consumer<URI> browser, Duration timeout) {
        return new OAuthLoopbackFlow(spec, new OAuthTokenClient(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                browser, timeout);
    }

    /**
     * A browser that, once given the authorize page, requests each URL {@code visits}
     * builds from it, in order, on its own thread.
     */
    private Consumer<URI> browser(AtomicReference<Map<String, String>> authorize,
                                  List<Function<Map<String, String>, String>> visits) {
        return uri -> {
            Map<String, String> query = FakeOAuthServer.form(uri.getRawQuery());
            authorize.set(query);
            CompletableFuture.runAsync(() -> {
                for (Function<Map<String, String>, String> visit : visits) {
                    try {
                        browserSaw.add(http.send(HttpRequest.newBuilder(URI.create(visit.apply(query))).build(),
                                HttpResponse.BodyHandlers.ofString()));
                    } catch (IOException | InterruptedException e) {
                        return;
                    }
                }
            });
        };
    }

    private static Function<Map<String, String>, String> callback(String code, String state) {
        return q -> q.get("redirect_uri") + "?code=" + enc(code) + "&state=" + enc(state);
    }

    private static Function<Map<String, String>, String> approve(String code) {
        return q -> q.get("redirect_uri") + "?code=" + enc(code) + "&state=" + enc(q.get("state"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void theBrowsersRedirectIsExchangedForACredential() throws Exception {
        tokenServer.on("/token", 200, "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600}");
        AtomicReference<Map<String, String>> authorize = new AtomicReference<>();

        ProviderCredential credential = flow(spec(0, true, "api read_user"),
                browser(authorize, List.of(approve("the-code"))), Duration.ofSeconds(10)).run(() -> false);

        assertEquals("at", credential.accessToken());
        assertEquals("rt", credential.refreshToken());

        Map<String, String> query = authorize.get();
        assertEquals("code", query.get("response_type"));
        assertEquals("loop-client", query.get("client_id"));
        assertEquals("api read_user", query.get("scope"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertTrue(query.get("redirect_uri").matches("http://127\\.0\\.0\\.1:\\d+/callback"), query.get("redirect_uri"));

        Map<String, String> exchanged = tokenServer.requestsTo("/token").getFirst().form();
        assertEquals("the-code", exchanged.get("code"));
        assertEquals(query.get("redirect_uri"), exchanged.get("redirect_uri"));
        assertEquals(query.get("code_challenge"), Pkce.challenge(exchanged.get("code_verifier")),
                "the verifier sent with the code is the one the challenge was made from");

        HttpResponse<String> page = awaitPages(1).getFirst();
        assertEquals(200, page.statusCode());
        assertEquals(OAuthLoopbackFlow.SUCCESS_PAGE, page.body());
        assertFalse(page.body().contains("the-code"));
        assertFalse(page.body().contains(query.get("state")));
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    }

    /** Bitbucket: no PKCE, and its scopes come from the consumer, so none are sent. */
    @Test
    void withoutPkceOrScopesTheAuthorizeUrlCarriesNeither() throws Exception {
        tokenServer.on("/token", 200, "{\"access_token\":\"at\"}");
        AtomicReference<Map<String, String>> authorize = new AtomicReference<>();

        flow(spec(0, false, ""), browser(authorize, List.of(approve("c"))), Duration.ofSeconds(10)).run(() -> false);

        assertFalse(authorize.get().containsKey("code_challenge"));
        assertFalse(authorize.get().containsKey("scope"));
        assertFalse(tokenServer.requestsTo("/token").getFirst().form().containsKey("code_verifier"));
    }

    /**
     * A redirect with the wrong state - forged, or a stale tab - is refused, and so is any
     * other path; neither ends the wait, and the real redirect still succeeds afterwards.
     */
    @Test
    void aWrongStateOrPathIsRefusedAndTheWaitGoesOn() throws Exception {
        tokenServer.on("/token", 200, "{\"access_token\":\"at\"}");
        AtomicReference<Map<String, String>> authorize = new AtomicReference<>();
        String evil = "<script>alert(1)</script>";

        ProviderCredential credential = flow(spec(0, true, "s"), browser(authorize, List.of(
                callback("forged-code", "wrong-state"),
                q -> q.get("redirect_uri").replace("/callback", "/favicon.ico"),
                q -> q.get("redirect_uri") + "?code=" + enc(evil) + "&state=" + enc(evil),
                approve("good-code"))), Duration.ofSeconds(10)).run(() -> false);

        assertEquals("at", credential.accessToken());
        List<HttpResponse<String>> pages = awaitPages(4);
        assertEquals(400, pages.get(0).statusCode());
        assertEquals(OAuthLoopbackFlow.REFUSED_PAGE, pages.get(0).body());
        assertEquals(404, pages.get(1).statusCode());
        assertEquals(400, pages.get(2).statusCode());
        assertFalse(pages.get(2).body().contains("script"), "request parameters are never echoed");
        assertEquals(200, pages.get(3).statusCode());
        assertEquals(1, tokenServer.requestsTo("/token").size(), "only the good code is exchanged");
        assertEquals("good-code", tokenServer.requestsTo("/token").getFirst().form().get("code"));
    }

    @Test
    void theServerListensOn127001OnlyAndStopsAfterwards() throws Exception {
        tokenServer.on("/token", 200, "{\"access_token\":\"at\"}");
        AtomicReference<InetSocketAddress> bound = new AtomicReference<>();
        AtomicReference<InetAddress> otherLocal = new AtomicReference<>();
        AtomicBoolean otherReachable = new AtomicBoolean();
        AtomicReference<Map<String, String>> authorize = new AtomicReference<>();
        Consumer<URI> approving = browser(authorize, List.of(approve("c")));
        OAuthLoopbackFlow[] flow = new OAuthLoopbackFlow[1];
        flow[0] = flow(spec(0, true, "s"), uri -> {
            bound.set(flow[0].address());
            InetAddress other = nonLoopbackAddress();
            otherLocal.set(other);
            if (other != null) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(other, bound.get().getPort()), 1000);
                    otherReachable.set(true);
                } catch (IOException expected) {
                    // refused: the listener is not on this interface
                }
            }
            approving.accept(uri);
        }, Duration.ofSeconds(10));

        flow[0].run(() -> false);

        assertEquals(InetAddress.getByName("127.0.0.1"), bound.get().getAddress());
        assertFalse(otherReachable.get(), "reachable on " + otherLocal.get());
        assertNull(flow[0].address());
        assertThrows(IOException.class, () -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", bound.get().getPort()), 1000);
            }
        }, "the listener is closed once the sign-in is over");
    }

    @Test
    void nothingArrivingTimesOutAndStopsTheServer() {
        AtomicReference<InetSocketAddress> bound = new AtomicReference<>();
        OAuthLoopbackFlow[] flow = new OAuthLoopbackFlow[1];
        flow[0] = flow(spec(0, true, "s"), uri -> bound.set(flow[0].address()), Duration.ofMillis(300));

        OAuthException e = assertThrows(OAuthException.class, () -> flow[0].run(() -> false));

        assertEquals(OAuthException.Reason.TIMED_OUT, e.reason());
        assertThrows(IOException.class, () -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", bound.get().getPort()), 1000);
            }
        });
    }

    @Test
    void cancellingEndsTheWait() {
        AtomicBoolean cancelled = new AtomicBoolean();
        long started = System.nanoTime();

        OAuthException e = assertThrows(OAuthException.class,
                () -> flow(spec(0, true, "s"), uri -> cancelled.set(true), Duration.ofMinutes(5))
                        .run(cancelled::get));

        assertEquals(OAuthException.Reason.CANCELLED, e.reason());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void aDeclinedSignInIsDeniedAndTheBrowserIsToldItFailed() throws Exception {
        AtomicReference<Map<String, String>> authorize = new AtomicReference<>();

        OAuthException e = assertThrows(OAuthException.class, () -> flow(spec(0, true, "s"), browser(authorize,
                        List.of(q -> q.get("redirect_uri") + "?error=access_denied&state=" + enc(q.get("state")))),
                Duration.ofSeconds(10)).run(() -> false));

        assertEquals(OAuthException.Reason.DENIED, e.reason());
        assertEquals(OAuthLoopbackFlow.FAILURE_PAGE, awaitPages(1).getFirst().body());
        assertTrue(tokenServer.requestsTo("/token").isEmpty());
    }

    /** "Signed in" is only shown once the exchange has actually worked. */
    @Test
    void aFailedExchangeShowsTheFailurePage() throws Exception {
        tokenServer.on("/token", 400, "{\"error\":\"invalid_grant\"}");
        AtomicReference<Map<String, String>> authorize = new AtomicReference<>();

        OAuthException e = assertThrows(OAuthException.class, () -> flow(spec(0, true, "s"),
                browser(authorize, List.of(approve("c"))), Duration.ofSeconds(10)).run(() -> false));

        assertTrue(e.isInvalidGrant());
        HttpResponse<String> page = awaitPages(1).getFirst();
        assertEquals(400, page.statusCode());
        assertEquals(OAuthLoopbackFlow.FAILURE_PAGE, page.body());
    }

    /** GitLab and Bitbucket redirect to one fixed port; if it is taken, say so plainly. */
    @Test
    void aTakenFixedPortIsAClearError() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = taken.getLocalPort();
            AtomicBoolean opened = new AtomicBoolean();

            OAuthException e = assertThrows(OAuthException.class,
                    () -> flow(spec(port, true, "s"), uri -> opened.set(true), Duration.ofSeconds(5))
                            .run(() -> false));

            assertEquals(OAuthException.Reason.PORT_IN_USE, e.reason());
            assertTrue(e.getMessage().contains(String.valueOf(port)), e.getMessage());
            assertTrue(e.getMessage().contains("token"), e.getMessage());
            assertFalse(opened.get(), "no browser is opened for a sign-in that cannot complete");
        }
    }

    private List<HttpResponse<String>> awaitPages(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (browserSaw.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, browserSaw.size(), "pages the browser received");
        return List.copyOf(browserSaw);
    }

    private static InetAddress nonLoopbackAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a;
                }
            }
        } catch (IOException ignored) {
            // no interfaces to try
        }
        return null;
    }
}
