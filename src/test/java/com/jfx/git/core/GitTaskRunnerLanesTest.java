package com.jfx.git.core;

import com.jfx.git.ui.FxHarness;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One lane per repository: two repositories' work runs side by side, one repository's
 * stays in order, and a repository's lane closes with its tab.
 *
 * <p>With one lane for the whole process, a long operation in one tab - a fetch, a large
 * commit, a slow status - held every other tab's git work behind it. These pin the runner
 * itself, with work that touches no repository: the backends here only name lanes.
 */
class GitTaskRunnerLanesTest {

    @TempDir
    Path tempDir;

    /** Lanes held shut by a test; released after it whatever happened. */
    private final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startToolkit() {
        // Results are delivered through Platform.runLater.
        FxHarness.start();
    }

    @AfterEach
    void releaseEveryLane() {
        holds.forEach(CountDownLatch::countDown);
        assertTrue(GitTaskRunner.awaitRepositoryWork(10_000), "a lane was still busy after the test");
        ThreadGuard.strict(false);
        ThreadGuard.clearViolations();
    }

    /** Runs a task on {@code repository}'s lane that waits until the returned latch is counted down. */
    private CountDownLatch hold(GitBackend repository) throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        holds.add(release);
        GitTaskRunner.run(repository, () -> {
            running.countDown();
            await(release);
            return GitResult.ok("held");
        }, result -> { });
        assertTrue(running.await(10, TimeUnit.SECONDS), "the hold never started");
        return release;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Side by side, and in order
    // ------------------------------------------------------------------

    @Test
    void twoRepositoriesWorkRunsSideBySide() throws Exception {
        GitBackend first = new GitService();
        GitBackend second = new GitService();
        hold(first);

        CompletableFuture<String> other = new CompletableFuture<>();
        GitTaskRunner.query(second, () -> "read", other::complete, e -> other.completeExceptionally(new AssertionError(e)));

        assertEquals("read", other.get(10, TimeUnit.SECONDS),
                "the second repository's read waited behind the first repository's held work");
        assertEquals(0, GitTaskRunner.queued(second));
    }

