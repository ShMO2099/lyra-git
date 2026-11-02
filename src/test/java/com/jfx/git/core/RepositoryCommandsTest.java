package com.jfx.git.core;

import com.jfx.git.model.StashEntry;
import com.jfx.git.model.StashOptions;
import com.jfx.git.testsupport.Engines;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conveniences and policies callers build from an engine's primitives, at
 * {@link RepositoryCommands}' interface: each once, on every engine - and none of them,
 * nor the engine-internal methods taken off with them, back on {@link GitBackend}, where
 * an engine could override a policy or a caller reach a method no caller needs.
 */
@Tag("heavy")
class RepositoryCommandsTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
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
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    private GitBackend fixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo" + count++).toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(dir.toPath().resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
        }
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static void write(GitBackend backend, String path, String content) throws Exception {
        Files.writeString(backend.getWorkTree().toPath().resolve(path), content);
    }

    private static String commit(GitBackend backend, String path, String content, String message) throws Exception {
        write(backend, path, content);
        assertTrue(backend.stageFile(path).isOk());
        GitResult made = backend.commit(message, false);
        assertTrue(made.isOk(), made::getMessage);
        return backend.getHeadHash();
    }

    // ------------------------------------------------------------------
    // Off the engine interface
    // ------------------------------------------------------------------

    /**
     * The conveniences live here and the engine-internal methods stay inside the engines.
     * Put back on {@link GitBackend}, a default is something every engine can override -
     * and the composites did, so the restore guard was each engine's to keep.
     */
    @Test
    void noneOfTheseIsBackOnTheEngineInterface() {
        record Signature(String name, Class<?>... parameters) {
            @Override
            public String toString() {
                return name + List.of(parameters);
            }
        }
        List<Signature> gone = List.of(
                // moved to RepositoryCommands
                new Signature("merge", String.class, boolean.class),
                new Signature("stash"),
                new Signature("stashPop", String.class),
                new Signature("stashApply", String.class),
                new Signature("stashDrop", String.class),
                new Signature("stashBranch", String.class, String.class),
                new Signature("push", String.class, boolean.class, boolean.class, boolean.class),
                new Signature("restoreTo", String.class, String.class),
                new Signature("cherryPickAll", List.class, Map.class),
                new Signature("hasAnyLfsTrackedPaths"),
                // conveniences nothing called
                new Signature("stash", String.class),
                new Signature("checkoutPullRequest", String.class, int.class),
                new Signature("checkoutRemoteRef", String.class, String.class, String.class),
                new Signature("cherryPickAll", List.class),
                new Signature("getCommitGraphPage", int.class),
                // the engines' own
                new Signature("abortMerge"),
                new Signature("rebaseAbort"),
                new Signature("rebaseContinue"),
                new Signature("undoLastCommit"),
                new Signature("checkRebaseFrom", String.class),
                new Signature("getStagedContent", String.class),
                new Signature("getUnstagedFiles"),
                new Signature("hasLinkedWorktrees"),
                new Signature("lfsTrackedPaths", Collection.class),
                new Signature("revertHunk", String.class, int.class, List.class, List.class),
                new Signature("applyToIndex", String.class, int.class, List.class, List.class, String.class));
        for (Signature signature : gone) {
            assertThrows(NoSuchMethodException.class,
                    () -> GitBackend.class.getMethod(signature.name(), signature.parameters()),
                    () -> signature + " is on GitBackend again");
        }
    }

    // ------------------------------------------------------------------
    // Stash, by commit
    // ------------------------------------------------------------------

    /**
     * A menu row carries the stash it was drawn for; by the time it is clicked, a pop or
     * drop may have renumbered the list. The commit finds the stash meant, wherever it now
     * is, and one that has gone is said to have gone - never the one now at its old place.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStashIsFoundByItsCommitWhereverItHasMoved(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        for (String each : List.of("oldest", "middle", "newest")) {
            write(backend, "a.txt", each + "\n");
            assertTrue(backend.stash(StashOptions.everything(each)).isOk(), name + ": stash " + each);
        }
        List<StashEntry> drawn = backend.getStashEntries();
        String middle = drawn.get(1).hash();
        assertTrue(drawn.get(1).message().contains("middle"), () -> name + ": " + drawn);

        // The newest goes behind the row's back: "middle" is now stash@{0}.
        assertTrue(backend.stashDrop(0).isOk());
        RepositoryCommands commands = new RepositoryCommands(backend);
        GitResult dropped = commands.stashDrop(middle);

        assertTrue(dropped.isOk(), () -> name + ": " + dropped.getMessage());
        List<StashEntry> left = backend.getStashEntries();
        assertEquals(1, left.size(), () -> name + ": " + left);
        assertTrue(left.get(0).message().contains("oldest"), () -> name + ": the one meant went: " + left);

        GitResult again = commands.stashPop(middle);
        assertFalse(again.isOk(), name + ": it is not there to pop");
        assertTrue(again.getMessage().contains("no longer in the list"), () -> name + ": " + again.getMessage());
        assertEquals(1, backend.getStashEntries().size(), name + ": and nothing else was touched");
    }

    /** {@link RepositoryCommands#stash()} takes everything, untracked files included. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theOneClickStashTakesUntrackedFilesToo(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        write(backend, "a.txt", "edited\n");
        write(backend, "new.txt", "never added\n");

        GitResult stashed = new RepositoryCommands(backend).stash();

        assertTrue(stashed.isOk(), () -> name + ": " + stashed.getMessage());
        assertFalse(backend.hasUncommittedChanges(), () -> name + ": " + backend.getStagingState());
        assertFalse(new File(backend.getWorkTree(), "new.txt").exists(), name + ": the untracked file went too");
    }

    // ------------------------------------------------------------------
    // Cherry-picks in a batch
    // ------------------------------------------------------------------

    /**
     * A batch that stops on a conflict part-way says how far it got - and is still a
     * conflict, as one pick that conflicts is: the repository is mid-cherry-pick with files
     * to resolve, and a plain failure hid that from everything that keys off the status.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aBatchThatConflictsPartWayIsAConflict(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        assertTrue(backend.createBranch("side", null, true).isOk());
        String clean = commit(backend, "b.txt", "side\n", "adds b");
        String clashing = commit(backend, "a.txt", "side's one\n", "changes a");
        assertTrue(backend.checkoutBranch("main").isOk());
        commit(backend, "a.txt", "main's one\n", "main changes a");

        GitResult picked = new RepositoryCommands(backend).cherryPickAll(List.of(clean, clashing), Map.of());

        assertTrue(picked.isConflict(), () -> name + ": " + picked.getStatus() + " " + picked.getMessage());
        assertEquals(List.of("a.txt"), picked.getConflicts(), name);
        assertTrue(picked.getMessage().contains("Applied 1 of 2"), () -> name + ": " + picked.getMessage());
    }
}
