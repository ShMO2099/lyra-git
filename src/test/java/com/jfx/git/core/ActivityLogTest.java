package com.jfx.git.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The log has to be trustworthy to be worth opening.
 *
 * <p>Its whole value is that the line it shows is what actually happened - so the cases
 * worth pinning are the ones where it could quietly say something else: an operation
 * still running reported as finished, a failure recorded as a success, a long session
 * growing without bound, and one repository's work appearing under another's name.
 */
class ActivityLogTest {

    @BeforeEach
    void clearTheLog() {
        // Process-global by design - it is a log - so each test starts from empty rather
        // than inheriting whatever the previous one recorded.
        ActivityLog.clear();
    }

    @Test
    void anOperationIsNotTimedUntilItFinishes() {
        var entry = ActivityLog.begin(ActivityLog.Scope.REPOSITORY, "demo", "Fetching");

        assertTrue(entry.isRunning(), "it has not come back yet");
        assertFalse(entry.isTimed(), "a running operation has no duration to report");
        assertEquals(ActivityLog.Outcome.RUNNING, entry.outcome());

        ActivityLog.finish(entry, ActivityLog.Outcome.OK, "Fetched 3 refs");

        assertFalse(entry.isRunning());
        assertTrue(entry.isTimed(), "a finished operation knows how long it took");
        assertTrue(entry.millis() >= 0);
        assertEquals("Fetched 3 refs", entry.detail());
    }

    @Test
    void finishingTwiceKeepsTheFirstAnswer() {
        var entry = ActivityLog.begin(ActivityLog.Scope.REPOSITORY, "demo", "Pushing");
        ActivityLog.finish(entry, ActivityLog.Outcome.OK, "Pushed");
        long first = entry.millis();

        // The status bar's failure paths can report twice; the second must not restart
        // the clock and turn a 40ms push into a two-second one.
        ActivityLog.finish(entry, ActivityLog.Outcome.FAILED, "Rejected");

        assertEquals(first, entry.millis());
        assertEquals(ActivityLog.Outcome.OK, entry.outcome());
    }

    @Test
    void aVerdictCanArriveAfterTheDuration() {
        // The status bar learns "it finished" and "it failed" in that order, from two
        // separate calls; revising has to change the verdict without touching the timing.
        var entry = ActivityLog.begin(ActivityLog.Scope.REPOSITORY, "demo", "Merging");
        ActivityLog.finish(entry, ActivityLog.Outcome.OK, null);
        long elapsed = entry.millis();

        ActivityLog.reviseOutcome(entry, ActivityLog.Outcome.FAILED, "Merge conflict in a.txt");

        assertEquals(ActivityLog.Outcome.FAILED, entry.outcome());
        assertEquals("Merge conflict in a.txt", entry.detail());
        assertEquals(elapsed, entry.millis(), "revising a verdict must not re-time the work");
    }

    @Test
    void nullEntriesAreTolerated() {
        // A cancelled operation may hold no entry at all; the log must not be the thing
        // that turns that into an exception on a worker thread.
        assertDoesNotThrow(() -> ActivityLog.finish(null, ActivityLog.Outcome.OK, "x"));
        assertDoesNotThrow(() -> ActivityLog.reviseOutcome(null, ActivityLog.Outcome.OK, "x"));
    }

    @Test
    void scopesAndRepositoriesAreKeptApart() {
        ActivityLog.note("Lyra started");
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "alpha", "Staged 2 files",
                ActivityLog.Outcome.OK);
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "beta", "Pulled", ActivityLog.Outcome.OK);

        List<ActivityLog.Entry> application =
                ActivityLog.entries(ActivityLog.Scope.APPLICATION, null);
        assertEquals(1, application.size());
        assertEquals("Lyra started", application.get(0).label());

        List<ActivityLog.Entry> alpha = ActivityLog.entries(ActivityLog.Scope.REPOSITORY, "alpha");
        assertEquals(1, alpha.size(), "beta's work must not show up under alpha");
        assertEquals("Staged 2 files", alpha.get(0).label());

        assertEquals(2, ActivityLog.entries(ActivityLog.Scope.REPOSITORY, null).size(),
                "a null repository means every repository");
        assertEquals(List.of("alpha", "beta"), ActivityLog.repositories());
    }

    @Test
    void entriesComeBackOldestFirst() {
        ActivityLog.note("first");
        ActivityLog.note("second");
        ActivityLog.note("third");

        assertEquals(List.of("first", "second", "third"),
                ActivityLog.entries().stream().map(ActivityLog.Entry::label).toList());
    }

    @Test
    void theLogIsBoundedAndDiscardsTheOldest() {
        for (int i = 0; i < ActivityLog.CAPACITY + 50; i++) {
            ActivityLog.note("entry " + i);
        }

        List<ActivityLog.Entry> entries = ActivityLog.entries();
        assertEquals(ActivityLog.CAPACITY, entries.size(),
                "a session left open all day must not grow without limit");
        assertEquals("entry 50", entries.get(0).label(), "the oldest go first");
        assertEquals("entry " + (ActivityLog.CAPACITY + 49),
                entries.get(entries.size() - 1).label());
    }

    @Test
    void aMultiLineMessageBecomesOneLine() {
        // Git errors name every affected file. One entry taking forty rows of a timeline
        // stops it being a timeline.
        var entry = ActivityLog.note(ActivityLog.Scope.REPOSITORY, "demo",
                "error: cannot checkout\n  a.txt\n  b.txt", ActivityLog.Outcome.FAILED);

        assertEquals("error: cannot checkout", entry.label());
    }

    @Test
    void averyLongMessageIsTrimmed() {
        var entry = ActivityLog.note("x".repeat(500));

        assertTrue(entry.label().length() <= 160, "was " + entry.label().length());
        assertTrue(entry.label().endsWith("…"), "the trim should be visible");
    }

    @Test
    void listenersFireOnEveryChangeAndStopWhenRemoved() {
        AtomicInteger fired = new AtomicInteger();
        Runnable unsubscribe = ActivityLog.addListener(fired::incrementAndGet);

        ActivityLog.note("one");
        int afterNote = fired.get();
        assertTrue(afterNote >= 1, "adding an entry is a change worth repainting for");

        var entry = ActivityLog.begin(ActivityLog.Scope.APPLICATION, null, "Cloning");
        ActivityLog.finish(entry, ActivityLog.Outcome.OK, null);
        assertTrue(fired.get() > afterNote, "finishing an operation changes what is displayed");

        unsubscribe.run();
        int afterUnsubscribe = fired.get();
        ActivityLog.note("two");
        assertEquals(afterUnsubscribe, fired.get(),
                "a closed viewer must stop being called, or it keeps its window alive");
    }

    @Test
    void aThrowingListenerDoesNotBreakLogging() {
        Runnable unsubscribe = ActivityLog.addListener(() -> {
            throw new IllegalStateException("a viewer mid-teardown");
        });
        try {
            assertDoesNotThrow(() -> ActivityLog.note("still recorded"));
            assertEquals(1, ActivityLog.entries().size());
        } finally {
            unsubscribe.run();
        }
    }
}
