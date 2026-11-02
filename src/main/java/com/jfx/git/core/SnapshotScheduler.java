package com.jfx.git.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Snapshots one repository's working tree on a timer, per
 * {@link AppPreferences#snapshotIntervalMinutes()} - see {@link WorkingTreeSnapshots}.
 *
 * <p>One per open {@link com.jfx.git.ui.RepoTab}, built like {@link AutoFetchScheduler}:
 * the timer only hands the work to the repository lane, and the next tick is armed once
 * that work has finished, so a slow snapshot of a large tree delays the next one rather
 * than queueing behind it. A snapshot of a tree that has not changed since the last one
 * is not taken, so an idle repository costs a status scan per tick and nothing else.
 */
public final class SnapshotScheduler {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "snapshot-timer");
        t.setDaemon(true);
        return t;
    });

    private final GitBackend backend;
    private final TimeUnit intervalUnit;
    private ScheduledFuture<?> scheduled;
    private volatile int runningIntervalMinutes;
    private volatile boolean disposed;

    public SnapshotScheduler(GitBackend backend) {
        this(backend, TimeUnit.MINUTES);
    }

    /** Package-visible so a test can make a "minute" a millisecond. */
    SnapshotScheduler(GitBackend backend, TimeUnit intervalUnit) {
        this.backend = backend;
        this.intervalUnit = intervalUnit;
    }

    /**
     * Starts, restarts or stops the timer for the current preference. Cheap to call on
     * every refresh: the running schedule is touched only when the interval changed.
     */
    public synchronized void applyPreference() {
        int minutes = AppPreferences.snapshotIntervalMinutes();
        if (minutes == runningIntervalMinutes) return;
        stop();
        runningIntervalMinutes = minutes;
        armNextTick(minutes);
    }

    private synchronized void armNextTick(int minutes) {
        if (disposed || minutes <= 0) return;
        scheduled = timer.schedule(this::snapshotNow, minutes, intervalUnit);
    }

    private void snapshotNow() {
        if (disposed || !backend.isOpen()) {
            armNextTick(runningIntervalMinutes);
            return;
        }
        int minutes = runningIntervalMinutes;
        GitTaskRunner.run(backend, () -> {
            WorkingTreeSnapshots.takeQuietly(backend,
                    "Every " + minutes + (minutes == 1 ? " minute" : " minutes"));
            return GitResult.ok();
        }, result -> armNextTick(runningIntervalMinutes));
    }

    /** Stops the timer; {@link #applyPreference} starts it again. */
    public synchronized void stop() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        runningIntervalMinutes = 0;
    }

    /** Stops the timer for good, when the tab closes. */
    public void shutdown() {
        disposed = true;
        stop();
        timer.shutdownNow();
    }
}
