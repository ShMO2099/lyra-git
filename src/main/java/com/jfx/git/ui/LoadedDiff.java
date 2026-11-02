package com.jfx.git.ui;

import com.jfx.git.core.HunkEdit;
import com.jfx.git.core.TextEncoding;
import com.jfx.git.ui.DiffParser.Hunk;
import com.jfx.git.ui.DiffRows.Kind;
import com.jfx.git.ui.DiffRows.Row;
import com.jfx.git.ui.syntax.Symbols;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One file's diff as the app has read it: decoded in a chosen encoding, split into hunks,
 * together with what it compares and what its preamble says about the file.
 *
 * <p>Immutable. Choosing another encoding or reloading gives a new loaded diff, so nothing
 * derived from one - rows, revealed lines, a hunk's edit - can outlive it unnoticed. The
 * viewer used to keep these facts in a dozen fields written from three places, and a
 * field one of them forgot was a stale summary, a charset from the previous file, or
 * context read from today's working tree under a commit's hunks.
 *
 * <p>No JavaFX here: what a diff shows and what it offers can be tested as plain values.
 * See GLOSSARY.md, "Loaded diff".
 */
public final class LoadedDiff {

    /** Nothing loaded: no file, no hunks, nothing offered. */
    public static final LoadedDiff NONE =
            new LoadedDiff(null, "", new byte[0], TextEncoding.GUESS, null, false);

    /** Said on a hunk whose actions wait for whitespace changes to be shown again. */
    static final String WHITESPACE_NOTE = "Show whitespace to stage or revert";
    /** Said on a hunk whose lines do not add up to its header. */
    static final String UNREADABLE_NOTE = "This hunk could not be read reliably - act on the whole file instead";

    private final DiffKind kind;
    private final String path;
    private final byte[] bytes;
    private final TextEncoding encoding;
    private final Supplier<byte[]> newSide;
    private final boolean whitespaceHidden;

    private final Charset lineCharset;
    private final String text;
    private final List<Hunk> hunks;
    private final DiffParser.Summary summary;
    private final int sections;

    /** The newer side's lines, read on first use; see {@link #newSideLines()}. */
    private List<String> newSideLines;

    private LoadedDiff(DiffKind kind, String path, byte[] bytes, TextEncoding encoding,
                       Supplier<byte[]> newSide, boolean whitespaceHidden) {
        this.kind = kind;
        this.path = path;
        this.bytes = bytes;
        this.encoding = encoding;
        this.newSide = newSide;
        this.whitespaceHidden = whitespaceHidden;
        // Decoded once with CRs kept, for the hunks - a line's CR is content a hunk action
        // must write back - and the display text derived from that by dropping them.
        this.lineCharset = encoding.diffLineCharset(bytes);
        String withCrs = encoding.decodeDiff(bytes, lineCharset, true);
        this.text = withCrs.indexOf('\r') < 0 ? withCrs : withoutLineCrs(withCrs);
        this.hunks = List.copyOf(DiffParser.parse(withCrs));
        this.summary = DiffParser.summarize(text);
        this.sections = DiffParser.sectionCount(text);
    }

    /**
     * Reads {@code bytes}, git's diff output for {@code path}.
     *
     * @param newSide          reads the whole file on the diff's newer side - the working
     *                         tree, the index or the revision the diff leads to - which is
     *                         what expanding context and Whole file fill in from; called at
     *                         most once, and only when needed. {@code null} when there is no
     *                         such file to read, which leaves the diff unexpandable
     * @param whitespaceHidden whether git was asked to leave whitespace changes out
     */
    public static LoadedDiff read(DiffKind kind, String path, byte[] bytes, TextEncoding encoding,
                                  Supplier<byte[]> newSide, boolean whitespaceHidden) {
        return new LoadedDiff(Objects.requireNonNull(kind, "kind"),
                path == null ? "" : path,
                bytes == null ? new byte[0] : bytes,
                encoding == null ? TextEncoding.GUESS : encoding,
                newSide, whitespaceHidden);
    }

