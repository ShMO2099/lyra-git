package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The file-system watcher hears outside edits and nothing else worth ignoring.
 *
 * <p>Real directories and real file writes; the only thing stood in for is "is the
 * application itself working on the repository", which a test controls directly. The
 * negative cases wait well past the debounce, because "nothing happened" can only be
 * shown by waiting for it not to.
 */
class RepositoryWatcherTest {

    private static final long DEBOUNCE = 150;
    /** Long enough for the platform to deliver an event and the debounce to expire. */
    private static final long QUIET_WAIT_MS = 2500;

    @TempDir
    Path root;

    private final Semaphore fired = new Semaphore(0);
    private final AtomicInteger fireCount = new AtomicInteger();
    private final AtomicBoolean ownWork = new AtomicBoolean();
    private RepositoryWatcher watcher;

    @BeforeEach
    void setUp() throws Exception {
        Git.init().setDirectory(root.toFile()).call().close();
        Files.writeString(root.resolve(".gitignore"), "build/\n/dist\n");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.createDirectories(root.resolve("build/out"));
        watcher = RepositoryWatcher.start(root.toFile(), root.resolve(".git").toFile(), () -> {
            fireCount.incrementAndGet();
            fired.release();
        }, ownWork::get, DEBOUNCE);
        assumeTrue(watcher != null, "this platform cannot watch directories");
        // Registration runs on the watcher's own thread; an edit made before it has
        // finished is the focus check's business, not something these tests are about.
        assertTrue(watcher.awaitRegistration(10_000), "the watcher never finished registering");
        assertTrue(watcher.watchedDirectories() > 0, "registered something to watch");
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) watcher.close();
    }

    private boolean firesWithin(long millis) throws InterruptedException {
        return fired.tryAcquire(millis, TimeUnit.MILLISECONDS);
    }

    @Test
    void anEditInTheWorkTreeIsHeard() throws Exception {
        Files.writeString(root.resolve("src/Main.java"), "class Main {}");

        assertTrue(firesWithin(10_000), "an outside edit must ask the tab to look again");
    }

    @Test
    void aBurstOfEditsIsOneNotification() throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.writeString(root.resolve("src/f" + i + ".txt"), "x" + i);
        }
        assertTrue(firesWithin(10_000));
        Thread.sleep(QUIET_WAIT_MS);

        assertTrue(fireCount.get() <= 2, "a burst is debounced, got " + fireCount.get());
    }

    @Test
    void lockFileChurnIsIgnored() throws Exception {
        Path lock = root.resolve(".git/index.lock");
        Files.writeString(lock, "x");
        Files.delete(lock);
        Files.writeString(root.resolve(".git/ORIG_HEAD"), "0000000000000000000000000000000000000000\n");
        Files.writeString(root.resolve("src/x.lock"), "x");

        assertFalse(firesWithin(QUIET_WAIT_MS), "git's own lock and ORIG_HEAD churn must not refresh");
    }

    @Test
    void ignoredHeavyDirectoriesAreIgnored() throws Exception {
        Files.writeString(root.resolve("node_modules/pkg/index.js"), "x");
        Files.writeString(root.resolve("build/out/app.class"), "x");

        assertFalse(firesWithin(QUIET_WAIT_MS), "node_modules and .gitignore'd build/ must not refresh");
    }

    @Test
    void aRefMovingIsHeard() throws Exception {
        Files.createDirectories(root.resolve(".git/refs/heads"));
        Files.writeString(root.resolve(".git/refs/heads/feature"),
                "0000000000000000000000000000000000000001\n");

        assertTrue(firesWithin(10_000), "a branch created from a terminal must be noticed");
    }

    /** A namespace of refs that did not exist when watching began, then a branch in it. */
    @Test
    void aRefInANewNamespaceIsHeard() throws Exception {
        Files.createDirectories(root.resolve(".git/refs/heads/team"));
        assertTrue(firesWithin(10_000), "the new namespace is a change of its own");
        // The watcher registers a new directory before it schedules the notification, so
        // by now the namespace is watched; only the branch below can fire again.
        fired.drainPermits();
        Files.writeString(root.resolve(".git/refs/heads/team/login"),
                "0000000000000000000000000000000000000002\n");

        assertTrue(firesWithin(10_000), "a branch in the new namespace must be noticed too");
    }

    @Test
    void changesWhileTheApplicationIsWorkingAreItsOwn() throws Exception {
        ownWork.set(true);
        Files.writeString(root.resolve("src/ours.txt"), "written by our own commit");

        assertFalse(firesWithin(QUIET_WAIT_MS), "the application's own operation refreshes the tab itself");
    }

    @Test
    void relevanceIsDecidedByPath() {
        File git = root.resolve(".git").toFile();
        assertTrue(watcher.isRelevant(root.resolve(".git/HEAD")));
        assertTrue(watcher.isRelevant(root.resolve(".git/index")));
        assertTrue(watcher.isRelevant(root.resolve("README.md")));
        assertFalse(watcher.isRelevant(root.resolve(".git/objects/ab/cdef")));
        assertFalse(watcher.isRelevant(root.resolve(".git/logs/HEAD")));
        assertFalse(watcher.isRelevant(root.resolve(".git/FETCH_HEAD")));
        assertFalse(watcher.isRelevant(root.resolve("dist/bundle.js")));
        assertFalse(watcher.isRelevant(root.resolve("vendor/sub/.git/index")));
        assertTrue(git.isDirectory());
    }
}
