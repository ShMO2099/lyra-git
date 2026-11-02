package com.jfx.git.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses unified diff text into hunks.
 *
 * <p>Kept separate from {@link DiffViewer} so the line-number bookkeeping can be
 * tested without starting a JavaFX toolkit.
 */
public final class DiffParser {

    /**
     * A single diff line. {@code kind} is one of {@code ' '}, {@code '+'} or {@code '-'}.
     *
     * @param cr whether the line ended in a CR before its LF. {@code text} never holds
     *           it - the viewer has no use for a stray CR - but it is content: a CRLF line
     *           and an LF line are different lines, and a change from one to the other is a
     *           real change. Anything that writes a line back into a file uses
     *           {@link #content()}, which puts it back.
     */
    public record Line(char kind, String text, Integer oldLine, Integer newLine, boolean cr) {

        /** A line with an LF ending, or one whose ending is not known. */
        public Line(char kind, String text, Integer oldLine, Integer newLine) {
            this(kind, text, oldLine, newLine, false);
        }

        /** The line as the file holds it, CR included; see {@link #cr()}. */
        public String content() {
            return cr ? text + "\r" : text;
        }

        public boolean isAdd() {
            return kind == '+';
        }

        public boolean isDelete() {
            return kind == '-';
        }

        /**
         * Whether this is git's {@code \\ No newline at end of file} note rather than a line of the file.
         *
         * <p>It is parked in the line list with {@code kind == ' '} so the viewer can show
         * it where git prints it, but it is <em>not content</em>: it describes the line
         * above. Anything that reconstructs the file's text - {@link Hunk#oldLines()},
         * {@link Hunk#newLines()}, the blocks {@code HunkSelection} builds - has to leave
         * it out, or it goes looking for a line the file has never contained.
         *
         * <p>The line numbers are what makes this safe. A file line that genuinely starts
         * with a backslash reaches the parser as {@code " \\foo"} - space-prefixed like any
         * context line - and comes out with both numbers set, so the text alone would
         * misidentify it. Only the marker has neither number.
         */
        public boolean isNoNewlineMarker() {
            return kind == ' ' && oldLine == null && newLine == null && text.startsWith("\\");
        }
    }

    /** One {@code @@ ... @@} block of a unified diff. */
    public record Hunk(String header,
                       int oldStart, int oldCount,
                       int newStart, int newCount,
                       List<Line> lines) {

        /** Content of the "before" side: context plus deleted lines. */
        public List<String> oldLines() {
            return side(l -> !l.isAdd());
        }

        /** Content of the "after" side: context plus added lines. */
        public List<String> newLines() {
            return side(l -> !l.isDelete());
        }

        private List<String> side(java.util.function.Predicate<Line> mine) {
            return lines.stream()
                    .filter(l -> mine.test(l) && !l.isNoNewlineMarker())
                    .map(Line::text)
                    .toList();
        }

        /**
         * Whether the lines parsed match the counts the header declared.
         *
         * <p>Git's own output always does. A hunk that does not was cut short, or had
         * something that is not part of it read into it - which is how the three
         * {@code diff --git} sections of an unmerged path used to become one hunk whose
         * "deletions" were the next section's headers. Such a hunk must never be applied:
         * its blocks describe no real file, and an empty "before" side places the rest at
         * the top of the file.
         */
        public boolean isConsistent() {
            return oldLines().size() == oldCount && newLines().size() == newCount;
        }

        /**
         * Whether the "before" side ends with a newline, or {@code null} when this hunk
         * says nothing about it.
         */
        public Boolean oldEndsWithNewline() {
            return endsWithNewline(l -> !l.isAdd());
        }

        /** Whether the "after" side ends with a newline, or {@code null} - see above. */
        public Boolean newEndsWithNewline() {
            return endsWithNewline(l -> !l.isDelete());
        }

        /**
         * What the no-newline markers say about one side's last line.
         *
         * <p>A marker anywhere in the hunk means the hunk reaches the end of the file, so
         * both sides can be answered for. With no marker at all the answer is {@code null}
         * rather than "yes": the hunk may simply end mid-file, and a caller that assumed
         * "yes" would append a newline to the middle of a file it never touched. Callers
         * read {@code null} as "leave the file's own trailing newline alone", which is
         * right either way - a hunk that does reach the end without a marker has a newline
         * there already.
         *
         * <p>The marker describes the line directly above it, which is why this looks at
         * the last line belonging to the side in question and then at what follows it.
         */
        private Boolean endsWithNewline(java.util.function.Predicate<Line> mine) {
            if (lines.stream().noneMatch(Line::isNoNewlineMarker)) {
                return null;
            }
            for (int i = lines.size() - 1; i >= 0; i--) {
                Line line = lines.get(i);
                if (line.isNoNewlineMarker() || !mine.test(line)) {
                    continue;
                }
                return !(i + 1 < lines.size() && lines.get(i + 1).isNoNewlineMarker());
            }
            return null;
        }
    }

