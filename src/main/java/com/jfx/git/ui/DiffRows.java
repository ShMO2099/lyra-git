package com.jfx.git.ui;

import com.jfx.git.ui.DiffParser.Hunk;
import com.jfx.git.ui.DiffParser.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flattens parsed hunks into the row list the viewer renders.
 *
 * <p>Every mode - unified, split, whole file, blame - comes down to a list of rows, so
 * the viewer has one render path instead of one per mode. Keeping the flattening here,
 * as plain data with no JavaFX in sight, means the line-number and padding arithmetic
 * can be tested directly rather than inferred from what ends up on screen.
 */
public final class DiffRows {

    /** What a row is, which decides both its gutter and its styling. */
    public enum Kind {
        /** The {@code diff --git} preamble, shown only when a diff covers several files. */
        FILE_HEADER,
        /** An {@code @@ -1,2 +1,2 @@} range marker. */
        HUNK_HEADER,
        /** A line of content: context, addition or deletion. */
        CODE,
        /** Filler opposite an unmatched line, so split columns stay in step. */
        PAD,
        /** A run of unchanged lines Git left out, offered for expansion. */
        EXPANDER
    }

    /**
     * One rendered line.
     *
     * @param marker      {@code ' '}, {@code '+'} or {@code '-'} for code rows
     * @param hunkIndex   which hunk the row belongs to, or -1 outside any hunk
     * @param hiddenLines lines an {@link Kind#EXPANDER} stands for; 0 otherwise
     * @param hunkLine    index into {@code hunks.get(hunkIndex).lines()} of the line this
     *                    row was built from, or {@link #NOT_A_HUNK_LINE} for anything that
     *                    was not - headers, padding, expanders, and context read from the
     *                    file rather than from the patch
     */
    public record Row(Kind kind,
                      char marker,
                      String text,
                      Integer oldLine,
                      Integer newLine,
                      int hunkIndex,
                      int hiddenLines,
                      int hunkLine) {

        /** {@link #hunkLine} for a row with no line in the patch behind it. */
        public static final int NOT_A_HUNK_LINE = -1;

        /**
         * A row that stands for nothing in the patch.
         *
         * <p>Kept so the seven-argument shape still reads naturally at the call sites that
         * genuinely have no patch line to point at.
         */
        public Row(Kind kind, char marker, String text, Integer oldLine, Integer newLine,
                   int hunkIndex, int hiddenLines) {
            this(kind, marker, text, oldLine, newLine, hunkIndex, hiddenLines, NOT_A_HUNK_LINE);
        }

        /** Whether this row can be staged or reverted on its own. */
        public boolean isPatchLine() {
            return hunkLine != NOT_A_HUNK_LINE && (isAdd() || isDelete());
        }

        public boolean isAdd() {
            return kind == Kind.CODE && marker == '+';
        }

        public boolean isDelete() {
            return kind == Kind.CODE && marker == '-';
        }

        /** The style class this row's paragraph carries, or {@code null} for plain code. */
        public String styleClass() {
            return switch (kind) {
                case FILE_HEADER -> "diff-file-header-row";
                case HUNK_HEADER -> "diff-hunk-header-row";
                case EXPANDER -> "diff-expander-row";
                case PAD -> "diff-blank";
                case CODE -> isAdd() ? "diff-addition" : isDelete() ? "diff-deletion" : null;
            };
        }

        /**
         * @param hunkLine where {@code line} sits in its hunk's own line list - the
         *                 identity partial staging is expressed in, and the reason this
         *                 is not derived from the row's position on screen: split view
         *                 pads and re-orders, so a row's position says nothing about
         *                 which patch line it is
         */
        static Row code(Line line, int hunkIndex, int hunkLine) {
            return new Row(Kind.CODE, line.kind(), line.text(),
                    line.oldLine(), line.newLine(), hunkIndex, 0, hunkLine);
        }

        static Row pad(int hunkIndex) {
            return new Row(Kind.PAD, ' ', "", null, null, hunkIndex, 0);
        }

        static Row hunkHeader(Hunk hunk, int hunkIndex) {
            return new Row(Kind.HUNK_HEADER, ' ', hunk.header(), null, null, hunkIndex, 0);
        }

        static Row expander(int hiddenLines, int hunkIndex) {
            String text = hiddenLines == 1 ? "1 hidden line" : hiddenLines + " hidden lines";
            return new Row(Kind.EXPANDER, ' ', text, null, null, hunkIndex, hiddenLines);
        }
    }

    /** The two columns of a side-by-side diff, padded to the same length. */
    public record Split(List<Row> left, List<Row> right) {
    }

    private DiffRows() {
    }

    // ------------------------------------------------------------------
    // Unified
    // ------------------------------------------------------------------

