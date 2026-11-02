package com.jfx.git.core;

import com.jfx.git.model.MergeMode;
import com.jfx.git.model.StashEntry;
import com.jfx.git.testsupport.Engines;

import org.eclipse.jgit.api.Git;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stash, check out (or merge), re-apply - on each engine, against a real repository: the
 * edit that was in the way ends up on the other branch or on top of the merge, or stays in
 * the stash when it cannot.
 */
@Tag("heavy")
class AutoStashTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(GitBackend::close);
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    /** {@code main} with {@code a.txt} = "one", and {@code other} adding a line to it. */
    private GitBackend fixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo").toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
            Files.writeString(dir.toPath().resolve("a.txt"), "one\ntwo\nthree\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.branchCreate().setName("other").call();
            git.checkout().setName("other").call();
            Files.writeString(dir.toPath().resolve("a.txt"), "one\ntwo\nthree\nfour\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("on other").call();
            git.checkout().setName("main").call();
        }
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static String read(GitBackend backend, String path) throws Exception {
        return Files.readString(new File(backend.getWorkTree(), path).toPath()).replace("\r\n", "\n");
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    private static final String BASE = "line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String THEIRS = "theirs line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String LOCAL_EDIT = "line 1\nline 2\nline 3\nline 4\nlocal line 5\n";
    private static final String THEIRS_WITH_LOCAL_EDIT = "theirs line 1\nline 2\nline 3\nline 4\nlocal line 5\n";
    private static final String ADDED = "added by the merge\n";
    private static final String NOTES = "my own notes, never added\n";

    /**
     * {@code main} with {@code shared.txt} and one commit {@code incoming} does not have,
     * so merging {@code incoming} - which edits line 1 and adds {@code remote.txt} - makes
     * a real merge commit.
     */
    private GitBackend mergeFixture(Supplier<GitBackend> factory) throws Exception {
        return mergeFixture(factory, "remote.txt");
    }

    /** The same, with the file {@code incoming} adds called {@code added}. */
    private GitBackend mergeFixture(Supplier<GitBackend> factory, String added) throws Exception {
        File dir = root.resolve("merge").toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(dir.toPath().resolve("shared.txt"), BASE);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("base").call();
            git.checkout().setCreateBranch(true).setName("incoming").call();
            Files.writeString(dir.toPath().resolve("shared.txt"), THEIRS);
            Files.writeString(dir.toPath().resolve(added), ADDED);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("incoming").call();
            git.checkout().setName("main").call();
            Files.writeString(dir.toPath().resolve("local.txt"), "local\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("local only").call();
        }
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static GitResult mergeIncoming(GitBackend backend) {
        return backend.merge("incoming", MergeMode.DEFAULT, null);
    }

    /** HEAD's parents, read through JGit rather than the engine under test. */
    private static List<String> headParents(GitBackend backend) throws Exception {
        try (Git git = Git.open(backend.getWorkTree());
             org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            var head = walk.parseCommit(git.getRepository().resolve("HEAD"));
            List<String> parents = new ArrayList<>();
            for (var parent : head.getParents()) parents.add(parent.name());
            return parents;
        }
    }

    private static String tip(GitBackend backend, String ref) throws Exception {
        try (Git git = Git.open(backend.getWorkTree())) {
            return git.getRepository().resolve(ref).name();
        }
    }

    /**
     * Each engine's refusal of a merge over an edited file it changes and an untracked
     * file it adds - git names both, JGit the first path in the way - is recognised, and
     * names the untracked file, so the stash takes it: after the merge the file the merge
     * added is in the commit, and the user's own bytes are back in the work tree.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aMergeBlockedByAnEditAndAnUntrackedFileIsMadeAndBothComeBackByteForByte(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = mergeFixture(factory);
        String ours = backend.getHeadHash();
        String theirs = tip(backend, "refs/heads/incoming");
        Files.writeString(new File(backend.getWorkTree(), "shared.txt").toPath(), LOCAL_EDIT);
        Files.writeString(new File(backend.getWorkTree(), "remote.txt").toPath(), NOTES);

        GitResult refused = mergeIncoming(backend);
        assertInstanceOf(Refusal.LocalChanges.class, refused.getRefusal(), () -> name + ": " + refused.getMessage());
        assertTrue(((Refusal.LocalChanges) refused.getRefusal()).untracked(),
                () -> name + ": the untracked file has to go into the stash: " + refused.getMessage());

        GitResult result = AutoStash.stashAndRetry(backend, AutoStash.Operation.MERGE,
                () -> mergeIncoming(backend), refused);

        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());
        assertEquals(List.of(ours, theirs), headParents(backend), name + ": a merge commit of the two tips");
        assertEquals(THEIRS_WITH_LOCAL_EDIT, read(backend, "shared.txt"),
                name + ": the edit re-applied on top of the merged file");
        assertEquals(NOTES, read(backend, "remote.txt"),
                name + ": the user's own file is back, byte for byte, over the one the merge added");
        assertEquals(List.of(), backend.getStashEntries(), name + ": the stash it made is gone");
    }

    /**
     * When the only thing in the way is an untracked file the refusal does not name at all,
     * a stash without untracked files has nothing to save, which both engines answer as a
     * failure: the user said yes and got "Could not stash your changes". The untracked file
     * is set aside instead - and a stash the user already had stays exactly where it was,
     * never applied or dropped as though this had made it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anUntrackedFileNoRefusalNamesIsSetAsideAndTheUsersOwnStashIsLeftAlone(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = mergeFixture(factory);
        String theirs = tip(backend, "refs/heads/incoming");
        Files.writeString(new File(backend.getWorkTree(), "local.txt").toPath(), "local\nput away earlier\n");
        assertTrue(backend.stash(new com.jfx.git.model.StashOptions("mine", false, false, List.of())).isOk());
        List<StashEntry> theirOwn = backend.getStashEntries();
        assertEquals(1, theirOwn.size(), name + ": sanity, the user's own stash");
        Files.writeString(new File(backend.getWorkTree(), "remote.txt").toPath(), NOTES);
        // An engine that words its refusal without naming the file: nothing says "untracked".
        GitResult refused = GitResult.failed("Your local changes would be overwritten by merge.");

        GitResult result = AutoStash.stashAndRetry(backend, AutoStash.Operation.MERGE,
                () -> mergeIncoming(backend), refused);

        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());
        assertEquals(theirs, headParents(backend).get(1), name + ": the merge was made");
        assertEquals(NOTES, read(backend, "remote.txt"), name + ": the untracked file is back");
        assertEquals("local\n", read(backend, "local.txt"), name + ": the user's stash was not applied");
        assertEquals(theirOwn.stream().map(StashEntry::hash).toList(),
                backend.getStashEntries().stream().map(StashEntry::hash).toList(),
                name + ": and it is still in the list, untouched");
    }

    /**
     * git refuses a real merge over anything staged, related or not, and so does the JGit
     * engine: a staged change to a file the merge never touches is set aside and comes back.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aMergeBlockedByAStagedChangeElsewhereIsMadeAndTheChangeComesBack(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = mergeFixture(factory);
        String ours = backend.getHeadHash();
        String theirs = tip(backend, "refs/heads/incoming");
        Files.writeString(new File(backend.getWorkTree(), "local.txt").toPath(), "local\nstaged more\n");
        assertTrue(backend.stageFile("local.txt").isOk());

        GitResult refused = mergeIncoming(backend);
        assertInstanceOf(Refusal.LocalChanges.class, refused.getRefusal(), () -> name + ": " + refused.getMessage());

        GitResult result = AutoStash.stashAndRetry(backend, AutoStash.Operation.MERGE,
                () -> mergeIncoming(backend), refused);

        assertTrue(result.isOk(), () -> name + ": " + result.getMessage());
        assertEquals(List.of(ours, theirs), headParents(backend), name);
        assertEquals("local\nstaged more\n", read(backend, "local.txt"), name + ": the staged change is back");
        assertEquals(THEIRS, read(backend, "shared.txt"));
        assertEquals(ADDED, read(backend, "remote.txt"));
        assertEquals(List.of(), backend.getStashEntries(), name);
    }

    /**
     * A merge that conflicts once the changes are out of the way stops there: the stash is
     * not popped onto the half-finished merge, and the result says where the changes are.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aMergeThatConflictsOnceTheChangesAreStashedKeepsThemInTheStashAndSaysSo(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = mergeFixture(factory);
        Files.writeString(new File(backend.getWorkTree(), "shared.txt").toPath(),
                "ours line 1\nline 2\nline 3\nline 4\nline 5\n");
        assertTrue(backend.stageFile("shared.txt").isOk());
        assertTrue(backend.commit("ours", false).isOk());
        String edited = "ours line 1\nline 2\nline 3\nline 4\nlocal line 5\n";
        Files.writeString(new File(backend.getWorkTree(), "shared.txt").toPath(), edited);

        GitResult refused = mergeIncoming(backend);
        assertInstanceOf(Refusal.LocalChanges.class, refused.getRefusal(), () -> name + ": " + refused.getMessage());

        GitResult result = AutoStash.stashAndRetry(backend, AutoStash.Operation.MERGE,
                () -> mergeIncoming(backend), refused);

        assertTrue(result.isConflict(), () -> name + ": " + result.getMessage());
        assertTrue(result.getMessage().contains(AutoStash.keptInStash(AutoStash.Operation.MERGE)),
                result.getMessage());
        assertTrue(new File(backend.getDirectory(), "MERGE_HEAD").exists(), name + ": the merge is under way");
        List<StashEntry> stashes = backend.getStashEntries();
        assertEquals(1, stashes.size(), name + ": nothing was dropped");
        assertTrue(AutoStash.isAutoStash(stashes.get(0)), () -> name + ": " + stashes.get(0).message());
    }

    /**
     * Set aside up front (Git Flow's finish): a merge in it that stops on a conflict keeps
     * the changes in the stash rather than popping them into the half-done merge, and says
     * when to pop them - after finishing again, not as soon as the merge is committed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void workSetAsideAroundThatStopsOnAConflictKeepsTheChangesInTheStash(
            String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = mergeFixture(factory);
        Files.writeString(new File(backend.getWorkTree(), "shared.txt").toPath(),
                "ours line 1\nline 2\nline 3\nline 4\nline 5\n");
        assertTrue(backend.stageFile("shared.txt").isOk());
        assertTrue(backend.commit("ours", false).isOk());
        Files.writeString(new File(backend.getWorkTree(), "local.txt").toPath(), "local\nmore\n");
        Files.writeString(new File(backend.getWorkTree(), "scratch.txt").toPath(), NOTES);

        GitResult result = AutoStash.stashAround(backend, AutoStash.Operation.FINISH,
                () -> mergeIncoming(backend));

        assertTrue(result.isConflict(), () -> name + ": " + result.getMessage());
        assertTrue(result.getMessage().contains("pop it once finishing is done"), result.getMessage());
        List<StashEntry> stashes = backend.getStashEntries();
        assertEquals(1, stashes.size(), name + ": the changes wait in the stash");
        assertTrue(AutoStash.isAutoStash(stashes.get(0)), () -> name + ": " + stashes.get(0).message());
        assertFalse(new File(backend.getWorkTree(), "scratch.txt").exists(),
                name + ": the untracked file went into the stash too, not into the merge");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anOperationThatStillFailsPutsTheChangesBack(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        Files.writeString(new File(backend.getWorkTree(), "a.txt").toPath(), "edited\n");
        GitResult refused = backend.checkoutBranch("other");

        GitResult result = AutoStash.stashAndRetry(backend, AutoStash.Operation.CHECKOUT,
                () -> backend.checkoutBranch("no-such-branch"), refused);

        assertFalse(result.isOk(), name);
        assertTrue(result.getMessage().contains("Your changes were put back"), result.getMessage());
        assertEquals("main", backend.getCurrentBranch());
        assertEquals("edited\n", read(backend, "a.txt"));
        assertEquals(List.of(), backend.getStashEntries());
    }
}
