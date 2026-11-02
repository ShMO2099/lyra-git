package com.jfx.git.core;

import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubIssue;
import com.jfx.git.core.gitlab.GitLabClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One page at a time from each provider: what the pull request and issue dialogs ask for
 * now instead of following every page before showing anything. See {@link ListPage}.
 */
class ProviderPagingTest {

    private HttpServer server;
    private URI base;
    /** Every request's path and query, in order. */
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        base = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void answer(String path, int status, String body, Map<String, String> headers) {
        server.createContext(path, exchange -> {
            requests.add(exchange.getRequestURI().toString());
            reply(exchange, status, body, headers);
        });
    }

    private static void reply(HttpExchange exchange, int status, String body, Map<String, String> headers)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String pr(int number) {
        return "{\"number\": " + number + ", \"title\": \"PR " + number + "\", \"user\": {\"login\": \"octocat\"},"
                + " \"draft\": false, \"head\": {\"ref\": \"b" + number + "\", \"sha\": \"s" + number + "\"},"
                + " \"base\": {\"ref\": \"main\"}}";
    }

    @Test
    void aGitHubPageAsksForOnePageAndSaysWhetherThereIsAnother() {
        answer("/repos/o/r/pulls", 200, "[" + pr(2) + "," + pr(1) + "]",
                Map.of("Link", "<" + base + "/repos/o/r/pulls?page=3>; rel=\"next\""));
        server.start();

        var page = new GitHubClient(base).listPullRequestsPage("t", "o", "r", 2, 50);

        assertTrue(page.isOk(), page.messageOrNull());
        assertEquals(2, page.orNull().items().size());
        assertTrue(page.orNull().hasMore(), "the Link header named a next page");
        assertEquals(1, requests.size(), "one request, not every page");
        assertTrue(requests.get(0).contains("per_page=50") && requests.get(0).contains("page=2"), requests.get(0));
    }

    @Test
    void theLastGitHubPageHasNoMore() {
        answer("/repos/o/r/pulls", 200, "[" + pr(1) + "]", Map.of());
        server.start();

        var page = new GitHubClient(base).listPullRequestsPage("t", "o", "r", 1, 50);

        assertFalse(page.orNull().hasMore());
    }

    @Test
    void gitHubIssuesComeFromSearchSoPullRequestsAreNeverDownloaded() {
        answer("/search/issues", 200, """
                {"total_count": 1, "incomplete_results": false, "items": [
                  {"number": 9, "title": "Crash", "user": {"login": "octocat"}, "state": "open", "labels": []}]}""",
                Map.of());
        answer("/repos/o/r/issues", 200, "[]", Map.of());
        server.start();

        var page = new GitHubClient(base).listIssuesPage("t", "o", "r", 1, 50);

        assertTrue(page.isOk(), page.messageOrNull());
        assertEquals(List.of(9), page.orNull().items().stream().map(GitHubIssue::number).toList());
        assertEquals(1, requests.size());
        String query = URI.create(requests.get(0)).getQuery();
        assertTrue(query.contains("repo:o/r") && query.contains("is:issue") && query.contains("is:open"), query);
    }

    @Test
    void whereSearchIsRefusedGitHubIssuesFallBackToTheIssuesListAndStayThere() {
        answer("/search/issues", 422, "{\"message\": \"Validation Failed\"}", Map.of());
        answer("/repos/o/r/issues", 200, """
                [{"number": 9, "title": "Crash", "user": {"login": "octocat"}, "state": "open", "labels": []},
                 {"number": 8, "title": "A pull request", "user": {"login": "octocat"}, "state": "open",
                  "labels": [], "pull_request": {}}]""",
                Map.of("Link", "<" + base + "/repos/o/r/issues?page=2>; rel=\"next\""));
        server.start();
        GitHubClient client = new GitHubClient(base);

        var first = client.listIssuesPage("t", "o", "r", 1, 50);
        var second = client.listIssuesPage("t", "o", "r", 2, 50);

        assertTrue(first.isOk(), first.messageOrNull());
        assertEquals(List.of(9), first.orNull().items().stream().map(GitHubIssue::number).toList(),
                "pull requests are still filtered out of the fallback");
        assertTrue(first.orNull().hasMore());
        assertTrue(second.isOk());
        assertEquals(1, requests.stream().filter(r -> r.startsWith("/search/")).count(),
                "search is not asked again once it has refused: " + requests);
    }

    @Test
    void aRejectedTokenIsNotHiddenBehindTheFallback() {
        answer("/search/issues", 401, "{\"message\": \"Bad credentials\"}", Map.of());
        answer("/repos/o/r/issues", 200, "[]", Map.of());
        server.start();

        var page = new GitHubClient(base).listIssuesPage("t", "o", "r", 1, 50);

        assertFalse(page.isOk());
        assertEquals(ApiFailure.AUTH_REJECTED, page.failureKind());
        assertEquals(1, requests.size());
    }

    @Test
    void aGitLabPageSaysWhetherThereIsAnother() {
        // One context: the project path is URL-encoded, and contexts match the decoded path.
        server.createContext("/projects/", exchange -> {
            String raw = exchange.getRequestURI().getRawPath();
            requests.add(exchange.getRequestURI().toString());
            if (raw.endsWith("/merge_requests")) {
                reply(exchange, 200, "[{\"iid\": 3, \"title\": \"MR\"}]",
                        Map.of("Link", "<" + base + "/projects/g%2Fp/merge_requests?page=2>; rel=\"next\""));
            } else {
                reply(exchange, 200, "[{\"iid\": 4, \"title\": \"Issue\"}]", Map.of());
            }
        });
        server.start();
        GitLabClient client = new GitLabClient(base);

        var mrs = client.listMergeRequestsPage("t", "g%2Fp", 1, 50);
        var issues = client.listIssuesPage("t", "g%2Fp", 1, 50);

        assertTrue(mrs.isOk(), mrs.messageOrNull());
        assertTrue(mrs.orNull().hasMore());
        assertFalse(issues.orNull().hasMore());
        assertTrue(requests.stream().allMatch(r -> r.contains("per_page=50") && r.contains("page=1")), requests.toString());
    }

    @Test
    void aBitbucketPageSaysWhetherThereIsAnotherFromItsNextField() {
        answer("/repositories/w/r/pullrequests", 200, """
                {"values": [{"id": 5, "title": "PR"}], "next": "%s/repositories/w/r/pullrequests?page=3"}"""
                .formatted(base), Map.of());
        server.start();

        var page = new BitbucketClient(base).listPullRequestsPage("t", "w", "r", 2, 50);

        assertTrue(page.isOk(), page.messageOrNull());
        assertEquals(1, page.orNull().items().size());
        assertTrue(page.orNull().hasMore());
        assertTrue(requests.get(0).contains("pagelen=50") && requests.get(0).contains("page=2"), requests.get(0));
    }
}
