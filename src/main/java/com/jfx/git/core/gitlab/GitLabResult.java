package com.jfx.git.core.gitlab;

import com.jfx.git.core.ApiFailure;

/**
 * The outcome of a GitLab API call: the value asked for, or why there is none. Mirrors
 * {@link com.jfx.git.core.github.GitHubResult} exactly - a trivial enough shape that
 * duplicating it keeps this package fully self-contained, the same tradeoff the rest
 * of this package makes rather than sharing types across providers.
 */
public sealed interface GitLabResult<T> {

    record Success<T>(T value) implements GitLabResult<T> {
    }

    /**
     * @param message what to tell the user - GitLab's own error message, a network
     *                failure's own message, or a rate-limit notice
     * @param kind    which sort of failure it was; only {@link ApiFailure#AUTH_REJECTED}
     *                means the token itself is no good
     */
    record Failure<T>(String message, ApiFailure kind) implements GitLabResult<T> {

        /** A failure nothing has classified, which is treated as {@link ApiFailure#OTHER}. */
        public Failure(String message) {
            this(message, ApiFailure.OTHER);
        }
    }

    static <T> GitLabResult<T> ok(T value) {
        return new Success<>(value);
    }

    static <T> GitLabResult<T> failed(String message) {
        return new Failure<>(message, ApiFailure.OTHER);
    }

    static <T> GitLabResult<T> failed(String message, ApiFailure kind) {
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
