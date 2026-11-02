package com.jfx.git.core;

import com.jfx.git.core.RebaseTodo.Action;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interactive rebase against real repositories.
 *
 * <p>This is the first thing in the app that can destroy work, so the tests care as much
 * about the failure paths as the happy one: a conflict has to stop cleanly, an abort has
 * to put everything back, and a plan that cannot run has to be refused before the
 * repository is touched at all.
 */
@Tag("heavy")
class InteractiveRebaseTest {

    @TempDir
    Path tempDir;

    private GitService service;

    @BeforeEach
    void setUp() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    /** Four commits, each touching its own file, so nothing conflicts. */
    private void fourIndependentCommits() throws Exception {
        commit("base.txt", "base", "base");
        commit("a.txt", "a", "add a");
        commit("b.txt", "b", "add b");
        commit("c.txt", "c", "add c");
    }

    /** Commits currently on HEAD, newest first. */
    private List<CommitInfo> log() {
        List<CommitInfo> log = new ArrayList<>();
        for (var row : service.getCommitGraphPage(50, GraphOrder.current()).rows()) {
            if (!row.isWipNode()) {
                log.add(row.getCommit());
            }
        }
        return log;
    }

    private List<String> messages() {
        return log().stream().map(CommitInfo::getSubject).toList();
    }

    /** A plan over the newest {@code count} commits. */
    private RebaseTodo planOverLast(int count) {
        return RebaseTodo.fromNewestFirst(log().subList(0, count));
    }

    // ------------------------------------------------------------------
    // Reorder, drop, squash
    // ------------------------------------------------------------------

    @Test
    void reorderingTwoCommitsSwapsThemInHistory() throws Exception {
        fourIndependentCommits();
        assertEquals(List.of("add c", "add b", "add a", "base"), messages());

        // Plan is oldest first: [add a, add b, add c]. Move "add c" to the front.
        RebaseTodo plan = planOverLast(3).moved(2, 0);
        GitResult result = service.rebaseInteractive(plan);

        assertTrue(result.isOk(), result.describe());
        assertEquals(List.of("add b", "add a", "add c", "base"), messages());
    }

    @Test
    void droppingACommitRemovesItAndItsChanges() throws Exception {
        fourIndependentCommits();

        // Oldest first: [add a, add b, add c]; drop "add b".
        GitResult result = service.rebaseInteractive(planOverLast(3).withAction(1, Action.DROP));

        assertTrue(result.isOk(), result.describe());
        assertEquals(List.of("add c", "add a", "base"), messages());
        assertFalse(tempDir.resolve("b.txt").toFile().exists(),
                "the dropped commit's file should be gone");
        assertTrue(tempDir.resolve("c.txt").toFile().exists(),
                "the commits after it must survive");
    }

    @Test
    void squashingFoldsACommitIntoTheOneAboveIt() throws Exception {
        fourIndependentCommits();

        GitResult result = service.rebaseInteractive(planOverLast(3).withAction(1, Action.SQUASH));

        assertTrue(result.isOk(), result.describe());
        assertEquals(3, log().size(), "two commits became one");
        // Both files survive; only the commit boundary went away.
        assertTrue(tempDir.resolve("a.txt").toFile().exists());
        assertTrue(tempDir.resolve("b.txt").toFile().exists());
    }

    @Test
    void fixupFoldsWithoutKeepingTheMessage() throws Exception {
        fourIndependentCommits();

        GitResult result = service.rebaseInteractive(planOverLast(3).withAction(1, Action.FIXUP));

        assertTrue(result.isOk(), result.describe());
        assertEquals(3, log().size());
        assertFalse(messages().contains("add b"), "a fixup discards its own message");
        assertTrue(tempDir.resolve("b.txt").toFile().exists(), "but keeps its changes");
    }

    @Test
    void rewordingReplacesTheMessageAndKeepsTheChanges() throws Exception {
        fourIndependentCommits();

        RebaseTodo plan = planOverLast(3)
                .withAction(0, Action.REWORD)
                .withMessage(0, "add a, explained properly");
        GitResult result = service.rebaseInteractive(plan);

        assertTrue(result.isOk(), result.describe());
        assertTrue(messages().contains("add a, explained properly"), messages().toString());
        assertFalse(messages().contains("add a"));
        assertTrue(tempDir.resolve("a.txt").toFile().exists());
    }

    @Test
    void commitsBeforeTheRangeAreUntouched() throws Exception {
        fourIndependentCommits();
        String baseId = log().get(3).getHash();

        assertTrue(service.rebaseInteractive(planOverLast(3).withAction(1, Action.DROP)).isOk());

        List<CommitInfo> after = log();
        assertEquals(baseId, after.get(after.size() - 1).getHash(),
                "the commit below the range must keep its identity");
    }

    @Test
    void replayedCommitsGetNewIdentities() throws Exception {
        fourIndependentCommits();
        String originalTop = log().get(0).getHash();

        assertTrue(service.rebaseInteractive(planOverLast(3).moved(2, 0)).isOk());

        assertNotEquals(originalTop, log().get(0).getHash(),
                "a rebase rewrites the commits it replays");
    }

