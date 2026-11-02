package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The "Speed up this repository" opt-in: what it sets, and that turning it off undoes it. */
class RepositoryTuningTest {

    @TempDir
    Path tempDir;

    private File repo;
    private NativeGitRunner git;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs git");
        repo = tempDir.resolve("repo").toFile();
        assertTrue(repo.mkdirs());
        git = new NativeGitRunner(repo);
        assertTrue(git.run("init", "-q").isOk());
        assertTrue(git.run("config", "user.name", "Test User").isOk());
        assertTrue(git.run("config", "user.email", "test@example.com").isOk());
        assertTrue(git.run("config", "commit.gpgsign", "false").isOk());
        Files.writeString(new File(repo, "a.txt").toPath(), "one\n");
        assertTrue(git.run("add", "a.txt").isOk());
        assertTrue(git.run("commit", "-q", "-m", "first").isOk());
    }

    /** The monitor's daemon must not outlive the test and hold the folder open. */
    @AfterEach
    void stopTheMonitor() {
        if (git != null) git.run("fsmonitor--daemon", "stop");
    }

    @Test
    void turningItOnSetsGitsCachesAndWritesACommitGraph() {
        GitResult result = RepositoryTuning.enable(repo);

        assertTrue(result.isOk(), result.getMessage());
        var state = RepositoryTuning.read(repo);
        assertTrue(state.enabled(), state.toString());
        assertTrue(state.untrackedCache());
        assertTrue(state.commitGraph());
        assertEquals(RepositoryTuning.fileSystemMonitorSupported(), state.fileSystemMonitor(),
                "the monitor only where this git has it");
        File info = new File(repo, ".git/objects/info");
        assertTrue(new File(info, "commit-graph").isFile() || new File(info, "commit-graphs").isDirectory(),
                "a commit-graph was written");
        // With changed-path filters, which is what speeds up a file's history.
        var verify = git.run("commit-graph", "verify");
        assertTrue(verify.isOk(), verify.stderr());
    }

    @Test
    void turningItOffRemovesTheSettings() {
        assertTrue(RepositoryTuning.enable(repo).isOk());

        GitResult result = RepositoryTuning.disable(repo);

        assertTrue(result.isOk(), result.getMessage());
        var state = RepositoryTuning.read(repo);
        assertFalse(state.fileSystemMonitor());
        assertFalse(state.untrackedCache());
        assertFalse(state.commitGraph());
        assertFalse(state.enabled());
    }

    /** Off is off: turning off a repository that never had it on is not an error. */
    @Test
    void turningOffWhatWasNeverOnIsFine() {
        assertTrue(RepositoryTuning.disable(repo).isOk());
    }

    /** status still answers correctly with the caches on - the point is speed, not change. */
    @Test
    void statusIsTheSameWithTheCachesOn() throws Exception {
        Files.writeString(new File(repo, "a.txt").toPath(), "two\n");
        Files.writeString(new File(repo, "new.txt").toPath(), "new\n");
        NativeGitBackend backend = new NativeGitBackend();
        try {
            backend.openRepository(repo);
            var before = backend.getStagingState();
            assertTrue(RepositoryTuning.enable(repo).isOk());

            var after = backend.getStagingState();

            assertEquals(before.unstaged(), after.unstaged());
            assertEquals(java.util.List.of("a.txt", "new.txt"), after.unstaged());
            // The graph's dirty check asks in the same mode once the repository is tuned.
            assertTrue(backend.hasUncommittedChanges());
            Files.delete(new File(repo, "new.txt").toPath());
            Files.writeString(new File(repo, "a.txt").toPath(), "one\n");
            assertFalse(backend.hasUncommittedChanges(), "clean again, and seen as clean");
        } finally {
            backend.close();
        }
    }
}
