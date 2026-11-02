package com.jfx.git.ui.dialogs;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

import java.util.ArrayList;
import java.util.List;

/**
 * A conflicted file as git wrote it, taken apart: the text both sides agree on, and the
 * conflicts between, each with the lines it offers and the marker lines it came in.
 *
 * <h2>Why take it apart</h2>
 * The merge tool used to show the working-tree file as it was, markers and all, and the
 * user resolved a conflict by deleting the right lines around {@code =======}. It shows
 * a clean file now - the agreed text, and in place of each conflict whatever has been
 * chosen for it - and writes git's markers back only for a conflict nobody has decided.
 * That needs the markers kept exactly as they were, labels included, so a conflict left
 * alone goes back to disk byte for byte: {@link #text()} is the file this was parsed from.
 *
 * <h2>Where each conflict sits in the three versions</h2>
 * {@link #locate} finds each conflict's lines in the ours, theirs and base files, which is
 * what the side panes tint and scroll to, and what the result shows for a conflict nobody
 * has decided: the common ancestor's lines. Git only writes those into the file with
 * {@code merge.conflictStyle=diff3}; without it they are worked out here.
 */
final class ConflictDocument {

    /** Git's own markers, which is what "still conflicted" means in a file. */
    static final String OURS_MARKER = "<<<<<<<";
    static final String BASE_MARKER = "|||||||";
    static final String SPLIT_MARKER = "=======";
    static final String THEIRS_MARKER = ">>>>>>>";

    /**
     * One conflict, as git wrote it.
     *
     * @param open       the {@code <<<<<<< label} line
     * @param ours       the lines between it and the base or split marker
     * @param baseMarker the {@code ||||||| label} line of a diff3-style conflict, or null
     * @param base       the ancestor's lines a diff3-style conflict carries, or null
     * @param split      the {@code =======} line
     * @param theirs     the lines between the split and the closing marker
     * @param close      the {@code >>>>>>> label} line
     */
    record Conflict(String open, List<String> ours, String baseMarker, List<String> base,
                    String split, List<String> theirs, String close) {

        Conflict {
            ours = List.copyOf(ours);
            base = base == null ? null : List.copyOf(base);
            theirs = List.copyOf(theirs);
        }

        /** The conflict exactly as it stood in the file: markers, labels and all. */
        List<String> markerLines() {
            List<String> lines = new ArrayList<>(ours.size() + theirs.size() + 4
                    + (base == null ? 0 : base.size() + 1));
            lines.add(open);
            lines.addAll(ours);
            if (baseMarker != null) {
                lines.add(baseMarker);
                lines.addAll(base);
            }
            lines.add(split);
            lines.addAll(theirs);
            lines.add(close);
            return lines;
        }
    }

    /**
     * A run of lines in one of the three versions, {@code [start, end)}. Empty when the
     * version has no lines there - one side deleted them, or neither had any - in which
     * case {@code start} is where they would have been.
     */
    record Range(int start, int end) {
        int length() {
            return end - start;
        }

        boolean contains(int line) {
            return line >= start && line < end;
        }
    }

    /**
     * Where one conflict sits in the three versions, and what the result shows for it
     * while it is undecided.
     *
     * @param ours   its lines in the ours file, or null when they could not be found
     *               there verbatim (an edited draft, a custom merge driver)
     * @param theirs the same in the theirs file
     * @param base   the ancestor's lines both sides changed, or null when unknown
     * @param shown  what stands for the conflict while it is unresolved: the ancestor's
     *               lines, from a diff3 section or from {@code base}; empty when there are
     *               none - both sides added different text at the same place, or the
     *               ancestor could not be worked out - and the result shows a placeholder
     */
    record Located(Range ours, Range theirs, Range base, List<String> shown) {
        Located {
            shown = List.copyOf(shown);
        }
    }

    /** The agreed text before, between and after the conflicts: one more than there are. */
    private final List<List<String>> commons;
    private final List<Conflict> conflicts;

    private ConflictDocument(List<List<String>> commons, List<Conflict> conflicts) {
        this.commons = commons;
        this.conflicts = conflicts;
    }

    List<Conflict> conflicts() {
        return conflicts;
    }

    /** The agreed lines before conflict {@code index}; {@code conflicts().size()} for the tail. */
    List<String> common(int index) {
        return commons.get(index);
    }

