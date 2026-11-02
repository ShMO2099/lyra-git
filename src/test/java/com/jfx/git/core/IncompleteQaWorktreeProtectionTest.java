package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Native Git guard behavior for branches and worktrees that still have live work. */
class IncompleteQaWorktreeProtectionTest {

    @TempDir
    Path tempDir;

    private Path repoPath;
    private NativeGitBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");
        repoPath = tempDir.resolve("repo");
        Files.createDirectories(repoPath);
        try (Git git = Git.init().setDirectory(repoPath.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Worktree QA");
            config.setString("user", null, "email", "worktree-qa@example.invalid");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(repoPath.resolve("tracked.txt"), "base\n");
            git.add().addFilepattern("tracked.txt").call();
            git.commit().setMessage("base").call();
            git.branchCreate().setName("feature").call();
        }
        backend = new NativeGitBackend();
        assertTrue(backend.openRepository(repoPath.toFile()));
    }

    @AfterEach
    void tearDown() {
        if (backend != null) backend.close();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    @Test
    void forceDeletingABranchCheckedOutInAnotherWorktreeIsRefused() throws Exception {
        Path linked = tempDir.resolve("linked-feature");
        assertTrue(backend.addWorktree(linked.toFile(), "feature", false).isOk());
        String featureTip = backend.getLocalBranches().stream()
                .filter(ref -> ref.shortName().equals("feature")).findFirst().orElseThrow().targetHash();

        var result = backend.deleteBranch("feature", true);

        assertFalse(result.isOk(), "even force cannot remove a branch checked out elsewhere");
        assertTrue(Files.isDirectory(linked));
        assertEquals("feature", new NativeGitRunner(linked.toFile()).run("branch", "--show-current").stdout().trim());
        assertEquals(featureTip, backend.getLocalBranches().stream()
                .filter(ref -> ref.shortName().equals("feature")).findFirst().orElseThrow().targetHash());
    }

    @Test
    void removingALockedOrDirtyWorktreeWithoutForcePreservesIt() throws Exception {
        Path linked = tempDir.resolve("linked-protected");
        assertTrue(backend.addWorktree(linked.toFile(), "feature", false).isOk());

        assertTrue(backend.setWorktreeLocked(linked.toFile(), true).isOk());
        var lockedRemoval = backend.removeWorktree(linked.toFile(), false);
        assertFalse(lockedRemoval.isOk(), "a locked worktree needs explicit force");
        assertTrue(Files.isDirectory(linked));
        assertTrue(backend.getWorktrees().stream().filter(tree -> samePath(tree.path().toPath(), linked))
                .findFirst().orElseThrow().locked());

        assertTrue(backend.setWorktreeLocked(linked.toFile(), false).isOk());
        Files.writeString(linked.resolve("uncommitted.txt"), "keep this work\n");
        var dirtyRemoval = backend.removeWorktree(linked.toFile(), false);
        assertFalse(dirtyRemoval.isOk(), "uncommitted work must not be removed implicitly");
        assertEquals("keep this work\n", Files.readString(linked.resolve("uncommitted.txt")));
        assertTrue(backend.getWorktrees().stream().anyMatch(tree -> samePath(tree.path().toPath(), linked)));
    }

    private boolean samePath(Path actual, Path expected) {
        return actual.toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize());
    }
}
