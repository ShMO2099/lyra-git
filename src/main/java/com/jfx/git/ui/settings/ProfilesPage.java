package com.jfx.git.ui.settings;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ProfileStore;
import com.jfx.git.model.Profile;
import com.jfx.git.ui.UiScale;
import com.jfx.git.ui.dialogs.GenerateSshKeyDialog;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;

import static com.jfx.git.ui.settings.SettingsLayout.hint;
import static com.jfx.git.ui.settings.SettingsLayout.page;
import static com.jfx.git.ui.settings.SettingsLayout.section;

/**
 * Who commits: the global identity every repository inherits, and the named profiles a
 * repository can be switched to - the Profiles section of the Settings tab.
 *
 * <p>These were two windows, "Global Git Profile" and "Profiles", reached from two menu
 * items next to each other. They answer one question - which name and key go on my
 * commits - so they are one page with two parts.
 *
 * <h2>Two different files</h2>
 * The global part edits the user's actual global gitconfig: the same file
 * {@code git config --global} would touch, and the one any other git tool on the machine
 * reads. It is read and written off the FX thread, and Save stays off until it has been
 * read, so a click before the read lands cannot write blanks over the user's name. The
 * named profiles are this application's own (see {@link ProfileStore}); applying one to a
 * repository writes into that repository's local config, and happens from the Profiles
 * menu, not here.
 */
public final class ProfilesPage extends VBox {

    /** The profile form's SSH key field, for tests and for anything that wants to focus it. */
    public static final String SSH_KEY_FIELD_ID = "profile-ssh-key";

    // --- Named profiles
    private final ListView<Profile> list = new ListView<>();
    private final TextField txtProfileName = new TextField();
    private final TextField txtGitName = new TextField();
    private final TextField txtGitEmail = new TextField();
    private final TextField txtSigningKey = new TextField();
    /** The private key remotes are reached with under this profile; blank leaves it to ssh. */
    private final TextField txtSshKey = new TextField();
    private final Button btnCopyPublicKey = new Button("Copy public key");
    private final CheckBox chkSign = new CheckBox("Sign commits with GPG");
    private final Button btnDelete = new Button("Delete");
    /** Why the profile could not be saved, beside Save; empty when nothing is wrong. */
    private final Label profileProblem = new Label();

    public ProfilesPage() {
        VBox content = page("Profiles");
        content.getChildren().addAll(globalIdentity());
        content.getChildren().addAll(namedProfiles());
        getChildren().add(content);
    }

    // ------------------------------------------------------------------
    // The global identity
    // ------------------------------------------------------------------

    /** What the global gitconfig says, read in one go off the FX thread. */
    private record GlobalIdentity(String name, String email, String signingKey, boolean sign, String hooksPath) {
    }

