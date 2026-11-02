package com.jfx.git.core.github;

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
 * Talks to GitHub's REST API - enough for a personal access token to be verified and
 * for the account's own repositories to be listed, which is the whole of "GitHub PAT
 * auth + repo browsing on clone" (Wave 4, §4.3 Phase A of the parity plan).
 *
 * <p>No OAuth here on purpose: a registered OAuth app plus a loopback-or-system-browser
 * handoff is materially more infrastructure than this desktop app needs for a first
 * cut, and GitHub still fully supports a pasted personal access token as a first-class
 * way in.
 *
 * <p>The base URI is a constructor parameter rather than a hardcoded constant so a test
 * can point this at a local fake server instead of the real api.github.com - the same
 * reasoning {@link com.jfx.git.core.NativeGitRunner} takes a work tree rather than
 * assuming one.
 */
public final class GitHubClient {

    private static final Logger LOGGER = Logger.getLogger(GitHubClient.class.getName());

    /** GitHub asks for this on every request; pinned so a response shape never drifts under us. */
    private static final String API_VERSION = "2022-11-28";

    private final URI baseUri;
    private final HttpClient http;
    private final Gson gson = new Gson();

    /**
     * Talks to whatever server the user configured - github.com unless a GitHub
     * Enterprise host is set. See {@link com.jfx.git.core.ProviderHosts}.
     */
    public GitHubClient() {
        this(com.jfx.git.core.ProviderHosts.github());
    }

