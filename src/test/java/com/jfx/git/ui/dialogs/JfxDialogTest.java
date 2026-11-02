package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared dialog shell.
 *
 * <p>Its whole value is that a dialog going through it cannot make the mistakes the
 * hand-built ones made: an invented size, a forgotten stylesheet, a label column that
 * ellipsizes. Each of those is asserted here once, so a dialog that adopts the shell
 * inherits the guarantee rather than having to be tested for it again.
 */
class JfxDialogTest {

    /**
     * Closes the off-screen stages this class opened.
     *
     * <p>Not housekeeping. A stage left showing goes on taking part in focus and layout
     * for the rest of the run, and the failures that causes land in whichever robot test
     * happens to follow - a click that reaches nothing, a dialog that "never opened".
     */
    @org.junit.jupiter.api.AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    @Test
    void thePresetsAreOrderedAndDistinct() {
        JfxDialog.Size[] ladder = {JfxDialog.Size.SM, JfxDialog.Size.MD,
                JfxDialog.Size.LG, JfxDialog.Size.XL};
        for (int i = 1; i < ladder.length; i++) {
            assertTrue(ladder[i].width() > ladder[i - 1].width(),
                    ladder[i] + " should be wider than " + ladder[i - 1]);
        }
        assertTrue(JfxDialog.Size.XL.height() > JfxDialog.Size.LG.height(),
                "the two workspace presets that pin a height should still be a ladder");
    }

    /** FIT exists so a dialog whose height depends on what it found is not forced. */
    @Test
    void fitPinsNothing() {
        assertFalse(JfxDialog.Size.FIT.isFixed());
        assertTrue(JfxDialog.Size.SM.isFixed());
    }

    /**
     * Only the workspaces pin a height. A short form held to a preset height floated over
     * a band of empty space - New tag had 118px of it above its buttons.
     */
    @Test
    void onlyTheWorkspacePresetsPinAHeight() {
        assertFalse(JfxDialog.Size.SM.pinsHeight());
        assertFalse(JfxDialog.Size.MD.pinsHeight());
        assertTrue(JfxDialog.Size.LG.pinsHeight());
        assertTrue(JfxDialog.Size.XL.pinsHeight());
    }

    /** WIDE is LG's width with the height left to the content, for a list that grows. */
    @Test
    void wideIsAsWideAsLgAndAsTallAsItsContent() {
        assertEquals(JfxDialog.Size.LG.width(), JfxDialog.Size.WIDE.width());
        assertTrue(JfxDialog.Size.WIDE.isFixed());
        assertFalse(JfxDialog.Size.WIDE.pinsHeight());
    }

