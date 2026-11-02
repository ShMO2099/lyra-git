package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.model.MergeMode;
import com.jfx.git.model.OperationState;
import com.jfx.git.model.PullMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The JGit engine's safety and honesty fixes from the engine review: operations that
 * destroyed or mislabelled work, results that said the wrong thing, and network calls
 * that did not report how they went.
 *
 * <p>Each test is a real repository, and where the right behaviour is "what git does",
 * it was taken from running git itself (2.56) on the same situation.
 */
@Tag("heavy")
class JGitEngineSafetyTest {

    @TempDir
    Path root;

    private Path repo;
    private GitService service;
    private Path emptyGlobal;
    private SystemReader originalReader;

    @BeforeEach
    void setUp() throws Exception {
        originalReader = SystemReader.getInstance();
        emptyGlobal = root.resolve("empty-global.gitconfig");
        Files.writeString(emptyGlobal, "");
        // Neither engine reads the developer's own configuration: a merge.suppressDest
        // or commit.gpgsign there would change what these tests see.
        SystemReader.setInstance(new SystemReader.Delegate(originalReader) {
            @Override
            public FileBasedConfig openUserConfig(org.eclipse.jgit.lib.Config parent, FS fs) {
                return new FileBasedConfig(parent, emptyGlobal.toFile(), fs);
            }

            @Override
            public FileBasedConfig openSystemConfig(org.eclipse.jgit.lib.Config parent, FS fs) {
                return new FileBasedConfig(parent, root.resolve("empty-system.gitconfig").toFile(), fs);
            }
        });
        EffectiveConfig.gitEnvironment = Map.of(
                "GIT_CONFIG_GLOBAL", emptyGlobal.toString(),
                "GIT_CONFIG_NOSYSTEM", "1");

        repo = root.resolve("repo");
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(repo.toFile());
    }

    @AfterEach
    void tearDown() {
        GitService.networkOutcome =
                worked -> CredentialsManager.getInstance().networkOperationFinished(worked);
        EffectiveConfig.gitEnvironment = Map.of();
        SystemReader.setInstance(originalReader);
        if (service != null) service.close();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void write(String name, String content) throws Exception {
        Path file = repo.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String read(String name) throws Exception {
        return Files.readString(repo.resolve(name), StandardCharsets.UTF_8);
    }

    private String commitFile(String name, String content, String message) throws Exception {
        write(name, content);
        assertTrue(service.stageFile(name).isOk());
        GitResult result = service.commit(message, false);
        assertTrue(result.isOk(), result.getMessage());
        return service.getHeadHash();
    }

    private void stage(String name, String content) throws Exception {
        write(name, content);
        assertTrue(service.stageFile(name).isOk());
    }

    /** {@code a}, {@code u} and {@code x} on main, then {@code side} changing {@code a}. */
    private String sideChangingA() throws Exception {
        stage("a.txt", "a\n");
        stage("u.txt", "u\n");
        commitFile("x.txt", "x\n", "base");
        assertTrue(service.createBranch("side", null, true).isOk());
        String side = commitFile("a.txt", "a side\n", "side edits a");
        assertTrue(service.checkoutBranch("main").isOk());
        return side;
    }

    /** {@link #sideChangingA} with main moved on as well, so merging side is a real merge. */
    private void diverged() throws Exception {
        sideChangingA();
        commitFile("x.txt", "x main\n", "main edits x");
    }

    private String fullMessageOf(String rev) throws Exception {
        try (Git git = Git.open(repo.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve(rev)).getFullMessage();
        }
    }

    private Path bareRemote() throws Exception {
        Path bare = root.resolve("remote.git");
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close();
        assertTrue(service.addRemote("origin", bare.toUri().toString()).isOk());
        return bare;
    }

    private static ObjectId refIn(Path gitDir, String ref) throws Exception {
        try (Git git = Git.open(gitDir.toFile())) {
            return git.getRepository().resolve(ref);
        }
    }

    /** Real git, isolated from the machine's configuration as JGit is above. */
    private String cli(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", emptyGlobal.toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", args) + ": " + out);
        return out.strip();
    }

    // ------------------------------------------------------------------
    // 1. Staged changes before a merge, cherry-pick or revert
    // ------------------------------------------------------------------

