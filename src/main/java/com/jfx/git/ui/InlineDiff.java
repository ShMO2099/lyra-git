package com.jfx.git.ui;

import com.jfx.git.ui.DiffRows.Kind;
import com.jfx.git.ui.DiffRows.Row;

import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Narrows a changed line down to the words that actually changed.
 *
 * <p>Git works in whole lines, so renaming one variable paints the entire line red on
 * one side and green on the other and leaves the reader to find the difference. This
 * pairs each deleted line with the added line that replaced it and diffs the two as
 * sequences of words, producing the character ranges worth emphasising.
 *
 * <p>Pure data in, pure data out - no JavaFX - so the pairing and the token arithmetic
 * are testable directly.
 */
public final class InlineDiff {

    /** A run of characters within one line that differs from its counterpart. */
    public record Segment(int start, int end) {
        int length() {
            return end - start;
        }
    }

    /**
     * Beyond this many lines on either side of a change, pairing line 1 with line 1 is
     * guesswork; the block has been rewritten rather than edited.
     */
    private static final int MAX_PAIRED_LINES = 100;

    /** Tokenizing a minified bundle costs more than the highlight is worth. */
    private static final int MAX_LINE_LENGTH = 2000;

    /**
     * If this much of a line changed, the two lines are not versions of each other and
     * highlighting "the changed parts" would mean highlighting nearly all of it.
     */
    private static final double MAX_CHANGED_FRACTION = 0.6;

    private InlineDiff() {
    }

    // ------------------------------------------------------------------
    // Row-level entry points
    // ------------------------------------------------------------------

    /**
     * Intra-line segments for a unified row list, keyed by row index.
     *
     * <p>Rows with no entry are left with their whole-line colouring, which is the right
     * answer for a pure insertion or deletion.
     */
    public static Map<Integer, List<Segment>> forUnified(List<Row> rows) {
        Map<Integer, List<Segment>> segments = new HashMap<>();

        int i = 0;
        while (i < rows.size()) {
            if (!isDelete(rows, i)) {
                i++;
                continue;
            }
            int deletionsEnd = i;
            while (isDelete(rows, deletionsEnd)) {
                deletionsEnd++;
            }
            int additionsEnd = deletionsEnd;
            while (isAdd(rows, additionsEnd)) {
                additionsEnd++;
            }

            pairRuns(rows, i, deletionsEnd, additionsEnd, segments);
            i = additionsEnd > deletionsEnd ? additionsEnd : deletionsEnd;
        }
        return segments;
    }

    /**
     * The same for split view, where the two columns already sit line against line.
     *
     * @return segments for the left column and for the right, keyed by row index
     */
    public static Map<Integer, List<Segment>>[] forSplit(List<Row> left, List<Row> right) {
        @SuppressWarnings("unchecked")
        Map<Integer, List<Segment>>[] result = new Map[]{new HashMap<>(), new HashMap<>()};

        int rows = Math.min(left.size(), right.size());
        for (int i = 0; i < rows; i++) {
            Row before = left.get(i);
            Row after = right.get(i);
            if (!before.isDelete() || !after.isAdd()) {
                continue;
            }
            Segments pair = compare(before.text(), after.text());
            if (pair == null) {
                continue;
            }
            result[0].put(i, pair.oldSegments());
            result[1].put(i, pair.newSegments());
        }
        return result;
    }

    private static boolean isDelete(List<Row> rows, int index) {
        return index < rows.size() && rows.get(index).kind() == Kind.CODE && rows.get(index).isDelete();
    }

    private static boolean isAdd(List<Row> rows, int index) {
        return index < rows.size() && rows.get(index).kind() == Kind.CODE && rows.get(index).isAdd();
    }

    /**
     * Pairs a run of deletions with the run of additions that follows it.
     *
     * <p>Pairing is by position: the first line removed against the first line added.
     * Anything cleverer would need to guess at intent, and guessing wrong is worse than
     * leaving the line coloured whole.
     */
    private static void pairRuns(List<Row> rows, int deletionsStart, int additionsStart,
                                 int additionsEnd, Map<Integer, List<Segment>> into) {
        int deletions = additionsStart - deletionsStart;
        int additions = additionsEnd - additionsStart;
        if (deletions == 0 || additions == 0) {
            return; // a pure removal or a pure insertion has nothing to pair against
        }
        if (deletions > MAX_PAIRED_LINES || additions > MAX_PAIRED_LINES) {
            return;
        }

        int pairs = Math.min(deletions, additions);
        for (int p = 0; p < pairs; p++) {
            int oldRow = deletionsStart + p;
            int newRow = additionsStart + p;
            Segments segments = compare(rows.get(oldRow).text(), rows.get(newRow).text());
            if (segments != null) {
                into.put(oldRow, segments.oldSegments());
                into.put(newRow, segments.newSegments());
            }
        }
    }

