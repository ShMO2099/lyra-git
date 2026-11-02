package com.jfx.git.core.bitbucket;

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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Talks to Bitbucket Cloud's REST API v2 - enough for an API token to be verified, the
 * account's own repositories to be listed, and a repository's open pull requests to be
 * listed (Wave 4, §4.3's Bitbucket arc of the parity plan). There is no issue listing:
 * Bitbucket Cloud's native Issue Tracker API was fully removed on 2026-08-20 - every
 * {@code /issues} endpoint now answers {@code 410 Gone} regardless of the repository,
 * verified live against several repositories while writing this, not merely one that
 * happened to have issues disabled.
 *
 * <p>Authenticates with {@code Authorization: Bearer <token>}, Bitbucket's current
 * recommended scheme for an API token (the older app-password scheme used HTTP Basic
 * auth with an account email).
 *
 * <p>Repository listing is the one place this client makes more than one HTTP call:
 * Bitbucket removed its own "list every repository I can see" shortcuts ({@code
 * /2.0/repositories?role=member} and {@code /2.0/user/permissions/repositories} both
 * now answer {@code 410 Gone}, verified live), so {@link #listRepositories} lists the
 * user's workspaces first and then each workspace's repositories in turn, aggregating
 * the results - the current, supported replacement.
 *
 * <p>The base URI is a constructor parameter rather than a hardcoded constant so a
 * test can point this at a local fake server instead of the real api.bitbucket.org,
 * the same reasoning {@link com.jfx.git.core.github.GitHubClient} already follows.
 */
public final class BitbucketClient {

    private static final Logger LOGGER = Logger.getLogger(BitbucketClient.class.getName());

    private final URI baseUri;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public BitbucketClient() {
        this(URI.create("https://api.bitbucket.org/2.0"));
    }

    public BitbucketClient(URI baseUri) {
        this.baseUri = baseUri;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** The authenticated user a token belongs to - both proves the token works and names who is connected. */
    public BitbucketResult<BitbucketUser> fetchAuthenticatedUser(String token) {
        return get(token, "/user", BitbucketUser.class);
    }

    /**
     * The authenticated user's own repositories, across every workspace they belong
     * to - see the class Javadoc for why this takes more than one HTTP call.
     */
    public BitbucketResult<List<BitbucketRepository>> listRepositories(String token) {
        BitbucketResult<List<BitbucketWorkspace>> workspaces = getAll(token, "/workspaces?pagelen=100",
                new TypeToken<Page<BitbucketWorkspace>>() { }.getType());
        if (!workspaces.isOk()) {
            return BitbucketResult.failed(workspaces.messageOrNull());
        }

        // Collect what works. A user can belong to a workspace whose repositories they
        // cannot list - a permission scope the token does not cover, a workspace under an
        // admin restriction - and failing the whole listing for one of those hid every
        // repository in all the others. GitHub's picker already shows what it can find;
        // this makes Bitbucket behave the same way rather than having two policies.
        List<BitbucketRepository> all = new ArrayList<>();
        String firstFailure = null;
        List<BitbucketWorkspace> workspaceList = workspaces.orNull();
        for (BitbucketWorkspace workspace : workspaceList) {
            BitbucketResult<List<BitbucketRepository>> repos = getAll(token,
                    "/repositories/" + workspace.slug() + "?role=member&pagelen=100",
                    new TypeToken<Page<BitbucketRepository>>() { }.getType());
            if (!repos.isOk()) {
                if (firstFailure == null) firstFailure = repos.messageOrNull();
                LOGGER.fine("Skipping workspace " + workspace.slug() + ": " + repos.messageOrNull());
                continue;
            }
            all.addAll(repos.orNull());
        }
        // Only when nothing at all could be listed is this a failure worth reporting: a
        // user with no workspaces legitimately has no repositories.
        if (all.isEmpty() && firstFailure != null) {
            return BitbucketResult.failed(firstFailure);
        }
        return BitbucketResult.ok(all);
    }

    /**
     * One page of a repository's open pull requests, newest first - what the pull
     * request dialog shows, a page at a time. Bitbucket says there is another by
     * putting its URL in {@code next}. See {@link com.jfx.git.core.ListPage}.
     *
     * @param page    1-based
     * @param perPage at most 50, Bitbucket's own limit for pull requests
     */
    public BitbucketResult<com.jfx.git.core.ListPage<BitbucketPullRequest>> listPullRequestsPage(
            String token, String workspace, String repoSlug, int page, int perPage) {
        BitbucketResult<Page<BitbucketPullRequest>> one = get(token,
                "/repositories/" + workspace + "/" + repoSlug + "/pullrequests?state=OPEN&sort=-created_on"
                        + "&pagelen=" + Math.min(perPage, 50) + "&page=" + page,
                new TypeToken<Page<BitbucketPullRequest>>() { }.getType());
        if (!one.isOk()) return BitbucketResult.failed(one.messageOrNull(), one.failureKind());
        Page<BitbucketPullRequest> current = one.orNull();
        if (current == null) return BitbucketResult.ok(new com.jfx.git.core.ListPage<>(List.of(), false));
        return BitbucketResult.ok(new com.jfx.git.core.ListPage<>(current.valuesOrEmpty(),
                current.next() != null && !current.next().isBlank()));
    }

    /** A repository's open pull requests, newest first. */
    public BitbucketResult<List<BitbucketPullRequest>> listPullRequests(String token, String workspace, String repoSlug) {
        // pagelen 50 is the most Bitbucket allows for pull requests.
        return getAll(token,
                "/repositories/" + workspace + "/" + repoSlug + "/pullrequests?state=OPEN&pagelen=50&sort=-created_on",
                new TypeToken<Page<BitbucketPullRequest>>() { }.getType());
    }


    /**
     * What to call this server when reporting a rate limit or a rejected token.
     *
     * <p>The public name is right for the public host and wrong for anything else: told
     * "Bitbucket rejected the token" while connected to their own company's server, a user
     * goes and checks the wrong account. The host is what they typed and what they will
     * recognise.
     */
    private String providerName() {
        String host = baseUri.getHost();
        if (host == null || host.isBlank()) return "Bitbucket";
        return host.equals("bitbucket.org") || host.endsWith(".bitbucket.org") ? "Bitbucket" : host;
    }
    /** One page of a Bitbucket listing; {@code next} is the following page's URL, if any. */
    private record Page<T>(List<T> values, String next) {

        /**
         * The page's items, empty when there are none.
         *
         * <p>Gson leaves {@code values} null for a response that omits the field - an
         * empty workspace, or an error body that parsed as a page - and every caller here
         * immediately iterates it. Read directly that is a NullPointerException out of a
         * repository picker, reported as an unexplained failure.
         */
        List<T> valuesOrEmpty() {
            return values == null ? List.of() : values;
        }
    }

    /**
     * Opens a new pull request. Bitbucket's own response to this call is the identical
     * pull-request shape {@link #listPullRequests} returns, so the same {@link
     * BitbucketPullRequest} model deserializes it directly.
     */
    public BitbucketResult<BitbucketPullRequest> createPullRequest(String token, String workspace, String repoSlug,
                                                                     String title, String description,
                                                                     String sourceBranch, String destinationBranch) {
        String json = gson.toJson(new CreatePullRequestBody(title, description,
                new BranchRef(new BranchName(sourceBranch)), new BranchRef(new BranchName(destinationBranch))));
        return post(token, "/repositories/" + workspace + "/" + repoSlug + "/pullrequests",
                json, BitbucketPullRequest.class);
    }

    private record CreatePullRequestBody(String title, String description, BranchRef source, BranchRef destination) {
    }

    private record BranchRef(BranchName branch) {
    }

    private record BranchName(String name) {
    }

    /** The overall CI status of a commit, reduced from Bitbucket's own per-build-key statuses. */
    public BitbucketResult<com.jfx.git.core.BuildStatus> getCommitStatus(String token, String workspace,
                                                                          String repoSlug, String revision) {
        BitbucketResult<List<CommitStatus>> page = getAll(token,
                "/repositories/" + workspace + "/" + repoSlug + "/commit/" + revision + "/statuses?pagelen=100",
                new TypeToken<Page<CommitStatus>>() { }.getType());
        if (!page.isOk()) {
            return BitbucketResult.failed(page.messageOrNull());
        }
        List<CommitStatus> statuses = page.orNull();
        if (statuses.isEmpty()) {
            return BitbucketResult.ok(com.jfx.git.core.BuildStatus.NONE);
        }
        List<com.jfx.git.core.BuildStatus> mapped = statuses.stream()
                .map(s -> mapStatus(s.state())).toList();
        return BitbucketResult.ok(com.jfx.git.core.BuildStatus.combine(mapped));
    }

    private static com.jfx.git.core.BuildStatus mapStatus(String state) {
        return switch (state == null ? "" : state) {
            case "SUCCESSFUL" -> com.jfx.git.core.BuildStatus.PASSED;
            case "INPROGRESS" -> com.jfx.git.core.BuildStatus.PENDING;
            default -> com.jfx.git.core.BuildStatus.FAILED;
        };
    }

    private record CommitStatus(String state) {
    }

    /**
     * Merges an open pull request server-side - Bitbucket does the merge itself;
     * nothing about the local repository changes until the caller next fetches or pulls.
     *
     * @param mergeStrategy one of Bitbucket's own {@code "merge_commit"}, {@code
     *                      "squash"}, or {@code "fast_forward"} - passed straight
     *                      through, not validated here
     * @return the merge commit's own hash on success
     */
    public BitbucketResult<String> mergePullRequest(String token, String workspace, String repoSlug,
                                                      int id, String mergeStrategy) {
        String json = gson.toJson(new MergeRequestBody(mergeStrategy));
        BitbucketResult<MergeResponse> result = post(token,
                "/repositories/" + workspace + "/" + repoSlug + "/pullrequests/" + id + "/merge",
                json, MergeResponse.class);
        if (!result.isOk()) {
            return BitbucketResult.failed(result.messageOrNull());
        }
        return BitbucketResult.ok(result.orNull().mergeCommit() == null
                ? null : result.orNull().mergeCommit().hash());
    }

    private record MergeRequestBody(@SerializedName("merge_strategy") String mergeStrategy) {
    }

    private record MergeResponse(@SerializedName("merge_commit") MergeCommit mergeCommit) {
    }

    private record MergeCommit(String hash) {
    }

    /** Every comment on a pull request, across every file and line, plus plain top-level comments. */
    public BitbucketResult<List<BitbucketComment>> listComments(String token, String workspace, String repoSlug,
                                                                  int prId) {
        return getAll(token,
                "/repositories/" + workspace + "/" + repoSlug + "/pullrequests/" + prId + "/comments?pagelen=100",
                new TypeToken<Page<BitbucketComment>>() { }.getType());
    }

    /**
     * Posts a new comment on a pull request, anchored to one line of the diff when
     * {@code path} is given - immediately visible. Bitbucket has no suggested-change
     * markdown syntax the way GitHub/GitLab do, so there is nothing for a caller to
     * compose specially here; {@code body} is posted exactly as given.
     *
     * @param newLine the line number in the new version to anchor to, or {@code null}
     *                to anchor to {@code oldLine} in the old version instead
     * @param oldLine the line number in the old version, used only when
     *                {@code newLine} is {@code null}
     */
    public BitbucketResult<BitbucketComment> createComment(String token, String workspace, String repoSlug,
                                                             int prId, String path, Integer newLine, Integer oldLine,
                                                             String body) {
        BitbucketComment.Inline inline = path == null ? null : new BitbucketComment.Inline(path, newLine, oldLine);
        String json = gson.toJson(new CreateCommentBody(new BitbucketComment.Content(body), inline));
        return post(token, "/repositories/" + workspace + "/" + repoSlug + "/pullrequests/" + prId + "/comments",
                json, BitbucketComment.class);
    }

    private record CreateCommentBody(BitbucketComment.Content content, BitbucketComment.Inline inline) {
    }

    private <T> BitbucketResult<T> get(String token, String path, java.lang.reflect.Type type) {
        return send(token, path, "GET", HttpRequest.BodyPublishers.noBody(), type);
    }

    /**
     * Every page of a paged listing, following each page's {@code next} - which the
     * listings here used to ignore, returning the first hundred and nothing after. See
     * {@link com.jfx.git.core.ApiPaging}.
     */
    private <T> BitbucketResult<List<T>> getAll(String token, String path, java.lang.reflect.Type pageType) {
        List<T> all = new ArrayList<>();
        String next = path;
        for (int page = 0; next != null; page++) {
            if (page == com.jfx.git.core.ApiPaging.MAX_PAGES) {
                LOGGER.info("Stopped following " + path + " after " + page + " pages");
                break;
            }
            if (page > 0 && com.jfx.git.core.GitTaskRunner.isCancelled()) {
                return BitbucketResult.failed("Cancelled");
            }
            BitbucketResult<Page<T>> one = get(token, next, pageType);
            if (!one.isOk()) return BitbucketResult.failed(one.messageOrNull(), one.failureKind());
            Page<T> current = one.orNull();
            if (current == null) break;
            all.addAll(current.valuesOrEmpty());
            URI following = com.jfx.git.core.ApiPaging.sameServer(current.next(), baseUri);
            next = following == null ? null : following.toString();
        }
        return BitbucketResult.ok(all);
    }

    private <T> BitbucketResult<T> post(String token, String path, String jsonBody, java.lang.reflect.Type type) {
        return send(token, path, "POST",
                HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8), type);
    }

    private <T> BitbucketResult<T> send(String token, String path, String method, HttpRequest.BodyPublisher body,
                                         java.lang.reflect.Type type) {
        if (token == null || token.isBlank()) {
            return BitbucketResult.failed("No API token supplied");
        }
        HttpRequest request;
        try {
            // An absolute path is a page's "next" link, already checked to be on this server.
            URI target = path.startsWith("https://") || path.startsWith("http://")
                    ? URI.create(path)
                    : com.jfx.git.core.ApiErrors.endpoint(baseUri, path);
            request = HttpRequest.newBuilder(target)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .method(method, body)
                    .build();
        } catch (Exception e) {
            return BitbucketResult.failed("Could not build the request: " + e.getMessage());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Bitbucket request failed", e);
            // TRANSPORT, not a rejection: nothing was reached, so this says nothing
            // about whether the token is still good.
            return BitbucketResult.failed("Could not reach Bitbucket: " + describe(e),
                    com.jfx.git.core.ApiFailure.TRANSPORT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BitbucketResult.failed("Request interrupted",
                    com.jfx.git.core.ApiFailure.TRANSPORT);
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            try {
                T value = gson.fromJson(response.body(), type);
                return BitbucketResult.ok(value);
            } catch (JsonSyntaxException e) {
                return BitbucketResult.failed("Bitbucket returned a response this app could not read");
            }
        }
        return BitbucketResult.failed(describeError(response),
                com.jfx.git.core.ApiFailure.of(response));
    }

    /**
     * Bitbucket's own {@code error.message} field when there is one, otherwise the bare
     * status - except for rate limiting and an expired token, which get the wording in
     * {@link com.jfx.git.core.ApiErrors}.
     */
    private String describeError(HttpResponse<String> response) {
        String common = com.jfx.git.core.ApiErrors.describe(providerName(), response);
        if (common != null) return common;
        try {
            var error = gson.fromJson(response.body(), ErrorBody.class);
            if (error != null && error.error() != null && error.error().message() != null
                    && !error.error().message().isBlank()) {
                return error.error().message();
            }
        } catch (JsonSyntaxException ignored) {
            // Not every failure response is JSON (a proxy's own HTML error page, say).
        }
        return "Bitbucket request failed with status " + response.statusCode();
    }

    private record ErrorBody(ErrorDetail error) {
    }

    private record ErrorDetail(String message) {
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
