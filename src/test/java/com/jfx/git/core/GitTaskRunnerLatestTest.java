package com.jfx.git.core;

import com.jfx.git.ui.FxHarness;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Latest-wins queries and prefetch on a repository's lane. */
class GitTaskRunnerLatestTest {

    /** Names this test's lane; the work below never touches it. */
    private final GitBackend repository = new GitService();

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    /** Holds the lane until released, so what is queued behind it stays waiting. */
    private static CountDownLatch blockTheLane(GitBackend repository) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        FxHarness.run(() -> GitTaskRunner.run(repository, () -> {
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GitResult.ok();
        }, result -> { }));
        assertTrue(running.await(10, TimeUnit.SECONDS));
        return release;
    }

    @Test
    void aNewerRequestDropsTheOneStillWaiting() throws Exception {
        CountDownLatch release = blockTheLane(repository);
        List<String> ran = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        FxHarness.run(() -> {
            GitTaskRunner.queryLatest(repository, "diff", () -> { ran.add("first"); return 1; }, v -> { }, e -> { });
            GitTaskRunner.queryLatest(repository, "diff", () -> { ran.add("second"); return 2; },
                    v -> done.countDown(), e -> { });
            GitTaskRunner.queryLatest(repository, "other", () -> { ran.add("unrelated"); return 3; }, v -> { }, e -> { });
        });
        release.countDown();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        com.jfx.git.ui.RobotWait.drainGitWork();
        assertEquals(List.of("second", "unrelated"), ran,
                "the superseded request never ran; a different key was not affected");
    }

    @Test
    void theSameKeyOnAnotherRepositoryIsAnotherRequest() throws Exception {
        GitBackend other = new GitService();
        CountDownLatch release = blockTheLane(repository);
        CountDownLatch releaseOther = blockTheLane(other);
        List<String> ran = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);

        FxHarness.run(() -> {
            GitTaskRunner.queryLatest(repository, "diff", () -> { ran.add("here"); return 1; },
                    v -> done.countDown(), e -> { });
            GitTaskRunner.queryLatest(other, "diff", () -> { ran.add("there"); return 2; },
                    v -> done.countDown(), e -> { });
        });
        release.countDown();
        releaseOther.countDown();

        assertTrue(done.await(10, TimeUnit.SECONDS),
                "a panel's request in one tab must not drop the same panel's request in another: " + ran);
        assertEquals(List.of("here", "there"), ran.stream().sorted().toList());
    }

    @Test
    void aPrefetchNeverRunsAheadOfWorkThatIsWaiting() throws Exception {
        CountDownLatch release = blockTheLane(repository);
        List<String> ran = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        GitTaskRunner.prefetch(repository, () -> ran.add("prefetch"));
        FxHarness.run(() -> GitTaskRunner.query(repository, () -> { ran.add("asked for"); return 1; },
                v -> done.countDown(), e -> { }));
        release.countDown();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("asked for"), ran,
                "real work was waiting when the prefetch reached the front, so it was dropped");
    }

    @Test
    void aPrefetchOnAnIdleLaneRuns() throws Exception {
        com.jfx.git.ui.RobotWait.drainGitWork();
        CountDownLatch ran = new CountDownLatch(1);

        GitTaskRunner.prefetch(repository, ran::countDown);

        assertTrue(ran.await(10, TimeUnit.SECONDS));
    }

    @Test
    void aPrefetchIsNotHeldBackByAnotherRepositorysQueue() throws Exception {
        GitBackend busy = new GitService();
        CountDownLatch release = blockTheLane(busy);
        try {
            FxHarness.run(() -> GitTaskRunner.query(busy, () -> 1, v -> { }, e -> { }));
            CountDownLatch ran = new CountDownLatch(1);

            GitTaskRunner.prefetch(repository, ran::countDown);

            assertTrue(ran.await(10, TimeUnit.SECONDS),
                    "another tab's waiting work is not waiting on this lane");
        } finally {
            release.countDown();
        }
    }
}
