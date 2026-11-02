package com.jfx.git.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Status letters and line counts for the files a commit touched.
 *
 * <p>The parsing has one genuinely fragile spot worth pinning down: git will not emit
 * {@code --numstat} and {@code --name-status} together. Given both it silently honours the
 * last one and drops the other, so the two outputs are read separately and merged, and a
 * regression here would look like every file reporting no counts rather than like a crash.
 */
class FileChangeTest {

    @TempDir
    Path tempDir;

    private NativeGitBackend backend;
    private NativeGitRunner runner;
    private File repoDir;

    @BeforeEach
    void setUp() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");

        repoDir = tempDir.toFile();
        runner = new NativeGitRunner(repoDir);
        if (!runner.run("init", "-b", "main").isOk()) {
            runner.run("init");
        }
        runner.run("config", "user.name", "Test User");
        runner.run("config", "user.email", "test@example.com");
        runner.run("config", "core.autocrlf", "false");

        backend = new NativeGitBackend();
        assertTrue(backend.openRepository(repoDir));
    }

    private void write(String path, String content) throws IOException {
        File file = new File(repoDir, path);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    private String commitAll(String message) {
        runner.run("add", "-A");
        runner.run("commit", "-m", message);
        return runner.run("rev-parse", "HEAD").trimmedStdout();
    }

    private Map<String, GitBackend.FileChange> byPath(String hash) {
        return backend.getChangesInCommit(hash).stream()
                .collect(Collectors.toMap(GitBackend.FileChange::path, c -> c));
    }

    @Test
    void reportsAddedFilesWithTheirLineCounts() throws IOException {
        write("src/app.txt", "one\ntwo\nthree\n");
        String hash = commitAll("add app");

        var changes = byPath(hash);
        assertEquals(1, changes.size());

        var change = changes.get("src/app.txt");
        assertNotNull(change, "the added file should be reported");
        assertEquals('A', change.status());
        assertEquals(3, change.added());
        assertEquals(0, change.deleted());
        assertTrue(change.hasCounts());
    }

    @Test
    void reportsModificationsWithBothSides() throws IOException {
        write("src/app.txt", "one\ntwo\nthree\n");
        commitAll("first");

        write("src/app.txt", "one\nTWO\nthree\nfour\n");
        String hash = commitAll("edit");

        var change = byPath(hash).get("src/app.txt");
        assertEquals('M', change.status());
        assertEquals(2, change.added());
        assertEquals(1, change.deleted());
    }

    @Test
    void reportsDeletions() throws IOException {
        write("gone.txt", "a\nb\n");
        commitAll("first");

        assertTrue(new File(repoDir, "gone.txt").delete());
        String hash = commitAll("remove it");

        var change = byPath(hash).get("gone.txt");
        assertEquals('D', change.status());
        assertEquals(0, change.added());
        assertEquals(2, change.deleted());
    }

    /**
     * A commit touching several files must line every count up with the right path.
     *
     * <p>The two git calls are merged on path rather than by position, so this would still
     * pass if they were zipped by index — but it fails loudly if a path is dropped or a
     * count lands on its neighbour.
     */
    @Test
    void keepsCountsWithTheirOwnPaths() throws IOException {
        write("a.txt", "1\n");
        write("b.txt", "1\n2\n");
        write("c.txt", "1\n2\n3\n");
        String hash = commitAll("three files");

        var changes = byPath(hash);
        assertEquals(3, changes.size());
        assertEquals(1, changes.get("a.txt").added());
        assertEquals(2, changes.get("b.txt").added());
        assertEquals(3, changes.get("c.txt").added());
    }

    /** A path with a space survives, which is what {@code -z} is there for. */
    @Test
    void handlesPathsWithSpaces() throws IOException {
        write("some dir/my file.txt", "x\n");
        String hash = commitAll("spaced");

        var change = byPath(hash).get("some dir/my file.txt");
        assertNotNull(change, "a path containing spaces should not be split or quoted away");
        assertEquals('A', change.status());
    }

    /**
     * Binary files report {@code -} rather than a number.
     *
     * <p>They must arrive as "count unknown" and not as zero: a zero would render as
     * "+0 −0" beside a file that certainly did change.
     */
    @Test
    void marksBinaryFilesAsHavingNoCounts() throws IOException {
        byte[] bytes = new byte[64];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        Files.write(new File(repoDir, "blob.bin").toPath(), bytes);
        String hash = commitAll("binary");

        var change = byPath(hash).get("blob.bin");
        assertNotNull(change);
        assertFalse(change.hasCounts(), "a binary file has no line counts to report");
        assertEquals(GitBackend.FileChange.UNKNOWN_COUNT, change.added());
    }

    /**
     * The JGit engine answers for itself rather than taking the interface default.
     *
     * <h2>What this test used to say</h2>
     * It asserted the opposite: that JGit inherited the default, so every file in every
     * commit came back marked {@code M} with no counts. That was true, and it was a bug -
     * the file list of a commit showed a modification letter beside a file the commit had
     * created. The test had pinned the degraded path as if it were the contract, which is
     * why nothing ever failed while the default engine shipped without an implementation.
     *
     * <p>The fallback still exists and is still right for an engine that cannot answer;
     * it is simply no longer the path this one takes.
     */
    @Test
    void jgitReportsRealStatusAndCounts() throws IOException {
        write("kept.txt", "a\nb\n");
        String hash = commitAll("first");

        try (GitService jgit = new GitService()) {
            assertTrue(jgit.openRepository(repoDir));
            List<GitBackend.FileChange> changes = jgit.getChangesInCommit(hash);

            assertEquals(List.of("kept.txt"), changes.stream().map(GitBackend.FileChange::path).toList());
            GitBackend.FileChange change = changes.get(0);
            assertEquals('A', change.status(),
                    "the first commit creates kept.txt, so it is an add - not the "
                            + "modification the interface default would have called it");
            assertTrue(change.hasCounts(), "a text file's line counts are knowable");
            assertEquals(2, change.added(), "both lines are new");
            assertEquals(0, change.deleted());
        }
    }
}
