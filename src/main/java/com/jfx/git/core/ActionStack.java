package com.jfx.git.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multi-step undo and redo for one repository, built on the reflog.
 *
 * <h2>Why the reflog rather than remembered inverses</h2>
 * The obvious design records an inverse per action and replays it. That is wrong for git:
 * the authoritative record of where a branch has been is the reflog, which git writes
 * whether or not this application was what moved HEAD. A stack of remembered inverses
 * drifts the moment the user runs something in a terminal, or git moves HEAD itself
 * partway through a rebase.
 *
 * <p>So a step is <em>a commit HEAD once pointed at</em>, read from the reflog. That is
 * correct across anything git can do, including operations this app never sees.
 *
 * <h2>Why undo is not always a reset</h2>
 * Resetting to the earlier hash is right for a move that changed <em>where a branch
 * points</em> — commit, merge, rebase, an earlier reset. It is wrong for a checkout,
 * which changed <em>which branch HEAD is on</em> and moved no pointer at all. Resetting
 * to undo {@code main → feature} would drag feature back onto main's commit and strand
 * the work that was on it in the reflog. So a checkout step keeps the two ref names from
 * its reflog comment and is reversed by checking the other one out; {@link Move} carries
 * that decision to the caller.
 *
 * <h2>The cursor, and why the reflog is snapshotted</h2>
 * Each undo is itself a reset, and git writes a reflog entry for it. Walking the live
 * reflog by index would therefore step back onto the undo that was just performed and
 * make the second undo a no-op. So the reflog is snapshotted when an undo session begins
 * and a cursor walks that snapshot; the snapshot is only replaced when the user does
 * something that is not an undo or redo — which is also what makes redo unreachable
 * afterwards, exactly as in an editor.
 *
 * <p>Not thread-safe by itself; callers hold it per repository tab and touch it from the
 * FX thread, with the resets themselves run on workers.
 */
public final class ActionStack {

    /** How far back to read; beyond this the reflog is rarely the user's mental model. */
    public static final int DEPTH = 50;

    /**
     * One state HEAD has been in.
     *
     * <p>{@code movedFrom} and {@code movedTo} are set only for a checkout, and carry the
     * two ends of git's {@code "moving from X to Y"} comment. They are what makes undoing
     * a checkout a checkout rather than a reset — see {@link #undoMove()}.
     *
     * @param hash      full SHA-1 to reset to in order to reach this state
     * @param label     what the move that produced it was, e.g. {@code "checkout main"}
     * @param movedFrom the ref this checkout left, or null when the move was not a checkout
     * @param movedTo   the ref this checkout arrived at, or null likewise
     */
    public record Step(String hash, String label, String movedFrom, String movedTo) {

        public Step(String hash, String label) {
            this(hash, label, null, null);
        }

        /** True when this move switched what HEAD points at rather than moving a branch. */
        public boolean isCheckout() {
            return movedFrom != null && movedTo != null;
        }
    }

    /**
     * What an undo or redo should actually do.
     *
     * <h2>Why this is not simply "reset to a hash"</h2>
     * A reset moves <em>the branch HEAD is on</em>. That is right for a commit, merge or
     * rebase, and catastrophic for a checkout: after {@code main → feature}, resetting to
     * main's tip would drag <em>feature</em> back onto main's commit and leave the work
     * that was on it reachable only through the reflog. Reversing a checkout means
     * checking the other ref out again, which touches no branch pointer at all.
     *
     * <h2>Why a checkout carries a hash as well</h2>
     * The reflog records a checkout's destination as it was <em>typed</em>:
     * {@code "moving from main to HEAD~1"}, {@code "... to origin/feature"},
     * {@code "... to v1.0"}. Checking that text out again resolves it now, not then -
     * {@code HEAD~1} is a different commit after the undo, and {@code origin/feature} may
     * have been fetched forward. Only a local branch name means "this branch, wherever it
     * is now"; anything else has to go back to the commit it landed on. The ref cannot be
     * classified here without asking the repository, so both travel and the caller picks:
     * see {@link #checkoutTarget}.
     *
     * @param label        what to show while it runs
     * @param checkoutRef  the ref to check out, or null when this move is a reset
     * @param resetHash    the commit to reset to, or null when this move is a checkout
     * @param checkoutHash the commit the checkout originally reached, for when
     *                     {@code checkoutRef} is not a local branch; null for a reset
     */
    public record Move(String label, String checkoutRef, String resetHash, String checkoutHash) {

