package com.jfx.git.ui;

import com.jfx.git.ui.DiffParser.Hunk;
import com.jfx.git.ui.DiffParser.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a choice of lines within a hunk into the two blocks a patch needs: what to find
 * in the index copy of the file, and what to put in its place.
 *
 * <p>The rule that makes partial staging work is that an <em>unselected</em> changed line
 * is not skipped - it is treated as context. A deletion you did not choose must stay in
 * the result; an addition you did not choose must stay out of it. Get that wrong and the
 * unselected half of the hunk silently disappears from the index.
 *
 * <p>Staging and unstaging are the same operation from opposite ends. An unstaged diff
 * compares the index against the working tree, so the index holds the hunk's "before"
 * side. A staged diff compares HEAD against the index, so the index holds the "after"
 * side. Which one to search for is the only thing that differs.
 */
public final class HunkSelection {

    /**
     * The block to find in the index copy, what replaces it, and whether the result should
     * end with a newline.
     *
     * @param endsWithNewline what the hunk's no-newline markers say the patched file should
     *                        end with, or {@code null} to leave the file's own trailing
     *                        newline as it is - see
     *                        {@link DiffParser.Hunk#newEndsWithNewline()}
     */
    public record Patch(int hintLine, List<String> search, List<String> replacement,
                        Boolean endsWithNewline) {

        public Patch(int hintLine, List<String> search, List<String> replacement) {
            this(hintLine, search, replacement, null);
        }

        /**
         * This patch as the backend takes it, read and written as {@code encoding}.
         *
         * <p>The encoding is the viewer's current choice rather than a guess: a file the
         * user opened explicitly as, say, UTF-16LE is one the guesser might read
         * differently, and staging a hunk rewrites the whole blob from the decoded text -
         * so every untouched line would come back in the wrong charset.
         */
        public com.jfx.git.core.HunkEdit toEdit(com.jfx.git.core.TextEncoding encoding) {
            return new com.jfx.git.core.HunkEdit(hintLine, search, replacement,
                    endsWithNewline, encoding);
        }

        /**
         * As {@link #toEdit(com.jfx.git.core.TextEncoding)}, noting the charset the diff's
         * lines were decoded with, so the backend can read the file the same way when its
         * own guess would differ - see {@code HunkPatcher.apply(byte[], HunkEdit)}.
         */
        public com.jfx.git.core.HunkEdit toEdit(com.jfx.git.core.TextEncoding encoding,
                                                java.nio.charset.Charset lineCharset) {
            return toEdit(encoding).withLineCharset(lineCharset);
        }

        /**
         * This patch as an edit of the <em>index</em>, told what the diff's preamble said
         * about the file as a whole.
         *
         * <p>Two things only the preamble knows. Staging every line of a deleted file, or
         * unstaging every line of an added one, empties the file - and the right result
         * is the path leaving the index, not an empty file staged in its place (which is
         * what used to happen, as a lone newline). And a path with no index entry yet
         * needs a mode: an executable file added with {@code new file mode 100755} must
         * not be staged as 100644.
         *
         * @param summary the preamble of the diff this patch was built from
         * @param staging true when staging from an unstaged diff, false when unstaging
         *                from a staged one
         */
        public com.jfx.git.core.HunkEdit toIndexEdit(com.jfx.git.core.TextEncoding encoding,
                                                     java.nio.charset.Charset lineCharset,
                                                     DiffParser.Summary summary,
                                                     boolean staging) {
            com.jfx.git.core.HunkEdit edit = toEdit(encoding, lineCharset);
            if (summary == null) {
                return edit;
            }
            // The side the index is moving towards: the working tree when staging, HEAD
            // when unstaging. If that side has no file, an emptied result is a removal.
            boolean removes = staging
                    ? summary.change() == DiffParser.Change.DELETED
                    : summary.change() == DiffParser.Change.ADDED;
            String mode = staging ? summary.newMode() : summary.oldMode();
            return edit.withWholeFile(mode, removes);
        }

        /**
         * True when applying this would change nothing.
         *
         * <p>Identical blocks are not sufficient: removing a file's final newline produces
         * exactly that, with the whole of the change carried by {@code endsWithNewline}.
         * So any patch with something to say about the trailing newline counts as real
         * work - at worst the patcher rewrites identical content.
         */
        public boolean isNoop() {
            return endsWithNewline == null && search.equals(replacement);
        }
    }

    private HunkSelection() {
    }

