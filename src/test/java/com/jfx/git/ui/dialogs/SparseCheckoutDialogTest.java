package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.FxHarness;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The picker {@code SidebarPane}'s "Sparse Checkout…" menu item opens. Covers only the
 * form's own wiring - which checkboxes exist and are pre-selected, when Apply and "Turn
 * off sparse checkout" are reachable, what the footer's verb says, and what each returns -
 * not the real git plumbing, which {@code NativeGitBackendTest}'s own sparse-checkout
 * cases already prove against a real repository.
 */
class SparseCheckoutDialogTest {

    /** Builds and lays out a dialog without showing it - the same technique {@code CreatePullRequestDialogTest} uses. */
    private SparseCheckoutDialog dialog(List<String> topLevelDirectories, List<String> currentlySelected,
                                         boolean currentlyEnabled) {
        return FxHarness.call(() -> {
            SparseCheckoutDialog d = new SparseCheckoutDialog(topLevelDirectories, currentlySelected, currentlyEnabled);
            d.getDialogPane().applyCss();
            d.getDialogPane().layout();
            return d;
        });
    }

    private Node applyButton(SparseCheckoutDialog dialog) {
        return dialog.getDialogPane().lookupButton(SparseCheckoutDialog.APPLY);
    }

    private Node disableButton(SparseCheckoutDialog dialog) {
        return dialog.getDialogPane().lookupButton(SparseCheckoutDialog.DISABLE);
    }

    private List<CheckBox> checkBoxes(SparseCheckoutDialog dialog) {
        return dialog.foldersForTest();
    }

    private String applyLabel(SparseCheckoutDialog dialog) {
        return ((Button) applyButton(dialog)).getText();
    }

    @Test
    void listsOneCheckboxPerTopLevelDirectoryNoneSelectedByDefault() {
        SparseCheckoutDialog dialog = dialog(List.of("src", "docs"), List.of(), false);
        FxHarness.run(() -> {
            List<CheckBox> boxes = checkBoxes(dialog);
            assertEquals(List.of("src", "docs"), boxes.stream().map(CheckBox::getText).toList());
            assertTrue(boxes.stream().noneMatch(CheckBox::isSelected));
        });
    }

    @Test
    void preSelectsWhicheverDirectoriesAreAlreadyInEffect() {
        SparseCheckoutDialog dialog = dialog(List.of("src", "docs"), List.of("src"), true);
        FxHarness.run(() -> {
            List<CheckBox> boxes = checkBoxes(dialog);
            assertTrue(boxes.stream().filter(b -> b.getText().equals("src")).findFirst().orElseThrow().isSelected());
            assertFalse(boxes.stream().filter(b -> b.getText().equals("docs")).findFirst().orElseThrow().isSelected());
        });
    }

    @Test
    void applyIsDisabledUntilAtLeastOneDirectoryIsChecked() {
        SparseCheckoutDialog dialog = dialog(List.of("src", "docs"), List.of(), false);
        FxHarness.run(() -> {
            assertTrue(applyButton(dialog).isDisabled(), "nothing checked - narrowing to nothing is not a thing");

            checkBoxes(dialog).get(0).setSelected(true);
            assertFalse(applyButton(dialog).isDisabled());

            checkBoxes(dialog).get(0).setSelected(false);
            assertTrue(applyButton(dialog).isDisabled());
        });
    }

    @Test
    void applyIsDisabledWhenThereAreNoTopLevelDirectoriesAtAll() {
        SparseCheckoutDialog dialog = dialog(List.of(), List.of(), false);
        FxHarness.run(() -> assertTrue(applyButton(dialog).isDisabled()));
    }

    @Test
    void theVerbCountsTheFoldersTicked() {
        SparseCheckoutDialog dialog = dialog(List.of("src", "docs", "tests"), List.of(), false);
        FxHarness.run(() -> {
            assertEquals("Check out folders", applyLabel(dialog), "nothing ticked: no count to give");
            checkBoxes(dialog).get(0).setSelected(true);
            assertEquals("Check out 1 folder", applyLabel(dialog));
            checkBoxes(dialog).get(2).setSelected(true);
            assertEquals("Check out 2 folders", applyLabel(dialog));
        });
    }

    @Test
    void selectAllTicksEveryFolderAndThenNone() {
        SparseCheckoutDialog dialog = dialog(List.of("src", "docs"), List.of("src"), true);
        FxHarness.run(() -> {
            Button all = (Button) dialog.getDialogPane().lookup("#" + SparseCheckoutDialog.SELECT_ALL_ID);
            assertEquals("Select all", all.getText());
            all.fire();
            assertTrue(checkBoxes(dialog).stream().allMatch(CheckBox::isSelected));
            assertEquals("Select none", all.getText());
            all.fire();
            assertTrue(checkBoxes(dialog).stream().noneMatch(CheckBox::isSelected));
            assertTrue(applyButton(dialog).isDisabled(), "and none ticked is still not a thing");
        });
    }

    @Test
    void turningOffIsOnlyOfferedWhenSparseCheckoutIsAlreadyOn() {
        SparseCheckoutDialog off = dialog(List.of("src"), List.of(), false);
        FxHarness.run(() -> {
            assertNull(disableButton(off), "nothing to turn off yet, so no button for it");
            assertFalse(off.getDialogPane().getButtonTypes().contains(SparseCheckoutDialog.DISABLE));
        });

        SparseCheckoutDialog on = dialog(List.of("src"), List.of("src"), true);
        FxHarness.run(() -> {
            assertNotNull(disableButton(on));
            assertFalse(disableButton(on).isDisabled());
            assertEquals("Turn off sparse checkout", ((Button) disableButton(on)).getText());
        });
    }

    @Test
    void confirmingApplyReturnsExactlyTheCheckedDirectories() {
        SparseCheckoutDialog dialog = dialog(List.of("src", "docs", "tests"), List.of(), false);
        SparseCheckoutDialog.Result result = FxHarness.call(() -> {
            List<CheckBox> boxes = checkBoxes(dialog);
            boxes.get(0).setSelected(true);
            boxes.get(2).setSelected(true);
            return dialog.getResultConverter().call(SparseCheckoutDialog.APPLY);
        });

        assertInstanceOf(SparseCheckoutDialog.Apply.class, result);
        assertEquals(List.of("src", "tests"), ((SparseCheckoutDialog.Apply) result).paths());
    }

    @Test
    void confirmingDisableReturnsDisableRegardlessOfCheckboxes() {
        SparseCheckoutDialog dialog = dialog(List.of("src"), List.of("src"), true);
        SparseCheckoutDialog.Result result = FxHarness.call(() ->
                dialog.getResultConverter().call(SparseCheckoutDialog.DISABLE));

        assertInstanceOf(SparseCheckoutDialog.Disable.class, result);
    }

    @Test
    void cancellingReturnsNoResult() {
        SparseCheckoutDialog dialog = dialog(List.of("src"), List.of(), false);
        SparseCheckoutDialog.Result result = FxHarness.call(() ->
                dialog.getResultConverter().call(javafx.scene.control.ButtonType.CANCEL));

        assertNull(result);
    }
}
