package com.jfx.git.core;

import java.net.http.HttpResponse;

/**
 * Why a provider call failed, in the only terms the caller can act on.
 *
 * <p>The message alone is not enough for the one decision that matters: whether to throw
 * a remembered token away. Every failure used to be treated as a rejection, so launching
 * a laptop offline - or hitting a rate limit, or catching a provider's five-minute outage
 * - erased a perfectly good saved login and the user had to go and make another token.
 *
 * <p>Only {@link #AUTH_REJECTED} says the credential itself is no good, and only a 401
 * earns it. A 403 does not: on GitHub it is both "you lack permission for this one
 * endpoint" and "you are rate limited", and neither means the token is dead.
 */
public enum ApiFailure {

    /** The provider said the credential is not valid. The only reason to forget a token. */
    AUTH_REJECTED,

    /** Rate limited - 429, or a 403 whose remaining-quota header says zero. */
    RATE_LIMITED,

    /** The provider is broken or unavailable right now: 5xx. */
    SERVER,

    /** Nothing was reached at all - offline, DNS, TLS, timeout, interrupted. */
    TRANSPORT,

    /** Anything else: a 404, a permissions 403, a body that would not parse. */
    OTHER;

    /** Whether a remembered credential should survive this. */
    public boolean keepsCredentials() {
        return this != AUTH_REJECTED;
    }

    /** Whether retrying later could plausibly work without the user doing anything. */
    public boolean isTemporary() {
        return this == RATE_LIMITED || this == SERVER || this == TRANSPORT;
    }

    /**
     * Classifies a response the provider actually sent.
     *
     * <p>Shared by all three clients: the header names differ, {@link ApiErrors} already
     * knows them, and the judgement about what each status means does not differ at all.
     */
    public static ApiFailure of(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 401) return AUTH_REJECTED;
        if (ApiErrors.isRateLimited(response)) return RATE_LIMITED;
        if (status >= 500) return SERVER;
        return OTHER;
    }
}
