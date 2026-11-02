package com.jfx.git.ui;

import com.jfx.git.core.EditorDrafts;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.LineEndings;
import com.jfx.git.core.TextEncoding;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One editing session, from Edit to Done: what the file was on disk, how far the buffer has
 * got towards being there again, and every flow that moves it - beginning, saving, drafts,
 * the file changing underneath, and ending.
 *
 * <p>The host - {@link DiffViewer} - shows the text and asks the questions; this decides
 * what happens. It reaches the host through two small interfaces: a {@link Buffer}, the
 * text surface and the file on it, and a {@link Prompter}, every question the flows ask and
 * the draft recovery offer. The disk is read through a {@link WorkingCopy}, and writes go
 * through the host's {@link SaveHandler}s. Everything else on screen - rendering, colouring,
 * which controls show - stays the host's.
 *
 * <p>The buffer's text itself stays in the host's text area, which owns the caret, the
 * selection and the undo history; everything the session needs to know about that text is
 * here. The fields describing the document were once loose on the viewer beside the fields
 * describing what it was <em>showing</em>, and every re-render was a chance to treat the
 * document as just another picture of the file.
 *
 * <p>Deliberately free of JavaFX: every rule in it - when the buffer is dirty, what a
 * failed save leaves behind, which line endings go back to disk, when a draft is offered -
 * can be checked without a window, with fakes for the five collaborators.
 */
final class EditorSession {

    /**
     * How far the buffer has got towards being on disk.
     *
     * <p>Modelled explicitly because "dirty or not" cannot express a save that was
     * attempted and did not happen. That state used to be indistinguishable from clean,
     * so the next file selection replaced the buffer without a word.
     */
    enum SaveState {
        /** The buffer matches what is on disk. */
        CLEAN,
        /** Edited, not yet written. */
        DIRTY,
        /** A write is in flight. */
        SAVING,
        /** A write was attempted and failed; the buffer is the only copy. */
        FAILED
    }

    // ------------------------------------------------------------------
    // The seams
    // ------------------------------------------------------------------

    /**
     * The host's side of the session: the text being edited, the file it is, and the surface
     * showing it. Called on the FX thread, like everything here.
     */
    interface Buffer {
        /** The file on screen; empty when there is none. */
        String path();

        /** The encoding the file is read and saved in. */
        TextEncoding encoding();

        /**
         * Whether what is on screen is the working tree, the only copy that can be edited
         * in place. A revision of a file - a commit's, a stash's - is not.
         */
        boolean showsWorkingCopy();

        /**
         * The buffer's text. Never contains a carriage return: the text area works in
         * paragraphs and drops every CR on the way in.
         */
        String text();

        /** Replaces the whole text: a recovered draft, a reconciled version, a reload. */
        void replace(String text);

        /**
         * Switches to the whole file, as editing needs, with nothing drawn in that would be
         * saved into it.
         *
         * @return false when the whole file is still on its way and the user has to ask
         *         again once it lands
         */
        boolean showWholeFile();

        /**
         * Draws the file as the session will edit it: {@code workingCopy} in place of the
         * revision on screen when given, which makes what is shown the working copy.
         *
         * @return the file as it reads with its own line endings - the last moment they
         *         are visible - or {@code null} when there is no source for it
         */
        String showForEditing(byte[] workingCopy);

        /**
         * Shows {@code path}'s working copy in place of what is on screen: the name a file
         * shown from a revision has now, after a rename.
         */
        void showWorkingCopyOf(String path, byte[] content);

        /** Reads what is on screen again under {@code encoding}. Only outside a session. */
        void adoptEncoding(TextEncoding encoding);

        /** Turns the surface into an editor, or back into a display. */
        void setEditing(boolean editing);

        /**
         * Draws the file from its source again, dropping whatever is in the buffer. The
         * source is the file as this session last knew it on disk: what editing began
         * from, or what the last save wrote there - see {@link #saved}.
         */
        void restoreDisplay();

        /**
         * {@code document}, the file with its own line endings, has just been written to
         * the working tree. From now on it is the source the file is drawn from, and what
         * is on screen is the working copy.
         *
         * <p>Done and Discard both end in {@link #restoreDisplay}. Without this it drew
         * whatever had been cached when editing began, so Save followed by Done showed the
         * file as it was before the save.
         */
        void saved(String document);

        /**
         * Something the host shows about the session changed: the save state, the
         * changed-on-disk notice, the line endings.
         */
        void refresh();
    }

    /** Every question the flows ask, and the draft recovery offer. */
    interface Prompter {
        /** Why a session with unsaved edits is ending. */
        enum Ending {
            /** The user asked to stop editing. */
            FINISHING,
            /** Other content is about to replace the buffer. */
            REPLACING
        }

        enum SaveFirst { SAVE, DISCARD, CANCEL }

        enum ChangedOnDisk { COMPARE, OVERWRITE, RELOAD, CANCEL }

        /**
         * Whether to save the edits before the session ends.
         *
         * @param lastFailure why the last save failed, or {@code null} when it did not
         */
        SaveFirst askToSave(String path, Ending why, String lastFailure);

        /** Whether to throw the unsaved edits away; Enter must not. */
        boolean confirmDiscard(String path, String lastFailure);

        /** The file was deleted or moved while it was open: write it back? */
        boolean askToRecreate(String path);

        /** The file changed on disk since the session opened it. */
        ChangedOnDisk askAboutChange(String path);

