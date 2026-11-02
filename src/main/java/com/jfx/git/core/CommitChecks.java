package com.jfx.git.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks over what is staged for the two mistakes a commit cannot take back once it is
 * pushed: a secret in the content, and a file too large for the repository - and for a
 * third that is only ever an accident, a conflict left unresolved.
 *
 * <h2>Why before the commit</h2>
 * A token in a pushed commit is a token to rotate, whatever happens to the commit
 * afterwards; a 200 MB build artefact in history makes every clone pay for it for ever,
 * and hosts refuse the push outright past their limit. Both are easy to stage by
 * accident - {@code Stage all} with a {@code .env} beside the code - and both are cheap to
 * see before git writes the commit, so the composer asks first.
 *
 * <h2>What is looked at</h2>
 * <ul>
 *   <li>Secrets: only the lines the staged diff adds. A key already in history is not
 *       this commit's doing, and flagging it on every commit would teach the user to click
 *       past the warning.</li>
 *   <li>Files whose names say what they hold - {@code .env}, {@code *.pem},
 *       {@code id_rsa} - whatever their content.</li>
 *   <li>Large files: anything staged over the threshold that LFS's patterns do not
 *       already cover.</li>
 *   <li>Conflict markers: a {@code <<<<<<<}, {@code =======}, {@code >>>>>>>} block
 *       among the lines the staged diff adds. Staging a conflicted file is how git is
 *       told it is resolved, so a stage-everything in the middle of a merge used to mark
 *       every conflict resolved with its markers in it, and the merge commit recorded
 *       them. Always looked for in a commit that finishes a merge, rebase, cherry-pick or
 *       revert, whatever the options say - see {@link #run(GitBackend, Options)} - and
 *       otherwise alongside the secret check, which reads the same diffs.</li>
 * </ul>
 *
 * <p>Bounded, because it runs on every commit: content is read for at most
 * {@link #MAX_SCANNED_FILES} files, and a file over {@link #MAX_SCAN_BYTES} is checked by
 * name and size only. The names and sizes of every staged file are always checked; those
 * cost a stat, not a read.
 *
 * <p>Plain Java; the composer runs it on the git lane and shows the findings.
 */
public final class CommitChecks {

    /** Content past this is not read for secrets; the size check still sees the file. */
    public static final long MAX_SCAN_BYTES = 2L * 1024 * 1024;

    /** Files read for secrets per commit, at most. */
    public static final int MAX_SCANNED_FILES = 500;

    private static final long MEGABYTE = 1024L * 1024;

    /** What a finding is about. */
    public enum Kind { SECRET, LARGE_FILE, CONFLICT_MARKERS }

    /**
     * One thing worth a second look.
     *
     * @param what  for a secret, what it looks like ("a GitHub token"); for a large file,
     *              its size in words; for conflict markers, "conflict markers"
     * @param line  the line of the new file the secret, or the first {@code <<<<<<<}, is
     *              on, or 0 when not known
     * @param bytes the file's size, for a large file; 0 otherwise
     */
    public record Finding(Kind kind, String path, String what, int line, long bytes) {
    }

    /**
     * Which checks run, and where "large" starts.
     *
     * @param conflictMarkers whether the added lines are looked over for conflict markers
     */
    public record Options(boolean secrets, boolean largeFiles, long largeFileBytes, boolean conflictMarkers) {

        /** Without the conflict-marker check. */
        public Options(boolean secrets, boolean largeFiles, long largeFileBytes) {
            this(secrets, largeFiles, largeFileBytes, false);
        }

        /**
         * The checks as the user has set them. Conflict markers go with secrets: that
         * check reads every added line already, so looking for markers in them costs
         * nothing more, and a user who turned it off to save the reads is not made to pay
         * for them anyway on every commit.
         */
        public static Options fromPreferences() {
            boolean secrets = AppPreferences.checkSecretsBeforeCommit();
            return new Options(secrets,
                    AppPreferences.checkLargeFilesBeforeCommit(),
                    AppPreferences.largeFileThresholdMb() * MEGABYTE,
                    secrets);
        }

        /** These options with the conflict-marker check on. */
        public Options withConflictMarkers() {
            return conflictMarkers ? this : new Options(secrets, largeFiles, largeFileBytes, true);
        }

        public boolean any() {
            return secrets || largeFiles || conflictMarkers;
        }
    }

    /** What the checks read. A seam, so the rules can be tested without a repository. */
    public interface Source {
        /** Every staged path. */
        List<String> stagedFiles();

        /** The staged file's size in bytes, or -1 when it is being deleted. */
        long size(String path);

        /** The staged diff of {@code path}, as git prints it. */
        byte[] stagedDiff(String path);

        /** The patterns LFS tracks. */
        List<String> lfsPatterns();
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    private record SecretRule(String what, Pattern pattern) {
    }

    /**
     * Shapes that are secrets with next to no false positives: each has a prefix its
     * issuer chose so that scanners like this one can find it.
     */
    private static final List<SecretRule> SECRET_RULES = List.of(
            new SecretRule("an AWS access key", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new SecretRule("a GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}")),
            new SecretRule("a GitHub token", Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}")),
            new SecretRule("a GitLab token", Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}")),
            new SecretRule("a Slack token", Pattern.compile("\\bxox[bpas]-[A-Za-z0-9-]{10,}")),
            new SecretRule("a private key", Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")),
            new SecretRule("a Google API key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}")));

    /** Lower-cased file names that are secrets by what they are, not what they say. */
    private static final List<String> SECRET_FILE_NAMES = List.of(
            ".env", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "oauth-secrets.properties");

    private static final List<String> SECRET_FILE_SUFFIXES = List.of(".pem", ".p12", ".pfx");

    /** {@code .env.example} and friends are the documented, empty version - the safe one. */
    private static final List<String> SAFE_ENV_SUFFIXES = List.of(".example", ".sample", ".template", ".dist");

    private CommitChecks() {
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    /**
     * Runs the checks on {@code git}'s index. On the git lane: it reads the index and the
     * work tree.
     *
     * <p>A commit that finishes a stopped merge, rebase, cherry-pick or revert is looked
     * over for conflict markers whatever {@code options} say. That is the commit they end
     * up in, and the one that is hardest to take back: a merge commit with a broken file
     * in it is history the moment it is pushed.
     */
    public static List<Finding> run(GitBackend git, Options options) {
        if (options == null) return new ArrayList<>();
        boolean finishingOperation = git.getOperationState().canContinue();
        return run(sourceOf(git), finishingOperation ? options.withConflictMarkers() : options);
    }

    /** Runs the checks on {@code source}. */
    public static List<Finding> run(Source source, Options options) {
        List<Finding> findings = new ArrayList<>();
        if (options == null || !options.any()) return findings;
        List<String> staged = source.stagedFiles();
        if (staged.isEmpty()) return findings;
        List<String> lfs = options.largeFiles() ? source.lfsPatterns() : List.of();

        int scanned = 0;
        for (String path : staged) {
            long size = source.size(path);
            if (size < 0) continue; // Being deleted: taking it out of the repository is the fix, not the problem.

            String byName = options.secrets() ? secretFileName(path) : null;
            if (byName != null) {
                findings.add(new Finding(Kind.SECRET, path, byName, 0, 0));
            }
            // A file named like a secret is one whatever it says, so its content is read
            // only if markers are being looked for.
            boolean secretsInContent = options.secrets() && byName == null;
            if ((secretsInContent || options.conflictMarkers())
                    && size <= MAX_SCAN_BYTES && scanned < MAX_SCANNED_FILES) {
                scanned++;
                byte[] diff = source.stagedDiff(path);
                Finding secret = secretsInContent ? secretInDiff(path, diff) : null;
                if (secret != null) findings.add(secret);
                Finding markers = options.conflictMarkers() ? conflictMarkersInDiff(path, diff) : null;
                if (markers != null) findings.add(markers);
            }
            if (options.largeFiles() && size > options.largeFileBytes() && !coveredByLfs(path, lfs)) {
                findings.add(new Finding(Kind.LARGE_FILE, path, describeSize(size), 0, size));
            }
        }
        return findings;
    }

    /** The real index and work tree, as a {@link Source}. */
    static Source sourceOf(GitBackend git) {
        File workTree = git.getWorkTree() != null ? git.getWorkTree() : git.getDirectory();
        return new Source() {
            @Override
            public List<String> stagedFiles() {
                return git.getStagedFiles();
            }

            @Override
            public long size(String path) {
                // The work tree's copy: the index's own blob size is not something every
                // engine can answer, and the two differ only when the file was edited
                // again after staging - by an amount that does not decide "too large".
                File file = new File(workTree, path);
                return file.isFile() ? file.length() : -1;
            }

            @Override
            public byte[] stagedDiff(String path) {
                return git.getStagedDiffBytes(path, false);
            }

            @Override
            public List<String> lfsPatterns() {
                return git.lfsTrackedPatterns();
            }
        };
    }

    // ------------------------------------------------------------------
    // The individual rules, for tests
    // ------------------------------------------------------------------

    /** What {@code line} looks like it holds, or {@code null} when it looks like nothing. */
    static String secretIn(String line) {
        if (line == null || line.length() < 8) return null;
        for (SecretRule rule : SECRET_RULES) {
            if (rule.pattern().matcher(line).find()) return rule.what();
        }
        return null;
    }

    /** Why {@code path}'s name alone makes it a secret, or {@code null}. */
    static String secretFileName(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(".env") || lower.startsWith(".env.")) {
            for (String safe : SAFE_ENV_SUFFIXES) {
                if (lower.endsWith(safe)) return null;
            }
            return "an environment file";
        }
        if (SECRET_FILE_NAMES.contains(lower)) {
            return lower.startsWith("id_") ? "an SSH private key" : "a credentials file";
        }
        for (String suffix : SECRET_FILE_SUFFIXES) {
            if (lower.endsWith(suffix)) return "a key or certificate file";
        }
        return null;
    }

    /** The first secret on a line {@code diff} adds, or {@code null}. */
    static Finding secretInDiff(String path, byte[] diff) {
        if (diff == null || diff.length == 0) return null;
        int length = (int) Math.min(diff.length, MAX_SCAN_BYTES);
        String text = new String(diff, 0, length, StandardCharsets.UTF_8);
        int newLine = 0;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("@@")) {
                newLine = hunkStart(line) - 1;
            } else if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            } else if (line.startsWith("+")) {
                newLine++;
                String what = secretIn(line.substring(1));
                if (what != null) return new Finding(Kind.SECRET, path, what, Math.max(newLine, 0), 0);
            } else if (line.startsWith(" ")) {
                newLine++;
            }
        }
        return null;
    }

    /**
     * A conflict block among the lines {@code diff} adds, or {@code null}: an added
     * {@code <<<<<<< }, then an added {@code =======}, then an added {@code >>>>>>> },
     * each at the start of its line - the test the merge editor applies to a file before
     * it is marked resolved ({@link ExternalTools#hasConflictMarkers}). A {@code =======}
     * alone is a Markdown heading's underline, and a marker block that was already in
     * the file before this commit is not this commit's doing.
     */
    static Finding conflictMarkersInDiff(String path, byte[] diff) {
        if (diff == null || diff.length == 0) return null;
        int length = (int) Math.min(diff.length, MAX_SCAN_BYTES);
        // ISO-8859-1 maps every byte to a char, so the ASCII markers are found whatever
        // the file's real encoding is.
        String text = new String(diff, 0, length, StandardCharsets.ISO_8859_1);
        int newLine = 0;
        int opener = 0;
        boolean divider = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("@@")) {
                newLine = hunkStart(line) - 1;
            } else if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            } else if (line.startsWith("+")) {
                newLine++;
                String added = line.substring(1);
                if (added.startsWith("<<<<<<< ")) {
                    if (opener == 0) opener = Math.max(newLine, 1);
                } else if (opener > 0 && added.startsWith("=======")) {
                    divider = true;
                } else if (divider && added.startsWith(">>>>>>> ")) {
                    return new Finding(Kind.CONFLICT_MARKERS, path, "conflict markers", opener, 0);
                }
            } else if (line.startsWith(" ")) {
                newLine++;
            }
        }
        return null;
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)");

    /** The first new-file line number a hunk header names, or 0. */
    private static int hunkStart(String header) {
        Matcher m = HUNK.matcher(header);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Whether one of LFS's {@code .gitattributes} patterns covers {@code path}. */
    static boolean coveredByLfs(String path, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) continue;
            try {
                // JGit's own matcher, so "covered" means what git means by it: a pattern
                // without a slash matches the name at any depth, one with a slash is
                // anchored, and ** spans folders.
                if (new org.eclipse.jgit.attributes.AttributesRule(pattern, "filter=lfs").isMatch(path, false)) {
                    return true;
                }
            } catch (RuntimeException unusable) {
                // A pattern JGit cannot read covers nothing.
            }
        }
        return false;
    }

    /** {@code 64 MB}, {@code 1.2 GB}: a size as a person reads it. */
    public static String describeSize(long bytes) {
        double mb = bytes / (double) MEGABYTE;
        if (mb >= 1024) return String.format(Locale.ROOT, "%.1f GB", mb / 1024);
        if (mb >= 10) return String.format(Locale.ROOT, "%d MB", Math.round(mb));
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }
}
