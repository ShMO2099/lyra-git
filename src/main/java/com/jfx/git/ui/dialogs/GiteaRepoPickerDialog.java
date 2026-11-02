package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaRepository;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.ui.Icons;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the connected Gitea or Forgejo account's repositories for the clone dialog's
 * "Browse…" menu, and answers with the one picked - whose HTTPS clone URL the clone
 * dialog then fills in, as it does for the other providers' pickers.
 *
 * <p>The same {@link RepoPicker} body as {@link GitHubRepoPickerDialog}; Gitea also says
 * which repositories are forks, so those rows carry a "Fork" tag. The caller offers it
 * only while {@link GiteaSession#isConnected()}.
 */
public class GiteaRepoPickerDialog extends Dialog<GiteaRepository> {

    private final RepoPicker<GiteaRepository> picker;

    public GiteaRepoPickerDialog() {
        this(new GiteaClient());
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GiteaRepoPickerDialog(GiteaClient client) {
        JfxDialog.shell(this, JfxDialog.Size.SM,
                "Browse " + HostingProvider.GITEA.displayName() + " repositories");
        // "Use", not "Clone": choosing one fills in the Clone dialog's URL; the
        // clone itself starts from there.
        JfxDialog.header(this, Icons.cloneRepo(), JfxDialog.Tone.NEUTRAL,
                "Choose a " + HostingProvider.GITEA.displayName() + " repository",
                "The one you choose fills in the clone URL");
        ButtonType okType = new ButtonType("Use repository", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        picker = new RepoPicker<>(this, okType, RepoPicker.REPOSITORIES,
                repo -> new RepoPicker.Entry(repo.fullName(), repo.description(), tags(repo)));

        loadRepositories(client);
    }

    private static List<String> tags(GiteaRepository repo) {
        List<String> tags = new ArrayList<>(2);
        if (repo.isPrivate()) tags.add("Private");
        if (repo.fork()) tags.add("Fork");
        return tags;
    }

    /**
     * Reads every page on the provider pool. The token is read there too: an OAuth token
     * near expiry is refreshed by whoever asks for it, and that must not be the FX thread.
     */
    private void loadRepositories(GiteaClient client) {
        GitTaskRunner.queryProvider(
                () -> client.listRepositories(GiteaSession.token()),
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