    public static List<Row> unified(List<Hunk> hunks) {
        return unified(hunks, false);
    }

    /**
     * @param includeExpanders emit a row for each run of lines Git omitted between hunks
     */
    public static List<Row> unified(List<Hunk> hunks, boolean includeExpanders) {
        return unified(hunks, includeExpanders ? Map.of() : null, List.of());
    }

    /**
     * Unified rows with some of the omitted context revealed.
     *
     * <p>Git shows three lines around a change, which is often too few to judge whether
     * the change is right. Each gap between hunks becomes an expander row carrying the
     * count of lines behind it; revealing them splices those lines back in from the file
     * itself.
     *
     * @param revealedAbove how many hidden lines to show above each hunk, keyed by hunk
     *                      index; {@code null} suppresses expanders entirely
     * @param fileLines     the whole file, needed to fill an expansion in
     */
    public static List<Row> unified(List<Hunk> hunks,
                                    Map<Integer, Integer> revealedAbove,
                                    List<String> fileLines) {
        return unified(hunks, revealedAbove, fileLines, 0);
    }

    /**
     * Unified rows with {@code extra} more lines of context on each side of every hunk
     * than the three git includes, on top of whatever the expanders have revealed.
     *
     * <p>How the Context selector's 10 is made without asking git again: the lines are
     * the file's own, read from {@code fileLines}, so the hunks - and with them every
     * line-staging identity - stay exactly the ones git produced.
     *
     * @param extra lines to add above and below each hunk; 0 for git's own context
     */
    public static List<Row> unified(List<Hunk> hunks,
                                    Map<Integer, Integer> revealedAbove,
                                    List<String> fileLines,
                                    int extra) {
        Map<Integer, Integer> above = revealedAbove == null ? null
                : atLeast(revealedAbove, hunks.size(), extra);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < hunks.size(); i++) {
            Hunk hunk = hunks.get(i);
            if (above != null) {
                addContextAbove(rows, hunks, i, above, fileLines, extra);
            }
            rows.add(Row.hunkHeader(hunk, i));
            List<Line> lines = hunk.lines();
            for (int li = 0; li < lines.size(); li++) {
                rows.add(Row.code(lines.get(li), i, li));
            }
        }
        if (above != null && extra > 0) {
            addTrailingContext(rows, hunks, fileLines, extra);
        }
        return rows;
    }

    /** {@code revealedAbove}, with every hunk revealing at least {@code extra} lines. */
    private static Map<Integer, Integer> atLeast(Map<Integer, Integer> revealedAbove,
                                                 int hunkCount, int extra) {
        if (extra <= 0) {
            return revealedAbove;
        }
        Map<Integer, Integer> above = new java.util.HashMap<>(revealedAbove);
        for (int i = 0; i < hunkCount; i++) {
            // Added to what the expander opened, not the larger of the two: with max, the
            // first click on an expander under Context 10 showed thirteen more lines, not
            // the twenty it stands for. Saturating, since "reveal all" is MAX_VALUE.
            above.merge(i, extra, (opened, more) -> (int) Math.min(Integer.MAX_VALUE, (long) opened + more));
        }
        return above;
    }

    /**
     * The gap above hunk {@code index}: context under the previous hunk, the expander for
     * whatever is still hidden, and the lines revealed above this one.
     *
     * @param extraBelow lines to show at the top of the gap, under the previous hunk
     */
    private static void addContextAbove(List<Row> rows, List<Hunk> hunks, int index,
                                        Map<Integer, Integer> revealedAbove,
                                        List<String> fileLines, int extraBelow) {
        int hidden = hiddenBefore(hunks, index);
        if (hidden <= 0) {
            return;
        }
        Hunk hunk = hunks.get(index);
        int revealed = Math.max(0, Math.min(hidden, revealedAbove.getOrDefault(index, 0)));
        // Nothing sits above the first hunk, so there is no "under the previous one".
        int below = index > 0 ? Math.max(0, Math.min(extraBelow, hidden - revealed)) : 0;
        // Only what is still hidden after the expansion keeps an expander; once the two
        // hunks meet, the rows run straight through with nothing between them.
        int remaining = hidden - revealed - below;

        // Revealed lines are unchanged, so the two sides differ by a constant offset -
        // whatever this hunk's own headers say it is.
        int offset = hunk.newStart() - hunk.oldStart();
        int gapStart = hunk.oldStart() - hidden;
        // Tagged with the hunk they follow, the way a trailing tail is.
        for (int old = gapStart; old < gapStart + below; old++) {
            rows.add(contextRow(old, offset, index - 1, fileLines));
        }
        if (remaining > 0) {
            rows.add(Row.expander(remaining, index));
        }
        for (int old = hunk.oldStart() - revealed; old < hunk.oldStart(); old++) {
            rows.add(contextRow(old, offset, index, fileLines));
        }
    }

    /**
     * One unchanged line read from the file.
     *
     * <p>fileLines is the new side (the working tree, the index or the revision the diff
     * leads to), so a line - unchanged, but possibly shifted by an earlier hunk's own
     * added/removed count - is looked up by its position on THAT side, not by the
     * old-side number the caller is counting with.
     */
    private static Row contextRow(int oldLine, int offset, int hunkIndex, List<String> fileLines) {
        int newLine = oldLine + offset;
        String text = newLine >= 1 && newLine <= fileLines.size() ? fileLines.get(newLine - 1) : "";
        return new Row(Kind.CODE, ' ', text, oldLine, newLine, hunkIndex, 0);
    }

    /**
     * Unchanged lines from the end of the last hunk to the end of the file - the mirror
     * image of {@link #addContextAbove}, needed because nothing today reveals a tail:
     * the expander only ever sits above a hunk, and there is no hunk below the last one
     * for it to lead into. Tagged with the last hunk's own index, matching how a leading
     * gap is tagged with the hunk it leads into, so revealed content behaves the same
     * (hover, stage/revert-by-hunk) on both sides of a hunk.
     */
    private static void addTrailingContext(List<Row> rows, List<Hunk> hunks, List<String> fileLines) {
        addTrailingContext(rows, hunks, fileLines, Integer.MAX_VALUE);
    }

    /** At most {@code limit} lines of the tail. */
    private static void addTrailingContext(List<Row> rows, List<Hunk> hunks, List<String> fileLines,
                                           int limit) {
        if (hunks.isEmpty()) {
            return;
        }
        Hunk last = hunks.get(hunks.size() - 1);
        int offset = last.newStart() - last.oldStart();
        int lastNewLine = last.newStart() + last.newCount() - 1;
        int hunkIndex = hunks.size() - 1;
        long end = Math.min(fileLines.size(), (long) lastNewLine + limit);
        for (int newLine = lastNewLine + 1; newLine <= end; newLine++) {
            rows.add(new Row(Kind.CODE, ' ', fileLines.get(newLine - 1), newLine - offset, newLine, hunkIndex, 0));
        }
    }

    /** The next expansion step: at most {@code step} more lines above hunk {@code index}. */
    public static Map<Integer, Integer> expand(Map<Integer, Integer> revealedAbove,
                                               List<Hunk> hunks, int index, int step) {
        Map<Integer, Integer> next = new java.util.HashMap<>(revealedAbove);
        int hidden = hiddenBefore(hunks, index);
        int already = next.getOrDefault(index, 0);
        next.put(index, Math.min(hidden, already + step));
        return next;
    }

    /**
     * Unchanged lines between the end of the previous hunk and the start of this one.
     *
     * <p>For the first hunk that is everything above it, which is why the count is taken
     * against line 1 rather than against a previous hunk that does not exist.
     */
    static int hiddenBefore(List<Hunk> hunks, int index) {
        if (index == 0) {
            return Math.max(0, hunks.get(0).oldStart() - 1);
        }
        Hunk previous = hunks.get(index - 1);
        int previousEnd = previous.oldStart() + previous.oldCount();
        return Math.max(0, hunks.get(index).oldStart() - previousEnd);
    }

    // ------------------------------------------------------------------
    // Split
    // ------------------------------------------------------------------

    /**
     * Splits each hunk into a before and an after column.
     *
     * <p>Runs of deletions and additions are laid alongside each other and the shorter
     * run padded, so a line and its replacement sit on the same row. Both columns keep
     * the hunk header, which is what holds them in step as the reader scrolls.
     */
    public static Split split(List<Hunk> hunks) {
        return split(hunks, null, List.of());
    }

    /**
     * Split rows with some of the omitted context revealed - split's own counterpart to
     * {@link #unified(List, Map, List)}. Revealed context is identical on both sides
     * (it is unchanged, by definition), so the same {@link #addContextAbove} call fills
     * both columns; only the hunk's deletions/additions differ between them.
     *
     * @param revealedAbove how many hidden lines to show above each hunk, keyed by hunk
     *                      index; {@code null} suppresses expanders entirely
     * @param fileLines     the whole file (new/current side), needed to fill an expansion in
     */
    public static Split split(List<Hunk> hunks, Map<Integer, Integer> revealedAbove, List<String> fileLines) {
        return split(hunks, revealedAbove, fileLines, 0);
    }

    /** Split's counterpart to {@link #unified(List, Map, List, int)}. */
    public static Split split(List<Hunk> hunks, Map<Integer, Integer> revealedAbove,
                              List<String> fileLines, int extra) {
        Map<Integer, Integer> above = revealedAbove == null ? null
                : atLeast(revealedAbove, hunks.size(), extra);
        List<Row> left = new ArrayList<>();
        List<Row> right = new ArrayList<>();

        for (int i = 0; i < hunks.size(); i++) {
            Hunk hunk = hunks.get(i);
            if (above != null) {
                addContextAbove(left, hunks, i, above, fileLines, extra);
                addContextAbove(right, hunks, i, above, fileLines, extra);
            }
            left.add(Row.hunkHeader(hunk, i));
            right.add(Row.hunkHeader(hunk, i));

            // Indices rather than the lines themselves: the row has to remember which
            // patch line it came from, and once a run has been padded out to match the
            // other column there is no way to work that out again from position.
            List<Integer> deletions = new ArrayList<>();
            List<Integer> additions = new ArrayList<>();
            List<Line> lines = hunk.lines();
            for (int li = 0; li < lines.size(); li++) {
                Line line = lines.get(li);
                if (line.isDelete()) {
                    deletions.add(li);
                } else if (line.isAdd()) {
                    additions.add(li);
                } else {
                    flush(lines, deletions, additions, left, right, i);
                    left.add(Row.code(line, i, li));
                    right.add(Row.code(line, i, li));
                }
            }
            flush(lines, deletions, additions, left, right, i);
        }
        if (above != null && extra > 0) {
            addTrailingContext(left, hunks, fileLines, extra);
            addTrailingContext(right, hunks, fileLines, extra);
        }
        return new Split(left, right);
    }

    private static void flush(List<Line> lines, List<Integer> deletions, List<Integer> additions,
                              List<Row> left, List<Row> right, int hunkIndex) {
        int rows = Math.max(deletions.size(), additions.size());
        for (int i = 0; i < rows; i++) {
            left.add(i < deletions.size()
                    ? Row.code(lines.get(deletions.get(i)), hunkIndex, deletions.get(i))
                    : Row.pad(hunkIndex));
            right.add(i < additions.size()
                    ? Row.code(lines.get(additions.get(i)), hunkIndex, additions.get(i))
                    : Row.pad(hunkIndex));
        }
        deletions.clear();
        additions.clear();
    }

    // ------------------------------------------------------------------
    // Whole file, with diff markers - every gap open, tail included
    // ------------------------------------------------------------------

    /**
     * Unified rows for the entire file, continuously - every hunk's leading gap opened
     * in full, plus the tail from the last hunk to end of file, so scrolling never meets
     * a collapsed section. Diff markers stay intact throughout.
     *
     * <p>Deliberately not named as an overload of {@link #unified}: that family always
     * means "hunks, with however much context was asked for," while {@link #fileContent}
     * already means "whole file, no diff markers at all" (the viewer's own plain
     * file-content mode). This sits between the two and needs its own name so the three
     * are never confused for each other at a call site.
     *
     * @param fileLines the whole file (new/current side)
     */
    public static List<Row> wholeFileUnified(List<Hunk> hunks, List<String> fileLines) {
        if (hunks.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>(unified(hunks, revealAll(hunks), fileLines));
        addTrailingContext(rows, hunks, fileLines);
        return rows;
    }

    /** Split's counterpart to {@link #wholeFileUnified}. */
    public static Split wholeFileSplit(List<Hunk> hunks, List<String> fileLines) {
        if (hunks.isEmpty()) {
            return new Split(List.of(), List.of());
        }
        Split base = split(hunks, revealAll(hunks), fileLines);
        List<Row> left = new ArrayList<>(base.left());
        List<Row> right = new ArrayList<>(base.right());
        addTrailingContext(left, hunks, fileLines);
        addTrailingContext(right, hunks, fileLines);
        return new Split(left, right);
    }

    /**
     * Every hunk's hidden count marked fully revealed. Relies on {@link #addContextAbove}
     * clamping {@code revealed} to what is actually hidden, so a plain "reveal
     * everything" sentinel needs no per-hunk arithmetic of its own.
     */
    private static Map<Integer, Integer> revealAll(List<Hunk> hunks) {
        Map<Integer, Integer> all = new java.util.HashMap<>();
        for (int i = 0; i < hunks.size(); i++) {
            all.put(i, Integer.MAX_VALUE);
        }
        return all;
    }

    // ------------------------------------------------------------------
    // Whole file, no diff markers
    // ------------------------------------------------------------------

    /** Every line of a file as an unchanged row, numbered from one. */
    public static List<Row> fileContent(String content) {
        return plain(List.of((content == null ? "" : content).split("\n", -1)));
    }

    /** The same, for text that has already been split - blame results, for instance. */
    public static List<Row> plain(List<String> lines) {
        List<Row> rows = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i) == null ? "" : lines.get(i);
            rows.add(new Row(Kind.CODE, ' ', text, i + 1, i + 1, -1, 0));
        }
        return rows;
    }
}
