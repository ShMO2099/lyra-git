package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editing session's rules, without a window: dirtiness, what a save does to the
 * baseline, and which line endings go back to disk.
 */
class EditorSessionTest {

    @Test
    void typingMakesItDirtyAndTypingItBackMakesItClean() {
        EditorSession session = EditorSessionFakes.session();
        session.open("a\nb", "a\nb", false);
        assertEquals(EditorSession.SaveState.CLEAN, session.saveState());

        session.follow(session.isDirty("a\nbc"));
        assertEquals(EditorSession.SaveState.DIRTY, session.saveState());
        session.follow(session.isDirty("a\nb"));
        assertEquals(EditorSession.SaveState.CLEAN, session.saveState());
    }

    @Test
    void aFailedSaveKeepsTheBaselineAndTypingDoesNotHideTheFailure() {
        EditorSession session = EditorSessionFakes.session();
        session.open("old", "old", false);
        session.savingStarted();
        session.saveFailed("read-only");

        assertEquals("old", session.baseline(), "only a confirmed write moves the baseline");
        assertTrue(session.lastSaveFailed());
        session.follow(true);
        assertTrue(session.lastSaveFailed(), "typing more does not make the failure go away");
        assertEquals("read-only", session.saveFailure());

        session.saveFailed(" ");
        assertEquals("the write did not complete", session.saveFailure(),
                "a failure always says something");
    }
}