    /** The same diff decoded as {@code other}; its newer side is read again too. */
    public LoadedDiff withEncoding(TextEncoding other) {
        if (other == encoding || this == NONE) {
            return this;
        }
        return new LoadedDiff(kind, path, bytes, other, newSide, whitespaceHidden);
    }

    /** What it compares; {@code null} for {@link #NONE}. */
    public DiffKind kind() {
        return kind;
    }

    public String path() {
        return path;
    }

    /** Git's output, as it arrived. */
    public byte[] bytes() {
        return bytes;
    }

    public TextEncoding encoding() {
        return encoding;
    }

    /** The diff as text, line endings dropped: what is shown and copied. */
    public String text() {
        return text;
    }

    /** Each hunk, its lines keeping their own CRs. */
    public List<Hunk> hunks() {
        return hunks;
    }

    /** What the preamble says about the file as a whole. */
    public DiffParser.Summary summary() {
        return summary;
    }

    /** The charset the lines were decoded with. */
    public Charset lineCharset() {
        return lineCharset;
    }

    /** How many {@code diff } sections it holds: more than one for an unmerged path's stages. */
    public int sections() {
        return sections;
    }

    public boolean whitespaceHidden() {
        return whitespaceHidden;
    }

    /**
     * Whether this is {@code previous} reloaded, so what the user revealed of it can stay
     * revealed: the same file and kind, and either a live change - the same unstaged change
     * after one of its hunks was staged - or exactly the same diff, as a commit's is when
     * the window comes back to it. Another commit's diff of the same path is neither: File
     * history steps from one to the next.
     */
    boolean continues(LoadedDiff previous) {
        return previous != null && kind != null && kind == previous.kind && path.equals(previous.path)
                && (kind.isLive() || Arrays.equals(bytes, previous.bytes));
    }

    // ------------------------------------------------------------------
    // The newer side
    // ------------------------------------------------------------------

    /** Whether the file behind it can be read, which is what context expansion needs. */
    public boolean canExpand() {
        return newSide != null && !path.isEmpty();
    }

    /** The newer side's lines, read the first time they are asked for. */
    private List<String> newSideLines() {
        if (newSideLines == null) {
            byte[] read = newSide == null ? null : newSide.get();
            String content = read == null ? null : encoding.decode(read);
            newSideLines = content == null ? List.of() : List.of(content.split("\n", -1));
        }
        return newSideLines;
    }