        public Move(String label, String checkoutRef, String resetHash) {
            this(label, checkoutRef, resetHash, null);
        }

        public boolean isCheckout() {
            return checkoutRef != null;
        }

        /**
         * What to check out: the ref by name when it is one of {@code localBranches}, so
         * the branch is re-entered wherever it now points, otherwise the recorded commit.
         */
        public String checkoutTarget(java.util.Collection<String> localBranches) {
            if (checkoutRef == null) return null;
            if (checkoutHash == null || (localBranches != null && localBranches.contains(checkoutRef))) {
                return checkoutRef;
            }
            return checkoutHash;
        }
    }

    /** Snapshot of reflog targets, newest first; index 0 is the current state. */
    private List<Step> steps = List.of();

    /**
     * How far back into {@link #steps} the user has walked.
     *
     * <p>Zero means "at the present"; {@code n} means n undos have been applied.
     */
    private int cursor;

    /**
     * The two hashes the last undo or redo moved between, or null when not navigating.
     *
     * <p>Two hashes rather than one "swallow the next refresh" flag. A flag consumes
     * whichever refresh arrives first, and refreshes come from elsewhere too - a
     * background fetch, a file-system change - so the wrong one could be absorbed and the
     * undo's own entry then folded into a rebuilt snapshot with the cursor back at zero,
     * which loses the redo chain. Naming the hashes makes the test exact instead of
     * ordinal: a reflog whose newest entry is where this class just moved HEAD
     * <em>to</em> is its own reset, and one still showing where it moved <em>from</em> is
     * a read that raced ahead of git's write. Neither is a user action. Anything else is,
     * and ends navigation.
     */
    private String navigatingTo;
    private String navigatingFrom;

    /**
     * Rebuilds the snapshot from a fresh reflog read.
     *
     * <p>Ignored while navigating, because the entries an undo writes are not user
     * actions and must not become undo targets of their own.
     *
     * @return true when the snapshot was replaced
     */
    public boolean refreshFrom(List<ReflogItem> reflog) {
        String newest = reflog.isEmpty() ? null : reflog.get(0).newHash();
        if (navigatingTo != null
                && (navigatingTo.equals(newest) || navigatingFrom.equals(newest))) {
            return false;
        }
        navigatingTo = null;
        navigatingFrom = null;

        steps = List.copyOf(stepsFrom(reflog));
        cursor = 0;
        return true;
    }

    /**
     * The reflog as undo steps: one per user-visible action, newest first.
     *
     * <h2>A rebase is one action</h2>
     * git writes an entry for every step of a rebase - {@code (start)} checking out the
     * new base, one per pick, {@code (finish)} returning to the branch. Walked one entry
     * at a time, undoing {@code git rebase main} took four undos: the first did nothing
     * (the finish entry moves no commit), the next ones parked the branch half-rebased and
     * then on main's tip with its own commits apparently gone, and only the fourth put it
     * back. So a whole run - from its finish (or abort, or the latest step of one still in
     * progress) down to its start - becomes a single step whose state is where the run
     * ended, and undoing it returns to the entry before the start.
     *
     * <h2>An entry that moved nothing is not a step</h2>
     * {@code git stash} writes "reset: moving to HEAD" with the hash unchanged, and an
     * aborted rebase returns to exactly where it began. Undoing either "succeeded" and
     * changed nothing. A step whose hash equals the one before it is dropped - except a
     * checkout, which switches branch without moving any commit and is a real action.
     */
    static List<Step> stepsFrom(List<ReflogItem> reflog) {
        List<ReflogItem> usable = new ArrayList<>();
        for (ReflogItem item : reflog) {
            if (item.newHash() != null) usable.add(item);
        }

        List<Step> collapsed = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            ReflogItem item = usable.get(i);
            if (isRebaseEntry(item.comment())) {
                // Walk older entries until the run's start (inclusive).
                int end = i;
                while (!isRebaseStart(usable.get(end).comment(), end > i)
                        && end + 1 < usable.size()
                        && (isRebaseEntry(usable.get(end + 1).comment())
                            || isRebaseStart(usable.get(end + 1).comment(), true))) {
                    end++;
                }
                boolean aborted = isRebaseAbort(item.comment());
                collapsed.add(new Step(item.newHash(), aborted ? "aborted rebase" : "rebase"));
                i = end;
                continue;
            }
            String[] ends = checkoutEnds(item.comment());
            collapsed.add(new Step(item.newHash(), labelFromReflog(item.comment()),
                    ends == null ? null : ends[0],
                    ends == null ? null : ends[1]));
        }

