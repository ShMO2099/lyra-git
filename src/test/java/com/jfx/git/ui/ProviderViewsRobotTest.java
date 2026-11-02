package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ProviderHosts;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.ui.views.GitHubPullRequestsView;
import com.jfx.git.ui.views.PrReviewView;
import com.jfx.git.ui.views.ProviderView;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolbar's Pull requests opens its list inside the repository's tab, not in a window:
 * the graph gives way, the toolbar stays, a review stacks on top of the list, Back walks
 * down the stack, and leaving the list stops its work.
 *
 * <p>The whole path is real - the toolbar, the tab, the view, its listing and the review -
 * against a local server standing in for a GitHub Enterprise host, which is how a remote
 * on {@code localhost} counts as GitHub. Only the git calls a review's preparation makes
 * are answered by hand: they would fetch the pull request's head from that server, which
 * speaks the REST API and not git's protocol.
 */
class ProviderViewsRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private GitService service;
    private RepoTab tab;
    private final AtomicInteger listRequests = new AtomicInteger();

    private static final String OPEN_PRS = """
            [{"number": 42, "title": "Fix flaky test", "user": {"login": "octocat", "avatar_url": null},
              "draft": false, "head": {"ref": "fix-flaky-test", "sha": "headsha123"},
              "base": {"ref": "main", "sha": "basesha456"},
              "html_url": "http://localhost/octocat/Hello-World/pull/42"}]""";

    @Override
    public void start(Stage stage) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        String api = "/api/v3/repos/octocat/Hello-World";
        server.createContext(api + "/pulls", exchange -> {
            listRequests.incrementAndGet();
            reply(exchange, OPEN_PRS);
        });
        server.createContext(api + "/pulls/42/comments", exchange -> reply(exchange, "[]"));
        server.createContext(api + "/commits/", exchange -> reply(exchange, "{\"total_count\": 0, \"check_runs\": []}"));
        server.start();
        ProviderHosts.setGithub(base);
        GitHubSession.connect("token", new GitHubUser("octocat", null));

        Path repo = tempDir.resolve("repo");
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(repo.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(repo.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.remoteAdd().setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(base + "/octocat/Hello-World.git"))
                    .call();
            service = new GitService();
            service.openRepository(repo.toFile());
        }
        tab = new RepoTab(new LyraApp(), "test", reviewable(service));
        stage.setScene(new Scene(new TabPane(tab), 1200, 760));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        GitHubSession.disconnect();
        ProviderHosts.setGithub("");
        if (server != null) server.stop(0);
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** The real backend, with the fetches and diffs of a review's preparation answered here. */
    private static GitBackend reviewable(GitService real) {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(), new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "fetchRemoteRef": return GitResult.ok("fetched");
                        case "fetchAll": return GitResult.ok("fetched");
                        case "mergeBase": return "basesha456";
                        case "getFilesBetweenCommits": return List.of("src/Main.java");
                        case "getDiffBytesBetweenCommits":
                            return "@@ -1 +1 @@\n-old\n+new\n".getBytes(StandardCharsets.UTF_8);
                        default: break;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Refreshes, so the toolbar knows the origin and has named and enabled its buttons. */
    private void refreshTab() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        RobotWait.untilOnFx("the Pull requests button turns on", () -> !pullRequestsButton().isDisabled());
    }

    private Button pullRequestsButton() {
        return (Button) tab.getToolbar().toolButton("Pull requests");
    }

    private Button backButton() {
        return lookup(".repo-view-header .button").queryAs(Button.class);
    }

    private boolean graphShowing() {
        for (Node n = tab.graphList(); n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return tab.graphList().getScene() != null;
    }

    private RepoView currentView() {
        return WaitForAsyncUtils.waitForAsyncFx(5000, () -> tab.currentView());
    }

    /** Opens the list from the toolbar and waits for its row to arrive. */
    private GitHubPullRequestsView openPullRequests() {
        clickOn(pullRequestsButton());
        RobotWait.untilOnFx("the pull requests open in the tab",
                () -> tab.currentView() instanceof GitHubPullRequestsView);
        RobotWait.untilOnFx("the open pull request is listed", () -> row42() != null);
        return (GitHubPullRequestsView) currentView();
    }

    private Node row42() {
        return lookup(".repo-view .list-cell .label").queryAll().stream()
                .filter(n -> n instanceof Labeled l && l.getText() != null && l.getText().startsWith("#42 "))
                .findFirst().orElse(null);
    }

    private Button actionButton(String text) {
        return lookup(".repo-view .button").queryAll().stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(Button.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no " + text + " button in the view"));
    }

    @Test
    void pullRequestsOpenInTheTabAReviewStacksOnTopAndBackWalksDown() {
        refreshTab();
        GitHubPullRequestsView list = openPullRequests();

        assertFalse(WaitForAsyncUtils.waitForAsyncFx(5000, this::graphShowing), "the graph gives way to the list");
        assertTrue(WaitForAsyncUtils.waitForAsyncFx(5000, () -> tab.getToolbar().getScene() != null
                && tab.getToolbar().isVisible()), "the toolbar stays");
        assertEquals("← Back to graph", backButton().getText());
        assertTrue(WaitForAsyncUtils.waitForAsyncFx(5000, () -> list.content().getScene() != null
                        && list.content().getScene() == tab.getTabPane().getScene()),
                "in the repository's own window, not one of its own");

        clickOn(row42());
        RobotWait.untilOnFx("Review… turns on with a row selected", () -> !actionButton("Review…").isDisabled());
        clickOn(actionButton("Review…"));
        RobotWait.untilOnFx("the review opens over the list", () -> tab.currentView() instanceof PrReviewView);
        assertEquals("← Back to Pull requests", backButton().getText());
        assertTrue(list.isOpen(), "the list stays open underneath");

        clickOn(backButton());
        RobotWait.untilOnFx("Back returns to the list", () -> tab.currentView() == list);
        assertTrue(WaitForAsyncUtils.waitForAsyncFx(5000, () -> row42() != null), "with its rows as they were");
        assertEquals(1, listRequests.get(), "and without asking the server again");

        clickOn(backButton());
        RobotWait.untilOnFx("Back again returns to the graph", () -> tab.currentView() == null);
        assertTrue(WaitForAsyncUtils.waitForAsyncFx(5000, this::graphShowing));
        assertFalse(list.isOpen(), "leaving the list stopped its listing");
    }

    @Test
    void pressingPullRequestsAgainBringsBackTheOpenListRatherThanStackingAnother() {
        refreshTab();
        GitHubPullRequestsView list = openPullRequests();

        clickOn(pullRequestsButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertSame(list, currentView());
        assertEquals("← Back to graph", backButton().getText(), "one list, not one on top of another");
        assertEquals(1, listRequests.get());
    }

    @Test
    void pressingPullRequestsUnderAReviewReturnsToTheList() {
        refreshTab();
        GitHubPullRequestsView list = openPullRequests();
        clickOn(row42());
        RobotWait.untilOnFx("Review… turns on", () -> !actionButton("Review…").isDisabled());
        clickOn(actionButton("Review…"));
        RobotWait.untilOnFx("the review opens", () -> tab.currentView() instanceof PrReviewView);
        RepoView review = currentView();

        clickOn(pullRequestsButton());
        RobotWait.untilOnFx("the list comes back to the top", () -> tab.currentView() == list);
        assertEquals("← Back to graph", backButton().getText());
        assertInstanceOf(PrReviewView.class, review);
    }

    /** The filter takes the focus when the list opens, and F5 loads the list again from there. */
    @Test
    void theFilterHasTheFocusAndF5LoadsTheListAgain() {
        refreshTab();
        openPullRequests();
        javafx.scene.control.TextField filter =
                lookup(".repo-view .sidebar-filter").queryAs(javafx.scene.control.TextField.class);
        RobotWait.untilOnFx("the filter has the focus", () -> filter.getScene().getFocusOwner() == filter);
        int before = listRequests.get();

        type(javafx.scene.input.KeyCode.F5);

        RobotWait.until("F5 asked the server again", () -> listRequests.get() > before);
        assertSame(GitHubPullRequestsView.class, currentView().getClass(), "and the list stays open");
    }

    /** "Create pull request" after a first push opens the list's new-request form, filled in. */
    @Test
    void aDraftOpensTheNewRequestFormFilledIn() {
        refreshTab();
        GitHubPullRequestsView list = openPullRequests();
        String branch = service.getCurrentBranch();

        interact(() -> list.prefill(new com.jfx.git.ui.dialogs.CreatePullRequestDialog.Draft(
                "Tidy the parser", "## Why\n", branch, "main")));

        javafx.scene.control.DialogPane form = RobotWait.untilDialogWithButton("OK");
        javafx.scene.control.TextField title = (javafx.scene.control.TextField) form.lookup(".pr-title");
        javafx.scene.control.TextArea description =
                (javafx.scene.control.TextArea) form.lookup(".pr-description");
        assertEquals("Tidy the parser", WaitForAsyncUtils.waitForAsyncFx(5000, () -> title.getText()));
        assertEquals("## Why\n", WaitForAsyncUtils.waitForAsyncFx(5000, () -> description.getText()));
        interact(() -> ((javafx.scene.control.Button) form.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire());
    }

    /**
     * The first push of a branch offers a pull request for it: a notification that stays,
     * whose button opens the list and its form, filled in from the branch's one commit.
     * origin names the GitHub host; its push URL is a bare repository beside the fixture,
     * so the push itself is real.
     */
    @Test
    void publishingABranchOffersAPullRequestFilledIn() throws Exception {
        Path bare = tempDir.resolve("pushed.git");
        Git.init().setDirectory(bare.toFile()).setBare(true).call().close();
        assertTrue(service.setLocalConfig("remote.origin.pushurl", bare.toUri().toString()).isOk());
        assertTrue(service.createBranch("feature/tidy-up", null, true).isOk());
        Files.writeString(service.getWorkTree().toPath().resolve("b.txt"), "b\n", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("b.txt").isOk());
        assertTrue(service.commit("Tidy the parser", false).isOk());
        refreshTab();

        interact(() -> tab.getToolbar().triggerPush());

        RobotWait.untilOnFx("the push never offered a pull request", () -> offerButton() != null);
        assertEquals("origin/feature/tidy-up", service.getUpstream("feature/tidy-up"), "the push published it");
        clickOn(offerButton());

        javafx.scene.control.DialogPane form = RobotWait.untilDialogWithButton("OK");
        javafx.scene.control.TextField title = (javafx.scene.control.TextField) form.lookup(".pr-title");
        assertEquals("Tidy the parser", WaitForAsyncUtils.waitForAsyncFx(5000, () -> title.getText()),
                "the branch's one commit names the request");
        assertTrue(tab.currentView() instanceof GitHubPullRequestsView, "over the list, in the tab");
        interact(() -> ((javafx.scene.control.Button) form.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire());

        // A second push publishes nothing, and offers nothing.
        RobotWait.drainGitWork();
        int offers = WaitForAsyncUtils.waitForAsyncFx(5000, () -> offerCount());
        interact(() -> tab.getToolbar().triggerPush());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(offers, (int) WaitForAsyncUtils.waitForAsyncFx(5000, () -> offerCount()));
    }

    private Button offerButton() {
        return lookup(".toast-action").queryAll().stream()
                .filter(n -> n instanceof Button b && "Create pull request".equals(b.getText()))
                .map(Button.class::cast).findFirst().orElse(null);
    }

    private int offerCount() {
        return (int) lookup(".toast-action").queryAll().stream()
                .filter(n -> n instanceof Button b && "Create pull request".equals(b.getText())).count();
    }

    @Test
    void closingTheTabStopsTheList() {
        refreshTab();
        ProviderView list = openPullRequests();

        interact(() -> tab.close());

        assertFalse(list.isOpen());
        assertNull(currentView());
        tab = null;
        service = null;
    }
}
