package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link GitHubIssuesView} against a real local HTTP server for the GitHub
 * side and a {@link Proxy}-based {@link GitBackend} stub for the git side - the same
 * two techniques {@code GitHubPullRequestsViewTest} uses, for the same reason: what
 * this class tests is the view's own wiring, not either layer's real behaviour,
 * which {@code GitHubClientTest} (HTTP/parsing, including the PR-filtering) already
 * covers.
 *
 * <p>"Open in browser" is not exercised end-to-end - launching a real browser in a
 * test would be both slow and environment-dependent - so these tests stop at
 * confirming the view resolves a repository, loads its issues, and reaches the
 * point of firing the button; {@code UrlOpener} has no dedicated test for the same
 * reason {@code FileOpener} does not.
 */
class GitHubIssuesViewTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
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

    private GitHubIssuesView buildView(GitBackend backend, GitHubClient client, StatusBar statusBar) {
        return FxHarness.call(() -> {
            GitHubIssuesView view = new GitHubIssuesView(backend, statusBar, client);
            view.content().applyCss();
            view.content().layout();
            return view;
        });
    }

    private static final String OPEN_ISSUES_BODY = """
            [{"number": 10985, "title": "This should fail", "user": {"login": "octocat", "avatar_url": null},
              "state": "open", "html_url": "https://github.com/octocat/Hello-World/issues/10985",
              "labels": [{"name": "bug"}]}]""";

    @Test
    void notConnectedShowsAPlaceholderAndNeverCallsTheClient() throws Exception {
        FxHarness.start();
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/issues", 200, OPEN_ISSUES_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git");

        GitHubIssuesView view = buildView(backend, client, new StatusBar());

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void aNonGitHubRemoteShowsAPlaceholderWithoutCallingTheClient() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/issues", 200, OPEN_ISSUES_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/octocat/Hello-World.git");

        GitHubIssuesView view = buildView(backend, client, new StatusBar());

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void openIssuesArePopulatedWhenConnectedToAGitHubRemote() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/issues", 200, OPEN_ISSUES_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git");

        GitHubIssuesView view = buildView(backend, client, new StatusBar());

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(10985, FxHarness.call(view::loadedEntries).get(0).number());
    }

    @Test
    void theOpenInBrowserButtonStaysDisabledUntilAnIssueIsSelected() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/octocat/Hello-World/issues", 200, OPEN_ISSUES_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git");

        GitHubIssuesView view = buildView(backend, client, new StatusBar());

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        assertFalse(FxHarness.call(view::isOpenInBrowserEnabled), "nothing is selected yet");

        FxHarness.run(() -> view.selectForTest(view.loadedEntries().get(0)));
        assertTrue(FxHarness.call(view::isOpenInBrowserEnabled));
    }
}
