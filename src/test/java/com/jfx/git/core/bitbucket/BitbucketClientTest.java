package com.jfx.git.core.bitbucket;

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
 * Runs {@link BitbucketClient} against a real local HTTP server rather than mocking
 * {@code HttpClient} itself - the same technique {@code GitHubClientTest} uses. The
 * response shapes below were verified against the real {@code api.bitbucket.org} API
 * while writing this: the {@code links.clone} array shape, the nested {@code
 * source}/{@code destination} pull-request endpoints (including a real fork's fields),
 * and that every {@code /issues} endpoint now answers {@code 410 Gone} regardless of
 * the repository - which is why this class has no {@code listIssues} to test.
 */
class BitbucketClientTest {

    private HttpServer server;
    private BitbucketClient client;
    private volatile String lastAuthorizationHeader;
    private volatile String lastMethod;
    private volatile String lastRequestBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        client = new BitbucketClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Unlike {@code GitHubClientTest}/{@code GitLabClientTest}, some tests here need
     * more than one context (listing repositories means one call for workspaces and
     * one per workspace's repositories) - {@code HttpServer.start()} throws on a
     * server already running, so it is only called once, on the first registration.
     */
    private boolean started;

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastMethod = exchange.getRequestMethod();
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        if (!started) {
            server.start();
            started = true;
        }
    }

    // ------------------------------------------------------------------
    // fetchAuthenticatedUser
    // ------------------------------------------------------------------

    @Test
    void aValidTokenReturnsTheAuthenticatedUser() {
        respond("/user", 200, """
                {"username": "octocat", "display_name": "The Octocat"}""");

        BitbucketResult<BitbucketUser> result = client.fetchAuthenticatedUser("real-token");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("octocat", result.orNull().username());
        assertEquals("octocat", result.orNull().handle());
    }

    @Test
    void handleFallsBackToDisplayNameWhenUsernameIsAbsent() {
        respond("/user", 200, """
                {"username": null, "display_name": "The Octocat"}""");

        BitbucketResult<BitbucketUser> result = client.fetchAuthenticatedUser("real-token");

        assertEquals("The Octocat", result.orNull().handle());
    }

    @Test
    void theTokenIsSentAsABearerAuthorizationHeader() {
        respond("/user", 200, """
                {"username": "octocat", "display_name": "The Octocat"}""");

        client.fetchAuthenticatedUser("abc123");

        assertEquals("Bearer abc123", lastAuthorizationHeader);
    }

    /** Bitbucket's real error body shape, verified against the live API while this was written. */
    @Test
    void aBadTokenSaysToReconnectRatherThanRepeatingTheApisWording() {
        respond("/user", 401, """
                {"type": "error", "error": {"message": "Access token expired."}}""");

        BitbucketResult<BitbucketUser> result = client.fetchAuthenticatedUser("wrong-token");

        assertFalse(result.isOk());
        String message = result.messageOrNull();
        // Bitbucket's own "Access token expired." is close, but does not say what to do
        // about it. See ApiErrors.
        assertTrue(message.contains("reconnect"), message);
        assertTrue(message.contains("expire"), message);
    }

    @Test
    void aBlankOrNullTokenFailsWithoutMakingARequest() {
        assertFalse(client.fetchAuthenticatedUser("").isOk());
        assertFalse(client.fetchAuthenticatedUser(null).isOk());
        assertFalse(client.fetchAuthenticatedUser("   ").isOk());
    }

    // ------------------------------------------------------------------
    // listRepositories (workspaces, then each workspace's repositories)
    // ------------------------------------------------------------------

    @Test
    void repositoriesAreAggregatedAcrossEveryWorkspace() {
        respond("/workspaces", 200, """
                {"values": [{"slug": "myteam"}, {"slug": "myotherteam"}]}""");
        respond("/repositories/myteam", 200, """
                {"values": [{"full_name": "myteam/repo-one", "is_private": false, "description": "",
                  "links": {"clone": [{"name": "https", "href": "https://bitbucket.org/myteam/repo-one.git"},
                                       {"name": "ssh", "href": "git@bitbucket.org:myteam/repo-one.git"}]}}]}""");
        respond("/repositories/myotherteam", 200, """
                {"values": [{"full_name": "myotherteam/repo-two", "is_private": true, "description": "secret",
                  "links": {"clone": [{"name": "https", "href": "https://bitbucket.org/myotherteam/repo-two.git"},
                                       {"name": "ssh", "href": "git@bitbucket.org:myotherteam/repo-two.git"}]}}]}""");

        BitbucketResult<List<BitbucketRepository>> result = client.listRepositories("token");

        assertTrue(result.isOk(), result.messageOrNull());
        List<BitbucketRepository> repos = result.orNull();
        assertEquals(2, repos.size());
        assertEquals("myteam/repo-one", repos.get(0).fullName());
        assertEquals("https://bitbucket.org/myteam/repo-one.git", repos.get(0).cloneUrl());
        assertEquals("git@bitbucket.org:myteam/repo-one.git", repos.get(0).sshUrl());
        assertFalse(repos.get(0).isPrivate());

        assertEquals("myotherteam/repo-two", repos.get(1).fullName());
        assertTrue(repos.get(1).isPrivate());
    }

    @Test
    void aWorkspaceWithNoRepositoriesContributesNoneRatherThanFailing() {
        respond("/workspaces", 200, """
                {"values": [{"slug": "emptyteam"}]}""");
        respond("/repositories/emptyteam", 200, """
                {"values": []}""");

        BitbucketResult<List<BitbucketRepository>> result = client.listRepositories("token");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    @Test
    void aFailureListingOneWorkspacesRepositoriesFailsTheWholeCall() {
        respond("/workspaces", 200, """
                {"values": [{"slug": "brokenteam"}]}""");
        respond("/repositories/brokenteam", 500, """
                {"type": "error", "error": {"message": "Internal server error"}}""");

        BitbucketResult<List<BitbucketRepository>> result = client.listRepositories("token");

        assertFalse(result.isOk());
    }

    // ------------------------------------------------------------------
    // listPullRequests
    // ------------------------------------------------------------------

    @Test
    void pullRequestsAreParsedWithTheFieldsTheCheckoutFlowNeeds() {
        respond("/repositories/atlassian/localstack/pullrequests", 200, """
                {"values": [
                  {
                    "id": 124,
                    "title": "Security upgrade",
                    "author": {"username": "atlassian", "display_name": "Atlassian"},
                    "source": {"branch": {"name": "snyk-fix-66078f227796d712b7b9a4bc0e7d8599"},
                               "repository": {"full_name": "atlassian/localstack"}},
                    "destination": {"branch": {"name": "master"},
                                     "repository": {"full_name": "atlassian/localstack"}},
                    "links": {"html": {"href": "https://bitbucket.org/atlassian/localstack/pull-requests/124"}}
                  }
                ]}""");

        BitbucketResult<List<BitbucketPullRequest>> result =
                client.listPullRequests("token", "atlassian", "localstack");

        assertTrue(result.isOk(), result.messageOrNull());
        BitbucketPullRequest pr = result.orNull().get(0);
        assertEquals(124, pr.id());
        assertEquals("Security upgrade", pr.title());
        assertEquals("atlassian", pr.author().username());
        assertEquals("snyk-fix-66078f227796d712b7b9a4bc0e7d8599", pr.source().branch().name());
        assertEquals("master", pr.destination().branch().name());
        assertEquals("https://bitbucket.org/atlassian/localstack/pull-requests/124", pr.htmlUrl());
        assertFalse(pr.isFromFork(), "source and destination are the same repository");
    }

    @Test
    void aPullRequestFromAForkIsDetectedByDifferingRepositoryFullNames() {
        respond("/repositories/atlassian/localstack/pullrequests", 200, """
                {"values": [
                  {
                    "id": 200,
                    "title": "Fix from a fork",
                    "author": {"username": "someuser", "display_name": "Some User"},
                    "source": {"branch": {"name": "fix-branch"},
                               "repository": {"full_name": "someuser/localstack"}},
                    "destination": {"branch": {"name": "master"},
                                     "repository": {"full_name": "atlassian/localstack"}},
                    "links": {"html": {"href": "https://bitbucket.org/atlassian/localstack/pull-requests/200"}}
                  }
                ]}""");

        BitbucketPullRequest pr = client.listPullRequests("token", "atlassian", "localstack").orNull().get(0);

        assertTrue(pr.isFromFork());
        assertEquals("https://bitbucket.org/someuser/localstack.git", pr.source().repository().cloneUrl());
    }

    @Test
    void anEmptyPullRequestListIsNotAFailure() {
        respond("/repositories/atlassian/localstack/pullrequests", 200, """
                {"values": []}""");

        BitbucketResult<List<BitbucketPullRequest>> result =
                client.listPullRequests("token", "atlassian", "localstack");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    // ------------------------------------------------------------------
    // mergePullRequest
    // ------------------------------------------------------------------

    @Test
    void mergingSendsAPostWithTheChosenStrategyAndParsesTheMergeCommitHash() {
        respond("/repositories/atlassian/localstack/pullrequests/124/merge", 200, """
                {"merge_commit": {"hash": "abc1234"}}""");

        BitbucketResult<String> result =
                client.mergePullRequest("token", "atlassian", "localstack", 124, "squash");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("abc1234", result.orNull());
        assertEquals("POST", lastMethod);
        assertTrue(lastRequestBody.contains("\"merge_strategy\":\"squash\""), lastRequestBody);
    }

    @Test
    void anUnmergeablePullRequestSurfacesBitbucketsOwnMessage() {
        respond("/repositories/atlassian/localstack/pullrequests/124/merge", 409, """
                {"error": {"message": "This pull request has conflicts and cannot be merged."}}""");

        BitbucketResult<String> result =
                client.mergePullRequest("token", "atlassian", "localstack", 124, "merge_commit");

        assertFalse(result.isOk());
        assertEquals("This pull request has conflicts and cannot be merged.", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // createPullRequest
    // ------------------------------------------------------------------

    @Test
    void creatingAPullRequestSendsAPostAndParsesTheNewPullRequest() {
        respond("/repositories/atlassian/localstack/pullrequests", 201, """
                {"id": 201, "title": "Security upgrade", "author": {"username": "atlassian", "display_name": "Atlassian"},
                 "source": {"branch": {"name": "fix-branch"}, "repository": {"full_name": "atlassian/localstack"}},
                 "destination": {"branch": {"name": "master"}, "repository": {"full_name": "atlassian/localstack"}},
                 "links": {"html": {"href": "https://bitbucket.org/atlassian/localstack/pull-requests/201"}}}""");

        BitbucketResult<BitbucketPullRequest> result = client.createPullRequest(
                "token", "atlassian", "localstack", "Security upgrade", "Bumps the dependency",
                "fix-branch", "master");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(201, result.orNull().id());
        assertEquals("POST", lastMethod);
        assertTrue(lastRequestBody.contains("\"source\":{\"branch\":{\"name\":\"fix-branch\"}}"), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"destination\":{\"branch\":{\"name\":\"master\"}}"), lastRequestBody);
    }

    @Test
    void aPullRequestWithNothingToMergeSurfacesBitbucketsOwnMessage() {
        respond("/repositories/atlassian/localstack/pullrequests", 400, """
                {"error": {"message": "Source and destination branches must be different."}}""");

        BitbucketResult<BitbucketPullRequest> result = client.createPullRequest(
                "token", "atlassian", "localstack", "Nothing to merge", null, "master", "master");

        assertFalse(result.isOk());
        assertEquals("Source and destination branches must be different.", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // getCommitStatus
    // ------------------------------------------------------------------

    @Test
    void aCommitWithNoStatusesIsReportedAsNone() {
        respond("/repositories/atlassian/localstack/commit/abc123/statuses", 200, """
                {"values": []}""");

        var result = client.getCommitStatus("token", "atlassian", "localstack", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.NONE, result.orNull());
    }

    @Test
    void allStatusesSucceedingIsReportedAsPassed() {
        respond("/repositories/atlassian/localstack/commit/abc123/statuses", 200, """
                {"values": [{"state": "SUCCESSFUL"}]}""");

        var result = client.getCommitStatus("token", "atlassian", "localstack", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.PASSED, result.orNull());
    }

    @Test
    void anInProgressBuildIsReportedAsPending() {
        respond("/repositories/atlassian/localstack/commit/abc123/statuses", 200, """
                {"values": [{"state": "SUCCESSFUL"}, {"state": "INPROGRESS"}]}""");

        var result = client.getCommitStatus("token", "atlassian", "localstack", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.PENDING, result.orNull());
    }

    @Test
    void oneStoppedBuildWinsOverAnInProgressOne() {
        respond("/repositories/atlassian/localstack/commit/abc123/statuses", 200, """
                {"values": [{"state": "STOPPED"}, {"state": "INPROGRESS"}]}""");

        var result = client.getCommitStatus("token", "atlassian", "localstack", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.FAILED, result.orNull());
    }

    // ------------------------------------------------------------------
    // listComments / createComment
    // ------------------------------------------------------------------

    @Test
    void listCommentsParsesAnInlineCommentsAnchor() {
        respond("/repositories/atlassian/localstack/pullrequests/124/comments", 200, """
                {"values": [
                    {"id": 1, "content": {"raw": "typo here"},
                     "user": {"display_name": "octocat"},
                     "inline": {"path": "src/Main.java", "to": 10, "from": null}}
                ]}""");

        var result = client.listComments("token", "atlassian", "localstack", 124);

        assertTrue(result.isOk(), result.messageOrNull());
        BitbucketComment comment = result.orNull().get(0);
        assertEquals("typo here", comment.content().raw());
        assertEquals("src/Main.java", comment.inline().path());
        assertEquals(10, comment.inline().to());
        assertNull(comment.inline().from());
    }

    @Test
    void anEmptyCommentListIsNotAFailure() {
        respond("/repositories/atlassian/localstack/pullrequests/124/comments", 200, """
                {"values": []}""");

        var result = client.listComments("token", "atlassian", "localstack", 124);

        assertTrue(result.isOk(), result.messageOrNull());
        assertTrue(result.orNull().isEmpty());
    }

    @Test
    void creatingAnInlineCommentSendsThePathAndLineAndParsesTheResponse() {
        respond("/repositories/atlassian/localstack/pullrequests/124/comments", 201, """
                {"id": 99, "content": {"raw": "Please rename this"},
                 "user": {"display_name": "octocat"},
                 "inline": {"path": "src/Main.java", "to": 10, "from": null}}""");

        var result = client.createComment("token", "atlassian", "localstack", 124,
                "src/Main.java", 10, null, "Please rename this");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(99, result.orNull().id());
        assertEquals("POST", lastMethod);
        assertTrue(lastRequestBody.contains("\"path\":\"src/Main.java\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"to\":10"), lastRequestBody);
    }

    @Test
    void creatingAPlainTopLevelCommentSendsNoInlineAnchorAtAll() {
        respond("/repositories/atlassian/localstack/pullrequests/124/comments", 201, """
                {"id": 100, "content": {"raw": "overall looks good"},
                 "user": {"display_name": "octocat"},
                 "inline": null}""");

        var result = client.createComment("token", "atlassian", "localstack", 124,
                null, null, null, "overall looks good");

        assertTrue(result.isOk(), result.messageOrNull());
        assertNull(result.orNull().inline());
        assertFalse(lastRequestBody.contains("\"inline\":{"), lastRequestBody);
    }

    @Test
    void creatingACommentOnAnUnknownLineSurfacesBitbucketsOwnMessage() {
        respond("/repositories/atlassian/localstack/pullrequests/124/comments", 400, """
                {"type": "error", "error": {"message": "Line number is not part of the diff"}}""");

        var result = client.createComment("token", "atlassian", "localstack", 124,
                "src/Main.java", 999, null, "comment");

        assertFalse(result.isOk());
        assertEquals("Line number is not part of the diff", result.messageOrNull());
    }
}
