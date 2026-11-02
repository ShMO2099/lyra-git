package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies local Git LFS clean-filter pointer conversion without a remote endpoint. */
class IncompleteQaLfsPointerTest {
    @TempDir Path repository;

    @Test void stagingAnLfsTrackedFileStoresAPointerAndKeepsRealWorktreeBytes() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "native Git is required for its LFS clean filter");
        assumeTrue(NativeGitRunner.isGitLfsInstalled(), "Git LFS must be installed for pointer conversion");
        byte[] content = "synthetic local large payload\n".repeat(64).getBytes(StandardCharsets.UTF_8);
        try (var fixture = ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Synthetic QA User");
            config.setString("user", null, "email", "qa-user@example.test");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }

        NativeGitBackend backend = new NativeGitBackend();
        try {
            assertTrue(backend.openRepository(repository.toFile()));
            assertTrue(backend.lfsInstall().isOk(), "install the repository-local LFS filter");
            assertTrue(backend.lfsTrack("*.bin").isOk(), "write a local tracking pattern");
            Files.write(repository.resolve("asset.bin"), content);
            assertTrue(backend.stageFile("asset.bin").isOk());

            byte[] staged = backend.getStagedBytes("asset.bin");
            String pointer = new String(staged, StandardCharsets.UTF_8);
            String oid = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            assertTrue(pointer.startsWith("version https://git-lfs.github.com/spec/v1\n"), pointer);
            assertTrue(pointer.contains("oid sha256:" + oid + "\n"), pointer);
            assertTrue(pointer.contains("size " + content.length + "\n"), pointer);
            assertArrayEquals(content, Files.readAllBytes(repository.resolve("asset.bin")),
                    "the worktree keeps the original bytes while the index stores the pointer");
            assertTrue(backend.lfsTrackedPatterns().contains("*.bin"));
        } finally {
            backend.close();
        }
    }
}
