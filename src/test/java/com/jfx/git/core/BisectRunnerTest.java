package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.BisectStatus;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bisecting with a script rather than a person.
 *
 * <p>Driven against a real repository with a real regression in it: a file whose content
 * is a number, "broken" from a known commit onwards, and a shell command that greps for
 * it. That is the shape of every real bisect, and it means the exit-code contract is
 * exercised for what it is - the thing that decides good from bad - rather than mocked.
 */
class BisectRunnerTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private final List<String> commits = new ArrayList<>();

    static {
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    /** Ten commits; from the sixth onwards the marker file says "broken". */
    private static final int COMMITS = 10;
    private static final int FIRST_BAD = 5;

    @BeforeEach
    void setUp() throws Exception {
        commits.addAll(buildFixture(tempDir));
        service = new GitService();
        assertTrue(service.openRepository(tempDir.toFile()));
    }

    /** Ten commits, broken from {@link #FIRST_BAD} on. Returns their hashes, oldest first. */
    private static List<String> buildFixture(Path dir) throws Exception {
        List<String> hashes = new ArrayList<>();
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            for (int i = 0; i < COMMITS; i++) {
                Files.writeString(dir.resolve("state.txt"),
                        i >= FIRST_BAD ? "broken\n" : "fine\n", StandardCharsets.UTF_8);
                git.add().addFilepattern("state.txt").call();
                hashes.add(git.commit().setMessage("commit " + i).call().getName());
            }
        }
        return hashes;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            try {
                service.bisectReset();
            } catch (Exception ignored) {
                // The test's own assertion is the interesting failure, not this.
            }
            service.close();
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    /** A command that fails exactly on the commits where the marker says "broken". */
    private static String failsWhenBroken() {
        return isWindows()
                ? "findstr /C:broken state.txt >nul && exit /b 1 || exit /b 0"
                : "! grep -q broken state.txt";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private void startBisect() {
        assertTrue(service.bisectStart().isOk());
        service.bisectMarkBad(commits.get(COMMITS - 1));
        service.bisectMarkGood(commits.get(0));
    }

    @Test
    void aScriptFindsTheFirstBadCommitWithoutAnyoneClicking() {
        startBisect();
        List<BisectRunner.Step> seen = new ArrayList<>();

        BisectRunner.Outcome outcome = new BisectRunner(service).run(failsWhenBroken(), seen::add);

        assertTrue(outcome.isOk(), () -> "run did not finish: " + outcome.problem());
        assertTrue(outcome.status().done(),
                () -> "the session should have reached an answer: " + outcome.status().message());
        assertEquals(commits.get(FIRST_BAD), outcome.status().currentCommitHash(),
                "the answer should be the first commit whose content is broken");
        assertFalse(seen.isEmpty(), "each candidate tested should be reported as it happens");
        assertEquals(seen.size(), outcome.steps().size(),
                "the callback and the outcome should agree on what was done");
    }

    /**
     * Exit 125 is git's "cannot be tested" - a commit that will not build. It has to skip
     * rather than count as bad, or a broken build in the middle of the range silently
     * becomes the answer.
     */
    @Test
    void exit125SkipsTheCommitRatherThanMarkingItBad() {
        startBisect();
        List<BisectRunner.Step> seen = new ArrayList<>();

        // Always "cannot test": every candidate should be skipped, and none marked.
        new BisectRunner(service).run(isWindows() ? "exit /b 125" : "exit 125", seen::add);

        assertFalse(seen.isEmpty(), "candidates should still have been offered");
        assertTrue(seen.stream().allMatch(s -> s.verdict() == BisectRunner.Step.Verdict.SKIPPED),
                () -> "every step should be a skip: " + seen);
    }

    /**
     * O8. git bisect run aborts on an exit code above 127 - a signal, a shell that could
     * not run the command, cmd.exe's 9009 "not recognized". Counting it as bad walked the
     * range to a "first bad commit" no test had judged.
     */
    @Test
    void anExitAbove127StopsTheRunWithoutMarking() {
        startBisect();
        BisectStatus before = service.bisectStatus();
        List<BisectRunner.Step> seen = new ArrayList<>();

        BisectRunner.Outcome outcome = new BisectRunner(service)
                .run(isWindows() ? "exit /b 200" : "exit 200", seen::add);

        assertFalse(outcome.isOk(), "an error exit is a stop, not a verdict");
        assertTrue(outcome.problem().contains("200"), outcome.problem());
        assertTrue(seen.isEmpty(), () -> "nothing may be marked: " + seen);
        assertEquals(before.currentCommitHash(), service.bisectStatus().currentCommitHash(),
                "the session stays on the candidate it was testing");
    }

    @Test
    void aCommandThatAlwaysSucceedsMarksEverythingGood() {
        startBisect();
        List<BisectRunner.Step> seen = new ArrayList<>();

        new BisectRunner(service).run(isWindows() ? "exit /b 0" : "true", seen::add);

        assertFalse(seen.isEmpty());
        assertTrue(seen.stream().allMatch(s -> s.verdict() == BisectRunner.Step.Verdict.GOOD),
                () -> "every step should be good: " + seen);
    }

    @Test
    void runningWithNoSessionIsRefusedRatherThanStartingOne() {
        BisectRunner.Outcome outcome = new BisectRunner(service).run("true", step -> { });

        assertFalse(outcome.isOk());
        assertTrue(outcome.problem().contains("No bisect session"), outcome.problem());
        assertTrue(outcome.steps().isEmpty());
    }

    @Test
    void anEmptyCommandIsRefusedBeforeAnythingRuns() {
        startBisect();
        BisectRunner runner = new BisectRunner(service);

        assertNotNull(runner.validate(""));
        assertNotNull(runner.validate(null));
        assertNull(runner.validate("true"));

        BisectRunner.Outcome outcome = runner.run("   ", step -> { });
        assertFalse(outcome.isOk());
        assertTrue(outcome.steps().isEmpty());
    }

    /**
     * Cancelling mid-command must not be read as a verdict.
     *
     * <p>The cancel path used to return git's 125, which the loop treats as "cannot be
     * tested" - so stopping a run called {@code bisectSkip()} on the way out, checking
     * out a different commit. A command this class killed is not the command saying
     * anything about the commit, and the class promises to leave the session standing.
     *
     * <p>Driven through {@link GitTaskRunner}, which owns the cancel flag: the run is
     * submitted as a task and the task is cancelled while the command is still sleeping.
     *
     * <p>In a directory of its own rather than {@code @TempDir}'s, because this test
     * deliberately kills a process whose working directory is the repository - on Windows
     * the dying process holds that directory briefly, and {@code @TempDir} would fail the
     * test for a cleanup problem it created itself. Cleanup here is best-effort, which is
     * the honest handling.
     */
    @Test
    void cancellingMidCommandMarksNothing() throws Exception {
        Path own = Files.createTempDirectory("lyra-bisect-cancel");
        GitService isolated = new GitService();
        try {
            buildFixture(own);
            assertTrue(isolated.openRepository(own.toFile()));
            assertTrue(isolated.bisectStart().isOk());
            isolated.bisectMarkBad(isolated.getHeadHash());
            // Oldest commit: enough range that a candidate is offered.
            var page = isolated.getCommitGraphPage(COMMITS, GraphOrder.current());
            String oldest = page.rows().stream().filter(r -> r.getCommit() != null)
                    .reduce((a, b) -> b).orElseThrow().getCommit().getHash();
            isolated.bisectMarkGood(oldest);

            String headBefore = isolated.getHeadHash();
            List<BisectRunner.Step> seen = new ArrayList<>();
            String slow = isWindows() ? "ping -n 30 127.0.0.1 >nul" : "sleep 30";

            GitTaskRunner.Task task = GitTaskRunner.queryDetached(
                    () -> new BisectRunner(isolated).run(slow, seen::add),
                    result -> { }, error -> { });

            Thread.sleep(600);
            task.cancel();
            Thread.sleep(1500);

            assertTrue(seen.isEmpty(),
                    () -> "a killed command judged nothing, so no step should be recorded: " + seen);
            assertEquals(headBefore, isolated.getHeadHash(),
                    "cancelling must not check out a different commit");
            assertTrue(isolated.bisectStatus().active(),
                    "and must leave the session where it stands");
        } finally {
            try {
                isolated.bisectReset();
            } catch (Exception ignored) {
                // Best effort; the assertions above are what this test is about.
            }
            isolated.close();
            org.eclipse.jgit.lib.RepositoryCache.clear();
            deleteBestEffort(own);
        }
    }

    /** The killed process may still hold the directory; this is not worth failing over. */
    private static void deleteBestEffort(Path root) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Retried below, then given up on.
                    }
                });
            } catch (Exception ignored) {
                // Already gone, or unreadable; either way nothing more to do here.
            }
            if (!Files.exists(root)) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The session must be left usable, so the user can look at where it stopped. */
    @Test
    void theSessionIsStillTheresAfterARun() {
        startBisect();

        new BisectRunner(service).run(failsWhenBroken(), step -> { });

        BisectStatus status = service.bisectStatus();
        assertTrue(status.active(), "the run must not end the session behind the user's back");
    }
}
