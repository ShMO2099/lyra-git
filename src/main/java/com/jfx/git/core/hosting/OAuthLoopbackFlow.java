package com.jfx.git.core.hosting;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.UrlOpener;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The authorization-code grant with a loopback redirect (RFC 8252): the browser opens the
 * provider's authorize page, and the provider redirects it back to a one-shot HTTP server
 * on this machine with the code.
 *
 * <p>For providers without a device grant - Bitbucket, Gitea/Forgejo, GitLab older than
 * 17.2. A port on the loopback interface is reachable by every process on this machine,
 * so the server is deliberately narrow:
 *
 * <ul>
 *   <li>Bound to {@code 127.0.0.1} only, never a wildcard address, so nothing on the
 *       network can reach it.</li>
 *   <li>Answers one path, {@code /callback}; everything else is a 404 and the wait goes on,
 *       so a browser's {@code /favicon.ico} request cannot end the sign-in.</li>
 *   <li>Accepts only a redirect whose {@code state} matches, compared in constant time; a
 *       mismatched one is refused and the wait goes on, so a stale tab or a forged request
 *       cannot end it either.</li>
 *   <li>Single use: after the first accepted redirect, it answers nothing else.</li>
 *   <li>Replies with fixed pages that never repeat a request parameter, so nothing a
 *       request carries is reflected into a page the browser renders.</li>
 *   <li>Always stopped, whether the sign-in succeeded, failed, timed out or was
 *       cancelled.</li>
 * </ul>
 *
 * <p>The browser's reply waits for the code exchange, so "Signed in" is only shown once it
 * is true.
 */
public final class OAuthLoopbackFlow {

    private static final Logger LOGGER = Logger.getLogger(OAuthLoopbackFlow.class.getName());

    /** How long the user gets to finish in the browser. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** How often waiting checks for cancellation. */
    private static final long POLL_MILLIS = 100;
    /** How long the browser's request is held open while the code is exchanged. */
    private static final long REPLY_WAIT_SECONDS = 30;

    static final String SUCCESS_PAGE = page("Signed in",
            "Signed in. You can close this tab and return to Lyra.");
    static final String FAILURE_PAGE = page("Sign-in failed",
            "Sign-in did not complete. Return to Lyra for details.");
    static final String REFUSED_PAGE = page("Sign-in not accepted",
            "This sign-in response was not expected and was ignored. Return to Lyra.");
    static final String NOT_FOUND_PAGE = page("Not found", "Not found.");

    private final OAuthSpec spec;
    private final OAuthTokenClient tokens;
    private final Consumer<URI> browser;
    private final Duration timeout;

    private volatile InetSocketAddress address;

    public OAuthLoopbackFlow(OAuthSpec spec) {
        this(spec, new OAuthTokenClient(), OAuthLoopbackFlow::openInBrowser, DEFAULT_TIMEOUT);
    }

    /**
     * @param browser opens the authorize page; the tests' fake browser follows the redirect
     *                itself
     * @param timeout how long to wait for the redirect
     */
    public OAuthLoopbackFlow(OAuthSpec spec, OAuthTokenClient tokens, Consumer<URI> browser, Duration timeout) {
        if (spec.flow() != OAuthSpec.Flow.LOOPBACK) {
            throw new IllegalArgumentException("Not a loopback-flow spec: " + spec);
        }
        this.spec = spec;
        this.tokens = tokens;
        this.browser = browser;
        this.timeout = timeout;
    }

    /** Where the server is listening while {@link #run} waits; null before and after. */
    InetSocketAddress address() {
        return address;
    }