    /**
     * Takes {@code text} apart. Carriage returns are folded away first, as the editor
     * holds text; a CRLF file's convention is restored by whoever writes it.
     *
     * <p>A conflict needs all of its markers in order. A half-written one - an opening
     * marker whose separator is missing - is not a conflict, and stays in the agreed text
     * as it is. A second opening marker restarts: the first was never closed.
     */
    static ConflictDocument parse(String text) {
        String[] lines = folded(text).split("\n", -1);
        List<List<String>> commons = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<String> common = new ArrayList<>();
        // The candidate conflict being read: its lines so far, and where its markers fell.
        List<String> pending = new ArrayList<>();
        int base = -1;
        int split = -1;
        boolean open = false;
        for (String line : lines) {
            if (line.startsWith(OURS_MARKER)) {
                common.addAll(pending);
                pending.clear();
                pending.add(line);
                open = true;
                base = -1;
                split = -1;
            } else if (!open) {
                common.add(line);
            } else if (split < 0 && base < 0 && line.startsWith(BASE_MARKER)) {
                base = pending.size();
                pending.add(line);
            } else if (split < 0 && line.equals(SPLIT_MARKER)) {
                split = pending.size();
                pending.add(line);
            } else if (split >= 0 && line.startsWith(THEIRS_MARKER)) {
                int oursEnd = base >= 0 ? base : split;
                conflicts.add(new Conflict(pending.get(0),
                        pending.subList(1, oursEnd),
                        base >= 0 ? pending.get(base) : null,
                        base >= 0 ? pending.subList(base + 1, split) : null,
                        pending.get(split),
                        pending.subList(split + 1, pending.size()),
                        line));
                commons.add(common);
                common = new ArrayList<>();
                pending.clear();
                open = false;
            } else {
                pending.add(line);
            }
        }
        common.addAll(pending);
        commons.add(common);
        return new ConflictDocument(commons, List.copyOf(conflicts));
    }

    /** The file this was parsed from, carriage returns aside. */
    String text() {
        List<List<String>> markers = new ArrayList<>();
        for (Conflict conflict : conflicts) {
            markers.add(conflict.markerLines());
        }
        return String.join("\n", interleave(markers, null));
    }

