package com.jfx.git.ui.settings;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ProviderHosts;
import com.jfx.git.core.Settings;
import com.jfx.git.core.TokenStore;
import com.jfx.git.core.UrlOpener;
import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.OAuthClients;
import com.jfx.git.core.hosting.OAuthException;
import com.jfx.git.core.hosting.OAuthSpec;
import com.jfx.git.core.hosting.OAuthTokenClient;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.core.hosting.ProviderGitCredentials;
import com.jfx.git.core.hosting.ProviderSessions;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;
import com.jfx.git.ui.dialogs.DialogParts;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.jfx.git.ui.settings.SettingsLayout.hint;
import static com.jfx.git.ui.settings.SettingsLayout.page;
import static com.jfx.git.ui.settings.SettingsLayout.section;

/**
 * Cloud provider integrations: GitHub, GitLab, Bitbucket and Gitea/Forgejo - the
 * Integrations section of the Settings tab.
 *
 * <h2>Two ways in</h2>
 * "Sign in…" goes through the browser (see {@link com.jfx.git.ui.dialogs.ProviderSignInDialog})
 * wherever an OAuth application is registered for the server - the shipped ones for the
 * public servers, or one the user names in "Server…" for their own. "Use a token…" - or
 * "Connect…" where there is no application - takes a pasted personal access token, which
 * every provider still supports as a first-class way in and which is the only way to limit
 * what the application may do on a Forgejo server. Either credential is validated against
 * the provider's own {@code /user} before anything is recorded, and lives in
 * {@link ProviderSessions} for the process's lifetime. "Remember" (on by default) keeps it
 * in {@link TokenStore} - the desktop keyring on Linux, DPAPI ciphertext on Windows - so
 * the next launch reconnects; it never reaches the settings file in clear.
 *
 * <h2>Git over HTTPS</h2>
 * A connected account also answers git's HTTPS logins for its own server (see
 * {@link ProviderGitCredentials}). One switch below the cards turns that off for all of
 * them - the rare user whose git account differs from the connected one can also name
 * theirs in a remote's URL, which always wins.
 *
 * <h2>A page, not a window</h2>
 * This was the Integrations window. It is a pane now so that it can sit in the Settings
 * tab beside the other settings; the sign-in, token and server prompts it opens are still
 * dialogs, because each asks one question and returns an answer. The page outlives any
 * single visit, so it listens to the sessions until {@link #dispose()} rather than until
 * a window hides.
 *
 * <p>The cards share one shape ({@link ProviderRow}) rather than being copied per provider
 * the way the picker/PR/issue dialogs are: those are separate top-level classes reused from
 * several call sites and worth keeping independently readable, but these cards live and
 * change together on one page, so copies of the connect/disconnect/status machinery
 * would be copies of the same bug waiting to diverge.
 */
public final class IntegrationsPane extends VBox {

    private final Map<HostingProvider, ProviderRow> rows = new EnumMap<>(HostingProvider.class);

    /** Brings every row up to date when a session connects or a restore finishes. */
    private final javafx.beans.InvalidationListener sessionsChanged = obs -> refreshRows();

    /**
     * Writes a credential to the platform store; returns whether it was kept. A field so a
     * test can stand in for the store. Runs on the provider pool, never the FX thread: on
     * Windows it starts PowerShell.
     */
    static java.util.function.BiPredicate<String, String> tokenWriter = TokenStore::store;

    private void refreshRows() {
        rows.values().forEach(ProviderRow::refreshUnlessBusy);
    }

    /** The GitHub row's status text, for tests. */
    String githubStatusForTest() {
        return rows.get(HostingProvider.GITHUB).status.getText();
    }

    /** {@code provider}'s main action button, for tests. */
    Button actionForTest(HostingProvider provider) {
        return rows.get(provider).action;
    }

    /** The token prompt's field, the "Remember" box, and the server prompt's two fields. */
    public static final String TOKEN_FIELD_ID = "integration-token";
    public static final String REMEMBER_ID = "integration-remember";
    public static final String SERVER_URL_ID = "integration-server-url";
    public static final String CLIENT_ID_FIELD_ID = "integration-client-id";

    /** {@code provider}'s token prompt, not shown: a test shows it and fills it in. */
    Dialog<ButtonType> tokenDialogForTest(HostingProvider provider) {
        return rows.get(provider).tokenDialog();
    }

