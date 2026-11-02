package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The confirmations, warnings, errors, notices, prompts and choices built by {@link Dialogs}.
 *
 * <p>What each kind decides once, so that the eighty-odd places that ask something do not
 * each have to: the buttons, the tone, which answer is red, which one Enter takes - and
 * that the words a caller passes still reach {@code headerText} and {@code contentText},
 * where the rest of the suite reads them.
 */
class DialogsTest {

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private static Button button(Dialog<?> dialog, ButtonType type) {
        return (Button) dialog.getDialogPane().lookupButton(type);
    }

    @Test
    void aConfirmationKeepsOkAndCancelAndNamesTheAction() {
        FxHarness.run(() -> {
            Alert alert = Dialogs.confirm("Move tag")
                    .title("Move v1.0 to HEAD?")
                    .subtitle("Not a fast-forward")
                    .message("The tag moves away from its existing history.")
                    .action("Move tag")
                    .build();

            assertEquals(List.of(ButtonType.OK, ButtonType.CANCEL), alert.getButtonTypes());
            assertEquals("Move tag", button(alert, ButtonType.OK).getText());
            assertEquals("Move v1.0 to HEAD?", alert.getHeaderText(), "the title is the header text");
            assertEquals("The tag moves away from its existing history.", alert.getContentText(),
                    "the message is the content text");
            assertNull(alert.getGraphic(), "no glossy bitmap");
            assertTrue(alert.getDialogPane().getStyleClass().contains("jfx-alert"));
            assertTrue(button(alert, ButtonType.OK).getStyleClass().contains("btn-primary"));
            assertEquals("Move tag", alert.getTitle(), "the window title is the short noun");
        });
    }

    @Test
    void aDestructiveActionIsRedAndNotAlsoThePrimary() {
        FxHarness.run(() -> {
            Alert alert = Dialogs.destructive("Delete branch").title("Delete feature/search?")
                    .action("Delete branch").build();
            var classes = button(alert, ButtonType.OK).getStyleClass();
            assertTrue(classes.contains("btn-danger"));
            assertFalse(classes.contains("btn-primary"));
            assertEquals(JfxDialog.Tone.DESTRUCTIVE, JfxDialog.headerOf(alert).tone());
            assertTrue(button(alert, ButtonType.OK).isDefaultButton(),
                    "an undoable delete keeps Enter on the action");
        });
    }

    /** For what cannot be undone, Enter is Cancel. */
    @Test
    void aSafeDefaultPutsEnterOnCancel() {
        FxHarness.run(() -> {
            Alert alert = Dialogs.destructive("Delete").title("Delete docs/CHANGELOG.md from disk?")
                    .action("Delete file").safeDefault().build();
            assertFalse(button(alert, ButtonType.OK).isDefaultButton());
            assertTrue(button(alert, ButtonType.CANCEL).isDefaultButton());
        });
    }

    /** JavaFX's stock warning had OK alone; an "anyway" needs a way to say no. */
    @Test
    void aWarningCanBeDeclined() {
        FxHarness.run(() -> {
            Alert alert = Dialogs.warn("Reword").title("These commits were pushed").action("Reword anyway").build();
            assertEquals(List.of(ButtonType.OK, ButtonType.CANCEL), alert.getButtonTypes());
        });
    }

    @Test
    void anErrorOrANoticeHasOneCloseButton() {
        FxHarness.run(() -> {
            for (Alert alert : List.of(
                    Dialogs.error("Clone").title("Could not clone").build(),
                    Dialogs.info("Updates").title("Lyra is up to date").build())) {
                assertEquals(List.of(ButtonType.OK), alert.getButtonTypes());
                assertEquals("Close", button(alert, ButtonType.OK).getText());
            }
        });
    }

