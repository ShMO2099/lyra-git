package com.jfx.git.ui;

import com.jfx.git.core.GitResult;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's own behaviours, on a real viewer.
 *
 * <p>These cover what {@link DiffViewerRenderTest} does not: the save shortcut, the
 * dirty badge, Save-and-Stage's availability, and the two indentation rules. The pure
 * functions are asserted directly; the stateful ones through a viewer shown off-screen.
 */
class DiffEditorEditingTest {

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

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    // ------------------------------------------------------------------
    // Indentation rules (pure seams)
    // ------------------------------------------------------------------

    @Test
    void tabFollowsTheFilesExistingIndentCharacter() {
        assertEquals("\t", DiffViewer.indentUnitFor("\t\tdeep()"),
                "a tab-indented file keeps getting tabs");
        assertEquals("    ", DiffViewer.indentUnitFor("    spaces()"),
                "a space-indented file keeps getting four spaces");
        assertEquals("    ", DiffViewer.indentUnitFor(""),
                "an empty line has no evidence, so spaces win");
        assertEquals("    ", DiffViewer.indentUnitFor(null),
                "no line at all behaves like an empty one");
    }

    @Test
    void enterCarriesTheCurrentIndentationOntoTheNewLine() {
        assertEquals("\n    ", DiffViewer.autoIndentAfter("    if (ready) {"));
        assertEquals("\n", DiffViewer.autoIndentAfter("no indent here"));
        assertEquals("\n\t", DiffViewer.autoIndentAfter("\tdeeper();"));
    }

    // ------------------------------------------------------------------
    // Save shortcut (pure seam)
    // ------------------------------------------------------------------

    @Test
    void saveShortcutIsCtrlSAndNothingElse() {
        assertTrue(DiffViewer.handleSaveShortcut(KeyCode.S, true, false),
                "Ctrl+S saves");
        assertTrue(DiffViewer.handleSaveShortcut(KeyCode.S, false, true),
                "Ctrl+S saves when reported as the control modifier rather than the shortcut one");
        assertFalse(DiffViewer.handleSaveShortcut(KeyCode.A, true, false),
                "Ctrl+A is not a save");
        assertFalse(DiffViewer.handleSaveShortcut(KeyCode.S, false, false),
                "plain S must reach the buffer");
        assertFalse(DiffViewer.handleSaveShortcut(KeyCode.Z, true, false),
                "undo is text undo while editing, not a save");
    }

    // ------------------------------------------------------------------
    // Editing session on a real viewer
    // ------------------------------------------------------------------

    /** Collectors for what the host was handed, plus the viewer on its stage. */
    private record Editing(DiffViewer viewer,
                           List<String> savedPaths, List<String> savedTexts) { }

    private Editing showEditableViewer(String path, String content) {
        return showEditableViewer(path, content, true);
    }

    /**
     * @param saveSucceeds what the host reports back; {@code false} is a read-only file or
     *                     a full disk, which the editor has to survive without losing the
     *                     buffer
     */
    private Editing showEditableViewer(String path, String content, boolean saveSucceeds) {
        return FxHarness.call(() -> {
            DiffViewer viewer = new DiffViewer();
            Stage stage = FxHarness.show(viewer, 900, 600);
            List<String> paths = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            viewer.setOnSaveFile((p, t, onResult) -> {
                paths.add(p);
                texts.add(t);
                onResult.accept(saveSucceeds
                        ? GitResult.ok("saved")
                        : GitResult.failed("the file is read-only"));
            });
            // The 3-arg overload supplies whole-file content synchronously, which is
            // what lets beginEditing() proceed in one press.
            String firstLine = content.split("\n", 2)[0];
            viewer.setFileDiffBytes(path,
                    ("@@ -1 +1 @@\n-old\n+" + firstLine + "\n").getBytes(),
                    content.getBytes());
            stage.setUserData("keep-alive");
            FxHarness.pulse(viewer);
            viewer.beginEditing();
            FxHarness.pulse(viewer);
            return new Editing(viewer, paths, texts);
        });
    }

