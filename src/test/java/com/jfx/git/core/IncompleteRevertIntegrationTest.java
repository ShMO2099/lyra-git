package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reverting history older than HEAD and selecting a merge mainline preserve the other side's work. */
class IncompleteRevertIntegrationTest {

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
        }
        service = new GitService();
        service.openRepository(repositoryPath.toFile());
    }

    @AfterEach
    void closeRepository() {
        if (service != null) service.close();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private String commit(String path, String content, String message) throws Exception {
        Files.writeString(repositoryPath.resolve(path), content);
        assertTrue(service.stageFile(path).isOk());
        assertTrue(service.commit(message, false).isOk());
        return service.getHeadHash();
    }

    private RevCommit head() throws Exception {
        try (Git git = Git.open(repositoryPath.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD"));
        }
    }

    @Test
    void revertingAnOlderCommitPreservesLaterIndependentWork() throws Exception {
        commit("base.txt", "base\n", "base");
        String target = commit("older.txt", "older contribution\n", "older change");
        String beforeRevert = commit("later.txt", "later contribution\n", "later change");

        GitResult result = service.revert(target);

        assertTrue(result.isOk(), result::getMessage);
        RevCommit reverted = head();
        assertEquals(beforeRevert, reverted.getParent(0).getName(), "revert adds a child commit");
        assertEquals("later contribution\n", Files.readString(repositoryPath.resolve("later.txt")));
        assertFalse(Files.exists(repositoryPath.resolve("older.txt")), "only the older patch is reversed");
        assertEquals("older change", service.getCommitMessage(target).lines().findFirst().orElseThrow(),
                "the original commit stays available in history");
    }

    @Test
    void revertingAMergeAgainstItsFirstParentRemovesOnlyTheMergedContribution() throws Exception {
        commit("base.txt", "base\n", "base");
        assertTrue(service.createBranch("feature", null, true).isOk());
        commit("feature.txt", "feature contribution\n", "feature contribution");
        assertTrue(service.checkoutBranch("main").isOk());
        commit("main.txt", "main contribution\n", "main contribution");
        assertTrue(new RepositoryCommands(service).merge("feature", true).isOk());
        String merge = service.getHeadHash();
        assertEquals(2, head().getParentCount());

        GitResult result = service.revert(merge, 1);

        assertTrue(result.isOk(), result::getMessage);
        assertEquals(merge, head().getParent(0).getName(), "reverting preserves the merge as a parent");
        assertTrue(Files.exists(repositoryPath.resolve("main.txt")), "first-parent work remains");
        assertFalse(Files.exists(repositoryPath.resolve("feature.txt")), "the merge's second-parent work is undone");
    }
}
