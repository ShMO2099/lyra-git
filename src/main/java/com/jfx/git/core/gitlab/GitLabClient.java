package com.jfx.git.core.gitlab;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Talks to GitLab's REST API v4 - enough for a personal access token to be verified,
 * the account's own projects to be listed, a project's open merge requests to be
 * listed, and its open issues to be listed (Wave 4, §4.3's GitLab arc of the parity
 * plan, repeating the shape already shipped for GitHub).
 *
 * <p>Authenticates with {@code Authorization: Bearer}. GitLab accepts both personal access
 * tokens and OAuth access tokens that way (docs.gitlab.com/api/rest/authentication), while
 * the older {@code PRIVATE-TOKEN} header takes only the former - and an account connected
 * through OAuth sign-in has only an OAuth token. One header for both kinds means the client
 * never needs to know how the account was connected.
 *
 * <p>The base URI is a constructor parameter rather than a hardcoded constant so a
 * test can point this at a local fake server instead of the real gitlab.com, the same
 * reasoning {@link com.jfx.git.core.github.GitHubClient} already follows.
 */
public final class GitLabClient {

    private static final Logger LOGGER = Logger.getLogger(GitLabClient.class.getName());

    private final URI baseUri;
    private final HttpClient http;
    private final Gson gson = new Gson();

    /**
     * Talks to whatever server the user configured - gitlab.com unless a self-managed
     * host is set. See {@link com.jfx.git.core.ProviderHosts}.
     */
    public GitLabClient() {
        this(com.jfx.git.core.ProviderHosts.gitlab());
    }

