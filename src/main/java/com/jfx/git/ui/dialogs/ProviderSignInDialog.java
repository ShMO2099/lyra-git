package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.TokenStore;
import com.jfx.git.core.UrlOpener;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.OAuthClients;
import com.jfx.git.core.hosting.OAuthDeviceFlow;
import com.jfx.git.core.hosting.OAuthException;
import com.jfx.git.core.hosting.OAuthLoopbackFlow;
import com.jfx.git.core.hosting.OAuthSpec;
import com.jfx.git.core.hosting.ProviderCredential;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Signs in to a hosting provider through the browser, in place of pasting a token.
 *
 * <h2>Two flows</h2>
 * Where the provider has a device flow (GitHub, GitLab 17.2+) the dialog shows a short code
 * and opens the provider's page, where the user - already signed in there, usually - types
 * it and approves. Nothing listens on this machine and no secret ships with the
 * application. Elsewhere (Bitbucket, Gitea and Forgejo, an older GitLab) the browser is
 * sent to the provider's sign-in page and comes back to a one-off listener on
 * {@code 127.0.0.1}; see {@link OAuthLoopbackFlow}.
 *
 * <p>Either way the work runs on the provider pool and the dialog only ever waits:
 * closing it, or Cancel, stops the flow. What it hands back is a credential nobody has
 * checked yet - the Integrations card validates it against the provider's {@code /user}
 * exactly as it does a pasted token, so both ways in end in the same place.
 */
public final class ProviderSignInDialog extends Stage {

    /** A credential the user signed in with, and whether to remember it. */
    public record SignIn(ProviderCredential credential, boolean remember) {
    }

    /** Builds the flows; replaced by tests to point them at a fake server. */
    interface Flows {
        OAuthDeviceFlow device(OAuthSpec spec);

        OAuthLoopbackFlow loopback(OAuthSpec spec);
    }

    static volatile Flows flows = new Flows() {
        @Override
        public OAuthDeviceFlow device(OAuthSpec spec) {
            return new OAuthDeviceFlow(spec);
        }

        @Override
        public OAuthLoopbackFlow loopback(OAuthSpec spec) {
            return new OAuthLoopbackFlow(spec);
        }
    };

    private final HostingProvider provider;
    private final URI web;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Label status = new Label();
    private final Label code = new Label();
    private final Button openPage = new Button();
    private final CheckBox remember = new CheckBox("Remember this sign-in");
    private SignIn result;

    /** What a flow step produced: its value, or why it stopped. */
    private record Outcome<T>(T value, OAuthException failure) {
    }

    ProviderSignInDialog(HostingProvider provider, URI web, OAuthSpec spec) {
        this.provider = provider;
        this.web = web;
        initModality(Modality.APPLICATION_MODAL);

        JfxDialog.Header header = JfxDialog.header(Icons.key(), JfxDialog.Tone.NEUTRAL,
                "Sign in to " + provider.displayName(), "Approve Lyra in your browser");
        status.setWrapText(true);
        code.getStyleClass().addAll("t-title", "sign-in-code");
        code.setManaged(false);
        code.setVisible(false);
        openPage.getStyleClass().add("btn-primary");
        openPage.setManaged(false);
        openPage.setVisible(false);

        boolean storable = TokenStore.isAvailable();
        remember.setSelected(storable);
        remember.setDisable(!storable);
        Label where = new Label(storable
                ? "Kept " + TokenStore.describeLocation() + "; never written as plain text."
                : "Not offered: " + TokenStore.describeLocation() + ".");
        where.getStyleClass().add("t-caption");
        where.setWrapText(true);

        VBox body = new VBox(UiScale.GAP, status, code, openPage, remember, where);
        if (provider == HostingProvider.GITEA) {
            // Forgejo - Codeberg's software - has no OAuth scopes yet, so a sign-in grants
            // the whole account. Said here, where the choice is made, not in a help page.
            Label scope = new Label("Forgejo servers such as Codeberg give an app that signs in full"
                    + " access to your account. Use a token instead to limit what it can do.");
            scope.getStyleClass().addAll("t-caption", "provider-note-warn");
            scope.setWrapText(true);
            body.getChildren().add(scope);
        }

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(UiScale.GAP, spacer, cancel);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("jfx-dialog-footer");

        body.getStyleClass().add("jfx-dialog-body");
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        // The shell's three parts, laid out by hand because this is a Stage: the header,
        // the body, and the footer strip running edge to edge.
        VBox root = new VBox(header.node(), body, grow, footer);
        root.getStyleClass().add("dialog-root");
        setScene(new Scene(root));
        JfxDialog.shell(this, JfxDialog.Size.SM, "Sign in to " + provider.displayName());

        // Closing the window is cancelling: the flow on the provider pool sees it and stops.
        setOnHidden(e -> cancelled.set(true));
        if (spec.flow() == OAuthSpec.Flow.DEVICE) {
            startDevice(spec);
        } else {
            startLoopback(spec);
        }
    }

