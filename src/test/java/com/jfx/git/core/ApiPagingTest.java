package com.jfx.git.core;

import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.gitlab.GitLabClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R12: provider lists follow "next page" to the end, and only on the same server.
 * Each client is run against a local HTTP server that splits its answer over two pages.
 */
class ApiPagingTest {

    private HttpServer server;
    private URI base;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        base = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, String body, String link) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (link != null) exchange.getResponseHeaders().add("Link", link);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void theLinkHeaderIsParsedAndOnlyFollowedOnTheSameServer() {
        URI api = URI.create("https://api.github.com");
        String header = "<https://api.github.com/user/repos?page=2>; rel=\"next\", "
                + "<https://api.github.com/user/repos?page=5>; rel=\"last\"";

        assertEquals(URI.create("https://api.github.com/user/repos?page=2"),
                ApiPaging.nextFromLinkHeader(header, api));
        assertNull(ApiPaging.nextFromLinkHeader("<https://evil.example/steal>; rel=\"next\"", api),
                "the next request carries the token; another host must not get it");
        assertNull(ApiPaging.nextFromLinkHeader("<http://api.github.com/x>; rel=\"next\"", api),
                "nor a downgrade to plain http");
        assertNull(ApiPaging.nextFromLinkHeader("<https://api.github.com/x>; rel=\"last\"", api));
    }

    @Test
    void gitHubRepositoriesArriveFromEveryPage() throws Exception {
        server.createContext("/user/repos", exchange -> {
            requests.incrementAndGet();
            if (exchange.getRequestURI().getQuery().contains("page=2")) {
                reply(exchange, "[{\"full_name\":\"o/two\"}]", null);
            } else {
                reply(exchange, "[{\"full_name\":\"o/one\"}]",
                        "<" + base + "/user/repos?per_page=100&page=2>; rel=\"next\"");
            }
        });
        server.start();

        var result = new GitHubClient(base).listRepositories("token");

        assertTrue(result.isOk(), result::messageOrNull);
        assertEquals(2, result.orNull().size());
        assertEquals(2, requests.get());
    }

    @Test
    void gitLabProjectsArriveFromEveryPage() throws Exception {
        server.createContext("/projects", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("page=2")) {
                reply(exchange, "[{\"id\":2}]", null);
            } else {
                reply(exchange, "[{\"id\":1}]", "<" + base + "/projects?page=2>; rel=\"next\"");
            }
        });
        server.start();

        var result = new GitLabClient(base).listProjects("token");

        assertTrue(result.isOk(), result::messageOrNull);
        assertEquals(2, result.orNull().size());
    }

    @Test
    void bitbucketPullRequestsFollowTheNextField() throws Exception {
        server.createContext("/repositories/ws/repo/pullrequests", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("page=2")) {
                reply(exchange, "{\"values\":[{\"id\":2}]}", null);
            } else {
                reply(exchange, "{\"values\":[{\"id\":1}],\"next\":\""
                        + base + "/repositories/ws/repo/pullrequests?page=2\"}", null);
            }
        });
        server.start();

        var result = new BitbucketClient(base).listPullRequests("token", "ws", "repo");

        assertTrue(result.isOk(), result::messageOrNull);
        assertEquals(2, result.orNull().size());
    }

    /** A combined status must see a failure on page two. */
    @Test
    void gitHubCheckRunsAreCombinedAcrossPages() throws Exception {
        server.createContext("/repos/o/r/commits/abc/check-runs", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("page=2")) {
                reply(exchange, "{\"total_count\":2,\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"failure\"}]}", null);
            } else {
                reply(exchange, "{\"total_count\":2,\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"success\"}]}",
                        "<" + base + "/repos/o/r/commits/abc/check-runs?page=2>; rel=\"next\"");
            }
        });
        server.createContext("/repos/o/r/commits/abc/status", exchange ->
                reply(exchange, "{\"state\":\"success\",\"total_count\":0}", null));
        server.start();

        var result = new GitHubClient(base).getCheckStatus("token", "o", "r", "abc");

        assertTrue(result.isOk(), result::messageOrNull);
        assertEquals(BuildStatus.FAILED, result.orNull());
    }
}
