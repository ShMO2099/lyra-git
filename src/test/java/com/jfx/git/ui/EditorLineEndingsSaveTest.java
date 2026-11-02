package com.jfx.git.ui;

import com.jfx.git.core.GitResult;

import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Saving a file keeps its line endings.
 *
 * <h2>The bug this exists for</h2>
 * RichTextFX drops every carriage return when text becomes paragraphs, and
 * {@code TextEncoding.encode} restores only the BOM and the charset. So the editor handed
 * its LF-only buffer straight to disk, and editing a single line of a CRLF file rewrote
 * every line of it. A one-character change produced a whole-file diff, and {@code git
 * blame} attributed the entire file to whoever pressed Ctrl+S.
 *
 * <p>These tests read what reached the save handler - the bytes that would be written -
 * rather than what the buffer holds, because the buffer is LF-only by construction and
 * that is not the bug.
 */
class EditorLineEndingsSaveTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeStages() {
        FxHarness.closeShownStages();
    }

    private record Editor(DiffViewer viewer, List<String> saved) {}

    private Editor editorOn(String content) {
        List<String> saved = new ArrayList<>();
        // A disk the save actually writes to, so a second save sees the first one's bytes -
        // which is where a stale line-ending record would show up as a false alarm.
        byte[][] disk = {content.getBytes(StandardCharsets.UTF_8)};
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(p -> disk[0]);
            v.setWorkingCopyExistsCheck(p -> true);
            v.setOnSaveFile((p, text, onResult) -> {
                saved.add(text);
                disk[0] = text.getBytes(StandardCharsets.UTF_8);
                onResult.accept(GitResult.ok("saved"));
            });
            v.setEditable(true);
            v.showFileContent("a.txt", content);
            stage.setUserData("keep-alive");
            FxHarness.pulse(v);
            v.beginEditing();
            FxHarness.pulse(v);
            return v;
        });
        return new Editor(viewer, saved);
    }

    /** Types into the buffer the way a user does: the buffer only ever holds LF. */
    private static void type(Editor editor, String bufferText) {
        FxHarness.run(() -> editor.viewer().replaceBuffer(bufferText));
    }

    private static void save(Editor editor) {
        FxHarness.run(editor.viewer()::saveEdits);
    }

    // ------------------------------------------------------------------

    /** The reported bug, exactly: one line changed, every line kept as it was. */
    @Test
    void aCrlfFileIsWrittenBackAsCrlf() {
        Editor editor = editorOn("one\r\ntwo\r\nthree\r\n");

        type(editor, "one\nTWO\nthree\n");
        save(editor);

        assertEquals(List.of("one\r\nTWO\r\nthree\r\n"), editor.saved(),
                "only the edited line may differ from what was on disk");
    }

    @Test
    void aClassicMacFileIsWrittenBackWithBareCarriageReturns() {
        Editor editor = editorOn("one\rtwo\r");

        type(editor, "one\ntwo\nthree\n");
        save(editor);

        assertEquals(List.of("one\rtwo\rthree\r"), editor.saved());
    }

    /**
     * Saved once, a mixed file is uniform on disk. The second save must not mistake that
     * for somebody else renormalising it - a false prompt in the save path hangs rather
     * than fails, so reaching the handler twice is the assertion.
     */
    @Test
    void aMixedFileCanBeSavedTwiceWithoutAFalseAlarm() {
        Editor editor = editorOn("one\r\ntwo\nthree\r\n");

        type(editor, "one\ntwo\nthree\nfour\n");
        save(editor);
        type(editor, "one\ntwo\nthree\nfour\nfive\n");
        save(editor);

        assertEquals(2, editor.saved().size(), "both saves went straight through");
        assertEquals("one\r\ntwo\r\nthree\r\nfour\r\nfive\r\n",
                editor.saved().get(1));
    }

    /** Throwing edits away must not leave the session looking dirty. */
    @Test
    void reloadingACrlfFileLeavesTheSessionClean() {
        Editor editor = editorOn("one\r\ntwo\r\n");
        type(editor, "one\nchanged\n");

        FxHarness.run(editor.viewer()::reloadFromDiskForTest);

        assertFalse(FxHarness.call(editor.viewer()::hasUnsavedEdits),
                "the buffer now matches the file, so there is nothing unsaved");
    }

    /** The close path writes through textForSaving, so it must agree with Ctrl+S. */
    @Test
    void textForSavingIsWhatTheSaveHandlerReceives() {
        Editor editor = editorOn("one\r\ntwo\r\n");

        type(editor, "one\ntwo\nthree\n");
        String forClose = FxHarness.call(editor.viewer()::textForSaving);
        save(editor);

        assertEquals(editor.saved().get(0), forClose,
                "Save and close and Ctrl+S must write the same bytes");
    }
}
