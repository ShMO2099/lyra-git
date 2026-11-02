package com.jfx.git.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CommitTrailers} against what {@code git interpret-trailers --trailer} produces for
 * the same input (checked by hand against git 2.4x; the expected strings are its output with
 * the final newline removed).
 */
class CommitTrailersTest {

    private static final String SOB = "Signed-off-by";
    private static final String CAB = "Co-authored-by";

    @Test
    void aTitleOnlyMessageGetsTheTrailerAfterOneBlankLine() {
        assertEquals("Fix it\n\nSigned-off-by: A <a@x>",
                CommitTrailers.append("Fix it", SOB, "A <a@x>"));
    }

    @Test
    void aBodyThatIsNotATrailerBlockGetsANewParagraph() {
        assertEquals("Fix it\n\nBecause.\n\nSigned-off-by: A <a@x>",
                CommitTrailers.append("Fix it\n\nBecause.\n", SOB, "A <a@x>"));
    }

    @Test
    void anExistingTrailerBlockIsJoinedNotSplit() {
        assertEquals("Fix it\n\nBecause.\n\nCo-authored-by: B <b@x>\nSigned-off-by: A <a@x>",
                CommitTrailers.append("Fix it\n\nBecause.\n\nCo-authored-by: B <b@x>", SOB, "A <a@x>"));
    }

    @Test
    void theTitleIsNeverATrailerBlockEvenWhenItLooksLikeOne() {
        assertEquals("Fixes: 123\n\nSigned-off-by: A <a@x>",
                CommitTrailers.append("Fixes: 123", SOB, "A <a@x>"));
        assertEquals("Fix\nSigned-off-by: B <b@x>\n\nSigned-off-by: A <a@x>",
                CommitTrailers.append("Fix\nSigned-off-by: B <b@x>", SOB, "A <a@x>"),
                "a trailer-looking line inside the title paragraph is still the title");
    }

    @Test
    void anIdenticalTrailerIsNotAddedTwice() {
        String message = "Fix it\n\nSigned-off-by: A <a@x>\nCo-authored-by: B <b@x>";
        assertEquals(message, CommitTrailers.append(message, SOB, "A <a@x>"));
        assertEquals(message, CommitTrailers.append(message + "\n\n", "signed-off-by", "a <A@X>"),
                "git compares token and value ignoring case");
    }

    @Test
    void aDifferentValueForTheSameTokenIsAdded() {
        assertEquals("Fix\n\nCo-authored-by: B <b@x>\nCo-authored-by: C <c@x>",
                CommitTrailers.append("Fix\n\nCo-authored-by: B <b@x>", CAB, "C <c@x>"));
    }

    @Test
    void aParagraphMixingProseAndAGitTrailerCountsWhenAQuarterAreTrailers() {
        // Three lines of prose and one Signed-off-by: git's 25% rule makes it a block.
        String mixed = "Fix\n\nline one\nline two\nline three\nSigned-off-by: B <b@x>";
        assertEquals(mixed + "\nSigned-off-by: A <a@x>", CommitTrailers.append(mixed, SOB, "A <a@x>"));

        // Without a git-generated trailer the same shape is just prose.
        String prose = "Fix\n\nline one\nline two\nline three\nReviewed-by: B <b@x>";
        assertEquals(prose + "\n\nSigned-off-by: A <a@x>", CommitTrailers.append(prose, SOB, "A <a@x>"));

        // And below a quarter it is prose even with one.
        String mostlyProse = "Fix\n\none\ntwo\nthree\nfour\nSigned-off-by: B <b@x>";
        assertEquals(mostlyProse + "\n\nSigned-off-by: A <a@x>",
                CommitTrailers.append(mostlyProse, SOB, "A <a@x>"));
    }

    @Test
    void indentedContinuationLinesBelongToTheTrailerAboveThem() {
        String folded = "Fix\n\nReviewed-by: Someone with a very\n  long name <s@x>";
        assertEquals(folded + "\nSigned-off-by: A <a@x>", CommitTrailers.append(folded, SOB, "A <a@x>"));
        assertEquals(List.of(new CommitTrailers.Trailer("Reviewed-by", "Someone with a very long name <s@x>")),
                CommitTrailers.trailers(folded));
    }

    @Test
    void cherryPickedFromIsATrailerGitWrote() {
        String picked = "Fix\n\n(cherry picked from commit 0123456789abcdef)";
        assertEquals(picked + "\nSigned-off-by: A <a@x>", CommitTrailers.append(picked, SOB, "A <a@x>"));
    }

    @Test
    void trailingBlankLinesAndCarriageReturnsDoNotLeaveAGap() {
        assertEquals("Fix\n\nBody\n\nSigned-off-by: A <a@x>",
                CommitTrailers.append("Fix\r\n\r\nBody\r\n\r\n\r\n", SOB, "A <a@x>"));
    }

    @Test
    void anEmptyMessageIsJustTheTrailer() {
        assertEquals("Signed-off-by: A <a@x>", CommitTrailers.append("", SOB, "A <a@x>"));
        assertEquals("Signed-off-by: A <a@x>", CommitTrailers.append(null, SOB, "A <a@x>"));
    }

    // ------------------------------------------------------------------
    // The description field: a body whose title lives elsewhere
    // ------------------------------------------------------------------

    @Test
    void anEmptyDescriptionBecomesTheTrailerBlock() {
        assertEquals("Co-authored-by: B <b@x>", CommitTrailers.appendToBody("", CAB, "B <b@x>"));
    }

    @Test
    void aDescriptionThatIsOnlyTrailersIsJoined() {
        assertEquals("Co-authored-by: B <b@x>\nCo-authored-by: C <c@x>",
                CommitTrailers.appendToBody("Co-authored-by: B <b@x>\n", CAB, "C <c@x>"));
    }

    @Test
    void aProseDescriptionGetsTheTrailerAsItsOwnParagraph() {
        assertEquals("Because.\n\nCo-authored-by: B <b@x>",
                CommitTrailers.appendToBody("Because.", CAB, "B <b@x>"));
        assertEquals("Because.\n\nCo-authored-by: B <b@x>",
                CommitTrailers.appendToBody("Because.\n\nCo-authored-by: B <b@x>", CAB, "B <b@x>"),
                "the same co-author twice is one trailer");
    }

    @Test
    void trailersReadsTheBlockAndNothingElse() {
        assertEquals(List.of(new CommitTrailers.Trailer("Signed-off-by", "A <a@x>"),
                        new CommitTrailers.Trailer("Co-authored-by", "B <b@x>")),
                CommitTrailers.trailers("Fix\n\nBody: not a block\nbecause prose\n\n"
                        + "Signed-off-by: A <a@x>\nCo-authored-by: B <b@x>"));
        assertTrue(CommitTrailers.trailers("Fixes: 1").isEmpty(), "a title is not a trailer block");
        assertTrue(CommitTrailers.trailers("Fix\n\nJust prose.").isEmpty());
    }
}
