package com.jfx.git.ui.views;

import com.jfx.git.core.BuildStatus;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaPullRequest;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.gitea.GiteaUser;
import com.jfx.git.model.ReviewComment;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.dialogs.CreatePullRequestDialog;
import com.jfx.git.ui.dialogs.MergeStrategyDialog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link GiteaPullRequestsView} against a local HTTP server standing in for a
 * Gitea API and a {@link Proxy}-based {@link GitBackend} stub - the technique
 * {@code GitHubPullRequestsViewTest} uses, adapted. What is tested is the view's wiring
 * between the two; {@code GiteaClientTest} covers the HTTP client itself.
 *
 * <p>The origin is on codeberg.org, the Gitea integration's default server.
 */
class GiteaPullRequestsViewTest {

    private static final String ORIGIN = "https://codeberg.org/octocat/Hello-World.git";
    private static final String REPO = "/repos/octocat/Hello-World";

    private HttpServer server;

    /** Every view a test built, closed afterwards so none wakes up when a later test connects. */
    private final List<GiteaPullRequestsView> built = new CopyOnWriteArrayList<>();

    @AfterEach
    void tidyUp() {
        built.forEach(view -> FxHarness.run(() -> view.listingForTest().close()));
        if (server != null) {
            server.stop(0);
        }
        GiteaSession.disconnect();
    }

    /** A status bar that remembers its errors, so a test can wait for a failure to be reported. */
    private static final class RecordingStatusBar extends StatusBar {
        final List<String> errors = new CopyOnWriteArrayList<>();

