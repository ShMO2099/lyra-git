package com.jfx.git.ui.dialogs;

import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaRepository;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.gitea.GiteaUser;
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
 * Drives {@link GiteaRepoPickerDialog} against a local HTTP server standing in for a
 * Gitea API - {@code GitHubRepoPickerDialogTest}, adapted: the list fills from the
 * account's repositories, the first is picked once they arrive, "Use repository" follows
 * the selection, and the answer carries the HTTPS clone URL the clone dialog fills in.
 *
 * <p>Every read of the list goes through {@link FxHarness#call}: the list is filled on the
 * FX thread by an async callback, and reading it from the test thread while it changes is
 * a race.
 */
class GiteaRepoPickerDialogTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        GiteaSession.disconnect();
    }

    private GiteaClient fakeClient(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/user/repos", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        GiteaSession.connect("a-token", new GiteaUser("octocat", null));
        return new GiteaClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private GiteaRepoPickerDialog buildDialog(GiteaClient client) {
        return FxHarness.call(() -> {
            GiteaRepoPickerDialog dialog = new GiteaRepoPickerDialog(client);
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            return dialog;
        });
    }

    @SuppressWarnings("unchecked")
    private ListView<GiteaRepository> list(GiteaRepoPickerDialog dialog) {
        return (ListView<GiteaRepository>) FxHarness.lookupAll(dialog.getDialogPane(), "list-view").get(0);
    }

    private Button okButton(GiteaRepoPickerDialog dialog) {
        return (Button) dialog.getDialogPane().getButtonTypes().stream()
                .filter(bt -> "Use repository".equals(bt.getText()))
                .map(bt -> dialog.getDialogPane().lookupButton(bt))
                .findFirst().orElseThrow();
    }

    private List<GiteaRepository> items(GiteaRepoPickerDialog dialog) {
        return FxHarness.call(() -> List.copyOf(list(dialog).getItems()));
    }

    private boolean okDisabled(GiteaRepoPickerDialog dialog) {
        return FxHarness.call(() -> okButton(dialog).isDisabled());
    }

    /** What the list's frame says while it has no rows: loading, nothing found, or why it failed. */
    private String placeholderText(GiteaRepoPickerDialog dialog) {
        return FxHarness.call(() -> list(dialog).getPlaceholder() instanceof Parent box
                ? box.getChildrenUnmodifiable().stream().filter(Label.class::isInstance)
                        .map(n -> ((Label) n).getText()).findFirst().orElse(null)
                : null);
    }

    private static final String ONE_REPO = """
            [{"full_name": "octocat/Hello-World", "clone_url": "https://codeberg.org/octocat/Hello-World.git",
              "ssh_url": "git@codeberg.org:octocat/Hello-World.git", "description": "My first repo",
              "private": false, "name": "Hello-World", "owner": {"login": "octocat"}}]""";

    @Test
    void populatesTheListFromTheAccountsRepositories() throws Exception {
        FxHarness.start();
        GiteaRepoPickerDialog dialog = buildDialog(fakeClient(200, ONE_REPO));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !items(dialog).isEmpty());

        List<GiteaRepository> repos = items(dialog);
        assertEquals(1, repos.size());
        assertEquals("octocat/Hello-World", repos.get(0).fullName());
    }

    @Test
    void cloneAnswersWithTheHttpsCloneUrlOnceARepositoryIsSelected() throws Exception {
        FxHarness.start();
        GiteaRepoPickerDialog dialog = buildDialog(fakeClient(200, ONE_REPO));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !items(dialog).isEmpty());
        assertFalse(okDisabled(dialog), "the first repository is selected once they arrive");
        FxHarness.run(() -> list(dialog).getSelectionModel().clearSelection());
        assertTrue(okDisabled(dialog), "and with nothing selected there is nothing to use");

        FxHarness.run(() -> list(dialog).getSelectionModel().selectFirst());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !okDisabled(dialog));

        GiteaRepository picked = FxHarness.call(() -> dialog.getResultConverter().call(
                dialog.getDialogPane().getButtonTypes().stream()
                        .filter(bt -> "Use repository".equals(bt.getText())).findFirst().orElseThrow()));
        assertEquals("https://codeberg.org/octocat/Hello-World.git", picked.cloneUrl());
    }

    @Test
    void anEmptyRepositoryListLeavesThePlaceholderVisibleAndTheButtonDisabled() throws Exception {
        FxHarness.start();
        GiteaRepoPickerDialog dialog = buildDialog(fakeClient(200, "[]"));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> "No repositories found.".equals(placeholderText(dialog)));

        assertTrue(okDisabled(dialog));
    }

    @Test
    void aRejectedTokenIsShownInThePlaceholder() throws Exception {
        FxHarness.start();
        GiteaRepoPickerDialog dialog = buildDialog(fakeClient(401, """
                {"message": "token is required"}"""));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
            String text = placeholderText(dialog);
            return text != null && text.startsWith("Could not load repositories: ");
        });
        assertTrue(okDisabled(dialog));
    }
}
