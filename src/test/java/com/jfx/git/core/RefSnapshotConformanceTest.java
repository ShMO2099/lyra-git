package com.jfx.git.core;

import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.graph.GraphVisibility;
import com.jfx.git.model.RefInfo;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every engine says about the repository's refs: the current branch, the remote
 * branches, and how a remote whose name has a slash in it is read.
 *
 * <p>Each scenario runs against the whole engine matrix, because these answers feed one
 * ref snapshot that the graph, the sidebar, the toolbar and the status bar all share - an
 * engine that answered differently would make the views disagree only on that engine.
 */
@Tag("heavy")
class RefSnapshotConformanceTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private int repositories;

    static {
        // Windows will not delete a memory-mapped pack file under @TempDir.
        var config = new org.eclipse.jgit.storage.file.WindowCacheConfig();
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
        opened.clear();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Detached HEAD
    // ------------------------------------------------------------------

    /**
     * A detached HEAD is no current branch, on every engine and in every answer that
     * carries one: blank, never the commit's hash. The engines used to disagree - JGit
     * answered the full hash, the CLI a short one - and the views shown beside each other
     * then named the same state differently.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void aDetachedHeadHasNoCurrentBranch(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = newRepository();
        String first;
        try (Git git = Git.open(dir)) {
            first = commit(git, "a.txt", "two\n", "second").getParent(0).getName();
        }
        Files.writeString(dir.toPath().resolve(".git/HEAD"), first + "\n");
        GitBackend backend = open(factory, dir);

        assertTrue(backend.isHeadDetached(), name);
        assertEquals("", backend.getCurrentBranch(), name + ": a detached HEAD has no current branch");
        assertEquals("", backend.getStagingState().branch(), name + ": nor has the staging state");
    }

    // ------------------------------------------------------------------
    // origin/HEAD
    // ------------------------------------------------------------------

    /**
     * {@code origin/HEAD} is a pointer at another remote branch, not a branch: no engine
     * lists it, so no view has to skip it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void aRemotesSymbolicHeadIsNotARemoteBranch(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = newRepository();
        try (Git git = Git.open(dir)) {
            RevCommit head = git.getRepository().parseCommit(git.getRepository().resolve("HEAD"));
            setRef(git, "refs/remotes/origin/main", head);
            link(git, "refs/remotes/origin/HEAD", "refs/remotes/origin/main");
        }
        GitBackend backend = open(factory, dir);

        List<String> remotes = backend.getRemoteBranches().stream().map(RefInfo::shortName).toList();
        assertEquals(List.of("origin/main"), remotes, name);
    }

    /**
     * {@code origin/HEAD} neither starts the graph's walk nor puts a chip on a row. As a
     * start it brought back the history of the branch it points at after the user hid that
     * branch; as a decoration it doubled every {@code origin/main} chip.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void aRemotesSymbolicHeadNeitherStartsTheWalkNorDecoratesARow(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = newRepository();
        String remoteOnly;
        try (Git git = Git.open(dir)) {
            // A commit only origin/main reaches: made on a side branch, which then goes.
            git.checkout().setCreateBranch(true).setName("side").call();
            RevCommit onRemote = commit(git, "r.txt", "remote\n", "only on the remote");
            remoteOnly = onRemote.getName();
            git.checkout().setName("main").call();
            setRef(git, "refs/remotes/origin/main", onRemote);
            link(git, "refs/remotes/origin/HEAD", "refs/remotes/origin/main");
            deleteRef(git, "refs/heads/side");
        }
        GitBackend backend = open(factory, dir);

        GraphPage all = backend.getCommitGraphPage(50, GraphOrder.current(), GraphVisibility.ALL);
        GitGraphRow row = rowOf(all, remoteOnly);
        assertTrue(row != null, name + ": origin/main's commit is on the page");
        List<String> refs = row.getCommit().getRefs().stream().map(RefInfo::fullName).toList();
        assertEquals(List.of("refs/remotes/origin/main"), refs, name + ": one chip, origin/main's");

        GraphPage hidden = backend.getCommitGraphPage(50, GraphOrder.current(),
                new GraphVisibility(Set.of("refs/remotes/origin/main"), null));
        assertEquals(null, rowOf(hidden, remoteOnly),
                name + ": hiding origin/main hides its history; origin/HEAD does not walk it back in");
    }

    // ------------------------------------------------------------------
    // A remote with a slash in its name
    // ------------------------------------------------------------------

    /**
     * Checking out {@code team/core/topic} makes a local {@code topic}: the branch part is
     * what follows the remote, {@code team/core}, not what follows the first slash - which
     * made a branch called {@code core/topic}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void aSlashNamedRemotesBranchChecksOutUnderItsOwnName(String name, Supplier<GitBackend> factory)
            throws Exception {
        File dir = newRepository();
        try (Git git = Git.open(dir)) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "team/core", "url", root.resolve("elsewhere").toString());
            config.setString("remote", "team/core", "fetch", "+refs/heads/*:refs/remotes/team/core/*");
            config.save();
            RevCommit head = git.getRepository().parseCommit(git.getRepository().resolve("HEAD"));
            setRef(git, "refs/remotes/team/core/topic", head);
        }
        GitBackend backend = open(factory, dir);

        assertTrue(backend.checkoutRemoteBranch("team/core/topic").isOk(), name);

        assertEquals("topic", backend.getCurrentBranch(), name + ": the local branch is named after the remote's");
        assertEquals("team/core/topic", backend.getUpstream("topic"), name + ": and tracks it");
    }

    // ------------------------------------------------------------------
    // The snapshot the page carries
    // ------------------------------------------------------------------

    /**
     * Every engine hands back the same snapshot with its page: the current branch, HEAD,
     * the branches, remote branches and tags - annotated ones peeled - what each branch
     * tracks, every stash and the configured remotes, a remote with a slash in its name
     * among them; and no {@code origin/HEAD}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void everyEngineHandsBackOneSnapshotWithItsPage(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = newRepository();
        String head;
        String stash;
        try (Git git = Git.open(dir)) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", root.resolve("origin.git").toString());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.setString("remote", "team/core", "url", root.resolve("core.git").toString());
            config.setString("remote", "team/core", "fetch", "+refs/heads/*:refs/remotes/team/core/*");
            config.setString("branch", "main", "remote", "origin");
            config.setString("branch", "main", "merge", "refs/heads/main");
            config.save();
            RevCommit commit = git.getRepository().parseCommit(git.getRepository().resolve("HEAD"));
            head = commit.getName();
            git.branchCreate().setName("feature").call();
            git.tag().setName("v1").setAnnotated(true).setMessage("one").call();
            setRef(git, "refs/remotes/origin/main", commit);
            link(git, "refs/remotes/origin/HEAD", "refs/remotes/origin/main");
            setRef(git, "refs/remotes/team/core/topic", commit);
            Files.writeString(dir.toPath().resolve("a.txt"), "changed\n");
            stash = git.stashCreate().call().getName();
        }
        GitBackend backend = open(factory, dir);

        com.jfx.git.model.RefSnapshot refs =
                backend.getCommitGraphPage(50, GraphOrder.current(), GraphVisibility.ALL).refs();

        assertEquals("main", refs.currentBranch(), name);
        assertEquals(head, refs.headHash(), name);
        assertEquals(List.of("feature", "main"), sorted(refs.localBranchNames()), name);
        assertEquals(List.of("origin/main", "team/core/topic"), sorted(refs.remoteBranchNames()), name);
        assertEquals(List.of("v1"), refs.tagNames(), name);
        assertEquals(head, refs.tags().get(0).targetHash(), name + ": the tag peeled to its commit");
        assertEquals(java.util.Map.of("refs/heads/main", "refs/remotes/origin/main"), refs.upstreams(), name);
        assertEquals(List.of(stash), refs.stashes().stream().map(com.jfx.git.model.StashEntry::hash).toList(), name);
        assertEquals(List.of("origin", "team/core"), sorted(refs.remoteNames()), name);
        assertEquals("topic", refs.branchOf("team/core/topic"), name);
    }

    /**
     * The refs read without a page are the page's own: the sidebar and the toolbar draw from
     * them when a page could not be read, and must draw what a page would have brought -
     * the slash-named remote, the upstream, the stash, and no {@code origin/HEAD}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void theRefsReadWithoutAPageAreThePagesOwn(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = newRepository();
        try (Git git = Git.open(dir)) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", root.resolve("origin.git").toString());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.setString("remote", "team/core", "url", root.resolve("core.git").toString());
            config.setString("remote", "team/core", "fetch", "+refs/heads/*:refs/remotes/team/core/*");
            config.setString("branch", "main", "remote", "origin");
            config.setString("branch", "main", "merge", "refs/heads/main");
            config.save();
            RevCommit commit = git.getRepository().parseCommit(git.getRepository().resolve("HEAD"));
            git.branchCreate().setName("feature").call();
            git.tag().setName("v1").setAnnotated(true).setMessage("one").call();
            setRef(git, "refs/remotes/origin/main", commit);
            link(git, "refs/remotes/origin/HEAD", "refs/remotes/origin/main");
            setRef(git, "refs/remotes/team/core/topic", commit);
            Files.writeString(dir.toPath().resolve("a.txt"), "changed\n");
            git.stashCreate().call();
        }
        GitBackend backend = open(factory, dir);

        com.jfx.git.model.RefSnapshot alone = backend.getRefSnapshot();

        assertEquals(backend.getCommitGraphPage(50, GraphOrder.current(), GraphVisibility.ALL).refs(), alone, name);
        assertEquals(List.of("origin/main", "team/core/topic"), sorted(alone.remoteBranchNames()), name);
    }

    /** An unborn branch, read without a page: still the current branch, with no HEAD commit. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void theRefsReadWithoutAPageKnowAnUnbornBranch(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("unborn" + (repositories++)).toFile();
        Git.init().setDirectory(dir).setInitialBranch("trunk").call().close();
        GitBackend backend = open(factory, dir);

        com.jfx.git.model.RefSnapshot alone = backend.getRefSnapshot();

        assertEquals("trunk", alone.currentBranch(), name);
        assertEquals(null, alone.headHash(), name);
        assertTrue(alone.localBranches().isEmpty(), name);
    }

    /**
     * A detached HEAD has no current branch in the snapshot either, and what the user
     * reads names the commit - in one place, so every view says it alike.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void aDetachedHeadIsNoCurrentBranchInTheSnapshot(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = newRepository();
        String head;
        try (Git git = Git.open(dir)) {
            head = git.getRepository().resolve("HEAD").getName();
        }
        Files.writeString(dir.toPath().resolve(".git/HEAD"), head + "\n");
        GitBackend backend = open(factory, dir);

        com.jfx.git.model.RefSnapshot refs =
                backend.getCommitGraphPage(50, GraphOrder.current(), GraphVisibility.ALL).refs();

        assertEquals("", refs.currentBranch(), name);
        assertTrue(refs.detached(), name);
        assertEquals(head, refs.headHash(), name);
        assertEquals("Detached at " + head.substring(0, 7), refs.headLabel(), name);
    }

    /** A branch with no commits yet is still the current branch, with no HEAD commit. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void anUnbornBranchIsStillTheCurrentBranch(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("unborn" + (repositories++)).toFile();
        Git.init().setDirectory(dir).setInitialBranch("trunk").call().close();
        GitBackend backend = open(factory, dir);

        com.jfx.git.model.RefSnapshot refs =
                backend.getCommitGraphPage(50, GraphOrder.current(), GraphVisibility.ALL).refs();

        assertEquals("trunk", backend.getCurrentBranch(), name);
        assertEquals("trunk", refs.currentBranch(), name);
        assertEquals(null, refs.headHash(), name);
        assertFalse(refs.detached(), name);
    }

    /**
     * The snapshot keeps the stashes the user hid from the graph - the sidebar lists them
     * all - while the page draws only the others.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.jfx.git.testsupport.Engines#all")
    void aHiddenStashStaysInTheSnapshotAndOffThePage(String name, Supplier<GitBackend> factory) throws Exception {
        File dir = newRepository();
        String stash;
        try (Git git = Git.open(dir)) {
            Files.writeString(dir.toPath().resolve("a.txt"), "changed\n");
            stash = git.stashCreate().call().getName();
        }
        GitBackend backend = open(factory, dir);
        Settings.useFile(root.resolve("settings-" + (repositories++) + ".properties"));
        try {
            AppPreferences.setHiddenStashes(Set.of(stash));

            GraphPage page = backend.getCommitGraphPage(50, GraphOrder.current(), GraphVisibility.ALL);

            assertEquals(List.of(stash), page.refs().stashes().stream()
                    .map(com.jfx.git.model.StashEntry::hash).toList(), name + ": the snapshot has it");
            assertFalse(page.rows().stream().anyMatch(GitGraphRow::isStash), name + ": the graph does not draw it");
        } finally {
            AppPreferences.setHiddenStashes(null);
            Settings.useFile(null);
        }
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A repository with one commit on {@code main}, built with JGit whichever engine is tested. */
    private File newRepository() throws Exception {
        File dir = root.resolve("repo" + (repositories++)).toFile();
        assertTrue(dir.mkdirs());
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            commit(git, "a.txt", "one\n", "first");
        }
        return dir;
    }

    private GitBackend open(Supplier<GitBackend> factory, File dir) throws GitOperationException {
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static RevCommit commit(Git git, String path, String content, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(path), content);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage(message).call();
    }

    private static void setRef(Git git, String name, ObjectId target) throws Exception {
        RefUpdate update = git.getRepository().updateRef(name);
        update.setNewObjectId(target);
        update.setForceUpdate(true);
        RefUpdate.Result result = update.update();
        assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                || result == RefUpdate.Result.NO_CHANGE, () -> name + ": " + result);
    }

    private static void link(Git git, String name, String target) throws Exception {
        RefUpdate.Result result = git.getRepository().updateRef(name).link(target);
        assertFalse(result == RefUpdate.Result.LOCK_FAILURE || result == RefUpdate.Result.IO_FAILURE,
                () -> name + ": " + result);
    }

    private static void deleteRef(Git git, String name) throws Exception {
        RefUpdate update = git.getRepository().updateRef(name);
        update.setForceUpdate(true);
        update.delete();
    }

    private static GitGraphRow rowOf(GraphPage page, String hash) {
        for (GitGraphRow row : page.rows()) {
            if (row.getCommit() != null && !row.isWipNode() && hash.equals(row.getCommit().getHash())) return row;
        }
        return null;
    }
}