    private javafx.scene.Node[] globalIdentity() {
        TextField txtName = new TextField();
        TextField txtEmail = new TextField();
        CheckBox chkGlobalSign = new CheckBox("Sign commits with GPG");
        TextField txtGpg = new TextField();
        txtGpg.setPromptText("Optional - blank uses gpg's own default key");
        TextField txtHooksPath = new TextField();
        txtHooksPath.setPromptText("Optional - blank uses each repository's own .git/hooks");

        GridPane grid = JfxDialog.form();
        grid.setMaxWidth(640);
        JfxDialog.addRow(grid, "Name", txtName);
        JfxDialog.addRow(grid, "Email", txtEmail);
        grid.add(chkGlobalSign, 1, grid.getRowCount());
        JfxDialog.addRow(grid, "GPG key ID", txtGpg);
        JfxDialog.addRow(grid, "Hooks path", txtHooksPath);

        Label status = hint("Reading your global git config…");
        Button btnSave = new Button("Save identity");
        btnSave.getStyleClass().add("btn-primary");
        btnSave.setDisable(true);

        GitTaskRunner.queryDetached(() -> {
            try {
                StoredConfig config = SystemReader.getInstance().getUserConfig();
                config.load();
                return new GlobalIdentity(config.getString("user", null, "name"),
                        config.getString("user", null, "email"),
                        config.getString("user", null, "signingkey"),
                        config.getBoolean("commit", "gpgsign", false),
                        config.getString("core", null, "hooksPath"));
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, identity -> {
            txtName.setText(identity.name());
            txtEmail.setText(identity.email());
            txtGpg.setText(identity.signingKey());
            chkGlobalSign.setSelected(identity.sign());
            txtHooksPath.setText(identity.hooksPath());
            status.setText("");
            btnSave.setDisable(false);
        }, error -> status.setText("Could not read the global git config: " + error
                + ". Nothing is saved over it until it can be read."));

        btnSave.setOnAction(e -> {
            GlobalIdentity wanted = new GlobalIdentity(txtName.getText(), txtEmail.getText(),
                    txtGpg.getText(), chkGlobalSign.isSelected(), txtHooksPath.getText());
            btnSave.setDisable(true);
            status.setText("Saving…");
            GitTaskRunner.queryDetached(() -> {
                try {
                    StoredConfig config = SystemReader.getInstance().getUserConfig();
                    config.load();
                    config.setString("user", null, "name", wanted.name());
                    config.setString("user", null, "email", wanted.email());
                    String gpgKey = wanted.signingKey();
                    if (gpgKey != null && !gpgKey.isBlank()) {
                        config.setString("user", null, "signingkey", gpgKey.trim());
                    } else {
                        config.unset("user", null, "signingkey");
                    }
                    config.setBoolean("commit", null, "gpgsign", wanted.sign());
                    String hooksPath = wanted.hooksPath();
                    if (hooksPath != null && !hooksPath.isBlank()) {
                        config.setString("core", null, "hooksPath", hooksPath.trim());
                    } else {
                        config.unset("core", null, "hooksPath");
                    }
                    config.save();
                    return Boolean.TRUE;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex.getMessage(), ex);
                }
            }, saved -> {
                btnSave.setDisable(false);
                status.setText("Saved to your global git config.");
            }, error -> {
                btnSave.setDisable(false);
                status.setText("");
                com.jfx.git.ui.dialogs.Dialogs.error("Save identity")
                        .title("Could not save your global identity")
                        .message(error == null || error.isBlank()
                                ? "Your global git config could not be written."
                                : "Your global git config could not be written: " + error.strip())
                        .answer();
            });
        });

        HBox actions = new HBox(UiScale.GAP, btnSave, status);
        actions.setAlignment(Pos.CENTER_LEFT);

        return new javafx.scene.Node[] {
                section("Global identity"),
                hint("Written to your global gitconfig - the same identity any other git tool "
                        + "on this machine uses, and what a new repository starts with until its "
                        + "own local config overrides it."),
                grid,
                hint("The Native Git engine signs commits itself, honouring this exactly as the "
                        + "git CLI would. The JGit engine cannot sign - it refuses to commit "
                        + "rather than produce an unsigned commit under this setting."),
                hint("core.hooksPath - a shared hooks directory used instead of each repository's "
                        + "own .git/hooks, honoured by both engines. A repository's own local "
                        + "config can still override this with its own path."),
                actions
        };
    }

    // ------------------------------------------------------------------
    // Named profiles
    // ------------------------------------------------------------------

    /**
     * Creates, edits and deletes named profiles. No paywall gates this, so the minimum
     * useful version is exactly this: a list, a form, Save and Delete.
     */
    private javafx.scene.Node[] namedProfiles() {
        // The themed list frame, not the frameless "list-dark" the window used: on a page
        // of the same colour, a frameless empty list was a sentence floating beside a form.
        list.setPrefWidth(180);
        list.setMinWidth(140);
        list.setPrefHeight(240);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Profile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        list.setPlaceholder(new Label("No profiles yet."));
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> showInForm(selected));

        Button btnNew = new Button("New");
        btnNew.setOnAction(e -> {
            list.getSelectionModel().clearSelection();
            showInForm(null);
            txtProfileName.requestFocus();
        });

        btnDelete.setDisable(true);
        btnDelete.setOnAction(e -> {
            Profile selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            ProfileStore.delete(selected.name());
            reloadList();
            list.getSelectionModel().clearSelection();
            showInForm(null);
        });

        VBox listBox = new VBox(UiScale.STEP, list, new HBox(UiScale.STEP, btnNew, btnDelete));
        VBox.setVgrow(list, Priority.ALWAYS);

        GridPane form = JfxDialog.form();
        JfxDialog.addRow(form, "Profile name", txtProfileName);
        JfxDialog.addRow(form, "Name", txtGitName);
        JfxDialog.addRow(form, "Email", txtGitEmail);
        form.add(chkSign, 1, form.getRowCount());
        JfxDialog.addRow(form, "GPG key ID", txtSigningKey);
        txtSigningKey.setPromptText("Optional");

        // The key this profile's repositories talk to their remotes with. With a work
        // and a personal account on one host, ssh left to itself offers whichever key
        // the server accepts first - so this is offered alone.
        txtSshKey.setId(SSH_KEY_FIELD_ID);
        txtSshKey.setPromptText("Optional - blank uses your usual SSH keys");
        JfxDialog.addRow(form, "SSH key", txtSshKey);

        Button btnChooseKey = new Button("Choose…");
        btnChooseKey.setOnAction(e -> chooseSshKey());
        Button btnGenerateKey = new Button("Generate…");
        btnGenerateKey.setOnAction(e -> generateSshKey());
        btnCopyPublicKey.setOnAction(e -> copyPublicKey());
        btnCopyPublicKey.disableProperty().bind(txtSshKey.textProperty().map(
                text -> com.jfx.git.core.SshKeys.publicKey(pathOrNull(text)) == null));
        HBox keyButtons = new HBox(UiScale.STEP, btnChooseKey, btnGenerateKey, btnCopyPublicKey);
        form.add(keyButtons, 1, form.getRowCount());

        Button btnSave = new Button("Save profile");
        btnSave.getStyleClass().add("btn-primary");
        btnSave.setOnAction(e -> save());
        // What stops the save, beside the button that was pressed, rather than in an alert.
        profileProblem.getStyleClass().add("jfx-field-error");
        profileProblem.setWrapText(true);
        txtProfileName.textProperty().addListener((obs, old, text) -> profileProblem.setText(""));
        HBox saveRow = new HBox(UiScale.GAP, btnSave, profileProblem);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        VBox formBox = new VBox(UiScale.GAP, form, saveRow);
        HBox.setHgrow(formBox, Priority.ALWAYS);
        formBox.setMaxWidth(560);

        HBox body = new HBox(UiScale.EDGE, listBox, formBox);

        reloadList();
        showInForm(null);

        return new javafx.scene.Node[] {
                section("Named profiles"),
                hint("Named identities you can switch a repository to - each one writes "
                        + "user.name/user.email (and GPG signing) into that repository's own "
                        + "local git config, leaving your global identity untouched. Switch "
                        + "a repository from the Profiles menu."),
                body
        };
    }

