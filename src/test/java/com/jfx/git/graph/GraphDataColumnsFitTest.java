package com.jfx.git.graph;

import com.jfx.git.testsupport.LayoutAssert;
import com.jfx.git.testsupport.MainWindowFixture;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.control.Labeled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The age and hash columns show their whole text: a date, not "14...".
 *
 * <p>The age column was sized for the short forms ("5d", "3h") and every commit over a
 * week old - which RelativeTime writes as a date - was cut off. A stash row's
 * {@code stash@{0}} is the longest thing the hash column carries.
 */
class GraphDataColumnsFitTest {

    @TempDir
    Path tempDir;

    @Test
    void agesAndHashesAreNeverCutOff() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            List<String> cut = FxHarness.call(() -> {
                List<String> problems = new ArrayList<>();
                List<javafx.scene.Node> cells = LayoutAssert.visibleNodes(window.scene().getRoot(),
                        n -> n.getStyleClass().contains("graph-age") || n.getStyleClass().contains("graph-hash"));
                assertFalse(cells.isEmpty(), "the graph should show age and hash cells");
                for (javafx.scene.Node cell : cells) {
                    Labeled label = (Labeled) cell;
                    javafx.scene.text.Text shown = (javafx.scene.text.Text) label.lookup(".text");
                    if (label.getText() != null && shown != null && !label.getText().equals(shown.getText())) {
                        problems.add(label.getText() + " -> " + shown.getText());
                    }
                }
                return problems;
            });
            LayoutAssert.failWith("age or hash text cut off", cut);
        }
    }

    /**
     * With room to spare, a branch chip shows its branch's name.
     *
     * <p>The column was capped at 64 pixels whatever the window, so eight of the fixture's
     * chips read "featu…" - a column of identical truncations, naming nothing.
     */
    @Test
    void aBranchChipShowsTheWholeNameWhenTheWindowIsWide() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            String shown = FxHarness.call(() -> {
                for (javafx.scene.Node node : LayoutAssert.visibleNodes(window.scene().getRoot(),
                        n -> n.getStyleClass().contains("ref-chip"))) {
                    Labeled chip = (Labeled) node;
                    if (chip.getText() != null && chip.getText().contains(MainWindowFixture.LONG_BRANCH)) {
                        javafx.scene.text.Text text = (javafx.scene.text.Text) chip.lookup(".text");
                        return text == null ? chip.getText() : text.getText();
                    }
                }
                return null;
            });
            org.junit.jupiter.api.Assertions.assertNotNull(shown,
                    "the fixture's " + MainWindowFixture.LONG_BRANCH + " should have a chip");
            org.junit.jupiter.api.Assertions.assertTrue(shown.contains(MainWindowFixture.LONG_BRANCH),
                    "the chip should read the whole branch name, read \"" + shown + "\"");
        }
    }
}
