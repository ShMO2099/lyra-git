package com.jfx.git.core;

import com.jfx.git.model.MergeMode;
import com.jfx.git.model.PullMode;
import com.jfx.git.testsupport.Engines;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every kind of {@link Refusal}, from every engine: the failed result carries the typed
 * refusal, set where the engine held git's words or JGit's exception, so no caller has to
 * read the message to know what to offer.
 *
 * <p>The UI used to recognise each of these by matching words - "would be overwritten",
 * "not fully merged", "already exists" - and these scenarios are the ones those matches
 * stood in for: the auto-stash's checkout, merge and pull; the sidebar's force delete, its
 * deinit and worktree removal, and the errors under its add-submodule and add-worktree
 * fields; the clone form's; the pull-request checkout's question.
 */
@Tag("heavy")
class RefusalConformanceTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private final List<Git> gits = new ArrayList<>();
    private int count;

    /** Windows will not delete a memory-mapped pack file, and {@code @TempDir} has to. */
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

    private static final String BASE = "one\ntwo\nthree\n";

    /**
     * {@code main} with {@code a.txt}, and {@code other} one commit ahead: a line added to
     * {@code a.txt} and a new {@code b.txt}. Built with JGit, opened by the engine under test.
     */
    private GitBackend fixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo" + count++).toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            identity(git.getRepository().getConfig());
            Files.writeString(dir.toPath().resolve("a.txt"), BASE);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.checkout().setCreateBranch(true).setName("other").call();
            Files.writeString(dir.toPath().resolve("a.txt"), BASE + "four\n");
            Files.writeString(dir.toPath().resolve("b.txt"), "theirs\n");
            git.add().addFilepattern("a.txt").addFilepattern("b.txt").call();
            git.commit().setMessage("on other").call();
            git.checkout().setName("main").call();
        }
        return open(factory, dir);
    }

    private GitBackend open(Supplier<GitBackend> factory, File dir) throws Exception {
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

    private static void write(GitBackend backend, String path, String content) throws Exception {
        Path file = backend.getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void commitLocally(GitBackend backend, String file, String message) throws Exception {
        write(backend, file, message + "\n");
        assertTrue(backend.stageFile(file).isOk());
        GitResult made = backend.commit(message, false);
        assertTrue(made.isOk(), made::getMessage);
    }

    /** {@code origin}: a bare repository the fixture's {@code main} is pushed to and tracks. */
    private File origin(GitBackend backend) throws Exception {
        File remote = root.resolve("remote" + count++).toFile();
        Git.init().setDirectory(remote).setBare(true).setInitialBranch("main").call().close();
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

    private static void commitAndPush(Git git, String file, String content) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(file), content);
        git.add().addFilepattern(file).call();
        git.commit().setMessage("change " + file).call();
        git.push().setRemote("origin").add(git.getRepository().getBranch()).call();
    }

    private static Refusal.LocalChanges localChanges(String name, GitResult result) {
        assertEquals(GitResult.Status.FAILED, result.getStatus(), () -> name + ": " + result.getMessage());
        return assertInstanceOf(Refusal.LocalChanges.class, result.getRefusal(),
                () -> name + ": a refusal for local changes, said: " + result.getMessage());
    }

    // ------------------------------------------------------------------
    // Local changes in the way
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aCheckoutOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "edited\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.checkoutBranch("other"));

        assertTrue(refusal.paths().contains("a.txt"), () -> name + ": names the file: " + refusal);
        assertFalse(refusal.untracked(), () -> name + ": an edit is no untracked file: " + refusal);
        assertEquals("main", backend.getCurrentBranch(), name + ": nothing switched");
    }

    /**
     * "Create branch here" checks the new branch out, so an edit in the way refuses it like
     * any checkout - and leaves no branch behind. JGit made the branch and then failed to
     * switch to it, so the auto-stash's retry met "already exists" and the user was left a
     * branch they had not got.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void creatingABranchElsewhereOverAnEditIsRefusedAndLeavesNoBranch(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "edited\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.createBranch("topic", "other", true));

        assertTrue(refusal.paths().contains("a.txt"), () -> name + ": names the file: " + refusal);
        assertEquals("main", backend.getCurrentBranch(), name + ": nothing switched");
        assertFalse(backend.getLocalBranches().stream().anyMatch(b -> b.shortName().equals("topic")),
                () -> name + ": no branch is left behind: " + backend.getLocalBranches());
    }

    /**
     * A branch made from a stash starts at the commit the stash was made on, so an edit in
     * the way refuses it like a checkout - typed on every engine. The CLI gave git's words
     * alone where JGit gave the refusal.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBranchFromAStashOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", BASE + "stashed\n");
        GitResult stashed = new RepositoryCommands(backend).stash();
        assertTrue(stashed.isOk(), stashed::getMessage);
        commitLocally(backend, "a.txt", "moved on");
        write(backend, "a.txt", "edited\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.stashBranch("from-stash", 0));

        assertTrue(refusal.paths().contains("a.txt"), () -> name + ": names the file: " + refusal);
        assertEquals("main", backend.getCurrentBranch(), name + ": nothing switched");
    }

    /** The untracked flag is what makes the auto-stash take untracked files too ({@code -u}). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aCheckoutOverAnUntrackedFileSaysItIsUntracked(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "b.txt", "mine\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.checkoutBranch("other"));

        assertTrue(refusal.paths().contains("b.txt"), () -> name + ": names the file: " + refusal);
        assertTrue(refusal.untracked(), () -> name + ": b.txt was never added: " + refusal);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRemoteBranchCheckoutOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        Git other = colleague(remote);
        other.checkout().setCreateBranch(true).setName("feature").call();
        commitAndPush(other, "a.txt", BASE + "feature\n");
        assertTrue(backend.fetchAll().isOk());
        write(backend, "a.txt", "edited\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.checkoutRemoteBranch("origin/feature"));

        assertTrue(refusal.paths().contains("a.txt"), () -> name + ": names the file: " + refusal);
    }

    /** The graph's "Checkout commit": a detached checkout is refused the same way, and offered the same. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aDetachedCheckoutOfACommitOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String commit;
        try (Git git = Git.open(backend.getWorkTree())) {
            commit = git.getRepository().resolve("other").getName();
        }
        write(backend, "a.txt", "edited\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.checkoutBranch(commit));

        assertTrue(refusal.paths().contains("a.txt"), () -> name + ": names the file: " + refusal);
        assertEquals("main", backend.getCurrentBranch(), name + ": nothing switched");
    }

    /** Pushes {@code git}'s HEAD as pull request {@code number}'s head, the way GitHub publishes one. */
    private static void publishPullRequest(Git git, int number) throws Exception {
        git.push().setRemote("origin")
                .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("HEAD:refs/pull/" + number + "/head"))
                .setForce(true)
                .call();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aPullRequestCheckoutOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        Git author = colleague(origin(backend));
        author.checkout().setCreateBranch(true).setName("proposal").call();
        Files.writeString(author.getRepository().getWorkTree().toPath().resolve("a.txt"), BASE + "proposed\n");
        author.add().addFilepattern("a.txt").call();
        author.commit().setMessage("propose").call();
        publishPullRequest(author, 7);
        write(backend, "a.txt", "edited\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.checkoutPullRequest("origin", 7, false));

        assertTrue(refusal.paths().contains("a.txt"), () -> name + ": names the file: " + refusal);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aMergeOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "edited\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.merge("other", MergeMode.DEFAULT, null));

        assertTrue(refusal.paths().contains("a.txt"), () -> name + ": names the file: " + refusal);
    }

    /** git refuses a real merge over anything staged, related to the merge or not. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRealMergeOverStagedWorkIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        commitLocally(backend, "c.txt", "on main");
        write(backend, "d.txt", "staged\n");
        assertTrue(backend.stageFile("d.txt").isOk());

        Refusal.LocalChanges refusal = localChanges(name, backend.merge("other", MergeMode.NO_FF, null));

        assertFalse(refusal.untracked(), () -> name + ": d.txt is in the index: " + refusal);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aPullOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        commitAndPush(colleague(remote), "a.txt", BASE + "upstream\n");
        write(backend, "a.txt", "edited\n");

        Refusal.LocalChanges refusal = localChanges(name, backend.pull(PullMode.MERGE));

        assertTrue(refusal.paths().contains("a.txt"), () -> name + ": names the file: " + refusal);
    }

    /** git's rebasing pull only says that there are changes; it is the same refusal. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRebasingPullOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File remote = origin(backend);
        commitAndPush(colleague(remote), "a.txt", BASE + "upstream\n");
        write(backend, "a.txt", "edited\n");

        localChanges(name, backend.pull(PullMode.REBASE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aCherryPickOverAnEditIsRefusedForLocalChanges(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        String pick;
        try (Git git = Git.open(backend.getWorkTree())) {
            pick = git.getRepository().resolve("other").getName();
        }
        write(backend, "a.txt", "edited\n");

        localChanges(name, backend.cherryPick(pick));
    }

    /** Only a refusal carries one: a plain failure, a conflict and a success do not. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void otherOutcomesCarryNoRefusal(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);

        GitResult missing = backend.checkoutBranch("no-such-branch");
        assertFalse(missing.isOk(), name);
        assertNull(missing.getRefusal(), () -> name + ": " + missing.getMessage());

        commitLocally(backend, "a.txt", "main's own line");
        GitResult conflict = backend.merge("other", MergeMode.DEFAULT, null);
        assertTrue(conflict.isConflict(), () -> name + ": " + conflict.getMessage());
        assertNull(conflict.getRefusal(), name + ": a conflict is a merge that started");
        assertTrue(backend.abortOperation().isOk());

        GitResult fine = backend.checkoutBranch("other");
        assertTrue(fine.isOk(), fine::getMessage);
        assertNull(fine.getRefusal(), name);
    }

    // ------------------------------------------------------------------
    // A branch not fully merged
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void deletingAnUnmergedBranchIsRefusedAsNotFullyMerged(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        GitResult refused = backend.deleteBranch("other", false);

        assertEquals(GitResult.Status.FAILED, refused.getStatus(), () -> name + ": " + refused.getMessage());
        assertEquals(new Refusal.NotFullyMerged("other"), refused.getRefusal(),
                () -> name + ": said: " + refused.getMessage());
        assertTrue(backend.deleteBranch("other", true).isOk(), name + ": forcing deletes it");

        assertTrue(backend.createBranch("merged", null, false).isOk());
        GitResult merged = backend.deleteBranch("merged", false);
        assertTrue(merged.isOk(), merged::getMessage);
        assertNull(merged.getRefusal(), name);
    }

    // ------------------------------------------------------------------
    // A diverged branch
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Local modifications
    // ------------------------------------------------------------------

    /** A superproject with {@code lib} committed as a submodule, checked out. */
    private GitBackend withSubmodule(Supplier<GitBackend> factory) throws Exception {
        File library = root.resolve("library" + count++).toFile();
        try (Git lib = Git.init().setDirectory(library).setInitialBranch("main").call()) {
            identity(lib.getRepository().getConfig());
            Files.writeString(new File(library, "lib.txt").toPath(), "lib\n");
            lib.add().addFilepattern("lib.txt").call();
            lib.commit().setMessage("lib").call();
        }
        File superDir = root.resolve("super" + count++).toFile();
        try (Git sup = Git.init().setDirectory(superDir).setInitialBranch("main").call()) {
            identity(sup.getRepository().getConfig());
            sup.getRepository().getConfig().setBoolean("protocol", "file", "allow", true);
            sup.getRepository().getConfig().save();
            Files.writeString(new File(superDir, "app.txt").toPath(), "app\n");
            sup.add().addFilepattern("app.txt").call();
            sup.commit().setMessage("app").call();
            sup.submoduleAdd().setURI(library.toURI().toString()).setPath("lib").call().close();
            sup.commit().setMessage("add lib").call();
            // Only for building the fixture: git reads "true" as no value it knows.
            sup.getRepository().getConfig().unset("protocol", "file", "allow");
            sup.getRepository().getConfig().save();
        }
        return open(factory, superDir);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void deinitialisingAModifiedSubmoduleIsRefusedForLocalModifications(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = withSubmodule(factory);
        write(backend, "lib/lib.txt", "changed inside the submodule\n");

        GitResult refused = backend.deinitSubmodule("lib", false);

        assertEquals(GitResult.Status.FAILED, refused.getStatus(), () -> name + ": " + refused.getMessage());
        assertEquals(new Refusal.LocalModifications("lib"), refused.getRefusal(),
                () -> name + ": said: " + refused.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void removingAModifiedWorktreeIsRefusedForLocalModifications(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);
        File tree = root.resolve("tree" + count++).toFile();
        GitResult added = backend.addWorktree(tree, "side", true);
        if (!NativeGitRunner.isGitInstalled()) {
            // Worktree changes run the git command line on every engine; without it there is
            // no worktree to refuse to remove.
            assertEquals(Engines.JGIT, name);
            assertFalse(added.isOk());
            return;
        }
        assertTrue(added.isOk(), () -> name + ": " + added.getMessage());
        Files.writeString(tree.toPath().resolve("a.txt"), "edited in the worktree\n");

        GitResult refused = backend.removeWorktree(tree, false);

        assertEquals(GitResult.Status.FAILED, refused.getStatus(), () -> name + ": " + refused.getMessage());
        assertEquals(new Refusal.LocalModifications(tree.getPath()), refused.getRefusal(),
                () -> name + ": said: " + refused.getMessage());
        assertTrue(backend.removeWorktree(tree, true).isOk(), name + ": forcing removes it");
    }

    // ------------------------------------------------------------------
    // Bad input, and the field it belongs to
    // ------------------------------------------------------------------

    private static Refusal.Field fieldOf(String name, GitResult result) {
        assertEquals(GitResult.Status.FAILED, result.getStatus(), () -> name + ": " + result.getMessage());
        return assertInstanceOf(Refusal.BadInput.class, result.getRefusal(),
                () -> name + ": bad input, said: " + result.getMessage()).field();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSubmoduleThatCannotBeAddedSaysWhichField(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = withSubmodule(factory);
        String nowhere = root.resolve("no-such-repository").toUri().toString();

        assertEquals(Refusal.Field.URL, fieldOf(name, backend.addSubmodule(nowhere, "vendor/x")),
                name + ": a URL that is no repository");
        assertEquals(Refusal.Field.PATH, fieldOf(name, backend.addSubmodule(nowhere, "lib")),
                name + ": a folder that is already a submodule");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aWorktreeThatCannotBeAddedSaysWhichField(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        File fresh = root.resolve("fresh" + count++).toFile();
        File taken = root.resolve("taken" + count++).toFile();
        assertTrue(taken.mkdirs());
        Files.writeString(taken.toPath().resolve("here.txt"), "already here\n");

        assertEquals(Refusal.Field.PATH, fieldOf(name, backend.addWorktree(taken, "other", false)),
                name + ": a folder with files in it");
        if (!NativeGitRunner.isGitInstalled()) {
            // Past that check, a worktree is the git command line's on every engine.
            assertEquals(Engines.JGIT, name);
            return;
        }
        assertEquals(Refusal.Field.BRANCH, fieldOf(name, backend.addWorktree(fresh, "main", false)),
                name + ": a branch checked out already");
        assertEquals(Refusal.Field.BRANCH, fieldOf(name, backend.addWorktree(fresh, "no-such-branch", false)),
                name + ": a branch that does not exist");
    }

    /**
     * Clones, by the two engines that make them: a clone opens no repository first, so it
     * is not a {@link GitBackend} call - {@link GitBackendFactory} hands it to git or to
     * JGit, and libgit2, Hybrid and Switchable have no clone of their own to test.
     */
    static Stream<Arguments> cloners() {
        List<Arguments> cloners = new ArrayList<>();
        cloners.add(Arguments.of(Engines.JGIT, (Cloner) GitService::cloneRepository));
        if (NativeGitRunner.isGitInstalled()) {
            cloners.add(Arguments.of(Engines.CLI, (Cloner) NativeGitBackend::cloneRepository));
        }
        return cloners.stream();
    }

    @FunctionalInterface
    interface Cloner {
        GitResult clone(String uri, File dir, String branch, int depth);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cloners")
    void aCloneThatCannotBeMadeSaysWhichField(String name, Cloner cloner) throws Exception {
        GitBackend source = fixture(GitService::new);
        String url = source.getWorkTree().getAbsolutePath();
        File taken = root.resolve("taken" + count++).toFile();
        assertTrue(taken.mkdirs());
        Files.writeString(taken.toPath().resolve("here.txt"), "already here\n");

        assertEquals(Refusal.Field.TARGET, fieldOf(name, cloner.clone(url, taken, null, 0)),
                name + ": a folder with files in it");
        assertEquals(Refusal.Field.URL, fieldOf(name, cloner.clone(
                root.resolve("no-such-repository").toString(), root.resolve("c" + count++).toFile(), null, 0)),
                name + ": a path that is no repository");
        assertEquals(Refusal.Field.BRANCH, fieldOf(name, cloner.clone(
                url, root.resolve("c" + count++).toFile(), "no-such-branch", 0)),
                name + ": a branch the repository does not have");
    }
}
