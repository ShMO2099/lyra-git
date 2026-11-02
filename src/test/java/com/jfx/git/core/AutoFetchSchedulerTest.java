package com.jfx.git.core;

import com.jfx.git.ui.FxHarness;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutoFetchScheduler}'s own timing and lifecycle - whether it schedules,
 * reschedules and stops correctly - kept separate from proving a background fetch
 * cannot block on a credential prompt, which {@link CredentialsManagerTest} and the
 * {@code fetchAllSilently} tests in {@link GitServiceTest} and
 * {@link NativeGitBackendTest} already cover.
 *
 * <p>Every test here uses the package-private constructor with {@link
 * TimeUnit#MILLISECONDS} instead of the real {@link TimeUnit#MINUTES}: {@code
 * AppPreferences.autoFetchIntervalMinutes()} only ever holds 0 or a handful of whole
 * minutes, and waiting on a real one in a test suite is not a trade this makes. The
 * scheduling logic under test - {@code scheduleWithFixedDelay} with that number, start,
 * restart and stop - is identical either way; only how long the unit is differs.
 *
 * <p>Most tests here need a fetch that actually succeeds - {@code onFetched} only fires
 * on {@code GitResult.isOk()}, and a schedule that never lands a successful fetch would
 * leave every "did it fire" assertion indistinguishable from "did it even schedule".
 * {@link #openRepoWithRemote} sets up a real bare local repository as the remote, so
 * every scheduled fetch actually succeeds; {@link #onFetchedIsOnlyCalledWhenTheFetchSucceeds}
 * is the one test that deliberately uses a repository with no remote instead.
 */
class AutoFetchSchedulerTest {

    @BeforeAll
    static void startToolkit() {
        // GitTaskRunner.run delivers onDone via Platform.runLater, which throws with no
        // toolkit running - and since that call sits inside a submitted background
        // task, the exception is swallowed by the executor rather than failing the
        // test loudly. Every fetch's onFetched callback would silently never fire
        // without this.
        FxHarness.start();
    }

    @TempDir
    Path tempDir;

    private GitService service;
    private AutoFetchScheduler scheduler;
    private Path remoteDir;

    private void openRepoWithNoRemote() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
    }

    /** A real bare local repository as the remote, so a scheduled fetch actually succeeds. */
    private void openRepoWithRemote() throws Exception {
        openRepoWithNoRemote();
        remoteDir = Files.createTempDirectory("lyra-auto-fetch-remote");
        Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();

        Files.writeString(tempDir.resolve("a.txt"), "a", java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(service.stageFile("a.txt").isOk());
        assertTrue(service.commit("first", false).isOk());
        assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
        assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());
    }

    @AfterEach
    void tidyUp() throws Exception {
        if (scheduler != null) {
            scheduler.shutdown();
            drainAnyFetchAlreadyDispatched();
        }
        if (service != null) {
            service.close();
        }
        AppPreferences.setAutoFetchIntervalMinutes(0);
        if (remoteDir != null && Files.exists(remoteDir)) {
            try (var walk = Files.walk(remoteDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                        // Best-effort cleanup.
                    }
                });
            }
        }
    }

    /**
     * Waits for a fetch that was already handed off to finish writing.
     *
     * <p>{@link AutoFetchScheduler#shutdown()} stops the timer, which is all it claims to
     * do - a fetch it has already passed to {@link GitTaskRunner} runs on the repository
     * lane and carries on. Most of these tests return the moment they have seen the one
     * fetch they were waiting for, with the next one seconds away and possibly already
     * running, so that write can still be in progress when JUnit starts deleting
     * {@code @TempDir}. On Windows a file open for writing cannot be deleted, and the test
     * fails with an {@code AccessDeniedException} on {@code .git/logs/refs/remotes/...} -
     * a failure that says nothing about scheduling and appears only when the timing lines
     * up, which is the worst kind to leave in a suite.
     *
     * <p>The repository's lane is a single thread taking work in order, so a marker
     * submitted there now cannot run until every fetch submitted before it has finished.
     * Waiting for the marker is therefore an exact drain rather than another sleep.
     */
    private void drainAnyFetchAlreadyDispatched() throws Exception {
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        GitTaskRunner.run(service, () -> {
            drained.countDown();
            return GitResult.ok("drained");
        }, result -> { });
        assertTrue(drained.await(30, TimeUnit.SECONDS),
                "a background fetch was still running after the scheduler stopped");
    }

    /** Polls a condition rather than sleeping a fixed amount, so a slow CI box is not a flake source. */
    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met within " + timeoutMs + "ms");
    }

    @Test
    void applyPreferenceDoesNothingWhenAutoFetchIsOff() throws Exception {
        openRepoWithRemote();
        AtomicInteger fetches = new AtomicInteger();
        scheduler = new AutoFetchScheduler(service, when -> fetches.incrementAndGet(), TimeUnit.MILLISECONDS);
        AppPreferences.setAutoFetchIntervalMinutes(0);

        scheduler.applyPreference();
        Thread.sleep(150);

        assertEquals(0, fetches.get(), "no interval configured means no timer running at all");
    }

    @Test
    void aConfiguredIntervalFetchesRepeatedly() throws Exception {
        openRepoWithRemote();
        AtomicInteger fetches = new AtomicInteger();
        scheduler = new AutoFetchScheduler(service, when -> fetches.incrementAndGet(), TimeUnit.MILLISECONDS);
        AppPreferences.setAutoFetchIntervalMinutes(20); // 20ms in this test's unit

        scheduler.applyPreference();

        waitUntil(() -> fetches.get() >= 3, 8000);
    }

    @Test
    void onFetchedIsOnlyCalledWhenTheFetchSucceeds() throws Exception {
        openRepoWithNoRemote();
        // No remote configured, so every fetchAllSilently() call fails ("No remotes
        // configured") - onFetched must never fire for that, only for isOk().
        AtomicInteger successes = new AtomicInteger();
        scheduler = new AutoFetchScheduler(service, when -> successes.incrementAndGet(), TimeUnit.MILLISECONDS);
        AppPreferences.setAutoFetchIntervalMinutes(10);

        scheduler.applyPreference();
        Thread.sleep(200);

        assertEquals(0, successes.get(),
                "a repository with no remotes can never have a successful auto-fetch");
    }

    @Test
    void reapplyingTheSameIntervalDoesNotResetTheSchedule() throws Exception {
        openRepoWithRemote();
        AtomicInteger fetches = new AtomicInteger();
        scheduler = new AutoFetchScheduler(service, when -> fetches.incrementAndGet(), TimeUnit.MILLISECONDS);
        AppPreferences.setAutoFetchIntervalMinutes(15);

        scheduler.applyPreference();
        // Calling this repeatedly, as RepoTab.refreshAll() does on every refresh, must
        // not restart the countdown each time - if it did, ticks fast enough here would
        // keep the first fetch from ever landing.
        for (int i = 0; i < 20; i++) {
            scheduler.applyPreference();
            Thread.sleep(5);
        }

        waitUntil(() -> fetches.get() >= 1, 5000);
    }

    @Test
    void stopCancelsAPendingScheduleSoNoFetchEverRuns() throws Exception {
        openRepoWithRemote();
        AtomicInteger fetches = new AtomicInteger();
        scheduler = new AutoFetchScheduler(service, when -> fetches.incrementAndGet(), TimeUnit.MILLISECONDS);
        AppPreferences.setAutoFetchIntervalMinutes(200);

        scheduler.applyPreference();
        scheduler.stop();
        Thread.sleep(400); // past where the first tick would have fired if stop() failed

        assertEquals(0, fetches.get(), "stopping before the first tick must leave nothing scheduled");
    }

    @Test
    void turningAutoFetchBackOffStopsAnAlreadyRunningSchedule() throws Exception {
        openRepoWithRemote();
        AtomicInteger fetches = new AtomicInteger();
        scheduler = new AutoFetchScheduler(service, when -> fetches.incrementAndGet(), TimeUnit.MILLISECONDS);
        AppPreferences.setAutoFetchIntervalMinutes(15);
        scheduler.applyPreference();
        waitUntil(() -> fetches.get() >= 1, 5000);

        AppPreferences.setAutoFetchIntervalMinutes(0);
        scheduler.applyPreference();
        // scheduled.cancel(false) lets a tick already mid-flight when stop() ran
        // finish rather than interrupting it, so the count can still rise once more
        // shortly after this returns - that race is inherent to "cancel future work,
        // don't interrupt in-flight work" and is not what this is checking. Settling
        // first lets that one straggler land before the count this test compares
        // against is read, so what remains to assert is only "no further tick after
        // that" - the guarantee stop() is actually meant to give.
        Thread.sleep(100);
        int countAfterSettling = fetches.get();
        Thread.sleep(300); // several would-be ticks' worth if it were still running

        assertEquals(countAfterSettling, fetches.get(),
                "switching the preference back to off must stop an already-running schedule");
    }

    @Test
    void changingTheIntervalRestartsWithTheNewPeriod() throws Exception {
        openRepoWithRemote();
        AtomicInteger fetches = new AtomicInteger();
        scheduler = new AutoFetchScheduler(service, when -> fetches.incrementAndGet(), TimeUnit.MILLISECONDS);

        // A very long interval that would not fire within this test's patience...
        AppPreferences.setAutoFetchIntervalMinutes(10_000);
        scheduler.applyPreference();
        Thread.sleep(50);
        assertEquals(0, fetches.get(), "the long interval should not have fired yet");

        // ...changed to a short one must actually take effect, not be ignored because a
        // schedule already exists.
        AppPreferences.setAutoFetchIntervalMinutes(15);
        scheduler.applyPreference();

        waitUntil(() -> fetches.get() >= 1, 5000);
    }

    @Test
    void shutdownStopsSchedulingForGoodAndIsSafeToCallTwice() throws Exception {
        openRepoWithRemote();
        AtomicInteger fetches = new AtomicInteger();
        scheduler = new AutoFetchScheduler(service, when -> fetches.incrementAndGet(), TimeUnit.MILLISECONDS);
        AppPreferences.setAutoFetchIntervalMinutes(15);
        scheduler.applyPreference();
        waitUntil(() -> fetches.get() >= 1, 5000);

        scheduler.shutdown();
        // See turningAutoFetchBackOffStopsAnAlreadyRunningSchedule: a tick already
        // mid-flight when shutdownNow() ran can still land just after it returns.
        Thread.sleep(100);
        int countAfterSettling = fetches.get();
        Thread.sleep(300);

        assertEquals(countAfterSettling, fetches.get(), "no tick should fire after shutdown");
        scheduler.shutdown(); // must not throw
    }
}
