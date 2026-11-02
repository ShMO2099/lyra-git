package com.jfx.git.ui.dialogs;

import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabProject;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.gitlab.GitLabUser;
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
 * Drives {@link GitLabRepoPickerDialog} against a real local HTTP server - the same
 * technique {@code GitHubClientTest}/{@code GitHubRepoPickerDialogTest} use.
 */
class GitLabRepoPickerDialogTest {

    private HttpServer server;

    @AfterEach
    void tidyUp() {
        if (server != null) {
            server.stop(0);
        }
        GitLabSession.disconnect();
    }

    private GitLabClient fakeClient(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.createContext("/projects", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        GitLabSession.connect("a-token", new GitLabUser("octocat", null));
        return new GitLabClient(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    private GitLabRepoPickerDialog buildDialog(GitLabClient client) {
        return FxHarness.call(() -> {
            GitLabRepoPickerDialog dialog = new GitLabRepoPickerDialog(client);
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            return dialog;
        });
    }

    @SuppressWarnings("unchecked")
    private ListView<GitLabProject> list(GitLabRepoPickerDialog dialog) {
        return (ListView<GitLabProject>) FxHarness.lookupAll(dialog.getDialogPane(), "list-view").get(0);
    }

    private Button okButton(GitLabRepoPickerDialog dialog) {
        return (Button) dialog.getDialogPane().getButtonTypes().stream()
                .filter(bt -> "Use project".equals(bt.getText()))
                .map(bt -> dialog.getDialogPane().lookupButton(bt))
                .findFirst().orElseThrow();
    }

    private List<GitLabProject> items(GitLabRepoPickerDialog dialog) {
        return FxHarness.call(() -> List.copyOf(list(dialog).getItems()));
    }

    private boolean okDisabled(GitLabRepoPickerDialog dialog) {
        return FxHarness.call(() -> okButton(dialog).isDisabled());
    }

    @Test
    void populatesTheListFromTheAuthenticatedUsersProjects() throws Exception {
        FxHarness.start();
        GitLabRepoPickerDialog dialog = buildDialog(fakeClient(200, """
                [{"path_with_namespace": "gitlab-org/gitlab-runner",
                  "http_url_to_repo": "https://gitlab.com/gitlab-org/gitlab-runner.git",
                  "ssh_url_to_repo": "git@gitlab.com:gitlab-org/gitlab-runner.git",
                  "description": "My project", "visibility": "public"}]"""));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !items(dialog).isEmpty());

        List<GitLabProject> projects = items(dialog);
        assertEquals(1, projects.size());
        assertEquals("gitlab-org/gitlab-runner", projects.get(0).pathWithNamespace());
    }

    /** What the list's frame says while it has no rows: loading, nothing found, or why it failed. */
    private String placeholderText(GitLabRepoPickerDialog dialog) {
        return FxHarness.call(() -> list(dialog).getPlaceholder() instanceof Parent box
                ? box.getChildrenUnmodifiable().stream().filter(Label.class::isInstance)
                        .map(n -> ((Label) n).getText()).findFirst().orElse(null)
                : null);
    }

    @Test
    void theUseButtonFollowsTheSelectionWhichStartsOnTheFirstProject() throws Exception {
        FxHarness.start();
        GitLabRepoPickerDialog dialog = buildDialog(fakeClient(200, """
                [{"path_with_namespace": "group/project", "http_url_to_repo": "x", "ssh_url_to_repo": "x",
                  "description": null, "visibility": "private"}]"""));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !items(dialog).isEmpty());
        assertEquals("group/project",
                FxHarness.call(() -> list(dialog).getSelectionModel().getSelectedItem().pathWithNamespace()),
                "once loaded the first project is selected");
        assertFalse(okDisabled(dialog), "so the button works without a click first");

        FxHarness.run(() -> list(dialog).getSelectionModel().clearSelection());
        assertTrue(okDisabled(dialog), "and with nothing selected there is nothing to use");
    }

    @Test
    void anEmptyProjectListLeavesThePlaceholderVisibleAndTheButtonDisabled() throws Exception {
        FxHarness.start();
        GitLabRepoPickerDialog dialog = buildDialog(fakeClient(200, "[]"));

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> "No projects found.".equals(placeholderText(dialog)));

        assertTrue(okDisabled(dialog));
    }
}
