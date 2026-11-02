package com.jfx.git.core.libgit2;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitBackendFactory;
import com.jfx.git.core.NativeGitRunner;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Libgit2BackendTest {

    @TempDir
    Path tempDir;

    private Libgit2Backend backend;
    private File repoDir;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Libgit2Loader.isAvailable(),
                "Bundled libgit2 is not available: " + Libgit2Loader.unavailableReason());

        repoDir = tempDir.toFile();
        try (Git git = Git.init().setDirectory(repoDir).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }

        backend = new Libgit2Backend();
        assertTrue(backend.openRepository(repoDir));
    }

    private void writeFile(String relativePath, String content) throws IOException {
        File file = new File(repoDir, relativePath);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    @Test
    void openRepositoryIdentifiesWorkTreeAndGitDir() throws IOException {
        assertTrue(backend.isOpen());
        assertNotNull(backend.getWorkTree());
        assertNotNull(backend.getDirectory());
        // libgit2 reports a resolved, trailing-slash workdir, so compare canonically.
        assertEquals(repoDir.getCanonicalFile(), backend.getWorkTree().getCanonicalFile());
    }

    @Test
    void statusIsReadInProcess() throws Exception {
        writeFile("untracked.txt", "new file\n");
        writeFile("nested/deep.txt", "nested\n");

        var state = backend.getStagingState();
        assertTrue(state.unstaged().contains("untracked.txt"));
        assertTrue(state.unstaged().contains("nested/deep.txt"),
                "recurse-untracked-dirs should list files, not the directory");
        assertTrue(state.staged().isEmpty());
    }

    /**
     * Guards the bug where {@code git_index_open} (which takes a path) was called with a
     * repository pointer, and the two {@code invokeExact} calls that followed threw
     * {@link java.lang.invoke.WrongMethodTypeException} — all of it swallowed by a
     * {@code catch (Throwable)} that silently fell back to the CLI.
     */
    @Test
    void stagingUsesLibgit2WithoutFallingBack() throws Exception {
        writeFile("staged.txt", "content\n");

        var result = backend.stageFile("staged.txt");
        assertTrue(result.isOk(), () -> "stageFile failed: " + result.getMessage());

        var state = backend.getStagingState();
        assertTrue(state.staged().contains("staged.txt"));
        assertFalse(state.unstaged().contains("staged.txt"));
    }

    @Test
    void unstagingReturnsFileToTheWorkingTree() throws Exception {
        writeFile("base.txt", "base\n");
        assertTrue(backend.stageFile("base.txt").isOk());
        assertTrue(backend.commit("base", false).isOk());

        writeFile("base.txt", "changed\n");
        assertTrue(backend.stageFile("base.txt").isOk());
        assertTrue(backend.getStagingState().staged().contains("base.txt"));

        assertTrue(backend.unstageFile("base.txt").isOk());
        var state = backend.getStagingState();
        assertFalse(state.staged().contains("base.txt"));
        assertTrue(state.unstaged().contains("base.txt"));
    }

    @Test
    void unstagingWorksBeforeTheFirstCommit() throws Exception {
        writeFile("first.txt", "first\n");
        assertTrue(backend.stageFile("first.txt").isOk());
        assertTrue(backend.getStagingState().staged().contains("first.txt"));

        assertTrue(backend.unstageFile("first.txt").isOk());
        var state = backend.getStagingState();
        assertFalse(state.staged().contains("first.txt"));
        assertTrue(state.unstaged().contains("first.txt"));
    }

    @Test
    void currentBranchIsReadInProcess() throws Exception {
        writeFile("f.txt", "x\n");
        backend.stageFile("f.txt");
        backend.commit("initial", false);

        String branch = backend.getCurrentBranch();
        assertNotNull(branch);
        assertFalse(branch.isBlank());
        assertFalse(branch.startsWith("refs/"), "shorthand expected, got " + branch);
    }

    @Test
    void conflictsAppearInBothUnstagedAndConflicting() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled());
        NativeGitRunner runner = new NativeGitRunner(repoDir);

        writeFile("merge.txt", "base\n");
        backend.stageAll();
        backend.commit("base", false);

        runner.run("checkout", "-b", "side");
        writeFile("merge.txt", "side\n");
        runner.run("commit", "-am", "side");
        runner.run("checkout", "-");
        writeFile("merge.txt", "main\n");
        runner.run("commit", "-am", "main");
        runner.run("merge", "side");

        var state = backend.getStagingState();
        assertTrue(state.conflicting().contains("merge.txt"));
        assertTrue(state.unstaged().contains("merge.txt"),
                "a conflicted path must still be listed as needing work in the tree");
    }

    @Test
    void errorMessagesAreReadable(@TempDir Path outside) throws Exception {
        try (Libgit2Backend other = new Libgit2Backend()) {
            // Outside the fixture repository: opening searches upward now (E6), so a folder
            // inside it would open the fixture rather than fail.
            File notARepo = new File(outside.toFile(), "definitely-not-a-repo");
            assertTrue(notARepo.mkdirs());

            var failure = assertThrows(Exception.class, () -> other.openRepository(notARepo));
            String message = String.valueOf(failure.getMessage());
            assertFalse(message.contains("could not read libgit2 error"),
                    "error text should be the real libgit2 message, got: " + message);
        }
    }

    @Test
    void factorySelectsLibgit2Backend() throws Exception {
        GitBackend created = GitBackendFactory.createForEngine(GitBackendFactory.BackendEngine.LIBGIT2);
        created.openRepository(repoDir);
        assertTrue(created.isOpen());
        assertInstanceOf(Libgit2Backend.class, created);
        created.close();
    }
}
