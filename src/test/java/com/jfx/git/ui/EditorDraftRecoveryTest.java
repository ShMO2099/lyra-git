package com.jfx.git.ui;

import com.jfx.git.core.EditorDrafts;
import com.jfx.git.core.GitResult;

import javafx.scene.Node;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parking an unsaved buffer, and getting it back.
 *
 * <p>The store is a fake held in a map, so nothing here touches a disk. The on-disk format
 * is {@code EditorDraftsTest}'s subject; this is about <em>when</em> the viewer decides to
 * park a buffer, forget one, and offer one back - which is the part with the interesting
 * mistakes in it.
 */
class EditorDraftRecoveryTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeStages() {
        FxHarness.closeShownStages();
    }

    /** Records what the viewer asks of a draft store, without writing anything. */
    private static final class FakeStore implements DiffViewer.DraftStore {
        final Map<String, EditorDrafts.Draft> drafts = new HashMap<>();
        int saves;

        @Override
        public void save(String path, String text, String baseline, String encoding) {
            saves++;
            drafts.put(path, new EditorDrafts.Draft("/repo", path, text,
                    Instant.now(), "baseline", encoding));
        }

        @Override
        public Optional<EditorDrafts.Draft> find(String path) {
            return Optional.ofNullable(drafts.get(path));
        }

        @Override
        public void discard(String path) {
            drafts.remove(path);
        }
    }

    private record Editor(DiffViewer viewer, FakeStore store) {}

    private Editor editorOn(String path, String content, boolean saveSucceeds) {
        FakeStore store = new FakeStore();
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setDraftStore(store);
            v.setOnSaveFile((p, text, onResult) -> onResult.accept(saveSucceeds
                    ? GitResult.ok("saved")
                    : GitResult.failed("the file is read-only")));
            v.setEditable(true);
            v.showFileContent(path, content);
            stage.setUserData("keep-alive");
            FxHarness.pulse(v);
            v.beginEditing();
            FxHarness.pulse(v);
            return v;
        });
        return new Editor(viewer, store);
    }

    /** A viewer that is only reading, with a draft already waiting for this path. */
    private Editor viewerWithStoredDraft(String path, String onDisk, String drafted) {
        FakeStore store = new FakeStore();
        store.drafts.put(path, new EditorDrafts.Draft("/repo", path, drafted,
                Instant.now(), "baseline", "UTF-8"));

        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setDraftStore(store);
            v.setOnSaveFile((p, text, onResult) -> onResult.accept(GitResult.ok("saved")));
            // The offer compares against the working copy on disk, not against whatever
            // revision happens to be on screen.
            v.setFileContentSupplier(p -> onDisk.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            v.setWorkingCopyExistsCheck(p -> true);
            v.setEditable(true);
            v.showFileContent(path, onDisk);
            stage.setUserData("keep-alive");
            FxHarness.pulse(v);
            return v;
        });
        return new Editor(viewer, store);
    }

    private static boolean bannerShowing(DiffViewer viewer) {
        return FxHarness.lookupAll(viewer, "diff-draft-banner").stream().anyMatch(Node::isManaged);
    }

    // ------------------------------------------------------------------

    /**
     * Opening a file with work waiting offers it back, and restoring leaves the session
     * honestly dirty rather than pretending the draft was already saved.
     */
    @Test
    void aStoredDraftIsOfferedAndCanBeRestored() {
        Editor editor = viewerWithStoredDraft("a.txt", "on disk\n", "my unsaved work\n");
        DiffViewer viewer = editor.viewer();

        assertTrue(bannerShowing(viewer), "a draft exists for this file, so say so");

        FxHarness.run(() -> {
            button(viewer, "Restore draft").fire();
            FxHarness.pulse(viewer);
        });

        assertTrue(viewer.isEditing(), "restoring opens an editing session");
        // Read on the FX thread: RichTextFX refuses a cross-thread read of a document
        // it has just had replaced under it.
        assertEquals("my unsaved work\n", FxHarness.call(viewer::editorText));
        assertTrue(FxHarness.call(viewer::hasUnsavedEdits),
                "the draft is not on disk, so the session must read as dirty");
        assertFalse(bannerShowing(viewer), "and the offer is done with");
    }

    @Test
    void dismissingTheOfferForgetsTheDraft() {
        Editor editor = viewerWithStoredDraft("a.txt", "on disk\n", "unsaved\n");
        DiffViewer viewer = editor.viewer();

        FxHarness.run(() -> button(viewer, "Discard draft").fire());

        assertFalse(bannerShowing(viewer));
        assertTrue(editor.store().drafts.isEmpty());
    }

    private static javafx.scene.control.Button button(DiffViewer viewer, String text) {
        return FxHarness.lookupAll(viewer, "diff-tool-button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button b && text.equals(b.getText()))
                .map(n -> (javafx.scene.control.Button) n)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button labelled " + text));
    }

    // ------------------------------------------------------------------
    // Found in review
    // ------------------------------------------------------------------

    /**
     * The host marks content read-only just before loading something else - clicking a
     * file in a commit's list does it first. Ending a dirty session there threw the edits
     * away without the prompt, and deleted the draft that was their last copy.
     */
    @Test
    void aDirtySessionSurvivesBeingToldTheNextContentIsReadOnly() {
        Editor editor = editorOn("a.txt", "one\n", true);
        FxHarness.run(() -> editor.viewer().replaceBuffer("one\ntwo\n"));
        FxHarness.run(editor.viewer()::writeDraftForTest);

        FxHarness.run(() -> editor.viewer().setEditable(false));

        assertTrue(editor.viewer().isEditing(),
                "the navigation that follows is what asks; this must not end the session first");
        assertTrue(FxHarness.call(editor.viewer()::hasUnsavedEdits));
        assertTrue(editor.store().drafts.containsKey("a.txt"), "and the draft is still there");
    }

    /**
     * A draft is decoded text, so the encoding that decoded it is part of what it means.
     * Restored under Guess, a file the user had switched to UTF-16 would be saved back as
     * UTF-8 and change in every byte.
     */
    @Test
    void restoringADraftPutsBackTheEncodingItWasWrittenUnder() {
        FakeStore store = new FakeStore();
        store.drafts.put("a.txt", new EditorDrafts.Draft("/repo", "a.txt", "unsaved\n",
                Instant.now(), "baseline", com.jfx.git.core.TextEncoding.UTF_16LE.label()));
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setDraftStore(store);
            v.setOnSaveFile((p, text, onResult) -> onResult.accept(GitResult.ok("saved")));
            v.setFileContentSupplier(p -> "on disk\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            v.setWorkingCopyExistsCheck(p -> true);
            v.setEditable(true);
            v.showFileContent("a.txt", "on disk\n");
            stage.setUserData("keep-alive");
            FxHarness.pulse(v);
            return v;
        });
        assertEquals(com.jfx.git.core.TextEncoding.GUESS, viewer.getEncoding(), "precondition");

        FxHarness.run(() -> {
            button(viewer, "Restore draft").fire();
            FxHarness.pulse(viewer);
        });

        assertEquals(com.jfx.git.core.TextEncoding.UTF_16LE, viewer.getEncoding(),
                "the save has to use the encoding the draft was written under");
        assertEquals("unsaved\n", FxHarness.call(viewer::editorText));
    }

}