    @Test
    void shellAppliesThePresetAndTheTitle() {
        FxHarness.run(() -> {
            Dialog<Void> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.MD, "Repository settings");

            assertEquals("Repository settings", dialog.getTitle());
            assertEquals(JfxDialog.Size.MD.width(), dialog.getDialogPane().getPrefWidth());
            assertEquals(javafx.scene.layout.Region.USE_COMPUTED_SIZE,
                    dialog.getDialogPane().getPrefHeight(),
                    "an MD dialog's height is measured from its content when it opens");

            Dialog<Void> workspace = new Dialog<>();
            JfxDialog.shell(workspace, JfxDialog.Size.LG, "Interactive rebase");
            assertEquals(JfxDialog.Size.LG.height(), workspace.getDialogPane().getPrefHeight());
        });
    }

    /**
     * An SM dialog opens exactly as tall as its content at its own width - wrapped text
     * included, which JavaFX on its own measures as a single line.
     */
    @Test
    void aShownSmDialogIsAsTallAsItsWrappedContent() {
        String longHint = "The branch starts at the commit this stash was taken from, so the stash "
                + "applies cleanly there even when it no longer does here. It is checked out, the "
                + "stash is applied - staged changes staged again - and then dropped.";
        Dialog<Void> dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            Dialog<Void> shown = new Dialog<>();
            shown.initOwner(owner);
            JfxDialog.shell(shown, JfxDialog.Size.SM, "Fit");
            Label hint = new Label(longHint);
            hint.setWrapText(true);
            shown.getDialogPane().setContent(new VBox(8, new TextField(), hint));
            shown.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the dialog never laid out", () -> dialog.getDialogPane().getHeight() > 0);
            FxHarness.run(() -> {
                var pane = dialog.getDialogPane();
                Label hint = (Label) ((VBox) pane.getContent()).getChildren().get(1);
                assertTrue(hint.getHeight() + 0.5 >= hint.prefHeight(hint.getWidth()),
                        "the hint is cut: given " + hint.getHeight() + "px, needs "
                                + hint.prefHeight(hint.getWidth()));
                assertEquals(pane.prefHeight(pane.getWidth()), pane.getHeight(), 1.0,
                        "and the window is no taller than its content needs");
            });
        } finally {
            FxHarness.run(() -> dialog.getDialogPane().getScene().getWindow().hide());
        }
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    /** The shell's header replaces JavaFX's band and its glossy bitmap. */
    @Test
    void theHeaderReplacesTheStockOne() {
        FxHarness.run(() -> {
            javafx.scene.control.Alert alert =
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
            JfxDialog.shell(alert, JfxDialog.Size.SM, "Delete branch");
            JfxDialog.Header header = JfxDialog.header(alert, new javafx.scene.layout.Region(),
                    JfxDialog.Tone.DESTRUCTIVE, "Delete feature/search?", "Local branch");

            assertSame(header.node(), alert.getDialogPane().getHeader());
            assertNull(alert.getGraphic(), "no glossy question mark beside the new header");
            assertSame(header, JfxDialog.headerOf(alert));
            assertEquals(JfxDialog.Tone.DESTRUCTIVE, header.tone());
        });
    }

    /**
     * Backticks set a SHA or a path in the monospace face; the header text anything else
     * reads - assistive technology, a test - is the plain words.
     */
    @Test
    void backticksSetMonospaceRunsAndTheHeaderTextIsPlain() {
        FxHarness.run(() -> {
            Dialog<Void> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, "Reword");
            JfxDialog.Header header = JfxDialog.header(dialog, null, JfxDialog.Tone.NEUTRAL,
                    "Reword `a64205a`", "On `main` · two later commits");

            assertEquals("Reword a64205a", dialog.getHeaderText());
            assertEquals("Reword a64205a", header.titleText());
            assertEquals("On main · two later commits", header.subtitleText());

            javafx.scene.text.TextFlow title = (javafx.scene.text.TextFlow)
                    header.node().lookup(".jfx-dialog-title");
            var runs = title.getChildren().stream()
                    .map(n -> (javafx.scene.text.Text) n).toList();
            assertEquals(2, runs.size());
            assertFalse(runs.get(0).getStyleClass().contains("jfx-dialog-mono"));
            assertTrue(runs.get(1).getStyleClass().contains("jfx-dialog-mono"),
                    "the SHA is the monospace run");
        });
    }

    /** No subtitle, no empty line under the title. */
    @Test
    void aMissingSubtitleTakesNoRoom() {
        FxHarness.run(() -> {
            JfxDialog.Header header = JfxDialog.header(null, JfxDialog.Tone.INFO, "About", null);
            javafx.scene.Node subtitle = header.node().lookup(".jfx-dialog-subtitle");
            assertFalse(subtitle.isManaged());
            assertFalse(subtitle.isVisible());
        });
    }

    // ------------------------------------------------------------------
    // Footer
    // ------------------------------------------------------------------

    /**
     * The first button whose role makes it the default is the filled primary; the rest
     * are not, and none is stretched to the widest one's width.
     */
    @Test
    void theFirstDefaultRoleIsPrimaryAndNothingIsStretched() {
        FxHarness.run(() -> {
            Dialog<Void> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, "Sparse checkout");
            ButtonType apply = new ButtonType("Apply", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            ButtonType disable = new ButtonType("Turn off sparse checkout",
                    javafx.scene.control.ButtonBar.ButtonData.OTHER);
            dialog.getDialogPane().getButtonTypes().addAll(apply, disable, ButtonType.CANCEL);

            var pane = dialog.getDialogPane();
            assertTrue(pane.lookupButton(apply).getStyleClass().contains("btn-primary"));
            assertFalse(pane.lookupButton(disable).getStyleClass().contains("btn-primary"));
            assertFalse(pane.lookupButton(ButtonType.CANCEL).getStyleClass().contains("btn-primary"));
            for (ButtonType type : pane.getButtonTypes()) {
                assertFalse(javafx.scene.control.ButtonBar.isButtonUniformSize(pane.lookupButton(type)),
                        type.getText() + " would be stretched to the widest label");
            }
        });
    }

    /** A destructive confirm is red and is not also the filled primary. */
    @Test
    void dangerReplacesPrimary() {
        FxHarness.run(() -> {
            Dialog<Void> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, "Delete branch");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            JfxDialog.danger(dialog, ButtonType.OK);

            var classes = dialog.getDialogPane().lookupButton(ButtonType.OK).getStyleClass();
            assertTrue(classes.contains("btn-danger"));
            assertFalse(classes.contains("btn-primary"));
        });
    }

    /** A verb replaces OK on screen while the button type - what callers test for - stays. */
    @Test
    void aVerbRelabelsTheButtonButKeepsItsType() {
        FxHarness.run(() -> {
            Dialog<Void> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, "New tag");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            JfxDialog.verb(dialog, ButtonType.OK, "Create tag");

            assertEquals("Create tag",
                    ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).getText());
            assertTrue(dialog.getDialogPane().getButtonTypes().contains(ButtonType.OK));
        });
    }

    /**
     * When Cancel is the default - the safe answer to a risky question - it is ringed,
     * not filled. Primer fills whichever button is default, which painted Cancel as the
     * recommended action and the real one grey.
     */
    @Test
    void aSafeDefaultIsNotFilledLikeThePrimary() {
        Dialog<Void> dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            Dialog<Void> shown = new Dialog<>();
            shown.initOwner(owner);
            JfxDialog.shell(shown, JfxDialog.Size.SM, "Force push");
            ButtonType go = new ButtonType("Push anyway", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            shown.getDialogPane().getButtonTypes().addAll(go, ButtonType.CANCEL);
            ((javafx.scene.control.Button) shown.getDialogPane().lookupButton(go)).setDefaultButton(false);
            ((javafx.scene.control.Button) shown.getDialogPane().lookupButton(ButtonType.CANCEL))
                    .setDefaultButton(true);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the dialog never laid out", () -> dialog.getDialogPane().getHeight() > 0);
            FxHarness.run(() -> {
                var pane = dialog.getDialogPane();
                pane.applyCss();
                var primary = topFill(pane.lookupButton(pane.getButtonTypes().get(0)));
                var cancel = topFill(pane.lookupButton(ButtonType.CANCEL));
                assertNotEquals(primary, cancel,
                        "the default Cancel is filled like the primary, so it reads as the recommended action");
            });
        } finally {
            FxHarness.run(() -> dialog.getDialogPane().getScene().getWindow().hide());
        }
    }

    /** The colour a button is filled with: its topmost background layer. */
    private static javafx.scene.paint.Paint topFill(javafx.scene.Node button) {
        var fills = ((javafx.scene.layout.Region) button).getBackground().getFills();
        return fills.get(fills.size() - 1).getFill();
    }

    /**
     * Focus starts in the first field. JavaFX left it on a footer button, so Enter in Add
     * remote closed the dialog before anything had been typed.
     */
    @Test
    void focusStartsInTheFirstField() {
        Dialog<Void> dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            Dialog<Void> shown = new Dialog<>();
            shown.initOwner(owner);
            JfxDialog.shell(shown, JfxDialog.Size.SM, "Add remote");
            TextField name = new TextField();
            name.setId("first-field");
            shown.getDialogPane().setContent(new VBox(8, new Label("Name"), name, new TextField()));
            shown.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("focus never reached the first field", () -> {
                var owner = dialog.getDialogPane().getScene().getFocusOwner();
                return owner != null && "first-field".equals(owner.getId());
            });
        } finally {
            FxHarness.run(() -> dialog.getDialogPane().getScene().getWindow().hide());
        }
    }

    /** Field-finding skips what cannot take input and looks inside containers. */
    @Test
    void theFirstInputIsTheFirstControlThatTakesTyping() {
        FxHarness.run(() -> {
            TextField disabled = new TextField();
            disabled.setDisable(true);
            TextField wanted = new TextField();
            VBox content = new VBox(new Label("Name"), disabled, new VBox(wanted), new TextField());
            assertSame(wanted, JfxDialog.firstInput(content));
        });
    }

    /**
     * The preset is a floor for the content, not a cap on it.
     *
     * <p>A capped pane is what clipped the Integrations window's own Close button.
     */
    @Test
    void thePresetDoesNotCapTheContent() {
        FxHarness.run(() -> {
            Dialog<Void> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, "Small");
            // USE_COMPUTED_SIZE (-1), not the preset: the shell sets a *preferred* size
            // and leaves the maximum alone, so content that needs more still gets it.
            assertEquals(javafx.scene.layout.Region.USE_COMPUTED_SIZE,
                    dialog.getDialogPane().getMaxWidth(),
                    "the preset must not become a ceiling");
            assertNotEquals(JfxDialog.Size.SM.width(), dialog.getDialogPane().getMaxWidth(),
                    "a pane capped at its preset is what clipped the Integrations footer");
        });
    }

    /**
     * And a shown dialog reaches its preset.
     *
     * <p>AtlantaFX Primer caps every dialog pane at 600px wide. Nothing lifted it, so an
     * XL dialog opened at its 720px floor and the merge tool cut its own toolbar labels.
     * The cap lives in the stylesheets, so only a dialog shown the way the application
     * shows one - owned, themed, on screen - runs into it.
     */
    @Test
    void aShownXlDialogIsAsWideAsItsPreset() {
        Dialog<Void> dialog = FxHarness.call(() -> {
            Stage owner = FxHarness.showRegistered(new VBox(), 400, 300);
            Dialog<Void> shown = new Dialog<>();
            shown.initOwner(owner);
            JfxDialog.shell(shown, JfxDialog.Size.XL, "Workspace");
            shown.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            shown.show();
            return shown;
        });
        try {
            RobotWait.untilOnFx("the dialog never laid out", () -> dialog.getDialogPane().getWidth() > 0);
            double width = FxHarness.call(() -> dialog.getDialogPane().getWidth());
            assertEquals(JfxDialog.Size.XL.width(), width, 0.5,
                    "an XL dialog held to its floor is Primer's 600px cap winning");
        } finally {
            FxHarness.run(() -> dialog.getDialogPane().getScene().getWindow().hide());
        }
    }

    /**
     * Going through the shell is what attaches the stylesheets.
     *
     * <p>The failure this prevents is silent: a dialog that skips theming still renders,
     * because AtlantaFX is the user-agent stylesheet — it simply ignores every
     * application style class and -jfx- token inside it.
     */
    @Test
    void shellThemesTheDialog() {
        FxHarness.run(() -> {
            Dialog<Void> unshelled = new Dialog<>();
            assertTrue(unshelled.getDialogPane().getStylesheets().isEmpty(),
                    "a bare dialog starts with no application stylesheets");

            Dialog<Void> dialog = new Dialog<>();
            JfxDialog.shell(dialog, JfxDialog.Size.SM, "Themed");
            assertFalse(dialog.getDialogPane().getStylesheets().isEmpty(),
                    "the shell must attach the application stylesheets");
        });
    }

    // ------------------------------------------------------------------
    // The form grid
    // ------------------------------------------------------------------

    @Test
    void theLabelColumnHoldsItsFloor() {
        FxHarness.run(() -> {
            GridPane form = JfxDialog.form();
            JfxDialog.addRow(form, "Name", new TextField());

            Stage stage = FxHarness.show(new VBox(form), 700, 200);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(form);

            // The *column* holds the floor, not the label inside it: a grid does not
            // stretch a child to fill its column, and it does not need to. What produces
            // alignment down a page is where the control starts, so that is what is
            // asserted - the label is free to be exactly as wide as its own text.
            javafx.scene.Node control = form.getChildren().get(1);
            assertTrue(control.getLayoutX() >= JfxDialog.LABEL_COLUMN - 0.5,
                    "the control column should start at the shared width, began at "
                            + control.getLayoutX());
        });
    }

    /**
     * The point of the whole exercise: a label longer than the floor is given its width
     * rather than an ellipsis.
     */
    @Test
    void aLabelLongerThanTheFloorIsNotTruncated() {
        FxHarness.run(() -> {
            GridPane form = JfxDialog.form();
            JfxDialog.addRow(form, "A preference label considerably longer than the floor",
                    new TextField());

            Stage stage = FxHarness.show(new VBox(form), 900, 200);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(form);

            Label label = (Label) form.getChildren().get(0);
            assertTrue(label.getWidth() + 0.5 >= label.prefWidth(-1),
                    () -> "given " + label.getWidth() + "px, needs " + label.prefWidth(-1));
            assertTrue(label.getWidth() > JfxDialog.LABEL_COLUMN,
                    "and the column must have grown past the floor to do it");
        });
    }

    /** Helper text is a step down the scale, not a second label. */
    @Test
    void hintsAreOneStepDownFromTheirLabel() {
        FxHarness.run(() -> {
            GridPane form = JfxDialog.form();
            JfxDialog.addRow(form, "Default branch", new TextField());
            JfxDialog.addHint(form, "The initial branch for a repository you create here.");

            Stage stage = FxHarness.show(new VBox(form), 700, 220);
            ThemeManager.register(stage.getScene());
            FxHarness.pulse(form);

            Label label = (Label) form.getChildren().get(0);
            Label hint = (Label) form.getChildren().get(2);
            assertTrue(hint.getFont().getSize() < label.getFont().getSize(),
                    "a hint at the same size as its label gives the reader no hierarchy: "
                            + hint.getFont().getSize() + " vs " + label.getFont().getSize());
        });
    }

    /** Hints sit under the control they explain, never under the label column. */
    @Test
    void hintsAreIndentedToTheControlColumn() {
        GridPane form = JfxDialog.form();
        JfxDialog.addRow(form, "Interval", new TextField());
        JfxDialog.addHint(form, "Fetches every remote on this schedule.");

        Label hint = (Label) form.getChildren().get(2);
        assertEquals(1, GridPane.getColumnIndex(hint),
                "a hint belongs beneath its control, not beneath its label");
    }

    @Test
    void addRowReportsTheRowItUsed() {
        GridPane form = JfxDialog.form();
        assertEquals(0, JfxDialog.addRow(form, "First", new TextField()));
        assertEquals(1, JfxDialog.addRow(form, "Second", new TextField()));
    }

    /** Labels carry no colon: a label in its own column already reads as one. */
    @Test
    void labelsCarryNoPunctuation() {
        GridPane form = JfxDialog.form();
        JfxDialog.addRow(form, "Name", new TextField());
        assertEquals("Name", ((Label) form.getChildren().get(0)).getText());
    }
}
