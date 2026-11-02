package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises remote push/fetch URLs and multi-remote prune against local bare repos. */
class IncompleteQaRemoteUrlsAndPruneIntegrationTest {
    @TempDir Path temp;

    @Test
    void pushUrlPublishesElsewhereWhileFetchUrlRemainsTheSource() throws Exception {
        Path work = temp.resolve("work");
        Path fetchRepo = bare("fetch.git");
        Path pushRepo = bare("publish.git");
        GitService service = initialized(work);
        try {
            assertTrue(service.addRemote("origin", fetchRepo.toString()).isOk());
            try (Git git = Git.open(work.toFile())) {
                var config = git.getRepository().getConfig();
                config.setStringList("remote", "origin", "pushurl", List.of(pushRepo.toString()));
                config.save();
            }
            commit(service, work, "local.txt", "local commit\n");
            String head = service.getHeadHash();

            assertTrue(new RepositoryCommands(service).push("origin", false, false, false).isOk());
            assertEquals(head, ref(pushRepo, "refs/heads/main"),
                    "publish must honor the configured push URL");
            assertNull(readRef(fetchRepo, "refs/heads/main"),
                    "push must not silently publish to the fetch URL as well");

            // A branch created only at the fetch endpoint proves Fetch still reads that URL.
            try (Git git = Git.open(work.toFile())) {
                git.branchCreate().setName("fetch-only").call();
                git.push().setRemote(fetchRepo.toString())
                        .setRefSpecs(new RefSpec("refs/heads/fetch-only:refs/heads/fetch-only")).call();
            }
            assertTrue(service.fetchAll().isOk());
            assertNotNull(readRef(work, "refs/remotes/origin/fetch-only"));
            assertNull(readRef(pushRepo, "refs/heads/fetch-only"));
        } finally {
            service.close();
            RepositoryCache.clear();
        }
    }

    @Test
    void fetchAllUpdatesEachRemoteAndPrunesOnlyTheDeletedTrackingBranch() throws Exception {
        Path work = temp.resolve("work");
        Path origin = bare("origin.git");
        Path backup = bare("backup.git");
        GitService service = initialized(work);
        try {
            commit(service, work, "base.txt", "base\n");
            assertTrue(service.addRemote("origin", origin.toString()).isOk());
            assertTrue(service.addRemote("backup", backup.toString()).isOk());
            try (Git git = Git.open(work.toFile())) {
                git.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
                git.branchCreate().setName("retired").call();
                git.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/retired:refs/heads/retired")).call();
                git.branchCreate().setName("backup-only").call();
                git.push().setRemote("backup").setRefSpecs(new RefSpec("refs/heads/backup-only:refs/heads/backup-only")).call();
                git.checkout().setName("main").call();
                git.branchDelete().setBranchNames("retired", "backup-only").call();
            }

            // Install a tracking ref for the soon-to-be-deleted branch, then delete it upstream.
            assertTrue(service.fetchAll().isOk());
            assertNotNull(readRef(work, "refs/remotes/origin/retired"));
            try (Git git = Git.open(origin.toFile())) {
                git.branchDelete().setBranchNames("retired").setForce(true).call();
            }
            // Keep a remote-only update in backup so this also proves all configured remotes run.
            try (Git git = Git.open(work.toFile())) {
                git.branchCreate().setName("backup-update").call();
                git.checkout().setName("backup-update").call();
                Files.writeString(work.resolve("backup.txt"), "from backup\n");
                git.add().addFilepattern("backup.txt").call();
                git.commit().setMessage("backup update").call();
                git.push().setRemote("backup").setRefSpecs(new RefSpec("refs/heads/backup-update:refs/heads/backup-update")).call();
                git.checkout().setName("main").call();
            }
            String headBefore = service.getHeadHash();
            String mainFileBefore = Files.readString(work.resolve("base.txt"));

            assertTrue(service.fetchAll().isOk());

            assertNull(readRef(work, "refs/remotes/origin/retired"), "deleted origin branch should be pruned");
            assertNotNull(readRef(work, "refs/remotes/backup/backup-only"), "backup namespace should be fetched");
            assertNotNull(readRef(work, "refs/remotes/backup/backup-update"), "second fetch should update backup refs");
            assertEquals(headBefore, service.getHeadHash(), "fetch must leave the checked out branch unchanged");
            assertEquals(mainFileBefore, Files.readString(work.resolve("base.txt")));
            assertFalse(Files.exists(work.resolve("backup.txt")), "fetch must not check out the backup-only commit");
        } finally {
            service.close();
            RepositoryCache.clear();
        }
    }

    private Path bare(String name) throws Exception {
        Path path = temp.resolve(name);
        try (Git git = Git.init().setDirectory(path.toFile()).setBare(true).call()) { }
        return path;
    }

    private GitService initialized(Path work) throws Exception {
        Files.createDirectories(work);
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "QA Synthetic");
            config.setString("user", null, "email", "qa@example.test");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        GitService service = new GitService();
        service.openRepository(work.toFile());
        return service;
    }

    private void commit(GitService service, Path work, String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit("fixture " + file, false).isOk());
    }

    private String ref(Path repository, String name) throws Exception {
        Ref ref = readRef(repository, name);
        assertNotNull(ref, name + " should exist");
        return ref.getObjectId().name();
    }

    private Ref readRef(Path repository, String name) throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            return git.getRepository().exactRef(name);
        }
    }
}