        /**
         * The edits and the file as it is now, side by side, to make one version from both.
         *
         * @return the text to save, or empty when the user backed out
         */
        Optional<String> reconcile(String path, String mine, String onDisk, String baseline);

        /** Says a draft parked at {@code savedAt} can be had back; not a modal. */
        void offerDraft(Instant savedAt);

        void withdrawDraftOffer();

        /**
         * The draft beside the working copy.
         *
         * @return the text to start editing with, or empty when the user only looked
         */
        Optional<String> compareDraft(String path, String draft, String onDisk);

        /** Whether to write the revision on screen over the working copy; Enter must not. */
        boolean confirmRestore(String path);

        /**
         * Which name to restore a renamed file's revision under.
         *
         * @return the path to write, or empty to cancel
         */
        Optional<String> chooseRestoreDestination(String path, String renamedTo);
    }

    /**
     * The working tree, read.
     *
     * <p>The host reads it with the same reader File view draws from. Only a host with a
     * save handler ever opens a session, and in that host the reader has to be the working
     * tree's; a viewer whose reader is a revision's - file history, snapshots - saves
     * nothing, so no session ever takes that revision for the working copy.
     */
    interface WorkingCopy {
        /**
         * Whether there is a working tree to read at all. A host with no reader has none,
         * and blocking its saves for want of a comparison it could never make would be a
         * bug of its own.
         */
        boolean readable();

        /**
         * Whether the path is really on disk, as opposed to readable-but-empty: the reader
         * answers empty bytes for both, and one is editable while the other is not there.
         */
        boolean exists(String path);

        /** The file's bytes, or {@code null} when it cannot be read. */
        byte[] read(String path);

        /**
         * How large the file is, told without reading it; negative when it cannot be told
         * that way, and only reading it would say.
         */
        long size(String path);
    }

    /**
     * Writes an edited buffer back to disk and says whether it worked.
     *
     * <p>A {@code BiConsumer} could not say. The viewer handed the text over and left
     * edit mode in the same breath, so a write that failed - a read-only file, a full
     * disk - left the only copy of the user's work in a buffer the viewer had already
     * marked clean, and the next file selection replaced it silently. The result
     * callback is what lets the document outlive a failed save.
     */
    @FunctionalInterface
    interface SaveHandler {
        /**
         * @param onResult called on the FX thread, exactly once, with the outcome; the
         *                 session holds the buffer and the dirty state until it reports
         *                 success
         */
        void save(String path, String text, Consumer<GitResult> onResult);
    }

    /**
     * Finds what a file shown from an older revision is called in the working tree now.
     *
     * <p>A function rather than a name handed over with the content: answering takes a
     * comparison across everything changed since that revision, which is worth doing for
     * the one file being edited or restored and not for every file that is looked at.
     */
    @FunctionalInterface
    interface RenameLookup {
        /**
         * @param answer called on the FX thread, once, with the name the file has now, or
         *               {@code null} when it still has the name it is shown under, or none
         */
        void currentNameOf(String path, Consumer<String> answer);
    }

    /**
     * Where an unsaved buffer is parked so a crash cannot take it.
     *
     * <p>Keyed by path within one repository, which the host wires in: the session has
     * never known which repository it belongs to.
     */
    interface DraftStore {
        void save(String path, String text, String baseline, String encoding);

        Optional<EditorDrafts.Draft> find(String path);

        void discard(String path);
    }

    private final Buffer buffer;
    private final Prompter prompter;
    private final WorkingCopy workingCopy;

    /** Writes the buffer; {@code null} makes the built-in editor unavailable. */
    private SaveHandler saveHandler;
    /** Writes the buffer and stages the file; {@code null} offers no such step. */
    private SaveHandler saveAndStageHandler;
    /** {@code null} where the name on screen is the one to write. */
    private RenameLookup renameLookup;
    /** {@code null} disables drafting. */
    private DraftStore drafts;

    EditorSession(Buffer buffer, Prompter prompter, WorkingCopy workingCopy) {
        this.buffer = buffer;
        this.prompter = prompter;
        this.workingCopy = workingCopy;
    }

    void setSaveHandler(SaveHandler handler) {
        this.saveHandler = handler;
    }

    void setSaveAndStageHandler(SaveHandler handler) {
        this.saveAndStageHandler = handler;
    }

    void setRenameLookup(RenameLookup lookup) {
        this.renameLookup = lookup;
    }

