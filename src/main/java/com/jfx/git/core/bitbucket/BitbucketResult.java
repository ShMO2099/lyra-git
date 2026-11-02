package com.jfx.git.core.bitbucket;

import com.jfx.git.core.ApiFailure;

/**
 * The outcome of a Bitbucket API call: the value asked for, or why there is none.
 * Mirrors {@link com.jfx.git.core.github.GitHubResult} exactly - see {@link
 * com.jfx.git.core.gitlab.GitLabResult} for why duplicating this trivial shape per
 * provider package is the deliberate choice here.
 */
public sealed interface BitbucketResult<T> {

    record Success<T>(T value) implements BitbucketResult<T> {
    }

    /**
     * @param message what to tell the user - Bitbucket's own error message, a network
     *                failure's own message, or a rate-limit notice
     * @param kind    which sort of failure it was; only {@link ApiFailure#AUTH_REJECTED}
     *                means the token itself is no good
     */
    record Failure<T>(String message, ApiFailure kind) implements BitbucketResult<T> {

        /** A failure nothing has classified, which is treated as {@link ApiFailure#OTHER}. */
        public Failure(String message) {
            this(message, ApiFailure.OTHER);
        }
    }

    static <T> BitbucketResult<T> ok(T value) {
        return new Success<>(value);
    }

    static <T> BitbucketResult<T> failed(String message) {
        return new Failure<>(message, ApiFailure.OTHER);
    }

    static <T> BitbucketResult<T> failed(String message, ApiFailure kind) {
        return new Failure<>(message, kind);
    }

    default boolean isOk() {
        return this instanceof Success<T>;
    }

    /** The value if this is a success, or {@code null} otherwise. */
    default T orNull() {
        return this instanceof Success<T> s ? s.value() : null;
    }

    /** The failure message if this is a failure, or {@code null} otherwise. */
    default String messageOrNull() {
        return this instanceof Failure<T> f ? f.message() : null;
    }

    /**
     * How this failed, or {@code null} when it did not.
     *
     * <p>Callers deciding whether to discard a stored credential must read this rather
     * than the message: being offline and being rejected are both failures, and only one
     * of them is the token's fault.
     */
    default ApiFailure failureKind() {
        return this instanceof Failure<T> f ? f.kind() : null;
    }
}
