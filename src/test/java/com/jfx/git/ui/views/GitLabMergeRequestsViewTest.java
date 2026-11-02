package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.gitlab.GitLabUser;
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
 * Drives {@link GitLabMergeRequestsView} against a real local HTTP server for the
 * GitLab side and a {@link Proxy}-based {@link GitBackend} stub for the git side - the
 * same two techniques {@code GitHubPullRequestsViewTest} uses, for the same reason:
 * what this class tests is the view's own wiring, not either layer's real behaviour.
 */
class GitLabMergeRequestsViewTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        GitLabSession.disconnect();
    }

    /** {@code checkoutRemoteRef} takes three String args, so a plain {@code BiFunction} does not fit. */
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

    private GitLabClient fakeClient(String path, int status, String body) throws Exception {
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
        return new GitLabClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private GitLabMergeRequestsView buildView(
            GitBackend backend, GitLabClient client, StatusBar statusBar, Runnable onCheckedOut) {
        return FxHarness.call(() -> {
            GitLabMergeRequestsView view = new GitLabMergeRequestsView(OperationRunner.of(backend, statusBar, onCheckedOut), client);
            view.content().applyCss();
            view.content().layout();
            return view;
        });
    }

    private static final String OPEN_MRS_BODY = """
            [{"iid": 7231, "title": "go: Update module", "author": {"username": "renovate-bot", "avatar_url": null},
              "draft": false, "source_branch": "renovate/testify", "target_branch": "main", "sha": "abc123",
              "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/7231"}]""";

    private static final String TWO_OPEN_MRS_BODY = """
            [{"iid": 7231, "title": "go: Update module", "author": {"username": "renovate-bot", "avatar_url": null},
              "draft": false, "source_branch": "renovate/testify", "target_branch": "main",
              "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/7231"},
             {"iid": 88, "title": "Add dark mode toggle", "author": {"username": "octocat", "avatar_url": null},
              "draft": false, "source_branch": "dark-mode", "target_branch": "main",
              "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/88"}]""";

    @Test
    void notConnectedShowsAPlaceholderAndNeverCallsTheClient() throws Exception {
        FxHarness.start();
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void aNonGitLabRemoteShowsAPlaceholderWithoutCallingTheClient() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void openMergeRequestsArePopulatedWhenConnectedToAGitLabRemote() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(7231, FxHarness.call(view::loadedEntries).get(0).iid());
    }

    @Test
    void checkingOutRunsCheckoutRemoteRefWithTheMergeRequestsHeadRefAndRefreshesOnSuccess() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);

        AtomicInteger checkoutCalls = new AtomicInteger();
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (remote, remoteRef, local) -> {
            checkoutCalls.incrementAndGet();
            assertEquals("origin", remote);
            assertEquals("refs/merge-requests/7231/head", remoteRef);
            assertEquals("mr-7231", local);
            return GitResult.ok("Checked out mr-7231");
        });

        AtomicInteger refreshCalls = new AtomicInteger();
        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());

        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
        assertEquals(1, checkoutCalls.get());
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    private void respondToMerge(int status, String body) {
        server.createContext("/projects/gitlab-org/gitlab-runner/merge_requests/7231/merge", exchange -> {
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
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        respondToMerge(200, """
                {"merge_commit_sha": "0b4e7a0", "squash_commit_sha": null}""");
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        AtomicInteger refreshCalls = new AtomicInteger();
        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.mergeForTest(view.loadedEntries().get(0), true));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
    }

    @Test
    void aFailedMergeDoesNotRefresh() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        respondToMerge(405, """
                {"message": "405 Method Not Allowed"}""");
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        AtomicInteger refreshCalls = new AtomicInteger();
        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.mergeForTest(view.loadedEntries().get(0), false));
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(200); // let the async merge's callback land before asserting nothing happened.

        assertEquals(0, refreshCalls.get());
        assertTrue(FxHarness.call(view::isMergeEnabled), "selection is unchanged, so merge stays available");
    }

    /**
     * The confirmation is headed like the other providers' merge and says the verb; squashing
     * is a toggle that says what it does, and the answer is whether it was ticked.
     */
    @Test
    void theMergeConfirmationAsksWhetherToSquashWithAToggle() {
        FxHarness.run(() -> {
            javafx.scene.control.Dialog<Boolean> confirm = GitLabMergeRequestsView.squashDialog("!17 Round totals to cents");
            var header = com.jfx.git.ui.dialogs.JfxDialog.headerOf(confirm);
            assertEquals("Merge !17 Round totals to cents", header.titleText());
            assertFalse(header.subtitleText().isEmpty(), "and where it happens");
            assertEquals("Merge", confirm.getTitle());
            var merge = (javafx.scene.control.Button) confirm.getDialogPane()
                    .lookupButton(javafx.scene.control.ButtonType.OK);
            assertEquals("Merge", merge.getText());

            var squash = (javafx.scene.control.CheckBox) confirm.getDialogPane()
                    .lookup("#" + GitLabMergeRequestsView.SQUASH_ID);
            assertEquals("Squash commits", squash.getText());
            assertTrue(squash.getParent().getStyleClass().contains("jfx-toggle-row"),
                    "a toggle with what ticking it does under it");
            assertFalse(squash.isSelected(), "the merge request's commits land as they are unless asked");

            squash.setSelected(true);
            merge.fire();
            assertEquals(Boolean.TRUE, confirm.getResult());
        });
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    /**
     * List and create share one path ({@code GET}/{@code POST} to the same {@code
     * .../merge_requests}) - {@code HttpServer} routes by path only, so one context
     * has to dispatch on method itself, counting list requests so a test can tell
     * whether a reload happened without needing the list's own content to change.
     */
    private volatile int listRequestCount;

    private GitLabClient fakeClientForCreate(String listBody, int createStatus, String createBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext("/projects/gitlab-org/gitlab-runner/merge_requests", exchange -> {
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
        return new GitLabClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    @Test
    void creatingAMergeRequestSendsTheDraftAndReloadsTheList() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClientForCreate(OPEN_MRS_BODY, 201, """
                {"iid": 99, "title": "New feature", "author": {"username": "octocat", "avatar_url": null},
                 "draft": false, "source_branch": "new-feature", "target_branch": "main",
                 "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/99"}""");
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));
        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 1);

        FxHarness.run(() -> view.createForTest(
                new CreatePullRequestDialog.Draft("New feature", "adds a thing", "new-feature", "main")));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 2);
    }

    @Test
    void aFailedCreateDoesNotReloadTheList() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClientForCreate(OPEN_MRS_BODY, 409, """
                {"message": ["Source and target branches are the same"]}""");
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));
        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequestCount >= 1);
        int countBeforeCreate = listRequestCount;

        FxHarness.run(() -> view.createForTest(
                new CreatePullRequestDialog.Draft("Nothing to merge", null, "main", "main")));
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(200); // let the async create's callback land before asserting nothing happened.

        assertEquals(countBeforeCreate, listRequestCount, "a failed create should not trigger a reload");
    }

    @Test
    void filteringByTitleNarrowsTheList() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, TWO_OPEN_MRS_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == 2);

        FxHarness.run(() -> view.filterForTest("dark"));
        WaitForAsyncUtils.waitForFxEvents();

        List<com.jfx.git.core.gitlab.GitLabMergeRequest> shown = FxHarness.call(view::loadedEntries);
        assertEquals(1, shown.size());
        assertEquals(88, shown.get(0).iid());
    }

    @Test
    void filteringByAuthorNarrowsTheList() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, TWO_OPEN_MRS_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == 2);

        FxHarness.run(() -> view.filterForTest("RENOVATE"));
        WaitForAsyncUtils.waitForFxEvents();

        List<com.jfx.git.core.gitlab.GitLabMergeRequest> shown = FxHarness.call(view::loadedEntries);
        assertEquals(1, shown.size());
        assertEquals(7231, shown.get(0).iid());
    }

    @Test
    void aFilterMatchingNothingEmptiesTheListWithoutLosingTheRealData() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, TWO_OPEN_MRS_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
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

    private void respondToStatuses(String sha, int status, String body) {
        server.createContext("/projects/gitlab-org/gitlab-runner/repository/commits/" + sha + "/statuses", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void aPassingPipelineColoursTheRowsStatus() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        respondToStatuses("abc123", 200, """
                [{"status": "success"}]""");
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(7231)) == com.jfx.git.core.BuildStatus.PASSED);
    }

    @Test
    void aCommitWithNoStatusesIsReportedAsNone() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        respondToStatuses("abc123", 200, "[]");
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(7231)) == com.jfx.git.core.BuildStatus.NONE);
    }

    // ------------------------------------------------------------------
    // Review…
    // ------------------------------------------------------------------

    private static com.jfx.git.core.gitlab.GitLabMergeRequest testMr() {
        return new com.jfx.git.core.gitlab.GitLabMergeRequest(7231, "go: Update module",
                new GitLabUser("renovate-bot", null), false, "renovate/testify", "main", "headsha123",
                new com.jfx.git.core.gitlab.GitLabMergeRequest.DiffRefs("basesha", "startsha", "headsha123"),
                "https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/7231");
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

    private void respondToMergeRequestDetail(int status, String body) {
        server.createContext("/projects/gitlab-org/gitlab-runner/merge_requests/7231", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static final String MR_DETAIL_WITH_DIFF_REFS = """
            {"iid": 7231, "title": "go: Update module", "author": {"username": "renovate-bot", "avatar_url": null},
             "draft": false, "source_branch": "renovate/testify", "target_branch": "main", "sha": "headsha123",
             "diff_refs": {"base_sha": "basesha", "start_sha": "startsha", "head_sha": "headsha123"},
             "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/7231"}""";

    @Test
    void reviewButtonEnablesOnlyWhenAMergeRequestIsSelected() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        assertFalse(FxHarness.call(view::isReviewEnabled), "nothing selected yet");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        FxHarness.run(() -> view.checkoutForTest(view.loadedEntries().get(0)));
        assertTrue(FxHarness.call(view::isReviewEnabled));
    }

    @Test
    void reviewFilesForTestRefetchesTheDetailForDiffRefsThenDiffsEachChangedFile() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        respondToMergeRequestDetail(200, MR_DETAIL_WITH_DIFF_REFS);
        GitBackend backend = reviewStubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git",
                GitResult.ok("fetched"), List.of("src/Main.java"), "@@ -1 +1 @@\n-old\n+new\n");

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        var outcome = FxHarness.call(() -> view.reviewFilesForTest(testMr()));

        assertTrue(outcome.ok(), outcome.message());
        assertEquals(1, outcome.value().size());
        assertEquals("src/Main.java", outcome.value().get(0).path());
    }

    @Test
    void reviewFilesForTestFailsCleanlyWhenGitLabReportsNoDiffRefs() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        respondToMergeRequestDetail(200, """
                {"iid": 7231, "title": "go: Update module", "author": {"username": "renovate-bot", "avatar_url": null},
                 "draft": false, "source_branch": "renovate/testify", "target_branch": "main", "sha": "headsha123",
                 "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/7231"}""");
        GitBackend backend = reviewStubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git",
                GitResult.ok("fetched"), List.of(), "");

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        var outcome = FxHarness.call(() -> view.reviewFilesForTest(testMr()));

        assertFalse(outcome.ok());
    }

    @Test
    void reviewBackendFetchesAndConvertsGitLabsDiffDiscussions() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        server.createContext("/projects/gitlab-org/gitlab-runner/merge_requests/7231/discussions", exchange -> {
            byte[] bytes = """
                    [{"id": "abc", "notes": [
                        {"id": 1, "body": "typo here", "author": {"username": "octocat", "avatar_url": null},
                         "position": {"new_path": "src/Main.java", "old_path": "src/Main.java",
                                      "new_line": 10, "old_line": null}}
                    ]}]"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testMr()));

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
    void reviewBackendPostsAllThreeDiffRefShas() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/merge_requests", 200, OPEN_MRS_BODY);
        java.util.concurrent.atomic.AtomicReference<String> lastBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/projects/gitlab-org/gitlab-runner/merge_requests/7231/discussions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {"id": "def", "notes": [
                        {"id": 2, "body": "please rename this", "author": {"username": "octocat", "avatar_url": null},
                         "position": {"new_path": "src/Main.java", "old_path": "src/Main.java",
                                      "new_line": 10, "old_line": null}}
                    ]}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git", (r, ref, n) -> GitResult.ok("x"));

        GitLabMergeRequestsView view = buildView(backend, client, new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testMr()));

        java.util.concurrent.atomic.AtomicReference<PrReviewView.Outcome<Void>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        FxHarness.run(() -> reviewBackend.postComment("src/Main.java", 10, com.jfx.git.model.ReviewComment.Side.NEW,
                "please rename this", result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertTrue(result.get().ok(), result.get().message());
        assertTrue(lastBody.get().contains("\"base_sha\":\"basesha\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"start_sha\":\"startsha\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"head_sha\":\"headsha123\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"new_line\":10"), lastBody.get());
    }
}
