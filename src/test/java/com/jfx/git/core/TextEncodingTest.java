package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEncodingTest {

    // ------------------------------------------------------------------
    // Whole-file decoding
    // ------------------------------------------------------------------

    @Test
    void decodesUtf8() {
        byte[] bytes = "héllo wörld".getBytes(StandardCharsets.UTF_8);
        assertEquals("héllo wörld", TextEncoding.UTF_8.decode(bytes));
    }

    @Test
    void stripsUtf8ByteOrderMark() {
        byte[] bytes = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertEquals("hi", TextEncoding.UTF_8_BOM.decode(bytes));
        // A BOM is invisible in the file, so plain UTF-8 must not leave it in the text.
        assertEquals("hi", TextEncoding.UTF_8.decode(bytes));
    }

    @Test
    void decodesUtf16BothWays() {
        byte[] le = "hi".getBytes(StandardCharsets.UTF_16LE);
        byte[] be = "hi".getBytes(StandardCharsets.UTF_16BE);
        assertEquals("hi", TextEncoding.UTF_16LE.decode(le));
        assertEquals("hi", TextEncoding.UTF_16BE.decode(be));
        // Reading one as the other is exactly the mis-decode the dropdown exists to fix.
        assertFalse("hi".equals(TextEncoding.UTF_16BE.decode(le)));
    }

    @Test
    void decodesEmptyAsEmpty() {
        assertEquals("", TextEncoding.UTF_8.decode(new byte[0]));
        assertEquals("", TextEncoding.GUESS.decode(null));
    }

    // ------------------------------------------------------------------
    // Guessing
    // ------------------------------------------------------------------

    @Test
    void guessesUtf8ForValidUtf8() {
        assertEquals(StandardCharsets.UTF_8,
                TextEncoding.guess("plain ascii".getBytes(StandardCharsets.UTF_8)));
        assertEquals(StandardCharsets.UTF_8,
                TextEncoding.guess("naïve".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void guessesFromByteOrderMark() {
        assertEquals(StandardCharsets.UTF_16LE,
                TextEncoding.guess(new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0}));
        assertEquals(StandardCharsets.UTF_16BE,
                TextEncoding.guess(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'h'}));
    }

    @Test
    void guessFallsBackToSingleByteForInvalidUtf8() {
        // 0xE9 alone is "é" in Windows-1252 but an incomplete UTF-8 sequence.
        byte[] latin1 = new byte[]{'c', 'a', 'f', (byte) 0xE9};
        assertFalse(StandardCharsets.UTF_8.equals(TextEncoding.guess(latin1)));
        // The point of the fallback: every byte maps to something readable.
        assertFalse(TextEncoding.GUESS.decode(latin1).contains("�"));
    }

    @Test
    void guessRoundTripsThroughDecode() {
        byte[] bytes = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertEquals("hi", TextEncoding.GUESS.decode(bytes));
    }

    // ------------------------------------------------------------------
    // Diff decoding
    // ------------------------------------------------------------------

    @Test
    void diffMarkersStayAsciiWhileContentIsDecoded() {
        // "café" written as Windows-1252, spliced into an ASCII diff by DiffFormatter.
        byte[] diff = concat(
                "@@ -1,2 +1,2 @@\n-old\n+caf".getBytes(StandardCharsets.US_ASCII),
                new byte[]{(byte) 0xE9},
                "\n".getBytes(StandardCharsets.US_ASCII));

        String decoded = TextEncoding.GUESS.decodeDiff(diff);
        assertTrue(decoded.startsWith("@@ -1,2 +1,2 @@\n"), decoded);
        assertTrue(decoded.contains("\n-old\n"), decoded);
        assertTrue(decoded.contains("+café"), decoded);
    }

    @Test
    void diffKeepsBlankLinesAndTrailingNewline() {
        String diff = "@@ -1,3 +1,3 @@\n a\n\n b\n";
        assertEquals(diff, TextEncoding.UTF_8.decodeDiff(diff.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void diffStripsCarriageReturns() {
        byte[] diff = "@@ -1 +1 @@\r\n+line\r\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("@@ -1 +1 @@\n+line\n", TextEncoding.UTF_8.decodeDiff(diff));
    }

    @Test
    void wideEncodingsDecodeDiffTextAsUtf8() {
        // JGit reports UTF-16 blobs as binary, so what reaches the viewer is this ASCII
        // notice - decoding it as UTF-16 would turn it into noise.
        byte[] notice = "Binary files a/x and b/x differ\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("Binary files a/x and b/x differ\n", TextEncoding.UTF_16LE.decodeDiff(notice));
    }

    // ------------------------------------------------------------------
    // Binary
    // ------------------------------------------------------------------

    @Test
    void hexDumpShowsOffsetHexAndAscii() {
        String dump = TextEncoding.BINARY.decode("Hi\n".getBytes(StandardCharsets.US_ASCII));
        assertTrue(dump.startsWith("00000000  48 69 0a"), dump);
        assertTrue(dump.endsWith("|Hi.|"), dump);
    }

    @Test
    void hexDumpWrapsEverySixteenBytes() {
        byte[] bytes = new byte[33];
        String[] rows = TextEncoding.BINARY.decode(bytes).split("\n");
        assertEquals(3, rows.length);
        assertTrue(rows[1].startsWith("00000010"), rows[1]);
        assertTrue(rows[2].startsWith("00000020"), rows[2]);
    }

    @Test
    void binaryIgnoresDiffStructure() {
        String dump = TextEncoding.BINARY.decodeDiff("+a\n".getBytes(StandardCharsets.US_ASCII));
        assertTrue(dump.startsWith("00000000  2b 61 0a"), dump);
    }

    // ------------------------------------------------------------------
    // Dropdown contents
    // ------------------------------------------------------------------

    @Test
    void selectableListsGuessAndBinaryFirst() {
        var selectable = TextEncoding.selectable();
        assertEquals(TextEncoding.GUESS, selectable.get(0));
        assertEquals(TextEncoding.BINARY, selectable.get(1));
        assertTrue(selectable.contains(TextEncoding.UTF_8));
        selectable.forEach(e -> assertTrue(e.isAvailable(), e.label()));
    }

    // ------------------------------------------------------------------
    // Round-tripping for mutation paths
    // ------------------------------------------------------------------

    /**
     * B10. Hunk staging, hunk reverting and conflict resolution all rewrite the whole
     * file from text they decoded themselves, so an assumed UTF-8 turns every untouched
     * line of a single-byte-encoded file into replacement characters.
     */
    @Test
    void editingANonUtf8FileRoundTripsItsBytesExactly() {
        byte[] original = {
                'c', 'a', 'f', (byte) 0xE9, '\n',
                'p', 'l', 'a', 'i', 'n', '\n'};

        String text = TextEncoding.decodeForEdit(original);

        assertNotNull(text, "windows-1252 maps every byte, so this must round-trip");
        assertArrayEquals(original, TextEncoding.encodeForEdit(text, original),
                "an unchanged edit must reproduce the file byte for byte");

        byte[] edited = TextEncoding.encodeForEdit(text.replace("plain", "edited"), original);
        byte[] expected = {
                'c', 'a', 'f', (byte) 0xE9, '\n',
                'e', 'd', 'i', 't', 'e', 'd', '\n'};
        assertArrayEquals(expected, edited,
                "the untouched line keeps its own encoding, not UTF-8 replacement characters");
    }

    /** A byte-order mark is part of the file, and an edit must not silently drop it. */
    @Test
    void editingKeepsAByteOrderMark() {
        byte[] original = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "hello\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String text = TextEncoding.decodeForEdit(original);

        assertEquals("hello\n", text, "the mark is not content");
        assertArrayEquals(original, TextEncoding.encodeForEdit(text, original),
                "but it has to be written back");
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // N10f. Editing as the encoding the user actually chose
    // ------------------------------------------------------------------

    /**
     * The viewer has an encoding dropdown, and the hunk paths used to re-guess anyway - so
     * the one case the dropdown exists for was the one it could not reach.
     */
    @Test
    void anExplicitEncodingIsUsedInsteadOfGuessing() {
        byte[] utf16 = "one\ntwo\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);

        String text = TextEncoding.decodeForEdit(utf16, TextEncoding.UTF_16LE);

        assertEquals("one\ntwo\n", text);
        assertArrayEquals(utf16, TextEncoding.encodeForEdit(text, utf16, TextEncoding.UTF_16LE),
                "an untouched round trip must reproduce the file byte for byte");
    }

    @Test
    void anEditKeepsTheChosenEncoding() {
        byte[] utf16 = "one\ntwo\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        String text = TextEncoding.decodeForEdit(utf16, TextEncoding.UTF_16LE);

        byte[] edited = TextEncoding.encodeForEdit(text.replace("two", "TWO"), utf16,
                TextEncoding.UTF_16LE);

        assertArrayEquals("one\nTWO\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE), edited);
    }

    /**
     * Honouring an explicit choice is no more dangerous than guessing, because the
     * round-trip guard does not care where the charset came from: a wrong choice cannot
     * reproduce the original bytes, so the edit is refused rather than corrupting the file.
     */
    @Test
    void aWrongExplicitEncodingRefusesTheEditRatherThanCorruptingIt() {
        // A lone 0xFF is not valid UTF-8, so decoding it as UTF-8 cannot round-trip.
        byte[] notUtf8 = {'a', (byte) 0xFF, 'b'};

        assertNull(TextEncoding.decodeForEdit(notUtf8, TextEncoding.UTF_8),
                "bytes that do not survive the trip must refuse, whoever chose the charset");
    }

    /** Passing no encoding is the old behaviour, unchanged. */
    @Test
    void noChoiceStillGuesses() {
        byte[] utf8 = "plain\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(TextEncoding.decodeForEdit(utf8), TextEncoding.decodeForEdit(utf8, null));
        assertEquals(TextEncoding.decodeForEdit(utf8),
                TextEncoding.decodeForEdit(utf8, TextEncoding.GUESS));
    }
}
