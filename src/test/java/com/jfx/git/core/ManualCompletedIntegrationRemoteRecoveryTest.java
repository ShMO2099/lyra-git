package com.jfx.git.core;

import com.jfx.git.model.PullMode;
import com.jfx.git.testsupport.Engines;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Local-bare-remote end-to-end coverage for the completed rejected-push recovery flow. */
@Tag("heavy")
class ManualCompletedIntegrationRemoteRecoveryTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private final List<Git> gits = new ArrayList<>();
    private int fixtureNumber;

    static {
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @AfterEach
    void closeRepositories() {
        for (GitBackend backend : opened) backend.close();
        for (Git git : gits) git.close();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void rejectedPushThenFetchMergeAndPushKeepsBothHistories(
            String name, Supplier<GitBackend> factory) throws Exception {
        Path localPath = root.resolve("local-" + fixtureNumber++);
        Path remotePath = root.resolve("origin-" + fixtureNumber++);
        Path colleaguePath = root.resolve("colleague-" + fixtureNumber++);
        initRepository(localPath);
        Files.createDirectories(remotePath);
        Git.init().setDirectory(remotePath.toFile()).setBare(true).setInitialBranch("main").call().close();

        GitBackend local = factory.get();
        assertTrue(local.openRepository(localPath.toFile()));
        opened.add(local);
        assertTrue(local.addRemote("origin", remotePath.toString()).isOk());
        assertTrue(new RepositoryCommands(local).push("origin", false, false, true).isOk());
        String shared = local.getHeadHash();

        Git colleague = Git.cloneRepository().setURI(remotePath.toString())
                .setDirectory(colleaguePath.toFile()).call();
        gits.add(colleague);
        setIdentity(colleague);
        Files.writeString(colleaguePath.resolve("remote.txt"), "colleague change\n");
        colleague.add().addFilepattern("remote.txt").call();
        String remoteTip = colleague.commit().setMessage("colleague change").call().getName();
        colleague.push().setRemote("origin").add("main").call();

        Files.writeString(localPath.resolve("local.txt"), "local change\n");
        assertTrue(local.stageFile("local.txt").isOk(), name);
        assertTrue(local.commit("local change", false).isOk(), name);
        String localTip = local.getHeadHash();

        GitResult rejected = new RepositoryCommands(local).push("origin", false, false, false);

        assertFalse(rejected.isOk(), name + ": stale local history cannot replace origin");
        assertEquals(localTip, local.getHeadHash(), name + ": rejection keeps the local commit");
        assertEquals(remoteTip, resolve(remotePath, "refs/heads/main"),
                name + ": rejection keeps the colleague's remote commit");
        assertEquals("local change\n", Files.readString(localPath.resolve("local.txt")), name);

        assertTrue(local.fetchAll().isOk(), name);
        assertEquals(localTip, local.getHeadHash(), name + ": fetch updates tracking only");
        assertTrue(local.pull(PullMode.MERGE).isOk(), name);
        assertEquals(2, parentCount(local), name + ": recovery preserves both tips in a merge");
        assertEquals(Set.of(localTip, remoteTip), parentsOfHead(local),
                name + ": recovery retains both developers' exact commits");
        assertEquals("local change\n", Files.readString(localPath.resolve("local.txt")), name);
        assertEquals("colleague change\n", Files.readString(localPath.resolve("remote.txt")), name);

        assertTrue(new RepositoryCommands(local).push("origin", false, false, false).isOk(), name);
        assertEquals(local.getHeadHash(), resolve(remotePath, "refs/heads/main"), name);
        assertNotEquals(shared, local.getHeadHash(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void fastForwardOnlyRefusesSafelyAndRebasePullKeepsLinearTopology(
            String name, Supplier<GitBackend> factory) throws Exception {
        Path localPath = root.resolve("rebase-local-" + fixtureNumber++);
        Path remotePath = root.resolve("rebase-origin-" + fixtureNumber++);
        Path colleaguePath = root.resolve("rebase-colleague-" + fixtureNumber++);
        initRepository(localPath);
        Files.createDirectories(remotePath);
        Git.init().setDirectory(remotePath.toFile()).setBare(true).setInitialBranch("main").call().close();

        GitBackend local = factory.get();
        assertTrue(local.openRepository(localPath.toFile()));
        opened.add(local);
        assertTrue(local.addRemote("origin", remotePath.toString()).isOk());
        assertTrue(new RepositoryCommands(local).push("origin", false, false, true).isOk());

        Git colleague = Git.cloneRepository().setURI(remotePath.toString())
                .setDirectory(colleaguePath.toFile()).call();
        gits.add(colleague);
        setIdentity(colleague);
        Files.writeString(colleaguePath.resolve("remote.txt"), "remote side\n");
        colleague.add().addFilepattern("remote.txt").call();
        String remoteTip = colleague.commit().setMessage("remote side").call().getName();
        colleague.push().setRemote("origin").add("main").call();

        Files.writeString(localPath.resolve("local.txt"), "local side\n");
        assertTrue(local.stageFile("local.txt").isOk(), name);
        assertTrue(local.commit("local side", false).isOk(), name);
        String originalLocalTip = local.getHeadHash();

        GitResult refused = local.pull(PullMode.FF_ONLY);
        assertFalse(refused.isOk(), name + ": divergent history cannot be fast-forwarded");
        assertEquals(originalLocalTip, local.getHeadHash(), name + ": refusal keeps local HEAD");
        assertFalse(Files.exists(localPath.resolve("remote.txt")), name + ": refusal does not write remote files");
        assertEquals("origin/main", local.getUpstream("main"), name + ": branch keeps its upstream");
        assertEquals(remoteTip, resolve(localPath, "refs/remotes/origin/main"),
                name + ": fetch records remote tracking tip");

        GitResult rebased = local.pull(PullMode.REBASE);
        assertTrue(rebased.isOk(), name + ": " + rebased.getMessage());
        assertEquals(remoteTip, parentOfHead(local), name + ": rebased local commit sits directly above remote");
        assertEquals(1, parentCount(local), name + ": rebase leaves linear ancestry");
        assertEquals("local side\n", Files.readString(localPath.resolve("local.txt")), name);
        assertEquals("remote side\n", Files.readString(localPath.resolve("remote.txt")), name);
        assertEquals(remoteTip, resolve(localPath, "refs/remotes/origin/main"), name);
    }

    private static void initRepository(Path directory) throws Exception {
        Files.createDirectories(directory);
        try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
            setIdentity(git);
            Files.writeString(directory.resolve("base.txt"), "shared base\n");
            git.add().addFilepattern("base.txt").call();
            git.commit().setMessage("shared base").call();
        }
    }

    private static void setIdentity(Git git) throws Exception {
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Manual QA Test");
        config.setString("user", null, "email", "manual-qa@example.test");
        config.setString("core", null, "autocrlf", "false");
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
    }

    private static String resolve(Path repository, String ref) throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            ObjectId id = git.getRepository().resolve(ref);
            return id == null ? null : id.getName();
        }
    }

    private static int parentCount(GitBackend backend) throws Exception {
        try (Git git = Git.open(backend.getWorkTree());
             RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD")).getParentCount();
        }
    }

    private static String parentOfHead(GitBackend backend) throws Exception {
        try (Git git = Git.open(backend.getWorkTree());
             RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD")).getParent(0).getName();
        }
    }

    private static Set<String> parentsOfHead(GitBackend backend) throws Exception {
        try (Git git = Git.open(backend.getWorkTree());
             RevWalk walk = new RevWalk(git.getRepository())) {
            var head = walk.parseCommit(git.getRepository().resolve("HEAD"));
            return java.util.Arrays.stream(head.getParents())
                    .map(parent -> parent.getName()).collect(java.util.stream.Collectors.toSet());
        }
    }
}
