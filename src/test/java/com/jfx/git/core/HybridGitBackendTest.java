package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HybridGitBackendTest {

    @TempDir
    Path tempDir;

    private HybridGitBackend backend;
    private File repoDir;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tempDir.toFile();

        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init().setDirectory(repoDir).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }

        backend = new HybridGitBackend();
        assertTrue(backend.openRepository(repoDir));
    }

    private void writeFile(String relativePath, String content) throws IOException {
        File file = new File(repoDir, relativePath);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    @Test
    void hybridBackendIsOpenAndDelegatesStaging() throws Exception {
        assertTrue(backend.isOpen());
        assertNotNull(backend.getWorkTree());

        writeFile("hybrid_test.txt", "hybrid content\n");
        var state = backend.getStagingState();
        assertEquals(1, state.unstaged().size());
        assertTrue(state.unstaged().contains("hybrid_test.txt"));

        assertTrue(backend.stageFile("hybrid_test.txt").isOk());
        var state2 = backend.getStagingState();
        assertEquals(1, state2.staged().size());

        var commitRes = backend.commit("Hybrid initial commit", false);
        assertTrue(commitRes.isOk());

        var graph = backend.getCommitGraphPage(10, GraphOrder.current());
        assertFalse(graph.rows().isEmpty());
    }

    @Test
    void factoryCreatesValidBackend() throws Exception {
        GitBackend created = GitBackendFactory.create(repoDir);
        assertTrue(created.isOpen());
        assertEquals(repoDir.getAbsolutePath(), created.getWorkTree().getAbsolutePath());
        created.close();
    }
}
