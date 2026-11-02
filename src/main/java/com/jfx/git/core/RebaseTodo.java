package com.jfx.git.core;

import com.jfx.git.model.CommitInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The plan for an interactive rebase: which commits to replay, in what order, and what
 * to do with each.
 *
 * <p>Held as immutable data with no Git calls in it, because this is where interactive
 * rebase is easy to get wrong and hard to test through a dialog. Every edit returns a new
 * todo, so the editor can undo by keeping the previous one, and {@link #problems()}
 * answers whether a plan is runnable before anything touches the repository.
 *
 * <p><b>Order is oldest first</b> - the order Git replays commits in, and the reverse of
 * how the graph shows them. Getting this backwards silently reverses history, so the
 * factory methods name the direction they take rather than leaving it to be assumed.
 */
public final class RebaseTodo {

    /** What to do with one commit. Mirrors the verbs in Git's own todo file. */
    public enum Action {
        /** Replay the commit unchanged. */
        PICK("pick", "Keep this commit as it is"),
        /** Replay it, then stop to rewrite its message. */
        REWORD("reword", "Keep the changes, change the message"),
        /** Replay it, then stop so the working tree can be amended. */
        EDIT("edit", "Stop after this commit to amend it"),
        /** Fold into the previous commit, combining both messages. */
        SQUASH("squash", "Fold into the commit above, keeping both messages"),
        /** Fold into the previous commit, discarding this message. */
        FIXUP("fixup", "Fold into the commit above, discarding this message"),
        /** Leave the commit out entirely. */
        DROP("drop", "Discard this commit and its changes");

        private final String token;
        private final String description;

        Action(String token, String description) {
            this.token = token;
            this.description = description;
        }

        /** The word Git uses in a todo file. */
        public String token() { return token; }
        public String description() { return description; }

        /** True for the actions that combine a commit into the one before it. */
        public boolean foldsUpward() { return this == SQUASH || this == FIXUP; }
    }

    /**
     * One line of the plan.
     *
     * @param message replacement message for a reword, or {@code null} to keep the
     *                commit's own
     */
    public record Step(CommitInfo commit, Action action, String message) {

        public Step withAction(Action action) { return new Step(commit, action, message); }
        public Step withMessage(String message) { return new Step(commit, action, message); }

        /** The message this step will produce. */
        public String effectiveMessage() {
            return message == null || message.isBlank() ? commit.getSubject() : message;
        }

        public String shortId() { return commit.getShortHash(); }
    }

    private final List<Step> steps;
    /**
     * Full SHA-1 of the commit the replayed steps are laid on top of.
     *
     * <p>Carried explicitly rather than derived, because it is not always the parent of
     * the first step. Replaying a range that is already on the branch sits on that
     * range's parent; moving the branch onto a commit elsewhere sits on that commit. The
     * steps are identical in both cases - only the base differs.
     */
    private final String base;
    /**
     * The commits in the order the plan was built in.
     *
     * <p>Carried through every edit because a reorder is invisible in the steps
     * themselves - every action is still "pick" - so there is nothing else to compare
     * against when deciding whether the plan would actually change anything.
     */
    private final List<CommitInfo> originalOrder;

    private RebaseTodo(List<Step> steps, List<CommitInfo> originalOrder, String base) {
        this.steps = List.copyOf(steps);
        this.originalOrder = List.copyOf(originalOrder);
        this.base = base;
    }

    /**
     * Builds a plan from commits in graph order, newest first.
     *
     * <p>This is what the commit list hands over, and it is reversed here so the plan
     * reads and replays in the direction Git works in.
     */
    public static RebaseTodo fromNewestFirst(List<CommitInfo> commits) {
        return fromNewestFirst(commits, null);
    }

    /** The same, laying the replayed commits on {@code base} rather than their own parent. */
    public static RebaseTodo fromNewestFirst(List<CommitInfo> commits, String base) {
        List<CommitInfo> oldestFirst = new ArrayList<>(commits);
        java.util.Collections.reverse(oldestFirst);
        return fromOldestFirst(oldestFirst, base);
    }

    /** Builds a plan from commits already in replay order. */
    public static RebaseTodo fromOldestFirst(List<CommitInfo> commits) {
        return fromOldestFirst(commits, null);
    }

    public static RebaseTodo fromOldestFirst(List<CommitInfo> commits, String base) {
        List<Step> steps = new ArrayList<>(commits.size());
        for (CommitInfo commit : commits) {
            steps.add(new Step(commit, Action.PICK, null));
        }
        // With no base given, the range keeps its own footing: the parent of its
        // earliest commit, which is what "replay from here" means.
        String footing = base;
        if (footing == null && !commits.isEmpty() && commits.get(0).getParentHashes().size() == 1) {
            footing = commits.get(0).getParentHashes().get(0);
        }
        return new RebaseTodo(steps, commits, footing);
    }

    /** The hash the replayed commits will be laid on, or {@code null} if not determinable. */
    public String base() { return base; }

    /** The plan, oldest first. */
    public List<Step> steps() { return steps; }

    /**
     * The earliest commit in the range, in the order the plan was built.
     *
     * <p>Deliberately not the first step: reordering moves a later commit to the front,
     * and taking the range from there would narrow the rebase to whatever now happens to
     * sit at the top - replaying one commit instead of the range the user chose.
     */
    public CommitInfo rangeStart() { return originalOrder.get(0); }

    public int size() { return steps.size(); }
    public Step step(int index) { return steps.get(index); }

    // ------------------------------------------------------------------
    // Edits
    // ------------------------------------------------------------------

    public RebaseTodo withAction(int index, Action action) {
        List<Step> updated = new ArrayList<>(steps);
        updated.set(index, updated.get(index).withAction(action));
        return new RebaseTodo(updated, originalOrder, base);
    }

    public RebaseTodo withMessage(int index, String message) {
        List<Step> updated = new ArrayList<>(steps);
        updated.set(index, updated.get(index).withMessage(message));
        return new RebaseTodo(updated, originalOrder, base);
    }

    /**
     * A plan that folds {@code hashes} into a single commit.
     *
     * <p>The oldest of the run keeps its {@code pick} and the rest become {@code squash},
     * which is how git itself expresses "make these one commit". The combined message is
     * put on the step that survives.
     *
     * <p>The commits have to be <em>adjacent</em> in the plan. A squash step folds into
     * whatever step is above it, so "squash A and C" over {@code pick A; pick B; squash C}
     * does not fold C into A - it folds C into B, silently combining a commit the caller
     * never named. There is no way to honour the request as asked without also moving
     * steps, which is a different operation with different consequences for the rest of
     * the plan, so this refuses rather than doing something adjacent to what was meant.
     * The graph already blocks a non-contiguous selection before it gets here; this
     * closes the same hole for the model API and the rebase dialog's manual editing.
     *
     * @param hashes  full SHA-1s of the commits to fold together
     * @param message combined message, or {@code null} to leave git's default
     * @return the edited plan, or {@code null} if any of {@code hashes} is not in this
     *         plan — squashing a commit the rebase would not replay is not something to
     *         guess at — or if they are not adjacent
     */
    public RebaseTodo squashing(Set<String> hashes, String message) {
        if (hashes == null || hashes.size() < 2) return null;

        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            if (hashes.contains(step(i).commit().getHash())) matched.add(i);
        }
        if (matched.size() != hashes.size()) return null;
        // Adjacent, so every squash folds into a step the caller actually chose.
        if (matched.get(matched.size() - 1) - matched.get(0) != matched.size() - 1) return null;

        RebaseTodo result = this;
        // Steps run oldest first, so the first match is the survivor.
        int oldestIndex = matched.get(0);
        for (int i = 1; i < matched.size(); i++) {
            result = result.withAction(matched.get(i), Action.SQUASH);
        }

        return message == null || message.isBlank()
                ? result
                : result.withMessage(oldestIndex, message);
    }

    /** Where {@code hash} sits in the plan, or -1 when the plan does not replay it. */
    public int indexOf(String hash) {
        for (int i = 0; i < size(); i++) {
            if (step(i).commit().getHash().equals(hash)) return i;
        }
        return -1;
    }

    /**
     * A plan that changes one commit's message and replays the rest as they are.
     *
     * @return the edited plan, or {@code null} if {@code hash} is not in this plan or
     *         {@code message} is blank - a commit cannot be reworded to nothing
     */
    public RebaseTodo rewording(String hash, String message) {
        int index = indexOf(hash);
        if (index < 0 || message == null || message.isBlank()) return null;
        return withAction(index, Action.REWORD).withMessage(index, message);
    }

    /**
     * A plan that leaves one commit out and replays the rest as they are.
     *
     * @return the edited plan, or {@code null} if {@code hash} is not in this plan. The
     *         result can still be invalid - dropping the only commit of a plan leaves
     *         nothing to replay - which {@link #problems()} reports.
     */
    public RebaseTodo dropping(String hash) {
        int index = indexOf(hash);
        return index < 0 ? null : withAction(index, Action.DROP);
    }

    /**
     * A plan that swaps one commit with the one replayed just after it - one step towards
     * the branch's tip, "up" in a graph drawn newest first.
     *
     * @return the edited plan, or {@code null} if {@code hash} is not in this plan or is
     *         already its newest commit
     */
    public RebaseTodo movingNewer(String hash) {
        int index = indexOf(hash);
        return index < 0 || index == size() - 1 ? null : moved(index, index + 1);
    }

    /**
     * A plan that swaps one commit with the one replayed just before it - one step away
     * from the tip, "down" in the graph.
     *
     * @return the edited plan, or {@code null} if {@code hash} is not in this plan or is
     *         already its oldest commit
     */
    public RebaseTodo movingOlder(String hash) {
        int index = indexOf(hash);
        return index <= 0 ? null : moved(index, index - 1);
    }

    /**
     * The same plan with every squash run's combined message on the step that survives it.
     *
     * <h2>The convention, and why it needs enforcing</h2>
     * A run of {@code squash}/{@code fixup} steps folds into the ordinary {@code pick}
     * above it, and that leader is the commit that survives - so the combined message
     * belongs there. {@link #squashing} has always put it there. The cherry-pick flow in
     * the graph did not: it marked a step {@code SQUASH} and set the message on that same
     * step.
     *
     * <p>Neither engine reads it there, and they disagreed about what to do instead. JGit
     * takes a run's message from the leader only, so the user's text was dropped and git's
     * default concatenation appeared in its place. The CLI engine writes one
     * {@code exec git commit --amend -F} per message-bearing step, so the last marked step
     * in the run won. The same plan produced different commits on different engines, and
     * on one of them the message the user had just typed silently vanished.
     *
     * <p>So the plan is normalized here, once, and both engines consume the result:
     *
     * <ul>
     *   <li>A folding step never carries a message afterwards.</li>
     *   <li>A run's message is the last non-blank one its folding steps carried - which is
     *       what the CLI engine already produced, so this moves JGit to match it rather
     *       than changing both.</li>
     *   <li>It lands on the leader only when the leader is an ordinary {@code pick} with
     *       no message of its own. A {@code reword} leader has its own prompt, and
     *       overwriting that would drop whichever message lost.</li>
     * </ul>
     */
    public RebaseTodo normalized() {
        if (steps.stream().noneMatch(s -> s.action().foldsUpward()
                && s.message() != null && !s.message().isBlank())) {
            return this;
        }

        List<Step> updated = new ArrayList<>(steps);
        for (int i = 0; i < updated.size(); i++) {
            if (!updated.get(i).action().foldsUpward()) continue;

            // The whole run, and the step above it that the run folds into. A drop line
            // neither ends a run nor leads one: git treats it as a no-op, and JGit never
            // sees it at all, so "squash" after "drop" folds into the step above the drop.
            int start = i;
            int end = i;
            for (int next = i + 1; next < updated.size(); next++) {
                Action action = updated.get(next).action();
                if (action == Action.DROP) continue;
                if (!action.foldsUpward()) break;
                end = next;
            }

            String combined = null;
            for (int j = start; j <= end; j++) {
                if (!updated.get(j).action().foldsUpward()) continue;
                String message = updated.get(j).message();
                if (message != null && !message.isBlank()) combined = message;
                updated.set(j, updated.get(j).withMessage(null));
            }

            int leader = start - 1;
            while (leader >= 0 && updated.get(leader).action() == Action.DROP) {
                leader--;
            }
            if (combined != null && leader >= 0
                    && updated.get(leader).action() == Action.PICK
                    && (updated.get(leader).message() == null
                        || updated.get(leader).message().isBlank())) {
                updated.set(leader, updated.get(leader).withMessage(combined));
            }
            i = end;
        }
        return new RebaseTodo(updated, originalOrder, base);
    }

    /** Moves the step at {@code from} so it ends up at {@code to}. */
    public RebaseTodo moved(int from, int to) {
        if (from == to || from < 0 || from >= steps.size() || to < 0 || to >= steps.size()) {
            return this;
        }
        List<Step> updated = new ArrayList<>(steps);
        updated.add(to, updated.remove(from));
        return new RebaseTodo(updated, originalOrder, base);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * Everything that would stop this plan from running, in the order a reader meets it.
     *
     * <p>Returned as a list rather than a first failure so the editor can show all of
     * them at once instead of revealing one per attempt.
     */
    public List<String> problems() {
        return problems(false);
    }

    /**
     * {@link #problems()}, for a plan whose merges are not replayed as merges.
     *
     * <p>A rebase cannot take a merge through a linear replay, so it is refused there.
     * A batch cherry-pick can: each merge is picked against the parent the user names
     * ({@code -m}) and lands as an ordinary commit, which any reword or squash in the
     * follow-up then treats like the rest. With {@code mergesAllowed} that one rule is
     * left out and every other still applies.
     */
    public List<String> problems(boolean mergesAllowed) {
        List<String> problems = new ArrayList<>();
        if (steps.isEmpty()) {
            problems.add("There are no commits to rebase.");
            return problems;
        }

        for (Step step : steps) {
            if (!mergesAllowed && step.commit().getParentHashes().size() > 1) {
                problems.add("Commit " + step.shortId()
                        + " is a merge, and an interactive rebase replays a linear history."
                        + " Rebase a range without merges instead.");
                break;
            }
        }

        // A squash or fixup folds into whatever precedes it, so something has to.
        boolean anythingKept = false;
        for (Step step : steps) {
            if (step.action().foldsUpward() && !anythingKept) {
                problems.add("Commit " + step.shortId() + " is set to "
                        + step.action().token() + ", but there is no commit above it to fold into.");
                break;
            }
            if (step.action() != Action.DROP) {
                anythingKept = true;
            }
        }

        if (!anythingKept) {
            problems.add("Every commit is set to drop, which would discard all of them.");
        }
        return problems;
    }

    public boolean isValid() { return problems().isEmpty(); }

    /**
     * True when running this would change nothing: every commit picked, in its original
     * position, replayed onto the same commit it already sits on.
     *
     * <p>The order check is the one that matters. A reorder leaves every action set to
     * "pick", so a plan that only moves commits around looks untouched unless it is
     * compared against the order it started in.
     *
     * <p>The base check matters just as much for a "rebase onto" plan (a commercial client's
     * drag-a-branch-onto-another, or "Rebase current onto this"): every step there is
     * also a plain, unmodified pick, since the point is to move the range wholesale, not
     * to edit it. Without checking {@code base} against where the range actually already
     * sits, such a plan looked identical to a true no-op and left "Start Rebase"
     * permanently disabled - the commits would never move at all.
     */
    public boolean isNoop() {
        if (isReordered()) return false;
        if (isRelocated()) return false;
        for (Step step : steps) {
            if (step.action() != Action.PICK || step.message() != null) return false;
        }
        return true;
    }

    /**
     * True when {@code base} is not the commit this range already sits on - i.e. this
     * plan replays it onto a genuinely different commit rather than back where it
     * started.
     */
    public boolean isRelocated() {
        if (base == null || originalOrder.isEmpty()) {
            return false;
        }
        List<String> oldestParents = originalOrder.get(0).getParentHashes();
        return oldestParents.size() != 1 || !oldestParents.get(0).equals(base);
    }

    /** True when the commits no longer sit in the order the plan was built with. */
    public boolean isReordered() {
        if (steps.size() != originalOrder.size()) return true;
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).commit().equals(originalOrder.get(i))) return true;
        }
        return false;
    }

    /** True when the plan reorders commits relative to {@code original}. */
    public boolean isReorderOf(RebaseTodo original) {
        if (original.size() != size()) return false;
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).commit().equals(original.step(i).commit())) return true;
        }
        return false;
    }

    /** "3 picked, 1 squashed, 1 dropped" - what the confirm button should promise. */
    public String summary() {
        int picked = 0, folded = 0, dropped = 0, reworded = 0;
        for (Step step : steps) {
            switch (step.action()) {
                case DROP -> dropped++;
                case SQUASH, FIXUP -> folded++;
                case REWORD -> reworded++;
                default -> picked++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (picked > 0) parts.add(picked + " kept");
        if (reworded > 0) parts.add(reworded + " reworded");
        if (folded > 0) parts.add(folded + " folded");
        if (dropped > 0) parts.add(dropped + " dropped");
        return String.join(", ", parts);
    }

    /** How many commits the rebase will produce. */
    public int resultingCommitCount() {
        int count = 0;
        for (Step step : steps) {
            if (step.action() != Action.DROP && !step.action().foldsUpward()) count++;
        }
        return count;
    }
}
