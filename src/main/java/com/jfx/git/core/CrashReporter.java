package com.jfx.git.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What to tell the user when something throws where nobody was catching.
 *
 * <h2>Why this is not telemetry</h2>
 * A crash reporter usually means "we send it to a server". This one sends nothing,
 * anywhere, ever - there is no network code in it, and there is no setting to turn one
 * on. It builds a report and puts it on the clipboard when the user asks. Reporting the
 * crash is then the user's action, to a destination of their choosing, with the contents
 * in front of them first.
 *
 * <p>That is not a smaller version of telemetry. It is what makes the report usable at
 * all for this application's users: a bug report about a git client necessarily contains
 * branch names, paths and remote URLs, which are exactly the things people cannot paste
 * into someone else's server. Showing them the text and letting them edit it is the only
 * honest way to ask.
 *
 * <h2>What is in the report</h2>
 * The stack trace, the thread, and the versions of everything that could plausibly matter
 * - this application, the JVM, JavaFX, the OS, and which git engine is selected. Not the
 * repository path, not the branch, not the remote: a stack trace is usually enough to
 * place a bug, and a path is the single most identifying thing on a developer's machine.
 * Anything more specific the user can add themselves, knowing they are adding it.
 */
public final class CrashReporter {

    private static final Logger LOGGER = Logger.getLogger(CrashReporter.class.getName());

    /** Set once at startup; see {@link #install}. */
    private static volatile java.util.function.Consumer<Report> presenter;

    private CrashReporter() {
    }

    /**
     * A crash, formatted and ready to be read.
     *
     * @param summary  one line for a dialog header
     * @param details  the full report, which is what reaches the clipboard
     */
    public record Report(String summary, String details) {
    }

    /**
     * Routes uncaught exceptions to {@code onCrash}, on every thread including the FX
     * Application Thread.
     *
     * <p>JavaFX installs its own handler that prints to stderr and carries on, which is
     * why an exception in an event handler currently leaves the user with a UI that has
     * silently stopped doing something and no way to find out what. Replacing the default
     * handler catches both that and anything on a worker.
     *
     * @param onCrash called on whichever thread crashed; a UI presenter must marshal
     */
    public static void install(java.util.function.Consumer<Report> onCrash) {
        presenter = onCrash;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> report(thread, error));
    }

    /**
     * Builds a report and hands it to whatever {@link #install} registered.
     *
     * <p>Guarded end to end: an exception handler that throws replaces one crash with
     * another and loses the first, so the fallback is a log line and nothing else.
     */
    public static void report(Thread thread, Throwable error) {
        try {
            Report report = build(thread, error);
            LOGGER.log(Level.SEVERE, "Uncaught exception on " + thread.getName(), error);
            var target = presenter;
            if (target != null) {
                target.accept(report);
            }
        } catch (Throwable failedToReport) {
            // Nothing useful is left to do; at least do not crash the handler.
            LOGGER.log(Level.SEVERE, "Could not build a crash report", failedToReport);
        }
    }

    static Report build(Thread thread, Throwable error) {
        String summary = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());

        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));

        String details = String.join(System.lineSeparator(),
                "Lyra crash report",
                "Time:      " + Instant.now(),
                "Thread:    " + thread.getName(),
                "App:       " + version(),
                "Java:      " + System.getProperty("java.version")
                        + " (" + System.getProperty("java.vendor") + ")",
                "JavaFX:    " + System.getProperty("javafx.runtime.version", "unknown"),
                "OS:        " + System.getProperty("os.name") + " "
                        + System.getProperty("os.version") + " / " + System.getProperty("os.arch"),
                "Engine:    " + engine(),
                "",
                trace.toString().stripTrailing(),
                "",
                "No repository path, branch name or remote URL is included. Add anything"
                        + " else you are happy to share before sending this.");
        return new Report(summary, details);
    }

    /** The application's own version, or a placeholder when running from a build tree. */
    private static String version() {
        return AppVersion.current();
    }

    /** Which engine is selected - the single most useful thing after the trace itself. */
    private static String engine() {
        try {
            return String.valueOf(GitBackendFactory.getEnginePreference());
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
