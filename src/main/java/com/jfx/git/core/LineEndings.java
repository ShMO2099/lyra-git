package com.jfx.git.core;

/**
 * Which line terminator a document uses.
 *
 * <p>There was no line-ending model in this package at all, because nothing needed one:
 * {@link TextEncoding#decode} deliberately leaves CR bytes where it finds them - "a CRLF
 * file round-trips as CRLF" - so every layer above it could stay ignorant and still be
 * correct. The editor's status footer is the first thing that has to actually name the
 * convention, which means counting.
 *
 * <p>{@link #MIXED} is not a theoretical case. A repository with {@code core.autocrlf=true}
 * and one file hand-edited on the other side of a share produces it routinely, and
 * reporting "LF" over a file that is ninety percent CRLF is worse than reporting nothing:
 * it is the state most worth knowing about, dressed as the state least worth knowing
 * about.
 *
 * <p>Related, deliberately not merged: {@code HunkPatcher} decides what terminator to
 * write when splicing a hunk, by the same majority rule {@link #dominant} uses. Its
 * behaviour is pinned by {@code HunkPatcherTest} against the exact bytes of a patch, so it
 * keeps its own copy rather than taking a dependency on a display concern.
 */
public enum LineEndings {

    LF("LF"),
    CRLF("CRLF"),
    /** Classic Mac OS, and the shape a half-converted file takes. */
    CR("CR"),
    /** More than one convention in the same document. */
    MIXED("Mixed"),
    /** A single line with no terminator at all, or no text. */
    NONE("\u2014");

    private final String label;

    LineEndings(String label) {
        this.label = label;
    }

    /** What to put on screen. */
    public String label() {
        return label;
    }

    /** How many of each terminator a document contains. */
    public record Counts(int lf, int crlf, int cr) {

        public int total() {
            return lf + crlf + cr;
        }

        /** How many distinct conventions appear. */
        public int kinds() {
            return (lf > 0 ? 1 : 0) + (crlf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
        }

        /** "3 CRLF, 1 LF" - the detail behind a {@link LineEndings#MIXED} verdict. */
        public String describe() {
            StringBuilder text = new StringBuilder();
            if (crlf > 0) text.append(crlf).append(" CRLF");
            if (lf > 0) text.append(text.isEmpty() ? "" : ", ").append(lf).append(" LF");
            if (cr > 0) text.append(text.isEmpty() ? "" : ", ").append(cr).append(" CR");
            return text.isEmpty() ? "no line endings" : text.toString();
        }
    }

    /** The convention {@code text} uses, or {@link #MIXED} when it uses more than one. */
    public static LineEndings detect(String text) {
        Counts counts = count(text);
        if (counts.total() == 0) return NONE;
        if (counts.kinds() > 1) return MIXED;
        if (counts.crlf() > 0) return CRLF;
        if (counts.lf() > 0) return LF;
        return CR;
    }

    /**
     * Counts each terminator in one pass.
     *
     * <p>A CR is only its own terminator when nothing follows it, which is what keeps the
     * CR half of a CRLF from being counted twice.
     */
    public static Counts count(String text) {
        if (text == null || text.isEmpty()) return new Counts(0, 0, 0);
        int lf = 0;
        int crlf = 0;
        int cr = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            } else if (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')) {
                cr++;
            }
        }
        return new Counts(lf, crlf, cr);
    }

    /**
     * The prevailing real terminator behind a {@link #MIXED} verdict - what the file would
     * be called if the stragglers were normalised.
     *
     * <p>Ties go to whichever appears first, so the convention the file opens with wins.
     * That is arbitrary, and it is arbitrary on purpose: any rule here is a guess, and a
     * stable guess is worth more than a clever one.
     */
    public static LineEndings dominant(String text) {
        Counts counts = count(text);
        if (counts.total() == 0) return NONE;

        int best = Math.max(counts.crlf(), Math.max(counts.lf(), counts.cr()));
        // Only the kinds actually tied for the lead are candidates; the first of those to
        // appear in the text is the answer.
        boolean crlfTied = counts.crlf() == best;
        boolean lfTied = counts.lf() == best;
        boolean crTied = counts.cr() == best;
        if (crlfTied && !lfTied && !crTied) return CRLF;
        if (lfTied && !crlfTied && !crTied) return LF;
        if (crTied && !crlfTied && !lfTied) return CR;

        return firstOf(text, crlfTied, lfTied, crTied);
    }

    /**
     * {@code bufferText} with its line breaks written the way {@code reference} writes
     * them - the majority convention, for a mixed file.
     *
     * <p>For text that went through a RichTextFX area, which drops every carriage return:
     * written back as it comes out, a CRLF file becomes an LF one, every line of it a
     * change.
     *
     * @param reference the file as it was, terminators intact; null leaves the text alone
     */
    public static String matchConvention(String reference, String bufferText) {
        if (reference == null || bufferText == null) {
            return bufferText;
        }
        LineEndings found = detect(reference);
        return convert(bufferText, found == MIXED ? dominant(reference) : found);
    }

    /**
     * {@code bufferText}, whose breaks are LF, with each break written as {@code target}.
     * Any CR already in it is folded to LF first, so none is ever doubled.
     */
    public static String convert(String bufferText, LineEndings target) {
        if (bufferText == null || target == null) {
            return bufferText;
        }
        String lf = bufferText.indexOf('\r') < 0 ? bufferText
                : bufferText.replace("\r\n", "\n").replace('\r', '\n');
        return switch (target) {
            case CRLF -> lf.replace("\n", "\r\n");
            case CR -> lf.replace('\n', '\r');
            case LF, MIXED, NONE -> lf;
        };
    }

    /** The first of the tied terminators to occur in {@code text}. */
    private static LineEndings firstOf(String text, boolean crlf, boolean lf, boolean cr) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                boolean isCrlf = i > 0 && text.charAt(i - 1) == '\r';
                if (isCrlf && crlf) return CRLF;
                if (!isCrlf && lf) return LF;
            } else if (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')) {
                if (cr) return CR;
            }
        }
        return NONE;
    }
}
