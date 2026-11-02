package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HunkPatcherTest {

    @Test
    void replacesTheBlockAtTheExpectedPosition() {
        String original = "a\nb\nc\n";
        var result = HunkPatcher.apply(original, 2, List.of("b"), List.of("B"));

        assertTrue(result.applied());
        assertEquals("a\nB\nc\n", result.content());
        assertEquals(0, result.offset());
    }

    /** The hint is where the diff said it was; edits above move it. */
    @Test
    void findsTheBlockWhenItHasMovedDown() {
        String original = "inserted\ninserted\na\nb\nc\n";
        var result = HunkPatcher.apply(original, 2, List.of("b"), List.of("B"));

        assertTrue(result.applied());
        assertEquals("inserted\ninserted\na\nB\nc\n", result.content());
        assertEquals(2, result.offset(), "found two lines below the hint");
        assertEquals(" (offset +2 lines)", result.describeOffset());
    }

    @Test
    void findsTheBlockWhenItHasMovedUp() {
        String original = "a\nb\nc\n";
        var result = HunkPatcher.apply(original, 8, List.of("b"), List.of("B"));

        assertTrue(result.applied());
        assertEquals("a\nB\nc\n", result.content());
        assertTrue(result.offset() < 0);
        assertTrue(result.describeOffset().startsWith(" (offset -"));
    }

    /** When a block repeats, the one nearest the hint is the one the user was looking at. */
    @Test
    void prefersTheOccurrenceNearestTheHint() {
        String original = "x\nx\nx\nx\nx\n";
        var result = HunkPatcher.apply(original, 4, List.of("x"), List.of("HIT"));

        assertEquals("x\nx\nx\nHIT\nx\n", result.content());
        assertEquals(0, result.offset());
    }

    @Test
    void reportsFailureWhenTheBlockIsGone() {
        var result = HunkPatcher.apply("a\nb\nc\n", 2, List.of("nowhere"), List.of("x"));
        assertFalse(result.applied());
        assertNull(result.content());
    }

    @Test
    void reportsFailureWhenTheFileIsShorterThanTheBlock() {
        var result = HunkPatcher.apply("a\n", 1, List.of("a", "b", "c"), List.of("x"));
        assertFalse(result.applied());
    }

    // ------------------------------------------------------------------
    // Trailing newline handling
    // ------------------------------------------------------------------

    @Test
    void aFileEndingWithANewlineKeepsIt() {
        var result = HunkPatcher.apply("a\nb\n", 1, List.of("a"), List.of("A"));
        assertEquals("A\nb\n", result.content());
    }

    @Test
    void aFileWithNoFinalNewlineDoesNotGainOne() {
        var result = HunkPatcher.apply("a\nb", 1, List.of("a"), List.of("A"));
        assertEquals("A\nb", result.content(), "a final newline must not be invented");
    }

    /** S3. Zero bytes, not a lone newline - git's empty blob is e69de29, not 8b13789. */
    @Test
    void anEmptyFileStaysEmptyWhenNothingIsAdded() {
        var result = HunkPatcher.apply("", 0, List.of(), List.of());
        assertTrue(result.applied());
        assertEquals("", result.content());
    }

    /** S3. Removing every line empties the file; there is no last line for a newline to follow. */
    @Test
    void removingEveryLineLeavesAnEmptyFileNotANewline() {
        assertEquals("", HunkPatcher.apply("a\nb\n", 1, List.of("a", "b"), List.of()).content());
        assertEquals("", HunkPatcher.apply("a\r\nb\r\n", 1, List.of("a\r", "b\r"), List.of()).content());
        assertEquals("", HunkPatcher.apply("a\nb", 1, List.of("a", "b"), List.of(), false).content());
    }

    /** A file holding one blank line is not an empty file, and must not become one. */
    @Test
    void aSingleBlankLineSurvives() {
        assertEquals("\n", HunkPatcher.apply("x\n", 1, List.of("x"), List.of("")).content());
    }

    // ------------------------------------------------------------------
    // Insertions and deletions
    // ------------------------------------------------------------------

    @Test
    void anEmptyReplacementDeletesTheBlock() {
        var result = HunkPatcher.apply("a\nb\nc\n", 2, List.of("b"), List.of());
        assertEquals("a\nc\n", result.content());
    }

    /**
     * S7. A hunk with no "before" content is a pure insertion, and git numbers an empty
     * range by the line it follows: {@code @@ -1,0 +2 @@} inserts after line 1.
     */
    @Test
    void anEmptySearchInsertsAfterTheHintLine() {
        var result = HunkPatcher.apply("a\nc\n", 1, List.of(), List.of("b"));
        assertTrue(result.applied());
        assertEquals("a\nb\nc\n", result.content());
        assertEquals(0, result.offset());
    }

    /** S7. {@code -0,0} is the top of the file, and the end is {@code -N,0} for N lines. */
    @Test
    void anEmptySearchCanInsertAtEitherEnd() {
        assertEquals("b\na\nc\n", HunkPatcher.apply("a\nc\n", 0, List.of(), List.of("b")).content());
        assertEquals("a\nc\nb\n", HunkPatcher.apply("a\nc\n", 2, List.of(), List.of("b")).content());
    }

    /**
     * With nothing to match there is nothing to search outwards for: an insertion point
     * past the end means the file is not the one the diff was taken from.
     */
    @Test
    void anEmptySearchBeyondTheEndIsRefused() {
        assertFalse(HunkPatcher.apply("a\nc\n", 5, List.of(), List.of("b")).applied());
    }

    /** S7, the revert direction: a -U0 deletion, put back after the line it followed. */
    @Test
    void aPureDeletionIsRestoredAfterTheLineItFollowed() {
        // `@@ -2 +1,0 @@` removed "b" from "a b c"; the new side is empty after line 1.
        var result = HunkPatcher.apply("a\nc\n", 1, List.of(), List.of("b"));
        assertEquals("a\nb\nc\n", result.content());
    }

    @Test
    void aMultiLineBlockIsReplacedWholesale() {
        var result = HunkPatcher.apply("1\n2\n3\n4\n", 2,
                List.of("2", "3"), List.of("two", "three", "extra"));
        assertEquals("1\ntwo\nthree\nextra\n4\n", result.content());
    }

    @Test
    void offsetIsOnlyDescribedWhenItIsNonZero() {
        var exact = HunkPatcher.apply("a\n", 1, List.of("a"), List.of("A"));
        assertEquals("", exact.describeOffset());
    }

    // ------------------------------------------------------------------
    // Line endings (D3)
    // ------------------------------------------------------------------

    /**
     * The blocks come from a diff, which has had its CRs stripped for display. The file
     * has not. Matching them literally is what made every hunk operation on a CRLF file
     * fail.
     */
    @Test
    void aCrlfFileIsMatchedByBlocksThatHaveLostTheirCr() {
        var result = HunkPatcher.apply("one\r\ntwo\r\n", 2, List.of("two"), List.of("TWO"));

        assertTrue(result.applied(), "the CR on the file's side must not defeat the match");
        assertEquals("one\r\nTWO\r\n", result.content(),
                "the replacement takes the ending of the line it replaced");
    }

    @Test
    void untouchedLinesKeepTheirOwnEndings() {
        var result = HunkPatcher.apply("a\r\nb\r\nc\r\n", 2, List.of("b"), List.of("B"));

        assertEquals("a\r\nB\r\nc\r\n", result.content());
    }

    /** An LF file must not acquire CRs from a matcher that tolerates them. */
    @Test
    void anLfFileStaysLf() {
        var result = HunkPatcher.apply("a\nb\n", 1, List.of("a"), List.of("A"));

        assertEquals("A\nb\n", result.content());
    }

    /**
     * A file that mixes endings keeps the mixture: only the patched region is decided
     * here, and it follows the line it replaces rather than a whole-file guess.
     */
    @Test
    void aMixedFileKeepsItsMixture() {
        var result = HunkPatcher.apply("a\nb\r\nc\n", 2, List.of("b"), List.of("B"));

        assertEquals("a\nB\r\nc\n", result.content());
    }

    /** An insertion replaces nothing, so it follows the file's prevailing ending. */
    @Test
    void anInsertionFollowsTheFilesPrevailingEnding() {
        var crlf = HunkPatcher.apply("a\r\nc\r\n", 1, List.of(), List.of("b"));
        assertEquals("a\r\nb\r\nc\r\n", crlf.content());

        var lf = HunkPatcher.apply("a\nc\n", 1, List.of(), List.of("b"));
        assertEquals("a\nb\nc\n", lf.content());
    }

    // ------------------------------------------------------------------
    // S1. Endings carried by the blocks themselves
    // ------------------------------------------------------------------

    /**
     * An LF line added to a CRLF file stays LF: the diff showed it that way, and
     * writing it as CRLF matched neither side, leaving a phantom change forever.
     */
    @Test
    void anLfLineAddedToACrlfFileIsWrittenAsLf() {
        var result = HunkPatcher.apply("a\r\nb\r\n", 1,
                List.of("a\r", "b\r"), List.of("a\r", "new", "b\r"));
        assertEquals("a\r\nnew\nb\r\n", result.content());
    }

    /** A change that is nothing but a line ending is a real change. */
    @Test
    void aCrlfToLfChangeIsApplied() {
        assertEquals("a\nb\r\n", HunkPatcher.apply("a\r\nb\r\n", 1,
                List.of("a\r"), List.of("a")).content());
        assertEquals("a\r\nb\n", HunkPatcher.apply("a\nb\n", 1,
                List.of("a"), List.of("a\r")).content(), "and back");
    }

    /**
     * When the file and the diff disagree about the lines being matched - autocrlf, a
     * block written by hand - the replacement follows the file, not the diff.
     */
    @Test
    void blocksThatDisagreeWithTheFileAreTranslated() {
        // The diff is LF (autocrlf's index side); the working-tree file is CRLF.
        var result = HunkPatcher.apply("a\r\nB\r\nc\r\n", 2, List.of("B"), List.of("b"));
        assertEquals("a\r\nb\r\nc\r\n", result.content());
        // And the other way: CRLF blocks against an LF file.
        var lf = HunkPatcher.apply("a\nB\nc\n", 2, List.of("B\r"), List.of("b\r"));
        assertEquals("a\nb\nc\n", lf.content());
    }

    /** A last line that really ends in a bare CR keeps it when the diff says so. */
    @Test
    void aVerbatimLastLineKeepsItsCr() {
        var result = HunkPatcher.apply("a\nb\n", 2, List.of("b"), List.of("b\r"), false);
        assertEquals("a\nb\r", result.content());
    }

    // ------------------------------------------------------------------
    // S2. One charset decision for both sides
    // ------------------------------------------------------------------

    private static final java.nio.charset.Charset CP1252 = java.nio.charset.Charset.forName("windows-1252");

    /**
     * An ASCII blob guesses as UTF-8; a diff adding a Windows-1252 é guesses as 1252.
     * The added line must reach the blob as the byte {@code e9}, not UTF-8's {@code c3 a9}.
     */
    @Test
    void anAsciiFileTakesTheDiffsCharsetForNonAsciiLines() {
        byte[] ascii = "one\ntwo\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        HunkEdit edit = new HunkEdit(1, List.of("one", "two"), List.of("one", "café", "two"))
                .withLineCharset(CP1252);

        var result = HunkPatcher.apply(ascii, edit);

        assertTrue(result.applied(), () -> "refused: " + result.refusal());
        assertArrayEquals(new byte[]{'o', 'n', 'e', '\n', 'c', 'a', 'f', (byte) 0xE9, '\n',
                't', 'w', 'o', '\n'}, result.content());
    }

    /** Lines that are all ASCII mean the same bytes in any of these charsets. */
    @Test
    void asciiLinesUseTheFilesOwnGuess() {
        byte[] utf8 = "naïve\none\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HunkEdit edit = new HunkEdit(2, List.of("one"), List.of("ONE")).withLineCharset(CP1252);

        var result = HunkPatcher.apply(utf8, edit);

        assertArrayEquals("naïve\nONE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), result.content());
    }

    /** When the file cannot be read the diff's way either, the two genuinely disagree. */
    @Test
    void aFileThatCannotBeReadAsTheDiffWasIsRefused() {
        byte[] cp1252 = {'c', 'a', 'f', (byte) 0xE9, '\n'};
        HunkEdit edit = new HunkEdit(1, List.of("café"), List.of("thé"))
                .withLineCharset(java.nio.charset.StandardCharsets.UTF_8);

        var result = HunkPatcher.apply(cp1252, edit);

        assertFalse(result.applied());
        assertEquals(HunkPatcher.Refusal.CHARSET_MISMATCH, result.refusal());
    }

    // ------------------------------------------------------------------
    // Setting the final newline (D3, second half)
    // ------------------------------------------------------------------

    /**
     * Removing a file's last newline is a real change, and the file being patched cannot
     * report it - only the hunk's marker can. Inheriting from the file staged a blob that
     * still ended with the newline the user had just removed.
     */
    @Test
    void theFinalNewlineCanBeRemoved() {
        var result = HunkPatcher.apply("a\nb\n", 2, List.of("b"), List.of("b"), false);

        assertEquals("a\nb", result.content());
    }

    @Test
    void theFinalNewlineCanBeAdded() {
        var result = HunkPatcher.apply("a\nb", 2, List.of("b"), List.of("b"), true);

        assertEquals("a\nb\n", result.content());
    }

    @Test
    void nullLeavesTheFilesOwnFinalNewlineAlone() {
        assertEquals("a\nB\n", HunkPatcher.apply("a\nb\n", 2, List.of("b"), List.of("B"), null).content());
        assertEquals("a\nB", HunkPatcher.apply("a\nb", 2, List.of("b"), List.of("B"), null).content());
    }

    /** No trailing newline means no line ending at all - a bare CR would be a stray byte. */
    @Test
    void removingTheFinalNewlineOfACrlfFileLeavesNoStrayCr() {
        var result = HunkPatcher.apply("a\r\nb\r\n", 2, List.of("b"), List.of("b"), false);

        assertEquals("a\r\nb", result.content(), "the last line must not keep a dangling CR");
    }
}