    /**
     * Signs in to {@code provider} at {@code web} with {@code spec}, waiting for the user.
     *
     * @return the credential and the remember choice, or empty when cancelled or failed
     */
    public static Optional<SignIn> signIn(HostingProvider provider, URI web, OAuthSpec spec) {
        ProviderSignInDialog dialog = new ProviderSignInDialog(provider, web, spec);
        dialog.showAndWait();
        return Optional.ofNullable(dialog.result);
    }

    /** The status line, for tests. */
    String statusForTest() {
        return status.getText();
    }

    /** What the dialog ended with, for tests; empty while it runs or once cancelled. */
    Optional<SignIn> resultForTest() {
        return Optional.ofNullable(result);
    }

    /** The code shown for the device flow, for tests; empty until there is one. */
    String codeForTest() {
        return code.getText();
    }

    private void startDevice(OAuthSpec spec) {
        status.setText("Asking " + provider.displayName() + " for a sign-in code…");
        OAuthDeviceFlow flow = flows.device(spec);
        run(() -> {
            try {
                return new Outcome<>(flow.start(), null);
            } catch (OAuthException e) {
                return new Outcome<>(null, e);
            }
        }, started -> {
            if (started.failure() != null) {
                if (started.failure().reason() == OAuthException.Reason.UNSUPPORTED
                        && provider == HostingProvider.GITLAB) {
                    // A GitLab older than 17.2 has no device flow; its browser sign-in works.
                    OAuthClients.shipped().spec(provider, web, OAuthSpec.Flow.LOOPBACK)
                            .ifPresentOrElse(this::startLoopback, () -> fail(started.failure()));
                    return;
                }
                fail(started.failure());
                return;
            }
            showCode(started.value());
            run(() -> {
                try {
                    return new Outcome<>(flow.poll(started.value(), cancelled::get), null);
                } catch (OAuthException e) {
                    return new Outcome<>(null, e);
                }
            }, this::finish);
        });
    }

    private void showCode(OAuthDeviceFlow.DeviceCode device) {
        code.setText(device.userCode());
        code.setManaged(true);
        code.setVisible(true);
        status.setText("Enter this code on " + device.verificationUri().getHost()
                + " and approve the request. This window carries on when you have.");
        openPage.setText("Copy code and open " + device.verificationUri().getHost());
        openPage.setManaged(true);
        openPage.setVisible(true);
        openPage.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(device.userCode());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            UrlOpener.open(device.browserUri().toString());
        });
        refit();
    }

    /**
     * The window is as tall as its content, measured when it opened; the code and its
     * button arrive afterwards and would otherwise be squeezed into the room the first
     * status line left.
     */
    private void refit() {
        if (isShowing()) {
            sizeToScene();
        }
    }

    private void startLoopback(OAuthSpec spec) {
        status.setText("Finish signing in to " + provider.displayName()
                + " in your browser. This window carries on when you have.");
        OAuthLoopbackFlow flow = flows.loopback(spec);
        run(() -> {
            try {
                return new Outcome<>(flow.run(cancelled::get), null);
            } catch (OAuthException e) {
                return new Outcome<>(null, e);
            }
        }, this::finish);
    }

    private void finish(Outcome<ProviderCredential> outcome) {
        if (outcome.failure() != null) {
            fail(outcome.failure());
            return;
        }
        result = new SignIn(outcome.value(), remember.isSelected() && !remember.isDisabled());
        close();
    }

    /** Says why it stopped and leaves the window open to read it; a cancel just closes. */
    private void fail(OAuthException failure) {
        if (failure.reason() == OAuthException.Reason.CANCELLED || cancelled.get()) {
            close();
            return;
        }
        code.setVisible(false);
        code.setManaged(false);
        openPage.setVisible(false);
        openPage.setManaged(false);
        String hint = switch (failure.reason()) {
            case DENIED -> "The request was declined.";
            case EXPIRED, TIMED_OUT -> "The sign-in took too long. Close this and try again.";
            case PORT_IN_USE -> failure.getMessage() + " Use a token to connect instead.";
            default -> failure.getMessage();
        };
        status.setText("Could not sign in to " + provider.displayName() + ". " + hint);
        refit();
    }

    /**
     * Runs a flow step on the provider pool and hands its outcome back on the FX thread -
     * unless the dialog was closed meanwhile, when nobody is waiting for it.
     */
    private <T> void run(Supplier<Outcome<T>> step, java.util.function.Consumer<Outcome<T>> then) {
        GitTaskRunner.queryProvider(step,
                outcome -> {
                    if (!cancelled.get()) then.accept(outcome);
                },
                message -> {
                    if (!cancelled.get()) {
                        status.setText("Could not sign in to " + provider.displayName() + ". " + message);
                    }
                });
    }
}
