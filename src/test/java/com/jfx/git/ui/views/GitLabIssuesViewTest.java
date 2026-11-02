package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.gitlab.GitLabUser;
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
 * Drives {@link GitLabIssuesView} against a real local HTTP server for the GitLab
 * side and a {@link Proxy}-based {@link GitBackend} stub for the git side - the same
 * two techniques {@code GitHubIssuesViewTest} uses, for the same reason.
 */
class GitLabIssuesViewTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        GitLabSession.disconnect();
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

    private GitLabIssuesView buildView(GitBackend backend, GitLabClient client, StatusBar statusBar) {
        return FxHarness.call(() -> {
            GitLabIssuesView view = new GitLabIssuesView(backend, statusBar, client);
            view.content().applyCss();
            view.content().layout();
            return view;
        });
    }

    private static final String OPEN_ISSUES_BODY = """
            [{"iid": 39706, "title": "Cleanup FF_USE_FASTZIP", "author": {"username": "vtak", "avatar_url": null},
              "state": "opened", "web_url": "https://gitlab.com/gitlab-org/gitlab-runner/-/work_items/39706",
              "labels": ["type::maintenance"]}]""";

    @Test
    void notConnectedShowsAPlaceholderAndNeverCallsTheClient() throws Exception {
        FxHarness.start();
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/issues", 200, OPEN_ISSUES_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git");

        GitLabIssuesView view = buildView(backend, client, new StatusBar());

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void aNonGitLabRemoteShowsAPlaceholderWithoutCallingTheClient() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/issues", 200, OPEN_ISSUES_BODY);
        GitBackend backend = stubBackend("https://github.com/octocat/Hello-World.git");

        GitLabIssuesView view = buildView(backend, client, new StatusBar());

        FxHarness.pulse(view.content());
        assertTrue(FxHarness.call(view::loadedEntries).isEmpty());
    }

    @Test
    void openIssuesArePopulatedWhenConnectedToAGitLabRemote() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/issues", 200, OPEN_ISSUES_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git");

        GitLabIssuesView view = buildView(backend, client, new StatusBar());

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        assertEquals(39706, FxHarness.call(view::loadedEntries).get(0).iid());
    }

    @Test
    void theOpenInBrowserButtonStaysDisabledUntilAnIssueIsSelected() throws Exception {
        FxHarness.start();
        GitLabSession.connect("token", new GitLabUser("octocat", null));
        GitLabClient client = fakeClient("/projects/gitlab-org/gitlab-runner/issues", 200, OPEN_ISSUES_BODY);
        GitBackend backend = stubBackend("https://gitlab.com/gitlab-org/gitlab-runner.git");

        GitLabIssuesView view = buildView(backend, client, new StatusBar());

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !FxHarness.call(view::loadedEntries).isEmpty());
        assertFalse(FxHarness.call(view::isOpenInBrowserEnabled), "nothing is selected yet");

        FxHarness.run(() -> view.selectForTest(view.loadedEntries().get(0)));
        assertTrue(FxHarness.call(view::isOpenInBrowserEnabled));
    }
}
