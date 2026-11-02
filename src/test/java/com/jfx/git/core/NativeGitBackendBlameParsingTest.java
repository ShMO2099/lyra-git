package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NativeGitBackend#parseBlamePorcelain} exercised directly against a hand-built
 * porcelain string, rather than through a real git process.
 *
 * <p>The crash this guards against ("Index 40 out of bounds for length 40", reported live
 * from File History's Blame toggle) depends on a metadata line's exact character count -
 * real author names, emails and paths hit or miss that length by pure coincidence, which is
 * why it surfaced on some files' history and not others despite the parsing code being the
 * same. A synthetic porcelain string makes the length deliberate and the test deterministic
 * instead of hoping some future commit's metadata happens to be the wrong length again.
 */
class NativeGitBackendBlameParsingTest {

    private static final String SHA = "0123456789012345678901234567890123456789";

    @Test
    void aFortyCharacterMetadataLineDoesNotAbortParsing() {
        // "committer " (10 chars) + a 30-character name is exactly 40 characters - one
        // past charAt's last valid index (39) on a 40-character string, which is exactly
        // what NativeGitBackend.getBlameLines used to hand to charAt(40) unchecked.
        String thirtyCharName = "X".repeat(30);
        assertEquals(40, ("committer " + thirtyCharName).length(),
                "test fixture must hit exactly 40 characters to exercise the bug");

        String porcelain = String.join("\n",
                SHA + " 1 1 1",
                "author Test User",
                "author-mail <test@example.com>",
                "author-time 1700000000",
                "author-tz +0000",
                "committer " + thirtyCharName,
                "committer-mail <test@example.com>",
                "committer-time 1700000000",
                "committer-tz +0000",
                "summary Test commit",
                "filename test.txt",
                "\tHello World"
        );

        List<BlameLine> lines = assertDoesNotThrow(
                () -> NativeGitBackend.parseBlamePorcelain(porcelain),
                "a coincidentally 40-character metadata line must not abort the whole blame");

        assertEquals(1, lines.size());
        assertEquals(SHA.substring(0, 8), lines.get(0).hash());
        assertEquals("Test User", lines.get(0).author());
        assertEquals("Test commit", lines.get(0).message());
        assertEquals("Hello World", lines.get(0).text());
        assertEquals(java.time.Instant.ofEpochSecond(1700000000L), lines.get(0).when(),
                "the author time as an instant, for the view to write in the user's format");
    }

    @Test
    void aGenuineCommitHeaderLineIsStillRecognised() {
        // The one real case the length/charAt check exists for: a genuine commit-header
        // line is always well past 40 characters, so tightening the guard to `> 40` must
        // not stop matching it.
        String porcelain = String.join("\n",
                SHA + " 3 3 1",
                "author Another Author",
                "summary Another commit",
                "\tSecond line"
        );

        List<BlameLine> lines = NativeGitBackend.parseBlamePorcelain(porcelain);

        assertEquals(1, lines.size());
        assertEquals(SHA.substring(0, 8), lines.get(0).hash());
        assertEquals("Another Author", lines.get(0).author());
        assertEquals("Second line", lines.get(0).text());
    }

    private static final String SHA_B = "abcdefabcdefabcdefabcdefabcdefabcdefabcd";

    /**
     * Porcelain describes a commit in full only the first time it appears. A file whose
     * history interleaves two commits - the ordinary case - therefore reaches a run of
     * lines belonging to a commit already described, introduced by its sha alone.
     */
    @Test
    void aCommitRevisitedLaterKeepsItsOwnAuthorSubjectAndDate() {
        String porcelain = String.join("\n",
                // First run: commit A, described in full.
                SHA + " 1 1 1",
                "author Ada",
                "author-time 1600000000",
                "summary Implement file history view",
                "filename f.java",
                "\tline one",
                // Second run: a different commit, also described in full.
                SHA_B + " 2 2 1",
                "author Bob",
                "author-time 1700000000",
                "summary Add comprehensive tests",
                "filename f.java",
                "\tline two",
                // Third run: commit A again - sha only, no block follows it.
                SHA + " 3 3 1",
                "\tline three"
        );

        List<BlameLine> lines = NativeGitBackend.parseBlamePorcelain(porcelain);

        assertEquals(3, lines.size());
        assertEquals("Ada", lines.get(0).author());
        assertEquals("Bob", lines.get(1).author());

        BlameLine revisited = lines.get(2);
        assertEquals(SHA.substring(0, 8), revisited.hash(),
                "the sha was always read correctly - it is the rest that went missing");
        assertEquals("Ada", revisited.author(),
                "a revisited commit must keep its own author, not inherit the previous run's");
        assertEquals("Implement file history view", revisited.message(),
                "nor its subject - this is the mismatch visible against commercial clients");
        assertEquals(lines.get(0).when(), revisited.when(),
                "nor its date");
        assertNotEquals(lines.get(1).when(), revisited.when(),
                "and specifically not the interleaved commit's date");
    }

    @Test
    void multipleLinesFromTheSameCommitGroupShareItsMetadata() {
        // Second and later lines in a run from the same commit repeat only the short
        // "<sha> <orig> <final>" form (no trailing line count) - still well past 40 chars.
        String porcelain = String.join("\n",
                SHA + " 1 1 2",
                "author Repeated Author",
                "summary Shared commit",
                "\tFirst line",
                SHA + " 2 2",
                "\tSecond line"
        );

        List<BlameLine> lines = NativeGitBackend.parseBlamePorcelain(porcelain);

        assertEquals(2, lines.size());
        assertEquals("First line", lines.get(0).text());
        assertEquals("Second line", lines.get(1).text());
        assertEquals(lines.get(0).hash(), lines.get(1).hash());
        assertEquals("Repeated Author", lines.get(1).author());
    }

    /**
     * G12. A metadata line with a space at index 40 was read as a commit header, and its
     * first forty characters became the "sha" of every line after it.
     */
    @Test
    void aMetadataLineWithASpaceAtFortyIsNotAHeader() {
        String committer = "committer " + "Y".repeat(30) + " Lastname";
        assertEquals(' ', committer.charAt(40), "fixture must have its space at index 40");

        String porcelain = String.join("\n",
                SHA + " 1 1 1",
                "author Real Author",
                "summary Real commit",
                committer,
                "filename a.txt",
                "\tthe line");

        List<BlameLine> lines = NativeGitBackend.parseBlamePorcelain(porcelain);

        assertEquals(1, lines.size());
        assertEquals(SHA.substring(0, 8), lines.get(0).hash());
        assertEquals("Real Author", lines.get(0).author());
    }

    @Test
    void aSha256HeaderIsRecognised() {
        String sha256 = "ab".repeat(32);
        String porcelain = String.join("\n",
                sha256 + " 1 1 1",
                "author Wide Hash",
                "summary sha256 repository",
                "\tcontent");

        List<BlameLine> lines = NativeGitBackend.parseBlamePorcelain(porcelain);

        assertEquals(1, lines.size());
        assertEquals(sha256.substring(0, 8), lines.get(0).hash());
        assertEquals("Wide Hash", lines.get(0).author());
    }

    /** G12. The output used to be trimmed, which took a blank last line with it. */
    @Test
    void aBlankLastLineIsKept() {
        String porcelain = String.join("\n",
                SHA + " 1 1 2",
                "author A",
                "summary S",
                "\tfirst",
                SHA + " 2 2",
                "\t") + "\n";

        List<BlameLine> lines = NativeGitBackend.parseBlamePorcelain(porcelain);

        assertEquals(2, lines.size());
        assertEquals("", lines.get(1).text());
    }
}
