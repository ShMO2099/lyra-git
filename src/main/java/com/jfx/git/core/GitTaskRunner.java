package com.jfx.git.core;

import javafx.application.Platform;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs git work off the JavaFX Application Thread and delivers the outcome back on it.
 *
 * <p>Previously every git call ran inline in the event handler (freezing the UI on
 * large repositories), except fetch/pull/push which each spawned a raw
 * non-daemon {@code new Thread(task)}. This funnels all of it through daemon workers so
 * the JVM can still exit cleanly.
 *
 * <h2>Lanes</h2>
 * <dl>
 *   <dt>Repository lanes</dt>
 *   <dd>One per open repository, each one thread, on purpose. A {@link GitBackend} is not
 *       built for concurrent callers: {@code GitService} holds one JGit
 *       {@code Repository}, whose object database, ref lock and {@code DirCache} writes
 *       are only safe when operations do not interleave, and two workers racing a refresh
 *       against a commit produced exactly the kind of failure that shows up as a corrupt
 *       status rather than an exception. Serialising a repository's git work through one
 *       worker makes every backend correct by construction.
 *       <p>Per repository rather than one for the whole process: with a single shared
 *       lane, a fetch, a large commit or a slow status in one tab stalled every other
 *       tab's work - the second tab's staging list waited behind the first tab's commit,
 *       and its status bar counted the first tab's work as its own "waiting". Two
 *       repositories share nothing a lane protects, so their work has no reason to queue
 *       together.
 *       <p>A lane is keyed by the backend instance a tab hands its components - every
 *       component of one tab shares it, and the application refuses to open two tabs on
 *       one repository, so one repository has one lane. Every call that touches a
 *       repository names it ({@link #run(GitBackend, Supplier, Consumer)} and the rest);
 *       there is deliberately no variant without one. A call on some shared lane and
 *       another on the repository's own would run side by side against one engine, which
 *       is the one thing the lanes exist to prevent, and {@link ThreadGuard} reports an
 *       engine called from another repository's lane.</dd>
 *   <dt>Network lane</dt>
 *   <dd>Also one thread, for work that touches no open repository: cloning into a
 *       directory nothing has opened yet, and provider REST calls to GitHub, GitLab and
 *       Bitbucket. These used to queue behind repository work and, worse, ahead of it -
 *       a clone of a large repository or a slow API call froze the staging panel of
 *       <em>every other open tab</em>, which is not a correctness constraint but an
 *       accident of there being one queue.</dd>
 * </dl>
 *
 * <h2>A repository lane's life</h2>
 * Created by the first work submitted for its repository, and named for it - {@code
 * git-worker-<folder>} once {@link #nameLane} says which folder - so a thread dump says
 * whose work is stuck. Its thread exits after a quiet minute and the next submission starts
 * another under the same name, so a tab left open in the background holds no thread.
 * {@link #closeRepository} retires it when the tab closes: the close runs behind
 * everything already queued, and nothing submitted afterwards runs at all. The lane table
 * holds its backends weakly, so a backend nothing else holds - a component built in a
 * test and never closed - takes its lane with it.
 *
 * <h2>Cancellation</h2>
 * Both {@link #run} and {@link #query} hand back a {@link Task}. Cancelling one that has
 * not started removes it from the queue; cancelling a running one raises a flag the work
 * itself polls through {@link #isCancelled()} - JGit progress monitors and the native
 * runner's process wait both check it, so a clone, fetch or long native command stops
 * within a beat rather than running to completion with nobody waiting for the answer.
 * A task that never runs calls none of its callbacks; {@link Task#onDropped} is how its
 * owner hears that it is not coming.
 */
public final class GitTaskRunner {

    private static final Logger LOGGER = Logger.getLogger(GitTaskRunner.class.getName());

    /** Clones and other work that touches no open repository. */
    private static final ExecutorService NETWORK_LANE = lane("git-network");

    /**
     * Hosting-provider API calls: pull request and issue lists, CI status, the account
     * check behind a remembered token.
     *
     * <p>A few threads of their own. On the single network lane every one of these queued
     * behind every other - one pull request list put two status requests per pull request
     * on it, none of them ever cancelled, and the issue list opened next waited behind all
     * of them, and behind any clone or update check besides. They touch no repository and
     * nothing on disk, so running several at once is safe. Four: the pull request list
     * keeps at most three status calls in flight, so a page or a merge never waits.
     */
    private static final ExecutorService PROVIDER_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "provider-api");
        t.setDaemon(true);
        return t;
    });

    private static ExecutorService lane(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * The task the calling thread is currently running, so work can poll its own
     * cancellation without every method having to take a flag parameter.
     */
    private static final ThreadLocal<Task> CURRENT = new ThreadLocal<>();

    /**
     * The repository whose lane the calling thread is running work for, or null on any
     * other thread; see {@link #laneRepository()}.
     */
    private static final ThreadLocal<GitBackend> CURRENT_REPOSITORY = new ThreadLocal<>();

    private GitTaskRunner() {
    }

    /**
     * A submitted piece of work, and the ability to ask it to stop.
     *
     * <p>Cancellation is cooperative once the work is running: git operations are not
     * safely interruptible at an arbitrary point - killing a thread mid-index-write is
     * how repositories get corrupted - so the flag is raised and the work checks it where
     * stopping is safe.
     */
    public static final class Task {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Future<?> future;
        /** Set when the lane picks the task up; a task cancelled before then never ran. */
        private final AtomicBoolean started = new AtomicBoolean();
        /** Repository lane the task was queued on, so a cancel before it starts can uncount it. */
        private volatile RepositoryLane lane;
        private volatile Consumer<Progress> progressSink;
        private volatile long lastProgressNanos;
        /** Told when this task will never run; see {@link #onDropped}. Guarded by this. */
        private Runnable droppedListener;
        /** Whether this task will never run, and whether the listener has been told. Guarded by this. */
        private boolean dropped;
        private boolean droppedTold;

        /**
         * Tells {@code listener}, on the FX thread, if this task never runs: cancelled before
         * it started, or submitted after its repository closed. Neither calls the task's own
         * callbacks, so whatever was waiting for them - a busy indicator, above all - waits
         * for ever unless it listens here. Told at once when the task has already been
         * dropped; never told for a task that started.
         */
        public void onDropped(Runnable listener) {
            synchronized (this) {
                droppedListener = listener;
                if (!dropped) return;
            }
            tellDropped();
        }

        /** Marks this task as one that will never run, and says so to whoever listens. */
        private void dropped() {
            synchronized (this) {
                dropped = true;
            }
            tellDropped();
        }

        private void tellDropped() {
            Runnable listener;
            synchronized (this) {
                if (droppedTold || droppedListener == null) return;
                droppedTold = true;
                listener = droppedListener;
            }
            Platform.runLater(listener);
        }

        /**
         * Receives this task's progress on the FX thread, at most every 80 ms. Nothing
         * calls it for work that does not report - most of it - so a caller can attach
         * one to anything.
         */
        public void onProgress(Consumer<Progress> sink) {
            this.progressSink = sink;
        }

        boolean wantsProgress() {
            return progressSink != null;
        }

        /** Hands a report to the sink, thinned so a transfer does not flood the FX thread. */
        void report(Progress progress) {
            Consumer<Progress> sink = progressSink;
            if (sink == null) return;
            long now = System.nanoTime();
            if (progress.fraction() < 1 && now - lastProgressNanos < 80_000_000L) return;
            lastProgressNanos = now;
            Platform.runLater(() -> sink.accept(progress));
        }

        /**
         * Asks this task to stop.
         *
         * <p>If it has not started, it never will. If it is running, the flag it polls
         * turns true and it stops at its next checkpoint - or, for work with no
         * checkpoint, runs to completion and delivers its result as usual. Cancelling is
         * therefore always safe and never guaranteed to be instant.
         */
        public void cancel() {
            cancelled.set(true);
            Future<?> f = future;
            // false: never interrupt a running git operation - see the class comment.
            if (f != null && f.cancel(false) && !started.get()) {
                // Removed from the queue without running, so it is waiting no longer.
                dequeued(lane);
                dropped();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * Drops this task if it is still waiting, and leaves it alone if it has started.
         *
         * <p>What superseding a request wants: work nobody will look at should not run, but
         * stopping work already running would kill its git process and report a failure
         * for something nobody did wrong. A started task finishes, and its caller ignores
         * the stale result as it already did.
         */
        void cancelIfWaiting() {
            if (!started.get()) cancel();
        }
    }

    // ------------------------------------------------------------------
    // Repository lanes
    // ------------------------------------------------------------------

    /** How long an idle lane keeps its thread; the next task starts a new one. */
    private static final long LANE_IDLE_SECONDS = 60;

    /**
     * One repository's lane: its single worker, what is waiting on it, and what it is
     * showing to whoever asked to be told.
     */
    private static final class RepositoryLane {

        private static final AtomicInteger UNNAMED = new AtomicInteger();

        private final ThreadPoolExecutor executor;
        private volatile String name = "git-worker-" + UNNAMED.incrementAndGet();
        private volatile Thread worker;
        /** Set by {@link #retire}; nothing is accepted afterwards. Guarded by this. */
        private boolean retired;

        /** Work this lane has accepted and not yet finished, markers and prefetches included. */
        final AtomicInteger pending = new AtomicInteger();
        /** Tasks submitted through {@link #run} or {@link #query} and not yet started. */
        final AtomicInteger queued = new AtomicInteger();
        /** Tasks running now: 0 or 1, the lane having one thread. */
        final AtomicInteger running = new AtomicInteger();
        /** When the lane last finished a task, in {@link System#nanoTime()} terms. */
        volatile long idleSince = System.nanoTime();
        /** The newest waiting task per purpose; see {@link #queryLatest}. FX thread only. */
        final Map<Object, Task> latest = new HashMap<>();
        final List<IntConsumer> queueListeners = new CopyOnWriteArrayList<>();

        RepositoryLane() {
            executor = new ThreadPoolExecutor(1, 1, LANE_IDLE_SECONDS, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, name);
                        t.setDaemon(true);
                        worker = t;
                        return t;
                    });
            executor.allowCoreThreadTimeOut(true);
        }

        /**
         * Queues {@code work} behind everything already here; null once the lane is
         * retired, when it never runs.
         *
         * <p>The returned future wraps the work, and the executor runs a wrapper around
         * the future, so a task cancelled while it waits still passes through the lane as
         * a no-op and is uncounted from {@link #pending} as it goes.
         */
        synchronized Future<?> submit(Runnable work) {
            if (retired) return null;
            FutureTask<Void> future = new FutureTask<>(work, null);
            pending.incrementAndGet();
            executor.execute(() -> {
                try {
                    future.run();
                } finally {
                    pending.decrementAndGet();
                }
            });
            return future;
        }

        /**
         * Queues {@code last}, if there is one, and then accepts nothing more; false when
         * the lane was already retired and {@code last} was not queued.
         */
        synchronized boolean retire(Runnable last) {
            if (retired) return false;
            if (last != null) submit(last);
            retired = true;
            // Nothing will wait here again, and a listener holding its tab is a leak.
            queueListeners.clear();
            // Graceful: what is queued still runs, in order, and the thread then exits.
            executor.shutdown();
            return true;
        }

        void rename(String folder) {
            name = "git-worker-" + folder;
            Thread current = worker;
            if (current != null && current.isAlive()) current.setName(name);
        }
    }

    /**
     * A lane table key: a backend, held weakly and compared by identity.
     *
     * <p>Identity because a lane belongs to one backend instance, whatever its
     * {@code equals} says; weakly so a backend nothing else holds takes its lane with it.
     */
    private static final class LaneKey extends WeakReference<GitBackend> {
        private final int hash;

        LaneKey(GitBackend repository, ReferenceQueue<GitBackend> queue) {
            super(repository, queue);
            this.hash = System.identityHashCode(repository);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) return true;
            if (!(other instanceof LaneKey key)) return false;
            GitBackend mine = get();
            return mine != null && mine == key.get();
        }
    }

    /** Every repository's lane. Guarded by itself. */
    private static final Map<LaneKey, RepositoryLane> LANES = new HashMap<>();
    /** Keys whose backend has been collected, for {@link #expungeCollected}. */
    private static final ReferenceQueue<GitBackend> COLLECTED = new ReferenceQueue<>();
    /** Lanes whose backend was collected, until the work they still hold has finished. */
    private static final List<RepositoryLane> ORPHANED = new ArrayList<>();

    /** The lane for {@code repository}, created on first use. */
    private static RepositoryLane laneFor(GitBackend repository) {
        Objects.requireNonNull(repository, "repository work must name its repository");
        synchronized (LANES) {
            expungeCollected();
            RepositoryLane lane = LANES.get(new LaneKey(repository, null));
            if (lane == null) {
                lane = new RepositoryLane();
                LANES.put(new LaneKey(repository, COLLECTED), lane);
            }
            return lane;
        }
    }

    /** The lane for {@code repository} if it has one, without creating it. */
    private static RepositoryLane existingLane(GitBackend repository) {
        synchronized (LANES) {
            expungeCollected();
            return LANES.get(new LaneKey(repository, null));
        }
    }

    /** Drops the lanes of collected backends. Holding {@link #LANES}. */
    private static void expungeCollected() {
        for (Reference<? extends GitBackend> gone; (gone = COLLECTED.poll()) != null; ) {
            RepositoryLane lane = LANES.remove(gone);
            if (lane != null) {
                // Nothing can name this repository any more; what it still holds finishes.
                lane.retire(null);
                ORPHANED.add(lane);
            }
        }
        ORPHANED.removeIf(lane -> lane.executor.isTerminated());
    }

    /** Every lane that may still hold work, retired and orphaned ones included. */
    private static List<RepositoryLane> allLanes() {
        synchronized (LANES) {
            expungeCollected();
            List<RepositoryLane> all = new ArrayList<>(LANES.values());
            all.addAll(ORPHANED);
            return all;
        }
    }

    /**
     * Names {@code repository}'s lane thread {@code git-worker-<folder>}, now and for
     * every thread it starts later.
     */
    public static void nameLane(GitBackend repository, String folder) {
        laneFor(repository).rename(folder);
    }

    /**
     * The repository whose lane the calling thread is running work for; null on the FX
     * thread, the network lane, a test thread, anywhere else. For {@link ThreadGuard}.
     */
    static GitBackend laneRepository() {
        return CURRENT_REPOSITORY.get();
    }

    /**
     * Runs {@code close} on {@code repository}'s lane behind everything already waiting
     * there, then retires the lane: whatever is submitted for the repository afterwards
     * does not run - its {@link Task} comes back cancelled and its callbacks are never
     * called. Closing is the user's last word on a repository, and work arriving after
     * it - a callback chaining a refresh of a tab nobody has open - would only run
     * against a released engine.
     *
     * <p>Waits for the close up to {@code timeoutMillis}: the repository's files are held
     * open until it runs, and whatever closes a tab expects them released. A lane stuck
     * past the bound runs the close when it frees.
     *
     * @return whether the close ran within the bound; true too when the lane was
     *         already retired, by an earlier close
     */
    public static boolean closeRepository(GitBackend repository, Runnable close, long timeoutMillis) {
        CountDownLatch closed = new CountDownLatch(1);
        boolean queued = laneFor(repository).retire(() -> {
            CURRENT_REPOSITORY.set(repository);
            try {
                close.run();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.log(Level.FINE, "Closing a repository failed", e);
            } finally {
                CURRENT_REPOSITORY.remove();
                closed.countDown();
            }
        });
        if (!queued) return true;
        try {
            return closed.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Waits until every repository lane has finished the work submitted to it before this
     * call - and a lane that first takes work while this waits, what reached it by then.
     *
     * <p>For a test's cleanup and its negative assertions: a click that starts git work
     * returns long before the work does. A marker is queued on each busy lane at once, so
     * the lanes drain side by side rather than one after another, and a retired lane is
     * waited on until its thread has finished.
     *
     * @return whether everything drained within {@code timeoutMillis}
     */
    public static boolean awaitRepositoryWork(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Set<RepositoryLane> drained = Collections.newSetFromMap(new IdentityHashMap<>());
        while (true) {
            List<RepositoryLane> busy = new ArrayList<>();
            for (RepositoryLane lane : allLanes()) {
                if (!drained.contains(lane) && lane.pending.get() > 0) busy.add(lane);
            }
            if (busy.isEmpty()) return true;
            List<CountDownLatch> markers = new ArrayList<>();
            List<RepositoryLane> retired = new ArrayList<>();
            for (RepositoryLane lane : busy) {
                CountDownLatch marker = new CountDownLatch(1);
                if (lane.submit(marker::countDown) != null) {
                    markers.add(marker);
                } else {
                    retired.add(lane);
                }
            }
            try {
                for (CountDownLatch marker : markers) {
                    if (!marker.await(remaining(deadline), TimeUnit.NANOSECONDS)) return false;
                }
                for (RepositoryLane lane : retired) {
                    if (!lane.executor.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            drained.addAll(busy);
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    // ------------------------------------------------------------------
    // Latest-wins queries and prefetch
    // ------------------------------------------------------------------

    /**
     * {@link #query}, where a newer request for the same {@code key} on the same
     * repository drops this one if it has not started yet.
     *
     * <h2>Why</h2>
     * A repository lane runs in order, so arrowing through twenty files queued twenty
     * diff reads and the twentieth appeared only after the nineteen nobody was looking at
     * any more had run - each caller threw the stale result away, but only after the work
     * was done. Call from the FX thread, as every caller of {@link #query} does.
     *
     * @param key what the request is for - one per purpose per owner, e.g. a panel and
     *            "diff"; requests with equal keys on one repository supersede each other
     */
    public static <T> Task queryLatest(GitBackend repository, Object key, Supplier<T> work,
                                       Consumer<T> onDone, Consumer<String> onError) {
        RepositoryLane lane = laneFor(repository);
        Task previous = lane.latest.remove(key);
        if (previous != null) previous.cancelIfWaiting();
        Task[] self = new Task[1];
        self[0] = query(repository, work,
                value -> {
                    lane.latest.remove(key, self[0]);
                    onDone.accept(value);
                },
                error -> {
                    lane.latest.remove(key, self[0]);
                    onError.accept(error);
                });
        if (!self[0].isCancelled()) lane.latest.put(key, self[0]);
        return self[0];
    }

    /**
     * Runs {@code work} on {@code repository}'s lane when nothing else is waiting there,
     * and not at all otherwise.
     *
     * <p>For fetching ahead of the user - the first file of a commit they just selected,
     * the next commit while they arrow through history - so the answer is already in
     * {@link SwitchableGitBackend}'s cache when they ask. It never delays anything asked
     * for: a prefetch that reaches the front of the lane while real work is queued behind
     * it is dropped, and it is not counted in the "waiting" figure the status bar shows.
     * Once started it runs to completion, so keep each one to a single, small read.
     * Failures are ignored: it was only ever a guess.
     */
    public static void prefetch(GitBackend repository, Runnable work) {
        RepositoryLane lane = laneFor(repository);
        lane.submit(() -> {
            if (lane.queued.get() > 0) return;
            CURRENT_REPOSITORY.set(repository);
            try {
                work.run();
            } catch (RuntimeException | LinkageError ignored) {
                // Only a guess at what would be wanted next; the real request will report.
            } finally {
                CURRENT_REPOSITORY.remove();
            }
        });
    }

    /**
     * Whether the work running on this thread has been cancelled.
     *
     * <p>Returns false on any thread that is not a task worker, so a backend called
     * directly from a test behaves as if nobody ever cancels.
     */
    public static boolean isCancelled() {
        Task task = CURRENT.get();
        return task != null && task.isCancelled();
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /**
     * How far a piece of work has got: what it is doing now, and how much of that is
     * done, from 0 to 1 - or negative when there is no telling.
     */
    public record Progress(String stage, double fraction) {
    }

    /**
     * Reports progress for the work running on this thread. A no-op on any other thread
     * and for work nobody asked to watch, so a backend can report unconditionally.
     */
    public static void reportProgress(String stage, double fraction) {
        Task task = CURRENT.get();
        if (task != null) task.report(new Progress(stage, fraction));
    }

    /**
     * Where the work on this thread reports to, for a helper thread it starts - the
     * reader of a git process's stderr - which has no task of its own. {@code null} when
     * nobody is watching, which is also the signal not to ask git for progress at all.
     */
    public static Consumer<Progress> progressReporter() {
        Task task = CURRENT.get();
        return task == null || !task.wantsProgress() ? null : task::report;
    }

    /** Wants the next task submitted from the FX thread, this pulse; see {@link #claimNextTask}. */
    private static Consumer<Task> claimant;

    /**
     * Hands the next task submitted from the FX thread in this pulse to {@code claimant},
     * before it starts.
     *
     * <p>How the status bar finds the work it is announcing without every one of the
     * two hundred places that say {@code busy(...)} then submit something having to pass
     * the task along: busy claims, the submission a line later is claimed. Unclaimed by
     * the end of the pulse, the claim lapses rather than catch some later, unrelated task.
     */
    public static void claimNextTask(Consumer<Task> claimant) {
        GitTaskRunner.claimant = claimant;
        Platform.runLater(() -> {
            if (GitTaskRunner.claimant == claimant) GitTaskRunner.claimant = null;
        });
    }

    private static void offerToClaimant(Task task) {
        Consumer<Task> waiting = claimant;
        if (waiting != null && Platform.isFxApplicationThread()) {
            claimant = null;
            waiting.accept(task);
        }
    }

    // ------------------------------------------------------------------
    // Queued work
    // ------------------------------------------------------------------

    /** Repository tasks submitted and not yet started, on every lane together. */
    private static final AtomicInteger QUEUED = new AtomicInteger();

    /**
     * Told, on the FX thread, how many of {@code repository}'s tasks are waiting behind
     * the running one - for the status bar's "2 waiting", which is the difference between
     * "this is slow" and "this is stuck". Another repository's work is not counted: it
     * does not wait on this lane, and this lane does not wait on it.
     *
     * <p>The lane keeps {@code listener} until the repository closes, so it must not hold
     * {@code repository} strongly: a lane that holds its own key is never let go.
     */
    public static void addQueueListener(GitBackend repository, IntConsumer listener) {
        laneFor(repository).queueListeners.add(listener);
    }

    public static void removeQueueListener(GitBackend repository, IntConsumer listener) {
        RepositoryLane lane = existingLane(repository);
        if (lane != null) lane.queueListeners.remove(listener);
    }

    /** Repository tasks waiting to start, on every repository's lane together. */
    public static int queued() {
        return QUEUED.get();
    }

    /** {@code repository}'s tasks waiting to start. */
    public static int queued(GitBackend repository) {
        RepositoryLane lane = existingLane(repository);
        return lane == null ? 0 : lane.queued.get();
    }

    private static void enqueued(RepositoryLane lane) {
        if (lane == null) return;
        QUEUED.incrementAndGet();
        announceQueue(lane, lane.queued.incrementAndGet());
    }

    private static void dequeued(RepositoryLane lane) {
        if (lane == null) return;
        QUEUED.updateAndGet(n -> Math.max(0, n - 1));
        announceQueue(lane, lane.queued.updateAndGet(n -> Math.max(0, n - 1)));
    }

    private static void announceQueue(RepositoryLane lane, int count) {
        if (lane.queueListeners.isEmpty()) return;
        Platform.runLater(() -> lane.queueListeners.forEach(l -> l.accept(count)));
    }

    // ------------------------------------------------------------------
    // Submitting
    // ------------------------------------------------------------------

    /**
     * Runs {@code work} on {@code repository}'s lane, then calls {@code onDone} on the FX
     * thread. Any exception is converted into a {@link GitResult#failed} so callers never
     * have to deal with an exception escaping an event handler.
     *
     * @param repository the backend {@code work} touches - the one its tab handed out,
     *                   so that every component of a tab queues on one lane
     */
    public static Task run(GitBackend repository, Supplier<GitResult> work, Consumer<GitResult> onDone) {
        return submit(laneFor(repository), null, repository, resultOf(work, onDone), null);
    }

    /**
     * {@link #run}, handing the task to {@code beforeQueued} before it is queued - so before
     * it can start - instead of to whatever {@link #claimNextTask} is waiting this pulse.
     * For a caller that wants the task itself: to watch its progress from its very first
     * report, or to hear {@link Task#onDropped} that it will never run.
     */
    public static Task run(GitBackend repository, Supplier<GitResult> work, Consumer<GitResult> onDone,
                           Consumer<Task> beforeQueued) {
        return submit(laneFor(repository), null, repository, resultOf(work, onDone),
                Objects.requireNonNull(beforeQueued));
    }

    /**
     * Runs {@code work} on the network lane - for a clone, or a provider API call, which
     * touch no open repository and so must not queue behind (or ahead of) a tab's
     * staging refresh.
     */
    public static Task runDetached(Supplier<GitResult> work, Consumer<GitResult> onDone) {
        return submit(null, NETWORK_LANE, null, resultOf(work, onDone), null);
    }

    /**
     * Runs a read-only query on {@code repository}'s lane and delivers the value on the
     * FX thread. On failure {@code onError} receives the message and {@code onDone} is not
     * called.
     *
     * @param repository the backend {@code work} touches; see {@link #run}
     */
    public static <T> Task query(GitBackend repository, Supplier<T> work, Consumer<T> onDone,
                                 Consumer<String> onError) {
        return submit(laneFor(repository), null, repository, valueOf(work, onDone, onError), null);
    }

    /** {@link #query}, handing the task to {@code beforeQueued} first; see {@link #run(GitBackend, Supplier, Consumer, Consumer)}. */
    public static <T> Task query(GitBackend repository, Supplier<T> work, Consumer<T> onDone,
                                 Consumer<String> onError, Consumer<Task> beforeQueued) {
        return submit(laneFor(repository), null, repository, valueOf(work, onDone, onError),
                Objects.requireNonNull(beforeQueued));
    }

    /** {@link #query} on the network lane; see {@link #runDetached}. */
    public static <T> Task queryDetached(Supplier<T> work, Consumer<T> onDone,
                                         Consumer<String> onError) {
        return submit(null, NETWORK_LANE, null, valueOf(work, onDone, onError), null);
    }

    /**
     * {@link #query} for a hosting-provider API call, on the provider pool - see
     * {@link #PROVIDER_POOL}. Cancel the returned task when nobody wants the answer any
     * more; the clients check {@link #isCancelled()} between requests.
     */
    public static <T> Task queryProvider(Supplier<T> work, Consumer<T> onDone, Consumer<String> onError) {
        return submit(null, PROVIDER_POOL, null, valueOf(work, onDone, onError), null);
    }

    /** {@link #run} for a hosting-provider API call that changes something; see {@link #queryProvider}. */
    public static Task runProvider(Supplier<GitResult> work, Consumer<GitResult> onDone) {
        return submit(null, PROVIDER_POOL, null, resultOf(work, onDone), null);
    }

    /**
     * Queues {@code body} as a task: on {@code lane} when it is repository work, on
     * {@code shared} otherwise.
     *
     * @param beforeQueued takes the task before it is queued; null hands it to the
     *                     {@link #claimNextTask claimant} waiting this pulse, if any
     */
    private static Task submit(RepositoryLane lane, ExecutorService shared, GitBackend repository,
                               Runnable body, Consumer<Task> beforeQueued) {
        Task task = new Task();
        task.lane = lane;
        if (beforeQueued == null) {
            offerToClaimant(task);
        } else {
            // Its owner has it; a busy() said in the same pulse is waiting for its own.
            beforeQueued.accept(task);
        }
        Runnable run = () -> {
            task.started.set(true);
            dequeued(lane);
            // A cancel that raced the submission found no future to stop, so the task is
            // stopped here instead: one cancelled before it starts never runs.
            if (task.isCancelled()) {
                task.dropped();
                return;
            }
            // Counted from the start of the run rather than from submission: a queued
            // task cancelled before it starts never runs, and would never be uncounted.
            begin(lane);
            CURRENT.set(task);
            if (repository != null) CURRENT_REPOSITORY.set(repository);
            try {
                body.run();
            } finally {
                CURRENT.remove();
                CURRENT_REPOSITORY.remove();
                end(lane);
            }
        };
        if (lane == null) {
            task.future = shared.submit(run);
            return task;
        }
        enqueued(lane);
        Future<?> future = lane.submit(run);
        if (future == null) {
            // The repository's tab has closed; see closeRepository.
            task.cancelled.set(true);
            dequeued(lane);
            task.dropped();
            return task;
        }
        task.future = future;
        return task;
    }

    /** The body of a {@link #run}: the result, or the failure, delivered to {@code onDone}. */
    private static Runnable resultOf(Supplier<GitResult> work, Consumer<GitResult> onDone) {
        return () -> {
            GitResult result;
            VirtualMachineError fatal = null;
            try {
                result = work.get();
            } catch (GitOperationException e) {
                result = GitResult.failed(e.getMessage());
            } catch (VirtualMachineError e) {
                // Reported first so "Committing…" does not spin forever, then rethrown:
                // an out-of-memory or stack overflow is not something to carry on after.
                result = GitResult.failed(describeError(e));
                fatal = e;
            } catch (Throwable e) {
                // Throwable, not Exception: an Error - a NoClassDefFoundError from a
                // missing optional dependency, an assertion - used to escape without
                // onDone ever running, which left the busy indicator and flags such as
                // checkingExternalChanges stuck for the rest of the session.
                result = GitResult.failed(describeError(e));
            }
            GitResult delivered = result;
            Platform.runLater(() -> onDone.accept(delivered));
            if (fatal != null) throw fatal;
        };
    }

    /** The body of a {@link #query}: the value to {@code onDone}, or the failure to {@code onError}. */
    private static <T> Runnable valueOf(Supplier<T> work, Consumer<T> onDone, Consumer<String> onError) {
        return () -> {
            try {
                T value = work.get();
                Platform.runLater(() -> onDone.accept(value));
            } catch (Exception e) {
                String msg = describe(e);
                Platform.runLater(() -> onError.accept(msg));
            } catch (VirtualMachineError e) {
                String msg = describeError(e);
                Platform.runLater(() -> onError.accept(msg));
                throw e;
            } catch (Throwable e) {
                // See resultOf: an Error must still reach the caller's error handler.
                String msg = describeError(e);
                Platform.runLater(() -> onError.accept(msg));
            }
        };
    }

    /** Names an Error by its type as well, since "null" or a bare class path says little. */
    private static String describeError(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    // ------------------------------------------------------------------
    // Is the application itself working on a repository right now?
    // ------------------------------------------------------------------

    /** Repository tasks running now, on every lane together. */
    private static final AtomicInteger REPOSITORY_WORK = new AtomicInteger();

    /** When the last repository task on any lane finished, in {@link System#nanoTime()} terms. */
    private static volatile long anyLaneIdleSince = System.nanoTime();

    private static void begin(RepositoryLane lane) {
        if (lane == null) return;
        lane.running.incrementAndGet();
        REPOSITORY_WORK.incrementAndGet();
    }

    private static void end(RepositoryLane lane) {
        if (lane == null) return;
        long now = System.nanoTime();
        if (lane.running.decrementAndGet() == 0) lane.idleSince = now;
        if (REPOSITORY_WORK.decrementAndGet() == 0) anyLaneIdleSince = now;
    }

    /**
     * Whether this application has work for {@code repository} queued or running, or
     * finished some within the last {@code quietMillis}.
     *
     * <p>For the file-system watcher: the application's own commits, checkouts and
     * status scans write to {@code .git} and the work tree like anyone else's, and a
     * watcher that could not tell them apart would refresh after every one of them. The
     * quiet period covers events the operating system delivers after the operation that
     * caused them has returned. Only this repository's work counts: another tab's commit
     * writes nothing here.
     */
    public static boolean repositoryWorkWithin(GitBackend repository, long quietMillis) {
        RepositoryLane lane = existingLane(repository);
        if (lane == null) return false;
        if (lane.running.get() > 0) return true;
        return System.nanoTime() - lane.idleSince < TimeUnit.MILLISECONDS.toNanos(quietMillis);
    }

    /**
     * {@link #repositoryWorkWithin}, for any repository at all - whether the application
     * as a whole has settled, as a start-up measurement wants to know.
     */
    public static boolean anyRepositoryWorkWithin(long quietMillis) {
        if (REPOSITORY_WORK.get() > 0) return true;
        return System.nanoTime() - anyLaneIdleSince < TimeUnit.MILLISECONDS.toNanos(quietMillis);
    }

    /** {@code getMessage()} is null for several JGit exceptions, so fall back to the type name. */
    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return msg;
    }

    /**
     * Stops every lane on the way out, without interrupting work already running.
     *
     * <h2>Why not shutdownNow</h2>
     * It was {@code shutdownNow()}, which interrupts every running worker - the one thing
     * {@link Task#cancel()} takes deliberate care never to do, for the reason the class
     * comment gives: killing a thread mid-index-write is how repositories get corrupted.
     * The exit path was doing what the cancel path forbids, and the window is small only
     * in the sense that a repository has to be mid-write when the user closes the window.
     *
     * <p>Graceful first, then one bounded wait shared by every lane, then force as a last
     * resort. The lanes are daemon threads, so the JVM never depends on this returning -
     * the wait exists to give a running git command a moment to finish, not to keep the
     * process alive.
     */
    public static void shutdown() {
        // Provider calls are HTTP requests with nothing on disk to protect: stopped outright.
        PROVIDER_POOL.shutdownNow();
        List<ExecutorService> lanes = new ArrayList<>();
        for (RepositoryLane lane : allLanes()) {
            lane.retire(null);
            lanes.add(lane.executor);
        }
        NETWORK_LANE.shutdown();
        lanes.add(NETWORK_LANE);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        for (ExecutorService lane : lanes) {
            awaitOrForce(lane, deadline);
        }
    }

    private static void awaitOrForce(ExecutorService lane, long deadline) {
        try {
            if (!lane.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
                lane.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lane.shutdownNow();
        }
    }
}