    /** The toolbar button labelled {@code text}, or null. */
    private static Button buttonWithText(DiffViewer viewer, String text) {
        return FxHarness.lookupAll(viewer, "diff-tool-button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(n -> (Button) n)
                .findFirst()
                .orElse(null);
    }

    private static Label editingBadge(DiffViewer viewer) {
        return FxHarness.lookupAll(viewer, "diff-editing-badge").stream()
                .filter(n -> n instanceof Label)
                .map(n -> (Label) n)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void badgeTurnsDirtyWhileTypingAndHonestAgainAfterSaving() {
        Editing editing = showEditableViewer("src/App.java", "class App {\n}\n");
        DiffViewer viewer = editing.viewer();
        Label badge = editingBadge(viewer);

        assertFalse(viewer.hasUnsavedEdits(), "a fresh session has nothing pending");
        assertFalse(badge.getText().contains("unsaved"), () -> "badge: " + badge.getText());

        FxHarness.run(() -> viewer.replaceBuffer("class App {\n    int x;\n}\n"));
        assertTrue(viewer.hasUnsavedEdits(), "the buffer differs from where editing began");

        FxHarness.run(viewer::updateEditingBadge);
        assertTrue(badge.getText().contains("unsaved"), () -> "badge: " + badge.getText());
        assertTrue(badge.getStyleClass().contains("diff-editing-dirty"),
                "the dirty style marks the label");

        FxHarness.run(viewer::saveEdits);
        assertEquals(1, editing.savedPaths().size(), "the host received the edit");
        assertEquals("src/App.java", editing.savedPaths().get(0));
        assertTrue(editing.savedTexts().get(0).contains("int x;"),
                "the typed text reached the host");
        assertTrue(viewer.isEditing(),
                "Ctrl+S is a checkpoint, not the end of the session");
        assertFalse(viewer.hasUnsavedEdits(),
                "the buffer matches what was written, so nothing is pending");
        assertFalse(badge.getStyleClass().contains("diff-editing-dirty"),
                "the dirty marker clears once the write is confirmed");
    }

    /**
     * The failure this whole save path was rebuilt around.
     *
     * <p>Save used to leave edit mode and clear the dirty flag before the write was even
     * attempted, so a write that failed left {@code hasUnsavedEdits()} answering false
     * about text that existed nowhere else - and the next file selection replaced it
     * without a prompt.
     */
    @Test
    void aFailedSaveKeepsTheBufferTheSessionAndTheDirtyState() {
        Editing editing = showEditableViewer("src/App.java", "class App {\n}\n", false);
        DiffViewer viewer = editing.viewer();
        Label badge = editingBadge(viewer);

        FxHarness.run(() -> viewer.replaceBuffer("class App {\n    int x;\n}\n"));
        FxHarness.run(viewer::saveEdits);

        assertEquals(1, editing.savedPaths().size(), "the write was attempted");
        assertTrue(viewer.isEditing(), "a failed save must not end the session");
        assertTrue(viewer.hasUnsavedEdits(),
                "the text is still only in the buffer, so it is still unsaved");
        assertTrue(viewer.editorText().contains("int x;"), "the edit is still there");
        assertTrue(badge.getText().contains("not saved"),
                () -> "the badge must say so: " + badge.getText());
        assertTrue(badge.getStyleClass().contains("diff-editing-failed"),
                "a failed save is marked differently from merely unsaved");
    }

    /**
     * Done keeps what is on screen. It used to redraw the file from the bytes cached before
     * editing began, so a Save followed by Done showed the file as it was before the edit
     * until something else happened to reload it.
     */
    @Test
    void doneAfterASaveKeepsTheSavedTextOnScreen() {
        Editing editing = showEditableViewer("src/App.java", "class App {\n}\n");
        DiffViewer viewer = editing.viewer();
        FxHarness.run(() -> viewer.replaceBuffer("class App {\n    int saved;\n}\n"));
        FxHarness.run(viewer::saveEdits);
        assertEquals(1, editing.savedTexts().size(), "precondition: the edit was written");

        FxHarness.run(viewer::finishEditing);

        assertFalse(viewer.isEditing());
        String shown = FxHarness.call(viewer::editorText);
        assertTrue(shown.contains("int saved;"), () -> "the saved text is what is on disk: " + shown);
    }

    /**
     * While the edits are being written there is nothing Discard could throw away: the
     * write lands anyway, so all it could do is end the session that write belongs to.
     */
    @Test
    void discardIsUnavailableWhileASaveIsOnItsWay() {
        List<java.util.function.Consumer<GitResult>> held = new ArrayList<>();
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            // Holds the answer back, the way a busy git lane does.
            v.setOnSaveFile((p, t, onResult) -> held.add(onResult));
            v.setFileDiffBytes("src/A.java", "@@ -1 +1 @@\n-old\n+a\n".getBytes(), "a\n".getBytes());
            stage.setUserData("keep-alive");
            FxHarness.pulse(v);
            v.beginEditing();
            FxHarness.pulse(v);
            return v;
        });
        FxHarness.run(() -> viewer.replaceBuffer("a\nb\n"));
        Button discard = FxHarness.call(() -> buttonWithText(viewer, "Discard edits"));
        assertFalse(discard.isDisabled(), "precondition: there is something to discard");

        FxHarness.run(viewer::saveEdits);
        assertTrue(discard.isDisabled(), "the edits are on their way to disk");

        FxHarness.run(() -> held.get(0).accept(GitResult.ok("saved")));
        assertFalse(discard.isDisabled(), "and once they are there it is available again");
    }

