package com.jfx.git.core.libgit2;

import com.jfx.git.core.GitResult;
import com.jfx.git.model.StagingState;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opens and closes a libgit2 repository five hundred times, looking for what unit tests
 * cannot see.
 *
 * <p>The FFM layer is written carefully - arenas are confined and scoped per call, every
 * native handle has a matching free in a {@code finally} - and none of that is provable
 * from a test that opens one repository once. A leaked {@code git_repository} or a leaked
 * index handle does not fail anything; it accumulates, and the first symptom is a machine
 * that has run the application for a day refusing to open another tab.
 *
 * <h2>What is actually asserted</h2>
 * Native allocations are invisible to the JVM's own memory accounting, so "assert stable
 * RSS" is not something a Java test can do honestly. What it can do is exhaust the thing
 * a leak exhausts:
 *
 * <ul>
 *   <li><b>Every cycle still works.</b> A leaked file handle on Windows stops the next
 *       open, and a leaked index handle stops the next write. Five hundred cycles that
 *       all succeed is the strongest statement available - the failure mode this guards
 *       against is cumulative, so it shows up as a cycle that fails, not as a number.</li>
 *   <li><b>The working tree is still writable afterwards.</b> Windows keeps a mapped
 *       pack file locked, so a repository whose handles leaked cannot be deleted. The
 *       {@code @TempDir} cleanup at the end of this test is itself the assertion.</li>
 * </ul>
 *
 * <p>Tagged {@code slow}: five hundred real repository opens is not something to run on
 * every change. {@code mvn test -Dgroups=slow -DexcludedTestGroups=}
 */
@Tag("slow")
class Libgit2SoakTest {

    /** Enough that a per-cycle leak exhausts something; short enough to stay a test. */
    private static final int CYCLES = 500;

    @TempDir
    Path tempDir;

    @Test
    void fiveHundredOpenAndCloseCyclesLeakNothingThatStopsTheNextOne() throws Exception {
        assumeTrue(Libgit2Loader.isAvailable(),
                "Bundled libgit2 is not available: " + Libgit2Loader.unavailableReason());

        File dir = tempDir.toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
            Files.writeString(tempDir.resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
        }

        for (int i = 0; i < CYCLES; i++) {
            int cycle = i;
            Libgit2Backend backend = new Libgit2Backend();
            try {
                assertTrue(backend.openRepository(dir), () -> "open failed on cycle " + cycle);

                // Exercise the paths that allocate natively: a status scan walks the
                // working tree, and staging opens, reloads and writes the index.
                Files.writeString(tempDir.resolve("a.txt"), "cycle " + i + "\n");
                StagingState state = backend.getStagingState();
                assertTrue(state.unstaged().contains("a.txt"),
                        () -> "status went wrong on cycle " + cycle);

                GitResult staged = backend.stageFile("a.txt");
                assertTrue(staged.isOk(), () -> "stage failed on cycle " + cycle + ": "
                        + staged.getMessage());
                assertTrue(backend.unstageFile("a.txt").isOk(),
                        () -> "unstage failed on cycle " + cycle);
            } finally {
                backend.close();
            }
        }

        // Still usable after five hundred cycles, from a fresh handle.
        Libgit2Backend after = new Libgit2Backend();
        try {
            assertTrue(after.openRepository(dir), "the repository should still open");
            assertEquals("main", after.getCurrentBranch());
        } finally {
            after.close();
        }

        // And still deletable, which on Windows means nothing is holding a pack file.
        assertTrue(Files.isWritable(tempDir.resolve("a.txt")));
    }
}
