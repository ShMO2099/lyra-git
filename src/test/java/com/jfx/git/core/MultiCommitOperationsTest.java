package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.dialogs.SquashMessageDialog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Squash, compare and multi-commit cherry-pick — the operations the graph's multiple
 * selection drives.
 */
class MultiCommitOperationsTest {

    @TempDir
    Path tempDir;

    private GitService git;
    private File repoDir;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tempDir.toFile();
        try (var jgit = org.eclipse.jgit.api.Git.init().setDirectory(repoDir).call()) {
            var config = jgit.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        git = new GitService();
        assertTrue(git.openRepository(repoDir));
    }

    private void commit(String path, String content, String message) throws Exception {
        Files.writeString(new File(repoDir, path).toPath(), content);
        assertTrue(git.stageAll().isOk());
        assertTrue(git.commit(message, false).isOk());
    }

    private static CommitInfo fake(String hash, String message) {
        return new CommitInfo(hash, message, "Tester", "t@e.com",
                Instant.EPOCH, List.of(), List.of());
    }

    // ------------------------------------------------------------------
    // Compare two commits
    // ------------------------------------------------------------------

    @Test
    void comparingTwoCommitsListsWhatDiffersBetweenThem() throws Exception {
        commit("a.txt", "one\n", "first");
        String first = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        commit("b.txt", "two\n", "second");
        commit("c.txt", "three\n", "third");
        String third = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        List<String> files = git.getFilesBetweenCommits(first, third);
        assertEquals(Set.of("b.txt", "c.txt"), Set.copyOf(files));
    }

    /**
     * Comparing two points asks what is different now, not what happened along the way,
     * so a file changed and changed back does not appear.
     */
    @Test
    void aFileChangedAndChangedBackIsNotADifference() throws Exception {
        commit("f.txt", "original\n", "first");
        String first = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        commit("f.txt", "edited\n", "second");
        commit("f.txt", "original\n", "third");
        String third = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        assertTrue(git.getFilesBetweenCommits(first, third).isEmpty(),
                "the two end states are identical");
    }

    @Test
    void theDiffBetweenTwoCommitsShowsBothSides() throws Exception {
        commit("f.txt", "before\n", "first");
        String first = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        commit("f.txt", "after\n", "second");
        String second = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        String diff = new String(
                git.getDiffBytesBetweenCommits(first, second, "f.txt", false),
                StandardCharsets.UTF_8);

        assertTrue(diff.contains("-before"), diff);
        assertTrue(diff.contains("+after"), diff);
    }

    @Test
    void comparingIsDirectional() throws Exception {
        commit("f.txt", "before\n", "first");
        String first = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();
        commit("f.txt", "after\n", "second");
        String second = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        String forward = new String(
                git.getDiffBytesBetweenCommits(first, second, "f.txt", false), StandardCharsets.UTF_8);
        String backward = new String(
                git.getDiffBytesBetweenCommits(second, first, "f.txt", false), StandardCharsets.UTF_8);

        assertTrue(forward.contains("+after"));
        assertTrue(backward.contains("+before"), "swapping the ends swaps the diff");
    }

    // ------------------------------------------------------------------
    // Multi-commit cherry-pick
    // ------------------------------------------------------------------

    @Test
    void anEmptySelectionIsRefusedRatherThanReportedAsSuccess() {
        var result = new RepositoryCommands(git).cherryPickAll(List.of(), java.util.Map.of());
        assertFalse(result.isOk());
    }

