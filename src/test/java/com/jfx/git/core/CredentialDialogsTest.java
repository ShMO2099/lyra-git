package com.jfx.git.core;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;

import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialogs {@link CredentialsManager} asks with, built and looked at without being
 * shown: the login, a key's passphrase, and a prompt it does not recognise. What they ask
 * and when is {@code CredentialsManagerTest}'s and {@code AskpassBridgeTest}'s; this is
 * what they look like - the server or key in the header, labelled fields, a toggle that
 * shows a secret, the verb on the button.
 */
class CredentialDialogsTest {

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    private static String captions(Dialog<?> dialog) {
        return FxHarness.lookupAll(dialog.getDialogPane(), "t-caption").stream()
                .map(n -> ((Label) n).getText())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void theLoginNamesTheServerAndFillsInTheUserTheUrlNames() {
        FxHarness.run(() -> {
            Dialog<String[]> dialog;
            try {
                dialog = CredentialsManager.loginDialog(new URIish("https://ada@github.com/shop/demo.git"));
            } catch (java.net.URISyntaxException e) {
                throw new AssertionError(e);
            }
            JfxDialog.Header header = JfxDialog.headerOf(dialog);
            assertEquals("Sign in to github.com", header.titleText());
            assertEquals("https://ada@github.com/shop/demo.git", header.subtitleText());
            assertEquals("Sign in", dialog.getTitle());

            TextField user = (TextField) dialog.getDialogPane().lookup("#" + CredentialsManager.LOGIN_USERNAME_ID);
            PasswordField password = (PasswordField) dialog.getDialogPane()
                    .lookup("#" + CredentialsManager.LOGIN_PASSWORD_ID);
            assertEquals("ada", user.getText(), "the user the URL names");
            Button signIn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            assertEquals("Sign in", signIn.getText());
            assertTrue(captions(dialog).contains("personal access token"), captions(dialog));

            password.setText("ghp_secret");
            assertEquals("ada", dialog.getResultConverter().call(ButtonType.OK)[0]);
            assertEquals("ghp_secret", dialog.getResultConverter().call(ButtonType.OK)[1]);
            user.setText(" ");
            assertTrue(signIn.isDisabled(), "a login needs a username");
        });
    }

    @Test
    void theRevealToggleShowsTheSecretAndKeepsWhatWasTyped() {
        FxHarness.run(() -> {
            Dialog<String> dialog = CredentialsManager.promptDialog(CredentialsManager.AskpassPrompt.of(
                    "Enter passphrase for key '/home/ada/.ssh/id_ed25519':", "SSH key"), true, null);
            JfxDialog.Header header = JfxDialog.headerOf(dialog);
            assertEquals("Unlock your SSH key", header.titleText());
            assertEquals("/home/ada/.ssh/id_ed25519", header.subtitleText());
            Button unlock = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            assertEquals("Unlock key", unlock.getText());
            assertTrue(unlock.isDisabled(), "nothing typed yet");

            PasswordField hidden = (PasswordField) dialog.getDialogPane()
                    .lookup("#" + CredentialsManager.PROMPT_SECRET_ID);
            hidden.setText("hunter2");
            assertFalse(unlock.isDisabled());
            ToggleButton reveal = (ToggleButton) FxHarness.lookupAll(dialog.getDialogPane(), "toggle-button").get(0);
            TextField shown = FxHarness.lookupAll(dialog.getDialogPane(), "text-field").stream()
                    .filter(n -> !(n instanceof PasswordField)).map(n -> (TextField) n)
                    .findFirst().orElseThrow();
            assertFalse(shown.isVisible(), "masked to start with");

            reveal.fire();
            assertTrue(shown.isVisible());
            assertFalse(hidden.isVisible());
            assertEquals("hunter2", shown.getText(), "what was typed, readable");
            shown.setText("hunter3");
            reveal.fire();
            assertTrue(hidden.isVisible());
            assertEquals("hunter3", hidden.getText(), "an edit made while shown is kept");
            assertEquals("hunter3", dialog.getResultConverter().call(ButtonType.OK));
        });
    }

    @Test
    void aWrongPassphraseIsSaidOnTheFieldUntilAnotherIsTyped() {
        FxHarness.run(() -> {
            Dialog<String> dialog = CredentialsManager.promptDialog(CredentialsManager.AskpassPrompt.of(
                    "'/home/ada/.ssh/id_ed25519' could not be read. Enter the passphrase again.", "SSH key"),
                    true, null);
            assertTrue(errors(dialog).contains("did not unlock"), errors(dialog));
            ((PasswordField) dialog.getDialogPane().lookup("#" + CredentialsManager.PROMPT_SECRET_ID)).setText("x");
            assertEquals("", errors(dialog));
        });
    }

    @Test
    void aPromptItDoesNotKnowIsShownAsItWasAsked() {
        String asked = "Touch the security key now, then type the six digits it shows\nand press Enter:";
        FxHarness.run(() -> {
            Dialog<String> dialog = CredentialsManager.promptDialog(
                    CredentialsManager.AskpassPrompt.of(asked, "Sign in"), false, null);
            assertTrue(FxHarness.lookupAll(dialog.getDialogPane(), "label").stream()
                    .anyMatch(n -> asked.equals(((Label) n).getText())), "the prompt in its own words");
            Node field = dialog.getDialogPane().lookup("#" + CredentialsManager.PROMPT_TEXT_ID);
            assertTrue(field instanceof TextField && !(field instanceof PasswordField), "not a secret");
            assertFalse(dialog.getDialogPane().lookupButton(ButtonType.OK).isDisabled(),
                    "an answer it cannot judge may be empty");
        });
    }

    private static String errors(Dialog<?> dialog) {
        return FxHarness.lookupAll(dialog.getDialogPane(), "jfx-field-error").stream()
                .map(n -> ((Label) n).getText())
                .reduce("", String::concat);
    }
}
