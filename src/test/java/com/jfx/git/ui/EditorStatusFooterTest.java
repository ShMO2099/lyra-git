package com.jfx.git.ui;

import com.jfx.git.core.LineEndings;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The footer draws its record and nothing else.
 *
 * <p>Every test here builds a {@link EditorStatusFooter.Status} by hand rather than
 * driving a viewer, because that is the contract: the footer owns no state and decides
 * nothing, so what it shows is a pure function of what it is handed. Tests that went
 * through a {@code DiffViewer} would be testing the viewer's arithmetic twice and the
 * footer's rendering not at all.
 */
class EditorStatusFooterTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeStages() {
        FxHarness.closeShownStages();
        // Static and persisted, so a level left set here decides an unrelated later
        // test's assertion. Same reasoning as UiZoomTest.
        FxHarness.run(EditorZoom::resetForTests);
    }

    private EditorStatusFooter footer() {
        return FxHarness.call(() -> {
            EditorStatusFooter footer = new EditorStatusFooter();
            footer.adopt(new Label("Java · TextMate"), new Label("UTF-8"), new Label("editable"));
            Stage stage = FxHarness.show(footer, 900, 60);
            stage.setUserData("keep-alive");
            FxHarness.pulse(footer);
            return footer;
        });
    }

    /** Every cell the footer draws itself, by its style class and current text. */
    private static List<String> cellTexts(EditorStatusFooter footer) {
        return FxHarness.lookupAll(footer, "diff-status-cell").stream()
                .filter(Node::isManaged)
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText())
                .toList();
    }

    private static Label cellStartingWith(EditorStatusFooter footer, String prefix) {
        return FxHarness.lookupAll(footer, "diff-status-cell").stream()
                .filter(n -> n instanceof Label label
                        && label.getText() != null && label.getText().startsWith(prefix))
                .map(n -> (Label) n)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no cell starting with " + prefix + " in " + cellTexts(footer)));
    }

    private static EditorStatusFooter.Status editing(int line, int column,
                                                     int selectedChars, int selectedLines,
                                                     String indent, LineEndings endings) {
        return new EditorStatusFooter.Status(true, true, line, column,
                selectedChars, selectedLines, indent, endings, null, null, false);
    }

    // ------------------------------------------------------------------

    @Test
    void withNoFileOpenTheWholeBarIsGone() {
        EditorStatusFooter footer = footer();
        FxHarness.run(() -> footer.show(EditorStatusFooter.Status.idle()));

        assertFalse(footer.isVisible(), "nothing is open, so there is nothing to report");
        assertFalse(footer.isManaged(), "and it must not reserve the height either");
    }

    @Test
    void theCaretCellReadsLineAndColumn() {
        EditorStatusFooter footer = footer();
        FxHarness.run(() -> footer.show(editing(3, 7, 0, 0, "", LineEndings.LF)));

        assertEquals("Ln 3, Col 7", cellStartingWith(footer, "Ln ").getText());
    }

    /** An empty selection has nothing to say, so the cell takes no space at all. */
    @Test
    void theSelectionCellIsAbsentUntilSomethingIsSelected() {
        EditorStatusFooter footer = footer();

        FxHarness.run(() -> footer.show(editing(1, 1, 0, 0, "", LineEndings.LF)));
        assertTrue(cellTexts(footer).stream().noneMatch(t -> t.contains("selected")),
                () -> "cells: " + cellTexts(footer));

        FxHarness.run(() -> footer.show(editing(1, 1, 15, 1, "", LineEndings.LF)));
        assertEquals("15 selected", cellStartingWith(footer, "15").getText());
    }

    @Test
    void aMultiLineSelectionReportsItsLinesAndCharacters() {
        EditorStatusFooter footer = footer();
        FxHarness.run(() -> footer.show(editing(1, 1, 42, 3, "", LineEndings.LF)));

        assertEquals("3 lines · 42 chars", cellStartingWith(footer, "3 lines").getText());
    }

    /**
     * Caret, selection and indentation describe an editing session. Reading a diff, they
     * would be describing a buffer the user cannot type into.
     */
    @Test
    void theEditingOnlyCellsAreHiddenWhileMerelyReading() {
        EditorStatusFooter footer = footer();
        FxHarness.run(() -> footer.show(new EditorStatusFooter.Status(
                true, false, 9, 9, 5, 2, "Tabs", LineEndings.LF, null, null, false)));

        List<String> cells = cellTexts(footer);
        assertTrue(cells.stream().noneMatch(t -> t.startsWith("Ln ")), () -> "cells: " + cells);
        assertTrue(cells.stream().noneMatch(t -> t.contains("selected")), () -> "cells: " + cells);
        assertTrue(cells.stream().noneMatch(t -> t.equals("Tabs")), () -> "cells: " + cells);
    }

    /**
     * A diff has no line-ending convention worth reporting - see
     * {@code DiffViewer.statusNow} - so the cell disappears rather than guessing.
     */
    @Test
    void theLineEndingCellIsHiddenWhenThereIsNoDocumentToCount() {
        EditorStatusFooter footer = footer();
        FxHarness.run(() -> footer.show(editing(1, 1, 0, 0, "", null)));

        assertTrue(cellTexts(footer).stream().noneMatch(t -> t.equals("LF") || t.startsWith("Mixed")),
                () -> "cells: " + cellTexts(footer));
    }

    /** "Mixed" alone says there is a problem without saying which way to resolve it. */
    @Test
    void mixedLineEndingsNameTheirMajority() {
        EditorStatusFooter footer = footer();
        FxHarness.run(() -> footer.show(new EditorStatusFooter.Status(
                true, true, 1, 1, 0, 0, "", LineEndings.MIXED, LineEndings.CRLF,
                LineEndings.count("a\r\nb\r\nc\n"), false)));

        assertEquals("Mixed (CRLF)", cellStartingWith(footer, "Mixed").getText());
    }

    @Test
    void theExternalChangeWarningAppearsOnlyWhenSomethingChanged() {
        EditorStatusFooter footer = footer();

        FxHarness.run(() -> footer.show(editing(1, 1, 0, 0, "", LineEndings.LF)));
        assertTrue(FxHarness.lookupAll(footer, "diff-status-warning").stream()
                .noneMatch(Node::isManaged), "nothing has changed, so no warning");

        FxHarness.run(() -> footer.show(new EditorStatusFooter.Status(
                true, true, 1, 1, 0, 0, "", LineEndings.LF, null, null, true)));
        assertTrue(FxHarness.lookupAll(footer, "diff-status-warning").stream()
                .anyMatch(Node::isManaged), "the file moved under the editor; say so");
    }

    /**
     * The editor's own zoom, not the chrome's. This is the first place it is visible at
     * all - until now it was reachable only by Ctrl+scroll, which is not discoverable.
     */
    @Test
    void theZoomCellFollowsTheEditorsOwnZoom() {
        EditorStatusFooter footer = footer();
        FxHarness.run(() -> footer.show(editing(1, 1, 0, 0, "", LineEndings.LF)));
        assertEquals("Zoom 100%", cellStartingWith(footer, "Zoom").getText());

        FxHarness.run(() -> EditorZoom.set(125));
        assertEquals("Zoom 125%", cellStartingWith(footer, "Zoom").getText());
    }
}