    // ------------------------------------------------------------------
    // The comparison itself
    // ------------------------------------------------------------------

    /** Segments on each side of one paired line. */
    record Segments(List<Segment> oldSegments, List<Segment> newSegments) {
    }

    /**
     * Compares two lines word by word.
     *
     * @return the changed ranges on each side, or {@code null} when the lines are too
     *         different for the comparison to tell the reader anything
     */
    static Segments compare(String before, String after) {
        if (before == null || after == null || before.equals(after)) {
            return null;
        }
        if (before.length() > MAX_LINE_LENGTH || after.length() > MAX_LINE_LENGTH) {
            return null;
        }

        List<Token> beforeTokens = tokenize(before);
        List<Token> afterTokens = tokenize(after);

        EditList edits = new HistogramDiff().diff(TOKEN_COMPARATOR,
                new TokenSequence(beforeTokens), new TokenSequence(afterTokens));
        if (edits.isEmpty()) {
            return null;
        }

        List<Segment> oldSegments = new ArrayList<>();
        List<Segment> newSegments = new ArrayList<>();
        for (Edit edit : edits) {
            addSegment(oldSegments, beforeTokens, edit.getBeginA(), edit.getEndA());
            addSegment(newSegments, afterTokens, edit.getBeginB(), edit.getEndB());
        }

        if (tooDifferent(oldSegments, before) || tooDifferent(newSegments, after)) {
            return null;
        }
        return new Segments(merge(oldSegments), merge(newSegments));
    }

    private static void addSegment(List<Segment> into, List<Token> tokens, int begin, int end) {
        if (begin >= end || tokens.isEmpty()) {
            return; // this side is an insertion or a deletion, with nothing to mark
        }
        into.add(new Segment(tokens.get(begin).start(), tokens.get(end - 1).end()));
    }

    private static boolean tooDifferent(List<Segment> segments, String line) {
        if (line.isEmpty()) {
            return false;
        }
        int changed = 0;
        for (Segment segment : segments) {
            changed += segment.length();
        }
        return changed > line.length() * MAX_CHANGED_FRACTION;
    }

    /** Joins segments that touch, so a run of changed tokens reads as one highlight. */
    private static List<Segment> merge(List<Segment> segments) {
        if (segments.size() < 2) {
            return List.copyOf(segments);
        }
        List<Segment> merged = new ArrayList<>();
        Segment current = segments.get(0);
        for (int i = 1; i < segments.size(); i++) {
            Segment next = segments.get(i);
            if (next.start() <= current.end()) {
                current = new Segment(current.start(), Math.max(current.end(), next.end()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    // ------------------------------------------------------------------
    // Tokens
    // ------------------------------------------------------------------

    /** A word, a run of whitespace, or a single punctuation character. */
    record Token(String text, int start, int end) {
    }

    /**
     * Splits a line into words, whitespace runs and single punctuation characters.
     *
     * <p>Punctuation is deliberately not grouped: {@code )}} and {@code );} share a
     * character, and grouping them would report the whole run as changed when only one
     * of them is.
     */
    static List<Token> tokenize(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            int start = i;
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                        || line.charAt(i) == '_' || line.charAt(i) == '$')) {
                    i++;
                }
            } else if (Character.isWhitespace(ch)) {
                while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
            } else {
                i++;
            }
            tokens.add(new Token(line.substring(start, i), start, i));
        }
        return tokens;
    }

    /** Adapts a token list to what JGit's diff algorithms consume. */
    private static final class TokenSequence extends Sequence {
        private final List<Token> tokens;

        TokenSequence(List<Token> tokens) {
            this.tokens = tokens;
        }

        @Override
        public int size() {
            return tokens.size();
        }
    }

    private static final SequenceComparator<TokenSequence> TOKEN_COMPARATOR =
            new SequenceComparator<>() {
                @Override
                public boolean equals(TokenSequence a, int ai, TokenSequence b, int bi) {
                    return a.tokens.get(ai).text().equals(b.tokens.get(bi).text());
                }

                @Override
                public int hash(TokenSequence seq, int ptr) {
                    return seq.tokens.get(ptr).text().hashCode();
                }
            };
}
