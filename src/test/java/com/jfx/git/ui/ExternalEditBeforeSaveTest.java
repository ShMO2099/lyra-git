package com.jfx.git.ui;

import com.jfx.git.core.GitResult;

import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Whether the editor notices that the file moved underneath it.
 *
 * <p>Every test here drives the <em>predicate</em> and the unchanged path, never the
 * prompt. That is deliberate: a modal opened on the FX thread inside a headless test
 * blocks forever, so a test that accidentally triggers one does not fail, it hangs the
 * suite. Reaching the save handler at all is therefore itself an assertion that no dialog
 * opened - and the case that must never prompt is the ordinary one, which is most of what
 * is checked below.
 *
 * <p>The prompt's own branches belong in a robot test, where there is a toolkit to answer
 * it with.
 */
class ExternalEditBeforeSaveTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeStages() {
        FxHarness.closeShownStages();
    }

    /** A viewer whose "disk" is a map the test can change behind its back. */
    private record Editor(DiffViewer viewer, Map<String, byte[]> disk, List<String> saved) {}

    private Editor editorOn(String path, String content) {
        Map<String, byte[]> disk = new HashMap<>();
        disk.put(path, content.getBytes(StandardCharsets.UTF_8));
        List<String> saved = new ArrayList<>();

        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(disk::get);
            v.setWorkingCopyExistsCheck(disk::containsKey);
            v.setOnSaveFile((p, text, onResult) -> {
                saved.add(text);
                disk.put(p, text.getBytes(StandardCharsets.UTF_8));
                onResult.accept(GitResult.ok("saved"));
            });
            v.setEditable(true);
            v.showFileContent(path, content);
            stage.setUserData("keep-alive");
            FxHarness.pulse(v);
            v.beginEditing();
            FxHarness.pulse(v);
            return v;
        });
        return new Editor(viewer, disk, saved);
    }

    private static void writeBehindTheApp(Editor editor, String path, String content) {
        editor.disk().put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------

    /**
     * The ordinary case, and the one that matters most: nothing changed, so nothing is
     * asked. If this ever starts prompting, every robot test that saves hangs.
     */
    @Test
    void anUntouchedFileOnDiskIsSavedWithoutAsking() {
        Editor editor = editorOn("a.txt", "one\n");

        FxHarness.run(() -> editor.viewer().replaceBuffer("one\ntwo\n"));
        FxHarness.run(editor.viewer()::saveEdits);

        assertEquals(List.of("one\ntwo\n"), editor.saved(),
                "the save went straight through, so no dialog stood in its way");
        assertFalse(editor.viewer().workingCopyChangedSinceEditingBegan());
    }

    /** Not editing, nothing to protect - the question is only meaningful in a session. */
    @Test
    void theQuestionIsFalseWhenNothingIsBeingEdited() {
        Editor editor = editorOn("a.txt", "one\n");
        FxHarness.run(editor.viewer()::cancelEditing);

        writeBehindTheApp(editor, "a.txt", "changed\n");

        assertFalse(editor.viewer().workingCopyChangedSinceEditingBegan());
    }

}
