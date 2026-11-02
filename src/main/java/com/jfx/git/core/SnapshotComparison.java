package com.jfx.git.core;

import com.jfx.git.model.Snapshot;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A snapshot against the working tree as it is now: which files differ, and how.
 *
 * <p>Not a commit-to-commit comparison, because one side has no commit: it is whatever is
 * on disk. So it is worked out from what both engines already answer - the files the
 * snapshot changed relative to HEAD, the files the working tree changed relative to HEAD
 * (a file neither changed is the same on both sides), each compared byte for byte - and
 * the diff is formatted here rather than by git.
 */
public final class SnapshotComparison {

    /** The most files compared; a snapshot's own list is capped well below this anyway. */
    static final int MAX_FILES = 1000;

    private SnapshotComparison() {
    }

    /**
     * The files that differ between {@code snapshot} and the working tree, by path, as
     * {@code A} (only in the working tree), {@code D} (only in the snapshot) or {@code M},
     * with line counts. Runs on the repository lane.
     */
    public static List<HistoryOps.FileChange> changes(GitBackend backend, Snapshot snapshot) {
        Map<String, Character> inSnapshot = new TreeMap<>();
        String head = backend.getHeadHash();
        if (head != null && !head.isBlank()) {
            for (HistoryOps.FileChange change : backend.getChangesBetweenCommits(head, snapshot.hash())) {
                inSnapshot.put(change.path(), change.status());
            }
        } else {
            for (HistoryOps.FileChange change : backend.getChangesInCommit(snapshot.hash())) {
                inSnapshot.put(change.path(), change.status());
            }
        }
        TreeSet<String> candidates = new TreeSet<>(inSnapshot.keySet());
        var staging = backend.getStagingState();
        candidates.addAll(staging.unstaged());
        candidates.addAll(staging.staged());
        candidates.addAll(staging.conflicting());

        List<HistoryOps.FileChange> changes = new ArrayList<>();
        for (String path : candidates) {
            if (changes.size() >= MAX_FILES || GitTaskRunner.isCancelled()) break;
            Sides sides = sides(backend, snapshot, path, inSnapshot.get(path));
            if (sides.same()) continue;
            char status = sides.old() == null ? 'A' : sides.now() == null ? 'D' : 'M';
            int[] counts = sides.binary() ? new int[]{0, 0} : counts(sides);
            changes.add(new HistoryOps.FileChange(path, status, counts[0], counts[1]));
        }
        return changes;
    }

    /** {@code path} as the snapshot has it and as the working tree has it; null where absent. */
    record Sides(byte[] old, byte[] now) {
        boolean same() {
            if (old == null || now == null) return old == now;
            return Arrays.equals(old, now) || Arrays.equals(withoutCr(old), withoutCr(now));
        }

        boolean binary() {
            return old != null && RawText.isBinary(old) || now != null && RawText.isBinary(now);
        }
    }

    private static Sides sides(GitBackend backend, Snapshot snapshot, String path, Character statusInSnapshot) {
        byte[] old = backend.getFileBytesAtCommit(snapshot.hash(), path);
        // An engine answers a path the commit does not have with no bytes; the change list
        // says when that is a deletion rather than an empty file.
        boolean deleted = statusInSnapshot != null && statusInSnapshot == 'D';
        boolean listedAsPresent = statusInSnapshot != null && !deleted;
        if (old == null || deleted || (old.length == 0 && !listedAsPresent)) old = null;
        java.io.File file = new java.io.File(backend.getWorkTree(), path);
        byte[] now = file.isFile() ? backend.getWorkingTreeBytes(path) : null;
        return new Sides(old, now);
    }

    /**
     * The unified diff from the snapshot's copy of {@code path} to the working tree's,
     * with git's headers, for the diff viewer. Runs on the repository lane.
     */
    public static byte[] diff(GitBackend backend, Snapshot snapshot, String path) {
        Sides sides = sides(backend, snapshot, path, null);
        return format(path, sides.old(), sides.now());
    }

    /** {@code old} to {@code now} as a unified diff; either side null for a missing file. */
    static byte[] format(String path, byte[] old, byte[] now) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String header = "diff --git a/" + path + " b/" + path + "\n"
                + (old == null ? "new file mode 100644\n" : "")
                + (now == null ? "deleted file mode 100644\n" : "");
        out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        Sides sides = new Sides(old, now);
        if (sides.binary()) {
            out.writeBytes(("Binary files " + (old == null ? "/dev/null" : "a/" + path) + " and "
                    + (now == null ? "/dev/null" : "b/" + path) + " differ\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        }
        out.writeBytes(("--- " + (old == null ? "/dev/null" : "a/" + path) + "\n+++ "
                + (now == null ? "/dev/null" : "b/" + path) + "\n").getBytes(StandardCharsets.UTF_8));
        byte[][] aligned = aligned(old, now);
        RawText a = new RawText(aligned[0]);
        RawText b = new RawText(aligned[1]);
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, a, b);
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setContext(3);
            formatter.format(edits, a, b);
            formatter.flush();
        } catch (IOException impossible) {
            // A byte array stream does not fail.
        }
        return out.toByteArray();
    }

    /** Lines added and removed going from the snapshot to the working tree. */
    private static int[] counts(Sides sides) {
        byte[][] aligned = aligned(sides.old(), sides.now());
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, new RawText(aligned[0]), new RawText(aligned[1]));
        int added = 0;
        int deleted = 0;
        for (Edit edit : edits) {
            added += edit.getLengthB();
            deleted += edit.getLengthA();
        }
        return new int[]{added, deleted};
    }

    /**
     * Both sides, with the working tree's CRLF line endings read as LF when the snapshot
     * has none: with {@code core.autocrlf} the repository stores LF and the disk holds
     * CRLF, and every line would otherwise differ.
     */
    private static byte[][] aligned(byte[] old, byte[] now) {
        byte[] a = old == null ? new byte[0] : old;
        byte[] b = now == null ? new byte[0] : now;
        if (indexOf(a, (byte) '\r') < 0 && indexOf(b, (byte) '\r') >= 0) b = withoutCr(b);
        return new byte[][]{a, b};
    }

    private static int indexOf(byte[] bytes, byte wanted) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == wanted) return i;
        }
        return -1;
    }

    /** {@code bytes} with each CR that ends a line removed. */
    static byte[] withoutCr(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') continue;
            out.write(bytes[i]);
        }
        return out.toByteArray();
    }
}
