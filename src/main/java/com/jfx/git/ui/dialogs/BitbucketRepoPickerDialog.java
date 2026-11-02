package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.bitbucket.BitbucketRepository;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.ui.Icons;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.List;

/**
 * Lists the connected Bitbucket account's own repositories, across every workspace it
 * belongs to, for the clone dialog's "Browse Bitbucket…" button - the Bitbucket half of
 * the same parity-plan item as {@code GitHubRepoPickerDialog}, with the same
 * {@link RepoPicker} body.
 *
 * <p>Requires {@link BitbucketSession#isConnected()} - the caller is expected to check
 * that first and offer this only then.
 */
public class BitbucketRepoPickerDialog extends Dialog<BitbucketRepository> {

    private final RepoPicker<BitbucketRepository> picker;

    public BitbucketRepoPickerDialog() {
        this(new BitbucketClient());
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    BitbucketRepoPickerDialog(BitbucketClient client) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Browse Bitbucket repositories");
        // "Use", not "Clone": choosing one fills in the Clone dialog's URL; the
        // clone itself starts from there.
        JfxDialog.header(this, Icons.cloneRepo(), JfxDialog.Tone.NEUTRAL, "Choose a Bitbucket repository",
                "The one you choose fills in the clone URL");
        ButtonType okType = new ButtonType("Use repository", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        picker = new RepoPicker<>(this, okType, RepoPicker.REPOSITORIES,
                repo -> new RepoPicker.Entry(repo.fullName(), repo.description(),
                        repo.isPrivate() ? List.of("Private") : List.of()));

        loadRepositories(client);
    }

    private void loadRepositories(BitbucketClient client) {
        String token = BitbucketSession.token();
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