    /**
     * git refuses a real merge over any staged change ("Your local changes to the
     * following files would be overwritten by merge"). JGit's merger fails on a staged
     * edit to a tracked file, but lets a staged <em>new</em> file through - no side has
     * it - and the merge commit then contained it.
     */
    @Test
    void aRealMergeIsRefusedOverAStagedNewFile() throws Exception {
        diverged();
        stage("new.txt", "mine, not the merge's\n");
        String before = service.getHeadHash();

        GitResult merged = service.merge("side", MergeMode.DEFAULT, null);

        assertFalse(merged.isOk(), merged.getMessage());
        assertFalse(merged.isConflict(), "nothing was started, so there is nothing to resolve");
        assertTrue(merged.getMessage().contains("would be overwritten by merge")
                && merged.getMessage().contains("new.txt"), merged.getMessage());
        assertEquals(before, service.getHeadHash());
        assertEquals(List.of("new.txt"), service.getStagedFiles());
        assertEquals("mine, not the merge's\n", read("new.txt"));
        assertEquals(OperationState.Kind.NONE, service.getOperationState().kind());
    }

    /**
     * The destructive half: a merge that stops on a conflict, then Abort. Abort resets
     * whatever is staged as the merge's output, and a path HEAD does not have is deleted
     * from the disk - so the user's staged new file was gone, with nothing to recover it
     * from. Now the merge does not start.
     */
    @Test
    void abortingAMergeCannotTakeAStagedNewFileWithIt() throws Exception {
        sideChangingA();
        commitFile("a.txt", "a main\n", "main edits a too");
        stage("new.txt", "mine\n");

        GitResult merged = service.merge("side", MergeMode.DEFAULT, null);
        if (merged.isConflict()) {
            service.abortOperation();
        }

        assertFalse(merged.isOk());
        assertTrue(Files.exists(repo.resolve("new.txt")), "the staged file must survive");
        assertEquals("mine\n", read("new.txt"));
        assertEquals(List.of("new.txt"), service.getStagedFiles());
    }

    /** A staged edit to a tracked file was refused already, as "Merge failed: FAILED"; now in git's words. */
    @Test
    void aRealMergeOverAStagedEditSaysWhichFile() throws Exception {
        diverged();
        stage("u.txt", "u staged\n");
        String before = service.getHeadHash();

        GitResult merged = service.merge("side", MergeMode.DEFAULT, null);

        assertFalse(merged.isOk(), merged.getMessage());
        assertTrue(merged.getMessage().contains("u.txt"), merged.getMessage());
        assertEquals(before, service.getHeadHash());
        assertEquals("u staged\n", read("u.txt"));
    }

    /** {@code --no-ff} makes a merge commit even where a fast-forward was possible, so it is refused too. */
    @Test
    void aNoFastForwardMergeIsRefusedOverAStagedNewFile() throws Exception {
        sideChangingA();
        stage("new.txt", "mine\n");
        String before = service.getHeadHash();

        GitResult merged = service.merge("side", MergeMode.NO_FF, null);

        assertFalse(merged.isOk(), merged.getMessage());
        assertEquals(before, service.getHeadHash());
        assertEquals(List.of("new.txt"), service.getStagedFiles());
    }

    /** A fast-forward is a checkout, and git carries an unrelated staged change across it. */
    @Test
    void aFastForwardCarriesAnUnrelatedStagedChangeAcross() throws Exception {
        String side = sideChangingA();
        stage("u.txt", "u staged\n");

        GitResult merged = service.merge("side", MergeMode.DEFAULT, null);

        assertTrue(merged.isOk(), merged.getMessage());
        assertEquals(side, service.getHeadHash());
        assertEquals(List.of("u.txt"), service.getStagedFiles());
        assertEquals("u staged\n", read("u.txt"));
    }

    /** Unstaged edits elsewhere never stopped git's merge, and must not stop this one. */
    @Test
    void anUnstagedEditElsewhereDoesNotBlockAMerge() throws Exception {
        diverged();
        write("u.txt", "u edited\n");

        GitResult merged = service.merge("side", MergeMode.DEFAULT, null);

        assertTrue(merged.isOk(), merged.getMessage());
        assertEquals("u edited\n", read("u.txt"));
        assertTrue(service.getStagedFiles().isEmpty(), "the edit stays unstaged, not in the merge");
    }

