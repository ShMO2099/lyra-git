package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubRepository;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.ui.Icons;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.List;

/**
 * Lists the connected GitHub account's own repositories, for the clone dialog's
 * "Browse GitHub…" button (Wave 4, §4.3 Phase A's second half of the parity plan -
 * {@link GitHubClient#listRepositories} already existed and was already tested; this
 * is the UI that was still missing).
 *
 * <p>Requires {@link GitHubSession#isConnected()} - the caller is expected to check
 * that first and offer this only then, the same way the clone dialog's own "Browse…"
 * button for a local target directory is unconditional but this one is not.
 *
 * <p>The body - filter, framed rows, loading and failure in the frame - is
 * {@link RepoPicker}'s, shared with the other providers' pickers.
 */
public class GitHubRepoPickerDialog extends Dialog<GitHubRepository> {

    private final RepoPicker<GitHubRepository> picker;

    public GitHubRepoPickerDialog() {
        this(new GitHubClient());
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GitHubRepoPickerDialog(GitHubClient client) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Browse GitHub repositories");
        // "Use", not "Clone": choosing one fills in the Clone dialog's URL; the
        // clone itself starts from there.
        JfxDialog.header(this, Icons.cloneRepo(), JfxDialog.Tone.NEUTRAL, "Choose a GitHub repository",
                "The one you choose fills in the clone URL");
        ButtonType okType = new ButtonType("Use repository", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        picker = new RepoPicker<>(this, okType, RepoPicker.REPOSITORIES,
                repo -> new RepoPicker.Entry(repo.fullName(), repo.description(),
                        repo.isPrivate() ? List.of("Private") : List.of()));

        loadRepositories(client);
    }

    private void loadRepositories(GitHubClient client) {
        String token = GitHubSession.token();
        GitTaskRunner.queryProvider(
                () -> client.listRepositories(token),
                result -> {
                    if (result.isOk()) {
                        picker.loaded(result.orNull());
                    } else {
                        picker.failed(result.messageOrNull());
                    }
                },
                picker::failed);
    }
}
