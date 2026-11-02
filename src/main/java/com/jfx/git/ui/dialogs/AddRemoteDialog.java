package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaRepoRef;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubRepoRef;
import com.jfx.git.core.github.GitHubRepository;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * Adds a remote by name and URL - the foundational capability {@link GitBackend#addRemote}
 * has always had but no UI ever called, and the commercial clients' "fork and add remote" flow
 * builds directly on (Wave 5's fork-and-add-remote item in the parity plan).
 *
 * <p>When the current repository's {@code origin} resolves to a GitHub or Gitea repository
 * and that provider is connected, an extra button offers to fork that repository into
 * the connected account and add the fork as a remote in one step, matching the commercial
 * clients' documented behavior of completing both actions together rather than asking twice.
 *
 * <p>"Add remote" is the footer's action and runs in place ({@link JfxDialog#actInPlace}):
 * the dialog stays open after it, with the outcome at the left of the footer, because
 * adding one remote should not rule out adding a second in the same visit - which is also
 * why the other button says Close rather than Cancel. The fork is a second action in the
 * body and reports in the same place.
 */
public class AddRemoteDialog extends Dialog<Void> {

    private final GitBackend gitService;
    private final Runnable onAdded;
    private final TextField nameField = new TextField();
    private final TextField urlField = new TextField();
    /** The footer's action; it keeps the dialog open for another. */
    public static final ButtonType ADD = new ButtonType("Add remote", ButtonBar.ButtonData.OK_DONE);

    private final DialogParts.Field nameRow;
    private final DialogParts.Field urlRow;
    private final JfxDialog.FooterStatus status;
    private final Button forkButton = new Button("Fork and add");
    /** Whether the origin has been read and the fork offer decided; for tests. */
    private boolean originRead;
    private boolean forkOffered;

    public AddRemoteDialog(GitBackend gitService, Runnable onAdded) {
        this(gitService, onAdded, new GitHubClient(), new GiteaClient());
    }

    /** Package-visible seam: a test points the GitHub client at a local fake server. */
    AddRemoteDialog(GitBackend gitService, Runnable onAdded, GitHubClient gitHub) {
        this(gitService, onAdded, gitHub, new GiteaClient());
    }

    /** Package-visible seam: a test points either client at a local fake server instead of the real API. */
    AddRemoteDialog(GitBackend gitService, Runnable onAdded, GitHubClient gitHub, GiteaClient gitea) {
        this.gitService = gitService;
        this.onAdded = onAdded;

        JfxDialog.shell(this, JfxDialog.Size.SM, "Add remote");
        JfxDialog.header(this, Icons.remote(), JfxDialog.Tone.NEUTRAL, "Add a remote",
                "Another place this repository fetches from and pushes to");
        getDialogPane().getButtonTypes().addAll(ADD, ButtonType.CLOSE);

        nameField.setPromptText("upstream");
        urlField.setPromptText("https://github.com/owner/repo.git");
        nameRow = DialogParts.field("Name", nameField);
        urlRow = DialogParts.field("URL", urlField)
                .hint("HTTPS or SSH. Credentials come from your git config.");
        VBox content = DialogParts.stack(nameRow, urlRow);
        status = JfxDialog.footerStatus(this);
        JfxDialog.actInPlace(this, ADD, "Adding the remote…", this::addManualRemote);

        // The fork offer joins once the origin is known. It is read on the repository
        // lane: asking git here, on the FX thread, froze the dialog's opening.
        GitTaskRunner.query(gitService, () -> gitService.getRemoteUrl("origin"),
                url -> {
                    originRead = true;
                    Optional<ForkHost> host = forkHostFor(url, gitHub, gitea);
                    if (host.isPresent()) {
                        forkButton.setOnAction(e -> forkAndAdd(host.get()));
                        content.getChildren().add(forkRow(host.get()));
                        forkOffered = true;
                        // Arrives after the window opened at the height it had then.
                        JfxDialog.refit(this);
                    }
                },
                error -> originRead = true);

        getDialogPane().setContent(content);
    }

    private void addManualRemote(JfxDialog.Progress progress) {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        nameRow.error(name.isEmpty() ? "Name it: origin, upstream, a teammate's name." : null);
        urlRow.error(url.isEmpty() ? "Paste the repository's URL." : null);
        if (name.isEmpty() || url.isEmpty()) {
            progress.failed("");
            return;
        }
        GitTaskRunner.run(gitService, () -> gitService.addRemote(name, url), result -> {
            if (result.isOk()) {
                nameField.clear();
                urlField.clear();
                onAdded.run();
                progress.kept("Added " + name + ". Add another, or close.");
            } else {
                progress.failed(result.describe());
            }
        });
    }

    /** The offer to fork the origin, under the fields: what it does, and its button. */
    private javafx.scene.Node forkRow(ForkHost host) {
        Label says = new Label("Forks the origin into your " + host.name() + " account, or reuses "
                + "your fork, and adds it as the remote " + host.login() + ".");
        says.getStyleClass().add("t-caption");
        says.setWrapText(true);
        says.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(says, Priority.ALWAYS);
        forkButton.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(UiScale.GAP, says, forkButton);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return new VBox(UiScale.SNUG, DialogParts.section("Or fork it on " + host.name()), row);
    }

    /** A repository as forking needs it: who owns it, and where to clone it from. */
    private record Fork(String fullName, String cloneUrl) {
    }

    /** A provider call's answer, without the provider's own result type. */
    private record Answer<T>(T value, String error) {
        boolean ok() {
            return error == null;
        }
    }

    /**
     * One provider's fork calls for the origin repository.
     *
     * <h2>Why a seam this small</h2>
     * GitHub and Gitea fork the same way - list the forks, reuse the account's own, or
     * create one - and differ only in their clients' types. Reducing each to these two
     * calls lets one flow serve both instead of a copy per provider.
     *
     * @param name  the provider's name, for the progress line
     * @param login the connected account, which names both the fork's owner and the remote
     */
    private record ForkHost(String name, String login,
                            java.util.function.Supplier<Answer<java.util.List<Fork>>> listForks,
                            java.util.function.Supplier<Answer<Fork>> createFork) {
    }

    /** The fork host for a connected GitHub origin. The token is read on the provider pool, by the calls. */
    private static ForkHost gitHubForks(GitHubClient gitHub, GitHubRepoRef ref) {
        return new ForkHost("GitHub", GitHubSession.user() == null ? null : GitHubSession.user().login(),
                () -> {
                    var listed = gitHub.listForks(GitHubSession.token(), ref.owner(), ref.repo());
                    return listed.isOk()
                            ? new Answer<>(listed.orNull().stream().map(AddRemoteDialog::fork).toList(), null)
                            : new Answer<>(null, listed.messageOrNull());
                },
                () -> {
                    var created = gitHub.createFork(GitHubSession.token(), ref.owner(), ref.repo());
                    return created.isOk()
                            ? new Answer<>(fork(created.orNull()), null)
                            : new Answer<>(null, created.messageOrNull());
                });
    }

    /** The fork host for a connected Gitea origin; Gitea's fork calls mirror GitHub's. */
    private static ForkHost giteaForks(GiteaClient gitea, GiteaRepoRef ref) {
        return new ForkHost(HostingProvider.GITEA.displayName(),
                GiteaSession.user() == null ? null : GiteaSession.user().login(),
                () -> {
                    var listed = gitea.listForks(GiteaSession.token(), ref.owner(), ref.repo());
                    return listed.isOk()
                            ? new Answer<>(listed.orNull().stream()
                                    .map(r -> new Fork(r.fullName(), r.cloneUrl())).toList(), null)
                            : new Answer<>(null, listed.messageOrNull());
                },
                () -> {
                    var created = gitea.createFork(GiteaSession.token(), ref.owner(), ref.repo());
                    return created.isOk() && created.orNull() != null
                            ? new Answer<>(new Fork(created.orNull().fullName(), created.orNull().cloneUrl()), null)
                            : new Answer<>(null, created.isOk() ? "the server did not return the fork"
                                    : created.messageOrNull());
                });
    }

    private static Fork fork(GitHubRepository repository) {
        return new Fork(repository.fullName(), repository.cloneUrl());
    }

    /**
     * Which provider can fork the origin, if any: the one whose configured server it is
     * on, and only while that provider is connected - a fork lands in the connected
     * account, so without one there is nowhere to put it.
     */
    private static Optional<ForkHost> forkHostFor(String url, GitHubClient gitHub, GiteaClient gitea) {
        Optional<GitHubRepoRef> onGitHub = GitHubRepoRef.fromRemoteUrl(url);
        if (onGitHub.isPresent() && GitHubSession.isConnected()) {
            return Optional.of(gitHubForks(gitHub, onGitHub.get()));
        }
        Optional<GiteaRepoRef> onGitea = GiteaRepoRef.fromRemoteUrl(url);
        if (onGitea.isPresent() && GiteaSession.isConnected()) {
            return Optional.of(giteaForks(gitea, onGitea.get()));
        }
        return Optional.empty();
    }

    /** Reuses the account's existing fork when there is one, otherwise creates it, then adds it as a remote. */
    private void forkAndAdd(ForkHost host) {
        String login = host.login();
        forkButton.setDisable(true);
        status.busy("Checking for an existing fork…");

        GitTaskRunner.queryProvider(
                host.listForks()::get,
                listResult -> {
                    if (!listResult.ok()) {
                        forkFailed(listResult.error());
                        return;
                    }
                    Optional<Fork> existing = listResult.value().stream()
                            .filter(r -> r.fullName() != null && r.fullName().startsWith(login + "/"))
                            .findFirst();
                    if (existing.isPresent()) {
                        addFork(existing.get(), login);
                    } else {
                        status.busy("Forking on " + host.name() + "…");
                        GitTaskRunner.queryProvider(
                                host.createFork()::get,
                                createResult -> {
                                    if (createResult.ok()) {
                                        addFork(createResult.value(), login);
                                    } else {
                                        forkFailed(createResult.error());
                                    }
                                },
                                this::forkFailed);
                    }
                },
                this::forkFailed);
    }

    private void addFork(Fork fork, String remoteName) {
        status.busy("Adding remote " + remoteName + "…");
        GitTaskRunner.run(gitService, () -> gitService.addRemote(remoteName, fork.cloneUrl()), result -> {
            forkButton.setDisable(false);
            if (result.isOk()) {
                status.ok(result.describe());
                onAdded.run();
            } else {
                status.failed(result.describe());
            }
        });
    }

    private void forkFailed(String message) {
        forkButton.setDisable(false);
        status.failed("Could not fork: " + message);
    }

    /** Whether the fork-and-add-remote button was offered at all - only for a connected GitHub or Gitea origin. */
    boolean isForkOffered() {
        return forkOffered;
    }

    boolean isOriginReadForTest() {
        return originRead;
    }

    void addForTest(String name, String url) {
        nameField.setText(name);
        urlField.setText(url);
        ((Button) getDialogPane().lookupButton(ADD)).fire();
    }

    void forkForTest() {
        forkButton.fire();
    }

    String statusForTest() {
        return status.text();
    }
}