    /** git: "your local changes would be overwritten by cherry-pick". JGit committed the staged file in the pick. */
    @Test
    void aCherryPickIsRefusedOverAStagedNewFile() throws Exception {
        String side = sideChangingA();
        stage("new.txt", "mine\n");
        String before = service.getHeadHash();

        GitResult picked = service.cherryPick(side);

        assertFalse(picked.isOk(), picked.getMessage());
        assertFalse(picked.isConflict());
        assertTrue(picked.getMessage().contains("cherry-pick"), picked.getMessage());
        assertEquals(before, service.getHeadHash());
        assertEquals(List.of("new.txt"), service.getStagedFiles());
        assertEquals("a\n", read("a.txt"));
    }

    @Test
    void aRevertIsRefusedOverAStagedNewFile() throws Exception {
        commitFile("a.txt", "a\n", "base");
        String change = commitFile("a.txt", "a changed\n", "change a");
        stage("new.txt", "mine\n");

        GitResult reverted = service.revert(change);

        assertFalse(reverted.isOk(), reverted.getMessage());
        assertTrue(reverted.getMessage().contains("revert"), reverted.getMessage());
        assertEquals(change, service.getHeadHash());
        assertEquals("a changed\n", read("a.txt"));
        assertEquals(List.of("new.txt"), service.getStagedFiles());
    }

    // ------------------------------------------------------------------
    // 5. Stops that are not conflicts
    // ------------------------------------------------------------------

    /** JGit knew which path was in the way; the result said only "Merge failed: FAILED". */
    @Test
    void aMergeThatWouldOverwriteALocalEditNamesThePath() throws Exception {
        diverged();
        write("a.txt", "a edited locally\n");

        GitResult merged = service.merge("side", MergeMode.DEFAULT, null);

        assertFalse(merged.isOk());
        assertFalse(merged.isConflict());
        assertTrue(merged.getMessage().contains("a.txt"), merged.getMessage());
        assertEquals("a edited locally\n", read("a.txt"));
    }

    @Test
    void aCherryPickThatWouldOverwriteALocalEditNamesThePath() throws Exception {
        String side = sideChangingA();
        write("a.txt", "a edited locally\n");

        GitResult picked = service.cherryPick(side);

        assertFalse(picked.isOk());
        assertFalse(picked.isConflict());
        assertTrue(picked.getMessage().contains("a.txt"), picked.getMessage());
        assertEquals("a edited locally\n", read("a.txt"));
    }

    /** RevertCommand reports this only through getFailingResult; it read as "did not complete". */
    @Test
    void aRevertThatWouldOverwriteALocalEditNamesThePath() throws Exception {
        commitFile("a.txt", "a\n", "base");
        String change = commitFile("a.txt", "a changed\n", "change a");
        write("a.txt", "a edited locally\n");

        GitResult reverted = service.revert(change);

        assertFalse(reverted.isOk());
        assertFalse(reverted.isConflict());
        assertTrue(reverted.getMessage().contains("a.txt"), reverted.getMessage());
        assertEquals("a edited locally\n", read("a.txt"));
    }

    /**
     * A rebasing pull over uncommitted changes never starts. It used to come back as a
     * conflict - "Rebase stopped: UNCOMMITTED_CHANGES" - over a repository with nothing
     * in progress and nothing to resolve.
     */
    @Test
    void aRebasingPullOverUncommittedChangesIsAFailureNotAConflict() throws Exception {
        upstreamMovedOn();
        commitFile("c.txt", "ours\n", "ours");
        write("a.txt", "a edited\n");

        GitResult pulled = service.pull(PullMode.REBASE);

        assertFalse(pulled.isOk(), pulled.getMessage());
        assertFalse(pulled.isConflict(), pulled.getMessage());
        assertTrue(pulled.getMessage().contains("a.txt"), pulled.getMessage());
        assertEquals(OperationState.Kind.NONE, service.getOperationState().kind());
    }

