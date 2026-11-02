package com.jfx.git.core;

import java.nio.charset.Charset;
import java.util.List;

/**
 * One hunk's worth of change, with everything needed to apply it to a file.
 *
 * <h2>Why this is one thing</h2>
 * Staging a hunk, unstaging one and reverting one are the same operation on text: find
 * the block the diff said was there, put a different block in its place. What that needs
 * accumulated as loose parameters until the backend methods were about to take seven
 * apiece - two of them a nullable {@code Boolean} and an enum, sitting at the end of the
 * list where a caller can transpose them and nothing complains.
 *
 * <p>They also belong together for a better reason than arity: each is a piece of state
 * the diff pipeline used to normalize away, and each one lost meant the wrong bytes were
 * written with no error. The block is what the diff showed; {@code endsWithNewline} is
 * what its no-newline marker said about the file's last byte; {@code encoding} is what
 * the user chose to read the file as. A patch is all of that or it is a guess.
 *
 * <h2>Line endings travel inside the blocks</h2>
 * A block line that ends with {@code '\r'} is one the diff showed with a CRLF ending; one
 * without was LF. That is the diff's own record of the bytes, and {@link HunkPatcher}
 * writes it back verbatim whenever the file agrees with the diff about the lines around
 * it - which is what lets an EOL-only change be staged at all, and a line added with LF
 * to a CRLF file be staged as LF. See {@link HunkPatcher#apply} for when it does not.
 *
 * @param hintLine        1-based position {@code search} occupied when the diff was made;
 *                        when {@code search} is empty, the line the insertion follows
 *                        (0 for the top of the file), which is how git numbers an empty
 *                        range
 * @param search          the block to find
 * @param replacement     what to put in its place
 * @param endsWithNewline whether the result should end with a newline, or {@code null} to
 *                        keep whatever the file being patched does - see
 *                        {@link HunkPatcher#apply}
 * @param encoding        how to read and write the file; {@link TextEncoding#GUESS} to
 *                        work it out from the bytes
 * @param lineCharset     the charset the blocks were decoded from the diff with, or
 *                        {@code null} when unknown. Only consulted for
 *                        {@link TextEncoding#GUESS}, where the diff and the file are
 *                        guessed separately and can disagree - see
 *                        {@link HunkPatcher#apply(byte[], HunkEdit)}
 * @param createMode      the index mode for a path that has no index entry yet, as the
 *                        diff's {@code new file mode} or {@code deleted file mode} line
 *                        gave it; {@code null} for a regular file. An existing entry's
 *                        own mode always wins
 * @param removesPath     whether an empty result means the path should leave the index
 *                        rather than stay as an empty file: staging all of a deletion, or
 *                        unstaging all of an addition. Ignored when the result is not empty
 */
public record HunkEdit(int hintLine,
                       List<String> search,
                       List<String> replacement,
                       Boolean endsWithNewline,
                       TextEncoding encoding,
                       Charset lineCharset,
                       String createMode,
                       boolean removesPath) {

    public HunkEdit {
        search = List.copyOf(search);
        replacement = List.copyOf(replacement);
        if (encoding == null) encoding = TextEncoding.GUESS;
    }

    /** A patch that leaves the file's trailing newline alone and works the encoding out. */
    public HunkEdit(int hintLine, List<String> search, List<String> replacement) {
        this(hintLine, search, replacement, null, TextEncoding.GUESS);
    }

    /** A patch whose lines' charset is not known; see {@link #lineCharset()}. */
    public HunkEdit(int hintLine, List<String> search, List<String> replacement,
                    Boolean endsWithNewline, TextEncoding encoding) {
        this(hintLine, search, replacement, endsWithNewline, encoding, null, null, false);
    }

    /** The same edit, read and written as {@code encoding} rather than by guessing. */
    public HunkEdit as(TextEncoding encoding) {
        return new HunkEdit(hintLine, search, replacement, endsWithNewline, encoding,
                lineCharset, createMode, removesPath);
    }

    /** The same edit, noting the charset its lines were decoded with. */
    public HunkEdit withLineCharset(Charset charset) {
        return new HunkEdit(hintLine, search, replacement, endsWithNewline, encoding,
                charset, createMode, removesPath);
    }

    /**
     * The same edit, told what the diff's preamble says about the file as a whole.
     *
     * @param createMode  see {@link #createMode()}
     * @param removesPath see {@link #removesPath()}
     */
    public HunkEdit withWholeFile(String createMode, boolean removesPath) {
        return new HunkEdit(hintLine, search, replacement, endsWithNewline, encoding,
                lineCharset, createMode, removesPath);
    }

    /** True when applying this would change nothing. */
    public boolean isNoop() {
        return endsWithNewline == null && search.equals(replacement);
    }

    /** {@link #createMode()} if it is a mode git accepts for a blob, otherwise 100644. */
    public String createModeOrRegular() {
        return createMode != null && createMode.matches("100644|100755|120000")
                ? createMode : "100644";
    }
}
