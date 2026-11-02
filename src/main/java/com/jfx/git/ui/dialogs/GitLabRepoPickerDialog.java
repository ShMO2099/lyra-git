package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabProject;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.ui.Icons;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.List;

/**
 * Lists the connected GitLab account's own projects, for the clone dialog's "Browse
 * GitLab…" button - the GitLab half of the same parity-plan item as
 * {@code GitHubRepoPickerDialog}, with the same {@link RepoPicker} body.
 *
 * <p>Requires {@link GitLabSession#isConnected()} - the caller is expected to check
 * that first and offer this only then.
 */
public class GitLabRepoPickerDialog extends Dialog<GitLabProject> {

    private final RepoPicker<GitLabProject> picker;

    public GitLabRepoPickerDialog() {
        this(new GitLabClient());
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GitLabRepoPickerDialog(GitLabClient client) {
        JfxDialog.shell(this, JfxDialog.Size.SM, "Browse GitLab projects");
        // "Use", not "Clone": choosing one fills in the Clone dialog's URL; the
        // clone itself starts from there.
        JfxDialog.header(this, Icons.cloneRepo(), JfxDialog.Tone.NEUTRAL, "Choose a GitLab project",
                "The one you choose fills in the clone URL");
        ButtonType okType = new ButtonType("Use project", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        picker = new RepoPicker<>(this, okType, RepoPicker.PROJECTS,
                project -> new RepoPicker.Entry(project.pathWithNamespace(), project.description(),
                        visibilityTags(project.visibility())));

        loadProjects(client);
    }

    /**
     * GitLab's visibility is three-way, and the tag says which: "Internal" is visible to
     * anyone signed in to the instance, which is neither public nor private.
     */
    private static List<String> visibilityTags(String visibility) {
        if ("private".equals(visibility)) return List.of("Private");
        if ("internal".equals(visibility)) return List.of("Internal");
        return List.of();
    }

    private void loadProjects(GitLabClient client) {
        String token = GitLabSession.token();
        GitTaskRunner.queryProvider(
                () -> client.listProjects(token),
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