    public GitLabClient(URI baseUri) {
        this.baseUri = baseUri;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The authenticated user a token belongs to - both proves the token works and names who is connected. */
    public GitLabResult<GitLabUser> fetchAuthenticatedUser(String token) {
        return get(token, "/user", GitLabUser.class);
    }

    /**
     * The authenticated user's own projects, most recently active first - every page,
     * following the {@code Link} header as every listing here does.
     */
    public GitLabResult<List<GitLabProject>> listProjects(String token) {
        return getAll(token, "/projects?membership=true&per_page=100&order_by=last_activity_at",
                new TypeToken<List<GitLabProject>>() { }.getType());
    }

    /** A project's open merge requests, newest first. */
    public GitLabResult<List<GitLabMergeRequest>> listMergeRequests(String token, String projectPath) {
        return getAll(token, "/projects/" + projectPath
                        + "/merge_requests?state=opened&per_page=100&order_by=created_at&sort=desc",
                new TypeToken<List<GitLabMergeRequest>>() { }.getType());
    }

    /**
     * One merge request's own full detail - not assumed to be a strict superset of
     * what {@link #listMergeRequests} already returns, but fetched separately before
     * reviewing a diff specifically because {@code diff_refs} (the three shas a diff
     * position needs together as one unit) is not guaranteed present on every list
     * response shape a REST API might choose to serialize.
     */
    public GitLabResult<GitLabMergeRequest> getMergeRequest(String token, String projectPath, int iid) {
        return get(token, "/projects/" + projectPath + "/merge_requests/" + iid, GitLabMergeRequest.class);
    }

    /** A project's open issues, newest first. */
    public GitLabResult<List<GitLabIssue>> listIssues(String token, String projectPath) {
        return getAll(token, "/projects/" + projectPath
                        + "/issues?state=opened&per_page=100&order_by=created_at&sort=desc",
                new TypeToken<List<GitLabIssue>>() { }.getType());
    }

    /**
     * One page of a project's open merge requests, newest first - what the merge request
     * dialog shows, a page at a time. See {@link com.jfx.git.core.ListPage}.
     *
     * @param page 1-based
     */
    public GitLabResult<com.jfx.git.core.ListPage<GitLabMergeRequest>> listMergeRequestsPage(
            String token, String projectPath, int page, int perPage) {
        return getPage(token, "/projects/" + projectPath + "/merge_requests?state=opened&order_by=created_at&sort=desc"
                        + "&per_page=" + perPage + "&page=" + page,
                new TypeToken<List<GitLabMergeRequest>>() { }.getType());
    }

    /** One page of a project's open issues, newest first; see {@link #listMergeRequestsPage}. */
    public GitLabResult<com.jfx.git.core.ListPage<GitLabIssue>> listIssuesPage(
            String token, String projectPath, int page, int perPage) {
        return getPage(token, "/projects/" + projectPath + "/issues?state=opened&order_by=created_at&sort=desc"
                        + "&per_page=" + perPage + "&page=" + page,
                new TypeToken<List<GitLabIssue>>() { }.getType());
    }

    /**
     * Opens a new merge request. GitLab's own response to this call is the identical
     * merge-request shape {@link #listMergeRequests} returns, so the same {@link
     * GitLabMergeRequest} model deserializes it directly.
     */
    public GitLabResult<GitLabMergeRequest> createMergeRequest(String token, String projectPath, String title,
                                                                String description, String sourceBranch,
                                                                String targetBranch) {
        String json = gson.toJson(new CreateMergeRequestBody(sourceBranch, targetBranch, title, description));
        return post(token, "/projects/" + projectPath + "/merge_requests", json, GitLabMergeRequest.class);
    }

    private record CreateMergeRequestBody(
            @SerializedName("source_branch") String sourceBranch,
            @SerializedName("target_branch") String targetBranch,
            String title,
            String description) {
    }

    /** The overall CI status of a commit, reduced from GitLab's own per-pipeline-job statuses. */
    public GitLabResult<com.jfx.git.core.BuildStatus> getCommitStatus(String token, String projectPath, String sha) {
        GitLabResult<List<CommitStatus>> result = getAll(token,
                "/projects/" + projectPath + "/repository/commits/" + sha + "/statuses?per_page=100",
                new TypeToken<List<CommitStatus>>() { }.getType());
        if (!result.isOk()) {
            return GitLabResult.failed(result.messageOrNull());
        }
        List<CommitStatus> statuses = result.orNull();
        if (statuses.isEmpty()) {
            return GitLabResult.ok(com.jfx.git.core.BuildStatus.NONE);
        }
        List<com.jfx.git.core.BuildStatus> mapped = statuses.stream()
                .map(s -> mapStatus(s.status())).toList();
        return GitLabResult.ok(com.jfx.git.core.BuildStatus.combine(mapped));
    }

    private static com.jfx.git.core.BuildStatus mapStatus(String status) {
        return switch (status == null ? "" : status) {
            case "success", "skipped" -> com.jfx.git.core.BuildStatus.PASSED;
            case "pending", "running", "created", "waiting_for_resource" -> com.jfx.git.core.BuildStatus.PENDING;
            default -> com.jfx.git.core.BuildStatus.FAILED;
        };
    }

    private record CommitStatus(String status) {
    }

    /**
     * Merges an open merge request server-side - GitLab does the merge itself; nothing
     * about the local repository changes until the caller next fetches or pulls.
     *
     * <p>Unlike GitHub/Bitbucket, GitLab has no per-request "merge method" - whether a
     * merge commit, a rebase-merge, or a fast-forward-only merge happens is a
     * project-level setting configured in GitLab itself, not something this API call
     * chooses. {@code squash} is the one merge-shape choice the API does expose.
     *
     * @return the merged merge request's own {@code merge_commit_sha} on success, or
     *         {@code squash_commit_sha} if it squashed and made no separate merge commit
     */
    public GitLabResult<String> mergeMergeRequest(String token, String projectPath, int iid, boolean squash) {
        String json = gson.toJson(new MergeRequestBody(squash));
        GitLabResult<MergeResponse> result = put(token,
                "/projects/" + projectPath + "/merge_requests/" + iid + "/merge", json, MergeResponse.class);
        if (!result.isOk()) {
            return GitLabResult.failed(result.messageOrNull());
        }
        MergeResponse merged = result.orNull();
        return GitLabResult.ok(merged.mergeCommitSha() != null ? merged.mergeCommitSha() : merged.squashCommitSha());
    }

    private record MergeRequestBody(boolean squash) {
    }

    private record MergeResponse(
            @SerializedName("merge_commit_sha") String mergeCommitSha,
            @SerializedName("squash_commit_sha") String squashCommitSha) {
    }

    /** Every discussion thread on a merge request, across every file and line, plus plain top-level comments. */
    public GitLabResult<List<GitLabDiscussion>> listDiscussions(String token, String projectPath, int iid) {
        return getAll(token, "/projects/" + projectPath + "/merge_requests/" + iid + "/discussions?per_page=100",
                new TypeToken<List<GitLabDiscussion>>() { }.getType());
    }

    /**
     * Starts a new discussion thread anchored to one line of the merge request's diff -
     * immediately visible. A "suggested change" is not a distinct API call: {@code
     * body} containing a <code>```suggestion</code> fenced block is all GitLab needs to
     * render one, so the caller composes that text itself before calling this.
     *
     * <p>{@code diffRefs} must be the merge request's own {@link
     * GitLabMergeRequest#diffRefs()} at the time the diff being commented on was
     * fetched - GitLab uses the three shas together to locate the position rather than
     * a single commit, since a diff position can span a rebase.
     */
    public GitLabResult<GitLabDiscussion> createDiscussion(String token, String projectPath, int iid, String body,
                                                            GitLabMergeRequest.DiffRefs diffRefs,
                                                            String oldPath, String newPath,
                                                            Integer oldLine, Integer newLine) {
        Position position = new Position("text", diffRefs.baseSha(), diffRefs.startSha(), diffRefs.headSha(),
                oldPath, newPath, oldLine, newLine);
        String json = gson.toJson(new CreateDiscussionBody(body, position));
        return post(token, "/projects/" + projectPath + "/merge_requests/" + iid + "/discussions", json,
                GitLabDiscussion.class);
    }

    private record CreateDiscussionBody(String body, Position position) {
    }

    private record Position(@SerializedName("position_type") String positionType,
                             @SerializedName("base_sha") String baseSha,
                             @SerializedName("start_sha") String startSha,
                             @SerializedName("head_sha") String headSha,
                             @SerializedName("old_path") String oldPath,
                             @SerializedName("new_path") String newPath,
                             @SerializedName("old_line") Integer oldLine,
                             @SerializedName("new_line") Integer newLine) {
    }

    private <T> GitLabResult<T> get(String token, String path, java.lang.reflect.Type type) {
        return send(token, path, "GET", HttpRequest.BodyPublishers.noBody(), type);
    }

    private <T> GitLabResult<T> put(String token, String path, String jsonBody, java.lang.reflect.Type type) {
        return send(token, path, "PUT",
                HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8), type);
    }

