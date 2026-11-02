package com.jfx.git.core;

import com.jfx.git.model.Snapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What every engine agrees on about working-tree snapshots: where they are kept, what a
 * snapshot commit's message says, the size caps, and which ones pruning lets go.
 *
 * <h2>Why snapshots</h2>
 * A discard, a hard reset or the "Reset" choice when checking out a diverged pull request
 * throws away work git never saw: it was never committed, never stashed, so neither the
 * reflog nor the stash can bring it back. A snapshot is a commit of the whole working
 * tree, made through a temporary index so that taking one changes nothing the user can
 * see - which is what lets the application take them unasked, on a timer and before
 * every destructive action.
 *
 * <h2>Why in the repository</h2>
 * As commits under {@link #REF_PREFIX} the content is deduplicated against history by git
 * itself, and survives a crash of this application; refs under
 * {@link GitBackend#INTERNAL_REF_PREFIX} are already hidden from the graph and the
 * sidebar, and nothing fetches or pushes them.
 */
public final class WorkingTreeSnapshots {

    private static final Logger LOGGER = Logger.getLogger(WorkingTreeSnapshots.class.getName());

    /** Where snapshots live: {@code refs/lyra/snapshots/<epoch-millis>}. */
    public static final String REF_PREFIX = GitBackend.INTERNAL_REF_PREFIX + "snapshots/";

    /** Where snapshots lived before the rename; each engine moves them to {@link #REF_PREFIX}. */
    static final String LEGACY_REF_PREFIX = GitBackend.LEGACY_INTERNAL_REF_PREFIX + "snapshots/";

    /** A file larger than this keeps its staged (or committed) version in a snapshot. */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /**
     * More untracked files than this and no snapshot is taken at all: a build directory
     * someone forgot to ignore would otherwise be hashed into the object database every
     * ten minutes.
     */
    public static final int MAX_UNTRACKED_FILES = 2000;

    /** Pruning keeps at least this many of the newest snapshots... */
    public static final int KEEP_COUNT = 50;

    /** ...and every snapshot younger than this, whichever keeps more. */
    public static final Duration KEEP_AGE = Duration.ofDays(7);

    /**
     * Who a snapshot commit is by. Fixed rather than the user's identity: it is never
     * pushed or shown as a commit, and a repository without {@code user.name} configured
     * should still be protected.
     */
    static final String AUTHOR_NAME = "Lyra snapshot";
    static final String AUTHOR_EMAIL = "snapshot@lyra.invalid";

    private static final String BRANCH_LINE = "Branch: ";
    private static final String FILES_LINE = "Files: ";

    private WorkingTreeSnapshots() {
    }

    /** The full ref a snapshot id names. */
    static String refName(String id) {
        return REF_PREFIX + id;
    }

    /** Whether {@code id} could be a snapshot's id - digits only, so it cannot name another ref. */
    static boolean isId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 19 && id.chars().allMatch(Character::isDigit);
    }

    /**
     * An id for a snapshot taken now: the time in milliseconds, moved on one at a time
     * past any already {@code taken} - two snapshots in the same millisecond are rare,
     * but a guard and the timer can meet.
     */
    static String newId(Predicate<String> taken) {
        long millis = System.currentTimeMillis();
        while (taken.test(Long.toString(millis))) {
            millis++;
        }
        return Long.toString(millis);
    }

    /** The commit message: the reason, then the branch and the file count as trailers. */
    static String message(String reason, String branch, int files) {
        return clean(reason) + "\n\n" + BRANCH_LINE + clean(branch) + "\n" + FILES_LINE + files + "\n";
    }

    /** One line, without control characters, so the message parses back the same way. */
    private static String clean(String text) {
        if (text == null || text.isBlank()) return "Snapshot";
        String line = text.strip().lines().findFirst().orElse("Snapshot");
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().strip();
    }

    /**
     * Reads a snapshot back from its id, commit and message.
     *
     * @return null when {@code id} is not a snapshot id
     */
    static Snapshot parse(String id, String hash, String message) {
        if (!isId(id)) return null;
        String reason = "Snapshot";
        String branch = "";
        int files = 0;
        List<String> lines = message == null ? List.of() : message.lines().toList();
        boolean first = true;
        for (String raw : lines) {
            String line = raw.strip();
            if (first) {
                if (!line.isEmpty()) reason = line;
                first = false;
            } else if (line.startsWith(BRANCH_LINE)) {
                branch = line.substring(BRANCH_LINE.length()).strip();
            } else if (line.startsWith(FILES_LINE)) {
                try {
                    files = Integer.parseInt(line.substring(FILES_LINE.length()).strip());
                } catch (NumberFormatException ignored) {
                    // Hand-edited; the count is only for display.
                }
            }
        }
        return new Snapshot(id, hash, Instant.ofEpochMilli(Long.parseLong(id)), reason, branch, files);
    }

    /**
     * Which of {@code newestFirst} pruning removes: everything beyond the newest
     * {@link #KEEP_COUNT} that is also older than {@link #KEEP_AGE}.
     */
    public static List<Snapshot> toPrune(List<Snapshot> newestFirst, Instant now) {
        List<Snapshot> prune = new ArrayList<>();
        Instant cutoff = now.minus(KEEP_AGE);
        for (int i = KEEP_COUNT; i < newestFirst.size(); i++) {
            Snapshot snapshot = newestFirst.get(i);
            if (snapshot.time().isBefore(cutoff)) prune.add(snapshot);
        }
        return prune;
    }

    /**
     * Deletes the snapshots {@link #toPrune} names. Runs on the repository lane.
     *
     * @return how many were deleted
     */
    public static int prune(GitBackend backend) {
        int deleted = 0;
        for (Snapshot old : toPrune(backend.listSnapshots(), Instant.now())) {
            if (backend.deleteSnapshot(old.id()).isOk()) deleted++;
        }
        return deleted;
    }

    /**
     * Takes a snapshot and prunes, never throwing: a snapshot is a safety net, and a
     * failure to take one must not stop the action it was taken for. Runs on the
     * repository lane.
     *
     * @return the new snapshot's id, or null when none was taken
     */
    public static String takeQuietly(GitBackend backend, String reason) {
        try {
            String id = backend.snapshotWorkingTree(reason);
            if (id != null) prune(backend);
            return id;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not take a working-tree snapshot", e);
            return null;
        }
    }
}