    /** A notice's own "Close" belongs on its own OK, not on a caller's "Open release page". */
    @Test
    void aNoticesCloseDoesNotRelabelCustomButtons() {
        FxHarness.run(() -> {
            ButtonType open = new ButtonType("Open release page", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            Alert alert = Dialogs.info("Updates").title("Lyra 2.1 is available")
                    .buttons(open, ButtonType.CLOSE).build();
            assertEquals("Open release page", button(alert, open).getText());
        });
    }

    /** Several facts to paste each get a well; a report can open with its details showing. */
    @Test
    void severalWellsAndDetailsThatStartOpen() {
        FxHarness.run(() -> {
            Alert alert = Dialogs.error("Host key changed").title("The SSH key of github.com has changed")
                    .code("SAVED", "SHA256:old").code("RECEIVED", "SHA256:new")
                    .details("the raw text").expanded().build();
            assertEquals(2, alert.getDialogPane().lookupAll(".jfx-code-well").size());
            assertTrue(alert.getDialogPane().isExpanded());
        });
    }

    @Test
    void customButtonsAndTheOneMarkedDangerous() {
        FxHarness.run(() -> {
            ButtonType reset = new ButtonType("Reset to the pull request", javafx.scene.control.ButtonBar.ButtonData.YES);
            ButtonType keep = new ButtonType("Keep my branch", javafx.scene.control.ButtonBar.ButtonData.NO);
            Alert alert = Dialogs.warn("Check out").buttons(reset, keep, ButtonType.CANCEL).danger(reset).build();
            assertEquals(List.of(reset, keep, ButtonType.CANCEL), alert.getButtonTypes());
            assertTrue(button(alert, reset).getStyleClass().contains("btn-danger"));
            assertFalse(button(alert, keep).getStyleClass().contains("btn-danger"));
        });
    }

    /** The commits a force push would remove, and the fingerprint to check, are in the body. */
    @Test
    void affectedRowsAndACodeWellAreInTheBody() {
        FxHarness.run(() -> {
            Alert alert = Dialogs.warn("Force push").title("Force push over 2 commits?")
                    .message("They were fetched in the background.")
                    .affected("REMOVED FROM ORIGIN/MAIN", List.of("94883fb Document refunds", "4d0b8f1 Document payments"))
                    .code("RUNS", "git config --global --add safe.directory C:/shop")
                    .build();
            var pane = alert.getDialogPane();
            assertEquals(2, pane.lookupAll(".jfx-affected-row").size());
            assertNotNull(pane.lookup(".jfx-code-well"));
            assertNotNull(pane.lookup(".jfx-code-copy"), "a fact to paste comes with Copy");
            assertEquals("They were fetched in the background.", alert.getContentText());
        });
    }

    /** "Don't ask again" stays in the footer: DialogPane rebuilds it when its buttons change. */
    @Test
    void aFooterLeftControlSurvivesTheFooterBeingBuilt() {
        FxHarness.run(() -> {
            CheckBox again = new CheckBox("Don't ask again");
            Alert alert = Dialogs.destructive("Discard").title("Discard changes to a.txt?")
                    .details("raw text").footerLeft(again).build();
            assertNotNull(alert.getDialogPane().lookup(".button-bar"));
            javafx.scene.control.ButtonBar bar =
                    (javafx.scene.control.ButtonBar) alert.getDialogPane().lookup(".button-bar");
            assertTrue(bar.getButtons().contains(again));
        });
    }

    /**
     * Opening Show Details makes the window taller. JavaFX resizes it to the pane's
     * preferred height, which the shell had pinned before the details were visible.
     */
    @Test
    void showingDetailsGrowsTheWindow() {
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 12; i++) trace.append("    at com.jfx.git.Frame").append(i).append(".run(Frame.java:1)\n");
        Alert alert = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            Alert shown = Dialogs.error("Something went wrong").title("NullPointerException")
                    .message("The application is probably still usable.").details(trace.toString()).build();
            shown.initOwner(owner);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the alert never laid out", () -> alert.getDialogPane().getHeight() > 0);
            double collapsed = FxHarness.call(() -> alert.getDialogPane().getHeight());
            FxHarness.run(() -> alert.getDialogPane().setExpanded(true));
            RobotWait.untilOnFx("the window never grew for the details",
                    () -> alert.getDialogPane().getHeight() > collapsed + 100);
        } finally {
            FxHarness.run(alert::hide);
        }
    }

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    @Test
    void aPromptRefusesBlankAndSaysWhatIsWrong() {
        FxHarness.run(() -> {
            Dialog<String> dialog = Dialogs.prompt("New branch")
                    .title("Create a branch").label("Branch name")
                    .validator(text -> text.contains(" ") ? "Branch names cannot contain spaces." : null)
                    .action("Create branch").build();
            TextField field = (TextField) dialog.getDialogPane().lookup(".jfx-prompt-field");
            Button ok = button(dialog, ButtonType.OK);
            Label error = (Label) dialog.getDialogPane().lookup(".jfx-field-error");

            assertEquals("Create branch", ok.getText());
            assertTrue(ok.isDisabled(), "blank is refused");
            assertFalse(error.isVisible(), "without a message: there is nothing to correct yet");

            field.setText("bad name");
            assertTrue(ok.isDisabled());
            assertEquals("Branch names cannot contain spaces.", error.getText());
            assertTrue(error.isVisible());

            field.setText("feature/search");
            assertFalse(ok.isDisabled());
            assertFalse(error.isVisible());
            assertEquals("feature/search", dialog.getResultConverter().call(ButtonType.OK));
            assertNull(dialog.getResultConverter().call(ButtonType.CANCEL));
        });
    }

    /** A rename selects the part that usually changes. */
    @Test
    void aRenameSelectsTheLastSegment() {
        Dialog<String> dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            Dialog<String> shown = Dialogs.prompt("Rename branch").initial("bugfix/rounding")
                    .select(Dialogs.Selection.LAST_SEGMENT).action("Rename").build();
            shown.initOwner(owner);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the last segment was never selected", () -> {
                TextField field = (TextField) dialog.getDialogPane().lookup(".jfx-prompt-field");
                return "rounding".equals(field.getSelectedText());
            });
        } finally {
            FxHarness.run(dialog::hide);
        }
    }

    // ------------------------------------------------------------------
    // Choice
    // ------------------------------------------------------------------

    @Test
    void aChoiceIsCardsAndAnswersWithTheOptionChosen() {
        FxHarness.run(() -> {
            Dialog<Integer> dialog = Dialogs.choose("Revert merge", List.of(1, 2))
                    .text(n -> "Parent " + n)
                    .description(n -> n == 1 ? "The branch that was merged into" : "The branch that was merged")
                    .action("Revert").build();
            var cards = dialog.getDialogPane().lookupAll(".jfx-option-card");
            assertEquals(2, cards.size());
            assertEquals(2, dialog.getDialogPane().lookupAll(".t-caption").size(), "each says what it means");

            RadioButton second = (RadioButton) dialog.getDialogPane().lookupAll(".jfx-option-radio")
                    .stream().filter(n -> "Parent 2".equals(((RadioButton) n).getText())).findFirst().orElseThrow();
            second.setSelected(true);
            assertEquals(2, dialog.getResultConverter().call(ButtonType.OK));
            assertEquals("Revert", button(dialog, ButtonType.OK).getText());
        });
    }
}
