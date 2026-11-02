package com.jfx.git.model;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Which multi-step git operation the repository is part-way through, if any.
 *
 * <h2>Why this exists</h2>
 * Merge used to be the only in-progress state the application knew about. A rebase, a
 * cherry-pick or a revert that stopped on a conflict got the merge's banner - "resolve,
 * then commit" and an "Abort merge" button that ran {@code git merge --abort}, which
 * fails outside a merge, or on JGit a hard reset that wiped the tree and left the rebase
 * directory behind. A rebase stopped at an {@code edit} step, or at a commit that became
 * empty, had no conflicts and so showed no banner at all, with no way to continue it.
 *
 * <p>Each of these has its own continue, skip and abort, and they are not
 * interchangeable. This record names the state so the UI can offer the right three
 * buttons, and every engine answers {@code continueOperation()}, {@code skipOperation()}
 * and {@code abortOperation()} for whichever state it reports.
 *
 * <h2>How it is detected</h2>
 * From the files git itself writes, so every engine with a {@code .git} directory to look
 * at gives the same answer - see {@link #detect(File)}. A rebase is checked before
 * {@code MERGE_HEAD} and {@code CHERRY_PICK_HEAD} because a rebase writes those too while
 * one of its own steps is stopped, and the thing to continue is then the rebase, not the
 * step.
 *
 * @param kind        what is in progress
 * @param interactive a rebase driven by a todo list ({@code rebase -i}, or any rebase on
 *                    the merge backend that git marks interactive)
 * @param stoppedAt   the commit the operation stopped on, when git recorded one - for
 *                    saying where, not for acting on
 * @param editStop    a rebase paused at an {@code edit} step on purpose rather than by a
 *                    conflict: the tree is clean and the user is expected to amend
 * @param incoming    what is being brought in, as git recorded it: the merged branch (from
 *                    {@code MERGE_MSG}, else {@code MERGE_HEAD}'s hash), the branch being
 *                    rebased, the commit being cherry-picked or reverted; null when git
 *                    recorded nothing. A full hash when no name is known
 * @param target      where it lands: the branch HEAD is on for a merge, cherry-pick, revert
 *                    or {@code git am} (HEAD's hash when detached), and for a rebase the
 *                    commit it replays onto, by the name it was given when git or JGit kept
 *                    one; null when unknown
 * @param step        a rebase or {@code am} session's current step, counting from 1; 0
 *                    when unknown
 * @param steps       how many steps it has in all; 0 when unknown
 */
public record OperationState(Kind kind, boolean interactive, String stoppedAt, boolean editStop,
                             String incoming, String target, int step, int steps) {

    /** Without the sides and the progress, for a state that does not name them. */
    public OperationState(Kind kind, boolean interactive, String stoppedAt, boolean editStop) {
        this(kind, interactive, stoppedAt, editStop, null, null, 0, 0);
    }

    /** The operations git can leave half-done. */
    public enum Kind {
        NONE("", false, false, false),
        MERGING("Merging", true, false, true),
        /** Rebasing with the merge backend ({@code .git/rebase-merge}). */
        REBASING("Rebasing", true, true, true),
        /** Rebasing with the older apply backend ({@code .git/rebase-apply}). */
        REBASING_APPLY("Rebasing", true, true, true),
        CHERRY_PICKING("Cherry-picking", true, true, true),
        REVERTING("Reverting", true, true, true),
        /** {@code git am}, which also lives in {@code .git/rebase-apply}. */
        APPLYING_MAILBOX("Applying patches", true, true, true),
        /** Not a stopped operation - bisect has its own banner - but still one in progress. */
        BISECTING("Bisecting", false, false, true);

        private final String label;
        private final boolean canContinue;
        private final boolean canSkip;
        private final boolean canAbort;

        Kind(String label, boolean canContinue, boolean canSkip, boolean canAbort) {
            this.label = label;
            this.canContinue = canContinue;
            this.canSkip = canSkip;
            this.canAbort = canAbort;
        }

        public String label() { return label; }
    }

    private static final OperationState NONE_STATE = new OperationState(Kind.NONE, false, null, false);

    public static OperationState none() {
        return NONE_STATE;
    }

    public static OperationState of(Kind kind) {
        return new OperationState(kind, false, null, false);
    }

    public boolean inProgress() {
        return kind != Kind.NONE;
    }

    /** Whether "Continue" means anything here - merge's is the finishing commit. */
    public boolean canContinue() { return kind.canContinue; }

    /** Whether the current step can be dropped and the operation carried on without it. */
    public boolean canSkip() { return kind.canSkip; }

    public boolean canAbort() { return kind.canAbort; }

    /** Any kind of rebase, whichever backend or flavour. */
    public boolean isRebase() {
        return kind == Kind.REBASING || kind == Kind.REBASING_APPLY;
    }

    /** Whether {@code text} is a commit hash rather than a name: 40 or 64 hex digits. */
    public static boolean isHash(String text) {
        if (text == null || (text.length() != 40 && text.length() != 64)) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** "Interactive rebase", "Cherry-picking" - what the banner calls this state. */
    public String label() {
        if (isRebase() && interactive) return "Interactive rebase";
        return kind.label();
    }

    /**
     * Reads the state from the marker files in {@code gitDir}.
     *
     * <p>The order follows what git's own {@code wt-status} and {@code sequencer} consult,
     * adjusted for what the user can act on: a rebase first, since a rebase that stopped
     * on one of its own picks or merges also leaves {@code CHERRY_PICK_HEAD} or
     * {@code MERGE_HEAD} behind and it is the rebase that continues; then merge,
     * cherry-pick and revert; then a multi-commit cherry-pick or revert whose sequencer
     * is still open after its current step finished; bisect last, since it is not a
     * stopped operation at all.
     *
     * <p>Bisect is recognised from git's own {@code BISECT_START} or from this
     * application's {@code lyra-bisect} file, whichever wrote it.
     */
    public static OperationState detect(File gitDir) {
        if (gitDir == null) return NONE_STATE;

        File rebaseMerge = new File(gitDir, "rebase-merge");
        if (rebaseMerge.isDirectory()) {
            boolean interactive = new File(rebaseMerge, "interactive").exists();
            String stopped = firstLine(new File(rebaseMerge, "stopped-sha"));
            if (stopped == null) stopped = firstLine(new File(gitDir, "REBASE_HEAD"));
            boolean edit = new File(rebaseMerge, "amend").exists();
            int[] progress = progress(rebaseMerge, "msgnum", "end");
            if (progress[1] == 0) progress = todoProgress(rebaseMerge);
            return new OperationState(Kind.REBASING, interactive, stopped, edit,
                    rebasedBranch(rebaseMerge), ontoName(gitDir, rebaseMerge), progress[0], progress[1]);
        }
        File rebaseApply = new File(gitDir, "rebase-apply");
        if (rebaseApply.isDirectory()) {
            int[] progress = progress(rebaseApply, "next", "last");
            if (new File(rebaseApply, "applying").exists()) {
                return new OperationState(Kind.APPLYING_MAILBOX, false, null, false,
                        null, headName(gitDir), progress[0], progress[1]);
            }
            return new OperationState(Kind.REBASING_APPLY, false,
                    firstLine(new File(gitDir, "REBASE_HEAD")), false,
                    rebasedBranch(rebaseApply), ontoName(gitDir, rebaseApply), progress[0], progress[1]);
        }
        if (new File(gitDir, "MERGE_HEAD").exists()) {
            String mergeHead = firstLine(new File(gitDir, "MERGE_HEAD"));
            String merged = mergedName(firstLine(new File(gitDir, "MERGE_MSG")));
            return new OperationState(Kind.MERGING, false, mergeHead, false,
                    merged != null ? merged : mergeHead, headName(gitDir), 0, 0);
        }
        if (new File(gitDir, "CHERRY_PICK_HEAD").exists()) {
            String picked = firstLine(new File(gitDir, "CHERRY_PICK_HEAD"));
            return new OperationState(Kind.CHERRY_PICKING, false, picked, false,
                    picked, headName(gitDir), 0, 0);
        }
        if (new File(gitDir, "REVERT_HEAD").exists()) {
            String reverted = firstLine(new File(gitDir, "REVERT_HEAD"));
            return new OperationState(Kind.REVERTING, false, reverted, false,
                    reverted, headName(gitDir), 0, 0);
        }
        // A range cherry-pick or revert between steps: no *_HEAD, but the sequencer still
        // holds the rest of the plan and "--continue" / "--abort" still apply to it.
        String next = firstLine(new File(gitDir, "sequencer/todo"));
        if (next != null) {
            String verb = next.strip();
            if (verb.startsWith("revert") || verb.startsWith("r ")) {
                return new OperationState(Kind.REVERTING, false, null, false, null, headName(gitDir), 0, 0);
            }
            if (verb.startsWith("pick") || verb.startsWith("p ")) {
                return new OperationState(Kind.CHERRY_PICKING, false, null, false, null, headName(gitDir), 0, 0);
            }
        }
        if (new File(gitDir, "BISECT_START").exists() || new File(gitDir, "lyra-bisect").exists()
                // The same file from before the application was renamed.
                || new File(gitDir, "jfxgit-bisect").exists()) {
            return of(Kind.BISECTING);
        }
        return NONE_STATE;
    }

    /**
     * The branch a merge's prepared message names: {@code Merge branch 'feature'},
     * {@code Merge remote-tracking branch 'origin/x'}, {@code Merge tag 'v1'} - what git
     * and JGit both write when no message was given. Null for a message of the user's own,
     * whose merge is then named by its hash.
     */
    public static String mergedName(String firstLine) {
        if (firstLine == null) return null;
        java.util.regex.Matcher m = MERGED.matcher(firstLine.strip());
        return m.find() ? m.group(1) : null;
    }

    private static final java.util.regex.Pattern MERGED = java.util.regex.Pattern.compile(
            "^Merge (?:remote-tracking )?(?:branch(?:es)?|tag|commit) '([^']+)'");

    /** The branch HEAD names, or HEAD's hash when it is detached; null when unreadable. */
    private static String headName(File gitDir) {
        String head = firstLine(new File(gitDir, "HEAD"));
        if (head == null) return null;
        if (head.startsWith("ref:")) return shortRef(head.substring(4).strip());
        return head;
    }

    /** The branch a rebase is rewriting, from its {@code head-name}; else the commit it started from. */
    private static String rebasedBranch(File stateDir) {
        String headName = firstLine(new File(stateDir, "head-name"));
        if (headName != null && headName.startsWith("refs/")) return shortRef(headName);
        return firstLine(new File(stateDir, "orig-head"));
    }

    /**
     * What a rebase replays onto, by the name it was started with when one was kept.
     *
     * <p>Git records only the commit ({@code onto}). JGit also writes {@code onto_name};
     * git's own record of the name is the reflog line it writes as the rebase starts -
     * {@code rebase (start): checkout main} - and that line is believed only when it moved
     * HEAD to the recorded commit, so an older rebase's line is never read as this one's.
     */
    private static String ontoName(File gitDir, File stateDir) {
        String onto = firstLine(new File(stateDir, "onto"));
        String named = firstLine(new File(stateDir, "onto_name"));
        if (named != null) return shortRef(named);
        String fromReflog = reflogOntoName(new File(gitDir, "logs/HEAD"), onto);
        return fromReflog != null ? fromReflog : onto;
    }

    /** The name in the last {@code (start): checkout <name>} reflog line that moved HEAD to {@code onto}. */
    private static String reflogOntoName(File reflog, String onto) {
        if (onto == null || !reflog.isFile()) return null;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(reflog, "r")) {
            // The tail is enough: the rebase's own line is among the last few it wrote.
            long length = file.length();
            int read = (int) Math.min(length, 64 * 1024);
            byte[] tail = new byte[read];
            file.seek(length - read);
            file.readFully(tail);
            String[] lines = new String(tail, StandardCharsets.UTF_8).split("\r?\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i];
                int tab = line.indexOf('\t');
                int marker = line.indexOf("(start): checkout ");
                if (tab < 0 || marker < tab) continue;
                String[] fields = line.substring(0, tab).split(" ");
                if (fields.length < 2 || !fields[1].equals(onto)) return null;
                String name = line.substring(marker + "(start): checkout ".length()).strip();
                return name.isEmpty() ? null : shortRef(name);
            }
        } catch (IOException | RuntimeException e) {
            // A name is a nicety; the hash still says where.
        }
        return null;
    }

    /** {@code refs/heads/x} as {@code x}, {@code refs/remotes/o/x} as {@code o/x}, and so on. */
    private static String shortRef(String ref) {
        for (String prefix : new String[]{"refs/heads/", "refs/remotes/", "refs/tags/"}) {
            if (ref.startsWith(prefix)) return ref.substring(prefix.length());
        }
        return ref;
    }

    /** Two numbers from a state directory's counter files, {0, 0} when either is missing. */
    private static int[] progress(File stateDir, String current, String total) {
        int at = number(firstLine(new File(stateDir, current)));
        int of = number(firstLine(new File(stateDir, total)));
        return at > 0 && of >= at ? new int[]{at, of} : new int[]{0, 0};
    }

    /**
     * A rebase's progress counted from its todo lists, for an engine that keeps no counters:
     * the steps done, the current one among them, and those still to come.
     */
    private static int[] todoProgress(File stateDir) {
        int done = steps(new File(stateDir, "done"));
        int left = steps(new File(stateDir, "git-rebase-todo"));
        return done > 0 ? new int[]{done, done + left} : new int[]{0, 0};
    }

    /** The lines of a todo list that are steps rather than comments. */
    private static int steps(File todo) {
        if (!todo.isFile()) return 0;
        try {
            int count = 0;
            for (String line : Files.readAllLines(todo.toPath(), StandardCharsets.UTF_8)) {
                String step = line.strip();
                if (!step.isEmpty() && !step.startsWith("#")) count++;
            }
            return count;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static int number(String text) {
        if (text == null) return 0;
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The first non-blank line of a small state file, or {@code null} if there is none. */
    private static String firstLine(File file) {
        if (!file.isFile()) return null;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (!line.isBlank()) return line.strip();
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable marker still says the operation exists; the detail is optional.
        }
        return null;
    }
}
