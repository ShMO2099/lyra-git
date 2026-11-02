package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubPullRequest;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared list behind the pull request, merge request and issue dialogs, driven
 * through {@link GitHubPullRequestsView} against a local server: a page at a time, CI
 * status only for rows on screen, nothing left running after the view closes, and a
 * list that loads by itself once the account connects. See {@link ProviderListing}.
 */
class ProviderListingTest {

    private HttpServer server;
    private ExecutorService serverThreads;
    private final List<String> listRequests = new CopyOnWriteArrayList<>();
    private final List<String> statusRequests = new CopyOnWriteArrayList<>();
    /** Holds every check-runs request until released, when a test wants work in flight. */
    private volatile CountDownLatch holdStatuses;
    private GitHubPullRequestsView view;

    @AfterEach
    void tidyUp() {
        CountDownLatch held = holdStatuses;
        if (held != null) held.countDown();
        if (view != null) FxHarness.run(view::closed);
        FxHarness.closeShownStages();
        if (server != null) server.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
        GitHubSession.disconnect();
    }

    private static GitBackend stubBackend(String originUrl) {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getRemoteUrl".equals(method.getName())) return originUrl;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    private static String pr(int number) {
        return "{\"number\": " + number + ", \"title\": \"PR " + number + "\", \"user\": {\"login\": \"octocat\"},"
                + " \"draft\": false, \"head\": {\"ref\": \"b" + number + "\", \"sha\": \"s" + number + "\"},"
                + " \"base\": {\"ref\": \"main\"}}";
    }

