package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.model.ReviewComment;
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
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link GitHubPullRequestsView} against a real local HTTP server for the
 * GitHub side (the same technique {@code GitHubClientTest} and {@code
 * GitHubRepoPickerDialogTest} use) and a {@link Proxy}-based {@link GitBackend} stub
 * for the git side (the same technique {@code CommitFadeRobotTest.stubBackend}
 * uses) - what this class tests is the view's own wiring between the two, not
 * either one's real behaviour, which is covered separately: {@code GitHubClientTest}
 * for the HTTP client, {@code GitServiceTest
 * .checkoutPullRequestFetchesTheHeadRefAndChecksItOutAsALocalBranch} for the real git
 * plumbing of {@code checkoutPullRequest}.
 */
class GitHubPullRequestsViewTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        GitHubSession.disconnect();
    }

    /** Answers {@code getRemoteUrl("origin")} and {@code checkoutPullRequest} explicitly; everything else harmlessly. */
    private static GitBackend stubBackend(String originUrl, BiFunction<String, Integer, GitResult> onCheckout) {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getRemoteUrl".equals(method.getName())) return originUrl;
                    if ("checkoutPullRequest".equals(method.getName())) {
                        return onCheckout.apply((String) args[0], (Integer) args[1]);
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

    private GitHubClient fakeClient(String path, int status, String body) throws Exception {
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
        return new GitHubClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private GitHubPullRequestsView buildView(
            GitBackend backend, GitHubClient client, StatusBar statusBar, Runnable onCheckedOut) {
        return FxHarness.call(() -> {
            GitHubPullRequestsView view = new GitHubPullRequestsView(OperationRunner.of(backend, statusBar, onCheckedOut), client);
            view.content().applyCss();
            view.content().layout();
            return view;
        });
    }

    private static final String OPEN_PRS_BODY = """
            [{"number": 42, "title": "Fix flaky test", "user": {"login": "octocat", "avatar_url": null},
              "draft": false, "head": {"ref": "fix-flaky-test", "sha": "abc123"}, "base": {"ref": "main"},
              "html_url": "https://github.com/octocat/Hello-World/pull/42"}]""";

    private static final String TWO_OPEN_PRS_BODY = """
            [{"number": 42, "title": "Fix flaky test", "user": {"login": "octocat", "avatar_url": null},
              "draft": false, "head": {"ref": "fix-flaky-test", "sha": "abc123"}, "base": {"ref": "main"},
              "html_url": "https://github.com/octocat/Hello-World/pull/42"},
             {"number": 7, "title": "Add dark mode toggle", "user": {"login": "hubot", "avatar_url": null},
              "draft": false, "head": {"ref": "dark-mode", "sha": "def456"}, "base": {"ref": "main"},
              "html_url": "https://github.com/octocat/Hello-World/pull/7"}]""";

    @Test
    void notConnectedShowsAPlaceholderAndNeverCallsTheClient() throws Exception {
        FxHarness.start();
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        // Give any (wrongly fired) async load a moment to land before asserting nothing did.
        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void aNonGitHubRemoteShowsAPlaceholderWithoutCallingTheClient() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void openPullRequestsArePopulatedWhenConnectedToAGitHubRemote() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(42, FxHarness.call(view::loadedEntries).get(0).number());
    }

    @Test
    void checkingOutRunsTheBackendCheckoutAndRefreshesOnSuccess() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);

        AtomicInteger checkoutCalls = new AtomicInteger();
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (remote, number) -> {
            checkoutCalls.incrementAndGet();
            assertEquals("origin", remote);
            assertEquals(42, number);
            return GitResult.ok("Checked out pull request #42 as pr-42");
        });

        AtomicInteger refreshCalls = new AtomicInteger();
        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());

        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
        assertEquals(1, checkoutCalls.get());
    }

    @Test
    void aFailedCheckoutDoesNotRefresh() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);

        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git",
                (remote, number) -> GitResult.failed("simulated failure"));

        AtomicInteger refreshCalls = new AtomicInteger();
        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());

        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(200); // let the async checkout's callback land before asserting nothing happened.

        assertEquals(0, refreshCalls.get());
        assertTrue(FxHarness.call(view::isCheckoutEnabled), "selection is unchanged, so checkout stays available");
    }

    /**
     * A checkout that stopped on a conflict - the changes auto-stash put back clashed with
     * the pull request - still moved the branch, so the tab has to show where it is now.
     */
    @Test
    void aCheckoutThatStoppedOnAConflictRefreshes() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);

        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git",
                (remote, number) -> GitResult.conflict("Re-applying your changes conflicted", List.of("a.txt")));

        AtomicInteger refreshCalls = new AtomicInteger();
        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());

        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
    }

    @Test
    void filteringByTitleNarrowsTheList() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, TWO_OPEN_PRS_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == 2);

        FxHarness.run(() -> view.filterForTest("dark"));
        WaitForAsyncUtils.waitForFxEvents();

        List<com.jfx.git.core.github.GitHubPullRequest> shown = FxHarness.call(view::loadedEntries);
        assertEquals(1, shown.size());
        assertEquals(7, shown.get(0).number());
    }

    @Test
    void filteringByAuthorNarrowsTheList() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, TWO_OPEN_PRS_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == 2);

        // Case-insensitive on purpose - a user typing a login rarely matches its case exactly.
        FxHarness.run(() -> view.filterForTest("HUBOT"));
        WaitForAsyncUtils.waitForFxEvents();

        List<com.jfx.git.core.github.GitHubPullRequest> shown = FxHarness.call(view::loadedEntries);
        assertEquals(1, shown.size());
        assertEquals(7, shown.get(0).number());
    }

    @Test
    void aFilterMatchingNothingEmptiesTheListWithoutLosingTheRealData() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, TWO_OPEN_PRS_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == 2);

        FxHarness.run(() -> view.filterForTest("no-such-thing"));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());

        // Clearing the filter must bring both rows back - nothing was discarded, just hidden.
        FxHarness.run(() -> view.filterForTest(""));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(2, FxHarness.call(view::loadedEntries).size());
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    private void respondToMerge(int status, String body) {
        server.createContext("/repos/octocat/Hello-World/pulls/42/merge", exchange -> {
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
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        respondToMerge(200, """
                {"sha": "abc", "merged": true, "message": "Pull Request successfully merged"}""");
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        AtomicInteger refreshCalls = new AtomicInteger();
        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.mergeForTest(view.loadedEntries().get(0), "squash"));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
    }

    @Test
    void aFailedMergeDoesNotRefresh() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        respondToMerge(405, """
                {"message": "Pull Request is not mergeable", "documentation_url": "https://docs.github.com/rest"}""");
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        AtomicInteger refreshCalls = new AtomicInteger();
        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.mergeForTest(view.loadedEntries().get(0), "merge"));
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
     * .../pulls}) - {@link HttpServer} routes by path only, so one context has to
     * dispatch on method itself, counting list requests so a test can tell whether a
     * reload happened without needing the list's own content to change.
     */
    private volatile int listRequestCount;

    private GitHubClient fakeClientForCreate(String listBody, int createStatus, String createBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext("/repos/octocat/Hello-World/pulls", exchange -> {
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
        return new GitHubClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    @Test
    void creatingAPullRequestSendsTheDraftAndReloadsTheList() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClientForCreate(OPEN_PRS_BODY, 201, """
                {"number": 55, "title": "New feature", "user": {"login": "octocat", "avatar_url": null},
                 "draft": false, "head": {"ref": "new-feature", "sha": "abc"}, "base": {"ref": "main"},
                 "html_url": "https://github.com/octocat/Hello-World/pull/55"}""");
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));
        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 1);

        FxHarness.run(() -> view.createForTest(
                new CreatePullRequestDialog.Draft("New feature", "adds a thing", "new-feature", "main")));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 2);
    }

    @Test
    void aFailedCreateDoesNotReloadTheList() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClientForCreate(OPEN_PRS_BODY, 422, """
                {"message": "Validation Failed", "documentation_url": "https://docs.github.com/rest"}""");
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));
        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 1);
        int countBeforeCreate = listRequestCount;

        FxHarness.run(() -> view.createForTest(
                new CreatePullRequestDialog.Draft("Nothing to merge", null, "main", "main")));
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(200); // let the async create's callback land before asserting nothing happened.

        assertEquals(countBeforeCreate, listRequestCount, "a failed create should not trigger a reload");
    }

    // ------------------------------------------------------------------
    // CI status
    // ------------------------------------------------------------------

    private void respondToCheckRuns(String sha, int status, String body) {
        server.createContext("/repos/octocat/Hello-World/commits/" + sha + "/check-runs", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void aPassingCheckRunColoursTheRowsStatus() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        respondToCheckRuns("abc123", 200, """
                {"total_count": 1, "check_runs": [{"status": "completed", "conclusion": "success"}]}""");
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(42)) == com.jfx.git.core.BuildStatus.PASSED);
    }

    @Test
    void aCommitWithNoCheckRunsIsReportedAsNone() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        respondToCheckRuns("abc123", 200, """
                {"total_count": 0, "check_runs": []}""");
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(42)) == com.jfx.git.core.BuildStatus.NONE);
    }

    // ------------------------------------------------------------------
    // Review…
    // ------------------------------------------------------------------

    private static com.jfx.git.core.github.GitHubPullRequest testPr() {
        return new com.jfx.git.core.github.GitHubPullRequest(42, "Fix flaky test",
                new GitHubUser("octocat", null), false,
                new com.jfx.git.core.github.GitHubPullRequest.Head("fix-flaky-test", "headsha123"),
                new com.jfx.git.core.github.GitHubPullRequest.Base("main", "basesha456"),
                "https://github.com/octocat/Hello-World/pull/42");
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
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        assertFalse(FxHarness.call(view::isReviewEnabled), "nothing selected yet");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));
        assertTrue(FxHarness.call(view::isReviewEnabled), "selecting a row to act on it also selects it for review");
    }

    @Test
    void reviewFilesForTestFetchesTheHeadRefThenDiffsEachChangedFile() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        GitBackend backend = reviewStubBackend("https://github.com/octocat/Hello-World.git",
                GitResult.ok("fetched"), List.of("src/Main.java"), "@@ -1 +1 @@\n-old\n+new\n");

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        var outcome = FxHarness.call(() -> view.reviewFilesForTest(testPr()));

        assertTrue(outcome.ok(), outcome.message());
        assertEquals(1, outcome.value().size());
        assertEquals("src/Main.java", outcome.value().get(0).path());
        assertTrue(new String(outcome.value().get(0).diffBytes(), StandardCharsets.UTF_8).contains("+new"));
    }

    @Test
    void reviewFilesForTestFailsCleanlyWhenTheHeadRefCannotBeFetched() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        GitBackend backend = reviewStubBackend("https://github.com/octocat/Hello-World.git",
                GitResult.failed("could not fetch refs/pull/42/head"), List.of(), "");

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        var outcome = FxHarness.call(() -> view.reviewFilesForTest(testPr()));

        assertFalse(outcome.ok());
        assertEquals("could not fetch refs/pull/42/head", outcome.message());
    }

    @Test
    void reviewBackendFetchesAndConvertsGitHubsReviewComments() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        server.createContext("/repos/octocat/Hello-World/pulls/42/comments", exchange -> {
            byte[] bytes = """
                    [{"id": 1, "body": "typo here", "path": "src/Main.java", "line": 10, "side": "RIGHT",
                      "user": {"login": "octocat", "avatar_url": null}, "commit_id": "headsha123",
                      "html_url": "https://github.com/octocat/Hello-World/pull/42#discussion_r1"}]"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        java.util.concurrent.atomic.AtomicReference<PrReviewView.Outcome<List<ReviewComment>>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        FxHarness.run(() -> reviewBackend.fetchComments(result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertTrue(result.get().ok(), result.get().message());
        ReviewComment comment = result.get().value().get(0);
        assertEquals("src/Main.java", comment.path());
        assertEquals(10, comment.line());
        assertEquals(ReviewComment.Side.NEW, comment.side());
        assertEquals("octocat", comment.author());
        assertEquals("typo here", comment.body());
    }

    @Test
    void reviewBackendPostsWithTheHeadCommitAndTheRightSide() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);
        java.util.concurrent.atomic.AtomicReference<String> lastBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/repos/octocat/Hello-World/pulls/42/comments", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {"id": 99, "body": "please rename this", "path": "src/Main.java", "line": 10, "side": "RIGHT",
                     "user": {"login": "octocat", "avatar_url": null}, "commit_id": "headsha123",
                     "html_url": "https://github.com/octocat/Hello-World/pull/42#discussion_r99"}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        java.util.concurrent.atomic.AtomicReference<PrReviewView.Outcome<Void>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        FxHarness.run(() -> reviewBackend.postComment("src/Main.java", 10, ReviewComment.Side.NEW,
                "please rename this", result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertTrue(result.get().ok(), result.get().message());
        assertTrue(lastBody.get().contains("\"commit_id\":\"headsha123\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"side\":\"RIGHT\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"line\":10"), lastBody.get());
    }

    /**
     * R6-1. A push during the review must not move where the comment lands.
     *
     * <p>The line number came from the diff of the commit the reviewer is reading, and
     * GitHub interprets it against whichever commit the comment is anchored to. Anchoring
     * to the newest one instead reinterprets that line in a different file: if the content
     * has shifted, the comment attaches to the wrong code and nothing says so. Posting
     * against the reviewed commit is valid - it is still part of the pull request - and
     * GitHub marks it outdated, which is what its own review UI does.
     */
    @Test
    void aPushDuringTheReviewDoesNotMoveWhereTheCommentLands() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/pulls", 200, OPEN_PRS_BODY);

        // The pull request has moved on since this review was opened.
        server.createContext("/repos/octocat/Hello-World/pulls/42", exchange -> {
            byte[] bytes = """
                    {"number": 42, "title": "Fix flaky test", "state": "open", "draft": false,
                     "head": {"ref": "fix-flaky-test", "sha": "muchnewersha"},
                     "base": {"ref": "main", "sha": "basesha456"},
                     "user": {"login": "octocat", "avatar_url": null},
                     "html_url": "https://github.com/octocat/Hello-World/pull/42"}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        java.util.concurrent.atomic.AtomicReference<String> lastBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/repos/octocat/Hello-World/pulls/42/comments", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {"id": 99, "body": "please rename this", "path": "src/Main.java", "line": 10, "side": "RIGHT",
                     "user": {"login": "octocat", "avatar_url": null}, "commit_id": "headsha123",
                     "html_url": "https://github.com/octocat/Hello-World/pull/42#discussion_r99"}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x"));

        GitHubPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        java.util.concurrent.atomic.AtomicReference<PrReviewView.Outcome<Void>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        FxHarness.run(() -> reviewBackend.postComment("src/Main.java", 10, ReviewComment.Side.NEW,
                "please rename this", result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertTrue(result.get().ok(), result.get().message());
        assertTrue(lastBody.get().contains("\"commit_id\":\"headsha123\""),
                "the comment must stay on the commit the reviewer was reading, not "
                        + "jump to the new head: " + lastBody.get());
    }
}
