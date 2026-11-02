package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The waiting helper the robot tests rely on, held to what it promises. */
class RobotWaitTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    /**
     * The sparse-checkout suite's own poll failed this way: its condition threw while
     * windows were changing, and the poll treated the throw as the answer. A condition that
     * throws is "not yet".
     */
    @Test
    void aConditionThatThrowsBeforeSucceedingIsWaitedOut() {
        AtomicInteger calls = new AtomicInteger();

        RobotWait.until("never settled", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IndexOutOfBoundsException("windows still changing");
            }
            return true;
        });

        assertEquals(3, calls.get());
    }

    @Test
    void theSameHoldsOnTheFxThread() {
        AtomicInteger calls = new AtomicInteger();

        RobotWait.untilOnFx("never settled", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("node not there yet");
            }
            return true;
        });

        assertEquals(3, calls.get());
    }

    @Test
    void aConditionThatNeverHoldsFailsNamingWhatItWaitedFor() {
        AssertionError failure = assertThrows(AssertionError.class, () ->
                RobotWait.until("the worktree was never created",
                        () -> false, Duration.ofMillis(200)));

        assertTrue(failure.getMessage().startsWith("the worktree was never created"),
                failure.getMessage());
    }

    /** A timeout should say what the condition last complained about, not just that it gave up. */
    @Test
    void aTimeoutCarriesTheLastFailureAsItsCause() {
        AssertionError failure = assertThrows(AssertionError.class, () ->
                RobotWait.until("no dialog", () -> {
                    throw new IllegalStateException("still loading");
                }, Duration.ofMillis(200)));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    /**
     * The drain has to wait for work on the repository lanes, which is where every git
     * operation runs. It used to post its marker to the network lane - a different thread -
     * and so returned immediately while repository work was still going.
     */
    @Test
    void drainingWaitsForWorkAlreadyOnTheRepositoryLane() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        GitTaskRunner.run(new GitService(), () -> {
            started.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
            return GitResult.ok();
        }, result -> { });
        assertTrue(started.await(5, TimeUnit.SECONDS), "precondition: the work is running");

        RobotWait.drainGitWork();

        assertTrue(finished.get(), "the drain returned while repository work was still running");
    }

    /**
     * An operation's callback queues a refresh - behind a single drain marker. The drain has
     * to wait for that follow-up too, or teardown closes the backend under the refresh.
     */
    @Test
    void drainingAlsoWaitsForWorkACallbackQueuesAfterward() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean followUpFinished = new AtomicBoolean();
        GitBackend repository = new GitService();
        GitTaskRunner.run(repository, () -> {
            started.countDown();
            return GitResult.ok();
        }, result -> GitTaskRunner.run(repository, () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            followUpFinished.set(true);
            return GitResult.ok();
        }, followUp -> { }));
        assertTrue(started.await(5, TimeUnit.SECONDS), "precondition: the work is running");

        RobotWait.drainGitWork();

        assertTrue(followUpFinished.get(), "the drain returned before the callback's follow-up ran");
    }

    /**
     * Each open repository has a lane of its own, so "everything git is doing" is spread
     * over several - and a callback on one can start work on a lane that did not exist when
     * the drain began. The drain waits for all of them.
     */
    @Test
    void drainingWaitsForEveryRepositorysLaneIncludingOneStartedMeanwhile() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        AtomicBoolean firstFinished = new AtomicBoolean();
        AtomicBoolean secondFinished = new AtomicBoolean();
        AtomicBoolean laterFinished = new AtomicBoolean();
        GitBackend later = new GitService();
        GitTaskRunner.run(new GitService(), () -> {
            started.countDown();
            pause(300);
            firstFinished.set(true);
            return GitResult.ok();
        }, result -> { });
        GitTaskRunner.run(new GitService(), () -> {
            started.countDown();
            pause(600);
            secondFinished.set(true);
            // A lane nothing had used until now, started while the drain is waiting.
            GitTaskRunner.run(later, () -> {
                pause(300);
                laterFinished.set(true);
                return GitResult.ok();
            }, nested -> { });
            return GitResult.ok();
        }, result -> { });
        assertTrue(started.await(5, TimeUnit.SECONDS), "precondition: both lanes are running");

        RobotWait.drainGitWork();

        assertTrue(firstFinished.get() && secondFinished.get(), "the drain returned while a lane was running");
        assertTrue(laterFinished.get(), "the drain returned before the lane started meanwhile had finished");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