    /** {@code provider}'s server prompt, not shown. */
    Dialog<?> serverDialogForTest(HostingProvider provider) {
        return rows.get(provider).serverDialog();
    }

    /**
     * Builds its clients per request rather than once.
     *
     * <p>A client fixes its base URI at construction, and the GitHub/GitLab/Gitea server
     * can be changed from this very page - so a client captured when the page was built
     * would still be talking to the previous server when the user connects a moment later.
     */
    public IntegrationsPane() {
        this(null, null, null, null);
    }

    /** Package-visible seam: a test points the GitHub client at a local fake server instead of the real API. */
    IntegrationsPane(GitHubClient gitHub) {
        this(gitHub, null, null, null);
    }

    IntegrationsPane(GitHubClient gitHub, GitLabClient gitLab, BitbucketClient bitbucket) {
        this(gitHub, gitLab, bitbucket, null);
    }

    /**
     * Package-visible seam: a test points any of these at a local fake server instead of
     * the real API. A {@code null} means "build one when it is needed", which is what
     * picks up a server the user changed while this page was open.
     */
    IntegrationsPane(GitHubClient injectedGitHub, GitLabClient injectedGitLab,
                     BitbucketClient injectedBitbucket, GiteaClient injectedGitea) {
        Supplier<GitHubClient> gitHub = injectedGitHub != null ? () -> injectedGitHub : GitHubClient::new;
        Supplier<GitLabClient> gitLab = injectedGitLab != null ? () -> injectedGitLab : GitLabClient::new;
        Supplier<BitbucketClient> bitbucket =
                injectedBitbucket != null ? () -> injectedBitbucket : BitbucketClient::new;
        Supplier<GiteaClient> gitea = injectedGitea != null ? () -> injectedGitea : GiteaClient::new;

        rows.put(HostingProvider.GITHUB, new ProviderRow(HostingProvider.GITHUB, "personal access token",
                () -> handle(GitHubSession.user() == null ? null : GitHubSession.user().login()),
                validator(token -> gitHub.get().fetchAuthenticatedUser(token),
                        r -> r.isOk(), (c, r) -> GitHubSession.connect(c, r.orNull()), r -> r.messageOrNull()),
                new SelfHostedServer(ProviderHosts::githubSetting, ProviderHosts::setGithub,
                        "https://github.example.com")));
        rows.put(HostingProvider.GITLAB, new ProviderRow(HostingProvider.GITLAB, "personal access token",
                () -> handle(GitLabSession.user() == null ? null : GitLabSession.user().username()),
                validator(token -> gitLab.get().fetchAuthenticatedUser(token),
                        r -> r.isOk(), (c, r) -> GitLabSession.connect(c, r.orNull()), r -> r.messageOrNull()),
                new SelfHostedServer(ProviderHosts::gitlabSetting, ProviderHosts::setGitlab,
                        "https://gitlab.example.com")));
        rows.put(HostingProvider.BITBUCKET, new ProviderRow(HostingProvider.BITBUCKET, "API token",
                () -> handle(BitbucketSession.user() == null ? null : BitbucketSession.user().handle()),
                validator(token -> bitbucket.get().fetchAuthenticatedUser(token),
                        r -> r.isOk(), (c, r) -> BitbucketSession.connect(c, r.orNull()), r -> r.messageOrNull()),
                null));
        rows.put(HostingProvider.GITEA, new ProviderRow(HostingProvider.GITEA, "access token",
                () -> handle(GiteaSession.user() == null ? null : GiteaSession.user().login()),
                validator(token -> gitea.get().fetchAuthenticatedUser(token),
                        r -> r.isOk(), (c, r) -> GiteaSession.connect(c, r.orNull()), r -> r.messageOrNull()),
                new SelfHostedServer(ProviderHosts::giteaSetting, ProviderHosts::setGitea,
                        "https://gitea.example.com")));

        VBox providers = new VBox(UiScale.STEP);
        rows.values().forEach(row -> providers.getChildren().add(row.row()));
        // As wide as the other pages' forms at most: a card stretched across a wide
        // window put its buttons a screen's width away from the name they act on.
        providers.setMaxWidth(760);

        // A remembered token is restored in the background after the window opens; a
        // page built before it landed used to say "Not connected" until rebuilt.
        com.jfx.git.core.IntegrationEvents.changesProperty().addListener(sessionsChanged);
        com.jfx.git.core.IntegrationEvents.restoringProperty().addListener(sessionsChanged);

        // The caveat that used to be buried in a six-sentence header: short enough to
        // survive, and kept to one line under the cards.
        Label caveat = hint("Issues are read-only: creating, commenting and closing are not implemented.");

        VBox content = page("Integrations");
        content.getChildren().addAll(
                hint("Connect an account to browse, clone and check out pull requests without leaving Lyra."),
                section("Accounts"),
                providers,
                section("Git over HTTPS"),
                gitSection(),
                caveat);
        getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);
    }

    /**
     * Stops following the sessions. Called when the Settings tab closes; a page left
     * listening would be kept alive by the static event properties for the rest of the
     * process.
     */
    public void dispose() {
        com.jfx.git.core.IntegrationEvents.changesProperty().removeListener(sessionsChanged);
        com.jfx.git.core.IntegrationEvents.restoringProperty().removeListener(sessionsChanged);
    }

    /**
     * The one switch for git over HTTPS, and what it does and does not cover.
     *
     * <p>Stored per provider ({@link ProviderGitCredentials#setUseForGit}), set for all of
     * them here: four checkboxes, one per card, did not fit the window, and nobody asked
     * for GitHub's account on push but not GitLab's. A remote whose URL names a user keeps
     * that user regardless.
     */
    private Node gitSection() {
        CheckBox useForGit = new CheckBox("Use connected accounts for git push, pull and fetch over HTTPS");
        boolean allOn = true;
        for (HostingProvider p : HostingProvider.values()) allOn &= ProviderGitCredentials.useForGit(p);
        useForGit.setSelected(allOn);
        useForGit.selectedProperty().addListener((obs, was, now) -> {
            for (HostingProvider p : HostingProvider.values()) ProviderGitCredentials.setUseForGit(p, now);
        });
        Label note = new Label("Each account is used only on its own server. SSH remotes keep using your SSH keys.");
        note.getStyleClass().add("t-caption");
        note.setWrapText(true);
        return new VBox(2, useForGit, note);
    }

    private static String handle(String login) {
        return "Connected as @" + (login == null || login.isBlank() ? "?" : login);
    }

    /**
     * Checks a credential against a provider's {@code /user} on the provider pool and, once
     * it checks out, connects that provider's session.
     */
    @FunctionalInterface
    private interface Validator {
        /**
         * @param wanted asked once the provider has answered and before anything is connected:
         *               false when whoever asked has stopped waiting - a prompt cancelled
         *               while it checked - and nothing is connected
         * @param done   how it ended, on the FX thread; always called
         */
        void check(ProviderCredential credential, BooleanSupplier wanted, Consumer<Checked> done);
    }

    /**
     * How checking a credential ended: connected, failed with the provider's or the
     * network's words, or dropped because nobody was waiting for it any more.
     *
     * <p>The outcome is handed back rather than reported here, because where to say a
     * refusal depends on who asked: the token prompt says it under its field and stays
     * open, a browser sign-in (which has no form left to correct) in an error dialog.
     *
     * @param reason why it failed, blank when nothing said; null when it did not fail
     */
    private record Checked(boolean connected, String reason) {
        static final Checked CONNECTED = new Checked(true, null);
        static final Checked DROPPED = new Checked(false, null);

        static Checked failed(String reason) {
            return new Checked(false, reason == null ? "" : reason);
        }

        boolean failed() {
            return reason != null;
        }
    }

    private static <R> Validator validator(Function<String, R> fetchUser, Predicate<R> ok,
                                           BiConsumer<ProviderCredential, R> connect,
                                           Function<R, String> message) {
        return (credential, wanted, done) -> GitTaskRunner.queryProvider(
                () -> fetchUser.apply(credential.accessToken()),
                result -> {
                    if (!ok.test(result)) {
                        done.accept(Checked.failed(message.apply(result)));
                    } else if (!wanted.getAsBoolean()) {
                        done.accept(Checked.DROPPED);
                    } else {
                        connect.accept(credential, result);
                        done.accept(Checked.CONNECTED);
                    }
                },
                error -> done.accept(Checked.failed(error)));
    }

    /** Package-visible: a test drives GitHub's connect flow directly, bypassing the nested prompt dialog. */
    void connectWith(String token) {
        connectWith(HostingProvider.GITHUB, ProviderCredential.token(token), false);
    }

    /** Package-visible: GitHub's connect flow with "Remember this token" as given. */
    void connectWith(String token, boolean remember) {
        connectWith(HostingProvider.GITHUB, ProviderCredential.token(token), remember);
    }

    /** Package-visible: a test drives GitLab's connect flow directly, bypassing the nested prompt dialog. */
    void connectGitLabWith(String token) {
        connectWith(HostingProvider.GITLAB, ProviderCredential.token(token), false);
    }

    /** Package-visible: a test drives Bitbucket's connect flow directly, bypassing the nested prompt dialog. */
    void connectBitbucketWith(String token) {
        connectWith(HostingProvider.BITBUCKET, ProviderCredential.token(token), false);
    }

    /** Package-visible: any provider's connect flow with any credential - what sign-in ends in. */
    void connectWith(HostingProvider provider, ProviderCredential credential, boolean remember) {
        rows.get(provider).connectWith(credential, remember);
    }

    /**
     * Connecting failed. A refused credential - the server's 401, or its "Bad credentials"
     * - gets its own title and a sentence saying what usually causes it, with the
     * server's words behind Show Details; anything else (the network, a rate limit) is
     * said in the words it came with, which are already meant for the user.
     */
    private static void showConnectError(String provider, String message) {
        String said = message == null || message.isBlank() ? null : message.strip();
        boolean refused = said != null && REFUSED.matcher(said).find();
        var failed = com.jfx.git.ui.dialogs.Dialogs.error("Connect " + provider)
                .icon(com.jfx.git.ui.Icons.key());
        if (refused) {
            failed.title(provider + " did not accept the sign-in")
                    .message("The token may have expired or been revoked, or lack the scopes Lyra"
                            + " asks for. Connect again with a new one.")
                    .details(said);
        } else {
            failed.title("Could not connect to " + provider)
                    .message(said == null ? provider + " gave no reason." : said);
        }
        failed.answer();
    }

    /** How a server says the credential itself is no good. */
    private static final java.util.regex.Pattern REFUSED = java.util.regex.Pattern.compile(
            "(?i)\\b401\\b|bad credentials|unauthori[sz]ed|rejected the token");

    /** {@code text} ending as a sentence does. */
    private static String sentence(String text) {
        String said = text.strip();
        return said.matches(".*[.!?]$") ? said : said + ".";
    }

    /**
     * A provider that can be pointed at a server of the user's own.
     *
     * <p>Bitbucket has no instance of this on purpose: Bitbucket Data Center speaks a
     * different REST API from Bitbucket Cloud, so a URL field there would produce
     * failures rather than support. See {@link com.jfx.git.core.ProviderHosts}.
     *
     * @param example an address of the kind it takes, for the field's placeholder
     */
    private record SelfHostedServer(Supplier<String> reader,
                                    java.util.function.Consumer<String> writer,
                                    String example) {

        /** The configured URL, never null - blank means "the public host". */
        String url() {
            String stored = reader.get();
            return stored == null ? "" : stored;
        }

        void set(String url) {
            writer.accept(url);
        }
    }

    /**
     * One provider's card: a status chip, its actions, and the connect/disconnect/status
     * glue behind them.
     */
    private static final class ProviderRow {

        private final HostingProvider provider;
        private final String tokenPromptHint;
        private final Supplier<String> connectedAsLabel;
        /** Validates a credential against the real API and connects the session if it checks out; always calls back. */
        private final Validator validateAndConnect;

        /** How to read and write this provider's server URL, or null when it has none. */
        private final SelfHostedServer host;

        private final Label status = new Label();
        /** Connect, Sign in or Disconnect, whichever the state allows. */
        private final Button action = new Button();
        /** "Use a token…" beside Sign in; hidden when sign-in is not offered or when connected. */
        private final Button tokenAction = new Button("Use a token…");
        {
            action.getStyleClass().add("card-action");
            tokenAction.getStyleClass().addAll("card-action", "btn-ghost");
            tokenAction.setOnAction(e -> promptForToken());
        }

        ProviderRow(HostingProvider provider, String tokenPromptHint, Supplier<String> connectedAsLabel,
                    Validator validateAndConnect, SelfHostedServer host) {
            this.provider = provider;
            this.tokenPromptHint = tokenPromptHint;
            this.connectedAsLabel = connectedAsLabel;
            this.validateAndConnect = validateAndConnect;
            this.host = host;
            status.getStyleClass().add("text-muted");
            refresh();
        }

        private String name() {
            return provider.displayName();
        }

        /** Per server, not per provider: see ProviderHosts.tokenAccount. Read when used. */
        private String storeKey() {
            return provider.tokenAccount();
        }

        private boolean isConnected() {
            return ProviderSessions.isConnected(provider);
        }

        /** How to sign in to the configured server through the browser, if anywhere. */
        private Optional<OAuthSpec> signInSpec() {
            return OAuthClients.shipped().spec(provider, provider.web());
        }

        /** Sets the status text and the actions available from whichever state this is. */
        void refresh() {
            // The chip carries the state as colour as well as words, so a glance down the
            // cards answers "which of these am I signed in to" without reading.
            status.getStyleClass().removeAll("state-chip-ok", "state-chip-warn");
            action.getStyleClass().remove("btn-primary");
            if (isConnected()) {
                status.getStyleClass().add("state-chip-ok");
                status.setText(connectedAsLabel.get());
                action.setText("Disconnect");
                action.setOnAction(e -> disconnect());
                showTokenAction(false);
                return;
            }
            // A remembered credential may still be on its way back; saying "Not connected"
            // then read as "it was not remembered".
            status.setText(com.jfx.git.core.IntegrationEvents.isRestoring() ? "Reconnecting…" : "Not connected");
            Optional<OAuthSpec> spec = signInSpec();
            if (spec.isPresent()) {
                action.setText("Sign in…");
                action.getStyleClass().add("btn-primary");
                action.setOnAction(e -> signIn());
                showTokenAction(true);
            } else {
                action.setText("Connect…");
                action.setOnAction(e -> promptForToken());
                showTokenAction(false);
            }
        }

        /** The card's action buttons; null until {@link #row()} has built the card. */
        private HBox actions;

        /**
         * Puts "Use a token…" beside Sign in, or takes it away - out of the card, not
         * hidden in it, so nothing measures a button nobody can see.
         */
        private void showTokenAction(boolean show) {
            if (actions == null) return;
            boolean shown = actions.getChildren().contains(tokenAction);
            if (show && !shown) {
                actions.getChildren().add(actions.getChildren().indexOf(action), tokenAction);
            } else if (!show && shown) {
                actions.getChildren().remove(tokenAction);
            }
        }

        /** True from Connect until its answer is in; a refresh then would hide "Connecting…". */
        private boolean busy;

        void refreshUnlessBusy() {
            if (!busy) refresh();
        }

        /**
         * Disconnects here and, for a browser sign-in, at the provider where it can be:
         * GitLab revokes on request; the others only let the user revoke the application
         * on their own settings page, which is offered.
         */
        private void disconnect() {
            ProviderCredential credential = ProviderSessions.credential(provider);
            ProviderSessions.disconnect(provider);
            // Disconnecting means disconnected, including next launch.
            TokenStore.remove(storeKey());
            refresh();
            if (credential == null || !credential.isOAuth()) return;
            Optional<OAuthSpec> spec = signInSpec();
            if (spec.isPresent() && spec.get().revokeUri() != null) {
                OAuthSpec revoking = spec.get();
                GitTaskRunner.queryProvider(() -> {
                    try {
                        new OAuthTokenClient().revoke(revoking, credential.accessToken());
                        return true;
                    } catch (OAuthException e) {
                        return false;
                    }
                }, revoked -> { }, error -> { });
                return;
            }
            String page = authorizedAppsPage(credential);
            if (page == null) return;
            ButtonType open = new ButtonType("Open " + name() + " settings", ButtonBar.ButtonData.OK_DONE);
            ButtonType answer = com.jfx.git.ui.dialogs.Dialogs.info("Disconnect " + name())
                    .icon(com.jfx.git.ui.Icons.key())
                    .title("Signed out of " + name() + " here")
                    .message("Lyra stays authorized on " + provider.web().getHost()
                            + " until you revoke it there, in your " + name() + " settings.")
                    .buttons(open, ButtonType.CLOSE)
                    // A notice relabels its confirming button Close; this one opens the page.
                    .action(open.getText())
                    .answer();
            if (answer == open) {
                UrlOpener.open(page);
            }
        }

        /** Where the user revokes this application on the provider, or null. */
        private String authorizedAppsPage(ProviderCredential credential) {
            String web = trimSlash(provider.web().toString());
            return switch (provider) {
                case GITHUB -> credential.clientId() == null ? web + "/settings/applications"
                        : web + "/settings/connections/applications/" + credential.clientId();
                case BITBUCKET -> "https://bitbucket.org/account/settings/app-authorizations/";
                case GITEA -> web + "/user/settings/applications";
                default -> null;
            };
        }

        private static String trimSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }

        /** Signs in through the browser, then connects with what came back, as a token would. */
        private void signIn() {
            signInSpec().ifPresent(spec -> com.jfx.git.ui.dialogs.ProviderSignInDialog.signIn(provider, provider.web(), spec)
                    .ifPresent(signedIn -> connectWith(signedIn.credential(), signedIn.remember())));
        }

        /** Asks for a token and connects with it; the prompt closes once the provider has accepted it. */
        private void promptForToken() {
            tokenDialog().showAndWait();
        }

        /**
         * The token prompt.
         *
         * <p>Connect checks the token with the provider before the prompt closes
         * ({@link JfxDialog#actInPlace}). It used to close first and check after, so a
         * mistyped or expired token opened an error over the page and the token had to be
         * pasted again into a new prompt; now the refusal is said under the field with the
         * token still in it. Cancel while it checks drops the answer and connects nothing.
         */
        Dialog<ButtonType> tokenDialog() {
            Dialog<ButtonType> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, "Connect " + name());
            JfxDialog.header(dialog, Icons.lock(), JfxDialog.Tone.NEUTRAL, "Connect " + name() + " with a token",
                    "`" + provider.web().getHost() + "`");
            ButtonType connect = new ButtonType("Connect", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(connect, ButtonType.CANCEL);

            DialogParts.Secret token = new DialogParts.Secret(tokenPromptHint);
            token.hidden.setId(TOKEN_FIELD_ID);
            DialogParts.Field tokenRow = DialogParts.field(capitalised(tokenPromptHint), token.node)
                    .trailing(token.reveal)
                    .hint("Checked with " + name() + " before anything is kept.");

            // On by default, as other clients do it: unticked, a connection lasted until
            // the app closed, and the next launch read as the token having been lost. Only
            // offered where there is somewhere proper to put it - a machine with no store
            // this app can reach gets a disabled box and the reason.
            CheckBox remember = new CheckBox("Remember this token");
            remember.setId(REMEMBER_ID);
            boolean storable = TokenStore.isAvailable();
            remember.setSelected(storable);
            remember.setDisable(!storable);
            String where = storable
                    ? "Kept " + TokenStore.describeLocation() + "; never written as plain text."
                    : "Not offered: " + TokenStore.describeLocation() + ".";
            dialog.getDialogPane().setContent(DialogParts.stack(tokenRow, DialogParts.toggle(remember, where)));

            Button connectButton = (Button) dialog.getDialogPane().lookupButton(connect);
            Runnable validate = () -> connectButton.setDisable(token.text().isBlank());
            token.hidden.textProperty().addListener((obs, old, text) -> {
                validate.run();
                token.error(tokenRow, null);   // a new token; the last one's refusal is not about it
            });
            validate.run();

            JfxDialog.actInPlace(dialog, connect, "Checking the token with " + name() + "…", progress ->
                    connect(ProviderCredential.token(token.text().trim()), remember.isSelected(),
                            () -> !progress.cancelled(),
                            checked -> {
                                if (progress.cancelled()) {
                                    return;
                                }
                                if (checked.connected()) {
                                    progress.succeeded();
                                    return;
                                }
                                String said = checked.reason() == null || checked.reason().isBlank()
                                        ? null : checked.reason().strip();
                                if (said != null && REFUSED.matcher(said).find()) {
                                    token.error(tokenRow, name() + " did not accept this token. It may have"
                                            + " expired or been revoked, or lack the scopes Lyra asks for.");
                                    progress.failed(sentence(said));
                                } else {
                                    progress.failed(said == null ? "Could not connect to " + name() + ". It gave no reason."
                                            : "Could not connect to " + name() + ": " + sentence(said));
                                }
                            }));
            Platform.runLater(token.hidden::requestFocus);
            return dialog;
        }

        /**
         * Validates the credential and connects with it, saying a refusal in an error dialog:
         * for a browser sign-in, which has no form left open to say it in.
         */
        void connectWith(ProviderCredential credential, boolean remember) {
            connect(credential, remember, () -> true, checked -> {
                if (checked.failed()) {
                    showConnectError(name(), checked.reason());
                }
            });
        }

        /**
         * Validates the credential and, only once it has been accepted, remembers it if
         * asked. Storing one the API has already rejected would leave the user reconnecting
         * every launch against a credential that was never going to work.
         *
         * @param wanted see {@link Validator#check}
         * @param done   how it ended, once the row shows it
         */
        private void connect(ProviderCredential credential, boolean remember, BooleanSupplier wanted,
                             Consumer<Checked> done) {
            busy = true;
            action.setDisable(true);
            status.setText("Connecting…");
            validateAndConnect.check(credential, wanted, checked -> {
                if (!checked.connected() || !remember || !isConnected()) {
                    finishConnect(null);
                    done.accept(checked);
                    return;
                }
                // Stored off the FX thread - it starts PowerShell on Windows - and the
                // result said after the row has refreshed, not before: refresh used to
                // overwrite "could not be remembered" with "Connected as", so a token that
                // was never saved looked exactly like one that was.
                String account = storeKey();
                GitTaskRunner.queryProvider(() -> tokenWriter.test(account, credential.toStored()),
                        kept -> {
                            // Marked so a refreshed OAuth token is stored again in its place.
                            if (kept) ProviderSessions.setRemembered(provider, true);
                            finishConnect(kept ? null
                                    : "Connected, but the sign-in could not be remembered - you will"
                                            + " be asked again next time.");
                            done.accept(checked);
                        },
                        error -> {
                            finishConnect("Connected, but the sign-in could not be"
                                    + " remembered: " + error);
                            done.accept(checked);
                        });
            });
        }

        private static String capitalised(String text) {
            return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }

        private void finishConnect(String warning) {
            busy = false;
            action.setDisable(false);
            refresh();
            if (warning != null) {
                status.getStyleClass().remove("state-chip-ok");
                status.getStyleClass().add("state-chip-warn");
                status.setText(warning);
                status.setWrapText(true);
                status.setMaxWidth(260);
            }
        }

        /**
         * One provider, as a card.
         *
         * <p>Was a row of four peers - a fixed-width name, a sentence of status and two
         * buttons - which is why it truncated: nothing in it said which part could give
         * way. A card has a rank. The mark and the name identify the provider, the host
         * beneath the name is the detail you only need when it is not the public one, and
         * the state is a chip rather than a sentence, so "connected" is a colour before it
         * is a word.
         */
        HBox row() {
            Label mark = new Label(initials());
            mark.getStyleClass().addAll("provider-mark", "provider-mark-" + provider.id());

            Label nameLabel = new Label(name());
            nameLabel.getStyleClass().add("provider-name");
            Label hostLabel = new Label(host == null ? publicHost() : hostSummary());
            hostLabel.getStyleClass().add("t-caption");
            VBox identity = new VBox(1, nameLabel, hostLabel);
            identity.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(identity, Priority.ALWAYS);
            identity.setMinWidth(Region.USE_PREF_SIZE);

            status.getStyleClass().add("state-chip");
            status.setMinWidth(Region.USE_PREF_SIZE);

            actions = new HBox(UiScale.STEP);
            actions.setAlignment(Pos.CENTER_RIGHT);
            if (host != null) {
                Button server = new Button("Server…");
                server.getStyleClass().add("card-action");
                server.setOnAction(e -> {
                    promptForServer();
                    hostLabel.setText(hostSummary());
                });
                actions.getChildren().add(server);
            }
            actions.getChildren().add(action);
            refresh();

            HBox box = new HBox(UiScale.GAP, mark, identity, status, actions);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("provider-row");
            return box;
        }

        /**
         * The two letters that stand for this provider - not the first two of its name,
         * which made GitHub, GitLab and Gitea all "GI".
         */
        private String initials() {
            return switch (provider) {
                case GITHUB -> "GH";
                case GITLAB -> "GL";
                case BITBUCKET -> "BB";
                case GITEA -> "GT";
            };
        }

        /** The public server, shown when no other is configured. */
        private String publicHost() {
            return switch (provider) {
                case GITHUB -> "github.com";
                case GITLAB -> "gitlab.com";
                case BITBUCKET -> "bitbucket.org";
                case GITEA -> "codeberg.org";
            };
        }

        /** The configured server, or the public one with a note that it can be changed. */
        private String hostSummary() {
            String url = host.url();
            if (url == null || url.isBlank()) {
                return publicHost() + "  ·  self-hosted supported";
            }
            return url.replaceFirst("^https?://", "").replaceFirst("/$", "");
        }

        /**
         * Points this provider at a self-hosted server - GitHub Enterprise, a self-managed
         * GitLab, a Gitea or Forgejo - and optionally names the OAuth application registered
         * there, which is what makes "Sign in…" available on it. Blank goes back to the
         * public server.
         *
         * <p>Changing the server disconnects: the credential in hand was issued by whichever
         * server the user was talking to a moment ago, and it means nothing to a different
         * one.
         */
        private void promptForServer() {
            serverDialog().showAndWait().ifPresent(this::useServer);
        }

        /** What the server prompt was saved with: the address as typed, and the trimmed app ID. */
        private record ServerChoice(String url, String clientId) {
        }

        /**
         * The server prompt. An address that cannot be used is said under the field as it is
         * typed, with Save off until it can - where it used to be an error dialog after Save,
         * with the prompt and what was typed in it gone.
         */
        Dialog<ServerChoice> serverDialog() {
            Dialog<ServerChoice> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, name() + " server");
            String now = host.url().isBlank() ? publicHost() : hostSummary();
            JfxDialog.header(dialog, Icons.remote(), JfxDialog.Tone.NEUTRAL, "Choose the " + name() + " server",
                    "Now `" + now + "`");
            ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

            TextField url = new TextField(host.url());
            url.setId(SERVER_URL_ID);
            url.setPromptText(host.example());
            DialogParts.Field urlRow = DialogParts.field("URL", url)
                    .hint("Blank for " + publicHost() + "."
                            + (isConnected() ? " Changing it disconnects the account connected now." : ""));
            String clientKey = OAuthClients.clientIdKey(provider);
            TextField clientId = new TextField(Settings.get(clientKey, ""));
            clientId.setId(CLIENT_ID_FIELD_ID);
            DialogParts.Field clientRow = DialogParts.field("OAuth app ID", clientId).optional()
                    .hint("The ID of an OAuth application registered on this server, for signing in"
                            + " with the browser. Leave blank to connect with a token.");
            dialog.getDialogPane().setContent(DialogParts.stack(urlRow, clientRow));

            Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
            Runnable validate = () -> {
                String problem = ProviderHosts.problemWith(url.getText());
                urlRow.error(problem == null ? null : sentence(problem));
                saveButton.setDisable(problem != null);
            };
            url.textProperty().addListener((obs, old, text) -> validate.run());
            validate.run();
            Platform.runLater(url::requestFocus);

            dialog.setResultConverter(button -> button == save
                    ? new ServerChoice(url.getText() == null ? "" : url.getText(),
                            clientId.getText() == null ? "" : clientId.getText().trim())
                    : null);
            return dialog;
        }

        /**
         * Applies a saved server prompt. Changing the server disconnects: the credential in
         * hand was issued by the server the user was talking to a moment ago.
         */
        private void useServer(ServerChoice choice) {
            String clientKey = OAuthClients.clientIdKey(provider);
            if (!choice.clientId().equals(Settings.get(clientKey, "").trim())) {
                Settings.put(clientKey, choice.clientId().isEmpty() ? null : choice.clientId());
            }
            String newUrl = choice.url();
            // Save is off while the address is one problemWith refuses; checked again
            // because setting one throws.
            if (!newUrl.trim().equals(host.url().trim()) && ProviderHosts.problemWith(newUrl) == null) {
                if (isConnected()) {
                    ProviderSessions.disconnect(provider);
                }
                // Whether or not it is connected right now: a credential remembered for the
                // old server - one whose restore failed while offline, say - must not survive
                // to be offered to the new one.
                TokenStore.remove(storeKey());
                host.set(newUrl);
            }
            refresh();
        }
    }

}