    public GitHubClient(URI baseUri) {
        this.baseUri = baseUri;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The authenticated user a token belongs to - both proves the token works and names who is connected. */
    public GitHubResult<GitHubUser> fetchAuthenticatedUser(String token) {
        return get(token, "/user", GitHubUser.class);
    }

    /**
     * The authenticated user's own repositories, newest-pushed first.
     *
     * <p>Every page, following the response's {@code Link} header up to
     * {@link com.jfx.git.core.ApiPaging#MAX_PAGES}; every other listing here does the same.
     */
    public GitHubResult<List<GitHubRepository>> listRepositories(String token) {
        return getAll(token, "/user/repos?per_page=100&sort=pushed",
                new TypeToken<List<GitHubRepository>>() { }.getType());
    }

    /**
     * One pull request, freshly.
     *
     * <p>The same shape {@link #listPullRequests} returns, fetched on its own because a
     * pull request is a moving target: {@code head.sha} is only true until the author
     * pushes again, and a review comment anchored to a stale one is rejected.
     */
    public GitHubResult<GitHubPullRequest> fetchPullRequest(String token, String owner, String repo,
                                                            int number) {
        return get(token, "/repos/" + owner + "/" + repo + "/pulls/" + number,
                GitHubPullRequest.class);
    }

    /** A repository's open pull requests, newest first, every page. */
    public GitHubResult<List<GitHubPullRequest>> listPullRequests(String token, String owner, String repo) {
        return getAll(token, "/repos/" + owner + "/" + repo + "/pulls?state=open&per_page=100&sort=created&direction=desc",
                new TypeToken<List<GitHubPullRequest>>() { }.getType());
    }

    /**
     * A repository's open issues, newest first, every page.
     *
     * <p>GitHub's issues endpoint also returns pull requests - every PR is internally
     * an issue - so the response is filtered down to real issues before it is handed
     * back, verified against the live API rather than assumed from the docs: a real
     * issue never carries a {@code pull_request} key at all, a PR always does.
     */
    public GitHubResult<List<GitHubIssue>> listIssues(String token, String owner, String repo) {
        GitHubResult<List<GitHubIssue>> result = getAll(token,
                "/repos/" + owner + "/" + repo + "/issues?state=open&per_page=100&sort=created&direction=desc",
                new TypeToken<List<GitHubIssue>>() { }.getType());
        if (!result.isOk()) {
            return result;
        }
        return GitHubResult.ok(result.orNull().stream().filter(issue -> !issue.isPullRequest()).toList());
    }

    /**
     * One page of a repository's open pull requests, newest first - what the pull
     * request dialog shows, a page at a time. See {@link com.jfx.git.core.ListPage}.
     *
     * @param page 1-based
     */
    public GitHubResult<com.jfx.git.core.ListPage<GitHubPullRequest>> listPullRequestsPage(
            String token, String owner, String repo, int page, int perPage) {
        return getPage(token, "/repos/" + owner + "/" + repo + "/pulls?state=open&sort=created&direction=desc"
                        + "&per_page=" + perPage + "&page=" + page,
                new TypeToken<List<GitHubPullRequest>>() { }.getType(), (List<GitHubPullRequest> list) -> list);
    }

    /**
     * One page of a repository's open issues, newest first.
     *
     * <h2>Why search</h2>
     * The issues endpoint returns pull requests too, so on a repository with hundreds of
     * open pull requests a page of fifty could hold no issues at all, and every one of
     * them was downloaded only to be thrown away. Search takes {@code is:issue} and
     * returns only issues.
     *
     * <p>Search is not everywhere - a GitHub Enterprise server can have it switched off,
     * and it has its own, smaller rate limit - so any refusal other than a rejected token
     * or an unreachable server falls back to the issues endpoint, filtered as before, and
     * this client stops trying search. A page from the fallback can hold fewer than
     * {@code perPage} issues; {@code hasMore} still comes from GitHub's own next link.
     *
     * @param page 1-based
     */
    public GitHubResult<com.jfx.git.core.ListPage<GitHubIssue>> listIssuesPage(
            String token, String owner, String repo, int page, int perPage) {
        if (!searchUnavailable) {
            String query = java.net.URLEncoder.encode("repo:" + owner + "/" + repo + " is:issue is:open",
                    java.nio.charset.StandardCharsets.UTF_8);
            GitHubResult<com.jfx.git.core.ListPage<GitHubIssue>> found = getPage(token,
                    "/search/issues?q=" + query + "&sort=created&order=desc&per_page=" + perPage + "&page=" + page,
                    SearchIssuesResponse.class, SearchIssuesResponse::items);
            if (found.isOk()) {
                return found;
            }
            com.jfx.git.core.ApiFailure kind = found.failureKind();
            if (kind == com.jfx.git.core.ApiFailure.AUTH_REJECTED || kind == com.jfx.git.core.ApiFailure.TRANSPORT) {
                return found;
            }
            LOGGER.info("Issue search unavailable (" + found.messageOrNull() + "); listing issues instead");
            searchUnavailable = true;
        }
        GitHubResult<com.jfx.git.core.ListPage<GitHubIssue>> listed = getPage(token,
                "/repos/" + owner + "/" + repo + "/issues?state=open&sort=created&direction=desc"
                        + "&per_page=" + perPage + "&page=" + page,
                new TypeToken<List<GitHubIssue>>() { }.getType(), (List<GitHubIssue> list) -> list);
        if (!listed.isOk()) {
            return listed;
        }
        var onePage = listed.orNull();
        return GitHubResult.ok(new com.jfx.git.core.ListPage<>(
                onePage.items().stream().filter(issue -> !issue.isPullRequest()).toList(), onePage.hasMore()));
    }

    /** Set once search has refused this client; see {@link #listIssuesPage}. */
    private volatile boolean searchUnavailable;

    private record SearchIssuesResponse(List<GitHubIssue> items) {
    }

    /**
     * The repository's forks - used to check whether the connected account already has
     * one before {@link #createFork} tries to make another. Every page: a popular
     * repository's own fork could otherwise sit past the first hundred and a second
     * fork be attempted.
     */
    public GitHubResult<List<GitHubRepository>> listForks(String token, String owner, String repo) {
        return getAll(token, "/repos/" + owner + "/" + repo + "/forks?per_page=100",
                new TypeToken<List<GitHubRepository>>() { }.getType());
    }

    /**
     * Forks a repository into the connected account. GitHub queues the fork as a
     * background job but returns the new repository's data immediately (HTTP 202).
     *
     * <p>The official docs do not say what happens on a repeat call against a
     * repository the account has already forked, so callers should check
     * {@link #listForks} first rather than relying on this being safe to retry.
     */
    public GitHubResult<GitHubRepository> createFork(String token, String owner, String repo) {
        return post(token, "/repos/" + owner + "/" + repo + "/forks", GitHubRepository.class);
    }

    /**
     * Opens a new pull request. GitHub's own response to this call is the identical
     * pull-request shape {@link #listPullRequests} returns, so the same {@link
     * GitHubPullRequest} model deserializes it directly.
     *
     * @param head the source branch - a bare branch name for one in this same
     *             repository, or {@code "owner:branch"} for a fork's; only the
     *             same-repository form is offered by this app's own compose dialog
     * @param base the branch this pull request would merge into
     */
    public GitHubResult<GitHubPullRequest> createPullRequest(String token, String owner, String repo,
                                                              String title, String body, String head, String base) {
        String json = gson.toJson(new CreatePullRequestBody(title, body, head, base));
        return post(token, "/repos/" + owner + "/" + repo + "/pulls", json, GitHubPullRequest.class);
    }

    private record CreatePullRequestBody(String title, String body, String head, String base) {
    }

    /**
     * Merges an open pull request server-side - GitHub does the merge itself; nothing
     * about the local repository changes until the caller next fetches or pulls.
     *
     * @param mergeMethod one of GitHub's own {@code "merge"}, {@code "squash"}, or
     *                    {@code "rebase"} - passed straight through, not validated here
     * @return GitHub's own human-readable confirmation (e.g. "Pull Request successfully
     *         merged") on success, so the caller can show exactly what GitHub said
     */
    public GitHubResult<String> mergePullRequest(String token, String owner, String repo,
                                                  int number, String mergeMethod) {
        String json = gson.toJson(new MergeRequestBody(mergeMethod));
        GitHubResult<MergeResponse> result = put(token,
                "/repos/" + owner + "/" + repo + "/pulls/" + number + "/merge", json, MergeResponse.class);
        return result.isOk()
                ? GitHubResult.ok(result.orNull().message())
                : GitHubResult.failed(result.messageOrNull());
    }

    private record MergeRequestBody(@SerializedName("merge_method") String mergeMethod) {
    }

    private record MergeResponse(boolean merged, String message, String sha) {
    }

    /**
     * The overall CI status of a commit, across <em>both</em> ways GitHub reports it.
     *
     * <h2>Why two calls</h2>
     * GitHub Actions and most modern third-party CI report through check runs. The older
     * commit-status API - the one a webhook-driven Jenkins or Buildkite still posts to -
     * is a separate list that check runs do not include. Reading only check runs showed
     * "no CI" for a repository whose build was in fact failing, which is worse than
     * showing nothing: it is a green-ish absence of warning over a red build.
     *
     * <p>So both are read and combined. That is a second request per commit, which is
     * affordable against GitHub's authenticated rate limit and is the price of the icon
     * telling the truth for both kinds of repository. A repository using only one system
     * gets an empty list from the other and is unaffected.
     *
     * <p>The check-runs call is the authoritative one: if it fails, so does this. The
     * statuses call is best-effort - a repository or token without access to it should
     * not lose the check-run answer that did arrive.
     */
    public GitHubResult<com.jfx.git.core.BuildStatus> getCheckStatus(String token, String owner, String repo,
                                                                      String sha) {
        // Every page: a failed run on page two used to leave the combined status green.
        GitHubResult<List<CheckRun>> result = getAll(token,
                "/repos/" + owner + "/" + repo + "/commits/" + sha + "/check-runs?per_page=100",
                CheckRunsResponse.class, CheckRunsResponse::checkRuns);
        if (!result.isOk()) {
            return GitHubResult.failed(result.messageOrNull());
        }

        List<com.jfx.git.core.BuildStatus> mapped = new java.util.ArrayList<>();
        List<CheckRun> runs = result.orNull();
        if (runs != null) {
            runs.stream().map(GitHubClient::mapCheckRun).forEach(mapped::add);
        }

        if (com.jfx.git.core.GitTaskRunner.isCancelled()) {
            return GitHubResult.failed("Cancelled");
        }
        GitHubResult<CombinedStatusResponse> legacy = get(token,
                "/repos/" + owner + "/" + repo + "/commits/" + sha + "/status",
                CombinedStatusResponse.class);
        if (legacy.isOk() && legacy.orNull() != null && legacy.orNull().totalCount() > 0) {
            mapped.add(mapCombinedState(legacy.orNull().state()));
        }

        // An empty list is BuildStatus.NONE - genuinely no CI, rather than a pass.
        return GitHubResult.ok(com.jfx.git.core.BuildStatus.combine(mapped));
    }

    /**
     * One check run's state.
     *
     * <h2>Why the unknown case is not a failure</h2>
     * This used to answer FAILED for every conclusion it did not recognise, which put a
     * red icon on {@code action_required} - a check waiting for a maintainer to approve
     * a first-time contributor's workflow run, which GitHub itself draws as amber and
     * which nobody has broken. Accusing a pull request of failing is the one answer that
     * costs the reader something when it is wrong, and every genuine failure arrives
     * under a name listed here, so anything unrecognised waits rather than accuses.
     */
    private static com.jfx.git.core.BuildStatus mapCheckRun(CheckRun run) {
        if (!"completed".equals(run.status())) {
            return com.jfx.git.core.BuildStatus.PENDING;
        }
        return switch (run.conclusion() == null ? "" : run.conclusion()) {
            // Ran, and does not stand in the way. "stale" is a run a later one superseded;
            // that later run is in the same list and carries the real answer.
            case "success", "neutral", "skipped", "stale" -> com.jfx.git.core.BuildStatus.PASSED;
            case "failure", "timed_out", "cancelled", "startup_failure"
                    -> com.jfx.git.core.BuildStatus.FAILED;
            // action_required, and anything GitHub adds later.
            default -> com.jfx.git.core.BuildStatus.PENDING;
        };
    }

    /** The combined state of the older commit-status API: {@code success}/{@code pending}/{@code failure}. */
    private static com.jfx.git.core.BuildStatus mapCombinedState(String state) {
        return switch (state == null ? "" : state) {
            case "success" -> com.jfx.git.core.BuildStatus.PASSED;
            case "failure", "error" -> com.jfx.git.core.BuildStatus.FAILED;
            default -> com.jfx.git.core.BuildStatus.PENDING;
        };
    }

    private record CheckRunsResponse(@SerializedName("total_count") int totalCount,
                                      @SerializedName("check_runs") List<CheckRun> checkRuns) {
    }

    private record CheckRun(String status, String conclusion) {
    }

    /** {@code /commits/{sha}/status} - the older API, one rolled-up state plus its contexts. */
    private record CombinedStatusResponse(String state,
                                           @SerializedName("total_count") int totalCount) {
    }

    /** Every review comment on a pull request, across every file and line. */
    public GitHubResult<List<GitHubReviewComment>> listReviewComments(String token, String owner, String repo,
                                                                        int prNumber) {
        return getAll(token, "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/comments?per_page=100",
                new TypeToken<List<GitHubReviewComment>>() { }.getType());
    }

    /**
     * Posts a new review comment anchored to one line of the pull request's diff -
     * immediately visible, not held as part of a pending review. A "suggested change"
     * is not a distinct API call: {@code body} containing a <code>```suggestion</code>
     * fenced block is all GitHub needs to render one, so the caller composes that text
     * itself before calling this.
     *
     * @param side {@code "LEFT"} (the base/old version) or {@code "RIGHT"} (the head/
     *             new version) of the diff
     */
    public GitHubResult<GitHubReviewComment> createReviewComment(String token, String owner, String repo,
                                                                    int prNumber, String commitId, String path,
                                                                    int line, String side, String body) {
        String json = gson.toJson(new CreateReviewCommentBody(body, commitId, path, line, side));
        return post(token, "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/comments", json,
                GitHubReviewComment.class);
    }

    private record CreateReviewCommentBody(String body, @SerializedName("commit_id") String commitId,
                                            String path, int line, String side) {
    }

    private <T> GitHubResult<T> get(String token, String path, java.lang.reflect.Type type) {
        return send(token, path, "GET", HttpRequest.BodyPublishers.noBody(), type);
    }

    private <T> GitHubResult<T> post(String token, String path, java.lang.reflect.Type type) {
        return send(token, path, "POST", HttpRequest.BodyPublishers.noBody(), type);
    }

    private <T> GitHubResult<T> post(String token, String path, String jsonBody, java.lang.reflect.Type type) {
        return send(token, path, "POST",
                HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8), type);
    }