    /**
     * Opens the authorize page, waits for the redirect and exchanges the code. Blocking; run
     * it off the FX thread.
     *
     * @param cancelled checked every {@value #POLL_MILLIS} ms while waiting; interrupting
     *                  the thread cancels too
     * @throws OAuthException {@link OAuthException.Reason#PORT_IN_USE} when the provider's
     *                        fixed port is taken, {@link OAuthException.Reason#TIMED_OUT},
     *                        {@link OAuthException.Reason#CANCELLED}, {@link
     *                        OAuthException.Reason#DENIED} when the user declined, or any
     *                        failure of the code exchange
     */
    public ProviderCredential run(BooleanSupplier cancelled) throws OAuthException {
        String state = Pkce.state();
        String verifier = spec.pkce() ? Pkce.verifier() : null;
        BlockingQueue<Callback> arrivals = new LinkedBlockingQueue<>();
        AtomicBoolean used = new AtomicBoolean();

        HttpServer server = bind();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "oauth-loopback");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, state, used, arrivals));
        Callback callback = null;
        try {
            server.start();
            address = server.getAddress();
            String redirectUri = OAuthSpec.redirectUri(address.getPort());
            browser.accept(authorizeUri(redirectUri, state, verifier));

            callback = await(arrivals, cancelled);
            if (callback.error() != null) {
                callback.reply().complete(false);
                if ("access_denied".equals(callback.error())) {
                    throw new OAuthException(OAuthException.Reason.DENIED, "access_denied", 0,
                            "The sign-in was declined.", null);
                }
                throw OAuthTokenClient.providerError(callback.error(), null, 0);
            }
            ProviderCredential credential = tokens.exchange(spec, callback.code(), verifier, redirectUri);
            callback.reply().complete(true);
            return credential;
        } finally {
            if (callback != null) callback.reply().complete(false);
            // Anything still queued - a second redirect racing the first - gets its page too.
            for (Callback late : arrivals) late.reply().complete(false);
            // A second or so for the browser's page to be written; nothing else is pending.
            server.stop(callback != null ? 1 : 0);
            executor.shutdownNow();
            address = null;
        }
    }

    /** The authorize page's address for this attempt. */
    URI authorizeUri(String redirectUri, String state, String verifier) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", spec.clientId());
        query.put("redirect_uri", redirectUri);
        if (!spec.scopes().isBlank()) query.put("scope", spec.scopes());
        query.put("state", state);
        if (verifier != null) {
            query.put("code_challenge", Pkce.challenge(verifier));
            query.put("code_challenge_method", "S256");
        }
        StringBuilder out = new StringBuilder(spec.authorizeUri().toString());
        out.append(spec.authorizeUri().getRawQuery() == null ? '?' : '&');
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) out.append('&');
            first = false;
            out.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(out.toString());
    }

    private HttpServer bind() throws OAuthException {
        InetAddress loopback;
        try {
            loopback = InetAddress.getByAddress("127.0.0.1", new byte[]{127, 0, 0, 1});
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try {
            return HttpServer.create(new InetSocketAddress(loopback, spec.fixedPort()), 0);
        } catch (BindException e) {
            throw new OAuthException(OAuthException.Reason.PORT_IN_USE, null, 0,
                    "Port " + spec.fixedPort() + " on this computer is already in use, and the provider"
                            + " only returns sign-ins to that port. Close whatever is using it and try"
                            + " again, or use a token instead.", e);
        } catch (IOException e) {
            throw new OAuthException(OAuthException.Reason.TRANSPORT, null, 0,
                    "Could not start the local sign-in listener: " + e.getClass().getSimpleName(), e);
        }
    }

    private Callback await(BlockingQueue<Callback> arrivals, BooleanSupplier cancelled) throws OAuthException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new OAuthException(OAuthException.Reason.CANCELLED, "Sign-in cancelled.");
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new OAuthException(OAuthException.Reason.TIMED_OUT,
                        "The browser did not return from the sign-in page in time. Start again.");
            }
            try {
                Callback c = arrivals.poll(Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(left) + 1),
                        TimeUnit.MILLISECONDS);
                if (c != null) return c;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OAuthException(OAuthException.Reason.CANCELLED, "Sign-in cancelled.");
            }
        }
    }

    /** Runs on the server's thread, once per browser request. */
    private void handle(HttpExchange exchange, String state, AtomicBoolean used, BlockingQueue<Callback> arrivals)
            throws IOException {
        try (exchange) {
            if (!OAuthSpec.CALLBACK_PATH.equals(exchange.getRequestURI().getPath())) {
                reply(exchange, 404, NOT_FOUND_PAGE);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                reply(exchange, 405, NOT_FOUND_PAGE);
                return;
            }
            Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
            if (!Pkce.matches(state, params.get("state"))) {
                LOGGER.log(Level.FINE, "Loopback sign-in: refused a redirect with the wrong state");
                reply(exchange, 400, REFUSED_PAGE);
                return;
            }
            String code = params.get("code");
            String error = params.get("error");
            if ((code == null || code.isBlank()) && (error == null || error.isBlank())) {
                reply(exchange, 400, REFUSED_PAGE);
                return;
            }
            if (!used.compareAndSet(false, true)) {
                reply(exchange, 400, REFUSED_PAGE);
                return;
            }
            Callback callback = new Callback(code, error == null || error.isBlank() ? null : error,
                    new CompletableFuture<>());
            arrivals.add(callback);
            boolean ok;
            try {
                ok = callback.reply().get(REPLY_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                ok = false;
            }
            reply(exchange, ok ? 200 : 400, ok ? SUCCESS_PAGE : FAILURE_PAGE);
        }
    }

    private static void reply(HttpExchange exchange, int status, String page) throws IOException {
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        // The callback URL carries the code; nothing on the page loads anything, but if it
        // ever did, the URL must not travel with it.
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // A malformed escape; the parameter is simply absent.
            }
        }
        return out;
    }

    private static String page(String title, String text) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + title + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:4em auto;max-width:32em;"
                + "padding:0 1em;color:#222}</style></head><body><p>" + text + "</p></body></html>";
    }

    /** The default browser: the system's. Its failure is logged without the address's query. */
    private static void openInBrowser(URI uri) {
        GitResult result = UrlOpener.open(uri.toString());
        if (!result.isOk()) {
            LOGGER.log(Level.WARNING, "Could not open the browser for sign-in at " + uri.getHost());
        }
    }

    /** One accepted redirect, and the page its browser gets once the exchange is decided. */
    private record Callback(String code, String error, CompletableFuture<Boolean> reply) {
    }
}