    /**
     * {@code a.txt} committed and pushed with main tracking origin/main, then a commit
     * pushed from another clone that this one has not fetched.
     */
    private void upstreamMovedOn() throws Exception {
        Path bare = bareRemote();
        commitFile("a.txt", "a\n", "base");
        assertTrue(service.push("origin", null, false, true).isOk());
        Path other = root.resolve("other");
        try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(other.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "Other");
            git.getRepository().getConfig().setString("user", null, "email", "other@example.com");
            Files.writeString(other.resolve("b.txt"), "theirs\n");
            git.add().addFilepattern("b.txt").call();
            git.commit().setMessage("theirs").setSign(false).call();
            git.push().call();
        }
    }

    /** The merge half of a pull is a merge, and refuses over a staged new file as one. */
    @Test
    void aMergingPullThatNeedsAMergeCommitIsRefusedOverAStagedNewFile() throws Exception {
        upstreamMovedOn();
        commitFile("c.txt", "ours\n", "ours");
        String before = service.getHeadHash();
        stage("new.txt", "mine\n");

        GitResult pulled = service.pull(PullMode.MERGE);

        assertFalse(pulled.isOk(), pulled.getMessage());
        assertFalse(pulled.isConflict());
        assertTrue(pulled.getMessage().contains("new.txt"), pulled.getMessage());
        assertEquals(before, service.getHeadHash());
        assertEquals(List.of("new.txt"), service.getStagedFiles());
        assertEquals(OperationState.Kind.NONE, service.getOperationState().kind());
    }

    /** A pull that only fast-forwards carries the staged file across, as git's does. */
    @Test
    void aFastForwardingPullCarriesAStagedNewFileAcross() throws Exception {
        upstreamMovedOn();
        stage("new.txt", "mine\n");

        GitResult pulled = service.pull(PullMode.MERGE);

        assertTrue(pulled.isOk(), pulled.getMessage());
        assertTrue(Files.exists(repo.resolve("b.txt")), "the upstream commit arrived");
        assertEquals(List.of("new.txt"), service.getStagedFiles());
    }

    // ------------------------------------------------------------------
    // 2. A push whose upstream could not be recorded
    // ------------------------------------------------------------------

    @Test
    void aPushThatLandedIsReportedAsPushedEvenWhenTheUpstreamCannotBeSaved() throws Exception {
        Path bare = bareRemote();
        String head = commitFile("a.txt", "a\n", "base");
        // A held lock is what another git process leaves while it writes the config.
        Path lock = repo.resolve(".git/config.lock");
        Files.writeString(lock, "");
        try {
            GitResult pushed = service.push("origin", null, false, true);

            assertTrue(pushed.isOk(), pushed.getMessage());
            assertTrue(pushed.getMessage().contains("upstream could not be recorded"), pushed.getMessage());
            assertEquals(head, refIn(bare, "refs/heads/main").getName());
        } finally {
            Files.deleteIfExists(lock);
        }
    }

    /** --follow-tags: annotated tags in the pushed history, and nothing else. */
    @Test
    void aPushWithTagsSendsOnlyTheAnnotatedTagsInThePushedHistory() throws Exception {
        Path bare = bareRemote();
        commitFile("a.txt", "a\n", "base");
        assertTrue(service.createTag("v1", "release one", null).isOk());
        assertTrue(service.createTag("light", null, null).isOk());
        assertTrue(service.createBranch("elsewhere", null, true).isOk());
        commitFile("e.txt", "e\n", "elsewhere");
        assertTrue(service.createTag("v2", "not on main", null).isOk());
        assertTrue(service.checkoutBranch("main").isOk());
        commitFile("b.txt", "b\n", "more");
        assertTrue(service.createTag("v3", "release three", null).isOk());

        assertTrue(service.push("origin", null, true, true).isOk());

        assertNotNull(refIn(bare, "refs/tags/v1"));
        assertNotNull(refIn(bare, "refs/tags/v3"));
        assertNull(refIn(bare, "refs/tags/v2"), "not reachable from main");
        assertNull(refIn(bare, "refs/tags/light"), "lightweight tags are not followed");
    }

    /** The reflog check is one walk now; what it answers must not have changed. */
    @Test
    void aLeaseOnACommitTheBranchOnceHadIsIntegratedAndOneNeverSeenIsNot() throws Exception {
        Path bare = bareRemote();
        commitFile("a.txt", "a\n", "base");
        commitFile("a.txt", "b\n", "second");
        assertTrue(service.push("origin", null, false, true).isOk());
        // Rewrite what was pushed: the remote's commit is now only in main's reflog.
        stage("a.txt", "b amended\n");
        assertTrue(service.commit("second, amended", true).isOk());

        PushLease rewritten = service.pushLease("origin");
        assertNotNull(rewritten);
        assertNull(rewritten.refusalIfNotIntegrated(), "the pushed commit is in main's reflog");

        // Someone else pushes; a fetch brings it in, and it was never part of main.
        Path other = root.resolve("other");
        try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(other.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "Other");
            git.getRepository().getConfig().setString("user", null, "email", "other@example.com");
            Files.writeString(other.resolve("o.txt"), "o\n");
            git.add().addFilepattern("o.txt").call();
            git.commit().setMessage("theirs").setSign(false).call();
            git.push().call();
        }
        assertTrue(service.fetchAll().isOk());

        PushLease unseen = service.pushLease("origin");
        assertNotNull(unseen);
        assertNotNull(unseen.refusalIfNotIntegrated(), "the fetched commit was never on main");
    }

    // ------------------------------------------------------------------
    // 6. Every network call reports how it went
    // ------------------------------------------------------------------

    /**
     * Five network calls never told the credentials manager how they went, so a login
     * rejected by one stayed pending until an unrelated success approved it into the
     * user's credential helper.
     */
    @Test
    void everyNetworkCallReportsItsOutcome() throws Exception {
        List<Boolean> outcomes = Collections.synchronizedList(new ArrayList<>());
        GitService.networkOutcome = outcomes::add;
        bareRemote();
        commitFile("a.txt", "a\n", "base");
        assertTrue(service.createTag("v1", "one", null).isOk());
        assertTrue(service.addRemote("nowhere", root.resolve("missing.git").toUri().toString()).isOk());

        outcomes.clear();
        assertFalse(service.pushTag("nowhere", "v1").isOk());
        assertEquals(List.of(false), outcomes, "pushTag");

        outcomes.clear();
        assertTrue(service.pushTag("origin", "v1").isOk());
        assertEquals(List.of(true), outcomes, "pushTag");

        outcomes.clear();
        assertFalse(service.deleteRemoteTag("nowhere", "v1").isOk());
        assertEquals(List.of(false), outcomes, "deleteRemoteTag");

        outcomes.clear();
        assertTrue(service.deleteRemoteTag("origin", "v1").isOk());
        assertEquals(List.of(true), outcomes, "deleteRemoteTag");

        // No tracking ref, so the lease asks the remote itself (ls-remote).
        outcomes.clear();
        assertNull(service.pushLease("nowhere"));
        assertEquals(List.of(false), outcomes, "ls-remote");

        outcomes.clear();
        assertFalse(service.addSubmodule(root.resolve("no-such-repo").toUri().toString(), "sub").isOk());
        assertEquals(List.of(false), outcomes, "addSubmodule");

        // One report per remote, each for itself: the dead remote's failure is not
        // swallowed by the live one's success, nor the other way round.
        outcomes.clear();
        assertFalse(service.fetchAll().isOk(), "one of the two remotes is missing");
        assertEquals(List.of(false, true), outcomes.stream().sorted().toList(), "fetchAll");
    }

    // ------------------------------------------------------------------
    // 3. A graph walk that fails part-way
    // ------------------------------------------------------------------

    @Test
    void aGraphPageCutShortByAnUnreadableCommitStillOffersMore() throws Exception {
        String first = commitFile("a.txt", "1\n", "first");
        commitFile("a.txt", "2\n", "second");
        commitFile("a.txt", "3\n", "third");
        assertFalse(service.getCommitGraphPage(100, GraphOrder.current()).hasMore(), "the whole history fits");

        // The oldest commit's object goes missing, as with a broken clone or a bad disk.
        Path object = repo.resolve(".git/objects/" + first.substring(0, 2) + "/" + first.substring(2));
        assertTrue(Files.exists(object), "the commit should be a loose object");
        assertTrue(object.toFile().setWritable(true));   // loose objects are read-only
        Files.delete(object);
        service.close();
        service = new GitService();
        service.openRepository(repo.toFile());

        GraphPage page = service.getCommitGraphPage(100, GraphOrder.current());
        assertTrue(page.hasMore(), "a truncated walk is not the end of the history");
    }

    // ------------------------------------------------------------------
    // 4. Staging a deletion never deletes
    // ------------------------------------------------------------------

    /**
     * A tracked symlink whose target is gone is still a file to git. {@code File.exists()}
     * follows the link, so staging it went to {@code git rm} without {@code --cached}, which
     * deleted the link from the working tree.
     */
    @Test
    void stagingADanglingSymlinkKeepsItInTheWorkingTree() throws Exception {
        assumeTrue(FS.DETECTED.supportsSymlinks(), "this file system has no symlinks");
        write("target.txt", "t\n");
        Path link = repo.resolve("link");
        try {
            Files.createSymbolicLink(link, Path.of("target.txt"));
        } catch (Exception noPrivilege) {
            assumeTrue(false, "cannot create symlinks here: " + noPrivilege);
        }
        try (Git git = Git.open(repo.toFile())) {
            git.add().addFilepattern("target.txt").addFilepattern("link").call();
            git.commit().setMessage("a link").setSign(false).call();
        }
        Files.delete(repo.resolve("target.txt"));

        assertTrue(service.stageFile("link").isOk());

        assertTrue(Files.isSymbolicLink(link), "the link itself must survive being staged");
        DirCache index = DirCache.read(repo.resolve(".git/index").toFile(), FS.DETECTED);
        assertNotNull(index.getEntry("link"), "and stay in the index");
        assertEquals(FileMode.SYMLINK, index.getEntry("link").getFileMode());
    }

    @Test
    void stagingADeletedFileStagesTheDeletion() throws Exception {
        commitFile("a.txt", "a\n", "base");
        Files.delete(repo.resolve("a.txt"));

        assertTrue(service.stageFile("a.txt").isOk());

        assertEquals(List.of("a.txt"), service.getStagedFiles());
    }

    // ------------------------------------------------------------------
    // 9. Large blobs
    // ------------------------------------------------------------------

    /**
     * {@code ObjectLoader.getBytes()} refuses anything above {@code core.streamFileThreshold},
     * and the refusal was answered with an empty array: a large file read as empty. The
     * threshold is lowered here rather than a 50 MiB file written.
     */
    @Test
    void aBlobAboveTheStreamingThresholdIsReadWhole() throws Exception {
        byte[] content = new byte[8192];
        java.util.Arrays.fill(content, (byte) 'x');
        Files.write(repo.resolve("big.txt"), content);
        assertTrue(service.stageFile("big.txt").isOk());
        assertTrue(service.commit("big", false).isOk());
        String commit = service.getHeadHash();

        WindowCacheConfig small = new WindowCacheConfig();
        small.setStreamFileThreshold(1024);
        small.install();
        try {
            assertArrayEquals(content, service.getFileBytesAtCommit(commit, "big.txt"));
            assertArrayEquals(content, service.getStagedBytes("big.txt"));
        } finally {
            new WindowCacheConfig().install();
        }
    }

    // ------------------------------------------------------------------
    // 10, 16, 17. Honest answers
    // ------------------------------------------------------------------

    /** A blame "at" a commit that is not there used to quietly become the working tree's. */
    @Test
    void blameAtACommitThatDoesNotExistIsEmptyRatherThanTheWorkingTree() throws Exception {
        commitFile("a.txt", "one\ntwo\n", "base");

        assertFalse(service.getBlameLines("a.txt", null).isEmpty());
        assertTrue(service.getBlameLines("a.txt", "0123456789abcdef0123456789abcdef01234567").isEmpty());
    }

    @Test
    void commitReportsAnUnreadableIndexRatherThanNothingStaged() throws Exception {
        commitFile("a.txt", "a\n", "base");
        Files.writeString(repo.resolve(".git/index"), "this is not an index");

        GitResult committed = service.commit("anything", false);

        assertFalse(committed.isOk());
        assertNotEquals("Nothing staged to commit", committed.getMessage());
        assertTrue(committed.getMessage().startsWith("Commit failed"), committed.getMessage());
    }

    /** The BranchOps contract, and what the CLI engine has always answered. */
    @Test
    void trackingStatusWithNoUpstreamIsZeroZero() throws Exception {
        commitFile("a.txt", "a\n", "base");

        assertArrayEquals(new int[]{0, 0}, service.getTrackingStatus());
    }

    @Test
    void diffsAndFileListsOfACommitThatIsNotThereSaySoQuietly() throws Exception {
        commitFile("a.txt", "a\n", "base");
        // A name that resolves to nothing, and a well-formed hash of an object that is not
        // there - a graph row from before a gc, say.
        for (String missing : List.of("no-such-branch", "0123456789abcdef0123456789abcdef01234567")) {
            assertTrue(service.getChangesInCommit(missing).isEmpty());
            assertTrue(service.getFilesInCommit(missing).isEmpty());
            assertTrue(service.getChangesBetweenCommits("HEAD", missing).isEmpty());
            assertEquals(0, service.getFileBytesAtCommit(missing, "a.txt").length);
            String diff = new String(service.getDiffBytesForFileInCommit(missing, "a.txt", false),
                    StandardCharsets.UTF_8);
            assertTrue(diff.contains("no commit " + missing), diff);
            assertFalse(diff.contains("NullPointerException"), diff);
        }
    }

    // ------------------------------------------------------------------
    // 11, 12, 15. Behaviour kept while the work got cheaper
    // ------------------------------------------------------------------

    @Test
    void discardAllRestoresEveryChangedAndDeletedFileAndRemovesUntrackedOnes() throws Exception {
        stage("a.txt", "a\n");
        stage("b.txt", "b\n");
        commitFile("c.txt", "c\n", "base");
        write("a.txt", "a changed\n");
        write("b.txt", "b changed\n");
        Files.delete(repo.resolve("c.txt"));
        write("new.txt", "new\n");

        assertTrue(service.discardAllChanges().isOk());

        assertEquals("a\n", read("a.txt"));
        assertEquals("b\n", read("b.txt"));
        assertEquals("c\n", read("c.txt"));
        assertFalse(Files.exists(repo.resolve("new.txt")));
        assertFalse(service.hasUncommittedChanges());
    }

    @Test
    void aTagMoveIsAFastForwardOnlyOntoADescendant() throws Exception {
        commitFile("a.txt", "1\n", "first");
        assertTrue(service.createTag("annotated", "release", null).isOk());
        assertTrue(service.createTag("light", null, null).isOk());
        String later = commitFile("a.txt", "2\n", "second");
        assertTrue(service.createBranch("other", "HEAD~1", true).isOk());
        String sideways = commitFile("o.txt", "o\n", "elsewhere");

        assertTrue(service.isTagMoveFastForward("annotated", later));
        assertTrue(service.isTagMoveFastForward("light", later));
        assertTrue(service.isTagMoveFastForward("annotated", sideways), "a child of the tagged commit");
        assertFalse(service.isTagMoveFastForward("no-such-tag", later));
        assertTrue(service.createTag("later", "on the second commit", later).isOk());
        assertFalse(service.isTagMoveFastForward("later", sideways), "the two lines have diverged");
    }

    @Test
    void aDetachedHeadIsAtRiskOnlyWhenNoBranchOrTagReachesIt() throws Exception {
        String first = commitFile("a.txt", "1\n", "first");
        commitFile("a.txt", "2\n", "second");
        assertTrue(service.checkoutBranch(first).isOk());
        assertTrue(service.isHeadDetached());
        assertFalse(service.isDetachedHeadOrphanRisk(), "main still reaches it");

        commitFile("a.txt", "detached\n", "made while detached");
        assertTrue(service.isDetachedHeadOrphanRisk(), "nothing reaches the new commit");

        assertTrue(service.createTag("keep", null, null).isOk());
        assertFalse(service.isDetachedHeadOrphanRisk(), "a tag keeps it");
    }

    /**
     * The engine now uses the interface's {@code restoreTo}: the dirty-tree guard, then
     * {@code reset(HARD)}, which forgets a pending squash's message as git's reset does -
     * so it cannot pre-fill an unrelated later commit.
     */
    @Test
    void restoringFromTheReflogForgetsAPendingSquashMessage() throws Exception {
        String first = commitFile("a.txt", "1\n", "first");
        commitFile("a.txt", "2\n", "second");
        Path squashMsg = repo.resolve(".git/SQUASH_MSG");
        Files.writeString(squashMsg, "Squashed commit of the following:\n");

        assertTrue(new RepositoryCommands(service).restoreTo(first, "undo").isOk());

        assertEquals(first, service.getHeadHash());
        assertFalse(Files.exists(squashMsg));
    }

    // ------------------------------------------------------------------
    // 14. The default merge message
    // ------------------------------------------------------------------

    @Test
    void aMergeIntoMainSaysWhichBranchAndLeavesOutTheDestination() throws Exception {
        diverged();

        assertTrue(service.merge("side", MergeMode.DEFAULT, null).isOk());

        assertEquals("Merge branch 'side'", fullMessageOf("HEAD").strip());
    }

    @Test
    void aMergeIntoAnyOtherBranchNamesIt() throws Exception {
        diverged();
        assertTrue(service.createBranch("feature", null, true).isOk());
        commitFile("f.txt", "f\n", "feature work");

        assertTrue(service.merge("side", MergeMode.NO_FF, null).isOk());

        assertEquals("Merge branch 'side' into feature", fullMessageOf("HEAD").strip());
    }

    @Test
    void aRemoteTrackingBranchIsNamedAsOne() throws Exception {
        String side = sideChangingA();
        try (Git git = Git.open(repo.toFile())) {
            RefUpdate update = git.getRepository().updateRef("refs/remotes/origin/side");
            update.setNewObjectId(ObjectId.fromString(side));
            update.update();
        }
        assertTrue(service.createBranch("feature", null, true).isOk());

        assertTrue(service.merge("origin/side", MergeMode.NO_FF, null).isOk());

        assertEquals("Merge remote-tracking branch 'origin/side' into feature",
                fullMessageOf("HEAD").strip());
    }

    /** The same merges made by git itself, compared subject for subject. */
    @Test
    void theDefaultMergeMessageMatchesGits() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "git is not installed");
        diverged();
        assertTrue(service.createBranch("feature", null, true).isOk());
        commitFile("f.txt", "f\n", "feature work");
        cli(repo, "tag", "v1", "side");
        cli(repo, "update-ref", "refs/remotes/origin/side", "side");

        List<String[]> merges = List.of(
                new String[]{"feature", "side"},
                new String[]{"main", "side"},
                new String[]{"feature", "v1"},
                new String[]{"feature", "origin/side"});
        for (String[] merge : merges) {
            assertTrue(service.checkoutBranch(merge[0]).isOk());
            String before = service.getHeadHash();
            assertTrue(service.merge(merge[1], MergeMode.NO_FF, null).isOk());
            String ours = fullMessageOf("HEAD").strip();
            cli(repo, "reset", "--hard", before);
            cli(repo, "merge", "--no-ff", "--no-edit", merge[1]);
            String theirs = cli(repo, "log", "-1", "--format=%B").strip();
            cli(repo, "reset", "--hard", before);
            assertEquals(theirs, ours, "merging " + merge[1] + " into " + merge[0]);
        }
    }

    @Test
    void suppressDestFollowsGitsRules() {
        List<String> none = List.of();
        assertEquals("Merge branch 'x'", DefaultMergeMessage.of("x", "refs/heads/x", "main", none));
        assertEquals("Merge branch 'x'", DefaultMergeMessage.of("x", "refs/heads/x", "master", none));
        assertEquals("Merge branch 'x' into dev", DefaultMergeMessage.of("x", "refs/heads/x", "dev", none));
        assertEquals("Merge branch 'x' into HEAD", DefaultMergeMessage.of("x", "refs/heads/x", "HEAD", none));
        assertEquals("Merge tag 'v1' into dev", DefaultMergeMessage.of("v1", "refs/tags/v1", "dev", none));
        assertEquals("Merge commit 'abc1234' into dev", DefaultMergeMessage.of("abc1234", null, "dev", none));
        // Configured: the defaults no longer apply, globs match, and an empty value clears.
        assertEquals("Merge branch 'x' into main",
                DefaultMergeMessage.of("x", "refs/heads/x", "main", List.of("dev")));
        assertEquals("Merge branch 'x'",
                DefaultMergeMessage.of("x", "refs/heads/x", "release/2.0", List.of("release/*")));
        assertEquals("Merge branch 'x' into dev",
                DefaultMergeMessage.of("x", "refs/heads/x", "dev", List.of("dev", "")));
    }
}
