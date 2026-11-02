package com.jfx.git.ui;

import com.jfx.git.testsupport.LayoutAssert;

import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff viewer's two header rows, as laid out with the application's stylesheets.
 *
 * <p>They used to carry a button called History beside one called "<- History" that did
 * something else, half a dozen characters standing in for icons, and two solid accent
 * blocks for which layout was selected. Each of those is checked here on the real,
 * styled control.
 */
class DiffHeaderLayoutTest {

    private static final String DIFF = """
            @@ -1,3 +1,3 @@
             context
            -removed
            +added
            """;

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private static DiffViewer styledViewer(double width) {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, width, 500);
            ThemeManager.register(stage.getScene());
            ThemeManager.apply(ThemeManager.Theme.DARK);
            viewer.setOnHistory(path -> { });
            viewer.setOnBlame(path -> { });
            viewer.setOnHunkAction(action -> { });
            viewer.setFileDiff(DiffKind.UNSTAGED, "src/main/java/com/shop/payments/PaymentService.java", DIFF);
            FxHarness.pulse(viewer);
            return viewer;
        });
    }

    /** One control mentions history, and it is the file's. Closing is a cross with a name. */
    @Test
    void historyMeansOneThingAndCloseSaysWhereItGoes() {
        DiffViewer viewer = styledViewer(1000);
        FxHarness.run(() -> {
            List<String> mentioning = new ArrayList<>();
            ButtonBase close = null;
            for (Node node : LayoutAssert.visibleNodes(viewer, n -> n instanceof ButtonBase)) {
                ButtonBase button = (ButtonBase) node;
                if (button.getText() != null && button.getText().toLowerCase().contains("history")) {
                    mentioning.add(button.getText());
                }
                if (button.getStyleClass().contains("diff-close")) {
                    close = button;
                }
            }
            assertEquals(List.of("File history"), mentioning,
                    "exactly one button should carry the word, and say whose history it is");
            assertTrue(close != null && "Back to history".equals(close.getAccessibleText()),
                    "the close control is named for where it goes");
            assertTrue(close.getText() == null || close.getText().isEmpty(),
                    "and is an icon, not a second \"History\"");
        });
    }

    @Test
    void noControlUsesATypedSymbolAsItsIcon() {
        DiffViewer viewer = styledViewer(1000);
        FxHarness.run(() -> {
            LayoutAssert.assertNoGlyphIcons(viewer);
            for (Node node : LayoutAssert.visibleNodes(viewer, n -> n instanceof Labeled)) {
                String text = ((Labeled) node).getText();
                if (text == null) continue;
                for (String glyph : LayoutAssert.GLYPH_ICONS) {
                    // Arrows and the dot are notation inside text; everything else in the
                    // list was only ever an icon.
                    if (glyph.equals("↑") || glyph.equals("↓") || glyph.equals("·") || glyph.equals("←")) continue;
                    assertTrue(!text.contains(glyph),
                            "\"" + text + "\" still carries the symbol " + glyph);
                }
            }
        });
    }

    /** The view switches are tinted when selected; nothing in the header is a solid block. */
    @Test
    void nothingInTheHeaderIsAFilledBlockAtRest() {
        DiffViewer viewer = styledViewer(1000);
        FxHarness.run(() -> {
            List<String> filled = new ArrayList<>();
            for (Node node : LayoutAssert.visibleNodes(viewer, n -> n instanceof ButtonBase)) {
                if (node.getStyleClass().contains("diff-hunk-action")
                        || node.getStyleClass().contains("diff-revert-hunk")) {
                    continue; // the hunk bar has its own test, HunkActionColourTest
                }
                if (LayoutAssert.countFilledButtons((javafx.scene.Parent) node) > 0) {
                    filled.add(LayoutAssertDescribe.of(node));
                }
            }
            assertTrue(filled.isEmpty(), "filled controls in the diff header: " + filled);
        });
    }

    /** A narrow pane shortens the path, not the words on the buttons. */
    @Test
    void aNarrowHeaderKeepsItsButtonsWordsWhole() {
        DiffViewer viewer = styledViewer(620);
        FxHarness.run(() -> {
            List<String> cut = new ArrayList<>();
            for (Node node : LayoutAssert.visibleNodes(viewer, n -> n instanceof ButtonBase)) {
                ButtonBase button = (ButtonBase) node;
                String text = button.getText();
                if (text == null || text.isEmpty()) continue;
                javafx.scene.text.Text shown = (javafx.scene.text.Text) button.lookup(".text");
                if (shown != null && !text.equals(shown.getText())) {
                    cut.add(text + " -> " + shown.getText());
                }
            }
            assertTrue(cut.isEmpty(), "button labels cut off at 620px: " + cut);
        });
    }

    /** Names a node for a failure message. */
    private static final class LayoutAssertDescribe {
        static String of(Node node) {
            String text = node instanceof Labeled l && l.getText() != null ? " \"" + l.getText() + "\"" : "";
            return node.getClass().getSimpleName() + node.getStyleClass() + text;
        }
    }
}
