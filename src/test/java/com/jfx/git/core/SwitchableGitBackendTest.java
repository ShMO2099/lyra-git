package com.jfx.git.core;

import com.jfx.git.model.BisectStatus;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Swapping the engine out from under live callers.
 *
 * <p>{@link SwitchableGitBackend} exists so a repository tab can change engine without
 * being rebuilt, which means the swap lands while a dozen collaborators hold the same
 * reference and workers are mid-call. The class reasons about that in comments - the
 * delegate is volatile, the replacement is opened before the old one is closed, a bisect
 * session lives inside one engine instance - and none of it was tested.
 */
class SwitchableGitBackendTest {

    @TempDir
    Path tempDir;

    private SwitchableGitBackend backend;
    private final List<String> commits = new ArrayList<>();

    static {
        // Windows will not delete a memory-mapped pack file, and @TempDir has to.
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            for (int i = 0; i < 8; i++) {
                Files.writeString(tempDir.resolve("f.txt"), "v" + i + "\n");
                git.add().addFilepattern("f.txt").call();
                commits.add(git.commit().setMessage("commit " + i).call().getName());
            }
        }
        backend = new SwitchableGitBackend(new GitService());
        assertTrue(backend.openRepository(tempDir.toFile()));
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            try {
                backend.close();
            } catch (Exception ignored) {
                // A test that already failed should report its own reason.
            }
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void assumeNativeGit() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");
    }

    @Test
    void switchingReopensAtTheSameWorkingTree() throws Exception {
        assumeNativeGit();
        File before = backend.getWorkTree();

        GitResult result = backend.switchTo(GitBackendFactory.BackendEngine.NATIVE_CLI);

        assertTrue(result.isOk(), result.getMessage());
        assertTrue(backend.currentEngine() instanceof NativeGitBackend);
        assertEquals(before.getCanonicalFile(), backend.getWorkTree().getCanonicalFile());
    }

    /**
     * The replacement is opened before the old engine is closed, so a switch that cannot
     * open leaves the tab working on what it already had rather than in a closed state.
     */
    @Test
    void aSwitchThatCannotOpenLeavesTheOldEngineWorking() throws Exception {
        GitBackend before = backend.currentEngine();
        // Delete the repository out from under it: the replacement cannot open, the
        // engine in hand is still the one that was working a moment ago.
        Path git = tempDir.resolve(".git");
        Path parked = tempDir.getParent().resolve("parked-git");
        Files.move(git, parked);
        try {
            GitResult result = backend.switchTo(GitBackendFactory.BackendEngine.NATIVE_CLI);
            assertFalse(result.isOk(), "there is no repository to reopen");
            assertSame(before, backend.currentEngine(), "the working engine must be kept");
        } finally {
            Files.move(parked, git);
        }
    }

    /**
     * A switch must not close an engine under a call that is still running on it.
     *
     * <p>Git work cannot be in flight then: it runs on the repository's lane, and so does
     * the switch. The editor's file I/O does not - a save is made from the FX thread - so
     * a switch can land part-way through one, and used to close the engine there and then:
     * the save carried on against a closed engine and failed. Held still inside the old
     * engine here, so the switch lands mid-call every time rather than now and then.
     */
    @Test
    void aCallOffTheLaneKeepsItsEngineOpenThroughASwitch() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch carryOn = new CountDownLatch(1);
        GitService first = new GitService() {
            @Override
            public byte[] getWorkingTreeBytes(String path) {
                inside.countDown();
                try {
                    carryOn.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.getWorkingTreeBytes(path);
            }
        };
        backend.close();
        backend = new SwitchableGitBackend(first);
        assertTrue(backend.openRepository(tempDir.toFile()));

        ExecutorService editor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> read = editor.submit(() -> backend.getWorkingTreeBytes("f.txt"));
            assertTrue(inside.await(10, TimeUnit.SECONDS), "the read should have reached the engine");

            assertTrue(backend.switchTo(GitBackendFactory.BackendEngine.JGIT).isOk());
            assertNotSame(first, backend.currentEngine());
            assertTrue(first.isOpen(), "a call is still running on the engine it replaced");

            carryOn.countDown();
            assertEquals("v7\n", new String(read.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            assertFalse(first.isOpen(), "closed once the last call on it returned");
        } finally {
            carryOn.countDown();
            editor.shutdownNow();
        }
    }

    /**
     * The same under load: the calls that skip the lane, from four threads, while the
     * engine is swapped twenty times - enough to catch a hold that lets the count reach
     * zero under someone.
     *
     * <p>Git reads are deliberately not among them. Four threads reading through one
     * engine at once is nothing any engine promises (see GitTaskRunner), and a git read on
     * the lane never overlaps a switch on the same lane.
     */
    @Test
    void editorReadsKeepWorkingWhileTheEngineIsSwappedUnderThem() throws Exception {
        assumeNativeGit();
        int readers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger switches = new AtomicInteger();
        Running running = new Running();

        try {
            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        while (running.on) {
                            assertTrue(backend.isOpen());
                            assertNotNull(backend.getWorkTree());
                            assertEquals("v7\n", backend.getWorkingTreeContent("f.txt"));
                            reads.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                });
            }

            start.countDown();
            for (int i = 0; i < 20; i++) {
                GitBackendFactory.BackendEngine next = i % 2 == 0
                        ? GitBackendFactory.BackendEngine.NATIVE_CLI
                        : GitBackendFactory.BackendEngine.JGIT;
                GitResult result = backend.switchTo(next);
                assertTrue(result.isOk(), result.getMessage());
                switches.incrementAndGet();
            }
        } finally {
            running.on = false;
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "readers should stop");
        }

        assertNull(failure.get(), () -> "a read failed during a switch: " + failure.get());
        assertEquals(20, switches.get());
        assertTrue(reads.get() > 0, "the readers should actually have read something");
    }

    /**
     * Which engine an instance is, so a caller can avoid switching to the one it has.
     *
     * <p>{@code RepoTab.applyEngineOverride} used to switch whenever a stored override
     * named an available engine, including the one the tab was built with - a full
     * open-close-open cycle on every launch of such a repository, for no change.
     */
    @Test
    void theCurrentEngineIsIdentifiable() {
        assertEquals(GitBackendFactory.BackendEngine.JGIT,
                GitBackendFactory.engineOf(backend),
                "a SwitchableGitBackend reports the engine inside it, not itself");

        assumeNativeGit();
        assertTrue(backend.switchTo(GitBackendFactory.BackendEngine.NATIVE_CLI).isOk());

        assertEquals(GitBackendFactory.BackendEngine.NATIVE_CLI,
                GitBackendFactory.engineOf(backend));
    }

    /**
     * O16. A bisect session used to live only in one engine's fields, so switching engines
     * mid-bisect abandoned it and stranded HEAD on a detached candidate with no "End
     * bisect" anywhere. The session is on disk now, and the new engine carries on with it
     * - including ending it and returning to the branch it started on.
     */
    @Test
    void aBisectSessionSurvivesAnEngineSwitch() {
        assumeNativeGit();
        assertTrue(backend.bisectStart().isOk());
        backend.bisectMarkBad(commits.get(commits.size() - 1));
        BisectStatus before = backend.bisectMarkGood(commits.get(0));
        assertTrue(before.active(), "the session should be running on the first engine");

        assertTrue(backend.switchTo(GitBackendFactory.BackendEngine.NATIVE_CLI).isOk());

        BisectStatus after = backend.bisectStatus();
        assertTrue(after.active(), "the new engine picks the session up");
        assertEquals(before.currentCommitHash(), after.currentCommitHash());
        assertTrue(backend.bisectReset().isOk());
        assertEquals("main", backend.getCurrentBranch(), "and returns to where it began");
    }

    /** A mutable flag the reader threads can see; simpler here than an AtomicBoolean. */
    private static final class Running {
        volatile boolean on = true;
    }
}