    /** Stages the whole hunk. */
    public static Patch stage(Hunk hunk) {
        return stage(hunk, allChangedLines(hunk));
    }

    /**
     * Stages only {@code selected}, by index within {@code hunk.lines()}.
     *
     * <p>Searches for the hunk's "before" side, which is what an unstaged diff says the
     * index currently holds.
     */
    public static Patch stage(Hunk hunk, Set<Integer> selected) {
        List<String> search = new ArrayList<>();
        List<String> replacement = new ArrayList<>();

        List<Line> lines = hunk.lines();
        Boolean endsWithNewline = null;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.isNoNewlineMarker()) {
                continue; // a note about the line above, not a line of the file
            }
            boolean chosen = selected.contains(i);
            int before = replacement.size();
            if (line.isAdd()) {
                // Additions are absent from the index, so they are never searched for.
                if (chosen) {
                    replacement.add(line.content());
                }
            } else if (line.isDelete()) {
                search.add(line.content());
                if (!chosen) {
                    replacement.add(line.content()); // left alone: still context
                }
            } else {
                search.add(line.content());
                replacement.add(line.content());
            }
            if (replacement.size() > before) {
                endsWithNewline = newlineAfter(lines, i);
            }
        }
        return new Patch(hunk.oldStart(), search, replacement, endsWithNewline);
    }

    /** Unstages the whole hunk. */
    public static Patch unstage(Hunk hunk) {
        return unstage(hunk, allChangedLines(hunk));
    }

    /**
     * Unstages only {@code selected}.
     *
     * <p>Searches for the hunk's "after" side, which is what a staged diff says the index
     * currently holds, and puts the "before" side of the chosen lines back.
     */
    public static Patch unstage(Hunk hunk, Set<Integer> selected) {
        List<String> search = new ArrayList<>();
        List<String> replacement = new ArrayList<>();

        List<Line> lines = hunk.lines();
        Boolean endsWithNewline = null;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.isNoNewlineMarker()) {
                continue;
            }
            boolean chosen = selected.contains(i);
            int before = replacement.size();
            if (line.isDelete()) {
                // Deletions are absent from the index; undoing one puts it back.
                if (chosen) {
                    replacement.add(line.content());
                }
            } else if (line.isAdd()) {
                search.add(line.content());
                if (!chosen) {
                    replacement.add(line.content()); // left staged
                }
            } else {
                search.add(line.content());
                replacement.add(line.content());
            }
            if (replacement.size() > before) {
                endsWithNewline = newlineAfter(lines, i);
            }
        }
        return new Patch(hunk.newStart(), search, replacement, endsWithNewline);
    }

    /** Reverts the whole hunk in the working tree. */
    public static Patch discard(Hunk hunk) {
        return discard(hunk, allChangedLines(hunk));
    }

    /**
     * Reverts only {@code selected} in the working tree - "Discard lines".
     *
     * <p>The working tree holds the "after" side of an unstaged diff, exactly as the
     * index holds the "after" side of a staged one, so this is {@link #unstage} aimed at a
     * different file: find the after side, put the before side of the chosen lines back,
     * and treat every unchosen change as context so it survives untouched.
     */
    public static Patch discard(Hunk hunk, Set<Integer> selected) {
        return unstage(hunk, selected);
    }

    /**
     * Whether the line at {@code index} is followed by a newline in the file, or
     * {@code null} when the hunk says nothing about it.
     *
     * <p>Tracked for whichever line ends up last in the replacement block, because that is
     * the line that becomes the file's last line. Only a hunk that carries a marker
     * somewhere knows it reaches the end of the file at all; without one this stays
     * {@code null} and the patcher keeps the file's existing trailing newline.
     */
    private static Boolean newlineAfter(List<Line> lines, int index) {
        if (lines.stream().noneMatch(Line::isNoNewlineMarker)) {
            return null;
        }
        return !(index + 1 < lines.size() && lines.get(index + 1).isNoNewlineMarker());
    }

    /** Every addition and deletion in the hunk, by index. */
    public static Set<Integer> allChangedLines(Hunk hunk) {
        Set<Integer> all = new java.util.HashSet<>();
        List<Line> lines = hunk.lines();
        for (int i = 0; i < lines.size(); i++) {
            if ((lines.get(i).isAdd() || lines.get(i).isDelete())
                    && !lines.get(i).isNoNewlineMarker()) {
                all.add(i);
            }
        }
        return all;
    }
}
