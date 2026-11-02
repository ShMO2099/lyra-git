package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.dialogs.ConflictDocument.Conflict;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The conflicts in the merge tool's result: where each one's lines are, what has been
 * decided for it, and the file that comes out.
 *
 * <h2>Tracked as positions, not as text styles</h2>
 * A conflict's lines are a run of paragraphs, {@link Slot}, moved along by every edit to
 * the result ({@link #afterEdit}). The obvious alternative - tagging each conflict's
 * paragraphs with a paragraph style and letting RichTextFX carry the tags through edits -
 * was tried on paper and dropped: a conflict resolved to nothing (one side deleted the
 * lines, and that is the side chosen) has no paragraph to carry a tag, so it would vanish
 * and could never be chosen again; RichTextFX's rich copy and paste carries the tags into
 * whatever conflict the lines are pasted into; and its undo history records every
 * re-colouring as an edit. A slot can be empty, an anchor between two lines.
 *
 * <h2>State comes from the text</h2>
 * What was decided ({@link Resolution}) is stored; whether it still holds is read off the
 * lines. A conflict whose lines are what its resolution says - the ancestor's lines, or
 * the ticked lines of each side - is in that state; anything else is {@link State#EDITED}:
 * resolved with whatever was typed there. Typing the same text back makes it what it was.
 */
final class ConflictRegions {

    private ConflictRegions() {
    }

    /** What a conflict's lines in the result amount to. */
    enum State {
        /** Nothing decided: the ancestor's lines, or a placeholder, stand in for it. */
        UNRESOLVED,
        /** Every line of ours, none of theirs. */
        OURS,
        THEIRS,
        /** Every line of both, ours first. */
        BOTH,
        /** Some lines of either side, picked one by one. */
        PICKED,
        /** Typed over: resolved with whatever is there. */
        EDITED;

        boolean resolved() {
            return this != UNRESOLVED;
        }
    }

    /**
     * What has been decided for one conflict: nothing yet, or which of each side's lines
     * to keep - indices into {@link Conflict#ours()} and {@link Conflict#theirs()}. Taking
     * a side that has no lines at all is a decision that keeps nothing.
     */
    record Resolution(boolean decided, Set<Integer> ours, Set<Integer> theirs) {

        static final Resolution UNDECIDED = new Resolution(false, Set.of(), Set.of());

        Resolution {
            ours = Set.copyOf(ours);
            theirs = Set.copyOf(theirs);
        }

        /** Use ours, Use theirs, Use both: every line of that side, and none of the other. */
        static Resolution choose(MergeToolDialog.Choice choice, Conflict conflict) {
            Set<Integer> ours = choice == MergeToolDialog.Choice.THEIRS ? Set.of() : all(conflict.ours().size());
            Set<Integer> theirs = choice == MergeToolDialog.Choice.OURS ? Set.of() : all(conflict.theirs().size());
            return new Resolution(true, ours, theirs);
        }

        /** Ticked lines; ticking nothing at all is undeciding. */
        static Resolution picked(Set<Integer> ours, Set<Integer> theirs) {
            return ours.isEmpty() && theirs.isEmpty() ? UNDECIDED : new Resolution(true, ours, theirs);
        }

        /** This with {@code line} of one side ticked or not. */
        Resolution toggled(boolean oursSide, int line) {
            Set<Integer> o = new TreeSet<>(decided ? ours : Set.of());
            Set<Integer> t = new TreeSet<>(decided ? theirs : Set.of());
            Set<Integer> side = oursSide ? o : t;
            if (!side.remove(line)) {
                side.add(line);
            }
            return picked(o, t);
        }

        static Set<Integer> all(int size) {
            Set<Integer> every = new TreeSet<>();
            for (int i = 0; i < size; i++) {
                every.add(i);
            }
            return every;
        }
    }

    /**
     * One conflict's lines in the result: {@code count} paragraphs from {@code start}. An
     * empty slot is an anchor just before paragraph {@code start}, where a conflict
     * resolved to nothing would put lines again.
     *
     * @param conflict the conflict's index in the parsed file
     */
    record Slot(int conflict, int start, int count, Resolution resolution) {
        int end() {
            return start + count;
        }

        Slot with(Resolution changed) {
            return new Slot(conflict, start, count, changed);
        }
    }

    /** What the result holds for a conflict while nothing is decided: never no lines. */
    static List<String> undecidedLines(List<String> shownBase) {
        return shownBase.isEmpty() ? List.of("") : shownBase;
    }

    /** The ticked lines of ours in order, then the ticked lines of theirs. */
    static List<String> compose(Conflict conflict, Resolution resolution) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < conflict.ours().size(); i++) {
            if (resolution.ours().contains(i)) lines.add(conflict.ours().get(i));
        }
        for (int i = 0; i < conflict.theirs().size(); i++) {
            if (resolution.theirs().contains(i)) lines.add(conflict.theirs().get(i));
        }
        return lines;
    }

    /** How many of {@link #compose}'s lines come from ours; the rest are theirs. */
    static int oursLinesIn(Conflict conflict, Resolution resolution) {
        int count = 0;
        for (int i = 0; i < conflict.ours().size(); i++) {
            if (resolution.ours().contains(i)) count++;
        }
        return count;
    }

    /** The lines a resolution puts in the result. */
    static List<String> expected(Conflict conflict, List<String> shownBase, Resolution resolution) {
        return resolution.decided() ? compose(conflict, resolution) : undecidedLines(shownBase);
    }

    /** What the lines a conflict has in the result amount to, given what was decided. */
    static State state(Conflict conflict, List<String> shownBase, Resolution resolution, List<String> current) {
        if (!current.equals(expected(conflict, shownBase, resolution))) {
            return State.EDITED;
        }
        if (!resolution.decided()) {
            return State.UNRESOLVED;
        }
        boolean allOurs = resolution.ours().size() == conflict.ours().size();
        boolean allTheirs = resolution.theirs().size() == conflict.theirs().size();
        if (allOurs && resolution.theirs().isEmpty()) return State.OURS;
        if (allTheirs && resolution.ours().isEmpty()) return State.THEIRS;
        if (allOurs && allTheirs) return State.BOTH;
        return State.PICKED;
    }

    /**
     * Where the slots are after one edit of the result: the text from column {@code c0}
     * of paragraph {@code p0} to somewhere in paragraph {@code p1} replaced by
     * {@code inserted}.
     *
     * <p>Every paragraph belongs to one conflict or to the agreed text, and an edit hands
     * its new paragraphs to the owners of the two it started and ended in: the lines it
     * starts with go to {@code p0}'s owner, and its last line goes to {@code p1}'s when
     * nothing typed is on it - it is then just the rest of that paragraph. So a line break
     * typed at the end of a conflict's last line, or at the start of its first, is a line
     * of the conflict; one typed at the start of the line after it is not; deleting a
     * conflict's lines with their line break leaves the next line where it was; and an
     * edit that swallows a conflict whole leaves it empty, an anchor where the edit ends.
     * An edit that keeps the number of lines - every replace-all, which never spans a
     * line break - moves nothing: each line stays whose it was.
     *
     * @param paragraphs how many paragraphs the result has after the edit
     */
    static List<Slot> afterEdit(List<Slot> slots, int p0, int c0, int p1, String inserted, int paragraphs) {
        int breaks = 0;
        for (int i = 0; i < inserted.length(); i++) {
            if (inserted.charAt(i) == '\n') breaks++;
        }
        if (breaks == p1 - p0) {
            return slots;
        }
        int delta = breaks - (p1 - p0);
        int last = p0 + breaks;
        // The first new paragraph that is the rest of p1 rather than text typed after p0.
        int firstOfP1 = breaks == 0
                ? (c0 == 0 && inserted.isEmpty() ? p0 : p0 + 1)
                : (inserted.endsWith("\n") ? last : last + 1);
        int ownerOfP0 = ownerOf(slots, p0);
        int ownerOfP1 = ownerOf(slots, p1);
        List<Slot> moved = new ArrayList<>(slots.size());
        int floor = 0;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            int start = slot.start();
            int end = slot.end();
            int newStart;
            int newCount;
            if (slot.count() == 0) {
                newCount = 0;
                newStart = start <= p0 ? start : start > p1 ? start + delta : firstOfP1;
            } else if (end <= p0) {
                newStart = start;
                newCount = slot.count();
            } else if (start > p1) {
                newStart = start + delta;
                newCount = slot.count();
            } else {
                int before = Math.max(0, Math.min(end, p0) - start);
                boolean ownsP0 = ownerOfP0 == i;
                boolean ownsP1 = ownerOfP1 == i;
                newCount = before
                        + (ownsP0 ? firstOfP1 - p0 : 0)
                        + (ownsP1 ? (last + 1 - firstOfP1) + (end - p1 - 1) : 0);
                newStart = before > 0 ? start : ownsP0 ? p0 : firstOfP1;
            }
            newStart = Math.max(floor, Math.min(newStart, paragraphs));
            newCount = Math.max(0, Math.min(newCount, paragraphs - newStart));
            moved.add(new Slot(slot.conflict(), newStart, newCount, slot.resolution()));
            floor = newStart + newCount;
        }
        return moved;
    }

    /** The slot paragraph {@code paragraph} is a line of, or -1 for the agreed text. */
    static int ownerOf(List<Slot> slots, int paragraph) {
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.count() > 0 && paragraph >= slot.start() && paragraph < slot.end()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The file the result stands for: the result's own lines, except that a conflict
     * nobody has decided goes back as git wrote it, markers and all, so a draft stays
     * conflicted for git and opens the same way next time.
     */
    static List<String> output(List<String> lines, List<Slot> slots, List<State> states, List<Conflict> conflicts) {
        List<String> out = new ArrayList<>(lines.size());
        int cursor = 0;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            out.addAll(lines.subList(Math.min(cursor, slot.start()), slot.start()));
            if (states.get(i) == State.UNRESOLVED) {
                out.addAll(conflicts.get(slot.conflict()).markerLines());
            } else {
                out.addAll(lines.subList(slot.start(), slot.end()));
            }
            cursor = slot.end();
        }
        out.addAll(lines.subList(Math.min(cursor, lines.size()), lines.size()));
        return out;
    }
}