    /**
     * A half-applied sequence is the state the user has to resolve; continuing past the
     * failure would bury the conflict under later picks.
     */
    @Test
    void aFailureStopsAndSaysHowFarItGot() throws Exception {
        commit("base.txt", "base\n", "base");

        assertTrue(git.createBranch("feature", null, true).isOk());
        commit("conflict.txt", "feature side\n", "feature change");
        String good = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        assertTrue(git.checkoutBranch("master").isOk() || git.checkoutBranch("main").isOk());

        var result = new RepositoryCommands(git).cherryPickAll(List.of(good, "0000000000000000000000000000000000000000"), java.util.Map.of());
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("Applied 1"),
                () -> "should say how far it got, was: " + result.getMessage());
    }

    // ------------------------------------------------------------------
    // Squash planning
    // ------------------------------------------------------------------

    @Test
    void squashingMarksEveryStepButTheOldest() {
        RebaseTodo plan = RebaseTodo.fromOldestFirst(
                List.of(fake("aaa", "first"), fake("bbb", "second"), fake("ccc", "third")), "base");

        RebaseTodo squashed = plan.squashing(Set.of("aaa", "bbb", "ccc"), "combined");

        assertNotNull(squashed);
        assertEquals(RebaseTodo.Action.PICK, squashed.step(0).action(), "the oldest survives");
        assertEquals(RebaseTodo.Action.SQUASH, squashed.step(1).action());
        assertEquals(RebaseTodo.Action.SQUASH, squashed.step(2).action());
        assertEquals(1, squashed.resultingCommitCount());
    }

    @Test
    void theCombinedMessageLandsOnTheSurvivingStep() {
        RebaseTodo plan = RebaseTodo.fromOldestFirst(
                List.of(fake("aaa", "first"), fake("bbb", "second")), "base");

        RebaseTodo squashed = plan.squashing(Set.of("aaa", "bbb"), "one message to rule them");

        assertEquals("one message to rule them", squashed.step(0).effectiveMessage());
    }

    @Test
    void squashingACommitTheRangeDoesNotReplayIsRefused() {
        RebaseTodo plan = RebaseTodo.fromOldestFirst(
                List.of(fake("aaa", "first"), fake("bbb", "second")), "base");

        assertNull(plan.squashing(Set.of("aaa", "not-in-this-plan"), "x"),
                "folding in a commit that is not being replayed is not something to guess at");
    }

    @Test
    void squashingNeedsAtLeastTwoCommits() {
        RebaseTodo plan = RebaseTodo.fromOldestFirst(List.of(fake("aaa", "first")), "base");
        assertNull(plan.squashing(Set.of("aaa"), "x"));
    }

    @Test
    void squashingLeavesCommitsOutsideTheSelectionAlone() {
        RebaseTodo plan = RebaseTodo.fromOldestFirst(
                List.of(fake("aaa", "first"), fake("bbb", "second"), fake("ccc", "third")), "base");

        RebaseTodo squashed = plan.squashing(Set.of("bbb", "ccc"), "folded");

        assertEquals(RebaseTodo.Action.PICK, squashed.step(0).action(), "aaa is untouched");
        assertEquals(RebaseTodo.Action.PICK, squashed.step(1).action(), "bbb is the survivor");
        assertEquals(RebaseTodo.Action.SQUASH, squashed.step(2).action());
        assertEquals(2, squashed.resultingCommitCount());
    }

    // ------------------------------------------------------------------
    // Combined message
    // ------------------------------------------------------------------

    @Test
    void theCombinedMessageReadsOldestFirst() {
        // The graph hands commits over newest first.
        String combined = SquashMessageDialog.combinedMessage(
                List.of(fake("ccc", "third"), fake("bbb", "second"), fake("aaa", "first")));

        assertEquals("first\n\nsecond\n\nthird", combined);
    }

    @Test
    void emptyMessagesAreSkippedRatherThanLeavingBlankGaps() {
        String combined = SquashMessageDialog.combinedMessage(
                List.of(fake("bbb", "kept"), fake("aaa", "   ")));

        assertEquals("kept", combined);
    }

    // ------------------------------------------------------------------
    // Stash entries
    // ------------------------------------------------------------------

    @Test
    void stashEntriesCarryTheCommitTheyWereTakenFrom() throws Exception {
        commit("f.txt", "committed\n", "base");
        String head = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        Files.writeString(new File(repoDir, "f.txt").toPath(), "uncommitted edit\n");
        assertTrue(new RepositoryCommands(git).stash().isOk());

        var entries = git.getStashEntries();
        assertEquals(1, entries.size());
        assertEquals(head, entries.get(0).parentHash(),
                "the graph hangs the stash from the commit it was taken from");
        assertEquals(0, entries.get(0).index());
    }

    @Test
    void nativeAndJgitAgreeOnStashEntries() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled());
        commit("f.txt", "committed\n", "base");
        Files.writeString(new File(repoDir, "f.txt").toPath(), "edit\n");
        assertTrue(new RepositoryCommands(git).stash().isOk());

        try (NativeGitBackend nativeBackend = new NativeGitBackend()) {
            nativeBackend.openRepository(repoDir);

            var fromJgit = git.getStashEntries();
            var fromNative = nativeBackend.getStashEntries();

            assertEquals(fromJgit.size(), fromNative.size());
            assertEquals(fromJgit.get(0).hash(), fromNative.get(0).hash());
            assertEquals(fromJgit.get(0).parentHash(), fromNative.get(0).parentHash());
        }
    }

    @Test
    void nativeAndJgitAgreeOnWhatDiffersBetweenCommits() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled());
        commit("a.txt", "one\n", "first");
        String first = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();
        commit("b.txt", "two\n", "second");
        String second = git.getCommitGraphPage(10, GraphOrder.current()).rows().get(0).getCommit().getHash();

        try (NativeGitBackend nativeBackend = new NativeGitBackend()) {
            nativeBackend.openRepository(repoDir);

            assertEquals(
                    Set.copyOf(git.getFilesBetweenCommits(first, second)),
                    Set.copyOf(nativeBackend.getFilesBetweenCommits(first, second)));
        }
    }
}
