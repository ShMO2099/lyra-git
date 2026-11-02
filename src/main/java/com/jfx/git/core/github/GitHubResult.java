package com.jfx.git.core.github;

import com.jfx.git.core.ApiFailure;

/**
 * The outcome of a GitHub API call: the value asked for, or why there is none.
 *
 * <p>Kept apart from {@link com.jfx.git.core.GitResult}: that type is a git operation's
 * outcome (an OK, a conflict with file names, or a failure message) and carries no
 * payload, which is right for git's own commands but wrong here, where a caller wants
 * the actual data - the authenticated user, the repository list - not just word that it
 * arrived.
 */
public sealed interface GitHubResult<T> {

    record Success<T>(T value) implements GitHubResult<T> {
    }

    /**
     * @param message what to tell the user - "Bad credentials" from GitHub itself, a
     *                network failure's own message, or a rate-limit notice
     * @param kind    which sort of failure it was; only {@link ApiFailure#AUTH_REJECTED}
     *                means the token itself is no good
     */
    record Failure<T>(String message, ApiFailure kind) implements GitHubResult<T> {

        /** A failure nothing has classified, which is treated as {@link ApiFailure#OTHER}. */
        public Failure(String message) {
            this(message, ApiFailure.OTHER);
        }
    }

    static <T> GitHubResult<T> ok(T value) {
        return new Success<>(value);
    }

    static <T> GitHubResult<T> failed(String message) {
        return new Failure<>(message, ApiFailure.OTHER);
    }

    static <T> GitHubResult<T> failed(String message, ApiFailure kind) {
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
     * than the message: "Could not reach GitHub" and "Bad credentials" are both failures
     * and only one of them is the token's fault.
     */
    default ApiFailure failureKind() {
        return this instanceof Failure<T> f ? f.kind() : null;
    }
}
