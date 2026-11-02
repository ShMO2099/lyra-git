package com.jfx.git.ui.dialogs;

import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.bitbucket.BitbucketRepository;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.bitbucket.BitbucketUser;
import com.jfx.git.ui.FxHarness;

import com.sun.net.httpserver.HttpServer;

import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link BitbucketRepoPickerDialog} against a real local HTTP server - the same
 * technique {@code GitHubRepoPickerDialogTest} uses. {@link BitbucketClient
 * #listRepositories} makes more than one HTTP call (workspaces, then each workspace's
 * repositories), so unlike the GitHub/GitLab picker tests the fake server here needs
 * more than one registered context.
 */
class BitbucketRepoPickerDialogTest {

    private HttpServer server;
    private boolean started;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        BitbucketSession.disconnect();
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        if (!started) {
            server.start();
            started = true;
        }
    }

    private BitbucketClient fakeClient() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        BitbucketSession.connect("a-token", new BitbucketUser("octocat", "The Octocat"));
        return new BitbucketClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private BitbucketRepoPickerDialog buildDialog(BitbucketClient client) {
        return FxHarness.call(() -> {
            BitbucketRepoPickerDialog dialog = new BitbucketRepoPickerDialog(client);
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            return dialog;
        });
    }

    @SuppressWarnings("unchecked")
    private ListView<BitbucketRepository> list(BitbucketRepoPickerDialog dialog) {
        return (ListView<BitbucketRepository>) FxHarness.lookupAll(dialog.getDialogPane(), "list-view").get(0);
    }

    private Button okButton(BitbucketRepoPickerDialog dialog) {
        return (Button) dialog.getDialogPane().getButtonTypes().stream()
                .filter(bt -> "Use repository".equals(bt.getText()))
                .map(bt -> dialog.getDialogPane().lookupButton(bt))
                .findFirst().orElseThrow();
    }

    private List<BitbucketRepository> items(BitbucketRepoPickerDialog dialog) {
        return FxHarness.call(() -> List.copyOf(list(dialog).getItems()));
    }

    private boolean okDisabled(BitbucketRepoPickerDialog dialog) {
        return FxHarness.call(() -> okButton(dialog).isDisabled());
    }

    /** What the list's frame says while it has no rows: loading, nothing found, or why it failed. */
    private String placeholderText(BitbucketRepoPickerDialog dialog) {
        return FxHarness.call(() -> list(dialog).getPlaceholder() instanceof Parent box
                ? box.getChildrenUnmodifiable().stream().filter(Label.class::isInstance)
                        .map(n -> ((Label) n).getText()).findFirst().orElse(null)
                : null);
    }

    @Test
    void populatesTheListAcrossEveryWorkspace() throws Exception {
        FxHarness.start();
        BitbucketClient client = fakeClient();
        respond("/workspaces", 200, """
                {"values": [{"slug": "myteam"}]}""");
        respond("/repositories/myteam", 200, """
                {"values": [{"full_name": "myteam/repo-one", "is_private": false, "description": "",
                  "links": {"clone": [{"name": "https", "href": "https://bitbucket.org/myteam/repo-one.git"}]}}]}""");

        BitbucketRepoPickerDialog dialog = buildDialog(client);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !items(dialog).isEmpty());
        assertEquals("myteam/repo-one", items(dialog).get(0).fullName());
    }

    @Test
    void theUseButtonFollowsTheSelectionWhichStartsOnTheFirstRepository() throws Exception {
        FxHarness.start();
        BitbucketClient client = fakeClient();
        respond("/workspaces", 200, """
                {"values": [{"slug": "myteam"}]}""");
        respond("/repositories/myteam", 200, """
                {"values": [{"full_name": "myteam/repo-one", "is_private": true, "description": null,
                  "links": {"clone": [{"name": "https", "href": "https://bitbucket.org/myteam/repo-one.git"}]}}]}""");

        BitbucketRepoPickerDialog dialog = buildDialog(client);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !items(dialog).isEmpty());
        assertEquals("myteam/repo-one",
                FxHarness.call(() -> list(dialog).getSelectionModel().getSelectedItem().fullName()),
                "once loaded the first repository is selected");
        assertFalse(okDisabled(dialog), "so the button works without a click first");

        FxHarness.run(() -> list(dialog).getSelectionModel().clearSelection());
        assertTrue(okDisabled(dialog), "and with nothing selected there is nothing to use");
    }

    @Test
    void noWorkspacesLeavesThePlaceholderVisibleAndTheButtonDisabled() throws Exception {
        FxHarness.start();
        BitbucketClient client = fakeClient();
        respond("/workspaces", 200, """
                {"values": []}""");

        BitbucketRepoPickerDialog dialog = buildDialog(client);

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> "No repositories found.".equals(placeholderText(dialog)));

        assertTrue(okDisabled(dialog));
    }
}