    // ------------------------------------------------------------------
    // Refused before anything is touched
    // ------------------------------------------------------------------

    @Test
    void anInvalidPlanIsRefusedWithoutRunning() throws Exception {
        fourIndependentCommits();
        List<String> before = messages();

        GitResult result = service.rebaseInteractive(planOverLast(3).withAction(0, Action.SQUASH));

        assertFalse(result.isOk());
        assertTrue(result.describe().contains("no commit above it"), result.describe());
        assertEquals(before, messages(), "history must be untouched");
    }

    @Test
    void aPlanThatChangesNothingSaysSoInsteadOfRewriting() throws Exception {
        fourIndependentCommits();
        String top = log().get(0).getHash();

        GitResult result = service.rebaseInteractive(planOverLast(3));

        assertTrue(result.isOk());
        assertEquals(top, log().get(0).getHash(), "no commit should have been rewritten");
    }

    @Test
    void aDirtyWorkingTreeBlocksTheRebase() throws Exception {
        fourIndependentCommits();
        Files.writeString(tempDir.resolve("a.txt"), "edited", StandardCharsets.UTF_8);
        List<String> before = messages();

        GitResult result = service.rebaseInteractive(planOverLast(3).withAction(1, Action.DROP));

        assertFalse(result.isOk());
        assertTrue(result.describe().contains("stash"), result.describe());
        assertEquals(before, messages());
        assertEquals("edited", Files.readString(tempDir.resolve("a.txt")),
                "the user's edit must survive being refused");
    }

    @Test
    void aRangeReachingTheFirstCommitIsRefusedClearly() throws Exception {
        fourIndependentCommits();

        // All four, including the root commit, which has no parent to rebase onto.
        GitResult result = service.rebaseInteractive(planOverLast(4).withAction(1, Action.DROP));

        assertFalse(result.isOk());
        assertTrue(result.describe().contains("first commit"), result.describe());
    }

    // ------------------------------------------------------------------
    // Conflict and abort - the path that has to be trustworthy
    // ------------------------------------------------------------------

    /** Three commits all editing the same line, so reordering them must collide. */
    private void threeConflictingCommits() throws Exception {
        commit("base.txt", "base", "base");
        commit("shared.txt", "version one\n", "first edit");
        commit("shared.txt", "version two\n", "second edit");
        commit("shared.txt", "version three\n", "third edit");
    }

    @Test
    void aConflictStopsTheRebaseAndSaysWhere() throws Exception {
        threeConflictingCommits();

        // Reordering edits to the same line cannot apply cleanly.
        GitResult result = service.rebaseInteractive(planOverLast(3).moved(2, 0));

        assertTrue(result.isConflict(), result.describe());
        assertTrue(result.describe().contains("abort"),
                "the message must point at the way out: " + result.describe());
    }

    @Test
    void abortingAConflictedRebasePutsEverythingBack() throws Exception {
        threeConflictingCommits();
        List<String> before = messages();
        String topBefore = log().get(0).getHash();

        assertTrue(service.rebaseInteractive(planOverLast(3).moved(2, 0)).isConflict());

        GitResult aborted = service.abortOperation();
        assertTrue(aborted.isOk(), aborted.describe());

        assertEquals(before, messages(), "abort must restore the original history");
        assertEquals(topBefore, log().get(0).getHash(), "including commit identity");
        assertEquals("version three\n", Files.readString(tempDir.resolve("shared.txt")),
                "and the working tree");
    }

    @Test
    void abortIsSafeToCallEvenAfterASuccessfulRebase() throws Exception {
        fourIndependentCommits();
        assertTrue(service.rebaseInteractive(planOverLast(3).withAction(1, Action.DROP)).isOk());
        List<String> after = messages();

        // Nothing is in progress; this must not undo the rebase that already finished.
        service.abortOperation();
        assertEquals(after, messages());
    }

    // ------------------------------------------------------------------
    // O3 / O4: each message reaches the commit it belongs to, as written
    // ------------------------------------------------------------------

    /** Full messages on HEAD, newest first, read straight from the repository. */
    private List<String> fullMessages() throws Exception {
        List<String> out = new ArrayList<>();
        try (Git git = Git.open(tempDir.toFile())) {
            for (var commit : git.log().call()) {
                out.add(commit.getFullMessage());
            }
        }
        return out;
    }

    /**
     * O3. JGit asks for a message once per reword and once at the end of a run with a
     * squash in it - not once per plan line. A queue in plan order handed a+b the message
     * meant for c, and left c unchanged.
     */
    @Test
    void rewordSquashRewordGivesEachCommitItsOwnMessage() throws Exception {
        fourIndependentCommits();
        // Oldest first: [add a, add b, add c].
        RebaseTodo plan = planOverLast(3)
                .withAction(0, Action.REWORD).withMessage(0, "Ra")
                .withAction(1, Action.SQUASH)
                .withAction(2, Action.REWORD).withMessage(2, "Rc");

        GitResult result = service.rebaseInteractive(plan);

        assertTrue(result.isOk(), result.describe());
        assertEquals(List.of("Rc", "Ra", "base"), messages());
    }

