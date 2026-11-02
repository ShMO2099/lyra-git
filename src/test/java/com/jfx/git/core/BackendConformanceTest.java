package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.StashOptions;
import com.jfx.git.testsupport.Engines;
import com.jfx.git.ui.DiffParser;
import com.jfx.git.ui.HunkSelection;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import com.jfx.git.model.BisectStatus;

/**
 * One scenario, every engine, the same assertion.
 *
 * <h2>Why this exists</h2>
 * {@link GitBackend}'s javadoc is a contract, and each engine used to be tested only
 * against its own expectations. That let the CLI backend drift from the JGit reference in
 * about ten places without a single failing test - a hard reset that skipped the
 * dirty-tree guard, conflicts reported as plain failures, a bisect candidate set that
 * still contained known-good commits, submodule updates that ignored the paths asked for.
 * Every one of those was found by reading the code, which is the wrong way to find them.
 *
 * <p>So each test here is written against the interface, given a fresh fixture
 * repository, and run against every engine this machine can provide. A new engine, or a
 * new implementation of an existing method, either matches the reference behaviour or
 * fails here.
 *
 * <h2>Scope</h2>
 * Conformance, not coverage: one case per behaviour that has actually diverged, or that
 * would be costly if it silently did. Engine-specific depth stays in
 * {@link GitServiceTest}, {@link NativeGitBackendTest} and their siblings.
 */
class BackendConformanceTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private int fixtureCount;

    /** The three fixture commits, oldest first; recorded while the fixture is built. */
    private final List<String> fixtureCommits = new ArrayList<>();

    /**
     * Windows will not delete a memory-mapped pack file, and {@code @TempDir} has to
     * remove every fixture repository afterwards.
     */
    static {
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
                // A test that already failed should report its own reason, not this one.
            }
        }
        opened.clear();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Engines under test
    // ------------------------------------------------------------------

    /**
     * Every engine available here. JGit is always present; the other two are gated on the
     * machine having a {@code git} executable and a loadable bundled {@code libgit2}, so
     * this suite checks what it can rather than failing for the absence of something the
     * project does not ship.
     */
    static Stream<Arguments> engines() {
        return Engines.all();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A repository with three commits on {@code main}, opened by the engine under test.
     *
     * <p>Built with JGit rather than with the engine, so the starting point is identical
     * whichever engine is being examined and a broken engine cannot quietly build itself
     * an easier one.
     */
    private GitBackend fixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo" + (fixtureCount++)).toFile();
        assertTrue(dir.mkdirs());
        fixtureCommits.clear();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            configure(git);
            commit(git, "a.txt", "one\n", "first");
            commit(git, "a.txt", "one\ntwo\n", "second");
            commit(git, "b.txt", "b\n", "third");
        }
        return open(factory, dir);
    }

    private GitBackend open(Supplier<GitBackend> factory, File dir) throws GitOperationException {
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir), "the engine should open the fixture repository");
        opened.add(backend);
        return backend;
    }

    private static void configure(Git git) throws Exception {
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Test User");
        config.setString("user", null, "email", "test@example.com");
        config.setString("core", null, "autocrlf", "false");
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
    }

    private void commit(Git git, String path, String content, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(path), content);
        git.add().addFilepattern(path).call();
        RevCommit created = git.commit().setMessage(message).call();
        fixtureCommits.add(created.getName());
    }

    /** HEAD's first parent in the fixture: the second of its three commits. */
    private String parentOfHead() {
        return fixtureCommits.get(fixtureCommits.size() - 2);
    }

    private static void write(GitBackend backend, String path, String content) throws IOException {
        Files.writeString(new File(backend.getWorkTree(), path).toPath(), content);
    }

    private static String read(GitBackend backend, String path) throws IOException {
        return Files.readString(new File(backend.getWorkTree(), path).toPath());
    }

    /** A branch off HEAD carrying one commit, leaving HEAD back on {@code main}. */
    private static void commitOnBranch(GitBackend backend, String branch,
                                       String path, String content) throws IOException {
        assertTrue(backend.createBranch(branch, null, true).isOk());
        write(backend, path, content);
        assertTrue(backend.stageFile(path).isOk());
        assertTrue(backend.commit("change on " + branch, false).isOk());
        assertTrue(backend.checkoutBranch("main").isOk());
    }

    // ------------------------------------------------------------------
    // The contract
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Pushing: the lease, and publishing a branch
    // ------------------------------------------------------------------

    /**
     * A bare repository beside the fixture, wired up as {@code origin} and pushed to.
     *
     * @return the remote's directory
     */
    private File remoteFor(GitBackend backend) throws Exception {
        File remote = root.resolve("remote" + fixtureCount++).toFile();
        Git.init().setDirectory(remote).setBare(true).call().close();
        assertTrue(backend.addRemote("origin", remote.getAbsolutePath()).isOk());
        assertTrue(new RepositoryCommands(backend).push("origin", false, false, true).isOk(),
                "the fixture's first push should succeed");
        return remote;
    }

    /** Deletes this clone's record of what the remote has, without touching the remote. */
    private static void forgetTrackingRef(GitBackend backend, String branch) {
        File ref = new File(backend.getDirectory(),
                "refs/remotes/origin/" + branch.replace('/', File.separatorChar));
        // Packed or loose: both have to go, or the clone still remembers.
        ref.delete();
        try (Git git = Git.open(backend.getWorkTree())) {
            var update = git.getRepository().updateRef("refs/remotes/origin/" + branch);
            update.setForceUpdate(true);
            update.delete();
        } catch (Exception ignored) {
            // A ref that is already gone is the state this method wanted.
        }
    }

    /**
     * P1. Force-with-lease without a lease is just force.
     *
     * <p>Reproduced against the JGit engine: with no {@code refs/remotes/origin/main} to
     * lease against, the force flag went out on its own and replaced a remote branch
     * carrying commits this clone had never seen. That is the one thing
     * {@code --force-with-lease} exists to prevent, and the case where it disappeared was
     * exactly the dangerous one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void forcePushIsRefusedWithoutATrustworthyLease(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = remoteFor(backend);
        String onRemote = backend.getHeadHash();

        // Diverge locally, then destroy this clone's record of the remote.
        assertTrue(new RepositoryCommands(backend).restoreTo(parentOfHead(), "rewrite history for the lease test").isOk());
        write(backend, "c.txt", "diverged\n");
        assertTrue(backend.stageFile("c.txt").isOk());
        assertTrue(backend.commit("divergent", false).isOk());
        forgetTrackingRef(backend, "main");

        assertNull(backend.pushLease("origin"),
                "with no record of the remote there is no lease to be had");

        GitResult forced = new RepositoryCommands(backend).push("origin", true, false, false);
        assertFalse(forced.isOk(),
                "a force push with nothing to lease against must be refused, not sent");

        try (Git check = Git.open(remote)) {
            assertEquals(onRemote,
                    check.getRepository().resolve("refs/heads/main").getName(),
                    "the remote branch must be exactly where it was");
        }
    }

    /**
     * P2. The same push, with the lease intact, is allowed through.
     *
     * <p>The counterpart to the test above: refusing everything would also pass it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void forcePushProceedsWhenTheLeaseStillHolds(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = remoteFor(backend);

        PushLease lease = backend.pushLease("origin");
        assertNotNull(lease, "a freshly pushed branch has a tracking ref to lease against");
        assertEquals("main", lease.branch());
        assertFalse(lease.expectsNoRemoteBranch(), "the branch is on the remote");

        assertTrue(new RepositoryCommands(backend).restoreTo(parentOfHead(), "rewrite history for the lease test").isOk());
        write(backend, "c.txt", "diverged\n");
        assertTrue(backend.stageFile("c.txt").isOk());
        assertTrue(backend.commit("divergent", false).isOk());
        String rewritten = backend.getHeadHash();

        assertTrue(new RepositoryCommands(backend).push("origin", true, false, false).isOk(),
                "the remote is where the lease says it is, so the force is safe");

        try (Git check = Git.open(remote)) {
            assertEquals(rewritten,
                    check.getRepository().resolve("refs/heads/main").getName());
        }
    }

    /**
     * P3. Publishing a branch that the remote has never seen.
     *
     * <p>The CLI engine built {@code git push -u <remote>} with no refspec, so under the
     * default {@code push.default = simple} an unpublished branch had no upstream to work
     * from and git refused instead of publishing it - while JGit, which always sent an
     * explicit refspec, published it happily. Same gesture, same toolbar button, two
     * different outcomes depending on the engine preference.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void pushPublishesABranchTheRemoteHasNeverSeen(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = remoteFor(backend);

        assertTrue(backend.createBranch("new-feature", null, true).isOk());
        write(backend, "d.txt", "new\n");
        assertTrue(backend.stageFile("d.txt").isOk());
        assertTrue(backend.commit("on the new branch", false).isOk());
        String head = backend.getHeadHash();

        PushLease lease = backend.pushLease("origin");
        assertNotNull(lease, "an unpublished branch has a lease: it must not be there yet");
        assertTrue(lease.expectsNoRemoteBranch(),
                "nothing to overwrite, so no force is called for");

        GitResult pushed = new RepositoryCommands(backend).push("origin", false, false, true);
        assertTrue(pushed.isOk(), () -> "publishing a new branch failed: " + pushed.getMessage());

        try (Git check = Git.open(remote)) {
            var published = check.getRepository().resolve("refs/heads/new-feature");
            assertNotNull(published, "the branch should now exist on the remote");
            assertEquals(head, published.getName());
        }
    }

    /**
     * B1. {@code restoreTo} is what the 50-step reflog Undo runs, and it is a hard reset.
     * An engine that skips the guard silently destroys uncommitted work.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void restoreToRefusesWhileTheTreeIsDirty(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String head = backend.getHeadHash();
        write(backend, "a.txt", "uncommitted edit\n");

        GitResult result = new RepositoryCommands(backend).restoreTo(parentOfHead(), "undo");

        assertFalse(result.isOk(), "a hard reset over a dirty tree must be refused");
        assertEquals("uncommitted edit\n", read(backend, "a.txt"),
                "the uncommitted edit must still be there");
        assertEquals(head, backend.getHeadHash(), "HEAD must not have moved");
    }

    /** B1, the other half: with a clean tree the restore goes through. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void restoreToMovesHeadWhenTheTreeIsClean(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);

        GitResult result = new RepositoryCommands(backend).restoreTo(parentOfHead(), "undo");

        assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());
        assertEquals(parentOfHead(), backend.getHeadHash());
    }

    /**
     * B2. Moving a tag is delete-then-create, which is not atomic. A target that does not
     * resolve has to be caught before the delete, or the user simply loses the tag.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aFailedTagMoveLeavesTheTagWhereItWas(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String head = backend.getHeadHash();
        assertTrue(backend.createTag("v1", "release one", head).isOk());

        GitResult result = backend.moveTag("v1", "no-such-commit");

        assertFalse(result.isOk(), "an unresolvable target is not a move");
        RefInfo tag = tag(backend, "v1");
        assertNotNull(tag, "the tag must survive a move that could not happen");
        assertEquals(head, tag.targetHash(), "and must still point where it did");
    }

    /**
     * B7. {@code RefInfo.targetHash} keys the graph's decoration map, so an annotated tag
     * reported with the tag object's own SHA decorates no commit row at all, and every
     * piece of tag-keyed logic then sees lightweight tags only.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anAnnotatedTagReportsTheCommitItPointsAt(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String head = backend.getHeadHash();
        assertTrue(backend.createTag("annotated", "a real message", head).isOk());
        assertTrue(backend.createTag("lightweight", null, head).isOk());

        for (String tagName : List.of("annotated", "lightweight")) {
            RefInfo found = tag(backend, tagName);
            assertNotNull(found, tagName + " should be listed");
            assertEquals(head, found.targetHash(),
                    tagName + " must be keyed by the commit, not by the tag object");
        }
    }

    /**
     * B6. Conflict banners, the resolution panel and the "N conflicting file(s)" wording
     * are all keyed off {@link GitResult#isConflict()}, so a conflict reported as a plain
     * failure changes behaviour rather than just wording.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aConflictingMergeReportsConflictAndNamesTheFiles(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = conflictingFixture(factory);

        GitResult result = new RepositoryCommands(backend).merge("other", false);

        assertFalse(result.isOk(), "the merge cannot succeed");
        assertTrue(result.isConflict(),
                () -> "a conflict must report CONFLICT, not FAILED: " + result.getMessage());
        assertTrue(result.getConflicts().contains("a.txt"),
                () -> "the conflicting file must be named: " + result.getConflicts());
    }

    /**
     * B13. A conflict resolved by taking one side verbatim leaves the tree, the index and
     * HEAD in agreement while the merge is still open. The WIP row is the only place the
     * finishing commit is offered, so an engine that calls this state clean takes away
     * the user's way out of it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aResolvedButUnfinishedMergeStillCountsAsUncommitted(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = conflictingFixture(factory);
        assertTrue(new RepositoryCommands(backend).merge("other", false).isConflict());

        // Resolve by taking our side exactly: nothing differs from HEAD any more.
        assertTrue(backend.resolveConflict("a.txt", "our side\n").isOk());

        assertTrue(backend.hasUncommittedChanges(),
                "MERGE_HEAD is still on disk; the merge needs its finishing commit");
    }

    /**
     * B11. Plain {@code git show} emits a combined diff for a merge, and a combined diff
     * narrowed to one path is almost always empty - a blank pane where the reference
     * engine shows the real change against the first parent.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aMergeCommitsFileDiffIsNotEmpty(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "other", "c.txt", "from the branch\n");
        write(backend, "d.txt", "from main\n");
        assertTrue(backend.stageFile("d.txt").isOk());
        assertTrue(backend.commit("main change", false).isOk());
        assertTrue(new RepositoryCommands(backend).merge("other", true).isOk(), "this merge does not conflict");

        String diff = new String(
                backend.getDiffBytesForFileInCommit(backend.getHeadHash(), "c.txt", false),
                StandardCharsets.UTF_8);

        assertFalse(diff.isBlank(), "a merge commit's file diff must not be blank");
        assertTrue(diff.contains("from the branch"),
                () -> "the diff should carry the file's content: " + diff);
    }

    /**
     * B14. "Before" is what git compares against - the index copy, falling back to HEAD.
     * For a partially staged file those are different blobs.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void workingTreeSidesCompareAgainstTheIndexNotHead(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "staged\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        write(backend, "a.txt", "and then edited again\n");

        byte[][] sides = backend.getWorkingTreeSides("a.txt");

        assertEquals("staged\n", new String(sides[0], StandardCharsets.UTF_8),
                "the \"before\" side is the staged copy");
        assertEquals("and then edited again\n", new String(sides[1], StandardCharsets.UTF_8),
                "the \"after\" side is what is on disk");
    }

    /**
     * B8. Nothing prunes a ref no fetch refspec covers, so a synthetic one parked under
     * {@code refs/remotes/} sits in the sidebar's remote-branch tree forever. Written here
     * in the shape older versions of this application left behind.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void syntheticRemoteRefsAreNotListedAsRemoteBranches(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String head = backend.getHeadHash();
        writeRefFile(backend, "refs/remotes/origin/main", head);
        writeRefFile(backend, "refs/remotes/origin/pr/42", head);
        writeRefFile(backend, "refs/remotes/_pr-checkout/review", head);
        writeRefFile(backend, "refs/remotes/origin/pr/login-fix", head);

        List<String> names = backend.getRemoteBranches().stream().map(RefInfo::shortName).toList();

        assertTrue(names.contains("origin/main"), () -> "real branches stay: " + names);
        assertTrue(names.contains("origin/pr/login-fix"),
                () -> "a real branch that happens to live under pr/ stays: " + names);
        assertFalse(names.contains("origin/pr/42"), () -> "a PR checkout ref is ours: " + names);
        assertFalse(names.stream().anyMatch(n -> n.startsWith("_pr-checkout/")),
                () -> "a review checkout ref is ours: " + names);
    }

    /**
     * B3. {@code g1..bad g2..bad} is the union of two ranges - bad minus the
     * <em>intersection</em> of the goods - so with two good marks it hands back commits
     * already proven good. Two parallel branches merged, one good mark on each.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void bisectNeverOffersACommitReachableFromAnyGoodMark(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        assertTrue(backend.createBranch("side", null, true).isOk());
        write(backend, "side.txt", "side\n");
        assertTrue(backend.stageFile("side.txt").isOk());
        assertTrue(backend.commit("only on side", false).isOk());
        String goodOnSide = backend.getHeadHash();

        assertTrue(backend.checkoutBranch("main").isOk());
        write(backend, "main.txt", "main\n");
        assertTrue(backend.stageFile("main.txt").isOk());
        assertTrue(backend.commit("only on main", false).isOk());
        String goodOnMain = backend.getHeadHash();

        assertTrue(new RepositoryCommands(backend).merge("side", true).isOk());
        write(backend, "bad.txt", "the regression\n");
        assertTrue(backend.stageFile("bad.txt").isOk());
        assertTrue(backend.commit("the bad commit", false).isOk());
        String bad = backend.getHeadHash();

        assertTrue(backend.bisectStart().isOk());
        try {
            backend.bisectMarkBad(bad);
            backend.bisectMarkGood(goodOnMain);
            BisectStatus status = backend.bisectMarkGood(goodOnSide);

            assertNotEquals(goodOnSide, status.currentCommitHash(),
                    "a commit already marked good must never be handed back to test");
            assertNotEquals(goodOnMain, status.currentCommitHash(),
                    "a commit already marked good must never be handed back to test");
        } finally {
            backend.bisectReset();
        }
    }

    // ------------------------------------------------------------------
    // Comparative review 2026-09-27: operations in progress (O1, O10, C2)
    // ------------------------------------------------------------------

    private static String branchTip(GitBackend backend, String branch) {
        return backend.getLocalBranches().stream()
                .filter(r -> branch.equals(r.shortName()))
                .map(RefInfo::targetHash)
                .findFirst().orElseThrow();
    }

    /**
     * O1. A cherry-pick that stops is a cherry-pick - not a merge - and is abandoned by
     * its own abort. The only button used to be "Abort merge", which fails outside a
     * merge on the CLI and hard-reset the tree on JGit.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStoppedCherryPickIsNamedAndAbortedByItsOwnCommand(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = conflictingFixture(factory);
        write(backend, "b.txt", "an unrelated local edit\n");

        String picked = branchTip(backend, "other");
        assertTrue(backend.cherryPick(picked).isConflict());
        var state = backend.getOperationState();
        assertEquals(com.jfx.git.model.OperationState.Kind.CHERRY_PICKING, state.kind());
        // What the conflict panel's headline names: "Cherry-picking a1b2c3d onto main".
        assertEquals(picked, state.incoming(), "the commit being cherry-picked");
        assertEquals("main", state.target(), "the branch it lands on");

        GitResult aborted = backend.abortOperation();

        assertTrue(aborted.isOk(), aborted::getMessage);
        assertFalse(backend.getOperationState().inProgress(), "nothing is in progress after an abort");
        assertEquals("our side\n", read(backend, "a.txt"));
        assertEquals("an unrelated local edit\n", read(backend, "b.txt"),
                "abort puts back what the cherry-pick changed, not the user's other work");
    }

    /**
     * O10. {@code git merge --abort} is {@code reset --merge}: it keeps local changes to
     * paths the merge did not touch. JGit used a hard reset, which destroyed them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void abortingAMergeKeepsUnrelatedLocalChanges(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = conflictingFixture(factory);
        write(backend, "b.txt", "an unrelated local edit\n");

        assertTrue(new RepositoryCommands(backend).merge("other", false).isConflict());
        var state = backend.getOperationState();
        assertEquals(com.jfx.git.model.OperationState.Kind.MERGING, state.kind());
        // What the conflict panel's headline names: "Merging other into main".
        assertEquals("other", state.incoming(), "the merged branch, by name");
        assertEquals("main", state.target(), "the branch it merges into");

        GitResult aborted = backend.abortOperation();

        assertTrue(aborted.isOk(), aborted::getMessage);
        assertFalse(backend.getOperationState().inProgress());
        assertEquals("our side\n", read(backend, "a.txt"));
        assertEquals("an unrelated local edit\n", read(backend, "b.txt"),
                "local changes the merge never touched must survive its abort");
    }

    /**
     * C2. Finishing a conflicted cherry-pick is still that commit: git keeps its author
     * and message. JGit made the person resolving it the author, and nothing read the
     * prepared message.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aCherryPickContinuedAfterAConflictKeepsItsAuthorAndMessage(String name,
                                                                    Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String theirs;
        try (Git git = Git.open(backend.getWorkTree())) {
            git.checkout().setCreateBranch(true).setName("other").call();
            Files.writeString(backend.getWorkTree().toPath().resolve("a.txt"), "their side\n");
            git.add().addFilepattern("a.txt").call();
            theirs = git.commit().setMessage("Their change\n\nWith a body line.\n")
                    .setAuthor("Original Author", "orig@example.com").call().getName();
            git.checkout().setName("main").call();
        }
        write(backend, "a.txt", "our side\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.commit("our change", false).isOk());

        assertTrue(backend.cherryPick(theirs).isConflict());
        assertTrue(backend.resolveConflict("a.txt", "both sides\n").isOk());

        GitResult continued = backend.continueOperation();

        assertTrue(continued.isOk(), continued::getMessage);
        assertFalse(backend.getOperationState().inProgress());
        try (Git git = Git.open(backend.getWorkTree())) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            assertEquals("orig@example.com", head.getAuthorIdent().getEmailAddress(),
                    "the cherry-picked commit keeps its author");
            assertEquals("Their change", head.getShortMessage());
            assertFalse(head.getFullMessage().contains("Conflicts"),
                    () -> "git's conflict summary is not part of the message: " + head.getFullMessage());
        }
    }

    /**
     * O1. A rebase paused at an {@code edit} step has a clean tree and no conflicts, and
     * used to show no banner and offer no way to continue.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRebaseStoppedAtAnEditStepIsReportedAndContinues(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        var range = backend.getRebaseRange(fixtureCommits.get(1));
        RebaseTodo plan = RebaseTodo.fromNewestFirst(range.commits(), range.base())
                .withAction(0, RebaseTodo.Action.EDIT);

        GitResult started = backend.rebaseInteractive(plan);

        assertFalse(started.isOk(), () -> "an edit stop is not a finished rebase: " + started.getMessage());
        var state = backend.getOperationState();
        assertEquals(com.jfx.git.model.OperationState.Kind.REBASING, state.kind());
        assertTrue(state.editStop(), "the stop is an edit, not a conflict");

        GitResult continued = backend.continueOperation();

        assertTrue(continued.isOk(), continued::getMessage);
        assertFalse(backend.getOperationState().inProgress());
        assertTrue(backend.getHeadCommitMessage().startsWith("third"),
                () -> "the rest of the plan ran: " + backend.getHeadCommitMessage());
    }

    /** O1. A rebase step that conflicts can be skipped, which drops that commit. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aConflictingRebaseStepCanBeSkipped(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = conflictingFixture(factory);
        String mainTip = backend.getHeadHash();
        assertTrue(backend.checkoutBranch("other").isOk());

        assertTrue(backend.rebase("main").isConflict());
        var state = backend.getOperationState();
        assertTrue(state.isRebase());
        // What the conflict panel's headline names: "Rebasing other onto main (step 1 of 1)".
        assertEquals("other", state.incoming(), "the branch being rebased");
        assertEquals("main", state.target(), "what it is replayed onto, by the name it was given");
        assertEquals(1, state.step(), "the step it stopped at");
        assertEquals(1, state.steps(), "the steps in all");

        GitResult skipped = backend.skipOperation();

        assertTrue(skipped.isOk(), skipped::getMessage);
        assertFalse(backend.getOperationState().inProgress());
        assertEquals(mainTip, backend.getHeadHash(), "the only commit was skipped, leaving main's tip");
        assertEquals("other", backend.getCurrentBranch(), "and the branch is checked out again");
    }

    /**
     * O16. A bisect started on the command line writes {@code BISECT_LOG}, which JGit
     * reads as {@code RepositoryState.BISECTING} - and counted as uncommitted work.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aCommandLineBisectIsNotUncommittedWork(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        Files.writeString(backend.getDirectory().toPath().resolve("BISECT_START"), "main\n");
        Files.writeString(backend.getDirectory().toPath().resolve("BISECT_LOG"), "git bisect start\n");

        assertFalse(backend.hasUncommittedChanges(), "bisecting has nothing to commit");
        assertEquals(com.jfx.git.model.OperationState.Kind.BISECTING, backend.getOperationState().kind());
    }

    // ------------------------------------------------------------------
    // Comparative review: stash, merge options, revert of a merge (O11, O17, O6)
    // ------------------------------------------------------------------

    /**
     * O11. One stash semantics everywhere: untracked files go into the stash, and apply
     * brings the changes back to the working tree without restoring the index - what
     * {@code git stash push -u} and {@code git stash apply} do.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void stashTakesUntrackedFilesAndApplyLeavesTheIndexAlone(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "one\ntwo\nstaged edit\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        write(backend, "new.txt", "untracked\n");

        assertTrue(backend.stash(StashOptions.everything("work")).isOk());
        assertFalse(new File(backend.getWorkTree(), "new.txt").exists(),
                "the untracked file goes into the stash");
        assertEquals("one\ntwo\n", read(backend, "a.txt"));

        GitResult applied = backend.stashApply(0);

        assertTrue(applied.isOk(), applied::getMessage);
        assertEquals("one\ntwo\nstaged edit\n", read(backend, "a.txt"));
        assertEquals("untracked\n", read(backend, "new.txt"), "and comes back");
        assertFalse(backend.getStagedFiles().contains("a.txt"),
                () -> "apply does not restore the index: " + backend.getStagedFiles());
        assertTrue(backend.getStagingState().unstaged().contains("a.txt"));
    }

    /**
     * A stashed untracked file comes back wherever the work tree has no file at its path -
     * even once HEAD tracks that path, as it does after a merge or checkout brought in a
     * file of the same name. git restores it ({@code checkout-index} into the empty
     * place), and the file then shows as a change to the tracked one. JGit's own apply
     * refused it, because it compared the stash with HEAD rather than with the work tree,
     * and auto-stash ({@link AutoStash}) could then not put the user's file back.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStashedUntrackedFileComesBackWhereTheWorkTreeHasNoneEvenOnceHeadTracksIt(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "new.txt", "my own\n");
        assertTrue(backend.stash(StashOptions.everything("set aside")).isOk());
        write(backend, "new.txt", "committed\n");
        assertTrue(backend.stageFile("new.txt").isOk());
        assertTrue(backend.commit("track new.txt", false).isOk());
        Files.delete(new File(backend.getWorkTree(), "new.txt").toPath());

        GitResult popped = backend.stashPop(0);

        assertTrue(popped.isOk(), () -> name + ": " + popped.getMessage());
        assertEquals("my own\n", read(backend, "new.txt"), name);
        assertTrue(backend.getStagingState().unstaged().contains("new.txt"),
                () -> name + ": a change to the tracked file now: " + backend.getStagingState().unstaged());
        assertFalse(backend.getStagedFiles().contains("new.txt"), name + ": the index is left alone");
        assertEquals(List.of(), backend.getStashEntries(), name + ": popped, so dropped");
    }

    /** ...and never over a file that is there: the pop is refused and the stash kept. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStashedUntrackedFileIsNeverRestoredOverAFileThatIsThere(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "new.txt", "my own\n");
        assertTrue(backend.stash(StashOptions.everything("set aside")).isOk());
        write(backend, "new.txt", "committed\n");
        assertTrue(backend.stageFile("new.txt").isOk());
        assertTrue(backend.commit("track new.txt", false).isOk());

        GitResult popped = backend.stashPop(0);

        assertFalse(popped.isOk(), name);
        assertEquals("committed\n", read(backend, "new.txt"), name + ": the file that was there is untouched");
        assertEquals(1, backend.getStashEntries().size(), name + ": and the stash is kept");
    }

    /** O17. {@code merge.ff = only} is honoured: a diverged merge is refused, not made. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void mergeHonoursMergeFfOnly(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "other", "c.txt", "branch\n");
        write(backend, "d.txt", "main\n");
        assertTrue(backend.stageFile("d.txt").isOk());
        assertTrue(backend.commit("main change", false).isOk());
        String before = backend.getHeadHash();
        try (Git git = Git.open(backend.getWorkTree())) {
            var config = git.getRepository().getConfig();
            config.setString("merge", null, "ff", "only");
            config.save();
        }

        GitResult merged = new RepositoryCommands(backend).merge("other", false);

        assertFalse(merged.isOk(), "a merge that cannot fast-forward is refused under merge.ff=only");
        assertEquals(before, backend.getHeadHash());
    }

    /**
     * O6. Reverting a merge needs {@code -m}; without it every engine failed. Parent 1 is
     * the mainline, so the revert takes away what the merged branch brought in.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void revertingAMergeUndoesWhatTheBranchBroughtIn(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "other", "c.txt", "from the branch\n");
        write(backend, "d.txt", "from main\n");
        assertTrue(backend.stageFile("d.txt").isOk());
        assertTrue(backend.commit("main change", false).isOk());
        assertTrue(new RepositoryCommands(backend).merge("other", true).isOk());
        String merge = backend.getHeadHash();

        GitResult reverted = backend.revert(merge, 0);

        assertTrue(reverted.isOk(), reverted::getMessage);
        assertFalse(new File(backend.getWorkTree(), "c.txt").exists(), "the branch's file is reverted");
        assertEquals("from main\n", read(backend, "d.txt"), "mainline's own change stays");
        assertTrue(backend.getHeadCommitMessage().startsWith("Revert"));
    }

    // ------------------------------------------------------------------
    // Comparative review: rebase ranges and bisect answers (O5, O7, O16)
    // ------------------------------------------------------------------

    /**
     * O5. A commit already applied upstream under another hash has nothing to replay; an
     * explicit plan that kept it stopped the CLI and left an empty duplicate on JGit.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void rebasingOntoABranchLeavesOutCommitsItAlreadyHas(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        assertTrue(backend.createBranch("feature", null, true).isOk());
        write(backend, "x.txt", "x\n");
        assertTrue(backend.stageFile("x.txt").isOk());
        assertTrue(backend.commit("add x", false).isOk());
        String addX = backend.getHeadHash();
        write(backend, "y.txt", "y\n");
        assertTrue(backend.stageFile("y.txt").isOk());
        assertTrue(backend.commit("add y", false).isOk());

        assertTrue(backend.checkoutBranch("main").isOk());
        // Something of main's own first: picked onto its original parent in the same
        // second, the copy would be byte-identical to the original - the same commit.
        write(backend, "m.txt", "main moved on\n");
        assertTrue(backend.stageFile("m.txt").isOk());
        assertTrue(backend.commit("main moves on", false).isOk());
        assertTrue(backend.cherryPick(addX).isOk());
        String mainTip = backend.getHeadHash();
        assertTrue(backend.checkoutBranch("feature").isOk());

        var range = backend.getRebaseRange(mainTip);

        assertEquals(List.of("add y"), range.commits().stream().map(c -> c.getSubject()).toList(),
                "the commit main already has is not replayed");
        GitResult rebased = backend.rebaseInteractive(
                RebaseTodo.fromNewestFirst(range.commits(), range.base()));
        assertTrue(rebased.isOk(), rebased::getMessage);
        assertEquals(1, backend.getCommitsSince(mainTip).size(), "no empty duplicate");
    }

    /**
     * O7. A good commit descending from the bad one means the marks are the wrong way
     * round (or the bug was fixed between them). git refuses; this used to name the bad
     * commit as the answer.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void bisectRefusesMarksTheWrongWayRound(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        assertTrue(backend.bisectStart().isOk());
        try {
            backend.bisectMarkBad(fixtureCommits.get(0));
            BisectStatus status = backend.bisectMarkGood(fixtureCommits.get(2));

            assertFalse(status.done(), () -> "this is not an answer: " + status.message());
            assertNull(status.currentCommitHash());
            assertTrue(status.message().contains("merge base"), status.message());
        } finally {
            backend.bisectReset();
        }
    }

    /** O7. With only skipped commits left, the answer is "one of these", not the bad end. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void bisectWithOnlySkippedCommitsLeftNamesEverySuspect(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "c.txt", "fourth\n");
        assertTrue(backend.stageFile("c.txt").isOk());
        assertTrue(backend.commit("fourth", false).isOk());
        String bad = backend.getHeadHash();

        assertTrue(backend.bisectStart().isOk());
        try {
            backend.bisectMarkBad(bad);
            BisectStatus status = backend.bisectMarkGood(fixtureCommits.get(0));
            for (int i = 0; i < 5 && !status.done(); i++) {
                status = backend.bisectSkip();
            }

            final BisectStatus end = status;
            assertTrue(end.done(), end::message);
            assertTrue(end.ambiguous(), () -> "several commits could be first bad: " + end.message());
            assertNull(end.currentCommitHash(), "no single culprit is named");
            assertEquals(3, end.suspects().size(), end.suspects()::toString);
            assertTrue(end.suspects().contains(bad));
        } finally {
            backend.bisectReset();
        }
    }

    /**
     * O16. The session is on disk, so another engine instance - the one an engine switch
     * or a restart opens - carries on with it instead of stranding a detached HEAD.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBisectSessionOutlivesTheEngineThatStartedIt(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend first = fixture(factory);
        assertTrue(first.bisectStart().isOk());
        first.bisectMarkBad(fixtureCommits.get(2));
        BisectStatus testing = first.bisectMarkGood(fixtureCommits.get(0));
        assertEquals(fixtureCommits.get(1), testing.currentCommitHash());

        GitBackend second = open(factory, first.getWorkTree());
        BisectStatus resumed = second.bisectStatus();

        assertTrue(resumed.active(), "the new engine sees the running session");
        assertEquals(testing.currentCommitHash(), resumed.currentCommitHash());
        assertTrue(second.bisectReset().isOk());
        assertEquals("main", second.getCurrentBranch(), "reset returns to where the session began");
        assertFalse(first.bisectStatus().active(), "and the first engine sees it ended");
    }

    /**
     * B10. Hunk staging rewrites the whole index blob from text it decoded itself, so the
     * encoding it reads with is the encoding every untouched line is written back in.
     * Assuming UTF-8 corrupts the parts of the file the user never touched.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void stagingOneHunkLeavesANonUtf8FilesOtherLinesIntact(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        // Windows-1252 bytes that are not valid UTF-8: accented "cafe" and "naive".
        byte[] original = {
                'c', 'a', 'f', (byte) 0xE9, '\n',
                'p', 'l', 'a', 'i', 'n', '\n',
                'n', 'a', (byte) 0xEF, 'v', 'e', '\n'};
        Files.write(new File(backend.getWorkTree(), "latin.txt").toPath(), original);
        assertTrue(backend.stageFile("latin.txt").isOk());
        assertTrue(backend.commit("add a latin-1 file", false).isOk());

        GitResult staged = backend.applyToIndex("latin.txt", new HunkEdit(2,
                List.of("plain"), List.of("edited")), "Staged hunk");

        assertTrue(staged.isOk(), () -> "expected success, got: " + staged.getMessage());
        byte[] expected = {
                'c', 'a', 'f', (byte) 0xE9, '\n',
                'e', 'd', 'i', 't', 'e', 'd', '\n',
                'n', 'a', (byte) 0xEF, 'v', 'e', '\n'};
        assertArrayEquals(expected, backend.getWorkingTreeSides("latin.txt")[0],
                "the untouched lines must be byte-identical, in the file's own encoding");
    }

    // ------------------------------------------------------------------
    // D2/D3. Line endings and the final newline, through the whole chain
    // ------------------------------------------------------------------

    /**
     * Stages every hunk of {@code path} the way the user interface does.
     *
     * <p>The point of going the long way round - ask the engine for the diff, decode it,
     * parse it, turn it into a patch, apply it - is that D2 and D3 both lived in the
     * seams between those steps rather than in any one of them. Each part had tests and
     * each part passed; the diff text lost a CR on the way in, and the parser handed the
     * no-newline marker on as though it were a line of the file. Calling
     * {@code applyToIndex} directly with hand-written blocks, which is what the older
     * test above does, steps over exactly the ground where the defects were.
     */
    static void stageEveryHunkThroughTheUiChain(GitBackend backend, String path) {
        // Decoded the way a LoadedDiff is: CRs kept for the parser, and the charset the
        // lines were read in handed on with the patch.
        byte[] diffBytes = backend.getUnstagedDiffBytes(path, false);
        java.nio.charset.Charset lineCharset = TextEncoding.GUESS.diffLineCharset(diffBytes);
        String diffText = TextEncoding.GUESS.decodeDiff(diffBytes, lineCharset, true);
        DiffParser.Summary summary = DiffParser.summarize(diffText);
        List<DiffParser.Hunk> hunks = DiffParser.parse(diffText);
        assertFalse(hunks.isEmpty(), () -> "the engine reported no hunks for " + path + ":\n" + diffText);

        for (DiffParser.Hunk hunk : hunks) {
            HunkSelection.Patch patch = HunkSelection.stage(hunk);
            assertFalse(patch.isNoop(), () -> "the patch built from this hunk does nothing:\n" + diffText);
            GitResult staged = backend.applyToIndex(path,
                    patch.toIndexEdit(TextEncoding.GUESS, lineCharset, summary, true), "Staged hunk");
            assertTrue(staged.isOk(), () -> "staging failed: " + staged.getMessage() + "\n" + diffText);
        }
    }

    /** Commits {@code content} at {@code path} exactly as given, with no rewriting. */
    private static void commitBytes(GitBackend backend, String path, String content) throws IOException {
        Files.write(new File(backend.getWorkTree(), path).toPath(),
                content.getBytes(StandardCharsets.UTF_8));
        assertTrue(backend.stageFile(path).isOk());
        assertTrue(backend.commit("add " + path, false).isOk());
    }

    /** The staged copy of {@code path}, as bytes - what a commit right now would record. */
    private static byte[] indexBytes(GitBackend backend, String path) {
        return backend.getWorkingTreeSides(path)[0];
    }

    private static void assertIndexHolds(String expected, GitBackend backend, String path) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), indexBytes(backend, path),
                () -> "index holds " + describe(new String(indexBytes(backend, path), StandardCharsets.UTF_8))
                        + " but should hold " + describe(expected));
    }

    /** Line endings made visible, because that is the whole of what these tests are about. */
    private static String describe(String content) {
        return '"' + content.replace("\r", "\\r").replace("\n", "\\n") + '"';
    }

    /**
     * D3. A CRLF file, staged one hunk at a time.
     *
     * <p>The diff arrives with its CRs stripped for display; the index blob still has
     * them. Comparing the two directly means nothing ever matches, so every hunk
     * operation on CRLF content failed with "the surrounding lines have changed" - on the
     * platform where CRLF files are most common. Nothing about the message suggested the
     * file was fine and the matcher was wrong.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aHunkOfACrlfFileStagesWithItsLineEndingsIntact(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitBytes(backend, "crlf.txt", "one\r\ntwo\r\nthree\r\n");

        write(backend, "crlf.txt", "one\r\nTWO\r\nthree\r\n");
        stageEveryHunkThroughTheUiChain(backend, "crlf.txt");

        assertIndexHolds("one\r\nTWO\r\nthree\r\n", backend, "crlf.txt");
    }

    /**
     * D2. A file with no final newline, staged one hunk at a time.
     *
     * <p>Such a hunk carries git's {@code \\\\ No newline at end of file} note, which the
     * parser used to hand on as though it were a line of the file. It went into the block
     * to search for, the file had never contained it, and the operation failed. Every
     * file without a trailing newline - C sources, dotfiles, generated fixtures - was
     * affected whenever the hunk reached the last line.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aHunkOfAFileWithNoFinalNewlineStages(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitBytes(backend, "bare.txt", "one\ntwo");

        write(backend, "bare.txt", "one\nTWO");
        stageEveryHunkThroughTheUiChain(backend, "bare.txt");

        assertIndexHolds("one\nTWO", backend, "bare.txt");
    }

    /**
     * D3, second half. Removing a file's final newline is the whole of the change.
     *
     * <p>The patcher used to take the result's trailing newline from the file it was
     * patching, so this edit staged a blob that still ended with the newline the user had
     * just removed - a silent disagreement between what the diff showed and what was
     * staged, with no error to notice. The marker in the hunk is the only thing that says
     * otherwise, which is why D2 had to be fixed before this could be.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void removingTheFinalNewlineIsWhatGetsStaged(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitBytes(backend, "trailing.txt", "one\ntwo\n");

        write(backend, "trailing.txt", "one\ntwo");
        stageEveryHunkThroughTheUiChain(backend, "trailing.txt");

        assertIndexHolds("one\ntwo", backend, "trailing.txt");
    }

    /** And the other direction: adding a final newline to a file that had none. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void addingAFinalNewlineIsWhatGetsStaged(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitBytes(backend, "grow.txt", "one\ntwo");

        write(backend, "grow.txt", "one\ntwo\n");
        stageEveryHunkThroughTheUiChain(backend, "grow.txt");

        assertIndexHolds("one\ntwo\n", backend, "grow.txt");
    }

    /**
     * D5. A squash run's message means the same thing on every engine.
     *
     * <p>The graph's cherry-pick flow marks a step {@code SQUASH} and puts the combined
     * message on that same step. JGit reads a run's message from the leader {@code pick}
     * only, so the user's text was dropped and git's default concatenation appeared
     * instead; the CLI engine amends once per message-bearing step, so the last one won.
     * The same plan produced different commits on different engines, and the failure was
     * silent - the wrong message is not an error, just a commit nobody reads until later.
     *
     * <p>{@code RebaseTodo.normalized()} settles it in the model, so this asserts the one
     * thing that matters: whichever engine ran it, the commit says what the user typed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSquashRunsMessageSurvivesOnEveryEngine(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitBytes(backend, "s1.txt", "one\n");
        commitBytes(backend, "s2.txt", "two\n");

        // The plan the cherry-pick flow builds: fold the newest into the one before it,
        // with the message on the squash step - where it does not belong, and where this
        // used to be lost.
        List<com.jfx.git.model.CommitInfo> recent = new ArrayList<>();
        for (var row : backend.getCommitGraphPage(3, GraphOrder.current()).rows()) {
            if (!row.isWipNode()) recent.add(row.getCommit());
        }
        RebaseTodo plan = RebaseTodo.fromNewestFirst(recent.subList(0, 2))
                .withAction(1, RebaseTodo.Action.SQUASH)
                .withMessage(1, "the message the user typed");

        GitResult rebased = backend.rebaseInteractive(plan);

        assertTrue(rebased.isOk(), () -> "rebase failed: " + rebased.getMessage());
        assertEquals("the message the user typed", backend.getHeadCommitMessage().trim(),
                "the combined commit must say what the user typed, on every engine");
    }

    /**
     * N10f. A hunk staged in the encoding the user picked, not the one a guesser infers.
     *
     * <p>Staging a hunk rewrites the whole index blob from text the backend decoded
     * itself, so the encoding it reads with is the encoding every untouched line is
     * written back in. The viewer has a dropdown for exactly the files where guessing is
     * not good enough, and the hunk paths used to ignore it and guess anyway.
     *
     * <p>UTF-16LE without a BOM is the case that makes this visible: it is guessable in
     * principle and easy to get wrong in practice, and getting it wrong rewrites lines
     * the user never touched. The assertion is on the resulting blob, byte for byte.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aHunkStagesInTheEncodingTheUserChose(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        byte[] original = "one\ntwo\nthree\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        Files.write(new File(backend.getWorkTree(), "wide.txt").toPath(), original);
        assertTrue(backend.stageFile("wide.txt").isOk());
        assertTrue(backend.commit("add a UTF-16LE file", false).isOk());

        GitResult staged = backend.applyToIndex("wide.txt",
                new HunkEdit(2, List.of("two"), List.of("TWO"), null, TextEncoding.UTF_16LE),
                "Staged hunk");

        assertTrue(staged.isOk(), () -> "expected success, got: " + staged.getMessage());
        assertArrayEquals(
                "one\nTWO\nthree\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE),
                indexBytes(backend, "wide.txt"),
                "every line, touched or not, must come back in the encoding it was read as");
    }

    /**
     * B20. Rename detection is on for the reference engine, so a rename is one row there
     * and would be an add plus a delete anywhere it is off.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRenameBetweenTwoCommitsIsOneRow(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String before = backend.getHeadHash();
        File workTree = backend.getWorkTree();
        Files.move(new File(workTree, "b.txt").toPath(), new File(workTree, "renamed.txt").toPath());
        assertTrue(backend.stageFile("b.txt").isOk(), "the deletion has to be staged too");
        assertTrue(backend.stageFile("renamed.txt").isOk());
        assertTrue(backend.commit("rename b.txt", false).isOk());

        List<String> files = backend.getFilesBetweenCommits(before, backend.getHeadHash());

        assertEquals(List.of("renamed.txt"), files,
                "a rename is one row, not an add plus a delete");
    }

    /** B20. A file's history, newest first, with each commit's subject and author. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void fileHistoryListsEveryCommitThatTouchedTheFile(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        var history = backend.getFileHistoryCommits("a.txt", 0);

        assertEquals(2, history.size(), name + ": a.txt was touched by two commits");
        assertEquals("second", history.get(0).getSubject(), name + ": newest first");
        assertEquals("Test User", history.get(0).getAuthorName(), name);
    }

    /**
     * A named stash reads back under its name, whichever engine wrote it.
     *
     * <p>The two engines get there by different routes - native git takes {@code -m} and
     * stores {@code On <branch>: <message>}, while JGit runs the message through {@link
     * java.text.MessageFormat} because its own default is a template - so "the name
     * survives the round trip" is exactly the kind of thing that could hold on one engine
     * and not the other.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStashKeepsTheNameItWasGiven(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "work in progress\n");

        assertTrue(backend.stash(StashOptions.everything("half the parser rewrite")).isOk());

        var entries = backend.getStashEntries();
        assertEquals(1, entries.size(), "one stash was made");
        assertTrue(entries.get(0).message().contains("half the parser rewrite"),
                () -> "the name should be in the stash message: " + entries.get(0).message());
        assertEquals("half the parser rewrite", entries.get(0).shortDescription(),
                "the graph row should show the name and nothing else");
    }

    /**
     * A stash name is stored literally.
     *
     * <p>JGit hands the message to {@code MessageFormat}, where an apostrophe is the
     * quoting character and {@code &#123;0&#125;} is a placeholder. Passed through
     * unescaped, "don't ship this" loses everything after the apostrophe and a name
     * containing braces comes back with the branch name substituted into it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStashNameSurvivesQuotesAndBraces(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "work in progress\n");
        String awkward = "don't ship {0} of this yet";

        assertTrue(backend.stash(StashOptions.everything(awkward)).isOk());

        var entries = backend.getStashEntries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).message().contains(awkward),
                () -> "the name should be stored verbatim, got: " + entries.get(0).message());
    }

    /** No name still stashes, and still says what it did. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anUnnamedStashStillWorks(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "work in progress\n");

        assertTrue(new RepositoryCommands(backend).stash().isOk(), "the no-argument form must keep working");
        assertEquals(1, backend.getStashEntries().size());
        assertEquals("one\ntwo\n", read(backend, "a.txt"), "the work should be off the tree");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Two branches that changed {@code a.txt} differently, with HEAD on {@code main}. */
    private GitBackend conflictingFixture(Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "other", "a.txt", "their side\n");
        write(backend, "a.txt", "our side\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.commit("our change", false).isOk());
        return backend;
    }

    private static RefInfo tag(GitBackend backend, String shortName) {
        return backend.getTags().stream()
                .filter(r -> shortName.equals(r.shortName()))
                .findFirst().orElse(null);
    }

    /** Writes a loose ref file directly, which is how git stores one. */
    private static void writeRefFile(GitBackend backend, String refName, String hash) throws IOException {
        Path refFile = backend.getDirectory().toPath().resolve(refName);
        Files.createDirectories(refFile.getParent());
        Files.writeString(refFile, hash + "\n");
    }

    // ------------------------------------------------------------------
    // Working-tree line counts
    // ------------------------------------------------------------------

    /**
     * The numbers beside a path in the staging list.
     *
     * <p>The fixture leaves {@code a.txt} at "one\ntwo\n"; rewriting it to three lines
     * that share only the first is two added and one removed on every engine that reports
     * anything at all. An engine that has not implemented this returns an empty map by
     * design, and the panel then draws no numbers - so the assertion is on the value when
     * a value is offered, not on there being one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void unstagedLineCountsDescribeTheWorkingTree(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "one\nsecond\nthird\n");

        var counts = backend.getWorkingTreeLineCounts(false, List.of("a.txt"));
        if (counts.isEmpty()) {
            // An engine may decline, and the panel then draws no numbers - but only an
            // engine that has actually declined. Naming the one that does keeps this from
            // quietly becoming a test that asserts nothing on the two that do not.
            assertEquals("libgit2", name,
                    name + " implements line counts, so an empty answer is a regression");
            return;
        }

        assertArrayEquals(new int[]{2, 1}, counts.get("a.txt"),
                name + " should report two lines added and one removed");
    }

    /**
     * Staged counts read the index against HEAD, so they must not move when the working
     * tree changes afterwards. Getting these two the wrong way round is the one mistake
     * this method invites, and it is invisible until a file is half staged.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void stagedAndUnstagedCountsAnswerAboutDifferentTrees(String name,
                                                          Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "one\ntwo\nstaged\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        write(backend, "a.txt", "one\ntwo\nstaged\nunstaged\n");

        var staged = backend.getWorkingTreeLineCounts(true, List.of("a.txt"));
        var unstaged = backend.getWorkingTreeLineCounts(false, List.of("a.txt"));
        if (staged.isEmpty() && unstaged.isEmpty()) {
            assertEquals("libgit2", name,
                    name + " implements line counts, so an empty answer is a regression");
            return;
        }

        assertArrayEquals(new int[]{1, 0}, staged.get("a.txt"),
                name + ": the index is one line ahead of HEAD");
        assertArrayEquals(new int[]{1, 0}, unstaged.get("a.txt"),
                name + ": the working tree is one line ahead of the index");
    }

    /** Nothing asked for, nothing read - on every engine. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void noPathsMeansNoCounts(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "changed\n");

        assertTrue(backend.getWorkingTreeLineCounts(false, List.of()).isEmpty(),
                name + " should not read anything when asked about nothing");
    }

    /**
     * The range a pull request would carry: what one branch has that another does not.
     *
     * <p>The direction is the whole risk. {@code getCommitsInRange(main, feature)} must
     * answer with the feature work, and swapping the arguments must answer with nothing -
     * a method that returned the union either way would look right in the one case the
     * dialog usually shows.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRangeReadsOneDirectionOnly(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "feature", "f.txt", "feature work\n");

        var ahead = backend.getCommitsInRange("main", "feature", 50);
        if (ahead.isEmpty()) {
            assertEquals("libgit2", name,
                    name + " implements ranges, so an empty answer is a regression");
            return;
        }

        assertEquals(1, ahead.size(), name + ": the branch carries one commit");
        var behind = backend.getCommitsInRange("feature", "main", 50);
        assertTrue(behind.isEmpty(),
                name + ": main has nothing feature lacks, so the reverse range is empty");
    }

    /** A limit is a limit on every engine. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRangeStopsAtTheLimit(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String root = fixtureCommits.get(0);

        var capped = backend.getCommitsInRange(root, "main", 1);
        if (capped.isEmpty()) {
            assertEquals("libgit2", name,
                    name + " implements ranges, so an empty answer is a regression");
            return;
        }

        assertEquals(1, capped.size(),
                name + " should return exactly the one commit it was asked for");
    }

    // ------------------------------------------------------------------
    // Per-branch tracking
    // ------------------------------------------------------------------

    /**
     * Ahead/behind for every branch, which the sidebar draws as arrows beside each one.
     *
     * <h2>Why an empty answer is indistinguishable from a healthy repository</h2>
     * The sidebar draws a badge for whatever this returns and nothing where it returns
     * nothing, so an engine that answers with an empty map looks exactly like a repository
     * where every branch is in sync. The JGit engine was taking the interface default and
     * doing precisely that - the whole feature silently absent rather than visibly missing.
     *
     * <p>The upstream here is another local branch ({@code remote = "."}), which is a real
     * git configuration and saves the fixture a bare repository and a push. Every engine
     * has to resolve it: git's {@code %(upstream:track)} does, and so does JGit's
     * {@code BranchTrackingStatus}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void everyBranchReportsHowFarAheadItIs(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "feature", "f.txt", "one commit past main\n");

        try (Git git = Git.open(backend.getWorkTree())) {
            var config = git.getRepository().getConfig();
            config.setString("branch", "feature", "remote", ".");
            config.setString("branch", "feature", "merge", "refs/heads/main");
            config.save();
        }

        var tracking = backend.getBranchTracking();

        assertArrayEquals(new int[]{1, 0}, tracking.get("feature"),
                name + ": feature carries one commit main does not; tracking = "
                        + tracking.keySet());
        assertFalse(tracking.containsKey("main"),
                name + ": main has no upstream configured, so it has nothing to report - "
                        + "and an entry of [0, 0] would put a zero beside it in the "
                        + "sidebar; tracking = " + tracking.keySet());
    }

    // ------------------------------------------------------------------
    // Status letters
    // ------------------------------------------------------------------

    /**
     * What a commit did to each file, per file.
     *
     * <h2>Why this test exists</h2>
     * {@code getChangesInCommit} is defaulted on the interface so an engine that cannot
     * answer degrades to "every path, marked M, with no counts". The JGit engine - the
     * one that ships on by default - never implemented it, so every commit's file list
     * marked a newly-created file as a modification and showed no line counts at all.
     * Nothing failed, because the fallback is a working answer; it is just the wrong one.
     * A conformance test is exactly the instrument that should have caught an engine
     * silently taking the degraded path, and it was not looking here.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aCommitReportsWhatItDidToEachFile(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        // a.txt is tracked and edited, b.txt is tracked and deleted, c.txt is brand new.
        write(backend, "a.txt", "one\ntwo\nthree\n");
        Files.delete(new File(backend.getWorkTree(), "b.txt").toPath());
        write(backend, "c.txt", "fresh\n");
        assertTrue(backend.stageAll().isOk());
        assertTrue(backend.commit("a mix of changes", false).isOk());

        var changes = backend.getChangesInCommit(backend.getHeadHash());
        assertEquals(3, changes.size(), name + ": three files were touched");

        var byPath = new java.util.HashMap<String, GitBackend.FileChange>();
        changes.forEach(change -> byPath.put(change.path(), change));

        assertEquals('M', byPath.get("a.txt").status(), name + ": a.txt was edited");
        assertEquals('D', byPath.get("b.txt").status(), name + ": b.txt was deleted");
        assertEquals('A', byPath.get("c.txt").status(),
                name + ": c.txt is new, and must not read as a modification");
    }

    /**
     * The same question about two commits rather than one.
     *
     * <h2>Why this test exists</h2>
     * The comparison view asked only for the paths that differ and stamped each one
     * {@code M}, so a file the later commit created read as a modification - the very fault
     * the test above pins for a single commit, back again one view over.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aComparisonReportsWhatDiffersInEachFile(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String before = backend.getHeadHash();
        // The fixture leaves a.txt at "one\ntwo\n"; this keeps one line and adds two.
        write(backend, "a.txt", "one\nsecond\nthird\n");
        Files.delete(new File(backend.getWorkTree(), "b.txt").toPath());
        write(backend, "c.txt", "fresh\n");
        assertTrue(backend.stageAll().isOk());
        assertTrue(backend.commit("a mix of changes", false).isOk());
        String after = backend.getHeadHash();

        var forward = byPath(backend.getChangesBetweenCommits(before, after));
        assertEquals(3, forward.size(), name + ": three files differ");
        assertEquals('M', forward.get("a.txt").status(), name + ": a.txt was edited");
        assertEquals('D', forward.get("b.txt").status(), name + ": b.txt is gone");
        assertEquals('A', forward.get("c.txt").status(),
                name + ": c.txt is new, and must not read as a modification");
        assertEquals(2, forward.get("a.txt").added(), name + ": two lines added");
        assertEquals(1, forward.get("a.txt").deleted(), name + ": one line removed");

        var backward = byPath(backend.getChangesBetweenCommits(after, before));
        assertEquals('A', backward.get("b.txt").status(),
                name + ": the other way round, the deleted file comes back");
        assertEquals('D', backward.get("c.txt").status(),
                name + ": and the added one goes away");
    }

    /**
     * A rename names where it came from, and the diff is taken across it.
     *
     * <p>Narrowed to the new path alone, a diff cannot see the old one and prints the whole
     * file as an addition - so a one-line edit to a moved file read as a new file.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRenameNamesItsOldPathAndDiffsAcrossTheMove(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String body = "alpha\nbravo\ncharlie\ndelta\necho\nfoxtrot\ngolf\nhotel\n";
        write(backend, "old.txt", body);
        assertTrue(backend.stageFile("old.txt").isOk());
        assertTrue(backend.commit("add old.txt", false).isOk());
        String before = backend.getHeadHash();

        Files.delete(new File(backend.getWorkTree(), "old.txt").toPath());
        write(backend, "new.txt", body.replace("delta", "DELTA"));
        assertTrue(backend.stageAll().isOk());
        assertTrue(backend.commit("move and touch", false).isOk());
        String after = backend.getHeadHash();

        for (var changes : List.of(backend.getChangesInCommit(after),
                backend.getChangesBetweenCommits(before, after))) {
            assertEquals(1, changes.size(), name + ": a rename is one row: " + changes);
            var change = changes.get(0);
            assertEquals('R', change.status(), name + ": " + change);
            assertEquals("new.txt", change.path(), name + ": the row is the new name");
            assertEquals("old.txt", change.oldPath(), name + ": and knows the old one");
        }

        String inCommit = new String(backend.getDiffBytesForFileInCommit(
                after, "new.txt", "old.txt", false), java.nio.charset.StandardCharsets.UTF_8);
        String between = new String(backend.getDiffBytesBetweenCommits(
                before, after, "new.txt", "old.txt", false), java.nio.charset.StandardCharsets.UTF_8);
        for (String diff : List.of(inCommit, between)) {
            assertTrue(diff.contains("rename from old.txt"),
                    () -> name + ": the diff should pair the two names, got:\n" + diff);
            assertTrue(diff.contains("-delta") && diff.contains("+DELTA"),
                    () -> name + ": and show the one line that changed, got:\n" + diff);
            assertFalse(diff.contains("+alpha"),
                    () -> name + ": not the whole file as new, got:\n" + diff);
        }
    }

    /**
     * A file restored from a commit into the name it has now - it was renamed since - is
     * written there and only there: the commit's name for it is not recreated, the index is
     * not touched, and a path the commit lacks is refused without writing anything.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void restoringAFileIntoTheNameItHasNowWritesThatFileAlone(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String first = fixtureCommits.get(0);
        File workTree = backend.getWorkTree();
        // a.txt is moved.txt now, with uncommitted work in it.
        Files.move(new File(workTree, "a.txt").toPath(), new File(workTree, "moved.txt").toPath());
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.stageFile("moved.txt").isOk());
        assertTrue(backend.commit("move a.txt", false).isOk());
        write(backend, "moved.txt", "uncommitted\n");
        byte[] index = indexFile(backend);

        GitResult restored = backend.restoreFileFromCommit(first, "a.txt", "moved.txt");

        assertTrue(restored.isOk(), () -> name + ": " + restored.getMessage());
        assertEquals("one\n", read(backend, "moved.txt"), name + ": the first commit's a.txt, under today's name");
        assertFalse(new File(workTree, "a.txt").exists(), name + ": the old name is not recreated");
        assertArrayEquals(index, indexFile(backend), name + ": the restore is not staged");

        assertTrue(backend.restoreFileFromCommit(first, "a.txt", "deep/er/a.txt").isOk(),
                name + ": a destination's missing folders are made");
        assertEquals("one\n", read(backend, "deep/er/a.txt"));

        GitResult refused = backend.restoreFileFromCommit(first, "b.txt", "nowhere.txt");
        assertFalse(refused.isOk(), name + ": the first commit has no b.txt");
        assertTrue(refused.getMessage().contains("does not exist"), () -> name + ": " + refused.getMessage());
        assertFalse(new File(workTree, "nowhere.txt").exists(), name + ": and nothing is written for it");
        assertArrayEquals(index, indexFile(backend), name + ": the index is still untouched");

        assertTrue(backend.restoreFileFromCommit(first, "a.txt", "a.txt").isOk(),
                name + ": into its own name it is the plain restore");
        assertEquals("one\n", read(backend, "a.txt"));
    }

    /**
     * A restore writes inside the working tree, never through a link out of it - into
     * another name or into the file's own. The CLI engine resolves its destination through
     * {@link WorktreePaths} (and git itself replaces a link rather than following it); the
     * JGit engine wrote straight to the path, so a link there sent the commit's bytes to
     * whatever it pointed at.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRestoreFromACommitNeverWritesThroughALinkOutOfTheWorkTree(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String first = fixtureCommits.get(0);
        Path outside = root.resolve("outside-" + fixtureCount + ".txt");
        Files.writeString(outside, "not the repository's\n");
        Path workTree = backend.getWorkTree().toPath();
        try {
            Files.createSymbolicLink(workTree.resolve("linked.txt"), outside);
            Files.delete(workTree.resolve("a.txt"));
            Files.createSymbolicLink(workTree.resolve("a.txt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException noLinks) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "cannot create symlinks here: " + noLinks);
        }

        backend.restoreFileFromCommit(first, "a.txt", "linked.txt");
        assertEquals("not the repository's\n", Files.readString(outside),
                name + ": restoring into another name must not write through its link");

        backend.restoreFileFromCommit(first, "a.txt");
        assertEquals("not the repository's\n", Files.readString(outside),
                name + ": restoring a file whose working copy is a link must not write through it");
    }

    /**
     * A merge commit lists what it brought in from the merged branch.
     *
     * <p>{@code git diff-tree} given one merge commit prints nothing unless told which
     * parent to diff against, so on the native engine every merge - and every stash, which
     * is a merge too - showed an empty file list.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aMergeCommitListsWhatItBroughtIn(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "side", "side.txt", "side\n");
        write(backend, "main.txt", "main\n");
        assertTrue(backend.stageFile("main.txt").isOk());
        assertTrue(backend.commit("only on main", false).isOk());
        assertTrue(new RepositoryCommands(backend).merge("side", true).isOk());

        var changes = byPath(backend.getChangesInCommit(backend.getHeadHash()));

        assertEquals(java.util.Set.of("side.txt"), changes.keySet(),
                name + ": against its first parent, the merge brought in side.txt alone");
        assertEquals('A', changes.get("side.txt").status(), name);
        assertEquals(List.of("side.txt"), backend.getFilesInCommit(backend.getHeadHash()),
                name + ": the path-only listing must agree, merge or not");
    }

    /**
     * On a detached HEAD what the user reads names the commit, by its hash - and every
     * engine says it alike. The three engines answered three ways: JGit the full hash,
     * the CLI an abbreviation, libgit2 the literal "HEAD", which the status bar showed as
     * if it were a branch called HEAD.
     *
     * <p>Since the ref snapshot (ADR 0002) a detached HEAD has no current branch at all -
     * {@code getCurrentBranch()} is blank on every engine - and the name the user reads is
     * worked out in one place, {@link com.jfx.git.model.RefNames#headLabel}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aDetachedHeadIsNamedByItsCommit(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String commit = parentOfHead();
        assertTrue(backend.checkoutBranch(commit).isOk());

        String current = backend.getCurrentBranch();

        assertNotNull(current, name);
        assertEquals("", current, name + ": a detached HEAD has no current branch");
        assertEquals("Detached at " + commit.substring(0, 7),
                com.jfx.git.model.RefNames.headLabel(current, backend.getHeadHash()),
                name + ": what the user reads names the commit");
    }

    /**
     * A merge with no message of the user's reads as git's own default on every engine.
     * JGit wrote "Merge side", so the same merge read differently in history depending on
     * which engine had made it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aDefaultMergeMessageIsGits(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "side", "side.txt", "side\n");
        write(backend, "main.txt", "main\n");
        assertTrue(backend.stageFile("main.txt").isOk());
        assertTrue(backend.commit("only on main", false).isOk());

        assertTrue(new RepositoryCommands(backend).merge("side", true).isOk());

        assertEquals("Merge branch 'side'", backend.getHeadCommitMessage().strip(), name);
    }

    /** No upstream means nothing ahead and nothing behind - not null on one engine only. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBranchWithNoUpstreamTracksNothing(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);

        assertArrayEquals(new int[]{0, 0}, backend.getTrackingStatus(), name);
    }

    /**
     * Checking out a name no branch has must fail - not restore the files of a folder
     * that happens to share it. Native git read such a name as a pathspec and reverted
     * the folder's uncommitted edits, exiting 0, so this reported "Switched to branch".
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void checkingOutAMissingBranchLeavesAFolderOfThatNameAlone(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        assertTrue(new File(backend.getWorkTree(), "docs").mkdirs());
        write(backend, "docs/guide.md", "v1\n");
        assertTrue(backend.stageFile("docs/guide.md").isOk());
        assertTrue(backend.commit("add docs", false).isOk());
        write(backend, "docs/guide.md", "my uncommitted edit\n");

        GitResult result = backend.checkoutBranch("docs");

        assertFalse(result.isOk(), name + ": there is no branch called docs: " + result.getMessage());
        assertEquals("my uncommitted edit\n", read(backend, "docs/guide.md"),
                name + ": the edit must survive");
    }

    /**
     * A remote branch checked out over a local edit it would overwrite is refused, and the
     * refusal leaves no local branch behind. The JGit engine created the tracking branch
     * and then failed the checkout, so cancelling the sidebar's "Stash and check out"
     * still added a branch, upstream and all, that the user had declined. Git refuses
     * before it creates anything.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRefusedRemoteCheckoutLeavesNoBranchBehind(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        onlyOnTheRemote(backend, "feature", "a.txt", "changed on feature\n");
        write(backend, "a.txt", "my uncommitted edit\n");

        GitResult result = backend.checkoutRemoteBranch("origin/feature");

        assertInstanceOf(Refusal.LocalChanges.class, result.getRefusal(),
                name + ": the edit is in the way: " + result.getMessage());
        assertEquals("main", backend.getCurrentBranch(), name);
        assertEquals("my uncommitted edit\n", read(backend, "a.txt"), name + ": the edit must survive");
        try (Git git = Git.open(backend.getWorkTree())) {
            assertNull(git.getRepository().exactRef("refs/heads/feature"),
                    name + ": a refused checkout must not leave the branch it would have made");
            assertNull(git.getRepository().getConfig().getString("branch", "feature", "remote"),
                    name + ": nor that branch's upstream");
        }
    }

    /**
     * The counterpart: with nothing in the way, the branch is made at the remote tip,
     * tracks it, and HEAD moves onto it with git's own reflog line.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRemoteCheckoutMakesATrackingBranchAndSaysSoInTheReflog(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String tip = onlyOnTheRemote(backend, "feature", "a.txt", "changed on feature\n");

        GitResult result = backend.checkoutRemoteBranch("origin/feature");

        assertTrue(result.isOk(), name + ": " + result.getMessage());
        assertEquals("changed on feature\n", read(backend, "a.txt"), name);
        try (Git git = Git.open(backend.getWorkTree())) {
            var repo = git.getRepository();
            assertEquals("refs/heads/feature", repo.getFullBranch(), name);
            assertEquals(tip, repo.resolve("HEAD").name(), name + ": the branch starts at the remote tip");
            assertEquals("origin", repo.getConfig().getString("branch", "feature", "remote"), name);
            assertEquals("refs/heads/feature", repo.getConfig().getString("branch", "feature", "merge"), name);
            assertEquals("checkout: moving from main to feature",
                    repo.getReflogReader("HEAD").getLastEntry().getComment(), name);
        }
    }

    /**
     * {@code origin/<branch>} with one commit changing {@code path}, and no local branch of
     * that name - as after a fetch. {@code origin} is configured, which is what lets a
     * checkout set it up as the upstream, but never contacted.
     *
     * @return the remote branch's tip
     */
    private static String onlyOnTheRemote(GitBackend backend, String branch, String path, String content)
            throws Exception {
        commitOnBranch(backend, branch, path, content);
        try (Git git = Git.open(backend.getWorkTree())) {
            var repo = git.getRepository();
            var config = repo.getConfig();
            config.setString("remote", "origin", "url",
                    backend.getWorkTree().toPath().resolveSibling("never-fetched.git").toString());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.save();
            var tip = repo.resolve("refs/heads/" + branch);
            var tracking = repo.updateRef("refs/remotes/origin/" + branch);
            tracking.setNewObjectId(tip);
            tracking.update();
            git.branchDelete().setBranchNames(branch).setForce(true).call();
            return tip.name();
        }
    }

    /**
     * A stash whose ref cannot be written must leave the work where it is. The JGit
     * engine wrote the stash commit, ignored that recording it had failed, and then
     * cleared the paths - leaving the work reachable only through fsck. Git refuses
     * ("Cannot save the current status") and touches nothing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStashThatCannotBeRecordedLeavesTheWorkInPlace(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File lock = new File(backend.getDirectory(), "refs/stash.lock");
        for (var options : List.of(
                new com.jfx.git.model.StashOptions(null, false, false, List.of("a.txt")),
                com.jfx.git.model.StashOptions.everything(null))) {
            write(backend, "a.txt", "edited\n");
            write(backend, "b.txt", "also edited\n");
            Files.writeString(lock.toPath(), "");
            try {
                GitResult result = backend.stash(options);

                assertFalse(result.isOk(), name + " " + options + ": the stash could not be recorded");
                assertEquals("edited\n", read(backend, "a.txt"), name + " " + options);
                assertEquals("also edited\n", read(backend, "b.txt"), name + " " + options);
            } finally {
                Files.deleteIfExists(lock.toPath());
            }
        }
    }

    /**
     * A reflog row says who moved HEAD and when - not who wrote the commit it moved to.
     * The native engine read the commit's author and author date, so a reset made today
     * showed a commit's 2001 date and someone else's name.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theReflogSaysWhoMovedHeadAndWhen(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String byAda;
        try (Git git = Git.open(backend.getWorkTree())) {
            var ada = new org.eclipse.jgit.lib.PersonIdent("Ada Author", "ada@example.com",
                    java.time.Instant.parse("2001-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
            Files.writeString(new File(backend.getWorkTree(), "c.txt").toPath(), "by ada\n");
            git.add().addFilepattern("c.txt").call();
            byAda = git.commit().setMessage("by ada").setAuthor(ada).setCommitter(ada)
                    .setSign(false).call().getName();
            Files.writeString(new File(backend.getWorkTree(), "c.txt").toPath(), "after\n");
            git.add().addFilepattern("c.txt").call();
            git.commit().setMessage("after ada").setSign(false).call();
        }

        assertTrue(backend.reset(byAda, com.jfx.git.model.ResetMode.MIXED).isOk());
        var reflog = backend.getReflog(2);

        assertEquals(2, reflog.size(), name);
        assertEquals(byAda, reflog.get(0).newHash(), name);
        assertEquals("Test User", reflog.get(0).author(),
                name + ": who moved HEAD, not who wrote the commit it moved to");
        assertTrue(java.time.Duration.between(reflog.get(0).when(), java.time.Instant.now())
                        .abs().toHours() < 1,
                name + ": when HEAD moved, not when the commit was written: " + reflog.get(0).when());
        assertNotNull(reflog.get(1).oldHash(),
                name + ": the last row shown still knows where HEAD came from - "
                        + "without it the row reads as a branch being created");
    }

    /**
     * And a root commit, which has no first parent to diff against, lists what it created.
     * The native engine names the first parent as {@code hash^1}, and reaches a root
     * commit only through its fallback.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRootCommitListsWhatItCreated(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        var changes = backend.getChangesInCommit(fixtureCommits.get(0));

        assertEquals(1, changes.size(), name + ": the first commit added a.txt: " + changes);
        assertEquals("a.txt", changes.get(0).path(), name);
        assertEquals('A', changes.get(0).status(), name);
        assertEquals(1, changes.get(0).added(), name + ": its one line");
    }

    private static java.util.Map<String, GitBackend.FileChange> byPath(
            List<GitBackend.FileChange> changes) {
        var byPath = new java.util.HashMap<String, GitBackend.FileChange>();
        changes.forEach(change -> byPath.put(change.path(), change));
        return byPath;
    }

    /** And by how much, for the file whose change is known exactly. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aCommitReportsHowMuchEachFileChanged(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        // The fixture leaves a.txt at "one\ntwo\n"; this keeps one line and adds two.
        write(backend, "a.txt", "one\nsecond\nthird\n");
        assertTrue(backend.stageAll().isOk());
        assertTrue(backend.commit("edit a.txt", false).isOk());

        var changes = backend.getChangesInCommit(backend.getHeadHash());
        var change = changes.stream()
                .filter(c -> "a.txt".equals(c.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + ": a.txt missing"));

        assertTrue(change.hasCounts(),
                name + ": a text file's line counts are knowable, and a row with none "
                        + "shows the status letter alone");
        assertEquals(2, change.added(), name + ": two lines added");
        assertEquals(1, change.deleted(), name + ": one line removed");
    }

    /**
     * The same question about work that is not committed yet.
     *
     * <p>{@code StagingState.statusOf} has the same {@code M} fallback for an engine that
     * supplies no letters, and the JGit engine was supplying none - so the staging lists
     * marked every untracked file as a modification too.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theStagingListsKnowAnAddFromAnEdit(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "edited\n");
        write(backend, "brand-new.txt", "new file\n");
        Files.delete(new File(backend.getWorkTree(), "b.txt").toPath());

        var state = backend.getStagingState();

        assertEquals('M', state.statusOf("a.txt"), name + ": a.txt was edited");
        assertEquals('A', state.statusOf("brand-new.txt"),
                name + ": an untracked file is an add, not a modification");
        assertEquals('D', state.statusOf("b.txt"), name + ": b.txt is gone");
    }

    /** Staging a new file must not turn its letter back into a modification. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStagedAddStillReadsAsAnAdd(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "brand-new.txt", "new file\n");
        assertTrue(backend.stageFile("brand-new.txt").isOk());

        var state = backend.getStagingState();

        assertTrue(state.staged().contains("brand-new.txt"), name + ": sanity");
        assertEquals('A', state.statusOf("brand-new.txt"),
                name + ": it is in the index now, and it is still an add");
    }

    /**
     * A staged rename is one move, not an add beside a delete.
     *
     * <h2>Why one row matters beyond tidiness</h2>
     * JGit's {@code Status} runs no similarity pass, so {@code git mv} reached it as two
     * unrelated facts and the staging list drew two rows for one move. Beside being wrong
     * about what happened, it offers an Unstage button on each half - and unstaging one
     * half of a rename leaves the index holding a delete without its add, or the reverse.
     * The CLI engine folds the pair in porcelain v2 and libgit2 folds it with
     * {@code RENAMES_HEAD_TO_INDEX}; this pins that the third engine agrees.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStagedRenameIsOneRowNotTwo(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        // The content has to survive the move intact, or there is no rename to detect -
        // a similarity pass is comparing blobs, not filenames.
        File workTree = backend.getWorkTree();
        String content = Files.readString(new File(workTree, "a.txt").toPath());
        Files.delete(new File(workTree, "a.txt").toPath());
        Files.writeString(new File(workTree, "renamed.txt").toPath(), content);
        assertTrue(backend.stageAll().isOk());

        var state = backend.getStagingState();

        // Whatever an engine reports, every path it names must be one that exists. This
        // is the assertion libgit2 was failing: with its own rename detection on it named
        // the row after the pre-move path, so the panel offered actions on a file that
        // was no longer there.
        assertTrue(state.staged().contains("renamed.txt"),
                name + ": the row must name the file as it is now; staged = "
                        + state.staged());

        if (!"libgit2".equals(name)) {
            assertEquals('R', state.statusOf("renamed.txt"),
                    name + ": a move is a rename, not an add");
            assertFalse(state.staged().contains("a.txt"),
                    name + ": the old path must not survive as a second row - unstaging "
                            + "one half of a rename leaves the index inconsistent; "
                            + "staged = " + state.staged());
            return;
        }

        // libgit2 reports the pair instead of the move, on purpose - see STATUS_FLAGS
        // there for why naming the folded row correctly would cost hash-size-dependent
        // struct offsets. Pinned rather than skipped, so that if the binding ever grows
        // that capability this test says so instead of quietly still passing.
        assertEquals('A', state.statusOf("renamed.txt"),
                name + " reports the move as an add beside a delete");
        assertTrue(state.staged().contains("a.txt"),
                name + ": and the delete is the other half; staged = " + state.staged());
        assertEquals('D', state.statusOf("a.txt"), name + ": both paths are real");
    }

    /**
     * The staged copy is the index blob, byte for byte - not the working tree, and not
     * a re-encoding of either.
     *
     * <p>The diff viewer reveals a staged diff's context from this, so a file edited after
     * staging would otherwise fill the gaps with lines that were never staged. Written in
     * Latin-1 so that decoding and re-encoding on the way through would show.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void stagedBytesAreTheIndexBlobNotTheWorkingTree(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        byte[] staged = "café staged\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(new File(backend.getWorkTree(), "a.txt").toPath(), staged);
        assertTrue(backend.stageFile("a.txt").isOk());
        write(backend, "a.txt", "edited after staging\n");

        assertArrayEquals(staged, backend.getStagedBytes("a.txt"),
                name + ": the staged bytes must be the index blob exactly");
        assertEquals(0, backend.getStagedBytes("not-tracked.txt").length,
                name + ": a path not in the index has no staged copy");
    }

    // ------------------------------------------------------------------
    // Repository discovery, submodules, sparse checkout, commit template
    // ------------------------------------------------------------------

    /**
     * Opening a folder inside a work tree opens its repository, and every engine names
     * the same root (E6). libgit2 used the no-search {@code git_repository_open} and
     * could not open a subfolder at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSubfolderOpensItsRepositoryAtTheRoot(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend first = fixture(factory);
        File workTree = first.getWorkTree().getCanonicalFile();
        File sub = new File(workTree, "nested/deeper");
        assertTrue(sub.mkdirs());

        GitBackend fromSub = open(factory, sub);

        assertEquals(workTree, fromSub.getWorkTree().getCanonicalFile(),
                name + ": a subfolder must open the repository it belongs to");
        write(fromSub, "c.txt", "c\n");
        assertTrue(fromSub.getStagingState().unstaged().contains("c.txt"), name + ": and it must be usable");
    }

    /**
     * A submodule pointer that moved is a change on every engine (E5). libgit2 was asked
     * to exclude submodules, so on the default engine a bump - including one staged by
     * another tool - never appeared in the staging list and was committed unseen.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSubmodulePointerChangeIsReported(String name, Supplier<GitBackend> factory) throws Exception {
        File library = root.resolve("library" + (fixtureCount++)).toFile();
        try (Git lib = Git.init().setDirectory(library).setInitialBranch("main").call()) {
            configure(lib);
            Files.writeString(new File(library, "lib.txt").toPath(), "one\n");
            lib.add().addFilepattern("lib.txt").call();
            lib.commit().setMessage("lib one").call();
        }
        File superDir = root.resolve("super" + (fixtureCount++)).toFile();
        try (Git sup = Git.init().setDirectory(superDir).setInitialBranch("main").call()) {
            configure(sup);
            sup.getRepository().getConfig().setBoolean("protocol", "file", "allow", true);
            sup.getRepository().getConfig().save();
            Files.writeString(new File(superDir, "app.txt").toPath(), "app\n");
            sup.add().addFilepattern("app.txt").call();
            sup.commit().setMessage("app").call();
            sup.submoduleAdd().setURI(library.toURI().toString()).setPath("lib").call().close();
            sup.commit().setMessage("add lib").call();
        }
        // Advance the submodule's checkout: the superproject now records an old pointer.
        File checkout = new File(superDir, "lib");
        try (Git sub = Git.open(checkout)) {
            configure(sub);
            Files.writeString(new File(checkout, "lib.txt").toPath(), "two\n");
            sub.add().addFilepattern("lib.txt").call();
            sub.commit().setMessage("lib two").call();
        }

        GitBackend backend = open(factory, superDir);
        assertTrue(backend.getStagingState().unstaged().contains("lib"),
                name + ": a moved submodule pointer must be listed; unstaged = " + backend.getStagingState().unstaged());

        try (Git sup = Git.open(superDir)) {
            sup.add().addFilepattern("lib").call();
        }
        assertTrue(backend.getStagedFiles().contains("lib"),
                name + ": a staged submodule bump must be listed; staged = " + backend.getStagedFiles());
    }

    /** Sparse checkout reads as on everywhere once core.sparseCheckout is set (E9). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void sparseCheckoutIsReportedByEveryEngine(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        assertFalse(backend.isSparseCheckoutEnabled(), name + ": a fresh repository is not sparse");

        File workTree = backend.getWorkTree();
        File gitDir = backend.getDirectory();
        backend.close();
        try (Git git = Git.open(workTree)) {
            git.getRepository().getConfig().setBoolean("core", null, "sparseCheckout", true);
            git.getRepository().getConfig().save();
        }
        Files.createDirectories(gitDir.toPath().resolve("info"));
        Files.writeString(gitDir.toPath().resolve("info/sparse-checkout"), "/*\n");
        GitBackend reopened = open(factory, workTree);

        assertTrue(reopened.isSparseCheckoutEnabled(),
                name + ": core.sparseCheckout=true must read as sparse");
    }

    /** Every engine finds the same commit.template. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theCommitTemplateIsTheSameOnEveryEngine(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        assertEquals("", backend.getCommitTemplate(), name + ": no template configured");

        Path template = root.resolve("template" + (fixtureCount++) + ".txt");
        Files.writeString(template, "Summary\n\n# Why?\n");
        File workTree = backend.getWorkTree();
        backend.close();
        try (Git git = Git.open(workTree)) {
            git.getRepository().getConfig().setString("commit", null, "template",
                    template.toString().replace('\\', '/'));
            git.getRepository().getConfig().save();
        }
        GitBackend reopened = open(factory, workTree);

        assertEquals("Summary\n\n# Why?\n", reopened.getCommitTemplate(), name);
    }

    /**
     * Every engine names the same committer for a sign-off, and a {@code committer.*}
     * setting wins over {@code user.*} as it does in git.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theSignOffIdentityIsTheSameOnEveryEngine(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        assertEquals("Test User <test@example.com>", backend.getCommitIdentity(), name);

        File workTree = backend.getWorkTree();
        backend.close();
        try (Git git = Git.open(workTree)) {
            git.getRepository().getConfig().setString("committer", null, "email", "ci@example.com");
            git.getRepository().getConfig().save();
        }
        GitBackend reopened = open(factory, workTree);

        assertEquals("Test User <ci@example.com>", reopened.getCommitIdentity(), name);
    }

    /**
     * A batch cherry-pick takes each merge against the parent the caller chose, not
     * always parent 1: here parent 2, so what lands is main's side of the merge rather
     * than the branch's.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBatchCherryPickUsesTheMainlineChosenForEachMerge(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String base = backend.getHeadHash();
        commitOnBranch(backend, "other", "c.txt", "from the branch\n");
        write(backend, "d.txt", "from main\n");
        assertTrue(backend.stageFile("d.txt").isOk());
        assertTrue(backend.commit("main change", false).isOk());
        assertTrue(new RepositoryCommands(backend).merge("other", true).isOk());
        String merge = backend.getHeadHash();
        write(backend, "e.txt", "after the merge\n");
        assertTrue(backend.stageFile("e.txt").isOk());
        assertTrue(backend.commit("after merge", false).isOk());
        String after = backend.getHeadHash();
        assertTrue(backend.createBranch("target", base, true).isOk());

        GitResult picked = new RepositoryCommands(backend).cherryPickAll(List.of(merge, after), java.util.Map.of(merge, 2));

        assertTrue(picked.isOk(), picked::getMessage);
        assertEquals("from main\n", read(backend, "d.txt"), "relative to parent 2, main's change is what the merge brought");
        assertFalse(new File(backend.getWorkTree(), "c.txt").exists(), "and the branch's is not");
        assertEquals("after the merge\n", read(backend, "e.txt"), "the ordinary commit after it follows");
    }

    // ------------------------------------------------------------------
    // The commit graph and history: every engine draws the reference's rows
    // ------------------------------------------------------------------

    /**
     * A repository with the things {@code git log --all} used to drag into the graph: a
     * stash, a note, a commit reachable only from the app's own {@code refs/lyra/}, and a
     * tag named like a branch. Built with JGit so every engine starts from the same state.
     *
     * <p>main: first - second - third, feature off second with two commits, an annotated
     * tag {@code v1} and a lightweight tag {@code main} on first.
     */
    private File graphFixture() throws Exception {
        File dir = root.resolve("graph" + (fixtureCount++)).toFile();
        assertTrue(dir.mkdirs());
        fixtureCommits.clear();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            configure(git);
            commit(git, "a.txt", "one\n", "first");
            commit(git, "a.txt", "one\ntwo\n", "second");
            git.checkout().setCreateBranch(true).setName("feature").call();
            commit(git, "f.txt", "f1\n", "feature one");
            commit(git, "f.txt", "f1\nf2\n", "feature two");
            git.checkout().setName("main").call();
            commit(git, "b.txt", "b\n", "third");

            var repo = git.getRepository();
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                var first = walk.parseCommit(repo.resolve(fixtureCommits.get(0)));
                git.tag().setName("v1").setMessage("annotated").setObjectId(first).call();
                git.tag().setName("main").setAnnotated(false).setObjectId(first).call();
                var third = walk.parseCommit(repo.resolve("HEAD"));
                git.notesAdd().setObjectId(third).setMessage("a note").call();
            }

            // A commit only refs/lyra/ reaches.
            git.checkout().setCreateBranch(true).setName("internal").call();
            commit(git, "i.txt", "internal\n", "internal only");
            git.checkout().setName("main").call();
            var update = repo.updateRef("refs/lyra/parked");
            update.setNewObjectId(repo.resolve("refs/heads/internal"));
            update.update();
            git.branchDelete().setBranchNames("internal").setForce(true).call();

            Files.writeString(dir.toPath().resolve("a.txt"), "one\ntwo\nstashed\n");
            git.stashCreate().setIndexMessage("stash index").setWorkingDirectoryMessage("stashed work").call();
        }
        return dir;
    }

    /** One line per row: what a renderer would draw from it. */
    private static List<String> drawn(com.jfx.git.graph.GraphPage page) {
        List<String> lines = new ArrayList<>();
        for (var row : page.rows()) {
            if (row.isStash()) {
                lines.add("stash " + row.getStash().hash() + " tie " + row.getTieLane()
                        + " through " + row.getThroughLanes());
            } else if (row.isWipNode()) {
                var layout = row.getLayout();
                lines.add("wip lane " + (layout == null ? -1 : layout.getLane())
                        + " to " + (layout == null ? List.of() : layout.getParentLanes()));
            } else if (row.getCommit() != null) {
                var c = row.getCommit();
                lines.add(c.getHash() + " lane " + c.getLane() + " parents " + c.getParentLanes()
                        + " refs " + c.getRefs().stream().map(RefInfo::fullName).toList());
            }
        }
        return lines;
    }

    private static List<String> subjects(com.jfx.git.graph.GraphPage page) {
        return page.rows().stream().filter(r -> r.getCommit() != null)
                .map(r -> r.getCommit().getSubject()).toList();
    }

    /**
     * G1, G10. The same rows, lanes and chips as the JGit reference - and none of the
     * stash's internal commits, the notes history or the app's parked refs, which the CLI
     * engine's {@code --all} used to show.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theGraphDrawsTheReferenceRowsAndNothingBesideHistory(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = graphFixture();
        GitBackend backend = open(factory, dir);
        GitBackend reference = open(GitService::new, dir);

        var order = com.jfx.git.graph.GraphOrder.BY_BRANCH;
        var page = backend.getCommitGraphPage(50, order, com.jfx.git.graph.GraphVisibility.ALL);

        List<String> subjects = subjects(page);
        assertEquals(5, subjects.size(), name + ": " + subjects);
        assertFalse(subjects.contains("internal only"), name + ": refs/lyra/ is not history");
        assertTrue(subjects.stream().noneMatch(s -> s.contains("stash") || s.contains("Notes")),
                name + ": no stash internals or notes commits: " + subjects);
        assertEquals(1, page.rows().stream().filter(r -> r.isStash()).count(),
                name + ": the stash is one stash row");
        assertEquals(drawn(reference.getCommitGraphPage(50, order, com.jfx.git.graph.GraphVisibility.ALL)),
                drawn(page), name + " must draw what the reference draws");

        assertTrue(backend.getLocalBranches().stream().anyMatch(b -> b.shortName().equals("main")),
                name + ": a tag called main must not turn the branch into heads/main");
    }

    /** G4, detached-HEAD marker: the WIP row hangs off HEAD, and a detached HEAD says so. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aDetachedDirtyHeadIsMarkedAndTheWipRowHangsOffIt(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = graphFixture();
        String second = fixtureCommits.get(1);
        Files.writeString(dir.toPath().resolve(".git/HEAD"), second + "\n");
        Files.writeString(dir.toPath().resolve("dirty.txt"), "uncommitted\n");
        GitBackend backend = open(factory, dir);
        GitBackend reference = open(GitService::new, dir);

        var page = backend.getCommitGraphPage(50, GraphOrder.current());
        var headRow = page.rows().stream()
                .filter(r -> r.getCommit() != null && r.getCommit().getHash().equals(second))
                .findFirst().orElseThrow();
        assertTrue(headRow.getCommit().getRefs().stream().anyMatch(RefInfo::isDetachedHead),
                name + ": the detached HEAD wears a marker");
        var wip = page.rows().get(0);
        assertTrue(wip.isWipNode());
        assertEquals(List.of(headRow.getCommit().getLane()), wip.getLayout().getParentLanes(),
                name + ": the uncommitted row's edge runs to HEAD's lane");
        assertEquals(drawn(reference.getCommitGraphPage(50, GraphOrder.current())), drawn(page), name);
    }

    /**
     * A detached HEAD is no current branch, the same on every engine and in every answer:
     * {@code getCurrentBranch()}, the staging state and the page's ref snapshot are all
     * blank - never the commit's hash, which JGit gave in full and the CLI shortened.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aDetachedHeadIsNoCurrentBranchOnEveryEngine(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = graphFixture();
        String second = fixtureCommits.get(1);
        Files.writeString(dir.toPath().resolve(".git/HEAD"), second + "\n");
        GitBackend backend = open(factory, dir);

        assertTrue(backend.isHeadDetached(), name);
        assertEquals("", backend.getCurrentBranch(), name);
        assertEquals("", backend.getStagingState().branch(), name);
        var refs = backend.getCommitGraphPage(50, GraphOrder.current()).refs();
        assertEquals("", refs.currentBranch(), name);
        assertEquals(second, refs.headHash(), name);
    }

    /** G8. An orphan branch's unborn HEAD must not empty the graph, filtered or not. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anUnbornHeadStillDrawsTheOtherBranches(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = graphFixture();
        Files.writeString(dir.toPath().resolve(".git/HEAD"), "ref: refs/heads/orphan\n");
        GitBackend backend = open(factory, dir);

        var all = backend.getCommitGraphPage(50, com.jfx.git.graph.GraphOrder.BY_BRANCH,
                com.jfx.git.graph.GraphVisibility.ALL);
        assertEquals(5, subjects(all).size(), name + ": " + subjects(all));

        var hideFeature = new com.jfx.git.graph.GraphVisibility(java.util.Set.of("refs/heads/feature"), null);
        var hidden = backend.getCommitGraphPage(50, com.jfx.git.graph.GraphOrder.BY_BRANCH, hideFeature);
        assertEquals(List.of("third", "second", "first"), subjects(hidden),
                name + ": hiding a branch with no HEAD to fall back on still walks the rest");
    }

    /** Hide & Solo: a hidden ref leaves no chip behind on a commit that is still drawn. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aHiddenTagDoesNotDecorate(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = graphFixture();
        GitBackend backend = open(factory, dir);

        var hideTag = new com.jfx.git.graph.GraphVisibility(java.util.Set.of("refs/tags/v1"), null);
        var page = backend.getCommitGraphPage(50, com.jfx.git.graph.GraphOrder.BY_BRANCH, hideTag);
        var first = page.rows().stream()
                .filter(r -> r.getCommit() != null && r.getCommit().getSubject().equals("first"))
                .findFirst().orElseThrow().getCommit();

        List<String> refs = first.getRefs().stream().map(RefInfo::fullName).toList();
        assertFalse(refs.contains("refs/tags/v1"), name + ": " + refs);
        assertTrue(refs.contains("refs/tags/main"), name + ": the other tag stays: " + refs);
    }

    /**
     * Hide &amp; Solo: whether the walk reaches a commit, however deep - what a click on a
     * hidden branch or tag asks before paging history in to find its tip.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theGraphReachesWhatAShownRefReaches(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = graphFixture();
        String first = fixtureCommits.get(0);
        String featureTwo = fixtureCommits.get(3);
        String internalOnly = fixtureCommits.get(5);
        GitBackend backend = open(factory, dir);
        var all = com.jfx.git.graph.GraphVisibility.ALL;

        assertTrue(backend.graphReaches(featureTwo, all), name + ": a branch's tip");
        assertFalse(backend.graphReaches(internalOnly, all), name + ": only refs/lyra/ reaches it");
        assertFalse(backend.graphReaches("0123456789abcdef0123456789abcdef01234567", all),
                name + ": no such commit");

        var hideFeature = new com.jfx.git.graph.GraphVisibility(java.util.Set.of("refs/heads/feature"), null);
        assertFalse(backend.graphReaches(featureTwo, hideFeature), name + ": only the hidden branch reaches it");
        assertTrue(backend.graphReaches(first, hideFeature), name + ": main reaches it as well");

        var soloMain = new com.jfx.git.graph.GraphVisibility(java.util.Set.of(), "refs/heads/main");
        assertTrue(backend.graphReaches(first, soloMain), name + ": a tag Solo excludes, on main's history");
        assertFalse(backend.graphReaches(featureTwo, soloMain), name + ": a branch Solo excludes");

        // HEAD detached on the commit only refs/lyra/ reaches: now HEAD alone reaches it.
        Files.writeString(dir.toPath().resolve(".git/HEAD"), internalOnly + "\n");
        GitBackend detached = open(factory, dir);
        var soloFeature = new com.jfx.git.graph.GraphVisibility(java.util.Set.of(), "refs/heads/feature");
        assertTrue(detached.graphReaches(internalOnly, soloFeature), name + ": HEAD is walked whatever Solo says");
    }

    /** G7. History follows a rename, and each commit says what the file was called there. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void fileHistoryFollowsARename(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        File workTree = backend.getWorkTree();
        Files.move(new File(workTree, "a.txt").toPath(), new File(workTree, "moved.txt").toPath());
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.stageFile("moved.txt").isOk());
        assertTrue(backend.commit("rename a.txt", false).isOk());
        write(backend, "moved.txt", "one\ntwo\nthree\n");
        assertTrue(backend.stageFile("moved.txt").isOk());
        assertTrue(backend.commit("edit moved.txt", false).isOk());

        var history = backend.getFileHistoryCommits("moved.txt", 0);

        assertEquals(List.of("edit moved.txt", "rename a.txt", "second", "first"),
                history.stream().map(com.jfx.git.model.CommitInfo::getSubject).toList(), name);
        assertEquals(List.of("moved.txt", "moved.txt", "a.txt", "a.txt"),
                history.stream().map(com.jfx.git.model.CommitInfo::getHistoryPath).toList(), name);
        assertEquals(2, backend.getFileHistoryCommits("moved.txt", 2).size(),
                name + ": a limit still limits");
    }

    /** G16. The same replay order on every engine, with a merge in the range. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void commitsSinceComeBackInTheSameOrderEverywhere(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = root.resolve("since" + (fixtureCount++)).toFile();
        assertTrue(dir.mkdirs());
        fixtureCommits.clear();
        String base;
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            configure(git);
            commit(git, "a.txt", "a\n", "base");
            base = fixtureCommits.get(0);
            git.checkout().setCreateBranch(true).setName("side").call();
            commit(git, "s.txt", "s\n", "side work");
            git.checkout().setName("main").call();
            commit(git, "m.txt", "m\n", "main work");
            git.merge().include(git.getRepository().resolve("side"))
                    .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF)
                    .setMessage("merge side").call();
        }
        GitBackend backend = open(factory, dir);
        GitBackend reference = open(GitService::new, dir);

        List<String> since = backend.getCommitsSince(base).stream()
                .map(com.jfx.git.model.CommitInfo::getSubject).toList();

        assertEquals(reference.getCommitsSince(base).stream()
                .map(com.jfx.git.model.CommitInfo::getSubject).toList(), since, name);
        assertEquals("merge side", since.get(since.size() - 1), name + ": oldest first ends at HEAD");
    }

    /**
     * G15. A graph page carries each commit's subject and a bounded body preview, and the
     * whole message comes from {@link GitBackend#getCommitMessage} - verbatim, and the
     * same on every engine.
     *
     * <p>The subject is git's ({@code %s}, the first paragraph on one line) on every
     * engine: the CLI's page gets it from git, the others cut it the same way, and a
     * disagreement would show the same commit two ways depending on the engine.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aGraphRowCarriesItsSubjectAndTheMessageIsFetchedOnDemand(String name,
                                                                  Supplier<GitBackend> factory)
            throws Exception {
        String longBody = ("A long explanation of why this change was needed. ").repeat(80);
        File dir = root.resolve("messages" + (fixtureCount++)).toFile();
        assertTrue(dir.mkdirs());
        fixtureCommits.clear();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            configure(git);
            commit(git, "a.txt", "a\n", "Subject line\n\nBody one\nBody two\n");
            commit(git, "b.txt", "b\n", "no trailing newline");
            commit(git, "c.txt", "c\n", "wrapped\nsubject\n\nand a body");
            commit(git, "d.txt", "d\n", "Long body\n\n" + longBody + "\n");
        }
        List<String> hashes = List.copyOf(fixtureCommits);
        GitBackend backend = open(factory, dir);

        java.util.Map<String, com.jfx.git.model.CommitInfo> rows = new java.util.HashMap<>();
        for (var row : backend.getCommitGraphPage(10, GraphOrder.current()).rows()) {
            if (row.getCommit() != null) rows.put(row.getCommit().getHash(), row.getCommit());
        }

        var bodied = rows.get(hashes.get(0));
        assertFalse(bodied.hasFullMessage(), name + ": a graph row is a summary");
        assertThrows(IllegalStateException.class, bodied::getFullMessage,
                name + ": a summary must not pass off its missing body as an empty one");
        assertEquals("Subject line", bodied.getSubject(), name);
        assertEquals("Body one Body two", bodied.getBodySummary(), name);
        assertEquals("wrapped subject", rows.get(hashes.get(2)).getSubject(),
                name + ": the subject is the whole first paragraph, as git says");
        assertEquals("and a body", rows.get(hashes.get(2)).getBodySummary(), name);
        assertEquals("", rows.get(hashes.get(1)).getBodySummary(), name);

        String preview = rows.get(hashes.get(3)).getBodySummary();
        assertTrue(preview.length() <= com.jfx.git.model.CommitInfo.BODY_PREVIEW_COLUMNS + 2,
                () -> name + ": the preview is bounded, got " + preview.length() + " chars");
        assertTrue(preview.startsWith("A long explanation of why this change"),
                () -> name + ": the preview is the start of the body: " + preview);

        assertEquals("Subject line\n\nBody one\nBody two\n", backend.getCommitMessage(hashes.get(0)),
                name + ": the message comes back verbatim");
        assertEquals("no trailing newline", backend.getCommitMessage(hashes.get(1)),
                name + ": nothing is added to a message that ends without a newline");
        assertEquals("Long body\n\n" + longBody + "\n", backend.getCommitMessage(hashes.get(3)), name);
        assertNull(backend.getCommitMessage("0123456789abcdef0123456789abcdef01234567"),
                name + ": no such commit is null, not an empty message");
        assertNull(backend.getCommitMessage("--all"), name + ": only a hash is accepted");
    }

    // ------------------------------------------------------------------
    // Merge modes: fast-forward only, always a merge commit, squash, a message
    // ------------------------------------------------------------------

    /** A branch "other" one commit ahead of main, which has not moved: a fast-forward. */
    private GitBackend fastForwardableFixture(Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "other", "c.txt", "from the branch\n");
        return backend;
    }

    /** "other" and main each one commit past their base, touching different files. */
    private GitBackend divergedFixture(Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fastForwardableFixture(factory);
        write(backend, "d.txt", "from main\n");
        assertTrue(backend.stageFile("d.txt").isOk());
        assertTrue(backend.commit("main change", false).isOk());
        return backend;
    }

    private static String tipOf(GitBackend backend, String branch) {
        return backend.getLocalBranches().stream()
                .filter(r -> branch.equals(r.shortName()))
                .map(RefInfo::targetHash)
                .findFirst().orElseThrow();
    }

    private static int headParentCount(GitBackend backend) throws Exception {
        try (Git git = Git.open(backend.getWorkTree());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD")).getParentCount();
        }
    }

    private static String squashMessage(GitBackend backend) throws IOException {
        Path file = backend.getDirectory().toPath().resolve("SQUASH_MSG");
        return Files.exists(file) ? Files.readString(file).replace("\r\n", "\n") : null;
    }

    /** Fast-forward only moves the branch when it can, and merges nothing when it cannot. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void fastForwardOnlyMovesTheBranchOrMergesNothing(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend ahead = fastForwardableFixture(factory);
        GitResult forwarded = ahead.merge("other", com.jfx.git.model.MergeMode.FF_ONLY, "ignored");
        assertTrue(forwarded.isOk(), forwarded::getMessage);
        assertEquals(tipOf(ahead, "other"), ahead.getHeadHash(), "HEAD is the branch's tip");

        GitBackend diverged = divergedFixture(factory);
        String before = diverged.getHeadHash();
        GitResult refused = diverged.merge("other", com.jfx.git.model.MergeMode.FF_ONLY, null);
        assertFalse(refused.isOk(), "diverged branches cannot fast-forward");
        assertFalse(refused.isConflict());
        assertEquals(before, diverged.getHeadHash(), "and nothing was merged");
        assertFalse(diverged.hasUncommittedChanges());
    }

    /**
     * "Always create a merge commit" makes one even where a fast-forward was possible, and
     * records the message typed for it - multi-line, with the characters the Windows
     * command line mangles, on the CLI engine where it travels through a file.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void noFastForwardMakesAMergeCommitWithTheMessageGiven(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fastForwardableFixture(factory);
        String message = "Bring in \"other\" - 12\" display\n\nBody line one\n#123 stays too";

        GitResult merged = backend.merge("other", com.jfx.git.model.MergeMode.NO_FF, "  " + message + "\n\n");

        assertTrue(merged.isOk(), merged::getMessage);
        assertEquals(2, headParentCount(backend), "a merge commit, not a fast-forward");
        assertEquals(message, backend.getHeadCommitMessage());
        assertTrue(new File(backend.getWorkTree(), "c.txt").exists());
    }

    /** Default still fast-forwards when it can - the configuration's answer out of the box. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theDefaultModeFastForwardsWhenItCan(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fastForwardableFixture(factory);

        GitResult merged = backend.merge("other", com.jfx.git.model.MergeMode.DEFAULT, null);

        assertTrue(merged.isOk(), merged::getMessage);
        assertEquals(tipOf(backend, "other"), backend.getHeadHash());
    }

    /** A custom message on a merge that stops keeps it in MERGE_MSG for the finishing commit. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aConflictingMergeKeepsTheMessageGivenForItsCommit(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = conflictingFixture(factory);

        GitResult merged = backend.merge("other", com.jfx.git.model.MergeMode.NO_FF, "Join the two sides");

        assertTrue(merged.isConflict(), merged::getMessage);
        assertEquals("Join the two sides",
                com.jfx.git.model.PreparedMessage.read(backend.getDirectory()));
    }

    /**
     * A squash stages what the branch changed and stops: HEAD stays, nothing records a
     * merge, and SQUASH_MSG carries a subject naming the branch above git's listing. The
     * commit that finishes it is an ordinary one-parent commit, and takes SQUASH_MSG away.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSquashStagesTheBranchAndTheCommitFinishesIt(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = divergedFixture(factory);
        String before = backend.getHeadHash();

        GitResult squashed = backend.merge("other", com.jfx.git.model.MergeMode.SQUASH, null);

        assertTrue(squashed.isOk(), squashed::getMessage);
        assertEquals(before, backend.getHeadHash(), "a squash does not move HEAD");
        assertTrue(backend.getStagedFiles().contains("c.txt"),
                () -> "the branch's change is staged: " + backend.getStagedFiles());
        assertFalse(new File(backend.getDirectory(), "MERGE_HEAD").exists(), "no merge is recorded");
        assertEquals(com.jfx.git.model.OperationState.Kind.NONE, backend.getOperationState().kind());
        String message = squashMessage(backend);
        assertNotNull(message, "SQUASH_MSG is left for the commit");
        assertTrue(message.startsWith("Squash merge branch 'other'\n\n"), message);
        assertTrue(message.contains("change on other"), () -> "git's listing stays: " + message);
        assertEquals("Squash merge branch 'other'",
                com.jfx.git.model.PreparedMessage.readSquash(backend.getDirectory()).lines().findFirst().orElse(""));

        assertTrue(backend.commit("Squash merge branch 'other'", false).isOk());
        assertEquals(1, headParentCount(backend), "a squash's commit has one parent");
        assertNull(squashMessage(backend), "committing uses up SQUASH_MSG, as git commit does");
    }

    /** A message given for a squash becomes its subject; a reset abandons the squash. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSquashTakesTheMessageGivenAndAResetAbandonsIt(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = divergedFixture(factory);

        assertTrue(backend.merge("other", com.jfx.git.model.MergeMode.SQUASH, "Add c\n\nin one go").isOk());

        String message = squashMessage(backend);
        assertNotNull(message);
        assertTrue(message.startsWith("Add c\n\nin one go\n\n"), message);

        assertTrue(backend.reset("HEAD", com.jfx.git.model.ResetMode.HARD).isOk());
        assertNull(squashMessage(backend), "a reset abandons the squash, as git reset does");
    }

    /** merge.ff=false makes git refuse a bare --squash; the squash mode still squashes. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSquashIsASquashUnderMergeFfFalse(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fastForwardableFixture(factory);
        String before = backend.getHeadHash();
        try (Git git = Git.open(backend.getWorkTree())) {
            var config = git.getRepository().getConfig();
            config.setString("merge", null, "ff", "false");
            config.save();
        }

        GitResult squashed = backend.merge("other", com.jfx.git.model.MergeMode.SQUASH, null);

        assertTrue(squashed.isOk(), squashed::getMessage);
        assertEquals(before, backend.getHeadHash());
        assertTrue(backend.getStagedFiles().contains("c.txt"));
    }

    /** Squashing a branch that is already in is nothing to do, and says so without a stray message. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void squashingAMergedBranchIsNothingToDo(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fastForwardableFixture(factory);
        assertTrue(backend.merge("other", com.jfx.git.model.MergeMode.FF_ONLY, null).isOk());

        GitResult squashed = backend.merge("other", com.jfx.git.model.MergeMode.SQUASH, null);

        assertTrue(squashed.isOk(), squashed::getMessage);
        assertTrue(squashed.getMessage().contains("nothing to squash"), squashed::getMessage);
        assertNull(squashMessage(backend));
        assertFalse(backend.hasUncommittedChanges());
    }

    /** A squash that conflicts is a conflict, names the file, and records no merge. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aConflictingSquashIsAConflictWithoutAMerge(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = conflictingFixture(factory);

        GitResult squashed = backend.merge("other", com.jfx.git.model.MergeMode.SQUASH, null);

        assertTrue(squashed.isConflict(), squashed::getMessage);
        assertTrue(squashed.getConflicts().contains("a.txt"), () -> squashed.getConflicts().toString());
        assertFalse(new File(backend.getDirectory(), "MERGE_HEAD").exists());
        assertTrue(squashMessage(backend).startsWith("Squash merge branch 'other'"));
    }

    // ------------------------------------------------------------------
    // Stash options: untracked, keep index, some paths, branch from a stash
    // ------------------------------------------------------------------

    /** Without "include untracked", a new file stays where it is and out of the stash. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStashWithoutUntrackedLeavesNewFilesAlone(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "edited\n");
        write(backend, "new.txt", "untracked\n");

        GitResult stashed = backend.stash(new com.jfx.git.model.StashOptions(null, false, false, List.of()));

        assertTrue(stashed.isOk(), stashed::getMessage);
        assertEquals("one\ntwo\n", read(backend, "a.txt"));
        assertEquals("untracked\n", read(backend, "new.txt"), "the untracked file is not stashed");
        assertEquals(1, backend.getStashEntries().size());
    }

    /**
     * --keep-index: what is staged stays staged and in the working tree, the rest goes
     * back to HEAD, and the stash still holds both - a reset and an apply bring back all
     * of it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void keepIndexLeavesTheStagedChangesButStashesEverything(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "one\ntwo\nstaged\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        write(backend, "b.txt", "b\nunstaged\n");
        write(backend, "new.txt", "untracked\n");

        GitResult stashed = backend.stash(new com.jfx.git.model.StashOptions("keep", true, true, List.of()));

        assertTrue(stashed.isOk(), stashed::getMessage);
        assertEquals("one\ntwo\nstaged\n", read(backend, "a.txt"), "the staged change stays in the tree");
        assertEquals(List.of("a.txt"), backend.getStagedFiles(), "and in the index");
        assertEquals("b\n", read(backend, "b.txt"), "the unstaged change is stashed");
        assertFalse(new File(backend.getWorkTree(), "new.txt").exists(), "so is the untracked file");

        assertTrue(backend.reset("HEAD", com.jfx.git.model.ResetMode.HARD).isOk());
        assertTrue(backend.stashApply(0).isOk());
        assertEquals("one\ntwo\nstaged\n", read(backend, "a.txt"));
        assertEquals("b\nunstaged\n", read(backend, "b.txt"));
        assertEquals("untracked\n", read(backend, "new.txt"));
    }

    /**
     * Stashing some paths takes those and nothing else - tracked or untracked - and takes
     * them literally: "[ab].txt" is one file, not a glob that would sweep in a.txt and
     * b.txt with it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aPartialStashTakesOnlyTheSelectedPathsLiterally(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "a edited\n");
        write(backend, "b.txt", "b edited\n");
        write(backend, "[ab].txt", "bracketed, untracked\n");
        write(backend, "other.txt", "also untracked\n");

        GitResult stashed = backend.stash(new com.jfx.git.model.StashOptions(
                "two files", true, false, List.of("[ab].txt", "b.txt")));

        assertTrue(stashed.isOk(), stashed::getMessage);
        assertFalse(new File(backend.getWorkTree(), "[ab].txt").exists(), "the selected new file is stashed");
        assertEquals("b\n", read(backend, "b.txt"), "the selected edit is stashed");
        assertEquals("a edited\n", read(backend, "a.txt"), "a.txt was not selected");
        assertEquals("also untracked\n", read(backend, "other.txt"), "other.txt was not selected");
        assertTrue(backend.getStashEntries().get(0).message().contains("two files"));

        assertTrue(backend.stashPop(0).isOk());
        assertEquals("b edited\n", read(backend, "b.txt"));
        assertEquals("bracketed, untracked\n", read(backend, "[ab].txt"));
    }

    /** A staged new file among the selected paths leaves the index and the tree. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aPartialStashTakesAStagedNewFileOutOfTheIndex(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "added.txt", "new and staged\n");
        assertTrue(backend.stageFile("added.txt").isOk());
        write(backend, "a.txt", "left alone\n");

        GitResult stashed = backend.stash(new com.jfx.git.model.StashOptions(
                null, true, false, List.of("added.txt")));

        assertTrue(stashed.isOk(), stashed::getMessage);
        assertFalse(new File(backend.getWorkTree(), "added.txt").exists());
        assertFalse(backend.getStagedFiles().contains("added.txt"), () -> backend.getStagedFiles().toString());
        assertEquals("left alone\n", read(backend, "a.txt"));
    }

    /** Keeping the index together with paths is refused, and nothing is touched. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void keepIndexWithPathsIsRefusedEverywhere(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "edited\n");

        GitResult stashed = backend.stash(new com.jfx.git.model.StashOptions(
                null, true, true, List.of("a.txt")));

        assertFalse(stashed.isOk());
        assertEquals("edited\n", read(backend, "a.txt"));
        assertTrue(backend.getStashEntries().isEmpty());
    }

    /**
     * Nothing to stash is a failure everywhere. git says "No local changes to save" and
     * exits 0, which the CLI engine used to report as a stash made.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void nothingToStashIsAFailure(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "edited\n");

        GitResult clean = fixture(factory).stash(StashOptions.everything("empty"));
        GitResult unmatched = backend.stash(new com.jfx.git.model.StashOptions(
                null, true, false, List.of("b.txt")));

        assertFalse(clean.isOk(), clean::getMessage);
        assertFalse(unmatched.isOk(), "b.txt has no changes: " + unmatched.getMessage());
        assertEquals("edited\n", read(backend, "a.txt"));
        assertTrue(backend.getStashEntries().isEmpty());
    }

    /**
     * A branch from a stash starts at the commit the stash was taken from, has the stash
     * applied with its index, and the stash is gone from the list.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBranchFromAStashStartsWhereTheStashWasTaken(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String base = backend.getHeadHash();
        write(backend, "a.txt", "one\ntwo\nstaged\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        write(backend, "b.txt", "b\nunstaged\n");
        assertTrue(backend.stash(StashOptions.everything("to branch")).isOk());
        String stash = backend.getStashEntries().get(0).hash();
        // main moves on and now conflicts with the stash: exactly when a branch is the way out.
        write(backend, "a.txt", "rewritten on main\n");
        assertTrue(backend.stageFile("a.txt").isOk());
        assertTrue(backend.commit("main moves on", false).isOk());

        GitResult branched = new RepositoryCommands(backend).stashBranch("from-stash", stash);

        assertTrue(branched.isOk(), branched::getMessage);
        assertEquals("from-stash", backend.getCurrentBranch());
        assertEquals(base, backend.getHeadHash(), "the branch starts at the stash's base");
        assertEquals("one\ntwo\nstaged\n", read(backend, "a.txt"));
        assertEquals("b\nunstaged\n", read(backend, "b.txt"));
        assertEquals(List.of("a.txt"), backend.getStagedFiles(), "the index is restored too");
        assertTrue(backend.getStashEntries().isEmpty(), "and the stash is dropped");
    }

    /** An existing branch name is refused before anything moves. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBranchFromAStashRefusesAnExistingName(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        assertTrue(backend.createBranch("taken", null, false).isOk());
        write(backend, "a.txt", "edited\n");
        assertTrue(backend.stash(StashOptions.everything("work")).isOk());

        GitResult branched = backend.stashBranch("taken", 0);

        assertFalse(branched.isOk());
        assertEquals("main", backend.getCurrentBranch());
        assertEquals(1, backend.getStashEntries().size(), "the stash is untouched");
        assertEquals("one\ntwo\n", read(backend, "a.txt"));
    }

    // ------------------------------------------------------------------
    // Searching all history
    // ------------------------------------------------------------------

    /**
     * Six commits built to separate every search criterion, keyed by a short name.
     *
     * <p>Each commit has its own committer time, an hour apart, so "newest first" has one
     * right answer on every engine rather than depending on how each breaks a tie.
     * {@code feature} is only reachable from its branch - HEAD stays on {@code main} - so a
     * search that walked HEAD alone would miss it. {@code move} renames a file without
     * touching its content, which a content search must not mistake for every line of the
     * file being removed and added again.
     */
    private java.util.Map<String, String> searchFixture(File dir) throws Exception {
        java.util.Map<String, String> made = new java.util.LinkedHashMap<>();
        java.time.Instant base = java.time.Instant.parse("2024-01-01T00:00:00Z");
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            configure(git);
            Path work = dir.toPath();
            Files.createDirectories(work.resolve("src"));
            Files.createDirectories(work.resolve("docs"));

            Files.writeString(work.resolve("src/app.txt"), "hello\n");
            made.put("initial", searchCommit(git, base, 1, "Ada Lovelace", "ada@example.com",
                    null, "Initial import", "src/app.txt"));

            Files.writeString(work.resolve("src/app.txt"), "hello\nneedle = 1\n");
            made.put("tweak", searchCommit(git, base, 2, "Alan Turing", "alan@bletchley.org",
                    new org.eclipse.jgit.lib.PersonIdent("Committer Bot", "bot@ci.example",
                            base.plusSeconds(2 * 3600), java.time.ZoneOffset.UTC),
                    "Tweak settings\n\nFixes the Frobnicator crash in detail.\n", "src/app.txt"));

            Files.writeString(work.resolve("docs/readme.md"), "docs\n");
            made.put("docs", searchCommit(git, base, 3, "Ada Lovelace", "ada@example.com",
                    null, "Write docs", "docs/readme.md"));

            Files.writeString(work.resolve("src/app.txt"), "hello\n");
            made.put("remove", searchCommit(git, base, 4, "Grace Hopper", "grace@navy.mil",
                    null, "Remove the setting", "src/app.txt"));

            git.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(work.resolve("feature.txt"), "A Needle in caps\n");
            made.put("feature", searchCommit(git, base, 5, "Grace Hopper", "grace@navy.mil",
                    null, "Feature work", "feature.txt"));
            git.checkout().setName("main").call();

            Files.createDirectories(work.resolve("lib"));
            Files.move(work.resolve("src/app.txt"), work.resolve("lib/app.txt"));
            git.rm().addFilepattern("src/app.txt").call();
            made.put("move", searchCommit(git, base, 6, "Ada Lovelace", "ada@example.com",
                    null, "Move app", "lib/app.txt"));
        }
        return made;
    }

    private static String searchCommit(Git git, java.time.Instant base, int hour, String name,
                                       String email, org.eclipse.jgit.lib.PersonIdent committer,
                                       String message, String path) throws Exception {
        git.add().addFilepattern(path).call();
        var author = new org.eclipse.jgit.lib.PersonIdent(name, email,
                base.plusSeconds(hour * 3600L), java.time.ZoneOffset.UTC);
        return git.commit().setMessage(message).setAuthor(author)
                .setCommitter(committer != null ? committer : author)
                .call().getName();
    }

    private static List<String> searched(GitBackend backend, com.jfx.git.model.CommitSearch query) {
        return backend.searchCommits(query, 50).stream()
                .map(com.jfx.git.model.CommitInfo::getHash).toList();
    }

    private static List<String> named(java.util.Map<String, String> made, String... names) {
        return java.util.Arrays.stream(names).map(made::get).toList();
    }

    /**
     * S1. Message text: the body counts, case does not, and several words must all appear -
     * anywhere, in any order. The graph's filter matched the subject alone, which is why a
     * commit described only in its body could not be found at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void searchMatchesTheWholeMessageCaseInsensitively(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = root.resolve("search" + (fixtureCount++)).toFile();
        var made = searchFixture(dir);
        GitBackend backend = open(factory, dir);

        assertEquals(named(made, "tweak"),
                searched(backend, com.jfx.git.model.CommitSearch.byMessage("frobnicator")),
                name + ": a word only in the body");
        assertEquals(named(made, "remove", "tweak"),
                searched(backend, com.jfx.git.model.CommitSearch.byMessage("SETTING the")),
                name + ": every word, in any order, subject or body");
        assertEquals(named(made, "feature"),
                searched(backend, com.jfx.git.model.CommitSearch.byMessage("feature work")),
                name + ": a commit reachable only from another branch is still searched");
    }

    /**
     * S1b. A commit only a stash reaches - the branch it was on rewritten since - is drawn
     * on the graph beside the stash, so the search has to find it too: a row the user can
     * see but never search for would be the one false "no match".
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void searchReachesACommitOnlyAStashReaches(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("search" + (fixtureCount++)).toFile();
        assertTrue(dir.mkdirs());
        String lost;
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            configure(git);
            commit(git, "a.txt", "one\n", "first");
            Files.writeString(dir.toPath().resolve("a.txt"), "one\nwip\n");
            git.add().addFilepattern("a.txt").call();
            lost = git.commit().setMessage("wip parser, rewritten away").call().getName();
            Files.writeString(dir.toPath().resolve("a.txt"), "one\nwip\nhalf\n");
            git.stashCreate().setWorkingDirectoryMessage("on the wip").call();
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("HEAD~1").call();
        }
        GitBackend backend = open(factory, dir);

        assertEquals(List.of(lost),
                searched(backend, com.jfx.git.model.CommitSearch.byMessage("wip parser")),
                name + ": the commit a stash was made on is searched, though no branch reaches it");
    }

    /**
     * S2. Author and committer match {@code "Name <email>"}, so an email finds them too;
     * a committer who is not the author is searched separately; and criteria combine.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void searchMatchesAuthorAndCommitterByNameOrEmail(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = root.resolve("search" + (fixtureCount++)).toFile();
        var made = searchFixture(dir);
        GitBackend backend = open(factory, dir);
        var none = com.jfx.git.model.CommitSearch.EMPTY;

        assertEquals(named(made, "tweak"), searched(backend, none.withAuthor("ALAN@bletchley")),
                name + ": by email, ignoring case");
        assertEquals(named(made, "move", "docs", "initial"),
                searched(backend, none.withAuthor("lovelace")), name + ": by name");
        assertEquals(named(made, "tweak"), searched(backend, none.withCommitter("bot@ci")),
                name + ": the committer is its own criterion");
        assertEquals(List.of(), searched(backend, none.withAuthor("bot@ci")),
                name + ": and is not the author");
        assertEquals(named(made, "docs"),
                searched(backend, none.withAuthor("ada").withMessage("docs")),
                name + ": criteria are AND-ed");
    }

    /** S3. A hash prefix, alone and with other criteria; and the limit is honoured. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void searchByHashPrefixAndLimit(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("search" + (fixtureCount++)).toFile();
        var made = searchFixture(dir);
        GitBackend backend = open(factory, dir);
        var none = com.jfx.git.model.CommitSearch.EMPTY;
        String docs = made.get("docs");

        assertEquals(List.of(docs), searched(backend, none.withHashPrefix(docs.substring(0, 8).toUpperCase())),
                name + ": prefix, any case");
        assertEquals(List.of(), searched(backend,
                        none.withHashPrefix(docs.substring(0, 8)).withAuthor("grace")),
                name + ": the prefix still has to satisfy the other criteria");
        assertEquals(named(made, "move", "feature"),
                backend.searchCommits(none.withMessage("e"), 2).stream()
                        .map(com.jfx.git.model.CommitInfo::getHash).toList(),
                name + ": newest first, cut at the limit");
        assertEquals(List.of(), backend.searchCommits(none, 50), name + ": an empty query finds nothing");
    }

    /** S4. Commits that touched a path - a file, or anything under a directory. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void searchByPath(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("search" + (fixtureCount++)).toFile();
        var made = searchFixture(dir);
        GitBackend backend = open(factory, dir);
        var none = com.jfx.git.model.CommitSearch.EMPTY;

        assertEquals(named(made, "move", "remove", "tweak", "initial"),
                searched(backend, none.withPath("src")), name + ": a directory");
        assertEquals(named(made, "docs"), searched(backend, none.withPath("docs/readme.md")),
                name + ": a file");
        assertEquals(named(made, "remove"),
                searched(backend, none.withPath("src/").withAuthor("grace")),
                name + ": with another criterion");
    }

    /**
     * S5. Content: the commit's own diff adds or removes a line containing the text -
     * {@code git log -G}. Only the lines that changed count, not the context around them;
     * a file that was only renamed changed no lines; and the text is literal, so a
     * character that means something to a regular expression means nothing here.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void searchByContentChange(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("search" + (fixtureCount++)).toFile();
        var made = searchFixture(dir);
        GitBackend backend = open(factory, dir);
        var none = com.jfx.git.model.CommitSearch.EMPTY;

        assertEquals(named(made, "feature", "remove", "tweak"),
                searched(backend, none.withContent("needle")),
                name + ": added, removed, and in another case on another branch");
        assertEquals(named(made, "initial"), searched(backend, none.withContent("hello")),
                name + ": unchanged context and a pure rename do not count");
        assertEquals(named(made, "remove", "tweak"),
                searched(backend, none.withContent("needle").withPath("src")),
                name + ": limited to the path");
        assertEquals(List.of(), searched(backend, none.withContent("e.=")),
                name + ": literal text - as a regex this would match \"needle = 1\"");
        assertEquals(named(made, "tweak"),
                searched(backend, none.withContent("needle = 1").withAuthor("turing")),
                name + ": with another criterion");
    }

    // ------------------------------------------------------------------
    // Managing remotes and upstreams
    // ------------------------------------------------------------------

    private static List<String> remoteBranchNames(GitBackend backend) {
        return backend.getRemoteBranches().stream().map(RefInfo::shortName).sorted().toList();
    }

    /**
     * R1. A renamed remote is still the same remote: its branches, the refspec that
     * fetches them and the upstreams that point at them all carry the new name.
     *
     * <p>The fetch afterwards is the part that catches a half-done rename. A refspec left
     * pointing at {@code refs/remotes/origin/*} brings every branch straight back under
     * the old name, and the sidebar then shows a remote that no longer exists.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void renamingARemoteTakesItsBranchesRefspecAndUpstreamsAlong(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = remoteFor(backend);
        assertEquals("origin/main", backend.getUpstream("main"), name + ": the fixture publishes main");

        GitResult renamed = backend.renameRemote("origin", "upstream");
        assertTrue(renamed.isOk(), () -> name + ": " + renamed.getMessage());

        assertEquals(List.of("upstream"), backend.getRemoteNames());
        assertEquals(List.of("upstream/main"), remoteBranchNames(backend),
                name + ": the remote-tracking branch moves with its remote");
        assertEquals("upstream/main", backend.getUpstream("main"),
                name + ": and main goes on tracking it");
        assertNotNull(backend.getRemoteUrl("upstream"), name + ": the URL comes along");
        assertNull(backend.getRemoteUrl("origin"));

        GitResult fetched = backend.fetchRemote("upstream");
        assertTrue(fetched.isOk(), () -> name + ": " + fetched.getMessage());
        assertEquals(List.of("upstream/main"), remoteBranchNames(backend),
                name + ": a fetch must not bring the branches back under the old name");
        try (Git check = Git.open(backend.getWorkTree())) {
            assertEquals("+refs/heads/*:refs/remotes/upstream/*",
                    check.getRepository().getConfig().getString("remote", "upstream", "fetch"));
            assertNotNull(check.getRepository().resolve("refs/remotes/upstream/main"));
        }
        assertTrue(remote.isDirectory());
    }

    /** R2. A rename that cannot be done changes nothing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void renamingARemoteRefusesAMissingOneAndANameAlreadyTaken(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = remoteFor(backend);
        assertTrue(backend.addRemote("other", remote.getAbsolutePath()).isOk());

        assertFalse(backend.renameRemote("nowhere", "fresh").isOk(), name + ": no such remote");
        assertFalse(backend.renameRemote("origin", "other").isOk(), name + ": the name is taken");
        assertFalse(backend.renameRemote("origin", " ").isOk(), name + ": a remote needs a name");

        assertEquals(List.of("origin", "other"), backend.getRemoteNames().stream().sorted().toList());
        assertEquals("origin/main", backend.getUpstream("main"));
    }

    /**
     * R3. The fetch URL and the push URL are separate settings, and changing one leaves
     * the other alone.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRemotesFetchAndPushUrlsAreSetSeparately(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        remoteFor(backend);
        assertNull(backend.getRemotePushUrl("origin"), name + ": no push URL of its own to begin with");

        String fetchUrl = "https://example.com/team/project.git";
        String pushUrl = "ssh://git@example.com/team/project.git";

        assertTrue(backend.setRemoteUrl("origin", fetchUrl, false).isOk());
        assertEquals(fetchUrl, backend.getRemoteUrl("origin"));
        assertNull(backend.getRemotePushUrl("origin"), name + ": changing the fetch URL adds no push URL");

        assertTrue(backend.setRemoteUrl("origin", pushUrl, true).isOk());
        assertEquals(pushUrl, backend.getRemotePushUrl("origin"));
        assertEquals(fetchUrl, backend.getRemoteUrl("origin"),
                name + ": a push URL must not replace the URL fetches use");

        assertTrue(backend.setRemoteUrl("origin", "", true).isOk());
        assertNull(backend.getRemotePushUrl("origin"), name + ": blank removes the separate push URL");
        assertTrue(backend.setRemoteUrl("origin", null, true).isOk(),
                name + ": and removing what is already gone is not an error");
        assertEquals(fetchUrl, backend.getRemoteUrl("origin"));
    }

    /**
     * R4. A remote cannot be left with nothing to fetch from, and setting a URL does not
     * create a remote: JGit's own command does, with a URL and no refspec.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void settingAUrlRefusesABlankFetchUrlAndARemoteThatDoesNotExist(String name,
                                                                    Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        remoteFor(backend);
        String before = backend.getRemoteUrl("origin");

        assertFalse(backend.setRemoteUrl("origin", " ", false).isOk());
        assertEquals(before, backend.getRemoteUrl("origin"));

        assertFalse(backend.setRemoteUrl("nowhere", "https://example.com/r.git", false).isOk());
        assertFalse(backend.setRemoteUrl("nowhere", "https://example.com/r.git", true).isOk());
        assertEquals(List.of("origin"), backend.getRemoteNames(),
                name + ": a refused change must not leave a new remote behind");
    }

    /**
     * R5. Deleting a branch on the remote removes it there and forgets it here, and leaves
     * the local branch of the same name where it was.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void deletingARemoteBranchRemovesItThereAndForgetsItHere(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = remoteFor(backend);
        assertTrue(backend.createBranch("side", null, true).isOk());
        write(backend, "side.txt", "side\n");
        assertTrue(backend.stageFile("side.txt").isOk());
        assertTrue(backend.commit("on side", false).isOk());
        assertTrue(new RepositoryCommands(backend).push("origin", false, false, true).isOk());
        assertTrue(backend.checkoutBranch("main").isOk());
        assertEquals(List.of("origin/main", "origin/side"), remoteBranchNames(backend));

        GitResult deleted = backend.deleteRemoteBranch("origin", "side");
        assertTrue(deleted.isOk(), () -> name + ": " + deleted.getMessage());

        try (Git check = Git.open(remote)) {
            assertNull(check.getRepository().resolve("refs/heads/side"),
                    name + ": the branch should be gone from the remote");
            assertNotNull(check.getRepository().resolve("refs/heads/main"),
                    name + ": and nothing else with it");
        }
        assertEquals(List.of("origin/main"), remoteBranchNames(backend),
                name + ": this clone's record of the branch goes too");
        assertTrue(backend.getLocalBranches().stream().anyMatch(b -> b.shortName().equals("side")),
                name + ": the local branch is not what was asked for");

        assertFalse(backend.deleteRemoteBranch("origin", "side").isOk(),
                name + ": there is nothing left to delete, and saying so is the honest answer");

        // Deleted by someone else since the last fetch: the remote has nothing to
        // delete, but the row the user clicked is this clone's stale record, and that
        // goes. Git left to itself calls this a success; JGit calls it a rejection.
        try (Git here = Git.open(backend.getWorkTree())) {
            var stale = here.getRepository().updateRef("refs/remotes/origin/side");
            stale.setNewObjectId(here.getRepository().resolve("refs/heads/side"));
            stale.setForceUpdate(true);
            stale.update();
        }
        assertEquals(List.of("origin/main", "origin/side"), remoteBranchNames(backend), "sanity");
        GitResult gone = backend.deleteRemoteBranch("origin", "side");
        assertFalse(gone.isOk(), name + ": the remote had no such branch");
        assertEquals(RemoteOps.alreadyGoneExplanation("origin", "side"), gone.getMessage());
        assertEquals(List.of("origin/main"), remoteBranchNames(backend),
                name + ": the stale record is cleared all the same");
        assertFalse(backend.deleteRemoteBranch("nowhere", "main").isOk(), name + ": no such remote");
        assertFalse(backend.deleteRemoteBranch("origin", " ").isOk(), name + ": no branch named");
    }

    /** R6. One remote, fetched by name; a name that is not a remote is refused. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void fetchingOneRemoteBringsBackWhatThisCloneForgot(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        remoteFor(backend);
        forgetTrackingRef(backend, "main");
        assertEquals(List.of(), remoteBranchNames(backend), "sanity: the record is gone");

        GitResult fetched = backend.fetchRemote("origin");
        assertTrue(fetched.isOk(), () -> name + ": " + fetched.getMessage());
        assertEquals(List.of("origin/main"), remoteBranchNames(backend));

        assertFalse(backend.fetchRemote("nowhere").isOk(), name + ": no such remote");
    }

    /** R7. An upstream can be set, read back and removed, for a branch that is not checked out. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anUpstreamIsSetReadAndUnset(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        remoteFor(backend);
        assertTrue(backend.createBranch("side", null, false).isOk());
        assertEquals("main", backend.getCurrentBranch(), "sanity: side is not checked out");
        assertNull(backend.getUpstream("side"), name + ": a new branch tracks nothing");

        assertEquals(java.util.Map.of("main", "origin/main"), backend.getUpstreams(),
                name + ": a branch that tracks nothing has no entry");

        GitResult set = backend.setUpstream("side", "origin/main");
        assertTrue(set.isOk(), () -> name + ": " + set.getMessage());
        assertEquals("origin/main", backend.getUpstream("side"));
        assertEquals("origin/main", backend.getUpstream("main"), name + ": main's own is untouched");
        assertEquals(java.util.Map.of("main", "origin/main", "side", "origin/main"), backend.getUpstreams(),
                name + ": all of them in one call agree with each asked singly");

        GitResult unset = backend.unsetUpstream("side");
        assertTrue(unset.isOk(), () -> name + ": " + unset.getMessage());
        assertNull(backend.getUpstream("side"));
        assertFalse(backend.unsetUpstream("side").isOk(),
                name + ": unsetting what is not set changes nothing, and says so");
    }

    /** R8. Neither side of an upstream can be something that does not exist. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anUpstreamNeedsBothBranchesToExist(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        remoteFor(backend);
        assertTrue(backend.createBranch("side", null, false).isOk());

        assertFalse(backend.setUpstream("side", "origin/nothing").isOk(), name + ": no such remote branch");
        assertFalse(backend.setUpstream("side", "nowhere/main").isOk(), name + ": no such remote");
        assertFalse(backend.setUpstream("nothing", "origin/main").isOk(), name + ": no such local branch");
        assertFalse(backend.unsetUpstream("nothing").isOk(), name + ": no such local branch");
        assertNull(backend.getUpstream("side"), name + ": a refused upstream is not half-set");
        assertNull(backend.getUpstream("nothing"));
    }

    /**
     * R9. An upstream whose branch has gone from the remote is still the upstream. It is
     * the one the user most needs to be told about, so it cannot read as "tracks nothing".
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anUpstreamThatHasGoneIsStillReported(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        remoteFor(backend);
        forgetTrackingRef(backend, "main");

        assertEquals("origin/main", backend.getUpstream("main"),
                name + ": the configuration still names it");
    }

    // ------------------------------------------------------------------
    // One commit changed in place: the plans the graph's commit menu runs
    // ------------------------------------------------------------------

    /** The fixture's history as subjects, newest first, read with JGit whatever the engine. */
    private static List<String> subjects(GitBackend backend) throws Exception {
        List<String> subjects = new ArrayList<>();
        try (Git git = Git.open(backend.getWorkTree())) {
            for (RevCommit commit : git.log().call()) {
                subjects.add(commit.getShortMessage());
            }
        }
        return subjects;
    }

    private static RebaseTodo planFrom(GitBackend backend, String hash) {
        var range = backend.getRebaseRange(hash);
        assertTrue(range.isUsable(), () -> "no range from " + hash + ": " + range.problem());
        return RebaseTodo.fromNewestFirst(range.commits(), range.base());
    }

    /**
     * H1. Reword: the message changes, body and all, and nothing else does.
     *
     * <p>The message is taken from the plan rather than typed into an editor git opens,
     * which is where the engines could differ: one that ignored it would keep the old
     * message and report success.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void rewordingACommitInPlaceChangesOnlyItsMessage(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String second = parentOfHead();

        GitResult result = backend.rebaseInteractive(
                planFrom(backend, second).rewording(second, "second, reworded\n\nWith a body."));
        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());

        assertEquals(List.of("third", "second, reworded", "first"), subjects(backend));
        try (Git git = Git.open(backend.getWorkTree())) {
            RevCommit reworded = git.log().setMaxCount(2).call().iterator().next().getParent(0);
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                assertEquals("second, reworded\n\nWith a body.",
                        walk.parseCommit(reworded).getFullMessage().strip(),
                        name + ": the whole message, not only its first line");
            }
        }
        assertEquals("one\ntwo\n", read(backend, "a.txt"), name + ": the commit's change is untouched");
        assertEquals("b\n", read(backend, "b.txt"));
    }

    /** H2. Drop: the commit and its change go; the commits after it are replayed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void droppingACommitInPlaceRemovesItsChangeAndKeepsTheRest(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String second = parentOfHead();

        GitResult result = backend.rebaseInteractive(planFrom(backend, second).dropping(second));
        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());

        assertEquals(List.of("third", "first"), subjects(backend));
        assertEquals("one\n", read(backend, "a.txt"), name + ": what the dropped commit added is gone");
        assertEquals("b\n", read(backend, "b.txt"), name + ": the commit after it was replayed");
    }

    /**
     * H3. Dropping the newest commit: the range starts one commit lower, because a plan
     * whose only step is a drop has nothing to replay.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void droppingTheNewestCommitFromARangeStartedBelowIt(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String third = backend.getHeadHash();

        GitResult result = backend.rebaseInteractive(planFrom(backend, parentOfHead()).dropping(third));
        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());

        assertEquals(List.of("second", "first"), subjects(backend));
        assertFalse(new File(backend.getWorkTree(), "b.txt").exists(),
                name + ": the dropped commit's file goes with it");
        assertEquals("one\ntwo\n", read(backend, "a.txt"));
    }

    /** H4. Move: two commits change places, and neither loses its change. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void movingACommitInPlaceSwapsItWithItsNeighbour(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String second = parentOfHead();

        GitResult up = backend.rebaseInteractive(planFrom(backend, second).movingNewer(second));
        assertTrue(up.isOk(), () -> name + ": " + up.getMessage());
        assertEquals(List.of("second", "third", "first"), subjects(backend),
                name + ": \"second\" is now the newer of the two");

        // And back, by the other route: the commit now on top moves down past its parent.
        String newParentOfHead;
        try (Git git = Git.open(backend.getWorkTree())) {
            newParentOfHead = git.log().setMaxCount(1).call().iterator().next().getParent(0).getName();
        }
        GitResult down = backend.rebaseInteractive(
                planFrom(backend, newParentOfHead).movingOlder(backend.getHeadHash()));
        assertTrue(down.isOk(), () -> name + ": " + down.getMessage());
        assertEquals(List.of("third", "second", "first"), subjects(backend));

        assertEquals("one\ntwo\n", read(backend, "a.txt"));
        assertEquals("b\n", read(backend, "b.txt"));
    }

    // ------------------------------------------------------------------
    // A conflict's sides, for a tool outside the application
    // ------------------------------------------------------------------

    private static void commitBytes(GitBackend backend, String path, byte[] bytes, String message)
            throws IOException {
        Files.write(new File(backend.getWorkTree(), path).toPath(), bytes);
        assertTrue(backend.stageFile(path).isOk());
        assertTrue(backend.commit(message, false).isOk());
    }

    /**
     * X1. Each side of a conflict comes back byte for byte.
     *
     * <p>Latin-1 with CRLF line endings, which is neither what a decoder guesses first nor
     * what it writes back. The text accessor may show it however it likes; these bytes
     * are written to disk for an external merge tool, and a tool handed three UTF-8, LF
     * inputs writes a UTF-8, LF result over a file that was neither.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theSidesOfAConflictAreReturnedByteForByte(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        java.nio.charset.Charset latin1 = StandardCharsets.ISO_8859_1;
        byte[] base = "caf\u00e9\r\nbase\r\n".getBytes(latin1);
        byte[] theirs = "caf\u00e9\r\ntheirs\r\n".getBytes(latin1);
        byte[] ours = "caf\u00e9\r\nours\r\n".getBytes(latin1);

        commitBytes(backend, "l.txt", base, "add l");
        assertTrue(backend.createBranch("other", null, true).isOk());
        commitBytes(backend, "l.txt", theirs, "their l");
        assertTrue(backend.checkoutBranch("main").isOk());
        commitBytes(backend, "l.txt", ours, "our l");
        assertTrue(new RepositoryCommands(backend).merge("other", false).isConflict(), name + ": the fixture should conflict");

        assertArrayEquals(base, backend.getConflictStageBytes("l.txt", 1), name + ": the common ancestor");
        assertArrayEquals(ours, backend.getConflictStageBytes("l.txt", 2), name + ": ours");
        assertArrayEquals(theirs, backend.getConflictStageBytes("l.txt", 3), name + ": theirs");

        assertEquals(0, backend.getConflictStageBytes("b.txt", 2).length,
                name + ": a file that is not conflicted has no stages");
        assertEquals(0, backend.getConflictStageBytes("no-such-file.txt", 1).length);
    }

    // ------------------------------------------------------------------
    // Patches
    // ------------------------------------------------------------------

    private File patchFile(String name) {
        return root.resolve(name + (fixtureCount++) + ".patch").toFile();
    }

    private static com.jfx.git.model.PatchApplyMode mode(String name) {
        return com.jfx.git.model.PatchApplyMode.valueOf(name);
    }

    /**
     * T1. A patch saved from commits, applied to the working tree, leaves their changes
     * there and nothing staged.
     *
     * <p>Two commits, named newest first as the graph names them, written oldest first:
     * applied in the other order the second hunk would not find its context.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSavedPatchAppliesToTheWorkingTreeAndStagesNothing(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String third = backend.getHeadHash();
        String second = parentOfHead();
        File patch = patchFile("two");

        GitResult saved = backend.createPatch(List.of(third, second), patch);
        assertTrue(saved.isOk(), () -> name + ": " + saved.getMessage());
        String text = Files.readString(patch.toPath());
        assertTrue(text.indexOf("Subject: [PATCH] second") < text.indexOf("Subject: [PATCH] third"),
                name + ": oldest first, so the file applies in the order the work was done");
        assertTrue(text.contains("From: Test User <test@example.com>"), name + ": the author travels with it");

        // Back to before both, then apply.
        assertTrue(new RepositoryCommands(backend).restoreTo(fixtureCommits.get(0), "rewind for the patch test").isOk());
        assertEquals("one\n", read(backend, "a.txt"));

        GitResult applied = backend.applyPatch(patch, mode("WORKTREE"));
        assertTrue(applied.isOk(), () -> name + ": " + applied.getMessage());

        assertEquals("one\ntwo\n", read(backend, "a.txt"), name + ": the second commit's change");
        assertEquals("b\n", read(backend, "b.txt"), name + ": the third commit's new file");
        var state = backend.getStagingState();
        assertTrue(state.staged().isEmpty(), name + ": nothing staged, was " + state.staged());
        assertEquals(fixtureCommits.get(0), backend.getHeadHash(), name + ": and no commit made");
    }

    /** T2. The same patch applied with staging: the changes are in the index, ready to commit. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSavedPatchAppliedToTheIndexIsStaged(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        File patch = patchFile("third");
        assertTrue(backend.createPatch(List.of(backend.getHeadHash()), patch).isOk());
        assertTrue(new RepositoryCommands(backend).restoreTo(parentOfHead(), "rewind for the patch test").isOk());
        assertFalse(new File(backend.getWorkTree(), "b.txt").exists());

        GitResult applied = backend.applyPatch(patch, mode("INDEX"));
        assertTrue(applied.isOk(), () -> name + ": " + applied.getMessage());

        assertEquals("b\n", read(backend, "b.txt"));
        assertEquals(List.of("b.txt"), backend.getStagingState().staged().stream().sorted().toList(),
                name + ": staged, so a commit takes it");
    }

    /**
     * T3. Applied as commits, a patch becomes the commit it was saved from: same author,
     * same message, same change. An engine that cannot do that says which one can.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSavedPatchAppliedAsCommitsRecreatesThemOrIsRefusedByName(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File patch = patchFile("series");
        assertTrue(backend.createPatch(List.of(backend.getHeadHash(), parentOfHead()), patch).isOk());
        assertTrue(new RepositoryCommands(backend).restoreTo(fixtureCommits.get(0), "rewind for the patch test").isOk());

        GitResult applied = backend.applyPatch(patch, mode("AM"));

        if (!applied.isOk()) {
            assertTrue(applied.getMessage().contains("Native Git engine"),
                    name + ": a refusal has to name what would work, said: " + applied.getMessage());
            assertEquals(fixtureCommits.get(0), backend.getHeadHash(), name + ": and change nothing");
            return;
        }
        assertEquals(List.of("third", "second", "first"), subjects(backend),
                name + ": both commits are back, in order");
        try (Git git = Git.open(backend.getWorkTree())) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            assertEquals("Test User", head.getAuthorIdent().getName());
            assertEquals("test@example.com", head.getAuthorIdent().getEmailAddress());
        }
        assertEquals("one\ntwo\n", read(backend, "a.txt"));
        assertEquals("b\n", read(backend, "b.txt"));
    }

    /** T4. A patch that does not fit is a failure, and leaves the file as it was. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aPatchThatDoesNotFitIsRefusedAndChangesNothing(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File patch = patchFile("second");
        assertTrue(backend.createPatch(List.of(parentOfHead()), patch).isOk());
        // The file no longer looks anything like what the patch was made against.
        write(backend, "a.txt", "entirely\ndifferent\ncontent\n");

        GitResult applied = backend.applyPatch(patch, mode("WORKTREE"));

        assertFalse(applied.isOk(), name + ": the context is gone, so the patch cannot apply");
        assertEquals("entirely\ndifferent\ncontent\n", read(backend, "a.txt"));
    }

    /** T5. What cannot be a patch is refused, with nothing written that looks like one. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aMergeCommitAndNothingAtAllAreNotPatches(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitOnBranch(backend, "side", "side.txt", "side\n");
        assertTrue(new RepositoryCommands(backend).merge("side", true).isOk(), name + ": a real merge commit");
        File patch = patchFile("merge");

        GitResult merge = backend.createPatch(List.of(backend.getHeadHash()), patch);
        assertFalse(merge.isOk(), name + ": a merge has no single patch");
        assertTrue(merge.getMessage().contains("merge commit"), merge.getMessage());

        assertFalse(backend.createPatch(List.of(), patch).isOk(), name + ": no commits named");
        assertFalse(backend.applyPatch(root.resolve("no-such.patch").toFile(), mode("WORKTREE")).isOk());
    }

    // ------------------------------------------------------------------
    // The repository's own configuration
    // ------------------------------------------------------------------

    /**
     * C1. A value set is the value read back, a key removed reads as not set, and an
     * empty value is a value: "no version-tag prefix" is a choice, not an absence.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRepositorySettingIsSetReadBackAndRemoved(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        assertNull(backend.getLocalConfig("gitflow.branch.develop"), name + ": not set to begin with");

        assertTrue(backend.setLocalConfig("gitflow.branch.develop", "develop").isOk());
        assertEquals("develop", backend.getLocalConfig("gitflow.branch.develop"));

        assertTrue(backend.setLocalConfig("gitflow.branch.develop", "integration").isOk());
        assertEquals("integration", backend.getLocalConfig("gitflow.branch.develop"),
                name + ": setting again replaces, it does not add a second value");

        assertTrue(backend.setLocalConfig("gitflow.prefix.versiontag", "").isOk());
        assertEquals("", backend.getLocalConfig("gitflow.prefix.versiontag"),
                name + ": an empty value is set, and reads back as empty rather than as missing");

        assertTrue(backend.setLocalConfig("gitflow.branch.develop", null).isOk());
        assertNull(backend.getLocalConfig("gitflow.branch.develop"), name + ": null removes the key");
        assertTrue(backend.setLocalConfig("gitflow.branch.develop", null).isOk(),
                name + ": removing what is not there is not an error");

        // Written where git reads it: the same file, whichever engine wrote it.
        try (Git git = Git.open(backend.getWorkTree())) {
            var config = git.getRepository().getConfig();
            config.load();
            assertEquals("", config.getString("gitflow", "prefix", "versiontag") == null
                    ? "" : config.getString("gitflow", "prefix", "versiontag"));
            assertNull(config.getString("gitflow", "branch", "develop"));
        }
    }

    /** C2. Something that is not a key is refused rather than written somewhere odd. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aKeyThatIsNotAKeyIsRefused(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);

        assertFalse(backend.setLocalConfig("nodots", "value").isOk());
        assertNull(backend.getLocalConfig("nodots"));
        assertNull(backend.getLocalConfig(null));
    }

    // ------------------------------------------------------------------
    // Git LFS
    // ------------------------------------------------------------------

    /** Whether an LFS refusal is one of the two that say what would work. */
    private static boolean namesTheFix(GitResult refused) {
        return LfsOps.NEEDS_NATIVE.equals(refused.getMessage())
                || LfsOps.NOT_INSTALLED.equals(refused.getMessage());
    }

    /** F1. Which files LFS holds is a file's contents, and every engine can read a file. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theTrackedPatternsAreReadFromGitattributesOnEveryEngine(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        assertEquals(List.of(), backend.lfsTrackedPatterns(), name + ": no .gitattributes, no patterns");

        write(backend, ".gitattributes", "# media\n*.psd filter=lfs diff=lfs merge=lfs -text\n"
                + "*.txt text eol=lf\nassets/** filter=lfs diff=lfs merge=lfs -text\n");

        assertEquals(List.of("*.psd", "assets/**"), backend.lfsTrackedPatterns(),
                name + ": only the lines that hand files to LFS, in the order written");
    }

    /**
     * F2. Tracking and untracking a pattern either does it, or refuses in words that say
     * what would. Never a success that changed nothing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void trackingAPatternWritesItOrIsRefusedByName(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        GitResult tracked = backend.lfsTrack("*.bin");
        if (!tracked.isOk()) {
            assertTrue(namesTheFix(tracked), name + ": a refusal has to name the fix, said: " + tracked.getMessage());
            assertEquals(List.of(), backend.lfsTrackedPatterns(), name + ": and write nothing");
            assertFalse(backend.lfsUntrack("*.bin").isOk());
            return;
        }
        assertEquals(List.of("*.bin"), backend.lfsTrackedPatterns());
        assertTrue(read(backend, ".gitattributes").contains("*.bin filter=lfs"), read(backend, ".gitattributes"));

        GitResult untracked = backend.lfsUntrack("*.bin");
        assertTrue(untracked.isOk(), () -> name + ": " + untracked.getMessage());
        assertEquals(List.of(), backend.lfsTrackedPatterns());

        assertFalse(backend.lfsTrack("  ").isOk(), name + ": nothing named");
        assertFalse(backend.lfsTrack("--everything").isOk(), name + ": an option is not a pattern");
    }

    /** F3. Setting LFS up registers its filter in the repository, not in the user's global config. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void settingLfsUpRegistersTheFilterInThisRepositoryOrIsRefusedByName(String name,
                                                                         Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        GitResult installed = backend.lfsInstall();
        if (!installed.isOk()) {
            assertTrue(namesTheFix(installed), name + ": said: " + installed.getMessage());
            return;
        }
        try (Git git = Git.open(backend.getWorkTree())) {
            var config = git.getRepository().getConfig();
            config.load();
            assertNotNull(config.getString("filter", "lfs", "smudge"),
                    name + ": the smudge filter should be in .git/config");
        }
    }

    /**
     * F4. The rest, on a repository with no remote and no LFS content: pruning has
     * nothing to do, there are no locks to list, and a lock cannot be taken - each
     * answered, none thrown.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theOtherLfsCallsAnswerEvenWithNothingToDo(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        GitResult pruned = backend.lfsPrune();
        assertTrue(pruned.isOk() || namesTheFix(pruned), name + ": said: " + pruned.getMessage());
        assertEquals(List.of(), backend.lfsLocks(), name + ": no server, so no locks to report");
        assertFalse(backend.lfsLock("a.txt").isOk(), name + ": there is no remote to hold a lock");
        assertFalse(backend.lfsLock(" ").isOk());
        assertFalse(backend.lfsUnlock("a.txt", false).isOk());
    }

    // ------------------------------------------------------------------
    // Blame
    // ------------------------------------------------------------------

    /**
     * Blame dates the same line the same way on every engine.
     *
     * <p>Each engine used to format the date itself, the native one as {@code yyyy-MM-dd}
     * and JGit as {@code dd/MM/yyyy}, so the blame column changed format with the engine.
     * The line now carries the author date as an instant - the commit's own, to the second.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void blameCarriesEachLinesAuthorDateAsTheCommitRecordedIt(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        List<BlameLine> lines = backend.getBlameLines("a.txt", fixtureCommits.get(2));

        assertEquals(2, lines.size(), name + ": a.txt has two lines");
        try (Git git = Git.open(backend.getWorkTree());
             org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            for (int i = 0; i < 2; i++) {
                RevCommit source = walk.parseCommit(git.getRepository().resolve(fixtureCommits.get(i)));
                java.time.Instant recorded = source.getAuthorIdent().getWhenAsInstant()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                BlameLine line = lines.get(i);
                assertTrue(source.getName().startsWith(line.hash()),
                        name + ": line " + (i + 1) + " comes from commit " + (i + 1) + ", not " + line.hash());
                assertEquals(recorded, line.when(), name + ": line " + (i + 1) + "'s author date");
            }
        }
    }

    // ------------------------------------------------------------------
    // Conflict prediction
    // ------------------------------------------------------------------

    /**
     * D1. Predicting a merge names the file that would conflict and touches nothing: HEAD,
     * the index and the working tree are as they were, and no merge is left in progress.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void predictingAConflictingMergeNamesTheFileAndChangesNothing(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = conflictingFixture(factory);
        String head = backend.getHeadHash();

        var prediction = backend.predictMergeConflicts("HEAD", "other");

        assertTrue(prediction.supported(), name + ": git " + NativeGitRunner.getGitVersion() + " can answer");
        assertEquals(List.of("a.txt"), prediction.paths(), name);
        assertTrue(prediction.hasConflicts());
        assertEquals(head, backend.getHeadHash(), name + ": HEAD stays where it was");
        assertFalse(backend.hasUncommittedChanges(), name + ": nothing was merged into the tree");
        assertEquals("our side\n", read(backend, "a.txt"));
    }

    /** D2. Branches that touched different files merge cleanly; a name that resolves to nothing cannot be asked about. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void predictingACleanMergeFindsNothingAndAnUnknownBranchIsNoAnswer(String name,
                                                                      Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = divergedFixture(factory);

        var clean = backend.predictMergeConflicts("HEAD", "other");
        assertTrue(clean.supported(), name);
        assertEquals(List.of(), clean.paths(), name);
        assertFalse(clean.hasConflicts());

        var unknown = backend.predictMergeConflicts("HEAD", "no-such-branch");
        assertFalse(unknown.supported(), name + ": an unknown branch is no answer, not a clean merge");
        assertFalse(unknown.hasConflicts());
    }

    // ------------------------------------------------------------------
    // Working-tree snapshots
    // ------------------------------------------------------------------

    private static byte[] indexFile(GitBackend backend) throws IOException {
        return Files.readAllBytes(backend.getDirectory().toPath().resolve("index"));
    }

    /**
     * D3. A snapshot holds the whole working tree - an untracked file included - and taking
     * it changes nothing: not the index file, not the status, not HEAD, not the stash.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSnapshotKeepsTheWholeWorkingTreeAndChangesNothing(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "one\ntwo\nedited\n");
        write(backend, "new.txt", "untracked\n");
        write(backend, "staged.txt", "staged\n");
        assertTrue(backend.stageFile("staged.txt").isOk());
        Files.delete(new File(backend.getWorkTree(), "b.txt").toPath());

        String head = backend.getHeadHash();
        byte[] index = indexFile(backend);
        var status = backend.getStagingState();

        String id = backend.snapshotWorkingTree("Before testing");

        assertNotNull(id, name + ": there were changes to keep");
        assertArrayEquals(index, indexFile(backend), name + ": the index file is untouched");
        assertEquals(status, backend.getStagingState(), name + ": the status is unchanged");
        assertEquals(head, backend.getHeadHash());
        assertEquals(List.of(), backend.getStashEntries(), name + ": nothing went into the stash");
        assertEquals("untracked\n", read(backend, "new.txt"), "the working tree is as it was");

        List<com.jfx.git.model.Snapshot> snapshots = backend.listSnapshots();
        assertEquals(1, snapshots.size(), name + ": " + snapshots);
        var snapshot = snapshots.get(0);
        assertEquals(id, snapshot.id());
        assertEquals("Before testing", snapshot.reason());
        assertEquals("main", snapshot.branch());
        assertEquals(4, snapshot.fileCount(), name + ": a.txt, b.txt, new.txt and staged.txt differ from HEAD");
        assertEquals("untracked\n", new String(backend.getFileBytesAtCommit(snapshot.hash(), "new.txt"),
                StandardCharsets.UTF_8));
        assertEquals("one\ntwo\nedited\n", new String(backend.getFileBytesAtCommit(snapshot.hash(), "a.txt"),
                StandardCharsets.UTF_8));
        assertTrue(backend.getFilesBetweenCommits(head, snapshot.hash()).contains("b.txt"),
                name + ": the deleted file is gone from the snapshot");
        assertTrue(remoteBranchNames(backend).isEmpty(), "a snapshot is not a branch");
    }

    /** D4. Nothing changed - against HEAD, or since the last snapshot - and nothing is taken. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSnapshotOfNothingNewIsNotTaken(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        assertNull(backend.snapshotWorkingTree("clean"), name + ": a clean tree has nothing to keep");

        write(backend, "a.txt", "changed\n");
        assertNotNull(backend.snapshotWorkingTree("first"));
        assertNull(backend.snapshotWorkingTree("again"), name + ": the same tree as the last snapshot");
        write(backend, "a.txt", "changed again\n");
        assertNotNull(backend.snapshotWorkingTree("second"), name + ": a new change is kept");
        assertEquals(List.of("second", "first"),
                backend.listSnapshots().stream().map(com.jfx.git.model.Snapshot::reason).toList(),
                name + ": newest first");
    }

    /**
     * Snapshots taken before the application was renamed, under {@code refs/jfxgit/},
     * are moved to the new prefix the first time an engine looks at them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSnapshotFromBeforeTheRenameIsMovedAcross(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "changed\n");
        String id = backend.snapshotWorkingTree("old build");
        assertNotNull(id);
        File dir = backend.getWorkTree();
        String current = WorkingTreeSnapshots.REF_PREFIX + id;
        String legacy = WorkingTreeSnapshots.LEGACY_REF_PREFIX + id;
        try (Git git = Git.open(dir)) {
            var repo = git.getRepository();
            var ref = repo.exactRef(current);
            var create = repo.updateRef(legacy);
            create.setNewObjectId(ref.getObjectId());
            create.update();
            var delete = repo.updateRef(current);
            delete.setForceUpdate(true);
            delete.delete();
        }

        GitBackend reopened = open(factory, dir);
        assertEquals(List.of("old build"),
                reopened.listSnapshots().stream().map(com.jfx.git.model.Snapshot::reason).toList(), name);
        try (Git git = Git.open(dir)) {
            assertNotNull(git.getRepository().exactRef(current), name + ": under the new prefix");
            assertNull(git.getRepository().exactRef(legacy), name + ": and gone from the old one");
        }
    }

    /**
     * D5. Restoring brings back a file deleted since - one path, or everything - without
     * staging it, and deleting a snapshot removes it from the list.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSnapshotRestoresDeletedFilesAndCanBeDeleted(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "work in progress\n");
        write(backend, "notes.txt", "my notes\n");
        String id = backend.snapshotWorkingTree("Before discarding");
        assertNotNull(id);

        assertTrue(backend.discardAllChanges().isOk());
        assertFalse(new File(backend.getWorkTree(), "notes.txt").exists(), "discard deleted the untracked file");
        assertEquals("one\ntwo\n", read(backend, "a.txt"));

        GitResult one = backend.restoreFromSnapshot(id, List.of("a.txt"));
        assertTrue(one.isOk(), name + ": " + one.getMessage());
        assertEquals("work in progress\n", read(backend, "a.txt"));
        assertFalse(new File(backend.getWorkTree(), "notes.txt").exists(), "only the path asked for");
        assertEquals(List.of(), backend.getStagedFiles(), name + ": restoring does not stage");

        GitResult all = backend.restoreFromSnapshot(id, null);
        assertTrue(all.isOk(), name + ": " + all.getMessage());
        assertEquals("my notes\n", read(backend, "notes.txt"));
        assertEquals(List.of(), backend.getStagedFiles());

        Files.delete(new File(backend.getWorkTree(), "notes.txt").toPath());
        GitResult untracked = backend.restoreFromSnapshot(id, List.of("notes.txt"));
        assertTrue(untracked.isOk(), name + ": a file the index never had comes back on its own too: "
                + untracked.getMessage());
        assertEquals("my notes\n", read(backend, "notes.txt"));

        assertFalse(backend.restoreFromSnapshot(id, List.of("never-there.txt")).isOk(),
                name + ": a file the snapshot does not have is refused");
        assertFalse(backend.restoreFromSnapshot("12345", null).isOk(), "an unknown snapshot is refused");

        assertTrue(backend.deleteSnapshot(id).isOk(), name);
        assertEquals(List.of(), backend.listSnapshots());
        assertFalse(backend.deleteSnapshot(id).isOk(), name + ": it is gone");
    }

    // ------------------------------------------------------------------
    // Branch cleanup: gone upstreams and merged branches
    // ------------------------------------------------------------------

    /**
     * BC1. A branch whose upstream was deleted is gone; one that tracks a branch still
     * there, and one that tracks nothing, are not.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBranchWhoseUpstreamWasDeletedIsGone(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        remoteFor(backend);
        assertTrue(backend.createBranch("side", null, true).isOk());
        GitResult published = new RepositoryCommands(backend).push(null, false, false, false);
        assertTrue(published.isOk(), () -> name + ": " + published.getMessage());
        assertTrue(backend.checkoutBranch("main").isOk());
        assertTrue(backend.createBranch("local-only", null, false).isOk());
        assertEquals(java.util.Set.of(), backend.getGoneBranches(), name + ": nothing has gone yet");

        forgetTrackingRef(backend, "side");

        assertEquals(java.util.Set.of("side"), backend.getGoneBranches(),
                name + ": only the branch whose remote-tracking branch is missing");
        assertEquals("origin/side", backend.getUpstream("side"), name + ": still configured");
    }

    /**
     * BC2. Merged into a target means the target contains the branch's tip: a branch at
     * the target's commit is, one with a commit of its own is not - until the target is
     * that branch.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void branchesMergedIntoATargetAreTheOnesItContains(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        assertTrue(backend.createBranch("merged", parentOfHead(), false).isOk());
        commitOnBranch(backend, "ahead", "c.txt", "c\n");

        assertEquals(List.of("main", "merged"), backend.getBranchesMergedInto("main"),
                name + ": main contains itself and the branch behind it, not the one ahead");
        assertEquals(List.of("ahead", "main", "merged"), backend.getBranchesMergedInto("ahead"),
                name + ": the branch ahead contains all three");
        assertEquals(List.of("merged"), backend.getBranchesMergedInto(parentOfHead()),
                name + ": a commit works as the target too");
        assertEquals(List.of(), backend.getBranchesMergedInto("no-such-branch"),
                name + ": a target that does not resolve contains nothing");
    }

    // ------------------------------------------------------------------
    // Search in files
    // ------------------------------------------------------------------

    /**
     * GR1. The working tree as it is on disk: an edited file's new lines, an untracked
     * file, in path order with the tracked ones; nothing ignored, nothing binary.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void grepSearchesTheWorkingTreeIncludingUntrackedFiles(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "one\ntwo\nthree two\n");
        write(backend, "0-new.txt", "two\r\n");
        assertTrue(new File(backend.getWorkTree(), "dir").mkdirs());
        write(backend, "dir/deep.txt", "x\ntwofold\n");
        write(backend, ".gitignore", "ignored.txt\n");
        write(backend, "ignored.txt", "two\n");
        Files.write(new File(backend.getWorkTree(), "blob.bin").toPath(),
                new byte[]{'t', 'w', 'o', 0, '\n'});

        var found = backend.grep(null, com.jfx.git.model.GrepQuery.literal("two"), 50);

        assertEquals(List.of(
                new com.jfx.git.model.GrepMatch("0-new.txt", 1, "two"),
                new com.jfx.git.model.GrepMatch("a.txt", 2, "two"),
                new com.jfx.git.model.GrepMatch("a.txt", 3, "three two"),
                new com.jfx.git.model.GrepMatch("dir/deep.txt", 2, "twofold")), found, name);
        assertEquals(2, backend.grep(null, com.jfx.git.model.GrepQuery.literal("two"), 2).size(),
                name + ": stops at the limit");
        assertEquals(List.of(), backend.grep(null, com.jfx.git.model.GrepQuery.literal(""), 50),
                name + ": an empty query finds nothing");
    }

    /** GR2. A commit's tree, not the working tree: what the file said then. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void grepAtACommitReadsThatCommitsFiles(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "two two\n");
        write(backend, "untracked.txt", "two\n");

        assertEquals(List.of(new com.jfx.git.model.GrepMatch("a.txt", 2, "two")),
                backend.grep(parentOfHead(), com.jfx.git.model.GrepQuery.literal("two"), 50),
                name + ": the second commit's a.txt, and no untracked file");
        assertEquals(List.of(new com.jfx.git.model.GrepMatch("a.txt", 2, "two")),
                backend.grep("HEAD", com.jfx.git.model.GrepQuery.literal("two"), 50), name + ": HEAD");
        assertEquals(List.of(),
                backend.grep(fixtureCommits.get(0), com.jfx.git.model.GrepQuery.literal("two"), 50),
                name + ": the first commit had no second line");
        assertThrows(GitOperationException.class,
                () -> backend.grep("no-such-rev", com.jfx.git.model.GrepQuery.literal("two"), 50),
                name + ": a revision that does not resolve is an error, not an empty answer");
    }

    /** GR3. Case, whole words, regular expressions and the path filter, read alike. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void grepReadsTheQueryOptionsAlike(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "Two\ntwofold\na.two\naxtwo\n");
        assertTrue(new File(backend.getWorkTree(), "dir").mkdirs());
        write(backend, "dir/c.md", "two\n");

        java.util.function.Function<com.jfx.git.model.GrepQuery, List<String>> lines = query ->
                backend.grep(null, query, 50).stream().map(m -> m.path() + ":" + m.line()).toList();

        assertEquals(List.of("a.txt:2", "a.txt:3", "a.txt:4", "dir/c.md:1"),
                lines.apply(new com.jfx.git.model.GrepQuery("two", true, false, false, "")),
                name + ": match case leaves out Two");
        assertEquals(List.of("a.txt:1", "a.txt:3", "dir/c.md:1"),
                lines.apply(new com.jfx.git.model.GrepQuery("two", false, true, false, "")),
                name + ": whole words only");
        assertEquals(List.of("a.txt:3"),
                lines.apply(new com.jfx.git.model.GrepQuery("a.two", false, false, false, "")),
                name + ": a literal dot is a dot");
        assertEquals(List.of("a.txt:3", "a.txt:4"),
                lines.apply(new com.jfx.git.model.GrepQuery("^a.two$", false, false, true, "")),
                name + ": as a regular expression it is any character");
        assertEquals(List.of("dir/c.md:1"),
                lines.apply(new com.jfx.git.model.GrepQuery("two", false, false, false, "*.md")),
                name + ": a wildcard crosses folders");
        assertEquals(List.of("dir/c.md:1"),
                lines.apply(new com.jfx.git.model.GrepQuery("two", false, false, false, "dir")),
                name + ": a folder");
        assertEquals(List.of("a.txt:1", "a.txt:2", "a.txt:3", "a.txt:4"),
                lines.apply(new com.jfx.git.model.GrepQuery("two", false, false, false, "!dir")),
                name + ": an exclude");
    }

    /** B25. Undo restores the original annotated object byte for byte on each engine. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void tagUndoRestoresExactAnnotatedObject(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String tagName = "undo-annotated";
        assertNull(backend.getRawTagObjectId(tagName), name + ": missing tag has no object id");
        assertTrue(backend.createTag(tagName, "release notes for QA", "HEAD").isOk());

        String originalObjectId = backend.getRawTagObjectId(tagName);
        byte[] originalObjectBytes = rawTagBytes(backend, tagName);
        assertNotNull(originalObjectId, name + ": annotated tag object exists");
        assertNotNull(originalObjectBytes, name + ": annotated tag bytes exist");
        assertNotEquals(backend.getHeadHash(), originalObjectId,
                name + ": annotated ref points to its tag object");

        assertTrue(backend.deleteTag(tagName).isOk());
        assertNull(backend.getRawTagObjectId(tagName), name + ": deleted tag ref is absent");
        assertTrue(backend.restoreTagRef(tagName, originalObjectId).isOk(), name);

        assertEquals(originalObjectId, backend.getRawTagObjectId(tagName), name + ": same object id");
        assertArrayEquals(originalObjectBytes, rawTagBytes(backend, tagName),
                name + ": message, tagger metadata, and any signature bytes are preserved");
    }

    /** B25. The same create-only ref restore also retains a lightweight tag's commit target. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void tagUndoRestoresExactLightweightObjectAndRefusalDoesNotReplaceNewTag(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        String tagName = "undo-lightweight";
        String commitId = backend.getHeadHash();
        assertTrue(backend.createTag(tagName, null, "HEAD").isOk());

        String originalObjectId = backend.getRawTagObjectId(tagName);
        byte[] originalObjectBytes = rawTagBytes(backend, tagName);
        assertEquals(commitId, originalObjectId, name + ": lightweight tag directly names the commit");
        assertTrue(backend.deleteTag(tagName).isOk());
        assertTrue(backend.restoreTagRef(tagName, originalObjectId).isOk(), name);
        assertEquals(originalObjectId, backend.getRawTagObjectId(tagName), name + ": same commit ref");
        assertArrayEquals(originalObjectBytes, rawTagBytes(backend, tagName), name + ": same commit object");

        assertTrue(backend.deleteTag(tagName).isOk());
        assertTrue(backend.createTag(tagName, null, parentOfHead()).isOk());
        String newerObjectId = backend.getRawTagObjectId(tagName);
        assertNotEquals(originalObjectId, newerObjectId, name + ": replacement tag points elsewhere");
        assertFalse(backend.restoreTagRef(tagName, originalObjectId).isOk(),
                name + ": Undo cannot replace a tag recreated after deletion");
        assertEquals(newerObjectId, backend.getRawTagObjectId(tagName),
                name + ": newer tag remains intact after refused Undo");
    }

    private static byte[] rawTagBytes(GitBackend backend, String tagName) throws Exception {
        try (Git git = Git.open(backend.getWorkTree())) {
            var ref = git.getRepository().exactRef("refs/tags/" + tagName);
            return ref == null ? null : git.getRepository().open(ref.getObjectId()).getBytes();
        }
    }
}