    /**
     * Pressing a display toggle used to rebuild the surface from the cached file bytes,
     * which deleted whatever had been typed and reported the result as clean.
     */
    @Test
    void aDisplayToggleDoesNotDiscardTheBuffer() {
        Editing editing = showEditableViewer("src/App.java", "class App {\n}\n");
        DiffViewer viewer = editing.viewer();

        FxHarness.run(() -> viewer.replaceBuffer("class App {\n    int unique;\n}\n"));
        assertTrue(viewer.hasUnsavedEdits(), "precondition: the buffer is dirty");

        ToggleButton wrap = FxHarness.call(() -> FxHarness.lookupAll(viewer, "diff-tool-toggle")
                .stream()
                .filter(n -> n instanceof ToggleButton t && DiffViewer.NAME_WRAP.equals(t.getAccessibleText()))
                .map(n -> (ToggleButton) n)
                .findFirst()
                .orElseThrow());
        FxHarness.run(() -> {
            wrap.setSelected(true);
            wrap.fire();
        });

        assertTrue(viewer.editorText().contains("int unique;"),
                "wrapping is a display setting; it must not reload the document");
        assertTrue(viewer.hasUnsavedEdits(), "and it must not pretend the buffer is clean");
        assertTrue(viewer.isEditing(), "nor end the session");
    }

    @Test
    void ctrlSDispatchedAtTheViewerReachesTheEditorAndSaves() {
        Editing editing = showEditableViewer("src/C.java", "c\n");
        DiffViewer viewer = editing.viewer();
        FxHarness.run(() -> viewer.replaceBuffer("c\nchanged\n"));

        // Aimed at the editing surface itself, where the shortcut filter lives.
        FxHarness.run(() -> viewer.pressKeyForTest(KeyCode.S, true));

        assertEquals(1, editing.savedPaths().size(),
                "the save shortcut reached the editing session");
        assertTrue(viewer.isEditing(),
                "Ctrl+S saves without throwing the user out of the editor");
    }

    @Test
    void saveAndStageButtonAppearsOnlyWhenAHandlerIsRegistered() {
        Editing editing = showEditableViewer("src/B.java", "b\n");
        Button without = buttonWithText(editing.viewer(), "Save and stage");
        assertTrue(without == null || !without.isVisible(),
                "no handler registered, so no Save-and-Stage while editing");

        FxHarness.run(() -> editing.viewer()
                .setOnSaveAndStage((p, t, onResult) -> {
                    editing.savedPaths().add(p);
                    onResult.accept(GitResult.ok("staged"));
                }));
        Button with = buttonWithText(editing.viewer(), "Save and stage");
        assertNotNull(with, "the button exists in the toolbar");
        FxHarness.pulse(editing.viewer());
        assertTrue(with.isVisible(), "registering the handler reveals it mid-session");

        FxHarness.run(() -> editing.viewer().replaceBuffer("b\nchanged\n"));
        FxHarness.run(editing.viewer()::saveAndStage);
        assertEquals("src/B.java", editing.savedPaths().get(0),
                "the combined action reached the host with the edited buffer");
        assertTrue(editing.viewer().isEditing(),
                "Save and stage is a save, and a save keeps the session open");
    }

    // ------------------------------------------------------------------
    // Outdenting (pure seam)
    // ------------------------------------------------------------------

    /**
     * Shift+Tab used to fall through to the plain Tab case and insert four more spaces.
     */
    @Test
    void outdentRemovesExactlyOneIndentLevel() {
        assertEquals("deep()", DiffViewer.withoutOneIndent("\tdeep()"),
                "a tab-indented line loses its tab");
        assertEquals("spaces()", DiffViewer.withoutOneIndent("    spaces()"),
                "four spaces are one level");
        assertEquals("    deeper()", DiffViewer.withoutOneIndent("        deeper()"),
                "only one level comes off, not all of them");
        assertEquals("ragged()", DiffViewer.withoutOneIndent("  ragged()"),
                "a part-level indent is taken to the margin rather than left behind");
        assertEquals("already()", DiffViewer.withoutOneIndent("already()"),
                "a line at the margin is left alone");
        assertEquals("", DiffViewer.withoutOneIndent(""),
                "an empty line has nothing to remove");
    }

    // ------------------------------------------------------------------
    // The status footer
    // ------------------------------------------------------------------

    /** Walks up from {@code node} looking for a parent carrying {@code styleClass}. */
    private static boolean hasAncestorStyled(javafx.scene.Node node, String styleClass) {
        for (javafx.scene.Node at = node; at != null; at = at.getParent()) {
            if (at.getStyleClass().contains(styleClass)) return true;
        }
        return false;
    }

