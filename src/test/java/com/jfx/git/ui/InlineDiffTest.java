package com.jfx.git.ui;

import com.jfx.git.ui.DiffRows.Row;
import com.jfx.git.ui.InlineDiff.Segment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineDiffTest {

    /** The changed text of {@code line}, as the highlight would mark it. */
    private static String highlighted(List<Segment> segments, String line) {
        StringBuilder out = new StringBuilder();
        for (Segment segment : segments) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(line, segment.start(), segment.end());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Comparing one pair of lines
    // ------------------------------------------------------------------

    // The delimiter must be a character Java source does not use, or the table splits
    // itself in the middle of the code it is describing.
    @ParameterizedTest(name = "{0} -> {1} marks [{2}] and [{3}]")
    @CsvSource(delimiter = '|', value = {
            // before             | after                | old mark | new mark
            "int count = 0;       | int total = 0;       | count    | total",
            "log(a);              | log(a, b);           |          | ', b'",
            "log(a, b);           | log(a);              | ', b'    |",
            "return x + 1;        | return x - 1;        | +        | -",
            "'  indented();'      | indented();          | '  '     |",
            "call(alpha, beta);   | call(gamma, beta);   | alpha    | gamma",
    })
    void marksOnlyWhatChanged(String before, String after, String oldMark, String newMark) {
        InlineDiff.Segments segments = InlineDiff.compare(before, after);
        assertNotNull(segments, "expected these lines to be paired");
        assertEquals(oldMark == null ? "" : oldMark, highlighted(segments.oldSegments(), before));
        assertEquals(newMark == null ? "" : newMark, highlighted(segments.newSegments(), after));
    }

    @Test
    void identicalLinesHaveNothingToMark() {
        assertNull(InlineDiff.compare("same", "same"));
    }

    @Test
    void aRenamedIdentifierMarksTheIdentifierNotTheLine() {
        InlineDiff.Segments segments = InlineDiff.compare(
                "public void handleRequest(Request request) {",
                "public void handleMessage(Request request) {");

        assertNotNull(segments);
        assertEquals("handleRequest", highlighted(segments.oldSegments(),
                "public void handleRequest(Request request) {"));
        assertEquals("handleMessage", highlighted(segments.newSegments(),
                "public void handleMessage(Request request) {"));
    }

    @Test
    void aWholeRewriteDeclinesToPair() {
        // Nothing in common: marking "the changed part" would mark the entire line, which
        // tells the reader less than the plain line colouring already does.
        assertNull(InlineDiff.compare(
                "final var result = compute(input);",
                "throw new UnsupportedOperationException();"));
    }

    @Test
    void aLeadingWhitespaceChangeMarksOnlyTheIndent() {
        InlineDiff.Segments segments = InlineDiff.compare("\tvalue();", "        value();");
        assertNotNull(segments);
        assertEquals("\t", highlighted(segments.oldSegments(), "\tvalue();"));
        assertEquals("        ", highlighted(segments.newSegments(), "        value();"));
    }

    @Test
    void veryLongLinesAreSkipped() {
        String before = "x".repeat(3000);
        assertNull(InlineDiff.compare(before, before + "y"));
    }

    @Test
    void punctuationIsComparedCharacterByCharacter() {
        InlineDiff.Segments segments = InlineDiff.compare("f(a);", "f(a),");
        assertNotNull(segments);
        assertEquals(";", highlighted(segments.oldSegments(), "f(a);"));
        assertEquals(",", highlighted(segments.newSegments(), "f(a),"));
    }

    // ------------------------------------------------------------------
    // Tokenizing
    // ------------------------------------------------------------------

    @Test
    void tokenizerKeepsWordsWhitespaceAndPunctuationApart() {
        List<InlineDiff.Token> tokens = InlineDiff.tokenize("a_1 = f(x);");
        assertEquals(List.of("a_1", " ", "=", " ", "f", "(", "x", ")", ";"),
                tokens.stream().map(InlineDiff.Token::text).toList());
    }

    @Test
    void tokenOffsetsCoverTheLineExactly() {
        String line = "  return value; ";
        List<InlineDiff.Token> tokens = InlineDiff.tokenize(line);
        assertEquals(0, tokens.get(0).start());
        assertEquals(line.length(), tokens.get(tokens.size() - 1).end());
        for (int i = 1; i < tokens.size(); i++) {
            assertEquals(tokens.get(i - 1).end(), tokens.get(i).start(),
                    "tokens must not leave a gap");
        }
    }

    @Test
    void tokenizingAnEmptyLineYieldsNoTokens() {
        assertTrue(InlineDiff.tokenize("").isEmpty());
    }

    // ------------------------------------------------------------------
    // Pairing across a hunk
    // ------------------------------------------------------------------

    private static List<Row> rows(String diff) {
        return DiffRows.unified(DiffParser.parse(diff));
    }

    @Test
    void pairsADeletionRunWithTheAdditionRunAfterIt() {
        List<Row> rows = rows("""
                @@ -1,2 +1,2 @@
                -int a = 1;
                -int b = 2;
                +int a = 9;
                +int b = 8;
                """);
        Map<Integer, List<Segment>> segments = InlineDiff.forUnified(rows);

        // Row 0 is the hunk header, 1-2 the deletions, 3-4 the additions.
        assertEquals("1", highlighted(segments.get(1), rows.get(1).text()));
        assertEquals("9", highlighted(segments.get(3), rows.get(3).text()));
        assertEquals("2", highlighted(segments.get(2), rows.get(2).text()));
        assertEquals("8", highlighted(segments.get(4), rows.get(4).text()));
    }

    @Test
    void apureInsertionIsLeftWholeLineColoured() {
        Map<Integer, List<Segment>> segments = InlineDiff.forUnified(rows("""
                @@ -1,1 +1,2 @@
                 kept
                +brand new line
                """));
        assertTrue(segments.isEmpty(), "an added line has no counterpart to compare against");
    }

    @Test
    void aPureDeletionIsLeftWholeLineColoured() {
        Map<Integer, List<Segment>> segments = InlineDiff.forUnified(rows("""
                @@ -1,2 +1,1 @@
                 kept
                -gone entirely
                """));
        assertTrue(segments.isEmpty());
    }

    @Test
    void anUnevenChangePairsAsFarAsItCan() {
        List<Row> rows = rows("""
                @@ -1,1 +1,2 @@
                -value = 1;
                +value = 2;
                +extra();
                """);
        Map<Integer, List<Segment>> segments = InlineDiff.forUnified(rows);

        assertTrue(segments.containsKey(1), "the one removed line pairs with the first added one");
        assertTrue(segments.containsKey(2));
        assertFalse(segments.containsKey(3), "the surplus added line has nothing to pair with");
    }

    @Test
    void contextLinesAreNeverMarked() {
        List<Row> rows = rows("""
                @@ -1,3 +1,3 @@
                 context before
                -changed = 1;
                +changed = 2;
                 context after
                """);
        Map<Integer, List<Segment>> segments = InlineDiff.forUnified(rows);
        assertFalse(segments.containsKey(1));
        assertFalse(segments.containsKey(4));
    }

    @Test
    void separateChangeBlocksAreEachPaired() {
        List<Row> rows = rows("""
                @@ -1,4 +1,4 @@
                -first = 1;
                +first = 2;
                 between
                -second = 3;
                +second = 4;
                """);
        Map<Integer, List<Segment>> segments = InlineDiff.forUnified(rows);
        assertTrue(segments.containsKey(1));
        assertTrue(segments.containsKey(2));
        assertTrue(segments.containsKey(4));
        assertTrue(segments.containsKey(5));
    }

    // ------------------------------------------------------------------
    // Split view
    // ------------------------------------------------------------------

    @Test
    void splitColumnsAreComparedRowAgainstRow() {
        DiffRows.Split split = DiffRows.split(DiffParser.parse("""
                @@ -1,1 +1,1 @@
                -total = old;
                +total = new;
                """));
        Map<Integer, List<Segment>>[] segments = InlineDiff.forSplit(split.left(), split.right());

        assertEquals("old", highlighted(segments[0].get(1), split.left().get(1).text()));
        assertEquals("new", highlighted(segments[1].get(1), split.right().get(1).text()));
    }

    @Test
    void splitPaddingRowsAreSkipped() {
        DiffRows.Split split = DiffRows.split(DiffParser.parse("""
                @@ -1,1 +1,2 @@
                 kept
                +added
                """));
        Map<Integer, List<Segment>>[] segments = InlineDiff.forSplit(split.left(), split.right());
        assertTrue(segments[0].isEmpty());
        assertTrue(segments[1].isEmpty());
    }
}
