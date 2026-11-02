package com.jfx.git.core;

import com.jfx.git.core.HistoryOps.FileChange;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Pure parsers of git's output for the native engine: no runner, no repository, so
 * the {@code -z} and separator edge cases can be exercised with hand-built strings.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliParsers {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private CliParsers() {
    }

    /**
     * One letter for a porcelain {@code XY} pair.
     *
     * <p>The index side wins when both are set: a file added to the index and then edited
     * again is still an addition, and calling it a modification loses the fact that it is
     * new to the repository.
     */
    static char letterOf(char x, char y) {
        char code = x != '.' ? x : y;
        return switch (code) {
            case 'A', 'M', 'D', 'R', 'C', 'T', 'U' -> code;
            default -> 'M';
        };
    }

    static int findNul(byte[] bytes, int start) {
        for (int i = start; i < bytes.length; i++) {
            if (bytes[i] == 0) return i;
        }
        return -1;
    }

    /** The query's message, identity and content criteria as {@code git log} options. */
    static List<String> searchLimitingArguments(com.jfx.git.model.CommitSearch query) {
        List<String> args = new ArrayList<>(List.of("--fixed-strings", "--regexp-ignore-case", "--all-match"));
        for (String term : query.messageTerms()) args.add("--grep=" + term);
        if (query.author() != null) args.add("--author=" + query.author());
        if (query.committer() != null) args.add("--committer=" + query.committer());
        if (query.content() != null) {
            args.add("-M");
            args.add("-G" + extendedRegexLiteral(query.content()));
        }
        return args;
    }

    /**
     * {@code text} as a POSIX extended regular expression that matches only itself -
     * what {@code -G} is given, since pickaxe always reads its argument as a regex and
     * a search for {@code a.b} must not find {@code axb}.
     */
    static String extendedRegexLiteral(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("\\.[]()*+?{}|^$".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern CLOBBERED_TAG = java.util.regex.Pattern.compile(
            "!\\s+\\[rejected\\]\\s+(\\S+)\\s+->\\s+\\S+\\s+\\(would clobber existing tag\\)");

    /** Success, success with tags that could not be updated, or failure. */
    static GitResult classifyFetch(NativeGitRunner.Result res) {
        return classifyFetch(res, "all remotes");
    }

    /** {@link #classifyFetch(NativeGitRunner.Result)}, naming what was fetched. */
    static GitResult classifyFetch(NativeGitRunner.Result res, String what) {
        if (res.isOk()) return GitResult.ok("Fetched " + what);
        String err = res.stderr();
        List<String> clobbered = new ArrayList<>();
        var m = CLOBBERED_TAG.matcher(err);
        while (m.find()) clobbered.add(m.group(1));
        boolean otherRejections = err.lines().anyMatch(line -> line.contains("[rejected]")
                && !line.contains("would clobber existing tag"));
        if (!clobbered.isEmpty() && !otherRejections && !err.contains("fatal:")) {
            return GitResult.ok("Fetched " + what + ". These tags were not updated, because a"
                    + " local tag of the same name points elsewhere: " + String.join(", ", clobbered));
        }
        return GitResult.failed(err);
    }

    /**
     * {@code config -z --list} output as key to value; the last value wins, as it does for
     * {@code config --get}. Section and variable names come lower-cased, a subsection as
     * written. Empty when git failed.
     */
    static Map<String, String> parseConfigList(NativeGitRunner.Result res) {
        Map<String, String> values = new HashMap<>();
        if (!res.isOk()) return values;
        for (String entry : res.stdout().split("\0")) {
            if (entry.isEmpty()) continue;
            int nl = entry.indexOf('\n');
            String key = nl < 0 ? entry : entry.substring(0, nl);
            values.put(key, nl < 0 ? "" : entry.substring(nl + 1));
        }
        return values;
    }

    /**
     * Record and field separators for {@code git log --format}.
     *
     * <p>An earlier format put every commit on one line and split fields on NUL, which
     * forced {@code %s} (subject only) — so commit bodies never reached the UI. ASCII RS
     * and US carry a multi-line {@code %B} safely: neither can appear in a hash, name,
     * timestamp or ref list, and a commit message containing one is not something git
     * itself round-trips either.
     */
    /** Splits git's line-oriented output whichever line ending it used. */
    static final String LINE_BREAK = "\\r?\\n";

    static final String RECORD_SEP = String.valueOf((char) 0x1E);
    static final String FIELD_SEP = String.valueOf((char) 0x1F);

    /**
     * Parses {@link CliHistory#COMMIT_FORMAT} output into commits, skipping malformed records.
     *
     * <p>Trailing fields are read defensively rather than by requiring a field count.
     * A root commit has an empty {@code %P}, so its record has separators side by side
     * that any trimming removes — and treating a short record as malformed dropped the
     * first commit of every repository from the graph.
     */
    static List<CommitInfo> parseCommits(String output) {
        return parseCommits(output, null, false);
    }

    /**
     * The same, decorating each commit from {@code decorations} (commit hash to refs), or
     * with no refs when that is {@code null}.
     *
     * @param graph whether this is {@link CliHistory#GRAPH_FORMAT} output, whose commits are
     *              {@linkplain CommitInfo#summary summaries} rather than whole messages
     */
    static List<CommitInfo> parseCommits(String output, Map<String, List<RefInfo>> decorations,
                                                 boolean graph) {
        List<CommitInfo> commits = new ArrayList<>();
        for (String record : output.split(RECORD_SEP, -1)) {
            if (record.isBlank()) continue;
            CommitInfo commit = parseRecord(record.split(FIELD_SEP, -1), decorations, graph);
            if (commit != null) commits.add(commit);
        }
        return commits;
    }

    /** One record's fields as a commit, or {@code null} when it cannot be one. */
    private static CommitInfo parseRecord(String[] f, Map<String, List<RefInfo>> decorations,
                                          boolean graph) {
        // hash, message, author, email and date are the fields a commit cannot lack.
        if (f.length < 5) return null;

        long epoch;
        try {
            epoch = Long.parseLong(field(f, 4).trim());
        } catch (NumberFormatException e) {
            // One unreadable timestamp should cost one row, not the whole page.
            LOGGER.fine("Skipping commit with unparseable date: " + field(f, 0));
            return null;
        }
        long committed = epoch;
        try {
            String ct = field(f, 6).trim();
            if (!ct.isEmpty()) committed = Long.parseLong(ct);
        } catch (NumberFormatException e) {
            // Ordering falls back to the author's clock rather than losing the row.
        }

        String hash = field(f, 0).trim();
        String parentList = field(f, 5).trim();
        List<String> parents = parentList.isEmpty() ? List.of() : List.of(parentList.split("\\s+"));
        List<RefInfo> refs = decorations == null ? List.of() : decorations.getOrDefault(hash, List.of());

        if (graph) {
            // Field 7 is the body preview, padded to its width by git; summary() trims it.
            return CommitInfo.summary(hash, field(f, 1), field(f, 7), field(f, 2), field(f, 3),
                    Instant.ofEpochSecond(epoch), Instant.ofEpochSecond(committed), parents, refs);
        }
        return new CommitInfo(hash, field(f, 1), field(f, 2), field(f, 3),
                Instant.ofEpochSecond(epoch), Instant.ofEpochSecond(committed), parents, refs);
    }

    /** Field {@code index}, or "" when the record ended before it. */
    private static String field(String[] fields, int index) {
        return index < fields.length ? fields[index] : "";
    }

    /**
     * Parses {@code git log --follow --name-status -z} in {@link CliHistory#FILE_HISTORY_FORMAT}.
     *
     * <p>Each record carries its {@code --name-status} entry after the last separator:
     * {@code M\0path\0}, or {@code R100\0old\0new\0} at the commit that renamed the file.
     * The renaming commit is recorded under its new name and everything older under the
     * old one; a merge, which {@code --follow} shows without an entry, keeps the path of
     * the commit before it.
     */
    static List<CommitInfo> parseFileHistory(String output, String path) {
        List<CommitInfo> commits = new ArrayList<>();
        String current = path;
        for (String record : output.split(RECORD_SEP, -1)) {
            if (record.isBlank()) continue;
            String[] f = record.split(FIELD_SEP, -1);
            CommitInfo commit = parseRecord(f, null, false);
            if (commit == null) continue;

            List<String> tokens = new ArrayList<>();
            for (String token : field(f, 7).split("\0")) {
                String t = token.strip();
                if (!t.isEmpty()) tokens.add(token.replace("\n", ""));
            }
            String here = current;
            if (tokens.size() >= 3 && (tokens.get(0).startsWith("R") || tokens.get(0).startsWith("C"))) {
                here = tokens.get(2);
                if (tokens.get(0).startsWith("R")) current = tokens.get(1);
            } else if (tokens.size() >= 2) {
                here = tokens.get(1);
                current = here;
            }
            commit.setHistoryPath(here);
            commits.add(commit);
        }
        return commits;
    }

    /** The time in {@code HEAD@{<epoch>}}, as {@code %gd} prints it under {@code --date=unix}. */
    static Instant reflogTime(String selector) {
        int open = selector.indexOf('{');
        int close = selector.lastIndexOf('}');
        if (open >= 0 && close > open) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(selector.substring(open + 1, close)));
            } catch (NumberFormatException e) {
                LOGGER.fine("Unreadable reflog time: " + selector);
            }
        }
        return Instant.EPOCH;
    }

    /** Reads the number following {@code key} in "[ahead 2, behind 1]". */
    static int parseTrack(String track, String key) {
        int at = track.indexOf(key);
        if (at < 0) return 0;
        int start = at + key.length();
        int end = start;
        while (end < track.length() && Character.isDigit(track.charAt(end))) end++;
        try {
            return Integer.parseInt(track.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Paths from a {@code -z} listing, exactly as git wrote them.
     *
     * <p>{@code -z} and no trimming: a line-based listing C-quotes a path containing a
     * quote or a newline, and trimming turned {@code " lead.txt"} into a different file -
     * either way the path handed back named nothing, and its diff came up empty.
     */
    static List<String> nulSeparated(String output) {
        List<String> paths = new ArrayList<>();
        for (String path : output.split("\0")) {
            if (!path.isEmpty()) paths.add(path);
        }
        return paths;
    }

    /**
     * Reads {@code --name-status -z}, joining each path to its line counts.
     *
     * <p>An ordinary record is {@code status\0path\0}; a rename or copy carries both paths,
     * {@code R100\0old\0new\0}, and the new one is the row while the old one rides along so
     * the diff can be taken across the move.
     */
    static List<FileChange> parseNameStatus(String stdout, Map<String, int[]> counts) {
        List<FileChange> changes = new ArrayList<>();
        String[] fields = stdout.split("\0");
        for (int i = 0; i + 1 < fields.length; ) {
            String status = fields[i];
            if (status.isEmpty()) { i++; continue; }
            char code = status.charAt(0);
            boolean twoPaths = code == 'R' || code == 'C';
            if (twoPaths && i + 2 >= fields.length) break;
            String oldPath = twoPaths ? fields[i + 1] : null;
            String path = twoPaths ? fields[i + 2] : fields[i + 1];
            i += twoPaths ? 3 : 2;

            int[] pair = counts.get(path);
            changes.add(new FileChange(path, code,
                    pair == null ? FileChange.UNKNOWN_COUNT : pair[0],
                    pair == null ? FileChange.UNKNOWN_COUNT : pair[1],
                    oldPath));
        }
        return changes;
    }

    /**
     * Added/deleted line counts by path, from git's NUL-separated {@code --numstat}.
     *
     * <p>The {@code -z} shape is not the obvious one and is worth stating: an ordinary
     * entry is {@code added\tdeleted\tpath\0}, with the path in the <em>same</em> NUL
     * record as the numbers. Only a rename splits them, as
     * {@code added\tdeleted\t\0oldPath\0newPath\0} — the trailing empty third field is the
     * signal that two path records follow.
     *
     * <p>A binary file reports {@code -} for both numbers and is left out entirely, so the
     * record built from it carries {@link FileChange#UNKNOWN_COUNT} and the row renders as
     * its status letter alone rather than as a false "+0 −0".
     */
    static Map<String, int[]> parseNumstat(String stdout) {
        Map<String, int[]> counts = new HashMap<>();
        String[] records = stdout.split("\0");
        for (int i = 0; i < records.length; ) {
            // Limit -1: the trailing empty field is the thing being tested for, and
            // split() drops it otherwise.
            String[] parts = records[i].split("\t", -1);
            if (parts.length < 3) {
                i++;
                continue;
            }

            String path;
            if (parts[2].isEmpty()) {
                if (i + 2 >= records.length) break;
                path = records[i + 2];   // the name it has now, not the one it had
                i += 3;
            } else {
                path = parts[2];
                i += 1;
            }

            try {
                counts.put(path, new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
            } catch (NumberFormatException e) {
                // "-\t-\tpath": binary, and deliberately left absent.
            }
        }
        return counts;
    }

    static int indexOf(byte[] bytes, byte wanted, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == wanted) return i;
        }
        return -1;
    }

    /**
     * A porcelain commit header: {@code <sha> <orig-line> <final-line> [<group-size>]}.
     *
     * <p>Matched strictly. The old test - "has a space at index 40" - also accepted a
     * {@code committer} or {@code filename} line that happened to have one there, and
     * read its first forty characters as a commit; it also never matched a SHA-256
     * repository's 64-character ids.
     */
    private static final java.util.regex.Pattern BLAME_HEADER =
            java.util.regex.Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64}) \\d+ \\d+( \\d+)?$");

    /** What one commit's header block said, kept for the groups that only cite its sha. */
    private record BlameCommit(String author, String summary, Instant when) {
    }

    /**
     * Parses {@code git blame --porcelain} output into per-line annotations.
     *
     * <p>Package-private and static so edge cases can be exercised directly with a
     * hand-built porcelain string, rather than needing a real git process to produce a
     * metadata line of exactly the wrong shape.
     *
     * <p>Every other porcelain line ({@code committer ...}, {@code filename ...},
     * {@code previous ...}) falls through to the header test, and real names and paths
     * produce lines of every length - which is why the header is recognised by
     * {@link #BLAME_HEADER}'s full shape rather than by where its first space falls. That
     * earlier test first threw on a 40-character metadata line and, once guarded, still
     * misread any metadata line with a space at index 40 as a commit.
     */
    static List<BlameLine> parseBlamePorcelain(String stdout) {
        List<BlameLine> lines = new ArrayList<>();
        String[] raw = stdout.split("\\r?\\n");

        // Porcelain describes each commit in full only the FIRST time it appears; every
        // later run of lines from that same commit is introduced by its sha alone, with
        // no author/summary/author-time block after it. Carrying the running values
        // forward therefore stamped whichever commit was most recently described in full
        // onto every revisit of an earlier one - so in a file whose history interleaves
        // two commits (the normal case: edit, someone else edits, you edit again) the
        // second and later runs showed the wrong author, subject and date entirely,
        // while their sha - and so the colour band keyed off it - stayed right, which is
        // what made it look like a rendering problem rather than a parsing one.
        Map<String, BlameCommit> describedCommits = new HashMap<>();

        String currentSha = "";
        String currentAuthor = "";
        String currentSummary = "";
        // An instant, not text: the view writes it in the user's format. See BlameLine.
        Instant currentDate = null;

        for (String l : raw) {
            if (l.startsWith("author ")) {
                currentAuthor = l.substring(7);
            } else if (l.startsWith("summary ")) {
                currentSummary = l.substring(8);
            } else if (l.startsWith("author-time ")) {
                try {
                    currentDate = Instant.ofEpochSecond(Long.parseLong(l.substring(12).trim()));
                } catch (Exception ignored) {}
            } else if (l.startsWith("\t")) {
                describedCommits.putIfAbsent(currentSha,
                        new BlameCommit(currentAuthor, currentSummary, currentDate));
                lines.add(new BlameLine(abbreviate(currentSha), currentAuthor, currentSummary,
                        currentDate, l.substring(1)));
            } else if (BLAME_HEADER.matcher(l).matches()) {
                currentSha = l.substring(0, l.indexOf(' '));
                BlameCommit known = describedCommits.get(currentSha);
                if (known != null) {
                    // A revisit, so no block follows: restore what this sha meant rather
                    // than letting the previous commit's details stand in for it.
                    currentAuthor = known.author();
                    currentSummary = known.summary();
                    currentDate = known.when();
                }
            }
        }
        return lines;
    }

    /** The short form shown in the gutter; the full sha keys {@code describedCommits}. */
    private static String abbreviate(String sha) {
        return sha.length() > 8 ? sha.substring(0, 8) : sha;
    }

    /** {@code N} from {@code stash@{N}}, or -1. */
    static int stashIndexOf(String selector) {
        String s = selector.trim();
        if (!s.startsWith("stash@{") || !s.endsWith("}")) return -1;
        try {
            return Integer.parseInt(s.substring("stash@{".length(), s.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * What {@code git status --porcelain=v2 --branch -z} lists.
     *
     * @param branch the branch from the {@code # branch.head} header, {@code ""} when HEAD
     *               is detached, or {@code null} when the header is missing - the caller
     *               reads it then
     */
    record StatusListing(List<String> staged, List<String> unstaged, Set<String> conflicts,
                         Map<String, Character> status, String branch) {
    }

    /** Reads {@code git status --porcelain=v2 --branch -z}; see {@link StatusListing}. */
    static StatusListing parseStatus(byte[] out) {
        List<String> staged = new ArrayList<>();
        List<String> unstaged = new ArrayList<>();
        Set<String> conflicts = new LinkedHashSet<>();
        // The porcelain record already carries the status letter; keeping it costs
        // nothing here and is what lets the staging list mark an added file as added
        // rather than showing every path as a modification.
        Map<String, Character> status = new HashMap<>();
        String branch = null;
        int i = 0;
        while (i < out.length) {
            byte type = out[i];
            int nextNul = findNul(out, i);
            if (nextNul < 0) break;

            String record = new String(out, i, nextNul - i, StandardCharsets.UTF_8);
            if (type == '#') {
                // # branch.head <name>, or "(detached)" when there is no branch - which
                // is blank, as getCurrentBranch() answers for a detached HEAD.
                if (record.startsWith("# branch.head ")) {
                    String head = record.substring("# branch.head ".length()).trim();
                    branch = "(detached)".equals(head) ? "" : head;
                }
                i = nextNul + 1;
                continue;
            }
            if (type == '1') {
                // 1 <xy> <sub> <mH> <mI> <mW> <hH> <hI> <path>
                String[] parts = record.split(" ", 9);
                if (parts.length >= 9) {
                    String xy = parts[1];
                    String path = parts[8];
                    char x = xy.charAt(0);
                    char y = xy.charAt(1);
                    if (x != '.') staged.add(path);
                    if (y != '.') unstaged.add(path);
                    status.put(path, letterOf(x, y));
                }
            } else if (type == '2') {
                // 2 <xy> <sub> <mH> <mI> <mW> <hH> <hI> <X><score> <path>\0<origPath>
                String[] parts = record.split(" ", 10);
                if (parts.length >= 10) {
                    String xy = parts[1];
                    String path = parts[9];
                    char x = xy.charAt(0);
                    char y = xy.charAt(1);
                    if (x != '.') staged.add(path);
                    if (y != '.') unstaged.add(path);
                    // Type 2 is a rename or copy whatever the XY says. The path here is
                    // the name the file has now, which is the one the staging row shows.
                    status.put(path, 'R');
                }
                // Skip the second NUL entry (origPath)
                i = nextNul + 1;
                nextNul = findNul(out, i);
                if (nextNul < 0) break;
            } else if (type == 'u') {
                // u <xy> <sub> <m1> <m2> <m3> <mW> <h1> <h2> <h3> <path>
                String[] parts = record.split(" ", 11);
                if (parts.length >= 11) {
                    String path = parts[10];
                    conflicts.add(path);
                    // A conflicted path still needs work in the working tree, and the
                    // staging panel reads that from the unstaged list. JGit reports it in
                    // both places; matching that keeps the panel's contents the same
                    // whichever engine filled it.
                    unstaged.add(path);
                }
            } else if (type == '?') {
                // ? <path>
                if (record.length() > 2) {
                    String path = record.substring(2);
                    unstaged.add(path);
                    // Untracked. Not yet added to the index, but "A" is what the user is
                    // about to make it, and it is the letter git itself shows.
                    status.put(path, 'A');
                }
            }
            i = nextNul + 1;
        }

        return new StatusListing(staged, unstaged, conflicts, status, branch);
    }

    /**
     * {@code git stash list -z --format=%gd%x1f%H%x1f%P%x1f%an%x1f%at%x1f%gs}: one entry
     * per NUL, the message last so a separator inside it stays inside it, and the index
     * read from {@code %gd} rather than counted.
     */
    static List<com.jfx.git.model.StashEntry> parseStashList(String stdout) {
        List<com.jfx.git.model.StashEntry> entries = new ArrayList<>();
        for (String record : stdout.split("\0")) {
            if (record.isBlank()) continue;
            String[] f = record.split(FIELD_SEP, 6);
            if (f.length < 6) continue;
            int index = stashIndexOf(f[0]);
            if (index < 0) continue;

            long epoch;
            try {
                epoch = Long.parseLong(f[4].trim());
            } catch (NumberFormatException e) {
                LOGGER.fine("Stash entry with an unreadable date: " + f[0]);
                epoch = 0;
            }
            String[] parents = f[2].trim().isEmpty() ? new String[0] : f[2].trim().split("\\s+");
            entries.add(new com.jfx.git.model.StashEntry(
                    index, f[1].trim(),
                    parents.length > 0 ? parents[0] : null,
                    f[5], f[3], Instant.ofEpochSecond(epoch)));
        }
        return entries;
    }

    /**
     * {@code git worktree list --porcelain}, main worktree first.
     *
     * <p>Records are blank-line separated; each carries {@code worktree <path>} plus
     * optional {@code bare}, {@code detached}, {@code branch <ref>} and {@code locked}.
     */
    static List<Worktrees.Worktree> parseWorktrees(String stdout) {
        List<Worktrees.Worktree> result = new ArrayList<>();
        File path = null;
        String branch = null;
        String head = null;
        boolean detached = false;
        boolean locked = false;

        String[] lines = stdout.split("\\r?\\n");
        for (int i = 0; i <= lines.length; i++) {
            boolean endOfRecord = i == lines.length || lines[i].isBlank();
            if (endOfRecord) {
                if (path != null) {
                    boolean main = result.isEmpty();
                    // A detached worktree is labelled by its commit, which the record
                    // already gives as "HEAD <sha>" - this was a rev-parse per worktree.
                    // Seven characters, as the JGit engine labels it.
                    String label = branch != null ? branch
                            : head != null && !head.isEmpty()
                                    ? head.substring(0, Math.min(7, head.length())) : "detached";
                    result.add(new Worktrees.Worktree(
                            main ? "(main)" : path.getName(), path, label, main, locked, detached));
                }
                path = null;
                branch = null;
                head = null;
                detached = false;
                locked = false;
                continue;
            }

            String line = lines[i];
            if (line.startsWith("worktree ")) {
                path = new File(line.substring("worktree ".length()).trim());
            } else if (line.startsWith("HEAD ")) {
                head = line.substring("HEAD ".length()).trim();
            } else if (line.startsWith("branch ")) {
                String ref = line.substring("branch ".length()).trim();
                branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
            } else if (line.equals("detached")) {
                detached = true;
            } else if (line.equals("locked") || line.startsWith("locked ")) {
                locked = true;
            }
        }
        return result;
    }

    /**
     * {@code git reflog -z --format=%H%x1f%gn%x1f%gd%x1f%gs} as at most {@code limit}
     * items, newest first; the output holds one entry more, the one the last item moved
     * from.
     */
    static List<ReflogItem> parseReflog(String stdout, int limit) {
        List<String[]> records = new ArrayList<>();
        for (String record : stdout.split("\0")) {
            if (record.isBlank()) continue;
            String[] f = record.split(FIELD_SEP, 4);
            if (f.length >= 4) records.add(f);
        }

        List<ReflogItem> items = new ArrayList<>();
        for (int i = 0; i < records.size() && i < limit; i++) {
            String[] f = records.get(i);
            String newHash = f[0].trim();
            // HEAD@{n} moved *from* whatever HEAD@{n+1} left it at.
            String oldHash = i + 1 < records.size() ? records.get(i + 1)[0].trim() : null;
            // The position is the entry's place in the log whatever its date says, so a
            // row with an unreadable time keeps its HEAD@{n} rather than shifting the rest.
            items.add(new ReflogItem(i, oldHash, newHash, f[3], f[1], reflogTime(f[2])));
        }
        return items;
    }

    /**
     * {@code for-each-ref --format=%(refname)\t%(upstream:track) refs/heads} as branch
     * name to {@code {ahead, behind}}, leaving out branches level with their upstream,
     * without one, or whose upstream is gone.
     */
    static Map<String, int[]> parseBranchTracking(String stdout) {
        Map<String, int[]> tracking = new HashMap<>();
        for (String line : stdout.split("\r?\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0 || !line.startsWith("refs/heads/")) continue;
            String branch = line.substring("refs/heads/".length(), tab);
            String track = line.substring(tab + 1).trim();
            if (track.isEmpty() || track.contains("gone")) continue;

            int ahead = parseTrack(track, "ahead ");
            int behind = parseTrack(track, "behind ");
            if (ahead > 0 || behind > 0) {
                tracking.put(branch, new int[]{ahead, behind});
            }
        }
        return tracking;
    }

    /**
     * The branches {@code for-each-ref --format=%(refname)\t%(upstream:track) refs/heads}
     * marks {@code [gone]}: an upstream is configured and its remote-tracking branch is
     * missing. Sorted.
     */
    static Set<String> parseGoneBranches(String stdout) {
        Set<String> gone = new java.util.TreeSet<>();
        for (String line : stdout.split("\r?\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0 || !line.startsWith("refs/heads/")) continue;
            if (line.substring(tab + 1).trim().equals("[gone]")) {
                gone.add(line.substring("refs/heads/".length(), tab));
            }
        }
        return gone;
    }

    /**
     * {@code git grep -n --null} output as matches, at most {@code limit} of them.
     *
     * <p>Each line is {@code path NUL number NUL text}; a search of a revision writes the
     * path as {@code <rev>:path}, and the prefix is cut off here. A carriage return
     * before the line feed belongs to the file's line ending, not to its text.
     */
    static List<com.jfx.git.model.GrepMatch> parseGrep(String stdout, String rev, int limit) {
        List<com.jfx.git.model.GrepMatch> matches = new ArrayList<>();
        String prefix = rev == null ? null : rev + ":";
        int start = 0;
        while (start < stdout.length() && matches.size() < limit) {
            int end = stdout.indexOf('\n', start);
            if (end < 0) end = stdout.length();
            String record = stdout.substring(start, end);
            start = end + 1;
            int first = record.indexOf('\0');
            int second = first < 0 ? -1 : record.indexOf('\0', first + 1);
            if (second < 0) continue;
            String path = record.substring(0, first);
            if (prefix != null && path.startsWith(prefix)) path = path.substring(prefix.length());
            int line;
            try {
                line = Integer.parseInt(record.substring(first + 1, second));
            } catch (NumberFormatException notALine) {
                continue;
            }
            String text = record.substring(second + 1);
            if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
            matches.add(new com.jfx.git.model.GrepMatch(path, line, text));
        }
        return matches;
    }

    /**
     * The paths whose attribute has {@code value} in {@code git check-attr -z} output.
     *
     * <p>-z separates path/attribute/value with NUL rather than ": " - the only
     * format that cannot be misread when a path itself contains a colon or space.
     */
    static Set<String> pathsWithAttribute(String stdout, String value) {
        String[] fields = stdout.split("\u0000", -1);
        Set<String> matching = new LinkedHashSet<>();
        for (int i = 0; i + 2 < fields.length; i += 3) {
            if (value.equals(fields[i + 2])) {
                matching.add(fields[i]);
            }
        }
        return matching;
    }

    /**
     * {@code git cat-file --batch} output for {@code count} names, as their blobs:
     * {@code null} for a name that is missing, ambiguous, or not a blob.
     *
     * @throws IllegalArgumentException when the output does not hold {@code count} answers
     */
    static byte[][] parseCatFileBatch(byte[] out, int count) {
        byte[][] blobs = new byte[count][];
        int at = 0;
        for (int index = 0; index < count; index++) {
            int eol = indexOf(out, (byte) '\n', at);
            if (eol < 0) throw new IllegalArgumentException("cat-file stopped after " + index + " answers");
            String header = new String(out, at, eol - at, StandardCharsets.UTF_8);
            at = eol + 1;
            // "<oid> <type> <size>", or "<name> missing" / "<name> ambiguous" - the name
            // may itself contain spaces, which is why the size is read from the end.
            if (header.endsWith(" missing") || header.endsWith(" ambiguous")) continue;
            int lastSpace = header.lastIndexOf(' ');
            int typeSpace = lastSpace < 0 ? -1 : header.lastIndexOf(' ', lastSpace - 1);
            if (typeSpace < 0) throw new IllegalArgumentException("unreadable cat-file header: " + header);
            String type = header.substring(typeSpace + 1, lastSpace);
            int size;
            try {
                size = Integer.parseInt(header.substring(lastSpace + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("unreadable cat-file size: " + header);
            }
            if (size < 0 || at + size > out.length) {
                throw new IllegalArgumentException("cat-file content shorter than its size: " + header);
            }
            if (type.equals("blob")) {
                blobs[index] = java.util.Arrays.copyOfRange(out, at, at + size);
            }
            at += size + 1;   // the content, and the newline --batch puts after it
        }
        return blobs;
    }

    /**
     * What the index holds for one path.
     *
     * @param exists   whether there is any entry for it
     * @param mode     the entry's mode, meaningful only when {@code exists}
     * @param unmerged whether the entries are conflict stages rather than one resolved entry
     */
    record IndexEntry(boolean exists, String mode, boolean unmerged) {}

    /**
     * {@code path}'s entry in {@code git ls-files --stage -z} output.
     *
     * <p>Matched on the exact path rather than trusting the pathspec: {@code ls-files} of
     * a directory name lists everything under it.
     */
    static IndexEntry parseIndexEntry(String stdout, String path) {
        boolean exists = false;
        boolean unmerged = false;
        String mode = "100644";
        // "100644 <sha> 0\t<path>\0" per entry
        for (String record : stdout.split("\0")) {
            int tab = record.indexOf('\t');
            if (tab < 0 || !record.substring(tab + 1).equals(path)) {
                continue;
            }
            String[] fields = record.substring(0, tab).split(" ");
            if (fields.length < 3) {
                continue;
            }
            exists = true;
            if (!fields[2].equals("0")) {
                unmerged = true;
            } else if (fields[0].matches("[0-7]{6}")) {
                mode = fields[0];
            }
        }
        return new IndexEntry(exists, mode, unmerged);
    }
}
