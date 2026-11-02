package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The crash report's contents.
 *
 * <p>Two properties matter and both are easy to lose in a later edit: it has to carry
 * enough to place the bug, and it must not carry anything the user would not choose to
 * paste. The second is the one worth a test - a well-meaning "include the repository
 * path, it helps" would sail through review.
 */
class CrashReporterTest {

    private static Throwable thrown() {
        try {
            throw new IllegalStateException("the index was locked");
        } catch (IllegalStateException e) {
            return e;
        }
    }

    @Test
    void theReportCarriesWhatIsNeededToPlaceTheBug() {
        var report = CrashReporter.build(Thread.currentThread(), thrown());

        assertTrue(report.summary().contains("IllegalStateException"), report.summary());
        assertTrue(report.summary().contains("the index was locked"), report.summary());

        String details = report.details();
        assertTrue(details.contains("IllegalStateException"), details);
        assertTrue(details.contains("CrashReporterTest"), "the stack trace should be in it");
        assertTrue(details.contains(Thread.currentThread().getName()), details);
        assertTrue(details.contains(System.getProperty("java.version")), details);
        assertTrue(details.contains(System.getProperty("os.name")), details);
    }

    /**
     * A git client's crash report would otherwise be full of the most identifying thing
     * on a developer's machine. The report says so, so the user knows what they are
     * agreeing to.
     */
    @Test
    void theReportSaysWhatItLeavesOut() {
        var report = CrashReporter.build(Thread.currentThread(), thrown());

        assertTrue(report.details().contains("No repository path"), report.details());
    }

    @Test
    void anExceptionWithNoMessageStillProducesAReadableSummary() {
        var report = CrashReporter.build(Thread.currentThread(), new NullPointerException());

        assertEquals("NullPointerException", report.summary());
        assertFalse(report.details().isBlank());
    }

    /**
     * A handler that throws replaces one crash with another and loses the first, which
     * is the worst possible failure mode for this class.
     */
    @Test
    void aPresenterThatThrowsDoesNotEscape() {
        AtomicReference<Object> reached = new AtomicReference<>();
        CrashReporter.install(report -> {
            reached.set(report);
            throw new RuntimeException("the presenter itself failed");
        });
        try {
            assertDoesNotThrow(() -> CrashReporter.report(Thread.currentThread(), thrown()));
            assertNotNull(reached.get(), "the presenter should still have been called");
        } finally {
            // Leave no handler behind for the rest of the suite.
            CrashReporter.install(report -> { });
            Thread.setDefaultUncaughtExceptionHandler(null);
        }
    }
}
