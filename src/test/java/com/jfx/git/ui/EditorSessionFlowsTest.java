package com.jfx.git.ui;

import com.jfx.git.core.TextEncoding;
import com.jfx.git.ui.EditorSession.Prompter.ChangedOnDisk;
import com.jfx.git.ui.EditorSession.Prompter.Ending;
import com.jfx.git.ui.EditorSession.Prompter.SaveFirst;
import com.jfx.git.ui.EditorSessionFakes.FakeSaver;
import com.jfx.git.ui.EditorSessionFakes.Rig;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editing session's flows, without a window: beginning, saving, the file changing
 * underneath, drafts and ending, each against fakes for the buffer, the prompter, the
 * working tree, the draft store and the save handler.
 *
 * <p>The questions are where these earn their keep. A real dialog in a headless test blocks
 * forever, so the viewer-level tests can only ever drive the paths that ask nothing; here
 * every answer to every question is one line.
 */
class EditorSessionFlowsTest {

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Nested
    class Saving {

        @Test
        void aCleanBufferHandsTheHostNothingAndAsksNothing() {
            Rig rig = new Rig().editing("a.txt", "one\n");

            rig.session.save();

            assertEquals(List.of(), rig.saver.writes, "nothing was typed, so nothing is written");
            assertEquals(List.of(), rig.prompter.asked);
        }

        @Test
        void aDirtySaveWritesMovesTheBaselineAndStaysInTheEditor() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.type("one\ntwo\n");

            rig.session.save();

            assertEquals(List.of("a.txt=one\ntwo\n"), rig.saver.writes);
            assertEquals(List.of(), rig.prompter.asked, "an untouched file on disk asks nothing");
            assertEquals("one\ntwo\n", rig.session.baseline(), "the baseline follows a confirmed write");
            assertFalse(rig.session.hasUnsavedEdits());
            assertTrue(rig.session.isOpen(), "Ctrl+S is a checkpoint, not the end of the session");
            assertTrue(rig.buffer.editing);
        }

        @Test
        void aFailedSaveKeepsTheBufferTheBaselineAndTheSession() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.FAIL;
            rig.type("one\ntwo\n");

            rig.session.save();

