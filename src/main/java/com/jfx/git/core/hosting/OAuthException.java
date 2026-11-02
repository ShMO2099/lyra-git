package com.jfx.git.core.hosting;

/**
 * Why an OAuth sign-in, refresh or revocation did not succeed.
 *
 * <p>Checked, because every caller has a decision to make and the decisions differ: a
 * refresh that fails with {@code invalid_grant} means the grant is gone and the provider
 * must be disconnected, while one that fails in transit means try again later and keep
 * everything. A cancelled sign-in is not an error at all to show. {@link #reason()} is what
 * those decisions read; {@link #getMessage()} is what the user reads.
 *
 * <p>No message ever contains a token, a code, a verifier or a client secret. Messages end
 * up in dialogs, status bars and logs, and the provider's own {@code error_description} is
 * passed through {@link OAuthTokenClient}'s redaction before it gets here.
 */
public final class OAuthException extends Exception {

    /** What went wrong, in terms a caller can branch on. */
    public enum Reason {
        /** The provider answered with an OAuth {@code error}; see {@link #error()}. */
        PROVIDER,
        /** The user declined on the provider's page ({@code access_denied}). */
        DENIED,
        /** The device code or authorization ran out before the user approved it. */
        EXPIRED,
        /** Nothing came back to the loopback server in time. */
        TIMED_OUT,
        /** The caller cancelled. */
        CANCELLED,
        /** The fixed loopback port the provider's registration names is taken. */
        PORT_IN_USE,
        /** The server does not offer this flow - an older GitLab without the device grant. */
        UNSUPPORTED,
        /** The server answered with an HTTP error and no OAuth error; see {@link #httpStatus()}. */
        HTTP,
        /** Nothing was reached: offline, DNS, TLS, timeout. */
        TRANSPORT,
        /** The server answered, but not with anything a token can be read from. */
        MALFORMED
    }

    private final Reason reason;
    private final String error;
    private final int httpStatus;

    OAuthException(Reason reason, String message) {
        this(reason, null, 0, message, null);
    }

    OAuthException(Reason reason, String error, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.error = error;
        this.httpStatus = httpStatus;
    }

    public Reason reason() {
        return reason;
    }

    /** The OAuth {@code error} code the provider sent, such as {@code invalid_grant}; null if none. */
    public String error() {
        return error;
    }

    /** The HTTP status of the response this came from; 0 when there was none. */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * Whether the grant itself is no longer valid: the refresh token was revoked, expired
     * or already used. The one failure after which the stored credential must be dropped,
     * because retrying it can never succeed.
     */
    public boolean isInvalidGrant() {
        return "invalid_grant".equals(error);
    }

    /** Whether trying the same thing again later could work without the user doing anything. */
    public boolean isTemporary() {
        return reason == Reason.TRANSPORT || (reason == Reason.HTTP && httpStatus >= 500);
    }
}
