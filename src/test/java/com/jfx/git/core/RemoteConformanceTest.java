package com.jfx.git.core;

import com.jfx.git.model.PullMode;
import com.jfx.git.testsupport.Engines;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pushing, pulling and fetching against real local remotes, on every engine - the
 * remote-side counterpart of {@link BackendConformanceTest}, whose engine list it reuses.
 *
 * <p>Each test is a finding from the 2026-09-27 comparative review (R1-R5, R10, R11,
 * R13, R14): behaviour that ignored the repository's configuration, differed between
 * engines, or discarded work. Everything runs against bare repositories in a temporary
 * directory; nothing touches a network.
 */
@Tag("heavy")
class RemoteConformanceTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private final List<Git> gits = new ArrayList<>();
    private int count;

    static {
        // Windows will not delete a memory-mapped pack file; see BackendConformanceTest.
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @AfterEach
    void tearDown() {
        for (GitBackend backend : opened) {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The test's own failure is the one worth reporting.
            }
        }
        for (Git git : gits) git.close();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A repository with one commit on main, opened by the engine under test. */
    private GitBackend fixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo" + count++).toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            identity(git.getRepository().getConfig());
            Files.writeString(dir.toPath().resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
        }
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static void identity(StoredConfig config) throws Exception {
        config.setString("user", null, "name", "Test User");
        config.setString("user", null, "email", "test@example.com");
        config.setString("core", null, "autocrlf", "false");
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
    }

    private File bare() throws Exception {
        File remote = root.resolve("remote" + count++).toFile();
        Git.init().setDirectory(remote).setBare(true).setInitialBranch("main").call().close();
        return remote;
    }

    /** {@code origin}, pushed to and tracked, as a freshly published repository has. */
    private File origin(GitBackend backend) throws Exception {
        File remote = bare();
        assertTrue(backend.addRemote("origin", remote.getAbsolutePath()).isOk());
        GitResult pushed = new RepositoryCommands(backend).push("origin", false, false, true);
        assertTrue(pushed.isOk(), pushed::getMessage);
        return remote;
    }

    /** Somebody else's clone of {@code remote}. */
    private Git colleague(File remote) throws Exception {
        File dir = root.resolve("colleague" + count++).toFile();
        Git git = Git.cloneRepository().setURI(remote.getAbsolutePath()).setDirectory(dir).call();
        identity(git.getRepository().getConfig());
        gits.add(git);
        return git;
    }

    /** Commits {@code file} in {@code git} and pushes the current branch; the new commit's id. */
    private static String commitAndPush(Git git, String file, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(file), message + "\n");
        git.add().addFilepattern(file).call();
        RevCommit made = git.commit().setMessage(message).call();
        git.push().setRemote("origin").add(git.getRepository().getBranch()).call();
        return made.getName();
    }

    private static void commitLocally(GitBackend backend, String file, String message) throws Exception {
        Files.writeString(new File(backend.getWorkTree(), file).toPath(), message + "\n");
        assertTrue(backend.stageFile(file).isOk());
        GitResult made = backend.commit(message, false);
        assertTrue(made.isOk(), made::getMessage);
    }

    private static void configure(GitBackend backend, Consumer<StoredConfig> change) throws Exception {
        try (Git git = Git.open(backend.getWorkTree())) {
            StoredConfig config = git.getRepository().getConfig();
            change.accept(config);
            config.save();
        }
    }

    private static String config(GitBackend backend, String section, String sub, String name) throws Exception {
        try (Git git = Git.open(backend.getWorkTree())) {
            return git.getRepository().getConfig().getString(section, sub, name);
        }
    }

    private static String refIn(File repo, String ref) throws Exception {
        try (Git git = Git.open(repo)) {
            ObjectId id = git.getRepository().resolve(ref);
            return id == null ? null : id.getName();
        }
    }

    // ------------------------------------------------------------------
    // R1: where a push goes
    // ------------------------------------------------------------------

    /** A repository whose only remote is not called origin could not push at all. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void pushGoesToTheOnlyRemoteWhateverItIsCalled(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        File remote = bare();
        assertTrue(backend.addRemote("github", remote.getAbsolutePath()).isOk());

        GitResult pushed = new RepositoryCommands(backend).push(null, false, false, false);

        assertTrue(pushed.isOk(), pushed::getMessage);
        assertEquals(backend.getHeadHash(), refIn(remote, "refs/heads/main"));
        assertEquals("github", config(backend, "branch", "main", "remote"),
                "the first push publishes the branch, so it now tracks what it was pushed to");
    }

    /**
     * The fork workflow: {@code feature} tracks {@code upstream/main} and pushes to
     * {@code origin} by {@code remote.pushDefault}. Pushing used to send it to origin with
     * {@code -u}, re-pointing its tracking at {@code origin/feature}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void pushFollowsPushDefaultAndLeavesTheUpstreamAlone(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File upstream = bare();
        File fork = bare();
        assertTrue(backend.addRemote("upstream", upstream.getAbsolutePath()).isOk());
        assertTrue(backend.addRemote("origin", fork.getAbsolutePath()).isOk());
        assertTrue(new RepositoryCommands(backend).push("upstream", false, false, true).isOk());

        assertTrue(backend.createBranch("feature", null, true).isOk());
        commitLocally(backend, "f.txt", "feature work");
        configure(backend, c -> {
            c.setString("branch", "feature", "remote", "upstream");
            c.setString("branch", "feature", "merge", "refs/heads/main");
            c.setString("remote", null, "pushDefault", "origin");
        });

        GitResult pushed = new RepositoryCommands(backend).push(null, false, false, false);

        assertTrue(pushed.isOk(), pushed::getMessage);
        assertEquals(backend.getHeadHash(), refIn(fork, "refs/heads/feature"));
        assertNotEquals(backend.getHeadHash(), refIn(upstream, "refs/heads/main"),
                "nothing may reach upstream's main");
        assertEquals("upstream", config(backend, "branch", "feature", "remote"));
        assertEquals("refs/heads/main", config(backend, "branch", "feature", "merge"),
                "the configured upstream must not be rewritten by a push");
    }

    /** {@code push.default=upstream}: the branch goes to its upstream's name, not its own. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void pushDefaultUpstreamPushesToTheTrackedBranch(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        assertTrue(backend.createBranch("topic", null, true).isOk());
        commitLocally(backend, "t.txt", "topic work");
        configure(backend, c -> {
            c.setString("branch", "topic", "remote", "origin");
            c.setString("branch", "topic", "merge", "refs/heads/main");
            c.setString("push", null, "default", "upstream");
        });

        GitResult pushed = new RepositoryCommands(backend).push(null, false, false, false);

        assertTrue(pushed.isOk(), pushed::getMessage);
        assertEquals(backend.getHeadHash(), refIn(remote, "refs/heads/main"));
        assertNull(refIn(remote, "refs/heads/topic"));
    }

    // ------------------------------------------------------------------
    // R4: a lease the user never integrated
    // ------------------------------------------------------------------

    /**
     * Auto-fetch brings a colleague's commit into {@code origin/main}; the user amends and
     * force-pushes. The lease equals the colleague's commit, so the push used to succeed
     * and discard it. It must now be refused until the user has seen what goes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void forcePushOverAnUnintegratedFetchIsRefusedUntilConfirmed(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        String theirs = commitAndPush(colleague(remote), "their.txt", "their work");
        assertTrue(backend.fetchAll().isOk());

        Files.writeString(new File(backend.getWorkTree(), "a.txt").toPath(), "amended\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.commit("mine, amended", true).isOk());

        PushLease lease = backend.pushLease(null);
        assertNotNull(lease);
        assertEquals(theirs, lease.expectedObjectId());
        assertFalse(lease.integrated(), "their commit was never part of this branch");
        assertTrue(lease.wouldDiscard().stream().anyMatch(l -> l.contains("their work")),
                () -> "the discarded commit should be listed: " + lease.wouldDiscard());

        GitResult refused = backend.push(null, lease, false, false);
        assertFalse(refused.isOk());
        assertTrue(refused.getMessage().contains("their work"), refused::getMessage);
        assertEquals(theirs, refIn(remote, "refs/heads/main"));

        GitResult forced = backend.push(null, lease.confirmingDiscard(), false, false);
        assertTrue(forced.isOk(), forced::getMessage);
        assertEquals(backend.getHeadHash(), refIn(remote, "refs/heads/main"));
    }

    /** Amending your own pushed commit is the ordinary case, and stays allowed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void forcePushAfterAnAmendIsIntegratedThroughTheReflog(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        String pushed = backend.getHeadHash();
        Files.writeString(new File(backend.getWorkTree(), "a.txt").toPath(), "amended\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.commit("first, amended", true).isOk());

        PushLease lease = backend.pushLease("origin");

        assertNotNull(lease);
        assertTrue(lease.integrated(), "the leased commit is in this branch's reflog");
        assertEquals(1, lease.wouldDiscard().size(), () -> "the pre-amend commit is replaced: " + lease.wouldDiscard());
        GitResult forced = backend.push("origin", lease, false, false);
        assertTrue(forced.isOk(), forced::getMessage);
        assertNotEquals(pushed, refIn(remote, "refs/heads/main"));
    }

    // ------------------------------------------------------------------
    // R2, R3: pull does what its mode says
    // ------------------------------------------------------------------

    /** With nothing configured, a native Merge pull of a diverged branch used to fail outright. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void mergePullReconcilesADivergedBranchWithNothingConfigured(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        commitAndPush(colleague(remote), "their.txt", "their work");
        commitLocally(backend, "mine.txt", "my work");

        GitResult pulled = backend.pull(PullMode.MERGE);

        assertTrue(pulled.isOk(), pulled::getMessage);
        assertEquals(2, parentsOfHead(backend), "a merge pull makes a merge commit");
    }

    /** {@code pull.rebase=true} used to turn a Merge pull into a rebase on both engines. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void mergePullMergesEvenWhenPullRebaseIsConfigured(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        commitAndPush(colleague(remote), "their.txt", "their work");
        commitLocally(backend, "mine.txt", "my work");
        configure(backend, c -> c.setBoolean("pull", null, "rebase", true));

        GitResult pulled = backend.pull(PullMode.MERGE);

        assertTrue(pulled.isOk(), pulled::getMessage);
        assertEquals(2, parentsOfHead(backend), "Merge must merge, whatever pull.rebase says");
    }

    private static int parentsOfHead(GitBackend backend) throws Exception {
        try (Git git = Git.open(backend.getWorkTree()); RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD")).getParentCount();
        }
    }

    // ------------------------------------------------------------------
    // R5, R11: fetch
    // ------------------------------------------------------------------

    /** A local tag that differs from the remote's used to make every native fetch fail. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aDivergedLocalTagDoesNotFailTheFetch(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        Git other = colleague(remote);
        commitAndPush(other, "their.txt", "their work");
        other.tag().setName("v1").setAnnotated(false).call();
        other.push().setRemote("origin").setPushTags().call();
        assertTrue(backend.createTag("v1", null, "HEAD").isOk(), "a local v1, elsewhere");

        GitResult fetched = backend.fetchAll();

        assertTrue(fetched.isOk(), fetched::getMessage);
        assertEquals(refIn(remote, "refs/heads/main"),
                refIn(backend.getDirectory(), "refs/remotes/origin/main"));
    }

    /** One dead remote used to stop the healthy ones from being fetched at all. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBrokenRemoteDoesNotStopTheOthersBeingFetched(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        assertTrue(backend.addRemote("aaa-missing",
                root.resolve("does-not-exist").toAbsolutePath().toString()).isOk());
        String theirs = commitAndPush(colleague(remote), "their.txt", "their work");

        GitResult fetched = backend.fetchAll();

        assertFalse(fetched.isOk(), "the broken remote is reported");
        assertEquals(theirs, refIn(backend.getDirectory(), "refs/remotes/origin/main"),
                "origin must still have been fetched");
    }

    // ------------------------------------------------------------------
    // R14: push with tags
    // ------------------------------------------------------------------

    /**
     * Push with tags sent every tag, so one the remote already had elsewhere turned the
     * whole push into "rejected". Only annotated tags in the pushed history go, and a
     * clash is not a failure.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void pushWithTagsIsNotSpoiltByAStaleTag(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        Git other = colleague(remote);
        other.tag().setName("old").setMessage("theirs").call();
        other.push().setRemote("origin").setPushTags().call();
        String theirTag = refIn(remote, "refs/tags/old");

        commitLocally(backend, "b.txt", "second");
        assertTrue(backend.createTag("old", "mine", "HEAD").isOk());
        assertTrue(backend.createTag("v2", "release", "HEAD").isOk());

        GitResult pushed = new RepositoryCommands(backend).push(null, false, true, false);

        assertTrue(pushed.isOk(), pushed::getMessage);
        assertEquals(backend.getHeadHash(), refIn(remote, "refs/heads/main"));
        assertNotNull(refIn(remote, "refs/tags/v2"), "a new annotated tag in the history follows");
        assertEquals(theirTag, refIn(remote, "refs/tags/old"), "the remote's own tag is untouched");
    }

    // ------------------------------------------------------------------
    // R10: checking a pull request out again
    // ------------------------------------------------------------------

    /**
     * The second checkout of the same pull request: fast-forward when behind, keep local
     * commits when ahead, refuse when diverged. The CLI used to reset the branch (losing
     * local commits) and JGit used to stay on the stale head while reporting success.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void checkingAPullRequestOutAgainNeverDiscardsLocalCommits(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        Git other = colleague(remote);
        other.checkout().setCreateBranch(true).setName("feature").call();
        commitAndPush(other, "f1.txt", "feature one");

        GitResult first = backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", false);
        assertTrue(first.isOk(), first::getMessage);

        // Behind: the PR moved on, the local branch did not.
        String f2 = commitAndPush(other, "f2.txt", "feature two");
        assertTrue(backend.checkoutBranch("main").isOk());
        GitResult again = backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", false);
        assertTrue(again.isOk(), again::getMessage);
        assertEquals("review", backend.getCurrentBranch());
        assertEquals(f2, backend.getHeadHash(), "a branch that is only behind is fast-forwarded");

        // Ahead: a local fix-up commit on the review branch survives a re-checkout.
        commitLocally(backend, "fix.txt", "local fix-up");
        String fixUp = backend.getHeadHash();
        assertTrue(backend.checkoutBranch("main").isOk());
        GitResult ahead = backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", false);
        assertTrue(ahead.isOk(), ahead::getMessage);
        assertEquals(fixUp, backend.getHeadHash(), "local commits must not be reset away");

        // Diverged: refused, and the local commit still there.
        commitAndPush(other, "f3.txt", "feature three");
        assertTrue(backend.checkoutBranch("main").isOk());
        GitResult diverged = backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", false);
        assertFalse(diverged.isOk());
        assertEquals(fixUp, refIn(backend.getDirectory(), "refs/heads/review"));
    }

    /**
     * R10, the other half: a diverged branch is refused with a count the dialogs can ask
     * about, and reset to the pull request only when the caller says so - from another
     * branch or while checked out on it - with its own commits left in its reflog.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aDivergedPullRequestBranchIsResetOnlyWhenAsked(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        Git other = colleague(remote);
        other.checkout().setCreateBranch(true).setName("feature").call();
        commitAndPush(other, "f1.txt", "feature one");
        assertTrue(backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", false).isOk());
        commitLocally(backend, "fix1.txt", "local fix one");
        commitLocally(backend, "fix2.txt", "local fix two");
        String fixUp = backend.getHeadHash();
        String f2 = commitAndPush(other, "f2.txt", "feature two");

        // Refused, with the number of local commits the question will name.
        assertTrue(backend.checkoutBranch("main").isOk());
        GitResult refused = backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", false);
        assertFalse(refused.isOk());
        assertEquals(2, DivergedCheckout.localCommits(refused), refused::getMessage);
        assertEquals("main", backend.getCurrentBranch(), "a refusal switches nothing");

        // Reset from another branch.
        GitResult reset = backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", true);
        assertTrue(reset.isOk(), reset::getMessage);
        assertEquals("review", backend.getCurrentBranch());
        assertEquals(f2, backend.getHeadHash());
        assertEquals(fixUp, refIn(backend.getDirectory(), "review@{1}"),
                "the local commits stay reachable in the branch's reflog");
        assertEquals(-1, DivergedCheckout.localCommits(reset));

        // Reset while on the branch itself, with an unrelated local change carried along.
        commitLocally(backend, "fix3.txt", "local fix three");
        String f3 = commitAndPush(other, "f3.txt", "feature three");
        Files.writeString(new File(backend.getWorkTree(), "a.txt").toPath(), "edited\n");
        assertEquals(1, DivergedCheckout.localCommits(
                backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", false)));
        GitResult onIt = backend.checkoutRemoteRef("origin", "refs/heads/feature", "review", true);
        assertTrue(onIt.isOk(), onIt::getMessage);
        assertEquals("review", backend.getCurrentBranch());
        assertEquals(f3, backend.getHeadHash());
        assertEquals("edited\n", Files.readString(new File(backend.getWorkTree(), "a.txt").toPath()),
                "an uncommitted change the reset does not touch survives it");
    }

    // ------------------------------------------------------------------
    // R13: merge-base
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void mergeBaseIsTheForkPoint(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String forkPoint = backend.getHeadHash();
        assertTrue(backend.createBranch("side", null, true).isOk());
        commitLocally(backend, "s.txt", "side");
        String side = backend.getHeadHash();
        assertTrue(backend.checkoutBranch("main").isOk());
        commitLocally(backend, "m.txt", "main moved on");

        assertEquals(forkPoint, backend.mergeBase("main", side));
        assertEquals(forkPoint, backend.mergeBase(backend.getHeadHash(), side));
        assertNull(backend.mergeBase("main", "no-such-ref"));
    }
}
