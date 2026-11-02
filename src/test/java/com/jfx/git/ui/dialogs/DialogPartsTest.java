package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;

import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts a dialog's body is built from, and a dialog that runs its action in place.
 */
class DialogPartsTest {

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    /** Label above, control at full width, hint under it, and the error in the same place. */
    @Test
    void aFieldStacksItsLabelControlHintAndError() {
        FxHarness.run(() -> {
            TextField name = new TextField();
            DialogParts.Field field = DialogParts.field("Tag name", name).optional().hint("Shown in the graph.");
            VBox node = (VBox) field.node();

            Label label = (Label) node.lookup(".t-label");
            assertEquals("Tag name", label.getText());
            assertSame(name, label.getLabelFor());
            assertNotNull(node.lookup(".jfx-field-optional"));
            Label hint = (Label) node.lookup(".t-caption");
            assertTrue(hint.isVisible());
            assertSame(name, field.control());

            Label error = (Label) node.lookup(".jfx-field-error");
            assertFalse(error.isVisible(), "no error until there is one");
            field.error("A tag named v1.0 already exists.");
            assertTrue(error.isVisible());
            assertTrue(name.getPseudoClassStates().contains(PseudoClass.getPseudoClass("danger")),
                    "the field itself is outlined in red too");
            field.error(null);
            assertFalse(error.isVisible());
            assertFalse(name.getPseudoClassStates().contains(PseudoClass.getPseudoClass("danger")));
        });
    }

    @Test
    void aFieldKeepsItsTrailingButtonOnTheSameRow() {
        FxHarness.run(() -> {
            TextField path = new TextField();
            Button browse = new Button("Browse…");
            DialogParts.Field field = DialogParts.field("Clone into", path).trailing(browse);
            assertSame(path.getParent(), browse.getParent(), "Browse sits beside the field, not under it");
        });
    }

    /** A wrapped control - a secret's pair - is outlined where it is drawn, on its inputs. */
    @Test
    void anErrorOutlinesTheInputsInsideAWrappedControl() {
        FxHarness.run(() -> {
            DialogParts.Secret token = new DialogParts.Secret("token");
            DialogParts.Field field = DialogParts.field("Personal access token", token.node).trailing(token.reveal);
            PseudoClass danger = PseudoClass.getPseudoClass("danger");
            field.error("GitHub did not accept this token.");
            assertTrue(token.hidden.getPseudoClassStates().contains(danger));
            assertTrue(token.shown.getPseudoClassStates().contains(danger));
            Label label = (Label) field.node().lookup(".t-label");
            assertSame(token.hidden, label.getLabelFor(), "the label belongs to the field inside the wrapper");
        });
    }

    /** Revealing a secret shows the same text, and hiding it again keeps it. */
    @Test
    void aSecretKeepsItsTextShownOrHidden() {
        FxHarness.run(() -> {
            DialogParts.Secret secret = new DialogParts.Secret("passphrase");
            secret.hidden.setText("correct horse");
            secret.reveal.setSelected(true);
            assertTrue(secret.shown.isVisible());
            assertEquals("correct horse", secret.shown.getText());
            secret.shown.appendText(" battery");
            secret.reveal.setSelected(false);
            assertEquals("correct horse battery", secret.text());
        });
    }

    @Test
    void aPairPutsTwoFieldsSideBySideAtHalfTheWidth() {
        FxHarness.run(() -> {
            var left = DialogParts.field("Branch", new TextField());
            var right = DialogParts.field("Clone depth", new TextField());
            var row = DialogParts.pair(left, right);
            assertEquals(2, row.getColumnConstraints().size());
            assertEquals(50, row.getColumnConstraints().get(0).getPercentWidth());
            assertEquals(Integer.valueOf(1), javafx.scene.layout.GridPane.getColumnIndex(right.node()));
        });
    }

