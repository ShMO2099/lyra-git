package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.graph.GraphVisibility;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A stash made on a commit that has since been rewritten away - the branch amended,
 * rebased or reset - is still on the graph, sitting beside that commit, on every engine.
 *
 * <p>The graph used to be walked from branches and tags only. Nothing reached the stash's
 * base any more, so the base never made the page, the stash row - which is placed beside
 * its base or not at all - was dropped, and clicking the stash in the sidebar selected
 * nothing.
 *
 * <p>And the other way about: a stash made on a branch the user has hidden does not bring
 * that branch's history back. Hide means hide.
 */
class StashOnRewrittenHistoryTest {

    @TempDir
    Path tempDir;

    private String stashHash;
    private String baseHash;

    private Git init() throws Exception {
        Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call();
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "T");
        config.setString("user", null, "email", "t@example.com");
        config.save();
        return git;
    }

    private void stashOnACommitNothingReaches() throws Exception {
        try (Git git = init()) {
            Files.writeString(tempDir.resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").setSign(false).call();
            Files.writeString(tempDir.resolve("a.txt"), "one\nwip\n");
            git.add().addFilepattern("a.txt").call();
            baseHash = git.commit().setMessage("wip").setSign(false).call().getName();
            Files.writeString(tempDir.resolve("a.txt"), "one\nwip\nhalf done\n");
            stashHash = git.stashCreate().setWorkingDirectoryMessage("half done").call().getName();
            // The wip commit is rewritten away: only the stash reaches it now.
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call();
            Files.writeString(tempDir.resolve("a.txt"), "one\nreworded\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("reworded").setSign(false).call();
        }
    }

    /** A stash made on {@code feature}'s tip, with {@code main} checked out again after. */
    private void stashOnABranch() throws Exception {
        try (Git git = init()) {
            Files.writeString(tempDir.resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").setSign(false).call();
            git.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(tempDir.resolve("a.txt"), "one\nfeature\n");
            git.add().addFilepattern("a.txt").call();
            baseHash = git.commit().setMessage("feature work").setSign(false).call().getName();
            Files.writeString(tempDir.resolve("a.txt"), "one\nfeature\nhalf done\n");
            stashHash = git.stashCreate().setWorkingDirectoryMessage("half done").call().getName();
            git.checkout().setName("main").call();
        }
    }

    private GitBackend open(String engine) {
        GitBackend backend = switch (engine) {
            case "jgit" -> new GitService();
            case "native" -> {
                assumeTrue(NativeGitRunner.isGitInstalled(), "needs git on PATH");
                yield new NativeGitBackend();
            }
            default -> throw new IllegalArgumentException(engine);
        };
        backend.openRepository(tempDir.toFile());
        return backend;
    }

    private static boolean draws(GraphPage page, String hash) {
        return page.rows().stream().anyMatch(r -> r.getCommit() != null && hash.equals(r.getCommit().getHash()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jgit", "native"})
    void theStashAndTheCommitItWasMadeOnAreOnTheGraph(String engine) throws Exception {
        stashOnACommitNothingReaches();
        GitBackend backend = open(engine);
        try {
            GraphPage page = backend.getCommitGraphPageVisible(100);
            assertTrue(draws(page, baseHash), "the base commit is drawn, though no branch reaches it");
            assertEquals(1, page.rows().stream().filter(r -> r.isStash()
                            && stashHash.equals(r.getStash().hash())).count(),
                    "and the stash beside it");
        } finally {
            backend.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jgit", "native"})
    void aHiddenStashDoesNotDrawItsHistory(String engine) throws Exception {
        stashOnACommitNothingReaches();
        // Opened first: the native engine's assumption aborts the test before the shared
        // settings are touched, so nothing is left behind for the tests that run after it.
        GitBackend backend = open(engine);
        Set<String> before = AppPreferences.hiddenStashes();
        try {
            AppPreferences.setHiddenStashes(Set.of(stashHash));
            GraphPage page = backend.getCommitGraphPageVisible(100);
            assertFalse(draws(page, baseHash), "hidden, the stash brings nothing onto the graph");
        } finally {
            AppPreferences.setHiddenStashes(before);
            backend.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jgit", "native"})
    void aStashOnAHiddenBranchDoesNotBringTheBranchBack(String engine) throws Exception {
        stashOnABranch();
        GitBackend backend = open(engine);
        try {
            assertTrue(draws(backend.getCommitGraphPage(100, GraphOrder.BY_BRANCH, GraphVisibility.ALL), baseHash),
                    "shown, the branch's commit is drawn");
            GraphPage hidden = backend.getCommitGraphPage(100, GraphOrder.BY_BRANCH,
                    new GraphVisibility(Set.of("refs/heads/feature"), null));
            assertFalse(draws(hidden, baseHash),
                    "hidden, the branch stays hidden though a stash was made on its tip");
        } finally {
            backend.close();
        }
    }
}
