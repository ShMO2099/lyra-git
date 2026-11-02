package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Counting line terminators, which the editor's footer reports.
 *
 * <p>Pure, so it runs without a toolkit. The cases worth having are the ones where the
 * answer is not the obvious one: a file with no terminator at all, a CR that belongs to a
 * CRLF rather than standing alone, and a document using two conventions at once.
 */
class LineEndingsTest {

    @Test
    void textWithNoTerminatorHasNoConvention() {
        assertEquals(LineEndings.NONE, LineEndings.detect(""));
        assertEquals(LineEndings.NONE, LineEndings.detect(null));
        assertEquals(LineEndings.NONE, LineEndings.detect("one line, no newline"));
    }

    @Test
    void eachConventionOnItsOwnIsRecognised() {
        assertEquals(LineEndings.LF, LineEndings.detect("a\nb\n"));
        assertEquals(LineEndings.CRLF, LineEndings.detect("a\r\nb\r\n"));
        assertEquals(LineEndings.CR, LineEndings.detect("a\rb\r"));
    }

    /** A file whose last line has no terminator is still whatever its other lines are. */
    @Test
    void aMissingFinalTerminatorDoesNotChangeTheVerdict() {
        assertEquals(LineEndings.LF, LineEndings.detect("a\nb"));
        assertEquals(LineEndings.CRLF, LineEndings.detect("a\r\nb"));
    }

    /**
     * The CR in a CRLF must not also count as a lone CR, or every CRLF file reads as
     * mixed - which would make the indicator useless on Windows specifically.
     */
    @Test
    void theCarriageReturnOfACrlfIsNotAlsoALoneCarriageReturn() {
        assertEquals(new LineEndings.Counts(0, 2, 0), LineEndings.count("a\r\nb\r\n"));
        assertEquals(LineEndings.CRLF, LineEndings.detect("a\r\nb\r\n"));
    }

    /** A CR in the middle of a line, with the newline later, is a stray - not a CRLF. */
    @Test
    void aStrayCarriageReturnInsideALineIsCountedAsOne() {
        assertEquals(new LineEndings.Counts(1, 0, 1), LineEndings.count("a\rb\n"));
        assertEquals(LineEndings.MIXED, LineEndings.detect("a\rb\n"));
    }

    @Test
    void twoConventionsInOneDocumentReadAsMixed() {
        assertEquals(LineEndings.MIXED, LineEndings.detect("a\r\nb\n"));
        assertEquals(new LineEndings.Counts(1, 1, 0), LineEndings.count("a\r\nb\n"));
    }

    /**
     * The majority behind a mixed verdict, which is what the footer puts in brackets.
     */
    @Test
    void dominantReportsTheMajorityConvention() {
        assertEquals(LineEndings.CRLF, LineEndings.dominant("a\r\nb\r\nc\n"));
        assertEquals(LineEndings.LF, LineEndings.dominant("a\nb\nc\r\n"));
        assertEquals(LineEndings.NONE, LineEndings.dominant("no terminators here"));
    }

    /**
     * A tie goes to whichever convention the file opens with. The rule is arbitrary -
     * any rule here is - so it is pinned rather than left to whatever the loop happens
     * to do after a refactor.
     */
    @Test
    void aTieGoesToTheConventionTheFileOpensWith() {
        assertEquals(LineEndings.CRLF, LineEndings.dominant("a\r\nb\n"));
        assertEquals(LineEndings.LF, LineEndings.dominant("a\nb\r\n"));
    }

    @Test
    void countsDescribeThemselvesForTheTooltip() {
        assertEquals("1 CRLF, 1 LF", LineEndings.count("a\r\nb\n").describe());
        assertEquals("2 LF", LineEndings.count("a\nb\n").describe());
        assertEquals("no line endings", LineEndings.count("bare").describe());
    }

    @Test
    void labelsAreWhatTheFooterShows() {
        assertEquals("LF", LineEndings.LF.label());
        assertEquals("CRLF", LineEndings.CRLF.label());
        assertEquals("Mixed", LineEndings.MIXED.label());
    }

    /**
     * Text out of a RichTextFX area goes back with the file's own terminators. The merge
     * tool wrote it as it came out, and a CRLF file came back LF on every line.
     */
    @Test
    void bufferTextTakesTheReferenceFilesConvention() {
        assertEquals("a\r\nb\r\n", LineEndings.matchConvention("x\r\ny\r\n", "a\nb\n"));
        assertEquals("a\nb\n", LineEndings.matchConvention("x\ny\n", "a\nb\n"));
        assertEquals("a\r\nb\r\n", LineEndings.matchConvention("x\r\ny\r\nz\n", "a\nb\n"),
                "a mixed file is written in its majority convention");
        assertEquals("a\r\nb", LineEndings.matchConvention("x\r\ny", "a\r\nb"),
                "a CR already present is never doubled");
        assertEquals("a\nb", LineEndings.matchConvention(null, "a\nb"));
    }
}
