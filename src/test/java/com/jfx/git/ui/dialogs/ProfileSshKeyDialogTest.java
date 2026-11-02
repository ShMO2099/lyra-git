package com.jfx.git.ui.dialogs;

import com.jfx.git.core.ProfileStore;
import com.jfx.git.core.Settings;
import com.jfx.git.core.SshKeys;
import com.jfx.git.model.Profile;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.settings.ProfilesPage;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A profile's SSH key in the two places that set it: the profile form on the Profiles
 * page of Settings, and the dialog that generates a new key.
 */
class ProfileSshKeyDialogTest {

    @TempDir
    Path root;

    @BeforeEach
    void setUp() {
        FxHarness.start();
        Settings.useFile(root.resolve("settings.properties"));
    }

    @AfterEach
    void tearDown() {
        Settings.useFile(null);
    }

    private static Button button(Node root, String text) {
        return (Button) FxHarness.lookupAll(root, "button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + text + "\" button"));
    }

    @SuppressWarnings("unchecked")
    private static ListView<Profile> profileList(Node root) {
        return (ListView<Profile>) FxHarness.lookupAll(root, "list-view").get(0);
    }

    // ------------------------------------------------------------------
    // The profile form
    // ------------------------------------------------------------------

    @Test
    void theProfileFormShowsItsKeyAndSavesWhatIsTyped() throws Exception {
        Path key = root.resolve("id_work");
        Files.writeString(key, "private\n");
        Files.writeString(root.resolve("id_work.pub"), "ssh-ed25519 AAAA work\n");
        ProfileStore.save(new Profile("Work", "Alice", "alice@work.example", "", false, key.toString()));
        ProfileStore.save(new Profile("Home", "Alice", "alice@home.example", "", false));

        FxHarness.run(() -> {
            Node content = new ProfilesPage();
            TextField sshKey = (TextField) content.lookup("#" + ProfilesPage.SSH_KEY_FIELD_ID);
            Button copy = button(content, "Copy public key");

            profileList(content).getSelectionModel().select(ProfileStore.profile("Work"));
            assertEquals(key.toString(), sshKey.getText());
            assertFalse(copy.isDisabled(), "there is a .pub beside the key, so there is something to copy");

            profileList(content).getSelectionModel().select(ProfileStore.profile("Home"));
            assertEquals("", sshKey.getText(), "a profile with no key shows none, not the last one looked at");
            assertTrue(copy.isDisabled());

            sshKey.setText("  " + key + "  ");
            button(content, "Save profile").fire();
        });

        assertEquals(key.toString(), ProfileStore.profile("Home").sshKeyPath(), "stored trimmed");
        assertEquals(key.toString(), ProfileStore.profile("Work").sshKeyPath(), "the other profile is untouched");
    }

    @Test
    void clearingTheFieldTakesTheKeyOffTheProfile() throws Exception {
        Path key = root.resolve("id_work");
        Files.writeString(key, "private\n");
        ProfileStore.save(new Profile("Work", "Alice", "alice@work.example", "", false, key.toString()));

        FxHarness.run(() -> {
            Node content = new ProfilesPage();
            profileList(content).getSelectionModel().select(ProfileStore.profile("Work"));
            ((TextField) content.lookup("#" + ProfilesPage.SSH_KEY_FIELD_ID)).setText("");
            button(content, "Save profile").fire();
        });

        assertEquals("", ProfileStore.profile("Work").sshKeyPath());
    }

    /** Said beside Save, on the page, where it used to be a warning dialog of its own. */
    @Test
    void aProfileWithNoNameIsRefusedBesideSave() {
        int before = ProfileStore.profiles().size();
        String said = FxHarness.call(() -> {
            Node content = new ProfilesPage();
            button(content, "Save profile").fire();
            return FxHarness.lookupAll(content, "label").stream()
                    .filter(n -> n.getStyleClass().contains("jfx-field-error"))
                    .map(n -> ((javafx.scene.control.Label) n).getText())
                    .findFirst().orElse("");
        });

        assertEquals("A profile needs a name.", said);
        assertEquals(before, ProfileStore.profiles().size(), "nothing was saved");
        assertFalse(FxHarness.call(() -> javafx.stage.Window.getWindows().stream()
                        .anyMatch(w -> w.isShowing() && w.getScene() != null
                                && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)),
                "and no dialog opened to say it");
    }

    // ------------------------------------------------------------------
    // Generating one
    // ------------------------------------------------------------------

    /** The footer's Generate: the dialog's action, run in place. */
    private static Button generate(GenerateSshKeyDialog dialog) {
        return (Button) dialog.getDialogPane().lookupButton(GenerateSshKeyDialog.GENERATE);
    }

    /** Said under the repeat field as it is typed, with Generate off until they agree. */
    @Test
    void passphrasesThatDifferAreCaughtBeforeAnythingIsWritten() {
        Path target = root.resolve("id_new");

        String said = FxHarness.call(() -> {
            GenerateSshKeyDialog dialog = new GenerateSshKeyDialog("id_new", "a@b");
            Node content = dialog.getDialogPane();
            ((TextField) content.lookup("#" + GenerateSshKeyDialog.FILE_ID)).setText(target.toString());
            ((PasswordField) content.lookup("#" + GenerateSshKeyDialog.PASSPHRASE_ID)).setText("one");
            ((PasswordField) content.lookup("#" + GenerateSshKeyDialog.REPEAT_ID)).setText("two");
            assertTrue(generate(dialog).isDisabled(), "Generate stays off while the two differ");
            generate(dialog).fire();
            return FxHarness.lookupAll(content, "label").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText())
                    .filter(t -> t != null && t.contains("passphrases"))
                    .findFirst().orElse("");
        });

        assertEquals("The two passphrases are not the same.", said);
        assertFalse(Files.exists(target), "nothing may be generated with a passphrase the user did not mean");
    }

    /** A repeat that matches so far is unfinished, not wrong; one that agrees turns Generate on. */
    @Test
    void aRepeatStillBeingTypedIsNotAnError() {
        FxHarness.run(() -> {
            GenerateSshKeyDialog dialog = new GenerateSshKeyDialog("id_new", "a@b");
            Node content = dialog.getDialogPane();
            ((TextField) content.lookup("#" + GenerateSshKeyDialog.FILE_ID)).setText(root.resolve("id_new").toString());
            ((PasswordField) content.lookup("#" + GenerateSshKeyDialog.PASSPHRASE_ID)).setText("hunter2");
            PasswordField repeat = (PasswordField) content.lookup("#" + GenerateSshKeyDialog.REPEAT_ID);
            repeat.setText("hunt");
            assertTrue(FxHarness.lookupAll(content, "jfx-field-error").stream()
                            .allMatch(n -> ((javafx.scene.control.Label) n).getText().isEmpty()),
                    "nothing is wrong yet");
            assertTrue(generate(dialog).isDisabled());
            repeat.setText("hunter2");
            assertFalse(generate(dialog).isDisabled(), "the two agree");
        });
    }

    @Test
    void theDialogStartsInTheSshFolderUnderTheSuggestedName() {
        String file = FxHarness.call(() -> ((TextField) new GenerateSshKeyDialog("id_ed25519_work", "a@b")
                .getDialogPane().lookup("#" + GenerateSshKeyDialog.FILE_ID)).getText());

        assertEquals(SshKeys.defaultDirectory().resolve("id_ed25519_work").toString(), file);
    }

    @Test
    void generatingShowsThePublicKeyReadyToCopy() throws Exception {
        Path target = root.resolve("id_generated");
        GenerateSshKeyDialog[] dialog = new GenerateSshKeyDialog[1];

        FxHarness.run(() -> {
            dialog[0] = new GenerateSshKeyDialog("id_generated", "alice@work.example");
            Node content = dialog[0].getDialogPane();
            ((TextField) content.lookup("#" + GenerateSshKeyDialog.FILE_ID)).setText(target.toString());
            generate(dialog[0]).fire();
        });

        // ssh-keygen runs off the FX thread; the dialog fills in when it is done.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        String shown = "";
        while (shown.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            shown = FxHarness.call(() -> ((TextInputControl) dialog[0].getDialogPane()
                    .lookup("#" + GenerateSshKeyDialog.PUBLIC_KEY_ID)).getText());
            if (shown == null) shown = "";
            // The outcome is said in the footer, where the dialog's action reports.
            boolean failed = FxHarness.call(() -> dialog[0].statusForTest().contains("ssh-keygen was not found"));
            assumeTrue(!failed, "needs ssh-keygen");
        }

        assertTrue(shown.startsWith("ssh-ed25519 "), "the public key, as a host's form wants it: " + shown);
        assertTrue(shown.endsWith("alice@work.example"));
        assertNotNull(SshKeys.publicKey(target));
        FxHarness.run(() -> {
            assertTrue(generate(dialog[0]).isDisabled(),
                    "one key per visit; a second press could only fail on \"already exists\"");
            assertTrue(dialog[0].statusForTest().startsWith("Created id_generated"),
                    "the dialog stays, saying what it made: " + dialog[0].statusForTest());
            assertTrue(shownInTree(dialog[0].getDialogPane().lookup("#" + GenerateSshKeyDialog.PUBLIC_KEY_ID)),
                    "with the public key showing in it");
        });
    }

    /** Whether {@code node} and every parent of it are visible: a hidden field hides what it holds. */
    private static boolean shownInTree(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    /** A name ssh-keygen finds taken is said under "Save as", and goes with the name. */
    @Test
    void aKeyFileThatExistsAlreadyIsSaidUnderItsField() throws Exception {
        Path target = root.resolve("id_taken");
        Files.writeString(target, "private\n");
        GenerateSshKeyDialog[] dialog = new GenerateSshKeyDialog[1];
        FxHarness.run(() -> {
            dialog[0] = new GenerateSshKeyDialog("id_taken", "a@b");
            ((TextField) dialog[0].getDialogPane().lookup("#" + GenerateSshKeyDialog.FILE_ID))
                    .setText(target.toString());
            generate(dialog[0]).fire();
        });

        com.jfx.git.ui.RobotWait.untilOnFx("the taken name was never said", () -> fieldErrors(dialog[0])
                .contains("already exists"));
        FxHarness.run(() -> {
            assertTrue(generate(dialog[0]).isDisabled(), "not again under the same name");
            ((TextField) dialog[0].getDialogPane().lookup("#" + GenerateSshKeyDialog.FILE_ID))
                    .setText(root.resolve("id_other").toString());
            assertEquals("", fieldErrors(dialog[0]), "a new name is not taken until ssh-keygen says so");
            assertFalse(generate(dialog[0]).isDisabled());
        });
        assertEquals("private\n", Files.readString(target), "the key that was there is untouched");
    }

    private static String fieldErrors(GenerateSshKeyDialog dialog) {
        return FxHarness.lookupAll(dialog.getDialogPane(), "jfx-field-error").stream()
                .map(n -> ((javafx.scene.control.Label) n).getText())
                .collect(java.util.stream.Collectors.joining());
    }
}
