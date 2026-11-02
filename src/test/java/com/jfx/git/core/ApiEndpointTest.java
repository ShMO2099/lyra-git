package com.jfx.git.core;

import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.gitlab.GitLabClient;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which URI each provider client actually asks for.
 *
 * <h2>Why this exists as its own class</h2>
 * Every client built its request with {@code baseUri.resolve(path)}, and every path starts
 * with {@code /} - which RFC 3986 says replaces the base's path rather than extending it.
 * GitLab therefore asked {@code gitlab.com/projects} (the web UI) instead of
 * {@code gitlab.com/api/v4/projects}, Bitbucket dropped its {@code /2.0}, and a GitHub
 * Enterprise host lost {@code /api/v3}. Two integrations had, on that evidence, never
 * worked against their real APIs.
 *
 * <p>The existing client tests could not catch it, and would not catch a recurrence,
 * because they serve from {@code http://localhost:<port>} - a base with <em>no path</em>,
 * the one shape where {@code resolve} does what it looks like it does. The fixture
 * differed from production in exactly the dimension that mattered.
 *
 * <p>So these tests mount the server under a path, the way every real API root is, and
 * assert on the URI the server was actually asked for. That is one layer lower than
 * "did the call succeed", which is where the defect lived.
 */
class ApiEndpointTest {

    private HttpServer server;
    /** Every path the server was asked for, in order. */
    private final List<String> requested = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().toString());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private URI base(String path) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + path);
    }

    /** The first path the server saw, which is what the client actually built. */
    private String firstRequest() {
        assertFalse(requested.isEmpty(), "the client never reached the server");
        return requested.get(0);
    }

    @Test
    void gitLabKeepsTheApiVersionFromItsBase() {
        new GitLabClient(base("/api/v4")).fetchAuthenticatedUser("token");

        assertTrue(firstRequest().startsWith("/api/v4/"),
                () -> "GitLab dropped its API root: " + firstRequest());
    }

    @Test
    void bitbucketKeepsTheApiVersionFromItsBase() {
        new BitbucketClient(base("/2.0")).fetchAuthenticatedUser("token");

        assertTrue(firstRequest().startsWith("/2.0/"),
                () -> "Bitbucket dropped its API root: " + firstRequest());
    }

    /** The shape a GitHub Enterprise Server host has, which public github.com does not. */
    @Test
    void gitHubEnterpriseKeepsTheApiPathFromItsBase() {
        new GitHubClient(base("/api/v3")).fetchAuthenticatedUser("token");

        assertTrue(firstRequest().startsWith("/api/v3/"),
                () -> "GitHub Enterprise dropped its API root: " + firstRequest());
    }

    /** Public GitHub has no base path, and must keep working exactly as before. */
    @Test
    void aBaseWithoutAPathIsUnaffected() {
        new GitHubClient(base("")).fetchAuthenticatedUser("token");

        assertEquals("/user", firstRequest());
    }

    // ------------------------------------------------------------------
    // The join itself
    // ------------------------------------------------------------------

    @Test
    void theJoinExtendsTheBasePathRatherThanReplacingIt() {
        assertEquals(URI.create("https://gitlab.com/api/v4/projects?membership=true"),
                ApiErrors.endpoint(URI.create("https://gitlab.com/api/v4"),
                        "/projects?membership=true"));
        assertEquals(URI.create("https://api.bitbucket.org/2.0/workspaces"),
                ApiErrors.endpoint(URI.create("https://api.bitbucket.org/2.0"), "/workspaces"));
        assertEquals(URI.create("https://api.github.com/user"),
                ApiErrors.endpoint(URI.create("https://api.github.com"), "/user"));
    }

    /** A user pasting a host with a trailing slash must not produce a doubled one. */
    @Test
    void aTrailingSlashOnTheBaseDoesNotDoubleUp() {
        assertEquals(URI.create("https://ghe.corp/api/v3/user"),
                ApiErrors.endpoint(URI.create("https://ghe.corp/api/v3/"), "/user"));
        assertEquals(URI.create("https://ghe.corp/api/v3/user"),
                ApiErrors.endpoint(URI.create("https://ghe.corp/api/v3//"), "/user"));
    }

    /** ProviderHosts is what supplies the base in production; the two have to compose. */
    @Test
    void theHostProviderAndTheJoinAgreeOnAnEnterpriseUrl() {
        assertEquals(URI.create("https://gitlab.com/api/v4/projects"),
                ApiErrors.endpoint(ProviderHosts.GITLAB_PUBLIC, "/projects"));
        assertEquals(URI.create("https://api.github.com/user"),
                ApiErrors.endpoint(ProviderHosts.GITHUB_PUBLIC, "/user"));
    }
}
