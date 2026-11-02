package com.jfx.git.core;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * How the bytes behind a file or a diff are turned into text.
 *
 * <p>Git stores content as bytes and has no notion of a character set, so the same
 * blob is legitimately readable as UTF-8, Windows-1252 or UTF-16. The viewer lets the
 * user say which, the way commercial clients' encoding dropdowns do, instead of assuming
 * UTF-8 and rendering replacement characters when that assumption is wrong.
 */
public enum TextEncoding {

    /** Detect from a byte-order mark, falling back to UTF-8 then Windows-1252. */
    GUESS("Guess Encoding", null),
    /** Do not decode at all: show an offset/hex/ASCII dump. */
    BINARY("Binary", null),
    UTF_8("UTF-8", StandardCharsets.UTF_8),
    UTF_8_BOM("UTF-8 BOM", StandardCharsets.UTF_8),
    UTF_16LE("UTF-16LE", StandardCharsets.UTF_16LE),
    UTF_16BE("UTF-16BE", StandardCharsets.UTF_16BE),
    UTF_32LE("UTF-32LE", charsetOrNull("UTF-32LE")),
    UTF_32BE("UTF-32BE", charsetOrNull("UTF-32BE"));

    /**
     * What {@link #GUESS} falls back to for bytes that are not valid UTF-8.
     *
     * <p>A single-byte set is deliberate: it maps every byte to some character, so the
     * text is at worst mojibake rather than a run of replacement characters, and the
     * user can see enough to pick the right encoding from the dropdown.
     */
    private static final Charset FALLBACK = charsetOrNull("windows-1252") != null
            ? charsetOrNull("windows-1252")
            : StandardCharsets.ISO_8859_1;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String label;
    private final Charset charset;

