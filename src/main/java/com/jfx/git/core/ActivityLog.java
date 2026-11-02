package com.jfx.git.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the application did, when, and how long it took.
 *
 * <h2>Why this exists</h2>
 * The status bar is a <em>current</em>-state display: it shows one message, the next
 * operation replaces it, and by the time something looks wrong the sentence explaining
 * it is gone. That is the right shape for a status bar and the wrong shape for the two
 * questions users actually ask after a surprise - "what did it just do to my
 * repository?" and "why did that take so long?". Neither is answerable from a display
 * that keeps only the last line.
 *
 * <p>So every operation that raises the status bar's progress indicator also lands here,
 * with a start time and, once it finishes, a duration. Nothing new has to be instrumented
 * at the call sites: {@code StatusBar.busy} and {@code StatusBar.idle} already bracket
 * every one of them, which is exactly the pair a duration needs.
 *
 * <h2>Two scopes</h2>
 * {@link Scope#REPOSITORY} entries name the repository they happened in, so a session
 * with six tabs open does not become one interleaved stream. {@link Scope#APPLICATION}
 * entries are the things that belong to no repository - startup, opening and closing
 * repositories, switching engines.
 *
 * <h2>Memory</h2>
 * Bounded to {@link #CAPACITY} entries, oldest discarded first. A log that grows without
 * limit turns a long-running session into a leak, and nobody scrolls back past a few
 * hundred lines anyway. Nothing is written to disk: this is a diagnostic aid for the
 * session in front of you, and a log file is a thing to manage, rotate, and accidentally
 * ship a repository path in.
 *
 * <p>Every method is safe to call from any thread.
 */
public final class ActivityLog {

    /** Where an entry belongs; see the class javadoc. */
    public enum Scope { APPLICATION, REPOSITORY }

    /** How an operation ended, or that it has not. */
    public enum Outcome { RUNNING, OK, WARNING, FAILED }

    /** How many entries are kept before the oldest start being discarded. */
    public static final int CAPACITY = 2000;

    /**
     * One operation, or one thing worth recording that was not an operation.
     *
     * <p>Mutable on purpose: an entry is created when work starts, and the two facts
     * worth knowing about it - how long it took and whether it worked - are only
     * available later. Creating a second entry at that point would double every line in
     * the log and lose the association between them.
     */
    public static final class Entry {
        private final Instant startedAt;
        private final Scope scope;
        private final String repository;
        private final String label;
        private volatile Outcome outcome;
        /** Milliseconds the work took, or -1 while it runs and for entries that are not work. */
        private volatile long millis = -1;
        private volatile String detail;
        /** Whether the work has ended; a note is created already ended. */
        private volatile boolean finished;

        private Entry(Scope scope, String repository, String label, Outcome outcome) {
            this.startedAt = Instant.now();
            this.scope = scope;
            this.repository = repository;
            this.label = label;
            this.outcome = outcome;
        }

        public Instant startedAt() { return startedAt; }
        public Scope scope() { return scope; }
        /** The repository this happened in, or null for {@link Scope#APPLICATION}. */
        public String repository() { return repository; }
        public String label() { return label; }
        public Outcome outcome() { return outcome; }
        /** Milliseconds taken, or -1 when this entry does not describe timed work. */
        public long millis() { return millis; }
        /** What the operation reported when it ended, or null. */
        public String detail() { return detail; }
        public boolean isFinished() { return finished; }
        /** Whether this entry describes work that started and has not ended. */
        public boolean isRunning() { return !finished; }
        /** Whether a duration is known - false for notes and for work still in flight. */
        public boolean isTimed() { return millis >= 0; }
    }

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private ActivityLog() {
    }

    /**
     * Records that {@code label} has started, and hands back the entry to finish.
     *
     * <p>An entry that is never finished stays in the log reading "started" with no
     * duration, which is the honest rendering of an operation that never came back.
     */
    public static Entry begin(Scope scope, String repository, String label) {
        return add(new Entry(scope, repository, clean(label), Outcome.RUNNING));
    }

    /**
     * Records that {@code entry} ended, with how long it took.
     *
     * <p>Null-tolerant and idempotent: callers pair this with {@link #begin} across an
     * asynchronous boundary, where a failure path can report twice and a cancelled
     * operation may hold no entry at all.
     */
    public static void finish(Entry entry, Outcome outcome, String detail) {
        if (entry == null || entry.finished) return;
        entry.millis = Math.max(0,
                java.time.Duration.between(entry.startedAt, Instant.now()).toMillis());
        entry.outcome = outcome == null ? Outcome.OK : outcome;
        entry.detail = clean(detail);
        entry.finished = true;
        notifyListeners();
    }

    /**
     * Revises how an already-finished operation is judged, without disturbing its timing.
     *
     * <p>The status bar learns the duration and the verdict at two different moments -
     * {@code idle()} and then {@code report(result)} - so an entry has to be finishable
     * before anyone knows whether it worked.
     */
    public static void reviseOutcome(Entry entry, Outcome outcome, String detail) {
        if (entry == null || outcome == null) return;
        entry.outcome = outcome;
        entry.detail = clean(detail);
        notifyListeners();
    }

    /** Records something that happened at a point in time rather than over one. */
    public static Entry note(Scope scope, String repository, String label, Outcome outcome) {
        Entry entry = new Entry(scope, repository, clean(label),
                outcome == null ? Outcome.OK : outcome);
        entry.finished = true;
        return add(entry);
    }

    /** An application-scope note: the common case for events belonging to no repository. */
    public static Entry note(String label) {
        return note(Scope.APPLICATION, null, label, Outcome.OK);
    }

    private static Entry add(Entry entry) {
        synchronized (ENTRIES) {
            if (ENTRIES.size() >= CAPACITY) {
                ENTRIES.removeFirst();
            }
            ENTRIES.addLast(entry);
        }
        notifyListeners();
        return entry;
    }

    /** Every entry, oldest first. */
    public static List<Entry> entries() {
        synchronized (ENTRIES) {
            return List.copyOf(ENTRIES);
        }
    }

    /**
     * The entries in one scope, oldest first.
     *
     * @param repository for {@link Scope#REPOSITORY}, keeps only entries from this
     *                   repository; null keeps them all
     */
    public static List<Entry> entries(Scope scope, String repository) {
        List<Entry> out = new ArrayList<>();
        synchronized (ENTRIES) {
            for (Entry entry : ENTRIES) {
                if (entry.scope != scope) continue;
                if (repository != null && !repository.equals(entry.repository)) continue;
                out.add(entry);
            }
        }
        return out;
    }

    /** Every repository named by at least one entry, in the order first seen. */
    public static List<String> repositories() {
        List<String> out = new ArrayList<>();
        synchronized (ENTRIES) {
            for (Entry entry : ENTRIES) {
                if (entry.repository != null && !out.contains(entry.repository)) {
                    out.add(entry.repository);
                }
            }
        }
        return out;
    }

    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
        notifyListeners();
    }

    /**
     * Calls {@code listener} whenever the log changes, so an open viewer stays current.
     *
     * <p>Fired on whichever thread made the change - which for git work is a worker
     * thread, not the FX one. A listener that touches the scene graph has to marshal.
     *
     * @return a handle that removes the listener; a viewer that closes without calling
     *         it keeps its whole window reachable from a static field
     */
    public static Runnable addListener(Runnable listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // A broken viewer must not take down the operation being logged.
            }
        }
    }

    /**
     * Trims a message to one line of reasonable length.
     *
     * <p>Git errors run to dozens of lines naming every affected file; the log is a
     * timeline, and one entry taking forty rows of it stops it being one.
     */
    private static String clean(String text) {
        if (text == null) return null;
        String line = text.strip();
        int newline = line.indexOf('\n');
        if (newline >= 0) line = line.substring(0, newline).strip();
        return line.length() > 160 ? line.substring(0, 157) + "…" : line;
    }
}