    /** What happened to a file, as the diff's preamble reports it. */
    public enum Change { MODIFIED, ADDED, DELETED, RENAMED, COPIED, MODE_CHANGED }

    /**
     * The header lines above the first {@code @@}.
     *
     * <p>A rename, a chmod or a binary file produces a diff with no hunks at all. Read as
     * hunks alone that is indistinguishable from "nothing changed", which is why those
     * cases look broken in a viewer that only parses hunks - the preamble is the only
     * place the real change is recorded.
     *
     * @param binary        Git declined to diff the content
     * @param submoduleFrom old commit of a submodule pointer, or {@code null}
     * @param combined      a combined diff ({@code diff --cc}, {@code @@@} headers) - an
     *                      unmerged path compared against more than one parent. Its lines
     *                      describe no single file, so nothing may be patched from them
     */
    public record Summary(Change change,
                          String oldPath,
                          String newPath,
                          String oldMode,
                          String newMode,
                          boolean binary,
                          String submoduleFrom,
                          String submoduleTo,
                          boolean combined) {

        public Summary(Change change, String oldPath, String newPath, String oldMode,
                       String newMode, boolean binary, String submoduleFrom, String submoduleTo) {
            this(change, oldPath, newPath, oldMode, newMode, binary, submoduleFrom, submoduleTo, false);
        }

        /** Whether this is a submodule pointer rather than a file's content. */
        public boolean isSubmodule() {
            return submoduleFrom != null || submoduleTo != null;
        }

        /** True when the change is entirely in the preamble, with no content to show. */
        public boolean isMetadataOnly() {
            return change == Change.RENAMED || change == Change.COPIED
                    || change == Change.MODE_CHANGED;
        }
    }

    private DiffParser() {
    }

    /** Reads the header lines of a single-file diff. */
    public static Summary summarize(String diffText) {
        Change change = Change.MODIFIED;
        String oldPath = null;
        String newPath = null;
        String oldMode = null;
        String newMode = null;
        boolean binary = false;
        String submoduleFrom = null;
        String submoduleTo = null;
        boolean combined = false;
        boolean gitlink = false;
        boolean inGitlinkHunk = false;

        if (diffText == null) {
            return new Summary(change, null, null, null, null, false, null, null);
        }

        // Line by line from the front, not split: the preamble is a few lines at the top and
        // the loop stops at the first hunk, but split() cut the whole diff into lines first
        // - every line of a 20,000-row diff copied to read five.
        int at = 0;
        int length = diffText.length();
        while (at <= length) {
            int newline = diffText.indexOf('\n', at);
            int end = newline < 0 ? length : newline;
            String line = diffText.substring(at, end);
            at = end + 1;
            // A CRLF diff's CRs are content to the hunks, but never part of a path or mode.
            String raw = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (raw.startsWith("@@@")) {
                combined = true;
                break;
            }
            if (inGitlinkHunk || (raw.startsWith("@@") && gitlink)) {
                // A submodule's "content" is one line per side naming a commit, and git
                // prints it as a hunk - so for a gitlink the ids are read from there.
                inGitlinkHunk = true;
                if (raw.startsWith("-Subproject commit ")) {
                    submoduleFrom = raw.substring("-Subproject commit ".length()).trim();
                } else if (raw.startsWith("+Subproject commit ")) {
                    submoduleTo = raw.substring("+Subproject commit ".length()).trim();
                }
                continue;
            }
            if (raw.startsWith("@@")) {
                break; // the preamble ends where the first hunk begins
            }
            if (raw.startsWith("index ") && raw.endsWith(" 160000")
                    || raw.endsWith("mode 160000")) {
                gitlink = true; // mode 160000: this path is a submodule pointer
            }
            if (raw.startsWith("diff --cc ") || raw.startsWith("diff --combined ")) {
                combined = true;
            } else if (raw.startsWith("rename from ")) {
                change = Change.RENAMED;
                oldPath = raw.substring("rename from ".length());
            } else if (raw.startsWith("rename to ")) {
                change = Change.RENAMED;
                newPath = raw.substring("rename to ".length());
            } else if (raw.startsWith("copy from ")) {
                change = Change.COPIED;
                oldPath = raw.substring("copy from ".length());
            } else if (raw.startsWith("copy to ")) {
                change = Change.COPIED;
                newPath = raw.substring("copy to ".length());
            } else if (raw.startsWith("new file mode ")) {
                change = Change.ADDED;
                newMode = raw.substring("new file mode ".length()).trim();
            } else if (raw.startsWith("deleted file mode ")) {
                change = Change.DELETED;
                oldMode = raw.substring("deleted file mode ".length()).trim();
            } else if (raw.startsWith("old mode ")) {
                oldMode = raw.substring("old mode ".length()).trim();
            } else if (raw.startsWith("new mode ")) {
                newMode = raw.substring("new mode ".length()).trim();
            } else if (raw.startsWith("Binary files ") || raw.startsWith("GIT binary patch")) {
                binary = true;
            } else if (raw.startsWith("Subproject commit ")) {
                String commit = raw.substring("Subproject commit ".length()).trim();
                if (submoduleFrom == null) {
                    submoduleFrom = commit;
                } else {
                    submoduleTo = commit;
                }
            }
        }

        // A chmod with no rename and no content change is only a mode change.
        if (change == Change.MODIFIED && oldMode != null && newMode != null
                && !oldMode.equals(newMode)) {
            change = Change.MODE_CHANGED;
        }
        return new Summary(change, oldPath, newPath, oldMode, newMode, binary,
                submoduleFrom, submoduleTo, combined);
    }

