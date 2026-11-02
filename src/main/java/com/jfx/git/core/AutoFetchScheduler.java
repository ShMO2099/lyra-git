package com.jfx.git.core;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically fetches one repository in the background, per
 * {@link RepositoryPreferences#effectiveAutoFetchIntervalMinutes(java.io.File)}.
 *
 * <p>One instance per open {@link com.jfx.git.ui.RepoTab}, not a single shared timer:
 * each repository has its own interval reading - the global preference unless
 * {@link RepositoryPreferences} gives this one an override - and the per-tab lifecycle,
 * start when the tab opens and stop when it closes, is what makes that work.
 *
 * <h2>Why the scheduling timer is separate from the git worker</h2>
 * This class's own single-thread {@link ScheduledExecutorService} only ever does one
 * thing: submit {@link GitBackend#fetchAllSilently()} to {@link GitTaskRunner} on
 * schedule. The actual git work still runs on the repository's {@code GitTaskRunner}
 * lane, serialised with every other operation on that repository exactly as before -
 * this class never talks to the backend directly. That separation is what keeps a slow or hung fetch from
 * ever affecting the timer's own ability to schedule the next tick or to be shut down.
 *
 * <h2>Why the next tick is armed after the last one finishes</h2>
 * {@code scheduleWithFixedDelay} measures its delay from when the task <em>returns</em>,
 * and this task returns as soon as it has handed the fetch to {@link GitTaskRunner} -
 * which is immediately. So the delay was measured against submission, not against the
 * fetch: a fetch slower than the interval, over a VPN or against an unresponsive remote,
 * had another one queued behind it on every tick, and the repository lane filled with
 * fetches while every user action waited behind them. Each tick is now armed only once
 * the previous fetch has actually completed, so at most one auto-fetch per repository
 * exists at a time.
 */
public final class AutoFetchScheduler {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auto-fetch-timer");
        t.setDaemon(true);
        return t;
    });

    private final GitBackend backend;
    private final java.util.function.Consumer<Instant> onFetched;
    /**
     * The unit {@link AppPreferences#autoFetchIntervalMinutes()}'s number is measured
     * in, for real use. A test that needs to observe a schedule actually firing without
     * waiting on a real wall-clock minute constructs this with {@link
     * TimeUnit#MILLISECONDS} instead via the package-private constructor below - the
     * number itself still comes from the same preference, so what changes for a test is
     * only how long a "minute" is, not the scheduling logic being exercised.
     */
    private final TimeUnit intervalUnit;

    private ScheduledFuture<?> scheduled;
    private volatile int runningIntervalMinutes;
    /**
     * Set by {@link #shutdown()}, and checked by every callback that outlives it.
     *
     * <p>A fetch already handed to the repository lane cannot be recalled, so its
     * completion can arrive after the tab that owns this has closed. Reporting it would
     * touch a disposed tab; arming the next tick would keep fetching a repository nobody
     * has open.
     */
    private volatile boolean disposed;

    /**
     * @param onFetched called on the FX thread after each successful fetch, whether or
     *                  not it changed anything - the point is "auto-fetch just ran",
     *                  which is what a "last fetched" label needs to know
     */
    public AutoFetchScheduler(GitBackend backend, java.util.function.Consumer<Instant> onFetched) {
        this(backend, onFetched, TimeUnit.MINUTES);
    }

    /** Package-visible: only {@link AutoFetchSchedulerTest} needs a unit other than minutes. */
    AutoFetchScheduler(GitBackend backend, java.util.function.Consumer<Instant> onFetched,
                       TimeUnit intervalUnit) {
        this.backend = backend;
        this.onFetched = onFetched;
        this.intervalUnit = intervalUnit;
    }

    /**
     * Applies the current {@link AppPreferences#autoFetchIntervalMinutes()}, starting,
     * restarting or stopping the timer as needed.
     *
     * <p>Safe to call repeatedly - from the tab opening, from a refresh, from
     * the Settings tab the moment the interval changes - because it only
     * touches the running schedule when the interval has actually changed. Restarting
     * it every refresh would keep resetting the countdown so a fetch could reasonably
     * never fire in a session where refreshes happen more often than the interval.
     */
    public void applyPreference() {
        // Per-repository first, global otherwise: "fetch every five minutes" is right for
        // the repository being worked in and wasteful for the dozen kept open beside it.
        // Read on each call rather than cached, so changing it takes effect at once.
        int minutes = RepositoryPreferences.effectiveAutoFetchIntervalMinutes(backend.getWorkTree());
        if (minutes == runningIntervalMinutes) {
            return;
        }
        stop();
        runningIntervalMinutes = minutes;
        if (minutes <= 0) {
            return;
        }
        armNextTick(minutes);
    }

    /**
     * Schedules one tick, {@code minutes} from now.
     *
     * <p>One-shot rather than fixed-delay: the repeat comes from {@link #fetchNow}
     * re-arming once its fetch has finished, which is what bounds the work to a single
     * outstanding fetch.
     */
    private synchronized void armNextTick(int minutes) {
        if (disposed || minutes <= 0) {
            return;
        }
        scheduled = timer.schedule(this::fetchNow, minutes, intervalUnit);
    }

    /** Runs the fetch immediately without waiting for the countdown to reach it. */
    /** Told true when a background fetch starts and false when it ends, on the FX thread. */
    private java.util.function.Consumer<Boolean> onActivity;

    public void setOnActivity(java.util.function.Consumer<Boolean> onActivity) {
        this.onActivity = onActivity;
    }

    private void fetchNow() {
        if (disposed) {
            return;
        }
        java.util.function.Consumer<Boolean> activity = onActivity;
        if (activity != null) javafx.application.Platform.runLater(() -> activity.accept(true));
        GitTaskRunner.run(backend, backend::fetchAllSilently, result -> {
            if (activity != null) activity.accept(false);
            if (disposed) {
                // The tab closed while this was running. Nothing to report to, and
                // nothing to schedule for.
                return;
            }
            if (result.isOk() && onFetched != null) {
                onFetched.accept(Instant.now());
            }
            // A failed background fetch (no cached credential, remote unreachable,
            // whatever) is deliberately unreported beyond this - the whole point of
            // fetchAllSilently is that it never demands attention. The next
            // user-initiated fetch reports normally if the problem is still there.
            //
            // Either way the next tick is armed from here, once the work is genuinely
            // over, so a slow fetch delays the following one rather than stacking up
            // behind it.
            armNextTick(runningIntervalMinutes);
        });
    }

    /** Stops the timer without shutting down the executor, so {@link #applyPreference} can restart it. */
    public synchronized void stop() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        // Read by the completion callback when it decides whether to arm another tick,
        // so clearing it here is also what stops a fetch already in flight from
        // rescheduling itself after stop() has run.
        runningIntervalMinutes = 0;
    }

    /**
     * Stops the timer for good. Call when the owning tab closes.
     *
     * <p>Marks this disposed before shutting the timer down, so a fetch already on the
     * repository lane completes into a callback that does nothing rather than into a
     * closed tab.
     */
    public void shutdown() {
        disposed = true;
        stop();
        timer.shutdownNow();
    }

    /**
     * Waits for fetches - and everything else - already handed to the repository lanes to
     * finish, on every repository's lane; see {@link GitTaskRunner#awaitRepositoryWork}.
     *
     * <p>Each lane is a single worker taking work in order, so a marker submitted now
     * cannot run until everything submitted before it has. The markers go on the
     * repository lanes, because that is where fetches and every repository operation run.
     * The first version went through {@code runDetached} - the network lane, a different
     * thread - where it waited for nothing it was meant to, so the drain returned at once
     * and whatever it guarded raced what was still running.
     *
     * @param timeoutMillis how long to wait before giving up
     * @return whether every lane drained in time
     */
    public static boolean drainPendingWork(long timeoutMillis) {
        return GitTaskRunner.awaitRepositoryWork(timeoutMillis);
    }
}
