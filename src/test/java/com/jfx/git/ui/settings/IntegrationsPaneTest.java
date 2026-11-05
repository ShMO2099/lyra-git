package com.jfx.git.ui.settings;

import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.OAuthClients;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;

import com.sun.net.httpserver.HttpServer;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link IntegrationsPane}'s GitHub row against a real local HTTP server, the
 * same technique {@code GitHubClientTest} uses - what this class adds on top is the
 * page's own state machine (Connect… / Connecting… / Connected as @x / Disconnect),
 * not the HTTP handling itself, which is already covered there.
 *
 * <p>{@link IntegrationsPane#connectWith} is called directly for the page's state machine
 * - the path a browser sign-in ends in - rather than through the token prompt; this class
 * is not an {@code ApplicationTest}, and the seam exists for exactly this, the same
 * reasoning {@code DiffTextArea.handleZoomScroll} being package-visible documents for
 * itself. The prompts themselves are shown and filled in through their field ids, and their
 * footer buttons fired, for what they add: a token checked with the prompt still open.
 *
 * <h2>The failure path blocks the FX thread on purpose</h2>
 * A bad token's callback calls {@code Alert.showAndWait()}, a real, correct thing for
 * production code to do - the whole point of a modal alert is to hold the thread until
 * acknowledged. {@code showAndWait()} runs a nested event loop rather than a true
 * block, so a {@code Platform.runLater} queued afterward - such as {@link
 * FxHarness#run} - still executes while it is "waiting"; that is what lets {@link
 * #closeAnyShowingAlert()} reach in and dismiss it from outside.
 */
class IntegrationsPaneTest {

    private HttpServer server;

    /** Every page a test built: each follows the sessions until disposed, as the tab's would. */
    private final List<IntegrationsPane> built = new java.util.ArrayList<>();

    private IntegrationsPane track(IntegrationsPane pane) {
        built.add(pane);
        return pane;
    }

    /**
     * No application registered for any provider, so every card connects with a token -
     * what these tests are about - whichever registrations ship, and whether or not this
     * machine keeps a Bitbucket secret beside them.
     */
    @BeforeEach
    void noRegistrations() {
        OAuthClients.useForTests(new java.util.Properties());
    }

    @AfterEach
    void tidyUp() {
        FxHarness.run(() -> built.forEach(IntegrationsPane::dispose));
        if (server != null) {
            server.stop(0);
        }
        GitHubSession.disconnect();
        GitLabSession.disconnect();
        BitbucketSession.disconnect();
        OAuthClients.useForTests(null);
    }

    private GitHubClient fakeClient(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext("/user", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new GitHubClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    /** Same shape as {@link #fakeClient}, for the GitLab row's own connect test. */
    private GitLabClient fakeGitLabClient(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext("/user", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new GitLabClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    /** Same shape as {@link #fakeClient}, for the Bitbucket row's own connect test. */
    private BitbucketClient fakeBitbucketClient(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext("/user", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new BitbucketClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private IntegrationsPane buildDialog(GitHubClient client) {
        return FxHarness.call(() -> {
            IntegrationsPane dialog = track(new IntegrationsPane(client));
            FxHarness.pulse(dialog);
            return dialog;
        });
    }

    private IntegrationsPane buildDialogForGitLab(GitLabClient client) {
        return FxHarness.call(() -> {
            IntegrationsPane dialog = track(new IntegrationsPane(new GitHubClient(), client, new BitbucketClient()));
            FxHarness.pulse(dialog);
            return dialog;
        });
    }

    private IntegrationsPane buildDialogForBitbucket(BitbucketClient client) {
        return FxHarness.call(() -> {
            IntegrationsPane dialog = track(new IntegrationsPane(new GitHubClient(), new GitLabClient(), client));
            FxHarness.pulse(dialog);
            return dialog;
        });
    }

    private List<Label> labels(IntegrationsPane dialog) {
        return FxHarness.call(() -> dialog.lookupAll(".label").stream()
                .map(n -> (Label) n).toList());
    }

    private List<Button> buttons(IntegrationsPane dialog) {
        return FxHarness.call(() -> dialog.lookupAll(".button").stream()
                .map(n -> (Button) n).toList());
    }

    /**
     * Reaches into the still-"waiting" FX thread to dismiss a modal alert from outside
     * - see class Javadoc.
     *
     * <p>Polled from this thread via {@link FxHarness#call}, not by streaming {@link
     * Window#getWindows()} directly from here: that list is live and the FX thread is
     * actively showing/hiding windows while this polls, and iterating an {@code
     * ObservableList} from outside its owning thread while it mutates concurrently is
     * exactly the {@code IndexOutOfBoundsException} a first version of this method hit.
     */
    private void closeAnyShowingAlert() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            boolean closed = FxHarness.call(() -> {
                var alerts = Window.getWindows().stream()
                        .filter(w -> w.isShowing() && w.getScene() != null
                                && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
                        .toList();
                alerts.forEach(Window::hide);
                return !alerts.isEmpty();
            });
            if (closed) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no alert appeared within 5 seconds");
    }

    @Test
    void startsDisconnectedWithAConnectButton() throws Exception {
        FxHarness.start();
        IntegrationsPane dialog = buildDialog(fakeClient(200, "{}"));

        assertTrue(labels(dialog).stream().anyMatch(l -> "Not connected".equals(l.getText())));
        assertTrue(buttons(dialog).stream().anyMatch(b -> "Connect…".equals(b.getText())));
    }

    @Test
    void aValidTokenConnectsAndShowsTheUsername() throws Exception {
        FxHarness.start();
        IntegrationsPane dialog = buildDialog(fakeClient(200, """
                {"login": "octocat", "avatar_url": null}"""));

        FxHarness.run(() -> dialog.connectWith("a-token"));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, GitHubSession::isConnected);

        assertEquals("octocat", GitHubSession.user().login());
        assertEquals("a-token", GitHubSession.token());
        assertTrue(labels(dialog).stream()
                        .anyMatch(l -> l.getText() != null && l.getText().contains("Connected as @octocat")),
                "the row should now show who is connected");
        assertTrue(buttons(dialog).stream().anyMatch(b -> "Disconnect".equals(b.getText())));
    }

    @Test
    void aBadTokenLeavesTheSessionDisconnectedAndReportsWhy() throws Exception {
        FxHarness.start();
        IntegrationsPane dialog = buildDialog(fakeClient(401, """
                {"message": "Bad credentials", "documentation_url": "https://docs.github.com/rest", "status": "401"}"""));

        FxHarness.run(() -> dialog.connectWith("wrong-token"));
        closeAnyShowingAlert();

        assertFalse(GitHubSession.isConnected(), "a bad token must not leave the session connected");
        assertTrue(labels(dialog).stream().anyMatch(l -> "Not connected".equals(l.getText())),
                "the row should fall back to Not connected, not stay on Connecting…");
    }

    @Test
    void disconnectingForgetsTheSessionAndReturnsToNotConnected() throws Exception {
        FxHarness.start();
        IntegrationsPane dialog = buildDialog(fakeClient(200, """
                {"login": "octocat", "avatar_url": null}"""));

        FxHarness.run(() -> dialog.connectWith("a-token"));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, GitHubSession::isConnected);

        FxHarness.run(() -> buttons(dialog).stream()
                .filter(b -> "Disconnect".equals(b.getText()))
                .findFirst().orElseThrow().fire());

        assertFalse(GitHubSession.isConnected());
        assertTrue(labels(dialog).stream().anyMatch(l -> "Not connected".equals(l.getText())));
        assertTrue(buttons(dialog).stream().anyMatch(b -> "Connect…".equals(b.getText())));
    }

    /**
     * Pins that the GitLab row is wired the same way GitHub's is - {@link
     * #aValidTokenConnectsAndShowsTheUsername} already covers the connect/disconnect
     * state machine itself in full, so this and the Bitbucket test below only need to
     * prove each row reaches its own session, not repeat every state transition.
     */
    @Test
    void aValidGitLabTokenConnectsTheGitLabSessionAndShowsTheUsername() throws Exception {
        FxHarness.start();
        IntegrationsPane dialog = buildDialogForGitLab(fakeGitLabClient(200, """
                {"username": "octocat", "avatar_url": null}"""));

        FxHarness.run(() -> dialog.connectGitLabWith("a-token"));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, GitLabSession::isConnected);

        assertEquals("octocat", GitLabSession.user().username());
        assertFalse(GitHubSession.isConnected(), "only the GitLab row was connected");
        assertTrue(labels(dialog).stream()
                .anyMatch(l -> l.getText() != null && l.getText().contains("Connected as @octocat")));
        assertEquals(1, buttons(dialog).stream().filter(b -> "Disconnect".equals(b.getText())).count(),
                "only the GitLab row should have disconnected");
    }

    /** See {@link #aValidGitLabTokenConnectsTheGitLabSessionAndShowsTheUsername} for why this is a thin pin. */
    @Test
    void aValidBitbucketTokenConnectsTheBitbucketSessionAndShowsTheHandle() throws Exception {
        FxHarness.start();
        IntegrationsPane dialog = buildDialogForBitbucket(fakeBitbucketClient(200, """
                {"username": "octocat", "display_name": "The Octocat"}"""));

        FxHarness.run(() -> dialog.connectBitbucketWith("a-token"));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, BitbucketSession::isConnected);

        assertEquals("octocat", BitbucketSession.user().handle());
        assertFalse(GitHubSession.isConnected(), "only the Bitbucket row was connected");
        assertTrue(labels(dialog).stream()
                .anyMatch(l -> l.getText() != null && l.getText().contains("Connected as @octocat")));
        assertEquals(1, buttons(dialog).stream().filter(b -> "Disconnect".equals(b.getText())).count(),
                "only the Bitbucket row should have disconnected");
    }

    // ------------------------------------------------------------------
    // Remembering, and sessions that change while the page is open
    // ------------------------------------------------------------------

    @Test
    void aTokenThatCouldNotBeRememberedSaysSoAndKeepsSaying() throws Exception {
        FxHarness.start();
        var previous = IntegrationsPane.tokenWriter;
        IntegrationsPane.tokenWriter = (account, token) -> false;   // the store refused
        try {
            IntegrationsPane dialog = buildDialog(fakeClient(200, """
                    {"login": "octocat", "avatar_url": null}"""));
            FxHarness.run(() -> dialog.connectWith("ghp_test", true));
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> FxHarness.call(dialog::githubStatusForTest).contains("could not be remembered"));
            WaitForAsyncUtils.waitForFxEvents();
            assertTrue(GitHubSession.isConnected(), "connected for this session all the same");
            assertTrue(FxHarness.call(dialog::githubStatusForTest).contains("could not be remembered"),
                    "and the warning is not overwritten by the ordinary connected text");
        } finally {
            IntegrationsPane.tokenWriter = previous;
        }
    }

    @Test
    void aRememberedTokenEndsAsAPlainConnection() throws Exception {
        FxHarness.start();
        var previous = IntegrationsPane.tokenWriter;
        java.util.List<String> stored = new java.util.concurrent.CopyOnWriteArrayList<>();
        IntegrationsPane.tokenWriter = (account, token) -> stored.add(account);
        try {
            IntegrationsPane dialog = buildDialog(fakeClient(200, """
                    {"login": "octocat", "avatar_url": null}"""));
            FxHarness.run(() -> dialog.connectWith("ghp_test", true));
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !stored.isEmpty());
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(java.util.List.of(com.jfx.git.core.ProviderHosts.tokenAccount("github")), stored,
                    "stored once, under the server's own account name");
            assertEquals("Connected as @octocat", FxHarness.call(dialog::githubStatusForTest));
        } finally {
            IntegrationsPane.tokenWriter = previous;
        }
    }

    // ------------------------------------------------------------------
    // The token and server prompts
    // ------------------------------------------------------------------

    /** Shows {@code dialog} and waits for it to lay out. */
    private static <D extends Dialog<?>> D shown(D dialog) {
        FxHarness.run(dialog::show);
        RobotWait.untilOnFx("the prompt never laid out", () -> dialog.getDialogPane().getHeight() > 0);
        return dialog;
    }

    /** What the prompt's fields say is wrong, joined; empty when nothing. */
    private static String fieldErrors(Dialog<?> dialog) {
        return FxHarness.lookupAll(dialog.getDialogPane(), "jfx-field-error").stream()
                .map(n -> ((Label) n).getText())
                .collect(java.util.stream.Collectors.joining());
    }

    private static Button footerButton(Dialog<?> dialog, String text) {
        ButtonType type = dialog.getDialogPane().getButtonTypes().stream()
                .filter(t -> text.equals(t.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " button"));
        return (Button) dialog.getDialogPane().lookupButton(type);
    }

    /** The dialog windows showing now. On the FX thread. */
    private static List<Window> showingDialogs() {
        return Window.getWindows().stream()
                .filter(w -> w.isShowing() && w.getScene() != null
                        && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
                .toList();
    }

    /** Pastes {@code token} into the prompt, with Remember off so nothing reaches the real store. */
    private static void paste(Dialog<?> prompt, String token) {
        ((CheckBox) prompt.getDialogPane().lookup("#" + IntegrationsPane.REMEMBER_ID)).setSelected(false);
        ((TextField) prompt.getDialogPane().lookup("#" + IntegrationsPane.TOKEN_FIELD_ID)).setText(token);
    }

    /**
     * A refused token is said under the field and the prompt stays, with the token in it
     * to correct - where it used to close and open an error dialog over the page.
     */
    @Test
    void aRefusedTokenIsSaidUnderTheFieldAndThePromptStaysOpen() throws Exception {
        FxHarness.start();
        IntegrationsPane page = buildDialog(fakeClient(401, """
                {"message": "Bad credentials", "documentation_url": "https://docs.github.com/rest", "status": "401"}"""));
        Dialog<ButtonType> prompt = shown(FxHarness.call(() -> page.tokenDialogForTest(HostingProvider.GITHUB)));
        try {
            List<Window> before = FxHarness.call(IntegrationsPaneTest::showingDialogs);
            FxHarness.run(() -> {
                paste(prompt, "wrong-token");
                footerButton(prompt, "Connect").fire();
            });
            RobotWait.untilOnFx("the refusal was never said under the field", () -> !fieldErrors(prompt).isEmpty());

            FxHarness.run(() -> {
                assertTrue(prompt.isShowing(), "the prompt stays open to correct the token");
                assertTrue(fieldErrors(prompt).contains("did not accept this token"), fieldErrors(prompt));
                assertEquals("wrong-token", ((TextField) prompt.getDialogPane()
                        .lookup("#" + IntegrationsPane.TOKEN_FIELD_ID)).getText(), "with the token still in it");
                assertFalse(footerButton(prompt, "Connect").isDisabled(), "ready to try again");
                assertEquals(before, showingDialogs(), "and no error dialog opened over it");
                assertEquals("Not connected", page.githubStatusForTest());
            });
            assertFalse(GitHubSession.isConnected());

            FxHarness.run(() -> paste(prompt, "another-token"));
            assertEquals("", FxHarness.call(() -> fieldErrors(prompt)), "a new token is not the refused one");
        } finally {
            FxHarness.run(() -> {
                if (prompt.isShowing()) prompt.close();
            });
        }
    }

    /** An accepted token closes the prompt, connected; what was pasted is trimmed. */
    @Test
    void anAcceptedTokenClosesThePromptConnected() throws Exception {
        FxHarness.start();
        IntegrationsPane page = buildDialog(fakeClient(200, """
                {"login": "octocat", "avatar_url": null}"""));
        Dialog<ButtonType> prompt = shown(FxHarness.call(() -> page.tokenDialogForTest(HostingProvider.GITHUB)));
        try {
            FxHarness.run(() -> {
                assertTrue(footerButton(prompt, "Connect").isDisabled(), "nothing to connect with yet");
                paste(prompt, "  a-token ");
                footerButton(prompt, "Connect").fire();
            });
            RobotWait.untilOnFx("the prompt never closed", () -> !prompt.isShowing());

            assertTrue(GitHubSession.isConnected());
            assertEquals("a-token", GitHubSession.token());
            assertEquals("Connected as @octocat", FxHarness.call(page::githubStatusForTest));
        } finally {
            FxHarness.run(() -> {
                if (prompt.isShowing()) prompt.close();
            });
        }
    }

    /** An address that cannot be used is said under the field as it is typed, with Save off. */
    @Test
    void aServerAddressThatCannotBeUsedIsSaidAsItIsTyped() throws Exception {
        FxHarness.start();
        IntegrationsPane page = buildDialog(fakeClient(200, "{}"));
        FxHarness.run(() -> {
            Dialog<?> prompt = page.serverDialogForTest(HostingProvider.GITHUB);
            TextField url = (TextField) prompt.getDialogPane().lookup("#" + IntegrationsPane.SERVER_URL_ID);
            Button save = footerButton(prompt, "Save");
            assertEquals("https://github.example.com", url.getPromptText(), "the placeholder is an example");

            url.setText("http://github.example.com");
            assertTrue(fieldErrors(prompt).contains("https://"), fieldErrors(prompt));
            assertTrue(save.isDisabled(), "a token would travel in clear text");

            url.setText("https://github.example.com");
            assertEquals("", fieldErrors(prompt));
            assertFalse(save.isDisabled());

            url.setText("");
            assertEquals("", fieldErrors(prompt), "blank is the public server");
            assertFalse(save.isDisabled());
        });
    }

    @Test
    void aSessionConnectedElsewhereShowsUpOnAnOpenPage() throws Exception {
        // What a background restore does after this page was built.
        FxHarness.start();
        IntegrationsPane dialog = buildDialog(fakeClient(200, "{}"));
        assertEquals("Not connected", FxHarness.call(dialog::githubStatusForTest));

        GitHubSession.connect("restored", new com.jfx.git.core.github.GitHubUser("octocat", null));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("Connected as @octocat", FxHarness.call(dialog::githubStatusForTest),
                "the row follows the session without being reopened");
    }
}
