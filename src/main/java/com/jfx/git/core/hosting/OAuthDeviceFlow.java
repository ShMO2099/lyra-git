package com.jfx.git.core.hosting;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The device authorization grant (RFC 8628): the user approves the sign-in by typing a
 * short code into the provider's own page, while this polls the token endpoint.
 *
 * <p>Used by GitHub and GitLab. Nothing listens on this machine, so there is no port to
 * collide with and no redirect URI to register per server - which is why it is preferred
 * wherever the provider offers it.
 *
 * <h2>Polling</h2>
 * The token endpoint answers {@code authorization_pending} until the user approves,
 * {@code slow_down} when polled too often (the interval then grows by five seconds, and
 * stays grown, as section 3.5 requires), {@code expired_token} when the code ran out and
 * {@code access_denied} when the user declined. GitHub sends these with HTTP 200 and GitLab
 * with HTTP 400; {@link OAuthTokenClient} reads the {@code error} field either way.
 *
 * <p>Waiting goes through a {@link Sleeper} and time through a {@link Clock}, so the tests
 * run the whole exchange - pending, slow down, success - in milliseconds.
 */
public final class OAuthDeviceFlow {

    /** How waiting between polls is done; replaced in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** RFC 8628 section 3.2: the interval when the server names none. */
    static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    /** RFC 8628 section 3.5: what {@code slow_down} adds to the interval. */
    static final Duration SLOW_DOWN_STEP = Duration.ofSeconds(5);
    /** How long a code lives when the server does not say; GitHub's and GitLab's own value. */
    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(15);

    /**
     * What the user needs to approve the sign-in.
     *
     * @param deviceCode              what this polls with; secret, never shown
     * @param userCode                what the user types into the provider's page
     * @param verificationUri         the page where the code is typed
     * @param verificationUriComplete the same page with the code already filled in, when the
     *                                provider offers one (GitLab does, GitHub does not); null
     *                                otherwise
     * @param expiresAt               when the code stops working
     * @param interval                how long to wait between polls, as the server asked
     */
    public record DeviceCode(String deviceCode, String userCode, URI verificationUri,
                             URI verificationUriComplete, Instant expiresAt, Duration interval) {

        /** The page to open in the browser: the pre-filled one when there is one. */
        public URI browserUri() {
            return verificationUriComplete != null ? verificationUriComplete : verificationUri;
        }

        /** Never shows the device code, which is as good as the token until it expires. */
        @Override
        public String toString() {
            return "DeviceCode[" + userCode + " at " + verificationUri + ", expires " + expiresAt + "]";
        }
    }

    private final OAuthSpec spec;
    private final OAuthTokenClient tokens;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Gson gson = new Gson();

    public OAuthDeviceFlow(OAuthSpec spec) {
        this(spec, new OAuthTokenClient(), Thread::sleep);
    }

    /** @param tokens also supplies the clock, so expiry and token lifetimes agree */
    public OAuthDeviceFlow(OAuthSpec spec, OAuthTokenClient tokens, Sleeper sleeper) {
        if (spec.flow() != OAuthSpec.Flow.DEVICE) {
            throw new IllegalArgumentException("Not a device-flow spec: " + spec);
        }
        this.spec = spec;
        this.tokens = tokens;
        this.clock = tokens.clock();
        this.sleeper = sleeper;
    }

    /**
     * Asks the provider for a device code.
     *
     * @throws OAuthException {@link OAuthException.Reason#UNSUPPORTED} when the server has no
     *                        device endpoint - a GitLab older than 17.2, which then needs
     *                        the loopback flow instead
     */
    public DeviceCode start() throws OAuthException {
        Map<String, String> form = new LinkedHashMap<>();
        if (!spec.scopes().isBlank()) form.put("scope", spec.scopes());
        String body;
        try {
            body = tokens.post(spec, spec.deviceUri(), form);
        } catch (OAuthException e) {
            if (e.reason() == OAuthException.Reason.HTTP && (e.httpStatus() == 404 || e.httpStatus() == 405)) {
                throw new OAuthException(OAuthException.Reason.UNSUPPORTED, null, e.httpStatus(),
                        spec.deviceUri().getHost() + " does not offer device sign-in.", e);
            }
            throw e;
        }

        DeviceReply reply;
        try {
            reply = gson.fromJson(body, DeviceReply.class);
        } catch (JsonParseException e) {
            reply = null;
        }
        if (reply == null || blank(reply.deviceCode()) || blank(reply.userCode()) || blank(reply.verificationUri())) {
            throw new OAuthException(OAuthException.Reason.MALFORMED,
                    "The sign-in server's reply did not contain a device code.");
        }
        URI verification;
        URI complete;
        try {
            verification = URI.create(reply.verificationUri());
            complete = blank(reply.verificationUriComplete()) ? null : URI.create(reply.verificationUriComplete());
        } catch (IllegalArgumentException e) {
            throw new OAuthException(OAuthException.Reason.MALFORMED,
                    "The sign-in server sent a verification address that is not a valid URL.");
        }
        Duration lifetime = reply.expiresIn() == null || reply.expiresIn() <= 0
                ? DEFAULT_LIFETIME : Duration.ofSeconds(reply.expiresIn());
        Duration interval = reply.interval() == null || reply.interval() <= 0
                ? DEFAULT_INTERVAL : Duration.ofSeconds(reply.interval());
        return new DeviceCode(reply.deviceCode(), reply.userCode(), verification, complete,
                clock.instant().plus(lifetime), interval);
    }

    /**
     * Polls until the user approves, declines, the code expires or {@code cancelled} says
     * stop. Blocking; run it off the FX thread.
     *
     * <p>{@code cancelled} is checked before and after every wait. Interrupting the polling
     * thread - which is what a JavaFX {@code Task.cancel()} does - ends a wait at once and
     * also counts as cancelling.
     *
     * @throws OAuthException {@link OAuthException.Reason#DENIED}, {@link
     *                        OAuthException.Reason#EXPIRED}, {@link OAuthException.Reason#CANCELLED},
     *                        or any failure of the token request itself
     */
    public ProviderCredential poll(DeviceCode code, BooleanSupplier cancelled) throws OAuthException {
        Duration interval = code.interval();
        while (true) {
            checkCancelled(cancelled);
            if (!clock.instant().isBefore(code.expiresAt())) throw expired();
            try {
                sleeper.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw cancelledException();
            }
            checkCancelled(cancelled);
            if (!clock.instant().isBefore(code.expiresAt())) throw expired();
            try {
                return tokens.pollDevice(spec, code.deviceCode());
            } catch (OAuthException e) {
                String error = e.error();
                if ("authorization_pending".equals(error)) continue;
                if ("slow_down".equals(error)) {
                    interval = interval.plus(SLOW_DOWN_STEP);
                    continue;
                }
                throw e;
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws OAuthException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelledException();
    }

    private static OAuthException cancelledException() {
        return new OAuthException(OAuthException.Reason.CANCELLED, "Sign-in cancelled.");
    }

    private static OAuthException expired() {
        return new OAuthException(OAuthException.Reason.EXPIRED,
                "The sign-in code expired before it was approved. Start again.");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** RFC 8628 section 3.2. */
    private record DeviceReply(@SerializedName("device_code") String deviceCode,
                               @SerializedName("user_code") String userCode,
                               @SerializedName("verification_uri") String verificationUri,
                               @SerializedName("verification_uri_complete") String verificationUriComplete,
                               @SerializedName("expires_in") Long expiresIn,
                               Long interval) {
    }
}
