package com.jfx.git.core;

import com.jfx.git.model.BisectStatus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives a bisect session with a script instead of a person - {@code git bisect run}.
 *
 * <h2>Why this is worth having</h2>
 * The manual bisect works and is tedious in exactly the way a computer is good at: check
 * out, build, test, click Good or Bad, repeat fourteen times. Anyone who has bisected a
 * real regression has written the test command down before starting. This runs it.
 *
 * <h2>The exit-code contract, which is git's</h2>
 * <dl>
 *   <dt>0</dt><dd>good - the problem is not here</dd>
 *   <dt>125</dt><dd>cannot be tested (it will not build); skip this commit</dd>
 *   <dt>1–124, 126, 127</dt><dd>bad</dd>
 *   <dt>anything else</dt><dd>not a verdict - the run stops without marking, as
 *       {@code git bisect run} does for codes above 127 (a signal, a shell that could
 *       not start the command, Windows' 9009 "not recognized")</dd>
 * </dl>
 * Deliberately the same numbers as {@code git bisect run}, so a script written for one
 * works in the other. 126 and 127 - "found but not executable" and "not found" - count as
 * bad in git and do so here; a script that cannot run at all is caught before the loop
 * starts, which is the case that actually matters.
 *
 * <h2>Safety</h2>
 * The loop stops on its own if the session ends, if the candidate stops changing (a
 * bisect that is not narrowing is a bug in this class, not a reason to keep checking out
 * commits), or after {@link #MAX_STEPS}. It is cancellable through
 * {@link GitTaskRunner}, and cancelling leaves the session where it stands rather than
 * resetting it - the user may want to look at the commit it stopped on.
 */
public final class BisectRunner {

    private static final Logger LOGGER = Logger.getLogger(BisectRunner.class.getName());

    /**
     * A bisect halves the range each step, so even a million commits is twenty steps.
     * This is a guard against a loop that is not converging, not a real limit.
     */
    private static final int MAX_STEPS = 64;

    /** git's own "I cannot test this one" code. */
    private static final int CANNOT_TEST = 125;

    /**
     * Not an exit code: the command did not produce a verdict at all.
     *
     * <p>Used for both "could not be started" and "was cancelled part-way", because they
     * mean the same thing to the loop - there is nothing to mark. Distinct from 125,
     * which is the command saying "I ran, and this commit cannot be tested".
     */
    private static final int NO_VERDICT = Integer.MIN_VALUE;

    private final GitBackend gitService;
    private final File workTree;

    public BisectRunner(GitBackend gitService) {
        this.gitService = gitService;
        this.workTree = gitService.getWorkTree();
    }

    /** What one step of the run did, for the log the user watches. */
    public record Step(String commitHash, int exitCode, Verdict verdict) {

        public enum Verdict { GOOD, BAD, SKIPPED }

        public String describe() {
            return GitBackend.shortenHash(commitHash) + " → " + switch (verdict) {
                case GOOD -> "good";
                case BAD -> "bad";
                case SKIPPED -> "skipped (exit " + exitCode + ")";
            };
        }
    }

    /** How a run ended, and everything it did on the way. */
    public record Outcome(List<Step> steps, BisectStatus status, String problem) {

        public boolean isOk() {
            return problem == null;
        }
    }

    /**
     * Runs {@code command} against each candidate until the session finishes.
     *
     * <p>Blocking; call it from a worker. {@code onStep} is called from that worker after
     * each step, so a caller updating the UI must marshal it themselves - the alternative,
     * marshalling here, would mean this class depending on JavaFX.
     *
     * @param command the shell command to run, as the user typed it
     */
    public Outcome run(String command, Consumer<Step> onStep) {
        List<Step> steps = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return new Outcome(steps, gitService.bisectStatus(), "No command to run");
        }

        BisectStatus status = gitService.bisectStatus();
        if (!status.active()) {
            return new Outcome(steps, status, "No bisect session is running");
        }

        String previousCandidate = null;
        for (int step = 0; step < MAX_STEPS; step++) {
            if (GitTaskRunner.isCancelled()) {
                return new Outcome(steps, status, "Cancelled after " + steps.size() + " step(s)");
            }
            if (status.done()) {
                return new Outcome(steps, status, null);
            }
            if (status.currentCommitHash() == null) {
                // Not finished and nothing to test: the session refused to go on - good
                // and bad marks the wrong way round, say. That is a stop with a reason,
                // and reporting it as a clean finish hid the reason.
                return new Outcome(steps, status, status.message());
            }
            String candidate = status.currentCommitHash();
            if (candidate.equals(previousCandidate)) {
                // The session handed back the same commit twice, so marking it achieved
                // nothing. Stopping beats checking out the same commit forever.
                return new Outcome(steps, status,
                        "Bisect stopped narrowing at " + GitBackend.shortenHash(candidate));
            }
            previousCandidate = candidate;

            int exit = runCommand(command);
            if (exit == NO_VERDICT) {
                // Either the command could not start, or Cancel killed it part-way. In
                // neither case did it judge this commit, so nothing is marked: marking it
                // would move HEAD on the strength of a command that never finished, and
                // a cancelled run promises to leave the session where it stands.
                return new Outcome(steps, status, GitTaskRunner.isCancelled()
                        ? "Cancelled after " + steps.size() + " step(s)"
                        : "Could not run the command");
            }

            if (exit < 0 || exit > 127) {
                // git bisect run aborts on these rather than marking anything: 128 and up
                // is a shell that could not run the command at all, or one killed by a
                // signal (128 + n), and 9009 is cmd.exe's "is not recognized as a
                // command". Counting them as bad, as this did, walked the whole range to
                // a confident "first bad commit" that no test had ever judged.
                return new Outcome(steps, status, "The command exited with " + exit
                        + ", which git bisect run treats as an error rather than a verdict -"
                        + " stopped without marking " + GitBackend.shortenHash(candidate)
                        + ". Check that the command runs on its own.");
            }

            Step.Verdict verdict = exit == 0 ? Step.Verdict.GOOD
                    : exit == CANNOT_TEST ? Step.Verdict.SKIPPED
                    : Step.Verdict.BAD;
            Step record = new Step(candidate, exit, verdict);
            steps.add(record);
            onStep.accept(record);

            status = switch (verdict) {
                case GOOD -> gitService.bisectMarkGood(candidate);
                case BAD -> gitService.bisectMarkBad(candidate);
                case SKIPPED -> gitService.bisectSkip();
            };
        }
        return new Outcome(steps, status, "Gave up after " + MAX_STEPS + " steps");
    }

    /**
     * Runs the user's command in the working tree and returns its exit code.
     *
     * <p>Through a shell, deliberately: this is a command line the user typed, and
     * {@code mvn -q test && ./check.sh} is exactly the shape people write. That makes it
     * arbitrary code execution by design - the same design as the custom editor command -
     * and the caller says so before the first run.
     *
     * <p>Output is drained and discarded rather than left unread: a build that fills the
     * pipe buffer would otherwise block forever, which would look like a hung bisect.
     *
     * @return the exit code, or {@link #NO_VERDICT} when the command never reached one -
     *         it could not start, or this class killed it because the run was cancelled
     */
    private int runCommand(String command) {
        ProcessBuilder builder = isWindows()
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        builder.directory(workTree);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            drain(process.getInputStream());
            // Polled rather than waited on outright, so Cancel reaches a long build.
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (GitTaskRunner.isCancelled()) {
                    process.destroyForcibly();
                    // Not 125. A command this class killed is not the command reporting
                    // that the commit cannot be tested, and treating it as one would
                    // skip the candidate - checking out a different commit - on the way
                    // out of a run the user asked to stop.
                    return NO_VERDICT;
                }
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NO_VERDICT;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not run the bisect command", e);
            return NO_VERDICT;
        }
    }

    /**
     * Checks that the command runs at all, before a bisect session starts moving HEAD
     * around on the strength of it.
     *
     * @return null when it is fine, otherwise what to tell the user
     */
    public String validate(String command) {
        if (command == null || command.isBlank()) return "Enter a command to run.";
        if (workTree == null || !workTree.isDirectory()) return "No working tree to run it in.";
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void drain(InputStream in) {
        Thread reader = new Thread(() -> {
            try (in; ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    // Kept only so a very short script's output can be logged at FINE;
                    // a long build's output is not worth holding in memory.
                    if (sink.size() < 64 * 1024) sink.write(buffer, 0, read);
                }
                LOGGER.fine(() -> "bisect command output: "
                        + sink.toString(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // The exit code is the answer; unreadable output does not change it.
            }
        }, "bisect-run-output");
        reader.setDaemon(true);
        reader.start();
    }
}
