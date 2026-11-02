package com.jfx.git.core;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Shared plumbing for the three provider REST clients: how a request URI is built, and
 * how the two failures that need saying properly are worded.
 *
 * <h2>Building a request URI</h2>
 * See {@link #endpoint(java.net.URI, String)} - the reason it exists is a trap the JDK
 * lays for exactly this shape of client.
 *
 * <h2>Wording two failures</h2>
 *
 * <p>GitHub, GitLab and Bitbucket each report their own errors well enough for most
 * cases, and their {@code message} field is what the user sees. Two are exceptions, and
 * they are the same two on all three services:
 *
 * <ul>
 *   <li><b>Rate limiting.</b> The provider says "API rate limit exceeded for user ID …",
 *       which omits the only thing the user can act on - when it stops. The reset time is
 *       in a header, not in the body.</li>
 *   <li><b>An expired or revoked token.</b> The provider says "Bad credentials" or
 *       "401 Unauthorized", which reads like a typo. Tokens the user pasted months ago
 *       expire on their own, and the fix is to reconnect, not to retype.</li>
 * </ul>
 *
 * <p>Kept here rather than in each client because the header names differ but the
 * judgement does not, and three copies of it would drift.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    /**
     * Joins an API base URI with a request path.
     *
     * <p>Not {@code baseUri.resolve(path)}, which is what this used to be and which is
     * wrong for every base that has a path of its own. RFC 3986 says an absolute
     * reference - and every path here starts with {@code /} - <em>replaces</em> the base's
     * path rather than extending it:
     *
     * <pre>
     * https://gitlab.com/api/v4     .resolve("/projects")  -> https://gitlab.com/projects
     * https://api.bitbucket.org/2.0 .resolve("/workspaces") -> https://api.bitbucket.org/workspaces
     * https://ghe.corp/api/v3       .resolve("/user")       -> https://ghe.corp/user
     * </pre>
     *
     * <p>So GitLab asked the web UI for JSON, Bitbucket dropped its API version, and a
     * GitHub Enterprise host lost {@code /api/v3}. Public GitHub was the only one that
     * worked, because {@code https://api.github.com} has no path to discard - which is
     * also why the tests never saw it: they serve from {@code http://localhost:<port>},
     * a base with no path, so {@code resolve} behaves there and only there.
     *
     * @param base the API root, with or without a trailing slash
     * @param path the endpoint, starting with {@code /}, query string included
     */
    public static URI endpoint(URI base, String path) {
        String root = base.toString();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return URI.create(root + (path.startsWith("/") ? path : "/" + path));
    }

    /**
     * A message for a rate-limited or unauthorised response, or {@code null} when this is
     * neither and the caller should fall back to the provider's own wording.
     *
     * @param provider what to call the service in the message, e.g. {@code "GitHub"}
     */
    public static String describe(String provider, HttpResponse<String> response) {
        if (isRateLimited(response)) {
            return provider + "'s API rate limit is used up" + resetsIn(response)
                    + ". Requests will work again after that.";
        }
        if (response.statusCode() == 401) {
            return provider + " rejected the token. Access tokens expire and can be revoked"
                    + " - reconnect the integration to supply a new one.";
        }
        return null;
    }

    /**
     * Whether this is the rate limiter rather than a permissions problem.
     *
     * <p>Both arrive as 403 on GitHub, and only a {@code …RateLimit-Remaining: 0} header
     * tells them apart - calling a permissions failure a rate limit would send the user
     * off to wait for something that is never going to change. 429 is the secondary-limit
     * response and needs no header to be unambiguous.
     */
    static boolean isRateLimited(HttpResponse<String> response) {
        if (response.statusCode() == 429) return true;
        if (response.statusCode() != 403) return false;
        return header(response, "X-RateLimit-Remaining", "RateLimit-Remaining")
                .map("0"::equals).orElse(false);
    }

    /**
     * " for another 4 minutes", from whichever reset header the provider sent, or an
     * empty string when it sent none.
     *
     * <p>{@code Retry-After} is a duration; the {@code …-Reset} headers are an absolute
     * epoch second. Both end up as the same sentence.
     */
    private static String resetsIn(HttpResponse<String> response) {
        OptionalLong retryAfter = response.headers().firstValueAsLong("Retry-After");
        long seconds;
        if (retryAfter.isPresent()) {
            seconds = retryAfter.getAsLong();
        } else {
            OptionalLong reset = response.headers().firstValueAsLong("X-RateLimit-Reset")
                    .isPresent()
                    ? response.headers().firstValueAsLong("X-RateLimit-Reset")
                    : response.headers().firstValueAsLong("RateLimit-Reset");
            if (reset.isEmpty()) return "";
            seconds = reset.getAsLong() - Instant.now().getEpochSecond();
        }

        if (seconds <= 0) return " (just now)";
        if (seconds < 60) return " for another " + seconds + " second" + (seconds == 1 ? "" : "s");
        long minutes = (seconds + 59) / 60;
        return " for another " + minutes + " minute" + (minutes == 1 ? "" : "s");
    }

    private static java.util.Optional<String> header(HttpResponse<String> response, String... names) {
        for (String name : names) {
            var found = response.headers().firstValue(name);
            if (found.isPresent()) return found;
        }
        return java.util.Optional.empty();
    }
}
