package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.SshKeys;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Makes a new SSH key pair and hands back where the private key was written.
 *
 * <p>The dialog stays open after generating, on the public key: the next thing anyone
 * does with a new key is paste its public half into a hosting site, and a dialog that
 * closed on success would leave them looking for a file to open. So Generate is the
 * footer's action run in place ({@link JfxDialog#actInPlace}) and keeps the dialog
 * ({@link JfxDialog.Progress#kept}), and the other button says Close: closing hands back
 * the key made, if one was.
 *
 * <p>What can be decided as it is typed is said under its field and keeps Generate off: no
 * file, a name the system cannot use, two passphrases that differ. What only ssh-keygen can
 * say - a file already there - comes back to the field it is about.
 *
 * <p>Generation runs off the FX thread. It is quick, but it is a child process, and a
 * process that is slow to start must not freeze the window it was asked from.
 */
public class GenerateSshKeyDialog extends Dialog<Path> {

    public static final String FILE_ID = "ssh-key-file";
    public static final String COMMENT_ID = "ssh-key-comment";
    public static final String PASSPHRASE_ID = "ssh-key-passphrase";
    public static final String REPEAT_ID = "ssh-key-passphrase-repeat";
    public static final String PUBLIC_KEY_ID = "ssh-key-public";

    /** The footer's action; the dialog stays open after it, on the public key. */
    public static final ButtonType GENERATE = new ButtonType("Generate", ButtonBar.ButtonData.OK_DONE);

    static final String PASSPHRASES_DIFFER = "The two passphrases are not the same.";

    private final TextField file = new TextField();
    private final TextField comment = new TextField();
    private final PasswordField passphrase = new PasswordField();
    private final PasswordField repeat = new PasswordField();
    private final TextArea publicKey = new TextArea();
    private final Button copy = new Button("Copy");
    private final DialogParts.Field fileRow;
    private final DialogParts.Field repeatRow;
    private final DialogParts.Field publicRow;
    private final JfxDialog.FooterStatus status;
    private final Button generate;
    /** Set once focus has left the repeat field: from then on a short repeat is wrong, not unfinished. */
    private boolean repeatLeft;
    /** The name ssh-keygen found a key at, as typed, and what it said; null until it does. */
    private String takenPath;
    private String takenSaid;
    private Path created;

    /**
     * @param suggestedName the key's file name to start from, e.g. {@code id_ed25519_work}
     * @param suggestedComment the label for the public key, usually the profile's email
     */
    public GenerateSshKeyDialog(String suggestedName, String suggestedComment) {
        JfxDialog.shell(this, JfxDialog.Size.MD, "Generate SSH key");
        JfxDialog.header(this, Icons.key(), JfxDialog.Tone.NEUTRAL, "Generate an SSH key",
                "A new Ed25519 key pair");
        getDialogPane().getButtonTypes().addAll(GENERATE, ButtonType.CLOSE);
        generate = (Button) getDialogPane().lookupButton(GENERATE);
        status = JfxDialog.footerStatus(this);

        file.setId(FILE_ID);
        file.setText(SshKeys.defaultDirectory().resolve(
                suggestedName == null || suggestedName.isBlank() ? "id_ed25519" : suggestedName).toString());
        comment.setId(COMMENT_ID);
        comment.setText(suggestedComment == null ? "" : suggestedComment);
        comment.setPromptText("you@example.com");
        passphrase.setId(PASSPHRASE_ID);
        repeat.setId(REPEAT_ID);

        Button browse = new Button("Browse…");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save the private key as");
            File dir = SshKeys.defaultDirectory().toFile();
            if (dir.isDirectory()) {
                chooser.setInitialDirectory(dir);
            }
            File chosen = chooser.showSaveDialog(getDialogPane().getScene().getWindow());
            if (chosen != null) {
                file.setText(chosen.getAbsolutePath());
            }
        });

        fileRow = DialogParts.field("Save as", file).trailing(browse)
                .hint("The private key. The public key is written beside it, with .pub added.");
        DialogParts.Field commentRow = DialogParts.field("Comment", comment).optional()
                .hint("What the key is labelled with where you add it; usually your email.");
        DialogParts.Field passphraseRow = DialogParts.field("Passphrase", passphrase).optional();
        repeatRow = DialogParts.field("Repeat passphrase", repeat);

        publicKey.setId(PUBLIC_KEY_ID);
        publicKey.setEditable(false);
        publicKey.setWrapText(true);
        // An Ed25519 public key with its comment wraps to three lines here; a fourth keeps a
        // scroll bar from appearing beside them.
        publicKey.setPrefRowCount(4);
        publicKey.getStyleClass().add("t-mono");
        copy.setMinWidth(Region.USE_PREF_SIZE);
        copy.setGraphic(Icons.copy());
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(publicKey.getText());
            Clipboard.getSystemClipboard().setContent(content);
            status.ok("The public key is on the clipboard. Add it to your account on the host.");
        });
        publicRow = DialogParts.field("Public key", publicKey).trailing(copy)
                .hint("Paste it where your host asks for an SSH key: its account settings, under SSH keys.");
        publicRow.node().setVisible(false);
        publicRow.node().setManaged(false);

        getDialogPane().setContent(DialogParts.stack(fileRow, commentRow,
                passphrases(passphraseRow, repeatRow), publicRow));

        JfxDialog.actInPlace(this, GENERATE, "Generating…", this::generate);

        file.textProperty().addListener((obs, old, text) -> validate());
        passphrase.textProperty().addListener((obs, old, text) -> validate());
        repeat.textProperty().addListener((obs, old, text) -> validate());
        repeat.focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                repeatLeft = true;
                validate();
            }
        });
        validate();
        Platform.runLater(comment::requestFocus);

        // Closing hands back the key that was made, if one was.
        setResultConverter(button -> created);
    }

    /**
     * The two passphrase fields side by side, at half the width each, with what a
     * passphrase is for under both: the pair is one decision.
     */
    private static VBox passphrases(DialogParts.Field first, DialogParts.Field second) {
        Label why = new Label("Leave both blank for none. With one, a stolen key file is not a stolen key.");
        why.getStyleClass().add("t-caption");
        why.setWrapText(true);
        why.setMinHeight(Region.USE_PREF_SIZE);
        return new VBox(UiScale.SNUG, DialogParts.pair(first, second), why);
    }

    /** Says what is wrong under its field and keeps Generate off until nothing is. */
    private void validate() {
        String path = file.getText() == null ? "" : file.getText().trim();
        String fileProblem = path.isEmpty() ? "Choose where to save the key."
                : path.equals(takenPath) ? takenSaid
                : pathProblem(path);
        fileRow.error(fileProblem);

        String secret = passphrase.getText() == null ? "" : passphrase.getText();
        String again = repeat.getText() == null ? "" : repeat.getText();
        boolean same = secret.equals(again);
        String repeatProblem = null;
        if (!same && !again.isEmpty() && (repeatLeft || !secret.startsWith(again))) {
            // Still typing a repeat that matches so far is not a mistake yet.
            repeatProblem = PASSPHRASES_DIFFER;
        } else if (!same && repeatLeft) {
            repeatProblem = "Type the passphrase again here.";
        }
        repeatRow.error(repeatProblem);

        // One key per visit: a second press could only fail on "already exists".
        generate.setDisable(created != null || fileProblem != null || !same);
    }

    /** Why {@code path} cannot be a file name here, or null. */
    private static String pathProblem(String path) {
        try {
            Path.of(path);
            return null;
        } catch (InvalidPathException e) {
            return "That is not a file name this system can use.";
        }
    }

    private void generate(JfxDialog.Progress progress) {
        Path target = Path.of(file.getText().trim());
        String secret = passphrase.getText() == null ? "" : passphrase.getText();
        String label = comment.getText();
        CompletableFuture.supplyAsync(() -> SshKeys.generate(target, label, secret))
                .whenComplete((result, error) -> Platform.runLater(() -> finished(progress, target, result, error)));
    }

    private void finished(JfxDialog.Progress progress, Path target, GitResult result, Throwable error) {
        if (error != null || result == null) {
            progress.failed("Could not generate the key: " + (error == null ? "no result" : error.getMessage()));
            return;
        }
        if (!result.isOk()) {
            String said = result.describe();
            if (said != null && said.contains("already exists")) {
                // About the name, so said under it until the name changes; the footer
                // would only repeat it.
                takenPath = file.getText() == null ? "" : file.getText().trim();
                takenSaid = said;
                progress.failed("");
                validate();
            } else {
                progress.failed(said);
            }
            return;
        }
        created = target;
        String pub = SshKeys.publicKey(target);
        publicKey.setText(pub == null ? "" : pub);
        boolean has = pub != null && !pub.isBlank();
        publicRow.node().setVisible(has);
        publicRow.node().setManaged(has);
        progress.kept(result.describe());
        validate();
        JfxDialog.refit(this);
    }

    /** What the footer says now: progress, the outcome, or what Copy did. */
    String statusForTest() {
        return status.text();
    }
}
