package com.jfx.git.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Answers about one commit, kept: what is kept, what never is, and the size bound. */
class CommitCacheTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tempDir;

    @Test
    void aSecondAskIsAnsweredWithoutComputing() {
        CommitCache cache = new CommitCache(1 << 20);
        AtomicInteger computed = new AtomicInteger();

        cache.get("k", () -> { computed.incrementAndGet(); return "answer"; });
        String again = cache.get("k", () -> { computed.incrementAndGet(); return "other"; });

        assertEquals("answer", again);
        assertEquals(1, computed.get());
    }

    /** A failure must not become permanent: errors, empties and nulls are asked again. */
    @Test
    void failuresAreNeverKept() {
        CommitCache cache = new CommitCache(1 << 20);
        AtomicInteger computed = new AtomicInteger();
        byte[] error = "Error getting diff: boom".getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < 2; i++) {
            cache.get("error", () -> { computed.incrementAndGet(); return error; });
            cache.get("empty", () -> { computed.incrementAndGet(); return new byte[0]; });
            cache.get("null", () -> { computed.incrementAndGet(); return null; });
            cache.get("none", () -> { computed.incrementAndGet(); return List.of(); });
        }

        assertEquals(8, computed.get(), "each was computed both times");
    }

    @Test
    void theLeastRecentlyUsedGoFirstOnceTheBudgetIsSpent() {
        CommitCache cache = new CommitCache(4 * 1000);
        for (int i = 0; i < 10; i++) {
            int n = i;
            cache.get("k" + n, () -> new byte[900]);
        }
        AtomicInteger computed = new AtomicInteger();

        cache.get("k9", () -> { computed.incrementAndGet(); return new byte[900]; });
        cache.get("k0", () -> { computed.incrementAndGet(); return new byte[900]; });

        assertEquals(1, computed.get(), "the newest was kept, the oldest was not");
    }

    @Test
    void onlyFullHashesAreKeys() {
        assertTrue(CommitCache.isFullHash(HASH));
        assertFalse(CommitCache.isFullHash("0123456"), "an abbreviation can come to mean another commit");
        assertFalse(CommitCache.isFullHash("HEAD"));
        assertFalse(CommitCache.isFullHash("main"));
        assertFalse(CommitCache.isFullHash(null));
    }

    // ------------------------------------------------------------------
    // Through SwitchableGitBackend, against a real repository
    // ------------------------------------------------------------------

    private File repo() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(dir.toPath().resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").setSign(false).call();
            Files.writeString(dir.toPath().resolve("a.txt"), "two\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("second").setSign(false).call();
        }
        return dir;
    }

    /** An engine that counts how often it is asked each thing. */
    private static GitBackend counting(GitBackend real, List<String> calls) {
        return (GitBackend) Proxy.newProxyInstance(GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void aCommitsDiffIsReadOnceThenKept() throws Exception {
        File dir = repo();
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        GitService real = new GitService();
        try (SwitchableGitBackend backend = new SwitchableGitBackend(counting(real, calls))) {
            backend.openRepository(dir);
            String head = backend.getHeadHash();

            byte[] first = backend.getDiffBytesForFileInCommit(head, "a.txt", null, false);
            byte[] second = backend.getDiffBytesForFileInCommit(head, "a.txt", null, false);
            var changes = backend.getChangesInCommit(head);
            backend.getChangesInCommit(head);

            assertArrayEquals(first, second);
            assertEquals(1, calls.stream().filter("getDiffBytesForFileInCommit"::equals).count());
            assertEquals(1, calls.stream().filter("getChangesInCommit"::equals).count());
            changes.clear();   // a caller's copy, not the kept one
            assertEquals(1, backend.getChangesInCommit(head).size(), "the kept list was not touched");
        }
    }

    /** "HEAD" moves; only what is named by a full hash is kept. */
    @Test
    void aMovableNameIsAlwaysAskedAgain() throws Exception {
        File dir = repo();
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        try (SwitchableGitBackend backend = new SwitchableGitBackend(counting(new GitService(), calls))) {
            backend.openRepository(dir);

            backend.getChangesInCommit("HEAD");
            backend.getChangesInCommit("HEAD");

            assertEquals(2, calls.stream().filter("getChangesInCommit"::equals).count());
        }
    }

    /** Opening chooses the engine; until then nothing pretends one is there. */
    @Test
    void aDeferredBackendChoosesItsEngineWhenOpened() throws Exception {
        File dir = repo();
        try (SwitchableGitBackend backend = SwitchableGitBackend.deferred()) {
            assertNull(backend.currentEngine());
            assertFalse(backend.isOpen());
            assertNull(backend.getWorkTree());
            assertThrows(IllegalStateException.class, backend::getHeadHash);

            assertTrue(backend.openRepository(dir));

            assertNotNull(backend.currentEngine());
            assertTrue(backend.isOpen());
            assertEquals(40, backend.getHeadHash().length());
        }
    }
}