    /** A fixup-only run is never asked about, so it must not consume the next reply. */
    @Test
    void aFixupOnlyRunDoesNotStealTheNextRewordsMessage() throws Exception {
        fourIndependentCommits();
        RebaseTodo plan = planOverLast(3)
                .withAction(1, Action.FIXUP)
                .withAction(2, Action.REWORD).withMessage(2, "renamed c");

        GitResult result = service.rebaseInteractive(plan);

        assertTrue(result.isOk(), result.describe());
        assertEquals(List.of("renamed c", "add a", "base"), messages());
    }

    /** A drop between squashes does not end the run - it is not there for JGit at all. */
    @Test
    void aSquashAfterADropStillFoldsIntoTheCommitAbove() throws Exception {
        fourIndependentCommits();
        commit("d.txt", "d", "add d");
        // Oldest first: [add a, add b, add c, add d].
        RebaseTodo plan = planOverLast(4)
                .withMessage(0, "a, b and d")
                .withAction(1, Action.SQUASH)
                .withAction(2, Action.DROP)
                .withAction(3, Action.SQUASH);

        GitResult result = service.rebaseInteractive(plan);

        assertTrue(result.isOk(), result.describe());
        assertEquals(List.of("a, b and d", "base"), messages());
        assertFalse(Files.exists(tempDir.resolve("c.txt")), "the dropped commit's change is gone");
    }

    /**
     * O4. The plain handler made JGit strip every line starting with '#', so "#123 root
     * cause" and "## Changes" vanished from reworded and squashed messages.
     */
    @Test
    void hashLinesInARewordedMessageSurvive() throws Exception {
        fourIndependentCommits();
        String message = "Fix the parser\n\n#123 root cause\n## Changes\n- one\n";
        RebaseTodo plan = planOverLast(1).withAction(0, Action.REWORD).withMessage(0, message);

        GitResult result = service.rebaseInteractive(plan);

        assertTrue(result.isOk(), result.describe());
        String written = fullMessages().get(0);
        assertTrue(written.contains("#123 root cause"), written);
        assertTrue(written.contains("## Changes"), written);
    }

    @Test
    void hashLinesInASquashMessageSurvive() throws Exception {
        fourIndependentCommits();
        RebaseTodo plan = planOverLast(2)
                .withMessage(0, "Combined\n\n#42 both halves\n")
                .withAction(1, Action.SQUASH);

        GitResult result = service.rebaseInteractive(plan);

        assertTrue(result.isOk(), result.describe());
        assertTrue(fullMessages().get(0).contains("#42 both halves"), fullMessages().get(0));
    }

    /** A plain reword with no new text keeps the commit's own message, body and all. */
    @Test
    void aRewordWithoutNewTextKeepsTheWholeMessage() throws Exception {
        commit("base.txt", "base", "base");
        commit("a.txt", "a", "subject\n\n#7 body line\n");
        RebaseTodo plan = planOverLast(1).withAction(0, Action.REWORD);

        GitResult result = service.rebaseInteractive(plan.normalized());

        assertTrue(result.isOk(), result.describe());
        assertTrue(fullMessages().get(0).contains("#7 body line"), fullMessages().get(0));
    }

    /**
     * A stop part-way (an edit) is resumed by a new rebase command, which needs the rest
     * of the plan's messages - they are saved beside JGit's state and read back.
     */
    @Test
    void messagesAfterAnEditStopStillApplyOnContinue() throws Exception {
        fourIndependentCommits();
        RebaseTodo plan = planOverLast(3)
                .withAction(0, Action.EDIT)
                .withAction(2, Action.REWORD).withMessage(2, "reworded after the stop");

        GitResult stopped = service.rebaseInteractive(plan);
        assertTrue(stopped.isConflict(), stopped.describe());
        assertTrue(service.getOperationState().editStop());

        GitResult continued = service.continueOperation();

        assertTrue(continued.isOk(), continued.describe());
        assertEquals(List.of("reworded after the stop", "add b", "add a", "base"), messages());
    }

    /**
     * O1 / JGit. Abort goes through the rebase's own abort - not a hard reset, which left
     * the rebase directory behind and the repository REBASING on a detached HEAD.
     */
    @Test
    void abortingAStoppedRebaseLeavesNoRebaseBehind() throws Exception {
        threeConflictingCommits();
        assertTrue(service.rebaseInteractive(planOverLast(3).moved(2, 0)).isConflict());

        GitResult aborted = service.abortOperation();

        assertTrue(aborted.isOk(), aborted.describe());
        assertFalse(service.getOperationState().inProgress());
        assertFalse(Files.exists(tempDir.resolve(".git/rebase-merge")));
        assertFalse(service.isHeadDetached());
    }
}
