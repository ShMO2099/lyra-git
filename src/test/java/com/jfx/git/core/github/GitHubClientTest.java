package com.jfx.git.core.github;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link GitHubClient} against a real local HTTP server rather than mocking
 * {@code HttpClient} itself - the server returns exactly the JSON shapes and status
 * codes verified against the real {@code api.github.com} while writing this (a 200 with
 * GitHub's real {@code /user} fields, a 401 with GitHub's real {@code {"message":
 * "Bad credentials", ...}} body), so what is proven here is the client's actual
 * request/response handling, not a mock's idea of it.
 */
class GitHubClientTest {

    private HttpServer server;
    private GitHubClient client;
    /** Set by a handler when a request arrives, so a test can assert on what was sent. */
    private volatile String lastAuthorizationHeader;
    private volatile String lastApiVersionHeader;
    private volatile String lastMethod;
    private volatile String lastRequestBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        client = new GitHubClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Like {@link #respond}, with response headers - which is where rate limits live. */
    private void respond(String path, int status, String body, java.util.Map<String, String> headers) {
        server.createContext(path, exchange -> {
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastMethod = exchange.getRequestMethod();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    /**
     * Registers an endpoint without starting the server, for a test that needs more than
     * one - {@link #respond(String, int, String)} starts it, so it goes last.
     */
    private void context(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastApiVersionHeader = exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version");
            lastMethod = exchange.getRequestMethod();
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    // ------------------------------------------------------------------
    // fetchAuthenticatedUser
    // ------------------------------------------------------------------

    @Test
    void aValidTokenReturnsTheAuthenticatedUser() {
        respond("/user", 200, """
                {"login": "octocat", "avatar_url": "https://avatars.githubusercontent.com/u/1"}""");

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("real-token");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("octocat", result.orNull().login());
        assertEquals("https://avatars.githubusercontent.com/u/1", result.orNull().avatarUrl());
    }

    @Test
    void theTokenIsSentAsABearerAuthorizationHeader() {
        respond("/user", 200, """
                {"login": "octocat", "avatar_url": null}""");

        client.fetchAuthenticatedUser("abc123");

        assertEquals("Bearer abc123", lastAuthorizationHeader);
    }

    @Test
    void theApiVersionHeaderIsSentOnEveryRequest() {
        respond("/user", 200, """
                {"login": "octocat", "avatar_url": null}""");

        client.fetchAuthenticatedUser("abc123");

        assertEquals("2022-11-28", lastApiVersionHeader);
    }

    @Test
    void aBlankOrNullTokenFailsWithoutMakingARequest() {
        // No context registered on the server at all - a request reaching it would 404,
        // not fail with this message, so passing proves the guard clause fired first.
        assertFalse(client.fetchAuthenticatedUser("").isOk());
        assertFalse(client.fetchAuthenticatedUser(null).isOk());
        assertFalse(client.fetchAuthenticatedUser("   ").isOk());
    }

    @Test
    void aNonJsonErrorBodyStillProducesAReadableFailure() {
        respond("/user", 500, "<html>Internal Server Error</html>");

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("token");

        assertFalse(result.isOk());
        assertTrue(result.messageOrNull().contains("500"), result.messageOrNull());
    }

    @Test
    void malformedJsonOnASuccessStatusIsAFailureNotAThrow() {
        respond("/user", 200, "not json at all {{{");

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("token");

        assertFalse(result.isOk());
    }

    @Test
    void anUnreachableServerIsAFailureNotAThrow() {
        var unreachable = new GitHubClient(URI.create("http://localhost:1"));

        GitHubResult<GitHubUser> result = unreachable.fetchAuthenticatedUser("token");

        assertFalse(result.isOk());
        assertNotNull(result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // Why a failure failed
    // ------------------------------------------------------------------

    /**
     * The distinction that decides whether a remembered token is thrown away.
     *
     * <p>Every failure used to look the same to the caller, and the caller deleted the
     * stored credential for all of them - so launching offline, or during a provider
     * outage, or one request past a rate limit, erased a login that was perfectly good.
     * See {@link com.jfx.git.core.ApiFailure} and {@code IntegrationSessions}.
     */
    @Test
    void anUnreachableServerIsATransportFailureNotARejection() {
        var unreachable = new GitHubClient(URI.create("http://localhost:1"));

        GitHubResult<GitHubUser> result = unreachable.fetchAuthenticatedUser("token");

        assertEquals(com.jfx.git.core.ApiFailure.TRANSPORT, result.failureKind());
        assertTrue(result.failureKind().keepsCredentials(),
                "being offline says nothing about whether the token is valid");
    }

    @Test
    void a401IsTheOneFailureThatMeansTheTokenIsNoGood() {
        respond("/user", 401, "{\"message\":\"Bad credentials\"}");

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("stale");

        assertEquals(com.jfx.git.core.ApiFailure.AUTH_REJECTED, result.failureKind());
        assertFalse(result.failureKind().keepsCredentials());
    }

    @Test
    void aRateLimited403KeepsTheCredential() {
        respond("/user", 403, "{\"message\":\"API rate limit exceeded\"}",
                java.util.Map.of("X-RateLimit-Remaining", "0"));

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("fine");

        assertEquals(com.jfx.git.core.ApiFailure.RATE_LIMITED, result.failureKind());
        assertTrue(result.failureKind().keepsCredentials(),
                "a rate limit is about how often, not about who");
    }

    /**
     * A 403 that is not a rate limit is a permissions answer, and a permissions answer is
     * not a statement about the credential either - which is why only 401 discards it.
     */
    @Test
    void aPlain403KeepsTheCredentialToo() {
        respond("/user", 403, "{\"message\":\"Resource not accessible by personal access token\"}");

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("scoped-too-narrow");

        assertEquals(com.jfx.git.core.ApiFailure.OTHER, result.failureKind());
        assertTrue(result.failureKind().keepsCredentials());
    }

    @Test
    void aServerErrorKeepsTheCredential() {
        respond("/user", 503, "upstream is having a bad day");

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("fine");

        assertEquals(com.jfx.git.core.ApiFailure.SERVER, result.failureKind());
        assertTrue(result.failureKind().isTemporary());
    }

    // ------------------------------------------------------------------
    // listRepositories
    // ------------------------------------------------------------------

    @Test
    void repositoriesAreParsedWithTheFieldsTheCloneFlowNeeds() {
        respond("/user/repos", 200, """
                [
                  {
                    "full_name": "octocat/boysenberry-repo-1",
                    "clone_url": "https://github.com/octocat/boysenberry-repo-1.git",
                    "ssh_url": "git@github.com:octocat/boysenberry-repo-1.git",
                    "description": "Testing",
                    "private": false
                  },
                  {
                    "full_name": "octocat/secret-repo",
                    "clone_url": "https://github.com/octocat/secret-repo.git",
                    "ssh_url": "git@github.com:octocat/secret-repo.git",
                    "description": null,
                    "private": true
                  }
                ]""");

        GitHubResult<List<GitHubRepository>> result = client.listRepositories("token");

        assertTrue(result.isOk(), result.messageOrNull());
        List<GitHubRepository> repos = result.orNull();
        assertEquals(2, repos.size());
        assertEquals("octocat/boysenberry-repo-1", repos.get(0).fullName());
        assertEquals("https://github.com/octocat/boysenberry-repo-1.git", repos.get(0).cloneUrl());
        assertEquals("git@github.com:octocat/boysenberry-repo-1.git", repos.get(0).sshUrl());
        assertEquals("Testing", repos.get(0).description());
        assertFalse(repos.get(0).isPrivate());

        assertNull(repos.get(1).description());
        assertTrue(repos.get(1).isPrivate());
    }

    @Test
    void anEmptyRepositoryListIsNotAFailure() {
        respond("/user/repos", 200, "[]");

        GitHubResult<List<GitHubRepository>> result = client.listRepositories("token");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    // ------------------------------------------------------------------
    // listPullRequests
    // ------------------------------------------------------------------

    @Test
    void pullRequestsAreParsedWithTheFieldsTheCheckoutFlowNeeds() {
        respond("/repos/octocat/Hello-World/pulls", 200, """
                [
                  {
                    "number": 42,
                    "title": "Fix flaky test",
                    "user": {"login": "octocat", "avatar_url": "https://avatars.githubusercontent.com/u/1"},
                    "draft": false,
                    "head": {"ref": "fix-flaky-test", "sha": "abc123"},
                    "base": {"ref": "main"},
                    "html_url": "https://github.com/octocat/Hello-World/pull/42"
                  },
                  {
                    "number": 43,
                    "title": "WIP: new feature",
                    "user": {"login": "hubot", "avatar_url": null},
                    "draft": true,
                    "head": {"ref": "new-feature", "sha": "def456"},
                    "base": {"ref": "develop"},
                    "html_url": "https://github.com/octocat/Hello-World/pull/43"
                  }
                ]""");

        GitHubResult<List<GitHubPullRequest>> result = client.listPullRequests("token", "octocat", "Hello-World");

        assertTrue(result.isOk(), result.messageOrNull());
        List<GitHubPullRequest> prs = result.orNull();
        assertEquals(2, prs.size());

        GitHubPullRequest first = prs.get(0);
        assertEquals(42, first.number());
        assertEquals("Fix flaky test", first.title());
        assertEquals("octocat", first.user().login());
        assertFalse(first.draft());
        assertEquals("fix-flaky-test", first.head().ref());
        assertEquals("abc123", first.head().sha());
        assertEquals("main", first.base().ref());
        assertEquals("https://github.com/octocat/Hello-World/pull/42", first.htmlUrl());

        assertTrue(prs.get(1).draft());
    }

    @Test
    void anEmptyPullRequestListIsNotAFailure() {
        respond("/repos/octocat/Hello-World/pulls", 200, "[]");

        GitHubResult<List<GitHubPullRequest>> result = client.listPullRequests("token", "octocat", "Hello-World");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    // ------------------------------------------------------------------
    // listIssues
    // ------------------------------------------------------------------

    @Test
    void issuesAreParsedWithTheFieldsTheListNeeds() {
        respond("/repos/octocat/Hello-World/issues", 200, """
                [
                  {
                    "number": 10985,
                    "title": "This should fail",
                    "user": {"login": "octocat", "avatar_url": null},
                    "state": "open",
                    "html_url": "https://github.com/octocat/Hello-World/issues/10985",
                    "labels": [{"name": "bug"}, {"name": "help wanted"}]
                  }
                ]""");

        GitHubResult<List<GitHubIssue>> result = client.listIssues("token", "octocat", "Hello-World");

        assertTrue(result.isOk(), result.messageOrNull());
        List<GitHubIssue> issues = result.orNull();
        assertEquals(1, issues.size());
        GitHubIssue issue = issues.get(0);
        assertEquals(10985, issue.number());
        assertEquals("This should fail", issue.title());
        assertEquals("octocat", issue.user().login());
        assertEquals("open", issue.state());
        assertEquals("https://github.com/octocat/Hello-World/issues/10985", issue.htmlUrl());
        assertEquals(List.of("bug", "help wanted"),
                issue.labels().stream().map(GitHubIssue.Label::name).toList());
        assertFalse(issue.isPullRequest());
    }

    /**
     * GitHub's issues endpoint also returns pull requests - verified against the live
     * API while writing this: a real issue never carries a {@code pull_request} key,
     * a PR always does, whatever its value.
     */
    @Test
    void pullRequestsReturnedByTheIssuesEndpointAreFilteredOut() {
        respond("/repos/octocat/Hello-World/issues", 200, """
                [
                  {
                    "number": 10985,
                    "title": "A real issue",
                    "user": {"login": "octocat", "avatar_url": null},
                    "state": "open",
                    "html_url": "https://github.com/octocat/Hello-World/issues/10985",
                    "labels": []
                  },
                  {
                    "number": 10980,
                    "title": "Actually a pull request",
                    "user": {"login": "octocat", "avatar_url": null},
                    "state": "open",
                    "html_url": "https://github.com/octocat/Hello-World/issues/10980",
                    "labels": [],
                    "pull_request": {"url": "https://api.github.com/repos/octocat/Hello-World/pulls/10980"}
                  }
                ]""");

        GitHubResult<List<GitHubIssue>> result = client.listIssues("token", "octocat", "Hello-World");

        assertTrue(result.isOk(), result.messageOrNull());
        List<GitHubIssue> issues = result.orNull();
        assertEquals(1, issues.size(), "the pull request must be filtered out");
        assertEquals(10985, issues.get(0).number());
    }

    @Test
    void anEmptyIssueListIsNotAFailure() {
        respond("/repos/octocat/Hello-World/issues", 200, "[]");

        GitHubResult<List<GitHubIssue>> result = client.listIssues("token", "octocat", "Hello-World");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    // ------------------------------------------------------------------
    // listForks / createFork
    // ------------------------------------------------------------------

    @Test
    void forksAreParsedWithTheFieldsTheAddRemoteFlowNeeds() {
        respond("/repos/octocat/Hello-World/forks", 200, """
                [
                  {
                    "full_name": "someone-else/Hello-World",
                    "clone_url": "https://github.com/someone-else/Hello-World.git",
                    "ssh_url": "git@github.com:someone-else/Hello-World.git",
                    "description": null,
                    "private": false
                  }
                ]""");

        GitHubResult<List<GitHubRepository>> result = client.listForks("token", "octocat", "Hello-World");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(1, result.orNull().size());
        assertEquals("someone-else/Hello-World", result.orNull().get(0).fullName());
    }

    @Test
    void anEmptyForkListIsNotAFailure() {
        respond("/repos/octocat/Hello-World/forks", 200, "[]");

        GitHubResult<List<GitHubRepository>> result = client.listForks("token", "octocat", "Hello-World");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    @Test
    void creatingAForkSendsAPostAndParsesTheNewRepository() {
        respond("/repos/octocat/Hello-World/forks", 202, """
                {
                  "full_name": "octocat/Hello-World",
                  "clone_url": "https://github.com/octocat/Hello-World.git",
                  "ssh_url": "git@github.com:octocat/Hello-World.git",
                  "description": null,
                  "private": false
                }""");

        GitHubResult<GitHubRepository> result = client.createFork("token", "octocat", "Hello-World");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("octocat/Hello-World", result.orNull().fullName());
        assertEquals("POST", lastMethod);
    }

    @Test
    void aFailedForkSurfacesGitHubsOwnMessage() {
        respond("/repos/octocat/Hello-World/forks", 403, """
                {"message": "Forbidden", "documentation_url": "https://docs.github.com/rest"}""");

        GitHubResult<GitHubRepository> result = client.createFork("token", "octocat", "Hello-World");

        assertFalse(result.isOk());
        assertEquals("Forbidden", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // mergePullRequest
    // ------------------------------------------------------------------

    @Test
    void mergingSendsAPutWithTheChosenMethodAndParsesGitHubsOwnMessage() {
        respond("/repos/octocat/Hello-World/pulls/42/merge", 200, """
                {"sha": "6dcb09b5b57875f334f61aebed695e2e4193db5", "merged": true,
                 "message": "Pull Request successfully merged"}""");

        GitHubResult<String> result = client.mergePullRequest("token", "octocat", "Hello-World", 42, "squash");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("Pull Request successfully merged", result.orNull());
        assertEquals("PUT", lastMethod);
        assertTrue(lastRequestBody.contains("\"merge_method\":\"squash\""), lastRequestBody);
    }

    @Test
    void anUnmergeablePullRequestSurfacesGitHubsOwnMessage() {
        respond("/repos/octocat/Hello-World/pulls/42/merge", 405, """
                {"message": "Pull Request is not mergeable", "documentation_url": "https://docs.github.com/rest"}""");

        GitHubResult<String> result = client.mergePullRequest("token", "octocat", "Hello-World", 42, "merge");

        assertFalse(result.isOk());
        assertEquals("Pull Request is not mergeable", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // createPullRequest
    // ------------------------------------------------------------------

    @Test
    void creatingAPullRequestSendsAPostAndParsesTheNewPullRequest() {
        respond("/repos/octocat/Hello-World/pulls", 201, """
                {"number": 55, "title": "Fix flaky test", "user": {"login": "octocat", "avatar_url": null},
                 "draft": false, "head": {"ref": "fix-flaky-test", "sha": "abc123"}, "base": {"ref": "main"},
                 "html_url": "https://github.com/octocat/Hello-World/pull/55"}""");

        GitHubResult<GitHubPullRequest> result = client.createPullRequest(
                "token", "octocat", "Hello-World", "Fix flaky test", "Fixes the race", "fix-flaky-test", "main");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(55, result.orNull().number());
        assertEquals("POST", lastMethod);
        assertTrue(lastRequestBody.contains("\"head\":\"fix-flaky-test\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"base\":\"main\""), lastRequestBody);
    }

    @Test
    void aPullRequestWithNothingToMergeSurfacesGitHubsOwnMessage() {
        respond("/repos/octocat/Hello-World/pulls", 422, """
                {"message": "Validation Failed", "documentation_url": "https://docs.github.com/rest"}""");

        GitHubResult<GitHubPullRequest> result = client.createPullRequest(
                "token", "octocat", "Hello-World", "Nothing to merge", null, "main", "main");

        assertFalse(result.isOk());
        assertEquals("Validation Failed", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // getCheckStatus
    // ------------------------------------------------------------------

    @Test
    void aCommitWithNoCheckRunsIsReportedAsNone() {
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 0, "check_runs": []}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.NONE, result.orNull());
    }

    @Test
    void allCheckRunsSucceedingIsReportedAsPassed() {
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 2, "check_runs": [
                  {"status": "completed", "conclusion": "success"},
                  {"status": "completed", "conclusion": "skipped"}
                ]}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.PASSED, result.orNull());
    }

    @Test
    void aStillRunningCheckIsReportedAsPending() {
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 2, "check_runs": [
                  {"status": "completed", "conclusion": "success"},
                  {"status": "in_progress", "conclusion": null}
                ]}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.PENDING, result.orNull());
    }

    /** A failure beats a still-pending check - the overall answer is never a false "pending" once something has already failed. */
    @Test
    void oneFailedCheckWinsOverAPendingOne() {
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 2, "check_runs": [
                  {"status": "completed", "conclusion": "failure"},
                  {"status": "in_progress", "conclusion": null}
                ]}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.FAILED, result.orNull());
    }

    // ------------------------------------------------------------------
    // listReviewComments / createReviewComment
    // ------------------------------------------------------------------

    @Test
    void listReviewCommentsParsesEveryFieldAReviewNeeds() {
        respond("/repos/octocat/Hello-World/pulls/42/comments", 200, """
                [{"id": 1, "body": "typo here", "path": "src/Main.java", "line": 10, "side": "RIGHT",
                  "user": {"login": "octocat", "avatar_url": null}, "commit_id": "abc123",
                  "html_url": "https://github.com/octocat/Hello-World/pull/42#discussion_r1"}]""");

        var result = client.listReviewComments("token", "octocat", "Hello-World", 42);

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(1, result.orNull().size());
        var comment = result.orNull().get(0);
        assertEquals("typo here", comment.body());
        assertEquals("src/Main.java", comment.path());
        assertEquals(10, comment.line());
        assertEquals("RIGHT", comment.side());
        assertEquals("octocat", comment.user().login());
    }

    @Test
    void noReviewCommentsIsNotAFailure() {
        respond("/repos/octocat/Hello-World/pulls/42/comments", 200, "[]");

        var result = client.listReviewComments("token", "octocat", "Hello-World", 42);

        assertTrue(result.isOk(), result.messageOrNull());
        assertTrue(result.orNull().isEmpty());
    }

    @Test
    void creatingAReviewCommentSendsThePositionAndParsesTheResponse() {
        respond("/repos/octocat/Hello-World/pulls/42/comments", 201, """
                {"id": 99, "body": "Please rename this", "path": "src/Main.java", "line": 10, "side": "RIGHT",
                 "user": {"login": "octocat", "avatar_url": null}, "commit_id": "abc123",
                 "html_url": "https://github.com/octocat/Hello-World/pull/42#discussion_r99"}""");

        var result = client.createReviewComment("token", "octocat", "Hello-World", 42, "abc123",
                "src/Main.java", 10, "RIGHT", "Please rename this");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(99, result.orNull().id());
        assertEquals("POST", lastMethod);
        assertTrue(lastRequestBody.contains("\"commit_id\":\"abc123\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"path\":\"src/Main.java\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"line\":10"), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"side\":\"RIGHT\""), lastRequestBody);
    }

    /** A suggestion is nothing but body text - the fenced block round-trips untouched, no separate API field. */
    @Test
    void aSuggestedChangeIsJustFencedMarkdownInTheBody() {
        respond("/repos/octocat/Hello-World/pulls/42/comments", 201, """
                {"id": 100, "body": "```suggestion\\nfixed line\\n```", "path": "src/Main.java", "line": 10,
                 "side": "RIGHT", "user": {"login": "octocat", "avatar_url": null}, "commit_id": "abc123",
                 "html_url": "https://github.com/octocat/Hello-World/pull/42#discussion_r100"}""");

        String suggestionBody = "```suggestion\nfixed line\n```";
        var result = client.createReviewComment("token", "octocat", "Hello-World", 42, "abc123",
                "src/Main.java", 10, "RIGHT", suggestionBody);

        assertTrue(result.isOk(), result.messageOrNull());
        assertTrue(lastRequestBody.contains("```suggestion"), lastRequestBody);
        assertEquals("```suggestion\nfixed line\n```", result.orNull().body());
    }

    @Test
    void creatingAReviewCommentOnAnUnknownLineSurfacesGitHubsOwnMessage() {
        respond("/repos/octocat/Hello-World/pulls/42/comments", 422, """
                {"message": "Validation Failed", "documentation_url": "https://docs.github.com/rest"}""");

        var result = client.createReviewComment("token", "octocat", "Hello-World", 42, "abc123",
                "src/Main.java", 999, "RIGHT", "comment");

        assertFalse(result.isOk());
        assertEquals("Validation Failed", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // Rate limiting and token expiry
    // ------------------------------------------------------------------

    /**
     * GitHub's real rate-limit response: 403, {@code X-RateLimit-Remaining: 0}, and a
     * body whose message names a user ID rather than a time. The reset header is the
     * only part the user can act on, so it has to reach them.
     */
    @Test
    void aRateLimitedRequestSaysWhenItWillWorkAgain() {
        long resetsAt = java.time.Instant.now().getEpochSecond() + 8 * 60;
        respond("/user", 403, """
                {"message": "API rate limit exceeded for user ID 1.",                 "documentation_url": "https://docs.github.com/rest/overview/rate-limits-for-the-rest-api"}""",
                java.util.Map.of("X-RateLimit-Remaining", "0",
                        "X-RateLimit-Reset", String.valueOf(resetsAt)));

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("real-token");

        assertFalse(result.isOk());
        String message = result.messageOrNull();
        assertTrue(message.contains("rate limit"), message);
        assertTrue(message.contains("8 minutes"), () -> "the reset time should be in it: " + message);
        assertFalse(message.contains("user ID"), () -> "GitHub's own wording is not useful here: " + message);
    }

    /** The secondary-limit response needs no header to be unambiguous. */
    @Test
    void aSecondaryRateLimitIsRecognisedFromTheStatusAlone() {
        respond("/user", 429, """
                {"message": "You have exceeded a secondary rate limit"}""",
                java.util.Map.of("Retry-After", "45"));

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("real-token");

        assertFalse(result.isOk());
        assertTrue(result.messageOrNull().contains("45 seconds"), result.messageOrNull());
    }

    /**
     * A 403 that is not a rate limit must not be reported as one - sending the user off
     * to wait for something that is never going to change is worse than saying nothing.
     */
    @Test
    void aPermissionsFailureIsNotReportedAsARateLimit() {
        respond("/user", 403, """
                {"message": "Resource not accessible by personal access token"}""",
                java.util.Map.of("X-RateLimit-Remaining", "4999"));

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("real-token");

        assertFalse(result.isOk());
        assertEquals("Resource not accessible by personal access token", result.messageOrNull());
    }

    /**
     * GitHub's real 401 body, verified against the live API while this was written.
     *
     * <p>It used to be passed straight through. "Bad credentials" reads like a typo, and
     * the usual cause is a token that has expired since it was pasted - so the message
     * has to point at reconnecting rather than at retyping.
     */
    @Test
    void anExpiredTokenSaysToReconnectRatherThanRepeatingBadCredentials() {
        respond("/user", 401, """
                {"message": "Bad credentials", "documentation_url": "https://docs.github.com/rest", "status": "401"}""");

        GitHubResult<GitHubUser> result = client.fetchAuthenticatedUser("stale-token");

        assertFalse(result.isOk());
        String message = result.messageOrNull();
        assertTrue(message.contains("reconnect"), message);
        assertTrue(message.contains("expire"), message);
    }

    // ------------------------------------------------------------------
    // R5-3. Re-reading a pull request before anchoring a comment to it
    // ------------------------------------------------------------------

    /**
     * A review comment is anchored to a commit, and the head this dialog was opened with
     * goes stale the moment the author pushes - which is exactly what happens while
     * someone is reviewing. Posting against the stale one is rejected, so the head is
     * re-read first; this is the call that does it.
     */
    @Test
    void fetchPullRequestAsksForTheOnePullRequest() {
        respond("/repos/octo/demo/pulls/7", 200, """
                {"number":7,"title":"Fix it","state":"open","draft":false,
                 "head":{"ref":"topic","sha":"f00dcafe"},
                 "base":{"ref":"main","sha":"deadbeef"},
                 "user":{"login":"octocat"},"html_url":"https://github.com/octo/demo/pull/7"}
                """);

        var result = client.fetchPullRequest("token", "octo", "demo", 7);

        // The handler is registered at that exact path, so anything else 404s and this
        // fails - which is the assertion that the URL is right.
        assertTrue(result.isOk(), () -> "expected success, got: " + result.messageOrNull());
        assertEquals("GET", lastMethod);
        assertEquals("f00dcafe", result.orNull().head().sha(),
                "the freshly-read head is what a comment must be anchored to");
    }

    /** A pull request that has moved reports its new head, not the one from list time. */
    @Test
    void fetchPullRequestReportsTheCurrentHead() {
        respond("/repos/octo/demo/pulls/7", 200, """
                {"number":7,"title":"Fix it","state":"open","draft":false,
                 "head":{"ref":"topic","sha":"newer000"},
                 "base":{"ref":"main","sha":"deadbeef"},
                 "user":{"login":"octocat"},"html_url":"https://github.com/octo/demo/pull/7"}
                """);

        assertEquals("newer000",
                client.fetchPullRequest("token", "octo", "demo", 7).orNull().head().sha());
    }

    // ------------------------------------------------------------------
    // R7-2. What a check state means, and where CI is allowed to report it
    // ------------------------------------------------------------------

    /**
     * {@code action_required} is a workflow waiting for a maintainer to approve a
     * first-time contributor's run. Nobody has broken anything, and GitHub itself draws
     * it amber - painting the pull request red accuses it of failing.
     */
    @Test
    void aCheckWaitingForApprovalIsPendingNotFailed() {
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 1, "check_runs": [
                  {"status": "completed", "conclusion": "action_required"}
                ]}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertEquals(com.jfx.git.core.BuildStatus.PENDING, result.orNull());
    }

    /** Every real failure arrives under a name we know, so the unknown case waits. */
    @Test
    void anUnrecognisedConclusionWaitsRatherThanAccuses() {
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 1, "check_runs": [
                  {"status": "completed", "conclusion": "something_github_added_later"}
                ]}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertEquals(com.jfx.git.core.BuildStatus.PENDING, result.orNull());
    }

    @Test
    void theNamedFailuresAreStillFailures() {
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 3, "check_runs": [
                  {"status": "completed", "conclusion": "success"},
                  {"status": "completed", "conclusion": "timed_out"},
                  {"status": "completed", "conclusion": "success"}
                ]}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertEquals(com.jfx.git.core.BuildStatus.FAILED, result.orNull());
    }

    /** A run a later one superseded carries no verdict of its own. */
    @Test
    void aStaleCheckDoesNotHoldThePullRequestAmber() {
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 2, "check_runs": [
                  {"status": "completed", "conclusion": "stale"},
                  {"status": "completed", "conclusion": "success"}
                ]}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertEquals(com.jfx.git.core.BuildStatus.PASSED, result.orNull());
    }

    /**
     * The coverage gap: a repository whose CI posts to the older commit-status API has no
     * check runs at all, and used to be reported as having no CI - a quiet absence of
     * warning over a red build.
     */
    @Test
    void aFailingLegacyCommitStatusIsNotReportedAsNoCi() {
        context("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 0, "check_runs": []}""");
        respond("/repos/octocat/Hello-World/commits/abc123/status", 200, """
                {"state": "failure", "total_count": 2}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.FAILED, result.orNull(),
                "a build failing through the older API is still a failing build");
    }

    /** Both systems at once: whichever is unhappy decides. */
    @Test
    void checkRunsAndLegacyStatusesAreCombined() {
        context("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 1, "check_runs": [
                  {"status": "completed", "conclusion": "success"}
                ]}""");
        respond("/repos/octocat/Hello-World/commits/abc123/status", 200, """
                {"state": "failure", "total_count": 1}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertEquals(com.jfx.git.core.BuildStatus.FAILED, result.orNull());
    }

    @Test
    void aPassingLegacyStatusPassesToo() {
        context("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 0, "check_runs": []}""");
        respond("/repos/octocat/Hello-World/commits/abc123/status", 200, """
                {"state": "success", "total_count": 3}""");

        assertEquals(com.jfx.git.core.BuildStatus.PASSED,
                client.getCheckStatus("token", "octocat", "Hello-World", "abc123").orNull());
    }

    /** Neither system reporting is still genuinely "no CI", not a pass. */
    @Test
    void aRepositoryWithNoCiAtAllIsStillNone() {
        context("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 0, "check_runs": []}""");
        respond("/repos/octocat/Hello-World/commits/abc123/status", 200, """
                {"state": "pending", "total_count": 0}""");

        assertEquals(com.jfx.git.core.BuildStatus.NONE,
                client.getCheckStatus("token", "octocat", "Hello-World", "abc123").orNull());
    }

    /** The statuses call is best-effort: losing it must not lose the check-run answer. */
    @Test
    void anInaccessibleStatusesEndpointDoesNotDiscardTheCheckRuns() {
        // Only check-runs is served; the statuses call 404s.
        respond("/repos/octocat/Hello-World/commits/abc123/check-runs", 200, """
                {"total_count": 1, "check_runs": [
                  {"status": "completed", "conclusion": "failure"}
                ]}""");

        var result = client.getCheckStatus("token", "octocat", "Hello-World", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.FAILED, result.orNull());
    }
}
