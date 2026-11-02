package com.jfx.git.ui;

import com.jfx.git.core.HunkEdit;

import java.util.Set;

/**
 * One hunk action a {@link LoadedDiff} offers: what it does, what its button says, and the
 * finished edit that does it.
 *
 * <p>Running it is the host's: asking first, the snapshot, the repository lane, and
 * reloading afterwards. Everything about <em>what</em> to write is settled here, from the
 * same loaded diff the hunk came from - the encoding, the charset its lines were decoded
 * with, each line's own ending, and what the diff's preamble says about the file.
 *
 * @param path      the file the action applies to
 * @param hunkIndex which hunk of the loaded diff
 * @param lines     the hunk lines it acts on, by index within the hunk; every changed line
 *                  for a whole-hunk action
 * @param wholeHunk whether it acts on the whole hunk rather than the selected lines
 * @param label     what its button says
 * @param tooltip   what its button explains on hover
 * @param edit      what to apply: to the index for {@link Type#STAGE} and
 *                  {@link Type#UNSTAGE}, to the working tree otherwise
 */
public record HunkAction(Type type, String path, int hunkIndex, Set<Integer> lines,
                         boolean wholeHunk, String label, String tooltip, HunkEdit edit) {

    /** What a hunk action does. */
    public enum Type {
        /** Into the index, from an unstaged diff. */
        STAGE,
        /** Out of the index, from a staged diff. */
        UNSTAGE,
        /** Thrown away from the working tree, from an unstaged diff. */
        DISCARD,
        /** A commit's change undone in the working tree, left as an uncommitted edit. */
        REVERT
    }

    public HunkAction {
        lines = Set.copyOf(lines);
    }

    /** How many changed lines it acts on. */
    public int lineCount() {
        return lines.size();
    }

    /** "1 line" or "3 lines": how status messages count what an action touched. */
    public String linesPhrase() {
        return lines.size() == 1 ? "1 line" : lines.size() + " lines";
    }
}