    TextEncoding(String label, Charset charset) {
        this.label = label;
        this.charset = charset;
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public String label() {
        return label;
    }

    /** The charset this decodes with, or {@code null} for {@link #GUESS} and {@link #BINARY}. */
    public Charset charset() {
        return charset;
    }

    /** True for the encodings this platform could not provide, so the UI can grey them out. */
    public boolean isAvailable() {
        return this == GUESS || this == BINARY || charset != null;
    }

    /**
     * True where a character is more than one byte wide and ASCII bytes are padded
     * with NULs, so a diff's line structure is not byte-oriented.
     */
    public boolean isWide() {
        return this == UTF_16LE || this == UTF_16BE || this == UTF_32LE || this == UTF_32BE;
    }

    @Override
    public String toString() {
        return label;
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /** Decodes whole-file content: File view, blame, conflict stages. */
    public String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (this == BINARY) {
            return hexDump(bytes);
        }
        Charset target = this == GUESS ? guess(bytes) : charset;
        int offset = bomLength(bytes, target);
        return new String(bytes, offset, bytes.length - offset, target);
    }

    /**
     * Decodes a unified diff.
     *
     * <p>{@code DiffFormatter} writes the {@code +}/{@code -}/{@code @@} scaffolding as
     * ASCII bytes and splices the file's own bytes in between, so the stream as a whole
     * is not in any one encoding. Each line is therefore split on the ASCII newline,
     * its one-byte marker taken off, and only the remainder decoded.
     *
     * <p>Wide encodings are decoded as UTF-8 instead: their content contains NUL bytes,
     * which makes JGit classify the blob as binary, so what arrives here is the ASCII
     * "Binary files ... differ" notice rather than the file's own bytes.
     */
    public String decodeDiff(byte[] bytes) {
        return decodeDiff(bytes, diffLineCharset(bytes), false);
    }

    /**
     * The charset {@link #decodeDiff} decodes a diff's line content with, or {@code null}
     * for {@link #BINARY}, which does not decode at all.
     *
     * <p>Exposed because a hunk built from the diff is later spliced into a file decoded
     * separately. For {@link #GUESS} the two guesses are made from different bytes - the
     * diff's and the file's - and can disagree: an ASCII-only index blob guesses UTF-8
     * while a diff adding one Windows-1252 {@code é} guesses Windows-1252. Whoever patches
     * the file needs to know which charset the lines it was handed are in, or it encodes
     * them into the file in the wrong one.
     */
    public Charset diffLineCharset(byte[] bytes) {
        if (this == BINARY) {
            return null;
        }
        return isWide() ? StandardCharsets.UTF_8
                : this == GUESS ? guess(bytes)
                : charset;
    }

    /**
     * Decodes a unified diff with an already-chosen line charset.
     *
     * @param lineCharset what {@link #diffLineCharset} chose for these bytes; passed in so
     *                    a caller that needs both does not pay for the guess twice
     * @param keepCr      whether a line's CR before its LF stays in the text. The viewer
     *                    wants it gone - a stray CR is not something to draw - but hunk
     *                    staging needs it: a CR is content, and the parser records it per
     *                    line so a CRLF line, an LF line and a change between the two can
     *                    each be written back exactly as the diff showed them.
     */
    public String decodeDiff(byte[] bytes, Charset lineCharset, boolean keepCr) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        if (this == BINARY || lineCharset == null) {
            return hexDump(bytes);
        }

        StringBuilder out = new StringBuilder(bytes.length);
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i != bytes.length && bytes[i] != '\n') {
                continue;
            }
            int end = i;
            boolean cr = end > start && bytes[end - 1] == '\r';
            // Keep CRLF diffs readable rather than leaving a stray CR in the text.
            if (cr) {
                end--;
            }
            if (end > start) {
                // The marker byte is ASCII regardless of the file's own encoding.
                out.append((char) (bytes[start] & 0xFF));
                if (end > start + 1) {
                    out.append(new String(bytes, start + 1, end - start - 1, lineCharset));
                }
            }
            if (cr && keepCr) {
                out.append('\r');
            }
            if (i != bytes.length) {
                out.append('\n');
            }
            start = i + 1;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /**
     * Resolves {@link #GUESS} for {@code bytes}: a byte-order mark decides outright,
     * otherwise UTF-8 if the bytes are valid UTF-8 and {@link #FALLBACK} if they are not.
     */
    public static Charset guess(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }
        if (startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)) return orUtf8(UTF_32BE.charset);
        if (startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00)) return orUtf8(UTF_32LE.charset);
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return StandardCharsets.UTF_8;
        if (startsWith(bytes, 0xFE, 0xFF)) return StandardCharsets.UTF_16BE;
        if (startsWith(bytes, 0xFF, 0xFE)) return StandardCharsets.UTF_16LE;
        return isValidUtf8(bytes) ? StandardCharsets.UTF_8 : FALLBACK;
    }

    private static Charset orUtf8(Charset charset) {
        return charset == null ? StandardCharsets.UTF_8 : charset;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Length of the byte-order mark {@code charset} would write, or 0 if absent. */
    private static int bomLength(byte[] bytes, Charset charset) {
        if (charset == null) {
            return 0;
        }
        String name = charset.name();
        if (name.startsWith("UTF-8") && startsWith(bytes, 0xEF, 0xBB, 0xBF)) return 3;
        if (name.equals("UTF-32LE") && startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00)) return 4;
        if (name.equals("UTF-32BE") && startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)) return 4;
        if (name.equals("UTF-16LE") && startsWith(bytes, 0xFF, 0xFE)) return 2;
        if (name.equals("UTF-16BE") && startsWith(bytes, 0xFE, 0xFF)) return 2;
        return 0;
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * Turns edited text back into bytes, the exact inverse of {@link #decode(byte[])}.
     *
     * <p>{@code original} is the bytes the text was decoded from. It settles the two
     * things the text itself cannot: which charset {@link #GUESS} meant, and whether
     * the file carried a byte-order mark that has to be put back. Saving a file must
     * not quietly re-encode it or drop its BOM - that shows up as a whole-file diff
     * over a one-line edit.
     *
     * <p>Line endings need no special handling: {@code decode} leaves CR bytes in
     * place, so a CRLF file round-trips as CRLF.
     *
     * @throws IllegalStateException if called on {@link #BINARY}, which is a refusal
     *                               to decode and therefore cannot be inverted
     */
    public byte[] encode(String text, byte[] original) {
        if (this == BINARY) {
            throw new IllegalStateException("Binary content cannot be edited as text");
        }
        byte[] source = original == null ? new byte[0] : original;
        Charset target = this == GUESS ? guess(source) : charset;
        byte[] body = (text == null ? "" : text).getBytes(target);

        byte[] mark = bom(source, target);
        if (mark.length == 0) {
            return body;
        }
        byte[] out = new byte[mark.length + body.length];
        System.arraycopy(mark, 0, out, 0, mark.length);
        System.arraycopy(body, 0, out, mark.length, body.length);
        return out;
    }

    /**
     * Decodes bytes that are about to be patched and written straight back.
     *
     * <p>Staging, unstaging and reverting a hunk, and resolving a conflict, all rewrite
     * the <em>whole</em> file from text they decoded themselves - so the encoding they
     * read with is also the encoding every untouched line is written back in. Assuming
     * UTF-8 there turns a GBK or Shift-JIS file into replacement characters outside the
     * hunk the user actually touched, which is silent corruption of content they never
     * looked at.
     *
     * <p>{@link #GUESS} picks the charset the same way the viewer does, and the result is
     * checked by re-encoding it: byte-identical means the round trip is safe. Anything
     * else returns {@code null}, and the caller refuses rather than writing a file it
     * cannot reproduce.
     *
     * @return the decoded text, or {@code null} when the bytes would not survive the trip
     */
    public static String decodeForEdit(byte[] bytes) {
        return decodeForEdit(bytes, GUESS);
    }

    /**
     * Decodes for editing as {@code as} rather than by guessing.
     *
     * <p>For the encoding the user picked in the viewer's dropdown. Guessing is right
     * when nobody has said otherwise and wrong the moment they have: a file opened
     * explicitly as UTF-16LE was still being re-guessed on the way back down to the
     * index, so the one case the dropdown exists for was the one it did not reach.
     *
     * <p>An explicit choice is no less safe than a guess, because the round-trip check
     * below does not care where the charset came from. Told to read a UTF-8 file as
     * UTF-16LE, the decode produces replacement characters, re-encoding them cannot
     * reproduce the original bytes, and this returns {@code null} - so a wrong choice
     * refuses the edit instead of corrupting the file.
     */
    public static String decodeForEdit(byte[] bytes, TextEncoding as) {
        TextEncoding how = as == null ? GUESS : as;
        byte[] source = bytes == null ? new byte[0] : bytes;
        String text = how.decode(source);
        return java.util.Arrays.equals(how.encode(text, source), source) ? text : null;
    }

    /**
     * Decodes for editing as exactly {@code charset}: no byte-order mark handling, no
     * guessing, and {@code null} unless the bytes survive the round trip.
     *
     * <p>For the case where a diff's lines were decoded in a charset the file's own guess
     * disagrees with (see {@link #diffLineCharset}). Reading the file the way the lines
     * were read is what lets a patch put those lines back as the bytes they came from; a
     * byte-order mark, if any, is carried through as ordinary characters and so survives
     * unchanged.
     */
    public static String decodeExactly(byte[] bytes, Charset charset) {
        byte[] source = bytes == null ? new byte[0] : bytes;
        String text = new String(source, charset);
        return java.util.Arrays.equals(text.getBytes(charset), source) ? text : null;
    }

    /** The inverse of {@link #decodeExactly(byte[], Charset)}. */
    public static byte[] encodeExactly(String text, Charset charset) {
        return (text == null ? "" : text).getBytes(charset);
    }

    /**
     * Re-encodes text that came from {@link #decodeForEdit(byte[])}, in the encoding
     * {@code original} was read with.
     */
    public static byte[] encodeForEdit(String text, byte[] original) {
        return encodeForEdit(text, original, GUESS);
    }

    /**
     * Re-encodes as {@code as}, which must be the encoding
     * {@link #decodeForEdit(byte[], TextEncoding)} read with - otherwise the untouched
     * parts of the file are rewritten in a charset they were never in.
     */
    public static byte[] encodeForEdit(String text, byte[] original, TextEncoding as) {
        TextEncoding how = as == null ? GUESS : as;
        return how.encode(text, original == null ? new byte[0] : original);
    }

    /**
     * The byte-order mark to write: the one the original carried, or - when the user
     * has explicitly picked "UTF-8 BOM" - one regardless of what the original had.
     */
    private byte[] bom(byte[] original, Charset target) {
        if (this == UTF_8_BOM) {
            return new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        }
        int length = bomLength(original, target);
        byte[] mark = new byte[length];
        System.arraycopy(original, 0, mark, 0, length);
        return mark;
    }

    // ------------------------------------------------------------------
    // Binary rendering
    // ------------------------------------------------------------------

    /** {@code offset  hex bytes  |ascii|}, sixteen bytes to the row. */
    public static String hexDump(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 4);
        for (int row = 0; row < bytes.length; row += 16) {
            out.append(String.format("%08x  ", row));
            StringBuilder ascii = new StringBuilder(16);
            for (int col = 0; col < 16; col++) {
                int index = row + col;
                if (index < bytes.length) {
                    int b = bytes[index] & 0xFF;
                    out.append(HEX[b >> 4]).append(HEX[b & 0x0F]).append(' ');
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    out.append("   ");
                }
                if (col == 7) {
                    out.append(' ');
                }
            }
            out.append(" |").append(ascii).append('|');
            if (row + 16 < bytes.length) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** The values offered in the dropdown, in the order commercial clients list them. */
    public static List<TextEncoding> selectable() {
        List<TextEncoding> available = new ArrayList<>();
        for (TextEncoding encoding : values()) {
            if (encoding.isAvailable()) {
                available.add(encoding);
            }
        }
        return available;
    }
}
