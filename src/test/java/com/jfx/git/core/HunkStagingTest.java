package com.jfx.git.core;

import com.jfx.git.ui.DiffParser;
import com.jfx.git.ui.HunkSelection;
import com.jfx.git.ui.HunkSelection.Patch;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partial staging against a real repository.
 *
 * <p>The invariant worth protecting is that staging part of a file changes the index and
 * nothing else: the working tree keeps every edit, and the unstaged remainder is still
 * there to stage later. A bug here loses the user's work silently, which is why these go
 * through real blobs and a real index rather than a mocked one.
 */
class HunkStagingTest {

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
            config.setString("core", null, "eol", "lf");
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

    private void write(String name, String content) throws Exception {
        Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private String workingTree(String name) throws Exception {
        return Files.readString(tempDir.resolve(name), StandardCharsets.UTF_8);
    }

    /** What the index holds for {@code name}, which is what a commit would record. */
    private String staged(String name) {
        return TextEncoding.GUESS.decode(service.getStagedBytes(name));
    }

    private void commitFile(String name, String content) throws Exception {
        write(name, content);
        assertTrue(service.stageFile(name).isOk());
        assertTrue(service.commit("seed", false).isOk());
    }

    /** Stages {@code selected} lines of the first hunk of the unstaged diff. */
    private GitResult stageFromUnstaged(String name, Set<Integer> selected) {
        String diff = utf8(service.getUnstagedDiffBytes(name, false));
        DiffParser.Hunk hunk = DiffParser.parse(diff).get(0);
        Patch patch = selected == null
                ? HunkSelection.stage(hunk)
                : HunkSelection.stage(hunk, selected);
        return service.applyToIndex(name, new HunkEdit(patch.hintLine(), patch.search(),
                patch.replacement()), "Staged hunk");
    }

    // ------------------------------------------------------------------
    // Staging
    // ------------------------------------------------------------------

    @Test
    void stagingOneHunkLeavesTheOtherUnstaged() throws Exception {
        commitFile("a.txt", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n");
        // Two changes far enough apart that Git emits two hunks.
        write("a.txt", "ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nTEN\n");

        String diff = utf8(service.getUnstagedDiffBytes("a.txt", false));
        List<DiffParser.Hunk> hunks = DiffParser.parse(diff);
        assertEquals(2, hunks.size(), "expected two separate hunks");

        Patch patch = HunkSelection.stage(hunks.get(0));
        assertTrue(service.applyToIndex("a.txt", new HunkEdit(patch.hintLine(), patch.search(),
                patch.replacement()), "Staged hunk").isOk());

        // The index has the first change and not the second.
        assertEquals("ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n", staged("a.txt"));
        // The working tree is untouched: both edits are still there.
        assertEquals("ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nTEN\n",
                workingTree("a.txt"));
    }

    @Test
    void aPartiallyStagedFileAppearsInBothLists() throws Exception {
        commitFile("a.txt", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n");
        write("a.txt", "ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nTEN\n");

        String diff = utf8(service.getUnstagedDiffBytes("a.txt", false));
        Patch patch = HunkSelection.stage(DiffParser.parse(diff).get(0));
        assertTrue(service.applyToIndex("a.txt", new HunkEdit(patch.hintLine(), patch.search(),
                patch.replacement()), "Staged hunk").isOk());

        // Not a bug: half of the file is staged and half is not.
        assertTrue(service.getStagedFiles().contains("a.txt"));
        assertTrue(service.getStagingState().unstaged().contains("a.txt"));
    }

    @Test
    void stagingTheRemainingHunkEmptiesTheUnstagedSide() throws Exception {
        commitFile("a.txt", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n");
        write("a.txt", "ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nTEN\n");

        assertTrue(stageFromUnstaged("a.txt", null).isOk());
        // The second hunk is now the only one left in the unstaged diff.
        assertTrue(stageFromUnstaged("a.txt", null).isOk());

        assertEquals(workingTree("a.txt"), staged("a.txt"));
        assertTrue(service.getStagingState().unstaged().stream().noneMatch("a.txt"::equals));
    }

    @Test
    void stagedContentIsWhatGetsCommitted() throws Exception {
        commitFile("a.txt", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n");
        write("a.txt", "ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nTEN\n");

        assertTrue(stageFromUnstaged("a.txt", null).isOk());
        assertTrue(service.commit("only the first change", false).isOk());

        // The commit holds the staged half; the second edit survives as a local change.
        assertEquals("ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n",
                service.getFileContentAtCommit(
                        service.getFileHistoryCommits("a.txt", 1).get(0), "a.txt"));
        assertTrue(service.getStagingState().unstaged().contains("a.txt"));
    }

    // ------------------------------------------------------------------
    // Line-level selections
    // ------------------------------------------------------------------

    @Test
    void stagingOneLineOfAHunkLeavesTheRest() throws Exception {
        commitFile("a.txt", "keep1\nold2\nkeep3\n");
        write("a.txt", "keep1\nnew2\nkeep3\nadded4\n");

        // Rows 1 and 2 are "-old2" and "+new2"; row 3 is "+added4".
        assertTrue(stageFromUnstaged("a.txt", Set.of(1, 2)).isOk());

        assertEquals("keep1\nnew2\nkeep3\n", staged("a.txt"));
        assertEquals("keep1\nnew2\nkeep3\nadded4\n", workingTree("a.txt"));
    }

    @Test
    void stagingOnlyAnAdditionKeepsTheUnselectedDeletion() throws Exception {
        commitFile("a.txt", "keep1\nold2\nkeep3\n");
        write("a.txt", "keep1\nnew2\nkeep3\nadded4\n");

        // Rows are: 0 " keep1", 1 "-old2", 2 "+new2", 3 " keep3", 4 "+added4".
        assertTrue(stageFromUnstaged("a.txt", Set.of(4)).isOk());

        // "old2" must still be in the index: its removal was not selected.
        assertEquals("keep1\nold2\nkeep3\nadded4\n", staged("a.txt"));
    }

    // ------------------------------------------------------------------
    // Unstaging
    // ------------------------------------------------------------------

    @Test
    void unstagingOneHunkPutsItBackWithoutTouchingTheWorkingTree() throws Exception {
        commitFile("a.txt", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n");
        write("a.txt", "ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nTEN\n");
        assertTrue(service.stageFile("a.txt").isOk());

        String stagedDiff = utf8(service.getStagedDiffBytes("a.txt", false));
        DiffParser.Hunk first = DiffParser.parse(stagedDiff).get(0);
        Patch patch = HunkSelection.unstage(first);
        assertTrue(service.applyToIndex("a.txt", new HunkEdit(patch.hintLine(), patch.search(),
                patch.replacement()), "Unstaged hunk").isOk());

        // The first change is back out of the index; the second is still in it.
        assertEquals("one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nTEN\n", staged("a.txt"));
        assertEquals("ONE\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nTEN\n",
                workingTree("a.txt"));
    }

    // ------------------------------------------------------------------
    // Failure
    // ------------------------------------------------------------------

    @Test
    void aHunkThatNoLongerAppliesIsRefusedRatherThanGuessed() throws Exception {
        commitFile("a.txt", "keep1\nold2\nkeep3\n");
        write("a.txt", "keep1\nnew2\nkeep3\n");
        String diff = utf8(service.getUnstagedDiffBytes("a.txt", false));
        DiffParser.Hunk hunk = DiffParser.parse(diff).get(0);

        // Something else rewrites the staged copy out from under the patch.
        write("a.txt", "totally\ndifferent\ncontent\n");
        assertTrue(service.stageFile("a.txt").isOk());

        Patch patch = HunkSelection.stage(hunk);
        GitResult result = service.applyToIndex("a.txt", new HunkEdit(patch.hintLine(), patch.search(),
                patch.replacement()), "Staged hunk");

        assertTrue(result.describe().contains("no longer applies"), result.describe());
        assertEquals("totally\ndifferent\ncontent\n", staged("a.txt"), "the index is unchanged");
    }

    @Test
    void stagingAHunkOfAnUntrackedFileCreatesTheIndexEntry() throws Exception {
        commitFile("seed.txt", "seed\n");
        write("fresh.txt", "first\nsecond\n");

        assertTrue(stageFromUnstaged("fresh.txt", null).isOk());
        assertEquals("first\nsecond\n", staged("fresh.txt"));
        assertTrue(service.getStagedFiles().contains("fresh.txt"));
    }

    /** A diff the engine hands over as bytes, read as the UTF-8 these fixtures write. */
    private static String utf8(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
