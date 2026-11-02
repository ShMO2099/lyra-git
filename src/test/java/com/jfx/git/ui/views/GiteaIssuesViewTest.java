package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaIssue;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.gitea.GiteaUser;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.StatusBar;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link GiteaIssuesView} against a local HTTP server standing in for a Gitea API
 * and a {@link Proxy}-based {@link GitBackend} stub - {@code GitHubIssuesViewTest}'s
 * technique, adapted. "Open in browser" is not fired: launching a real browser in a test
 * would be slow and depend on the machine.
 */
class GiteaIssuesViewTest {

    private static final String ORIGIN = "https://codeberg.org/octocat/Hello-World.git";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> query = new AtomicReference<>();

    /** Every view a test built, closed afterwards so none wakes up when a later test connects. */
    private final List<GiteaIssuesView> built = new CopyOnWriteArrayList<>();

    @AfterEach
    void tidyUp() {
        built.forEach(view -> FxHarness.run(() -> view.listingForTest().close()));
        if (server != null) {
            server.stop(0);
        }
        GiteaSession.disconnect();
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
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    private GiteaClient fakeClient(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/octocat/Hello-World/issues", exchange -> {
            requests.incrementAndGet();
            query.set(exchange.getRequestURI().getQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new GiteaClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private GiteaIssuesView buildView(GitBackend backend, GiteaClient client) {
        return FxHarness.call(() -> {
            GiteaIssuesView view = new GiteaIssuesView(backend, null, new StatusBar(), client);
            view.content().applyCss();
            view.content().layout();
            built.add(view);
            return view;
        });
    }

    private static final String OPEN_ISSUES_BODY = """
            [{"number": 12, "title": "Crash on start", "user": {"login": "octocat", "avatar_url": null},
              "state": "open", "html_url": "https://codeberg.org/octocat/Hello-World/issues/12",
              "labels": [{"name": "bug"}]},
             {"number": 13, "title": "A pull request a server listed anyway", "user": {"login": "hubot"},
              "state": "open", "html_url": "https://codeberg.org/octocat/Hello-World/pulls/13",
              "labels": [], "pull_request": {"merged": false}},
             {"number": 14, "title": "Docs typo", "user": {"login": "hubot"},
              "state": "open", "html_url": "https://codeberg.org/octocat/Hello-World/issues/14",
              "labels": [{"name": "documentation"}]}]""";

    private List<GiteaIssue> waitForRows(GiteaIssuesView view, int count) throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(view::loadedEntries).size() == count);
        return FxHarness.call(view::loadedEntries);
    }

    @Test
    void notConnectedShowsAPlaceholderAndNeverCallsTheClient() throws Exception {
        FxHarness.start();
        GiteaIssuesView view = buildView(stubBackend(ORIGIN), fakeClient(OPEN_ISSUES_BODY));

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(0, requests.get());
    }

    @Test
    void aRemoteOnAnotherServerShowsAPlaceholderWithoutCallingTheClient() throws Exception {
        FxHarness.start();
        GiteaSession.connect("token", new GiteaUser("octocat", null));
        GiteaIssuesView view = buildView(stubBackend("https://gitlab.com/octocat/Hello-World.git"),
                fakeClient(OPEN_ISSUES_BODY));

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(0, requests.get());
    }

    @Test
    void openIssuesArePopulatedWithoutPullRequests() throws Exception {
        FxHarness.start();
        GiteaSession.connect("token", new GiteaUser("octocat", null));
        GiteaIssuesView view = buildView(stubBackend(ORIGIN), fakeClient(OPEN_ISSUES_BODY));

        List<GiteaIssue> shown = waitForRows(view, 2);
        assertEquals(List.of(12, 14), shown.stream().map(GiteaIssue::number).toList());
        assertTrue(query.get().contains("type=issues"), "the server is asked to leave pull requests out: " + query.get());
    }

    @Test
    void filteringByLabelNarrowsTheList() throws Exception {
        FxHarness.start();
        GiteaSession.connect("token", new GiteaUser("octocat", null));
        GiteaIssuesView view = buildView(stubBackend(ORIGIN), fakeClient(OPEN_ISSUES_BODY));
        waitForRows(view, 2);

        FxHarness.run(() -> view.filterForTest("DOCUMENT"));
        assertEquals(14, waitForRows(view, 1).get(0).number());
    }

    @Test
    void theOpenInBrowserButtonStaysDisabledUntilAnIssueIsSelected() throws Exception {
        FxHarness.start();
        GiteaSession.connect("token", new GiteaUser("octocat", null));
        GiteaIssuesView view = buildView(stubBackend(ORIGIN), fakeClient(OPEN_ISSUES_BODY));
        GiteaIssue first = waitForRows(view, 2).get(0);

        assertFalse(FxHarness.call(view::isOpenInBrowserEnabled), "nothing is selected yet");
        FxHarness.run(() -> view.selectForTest(first));
        assertTrue(FxHarness.call(view::isOpenInBrowserEnabled));
    }
}
