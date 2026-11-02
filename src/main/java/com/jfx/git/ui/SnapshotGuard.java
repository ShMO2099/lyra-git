package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.WorkingTreeSnapshots;
import com.jfx.git.model.Snapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Takes a working-tree snapshot just before an action that throws uncommitted work away -
 * a discard, a hard reset, the "Reset" choice for a diverged pull request - so the work
 * can be brought back afterwards. See {@link WorkingTreeSnapshots}.
 *
 * <p>The snapshot never stands in the action's way: one that cannot be taken is logged
 * and the action goes ahead, exactly as it did before snapshots existed.
 */
public final class SnapshotGuard {

    private SnapshotGuard() {
    }

    /**
     * Wraps {@code work} so a snapshot is taken first, on the same lane, in the same task:
     * nothing can change the working tree between the two.
     */
    public static Supplier<GitResult> guarded(GitBackend backend, String reason, Supplier<GitResult> work) {
        return () -> {
            WorkingTreeSnapshots.takeQuietly(backend, reason);
            return work.get();
        };
    }

    /**
     * Runs a discard of {@code paths} - all changes when null - after a snapshot, and
     * reports it with Undo, which restores those files from the snapshot, and a way to
     * the snapshot list.
     *
     * @param openSnapshots opens the snapshot list; null leaves that action out
     * @param refresh       what the discard, and its undo, redraw once done
     */
    public static void discard(OperationRunner operations, String busy, String reason,
                               List<String> paths, Supplier<GitResult> work, Runnable openSnapshots,
                               OperationRunner.Refresh refresh) {
        GitBackend backend = operations.repository();
        AtomicReference<String> kept = new AtomicReference<>();
        operations.operation(busy, refresh)
                .showSuccess(result -> {
                    String id = kept.get();
                    Runnable undo = id == null ? null : () -> restore(operations, id, paths, refresh);
                    operations.statusBar().offerDiscardRecovery(result.describe(), undo, openSnapshots);
                })
                .run(() -> {
                    List<String> covered = paths != null ? paths : changedPaths(backend);
                    String id = WorkingTreeSnapshots.takeQuietly(backend, reason);
                    if (id == null) id = newestMatching(backend, covered);
                    kept.set(id);
                    return work.get();
                });
    }

    /** Puts {@code paths} - every file when null - back from snapshot {@code id}. */
    static void restore(OperationRunner operations, String id, List<String> paths, OperationRunner.Refresh refresh) {
        operations.run("Restoring from the snapshot", refresh,
                () -> operations.repository().restoreFromSnapshot(id, paths));
    }

    /** Every path with uncommitted changes, for checking the newest snapshot covers them. */
    private static List<String> changedPaths(GitBackend backend) {
        var staging = backend.getStagingState();
        List<String> paths = new ArrayList<>(staging.unstaged());
        for (String path : staging.staged()) {
            if (!paths.contains(path)) paths.add(path);
        }
        return paths;
    }

    /**
     * The newest snapshot, when it holds every one of {@code paths} exactly as the working
     * tree does now - which is why no new one was needed. Null otherwise: no snapshot was
     * taken because there was nothing to keep, or because there were too many untracked
     * files, and an older snapshot would "undo" to the wrong content.
     */
    private static String newestMatching(GitBackend backend, List<String> paths) {
        if (paths.isEmpty()) return null;
        try {
            List<Snapshot> snapshots = backend.listSnapshots();
            if (snapshots.isEmpty()) return null;
            Snapshot newest = snapshots.get(0);
            for (String path : paths) {
                if (!sameText(backend.getFileBytesAtCommit(newest.hash(), path), backend.getWorkingTreeBytes(path))) {
                    return null;
                }
            }
            return newest.id();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean sameText(byte[] a, byte[] b) {
        byte[] x = a == null ? new byte[0] : a;
        byte[] y = b == null ? new byte[0] : b;
        return Arrays.equals(x, y) || Arrays.equals(stripCr(x), stripCr(y));
    }

    private static byte[] stripCr(byte[] bytes) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(bytes.length);
        for (byte b : bytes) {
            if (b != '\r') out.write(b);
        }
        return out.toByteArray();
    }
}