    /**
     * The agreed text with {@code regions.get(i)} standing in for conflict {@code i}.
     *
     * @param starts filled with the line each region starts on, when not null
     */
    List<String> interleave(List<List<String>> regions, List<Integer> starts) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < conflicts.size(); i++) {
            lines.addAll(commons.get(i));
            if (starts != null) {
                starts.add(lines.size());
            }
            lines.addAll(regions.get(i));
        }
        lines.addAll(commons.get(conflicts.size()));
        return lines;
    }

    // ------------------------------------------------------------------
    // Locating the conflicts in the three versions
    // ------------------------------------------------------------------

    /**
     * Finds each conflict's lines in the three versions of the file, and the ancestor's
     * lines that stand for it while it is undecided.
     *
     * <p>Each side's view of the file - the agreed text with that side's lines in every
     * conflict - is matched against that side's own version, which places the conflict's
     * lines there; that side's changes from the ancestor then say which of the ancestor's
     * lines they replaced. The ancestor's part of the conflict is what either side
     * replaced. This is how diff3 finds the base section git writes with
     * {@code merge.conflictStyle=diff3}, worked from the conflict as it appears in the
     * file rather than from a fresh three-way merge. That matters: git trims lines both
     * sides agree on from the edges of a conflict, and a re-merge (JGit's
     * {@code MergeAlgorithm}) does not, so its conflicts and git's do not always line up
     * to be matched.
     *
     * @return one entry per conflict, in order
     */
    List<Located> locate(String oursText, String theirsText, String baseText) {
        List<String> ours = lines(oursText);
        List<String> theirs = lines(theirsText);
        List<String> base = lines(baseText);
        List<Range> inOurs = placeSide(ours, true);
        List<Range> inTheirs = placeSide(theirs, false);
        EditList baseToOurs = diff(base, ours);
        EditList baseToTheirs = diff(base, theirs);
        List<Located> located = new ArrayList<>();
        for (int i = 0; i < conflicts.size(); i++) {
            Conflict conflict = conflicts.get(i);
            Range o = inOurs.get(i);
            Range t = inTheirs.get(i);
            Range b = null;
            if (o != null && t != null) {
                Range fromOurs = toBase(baseToOurs, o);
                Range fromTheirs = toBase(baseToTheirs, t);
                b = new Range(Math.min(fromOurs.start(), fromTheirs.start()),
                        Math.max(fromOurs.end(), fromTheirs.end()));
            }
            List<String> shown;
            if (conflict.base() != null) {
                // Git wrote the ancestor's lines itself; nothing to work out.
                shown = conflict.base();
            } else if (b != null && b.end() <= base.size()) {
                shown = base.subList(b.start(), b.end());
            } else {
                shown = List.of();
            }
            located.add(new Located(o, t, b, shown));
        }
        return located;
    }

    /**
     * Where each conflict's lines for one side are in that side's file, or null for a
     * conflict whose lines are not there verbatim.
     *
     * <p>The side's view of the merged file differs from the side's file by the other
     * side's clean changes, which is why this is a diff rather than an offset.
     */
    private List<Range> placeSide(List<String> sideFile, boolean oursSide) {
        List<List<String>> chosen = new ArrayList<>();
        for (Conflict conflict : conflicts) {
            chosen.add(oursSide ? conflict.ours() : conflict.theirs());
        }
        List<Integer> starts = new ArrayList<>();
        List<String> view = interleave(chosen, starts);
        EditList viewToSide = diff(view, sideFile);
        List<Range> ranges = new ArrayList<>();
        for (int i = 0; i < conflicts.size(); i++) {
            int start = starts.get(i);
            ranges.add(verbatim(viewToSide, start, start + chosen.get(i).size(), sideFile.size()));
        }
        return ranges;
    }

    /**
     * Lines {@code [from, to)} of A in B's numbering, when the diff leaves every one of
     * them unchanged; null when any of them was changed. An empty range maps to the
     * position it falls at, before any lines B inserted there.
     */
    static Range verbatim(EditList edits, int from, int to, int sizeB) {
        int delta = 0;
        for (Edit edit : edits) {
            boolean before = from == to
                    ? edit.getEndA() < from || (edit.getEndA() == from && edit.getLengthA() > 0)
                    : edit.getEndA() <= from;
            if (before) {
                delta += edit.getLengthB() - edit.getLengthA();
                continue;
            }
            if (from == to) {
                // At or inside a change: where B's lines for it begin.
                if (edit.getBeginA() <= from) {
                    return new Range(edit.getBeginB(), edit.getBeginB());
                }
                break;
            }
            if (edit.getBeginA() < to) {
                // Changed, or interrupted by lines B inserted between them.
                return null;
            }
            break;
        }
        return new Range(Math.min(from + delta, sizeB), Math.min(to + delta, sizeB));
    }

    /**
     * The ancestor's lines that a run of one side's lines stands for: unchanged lines map
     * across, changed ones bring the whole stretch of the ancestor they replaced, and an
     * empty run where that side deleted lines brings the lines it deleted.
     *
     * @param baseToSide the side's changes from the ancestor (A = base, B = side)
     */
    static Range toBase(EditList baseToSide, Range side) {
        int delta = 0;
        if (side.length() == 0) {
            int at = side.start();
            for (Edit edit : baseToSide) {
                if (edit.getLengthB() == 0 && edit.getBeginB() == at) {
                    // This side deleted lines right here.
                    return new Range(edit.getBeginA(), edit.getEndA());
                }
                if (edit.getEndB() <= at) {
                    delta += edit.getLengthB() - edit.getLengthA();
                } else if (edit.getBeginB() < at) {
                    // Inside lines this side replaced: the lines they replaced.
                    return new Range(edit.getBeginA(), edit.getEndA());
                } else {
                    break;
                }
            }
            return new Range(at - delta, at - delta);
        }
        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;
        int next = 0;
        for (int line = side.start(); line < side.end(); line++) {
            while (next < baseToSide.size() && baseToSide.get(next).getEndB() <= line) {
                Edit passed = baseToSide.get(next++);
                delta += passed.getLengthB() - passed.getLengthA();
            }
            Edit edit = next < baseToSide.size() ? baseToSide.get(next) : null;
            if (edit != null && edit.getBeginB() <= line) {
                start = Math.min(start, edit.getBeginA());
                end = Math.max(end, edit.getEndA());
            } else {
                start = Math.min(start, line - delta);
                end = Math.max(end, line - delta + 1);
            }
        }
        return new Range(start, Math.max(start, end));
    }

    /** A text's lines as the editor holds them: CRs folded, split on LF, the last kept. */
    static List<String> lines(String text) {
        return List.of(folded(text).split("\n", -1));
    }

    static String folded(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Where two runs of lines differ, as JGit's histogram diff sees it. */
    static EditList diff(List<String> a, List<String> b) {
        return DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(LINES, new Lines(a), new Lines(b));
    }

    /** Lines of text as a sequence JGit can diff without encoding them to bytes first. */
    private static final class Lines extends Sequence {
        final List<String> lines;

        Lines(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public int size() {
            return lines.size();
        }
    }

    private static final SequenceComparator<Lines> LINES = new SequenceComparator<>() {
        @Override
        public boolean equals(Lines a, int ai, Lines b, int bi) {
            return a.lines.get(ai).equals(b.lines.get(bi));
        }

        @Override
        public int hash(Lines seq, int ptr) {
            return seq.lines.get(ptr).hashCode();
        }
    };
}
