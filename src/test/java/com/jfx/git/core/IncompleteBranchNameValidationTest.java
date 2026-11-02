package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Invalid and duplicate branch names must leave both refs and HEAD intact. */
class IncompleteBranchNameValidationTest {

    @TempDir
    Path repositoryPath;

    private GitService service;

    @BeforeEach
    void openRepository() throws Exception {
        try (Git git = Git.init().setDirectory(repositoryPath.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "QA User");
            config.setString("user", null, "email", "qa@example.invalid");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(repositoryPath.resolve("tracked.txt"), "fixture\n");
            git.add().addFilepattern("tracked.txt").call();
            git.commit().setMessage("fixture base").call();
        }
        service = new GitService();
        service.openRepository(repositoryPath.toFile());
        assertTrue(service.createBranch("feature", null, false).isOk());
    }

    @AfterEach
    void closeRepository() {
        if (service != null) service.close();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private Set<String> branchNames() {
        return service.getLocalBranches().stream().map(ref -> ref.shortName()).collect(Collectors.toSet());
    }

    @Test
    void invalidAndDuplicateCreatesDoNotAddOrMoveARef() {
        String head = service.getHeadHash();
        Set<String> before = branchNames();

        for (String invalid : new String[]{"", "bad name", "bad..name"}) {
            GitResult result = service.createBranch(invalid, null, false);
            assertFalse(result.isOk(), "must reject branch name: " + invalid);
            assertEquals(before, branchNames(), "rejected create must preserve refs: " + invalid);
            assertEquals(head, service.getHeadHash(), "rejected create must preserve HEAD: " + invalid);
        }

        GitResult duplicate = service.createBranch("feature", null, false);
        assertFalse(duplicate.isOk(), "a duplicate branch must not replace the existing ref");
        assertEquals(before, branchNames());
        assertEquals(head, service.getHeadHash());
    }

    @Test
    void invalidAndDuplicateRenamesKeepTheSourceBranchAndHead() {
        String head = service.getHeadHash();
        Set<String> before = branchNames();

        for (String invalid : new String[]{"", "bad name", "bad..name", "main"}) {
            GitResult result = service.renameBranch("feature", invalid);
            assertFalse(result.isOk(), "must reject rename target: " + invalid);
            assertEquals(before, branchNames(), "rejected rename must preserve refs: " + invalid);
            assertEquals(head, service.getHeadHash(), "rejected rename must preserve HEAD: " + invalid);
            assertEquals("main", service.getCurrentBranch());
        }
    }
}