            assertTrue(rig.session.isOpen());
            assertEquals("one\ntwo\n", rig.buffer.text, "the buffer is the only copy now");
            assertEquals("one\n", rig.session.baseline(), "only a confirmed write moves the baseline");
            assertTrue(rig.session.hasUnsavedEdits());
            assertTrue(rig.session.lastSaveFailed());
            assertEquals("the file is read-only", rig.session.saveFailure());
        }

        /** Typed back to the baseline, a failed save still has something to retry. */
        @Test
        void afterAFailedSaveSavingAgainTriesEvenWhenTheBufferMatchesTheBaseline() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.FAIL;
            rig.type("one\ntwo\n");
            rig.session.save();

            rig.type("one\n");
            rig.saver.mode = FakeSaver.Mode.SUCCEED;
            rig.session.save();

            assertEquals(2, rig.saver.writes.size());
            assertFalse(rig.session.lastSaveFailed());
        }

        @Test
        void aSecondSaveWhileTheFirstIsInFlightDoesNotRaceIt() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.HOLD;
            rig.type("one\ntwo\n");

            rig.session.save();
            rig.session.save();

            assertEquals(1, rig.saver.writes.size());
            assertTrue(rig.session.isSaving());
        }

        @Test
        void saveAndStageRunsItsHandlerEvenWhenTheBufferIsClean() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            List<String> staged = new ArrayList<>();
            rig.session.setSaveAndStageHandler((path, text, onResult) -> {
                staged.add(path);
                onResult.accept(com.jfx.git.core.GitResult.ok("staged"));
            });

            rig.session.saveAndStage();

            assertEquals(List.of("a.txt"), staged, "the handler also updates the index");
            assertEquals(List.of(), rig.saver.writes, "and Save itself is not involved");
        }

        @Test
        void aConfirmedSaveClearsTheSessionsDraftAndAFailedOneKeepsIt() {
            Rig saved = new Rig().editing("a.txt", "one\n");
            saved.type("one\ntwo\n");
            saved.session.writeDraft();
            saved.session.save();
            assertTrue(saved.drafts.drafts.isEmpty(), "the work is on disk, so the draft goes");

            Rig failed = new Rig().editing("a.txt", "one\n");
            failed.saver.mode = FakeSaver.Mode.FAIL;
            failed.type("one\ntwo\n");
            failed.session.writeDraft();
            failed.session.save();
            assertTrue(failed.drafts.drafts.containsKey("a.txt"), "the draft is the last copy");
        }

        @Test
        void textTypedDuringASlowSaveIsParkedWhenTheSaveLands() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.HOLD;
            rig.type("one\ntwo\n");
            rig.session.save();
            rig.type("one\ntwo\nthree\n");

            rig.saver.held.get(0).accept(com.jfx.git.core.GitResult.ok("saved"));

            assertEquals("one\ntwo\nthree\n", rig.drafts.drafts.get("a.txt").text());
            assertEquals("one\ntwo\n", rig.session.baseline(), "what was written, not what was typed since");
            assertTrue(rig.session.hasUnsavedEdits());
        }
    }

    // ------------------------------------------------------------------
    // A write that lands after its session is over
    // ------------------------------------------------------------------

    /**
     * A save waits on the git lane, and the user need not wait with it: by the time the
     * write lands its session may have ended and another begun. The result belongs to the
     * session that asked for it, and to no other.
     */
    @Nested
    class AWriteThatLandsLate {

        /**
         * a.txt's write, started by {@code save}, held on a busy lane; its session left with
         * Discard; and b.txt being edited.
         */
        private Rig leftWhileSaving(java.util.function.Consumer<Rig> save) {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.HOLD;
            rig.type("one\nfrom a\n");
            save.accept(rig);
            assertTrue(rig.session.isSaving(), "precondition: a.txt's write is in flight");

            // The user stops waiting: another file, and Discard edits when asked.
            rig.prompter.saveFirst = SaveFirst.DISCARD;
            assertTrue(rig.session.leaveForNewContent(() -> { }));
            rig.showing("b.txt", "bee\n");
            rig.session.begin();
            assertTrue(rig.session.isOpen(), "precondition: b.txt is being edited");
            return rig;
        }

        @Test
        void doneThenSaveLandingLateLeavesTheNextSessionAndItsTypingAlone() {
            // Done, then Save at the question.
            Rig rig = leftWhileSaving(r -> {
                r.prompter.saveFirst = SaveFirst.SAVE;
                r.session.finish();
            });
            rig.type("bee\ntyped\n");

            rig.saver.held.get(0).accept(com.jfx.git.core.GitResult.ok("saved"));

            assertTrue(rig.session.isOpen(), "a.txt's Done does not end b.txt's session");
            assertTrue(rig.buffer.editing);
            assertEquals("bee\ntyped\n", rig.buffer.text, "and nothing redrew over the typing");
            assertEquals("bee\n", rig.session.baseline(), "a.txt's text is not what b.txt is on disk");
            assertTrue(rig.session.hasUnsavedEdits());
            assertFalse(rig.drafts.drafts.containsKey("b.txt"), "nor is b.txt parked for a.txt's write");
        }

        @Test
        void aLateWriteDoesNotRunTheNextSessionsDeferredNavigation() {
            List<String> ran = new ArrayList<>();
            // Another file, and Save at the question: the navigation waits for the write.
            Rig rig = leftWhileSaving(r -> {
                r.prompter.saveFirst = SaveFirst.SAVE;
                assertFalse(r.session.leaveForNewContent(() -> ran.add("to b.txt")));
            });
            rig.type("bee\ntyped\n");
            // b.txt's own Save first? Save, held on the lane behind a.txt's.
            rig.prompter.saveFirst = SaveFirst.SAVE;
            assertFalse(rig.session.leaveForNewContent(() -> ran.add("to c.txt")));

            rig.saver.held.get(0).accept(com.jfx.git.core.GitResult.ok("saved"));

            assertEquals(List.of(), ran, "c.txt waits for b.txt's write, not a.txt's");
            assertTrue(rig.session.isOpen());
            assertTrue(rig.session.isSaving(), "b.txt's write is still the one in flight");

            rig.saver.held.get(1).accept(com.jfx.git.core.GitResult.ok("saved"));

            assertEquals(List.of("to c.txt"), ran);
            assertFalse(rig.session.isOpen());
        }

        @Test
        void aLateFailureIsNotTheNextSessionsFailure() {
            // Ctrl+S.
            Rig rig = leftWhileSaving(r -> r.session.save());

            rig.saver.held.get(0).accept(com.jfx.git.core.GitResult.failed("the disk is full"));

            assertFalse(rig.session.lastSaveFailed(), "b.txt was never saved, so it never failed");
            assertEquals("", rig.session.saveFailure());
            assertTrue(rig.session.isOpen());
        }

        /**
         * Throwing the edits away while they are being written would not stop them landing;
         * it would only end the session they land in. So Discard waits for the write.
         */
        @Test
        void discardWaitsWhileTheEditsAreOnTheirWayToDisk() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.HOLD;
            rig.type("one\ntwo\n");
            rig.session.save();
            rig.prompter.discard = true;

            rig.session.confirmDiscard();

            assertEquals(List.of(), rig.prompter.asked, "nothing to ask about yet");
            assertTrue(rig.session.isOpen(), "the session waits for its write");

            rig.saver.held.get(0).accept(com.jfx.git.core.GitResult.ok("saved"));

            assertEquals("one\ntwo\n", rig.session.baseline(), "and takes it when it lands");
            assertTrue(rig.session.isOpen());
        }
    }

    // ------------------------------------------------------------------
    // Line endings
    // ------------------------------------------------------------------

    @Nested
    class KeepingLineEndings {

        @Test
        void aCrlfFileIsWrittenBackAsCrlfAndIsNotDirtyAfterwards() {
            Rig rig = new Rig().editing("a.txt", "one\r\ntwo\r\n");
            assertEquals("one\ntwo\n", rig.buffer.text, "precondition: the buffer holds no CRs");
            assertFalse(rig.session.hasUnsavedEdits(), "a CRLF file opens clean");

            rig.type("one\ntwo\nthree\n");
            rig.session.save();

            assertEquals(List.of("a.txt=one\r\ntwo\r\nthree\r\n"), rig.saver.writes);
            assertEquals(List.of(), rig.prompter.asked, "a CRLF file is not somebody else's edit");
            assertFalse(rig.session.hasUnsavedEdits(), "and it does not read as dirty for good");
        }

        @Test
        void aMixedFileIsWrittenInItsMajorityConvention() {
            Rig rig = new Rig().editing("a.txt", "a\r\nb\r\nc\n");
            rig.type("a\nb\nc\nd\n");

            rig.session.save();

            assertEquals(List.of("a.txt=a\r\nb\r\nc\r\nd\r\n"), rig.saver.writes);
        }

        @Test
        void aFileRenormalisedOnDiskCountsAsChanged() {
            Rig rig = new Rig().editing("a.txt", "one\r\ntwo\r\n");
            rig.disk.put("a.txt", "one\ntwo\n");

            assertTrue(rig.session.workingCopyChangedSinceEditingBegan(),
                    "the next save would quietly put every CR back");
        }
    }

    // ------------------------------------------------------------------
    // The file underneath
    // ------------------------------------------------------------------

    @Nested
    class TheFileUnderneath {

        private Rig changedUnderneath() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.type("one\nmine\n");
            rig.disk.put("a.txt", "one\ntheirs\n");
            return rig;
        }

        @Test
        void overwriteWritesTheEdits() {
            Rig rig = changedUnderneath();
            rig.prompter.change = ChangedOnDisk.OVERWRITE;

            rig.session.save();

            assertEquals(List.of("changed on disk?"), rig.prompter.asked);
            assertEquals(List.of("a.txt=one\nmine\n"), rig.saver.writes);
        }

        @Test
        void cancelWritesNothingAndKeepsTheEdits() {
            Rig rig = changedUnderneath();
            rig.prompter.change = ChangedOnDisk.CANCEL;

            rig.session.save();

            assertEquals(List.of(), rig.saver.writes);
            assertEquals("one\nmine\n", rig.buffer.text);
            assertTrue(rig.session.hasUnsavedEdits());
            assertTrue(rig.session.isOpen());
        }

        @Test
        void reloadTakesTheFileAsItIsNowAndWritesNothing() {
            Rig rig = changedUnderneath();
            rig.session.writeDraft();
            rig.prompter.change = ChangedOnDisk.RELOAD;

            rig.session.save();

            assertEquals(List.of(), rig.saver.writes);
            assertEquals("one\ntheirs\n", rig.buffer.text);
            assertEquals("one\ntheirs\n", rig.session.baseline());
            assertFalse(rig.session.hasUnsavedEdits(), "thrown away and reloaded is clean");
            assertTrue(rig.drafts.drafts.isEmpty(), "and the edits' draft goes with them");
        }

        @Test
        void compareSavesTheReconciledTextAgainstTheFileAsItIsNow() {
            Rig rig = changedUnderneath();
            rig.prompter.change = ChangedOnDisk.COMPARE;
            rig.prompter.reconciled = Optional.of("one\nmine\ntheirs\n");

            rig.session.save();

            assertEquals(List.of("one\nmine\n", "one\ntheirs\n", "one\n"), rig.prompter.compared,
                    "the edits, the file now, and what both diverged from");
            assertEquals(List.of("a.txt=one\nmine\ntheirs\n"), rig.saver.writes);
            assertEquals("one\nmine\ntheirs\n", rig.session.baseline());
            assertFalse(rig.session.workingCopyChangedSinceEditingBegan());
        }

        @Test
        void backingOutOfTheComparisonWritesNothing() {
            Rig rig = changedUnderneath();
            rig.prompter.change = ChangedOnDisk.COMPARE;
            rig.prompter.reconciled = Optional.empty();

            rig.session.save();

            assertEquals(List.of(), rig.saver.writes);
            assertEquals("one\nmine\n", rig.buffer.text);
        }

        @Test
        void aVanishedFileIsRecreatedOnlyWhenTheUserSaysSo() {
            Rig declined = new Rig().editing("a.txt", "one\n");
            declined.type("two\n");
            declined.disk.files.remove("a.txt");
            declined.prompter.recreate = false;
            declined.session.save();
            assertEquals(List.of("recreate?"), declined.prompter.asked);
            assertEquals(List.of(), declined.saver.writes);
            assertTrue(declined.session.hasUnsavedEdits());

            Rig recreated = new Rig().editing("a.txt", "one\n");
            recreated.type("two\n");
            recreated.disk.files.remove("a.txt");
            recreated.prompter.recreate = true;
            recreated.session.save();
            assertEquals(List.of("a.txt=two\n"), recreated.saver.writes);
        }

        @Test
        void theNoticeLightsOnceAndGoesOutWhenTheSaveResolvesIt() {
            Rig rig = changedUnderneath();

            rig.session.noteExternalChange();
            rig.session.noteExternalChange();
            assertTrue(rig.session.externalChangePending());

            rig.prompter.change = ChangedOnDisk.OVERWRITE;
            rig.session.save();
            assertFalse(rig.session.externalChangePending());
        }

        /**
         * The cap on the comparison is there so that a file this size is not read on the FX
         * thread - on every save, and on every return to the window while it is open.
         */
        @Test
        void aFileTooLargeToCompareIsNotReadToFindThatOut() {
            String large = "x".repeat(9 * 1024 * 1024);
            Rig rig = new Rig().editing("large.txt", large);
            rig.disk.put("large.txt", large + "y");
            rig.disk.reads.clear();

            assertFalse(rig.session.workingCopyChangedSinceEditingBegan(),
                    "too large to compare is not the same as a change");
            assertEquals(List.of(), rig.disk.reads, "and its size alone says so");
        }

        @Test
        void withNoWorkingTreeToReadTheSaveIsNotBlocked() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.disk.readable = false;
            rig.type("two\n");

            assertFalse(rig.session.workingCopyChangedSinceEditingBegan(),
                    "no way to compare is not the same as a change");
            rig.session.save();
            assertEquals(1, rig.saver.writes.size());
        }
    }

    // ------------------------------------------------------------------
    // Beginning
    // ------------------------------------------------------------------

    @Nested
    class Beginning {

        @Test
        void aRevisionIsEditedFromTheWorkingCopyNotFromHistory() {
            Rig rig = new Rig();
            rig.disk.put("a.txt", "as it is now\n");
            rig.showingRevision("a.txt", "as it was\n");

            rig.session.begin();

            assertTrue(rig.session.isOpen());
            assertArrayEquals("as it is now\n".getBytes(StandardCharsets.UTF_8), rig.buffer.workingCopyDrawn);
            assertEquals("as it is now\n", rig.buffer.text);
            assertFalse(rig.session.createdFromMissingFile());
        }

        @Test
        void aRevisionOfAFileNoLongerOnDiskIsEditedAsShownAndSavingCreatesIt() {
            Rig rig = new Rig().showingRevision("gone.txt", "from an old commit\n");

            rig.session.begin();

            assertTrue(rig.session.isOpen());
            assertNull(rig.buffer.workingCopyDrawn);
            assertTrue(rig.session.createdFromMissingFile());
            assertFalse(rig.session.workingCopyChangedSinceEditingBegan(),
                    "the file was always absent; that is what this session is fixing");

            rig.type("recreated\n");
            rig.session.save();
            assertEquals(List.of("gone.txt=recreated\n"), rig.saver.writes);
            assertEquals(List.of(), rig.prompter.asked, "never reported as vanished");

            rig.disk.put("gone.txt", "somebody else\n");
            assertTrue(rig.session.workingCopyChangedSinceEditingBegan(),
                    "once it exists, a write by someone else is a change");
        }

        @Test
        void aFileRenamedSinceIsEditedUnderItsNewName() {
            Rig rig = new Rig();
            rig.disk.put("new.txt", "renamed and edited\n");
            rig.showingRevision("old.txt", "as it was\n");
            List<java.util.function.Consumer<String>> answers = new ArrayList<>();
            rig.session.setRenameLookup((path, answer) -> answers.add(answer));

            rig.session.begin();
            assertFalse(rig.session.isOpen(), "the lookup has not answered yet");

            answers.get(0).accept("new.txt");

            assertEquals(List.of("new.txt"), rig.buffer.shownPaths);
            assertTrue(rig.session.isOpen());
            assertEquals("renamed and edited\n", rig.buffer.text);
            rig.type("more\n");
            rig.session.save();
            assertEquals(List.of("new.txt=more\n"), rig.saver.writes, "Save writes the new name");
        }

        @Test
        void aLookupAnsweringAfterTheFileWasLeftOpensNothing() {
            Rig rig = new Rig().showingRevision("old.txt", "as it was\n");
            rig.disk.put("new.txt", "x\n");
            List<java.util.function.Consumer<String>> answers = new ArrayList<>();
            rig.session.setRenameLookup((path, answer) -> answers.add(answer));
            rig.session.begin();

            rig.buffer.path = "other.txt";
            answers.get(0).accept("new.txt");

            assertFalse(rig.session.isOpen());
            assertEquals(List.of(), rig.buffer.shownPaths);
        }

        @Test
        void aWholeFileStillOnItsWayOpensNothingYet() {
            Rig rig = new Rig().showing("a.txt", "one\n");
            rig.buffer.wholeFileAvailable = false;

            rig.session.begin();

            assertFalse(rig.session.isOpen(), "the user presses Edit again once it lands");
            assertFalse(rig.buffer.editing);
        }

        @Test
        void withNoSaveHandlerOrABinaryFileThereIsNothingToBegin() {
            Rig noHandler = new Rig().showing("a.txt", "one\n");
            noHandler.session.setSaveHandler(null);
            noHandler.session.begin();
            assertFalse(noHandler.session.isOpen());

            Rig binary = new Rig().showing("a.bin", "x");
            binary.buffer.encoding = TextEncoding.BINARY;
            binary.session.begin();
            assertFalse(binary.session.isOpen());
        }
    }

    // ------------------------------------------------------------------
    // Drafts
    // ------------------------------------------------------------------

    @Nested
    class Drafts {

        @Test
        void aDraftIsWrittenOnlyOnceSomethingHasBeenTyped() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.session.writeDraft();
            assertEquals(0, rig.drafts.saves, "opening a file and typing nothing parks nothing");

            rig.type("one\ntwo\n");
            rig.session.writeDraft();
            assertEquals("one\ntwo\n", rig.drafts.drafts.get("a.txt").text());
            assertTrue(rig.session.ownsDraft());
        }

        @Test
        void aStoredDraftIsOfferedAndRestoringItLeavesTheSessionDirty() {
            Rig rig = new Rig().showing("a.txt", "on disk\n");
            rig.drafts.park("a.txt", "my unsaved work\n", "UTF-8");

            rig.session.offerDraft();
            assertTrue(rig.prompter.offerUp);

            rig.session.restoreDraft();

            assertTrue(rig.session.isOpen());
            assertFalse(rig.prompter.offerUp, "the offer is done with");
            assertEquals("my unsaved work\n", rig.buffer.text);
            assertEquals("on disk\n", rig.session.baseline(), "the baseline is the disk now, not the draft's");
            assertTrue(rig.session.hasUnsavedEdits());
            assertTrue(rig.session.ownsDraft(), "a save or a discard of this session removes it");
        }

        @Test
        void aDraftMatchingTheFileIsDroppedSilentlyCrlfOrNot() {
            Rig rig = new Rig().showing("a.txt", "one\r\ntwo\r\n");
            rig.drafts.park("a.txt", "one\ntwo\n", "UTF-8");

            rig.session.offerDraft();

            assertFalse(rig.prompter.offerUp);
            assertTrue(rig.drafts.drafts.isEmpty());
        }

        /**
         * The offer is looked for every time the file is shown, so its staleness check must
         * not read a file of this size; whether the draft is stale is left to the user.
         */
        @Test
        void aDraftOverAFileTooLargeToCompareIsOfferedWithoutReadingTheFile() {
            Rig rig = new Rig().showing("large.txt", "x".repeat(9 * 1024 * 1024));
            rig.drafts.park("large.txt", "unsaved\n", "UTF-8");
            rig.disk.reads.clear();

            rig.session.offerDraft();

            assertTrue(rig.prompter.offerUp);
            assertEquals(List.of(), rig.disk.reads);
        }

        @Test
        void noDraftIsOfferedOverARevisionOrDuringASession() {
            Rig revision = new Rig().showingRevision("a.txt", "as it was\n");
            revision.drafts.park("a.txt", "unsaved\n", "UTF-8");
            revision.session.offerDraft();
            assertFalse(revision.prompter.offerUp);
            assertTrue(revision.drafts.drafts.containsKey("a.txt"), "kept for when the working copy is opened");

            Rig editing = new Rig().editing("a.txt", "on disk\n");
            editing.drafts.park("a.txt", "unsaved\n", "UTF-8");
            editing.session.offerDraft();
            assertFalse(editing.prompter.offerUp);
        }

        @Test
        void beginningASessionWithdrawsTheOfferAndKeepsTheUnrecoveredDraft() {
            Rig rig = new Rig().showing("a.txt", "on disk\n");
            rig.drafts.park("a.txt", "unsaved\n", "UTF-8");
            rig.session.offerDraft();

            rig.session.begin();
            rig.session.finish();

            assertFalse(rig.prompter.offerUp);
            assertTrue(rig.drafts.drafts.containsKey("a.txt"),
                    "an earlier session's work is still waiting to be recovered");
        }

        @Test
        void restoringPutsBackTheEncodingTheDraftWasWrittenUnder() {
            Rig rig = new Rig().showing("a.txt", "on disk\n");
            rig.buffer.encoding = TextEncoding.GUESS;
            rig.drafts.park("a.txt", "unsaved\n", TextEncoding.UTF_16LE.label());
            rig.session.offerDraft();

            rig.session.restoreDraft();

            assertEquals(TextEncoding.UTF_16LE, rig.buffer.adopted);
        }

        @Test
        void comparingTheDraftStartsEditingWithWhatTheUserMade() {
            Rig rig = new Rig().showing("a.txt", "on disk\n");
            rig.drafts.park("a.txt", "unsaved\n", "UTF-8");
            rig.session.offerDraft();
            rig.prompter.draftChoice = Optional.of("merged\n");

            rig.session.compareDraft();

            assertEquals(List.of("unsaved\n", "on disk\n"), rig.prompter.compared);
            assertTrue(rig.session.isOpen());
            assertEquals("merged\n", rig.buffer.text);
        }

        @Test
        void onlyLookingAtTheComparisonChangesNothing() {
            Rig rig = new Rig().showing("a.txt", "on disk\n");
            rig.buffer.encoding = TextEncoding.GUESS;
            rig.drafts.park("a.txt", "unsaved\n", TextEncoding.UTF_8.label());
            rig.session.offerDraft();

            rig.session.compareDraft();

            assertFalse(rig.session.isOpen());
            assertTrue(rig.prompter.offerUp, "the offer still stands");
            assertNull(rig.buffer.adopted, "looking has no side effect on the encoding");
        }

        @Test
        void dismissingTheOfferForgetsTheDraft() {
            Rig rig = new Rig().showing("a.txt", "on disk\n");
            rig.drafts.park("a.txt", "unsaved\n", "UTF-8");
            rig.session.offerDraft();

            rig.session.dismissDraft();

            assertFalse(rig.prompter.offerUp);
            assertTrue(rig.drafts.drafts.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Ending
    // ------------------------------------------------------------------

    @Nested
    class LeavingForNewContent {

        @Test
        void aCleanSessionEndsAndTheNavigationGoesAheadNow() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            List<String> ran = new ArrayList<>();

            assertTrue(rig.session.leaveForNewContent(() -> ran.add("navigated")));

            assertFalse(rig.session.isOpen());
            assertFalse(rig.buffer.editing);
            assertEquals(List.of(), ran, "the caller carries on itself");
            assertEquals(List.of(), rig.prompter.asked);
        }

        @Test
        void saveDefersTheNavigationUntilTheWriteIsConfirmed() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.HOLD;
            rig.prompter.saveFirst = SaveFirst.SAVE;
            rig.type("one\ntwo\n");
            List<String> ran = new ArrayList<>();

            assertFalse(rig.session.leaveForNewContent(() -> ran.add("navigated")));
            assertEquals(List.of("save first? " + Ending.REPLACING), rig.prompter.asked);
            assertEquals(List.of(), ran, "not before the write lands");
            assertTrue(rig.session.isOpen());

            rig.saver.held.get(0).accept(com.jfx.git.core.GitResult.ok("saved"));

            assertEquals(List.of("navigated"), ran);
            assertFalse(rig.session.isOpen());
        }

        @Test
        void aFailedSaveKeepsTheSessionAndNeverNavigates() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.FAIL;
            rig.prompter.saveFirst = SaveFirst.SAVE;
            rig.type("one\ntwo\n");
            List<String> ran = new ArrayList<>();

            assertFalse(rig.session.leaveForNewContent(() -> ran.add("navigated")));

            assertEquals(List.of(), ran);
            assertTrue(rig.session.isOpen());
            assertEquals("one\ntwo\n", rig.buffer.text);
            assertTrue(rig.session.lastSaveFailed());
        }

        @Test
        void discardEndsTheSessionDropsItsDraftAndGoesAhead() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.prompter.saveFirst = SaveFirst.DISCARD;
            rig.type("one\ntwo\n");
            rig.session.writeDraft();

            assertTrue(rig.session.leaveForNewContent(() -> { }));

            assertFalse(rig.session.isOpen());
            assertTrue(rig.drafts.drafts.isEmpty(), "chosen, so nothing comes back as a recovered draft");
            assertEquals(List.of(), rig.saver.writes);
        }

        @Test
        void cancelRefusesTheNavigationAndKeepsEverything() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.prompter.saveFirst = SaveFirst.CANCEL;
            rig.type("one\ntwo\n");
            rig.session.writeDraft();

            assertFalse(rig.session.leaveForNewContent(() -> { }));

            assertTrue(rig.session.isOpen());
            assertEquals("one\ntwo\n", rig.buffer.text);
            assertTrue(rig.drafts.drafts.containsKey("a.txt"));
        }

        @Test
        void theQuestionSaysWhenTheLastSaveFailed() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.saver.mode = FakeSaver.Mode.FAIL;
            rig.type("one\ntwo\n");
            rig.session.save();

            rig.session.leaveForNewContent(() -> { });

            assertEquals("the file is read-only", rig.prompter.failureShown);
        }
    }

    @Nested
    class Finishing {

        @Test
        void aCleanSessionFinishesAndRedrawsWithoutAsking() {
            Rig rig = new Rig().editing("a.txt", "one\n");

            rig.session.finish();

            assertFalse(rig.session.isOpen());
            assertEquals(1, rig.buffer.restores);
            assertEquals(List.of(), rig.prompter.asked);
        }

        @Test
        void saveFinishesOnlyOnceTheWriteIsConfirmed() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.prompter.saveFirst = SaveFirst.SAVE;
            rig.saver.mode = FakeSaver.Mode.FAIL;
            rig.type("one\ntwo\n");

            rig.session.finish();
            assertEquals(List.of("save first? " + Ending.FINISHING), rig.prompter.asked);
            assertTrue(rig.session.isOpen(), "a failed save keeps the session");

            rig.saver.mode = FakeSaver.Mode.SUCCEED;
            rig.session.finish();
            assertFalse(rig.session.isOpen());
            assertEquals("one\ntwo\n", rig.disk.text("a.txt"));
        }

        @Test
        void discardThrowsTheEditsAwayAndCancelKeepsThem() {
            Rig discarded = new Rig().editing("a.txt", "one\n");
            discarded.prompter.saveFirst = SaveFirst.DISCARD;
            discarded.type("one\ntwo\n");
            discarded.session.finish();
            assertFalse(discarded.session.isOpen());
            assertEquals("one\n", discarded.buffer.text, "redrawn from the file");

            Rig kept = new Rig().editing("a.txt", "one\n");
            kept.prompter.saveFirst = SaveFirst.CANCEL;
            kept.type("one\ntwo\n");
            kept.session.finish();
            assertTrue(kept.session.isOpen());
            assertEquals("one\ntwo\n", kept.buffer.text);
        }

        /**
         * Done keeps what is on screen. After a save that is what was written - not the
         * text editing began from, which is what the display had cached.
         */
        @Test
        void doneAfterASaveShowsWhatWasSavedNotWhatEditingBeganFrom() {
            Rig rig = new Rig().editing("a.txt", "one\r\n");
            rig.type("one\ntwo\n");
            rig.session.save();

            rig.session.finish();

            assertFalse(rig.session.isOpen());
            assertEquals("one\ntwo\n", rig.buffer.text, "what is on screen stays on screen");
            assertEquals("one\r\ntwo\r\n", rig.buffer.shown,
                    "drawn from the file as it was written, line endings and all");
        }

        /** Discarding throws away what came after the last save; the save itself stays. */
        @Test
        void discardAfterASaveGoesBackToWhatWasSaved() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.type("one\ntwo\n");
            rig.session.save();
            rig.type("one\ntwo\nthree\n");
            rig.prompter.discard = true;

            rig.session.confirmDiscard();

            assertFalse(rig.session.isOpen());
            assertEquals("one\ntwo\n", rig.buffer.text, "the file as it is on disk now");
        }

        @Test
        void theDiscardButtonAsksOnlyWhenThereIsSomethingToLose() {
            Rig clean = new Rig().editing("a.txt", "one\n");
            clean.session.confirmDiscard();
            assertFalse(clean.session.isOpen());
            assertEquals(List.of(), clean.prompter.asked);

            Rig declined = new Rig().editing("a.txt", "one\n");
            declined.type("one\ntwo\n");
            declined.prompter.discard = false;
            declined.session.confirmDiscard();
            assertEquals(List.of("discard?"), declined.prompter.asked);
            assertTrue(declined.session.isOpen());

            Rig confirmed = new Rig().editing("a.txt", "one\n");
            confirmed.type("one\ntwo\n");
            confirmed.session.writeDraft();
            confirmed.prompter.discard = true;
            confirmed.session.confirmDiscard();
            assertFalse(confirmed.session.isOpen());
            assertTrue(confirmed.drafts.drafts.isEmpty(), "the draft goes with the edits");
        }

        @Test
        void closingTheTabEndsTheSessionAndItsDraftWithoutRedrawing() {
            Rig rig = new Rig().editing("a.txt", "one\n");
            rig.type("one\ntwo\n");
            rig.session.writeDraft();

            rig.session.endForClose();

            assertFalse(rig.session.isOpen());
            assertTrue(rig.drafts.drafts.isEmpty());
            assertEquals(0, rig.buffer.restores, "the tab is going; nothing to draw");
        }
    }

    // ------------------------------------------------------------------
    // Restore this version
    // ------------------------------------------------------------------

    @Nested
    class RestoringAVersion {

        @Test
        void theRevisionIsWrittenOnlyOnceConfirmed() {
            Rig declined = new Rig().showingRevision("a.txt", "as it was\n");
            declined.session.restoreVersion("a.txt", "as it was\n");
            assertEquals(List.of("restore?"), declined.prompter.asked);
            assertEquals(List.of(), declined.saver.writes);

            Rig confirmed = new Rig().showingRevision("a.txt", "as it was\n");
            confirmed.prompter.restore = true;
            confirmed.session.restoreVersion("a.txt", "as it was\n");
            assertEquals(List.of("a.txt=as it was\n"), confirmed.saver.writes);
        }

        @Test
        void aRenamedFileAsksWhichNameToWrite() {
            Rig rig = new Rig().showingRevision("old.txt", "as it was\n");
            rig.session.setRenameLookup((path, answer) -> answer.accept("new.txt"));
            rig.prompter.destination = Optional.of("new.txt");

            rig.session.restoreVersion("old.txt", "as it was\n");

            assertEquals(List.of("restore where? old.txt or new.txt"), rig.prompter.asked);
            assertEquals(List.of("new.txt=as it was\n"), rig.saver.writes);
        }

        /**
         * The lookup answers later; a host that has dropped its save handler by then has
         * nowhere to write. This used to throw from inside the lookup's callback.
         */
        @Test
        void aSaveHandlerGoneByTheTimeTheLookupAnswersWritesNothing() {
            Rig rig = new Rig().showingRevision("a.txt", "as it was\n");
            List<java.util.function.Consumer<String>> answers = new ArrayList<>();
            rig.session.setRenameLookup((path, answer) -> answers.add(answer));
            rig.prompter.restore = true;
            rig.session.restoreVersion("a.txt", "as it was\n");

            rig.session.setSaveHandler(null);
            answers.get(0).accept(null);

            assertEquals(List.of(), rig.saver.writes);
        }
    }

    // ------------------------------------------------------------------
    // The session stays testable as plain values
    // ------------------------------------------------------------------

    /** Nothing from JavaFX in the session, so all of the above runs without a toolkit. */
    @Test
    void theSessionImportsNothingFromJavaFx() throws Exception {
        String text = Files.readString(Path.of("src/main/java/com/jfx/git/ui/EditorSession.java"),
                StandardCharsets.UTF_8);
        assertFalse(text.contains("javafx."), "EditorSession reaches into JavaFX");
    }
}
