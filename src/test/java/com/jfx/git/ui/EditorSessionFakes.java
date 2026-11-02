package com.jfx.git.ui;

import com.jfx.git.core.EditorDrafts;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.TextEncoding;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Plain stand-ins for everything an {@link EditorSession} talks to - the buffer, the
 * prompter, the working tree, the draft store and the save handler - so its flows run
 * without a window. Every question the prompter is asked is recorded, which is what lets a
 * test say "nothing was asked": a real modal in a headless test does not fail, it hangs.
 */
final class EditorSessionFakes {

    private EditorSessionFakes() {
    }

    /** A session over fakes, for tests of the state rules alone. */
    static EditorSession session() {
        return new Rig().session;
    }

    /** What the text area does to text on its way in: paragraphs hold no terminators. */
    static String asTyped(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** The text area and the file on it. */
    static final class FakeBuffer implements EditorSession.Buffer {
        String path = "";
        TextEncoding encoding = TextEncoding.UTF_8;
        /** Whether what is on screen is the working tree rather than a revision. */
        boolean showsWorkingCopy = true;
        /** The file as shown, line endings and all; {@code null} when there is no source. */
        String shown = "";
        /** False while the whole file is still on its way. */
        boolean wholeFileAvailable = true;
        String text = "";
        boolean editing;
        int refreshes;
        int restores;
        /** What the session drew in place of the revision, if anything. */
        byte[] workingCopyDrawn;
        TextEncoding adopted;
        final List<String> shownPaths = new ArrayList<>();

        @Override
        public String path() {
            return path;
        }

        @Override
        public TextEncoding encoding() {
            return encoding;
        }

        @Override
        public boolean showsWorkingCopy() {
            return showsWorkingCopy;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public void replace(String text) {
            this.text = asTyped(text);
        }

        @Override
        public boolean showWholeFile() {
            return wholeFileAvailable;
        }

        @Override
        public String showForEditing(byte[] workingCopy) {
            if (workingCopy != null) {
                workingCopyDrawn = workingCopy;
                shown = encoding.decode(workingCopy);
                showsWorkingCopy = true;
            }
            text = asTyped(shown);
            return shown;
        }

        @Override
        public void showWorkingCopyOf(String path, byte[] content) {
            this.path = path;
            shownPaths.add(path);
            shown = encoding.decode(content);
            text = asTyped(shown);
            showsWorkingCopy = true;
        }

        @Override
        public void adoptEncoding(TextEncoding encoding) {
            this.encoding = encoding;
            this.adopted = encoding;
        }

        @Override
        public void setEditing(boolean editing) {
            this.editing = editing;
        }

        @Override
        public void restoreDisplay() {
            restores++;
            text = asTyped(shown);
        }

        @Override
        public void saved(String document) {
            shown = document;
            showsWorkingCopy = true;
        }

        @Override
        public void refresh() {
            refreshes++;
        }
    }

    /** Answers as told, and remembers every question. */
    static final class FakePrompter implements EditorSession.Prompter {
        final List<String> asked = new ArrayList<>();
        SaveFirst saveFirst = SaveFirst.CANCEL;
        boolean discard;
        boolean recreate;
        ChangedOnDisk change = ChangedOnDisk.CANCEL;
        Optional<String> reconciled = Optional.empty();
        Optional<String> draftChoice = Optional.empty();
        boolean restore;
        Optional<String> destination = Optional.empty();
        /** The last failure an unsaved-work question was told about. */
        String failureShown;
        /** What the reconcile comparison was given: mine, on disk, baseline. */
        List<String> compared = List.of();
        /** Whether the draft offer is up, and when its draft was parked. */
        boolean offerUp;
        Instant offeredDraftTime;

        @Override
        public SaveFirst askToSave(String path, Ending why, String lastFailure) {
            asked.add("save first? " + why);
            failureShown = lastFailure;
            return saveFirst;
        }

        @Override
        public boolean confirmDiscard(String path, String lastFailure) {
            asked.add("discard?");
            failureShown = lastFailure;
            return discard;
        }

        @Override
        public boolean askToRecreate(String path) {
            asked.add("recreate?");
            return recreate;
        }

        @Override
        public ChangedOnDisk askAboutChange(String path) {
            asked.add("changed on disk?");
            return change;
        }

        @Override
        public Optional<String> reconcile(String path, String mine, String onDisk, String baseline) {
            asked.add("reconcile");
            compared = List.of(mine, onDisk, baseline);
            return reconciled;
        }

        @Override
        public void offerDraft(Instant savedAt) {
            offerUp = true;
            offeredDraftTime = savedAt;
        }

        @Override
        public void withdrawDraftOffer() {
            offerUp = false;
        }

        @Override
        public Optional<String> compareDraft(String path, String draft, String onDisk) {
            asked.add("compare draft");
            compared = List.of(draft, onDisk);
            return draftChoice;
        }

        @Override
        public boolean confirmRestore(String path) {
            asked.add("restore?");
            return restore;
        }

        @Override
        public Optional<String> chooseRestoreDestination(String path, String renamedTo) {
            asked.add("restore where? " + path + " or " + renamedTo);
            return destination;
        }
    }

    /** A working tree held in a map. */
    static final class FakeDisk implements EditorSession.WorkingCopy {
        final Map<String, byte[]> files = new HashMap<>();
        boolean readable = true;
        /** Every path whose content was read, in order: a read is the cost being watched. */
        final List<String> reads = new ArrayList<>();

        void put(String path, String content) {
            files.put(path, content.getBytes(StandardCharsets.UTF_8));
        }

        String text(String path) {
            byte[] bytes = files.get(path);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public boolean readable() {
            return readable;
        }

        @Override
        public boolean exists(String path) {
            return files.containsKey(path);
        }

        @Override
        public byte[] read(String path) {
            reads.add(path);
            return readable ? files.get(path) : null;
        }

        /** A stat, as the real one is: not a read. */
        @Override
        public long size(String path) {
            byte[] bytes = readable ? files.get(path) : null;
            return bytes == null ? -1 : bytes.length;
        }
    }

    /** Drafts in a map, as {@code EditorDraftRecoveryTest}'s store keeps them. */
    static final class FakeDrafts implements EditorSession.DraftStore {
        final Map<String, EditorDrafts.Draft> drafts = new HashMap<>();
        int saves;

        void park(String path, String text, String encoding) {
            drafts.put(path, new EditorDrafts.Draft("/repo", path, text, Instant.EPOCH, "baseline", encoding));
        }

        @Override
        public void save(String path, String text, String baseline, String encoding) {
            saves++;
            drafts.put(path, new EditorDrafts.Draft("/repo", path, text, Instant.now(), "baseline", encoding));
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

    /**
     * Writes to the fake disk and says so - or fails, or holds the answer back the way a
     * busy git lane does.
     */
    static final class FakeSaver implements EditorSession.SaveHandler {
        enum Mode { SUCCEED, FAIL, HOLD }

        private final FakeDisk disk;
        Mode mode = Mode.SUCCEED;
        String failure = "the file is read-only";
        /** Every write handed over, as {@code path=text}. */
        final List<String> writes = new ArrayList<>();
        final List<Consumer<GitResult>> held = new ArrayList<>();

        FakeSaver(FakeDisk disk) {
            this.disk = disk;
        }

        @Override
        public void save(String path, String text, Consumer<GitResult> onResult) {
            writes.add(path + "=" + text);
            switch (mode) {
                case SUCCEED -> {
                    disk.put(path, text);
                    onResult.accept(GitResult.ok("saved"));
                }
                case FAIL -> onResult.accept(GitResult.failed(failure));
                case HOLD -> held.add(onResult);
            }
        }
    }

    /** A session wired to one of each fake. */
    static final class Rig {
        final FakeBuffer buffer = new FakeBuffer();
        final FakePrompter prompter = new FakePrompter();
        final FakeDisk disk = new FakeDisk();
        final FakeDrafts drafts = new FakeDrafts();
        final FakeSaver saver = new FakeSaver(disk);
        final EditorSession session = new EditorSession(buffer, prompter, disk);

        Rig() {
            session.setSaveHandler(saver);
            session.setDraftStore(drafts);
        }

        /** {@code path} in the working tree, and its working copy on screen. */
        Rig showing(String path, String content) {
            disk.put(path, content);
            buffer.path = path;
            buffer.shown = content;
            buffer.text = asTyped(content);
            buffer.showsWorkingCopy = true;
            return this;
        }

        /** {@code path} as some revision had it, on screen in place of the working copy. */
        Rig showingRevision(String path, String content) {
            buffer.path = path;
            buffer.shown = content;
            buffer.text = asTyped(content);
            buffer.showsWorkingCopy = false;
            return this;
        }

        /** The same, with a session open on it. */
        Rig editing(String path, String content) {
            showing(path, content);
            session.begin();
            if (!session.isOpen()) {
                throw new AssertionError("precondition: the session opened");
            }
            return this;
        }

        /** What the user typing does to the buffer. */
        void type(String text) {
            buffer.text = text;
        }
    }
}
