package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.bitbucket.BitbucketUser;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.dialogs.CreatePullRequestDialog;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link BitbucketPullRequestsView} against a real local HTTP server for the
 * Bitbucket side and a {@link Proxy}-based {@link GitBackend} stub for the git side -
 * the same two techniques {@code GitHubPullRequestsViewTest} uses. The two checkout
 * tests are the point of this class: proving the view picks {@code origin} for a
 * same-repository PR and the fork's own clone URL for one that is not, which is the
 * one piece of real logic Bitbucket's version of this view adds over GitHub/GitLab's.
 */
class BitbucketPullRequestsViewTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        BitbucketSession.disconnect();
    }

    @FunctionalInterface
    private interface CheckoutRemoteRef {
        GitResult apply(String remoteNameOrUrl, String remoteRef, String localBranchName);
    }

    private static GitBackend stubBackend(String originUrl, CheckoutRemoteRef onCheckout) {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getRemoteUrl".equals(method.getName())) return originUrl;
                    if ("checkoutRemoteRef".equals(method.getName())) {
                        return onCheckout.apply((String) args[0], (String) args[1], (String) args[2]);
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    private BitbucketClient fakeClient(String path, int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new BitbucketClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private BitbucketPullRequestsView buildView(
            GitBackend backend, BitbucketClient client, StatusBar statusBar, Runnable onCheckedOut) {
        return FxHarness.call(() -> {
            BitbucketPullRequestsView view =
                    new BitbucketPullRequestsView(OperationRunner.of(backend, statusBar, onCheckedOut), client);
            view.content().applyCss();
            view.content().layout();
            return view;
        });
    }

    private static final String SAME_REPO_PR_BODY = """
            {"values": [{"id": 124, "title": "Security upgrade", "author": {"username": "atlassian", "display_name": "Atlassian"},
              "source": {"branch": {"name": "fix-branch"}, "repository": {"full_name": "atlassian/localstack"}, "commit": {"hash": "abc123"}},
              "destination": {"branch": {"name": "master"}, "repository": {"full_name": "atlassian/localstack"}},
              "links": {"html": {"href": "https://bitbucket.org/atlassian/localstack/pull-requests/124"}}}]}""";

    private static final String FORK_PR_BODY = """
            {"values": [{"id": 200, "title": "Fix from a fork", "author": {"username": "someuser", "display_name": "Some User"},
              "source": {"branch": {"name": "fix-branch"}, "repository": {"full_name": "someuser/localstack"}},
              "destination": {"branch": {"name": "master"}, "repository": {"full_name": "atlassian/localstack"}},
              "links": {"html": {"href": "https://bitbucket.org/atlassian/localstack/pull-requests/200"}}}]}""";

    private static final String TWO_PRS_BODY = """
            {"values": [
              {"id": 124, "title": "Security upgrade", "author": {"username": "atlassian", "display_name": "Atlassian"},
               "source": {"branch": {"name": "fix-branch"}, "repository": {"full_name": "atlassian/localstack"}},
               "destination": {"branch": {"name": "master"}, "repository": {"full_name": "atlassian/localstack"}},
               "links": {"html": {"href": "https://bitbucket.org/atlassian/localstack/pull-requests/124"}}},
              {"id": 88, "title": "Add dark mode toggle", "author": {"username": "someuser", "display_name": "Some User"},
               "source": {"branch": {"name": "dark-mode"}, "repository": {"full_name": "atlassian/localstack"}},
               "destination": {"branch": {"name": "master"}, "repository": {"full_name": "atlassian/localstack"}},
               "links": {"html": {"href": "https://bitbucket.org/atlassian/localstack/pull-requests/88"}}}
            ]}""";

    @Test
    void notConnectedShowsAPlaceholderAndNeverCallsTheClient() throws Exception {
        FxHarness.start();
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void aNonBitbucketRemoteShowsAPlaceholderWithoutCallingTheClient() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void openPullRequestsArePopulatedWhenConnectedToABitbucketRemote() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(124, FxHarness.call(view::loadedEntries).get(0).id());
    }

    @Test
    void checkingOutASameRepositoryPullRequestFetchesFromOriginByBranchName() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);

        AtomicInteger checkoutCalls = new AtomicInteger();
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (remote, ref, local) -> {
            checkoutCalls.incrementAndGet();
            assertEquals("origin", remote, "same-repository PR: fetch from the configured remote");
            assertEquals("refs/heads/fix-branch", ref);
            assertEquals("pr-124", local);
            return GitResult.ok("Checked out pr-124");
        });

        AtomicInteger refreshCalls = new AtomicInteger();
        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
        assertEquals(1, checkoutCalls.get());
    }

    @Test
    void checkingOutAForkPullRequestFetchesDirectlyFromTheForksCloneUrl() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, FORK_PR_BODY);

        AtomicInteger checkoutCalls = new AtomicInteger();
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (remote, ref, local) -> {
            checkoutCalls.incrementAndGet();
            assertEquals("https://bitbucket.org/someuser/localstack.git", remote,
                    "a fork PR has to be fetched from its own clone URL, not origin");
            assertEquals("refs/heads/fix-branch", ref);
            assertEquals("pr-200", local);
            return GitResult.ok("Checked out pr-200");
        });

        AtomicInteger refreshCalls = new AtomicInteger();
        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
        assertEquals(1, checkoutCalls.get());
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    private void respondToMerge(int status, String body) {
        server.createContext("/repositories/atlassian/localstack/pullrequests/124/merge", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void mergingRunsTheBackendMergeAndRefreshesOnSuccess() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        respondToMerge(200, """
                {"merge_commit": {"hash": "abc1234"}}""");
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        AtomicInteger refreshCalls = new AtomicInteger();
        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.mergeForTest(view.loadedEntries().get(0), "squash"));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
    }

    @Test
    void aFailedMergeDoesNotRefresh() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        respondToMerge(409, """
                {"error": {"message": "This pull request has conflicts and cannot be merged."}}""");
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        AtomicInteger refreshCalls = new AtomicInteger();
        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.mergeForTest(view.loadedEntries().get(0), "merge_commit"));
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(200); // let the async merge's callback land before asserting nothing happened.

        assertEquals(0, refreshCalls.get());
        assertTrue(FxHarness.call(view::isMergeEnabled), "selection is unchanged, so merge stays available");
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    /**
     * List and create share one path ({@code GET}/{@code POST} to the same {@code
     * .../pullrequests}) - {@code HttpServer} routes by path only, so one context has
     * to dispatch on method itself, counting list requests so a test can tell whether
     * a reload happened without needing the list's own content to change. Unlike
     * GitHub/GitLab, the list body needs Bitbucket's {@code values} envelope but the
     * create response - a single pull request, not a page of them - does not.
     */
    private volatile int listRequestCount;

    private BitbucketClient fakeClientForCreate(String listBody, int createStatus, String createBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext("/repositories/atlassian/localstack/pullrequests", exchange -> {
            String responseBody;
            int status;
            if ("POST".equals(exchange.getRequestMethod())) {
                responseBody = createBody;
                status = createStatus;
            } else {
                listRequestCount++;
                responseBody = listBody;
                status = 200;
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new BitbucketClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    @Test
    void creatingAPullRequestSendsTheDraftAndReloadsTheList() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClientForCreate(SAME_REPO_PR_BODY, 201, """
                {"id": 201, "title": "New feature", "author": {"username": "atlassian", "display_name": "Atlassian"},
                 "source": {"branch": {"name": "new-feature"}, "repository": {"full_name": "atlassian/localstack"}},
                 "destination": {"branch": {"name": "master"}, "repository": {"full_name": "atlassian/localstack"}},
                 "links": {"html": {"href": "https://bitbucket.org/atlassian/localstack/pull-requests/201"}}}""");
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));
        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 1);

        FxHarness.run(() -> view.createForTest(
                new CreatePullRequestDialog.Draft("New feature", "adds a thing", "new-feature", "master")));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 2);
    }

    @Test
    void aFailedCreateDoesNotReloadTheList() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClientForCreate(SAME_REPO_PR_BODY, 400, """
                {"error": {"message": "Source and destination branches must be different."}}""");
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));
        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 1);
        int countBeforeCreate = listRequestCount;

        FxHarness.run(() -> view.createForTest(
                new CreatePullRequestDialog.Draft("Nothing to merge", null, "master", "master")));
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(200); // let the async create's callback land before asserting nothing happened.

        assertEquals(countBeforeCreate, listRequestCount, "a failed create should not trigger a reload");
    }

    @Test
    void filteringByTitleNarrowsTheList() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, TWO_PRS_BODY);
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == 2);

        FxHarness.run(() -> view.filterForTest("dark"));
        WaitForAsyncUtils.waitForFxEvents();

        List<com.jfx.git.core.bitbucket.BitbucketPullRequest> shown = FxHarness.call(view::loadedEntries);
        assertEquals(1, shown.size());
        assertEquals(88, shown.get(0).id());
    }

    @Test
    void filteringByAuthorNarrowsTheList() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, TWO_PRS_BODY);
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == 2);

        FxHarness.run(() -> view.filterForTest("SOMEUSER"));
        WaitForAsyncUtils.waitForFxEvents();

        List<com.jfx.git.core.bitbucket.BitbucketPullRequest> shown = FxHarness.call(view::loadedEntries);
        assertEquals(1, shown.size());
        assertEquals(88, shown.get(0).id());
    }

    @Test
    void aFilterMatchingNothingEmptiesTheListWithoutLosingTheRealData() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, TWO_PRS_BODY);
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == 2);

        FxHarness.run(() -> view.filterForTest("no-such-thing"));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());

        FxHarness.run(() -> view.filterForTest(""));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(2, FxHarness.call(view::loadedEntries).size());
    }

    // ------------------------------------------------------------------
    // CI status
    // ------------------------------------------------------------------

    private void respondToStatuses(String revision, int status, String body) {
        server.createContext("/repositories/atlassian/localstack/commit/" + revision + "/statuses", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void aPassingBuildColoursTheRowsStatus() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        respondToStatuses("abc123", 200, """
                {"values": [{"state": "SUCCESSFUL"}]}""");
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(124)) == com.jfx.git.core.BuildStatus.PASSED);
    }

    @Test
    void aCommitWithNoStatusesIsReportedAsNone() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        respondToStatuses("abc123", 200, """
                {"values": []}""");
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(124)) == com.jfx.git.core.BuildStatus.NONE);
    }

    // ------------------------------------------------------------------
    // Review…
    // ------------------------------------------------------------------

    private static com.jfx.git.core.bitbucket.BitbucketPullRequest testPr() {
        return new com.jfx.git.core.bitbucket.BitbucketPullRequest(124, "Security upgrade",
                new BitbucketUser("atlassian", "Atlassian"),
                new com.jfx.git.core.bitbucket.BitbucketPullRequest.Endpoint(
                        new com.jfx.git.core.bitbucket.BitbucketPullRequest.Branch("fix-branch"),
                        new com.jfx.git.core.bitbucket.BitbucketPullRequest.Repository("atlassian/localstack"),
                        new com.jfx.git.core.bitbucket.BitbucketPullRequest.Commit("headsha123")),
                new com.jfx.git.core.bitbucket.BitbucketPullRequest.Endpoint(
                        new com.jfx.git.core.bitbucket.BitbucketPullRequest.Branch("master"),
                        new com.jfx.git.core.bitbucket.BitbucketPullRequest.Repository("atlassian/localstack"),
                        new com.jfx.git.core.bitbucket.BitbucketPullRequest.Commit("basesha456")),
                new com.jfx.git.core.bitbucket.BitbucketPullRequest.Links(
                        new com.jfx.git.core.bitbucket.BitbucketPullRequest.LinkHref(
                                "https://bitbucket.org/atlassian/localstack/pull-requests/124")));
    }

    /** Answers {@code fetchRemoteRef}/{@code getFilesBetweenCommits}/{@code getDiffBytesBetweenCommits} explicitly. */
    private static GitBackend reviewStubBackend(String originUrl, GitResult fetchResult,
                                                 List<String> changedFiles, String diffText) {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRemoteUrl": return originUrl;
                        case "fetchRemoteRef": return fetchResult;
                        case "fetchAll": return GitResult.ok("fetched");
                        case "getFilesBetweenCommits": return changedFiles;
                        case "getDiffBytesBetweenCommits": return diffText.getBytes(StandardCharsets.UTF_8);
                        default: break;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    @Test
    void reviewButtonEnablesOnlyWhenAPullRequestIsSelected() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        assertFalse(FxHarness.call(view::isReviewEnabled), "nothing selected yet");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));
        assertTrue(FxHarness.call(view::isReviewEnabled));
    }

    @Test
    void reviewFilesForTestFetchesTheSourceBranchThenDiffsEachChangedFile() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        GitBackend backend = reviewStubBackend("https://bitbucket.org/atlassian/localstack.git",
                GitResult.ok("fetched"), List.of("src/Main.java"), "@@ -1 +1 @@\n-old\n+new\n");

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        var outcome = FxHarness.call(() -> view.reviewFilesForTest(testPr()));

        assertTrue(outcome.ok(), outcome.message());
        assertEquals(1, outcome.value().size());
        assertEquals("src/Main.java", outcome.value().get(0).path());
    }

    @Test
    void reviewFilesForTestFailsCleanlyWithoutADestinationCommit() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        // SAME_REPO_PR_BODY's own destination carries no "commit" field at all - the
        // real defensive case this guards, not a contrived one.
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        GitBackend backend = reviewStubBackend("https://bitbucket.org/atlassian/localstack.git",
                GitResult.ok("fetched"), List.of(), "");

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        var realPrFromServer = FxHarness.call(view::loadedEntries).get(0);

        var outcome = FxHarness.call(() -> view.reviewFilesForTest(realPrFromServer));

        assertFalse(outcome.ok());
    }

    @Test
    void reviewBackendFetchesAndConvertsBitbucketsInlineComments() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        server.createContext("/repositories/atlassian/localstack/pullrequests/124/comments", exchange -> {
            byte[] bytes = """
                    {"values": [
                        {"id": 1, "content": {"raw": "typo here"},
                         "user": {"username": "octocat", "display_name": "Octocat"},
                         "inline": {"path": "src/Main.java", "to": 10, "from": null}}
                    ]}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        java.util.concurrent.atomic.AtomicReference<PrReviewView.Outcome<List<com.jfx.git.model.ReviewComment>>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        FxHarness.run(() -> reviewBackend.fetchComments(result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertTrue(result.get().ok(), result.get().message());
        var comment = result.get().value().get(0);
        assertEquals("src/Main.java", comment.path());
        assertEquals(10, comment.line());
        assertEquals(com.jfx.git.model.ReviewComment.Side.NEW, comment.side());
        assertEquals("octocat", comment.author());
    }

    @Test
    void reviewBackendReportsNoSuggestionSupport() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        assertFalse(reviewBackend.supportsSuggestions(), "Bitbucket has no suggested-change markdown syntax");
    }

    @Test
    void reviewBackendPostsWithTheRightPathAndLine() throws Exception {
        FxHarness.start();
        BitbucketSession.connect("token", new BitbucketUser("atlassian", "Atlassian"));
        BitbucketClient client = fakeClient("/repositories/atlassian/localstack/pullrequests", 200, SAME_REPO_PR_BODY);
        java.util.concurrent.atomic.AtomicReference<String> lastBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/repositories/atlassian/localstack/pullrequests/124/comments", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {"id": 99, "content": {"raw": "please rename this"},
                     "user": {"username": "octocat", "display_name": "Octocat"},
                     "inline": {"path": "src/Main.java", "to": 10, "from": null}}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        GitBackend backend = stubBackend("https://bitbucket.org/atlassian/localstack.git", (r, ref, l) -> GitResult.ok("x"));

        BitbucketPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        java.util.concurrent.atomic.AtomicReference<PrReviewView.Outcome<Void>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        FxHarness.run(() -> reviewBackend.postComment("src/Main.java", 10, com.jfx.git.model.ReviewComment.Side.NEW,
                "please rename this", result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertTrue(result.get().ok(), result.get().message());
        assertTrue(lastBody.get().contains("\"path\":\"src/Main.java\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"to\":10"), lastBody.get());
    }
}
