package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.ui.FxHarness;

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
 * Drives {@link AddRemoteDialog} against a real local HTTP server for the GitHub fork
 * calls (the technique {@code GitHubClientTest} and {@code GitHubPullRequestsViewTest}
 * use) and a {@link Proxy}-based {@link GitBackend} stub for the git side. What this
 * class proves is the dialog's own wiring - the manual add path, and the
 * detect-or-create fork path feeding into {@code addRemote} - not either dependency's
 * own behaviour, which {@code GitHubClientTest} and {@code GitServiceTest} already cover.
 */
class AddRemoteDialogTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        GitHubSession.disconnect();
    }

    /** Answers {@code getRemoteUrl("origin")} and {@code addRemote} explicitly; everything else harmlessly. */
    private static GitBackend stubBackend(String originUrl, BiFunction<String, String, GitResult> onAddRemote) {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getRemoteUrl".equals(method.getName())) return originUrl;
                    if ("addRemote".equals(method.getName())) {
                        return onAddRemote.apply((String) args[0], (String) args[1]);
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

    /** A GET-only fake server answering one path with one body - the "a fork already exists" shape. */
    private GitHubClient fakeClient(String path, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new GitHubClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    /**
     * A fake server whose one path answers differently by HTTP method - {@code listForks}
     * and {@code createFork} both hit {@code /repos/{owner}/{repo}/forks}, distinguished
     * only by GET vs POST, which {@code com.sun.net.httpserver} cannot route to two
     * separate registered contexts.
     */
    private GitHubClient fakeClientByMethod(String path, String getBody, String postBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext(path, exchange -> {
            boolean isGet = "GET".equals(exchange.getRequestMethod());
            String body = isGet ? getBody : postBody;
            int status = isGet ? 200 : 202;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new GitHubClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private AddRemoteDialog buildDialog(GitBackend backend, Runnable onAdded, GitHubClient client) throws Exception {
        AddRemoteDialog built = FxHarness.call(() -> {
            AddRemoteDialog dialog = new AddRemoteDialog(backend, onAdded, client);
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            return dialog;
        });
        // The origin is read on the repository lane; the fork offer is decided when it lands.
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(built::isOriginReadForTest));
        return built;
    }

    @Test
    void manuallyAddingARemoteCallsTheBackendAndRefreshes() throws Exception {
        FxHarness.start();
        AtomicInteger addCalls = new AtomicInteger();
        GitBackend backend = stubBackend("https://gitlab.com/octocat/Hello-World.git", (name, url) -> {
            addCalls.incrementAndGet();
            assertEquals("upstream", name);
            assertEquals("https://example.com/x.git", url);
            return GitResult.ok("Added remote upstream");
        });
        AtomicInteger refreshCalls = new AtomicInteger();
        AddRemoteDialog dialog = buildDialog(backend, refreshCalls::incrementAndGet, new GitHubClient());

        FxHarness.run(() -> dialog.addForTest("upstream", "https://example.com/x.git"));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);

        assertEquals(1, addCalls.get());
    }

    @Test
    void aFailedManualAddDoesNotRefreshAndShowsTheMessage() throws Exception {
        FxHarness.start();
        GitBackend backend = stubBackend("https://gitlab.com/octocat/Hello-World.git",
                (name, url) -> GitResult.failed("remote upstream already exists"));
        AtomicInteger refreshCalls = new AtomicInteger();
        AddRemoteDialog dialog = buildDialog(backend, refreshCalls::incrementAndGet, new GitHubClient());

        FxHarness.run(() -> dialog.addForTest("upstream", "https://example.com/x.git"));
        WaitForAsyncUtils.waitForFxEvents();
        Thread.sleep(200);

        assertEquals(0, refreshCalls.get());
        assertEquals("remote upstream already exists", FxHarness.call(dialog::statusForTest));
    }

    @Test
    void forkButtonIsNotOfferedWithoutAGitHubOriginOrConnection() throws Exception {
        FxHarness.start();
        GitBackend backend = stubBackend("https://gitlab.com/octocat/Hello-World.git", (n, u) -> GitResult.ok());

        AddRemoteDialog dialog = buildDialog(backend, () -> { }, new GitHubClient());

        assertFalse(FxHarness.call(dialog::isForkOffered));
    }

    @Test
    void forkButtonIsOfferedForAConnectedGitHubOrigin() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitBackend backend = stubBackend("https://github.com/upstream-owner/Hello-World.git", (n, u) -> GitResult.ok());

        AddRemoteDialog dialog = buildDialog(backend, () -> { }, new GitHubClient());

        assertTrue(FxHarness.call(dialog::isForkOffered));
    }

    @Test
    void forkingWhenNoForkExistsYetCreatesOneAndAddsItAsARemote() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClientByMethod("/repos/upstream-owner/Hello-World/forks", "[]", """
                {"full_name": "octocat/Hello-World",
                 "clone_url": "https://github.com/octocat/Hello-World.git",
                 "ssh_url": "git@github.com:octocat/Hello-World.git",
                 "description": null, "private": false}""");

        AtomicInteger addCalls = new AtomicInteger();
        GitBackend backend = stubBackend("https://github.com/upstream-owner/Hello-World.git", (name, url) -> {
            addCalls.incrementAndGet();
            assertEquals("octocat", name);
            assertEquals("https://github.com/octocat/Hello-World.git", url);
            return GitResult.ok("Added remote octocat");
        });
        AtomicInteger refreshCalls = new AtomicInteger();
        AddRemoteDialog dialog = buildDialog(backend, refreshCalls::incrementAndGet, client);

        FxHarness.run(dialog::forkForTest);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);

        assertEquals(1, addCalls.get());
    }

    @Test
    void forkingWhenAForkAlreadyExistsReusesItInsteadOfCreatingAnother() throws Exception {
        FxHarness.start();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubClient client = fakeClient("/repos/upstream-owner/Hello-World/forks", """
                [{"full_name": "octocat/Hello-World",
                  "clone_url": "https://github.com/octocat/Hello-World.git",
                  "ssh_url": "git@github.com:octocat/Hello-World.git",
                  "description": null, "private": false}]""");

        AtomicInteger addCalls = new AtomicInteger();
        GitBackend backend = stubBackend("https://github.com/upstream-owner/Hello-World.git", (name, url) -> {
            addCalls.incrementAndGet();
            assertEquals("https://github.com/octocat/Hello-World.git", url);
            return GitResult.ok("Added remote octocat");
        });
        AtomicInteger refreshCalls = new AtomicInteger();
        AddRemoteDialog dialog = buildDialog(backend, refreshCalls::incrementAndGet, client);

        FxHarness.run(dialog::forkForTest);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);

        assertEquals(1, addCalls.get(), "a fork the account already owns must not be created again");
    }

    // ------------------------------------------------------------------
    // Gitea: the same flow through Gitea's fork calls
    // ------------------------------------------------------------------

    private com.jfx.git.core.gitea.GiteaClient fakeGiteaByMethod(String path, String getBody, String postBody,
                                                                  AtomicInteger posts) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            boolean isGet = "GET".equals(exchange.getRequestMethod());
            if (!isGet) posts.incrementAndGet();
            byte[] bytes = (isGet ? getBody : postBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(isGet ? 200 : 202, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new com.jfx.git.core.gitea.GiteaClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private AddRemoteDialog buildGiteaDialog(GitBackend backend, Runnable onAdded,
                                             com.jfx.git.core.gitea.GiteaClient gitea) throws Exception {
        AddRemoteDialog built = FxHarness.call(() -> {
            AddRemoteDialog dialog = new AddRemoteDialog(backend, onAdded, new GitHubClient(), gitea);
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            return dialog;
        });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> FxHarness.call(built::isOriginReadForTest));
        return built;
    }

    @Test
    void forkButtonIsOfferedForAConnectedGiteaOriginOnly() throws Exception {
        FxHarness.start();
        GitBackend backend = stubBackend("https://codeberg.org/upstream-owner/Hello-World.git", (n, u) -> GitResult.ok());

        assertFalse(FxHarness.call(buildDialog(backend, () -> { }, new GitHubClient())::isForkOffered),
                "nowhere to put a fork without a connected account");

        com.jfx.git.core.gitea.GiteaSession.connect("token", new com.jfx.git.core.gitea.GiteaUser("octocat", null));
        try {
            assertTrue(FxHarness.call(buildDialog(backend, () -> { }, new GitHubClient())::isForkOffered));
        } finally {
            com.jfx.git.core.gitea.GiteaSession.disconnect();
        }
    }

    @Test
    void forkingOnGiteaCreatesTheForkAndAddsItAsARemote() throws Exception {
        FxHarness.start();
        com.jfx.git.core.gitea.GiteaSession.connect("token", new com.jfx.git.core.gitea.GiteaUser("octocat", null));
        try {
            AtomicInteger posts = new AtomicInteger();
            var gitea = fakeGiteaByMethod("/repos/upstream-owner/Hello-World/forks",
                    """
                    [{"full_name": "someone-else/Hello-World",
                      "clone_url": "https://codeberg.org/someone-else/Hello-World.git"}]""",
                    """
                    {"full_name": "octocat/Hello-World", "clone_url": "https://codeberg.org/octocat/Hello-World.git",
                     "ssh_url": "git@codeberg.org:octocat/Hello-World.git", "private": false, "fork": true}""",
                    posts);
            AtomicInteger addCalls = new AtomicInteger();
            GitBackend backend = stubBackend("https://codeberg.org/upstream-owner/Hello-World.git", (name, url) -> {
                addCalls.incrementAndGet();
                assertEquals("octocat", name);
                assertEquals("https://codeberg.org/octocat/Hello-World.git", url);
                return GitResult.ok("Added remote octocat");
            });
            AtomicInteger refreshCalls = new AtomicInteger();
            AddRemoteDialog dialog = buildGiteaDialog(backend, refreshCalls::incrementAndGet, gitea);

            FxHarness.run(dialog::forkForTest);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);

            assertEquals(1, posts.get(), "another account's fork is not this account's");
            assertEquals(1, addCalls.get());
        } finally {
            com.jfx.git.core.gitea.GiteaSession.disconnect();
        }
    }

    @Test
    void forkingOnGiteaReusesTheAccountsExistingFork() throws Exception {
        FxHarness.start();
        com.jfx.git.core.gitea.GiteaSession.connect("token", new com.jfx.git.core.gitea.GiteaUser("octocat", null));
        try {
            AtomicInteger posts = new AtomicInteger();
            var gitea = fakeGiteaByMethod("/repos/upstream-owner/Hello-World/forks",
                    """
                    [{"full_name": "octocat/Hello-World",
                      "clone_url": "https://codeberg.org/octocat/Hello-World.git"}]""",
                    "{}", posts);
            AtomicInteger refreshCalls = new AtomicInteger();
            GitBackend backend = stubBackend("https://codeberg.org/upstream-owner/Hello-World.git",
                    (name, url) -> GitResult.ok("Added remote octocat"));
            AddRemoteDialog dialog = buildGiteaDialog(backend, refreshCalls::incrementAndGet, gitea);

            FxHarness.run(dialog::forkForTest);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> refreshCalls.get() > 0);

            assertEquals(0, posts.get(), "Gitea refuses a second fork into the same account outright");
        } finally {
            com.jfx.git.core.gitea.GiteaSession.disconnect();
        }
    }
}
