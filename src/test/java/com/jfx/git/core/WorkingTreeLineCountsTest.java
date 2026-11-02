package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How much each uncommitted file changed by.
 *
 * <p>The numbers the staging list draws beside a path. Asserted against a real repository
 * rather than a stub, because the whole risk in this feature is which two trees get
 * compared: index-versus-working-tree and HEAD-versus-index are easy to write down and
 * easy to swap, and a stub would agree with whichever one the code happened to pick.
 */
class WorkingTreeLineCountsTest {

    @TempDir
    Path repoDir;

    private GitService service;

    @BeforeEach
    void openRepository() throws Exception {
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();

            Files.writeString(repoDir.resolve("kept.txt"),
                    "one\ntwo\nthree\nfour\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("kept.txt").call();
            git.commit().setMessage("first").call();
        }
        service = new GitService();
        service.openRepository(repoDir.toFile());
    }

    @AfterEach
    void closeRepository() {
        if (service != null) {
            service.close();
        }
    }

    private int[] unstaged(String path) {
        return service.getWorkingTreeLineCounts(false, List.of(path)).get(path);
    }

    private int[] staged(String path) {
        return service.getWorkingTreeLineCounts(true, List.of(path)).get(path);
    }

    @Test
    void anEditedFileReportsWhatItGainedAndLost() throws Exception {
        // two/three become one line: two removed, one added.
        Files.writeString(repoDir.resolve("kept.txt"),
                "one\nmerged\nfour\n", StandardCharsets.UTF_8);

        int[] counts = unstaged("kept.txt");

        assertNotNull(counts, "an edited tracked file has countable changes");
        assertEquals(1, counts[0], "one line added");
        assertEquals(2, counts[1], "two lines removed");
    }

    @Test
    void theStagedSideAnswersAboutTheIndexNotTheWorkingTree() throws Exception {
        Files.writeString(repoDir.resolve("kept.txt"),
                "one\ntwo\nthree\nfour\nfive\n", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("kept.txt").isOk());

        assertArrayEquals(new int[]{1, 0}, staged("kept.txt"),
                "the index has one more line than HEAD");
        assertNull(unstaged("kept.txt"),
                "and the working tree now matches the index, so it has nothing to report");

        // Edit again without staging: the two sides must now disagree, which is the whole
        // reason they are asked for separately.
        Files.writeString(repoDir.resolve("kept.txt"),
                "one\ntwo\nthree\nfour\nfive\nsix\n", StandardCharsets.UTF_8);

        assertArrayEquals(new int[]{1, 0}, staged("kept.txt"),
                "staging is unaffected by a later edit");
        assertArrayEquals(new int[]{1, 0}, unstaged("kept.txt"),
                "and the unstaged side sees only the new line");
    }

    @Test
    void aNewFileCountsAsAllAdditions() throws Exception {
        Files.writeString(repoDir.resolve("fresh.txt"),
                "a\nb\nc\n", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("fresh.txt").isOk());

        assertArrayEquals(new int[]{3, 0}, staged("fresh.txt"));
    }

    @Test
    void aDeletedFileCountsAsAllRemovals() throws Exception {
        Files.delete(repoDir.resolve("kept.txt"));

        assertArrayEquals(new int[]{0, 4}, unstaged("kept.txt"));
    }

    /**
     * A binary file is left out rather than counted as zero. Git prints a dash there, and
     * a row reading "+0 -0" would claim the file did not change when in fact it did.
     */
    @Test
    void aBinaryFileIsAbsentRatherThanZero() throws Exception {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0x89;
        bytes[7] = 0;
        Files.write(repoDir.resolve("image.bin"), bytes);
        assertTrue(service.stageFile("image.bin").isOk());

        Map<String, int[]> counts = service.getWorkingTreeLineCounts(
                true, List.of("image.bin"));

        assertFalse(counts.containsKey("image.bin"),
                "no entry at all, so the row draws no numbers: " + counts.keySet());
    }

    /**
     * The walk is bounded by what the caller is showing. An unbounded working-tree diff
     * visits everything on disk, which on a real project means the ignored build output.
     */
    @Test
    void onlyTheRequestedPathsAreAnswered() throws Exception {
        Files.writeString(repoDir.resolve("kept.txt"), "changed\n", StandardCharsets.UTF_8);
        Files.writeString(repoDir.resolve("other.txt"), "also changed\n",
                StandardCharsets.UTF_8);
        assertTrue(service.stageFile("other.txt").isOk());

        Map<String, int[]> counts = service.getWorkingTreeLineCounts(
                false, List.of("kept.txt"));

        assertTrue(counts.containsKey("kept.txt"));
        assertFalse(counts.containsKey("other.txt"),
                "a path that was not asked about should not be walked");
    }

    @Test
    void askingAboutNothingReadsNothing() {
        assertTrue(service.getWorkingTreeLineCounts(false, List.of()).isEmpty());
        assertTrue(service.getWorkingTreeLineCounts(true, null).isEmpty());
    }
}
