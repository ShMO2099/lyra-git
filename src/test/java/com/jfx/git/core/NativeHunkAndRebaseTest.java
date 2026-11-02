package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three operations that used to fail outright on the native engine: hunk revert,
 * partial staging, and interactive rebase.
 */
@Tag("heavy")
class NativeHunkAndRebaseTest {

    @TempDir
    Path tempDir;

    private NativeGitBackend backend;
    private NativeGitRunner runner;
    private File repoDir;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native git is not installed");

        repoDir = tempDir.toFile();
        runner = new NativeGitRunner(repoDir);
        runner.run("init", "-b", "main");
        runner.run("config", "user.name", "Test User");
        runner.run("config", "user.email", "test@example.com");
        runner.run("config", "core.autocrlf", "false");
        runner.run("config", "commit.gpgsign", "false");

        backend = new NativeGitBackend();
        assertTrue(backend.openRepository(repoDir));
    }

    private void write(String path, String content) throws Exception {
        Files.writeString(new File(repoDir, path).toPath(), content);
    }

    private String read(String path) throws Exception {
        return Files.readString(new File(repoDir, path).toPath());
    }

    private void commit(String path, String content, String message) throws Exception {
        write(path, content);
        assertTrue(backend.stageAll().isOk());
        assertTrue(backend.commit(message, false).isOk());
    }

    private List<String> subjects() {
        return backend.getCommitGraphPage(20, GraphOrder.current()).rows().stream()
                .filter(r -> !r.isSynthetic())
                .map(r -> r.getCommit().getSubject())
                .toList();
    }

    // ------------------------------------------------------------------
    // revertHunk
    // ------------------------------------------------------------------

    @Test
    void revertingAHunkRestoresTheWorkingTreeLines() throws Exception {
        commit("f.txt", "one\ntwo\nthree\n", "base");
        write("f.txt", "one\nCHANGED\nthree\n");

        var result = backend.revertHunk("f.txt", new HunkEdit(2, List.of("CHANGED"), List.of("two")));

        assertTrue(result.isOk(), () -> "revert failed: " + result.getMessage());
        assertEquals("one\ntwo\nthree\n", read("f.txt"));
    }

    @Test
    void revertingAHunkThatNoLongerAppliesIsRefused() throws Exception {
        commit("f.txt", "one\ntwo\n", "base");
        write("f.txt", "something else entirely\n");

        var result = backend.revertHunk("f.txt", new HunkEdit(2, List.of("CHANGED"), List.of("two")));

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("no longer applies"), result.getMessage());
        assertEquals("something else entirely\n", read("f.txt"), "the file must be left alone");
    }

    @Test
    void revertingInAMissingFileIsRefused() {
        var result = backend.revertHunk("absent.txt", new HunkEdit(1, List.of("a"), List.of("b")));
        assertFalse(result.isOk());
    }

    // ------------------------------------------------------------------
    // applyToIndex (partial staging)
    // ------------------------------------------------------------------

    /**
     * Partial staging must leave the index holding something that is in neither HEAD nor
     * the working tree, so the file shows up as both staged and unstaged.
     */
    @Test
    void stagingOneHunkLeavesTheFileInBothLists() throws Exception {
        commit("f.txt", "one\ntwo\nthree\n", "base");
        write("f.txt", "ONE\ntwo\nTHREE\n");

        // Stage only the first line's change.
        var result = backend.applyToIndex("f.txt", new HunkEdit(1, List.of("one"), List.of("ONE")), "Staged hunk");
        assertTrue(result.isOk(), () -> "stage failed: " + result.getMessage());

        assertEquals("ONE\ntwo\nthree\n", TextEncoding.GUESS.decode(backend.getStagedBytes("f.txt")),
                "the index holds only the staged hunk");
        assertEquals("ONE\ntwo\nTHREE\n", read("f.txt"), "the working tree is untouched");

        var state = backend.getStagingState();
        assertTrue(state.staged().contains("f.txt"));
        assertTrue(state.unstaged().contains("f.txt"),
                "the unstaged remainder is still there - that is what partial staging means");
    }

    @Test
    void unstagingOneHunkPutsThatPartBack() throws Exception {
        commit("f.txt", "one\ntwo\n", "base");
        write("f.txt", "ONE\nTWO\n");
        assertTrue(backend.stageAll().isOk());
        assertEquals("ONE\nTWO\n", TextEncoding.GUESS.decode(backend.getStagedBytes("f.txt")));

        var result = backend.applyToIndex("f.txt", new HunkEdit(1, List.of("ONE"), List.of("one")), "Unstaged hunk");

        assertTrue(result.isOk(), () -> "unstage failed: " + result.getMessage());
        assertEquals("one\nTWO\n", TextEncoding.GUESS.decode(backend.getStagedBytes("f.txt")));
    }

    @Test
    void partialStagingSurvivesACommit() throws Exception {
        commit("f.txt", "one\ntwo\n", "base");
        write("f.txt", "ONE\nTWO\n");

        assertTrue(backend.applyToIndex("f.txt", new HunkEdit(1, List.of("one"), List.of("ONE")), "Staged hunk").isOk());
        assertTrue(backend.commit("only the first line", false).isOk());

        assertEquals("ONE\ntwo\n", backend.getFileContentAtCommit("HEAD", "f.txt"),
                "the commit records exactly what was staged");
        assertEquals("ONE\nTWO\n", read("f.txt"), "the rest is still uncommitted");
    }

    @Test
    void aHunkThatNoLongerMatchesTheIndexIsRefused() throws Exception {
        commit("f.txt", "one\n", "base");
        var result = backend.applyToIndex("f.txt", new HunkEdit(1, List.of("nowhere"), List.of("x")), "Staged hunk");
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("no longer applies"), result.getMessage());
    }

    /** The native and JGit engines must agree on what partial staging produces. */
    @Test
    void nativeAndJgitAgreeOnPartialStaging() throws Exception {
        commit("f.txt", "one\ntwo\nthree\n", "base");
        write("f.txt", "ONE\ntwo\nTHREE\n");
        assertTrue(backend.applyToIndex("f.txt", new HunkEdit(1, List.of("one"), List.of("ONE")), "Staged hunk").isOk());
        String fromNative = TextEncoding.GUESS.decode(backend.getStagedBytes("f.txt"));

        GitService jgit = new GitService();
        assertTrue(jgit.openRepository(repoDir));
        try {
            assertEquals(fromNative, TextEncoding.GUESS.decode(jgit.getStagedBytes("f.txt")),
                    "both engines must see the same index");
        } finally {
            jgit.close();
        }
    }

    // ------------------------------------------------------------------
    // rebaseInteractive
    // ------------------------------------------------------------------

    private RebaseTodo planFromHead(int count) {
        List<CommitInfo> commits = backend.getCommitGraphPage(20, GraphOrder.current()).rows().stream()
                .filter(r -> !r.isSynthetic())
                .map(r -> r.getCommit())
                .limit(count)
                .toList();
        var range = backend.getRebaseRange(commits.get(commits.size() - 1).getHash());
        assertTrue(range.isUsable(), () -> "range unusable: " + range.problem());
        return RebaseTodo.fromNewestFirst(range.commits(), range.base());
    }

    @Test
    void squashingFoldsCommitsAndKeepsTheCombinedMessage() throws Exception {
        commit("a.txt", "a\n", "base");
        commit("b.txt", "b\n", "second");
        commit("c.txt", "c\n", "third");

        RebaseTodo plan = planFromHead(2);
        assertEquals(2, plan.size());
        RebaseTodo squashed = plan.squashing(
                java.util.Set.of(plan.step(0).commit().getHash(), plan.step(1).commit().getHash()),
                "second and third, together");
        assertNotNull(squashed);

        var result = backend.rebaseInteractive(squashed);
        assertTrue(result.isOk(), () -> "rebase failed: " + result.getMessage());

        List<String> after = subjects();
        assertEquals(List.of("second and third, together", "base"), after);
        // Both commits' work survives the fold.
        assertTrue(new File(repoDir, "b.txt").exists());
        assertTrue(new File(repoDir, "c.txt").exists());
    }

    @Test
    void rewordingChangesOnlyTheMessage() throws Exception {
        commit("a.txt", "a\n", "base");
        commit("b.txt", "b\n", "typpo in here");

        RebaseTodo plan = planFromHead(1);
        RebaseTodo reworded = plan
                .withAction(0, RebaseTodo.Action.REWORD)
                .withMessage(0, "typo fixed");

        var result = backend.rebaseInteractive(reworded);
        assertTrue(result.isOk(), () -> "rebase failed: " + result.getMessage());

        assertEquals(List.of("typo fixed", "base"), subjects());
        assertTrue(new File(repoDir, "b.txt").exists(), "the changes are untouched");
    }

    @Test
    void droppingRemovesTheCommitAndItsChanges() throws Exception {
        commit("a.txt", "a\n", "base");
        commit("b.txt", "b\n", "unwanted");
        commit("c.txt", "c\n", "wanted");

        RebaseTodo plan = planFromHead(2);
        // Steps run oldest first, so index 0 is "unwanted".
        RebaseTodo dropped = plan.withAction(0, RebaseTodo.Action.DROP);

        var result = backend.rebaseInteractive(dropped);
        assertTrue(result.isOk(), () -> "rebase failed: " + result.getMessage());

        assertEquals(List.of("wanted", "base"), subjects());
        assertFalse(new File(repoDir, "b.txt").exists(), "the dropped commit's file is gone");
        assertTrue(new File(repoDir, "c.txt").exists());
    }

    @Test
    void reorderingReplaysCommitsInTheNewOrder() throws Exception {
        commit("a.txt", "a\n", "base");
        commit("b.txt", "b\n", "first change");
        commit("c.txt", "c\n", "second change");

        RebaseTodo plan = planFromHead(2);
        RebaseTodo moved = plan.moved(0, 1);

        var result = backend.rebaseInteractive(moved);
        assertTrue(result.isOk(), () -> "rebase failed: " + result.getMessage());

        assertEquals(List.of("first change", "second change", "base"), subjects());
    }

    @Test
    void aDirtyWorkingTreeIsRefusedBeforeAnythingIsRewritten() throws Exception {
        commit("a.txt", "a\n", "base");
        commit("b.txt", "b\n", "second");
        write("a.txt", "uncommitted edit\n");

        RebaseTodo plan = planFromHead(1).withAction(0, RebaseTodo.Action.REWORD)
                .withMessage(0, "should not happen");

        var result = backend.rebaseInteractive(plan);
        assertFalse(result.isOk());
        assertEquals(List.of("second", "base"), subjects(), "history is untouched");
    }

    /** The plan git receives is what the todo file says; render it without running one. */
    @Test
    void thePlanIsRenderedAsAGitTodoFile() throws Exception {
        commit("a.txt", "a\n", "base");
        commit("b.txt", "b\n", "second");
        commit("c.txt", "c\n", "third");

        RebaseTodo plan = planFromHead(2);
        RebaseTodo edited = plan
                .withAction(1, RebaseTodo.Action.SQUASH)
                .withMessage(0, "combined");

        List<Path> temporaries = new java.util.ArrayList<>();
        try {
            String todo = backend.buildTodoFile(edited, temporaries);
            List<String> lines = List.of(todo.stripTrailing().split("\n"));

            assertTrue(lines.get(0).startsWith("pick "), lines.toString());
            assertTrue(lines.get(1).startsWith("squash "), lines.toString());
            // The amend lands after the squash, not before it, or the fold would overwrite it.
            assertTrue(lines.get(2).startsWith("exec git commit --amend --no-verify -F "), lines.toString());
            assertEquals(3, lines.size(), lines.toString());
        } finally {
            for (Path p : temporaries) Files.deleteIfExists(p);
        }
    }

    @Test
    void aRewordRendersAsPickPlusAnExplicitAmend() throws Exception {
        commit("a.txt", "a\n", "base");
        commit("b.txt", "b\n", "second");

        RebaseTodo plan = planFromHead(1)
                .withAction(0, RebaseTodo.Action.REWORD)
                .withMessage(0, "new message");

        List<Path> temporaries = new java.util.ArrayList<>();
        try {
            List<String> lines = List.of(backend.buildTodoFile(plan, temporaries)
                    .stripTrailing().split("\n"));
            assertTrue(lines.get(0).startsWith("pick "),
                    "reword must not reach git as 'reword', which would open an editor");
            assertTrue(lines.get(1).startsWith("exec git commit --amend --no-verify -F "), lines.toString());
        } finally {
            for (Path p : temporaries) Files.deleteIfExists(p);
        }
    }
}