    /**
     * How many {@code diff } sections the text holds.
     *
     * <p>A diff of one path has one. JGit's working-tree diff of an unmerged path has
     * three - one per conflict stage - and none of them is the file on disk against the
     * index; hunk actions built from any of them would patch the wrong content.
     */
    public static int sectionCount(String diffText) {
        if (diffText == null || diffText.isEmpty()) {
            return 0;
        }
        int count = diffText.startsWith("diff ") ? 1 : 0;
        int at = 0;
        while ((at = diffText.indexOf("\ndiff ", at)) >= 0) {
            count++;
            at += 6;
        }
        return count;
    }

    /** A one-line description of a change that has no hunks to show. */
    public static String describe(Summary summary) {
        return switch (summary.change()) {
            case RENAMED -> "Renamed" + path(summary);
            case COPIED -> "Copied" + path(summary);
            case MODE_CHANGED -> "Mode changed from " + summary.oldMode() + " to " + summary.newMode();
            case ADDED -> "New file" + (summary.newMode() == null ? "" : " (mode " + summary.newMode() + ")");
            case DELETED -> "File deleted";
            case MODIFIED -> summary.binary() ? "Binary file - content not shown" : "No textual changes";
        };
    }

    private static String path(Summary summary) {
        if (summary.oldPath() == null || summary.newPath() == null) {
            return "";
        }
        return " from " + summary.oldPath() + " to " + summary.newPath();
    }