    @Test
    void optionCardsReportTheOptionChosen() {
        FxHarness.run(() -> {
            DialogParts.OptionCards<String> cards = new DialogParts.OptionCards<>(
                    List.of("default", "ff-only", "no-ff", "squash"), s -> s, s -> "about " + s).twoColumns();
            assertNull(cards.selected());
            cards.select("squash");
            assertEquals("squash", cards.selected());
            cards.radioFor("no-ff").setSelected(true);
            assertEquals("no-ff", cards.selectedProperty().get());
            assertEquals(4, cards.node().lookupAll(".jfx-option-card").size());
        });
    }

    /** A framed list is as tall as its rows, between a floor and a ceiling. */
    @Test
    void aFramedListIsAsTallAsItsRows() {
        FxHarness.run(() -> {
            ListView<String> list = new ListView<>(FXCollections.observableArrayList("a", "b"));
            DialogParts.framed(list, s -> new DialogParts.Row(null, s), 3, 8, false);
            assertEquals(3 * DialogParts.ROW + 4, list.getPrefHeight(), "the floor: three rows");
            list.getItems().setAll("a", "b", "c", "d", "e");
            assertEquals(5 * DialogParts.ROW + 4, list.getPrefHeight());
            list.getItems().setAll(java.util.Collections.nCopies(30, "x"));
            assertEquals(8 * DialogParts.ROW + 4, list.getPrefHeight(), "the ceiling: eight, then it scrolls");
            assertTrue(list.getStyleClass().contains("jfx-framed-list"));
        });
    }

    /** "Don't ask again" survives the footer being rebuilt when a button is added. */
    @Test
    void aFooterLeftNodeSurvivesTheFooterBeingRebuilt() {
        FxHarness.run(() -> {
            Dialog<Void> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, "Discard");
            dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
            CheckBox again = new CheckBox("Don't ask again");
            JfxDialog.footerLeft(dialog, again);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
            ButtonBar bar = (ButtonBar) dialog.getDialogPane().lookup(".button-bar");
            assertTrue(bar.getButtons().contains(again));
        });
    }

    /**
     * The action runs with the dialog open: a failure leaves it open with the reason in the
     * footer and the input intact; success closes it with the action's result.
     */
    @Test
    void anActionInPlaceKeepsTheDialogUntilItWorks() {
        AtomicReference<JfxDialog.Progress> running = new AtomicReference<>();
        Dialog<String> dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            Dialog<String> shown = new Dialog<>();
            shown.initOwner(owner);
            JfxDialog.shell(shown, JfxDialog.Size.SM, "Clone");
            TextField url = new TextField("https://example.com/repo.git");
            shown.getDialogPane().setContent(new VBox(url));
            shown.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            shown.setResultConverter(b -> b == ButtonType.OK ? url.getText() : null);
            JfxDialog.actInPlace(shown, ButtonType.OK, "Cloning…", running::set);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the dialog never laid out", () -> dialog.getDialogPane().getHeight() > 0);
            Button ok = (Button) FxHarness.call(() -> dialog.getDialogPane().lookupButton(ButtonType.OK));
            FxHarness.run(ok::fire);
            assertNotNull(running.get(), "pressing the action starts the work");
            FxHarness.run(() -> {
                assertTrue(dialog.isShowing(), "and does not close the dialog");
                assertTrue(dialog.getDialogPane().getContent().isDisabled(), "the body is locked while it runs");
                assertTrue(ok.isDisabled());
            });

            FxHarness.run(() -> running.get().failed("Repository not found."));
            FxHarness.run(() -> {
                assertTrue(dialog.isShowing());
                assertFalse(dialog.getDialogPane().getContent().isDisabled(), "unlocked to try again");
                Label status = (Label) dialog.getDialogPane().lookup(".jfx-footer-status > .label");
                assertEquals("Repository not found.", status.getText());
                assertTrue(status.getParent().getStyleClass().contains("failed"));
            });

            FxHarness.run(ok::fire);
            FxHarness.run(() -> running.get().succeeded());
            RobotWait.untilOnFx("success never closed the dialog", () -> !dialog.isShowing());
            assertEquals("https://example.com/repo.git", FxHarness.call(dialog::getResult));
        } finally {
            FxHarness.run(() -> {
                if (dialog.isShowing()) dialog.close();
            });
        }
    }
}
