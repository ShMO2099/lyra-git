package com.jfx.git.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The frame the right-hand panel builds its four modes into.
 *
 * <p>The panel is rebuilt on every stage, unstage and selection change, so the things
 * worth pinning down here are about repetition rather than appearance: that rebuilding
 * does not accumulate, and that a mode always says which one it is.
 */
class DetailPanelTest {

    private static String headerText(DetailPanel panel, String styleClass) {
        var found = FxHarness.lookupAll(panel, styleClass);
        return found.isEmpty() ? null : ((Label) found.get(0)).getText();
    }

    @Test
    void startsWithNothingSelectedAndNoHeader() {
        FxHarness.run(() -> {
            DetailPanel panel = new DetailPanel();

            assertFalse(FxHarness.lookupAll(panel, "mode-header").get(0).isManaged(),
                    "with nothing selected there is no mode to name");
            assertFalse(panel.body().getChildren().isEmpty(),
                    "the empty state should still say what to do");
        });
    }

    @Test
    void eachModeNamesItself() {
        FxHarness.run(() -> {
            DetailPanel panel = new DetailPanel();

            panel.setMode(DetailPanel.Mode.COMMIT, "Commit", "8878ebd", "2 files");
            assertEquals("Commit", headerText(panel, "mode-header-title"));
            assertEquals("8878ebd", headerText(panel, "mode-header-subject"));
            assertEquals("2 files", headerText(panel, "count-badge"));

            panel.setMode(DetailPanel.Mode.WORKING_TREE, "Working tree", null, "3 changes");
            assertEquals("Working tree", headerText(panel, "mode-header-title"));

            var subject = FxHarness.lookupAll(panel, "mode-header-subject").get(0);
            assertFalse(subject.isManaged(),
                    "the working tree is not a commit, so it has nothing to name");
        });
    }

    /**
     * A stash borrows the amber the graph draws stash rows in.
     *
     * <p>And gives it back: switching to another mode must drop the class, or every panel
     * after the first stash keeps the stash colour.
     */
    @Test
    void theStashMarkIsAmberAndOnlyForStashes() {
        FxHarness.run(() -> {
            DetailPanel panel = new DetailPanel();

            panel.setMode(DetailPanel.Mode.STASH, "Stash", "stash@{0}", null);
            var glyph = FxHarness.lookupAll(panel, "mode-header-glyph").get(0);
            assertTrue(glyph.getStyleClass().contains("mode-header-glyph-stash"));

            panel.setMode(DetailPanel.Mode.COMMIT, "Commit", "8878ebd", null);
            assertFalse(glyph.getStyleClass().contains("mode-header-glyph-stash"),
                    "the stash colour must not leak into the next mode");
        });
    }

    /**
     * Rebuilding must not stack footers.
     *
     * <p>This is the bug the footer slot exists to prevent: the staging panel is rebuilt
     * after every single stage and unstage, and a composer appended straight to the
     * panel's children would leave one more copy of itself behind each time.
     */
    @Test
    void rebuildingDoesNotAccumulateFooters() {
        FxHarness.run(() -> {
            DetailPanel panel = new DetailPanel();
            int baseline = panel.getChildren().size();

            for (int i = 0; i < 5; i++) {
                panel.clearBody();
                panel.setFooter(new Region());
            }

            assertEquals(baseline + 1, panel.getChildren().size(),
                    "five rebuilds should leave exactly one footer");
        });
    }

    @Test
    void clearingTheBodyRemovesTheFooterToo() {
        FxHarness.run(() -> {
            DetailPanel panel = new DetailPanel();
            panel.setFooter(new Region());
            int withFooter = panel.getChildren().size();

            panel.clearBody();
            assertEquals(withFooter - 1, panel.getChildren().size(),
                    "a mode without a composer must not inherit the previous mode's");
        });
    }

    /** The count is refreshed after the file list arrives, so it has to survive alone. */
    @Test
    void theCountCanBeUpdatedAfterTheModeIsSet() {
        FxHarness.run(() -> {
            DetailPanel panel = new DetailPanel();
            panel.setMode(DetailPanel.Mode.COMMIT, "Commit", "8878ebd", null);

            var badge = FxHarness.lookupAll(panel, "count-badge").get(0);
            assertFalse(badge.isManaged(), "no count is known yet");

            panel.setCount("12 files");
            assertTrue(badge.isManaged());
            assertEquals("12 files", ((Label) badge).getText());
        });
    }
}