    void setDraftStore(DraftStore store) {
        this.drafts = store;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /**
     * True while the buffer is the file being edited.
     *
     * <p>Only whole-file content can be edited: a diff is a description of a change,
     * not the file, so there is nothing coherent to write back from it.
     */
    private boolean open;

    /**
     * Which session this is: moves on whenever one opens or ends.
     *
     * <p>A write waits on the git lane and the user need not wait with it - they can leave
     * the file, discard, open another and start typing before it lands. Its result used to
     * act on whatever session was open by then: the next file took the old one's text as
     * its baseline, was parked as a draft for it, and was ended - its typing redrawn away -
     * by the old one's Done. A result now acts only on the session that asked for it.
     */
    private long generation;

    /**
     * The text as last known to be on disk: what editing started from, and afterwards
     * whatever the most recent <em>successful</em> save wrote.
     *
     * <p>Only a confirmed write moves it. A failed one leaves it where it was, so the
     * buffer stays dirty and the document stays protected - which is the whole point.
     */
    private String baseline = "";

    private SaveState saveState = SaveState.CLEAN;
    /** Why the last save failed, shown on the badge. */
    private String saveFailure = "";
    /**
     * Navigation deferred until a save finishes, so that choosing "Save" when leaving a
     * dirty buffer does not hand the text off and move on regardless of the outcome.
     */
    private Runnable pendingAfterSave;

    /**
     * The line-ending convention the file had when this session opened it; {@code null}
     * outside a session, and whenever the document could not be read.
     */
    private LineEndings endings;
    /** The majority convention behind a {@code MIXED} file, which is what it is saved as. */
    private LineEndings dominantEnding;
    /** How many of each terminator the file had, for the footer's tooltip. */
    private LineEndings.Counts endingCounts;

    /** Whether the file changed on disk while this session held it. */
    private boolean externalChangePending;
    /**
     * Whether this session began with no file on disk, so Save is creating one. Without
     * it, the "it vanished" check fires on the entirely normal case of editing a revision
     * of a file that was deleted later and saving it back.
     */
    private boolean createdFromMissingFile;
    /**
     * Whether the stored draft for this file is this session's own. A draft left by an
     * earlier, crashed session belongs to the recovery offer, not to whichever session
     * happens to be open.
     */
    private boolean ownsDraft;
    /** The draft on offer, while the offer is up. */
    private EditorDrafts.Draft pendingDraft;

    boolean isOpen() {
        return open;
    }

    /** Whether the user has actually changed anything since the last confirmed write. */
    boolean hasUnsavedEdits() {
        return open && isDirty(buffer.text());
    }

    /**
     * Editing needs a save handler, a file, and decodable content.
     *
     * <p>Deliberately does not require the working copy on screen: a historical, stashed
     * or compared revision can be edited too - {@link #begin()} starts from the working
     * copy, and Save always writes into the working tree.
     */
    boolean canEdit() {
        return saveHandler != null
                && !buffer.path().isEmpty()
                && buffer.encoding() != TextEncoding.BINARY;
    }

    boolean canSave() {
        return saveHandler != null;
    }

    boolean canSaveAndStage() {
        return saveAndStageHandler != null;
    }

    // ------------------------------------------------------------------
    // Beginning
    // ------------------------------------------------------------------

    /**
     * Turns the file on screen into an editor.
     *
     * <p>A revision of a file that is not on disk under that name may be a file renamed
     * since: its working copy is under the new name, and that is the copy to edit -
     * starting from the revision and saving would recreate the old name beside it. A file
     * deleted since has no new name, and is recreated from the revision.
     */
    void begin() {
        if (open || !canEdit()) {
            return;
        }
        String shown = buffer.path();
        RenameLookup lookup = renameLookup;
        if (!buffer.showsWorkingCopy() && lookup != null && workingCopy.readable()
                && !shown.isEmpty() && !workingCopy.exists(shown)) {
            lookup.currentNameOf(shown, renamedTo -> {
                // Answered later: only for the file still on screen, and only once.
                if (open || !shown.equals(buffer.path())) {
                    return;
                }
                if (renamedTo != null && !renamedTo.equals(shown) && workingCopy.exists(renamedTo)) {
                    byte[] onDisk = workingCopy.read(renamedTo);
                    buffer.showWorkingCopyOf(renamedTo, onDisk == null ? new byte[0] : onDisk);
                }
                beginAsShown();
            });
            return;
        }
        beginAsShown();
    }

    /** {@link #begin} for the file on screen, under the name it is shown with. */
    private void beginAsShown() {
        if (open || !canEdit()) {
            return;
        }
        if (!buffer.showWholeFile()) {
            return;
        }
        String path = buffer.path();
        // "Edit working copy" has to actually start from the working copy. It used to open
        // whatever revision was on screen - a commit's version, a stash's, the other side
        // of a comparison - and Save then wrote that over the working copy, so editing a
        // file from history silently discarded every change made to it since. That is
        // Restore, which is its own flow and asks first.
        //
        // Only where there is a working copy to reach: with no working tree to read, editing
        // what is on screen is the only thing it could mean. A file not on disk keeps the
        // revision in the buffer, and Save recreates it from that.
        byte[] fromDisk = null;
        if (!buffer.showsWorkingCopy() && workingCopy.readable() && workingCopy.exists(path)) {
            fromDisk = workingCopy.read(path);
        }
        // A session that starts with nothing on disk is creating the file, so "it is not
        // there" is the expected state rather than something that happened underneath.
        boolean creatingTheFile = workingCopy.readable() && !workingCopy.exists(path);
        // A new session supersedes the recovery offer. Left up, "Restore draft" would
        // replace this session's typing without asking, and "Discard draft" would delete
        // this session's own crash copy once the debounce had written one.
        withdrawDraftOffer();

        // Drawn before the session opens, not after: from here on the host leaves the
        // buffer alone, so this is the last chance to put the file's own text into it.
        String document = buffer.showForEditing(fromDisk);
        open(buffer.text(), document, creatingTheFile);
        buffer.setEditing(true);
    }

    /**
     * Starts a session on {@code bufferText}, the file as the text area holds it.
     *
     * @param document the same file with its carriage returns, which the buffer has lost
     */
    void open(String bufferText, String document, boolean createdFromMissingFile) {
        this.generation++;
        this.open = true;
        this.baseline = bufferText;
        captureEndings(document);
        this.createdFromMissingFile = createdFromMissingFile;
        this.externalChangePending = false;
        this.ownsDraft = false;
        this.saveState = SaveState.CLEAN;
        this.saveFailure = "";
    }

    /** Back to no session. A write still in flight belongs to nothing from here on. */
    void close() {
        generation++;
        open = false;
        saveState = SaveState.CLEAN;
        saveFailure = "";
        pendingAfterSave = null;
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /**
     * Writes the buffer to disk, and stays in the editor.
     *
     * <p>Ctrl+S in the middle of a change is a checkpoint, not a way of saying the work is
     * over. And the buffer is held until the write is confirmed: see {@link #requestSave}.
     */
    void save() {
        if (!open) {
            return;
        }
        requestSave(saveHandler, null, false);
    }

    /**
     * Saves and stages in one step, for the change the user knows is finished. Like
     * {@link #save()} it keeps the session open; {@link #finish()} is how a session ends.
     */
    void saveAndStage() {
        if (!open) {
            return;
        }
        // A staged-file action even when an earlier Save already wrote the buffer: the
        // handler also updates the index.
        requestSave(saveAndStageHandler, null, true);
    }

    /**
     * Hands the buffer to {@code handler} and keeps it until the write is confirmed.
     *
     * <p>The ordering is the fix. Edit mode used to end, and the dirty flag used to
     * clear, before the write was even attempted - so a failure left
     * {@code hasUnsavedEdits()} answering false about text that existed nowhere else.
     * Here nothing about the document changes until a successful result arrives, and a
     * failure is a state the editor stays in, visible on the badge.
     *
     * @param afterSaved   run once the buffer is safely on disk - used by navigation, which
     *                     must not proceed on a save that did not happen
     * @param forceHandler run the handler even when the buffer matches its baseline;
     *                     used by Save and stage, whose handler also updates the index
     */
    private void requestSave(SaveHandler handler, Runnable afterSaved, boolean forceHandler) {
        String path = buffer.path();
        if (handler == null || path.isEmpty()) {
            return;
        }
        if (isSaving()) {
            // A second Ctrl+S while the first write is in flight would race it.
            return;
        }
        if (!forceHandler && !isDirty(buffer.text()) && !lastSaveFailed()) {
            // Nothing to write. The follow-up still runs: the caller asked for the buffer
            // to be safe before moving on, and it already is. Checked before the disk
            // comparison below, so an untouched buffer never prompts about anything.
            if (afterSaved != null) afterSaved.run();
            return;
        }
        if (!reconcileExternalChange(path)) {
            // "Discard my edits and reload" leaves nothing unsaved, and a caller waiting on
            // the buffer being safe - a navigation deferred behind this save - can go ahead.
            // Cancel leaves it dirty, and the caller keeps waiting, as it should.
            if (!hasUnsavedEdits() && afterSaved != null) {
                afterSaved.run();
            }
            return;
        }

        // Read after reconciling, not before: a Compare can leave different text in the
        // buffer, and that text is what the user just agreed to write.
        String text = buffer.text();
        // The handler gets the document's own line endings; the baseline keeps the
        // buffer's. Deliberately two different strings: the baseline is compared against
        // the buffer, and converting one without the other would make every CRLF file
        // read as permanently dirty.
        String document = withDocumentEndings(text);
        long savedBy = generation;
        savingStarted();
        buffer.refresh();
        handler.save(path, document, result -> {
            if (savedBy != generation) {
                // Landed after its session ended - discarded, or left for other content -
                // and perhaps after another began. Nothing here is that write's any more:
                // not the baseline, not the draft, not the save state, and certainly not
                // the navigation or the Done it was holding, which would end whichever
                // session is open now.
                return;
            }
            boolean ok = result != null && result.isOk();
            if (ok) {
                // The baseline, the file's line endings and "is it being created" all move
                // to what was just written; see saveSucceeded.
                saveSucceeded(text);
                // And what the file is drawn from once the session ends, which is what
                // lets Done keep what is on screen.
                buffer.saved(document);
                // The buffer exists somewhere better than a draft now - unless the user
                // kept typing while the write was on the git lane, in which case the
                // newer text is the only copy of itself and has to be parked, not
                // dropped. Deliberately not done on failure: that is the case drafts are for.
                if (buffer.text().equals(text)) {
                    discardSessionDraft();
                } else {
                    writeDraft();
                }
            } else {
                saveFailed(result == null ? null : result.getMessage());
            }
            buffer.refresh();
            if (ok && afterSaved != null) {
                afterSaved.run();
            }
        });
    }

    /**
     * Writes the revision on screen over the working copy, after saying so - no session
     * needed.
     *
     * <p>The operation the Edit button used to perform by accident, offered deliberately
     * and named for what it does. What was on screen when it was asked for is what is
     * restored, whatever is showing by the time a rename lookup answers.
     *
     * @param content the revision's text, as shown
     */
    void restoreVersion(String path, String content) {
        if (saveHandler == null || path == null || path.isEmpty() || content == null) {
            return;
        }
        RenameLookup lookup = renameLookup;
        if (lookup == null) {
            confirmRestore(path, null, content);
            return;
        }
        // Asked before the question, because the question depends on it.
        lookup.currentNameOf(path, renamedTo -> confirmRestore(path, renamedTo, content));
    }

    /**
     * The confirmation, then the write.
     *
     * @param renamedTo what the file is called now, when that is not {@code path}: the
     *                  question becomes which of the two names to write
     */
    private void confirmRestore(String path, String renamedTo, String content) {
        String into;
        if (renamedTo == null || renamedTo.equals(path)) {
            if (!prompter.confirmRestore(path)) {
                return;
            }
            into = path;
        } else {
            Optional<String> chosen = prompter.chooseRestoreDestination(path, renamedTo);
            if (chosen.isEmpty()) {
                return;
            }
            into = chosen.get();
        }
        SaveHandler handler = saveHandler;
        if (handler != null) {
            handler.save(into, content, result -> { });
        }
    }

    // ------------------------------------------------------------------
    // Ending
    // ------------------------------------------------------------------

    /**
     * Ends the session, keeping what is on screen.
     *
     * <p>Distinct from both Save (which writes and carries on) and Discard (which throws
     * the buffer away). A session with unsaved work refuses to end quietly; one with none
     * has the buffer on disk, as editing began or as the last save wrote it, and that is
     * what {@link Buffer#restoreDisplay} draws.
     */
    void finish() {
        if (!open) {
            return;
        }
        if (!hasUnsavedEdits()) {
            end();
            buffer.restoreDisplay();
            return;
        }
        Prompter.SaveFirst choice = prompter.askToSave(buffer.path(),
                Prompter.Ending.FINISHING, lastFailure());
        if (choice == Prompter.SaveFirst.CANCEL) {
            return;
        }
        if (choice == Prompter.SaveFirst.DISCARD) {
            cancel();
            return;
        }
        // Only leaves once the write is confirmed; a failed save keeps the session.
        requestSave(saveHandler, () -> {
            end();
            buffer.restoreDisplay();
        }, false);
    }

    /**
     * Asks before throwing typed work away.
     *
     * <p>{@link #cancel()} itself stays unconditional - the host calls it when the content
     * underneath stops being editable, and there is nothing to ask about there - so the
     * confirmation lives with the button instead.
     *
     * <p>Not while a write is in flight: the edits are on their way to disk, and throwing
     * them away here would not stop them landing - it would only end the session they
     * land in. The host greys the button out meanwhile.
     */
    void confirmDiscard() {
        if (open && isSaving()) {
            return;
        }
        if (!open || !hasUnsavedEdits()) {
            cancel();
            return;
        }
        if (prompter.confirmDiscard(buffer.path(), lastFailure())) {
            cancel();
        }
    }

    /** Leaves the session and puts back what was on screen before. */
    void cancel() {
        if (!open) {
            return;
        }
        // Asked for and thrown away, so there is nothing left to recover.
        discardSessionDraft();
        end();
        buffer.restoreDisplay();
    }

    /**
     * Ends the session for a closing tab, leaving no draft behind.
     *
     * <p>Called once the close has been decided - saved, or deliberately discarded. It
     * matters twice over: the draft of work that was just saved or thrown away would
     * otherwise come back as a "recovered draft" next time, and with the session still
     * open the draft debounce could park the buffer again after the tab had gone.
     */
    void endForClose() {
        if (!open) {
            return;
        }
        discardSessionDraft();
        end();
    }

    /**
     * Called when something is about to replace what is on screen - a different file,
     * a switch to Diff view, a refresh.
     *
     * <p>Offers to save first. Selecting another file in the list is an easy thing to do
     * by accident, and silently throwing away typed changes is not acceptable behaviour
     * for something calling itself an editor.
     *
     * <p>Three answers, not two. Save used to hand the text to the host and let the
     * navigation proceed in the same breath, which is fine right up until the write
     * fails - and then the buffer is gone and the file on disk is unchanged. So Save
     * defers the navigation until the write is confirmed, and Cancel refuses it outright.
     *
     * @param proceed what the caller was about to do, replayed if and when the buffer is
     *                safe
     * @return true to carry on now; false when the caller must stop, because the user
     *         either cancelled or is waiting on a save
     */
    boolean leaveForNewContent(Runnable proceed) {
        if (!open) {
            return true;
        }
        String path = buffer.path();
        if (!hasUnsavedEdits() || saveHandler == null || path.isEmpty()) {
            end();
            return true;
        }

        Prompter.SaveFirst choice = prompter.askToSave(path, Prompter.Ending.REPLACING, lastFailure());
        if (choice == Prompter.SaveFirst.CANCEL) {
            return false;
        }
        if (choice == Prompter.SaveFirst.DISCARD) {
            // Chosen, not merely allowed: end() only drops a draft when the buffer is
            // clean, and this one is by definition dirty.
            discardSessionDraft();
            end();
            return true;
        }
        // Replayed rather than continued: by the time the write lands the caller's stack
        // is long gone, so the navigation has to be something that can be run again.
        deferUntilSaved(proceed);
        requestSave(saveHandler, () -> {
            Runnable deferred = takeDeferred();
            end();
            if (deferred != null) deferred.run();
        }, false);
        return false;
    }

    private void end() {
        if (!hasUnsavedEdits()) {
            // Reached with a clean buffer, so a parked copy would only be noise later.
            discardSessionDraft();
        }
        // The session is over, so the state that described it goes with it. Callers reach
        // here only once the buffer is safe - saved, discarded, or never dirty.
        close();
        buffer.setEditing(false);
    }

    /** Why the last save failed, for the unsaved-work questions; {@code null} when it did not. */
    private String lastFailure() {
        return lastSaveFailed() ? saveFailure : null;
    }

    // ------------------------------------------------------------------
    // Unsaved work that outlives the process
    // ------------------------------------------------------------------

    /** Parks the buffer, if there is a store and something worth parking. */
    void writeDraft() {
        if (drafts == null || !hasUnsavedEdits()) {
            return;
        }
        drafts.save(buffer.path(), buffer.text(), baseline, buffer.encoding().label());
        ownsDraft = true;
    }

    /**
     * Offers a parked buffer back, if there is one worth offering - called whenever new
     * content is on screen.
     *
     * <p>Per file rather than per repository, and not a modal. Told about three
     * unrecovered drafts on opening a repository, at the moment they wanted the graph,
     * nobody reads the list; shown one when they open the file it belongs to, the choice
     * is concrete. It also has to be ignorable, which a modal is not.
     */
    void offerDraft() {
        withdrawDraftOffer();
        String path = buffer.path();
        // Only over the working copy. A draft is unsaved work on the file as it stands, and
        // offered over a commit or a stash its Compare would set it against that revision
        // under the heading "On disk now" - and saving from there would put historical
        // text over the working copy.
        if (drafts == null || open || !buffer.showsWorkingCopy() || path.isEmpty()) {
            return;
        }
        EditorDrafts.Draft draft = drafts.find(path).orElse(null);
        if (draft == null) {
            return;
        }
        // A draft matching what is on disk is stale - the work reached the file by some
        // other route - so it is dropped rather than offered. Compared in buffer form: the
        // draft is the LF-only buffer, the file may well be CRLF, and comparing raw text
        // offered every saved CRLF file back as a "recovered draft".
        //
        // Not for a file too large to compare: this runs every time the file is shown, on
        // the FX thread. Whether that draft is stale is left to the user, who is offered it.
        String current = tooLargeToCompare(path) ? null : workingCopyText(buffer.encoding());
        if (current != null && asBuffer(current).equals(asBuffer(draft.text()))) {
            drafts.discard(path);
            return;
        }
        pendingDraft = draft;
        prompter.offerDraft(draft.savedAt());
    }

    /** Takes the recovery offer down: the content it was about is no longer on screen. */
    void withdrawDraftOffer() {
        pendingDraft = null;
        prompter.withdrawDraftOffer();
    }

    /**
     * Puts the offered draft back in the editor as unsaved work.
     *
     * <p>The baseline comes from what is on disk <em>now</em>, not from what the draft
     * remembers: that is what makes the session honestly dirty, and what lets the
     * overwrite check compare against reality rather than against history.
     */
    void restoreDraft() {
        EditorDrafts.Draft draft = pendingDraft;
        withdrawDraftOffer();
        if (draft == null) {
            return;
        }
        // Before the session opens, so the baseline it takes and the save it ends in are
        // both under the encoding the draft was written with.
        adoptEncoding(recordedEncoding(draft));
        beginWith(draft.text());
    }

    /** Shows the offered draft beside the file, the same reconciliation a changed file offers. */
    void compareDraft() {
        EditorDrafts.Draft draft = pendingDraft;
        if (draft == null) {
            return;
        }
        // The disk side is decoded the way the draft was, so the two are compared like for
        // like. The selector itself only changes if the user goes on to use the result:
        // looking should not have a side effect.
        TextEncoding recorded = recordedEncoding(draft);
        String current = workingCopyText(recorded != null ? recorded : buffer.encoding());
        String disk = current == null ? "" : current;
        prompter.compareDraft(buffer.path(), draft.text(), disk).ifPresent(text -> {
            withdrawDraftOffer();
            adoptEncoding(recorded);
            beginWith(text);
        });
    }

    /** Forgets the offered draft, as the offer's own Discard does. */
    void dismissDraft() {
        discardDraft();
        withdrawDraftOffer();
    }

    /** Opens a session and puts {@code text} in it, as the draft that is now its own. */
    private void beginWith(String text) {
        begin();
        if (!open) {
            return;
        }
        buffer.replace(text);
        // The stored draft is this session's content now, so the session's own cleanup -
        // a save, a discard - is what should remove it.
        ownsDraft = true;
        buffer.refresh();
    }

    /** Forgets the stored draft if this session is the one that parked it. */
    private void discardSessionDraft() {
        if (ownsDraft) {
            discardDraft();
            ownsDraft = false;
        }
    }

    /** Forgets the parked buffer: it exists somewhere better now, or not at all. */
    private void discardDraft() {
        String path = buffer.path();
        if (drafts != null && !path.isEmpty()) {
            drafts.discard(path);
        }
    }

    /**
     * Switches the host to {@code recorded}. Only ever outside a session: re-decoding is
     * exactly right before one opens and would replace the user's buffer during one.
     */
    private void adoptEncoding(TextEncoding recorded) {
        if (recorded != null && recorded != buffer.encoding() && !open) {
            buffer.adoptEncoding(recorded);
        }
    }

    /**
     * The encoding a draft was written under, if this machine can still use it.
     *
     * <p>A draft holds decoded text, so which encoding decoded it is part of what it
     * means. Restored under whatever the selector happens to say - Guess, on a fresh open -
     * a file the user had deliberately switched to, say, a BOM-less UTF-16 would be saved
     * back as UTF-8 and every byte of it would change.
     */
    private static TextEncoding recordedEncoding(EditorDrafts.Draft draft) {
        if (draft.encoding() == null || draft.encoding().isBlank()) {
            return null;
        }
        return TextEncoding.selectable().stream()
                .filter(candidate -> candidate.label().equals(draft.encoding()))
                .findFirst()
                .orElse(null);
    }

    /** The working copy as it stands on disk, decoded as {@code as}, or {@code null} when unreadable. */
    private String workingCopyText(TextEncoding as) {
        String path = buffer.path();
        if (!workingCopy.readable() || path.isEmpty() || !workingCopy.exists(path)) {
            return null;
        }
        byte[] bytes = workingCopy.read(path);
        return bytes == null ? null : as.decode(bytes);
    }

    // ------------------------------------------------------------------
    // What happened to the file underneath the editor
    // ------------------------------------------------------------------

    /** Where the file on disk stands relative to what this session started from. */
    private enum DiskState {
        /** Still what the editor thinks it is. */
        UNCHANGED,
        /** Someone else wrote to it. */
        CHANGED,
        /** Someone else deleted it. */
        VANISHED,
        /** No way to tell - no working tree, unreadable, or too large to be worth reading. */
        UNREADABLE
    }

    /**
     * Above this, skip the comparison rather than stall the save.
     *
     * <p>Reading an editor-sized file to compare it is sub-millisecond. Reading something
     * this size on the FX thread, on every Ctrl+S and every return to the window, would be
     * visible - and nothing this size is being hand-edited anyway.
     */
    private static final int MAX_EXTERNAL_CHECK_BYTES = 8 * 1024 * 1024;

    /** Whether {@code path} is known, without reading it, to be past the comparison's cap. */
    private boolean tooLargeToCompare(String path) {
        return workingCopy.size(path) > MAX_EXTERNAL_CHECK_BYTES;
    }

    private DiskState diskStateNow(String path) {
        if (!workingCopy.readable() || path.isEmpty()) {
            return DiskState.UNREADABLE;
        }
        if (createdFromMissingFile) {
            return DiskState.UNCHANGED;
        }
        if (!workingCopy.exists(path)) {
            return DiskState.VANISHED;
        }
        // The size first: the cap is there to keep a file this large from being read on
        // the FX thread at all, and checking the bytes once read did not spare the read.
        if (tooLargeToCompare(path)) {
            return DiskState.UNREADABLE;
        }
        byte[] onDisk = workingCopy.read(path);
        // Checked again for a host that cannot tell the size without reading.
        if (onDisk == null || onDisk.length > MAX_EXTERNAL_CHECK_BYTES) {
            return DiskState.UNREADABLE;
        }
        // Decoded against decoded, deliberately. Comparing bytes would report a change
        // whenever encode(text, original) round-trips differently from decode - a BOM, an
        // inherited charset - and a spurious prompt in the save path is worse than no
        // prompt at all.
        //
        // Then normalised, because the buffer cannot hold a CR: a CRLF file read off disk
        // never equals the buffer it was loaded into. Comparing the raw decodings reported
        // every CRLF file in the repository as changed by someone else, on every save.
        String diskText = buffer.encoding().decode(onDisk);
        if (!asBuffer(diskText).equals(asBuffer(baseline))) {
            return DiskState.CHANGED;
        }
        // Same lines, but something may have renormalised the terminators underneath -
        // dos2unix, or a checkout after an autocrlf change. asBuffer cannot see that, and
        // the next save would quietly put every line back the way the editor found it.
        if (endings != null && diskText.indexOf('\n') >= 0
                && LineEndings.detect(diskText) != endings) {
            return DiskState.CHANGED;
        }
        return DiskState.UNCHANGED;
    }

    /**
     * {@code text} as the editing buffer would hold it.
     *
     * <p>Anything comparing a buffer against a file has to meet it here. Line endings are
     * invisible in this form by design, which is why {@link #diskStateNow} compares the
     * file's convention separately: a save restores the convention the editor opened
     * with, so an external renormalisation would otherwise be silently undone by the next
     * Ctrl+S.
     */
    static String asBuffer(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * Whether the file moved under this session.
     *
     * <p>A pure question with no dialog attached, so the host can ask it while deciding
     * whether to close a tab or light a warning.
     */
    boolean workingCopyChangedSinceEditingBegan() {
        if (!open) {
            return false;
        }
        DiskState state = diskStateNow(buffer.path());
        return state == DiskState.CHANGED || state == DiskState.VANISHED;
    }

    /**
     * Lights the "changed on disk" notice.
     *
     * <p>Deliberately not a question. This is reached when the window regains focus, and
     * a modal that appears because someone alt-tabbed back is hostile - the point is to
     * warn before the next Ctrl+S, not to interrupt at an arbitrary moment.
     */
    void noteExternalChange() {
        if (!open || externalChangePending) {
            return;
        }
        externalChangePending = true;
        buffer.refresh();
    }

    /** Puts the notice out again, once the divergence is resolved one way or the other. */
    void clearExternalChangeNotice() {
        if (!externalChangePending) {
            return;
        }
        externalChangePending = false;
        buffer.refresh();
    }

    /**
     * Asks what to do about a file that changed on disk, and does it.
     *
     * @return whether the save should go ahead now
     */
    private boolean reconcileExternalChange(String path) {
        DiskState state = diskStateNow(path);
        if (state != DiskState.CHANGED && state != DiskState.VANISHED) {
            clearExternalChangeNotice();
            return true;
        }

        if (state == DiskState.VANISHED) {
            if (!prompter.askToRecreate(path)) {
                markDirty();
                buffer.refresh();
                return false;
            }
            clearExternalChangeNotice();
            return true;
        }

        switch (prompter.askAboutChange(path)) {
            case COMPARE -> {
                return compareWithDisk(path);
            }
            case OVERWRITE -> {
                clearExternalChangeNotice();
                return true;
            }
            case RELOAD -> {
                reloadFromDisk();
                return false;
            }
            default -> {
                markDirty();
                buffer.refresh();
                return false;
            }
        }
    }

    /**
     * Opens the buffer and the file side by side so the user can build one result.
     *
     * <p>Genuinely three-way: the baseline is what both sides diverged from.
     *
     * @return whether the save should go ahead with whatever the comparison produced
     */
    private boolean compareWithDisk(String path) {
        byte[] onDisk = workingCopy.read(path);
        String diskText = onDisk == null ? "" : buffer.encoding().decode(onDisk);
        String mine = buffer.text();

        Optional<String> reconciled = prompter.reconcile(path, mine, diskText, baseline);
        if (reconciled.isEmpty()) {
            markDirty();
            buffer.refresh();
            return false;
        }
        // The reconciliation was made against the file as it is now, so that is the
        // version this save is replacing.
        buffer.replace(reconciled.get());
        // As reloadFromDisk does: the baseline in the buffer's own form, CRs folded, or a
        // CRLF file reads as dirty for good - and the terminators the file has now, or a
        // save straight after rewrites every line back to the ones something outside just
        // changed them from.
        rebase(asBuffer(diskText));
        captureEndings(diskText);
        clearExternalChangeNotice();
        return true;
    }

    /** Throws the buffer away and takes what is on disk instead. */
    void reloadFromDisk() {
        byte[] onDisk = workingCopy.read(buffer.path());
        String fresh = onDisk == null ? "" : buffer.encoding().decode(onDisk);
        buffer.replace(fresh);
        // The baseline is taken back out of the buffer rather than from `fresh`: the buffer
        // drops CRs, so for a CRLF file the two differ and the session would read as dirty
        // the instant the user asked to throw their edits away. And the file's convention
        // is taken again, because whatever rewrote it may have changed that.
        rebase(buffer.text());
        captureEndings(fresh);
        discardSessionDraft();
        markClean();
        clearExternalChangeNotice();
        buffer.refresh();
    }

    // ------------------------------------------------------------------
    // The rules underneath the flows
    // ------------------------------------------------------------------

    String baseline() {
        return baseline;
    }

    /** Takes {@code text} as what is on disk now: after a reconcile or a reload. */
    private void rebase(String text) {
        baseline = text;
    }

    boolean isDirty(String bufferText) {
        return !bufferText.equals(baseline);
    }

    /**
     * Follows typing: edited since the last confirmed write is DIRTY, typed back to it is
     * CLEAN. SAVING and FAILED are left alone - only the write's own outcome ends those.
     */
    void follow(boolean dirty) {
        if (dirty && saveState == SaveState.CLEAN) {
            saveState = SaveState.DIRTY;
        } else if (!dirty && saveState == SaveState.DIRTY) {
            saveState = SaveState.CLEAN;
        }
    }

    SaveState saveState() {
        return saveState;
    }

    String saveFailure() {
        return saveFailure;
    }

    boolean isSaving() {
        return saveState == SaveState.SAVING;
    }

    boolean lastSaveFailed() {
        return saveState == SaveState.FAILED;
    }

    void savingStarted() {
        saveState = SaveState.SAVING;
    }

    /** Back to edited-but-unsaved: a save was abandoned. */
    private void markDirty() {
        saveState = SaveState.DIRTY;
    }

    /** The buffer matches the disk again, by a reload rather than a write. */
    private void markClean() {
        saveState = SaveState.CLEAN;
        saveFailure = "";
    }

    /**
     * {@code savedBufferText} reached the disk.
     *
     * <p>What was written is now the file's convention. Without re-reading it a MIXED
     * file - saved uniform in its majority - or a one-line file that gained lines would
     * read as renormalised by someone else on the very next save. And the file exists now,
     * whatever state it began in.
     */
    void saveSucceeded(String savedBufferText) {
        baseline = savedBufferText;
        captureEndings(withDocumentEndings(savedBufferText));
        saveState = SaveState.CLEAN;
        saveFailure = "";
        createdFromMissingFile = false;
    }

    void saveFailed(String reason) {
        saveState = SaveState.FAILED;
        saveFailure = reason == null || reason.isBlank() ? "the write did not complete" : reason;
    }

    void deferUntilSaved(Runnable navigation) {
        pendingAfterSave = navigation;
    }

    /** The deferred navigation, handed over once and forgotten. */
    Runnable takeDeferred() {
        Runnable deferred = pendingAfterSave;
        pendingAfterSave = null;
        return deferred;
    }

    /**
     * Records the file's line-ending convention from text that still has its CRs: when a
     * session opens the file, when it reloads it, and after a save - the only moments the
     * real terminators are visible. The buffer never has them.
     */
    private void captureEndings(String document) {
        if (document == null) {
            endings = null;
            dominantEnding = null;
            endingCounts = null;
            return;
        }
        endings = LineEndings.detect(document);
        dominantEnding = LineEndings.dominant(document);
        endingCounts = LineEndings.count(document);
    }

    /**
     * {@code bufferText} with the document's terminators put back.
     *
     * <p>The buffer is guaranteed free of carriage returns, so a plain replacement cannot
     * double one up.
     */
    String withDocumentEndings(String bufferText) {
        if (endings == null || bufferText == null) {
            return bufferText;
        }
        LineEndings target = endings == LineEndings.MIXED ? dominantEnding : endings;
        return LineEndings.convert(bufferText, target);
    }

    LineEndings endings() {
        return endings;
    }

    LineEndings dominantEnding() {
        return dominantEnding;
    }

    LineEndings.Counts endingCounts() {
        return endingCounts;
    }

    boolean externalChangePending() {
        return externalChangePending;
    }

    boolean createdFromMissingFile() {
        return createdFromMissingFile;
    }

    boolean ownsDraft() {
        return ownsDraft;
    }
}