    @Test
    void oneRepositorysWorkRunsOneAtATimeInTheOrderItWasAskedFor() throws Exception {
        GitBackend repository = new GitService();
        CountDownLatch release = hold(repository);
        List<Integer> order = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(40);
        for (int i = 0; i < 40; i++) {
            int index = i;
            Runnable step = () -> {
                mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                order.add(index);
                Thread.onSpinWait();
                inFlight.decrementAndGet();
            };
            // Both kinds of submission share the lane.
            if (i % 2 == 0) {
                GitTaskRunner.run(repository, () -> { step.run(); return GitResult.ok(); }, r -> done.countDown());
            } else {
                GitTaskRunner.query(repository, () -> { step.run(); return index; }, v -> done.countDown(),
                        e -> done.countDown());
            }
        }
        assertEquals(40, GitTaskRunner.queued(repository), "everything waits behind the hold");
        release.countDown();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 40; i++) expected.add(i);
        assertEquals(expected, order, "one repository's work runs in the order it was submitted");
        assertEquals(1, mostAtOnce.get(), "never two tasks of one repository at once");
    }

    @Test
    void aLaneThreadIsNamedForItsRepository() throws Exception {
        GitBackend repository = new GitService();
        GitTaskRunner.nameLane(repository, "u13-alpha");

        CompletableFuture<String> thread = new CompletableFuture<>();
        GitTaskRunner.query(repository, () -> Thread.currentThread().getName(), thread::complete,
                e -> thread.completeExceptionally(new AssertionError(e)));

        assertEquals("git-worker-u13-alpha", thread.get(10, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------
    // Waiting counts
    // ------------------------------------------------------------------

    @Test
    void waitingWorkIsCountedForItsOwnRepositoryOnly() throws Exception {
        GitBackend busy = new GitService();
        GitBackend idle = new GitService();
        List<Integer> busyAnnounced = new CopyOnWriteArrayList<>();
        List<Integer> idleAnnounced = new CopyOnWriteArrayList<>();
        GitTaskRunner.addQueueListener(busy, busyAnnounced::add);
        GitTaskRunner.addQueueListener(idle, idleAnnounced::add);
        int before = GitTaskRunner.queued();
        CountDownLatch release = hold(busy);

        GitTaskRunner.run(busy, GitResult::ok, r -> { });
        GitTaskRunner.run(busy, GitResult::ok, r -> { });

        assertEquals(2, GitTaskRunner.queued(busy));
        assertEquals(0, GitTaskRunner.queued(idle), "another repository's queue is not this one's");
        assertEquals(before + 2, GitTaskRunner.queued(), "the total still counts every lane");
        release.countDown();
        assertTrue(GitTaskRunner.awaitRepositoryWork(10_000));
        FxHarness.run(() -> { });
        assertTrue(busyAnnounced.contains(2), "the busy lane's listener heard 2 waiting: " + busyAnnounced);
        assertEquals(0, busyAnnounced.getLast().intValue(), "and then none: " + busyAnnounced);
        assertEquals(List.of(), idleAnnounced, "the idle lane's listener heard nothing of the busy one");
    }

    @Test
    void cancellingWaitingWorkUncountsItAndItNeverRuns() throws Exception {
        GitBackend repository = new GitService();
        CountDownLatch release = hold(repository);
        AtomicBoolean ran = new AtomicBoolean();

        GitTaskRunner.Task task = GitTaskRunner.run(repository, () -> {
            ran.set(true);
            return GitResult.ok();
        }, r -> ran.set(true));
        assertEquals(1, GitTaskRunner.queued(repository));
        task.cancel();

        assertEquals(0, GitTaskRunner.queued(repository));
        release.countDown();
        assertTrue(GitTaskRunner.awaitRepositoryWork(10_000));
        FxHarness.run(() -> { });
        assertFalse(ran.get(), "a task cancelled before it started never runs");
    }

    // ------------------------------------------------------------------
    // Told when a task will never run
    // ------------------------------------------------------------------

    @Test
    void aTaskCancelledBeforeItStartsSaysSo() throws Exception {
        GitBackend repository = new GitService();
        CountDownLatch release = hold(repository);
        CountDownLatch dropped = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();

        GitTaskRunner.Task task = GitTaskRunner.run(repository, () -> {
            ran.set(true);
            return GitResult.ok();
        }, r -> ran.set(true), queued -> queued.onDropped(dropped::countDown));
        task.cancel();

        assertTrue(dropped.await(10, TimeUnit.SECONDS), "whoever waited for its callback is told it is not coming");
        release.countDown();
        assertTrue(GitTaskRunner.awaitRepositoryWork(10_000));
        FxHarness.run(() -> { });
        assertFalse(ran.get());
    }

    @Test
    void aTaskSubmittedAfterItsRepositoryClosedSaysSo() throws Exception {
        GitBackend repository = new GitService();
        assertTrue(GitTaskRunner.closeRepository(repository, () -> { }, 10_000));
        CountDownLatch dropped = new CountDownLatch(1);

        GitTaskRunner.run(repository, GitResult::ok, r -> { }, queued -> queued.onDropped(dropped::countDown));

        assertTrue(dropped.await(10, TimeUnit.SECONDS));
    }

    @Test
    void aTaskThatRanIsNeverDropped() throws Exception {
        GitBackend repository = new GitService();
        AtomicBoolean dropped = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        GitTaskRunner.Task task = GitTaskRunner.run(repository, GitResult::ok, r -> done.countDown(),
                queued -> queued.onDropped(() -> dropped.set(true)));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        task.cancel();
        FxHarness.run(() -> { });
        FxHarness.run(() -> { });
        assertFalse(dropped.get(), "cancelling work that has finished drops nothing");
    }

    @Test
    void aListenerAddedAfterTheDropIsToldAtOnce() throws Exception {
        GitBackend repository = new GitService();
        assertTrue(GitTaskRunner.closeRepository(repository, () -> { }, 10_000));
        GitTaskRunner.Task task = GitTaskRunner.run(repository, GitResult::ok, r -> { });
        CountDownLatch dropped = new CountDownLatch(1);

        task.onDropped(dropped::countDown);

        assertTrue(dropped.await(10, TimeUnit.SECONDS));
    }

    /**
     * A task handed to its owner is not also offered to the busy said in the same pulse:
     * that busy is waiting for its own submission, and taking another's - another tab's,
     * on the one static slot - showed the wrong work's progress and cancelled the wrong
     * work.
     */
    @Test
    void aTaskHandedToItsOwnerIsNotTakenByAWaitingClaim() throws Exception {
        GitBackend repository = new GitService();
        List<GitTaskRunner.Task> claimed = new CopyOnWriteArrayList<>();
        List<GitTaskRunner.Task> owned = new CopyOnWriteArrayList<>();
        GitTaskRunner.Task[] plain = new GitTaskRunner.Task[1];

        FxHarness.run(() -> {
            GitTaskRunner.claimNextTask(claimed::add);
            GitTaskRunner.run(repository, GitResult::ok, r -> { }, owned::add);
            plain[0] = GitTaskRunner.run(repository, GitResult::ok, r -> { });
        });

        assertEquals(1, owned.size());
        assertEquals(List.of(plain[0]), claimed, "the claim waited for the next unowned submission");
        assertTrue(GitTaskRunner.awaitRepositoryWork(10_000));
    }

    // ------------------------------------------------------------------
    // Closing
    // ------------------------------------------------------------------

    @Test
    void closingRunsBehindWhatIsQueuedThenRefusesEverythingAfter() throws Exception {
        GitBackend repository = new GitService();
        CountDownLatch release = hold(repository);
        List<String> ran = new CopyOnWriteArrayList<>();
        GitTaskRunner.run(repository, () -> { ran.add("queued before"); return GitResult.ok(); }, r -> { });
        release.countDown();

        assertTrue(GitTaskRunner.closeRepository(repository, () -> ran.add("close"), 10_000));
        AtomicBoolean delivered = new AtomicBoolean();
        GitTaskRunner.Task late = GitTaskRunner.run(repository, () -> {
            ran.add("after close");
            return GitResult.ok();
        }, r -> delivered.set(true));
        GitTaskRunner.prefetch(repository, () -> ran.add("prefetch after close"));

        assertTrue(GitTaskRunner.awaitRepositoryWork(10_000));
        FxHarness.run(() -> { });
        assertEquals(List.of("queued before", "close"), ran,
                "the close ran last of what was queued, and nothing submitted after it ran");
        assertTrue(late.isCancelled(), "work for a closed repository comes back cancelled");
        assertFalse(delivered.get(), "and nobody is told a result for it");
        assertEquals(0, GitTaskRunner.queued(repository));
        assertTrue(GitTaskRunner.closeRepository(repository, () -> ran.add("second close"), 1_000),
                "a second close finds the repository already closed");
        assertEquals(List.of("queued before", "close"), ran, "and does not close it again");
    }

    @Test
    void closingWaitsOnlyBoundedForItsOwnLaneAndClosesWhenItFrees() throws Exception {
        GitBackend repository = new GitService();
        CountDownLatch release = hold(repository);
        AtomicBoolean closed = new AtomicBoolean();

        long started = System.nanoTime();
        assertFalse(GitTaskRunner.closeRepository(repository, () -> closed.set(true), 200),
                "the close reports that it could not run yet");
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "the wait was bounded");
        assertFalse(closed.get(), "nothing is closed under the work still running");

        release.countDown();
        assertTrue(GitTaskRunner.awaitRepositoryWork(10_000), "a draining lane is waited for too");
        assertTrue(closed.get(), "the close ran once the lane freed");
    }

    @Test
    void closingOneRepositoryDoesNotWaitForAnother() throws Exception {
        GitBackend closing = new GitService();
        GitBackend busy = new GitService();
        hold(busy);
        AtomicBoolean closed = new AtomicBoolean();

        assertTrue(GitTaskRunner.closeRepository(closing, () -> closed.set(true), 5_000),
                "closing a tab waited for another tab's work");
        assertTrue(closed.get());
    }

    // ------------------------------------------------------------------
    // Lane confinement
    // ------------------------------------------------------------------

    /** A tab's backend over a fresh repository, as RepoTab hands it out. */
    private SwitchableGitBackend openTabBackend(String name) throws Exception {
        Path dir = tempDir.resolve(name);
        try (var fixture = ThreadGuard.buildingFixture()) {
            Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call().close();
        }
        SwitchableGitBackend backend = new SwitchableGitBackend(new GitService());
        assertTrue(backend.openRepository(dir.toFile()));
        return backend;
    }

    @Test
    void anEngineCalledFromAnotherRepositorysLaneIsReported() throws Exception {
        SwitchableGitBackend alpha = openTabBackend("alpha");
        SwitchableGitBackend beta = openTabBackend("beta");
        try {
            ThreadGuard.clearViolations();
            CompletableFuture<String> own = new CompletableFuture<>();
            GitTaskRunner.query(beta, beta::getCurrentBranch, own::complete, e -> own.completeExceptionally(new AssertionError(e)));
            assertEquals("main", own.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(), ThreadGuard.violations(), "an engine on its own lane is the rule");

            CompletableFuture<String> crossed = new CompletableFuture<>();
            GitTaskRunner.query(alpha, beta::getCurrentBranch, crossed::complete, e -> crossed.completeExceptionally(new AssertionError(e)));
            crossed.get(10, TimeUnit.SECONDS);

            List<ThreadGuard.Violation> seen = ThreadGuard.violations();
            assertEquals(1, seen.size(), "beta's engine ran on alpha's lane: " + seen);
            assertEquals("GitBackend.getCurrentBranch", seen.getFirst().operation());
            assertTrue(seen.getFirst().where().startsWith("another repository's lane"), seen.getFirst().where());
            assertTrue(seen.getFirst().message().contains("Submit it naming the repository it touches"),
                    seen.getFirst().message());
        } finally {
            GitTaskRunner.closeRepository(alpha, alpha::close, 10_000);
            GitTaskRunner.closeRepository(beta, beta::close, 10_000);
        }
    }

    @Test
    void underStrictThreadingAnotherRepositorysLaneIsRefused() throws Exception {
        SwitchableGitBackend alpha = openTabBackend("alpha");
        SwitchableGitBackend beta = openTabBackend("beta");
        try {
            ThreadGuard.strict(true);
            CompletableFuture<String> failure = new CompletableFuture<>();
            GitTaskRunner.query(alpha, beta::getCurrentBranch,
                    branch -> failure.completeExceptionally(new AssertionError("ran: " + branch)),
                    failure::complete);

            assertTrue(failure.get(10, TimeUnit.SECONDS).contains("another repository's lane"));
        } finally {
            ThreadGuard.strict(false);
            GitTaskRunner.closeRepository(alpha, alpha::close, 10_000);
            GitTaskRunner.closeRepository(beta, beta::close, 10_000);
        }
    }
}