    /** The text of every footer cell currently taking up space. */
    private static List<String> footerCells(DiffViewer viewer) {
        return FxHarness.lookupAll(viewer, "diff-status-cell").stream()
                .filter(javafx.scene.Node::isManaged)
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText())
                .toList();
    }

    /**
     * The badge moved into the footer rather than being replaced by it.
     *
     * <p>Relocating the node is what lets the three badge tests above stand unchanged -
     * same text, same style classes, same owner in {@code updateEditingBadge}. This pins
     * where it landed, so a later tidy-up cannot quietly put it back on the toolbar and
     * leave the footer with a hole where the save state should be.
     */
    @Test
    void theSaveBadgeLivesInTheFooterNow() {
        Editing editing = showEditableViewer("src/App.java", "class App {\n}\n");

        assertTrue(hasAncestorStyled(editingBadge(editing.viewer()), "diff-status-footer"),
                "the badge belongs with the other document facts");
    }

    @Test
    void theFooterReportsTheCaretWhileEditing() {
        Editing editing = showEditableViewer("src/App.java", "class App {\n    int x;\n}\n");
        DiffViewer viewer = editing.viewer();

        FxHarness.run(() -> viewer.moveCaretForTest(1, 4));

        assertTrue(footerCells(viewer).contains("Ln 2, Col 5"),
                () -> "footer cells: " + footerCells(viewer));
    }

    /** The line-ending cell is a fact about the document, so it is there while editing. */
    @Test
    void theFooterNamesTheLineEndings() {
        Editing editing = showEditableViewer("src/App.java", "a\nb\n");

        assertTrue(footerCells(editing.viewer()).contains("LF"),
                () -> "footer cells: " + footerCells(editing.viewer()));
    }

    // ------------------------------------------------------------------
    // Indentation summary (pure seam)
    // ------------------------------------------------------------------

    /**
     * Read off the document, not off a preference - the same rule
     * {@code indentUnitFor} follows, for the same reason.
     */
    @Test
    void indentSummaryReadsTheDocumentRatherThanAPreference() {
        assertEquals("Tabs", DiffViewer.indentSummary("\tone();\n\t\ttwo();\n"));
        assertEquals("Spaces: 2", DiffViewer.indentSummary("a()\n  b()\n    c()\n"));
        assertEquals("Spaces: 4", DiffViewer.indentSummary("a()\n    b()\n        c()\n"));
        assertEquals("", DiffViewer.indentSummary("nothing()\nindented()\n"),
                "a flat file gives no evidence, and guessing would be worse than silence");
        assertEquals("", DiffViewer.indentSummary(""));
    }

    /**
     * One deeply indented line is not evidence of a deep indent unit. The step between
     * depths is, which is why the smallest gap wins over the shallowest depth.
     */
    @Test
    void indentSummaryTakesTheStepBetweenDepthsNotTheShallowestOne() {
        assertEquals("Spaces: 4",
                DiffViewer.indentSummary("call(\n        wrapped,\n    arg);\n"));
    }

    /**
     * Undo stops at the file the session opened with.
     *
     * <p>The text area is a display surface until editing starts, and every render before
     * that - the diff, then the file - was an undoable change. Ctrl+Z at the start of a
     * session used to walk back into the diff rows.
     */
    @Test
    void undoCannotReachPastTheStartOfTheSession() {
        String content = "class App {\n}\n";
        Editing editing = showEditableViewer("src/App.java", content);
        DiffViewer viewer = editing.viewer();
        DiffTextArea area = FxHarness.call(() ->
                (DiffTextArea) FxHarness.lookupAll(viewer, "code-area").get(0));
        String opened = FxHarness.call(viewer::editorText);

        FxHarness.run(() -> {
            area.insertText(0, "// typed\n");
            area.undo();
            area.undo();
            area.undo();
        });

        assertEquals(opened, FxHarness.call(viewer::editorText),
                "undo takes back the typing and nothing before it");
        assertFalse(viewer.hasUnsavedEdits(), "which leaves the buffer clean");
    }

    /** Esc in a clean editor closes the file, as the History button does. */
    @Test
    void escapeInACleanEditorClosesTheFile() {
        Editing editing = showEditableViewer("src/App.java", "class App {\n}\n");
        DiffViewer viewer = editing.viewer();

        FxHarness.run(() -> viewer.pressKeyForTest(KeyCode.ESCAPE, false));

        assertEquals("", FxHarness.call(() -> viewer.filePathProperty().get()),
                "a clean session has nothing to ask about");
        assertFalse(viewer.isEditing());
    }
}