    private <T> GitHubResult<T> put(String token, String path, String jsonBody, java.lang.reflect.Type type) {
        return send(token, path, "PUT",
                HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8), type);
    }

    /** One page of a list, and whether GitHub linked a next one. */
    private <P, E> GitHubResult<com.jfx.git.core.ListPage<E>> getPage(String token, String path,
            java.lang.reflect.Type pageType, java.util.function.Function<P, List<E>> items) {
        java.net.URI[] following = new java.net.URI[1];
        GitHubResult<P> one = send(token, path, "GET", HttpRequest.BodyPublishers.noBody(), pageType, following);
        if (!one.isOk()) return GitHubResult.failed(one.messageOrNull(), one.failureKind());
        List<E> page = one.orNull() == null ? null : items.apply(one.orNull());
        return GitHubResult.ok(new com.jfx.git.core.ListPage<>(page, following[0] != null));
    }

    /**
     * Every page of a list, following GitHub's {@code Link: rel="next"} - see
     * {@link com.jfx.git.core.ApiPaging} for why one page was not enough.
     *
     * @param pageType the JSON shape of one page
     * @param items    the list inside one page (the page itself, for a plain array)
     */
    private <P, E> GitHubResult<List<E>> getAll(String token, String path, java.lang.reflect.Type pageType,
                                              java.util.function.Function<P, List<E>> items) {
        List<E> all = new java.util.ArrayList<>();
        String next = path;
        for (int page = 0; next != null; page++) {
            if (page == com.jfx.git.core.ApiPaging.MAX_PAGES) {
                LOGGER.info("Stopped following " + path + " after " + page + " pages");
                break;
            }
            if (page > 0 && com.jfx.git.core.GitTaskRunner.isCancelled()) {
                return GitHubResult.failed("Cancelled");
            }
            java.net.URI[] following = new java.net.URI[1];
            GitHubResult<P> one = send(token, next, "GET", HttpRequest.BodyPublishers.noBody(), pageType, following);
            if (!one.isOk()) return GitHubResult.failed(one.messageOrNull(), one.failureKind());
            com.jfx.git.core.ApiPaging.addAll(all, one.orNull() == null ? null : items.apply(one.orNull()));
            next = following[0] == null ? null : following[0].toString();
        }
        return GitHubResult.ok(all);
    }

    private <E> GitHubResult<List<E>> getAll(String token, String path, java.lang.reflect.Type listType) {
        return getAll(token, path, listType, (List<E> list) -> list);
    }

    private <T> GitHubResult<T> send(String token, String path, String method, HttpRequest.BodyPublisher body,
                                      java.lang.reflect.Type type) {
        return send(token, path, method, body, type, null);
    }

    /**
     * @param path     relative to the base URI, or an absolute URL already checked to be
     *                 on the same server (a next-page link)
     * @param nextPage when not null, receives the response's next-page link, if any
     */
    private <T> GitHubResult<T> send(String token, String path, String method, HttpRequest.BodyPublisher body,
                                      java.lang.reflect.Type type, java.net.URI[] nextPage) {
        if (token == null || token.isBlank()) {
            return GitHubResult.failed("No personal access token supplied");
        }
        HttpRequest request;
        try {
            java.net.URI target = path.startsWith("https://") || path.startsWith("http://")
                    ? java.net.URI.create(path)
                    : com.jfx.git.core.ApiErrors.endpoint(baseUri, path);
            request = HttpRequest.newBuilder(target)
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", API_VERSION)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .method(method, body)
                    .build();
        } catch (Exception e) {
            return GitHubResult.failed("Could not build the request: " + e.getMessage());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "GitHub request failed", e);
            // TRANSPORT, not a rejection: nothing was reached, so this says nothing
            // about whether the token is still good.
            return GitHubResult.failed("Could not reach GitHub: " + describe(e),
                    com.jfx.git.core.ApiFailure.TRANSPORT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GitHubResult.failed("Request interrupted",
                    com.jfx.git.core.ApiFailure.TRANSPORT);
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            try {
                T value = gson.fromJson(response.body(), type);
                if (nextPage != null) {
                    nextPage[0] = com.jfx.git.core.ApiPaging.nextLink(response, baseUri);
                }
                return GitHubResult.ok(value);
            } catch (JsonSyntaxException e) {
                return GitHubResult.failed("GitHub returned a response this app could not read");
            }
        }
        return GitHubResult.failed(describeError(response),
                com.jfx.git.core.ApiFailure.of(response));
    }


    /**
     * What to call this server when reporting a rate limit or a rejected token.
     *
     * <p>The public name is right for the public host and wrong for anything else: told
     * "GitHub rejected the token" while connected to their own company's server, a user
     * goes and checks the wrong account. The host is what they typed and what they will
     * recognise.
     */
    private String providerName() {
        String host = baseUri.getHost();
        if (host == null || host.isBlank()) return "GitHub";
        return host.equals("github.com") || host.endsWith(".github.com") ? "GitHub" : host;
    }
    /**
     * What to tell the user about a failed request.
     *
     * <p>Rate limiting and an expired token get their own wording - see
     * {@link com.jfx.git.core.ApiErrors} for why GitHub's own is not actionable for
     * those two. Everything else falls through to GitHub's {@code message}, which is
     * usually good.
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
        return "GitHub request failed with status " + response.statusCode();
    }

    private record ErrorBody(String message) {
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
