package com.jfx.git.core.gitea;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jfx.git.core.ApiFailure;
import com.jfx.git.core.ApiPaging;
import com.jfx.git.core.BuildStatus;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link GiteaClient} against a real local HTTP server rather than a mocked
 * {@code HttpClient}, so what is proven is the client's own request and response handling:
 * the paths and queries it sends, the JSON bodies it writes, and how it reads Gitea's
 * answers. The response shapes follow Gitea's and Forgejo's API definition
 * ({@code /swagger.v1.json}): {@code APIError} is {@code {"message", "url"}}, a merge
 * answers with an empty body, and lists page with {@code Link} and {@code X-Total-Count}.
 */
class GiteaClientTest {

    /** One request the fake server received. */
    private record Request(String method, String path, String query, String authorization, String accept,
                           String body) {
        /** The query parameter {@code name}, or null. */
        String param(String name) {
            if (query == null) return null;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
            }
            return null;
        }
    }

    /** A canned answer. */
    private record Answer(int status, String body, Map<String, String> headers) {
    }

    private HttpServer server;
    private GiteaClient client;
    private String base;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    /** Keyed by {@code "METHOD /path"} or, for one page of a list, {@code "METHOD /path#page"}. */
    private final Map<String, Answer> answers = new HashMap<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), query,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Accept"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            String key = request.method() + " " + request.path();
            Answer answer = answers.get(key + "#" + request.param("page"));
            if (answer == null) answer = answers.get(key);
            if (answer == null) answer = new Answer(404, "{\"message\":\"The target couldn't be found.\"}", Map.of());
            answer.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
        client = new GiteaClient(URI.create(base));
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void on(String method, String path, int status, String body) {
        on(method, path, status, body, Map.of());
    }

    private void on(String method, String path, int status, String body, Map<String, String> headers) {
        answers.put(method + " " + path, new Answer(status, body, headers));
    }

    private void onPage(String path, int page, String body, Map<String, String> headers) {
        answers.put("GET " + path + "#" + page, new Answer(200, body, headers));
    }

    private Request only() {
        assertEquals(1, requests.size(), () -> "expected one request, got " + requests);
        return requests.get(0);
    }

    private static JsonObject json(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String nextLink(String path, int page) {
        return "<" + base + path + "?page=" + page + "&limit=50>; rel=\"next\"";
    }

    // ------------------------------------------------------------------
    // Authentication and failures
    // ------------------------------------------------------------------

    @Test
    void aValidTokenReturnsTheAuthenticatedUser() {
        on("GET", "/user", 200, """
                {"id": 1, "login": "forgejo-fan", "full_name": "F. Fan",
                 "avatar_url": "https://codeberg.org/avatars/abc"}""");

        GiteaResult<GiteaUser> result = client.fetchAuthenticatedUser("real-token");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("forgejo-fan", result.orNull().login());
        assertEquals("https://codeberg.org/avatars/abc", result.orNull().avatarUrl());
    }

    @Test
    void theTokenIsSentAsABearerHeaderAndJsonIsAskedFor() {
        on("GET", "/user", 200, "{\"login\": \"u\"}");

        client.fetchAuthenticatedUser("abc123");

        assertEquals("Bearer abc123", only().authorization());
        assertEquals("application/json", only().accept());
    }

    /** A self-hosted server's API root has a path, which a request must extend, not replace. */
    @Test
    void theApiPathOfTheBaseIsKept() {
        var withPath = new GiteaClient(URI.create(base + "/api/v1"));
        on("GET", "/api/v1/user", 200, "{\"login\": \"u\"}");

        assertTrue(withPath.fetchAuthenticatedUser("t").isOk());
        assertEquals("/api/v1/user", only().path());
    }

    @Test
    void aBlankOrNullTokenFailsWithoutMakingARequest() {
        assertFalse(client.fetchAuthenticatedUser("").isOk());
        assertFalse(client.fetchAuthenticatedUser(null).isOk());
        assertFalse(client.fetchAuthenticatedUser("   ").isOk());
        assertTrue(requests.isEmpty());
    }

    @Test
    void a401IsTheOneFailureThatMeansTheTokenIsNoGood() {
        on("GET", "/user", 401, "{\"message\":\"user does not exist [uid: 0, name: ]\",\"url\":\"x\"}");

        GiteaResult<GiteaUser> result = client.fetchAuthenticatedUser("stale");

        assertEquals(ApiFailure.AUTH_REJECTED, result.failureKind());
        assertFalse(result.failureKind().keepsCredentials());
        assertTrue(result.messageOrNull().contains("reconnect"), result.messageOrNull());
    }

    @Test
    void aPlain403KeepsTheCredential() {
        on("GET", "/user", 403, "{\"message\":\"token does not have at least one of required scope(s): [read:user]\"}");

        GiteaResult<GiteaUser> result = client.fetchAuthenticatedUser("narrow");

        assertEquals(ApiFailure.OTHER, result.failureKind());
        assertTrue(result.failureKind().keepsCredentials());
        assertTrue(result.messageOrNull().contains("required scope"), result.messageOrNull());
    }

    @Test
    void aServerErrorKeepsTheCredential() {
        on("GET", "/user", 503, "upstream is having a bad day");

        GiteaResult<GiteaUser> result = client.fetchAuthenticatedUser("fine");

        assertEquals(ApiFailure.SERVER, result.failureKind());
        assertTrue(result.messageOrNull().contains("503"), result.messageOrNull());
    }

    /** Gitea has no API rate limit of its own, but a public server's proxy answers 429. */
    @Test
    void a429IsARateLimitThatSaysWhenItEnds() {
        on("GET", "/user", 429, "Too Many Requests", Map.of("Retry-After", "45"));

        GiteaResult<GiteaUser> result = client.fetchAuthenticatedUser("fine");

        assertEquals(ApiFailure.RATE_LIMITED, result.failureKind());
        assertTrue(result.messageOrNull().contains("45 seconds"), result.messageOrNull());
    }

    @Test
    void anUnreachableServerIsATransportFailureNotARejection() {
        var unreachable = new GiteaClient(URI.create("http://localhost:1"));

        GiteaResult<GiteaUser> result = unreachable.fetchAuthenticatedUser("token");

        assertEquals(ApiFailure.TRANSPORT, result.failureKind());
        assertTrue(result.messageOrNull().startsWith("Could not reach localhost"), result.messageOrNull());
    }

    @Test
    void malformedJsonOnASuccessStatusIsAFailureNotAThrow() {
        on("GET", "/user", 200, "not json at all {{{");

        assertFalse(client.fetchAuthenticatedUser("token").isOk());
    }

    // ------------------------------------------------------------------
    // Repositories and paging
    // ------------------------------------------------------------------

    @Test
    void repositoriesAreParsedWithTheFieldsTheCloneFlowNeeds() {
        on("GET", "/user/repos", 200, """
                [{"id": 5, "name": "demo", "full_name": "alice/demo",
                  "owner": {"login": "alice"}, "description": "A demo",
                  "private": true, "fork": false, "default_branch": "main",
                  "html_url": "https://codeberg.org/alice/demo",
                  "clone_url": "https://codeberg.org/alice/demo.git",
                  "ssh_url": "git@codeberg.org:alice/demo.git"}]""");

        var result = client.listRepositories("t");

        assertTrue(result.isOk(), result.messageOrNull());
        GiteaRepository repo = result.orNull().get(0);
        assertEquals("alice/demo", repo.fullName());
        assertEquals("https://codeberg.org/alice/demo.git", repo.cloneUrl());
        assertEquals("git@codeberg.org:alice/demo.git", repo.sshUrl());
        assertEquals("A demo", repo.description());
        assertTrue(repo.isPrivate());
        assertEquals("demo", repo.name());
        assertEquals("alice", repo.owner().login());
        assertEquals("main", repo.defaultBranch());
        assertEquals("https://codeberg.org/alice/demo", repo.htmlUrl());
        assertFalse(repo.fork());
    }

    @Test
    void everyPageOfRepositoriesIsReadFollowingTheLinkHeader() {
        onPage("/user/repos", 1, "[{\"full_name\": \"a/one\"}]", Map.of("Link", nextLink("/user/repos", 2)));
        onPage("/user/repos", 2, "[{\"full_name\": \"a/two\"}]", Map.of());

        var result = client.listRepositories("t");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(List.of("a/one", "a/two"), result.orNull().stream().map(GiteaRepository::fullName).toList());
        assertEquals(2, requests.size());
        assertEquals("1", requests.get(0).param("page"));
        assertEquals("50", requests.get(0).param("limit"));
        assertEquals("2", requests.get(1).param("page"));
        assertTrue(requests.stream().allMatch(r -> "Bearer t".equals(r.authorization())));
    }

    /**
     * A server whose {@code ROOT_URL} is not the address it is reached on links its next
     * page on another host. That link is never followed - the token would go with it -
     * but {@code X-Total-Count} still says there is more, and the next page is asked for
     * here, by number.
     */
    @Test
    void aNextLinkOnAnotherHostIsNotFollowedButTheTotalCountStillPages() {
        onPage("/user/repos", 1, "[{\"full_name\": \"a/one\"}]", Map.of(
                "Link", "<https://elsewhere.example/api/v1/user/repos?page=2&limit=50>; rel=\"next\"",
                "X-Total-Count", "2"));
        onPage("/user/repos", 2, "[{\"full_name\": \"a/two\"}]", Map.of("X-Total-Count", "2"));

        var result = client.listRepositories("t");

        assertEquals(2, result.orNull().size());
        assertEquals(2, requests.size(), "both requests went to this server");
    }

    @Test
    void anEmptyPageEndsAListingWhateverTheHeadersSay() {
        onPage("/user/repos", 1, "[]", Map.of("Link", nextLink("/user/repos", 2)));

        var result = client.listRepositories("t");

        assertTrue(result.orNull().isEmpty());
        assertEquals(1, requests.size());
    }

    @Test
    void aListingThatNeverEndsStopsAtTheCap() {
        on("GET", "/user/repos", 200, "[{\"full_name\": \"a/same\"}]", Map.of("Link", nextLink("/user/repos", 2)));

        var result = client.listRepositories("t");

        assertEquals(ApiPaging.MAX_PAGES, requests.size());
        assertEquals(ApiPaging.MAX_PAGES, result.orNull().size());
    }

    @Test
    void aFailureOnALaterPageFailsTheListing() {
        onPage("/user/repos", 1, "[{\"full_name\": \"a/one\"}]", Map.of("X-Total-Count", "60"));
        answers.put("GET /user/repos#2", new Answer(500, "{\"message\":\"database is locked\"}", Map.of()));

        var result = client.listRepositories("t");

        assertFalse(result.isOk());
        assertEquals(ApiFailure.SERVER, result.failureKind());
        assertEquals("database is locked", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // Pull requests
    // ------------------------------------------------------------------

    private static final String PULL = """
            {"id": 99, "number": 7, "title": "Fix it", "state": "open",
             "user": {"login": "bob"}, "html_url": "https://codeberg.org/alice/demo/pulls/7",
             "mergeable": true, "merged": false,
             "head": {"label": "topic", "ref": "topic", "sha": "f00dcafe",
                      "repo": {"full_name": "bob/demo", "name": "demo", "owner": {"login": "bob"}}},
             "base": {"label": "main", "ref": "main", "sha": "deadbeef"}}""";

    @Test
    void aPullRequestPageIsParsedWithTheFieldsTheDialogNeeds() {
        on("GET", "/repos/alice/demo/pulls", 200, "[" + PULL + "]",
                Map.of("Link", nextLink("/repos/alice/demo/pulls", 3)));

        var result = client.listPullRequestsPage("t", "alice", "demo", 2, 50);

        assertTrue(result.isOk(), result.messageOrNull());
        assertTrue(result.orNull().hasMore());
        GiteaPullRequest pr = result.orNull().items().get(0);
        assertEquals(7, pr.number());
        assertEquals("Fix it", pr.title());
        assertEquals("bob", pr.user().login());
        assertEquals("topic", pr.head().ref());
        assertEquals("f00dcafe", pr.head().sha());
        assertEquals("bob/demo", pr.head().repo().fullName(), "a fork's pull request names the fork");
        assertEquals("main", pr.base().ref());
        assertEquals("deadbeef", pr.base().sha());
        assertEquals("https://codeberg.org/alice/demo/pulls/7", pr.htmlUrl());
        assertEquals("open", pr.state());
        assertTrue(pr.mergeable());
        assertFalse(pr.merged());
        assertFalse(pr.draft(), "a server without the field means not a draft");
        Request sent = only();
        assertEquals("open", sent.param("state"));
        assertEquals("2", sent.param("page"));
        assertEquals("50", sent.param("limit"));
    }

    @Test
    void theLastPullRequestPageSaysThereIsNoMore() {
        on("GET", "/repos/alice/demo/pulls", 200, "[" + PULL + "]", Map.of("X-Total-Count", "1"));

        assertFalse(client.listPullRequestsPage("t", "alice", "demo", 1, 50).orNull().hasMore());
    }

    @Test
    void anEmptyPullRequestListIsNotAFailure() {
        on("GET", "/repos/alice/demo/pulls", 200, "[]");

        var result = client.listPullRequests("t", "alice", "demo");

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    @Test
    void fetchPullRequestAsksForTheOnePullRequest() {
        on("GET", "/repos/alice/demo/pulls/7", 200, PULL.replace("f00dcafe", "newer000"));

        var result = client.fetchPullRequest("t", "alice", "demo", 7);

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("newer000", result.orNull().head().sha());
    }

    @Test
    void creatingAPullRequestPostsItsFieldsAndParsesTheAnswer() {
        on("POST", "/repos/alice/demo/pulls", 201, PULL);

        var result = client.createPullRequest("t", "alice", "demo", "Fix it", "Details", "topic", "main");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(7, result.orNull().number());
        JsonObject body = json(only().body());
        assertEquals("Fix it", body.get("title").getAsString());
        assertEquals("Details", body.get("body").getAsString());
        assertEquals("topic", body.get("head").getAsString());
        assertEquals("main", body.get("base").getAsString());
    }

    @Test
    void aDuplicatePullRequestSurfacesTheServersOwnMessage() {
        on("POST", "/repos/alice/demo/pulls", 409, """
                {"message": "pull request already exists for these targets [id: 4, issue_id: 9, head_repo_id: 5, base_repo_id: 5, head_branch: topic, base_branch: main]", "url": "x"}""");

        var result = client.createPullRequest("t", "alice", "demo", "Fix it", null, "topic", "main");

        assertFalse(result.isOk());
        assertTrue(result.messageOrNull().contains("already exists"), result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    @Test
    void mergingPostsTheStyleAsDoAndConfirmsTheEmptyAnswer() {
        on("POST", "/repos/alice/demo/pulls/7/merge", 200, "");

        var result = client.mergePullRequest("t", "alice", "demo", 7, "squash");

        assertTrue(result.isOk(), result.messageOrNull());
        assertTrue(result.orNull().contains("#7"), result.orNull());
        JsonObject body = json(only().body());
        assertEquals("squash", body.get("Do").getAsString());
        assertFalse(body.get("delete_branch_after_merge").getAsBoolean());
        assertFalse(body.has("MergeTitleField"), "Gitea's default title is kept unless one is given");
        assertFalse(body.has("MergeMessageField"));
        assertFalse(body.has("head_commit_id"));
    }

    @Test
    void everyMergeStyleIsPassedThroughAsGiteaSpellsIt() {
        on("POST", "/repos/alice/demo/pulls/7/merge", 200, "");

        for (String style : GiteaClient.MERGE_STYLES) {
            assertTrue(client.mergePullRequest("t", "alice", "demo", 7, style).isOk(), style);
        }

        assertEquals(List.of("merge", "rebase", "rebase-merge", "squash", "fast-forward-only"),
                requests.stream().map(r -> json(r.body()).get("Do").getAsString()).toList());
    }

    @Test
    void theFullMergeSendsTitleMessageBranchDeletionAndTheExpectedHead() {
        on("POST", "/repos/alice/demo/pulls/7/merge", 200, "");

        client.mergePullRequest("t", "alice", "demo", 7, "merge", true, "Merge it", "Because", "f00dcafe");

        JsonObject body = json(only().body());
        assertTrue(body.get("delete_branch_after_merge").getAsBoolean());
        assertEquals("Merge it", body.get("MergeTitleField").getAsString());
        assertEquals("Because", body.get("MergeMessageField").getAsString());
        assertEquals("f00dcafe", body.get("head_commit_id").getAsString());
    }

    /** Gitea's 405 often has no body; the message has to say what it means on its own. */
    @Test
    void a405SaysThePullRequestCannotBeMergedAsItStands() {
        on("POST", "/repos/alice/demo/pulls/7/merge", 405, "");

        var result = client.mergePullRequest("t", "alice", "demo", 7, "merge");

        assertFalse(result.isOk());
        String message = result.messageOrNull();
        assertTrue(message.contains("#7") && message.contains("cannot be merged"), message);
        assertTrue(message.contains("conflicts"), message);
        assertEquals(ApiFailure.OTHER, result.failureKind());
    }

    @Test
    void a405KeepsTheServersReasonWhenItGivesOne() {
        on("POST", "/repos/alice/demo/pulls/7/merge", 405, "{\"message\":\"Not all required status checks successful\"}");

        String message = client.mergePullRequest("t", "alice", "demo", 7, "merge").messageOrNull();

        assertTrue(message.contains("Not all required status checks successful"), message);
    }

    @Test
    void a409SaysThePullRequestChangedUnderneathTheMerge() {
        on("POST", "/repos/alice/demo/pulls/7/merge", 409, "{\"message\":\"merge failed because of conflict\"}");

        var result = client.mergePullRequest("t", "alice", "demo", 7, "rebase");

        String message = result.messageOrNull();
        assertTrue(message.contains("changed") && message.contains("new commits"), message);
        assertTrue(message.contains("merge failed because of conflict"), message);
    }

    @Test
    void aRejectedTokenDuringAMergeIsStillARejection() {
        on("POST", "/repos/alice/demo/pulls/7/merge", 401, "{\"message\":\"invalid token\"}");

        assertEquals(ApiFailure.AUTH_REJECTED,
                client.mergePullRequest("t", "alice", "demo", 7, "merge").failureKind());
    }

    // ------------------------------------------------------------------
    // Combined commit status
    // ------------------------------------------------------------------

    private BuildStatus statusFor(String state, int total) {
        answers.clear();
        on("GET", "/repos/alice/demo/commits/abc123/status", 200,
                "{\"state\": \"" + state + "\", \"sha\": \"abc123\", \"total_count\": " + total + ", \"statuses\": []}");
        var result = client.getCommitStatus("t", "alice", "demo", "abc123");
        assertTrue(result.isOk(), result.messageOrNull());
        return result.orNull();
    }

    @Test
    void eachCombinedStateMapsToTheSameStatusesGitHubUses() {
        assertEquals(BuildStatus.PASSED, statusFor("success", 2));
        assertEquals(BuildStatus.FAILED, statusFor("failure", 2));
        assertEquals(BuildStatus.FAILED, statusFor("error", 2));
        assertEquals(BuildStatus.PENDING, statusFor("pending", 2));
        assertEquals(BuildStatus.PENDING, statusFor("warning", 2), "a warning is amber, not a pass or a failure");
        assertEquals(BuildStatus.PASSED, statusFor("skipped", 1));
    }

    @Test
    void anUnknownStateWaitsRatherThanAccuses() {
        assertEquals(BuildStatus.PENDING, statusFor("something_added_later", 1));
    }

    @Test
    void aCommitWithNoStatusesHasNoCi() {
        assertEquals(BuildStatus.NONE, statusFor("", 0));
        assertEquals(BuildStatus.NONE, statusFor("pending", 0));
    }

    @Test
    void aFailureOnTheSecondPageOfStatusesIsNotLost() {
        String path = "/repos/alice/demo/commits/abc123/status";
        // A server whose page size is one: the roll-up on page one sees only the success.
        onPage(path, 1, "{\"state\": \"success\", \"total_count\": 1,"
                        + " \"statuses\": [{\"status\": \"success\", \"context\": \"lint\"}]}",
                Map.of("X-Total-Count", "2"));
        onPage(path, 2, "{\"state\": \"failure\", \"total_count\": 1,"
                        + " \"statuses\": [{\"status\": \"failure\", \"context\": \"test\"}]}",
                Map.of("X-Total-Count", "2"));

        assertEquals(BuildStatus.FAILED, client.getCommitStatus("t", "alice", "demo", "abc123").orNull());
        assertEquals(2, requests.size());
    }

    @Test
    void eachStatusOnAPageCounts() {
        on("GET", "/repos/alice/demo/commits/abc123/status", 200, """
                {"state": "warning", "total_count": 2, "statuses": [
                  {"status": "success", "context": "build"}, {"status": "warning", "context": "lint"}]}""");

        assertEquals(BuildStatus.PENDING, client.getCommitStatus("t", "alice", "demo", "abc123").orNull());
    }

    @Test
    void anUnreadableStatusIsAFailureNotAPass() {
        on("GET", "/repos/alice/demo/commits/abc123/status", 404, "{\"message\":\"Not Found\"}");

        var result = client.getCommitStatus("t", "alice", "demo", "abc123");

        assertFalse(result.isOk());
        assertEquals("Not Found", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // Review comments
    // ------------------------------------------------------------------

    @Test
    void reviewCommentsAreReadThroughEachReviewThatHasAny() {
        on("GET", "/repos/alice/demo/pulls/7/reviews", 200, """
                [{"id": 1, "state": "APPROVED", "comments_count": 0, "user": {"login": "carol"}},
                 {"id": 2, "state": "PENDING", "comments_count": 3, "user": {"login": "me"}},
                 {"id": 3, "state": "COMMENT", "comments_count": 2, "user": {"login": "dave"},
                  "commit_id": "f00dcafe"}]""");
        on("GET", "/repos/alice/demo/pulls/7/reviews/3/comments", 200, """
                [{"id": 31, "body": "Rename this", "path": "src/A.java", "position": 12, "original_position": 0,
                  "user": {"login": "dave"}, "commit_id": "f00dcafe", "pull_request_review_id": 3,
                  "html_url": "https://codeberg.org/alice/demo/pulls/7#issuecomment-31"},
                 {"id": 32, "body": "Why was this removed?", "path": "src/B.java", "position": 0,
                  "original_position": 4, "user": {"login": "dave"}, "commit_id": "f00dcafe",
                  "pull_request_review_id": 3}]""");

        var result = client.listReviewComments("t", "alice", "demo", 7);

        assertTrue(result.isOk(), result.messageOrNull());
        List<GiteaReviewComment> comments = result.orNull();
        assertEquals(2, comments.size());
        GiteaReviewComment onNew = comments.get(0);
        assertEquals(31, onNew.id());
        assertEquals("Rename this", onNew.body());
        assertEquals("src/A.java", onNew.path());
        assertEquals(12, onNew.line());
        assertEquals("RIGHT", onNew.side());
        assertEquals("dave", onNew.user().login());
        assertEquals("f00dcafe", onNew.commitId());
        assertEquals(3, onNew.reviewId());
        GiteaReviewComment onOld = comments.get(1);
        assertEquals(4, onOld.line());
        assertEquals("LEFT", onOld.side());
        // Review 1 has nothing to read and review 2 is somebody's unsubmitted draft.
        assertEquals(2, requests.size(), () -> "unexpected requests: " + requests);
    }

    @Test
    void noReviewsIsNoCommentsNotAFailure() {
        on("GET", "/repos/alice/demo/pulls/7/reviews", 200, "[]");

        var result = client.listReviewComments("t", "alice", "demo", 7);

        assertTrue(result.isOk());
        assertTrue(result.orNull().isEmpty());
    }

    @Test
    void postingACommentSubmitsACommentReviewHoldingIt() {
        on("POST", "/repos/alice/demo/pulls/7/reviews", 200, """
                {"id": 40, "state": "COMMENT", "body": "", "commit_id": "f00dcafe", "comments_count": 1,
                 "user": {"login": "me"}}""");

        var result = client.createReviewComment("t", "alice", "demo", 7, "f00dcafe", "src/A.java", 12, "RIGHT",
                "```suggestion\nbetter();\n```");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(40, result.orNull().id());
        JsonObject body = json(only().body());
        assertEquals("COMMENT", body.get("event").getAsString());
        assertEquals("f00dcafe", body.get("commit_id").getAsString());
        assertEquals("", body.get("body").getAsString());
        JsonObject line = body.getAsJsonArray("comments").get(0).getAsJsonObject();
        assertEquals("src/A.java", line.get("path").getAsString());
        assertEquals("```suggestion\nbetter();\n```", line.get("body").getAsString());
        assertEquals(12, line.get("new_position").getAsInt());
        assertFalse(line.has("old_position"));
    }

    @Test
    void aCommentOnTheOldSideIsAnOldPosition() {
        on("POST", "/repos/alice/demo/pulls/7/reviews", 200, "{\"id\": 41, \"state\": \"COMMENT\"}");

        client.createReviewComment("t", "alice", "demo", 7, "f00dcafe", "src/B.java", 4, "LEFT", "Why?");

        JsonObject line = json(only().body()).getAsJsonArray("comments").get(0).getAsJsonObject();
        assertEquals(4, line.get("old_position").getAsInt());
        assertFalse(line.has("new_position"));
    }

    @Test
    void aRefusedCommentSurfacesTheServersOwnMessage() {
        on("POST", "/repos/alice/demo/pulls/7/reviews", 422, "{\"message\":\"line is not in the diff\"}");

        var result = client.createReviewComment("t", "alice", "demo", 7, "f00dcafe", "x", 999, "RIGHT", "b");

        assertFalse(result.isOk());
        assertEquals("line is not in the diff", result.messageOrNull());
    }

    // ------------------------------------------------------------------
    // Issues
    // ------------------------------------------------------------------

    @Test
    void issuesAreAskedForWithoutPullRequestsAndParsed() {
        on("GET", "/repos/alice/demo/issues", 200, """
                [{"number": 3, "title": "Crash", "state": "open", "user": {"login": "erin"},
                  "html_url": "https://codeberg.org/alice/demo/issues/3",
                  "labels": [{"id": 1, "name": "bug", "color": "ee0701"}], "pull_request": null},
                 {"number": 7, "title": "Fix it", "state": "open", "user": {"login": "bob"},
                  "pull_request": {"merged": false}}]""", Map.of("X-Total-Count", "40"));

        var result = client.listIssuesPage("t", "alice", "demo", 1, 20);

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals(1, result.orNull().items().size(), "a pull request is dropped even if the server sends one");
        GiteaIssue issue = result.orNull().items().get(0);
        assertEquals(3, issue.number());
        assertEquals("Crash", issue.title());
        assertEquals("erin", issue.user().login());
        assertEquals("open", issue.state());
        assertEquals("https://codeberg.org/alice/demo/issues/3", issue.htmlUrl());
        assertEquals("bug", issue.labels().get(0).name());
        assertTrue(result.orNull().hasMore());
        Request sent = only();
        assertEquals("issues", sent.param("type"));
        assertEquals("open", sent.param("state"));
        assertEquals("20", sent.param("limit"));
    }

    @Test
    void theWholeIssueListIsFilteredToo() {
        on("GET", "/repos/alice/demo/issues", 200, """
                [{"number": 3, "title": "Crash"}, {"number": 7, "pull_request": {}}]""");

        assertEquals(List.of(3), client.listIssues("t", "alice", "demo").orNull().stream()
                .map(GiteaIssue::number).toList());
    }

    // ------------------------------------------------------------------
    // Forks
    // ------------------------------------------------------------------

    @Test
    void forksAreListed() {
        on("GET", "/repos/alice/demo/forks", 200, """
                [{"full_name": "bob/demo", "owner": {"login": "bob"}, "fork": true,
                  "clone_url": "https://codeberg.org/bob/demo.git"}]""");

        var result = client.listForks("t", "alice", "demo");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("bob/demo", result.orNull().get(0).fullName());
        assertTrue(result.orNull().get(0).fork());
    }

    @Test
    void creatingAForkPostsAnEmptyObjectAndParsesTheNewRepository() {
        on("POST", "/repos/alice/demo/forks", 202, """
                {"full_name": "me/demo", "fork": true, "clone_url": "https://codeberg.org/me/demo.git"}""");

        var result = client.createFork("t", "alice", "demo");

        assertTrue(result.isOk(), result.messageOrNull());
        assertEquals("me/demo", result.orNull().fullName());
        assertEquals("POST", only().method());
        assertEquals("{}", only().body());
    }

    @Test
    void aSecondForkSurfacesTheServersOwnMessage() {
        on("POST", "/repos/alice/demo/forks", 409, "{\"message\":\"repository is already forked by user\"}");

        var result = client.createFork("t", "alice", "demo");

        assertFalse(result.isOk());
        assertEquals("repository is already forked by user", result.messageOrNull());
    }
}
