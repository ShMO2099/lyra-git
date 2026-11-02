package com.jfx.git.core;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A real fetch on the native engine, watched: git is asked for progress, the stages
 * arrive, and the fetch still succeeds - its stderr, which the result is read from, no
 * worse for having had the progress taken out of it.
 */
class NativeFetchProgressTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @Test
    void aWatchedFetchReportsItsStagesAndStillSucceeds() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs git on PATH");
        Path origin = tempDir.resolve("origin");
        try (Git git = Git.init().setDirectory(origin.toFile()).setInitialBranch("main").call()) {
            for (int i = 0; i < 20; i++) {
                Files.writeString(origin.resolve("f" + i + ".txt"), "content " + i + "\n".repeat(i + 1));
                git.add().addFilepattern(".").call();
                git.commit().setMessage("commit " + i).setAuthor("T", "t@example.com")
                        .setCommitter("T", "t@example.com").setSign(false).call();
            }
        }
        Path work = tempDir.resolve("work");
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(origin.toUri().toString())).call();
        }

        NativeGitBackend backend = new NativeGitBackend();
        backend.openRepository(work.toFile());
        List<GitTaskRunner.Progress> seen = new CopyOnWriteArrayList<>();
        AtomicReference<GitResult> result = new AtomicReference<>();
        FxHarness.run(() -> {
            GitTaskRunner.claimNextTask(task -> task.onProgress(seen::add));
            GitTaskRunner.run(backend, backend::fetchAll, result::set);
        });
        RobotWait.untilOnFx("the fetch never finished", () -> result.get() != null);
        backend.close();

        assertTrue(result.get().isOk(), "the fetch still succeeds: " + result.get().getMessage());
        assertFalse(seen.isEmpty(), "and its stages were reported");
        assertTrue(seen.stream().anyMatch(p -> p.stage().contains("objects")), "stages: " + seen);
    }
}