    /**
     * Parses unified diff text into hunks.
     *
     * <h2>Where a hunk ends</h2>
     * A hunk ends at the next {@code @@} header, at the next {@code diff } line, or once
     * the lines its header declared have all been read - whichever comes first. Reading on
     * until the next {@code @@} alone was what turned JGit's three-section diff of an
     * unmerged path into garbage: the second section's {@code diff --git} line became
     * context, its {@code --- a/x} a deletion and its {@code +++ b/x} an addition, and a
     * Discard hunk built from that prepended a whole copy of one side to the file.
     *
     * <p>Counting is also what makes a blank line inside a hunk readable. With
     * {@code diff.suppressBlankEmpty} git prints an empty context line with no leading
     * space at all, and only the counts say it belongs to the hunk rather than being the
     * end of the text.
     *
     * <h2>Line endings</h2>
     * A CR before a line's LF is taken off the text and recorded on the {@link Line}; see
     * {@link Line#cr()}. Text decoded with its CRs stripped simply records none.
     *
     * <h2>Combined diffs</h2>
     * {@code diff --cc} output has one marker column per parent and {@code @@@} headers.
     * Its lines are mapped onto the two-sided model for display - removed from some parent,
     * added relative to some parent, or common - with the old numbering following the
     * first parent. The summary marks such a diff {@link Summary#combined() combined}, and
     * nothing may be patched from it.
     */
    public static List<Hunk> parse(String diffText) {
        List<Hunk> hunks = new ArrayList<>();
        if (diffText == null || diffText.isEmpty()) {
            return hunks;
        }

        Header header = null;
        List<Line> lines = new ArrayList<>();
        int oldLn = 0;
        int newLn = 0;
        int oldLeft = 0;
        int newLeft = 0;

        String[] raws = diffText.split("\n", -1);
        for (int i = 0; i < raws.length; i++) {
            String raw = raws[i];
            boolean cr = raw.endsWith("\r");
            if (cr) {
                raw = raw.substring(0, raw.length() - 1);
            }

            if (raw.startsWith("@@")) {
                if (header != null) {
                    hunks.add(withLines(header.hunk(), lines));
                    lines = new ArrayList<>();
                }
                header = headerOf(raw);
                oldLn = header.hunk().oldStart();
                newLn = header.hunk().newStart();
                oldLeft = header.hunk().oldCount();
                newLeft = header.hunk().newCount();
                continue;
            }
            if (header == null) {
                continue; // file headers ahead of the first hunk
            }
            if (raw.startsWith("diff ")) {
                // The next file section: whatever follows until its own @@ is preamble.
                hunks.add(withLines(header.hunk(), lines));
                lines = new ArrayList<>();
                header = null;
                continue;
            }
            if (raw.startsWith("\\")) {
                // "\ No newline at end of file" follows the line it describes, which may
                // be the hunk's last - so it is read even once the counts are used up.
                lines.add(new Line(' ', raw, null, null));
                continue;
            }
            boolean bounded = header.valid();
            if (bounded && oldLeft <= 0 && newLeft <= 0) {
                continue; // past the hunk's declared end
            }
            if (raw.isEmpty() && (!bounded || i == raws.length - 1)) {
                continue; // the split's trailing element, not a line
            }

            if (header.parents() > 1) {
                int parents = header.parents();
                String prefix = raw.length() >= parents ? raw.substring(0, parents)
                        : (raw + " ".repeat(parents)).substring(0, parents);
                String text = raw.length() >= parents ? raw.substring(parents) : "";
                boolean removed = prefix.indexOf('-') >= 0;
                boolean added = !removed && prefix.indexOf('+') >= 0;
                // In the first parent: a removed line that parent had, or a result line
                // that parent did not lack.
                boolean inFirst = removed ? prefix.charAt(0) == '-' : prefix.charAt(0) != '+';
                if (removed) {
                    lines.add(new Line('-', text, inFirst ? oldLn : null, null, cr));
                } else if (added) {
                    lines.add(new Line('+', text, null, newLn, cr));
                } else {
                    lines.add(new Line(' ', text, oldLn, newLn, cr));
                }
                if (inFirst) {
                    oldLn++;
                    oldLeft--;
                }
                if (!removed) {
                    newLn++;
                    newLeft--;
                }
            } else if (raw.startsWith("+")) {
                lines.add(new Line('+', raw.substring(1), null, newLn++, cr));
                newLeft--;
            } else if (raw.startsWith("-")) {
                lines.add(new Line('-', raw.substring(1), oldLn++, null, cr));
                oldLeft--;
            } else {
                String text = raw.startsWith(" ") ? raw.substring(1) : raw;
                lines.add(new Line(' ', text, oldLn++, newLn++, cr));
                oldLeft--;
                newLeft--;
            }
        }
        if (header != null) {
            hunks.add(withLines(header.hunk(), lines));
        }
        return hunks;
    }

    /**
     * A parsed {@code @@} line.
     *
     * @param parents how many marker columns each line has: 1 for a unified diff, more
     *                for a combined one
     * @param valid   whether the ranges parsed; a malformed header gives zeroes, and its
     *                counts cannot be used to find where the hunk ends
     */
    private record Header(Hunk hunk, int parents, boolean valid) {}

    private static Hunk withLines(Hunk header, List<Line> lines) {
        return new Hunk(header.header(), header.oldStart(), header.oldCount(),
                header.newStart(), header.newCount(), List.copyOf(lines));
    }

    /** Parses {@code @@ -397,6 +397,11 @@}; an omitted count means 1. */
    static Hunk parseHeader(String raw) {
        return headerOf(raw).hunk();
    }

    /**
     * Parses a unified {@code @@ -a,b +c,d @@} or a combined {@code @@@ -a,b -c,d +e,f @@@}
     * header. For a combined one the "old" range is the first parent's; reading only the
     * two-{@code @} form used to give a combined header all zeroes.
     */
    private static Header headerOf(String raw) {
        int oldStart = 0;
        int oldCount = 0;
        int newStart = 0;
        int newCount = 0;
        int ats = 0;
        while (ats < raw.length() && raw.charAt(ats) == '@') {
            ats++;
        }
        int parents = Math.max(1, ats - 1);
        boolean valid = false;
        try {
            String fence = "@".repeat(ats);
            String spec = raw.substring(ats, raw.indexOf(fence, ats)).trim();
            boolean sawOld = false;
            boolean sawNew = false;
            for (String part : spec.split("\\s+")) {
                String[] nums = part.substring(1).split(",");
                int start = Integer.parseInt(nums[0]);
                int count = nums.length > 1 ? Integer.parseInt(nums[1]) : 1;
                if (part.charAt(0) == '-' && !sawOld) {
                    oldStart = start;
                    oldCount = count;
                    sawOld = true;
                } else if (part.charAt(0) == '+') {
                    newStart = start;
                    newCount = count;
                    sawNew = true;
                }
            }
            valid = sawOld && sawNew;
        } catch (Exception ignored) {
            // Malformed header: fall back to zeroes and still render the lines.
        }
        return new Header(new Hunk(raw, oldStart, oldCount, newStart, newCount, List.of()),
                parents, valid);
    }
}
