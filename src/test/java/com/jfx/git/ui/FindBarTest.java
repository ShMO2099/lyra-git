package com.jfx.git.ui;

import com.jfx.git.core.GitResult;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import org.fxmisc.richtext.GenericStyledArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find, replace and go to line, on a real viewer: in a diff, in a whole file, in the
 * editor and across Split view's two columns.
 */
class FindBarTest {

    private static final String FILE = """
            class Greeter {
                String greet(String name) {
                    return "Hello, " + name;
                }
                String part(String name) {
                    return "Goodbye, " + name;
                }
            }
            """;

    private static final String DIFF = """
            @@ -5,3 +5,3 @@
                 String part(String name) {
            -        return "Bye, " + name;
            +        return "Goodbye, " + name;
                 }
            """;

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    /** A viewer showing {@link #FILE} in File view, or {@link #DIFF} in Diff view. */
    private static DiffViewer viewer(boolean fileView) {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            FxHarness.show(viewer, 900, 600);
            viewer.setFileDiffBytes("src/Greeter.java", DIFF.getBytes(), FILE.getBytes());
            if (fileView) {
                toggle(viewer, "File view");
            }
            FxHarness.pulse(viewer);
            return viewer;
        });
    }

    /** The same file, being edited. */
    private static DiffViewer editor() {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 900, 600);
            viewer.setOnSaveFile((path, text, onResult) -> onResult.accept(GitResult.ok("saved")));
            viewer.setFileDiffBytes("src/Greeter.java", DIFF.getBytes(), FILE.getBytes());
            stage.setUserData("keep-alive");
            FxHarness.pulse(viewer);
            viewer.beginEditing();
            FxHarness.pulse(viewer);
            assertTrue(viewer.isEditing(), "the test needs an editing session");
            return viewer;
        });
    }

    private static void toggle(DiffViewer viewer, String label) {
        for (Node node : FxHarness.lookupAll(viewer, "diff-segment")) {
            if (node instanceof ToggleButton button && label.equals(button.getText())) {
                button.fire();
                return;
            }
        }
        throw new AssertionError("no toggle labelled " + label);
    }

    /** The text areas on screen, in layout order. */
    private static List<GenericStyledArea<?, ?, ?>> areas(DiffViewer viewer) {
        List<GenericStyledArea<?, ?, ?>> found = new ArrayList<>();
        collect(viewer, found);
        return found;
    }

    private static void collect(Node node, List<GenericStyledArea<?, ?, ?>> into) {
        if (!node.isVisible()) return;
        if (node instanceof GenericStyledArea<?, ?, ?> area) {
            into.add(area);
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, into);
        }
    }

    private static void type(FindBar bar, String query) {
        bar.queryField().setText(query);
    }

    // ------------------------------------------------------------------
    // Finding
    // ------------------------------------------------------------------

    @Test
    void findsInTheDiffAndSaysHowMany() {
        DiffViewer viewer = viewer(false);
        FxHarness.run(() -> {
            viewer.showFind(false);
            FindBar bar = viewer.findBar();
            assertTrue(bar.isVisible());
            assertFalse(bar.replaceShown(), "a diff is not an editor: nothing to replace in");
            type(bar, "goodbye");
            assertEquals(1, bar.hits().size());
            assertEquals("1 of 1", bar.countText());
            assertEquals(1, bar.highlightCount(), "the match is marked in the text");
            GenericStyledArea<?, ?, ?> area = areas(viewer).get(0);
            assertEquals("Goodbye", area.getSelectedText(), "and selected, so it is in view");
        });
    }

    @Test
    void typingMoreOfTheQueryStaysOnTheSameMatch() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            areas(viewer).get(0).moveTo(0);
            viewer.showFind(false);
            FindBar bar = viewer.findBar();
            type(bar, "G");
            int first = bar.hits().get(bar.currentIndex()).match().start();
            type(bar, "Gr");
            assertEquals(first, bar.hits().get(bar.currentIndex()).match().start(),
                    "one more character narrows the search; it does not jump to the next hit");
            type(bar, "Gre");
            assertEquals(first, bar.hits().get(bar.currentIndex()).match().start());
        });
    }

    @Test
    void stepsWrapRoundBothWays() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            areas(viewer).get(0).moveTo(0);
            viewer.showFind(false);
            FindBar bar = viewer.findBar();
            type(bar, "name");
            int total = bar.hits().size();
            assertEquals(4, total, "two parameters and two uses");
            assertEquals(0, bar.currentIndex());
            bar.step(-1);
            assertEquals(total - 1, bar.currentIndex(), "back from the first is the last");
            bar.step(1);
            assertEquals(0, bar.currentIndex(), "on from the last is the first");
            assertEquals("1 of 4", bar.countText());
        });
    }

    @Test
    void optionsChangeWhatMatches() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            viewer.showFind(false);
            FindBar bar = viewer.findBar();
            List<ToggleButton> options = bar.optionToggles();
            type(bar, "string");
            assertEquals(4, bar.hits().size(), "case ignored: every String");
            options.get(0).setSelected(true);
            assertEquals("No matches", bar.countText(), "match case: none is lower-case");
            options.get(0).setSelected(false);
            type(bar, "gree");
            options.get(1).setSelected(true);
            assertTrue(bar.hits().isEmpty(), "whole word: \"gree\" is part of greet and Greeter");
            options.get(1).setSelected(false);
            options.get(2).setSelected(true);
            type(bar, "\"(Hello|Goodbye), \"");
            assertEquals(2, bar.hits().size(), "a regular expression");
        });
    }

    @Test
    void anUnfinishedExpressionMarksTheFieldAndSaysWhy() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            viewer.showFind(false);
            FindBar bar = viewer.findBar();
            bar.optionToggles().get(2).setSelected(true);
            type(bar, "(name");
            assertTrue(bar.queryField().getStyleClass().contains("find-error"));
            assertFalse(bar.countText().isBlank());
            type(bar, "(name)");
            assertFalse(bar.queryField().getStyleClass().contains("find-error"),
                    "finished, the mark goes");
        });
    }

    @Test
    void aShortSelectionIsWhatIsLookedFor() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            GenericStyledArea<?, ?, ?> area = areas(viewer).get(0);
            int at = area.getText().indexOf("Greeter");
            area.selectRange(at, at + "Greeter".length());
            viewer.showFind(false);
            assertEquals("Greeter", viewer.findBar().queryField().getText());
        });
    }

    @Test
    void escClosesTheBarAndTakesTheMarksOff() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            viewer.showFind(false);
            FindBar bar = viewer.findBar();
            type(bar, "name");
            assertTrue(bar.highlightCount() > 0);
            bar.queryField().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                    false, false, false, false));
            assertFalse(bar.isVisible());
            assertEquals(0, bar.highlightCount(), "no stray marks left in the text");
            assertFalse(viewer.closeFind(), "nothing left to close");
        });
    }

    @Test
    void enterInTheQueryStepsAndShiftEnterStepsBack() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            areas(viewer).get(0).moveTo(0);
            viewer.showFind(false);
            FindBar bar = viewer.findBar();
            type(bar, "name");
            bar.queryField().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    false, false, false, false));
            assertEquals(1, bar.currentIndex());
            bar.queryField().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    true, false, false, false));
            assertEquals(0, bar.currentIndex());
        });
    }

    @Test
    void splitViewSearchesBothColumns() {
        DiffViewer viewer = viewer(false);
        FxHarness.run(() -> {
            toggle(viewer, "Split");
            FxHarness.pulse(viewer);
            viewer.showFind(false);
            FindBar bar = viewer.findBar();
            type(bar, "String");
            List<Integer> columns = bar.hits().stream().map(FindBar.Hit::area).distinct().toList();
            assertEquals(List.of(0, 1), columns,
                    "the old line is in the left column, the new in the right");
            toggle(viewer, "Unified");
            FxHarness.pulse(viewer);
            assertTrue(bar.hits().stream().allMatch(hit -> hit.area() == 0),
                    "back in one column, the search follows it");
        });
    }

    // ------------------------------------------------------------------
    // Replacing
    // ------------------------------------------------------------------

    @Test
    void replaceIsOfferedInTheEditorAndReplacesOneAtATime() {
        DiffViewer viewer = editor();
        FxHarness.run(() -> {
            GenericStyledArea<?, ?, ?> area = areas(viewer).get(0);
            area.moveTo(0);
            viewer.showFind(true);
            FindBar bar = viewer.findBar();
            assertTrue(bar.replaceShown());
            type(bar, "name");
            bar.replacementField().setText("who");
            bar.replaceCurrent();
            assertTrue(viewer.editorText().contains("String greet(String who)"),
                    viewer.editorText());
            assertEquals(3, bar.hits().size(), "one fewer left to find");
            assertTrue(viewer.hasUnsavedEdits(), "a replace is an edit like any other");
        });
    }

    @Test
    void replaceAllIsOneUndo() {
        DiffViewer viewer = editor();
        FxHarness.run(() -> {
            String before = viewer.editorText();
            viewer.showFind(true);
            FindBar bar = viewer.findBar();
            type(bar, "name");
            bar.replacementField().setText("who");
            bar.replaceAll();
            assertEquals(before.replace("name", "who"), viewer.editorText());
            assertEquals("Replaced 4", bar.countText());
            areas(viewer).get(0).undo();
            assertEquals(before, viewer.editorText(), "a single Ctrl+Z puts every one back");
        });
    }

    @Test
    void aRegexReplacementUsesItsGroups() {
        DiffViewer viewer = editor();
        FxHarness.run(() -> {
            viewer.showFind(true);
            FindBar bar = viewer.findBar();
            bar.optionToggles().get(2).setSelected(true);
            type(bar, "\"(\\w+), \"");
            bar.replacementField().setText("\"$1 \"");
            bar.replaceAll();
            assertTrue(viewer.editorText().contains("\"Hello \" + name"), viewer.editorText());
            assertTrue(viewer.editorText().contains("\"Goodbye \" + name"), viewer.editorText());
        });
    }

    @Test
    void outsideTheEditorReplaceDoesNothing() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            String before = areas(viewer).get(0).getText();
            viewer.showFind(true);
            FindBar bar = viewer.findBar();
            assertFalse(bar.replaceShown(), "Ctrl+H on a read-only view opens plain find");
            type(bar, "name");
            bar.replacementField().setText("who");
            bar.replaceAll();
            bar.replaceCurrent();
            assertEquals(before, areas(viewer).get(0).getText());
        });
    }

    // ------------------------------------------------------------------
    // Going to a line
    // ------------------------------------------------------------------

    @Test
    void goesToALineOfTheFile() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            viewer.showGoToLine();
            FindBar bar = viewer.findBar();
            assertEquals("1 to " + areas(viewer).get(0).getParagraphs().size(), bar.lineHintText());
            bar.lineField().setText("6");
            bar.goToLine();
            assertEquals(5, areas(viewer).get(0).getCurrentParagraph());
            assertFalse(bar.isVisible(), "done: the bar closes");
        });
    }

    @Test
    void aLineThatIsNotThereIsSaidSo() {
        DiffViewer viewer = viewer(true);
        FxHarness.run(() -> {
            viewer.showGoToLine();
            FindBar bar = viewer.findBar();
            bar.lineField().setText("99");
            bar.goToLine();
            assertTrue(bar.lineHintText().startsWith("There is no line 99"), bar.lineHintText());
            assertTrue(bar.isVisible(), "left open to try again");
            bar.lineField().setText("six");
            bar.goToLine();
            assertTrue(bar.lineHintText().startsWith("A line number"), bar.lineHintText());
        });
    }

    @Test
    void inADiffALineOutsideTheChangesPointsAtFileView() {
        DiffViewer viewer = viewer(false);
        FxHarness.run(() -> {
            viewer.showGoToLine();
            FindBar bar = viewer.findBar();
            bar.lineField().setText("6");
            bar.goToLine();
            GenericStyledArea<?, ?, ?> area = areas(viewer).get(0);
            assertTrue(area.getParagraph(area.getCurrentParagraph()).getText().contains("Goodbye"),
                    "line 6 of the new file is the added line");
            viewer.showGoToLine();
            bar.lineField().setText("2");
            bar.goToLine();
            assertTrue(bar.lineHintText().contains("not in the changes shown"), bar.lineHintText());
            bar.lineField().setText("0");
            bar.goToLine();
            assertTrue(bar.lineHintText().startsWith("There is no line 0"), bar.lineHintText());
        });
    }
}
