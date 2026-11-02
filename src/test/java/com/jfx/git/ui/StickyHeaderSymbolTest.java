package com.jfx.git.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sticky header names the definition a row is inside, on the side its lines come from.
 *
 * <p>The file lines the header scans are the diff's new side, and it used to look a row up
 * by its <em>old</em> line number. Below a hunk that added lines the two numbers part, and
 * the header named whatever definition sat that many lines higher in the new file.
 */
class StickyHeaderSymbolTest {

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    /** The file after the change: alpha gains six lines, and gamma's z changes. */
    private static final String NEW_FILE = newFile();

    /** alpha's six added lines push gamma from line 10 down to line 16. */
    private static final String DIFF = String.join("\n",
            "@@ -1,3 +1,9 @@",
            " def alpha():",
            "     x = 1",
            "+    added = 1",
            "+    added = 2",
            "+    added = 3",
            "+    added = 4",
            "+    added = 5",
            "+    added = 6",
            "     return x",
            "@@ -10,3 +16,3 @@",
            " def gamma():",
            "-    z = 3",
            "+    z = 4",
            "     return z",
            "");

    private static String newFile() {
        List<String> lines = new ArrayList<>(List.of(
                "def alpha():",
                "    x = 1",
                "    added = 1",
                "    added = 2",
                "    added = 3",
                "    added = 4",
                "    added = 5",
                "    added = 6",
                "    return x",
                "",
                "def beta():",
                "    y = 2",
                "    y = 3",
                "    return y",
                "",
                "def gamma():",
                "    z = 4",
                "    return z"));
        // Room below, so gamma's last line can be scrolled to the top of the viewport.
        for (int i = 0; i < 40; i++) {
            lines.add("# end " + i);
        }
        return String.join("\n", lines) + "\n";
    }

    @Test
    void aRowBelowAHunkThatAddedLinesIsNamedByItsNewSideDefinition() {
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(path -> NEW_FILE.getBytes(StandardCharsets.UTF_8));
            v.setFileDiff("app.py", DIFF);
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
        // Whole file: the header reads the definition from the file's own lines, which are
        // there once the whole file is.
        FxHarness.run(() -> {
            toggle(viewer, "All");
            FxHarness.pulse(viewer);
        });
        FxHarness.run(() -> {
            DiffTextArea area = areaOf(viewer);
            int row = paragraphWithText(area, "    return z");
            area.showParagraphAtTop(row);
            FxHarness.pulse(viewer);
        });

        RobotWait.untilOnFx("the sticky header never appeared over gamma's last line",
                () -> !stickyText(viewer).isEmpty());
        String sticky = FxHarness.call(() -> stickyText(viewer));
        assertTrue(sticky.contains("def gamma"),
                "the row is inside gamma on the new side, where the scanned lines come from: " + sticky);
    }

    private static DiffTextArea areaOf(DiffViewer viewer) {
        return (DiffTextArea) FxHarness.lookupAll(viewer, "code-area").get(0);
    }

    private static int paragraphWithText(DiffTextArea area, String text) {
        for (int i = area.getParagraphs().size() - 1; i >= 0; i--) {
            if (text.equals(area.getText(i))) {
                return i;
            }
        }
        throw new AssertionError("no row reads \"" + text + "\": " + area.getText());
    }

    private static String stickyText(DiffViewer viewer) {
        return FxHarness.lookupAll(viewer, "diff-sticky-header").stream()
                .filter(n -> n instanceof Label l && l.isVisible())
                .map(n -> ((Label) n).getText())
                .findFirst().orElse("");
    }

    private static void toggle(DiffViewer viewer, String label) {
        for (Node node : FxHarness.lookupAll(viewer, "diff-segment")) {
            if (node instanceof ToggleButton button && label.equals(button.getText())) {
                button.fire();
                return;
            }
        }
        throw new AssertionError("no \"" + label + "\" toggle");
    }
}
