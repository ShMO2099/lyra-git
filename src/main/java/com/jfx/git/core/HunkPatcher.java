package com.jfx.git.core;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Splices one hunk's worth of lines into a file's text.
 *
 * <p>Staging, unstaging and reverting a single hunk are all the same operation on text:
 * find the block the diff said was there, put a different block in its place. Only
 * <em>where the text comes from and goes</em> differs — the working tree for a revert,
 * the index blob for a partial stage — and that part is the backend's business. Keeping
 * the splice here means the backends cannot drift on the things that are actually
 * delicate: locating a block that may have moved since the diff was taken, and turning
 * the file's bytes into text and back.
 *
 * <p>Three things about that text are easy to normalize away and are deliberately not: a
 * line's CR, the file's final newline, and the charset the diff's lines were read in.
 * All three are content, all are invisible, and losing any of them silently stages
 * something other than what the diff showed - see {@link #apply(String, int, List, List,
 * Boolean)} and {@link #apply(byte[], HunkEdit)}.
 */
public final class HunkPatcher {

    private HunkPatcher() {}

    /**
     * The outcome of a splice.
     *
     * @param content the whole file afterwards, or {@code null} when the hunk did not apply
     * @param offset  how far from the expected position the block was found; non-zero means
     *                the file changed around the hunk but it still applied cleanly
     */
    public record Result(String content, int offset) {
        public boolean applied() {
            return content != null;
        }

        /** "" or " (offset +3 lines)" — the tail of a status message. */
        public String describeOffset() {
            return describe(offset);
        }
    }

    /** Why {@link #apply(byte[], HunkEdit)} produced nothing. */
    public enum Refusal {
        /** It did not refuse. */
        NONE,
        /** The file's bytes do not survive being decoded and re-encoded. */
        ENCODING,
        /**
         * The diff's lines were read in one charset and the file guesses as another, and
         * the file cannot be read the diff's way either - so the lines cannot be put back
         * as the bytes they came from.
         */
        CHARSET_MISMATCH,
        /** The block is no longer where, or no longer what, the diff said. */
        NOT_FOUND
    }

    /**
     * The outcome of patching a file's bytes.
     *
     * @param content the whole file afterwards, or {@code null} when refused
     * @param offset  see {@link Result#offset()}
     * @param refusal why {@code content} is {@code null}; {@link Refusal#NONE} otherwise
     */
    public record BytesResult(byte[] content, int offset, Refusal refusal) {
        public boolean applied() {
            return content != null;
        }

        /** "" or " (offset +3 lines)" — the tail of a status message. */
        public String describeOffset() {
            return describe(offset);
        }
    }

    /**
     * The status message for a refused patch, worded the same on every engine.
     *
     * @param index true when the file being patched is the staged copy, false for the
     *              working tree
     */
    public static String explain(Refusal refusal, String path, boolean index) {
        return switch (refusal) {
            case ENCODING -> (index ? "Cannot stage part of " : "Cannot edit ") + path
                    + " safely - its encoding does not survive a read/write round trip";
            case CHARSET_MISMATCH -> "Cannot " + (index ? "stage part of " : "edit ") + path
                    + " safely - the diff and the file disagree about its encoding;"
                    + " choose one from the encoding menu and try again";
            case NOT_FOUND -> index
                    ? "This hunk no longer applies to the staged copy of " + path
                            + " - stage or unstage the whole file instead"
                    : "This hunk no longer applies to " + path
                            + " - the surrounding lines have changed";
            case NONE -> "";
        };
    }

    private static String describe(int offset) {
        return offset == 0 ? "" : " (offset " + (offset > 0 ? "+" : "") + offset + " lines)";
    }

    private static final Result NOT_FOUND = new Result(null, 0);

    /**
     * Applies {@code edit} to a file's bytes: decode, splice, encode.
     *
     * <p>Every backend's partial stage, unstage and revert goes through here, so the
     * decision of which charset to read the file in is made once.
     *
     * <h2>Deciding the charset once for both sides</h2>
     * With {@link TextEncoding#GUESS} the diff and the file are guessed separately, from
     * different bytes, and the guesses can disagree. An index blob that happens to be pure
     * ASCII guesses as UTF-8; a diff that adds one Windows-1252 {@code é} to it guesses as
     * Windows-1252. Decoding each its own way and splicing the diff's {@code "café"} into
     * the UTF-8 reading wrote {@code c3 a9} into what is really a Latin-1 file.
     *
     * <p>So when the edit knows the charset its lines came from ({@link
     * HunkEdit#lineCharset()}) and that differs from the file's own guess:
     * <ul>
     *   <li>lines that are all ASCII mean the same bytes either way, so the file's own
     *       guess stands;</li>
     *   <li>otherwise the file is read the way the lines were, provided its bytes survive
     *       that - which puts the new lines back as exactly the bytes the diff carried;</li>
     *   <li>and when they do not, the edit is refused: the two sides genuinely disagree
     *       about what the bytes mean, and either choice would corrupt one of them.</li>
     * </ul>
     * An encoding the user picked explicitly is the one both the diff and the file were
     * decoded with already, so it is used as it stands.
     */
    public static BytesResult apply(byte[] original, HunkEdit edit) {
        byte[] source = original == null ? new byte[0] : original;

        Charset exact = null; // non-null: read and write as exactly this charset
        if (edit.encoding() == TextEncoding.GUESS && edit.lineCharset() != null) {
            Charset own = TextEncoding.guess(source);
            if (!own.equals(edit.lineCharset()) && !allAscii(edit)) {
                if (TextEncoding.decodeExactly(source, edit.lineCharset()) == null) {
                    return new BytesResult(null, 0, Refusal.CHARSET_MISMATCH);
                }
                exact = edit.lineCharset();
            }
        }

        String text = exact != null
                ? TextEncoding.decodeExactly(source, exact)
                : TextEncoding.decodeForEdit(source, edit.encoding());
        if (text == null) {
            return new BytesResult(null, 0, Refusal.ENCODING);
        }
        Result patched = apply(text, edit.hintLine(), edit.search(), edit.replacement(),
                edit.endsWithNewline());
        if (!patched.applied()) {
            return new BytesResult(null, 0, Refusal.NOT_FOUND);
        }
        byte[] bytes = exact != null
                ? TextEncoding.encodeExactly(patched.content(), exact)
                : TextEncoding.encodeForEdit(patched.content(), source, edit.encoding());
        return new BytesResult(bytes, patched.offset(), Refusal.NONE);
    }

    private static boolean allAscii(HunkEdit edit) {
        return edit.search().stream().allMatch(HunkPatcher::isAscii)
                && edit.replacement().stream().allMatch(HunkPatcher::isAscii);
    }

    private static boolean isAscii(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replaces {@code search} with {@code replacement} in {@code original}, keeping the
     * file's own trailing newline.
     */
    public static Result apply(String original, int hintLine,
                               List<String> search, List<String> replacement) {
        return apply(original, hintLine, search, replacement, null);
    }

    /**
     * Replaces {@code search} with {@code replacement} in {@code original}.
     *
     * <h2>Line endings</h2>
     * A block line ending in {@code '\r'} is one the diff showed with a CRLF ending, and
     * one without was LF - see {@link HunkEdit}. When the file holds the search block
     * exactly, CRs and all, the diff and the file agree about the bytes and the
     * replacement is written <em>verbatim</em>: a line added with LF to a CRLF file stays
     * LF, and a change that is nothing but a line ending (CRLF to LF, or back) is a real
     * change that gets staged or reverted. Normalizing either way used to make the first
     * a phantom change that never went away, and the second impossible.
     *
     * <p>The file and the diff do not always agree, though, and matching ignores a
     * trailing CR on either side so that the patch still lands. Under
     * {@code core.autocrlf=true} the diff and the index blob hold LF while the
     * working-tree file a revert patches holds CRLF; blocks written by hand carry no CR at
     * all. When the match needed that tolerance the diff's endings are not the file's, so
     * the replacement is <em>translated</em> instead: every line takes the ending of the
     * first line it replaces. A file's endings survive a patch unchanged either way,
     * including a file that mixes them.
     *
     * <h2>Empty ranges</h2>
     * An empty {@code search} is a pure insertion, and git numbers an empty range by the
     * line it follows: {@code @@ -2,0 +3 @@} inserts <em>after</em> line 2. So for an empty
     * search {@code hintLine} is that line, not the first line of a block - reading it the
     * other way put every zero-context insertion one line early. With nothing to match,
     * there is nothing to search outwards for either: a hint beyond the end of the file
     * means the file is not the one the diff was taken from, and the insertion is refused.
     * Its endings follow the file's prevailing one when it carries none of its own.
     *
     * <h2>An empty result</h2>
     * Removing every line leaves an empty file - zero bytes, git's {@code e69de29} - not a
     * lone newline. There is no last line for a final newline to follow.
     *
     * @param hintLine        1-based position {@code search} occupied when the diff was
     *                        made, or for an empty {@code search} the line to insert after
     * @param search          the block to find
     * @param replacement     what to put in its place
     * @param endsWithNewline whether the result should end with a newline, or {@code null}
     *                        to keep whatever {@code original} does. Non-null only when the
     *                        hunk carried a {@code \\ No newline at end of file} marker, which
     *                        is the only thing that reports a change to a file's last byte -
     *                        inheriting from {@code original} would stage a blob still
     *                        ending with the newline the user had just removed.
     */
    public static Result apply(String original, int hintLine, List<String> search,
                               List<String> replacement, Boolean endsWithNewline) {
        // Whether the file ends with a newline has to survive the round trip: splitting
        // with -1 leaves a trailing empty element for it, and re-joining would otherwise
        // either lose the final newline or invent one.
        boolean trailingNewline = original.isEmpty() || original.endsWith("\n");
        List<String> lines = new ArrayList<>(List.of(original.split("\n", -1)));
        if (trailingNewline && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        int expected;
        int at;
        boolean verbatim;
        if (search.isEmpty()) {
            expected = Math.max(0, hintLine);
            if (expected > lines.size()) {
                return NOT_FOUND;
            }
            at = expected;
            // Nothing was matched, so there is no evidence either way about whether the
            // diff's endings are the file's. Lines that carry a CR say so themselves; lines
            // that carry none, going into a mostly-CRLF file, are far more likely to have
            // lost it (hand-written, or autocrlf) than to be deliberately LF.
            verbatim = !(mostlyCrlf(lines) && replacement.stream().noneMatch(HunkPatcher::endsWithCr));
        } else {
            expected = hintLine - 1;
            at = locate(lines, search, expected);
            if (at < 0) {
                return NOT_FOUND;
            }
            verbatim = matchesExactly(lines, search, at);
        }

        List<String> updated = new ArrayList<>(lines.subList(0, at));
        if (verbatim) {
            updated.addAll(replacement);
        } else {
            boolean cr = !search.isEmpty() ? endsWithCr(lines.get(at)) : mostlyCrlf(lines);
            for (String line : replacement) {
                updated.add(withoutCr(line) + (cr ? "\r" : ""));
            }
        }
        updated.addAll(lines.subList(at + search.size(), lines.size()));

        if (updated.isEmpty()) {
            return new Result("", at - expected);
        }

        boolean finalNewline = endsWithNewline == null ? trailingNewline : endsWithNewline;
        if (!finalNewline && !verbatim) {
            // No trailing newline means no line ending at all; a CR invented by the
            // translation above would be a stray byte. Verbatim lines are left alone: a
            // last line that really ends in a bare CR is what the diff showed.
            int last = updated.size() - 1;
            updated.set(last, withoutCr(updated.get(last)));
        }

        StringBuilder out = new StringBuilder(String.join("\n", updated));
        if (finalNewline) {
            out.append('\n');
        }
        return new Result(out.toString(), at - expected);
    }

    private static boolean endsWithCr(String line) {
        return line.endsWith("\r");
    }

    private static String withoutCr(String line) {
        return endsWithCr(line) ? line.substring(0, line.length() - 1) : line;
    }

    /** Whether most of the file's lines are CRLF, for deciding what an insertion looks like. */
    private static boolean mostlyCrlf(List<String> lines) {
        int cr = 0;
        for (String line : lines) {
            if (endsWithCr(line)) cr++;
        }
        return cr * 2 > lines.size();
    }

    /**
     * Index of {@code block} within {@code lines}, searching outwards from {@code hint}.
     *
     * <p>The hint is where the diff said the block was, but edits elsewhere in the file
     * move it. Searching outwards means the nearest candidate wins when the block occurs
     * more than once, which is the one the user was looking at.
     *
     * @return the 0-based index, or -1 when the block is not present
     */
    public static int locate(List<String> lines, List<String> block, int hint) {
        if (block.isEmpty()) {
            // Nothing to find: the position is whatever the caller says it is.
            return Math.max(0, Math.min(hint, lines.size()));
        }
        int last = lines.size() - block.size();
        if (last < 0) {
            return -1;
        }
        int start = Math.max(0, Math.min(hint, last));
        if (matchesAt(lines, block, start)) {
            return start;
        }
        for (int distance = 1; distance <= lines.size(); distance++) {
            int before = start - distance;
            if (before >= 0 && matchesAt(lines, block, before)) {
                return before;
            }
            int after = start + distance;
            if (after <= last && matchesAt(lines, block, after)) {
                return after;
            }
            if (before < 0 && after > last) {
                break;
            }
        }
        return -1;
    }

    private static boolean matchesAt(List<String> lines, List<String> block, int index) {
        for (int i = 0; i < block.size(); i++) {
            if (!sameLine(lines.get(index + i), block.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the file holds {@code block} at {@code index} byte for byte, CRs included. */
    private static boolean matchesExactly(List<String> lines, List<String> block, int index) {
        for (int i = 0; i < block.size(); i++) {
            if (!lines.get(index + i).equals(block.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a line of the file is the line the diff meant, ignoring a trailing CR on
     * either side. See {@link #apply(String, int, List, List, Boolean)} on line endings.
     */
    private static boolean sameLine(String fileLine, String blockLine) {
        if (fileLine.equals(blockLine)) {
            return true;
        }
        int fileLength = fileLine.length() - (endsWithCr(fileLine) ? 1 : 0);
        int blockLength = blockLine.length() - (endsWithCr(blockLine) ? 1 : 0);
        return fileLength == blockLength && fileLine.regionMatches(0, blockLine, 0, fileLength);
    }
}
