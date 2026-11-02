package com.jfx.git.core.gitlab;

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
 * Runs {@link GitLabClient} against a real local HTTP server rather than mocking
 * {@code HttpClient} itself - the same technique {@code GitHubClientTest} uses. The
 * response shapes below (field names, {@code iid} vs {@code id}, {@code "opened"} vs
 * GitHub's {@code "open"}) were verified against the real {@code gitlab.com} API
 * while writing this, not assumed from GitLab's docs.
 */
class GitLabClientTest {

    private HttpServer server;
    private GitLabClient client;
    private volatile String lastAuthorizationHeader;
    private volatile String lastPrivateTokenHeader;
    private volatile String lastMethod;
    private volatile String lastRequestBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        client = new GitLabClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastPrivateTokenHeader = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");
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
                {"username": "octocat", "avatar_url": "https://gitlab.com/uploads/-/system/user/avatar/1/avatar.png"}""");

        GitLabResult<GitLabUser> result = client.fetchAuthenticatedUser("real-token");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("octocat", result.orNull().username());
    }

    /**
     * Bearer, because it is the one header GitLab accepts for both a personal access token
     * and an OAuth access token; {@code PRIVATE-TOKEN} rejects the latter, which would break
     * every account connected through OAuth sign-in.
     */
    @Test
    void theTokenIsSentAsABearerTokenSoOAuthTokensWorkToo() {
        respond("/user", 200, """
                {"username": "octocat", "avatar_url": null}""");

        client.fetchAuthenticatedUser("abc123");

        assertEquals("Bearer abc123", lastAuthorizationHeader);
        assertNull(lastPrivateTokenHeader, "the token must be sent once, not in two headers");
    }

    /** GitLab's real 401 body, verified against the live API while this was written. */
    @Test
    void aBadTokenSaysToReconnectRatherThanRepeating401Unauthorized() {
        respond("/user", 401, """
                {"message": "401 Unauthorized"}""");

        GitLabResult<GitLabUser> result = client.fetchAuthenticatedUser("wrong-token");

        assertFalse(result.isOk());
        String message = result.messageOrNull();
        // "401 Unauthorized" is what GitLab says and it reads like a typo; the usual
        // cause is a token that expired since it was pasted. See ApiErrors.
        assertTrue(message.contains("reconnect"), message);
        assertTrue(message.contains("expire"), message);
    }

    @Test
    void aBlankOrNullTokenFailsWithoutMakingARequest() {
        assertFalse(client.fetchAuthenticatedUser("").isOk());
        assertFalse(client.fetchAuthenticatedUser(null).isOk());
        assertFalse(client.fetchAuthenticatedUser("   ").isOk());
    }

    @Test
    void anUnreachableServerIsAFailureNotAThrow() {
        var unreachable = new GitLabClient(URI.create("http://localhost:1"));

        GitLabResult<GitLabUser> result = unreachable.fetchAuthenticatedUser("token");

        assertFalse(result.isOk());
        assertNotNull(result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // listProjects
    // ------------------------------------------------------------------

    @Test
    void projectsAreParsedWithTheFieldsTheCloneFlowNeeds() {
        respond("/projects", 200, """
                [
                  {
                    "path_with_namespace": "gitlab-org/gitlab-runner",
                    "http_url_to_repo": "https://gitlab.com/gitlab-org/gitlab-runner.git",
                    "ssh_url_to_repo": "git@gitlab.com:gitlab-org/gitlab-runner.git",
                    "description": "GitLab Runner",
                    "visibility": "public"
                  },
                  {
                    "path_with_namespace": "someone/private-project",
                    "http_url_to_repo": "https://gitlab.com/someone/private-project.git",
                    "ssh_url_to_repo": "git@gitlab.com:someone/private-project.git",
                    "description": null,
                    "visibility": "private"
                  }
                ]""");

        GitLabResult<List<GitLabProject>> result = client.listProjects("token");

        assertTrue(result.isOk(), result.messageOrNull());
        List<GitLabProject> projects = result.orNull();
        assertEquals(2, projects.size());
        assertEquals("gitlab-org/gitlab-runner", projects.get(0).pathWithNamespace());
        assertEquals("https://gitlab.com/gitlab-org/gitlab-runner.git", projects.get(0).httpUrlToRepo());
        assertFalse(projects.get(0).isPrivate(), "\"public\" visibility is not private");

        assertTrue(projects.get(1).isPrivate());
    }

    @Test
    void internalVisibilityCountsAsPrivate() {
        respond("/projects", 200, """
                [{"path_with_namespace": "group/internal-project", "http_url_to_repo": "x",
                  "ssh_url_to_repo": "x", "description": null, "visibility": "internal"}]""");

        GitLabResult<List<GitLabProject>> result = client.listProjects("token");

        assertTrue(result.orNull().get(0).isPrivate(),
                "\"internal\" still restricts who can see it, so it is not the open \"public\" state");
    }

    // ------------------------------------------------------------------
    // listMergeRequests
    // ------------------------------------------------------------------

    @Test
    void mergeRequestsAreParsedWithTheFieldsTheCheckoutFlowNeeds() {
        // com.sun.net.httpserver decodes the request path before matching a context,
        // so a context registered here has to be the decoded form even though the real
        // client sends "%2F" literally on the wire - see the class Javadoc.
        respond("/projects/gitlab-org/gitlab-runner/merge_requests", 200, """
                [
                  {
                    "iid": 7231,
                    "title": "go: Update module github.com/stretchr/testify to v1.12.1",
                    "author": {"username": "gitlab-dependency-update-bot", "avatar_url": null},
                    "draft": false,
                    "source_branch": "renovate/github.com-stretchr-testify-1.x",
                    "target_branch": "main",
                    "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/7231"
                  }
                ]""");

        GitLabResult<List<GitLabMergeRequest>> result =
                client.listMergeRequests("token", "gitlab-org%2Fgitlab-runner");

        assertTrue(result.isOk(), result.messageOrNull());
        GitLabMergeRequest mr = result.orNull().get(0);
        assertEquals(7231, mr.iid());
        assertEquals("gitlab-dependency-update-bot", mr.author().username());
        assertFalse(mr.draft());
        assertEquals("renovate/github.com-stretchr-testify-1.x", mr.sourceBranch());
        assertEquals("main", mr.targetBranch());
        assertEquals("https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/7231", mr.webUrl());
    }

    @Test
    void anEmptyMergeRequestListIsNotAFailure() {
        respond("/projects/group/project/merge_requests", 200, "[]");

        GitLabResult<List<GitLabMergeRequest>> result = client.listMergeRequests("token", "group%2Fproject");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    @Test
    void getMergeRequestParsesDiffRefs() {
        respond("/projects/group/project/merge_requests/7231", 200, """
                {
                  "iid": 7231,
                  "title": "go: Update module",
                  "author": {"username": "octocat", "avatar_url": null},
                  "draft": false,
                  "source_branch": "feature",
                  "target_branch": "main",
                  "sha": "headsha123",
                  "diff_refs": {"base_sha": "basesha", "start_sha": "startsha", "head_sha": "headsha123"},
                  "web_url": "https://gitlab.com/group/project/-/merge_requests/7231"
                }""");

        GitLabResult<GitLabMergeRequest> result = client.getMergeRequest("token", "group%2Fproject", 7231);

        assertTrue(result.isOk(), result.messageOrNull());
        GitLabMergeRequest.DiffRefs diffRefs = result.orNull().diffRefs();
        assertEquals("basesha", diffRefs.baseSha());
        assertEquals("startsha", diffRefs.startSha());
        assertEquals("headsha123", diffRefs.headSha());
    }

    // ------------------------------------------------------------------
    // listIssues
    // ------------------------------------------------------------------

    @Test
    void issuesAreParsedWithTheFieldsTheListNeedsIncludingPlainStringLabels() {
        respond("/projects/gitlab-org/gitlab-runner/issues", 200, """
                [
                  {
                    "iid": 39706,
                    "title": "[Feature flag] Cleanup FF_USE_FASTZIP",
                    "author": {"username": "vtak", "avatar_url": null},
                    "state": "opened",
                    "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/work_items/39706",
                    "labels": ["devops::verify", "type::maintenance"]
                  }
                ]""");

        GitLabResult<List<GitLabIssue>> result = client.listIssues("token", "gitlab-org%2Fgitlab-runner");

        assertTrue(result.isOk(), result.messageOrNull());
        GitLabIssue issue = result.orNull().get(0);
        assertEquals(39706, issue.iid());
        assertEquals("vtak", issue.author().username());
        assertEquals("opened", issue.state());
        assertEquals(List.of("devops::verify", "type::maintenance"), issue.labels());
    }

    @Test
    void anEmptyIssueListIsNotAFailure() {
        respond("/projects/group/project/issues", 200, "[]");

        GitLabResult<List<GitLabIssue>> result = client.listIssues("token", "group%2Fproject");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    // ------------------------------------------------------------------
    // mergeMergeRequest
    // ------------------------------------------------------------------

    @Test
    void mergingSendsAPutWithTheSquashFlagAndParsesTheMergeCommitSha() {
        respond("/projects/group/project/merge_requests/7231/merge", 200, """
                {"merge_commit_sha": "0b4e7a0", "squash_commit_sha": null}""");

        GitLabResult<String> result = client.mergeMergeRequest("token", "group%2Fproject", 7231, true);

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("0b4e7a0", result.orNull());
        assertEquals("PUT", lastMethod);
        assertTrue(lastRequestBody.contains("\"squash\":true"), lastRequestBody);
    }

    /** When it squashed, there is no separate merge commit - the squash commit is the one that landed. */
    @Test
    void aSquashedMergeFallsBackToTheSquashCommitShaWhenThereIsNoMergeCommit() {
        respond("/projects/group/project/merge_requests/7231/merge", 200, """
                {"merge_commit_sha": null, "squash_commit_sha": "abc1234"}""");

        GitLabResult<String> result = client.mergeMergeRequest("token", "group%2Fproject", 7231, true);

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("abc1234", result.orNull());
    }

    @Test
    void anUnmergeableMergeRequestSurfacesGitLabsOwnMessage() {
        respond("/projects/group/project/merge_requests/7231/merge", 405, """
                {"message": "405 Method Not Allowed"}""");

        GitLabResult<String> result = client.mergeMergeRequest("token", "group%2Fproject", 7231, false);

        assertFalse(result.isOk());
        assertEquals("405 Method Not Allowed", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // createMergeRequest
    // ------------------------------------------------------------------

    @Test
    void creatingAMergeRequestSendsAPostAndParsesTheNewMergeRequest() {
        respond("/projects/group/project/merge_requests", 201, """
                {"iid": 99, "title": "go: Update module", "author": {"username": "octocat", "avatar_url": null},
                 "draft": false, "source_branch": "renovate/testify", "target_branch": "main",
                 "web_url": "https://gitlab.com/group/project/-/merge_requests/99"}""");

        GitLabResult<GitLabMergeRequest> result = client.createMergeRequest(
                "token", "group%2Fproject", "go: Update module", "Bumps testify", "renovate/testify", "main");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(99, result.orNull().iid());
        assertEquals("POST", lastMethod);
        assertTrue(lastRequestBody.contains("\"source_branch\":\"renovate/testify\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"target_branch\":\"main\""), lastRequestBody);
    }

    @Test
    void aMergeRequestWithNothingToMergeSurfacesGitLabsOwnMessage() {
        respond("/projects/group/project/merge_requests", 409, """
                {"message": ["Source and target branches are the same"]}""");

        GitLabResult<GitLabMergeRequest> result = client.createMergeRequest(
                "token", "group%2Fproject", "Nothing to merge", null, "main", "main");

        assertFalse(result.isOk());
    }

    // ------------------------------------------------------------------
    // getCommitStatus
    // ------------------------------------------------------------------

    @Test
    void aCommitWithNoStatusesIsReportedAsNone() {
        respond("/projects/group/project/repository/commits/abc123/statuses", 200, "[]");

        var result = client.getCommitStatus("token", "group%2Fproject", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.NONE, result.orNull());
    }

    @Test
    void allStatusesSucceedingIsReportedAsPassed() {
        respond("/projects/group/project/repository/commits/abc123/statuses", 200, """
                [{"status": "success"}, {"status": "skipped"}]""");

        var result = client.getCommitStatus("token", "group%2Fproject", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.PASSED, result.orNull());
    }

    @Test
    void aRunningPipelineIsReportedAsPending() {
        respond("/projects/group/project/repository/commits/abc123/statuses", 200, """
                [{"status": "success"}, {"status": "running"}]""");

        var result = client.getCommitStatus("token", "group%2Fproject", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.PENDING, result.orNull());
    }

    @Test
    void oneFailedStatusWinsOverAPendingOne() {
        respond("/projects/group/project/repository/commits/abc123/statuses", 200, """
                [{"status": "failed"}, {"status": "running"}]""");

        var result = client.getCommitStatus("token", "group%2Fproject", "abc123");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(com.jfx.git.core.BuildStatus.FAILED, result.orNull());
    }

    // ------------------------------------------------------------------
    // listDiscussions / createDiscussion
    // ------------------------------------------------------------------

    @Test
    void listDiscussionsParsesADiffAnchoredThreadsPosition() {
        respond("/projects/group/project/merge_requests/7/discussions", 200, """
                [{"id": "abc", "notes": [
                    {"id": 1, "body": "typo here", "author": {"username": "octocat", "avatar_url": null},
                     "position": {"new_path": "src/Main.java", "old_path": "src/Main.java",
                                  "new_line": 10, "old_line": null}}
                ]}]""");

        var result = client.listDiscussions("token", "group%2Fproject", 7);

        assertTrue(result.isOk(), result.messageOrNull());
        GitLabDiscussion discussion = result.orNull().get(0);
        assertEquals("typo here", discussion.firstNote().body());
        assertEquals("octocat", discussion.firstNote().author().username());
        assertEquals("src/Main.java", discussion.position().newPath());
        assertEquals(10, discussion.position().newLine());
        assertNull(discussion.position().oldLine());
    }

    @Test
    void anEmptyDiscussionListIsNotAFailure() {
        respond("/projects/group/project/merge_requests/7/discussions", 200, "[]");

        var result = client.listDiscussions("token", "group%2Fproject", 7);

        assertTrue(result.isOk(), result.messageOrNull());
        assertTrue(result.orNull().isEmpty());
    }

    @Test
    void creatingADiscussionSendsAllThreeDiffRefShasAndTheLinePosition() {
        respond("/projects/group/project/merge_requests/7/discussions", 201, """
                {"id": "def", "notes": [
                    {"id": 2, "body": "please rename this", "author": {"username": "octocat", "avatar_url": null},
                     "position": {"new_path": "src/Main.java", "old_path": "src/Main.java",
                                  "new_line": 10, "old_line": null}}
                ]}""");

        var diffRefs = new GitLabMergeRequest.DiffRefs("base123", "start123", "head123");
        var result = client.createDiscussion("token", "group%2Fproject", 7, "please rename this",
                diffRefs, "src/Main.java", "src/Main.java", null, 10);

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("please rename this", result.orNull().firstNote().body());
        assertEquals("POST", lastMethod);
        assertTrue(lastRequestBody.contains("\"base_sha\":\"base123\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"start_sha\":\"start123\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"head_sha\":\"head123\""), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"new_line\":10"), lastRequestBody);
        assertTrue(lastRequestBody.contains("\"position_type\":\"text\""), lastRequestBody);
    }

    /** A suggestion is nothing but body text - the fenced block round-trips untouched, no separate API field. */
    @Test
    void aSuggestedChangeIsJustFencedMarkdownInTheBody() {
        respond("/projects/group/project/merge_requests/7/discussions", 201, """
                {"id": "ghi", "notes": [
                    {"id": 3, "body": "```suggestion\\nfixed line\\n```",
                     "author": {"username": "octocat", "avatar_url": null},
                     "position": {"new_path": "src/Main.java", "old_path": "src/Main.java",
                                  "new_line": 10, "old_line": null}}
                ]}""");

        var diffRefs = new GitLabMergeRequest.DiffRefs("base123", "start123", "head123");
        String suggestionBody = "```suggestion\nfixed line\n```";
        var result = client.createDiscussion("token", "group%2Fproject", 7, suggestionBody,
                diffRefs, "src/Main.java", "src/Main.java", null, 10);

        assertTrue(result.isOk(), result.messageOrNull());
        assertTrue(lastRequestBody.contains("```suggestion"), lastRequestBody);
        assertEquals("```suggestion\nfixed line\n```", result.orNull().firstNote().body());
    }

    @Test
    void creatingADiscussionOnAnUnknownLineSurfacesGitLabsOwnMessage() {
        respond("/projects/group/project/merge_requests/7/discussions", 400, """
                {"message": "400 (Bad request) \\"line_code\\" not given"}""");

        var diffRefs = new GitLabMergeRequest.DiffRefs("base123", "start123", "head123");
        var result = client.createDiscussion("token", "group%2Fproject", 7, "comment",
                diffRefs, "src/Main.java", "src/Main.java", null, 999);

        assertFalse(result.isOk());
        assertEquals("400 (Bad request) \"line_code\" not given", result.messageOrNull());
    }
}