    private static String prs(int fromInclusive, int downToInclusive) {
        return IntStream.iterate(fromInclusive, n -> n >= downToInclusive, n -> n - 1)
                .mapToObj(ProviderListingTest::pr).collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * Page 1 is PRs 100..51 with a next link; page 2 is 52..40 - two of them already on
     * page 1, as happens when a pull request opens between the two requests.
     */
    private GitHubClient fakeGitHub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        String base = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/repos/octocat/Hello-World/pulls", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            listRequests.add(query);
            if (query.contains("page=2")) {
                reply(exchange, prs(52, 40), null);
            } else {
                reply(exchange, prs(100, 51),
                        "<" + base + "/repos/octocat/Hello-World/pulls?page=2>; rel=\"next\"");
            }
        });
        server.createContext("/repos/octocat/Hello-World/commits/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/check-runs")) {
                statusRequests.add(path);
                CountDownLatch held = holdStatuses;
                if (held != null) {
                    try {
                        held.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                reply(exchange, "{\"total_count\": 0, \"check_runs\": []}", null);
            } else {
                reply(exchange, "{\"state\": \"pending\", \"total_count\": 0}", null);
            }
        });
        server.start();
        return new GitHubClient(URI.create(base));
    }

    private static void reply(HttpExchange exchange, String body, String link) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (link != null) exchange.getResponseHeaders().add("Link", link);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GitHubPullRequestsView build(GitHubClient client) {
        view = FxHarness.call(() -> new GitHubPullRequestsView(
                OperationRunner.of(stubBackend("https://github.com/octocat/Hello-World.git"), new StatusBar(), () -> { }),
                "https://github.com/octocat/Hello-World.git", client));
        return view;
    }

    private static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, condition);
    }

    @Test
    void theNewestPageShowsAtOnceAndLoadMoreAppendsTheNext() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        build(fakeGitHub());

        waitFor(() -> FxHarness.call(view::loadedEntries).size() == 50);
        assertEquals(1, listRequests.size(), "one request before anything shows, not every page");
        assertTrue(FxHarness.call(() -> view.listingForTest().hasMore()));
        assertEquals("Newest 50 open pull requests", FxHarness.call(() -> view.listingForTest().noteText()));

        GitHubPullRequest picked = FxHarness.call(view::loadedEntries).get(5);
        FxHarness.run(() -> view.selectForTest(picked));
        FxHarness.run(() -> view.listingForTest().loadMore());

        // 52..40 is thirteen, two of which page 1 already had.
        waitFor(() -> FxHarness.call(view::loadedEntries).size() == 61);
        assertEquals(picked, FxHarness.call(view::selectedForTest), "the picked row stays picked");
        List<Integer> numbers = FxHarness.call(view::loadedEntries).stream().map(GitHubPullRequest::number).toList();
        assertEquals(numbers.size(), numbers.stream().distinct().count(), "nothing listed twice");
        assertFalse(FxHarness.call(() -> view.listingForTest().hasMore()));
        assertEquals(2, listRequests.size());
    }

    @Test
    void theFilterSaysItSearchesOnlyWhatIsLoaded() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        build(fakeGitHub());
        waitFor(() -> FxHarness.call(view::loadedEntries).size() == 50);

        FxHarness.run(() -> view.filterForTest("PR 7"));

        assertEquals(List.of(79, 78, 77, 76, 75, 74, 73, 72, 71, 70),
                FxHarness.call(view::loadedEntries).stream().map(GitHubPullRequest::number).toList());
        assertEquals("Filtering 50 loaded - Load more to search further",
                FxHarness.call(() -> view.listingForTest().noteText()));
    }

    @Test
    void statusIsAskedOnlyForTheRowsOnScreen() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        build(fakeGitHub());
        FxHarness.run(() -> FxHarness.show(view.content(), 900, 600));

        waitFor(() -> FxHarness.call(view::loadedEntries).size() == 50);
        waitFor(() -> !statusRequests.isEmpty());
        Thread.sleep(500); // let any (wrongly) queued status calls go out before counting.

        int asked = statusRequests.size();
        assertTrue(asked < 50, "only the rows on screen, not all fifty: " + asked);
        assertEquals(asked, statusRequests.stream().distinct().count(), "each asked once");
    }

    @Test
    void closingTheViewStopsWorkThatHasNotStarted() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        holdStatuses = new CountDownLatch(1);
        build(fakeGitHub());
        FxHarness.run(() -> FxHarness.show(view.content(), 900, 600));
        waitFor(() -> FxHarness.call(view::loadedEntries).size() == 50);
        waitFor(() -> statusRequests.size() >= 3);

        FxHarness.run(view::closed);
        int atClose = statusRequests.size();
        assertTrue(atClose <= ProviderListing.STATUS_IN_FLIGHT, "at most a handful in flight: " + atClose);
        holdStatuses.countDown();
        Thread.sleep(500);

        assertEquals(atClose, statusRequests.size(), "nothing queued started after the view closed");
        assertTrue(FxHarness.call(() -> view.listingForTest().isClosed()));
    }

    @Test
    void aListOpenedBeforeConnectingLoadsOnceTheAccountConnects() throws Exception {
        FxHarness.start();
        build(fakeGitHub());
        FxHarness.pulse(view.content());
        assertTrue(listRequests.isEmpty(), "nothing to ask without a token");

        GitHubSession.connect("token", new GitHubUser("octocat", null));

        waitFor(() -> FxHarness.call(view::loadedEntries).size() == 50);
    }

    @Test
    void whileARememberedTokenIsRestoredTheListSaysReconnectingThenLoads() throws Exception {
        FxHarness.start();
        java.lang.reflect.Method restoring =
                com.jfx.git.core.IntegrationEvents.class.getDeclaredMethod("setRestoring", boolean.class);
        restoring.setAccessible(true);
        FxHarness.run(() -> invoke(restoring, true));
        try {
            build(fakeGitHub());
            assertEquals("Reconnecting to GitHub…", FxHarness.call(() -> view.listingForTest().placeholderText()));

            GitHubSession.connect("token", new GitHubUser("octocat", null));
            FxHarness.run(() -> invoke(restoring, false));

            waitFor(() -> FxHarness.call(view::loadedEntries).size() == 50);
        } finally {
            FxHarness.run(() -> invoke(restoring, false));
        }
    }

    private static void invoke(java.lang.reflect.Method setRestoring, boolean value) {
        try {
            setRestoring.invoke(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theOriginIsReadOffTheFxThreadWhenTheCallerDidNotHaveIt() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeGitHub();
        java.util.concurrent.atomic.AtomicBoolean readOnFx = new java.util.concurrent.atomic.AtomicBoolean();
        GitBackend backend = (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(), new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getRemoteUrl".equals(method.getName())) {
                        if (javafx.application.Platform.isFxApplicationThread()) readOnFx.set(true);
                        return "https://github.com/octocat/Hello-World.git";
                    }
                    return method.getReturnType() == List.class ? List.of() : null;
                });

        view = FxHarness.call(() -> new GitHubPullRequestsView(OperationRunner.of(backend, new StatusBar(), () -> { }), client));

        waitFor(() -> FxHarness.call(view::loadedEntries).size() == 50);
        assertFalse(readOnFx.get(), "git work belongs on a lane, not the FX thread");
    }
}