    /** Whether the newer side has been read; the sticky header names definitions only then. */
    boolean newSideRead() {
        return newSideLines != null && !newSideLines.isEmpty();
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /**
     * Unified rows.
     *
     * @param wholeFile     every line of the file, not just the hunks
     * @param extraContext  lines beyond git's three on each side of every hunk
     * @param revealedAbove lines the expanders have opened above each hunk, by hunk index
     */
    public Rows unified(boolean wholeFile, int extraContext, Map<Integer, Integer> revealedAbove) {
        boolean expandable = canExpand();
        // Read only when something beyond git's own lines is wanted: the whole file, the
        // extra context, or what the expanders have opened.
        List<String> lines = expandable && (wholeFile || extraContext > 0 || !revealedAbove.isEmpty())
                ? newSideLines() : List.of();
        if (wholeFile && expandable) {
            return new Rows(DiffRows.wholeFileUnified(hunks, lines));
        }
        if (expandable) {
            return new Rows(DiffRows.unified(hunks, revealedAbove, lines, extraContext));
        }
        return new Rows(DiffRows.unified(hunks));
    }

    /** Split rows; see {@link #unified}. Split has no expanders, so nothing to reveal. */
    public SplitRows split(boolean wholeFile, int extraContext) {
        boolean expandable = canExpand();
        List<String> lines = expandable && (wholeFile || extraContext > 0) ? newSideLines() : List.of();
        DiffRows.Split split;
        if (wholeFile && expandable) {
            split = DiffRows.wholeFileSplit(hunks, lines);
        } else if (extraContext > 0 && expandable) {
            // Clicking an expander is handled on the unified surface only, so in split it
            // would be a row that looks like a button and does nothing. Both columns carry
            // it at the same position, so removing it from each keeps them in step.
            DiffRows.Split withExpanders = DiffRows.split(hunks, Map.of(), lines, extraContext);
            split = new DiffRows.Split(
                    withExpanders.left().stream().filter(r -> r.kind() != Kind.EXPANDER).toList(),
                    withExpanders.right().stream().filter(r -> r.kind() != Kind.EXPANDER).toList());
        } else {
            split = DiffRows.split(hunks);
        }
        return new SplitRows(new Rows(split.left()), new Rows(split.right()));
    }

    /** {@code revealedAbove} with another {@code step} lines opened above hunk {@code index}. */
    public Map<Integer, Integer> expand(Map<Integer, Integer> revealedAbove, int index, int step) {
        return DiffRows.expand(revealedAbove, hunks, index, step);
    }

    /**
     * The definition row {@code index} of {@code rows} sits inside, as the newer side's own
     * lines say, or {@code null}. Only once those lines have been read: naming the
     * definition is not worth reading the file for.
     *
     * <p>Looked up by the row's new-side line number - the lines are the new side's. Below
     * a hunk that added or removed lines the old number points somewhere else entirely.
     */
    public String enclosingSymbol(Rows rows, int index) {
        if (!newSideRead() || index < 0 || index >= rows.size()) {
            return null;
        }
        int line = newLineAt(rows.list(), index);
        return line < 1 ? null : Symbols.enclosing(path, newSideLines, line);
    }

    /**
     * The new-side line row {@code index} stands at: its own, or for a deletion - which has
     * none - the nearest one above it in its hunk, or the line before the hunk.
     */
    private int newLineAt(List<Row> rows, int index) {
        int hunk = rows.get(index).hunkIndex();
        for (int i = index; i >= 0 && rows.get(i).hunkIndex() == hunk; i--) {
            Integer line = rows.get(i).newLine();
            if (line != null) {
                return line;
            }
        }
        return hunk >= 0 && hunk < hunks.size() ? hunks.get(hunk).newStart() - 1 : -1;
    }

    /**
     * Where the changes fall in the newer side, for the views that show every line rather
     * than just the hunks: 0-based line indices below {@code totalLines}.
     *
     * <p>Each hunk is walked counting new-side lines: context and additions each occupy
     * one, while a deletion occupies none - it is marked at the line that now sits where
     * the removed text was cut out, which is where a reader scrolling to it would look. A
     * line that was replaced therefore marks both, as a real diff's rows do.
     */
    public Marks marks(int totalLines) {
        Set<Integer> additions = new HashSet<>();
        Set<Integer> deletions = new HashSet<>();
        for (Hunk hunk : hunks) {
            int newLine = hunk.newStart();
            for (DiffParser.Line line : hunk.lines()) {
                if (line.isAdd()) {
                    mark(additions, newLine, totalLines);
                    newLine++;
                } else if (line.isDelete()) {
                    mark(deletions, newLine, totalLines);
                } else {
                    newLine++;
                }
            }
        }
        return new Marks(additions, deletions);
    }

    private static void mark(Set<Integer> into, int lineNumber, int totalLines) {
        int index = lineNumber - 1;
        if (index >= 0 && index < totalLines) {
            into.add(index);
        }
    }

    // ------------------------------------------------------------------
    // Hunk actions
    // ------------------------------------------------------------------

    /**
     * Whether this diff offers hunk actions at all.
     *
     * <p>Its kind has to have some, and its lines have to describe the file a patch would
     * be applied to: a submodule pointer has no lines, a combined diff or JGit's several
     * sections for an unmerged path is not the file against the index, and a binary diff
     * has no hunks. Ignoring whitespace is answered per hunk instead, with a note, because
     * that one the user can undo.
     */
    public boolean offersHunkActions() {
        return (kind == DiffKind.UNSTAGED || kind == DiffKind.STAGED || kind == DiffKind.COMMIT)
                && !summary.isSubmodule() && !summary.combined() && !summary.binary()
                && sections <= 1;
    }

    /** Whether a selection of lines can be acted on, not just whole hunks. */
    public boolean offersLineActions() {
        return offersHunkActions() && (kind == DiffKind.UNSTAGED || kind == DiffKind.STAGED);
    }

    /**
     * The actions on offer for hunk {@code hunkIndex}, given the lines of it the user has
     * selected - by index within the hunk - or the reason there are none.
     *
     * <p>Whole-hunk actions are always offered where the kind has them; line actions only
     * when a changed line is selected, so no button invites a click that would do nothing.
     */
    public Offer actionsFor(int hunkIndex, Set<Integer> selected) {
        if (!offersHunkActions() || hunkIndex < 0 || hunkIndex >= hunks.size()) {
            return Offer.NONE;
        }
        if (whitespaceHidden) {
            // A -w diff hides whitespace-only changes and prints context from the new side,
            // so its blocks are not what the file holds: staging failed as "no longer
            // applies", and a revert could land on the wrong lines.
            return new Offer(List.of(), WHITESPACE_NOTE);
        }
        Hunk hunk = hunks.get(hunkIndex);
        if (!hunk.isConsistent()) {
            // Git's own diffs always add up; one that does not was misread, and its blocks
            // describe no real file.
            return new Offer(List.of(), UNREADABLE_NOTE);
        }
        Set<Integer> all = HunkSelection.allChangedLines(hunk);
        Set<Integer> chosen = new HashSet<>(selected);
        chosen.retainAll(all);

        List<HunkAction> actions = new ArrayList<>();
        switch (kind) {
            case UNSTAGED -> {
                add(actions, HunkAction.Type.STAGE, hunkIndex, all, true);
                add(actions, HunkAction.Type.STAGE, hunkIndex, chosen, false);
                add(actions, HunkAction.Type.DISCARD, hunkIndex, all, true);
                add(actions, HunkAction.Type.DISCARD, hunkIndex, chosen, false);
            }
            case STAGED -> {
                add(actions, HunkAction.Type.UNSTAGE, hunkIndex, all, true);
                add(actions, HunkAction.Type.UNSTAGE, hunkIndex, chosen, false);
            }
            case COMMIT -> add(actions, HunkAction.Type.REVERT, hunkIndex, all, true);
            default -> { }
        }
        return new Offer(actions, "");
    }

    private void add(List<HunkAction> into, HunkAction.Type type, int hunkIndex,
                     Set<Integer> lines, boolean wholeHunk) {
        if (lines.isEmpty()) {
            return;
        }
        Hunk hunk = hunks.get(hunkIndex);
        HunkSelection.Patch patch = switch (type) {
            case STAGE -> HunkSelection.stage(hunk, lines);
            case UNSTAGE -> HunkSelection.unstage(hunk, lines);
            // Both put the before side back in the working tree, which holds the after side
            // - an unstaged diff's, or the commit's.
            case DISCARD, REVERT -> HunkSelection.discard(hunk, lines);
        };
        if (patch.isNoop()) {
            return;
        }
        HunkEdit edit = switch (type) {
            case STAGE -> patch.toIndexEdit(encoding, lineCharset, summary, true);
            case UNSTAGE -> patch.toIndexEdit(encoding, lineCharset, summary, false);
            case DISCARD, REVERT -> patch.toEdit(encoding, lineCharset);
        };
        into.add(new HunkAction(type, path, hunkIndex, lines, wholeHunk,
                label(type, wholeHunk, lines.size()), tooltip(type, wholeHunk), edit));
    }

    private static String label(HunkAction.Type type, boolean wholeHunk, int count) {
        String verb = switch (type) {
            case STAGE -> "Stage";
            case UNSTAGE -> "Unstage";
            case DISCARD -> "Discard";
            case REVERT -> "Revert";
        };
        if (wholeHunk) {
            return verb + " hunk";
        }
        return verb + (count == 1 ? " line" : " " + count + " lines");
    }

    private static String tooltip(HunkAction.Type type, boolean wholeHunk) {
        return switch (type) {
            case STAGE -> wholeHunk ? "Apply just this hunk to the index"
                    : "Apply only the selected lines to the index";
            case UNSTAGE -> wholeHunk ? "Take just this hunk out of the index"
                    : "Take only the selected lines out of the index";
            case DISCARD -> wholeHunk ? "Undo just this hunk in the working tree"
                    : "Undo only the selected lines in the working tree";
            case REVERT -> "Undo this commit's hunk in the working tree, as an uncommitted edit";
        };
    }

    /** {@code text} with every CR that ends a line removed. */
    private static String withoutLineCrs(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) == '\n')) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /**
     * What a hunk offers: its actions, or a note saying why there are none when the user
     * can do something about it. Both empty when there is simply nothing to offer.
     */
    public record Offer(List<HunkAction> actions, String note) {

        static final Offer NONE = new Offer(List.of(), "");

        public Offer {
            actions = List.copyOf(actions);
            note = note == null ? "" : note;
        }

        /** The action for the whole hunk, or for the selected lines, of one of {@code types}. */
        public HunkAction find(boolean wholeHunk, HunkAction.Type... types) {
            for (HunkAction action : actions) {
                if (action.wholeHunk() == wholeHunk && Arrays.asList(types).contains(action.type())) {
                    return action;
                }
            }
            return null;
        }
    }

    /** Changed lines of the newer side, by 0-based index; see {@link #marks}. */
    public record Marks(Set<Integer> additions, Set<Integer> deletions) {
    }

    /** The two columns of a split view, each with its own index. */
    public record SplitRows(Rows left, Rows right) {
    }

    /**
     * Rendered rows, and the questions asked of them by position: which row a hunk starts
     * on, which of a hunk's lines a run of rows covers, where a new-side line is.
     *
     * <p>Answered from the rows themselves - each carries its hunk and its line within it -
     * never from counting paragraphs, which only works until a layout pads or re-orders.
     */
    public static final class Rows {

        public static final Rows EMPTY = new Rows(List.of());

        private final List<Row> list;
        /** Each hunk's first row, worked out once; see {@link #firstRowOf}. */
        private int[] firstRows;

        /**
         * Takes {@code list} as it is, not a copy: the viewer remembers syntax colours and
         * word diffs by the identity of the rows they were worked out for, and a copy per
         * render would make every display toggle work them all out again. Whoever hands a
         * list over no longer changes it.
         */
        Rows(List<Row> list) {
            this.list = list;
        }

        /** Rows built elsewhere - a whole file, a blame - for the same questions. */
        public static Rows of(List<Row> list) {
            return list.isEmpty() ? EMPTY : new Rows(list);
        }

        public List<Row> list() {
            return list;
        }

        public int size() {
            return list.size();
        }

        /** The first row of hunk {@code hunk}, or -1 when it has none. */
        public int firstRowOf(int hunk) {
            if (firstRows == null) {
                int count = 0;
                for (Row row : list) {
                    count = Math.max(count, row.hunkIndex() + 1);
                }
                int[] first = new int[count];
                Arrays.fill(first, -1);
                for (int i = 0; i < list.size(); i++) {
                    int index = list.get(i).hunkIndex();
                    if (index >= 0 && first[index] < 0) {
                        first[index] = i;
                    }
                }
                firstRows = first;
            }
            return hunk >= 0 && hunk < firstRows.length ? firstRows[hunk] : -1;
        }

        /**
         * The changed lines of hunk {@code hunk} - by index within the hunk - among rows
         * {@code first} to {@code last}. Padding, context, headers and expanders count for
         * nothing, and neither do a neighbouring hunk's rows.
         */
        public Set<Integer> changedLines(int hunk, int first, int last) {
            Set<Integer> lines = new HashSet<>();
            for (int i = Math.max(0, first); i <= last && i < list.size(); i++) {
                Row row = list.get(i);
                if (row.hunkIndex() == hunk && row.isPatchLine()) {
                    lines.add(row.hunkLine());
                }
            }
            return lines;
        }

        /** The highest new-side line number any row shows; 0 when none does. */
        public int lastNewLine() {
            int last = 0;
            for (Row row : list) {
                if (row.newLine() != null) {
                    last = Math.max(last, row.newLine());
                }
            }
            return last;
        }

        /** The row showing new-side line {@code line}, or -1. */
        public int rowOfNewLine(int line) {
            for (int i = 0; i < list.size(); i++) {
                Integer shown = list.get(i).newLine();
                if (shown != null && shown == line) {
                    return i;
                }
            }
            return -1;
        }
    }
}