    private <T> GitLabResult<T> post(String token, String path, String jsonBody, java.lang.reflect.Type type) {
        return send(token, path, "POST",
                HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8), type);
    }

    /** One page of a list, and whether GitLab linked a next one. */
    private <E> GitLabResult<com.jfx.git.core.ListPage<E>> getPage(String token, String path,
                                                                 java.lang.reflect.Type listType) {
        URI[] following = new URI[1];
        GitLabResult<List<E>> one = send(token, path, "GET", HttpRequest.BodyPublishers.noBody(), listType, following);
        if (!one.isOk()) return GitLabResult.failed(one.messageOrNull(), one.failureKind());
        return GitLabResult.ok(new com.jfx.git.core.ListPage<>(one.orNull(), following[0] != null));
    }

    /**
     * Every page of a list, following GitLab's {@code Link: rel="next"} - see
     * {@link com.jfx.git.core.ApiPaging} for why one page was not enough.
     */
    private <E> GitLabResult<List<E>> getAll(String token, String path, java.lang.reflect.Type listType) {
        List<E> all = new java.util.ArrayList<>();
        String next = path;
        for (int page = 0; next != null; page++) {
            if (page == com.jfx.git.core.ApiPaging.MAX_PAGES) {
                LOGGER.info("Stopped following " + path + " after " + page + " pages");
                break;
            }
            if (page > 0 && com.jfx.git.core.GitTaskRunner.isCancelled()) {
                return GitLabResult.failed("Cancelled");
            }
            URI[] following = new URI[1];
            GitLabResult<List<E>> one = send(token, next, "GET", HttpRequest.BodyPublishers.noBody(),
                    listType, following);
            if (!one.isOk()) return one;
            com.jfx.git.core.ApiPaging.addAll(all, one.orNull());
            next = following[0] == null ? null : following[0].toString();
        }
        return GitLabResult.ok(all);
    }

    private <T> GitLabResult<T> send(String token, String path, String method, HttpRequest.BodyPublisher body,
                                      java.lang.reflect.Type type) {
        return send(token, path, method, body, type, null);
    }

    /**
     * @param path     relative to the base URI, or an absolute URL already checked to be
     *                 on the same server (a next-page link)
     * @param nextPage when not null, receives the response's next-page link, if any
     */
    private <T> GitLabResult<T> send(String token, String path, String method, HttpRequest.BodyPublisher body,
                                      java.lang.reflect.Type type, URI[] nextPage) {
        if (token == null || token.isBlank()) {
            return GitLabResult.failed("No personal access token supplied");
        }
        HttpRequest request;
        try {
            URI target = path.startsWith("https://") || path.startsWith("http://")
                    ? URI.create(path)
                    : com.jfx.git.core.ApiErrors.endpoint(baseUri, path);
            request = HttpRequest.newBuilder(target)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .method(method, body)
                    .build();
        } catch (Exception e) {
            return GitLabResult.failed("Could not build the request: " + e.getMessage());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "GitLab request failed", e);
            // TRANSPORT, not a rejection: nothing was reached, so this says nothing
            // about whether the token is still good.
            return GitLabResult.failed("Could not reach GitLab: " + describe(e),
                    com.jfx.git.core.ApiFailure.TRANSPORT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GitLabResult.failed("Request interrupted",
                    com.jfx.git.core.ApiFailure.TRANSPORT);
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            try {
                T value = gson.fromJson(response.body(), type);
                if (nextPage != null) {
                    nextPage[0] = com.jfx.git.core.ApiPaging.nextLink(response, baseUri);
                }
                return GitLabResult.ok(value);
            } catch (JsonSyntaxException e) {
                return GitLabResult.failed("GitLab returned a response this app could not read");
            }
        }
        return GitLabResult.failed(describeError(response),
                com.jfx.git.core.ApiFailure.of(response));
    }


    /**
     * What to call this server when reporting a rate limit or a rejected token.
     *
     * <p>The public name is right for the public host and wrong for anything else: told
     * "GitLab rejected the token" while connected to their own company's server, a user
     * goes and checks the wrong account. The host is what they typed and what they will
     * recognise.
     */
    private String providerName() {
        String host = baseUri.getHost();
        if (host == null || host.isBlank()) return "GitLab";
        return host.equals("gitlab.com") || host.endsWith(".gitlab.com") ? "GitLab" : host;
    }
    /**
     * GitLab's own {@code message} field when there is one, otherwise the bare status -
     * except for rate limiting and an expired token, which get the wording in
     * {@link com.jfx.git.core.ApiErrors}.
     */
    private String describeError(HttpResponse<String> response) {
        String common = com.jfx.git.core.ApiErrors.describe(providerName(), response);
        if (common != null) return common;
        try {
            var error = gson.fromJson(response.body(), ErrorBody.class);
            if (error != null && error.message() != null && !error.message().isBlank()) {
                return error.message();
            }
        } catch (JsonSyntaxException ignored) {
            // Not every failure response is JSON (a proxy's own HTML error page, say).
        }
        return "GitLab request failed with status " + response.statusCode();
    }

    private record ErrorBody(String message) {
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
