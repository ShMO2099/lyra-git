package com.jfx.git.ui.dialogs;

import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubRepository;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.ui.FxHarness;

import com.sun.net.httpserver.HttpServer;

import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

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
 * Drives {@link GitHubRepoPickerDialog} against a real local HTTP server, the same
 * technique {@code GitHubClientTest} and {@code IntegrationDialogTest} already use -
 * this class adds the dialog's own list-population, filter and OK-button-gating state, not
 * the HTTP handling itself.
 *
 * <p>Every read of the dialog's list or button state is routed through {@link
 * FxHarness#call}, never read directly from the polling thread: {@code
 * ListView.getItems()} is populated on the FX thread by the async {@code
 * GitTaskRunner.query} callback, and reading a live {@code ObservableList} from another
 * thread while it mutates is exactly the concurrency bug {@code IntegrationDialogTest}
 * documents for {@code Window.getWindows()}.
 */
class GitHubRepoPickerDialogTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        GitHubSession.disconnect();
    }

    private GitHubClient fakeClient(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext("/user/repos", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        GitHubSession.connect("a-token", new GitHubUser("octocat", null));
        return new GitHubClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private GitHubRepoPickerDialog buildDialog(GitHubClient client) {
        return FxHarness.call(() -> {
            GitHubRepoPickerDialog dialog = new GitHubRepoPickerDialog(client);
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            return dialog;
        });
    }

    @SuppressWarnings("unchecked")
    private ListView<GitHubRepository> list(GitHubRepoPickerDialog dialog) {
        return (ListView<GitHubRepository>) FxHarness.lookupAll(dialog.getDialogPane(), "list-view").get(0);
    }

    private Button okButton(GitHubRepoPickerDialog dialog) {
        return (Button) dialog.getDialogPane().getButtonTypes().stream()
                .filter(bt -> "Use repository".equals(bt.getText()))
                .map(bt -> dialog.getDialogPane().lookupButton(bt))
                .findFirst().orElseThrow();
    }

    private List<GitHubRepository> items(GitHubRepoPickerDialog dialog) {
        return FxHarness.call(() -> List.copyOf(list(dialog).getItems()));
    }

    private boolean okDisabled(GitHubRepoPickerDialog dialog) {
        return FxHarness.call(() -> okButton(dialog).isDisabled());
    }

    /** What the list's frame says while it has no rows: loading, nothing found, or why it failed. */
    private String placeholderText(GitHubRepoPickerDialog dialog) {
        return FxHarness.call(() -> list(dialog).getPlaceholder() instanceof Parent box
                ? box.getChildrenUnmodifiable().stream().filter(Label.class::isInstance)
                        .map(n -> ((Label) n).getText()).findFirst().orElse(null)
                : null);
    }

    private TextField filter(GitHubRepoPickerDialog dialog) {
        return (TextField) dialog.getDialogPane().lookup("#" + RepoPicker.FILTER_ID);
    }

    @Test
    void populatesTheListFromTheAuthenticatedUsersRepositories() throws Exception {
        FxHarness.start();
        GitHubRepoPickerDialog dialog = buildDialog(fakeClient(200, """
                [{"full_name": "octocat/Hello-World", "clone_url": "https://github.com/octocat/Hello-World.git",
                  "ssh_url": "git@github.com:octocat/Hello-World.git", "description": "My first repo", "private": false}]"""));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !items(dialog).isEmpty());

        List<GitHubRepository> repos = items(dialog);
        assertEquals(1, repos.size());
        assertEquals("octocat/Hello-World", repos.get(0).fullName());
    }

    @Test
    void theUseButtonFollowsTheSelectionWhichStartsOnTheFirstRepository() throws Exception {
        FxHarness.start();
        GitHubRepoPickerDialog dialog = buildDialog(fakeClient(200, """
                [{"full_name": "octocat/Hello-World", "clone_url": "https://github.com/octocat/Hello-World.git",
                  "ssh_url": "git@github.com:octocat/Hello-World.git", "description": null, "private": true}]"""));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !items(dialog).isEmpty());
        assertEquals("octocat/Hello-World",
                FxHarness.call(() -> list(dialog).getSelectionModel().getSelectedItem().fullName()),
                "once loaded the first repository is selected");
        assertFalse(okDisabled(dialog), "so the button works without a click first");

        FxHarness.run(() -> list(dialog).getSelectionModel().clearSelection());
        assertTrue(okDisabled(dialog), "and with nothing selected there is nothing to use");
    }

    @Test
    void theFilterCountsTheRepositoriesAndNarrowsTheList() throws Exception {
        FxHarness.start();
        GitHubRepoPickerDialog dialog = buildDialog(fakeClient(200, """
                [{"full_name": "ada/engine", "clone_url": "x", "ssh_url": "x", "description": "Analytical", "private": false},
                 {"full_name": "ada/notes", "clone_url": "x", "ssh_url": "x", "description": "On Menabrea", "private": true},
                 {"full_name": "ada/loom", "clone_url": "x", "ssh_url": "x", "description": null, "private": false}]"""));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> items(dialog).size() == 3);
        assertEquals("Filter 3 repositories", FxHarness.call(() -> filter(dialog).getPromptText()));
        assertFalse(FxHarness.call(() -> filter(dialog).isDisabled()), "there is something to filter now");

        FxHarness.run(() -> filter(dialog).setText("menabrea"));
        assertEquals(List.of("ada/notes"), items(dialog).stream().map(GitHubRepository::fullName).toList(),
                "the description is matched as well as the name");
        assertEquals("ada/notes",
                FxHarness.call(() -> list(dialog).getSelectionModel().getSelectedItem().fullName()),
                "the selection moves to what is left");

        FxHarness.run(() -> filter(dialog).setText("babbage"));
        assertTrue(items(dialog).isEmpty());
        assertEquals("No repository matches \u201cbabbage\u201d.", placeholderText(dialog));
        assertTrue(okDisabled(dialog));
    }

    @Test
    void aFailedLoadSaysWhyInTheListAndLeavesNothingToFilter() throws Exception {
        FxHarness.start();
        GitHubRepoPickerDialog dialog = buildDialog(fakeClient(401, """
                {"message": "Bad credentials"}"""));
        assertTrue(FxHarness.call(() -> filter(dialog).isDisabled()), "nothing to filter while loading");

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
            String text = placeholderText(dialog);
            return text != null && text.startsWith("Could not load repositories: ");
        });
        assertTrue(okDisabled(dialog));
        assertTrue(FxHarness.call(() -> filter(dialog).isDisabled()));
    }

    @Test
    void anEmptyRepositoryListLeavesThePlaceholderVisibleAndTheButtonDisabled() throws Exception {
        FxHarness.start();
        GitHubRepoPickerDialog dialog = buildDialog(fakeClient(200, "[]"));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> "No repositories found.".equals(placeholderText(dialog)));

        assertTrue(okDisabled(dialog));
    }
}