        @Override
        public void error(String message) {
            errors.add(message);
            super.error(message);
        }
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
                    return harmless(method.getReturnType());
                });
    }

    private static Object harmless(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == List.class) return List.of();
        return null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GiteaClient fakeClient(String path, int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext(path, exchange -> respond(exchange, status, body));
        server.start();
        return new GiteaClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private GiteaPullRequestsView buildView(GitBackend backend, GiteaClient client, StatusBar statusBar,
                                               Runnable onCheckedOut) {
        return FxHarness.call(() -> {
            GiteaPullRequestsView view = new GiteaPullRequestsView(OperationRunner.of(backend, statusBar, onCheckedOut), null, client);
            view.content().applyCss();
            view.content().layout();
            built.add(view);
            return view;
        });
    }

    private static void connect() {
        GiteaSession.connect("token", new GiteaUser("octocat", null));
    }

    private static final String OPEN_PRS_BODY = """
            [{"number": 42, "title": "Fix flaky test", "user": {"login": "octocat", "avatar_url": null},
              "draft": false, "head": {"ref": "fix-flaky-test", "sha": "abc123"},
              "base": {"ref": "main", "sha": "base000"}, "state": "open",
              "html_url": "https://codeberg.org/octocat/Hello-World/pulls/42"}]""";

    private static final String TWO_OPEN_PRS_BODY = """
            [{"number": 42, "title": "Fix flaky test", "user": {"login": "octocat", "avatar_url": null},
              "draft": false, "head": {"ref": "fix-flaky-test", "sha": "abc123"},
              "base": {"ref": "main", "sha": "base000"},
              "html_url": "https://codeberg.org/octocat/Hello-World/pulls/42"},
             {"number": 7, "title": "Add dark mode toggle", "user": {"login": "hubot", "avatar_url": null},
              "draft": false, "head": {"ref": "dark-mode", "sha": "def456"},
              "base": {"ref": "main", "sha": "base000"},
              "html_url": "https://codeberg.org/octocat/Hello-World/pulls/7"}]""";

    private List<GiteaPullRequest> waitForRows(GiteaPullRequestsView view, int count) throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == count);
        return FxHarness.call(view::loadedEntries);
    }

    @Test
    void notConnectedShowsAPlaceholderAndNeverCallsTheClient() throws Exception {
        FxHarness.start();
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, OPEN_PRS_BODY);
        });
        server.start();
        GiteaClient client = new GiteaClient(URI.create("http://localhost:" + server.getAddress().getPort()));

        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(0, requests.get());
        assertFalse(FxHarness.call(view::isNewPullRequestEnabled));
    }

    @Test
    void aRemoteOnAnotherServerShowsAPlaceholderWithoutCallingTheClient() throws Exception {
        FxHarness.start();
        connect();
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, OPEN_PRS_BODY);
        });
        server.start();
        GiteaClient client = new GiteaClient(URI.create("http://localhost:" + server.getAddress().getPort()));

        GiteaPullRequestsView view = buildView(
                stubBackend("https://github.com/octocat/Hello-World.git", (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(0, requests.get());
        assertFalse(FxHarness.call(view::isNewPullRequestEnabled));
    }

    @Test
    void openPullRequestsArePopulatedWhenConnectedToAGiteaRemote() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);

        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });

        assertEquals(42, waitForRows(view, 1).get(0).number());
        assertTrue(FxHarness.call(view::isNewPullRequestEnabled));
    }

    @Test
    void theListAsksForOnePageAtATime() throws Exception {
        FxHarness.start();
        connect();
        AtomicReference<String> query = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(REPO + "/pulls", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, OPEN_PRS_BODY);
        });
        server.start();
        GiteaClient client = new GiteaClient(URI.create("http://localhost:" + server.getAddress().getPort()));

        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });
        waitForRows(view, 1);

        assertTrue(query.get().contains("state=open"), query.get());
        assertTrue(query.get().contains("page=1"), query.get());
        assertTrue(query.get().contains("limit="), query.get());
    }

    @Test
    void checkingOutFetchesThePullRefThroughTheBackendAndRefreshesOnSuccess() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);

        AtomicInteger checkoutCalls = new AtomicInteger();
        GitBackend backend = stubBackend(ORIGIN, (remote, number) -> {
            checkoutCalls.incrementAndGet();
            assertEquals("origin", remote);
            assertEquals(42, number);
            return GitResult.ok("Checked out pull request #42 as pr-42");
        });
        AtomicInteger refreshCalls = new AtomicInteger();
        GiteaPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);
        GiteaPullRequest pr = waitForRows(view, 1).get(0);

        FxHarness.run(() -> view.checkoutForTest(pr));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
        assertEquals(1, checkoutCalls.get());
    }

    @Test
    void aFailedCheckoutDoesNotRefresh() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        AtomicInteger checkoutCalls = new AtomicInteger();
        GitBackend backend = stubBackend(ORIGIN, (remote, number) -> {
            checkoutCalls.incrementAndGet();
            return GitResult.failed("simulated failure");
        });
        AtomicInteger refreshCalls = new AtomicInteger();
        GiteaPullRequestsView view = buildView(backend, client, new StatusBar(), refreshCalls::incrementAndGet);
        GiteaPullRequest pr = waitForRows(view, 1).get(0);

        FxHarness.run(() -> view.checkoutForTest(pr));
        // The button is held off while the checkout runs and comes back when it finishes.
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> checkoutCalls.get() == 1 && FxHarness.call(view::isCheckoutEnabled));

        assertEquals(0, refreshCalls.get());
    }

    @Test
    void filteringByTitleOrAuthorNarrowsTheListWithoutLosingRows() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, TWO_OPEN_PRS_BODY);
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });
        waitForRows(view, 2);

        FxHarness.run(() -> view.filterForTest("dark"));
        assertEquals(7, waitForRows(view, 1).get(0).number());

        FxHarness.run(() -> view.filterForTest("HUBOT"));
        assertEquals(7, waitForRows(view, 1).get(0).number());

        FxHarness.run(() -> view.filterForTest(""));
        waitForRows(view, 2);
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    @Test
    void mergeOffersGiteasFiveStyles() {
        FxHarness.start();
        assertEquals(GiteaClient.MERGE_STYLES,
                GiteaPullRequestsView.MERGE_STRATEGIES.stream().map(MergeStrategyDialog.Choice::value).toList());
    }

    /** Each style is a card saying what it leaves on the base branch, and the button names the one chosen. */
    @Test
    void eachMergeStyleSaysWhatItDoesAndTheButtonNamesIt() {
        FxHarness.start();
        GiteaPullRequestsView.MERGE_STRATEGIES.forEach(choice -> assertNotNull(choice.description(),
                choice.value() + " has no description on its card"));
        FxHarness.run(() -> {
            MergeStrategyDialog dialog = new MergeStrategyDialog("#7 Fix", GiteaPullRequestsView.MERGE_STRATEGIES);
            javafx.scene.control.Button merge = (javafx.scene.control.Button)
                    dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
            assertEquals("Create a merge commit", merge.getText(), "opens on the first style");
            List<javafx.scene.control.RadioButton> cards = dialog.getDialogPane().lookupAll(".jfx-option-radio")
                    .stream().map(javafx.scene.control.RadioButton.class::cast).toList();
            assertEquals(5, cards.size(), "all five styles on screen at once");
            cards.stream().filter(card -> card.getText().equals("Squash and merge")).findFirst()
                    .orElseThrow().setSelected(true);
            assertEquals("Squash and merge", merge.getText());
            assertEquals("squash", dialog.getResultConverter().call(javafx.scene.control.ButtonType.OK));
        });
    }

    @Test
    void mergingSendsTheStyleAndTheHeadTheRowShowedThenRefreshes() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        AtomicReference<String> mergeBody = new AtomicReference<>();
        server.createContext(REPO + "/pulls/42/merge", exchange -> {
            mergeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "");
        });
        AtomicInteger refreshCalls = new AtomicInteger();
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), refreshCalls::incrementAndGet);
        GiteaPullRequest pr = waitForRows(view, 1).get(0);

        FxHarness.run(() -> view.mergeForTest(pr, "rebase-merge"));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);
        assertTrue(mergeBody.get().contains("\"Do\":\"rebase-merge\""), mergeBody.get());
        assertTrue(mergeBody.get().contains("\"head_commit_id\":\"abc123\""),
                "a push since the list was read must be refused, not merged unseen: " + mergeBody.get());
    }

    @Test
    void aRefusedMergeIsReportedAndDoesNotRefresh() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        server.createContext(REPO + "/pulls/42/merge", exchange -> respond(exchange, 405, ""));
        AtomicInteger refreshCalls = new AtomicInteger();
        RecordingStatusBar statusBar = new RecordingStatusBar();
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                statusBar, refreshCalls::incrementAndGet);
        GiteaPullRequest pr = waitForRows(view, 1).get(0);

        FxHarness.run(() -> view.mergeForTest(pr, "merge"));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !statusBar.errors.isEmpty());
        assertTrue(statusBar.errors.get(0).contains("cannot be merged"), statusBar.errors.get(0));
        assertEquals(0, refreshCalls.get());
        assertTrue(FxHarness.call(view::isMergeEnabled), "selection is unchanged, so merge stays available");
    }

    @Test
    void aMergeRefusedBecauseTheHeadMovedSaysSo() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        server.createContext(REPO + "/pulls/42/merge", exchange -> respond(exchange, 409, """
                {"message": "head out of date"}"""));
        RecordingStatusBar statusBar = new RecordingStatusBar();
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                statusBar, () -> { });
        GiteaPullRequest pr = waitForRows(view, 1).get(0);

        FxHarness.run(() -> view.mergeForTest(pr, "squash"));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !statusBar.errors.isEmpty());
        assertTrue(statusBar.errors.get(0).contains("changed while it was being merged"), statusBar.errors.get(0));
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    private final AtomicInteger listRequests = new AtomicInteger();
    private final AtomicReference<String> createBody = new AtomicReference<>();

    /** List and create share one path, so one context dispatches on the method. */
    private GiteaClient fakeClientForCreate(int createStatus, String createResponse) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(REPO + "/pulls", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, createStatus, createResponse);
            } else {
                listRequests.incrementAndGet();
                respond(exchange, 200, OPEN_PRS_BODY);
            }
        });
        server.start();
        return new GiteaClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    @Test
    void creatingAPullRequestSendsTheDraftAndReloadsTheList() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClientForCreate(201, """
                {"number": 55, "title": "New feature", "user": {"login": "octocat"},
                 "head": {"ref": "new-feature", "sha": "abc"}, "base": {"ref": "main", "sha": "base000"},
                 "html_url": "https://codeberg.org/octocat/Hello-World/pulls/55"}""");
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequests.get() >= 1);

        FxHarness.run(() -> view.createForTest(
                new CreatePullRequestDialog.Draft("New feature", "adds a thing", "new-feature", "main")));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequests.get() >= 2);
        assertTrue(createBody.get().contains("\"head\":\"new-feature\""), createBody.get());
        assertTrue(createBody.get().contains("\"base\":\"main\""), createBody.get());
    }

    @Test
    void aFailedCreateIsReportedAndDoesNotReloadTheList() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClientForCreate(422, """
                {"message": "Invalid PullRequest: There are no changes between the head and the base"}""");
        RecordingStatusBar statusBar = new RecordingStatusBar();
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                statusBar, () -> { });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> listRequests.get() >= 1);
        int before = listRequests.get();

        FxHarness.run(() -> view.createForTest(
                new CreatePullRequestDialog.Draft("Nothing to merge", null, "main", "main")));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !statusBar.errors.isEmpty());
        assertTrue(statusBar.errors.get(0).contains("no changes"), statusBar.errors.get(0));
        assertEquals(before, listRequests.get(), "a failed create should not trigger a reload");
    }

    // ------------------------------------------------------------------
    // CI status
    // ------------------------------------------------------------------

    private void respondToStatus(String sha, String body) {
        server.createContext(REPO + "/commits/" + sha + "/status", exchange -> respond(exchange, 200, body));
    }

    @Test
    void aSuccessfulCombinedStatusColoursTheRow() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        respondToStatus("abc123", """
                {"state": "success", "total_count": 1, "statuses": [{"status": "success", "context": "ci"}]}""");
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(42)) == BuildStatus.PASSED);
    }

    @Test
    void aFailingStatusAmongPassingOnesColoursTheRowRed() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        respondToStatus("abc123", """
                {"state": "failure", "total_count": 2,
                 "statuses": [{"status": "success", "context": "lint"}, {"status": "failure", "context": "test"}]}""");
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(42)) == BuildStatus.FAILED);
    }

    @Test
    void aCommitWithNoStatusesIsReportedAsNone() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        respondToStatus("abc123", """
                {"state": "", "total_count": 0, "statuses": []}""");
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> FxHarness.call(() -> view.statusForTest(42)) == BuildStatus.NONE);
    }

    // ------------------------------------------------------------------
    // Review…
    // ------------------------------------------------------------------

    private static GiteaPullRequest testPr() {
        return new GiteaPullRequest(42, "Fix flaky test", new GiteaUser("octocat", null), false,
                new GiteaPullRequest.Head("fix-flaky-test", "headsha123", null),
                new GiteaPullRequest.Base("main", "basesha456"),
                "https://codeberg.org/octocat/Hello-World/pulls/42", "open", true, false);
    }

    /** Answers the fetch, merge-base and diff calls the review preparation makes. */
    private static GitBackend reviewStubBackend(GitResult fetchResult, List<String> changedFiles, String diffText,
                                                List<String> fetchedRefs, List<String> diffFrom) {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRemoteUrl": return ORIGIN;
                        case "fetchRemoteRef":
                            fetchedRefs.add((String) args[1]);
                            return fetchResult;
                        case "fetchAll": return GitResult.ok("fetched");
                        case "mergeBase": return "forkpoint789";
                        case "getFilesBetweenCommits":
                            diffFrom.add((String) args[0]);
                            return changedFiles;
                        case "getDiffBytesBetweenCommits": return diffText.getBytes(StandardCharsets.UTF_8);
                        default: return harmless(method.getReturnType());
                    }
                });
    }

    @Test
    void reviewButtonEnablesOnlyWhenAPullRequestIsSelected() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });

        assertFalse(FxHarness.call(view::isReviewEnabled), "nothing selected yet");
        GiteaPullRequest pr = waitForRows(view, 1).get(0);
        FxHarness.run(() -> view.checkoutForTest(pr));
        assertTrue(FxHarness.call(view::isReviewEnabled));
    }

    @Test
    void reviewFilesFetchThePullRefThenDiffEachFileFromTheForkPoint() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        List<String> fetched = new CopyOnWriteArrayList<>();
        List<String> from = new CopyOnWriteArrayList<>();
        GitBackend backend = reviewStubBackend(GitResult.ok("fetched"), List.of("src/Main.java"),
                "@@ -1 +1 @@\n-old\n+new\n", fetched, from);
        GiteaPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        var outcome = FxHarness.call(() -> view.reviewFilesForTest(testPr()));

        assertTrue(outcome.ok(), outcome.message());
        assertEquals(List.of("refs/pull/42/head"), fetched);
        assertEquals(List.of("forkpoint789"), from, "diffed from the merge-base, as Gitea's own files tab is");
        assertEquals("src/Main.java", outcome.value().get(0).path());
        assertTrue(new String(outcome.value().get(0).diffBytes(), StandardCharsets.UTF_8).contains("+new"));
    }

    @Test
    void reviewFilesFailCleanlyWhenThePullRefCannotBeFetched() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        GitBackend backend = reviewStubBackend(GitResult.failed("could not fetch refs/pull/42/head"), List.of(), "",
                new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        GiteaPullRequestsView view = buildView(backend, client, new StatusBar(), () -> { });

        var outcome = FxHarness.call(() -> view.reviewFilesForTest(testPr()));

        assertFalse(outcome.ok());
        assertEquals("could not fetch refs/pull/42/head", outcome.message());
    }

    @Test
    void reviewBackendReadsEachSubmittedReviewsComments() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        server.createContext(REPO + "/pulls/42/reviews", exchange -> respond(exchange, 200, """
                [{"id": 5, "state": "COMMENT", "comments_count": 2, "user": {"login": "octocat"}},
                 {"id": 6, "state": "PENDING", "comments_count": 1, "user": {"login": "octocat"}}]"""));
        server.createContext(REPO + "/pulls/42/reviews/5/comments", exchange -> respond(exchange, 200, """
                [{"id": 1, "body": "typo here", "path": "src/Main.java", "position": 10, "original_position": 0,
                  "user": {"login": "octocat"}, "commit_id": "headsha123"},
                 {"id": 2, "body": "was this needed?", "path": "src/Old.java", "position": 0, "original_position": 3,
                  "user": {"login": "hubot"}, "commit_id": "headsha123"}]"""));
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        AtomicReference<PrReviewView.Outcome<List<ReviewComment>>> result = new AtomicReference<>();
        FxHarness.run(() -> reviewBackend.fetchComments(result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertTrue(result.get().ok(), result.get().message());
        List<ReviewComment> comments = result.get().value();
        assertEquals(2, comments.size(), "the unsubmitted draft review's comments stay out");
        assertEquals(new ReviewComment("src/Main.java", 10, ReviewComment.Side.NEW, "octocat", "typo here"),
                comments.get(0));
        assertEquals(new ReviewComment("src/Old.java", 3, ReviewComment.Side.OLD, "hubot", "was this needed?"),
                comments.get(1));
        assertFalse(reviewBackend.supportsSuggestions(), "Gitea has nothing to apply a suggestion with");
    }

    @Test
    void reviewBackendPostsAOneCommentReviewOnTheReviewedCommit() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        AtomicReference<String> posted = new AtomicReference<>();
        server.createContext(REPO + "/pulls/42/reviews", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"id": 9, "state": "COMMENT", "commit_id": "headsha123", "comments_count": 1}""");
        });
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        AtomicReference<PrReviewView.Outcome<Void>> result = new AtomicReference<>();
        FxHarness.run(() -> reviewBackend.postComment("src/Old.java", 3, ReviewComment.Side.OLD,
                "please keep this", result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertTrue(result.get().ok(), result.get().message());
        assertTrue(posted.get().contains("\"event\":\"COMMENT\""), posted.get());
        assertTrue(posted.get().contains("\"commit_id\":\"headsha123\""), posted.get());
        assertTrue(posted.get().contains("\"old_position\":3"), "an OLD-side line is the base's: " + posted.get());
        assertFalse(posted.get().contains("new_position"), posted.get());
    }

    @Test
    void aRefusedCommentOnAPullRequestThatMovedSaysWhy() throws Exception {
        FxHarness.start();
        connect();
        GiteaClient client = fakeClient(REPO + "/pulls", 200, OPEN_PRS_BODY);
        server.createContext(REPO + "/pulls/42", exchange -> respond(exchange, 200, """
                {"number": 42, "title": "Fix flaky test", "head": {"ref": "fix-flaky-test", "sha": "muchnewersha"},
                 "base": {"ref": "main", "sha": "basesha456"}, "user": {"login": "octocat"}}"""));
        server.createContext(REPO + "/pulls/42/reviews", exchange -> respond(exchange, 422, """
                {"message": "line is not part of the diff"}"""));
        GiteaPullRequestsView view = buildView(stubBackend(ORIGIN, (r, n) -> GitResult.ok("x")), client,
                new StatusBar(), () -> { });
        PrReviewView.Backend reviewBackend = FxHarness.call(() -> view.reviewBackendForTest(testPr()));

        AtomicReference<PrReviewView.Outcome<Void>> result = new AtomicReference<>();
        FxHarness.run(() -> reviewBackend.postComment("src/Main.java", 10, ReviewComment.Side.NEW,
                "please rename this", result::set));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> result.get() != null);

        assertFalse(result.get().ok());
        assertTrue(result.get().message().contains("new commits since this review was opened"),
                result.get().message());
    }
}