        List<Step> result = new ArrayList<>();
        for (int i = 0; i < collapsed.size(); i++) {
            Step step = collapsed.get(i);
            boolean movedNothing = i + 1 < collapsed.size()
                    && step.hash().equals(collapsed.get(i + 1).hash());
            if (movedNothing && !step.isCheckout()) continue;
            result.add(step);
        }
        return result;
    }

    /**
     * An entry written by a rebase: git's {@code rebase (pick): …}, {@code rebase -i
     * (finish): …}, {@code pull --rebase (start): …}, and JGit's {@code rebase: …},
     * {@code rebase finished: …}, {@code rebase: aborting}.
     */
    static boolean isRebaseEntry(String comment) {
        if (comment == null) return false;
        String c = comment.strip().toLowerCase(Locale.ROOT);
        if (c.startsWith("pull --rebase") || c.startsWith("pull -r")) {
            return c.contains("(");
        }
        return c.startsWith("rebase");
    }

    /**
     * Whether {@code comment} starts a rebase run. git's {@code (start)} entry does; JGit
     * writes a plain {@code checkout: moving from <branch> to <full hash>} for it, which
     * only counts when it directly precedes rebase entries ({@code insideRun}).
     */
    private static boolean isRebaseStart(String comment, boolean insideRun) {
        if (comment == null) return false;
        String c = comment.strip().toLowerCase(Locale.ROOT);
        if (isRebaseEntry(comment) && c.contains("(start)")) return true;
        if (!insideRun) return false;
        String[] ends = checkoutEnds(comment);
        return ends != null && ends[1].matches("[0-9a-f]{40}([0-9a-f]{24})?");
    }

    private static boolean isRebaseAbort(String comment) {
        String c = comment == null ? "" : comment.toLowerCase(Locale.ROOT);
        return c.contains("(abort)") || c.startsWith("rebase: aborting");
    }

    /**
     * Whether there is an earlier state to return to.
     *
     * <p>Needs a step beyond the cursor: at the last recorded state there is nothing
     * further back that the reflog can name.
     */
    public boolean canUndo() {
        return cursor + 1 < steps.size();
    }

    public boolean canRedo() {
        return cursor > 0;
    }

    /**
     * What undo would do, phrased for a tooltip.
     *
     * <p>Names the action being undone — the label on the step the cursor is at — rather
     * than the state being returned to, because "Undo: commit add parser" is what the
     * user recognises.
     */
    public String undoDescription() {
        if (!canUndo()) return "Nothing to undo";
        return "Undo: " + steps.get(cursor).label();
    }

    public String redoDescription() {
        if (!canRedo()) return "Nothing to redo";
        return "Redo: " + steps.get(cursor - 1).label();
    }

    /**
     * What undo should do, or {@code null} when there is nothing to undo.
     *
     * <p>The move being reversed is the step the cursor is <em>at</em>; the state being
     * returned to is the one behind it. Which of the two matters depends on the kind of
     * move: a checkout is reversed by name, using the ref it left, and everything else by
     * resetting to the earlier state's hash.
     */
    public Move undoMove() {
        if (!canUndo()) return null;
        Step reversing = steps.get(cursor);
        String label = undoDescription();
        return reversing.isCheckout()
                ? new Move(label, reversing.movedFrom(), null, steps.get(cursor + 1).hash())
                : new Move(label, null, steps.get(cursor + 1).hash());
    }

    /**
     * What redo should do, or {@code null} when there is nothing to redo.
     *
     * <p>The move being re-applied is the step just in front of the cursor, so a checkout
     * is redone by checking out the ref it originally arrived at.
     */
    public Move redoMove() {
        if (!canRedo()) return null;
        Step reapplying = steps.get(cursor - 1);
        String label = redoDescription();
        return reapplying.isCheckout()
                ? new Move(label, reapplying.movedTo(), null, reapplying.hash())
                : new Move(label, null, reapplying.hash());
    }

    /** Records that the undo the caller requested succeeded. */
    public void undoApplied() {
        if (canUndo()) {
            navigatingFrom = steps.get(cursor).hash();
            cursor++;
            navigatingTo = steps.get(cursor).hash();
        }
    }

    public void redoApplied() {
        if (canRedo()) {
            navigatingFrom = steps.get(cursor).hash();
            cursor--;
            navigatingTo = steps.get(cursor).hash();
        }
    }

    /** Drops the snapshot, e.g. when the engine or repository changed underneath. */
    public void reset() {
        steps = List.of();
        cursor = 0;
        navigatingTo = null;
        navigatingFrom = null;
    }

    public int depth() {
        return steps.size();
    }

    public int cursor() {
        return cursor;
    }

    /** The steps an "undo history" menu should list, oldest move last. */
    public List<Step> upcomingUndoSteps(int limit) {
        List<Step> result = new ArrayList<>();
        for (int i = cursor; i < steps.size() - 1 && result.size() < limit; i++) {
            result.add(steps.get(i));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    /**
     * Turns a reflog comment into the label an undo button should show.
     *
     * <p>Git's comments read like {@code "checkout: moving from main to feature"} or
     * {@code "rebase (finish): returning to refs/heads/main"}. The useful half is the
     * operation and its target, not the whole sentence.
     */
    public static String labelFromReflog(String comment) {
        if (comment == null || comment.isBlank()) return "last action";

        int colon = comment.indexOf(':');
        String operation = colon > 0 ? comment.substring(0, colon).trim() : comment.trim();
        String detail = colon > 0 ? comment.substring(colon + 1).trim() : "";
        String lower = operation.toLowerCase(Locale.ROOT);

        if (lower.startsWith("checkout")) {
            // "moving from main to feature" — the destination is what the user did.
            String[] ends = checkoutEnds(comment);
            return ends == null ? "checkout" : "checkout " + ends[1];
        }
        if (lower.startsWith("clone")) return "clone";
        if (lower.startsWith("rebase")) return "rebase";
        if (lower.startsWith("commit")) {
            if (lower.contains("amend")) return "amend commit";
            if (lower.contains("initial")) return "initial commit";
            return detail.isBlank() ? "commit" : "commit " + firstWords(detail, 5);
        }
        if (lower.startsWith("reset")) {
            return detail.isBlank() ? "reset" : "reset to " + firstWords(detail, 3);
        }
        if (lower.startsWith("merge")) {
            return detail.isBlank() ? "merge" : "merge " + firstWords(detail, 3);
        }
        if (lower.startsWith("pull")) return "pull";
        if (lower.startsWith("cherry-pick")) return "cherry-pick";
        if (lower.startsWith("revert")) return "revert";
        if (lower.startsWith("branch")) return "create branch";

        return lower.isBlank() ? "last action" : lower;
    }

    /**
     * The two refs a checkout moved between, or {@code null} when the comment is not one.
     *
     * <p>Git writes {@code "checkout: moving from main to feature"}, using the short
     * commit id in place of a name at either end when HEAD was or becomes detached —
     * which is exactly what an undo wants, since checking that id out detaches again.
     *
     * <p>Splitting on the <em>last</em> {@code " to "} is safe because a ref name cannot
     * contain a space, so the separator occurs once however the branches are named. A
     * branch called {@code to-do} does not fool it.
     *
     * @return {@code {from, to}}, or null
     */
    static String[] checkoutEnds(String comment) {
        if (comment == null) return null;
        int colon = comment.indexOf(':');
        if (colon < 0) return null;
        if (!comment.substring(0, colon).trim().toLowerCase(Locale.ROOT).startsWith("checkout")) {
            return null;
        }

        String detail = comment.substring(colon + 1).trim();
        String prefix = "moving from ";
        if (!detail.startsWith(prefix)) return null;

        String pair = detail.substring(prefix.length());
        int separator = pair.lastIndexOf(" to ");
        if (separator < 0) return null;

        String from = pair.substring(0, separator).trim();
        String to = pair.substring(separator + 4).trim();
        return from.isEmpty() || to.isEmpty() ? null : new String[] {from, to};
    }

    private static String firstWords(String text, int count) {
        String[] words = text.trim().split("\\s+");
        int take = Math.min(count, words.length);
        return String.join(" ", java.util.Arrays.copyOfRange(words, 0, take));
    }
}