    private void reloadList() {
        list.getItems().setAll(ProfileStore.profiles());
    }

    /** Fills the form from {@code profile}, or clears it to a blank new-profile state. */
    private void showInForm(Profile profile) {
        boolean editing = profile != null;
        txtProfileName.setText(editing ? profile.name() : "");
        txtProfileName.setDisable(editing); // renaming would orphan any repo already pointed at the old name
        txtGitName.setText(editing ? profile.gitName() : "");
        txtGitEmail.setText(editing ? profile.gitEmail() : "");
        txtSigningKey.setText(editing ? profile.signingKey() : "");
        txtSshKey.setText(editing ? profile.sshKeyPath() : "");
        chkSign.setSelected(editing && profile.gpgSign());
        btnDelete.setDisable(!editing);
    }

    private void save() {
        String name = txtProfileName.getText();
        if (name == null || name.isBlank()) {
            profileProblem.setText("A profile needs a name.");
            txtProfileName.requestFocus();
            return;
        }
        ProfileStore.save(new Profile(name.trim(), txtGitName.getText(), txtGitEmail.getText(),
                txtSigningKey.getText(), chkSign.isSelected(),
                txtSshKey.getText() == null ? "" : txtSshKey.getText().trim()));
        reloadList();
        list.getSelectionModel().select(ProfileStore.profile(name.trim()));
    }

    private static java.nio.file.Path pathOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return java.nio.file.Path.of(text.trim());
        } catch (RuntimeException notAPath) {
            return null;
        }
    }

    private void chooseSshKey() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Choose the private key");
        java.io.File dir = com.jfx.git.core.SshKeys.defaultDirectory().toFile();
        if (dir.isDirectory()) {
            chooser.setInitialDirectory(dir);
        }
        java.io.File chosen = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen == null) return;
        // The private key is what ssh is given; the .pub beside it is what people tend
        // to pick, because it is the one they were last asked to copy.
        String path = chosen.getAbsolutePath();
        txtSshKey.setText(path.endsWith(".pub") ? path.substring(0, path.length() - ".pub".length()) : path);
    }

    private void generateSshKey() {
        String label = txtProfileName.getText() == null ? "" : txtProfileName.getText().trim();
        String suffix = label.replaceAll("[^A-Za-z0-9_-]+", "_").toLowerCase(java.util.Locale.ROOT);
        new GenerateSshKeyDialog(suffix.isEmpty() ? "id_ed25519" : "id_ed25519_" + suffix,
                txtGitEmail.getText())
                .showAndWait()
                .ifPresent(key -> txtSshKey.setText(key.toString()));
    }

    private void copyPublicKey() {
        String publicKey = com.jfx.git.core.SshKeys.publicKey(pathOrNull(txtSshKey.getText()));
        if (publicKey == null) return;
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(publicKey);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }
}
