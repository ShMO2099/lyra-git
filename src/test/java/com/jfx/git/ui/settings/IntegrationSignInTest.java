package com.jfx.git.ui.settings;

import com.jfx.git.core.Settings;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.OAuthClients;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.core.hosting.ProviderSessions;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;
import com.sun.net.httpserver.HttpServer;

import javafx.scene.control.Button;
import javafx.scene.control.Labeled;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signing in with the browser, as the Integrations page sees it: where an OAuth application
 * is registered the card offers "Sign in…" with "Use a token…" beside it, and what the
 * sign-in dialog hands back is connected - and remembered - exactly as a pasted token is.
 * The flow itself is {@code ProviderSignInDialogTest}'s.
 */
class IntegrationSignInTest {

    @TempDir
    Path tempDir;

    private SettingsTab tab;
    private HttpServer api;
    private java.util.function.BiPredicate<String, String> previousWriter;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @BeforeEach
    void setUp() throws Exception {
        Settings.useFile(tempDir.resolve("settings.properties"));
        previousWriter = IntegrationsPane.tokenWriter;
    }

    @AfterEach
    void tearDown() {
        IntegrationsPane.tokenWriter = previousWriter;
        FxHarness.run(() -> {
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
        if (api != null) api.stop(0);
        for (HostingProvider p : HostingProvider.values()) ProviderSessions.disconnect(p);
        Settings.useFile(null);
    }

    private static List<Labeled> labelled(javafx.scene.Node root) {
        List<Labeled> found = new ArrayList<>();
        walk(root, found);
        return found;
    }

    private static void walk(javafx.scene.Node node, List<Labeled> into) {
        if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
            into.add(labeled);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) walk(child, into);
        }
    }

    /** With an application registered for every server, the busiest the cards get still fits. */
    @Test
    void signInAndUseATokenFitBesideEachOther() {
        for (HostingProvider p : HostingProvider.values()) {
            Settings.put(OAuthClients.clientIdKey(p), "app-" + p.id());
        }
        IntegrationsPane dialog = FxHarness.call(() -> {
            tab = new SettingsTab();
            javafx.scene.control.TabPane shown = SettingsTabs.show(tab);
            SettingsTabs.at(shown, tab, com.jfx.git.ui.SettingsSection.INTEGRATIONS);
            return (IntegrationsPane) tab.page(com.jfx.git.ui.SettingsSection.INTEGRATIONS);
        });
        FxHarness.run(() -> {
            List<Labeled> parts = labelled(dialog);
            assertTrue(parts.stream().anyMatch(l -> "Sign in…".equals(l.getText())));
            assertTrue(parts.stream().anyMatch(l -> "Use a token…".equals(l.getText())));
            double height = dialog.getScene().getHeight();
            List<String> problems = new ArrayList<>();
            for (Labeled part : parts) {
                if (!part.isWrapText() && part.getWidth() + 0.5 < part.prefWidth(-1)) {
                    problems.add("\"" + part.getText() + "\" cut short");
                }
                if (part.localToScene(part.getBoundsInLocal()).getMaxY() > height + 0.5) {
                    problems.add("\"" + part.getText() + "\" below the window");
                }
            }
            assertTrue(problems.isEmpty(), problems::toString);
            assertEquals("Connect…", ((Button) dialog.actionForTest(HostingProvider.BITBUCKET)).getText(),
                    "Bitbucket's sign-in needs a shipped secret too; with only an id it stays on tokens");
        });
    }

    /** A sign-in is connected and remembered as an OAuth grant, not flattened to a bare token. */
    @Test
    void aSignedInAccountIsConnectedAndRememberedAsAGrant() throws Exception {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/user", exchange -> {
            byte[] body = "{\"login\":\"octocat\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        GitHubClient client = new GitHubClient(URI.create("http://127.0.0.1:" + api.getAddress().getPort()));
        Map<String, String> stored = new ConcurrentHashMap<>();
        IntegrationsPane.tokenWriter = (account, value) -> {
            stored.put(account, value);
            return true;
        };
        ProviderCredential grant = ProviderCredential.oauth("gho_signed_in", null, null, "app-id", "repo");

        IntegrationsPane dialog = FxHarness.call(() -> new IntegrationsPane(client));
        FxHarness.run(() -> dialog.connectWith(HostingProvider.GITHUB, grant, true));
        RobotWait.until("the grant was never stored", () -> !stored.isEmpty());

        assertEquals("gho_signed_in", GitHubSession.token());
        ProviderCredential remembered = ProviderCredential.fromStored(stored.values().iterator().next());
        assertTrue(remembered.isOAuth(), "stored as a grant, so it can be refreshed");
        assertEquals("app-id", remembered.clientId());
        RobotWait.untilOnFx("the status never said who", () -> dialog.githubStatusForTest().contains("@octocat"));
        FxHarness.run(dialog::dispose);
    }
}
