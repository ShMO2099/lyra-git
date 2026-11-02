package com.jfx.git.core.gitea;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.jfx.git.core.ApiErrors;
import com.jfx.git.core.ApiFailure;
import com.jfx.git.core.ApiPaging;
import com.jfx.git.core.BuildStatus;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ListPage;
import com.jfx.git.core.ProviderHosts;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Talks to a Gitea or Forgejo server's API ({@code /api/v1}): the account, its
 * repositories, pull requests with their merge, CI status and review comments, issues and
 * forks.
 *
 * <h2>Why it mirrors GitHubClient</h2>
 * Gitea's API was modelled on GitHub's, and the Gitea dialogs are adapted copies of the
 * GitHub ones. So the method names, parameters and result shapes here follow
 * {@link com.jfx.git.core.github.GitHubClient} wherever Gitea allows, and differ only where
 * Gitea's API genuinely does - merge styles, a combined status rather than check runs, and
 * line comments that only exist inside a review. Each difference is explained where it is.
 *
 * <h2>Paging</h2>
 * Gitea pages with {@code page} and {@code limit} and says whether there is more in a
 * {@code Link: rel="next"} header and an {@code X-Total-Count} header. This client asks for
 * the next page itself, by number, rather than following the link's URL: Gitea builds that
 * URL from its configured {@code ROOT_URL}, which behind a reverse proxy is often not the
 * address the user reaches it on, and {@link ApiPaging} rightly refuses to send the token
 * anywhere else. The link and the count are read only as "there is more". Listings stop at
 * {@link ApiPaging#MAX_PAGES}, like every other provider's.
 *
 * <p>The base URI is a constructor parameter so a test can point this at a local server.
 */
public final class GiteaClient {

    private static final Logger LOGGER = Logger.getLogger(GiteaClient.class.getName());

    /**
     * Items asked for per page when reading a whole list. Gitea's default cap on a page
     * ({@code MAX_RESPONSE_ITEMS}) is 50; asking for more is silently clipped to it.
     */
    static final int PAGE_LIMIT = 50;

    /**
     * The merge styles Gitea accepts as {@code Do}, in the order a merge dialog offers
     * them. A repository can switch any of them off, which the server then refuses.
     */
    public static final List<String> MERGE_STYLES =
            List.of("merge", "rebase", "rebase-merge", "squash", "fast-forward-only");

    private final URI baseUri;
    private final HttpClient http;
    private final Gson gson = new Gson();

    /** Talks to the configured server - Codeberg unless another is set. See {@link ProviderHosts#gitea()}. */
    public GiteaClient() {
        this(ProviderHosts.gitea());
    }

    public GiteaClient(URI baseUri) {
        this.baseUri = baseUri;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The user a token belongs to - both proves the token works and names who is connected. */
    public GiteaResult<GiteaUser> fetchAuthenticatedUser(String token) {
        return get(token, "/user", GiteaUser.class);
    }

    /**
     * The repositories the authenticated user owns or can reach through membership,
     * every page. {@code order_by} is a Forgejo parameter that Gitea ignores; where it
     * works the most recently updated come first, as on GitHub.
     */
    public GiteaResult<List<GiteaRepository>> listRepositories(String token) {
        return getAll(token, "/user/repos?order_by=recentupdate",
                new TypeToken<List<GiteaRepository>>() { }.getType());
    }

    /**
     * One pull request, freshly - a pull request's head moves whenever its author pushes,
     * and the review dialog re-reads it to say so when a comment is refused.
     */
    public GiteaResult<GiteaPullRequest> fetchPullRequest(String token, String owner, String repo, int number) {
        return get(token, repoPath(owner, repo) + "/pulls/" + number, GiteaPullRequest.class);
    }

    /** A repository's open pull requests, newest first (Gitea's default order), every page. */
    public GiteaResult<List<GiteaPullRequest>> listPullRequests(String token, String owner, String repo) {
        return getAll(token, repoPath(owner, repo) + "/pulls?state=open",
                new TypeToken<List<GiteaPullRequest>>() { }.getType());
    }

    /**
     * One page of a repository's open pull requests, newest first - what the pull request
     * dialog shows a page at a time. See {@link ListPage}.
     *
     * @param page 1-based
     */
    public GiteaResult<ListPage<GiteaPullRequest>> listPullRequestsPage(String token, String owner, String repo,
                                                                        int page, int perPage) {
        return getPage(token, repoPath(owner, repo) + "/pulls?state=open", page, perPage,
                new TypeToken<List<GiteaPullRequest>>() { }.getType(), (List<GiteaPullRequest> list) -> list);
    }

    /**
     * A repository's open issues, every page. {@code type=issues} has the server leave
     * pull requests out, and anything that still carries {@code pull_request} is dropped
     * as well, in case a server ignores the parameter.
     */
    public GiteaResult<List<GiteaIssue>> listIssues(String token, String owner, String repo) {
        GiteaResult<List<GiteaIssue>> result = getAll(token, repoPath(owner, repo) + "/issues?state=open&type=issues",
                new TypeToken<List<GiteaIssue>>() { }.getType());
        if (!result.isOk()) return result;
        return GiteaResult.ok(result.orNull().stream().filter(issue -> !issue.isPullRequest()).toList());
    }

    /**
     * One page of a repository's open issues, newest first.
     *
     * <p>GitHub's version goes through search because its issues endpoint cannot leave
     * pull requests out; Gitea's can ({@code type=issues}), so a page here is a full page
     * of issues and no search is needed.
     *
     * @param page 1-based
     */
    public GiteaResult<ListPage<GiteaIssue>> listIssuesPage(String token, String owner, String repo,
                                                            int page, int perPage) {
        GiteaResult<ListPage<GiteaIssue>> listed = getPage(token,
                repoPath(owner, repo) + "/issues?state=open&type=issues", page, perPage,
                new TypeToken<List<GiteaIssue>>() { }.getType(), (List<GiteaIssue> list) -> list);
        if (!listed.isOk()) return listed;
        ListPage<GiteaIssue> onePage = listed.orNull();
        return GiteaResult.ok(new ListPage<>(
                onePage.items().stream().filter(issue -> !issue.isPullRequest()).toList(), onePage.hasMore()));
    }

    /**
     * The repository's forks, every page - checked before {@link #createFork} so the
     * connected account's existing fork is reused rather than a second one attempted.
     */
    public GiteaResult<List<GiteaRepository>> listForks(String token, String owner, String repo) {
        return getAll(token, repoPath(owner, repo) + "/forks",
                new TypeToken<List<GiteaRepository>>() { }.getType());
    }

    /**
     * Forks a repository into the connected account (HTTP 202 with the new repository).
     *
     * <p>Unlike GitHub, Gitea refuses a second fork into the same account outright, with
     * a 409 whose message says the repository already exists - surfaced as it is.
     */
    public GiteaResult<GiteaRepository> createFork(String token, String owner, String repo) {
        // Every field of CreateForkOption is optional, but the server binds a JSON body.
        return post(token, repoPath(owner, repo) + "/forks", "{}", GiteaRepository.class);
    }

    /**
     * Opens a new pull request; the server answers 201 with the pull request.
     *
     * @param head the source branch - a bare branch name in this repository, or
     *             {@code "owner:branch"} for a fork's
     * @param base the branch it would merge into
     */
    public GiteaResult<GiteaPullRequest> createPullRequest(String token, String owner, String repo,
                                                           String title, String body, String head, String base) {
        String json = gson.toJson(new CreatePullRequestBody(title, body, head, base));
        return post(token, repoPath(owner, repo) + "/pulls", json, GiteaPullRequest.class);
    }

    private record CreatePullRequestBody(String title, String body, String head, String base) {
    }

    /**
     * Merges a pull request server-side with one of {@link #MERGE_STYLES}, keeping the
     * source branch and Gitea's default commit title and message.
     *
     * @return a confirmation to show; Gitea itself answers a merge with an empty body
     * @see #mergePullRequest(String, String, String, int, String, boolean, String, String, String)
     */
    public GiteaResult<String> mergePullRequest(String token, String owner, String repo,
                                                int number, String mergeMethod) {
        return mergePullRequest(token, owner, repo, number, mergeMethod, false, null, null, null);
    }

    /**
     * Merges a pull request server-side - nothing in the local repository changes until
     * the next fetch.
     *
     * <h2>The two refusals</h2>
     * Gitea's own answers to a refused merge say little - a 405 often has no body at all -
     * so each gets a sentence saying what it means:
     * <ul>
     *   <li><b>405</b>: the pull request cannot be merged as it stands - conflicts, a
     *       draft, required checks or approvals missing, the style switched off for the
     *       repository, or it is already merged.</li>
     *   <li><b>409</b>: it changed underneath the merge - new commits on its branch
     *       ({@code headCommitId} no longer the head) or a conflict found while merging.</li>
     * </ul>
     *
     * @param mergeMethod  Gitea's {@code Do}: one of {@link #MERGE_STYLES}; passed through
     * @param deleteBranch delete the source branch once merged
     * @param title        the merge commit's title, or null for Gitea's default
     * @param message      the merge commit's message, or null for Gitea's default
     * @param headCommitId the head the user saw, so a push since then is refused with a
     *                     409 rather than merged unseen; null to merge whatever the head is
     */
    public GiteaResult<String> mergePullRequest(String token, String owner, String repo, int number,
                                                String mergeMethod, boolean deleteBranch, String title,
                                                String message, String headCommitId) {
        String json = gson.toJson(new MergeBody(mergeMethod, deleteBranch, title, message, headCommitId));
        Reply reply = exchange(token, repoPath(owner, repo) + "/pulls/" + number + "/merge", "POST", json);
        if (reply.failure() != null) return GiteaResult.failed(reply.failure().message(), reply.failure().kind());
        HttpResponse<String> response = reply.response();
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return GiteaResult.ok("Merged pull request #" + number);
        }
        String server = serverMessage(response);
        String detail = server == null ? "" : " (" + providerName() + " said: " + server + ")";
        if (status == 405) {
            return GiteaResult.failed("Pull request #" + number + " cannot be merged as it stands: it may"
                    + " have conflicts, be a draft, be waiting for required checks or approvals, use a merge"
                    + " style this repository does not allow, or already be merged" + detail + ".",
                    ApiFailure.of(response));
        }
        if (status == 409) {
            return GiteaResult.failed("Pull request #" + number + " changed while it was being merged: its"
                    + " branch has new commits, or it now conflicts with its base. Reload it and try again"
                    + detail + ".", ApiFailure.of(response));
        }
        return GiteaResult.failed(describeError(response), ApiFailure.of(response));
    }

    /** Gitea's {@code MergePullRequestOption}; nulls are left out of the JSON. */
    private record MergeBody(@SerializedName("Do") String action,
                             @SerializedName("delete_branch_after_merge") boolean deleteBranchAfterMerge,
                             @SerializedName("MergeTitleField") String title,
                             @SerializedName("MergeMessageField") String message,
                             @SerializedName("head_commit_id") String headCommitId) {
    }

    /**
     * The overall CI status of a commit - what {@code GitHubClient.getCheckStatus} answers
     * for GitHub, in the same {@link BuildStatus}.
     *
     * <h2>Why one call is enough</h2>
     * GitHub has two systems, check runs and commit statuses, and has to read both. Gitea
     * has only commit statuses - Gitea Actions, Forgejo Actions and Woodpecker all post to
     * them - and rolls them up into one combined {@code state}.
     *
     * <p>The roll-up covers one page of statuses, though - the server pages the list it
     * rolls up - so every page is read and each status counted on its own: a failure on
     * page two must not leave the icon green. Combining them the way
     * {@link BuildStatus#combine} does gives the same answer as Gitea's own roll-up, whose
     * order is also failure, then warning and pending, then success. No statuses at all is
     * {@link BuildStatus#NONE}.
     *
     * @param ref a commit sha, or a branch or tag name
     */
    public GiteaResult<BuildStatus> getCommitStatus(String token, String owner, String repo, String ref) {
        GiteaResult<List<BuildStatus>> each = getAll(token, repoPath(owner, repo) + "/commits/" + ref + "/status",
                CombinedStatusResponse.class, CombinedStatusResponse::each);
        if (!each.isOk()) return GiteaResult.failed(each.messageOrNull(), each.failureKind());
        return GiteaResult.ok(BuildStatus.combine(each.orNull()));
    }

    /**
     * A commit status state - one context's, or the roll-up's - as a {@link BuildStatus}.
     *
     * <p>{@code warning} is amber rather than green or red: Gitea draws it as a caution,
     * not a pass, and {@link BuildStatus} has no "passed with warnings" - pending is the
     * amber one. Anything unrecognised also waits rather than accuses, for the reason
     * {@code GitHubClient} gives: every real failure arrives as {@code failure} or
     * {@code error}, and a red icon that is wrong costs the reader the most.
     */
    static BuildStatus mapState(String state) {
        return switch (state == null ? "" : state) {
            // "skipped" is a newer Gitea state: ran nothing, stands in nobody's way.
            case "success", "skipped" -> BuildStatus.PASSED;
            case "failure", "error" -> BuildStatus.FAILED;
            // pending, warning, and anything a later server adds.
            default -> BuildStatus.PENDING;
        };
    }

    /** {@code /commits/{ref}/status}: one rolled-up state over the page's statuses. */
    private record CombinedStatusResponse(String state, @SerializedName("total_count") int totalCount,
                                          List<CommitStatus> statuses) {

        /**
         * This page's statuses, one each. A server that sent the roll-up without the list
         * is taken at its word; one with neither has no CI - Gitea reports an empty state
         * (or {@code pending}) with a zero count for that.
         */
        List<BuildStatus> each() {
            if (statuses != null && !statuses.isEmpty()) {
                return statuses.stream().map(s -> mapState(s == null ? null : s.status())).toList();
            }
            return totalCount > 0 ? List.of(mapState(state)) : List.of();
        }
    }

    /** One context's latest status; {@code status} uses the same names as the roll-up's {@code state}. */
    private record CommitStatus(String status, String context) {
    }

    /**
     * Every submitted review comment on a pull request, across every review.
     *
     * <h2>Why two steps</h2>
     * Gitea has no endpoint for a pull request's line comments as such: each comment
     * belongs to a review, and is listed through it. So the reviews are read first, every
     * page, and then the comments of each review that has any - {@code comments_count}
     * saves a round trip per approval with nothing attached.
     *
     * <p>A {@code PENDING} review is an unsubmitted draft that only its author can see;
     * its comments are left out, as GitHub leaves pending comments out of the same list.
     */
    public GiteaResult<List<GiteaReviewComment>> listReviewComments(String token, String owner, String repo,
                                                                    int prNumber) {
        String pull = repoPath(owner, repo) + "/pulls/" + prNumber;
        GiteaResult<List<GiteaReview>> reviews = getAll(token, pull + "/reviews",
                new TypeToken<List<GiteaReview>>() { }.getType());
        if (!reviews.isOk()) return GiteaResult.failed(reviews.messageOrNull(), reviews.failureKind());
        List<GiteaReviewComment> all = new ArrayList<>();
        for (GiteaReview review : reviews.orNull()) {
            if (review == null || "PENDING".equals(review.state())) continue;
            if (review.commentsCount() != null && review.commentsCount() == 0) continue;
            if (GitTaskRunner.isCancelled()) return GiteaResult.failed("Cancelled");
            GiteaResult<List<RawReviewComment>> comments = get(token,
                    pull + "/reviews/" + review.id() + "/comments",
                    new TypeToken<List<RawReviewComment>>() { }.getType());
            if (!comments.isOk()) return GiteaResult.failed(comments.messageOrNull(), comments.failureKind());
            if (comments.orNull() == null) continue;
            for (RawReviewComment raw : comments.orNull()) {
                if (raw != null) all.add(raw.toComment(review.id()));
            }
        }
        return GiteaResult.ok(all);
    }

    /**
     * Gitea's {@code PullReviewComment}. {@code position} is the line in the new file and
     * {@code original_position} the line in the old one; whichever is set says the side.
     */
    private record RawReviewComment(long id, String body, String path, Long position,
                                    @SerializedName("original_position") Long originalPosition,
                                    GiteaUser user,
                                    @SerializedName("commit_id") String commitId,
                                    @SerializedName("html_url") String htmlUrl,
                                    @SerializedName("pull_request_review_id") Long reviewId) {

        GiteaReviewComment toComment(long fallbackReviewId) {
            boolean onNew = position != null && position > 0;
            boolean onOld = !onNew && originalPosition != null && originalPosition > 0;
            Integer line = onNew ? Integer.valueOf(position.intValue())
                    : onOld ? Integer.valueOf(originalPosition.intValue()) : null;
            return new GiteaReviewComment(id, body, path, line, onOld ? "LEFT" : "RIGHT", user, commitId,
                    htmlUrl, reviewId == null ? fallbackReviewId : reviewId);
        }
    }

    /**
     * Posts a comment on one line of the pull request's diff, visible immediately.
     *
     * <p>Gitea has no stand-alone line comment: this submits a review whose event is
     * {@code COMMENT} - neither an approval nor a change request - holding just this one
     * comment, which is what Gitea's own "add single comment" button does. So the answer
     * is the review, not the comment.
     *
     * <p>The parameters are {@code GitHubClient.createReviewComment}'s: {@code line} is a
     * line number in the file on {@code side}, which Gitea calls {@code new_position} for
     * the head and {@code old_position} for the base.
     *
     * @param commitId the head the reviewer was reading; Gitea anchors the comment there
     * @param side     {@code "LEFT"} (the base/old version) or {@code "RIGHT"} (the head/
     *                 new version) of the diff
     */
    public GiteaResult<GiteaReview> createReviewComment(String token, String owner, String repo, int prNumber,
                                                        String commitId, String path, int line, String side,
                                                        String body) {
        boolean old = "LEFT".equals(side);
        CreateReviewBody review = new CreateReviewBody("COMMENT", commitId, "",
                List.of(new CreateReviewLine(path, body, old ? null : line, old ? line : null)));
        return post(token, repoPath(owner, repo) + "/pulls/" + prNumber + "/reviews", gson.toJson(review),
                GiteaReview.class);
    }

    /** Gitea's {@code CreatePullReviewOptions}. */
    private record CreateReviewBody(String event, @SerializedName("commit_id") String commitId, String body,
                                    List<CreateReviewLine> comments) {
    }

    /** Gitea's {@code CreatePullReviewComment}; the unused position is left out. */
    private record CreateReviewLine(String path, String body,
                                    @SerializedName("new_position") Integer newPosition,
                                    @SerializedName("old_position") Integer oldPosition) {
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    private static String repoPath(String owner, String repo) {
        return "/repos/" + owner + "/" + repo;
    }

    private <T> GiteaResult<T> get(String token, String path, Type type) {
        return send(token, path, "GET", null, type);
    }

    private <T> GiteaResult<T> post(String token, String path, String jsonBody, Type type) {
        return send(token, path, "POST", jsonBody, type);
    }

    private <T> GiteaResult<T> send(String token, String path, String method, String jsonBody, Type type) {
        Reply reply = exchange(token, path, method, jsonBody);
        if (reply.failure() != null) return GiteaResult.failed(reply.failure().message(), reply.failure().kind());
        return parse(reply.response(), type);
    }

    /** {@code path} with the page and its size added to its query. */
    private static String paged(String path, int page, int limit) {
        return path + (path.contains("?") ? "&" : "?") + "page=" + page + "&limit=" + limit;
    }

    /** One page of a list, and whether the server says there is another. */
    private <P, E> GiteaResult<ListPage<E>> getPage(String token, String path, int page, int perPage,
                                                    Type pageType, Function<P, List<E>> items) {
        Reply reply = exchange(token, paged(path, page, perPage), "GET", null);
        if (reply.failure() != null) return GiteaResult.failed(reply.failure().message(), reply.failure().kind());
        GiteaResult<P> one = parse(reply.response(), pageType);
        if (!one.isOk()) return GiteaResult.failed(one.messageOrNull(), one.failureKind());
        List<E> list = one.orNull() == null ? null : items.apply(one.orNull());
        long seen = (long) (page - 1) * perPage + (list == null ? 0 : list.size());
        return GiteaResult.ok(new ListPage<>(list, hasMore(reply.response(), seen)));
    }

    /**
     * Every page of a list, by page number - see the class comment for why the server's
     * next link is read but not followed.
     *
     * @param pageType the JSON shape of one page
     * @param items    the list inside one page (the page itself, for a plain array)
     */
    private <P, E> GiteaResult<List<E>> getAll(String token, String path, Type pageType,
                                               Function<P, List<E>> items) {
        List<E> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            if (page > ApiPaging.MAX_PAGES) {
                LOGGER.info("Stopped following " + path + " after " + ApiPaging.MAX_PAGES + " pages");
                break;
            }
            if (page > 1 && GitTaskRunner.isCancelled()) return GiteaResult.failed("Cancelled");
            Reply reply = exchange(token, paged(path, page, PAGE_LIMIT), "GET", null);
            if (reply.failure() != null) {
                return GiteaResult.failed(reply.failure().message(), reply.failure().kind());
            }
            GiteaResult<P> one = parse(reply.response(), pageType);
            if (!one.isOk()) return GiteaResult.failed(one.messageOrNull(), one.failureKind());
            List<E> list = one.orNull() == null ? null : items.apply(one.orNull());
            ApiPaging.addAll(all, list);
            // An empty page ends it whatever the headers say, so a server that keeps
            // claiming more cannot keep this asking.
            if (list == null || list.isEmpty() || !hasMore(reply.response(), all.size())) break;
        }
        return GiteaResult.ok(all);
    }

    private <E> GiteaResult<List<E>> getAll(String token, String path, Type listType) {
        return getAll(token, path, listType, (List<E> list) -> list);
    }

    /**
     * Whether the server says there is more after this page: a {@code rel="next"} link on
     * this server, or an {@code X-Total-Count} beyond the items read so far. The count
     * covers a server whose link names another host because of its {@code ROOT_URL}.
     *
     * @param seen how many items this page and those before it hold
     */
    private boolean hasMore(HttpResponse<String> response, long seen) {
        if (ApiPaging.nextLink(response, baseUri) != null) return true;
        OptionalLong total = response.headers().firstValueAsLong("X-Total-Count");
        return total.isPresent() && seen < total.getAsLong();
    }

    /** What came back: a response of any status, or a failure before one arrived. */
    private record Reply(HttpResponse<String> response, GiteaResult.Failure<?> failure) {
    }

    /**
     * Sends one request.
     *
     * @param path     relative to the base URI, query string included
     * @param jsonBody the request body, or null for none
     */
    private Reply exchange(String token, String path, String method, String jsonBody) {
        if (token == null || token.isBlank()) {
            return new Reply(null, new GiteaResult.Failure<>("No access token supplied"));
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(ApiErrors.endpoint(baseUri, path))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .method(method, jsonBody == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            return new Reply(null, new GiteaResult.Failure<>("Could not build the request: " + e.getMessage()));
        }
        try {
            return new Reply(http.send(request, HttpResponse.BodyHandlers.ofString()), null);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Gitea request failed", e);
            // TRANSPORT, not a rejection: nothing was reached, so this says nothing about
            // whether the token is still good.
            return new Reply(null, new GiteaResult.Failure<>("Could not reach " + providerName() + ": "
                    + describe(e), ApiFailure.TRANSPORT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(null, new GiteaResult.Failure<>("Request interrupted", ApiFailure.TRANSPORT));
        }
    }

    /** A response as {@code type}, or why not; a 2xx with no body is a success with null. */
    private <T> GiteaResult<T> parse(HttpResponse<String> response, Type type) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                T value = gson.fromJson(response.body(), type);
                return GiteaResult.ok(value);
            } catch (JsonSyntaxException e) {
                return GiteaResult.failed(providerName() + " returned a response this app could not read");
            }
        }
        return GiteaResult.failed(describeError(response), ApiFailure.of(response));
    }

    /**
     * What to call the server in messages: "Codeberg" for codeberg.org, otherwise the host
     * the user configured - the name they will recognise, rather than a product name that
     * does not say which of their servers is meant.
     */
    private String providerName() {
        String host = baseUri.getHost();
        if (host == null || host.isBlank()) return "Gitea";
        return host.equalsIgnoreCase("codeberg.org") ? "Codeberg" : host;
    }

    /**
     * What to tell the user about a failed request: {@link ApiErrors}' wording for a rate
     * limit or a rejected token, otherwise the server's own {@code message}.
     */
    private String describeError(HttpResponse<String> response) {
        String common = ApiErrors.describe(providerName(), response);
        if (common != null) return common;
        String server = serverMessage(response);
        if (server != null) return server;
        return providerName() + " request failed with status " + response.statusCode();
    }

    /** The {@code message} of Gitea's {@code APIError} body, or null when there is none. */
    private String serverMessage(HttpResponse<String> response) {
        try {
            ErrorBody error = gson.fromJson(response.body(), ErrorBody.class);
            if (error != null && error.message() != null && !error.message().isBlank()) {
                return error.message().trim();
            }
        } catch (JsonSyntaxException | IllegalStateException ignored) {
            // Not every failure is JSON - a reverse proxy's own HTML error page, say.
        }
        return null;
    }

    private record ErrorBody(String message) {
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
