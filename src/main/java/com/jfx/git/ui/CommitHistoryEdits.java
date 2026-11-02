package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.RebaseTodo;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.CommitPosition;
import com.jfx.git.model.RebaseEligibility;
import com.jfx.git.model.RebaseRange;
import com.jfx.git.ui.dialogs.Dialogs;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Changes to one commit made straight from the graph: reword it, drop it, or move it one
 * place up or down its branch.
 *
 * <p>Each is an interactive rebase with a plan nobody had to write. The rebase editor can
 * do all four, but it is a dialog listing every commit from the chosen one to the tip, in
 * which the user finds the row again, changes its action and confirms - for something
 * the graph's own menu can name in two words. The plan built here is the one that editor
 * would have produced, run through the same {@link GitBackend#rebaseInteractive}, so
 * hooks, signing, conflicts and Undo all behave as they do there.
 *
 * <p>What is rewritten is the range from the commit - or, where its neighbour below has
 * to move too, from that neighbour - to the tip. If the oldest commit of that range has
 * been pushed, the user is asked first, the same question squashing asks.
 */
public final class CommitHistoryEdits {

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs each edit, refreshing the whole tab afterwards whether or not it worked. */
    private final OperationRunner operations;

    public CommitHistoryEdits(OperationRunner operations) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
    }

    /** A range ready to edit, or why there is not one. */
    private record Loaded(RebaseRange range, CommitPosition position, String problem) {
    }

    // ------------------------------------------------------------------
    // The four edits
    // ------------------------------------------------------------------

    /** Asks for a new message for {@code commit}, then rewrites it. */
    public void reword(CommitInfo commit) {
        String hash = commit.getHash();
        withPlan(hash, (loaded, plan) -> {
            // The range's own copy: it was read with its whole message, and the graph
            // row this came from carries the subject alone.
            CommitInfo whole = loaded.range().commits().stream()
                    .filter(c -> c.getHash().equals(hash)).findFirst().orElse(null);
            if (whole == null) {
                statusBar.error(notInRange(commit));
                return;
            }
            new com.jfx.git.ui.dialogs.RewordDialog(whole).offerCoAuthors(gitService, statusBar::error)
                    .showAndWait().ifPresent(message ->
                    run(plan.rewording(hash, message), loaded.position().pushed(), commit, "Reword", "Rewording"));
        });
    }

    /** Asks, then takes {@code commit} out of the branch. */
    public void drop(CommitInfo commit) {
        // A rebase like any other, so Undo on the toolbar walks back over it: Enter drops.
        if (!Dialogs.destructive("Drop commit")
                .icon(Icons.commit())
                .title("Drop `" + commit.getShortHash() + "`?")
                .subtitle(commit.getSubject())
                .message("The commit and its changes are taken out of the branch, and the commits after"
                        + " it are replayed without it - stopping for you to resolve if one depended"
                        + " on it. Undo on the toolbar puts it back.")
                .action("Drop commit")
                .ask()) {
            return;
        }

        String hash = commit.getHash();
        withPlan(hash, (loaded, plan) -> {
            if (plan.size() > 1) {
                run(plan.dropping(hash), loaded.position().pushed(), commit, "Drop", "Dropping");
                return;
            }
            // The newest commit, alone in its range: a plan that drops its only step
            // replays nothing. Start one commit lower, so there is something to keep.
            List<String> parents = commit.getParentHashes();
            if (parents.size() != 1) {
                statusBar.error(notInRange(commit));
                return;
            }
            // Whether it was pushed is still asked of the commit being dropped: the one
            // below it is replayed as it is, and so is not rewritten at all.
            withPlan(parents.get(0), (lower, wider) ->
                            run(wider.dropping(hash), loaded.position().pushed(), commit, "Drop", "Dropping"),
                    "The newest commit can only be dropped here when the commit before it can be"
                            + " replayed. Reset the branch to that commit instead.");
        });
    }

    /** Swaps {@code commit} with the commit above it in the graph: the next newer one. */
    public void moveUp(CommitInfo commit) {
        String hash = commit.getHash();
        withPlan(hash, (loaded, plan) -> {
            RebaseTodo moved = plan.movingNewer(hash);
            if (moved == null) {
                statusBar.error(commit.getShortHash() + " is already the newest commit on the branch");
                return;
            }
            run(moved, loaded.position().pushed(), commit, "Move", "Moving");
        });
    }

    /** Swaps {@code commit} with the commit below it in the graph: its parent. */
    public void moveDown(CommitInfo commit) {
        List<String> parents = commit.getParentHashes();
        if (parents.size() != 1) {
            statusBar.error(commit.getShortHash() + " has no single commit below it to change places with");
            return;
        }
        String hash = commit.getHash();
        // From the parent: it is replayed too, after the commit that was above it.
        withPlan(parents.get(0), (loaded, plan) ->
                        run(plan.movingOlder(hash), loaded.position().pushed(), commit, "Move", "Moving"),
                commit.getShortHash() + " cannot move down: the commit below it is the repository's"
                        + " first commit or a merge, and neither can be replayed.");
    }

    // ------------------------------------------------------------------
    // The pipeline they share
    // ------------------------------------------------------------------

    private void withPlan(String rangeFrom, BiConsumer<Loaded, RebaseTodo> then) {
        withPlan(rangeFrom, then, null);
    }

    /**
     * Reads the range from {@code rangeFrom} to the tip on the git lane and hands it over
     * as a plan of plain picks.
     *
     * <p>The eligibility check comes first and is not optional. For a commit that is not
     * on the current branch {@code getRebaseRange} answers with a different plan
     * altogether - the current branch moved <em>onto</em> that commit - and editing one
     * step of that would rebase the branch somewhere the user never asked for.
     *
     * @param unreplayable what to say when {@code rangeFrom} is a root or a merge, where
     *                     the caller knows a better way to put it; {@code null} for the
     *                     general wording
     */
    private void withPlan(String rangeFrom, BiConsumer<Loaded, RebaseTodo> then, String unreplayable) {
        GitTaskRunner.query(gitService, () -> {
            CommitPosition position = gitService.getCommitPosition(rangeFrom);
            RebaseEligibility eligibility = RebaseEligibility.from(position);
            if (eligibility != RebaseEligibility.OK) {
                boolean replay = eligibility == RebaseEligibility.ROOT_COMMIT
                        || eligibility == RebaseEligibility.MERGE_COMMIT;
                return new Loaded(null, position,
                        replay && unreplayable != null ? unreplayable : problem(eligibility));
            }
            return new Loaded(gitService.getRebaseRange(rangeFrom), position, null);
        }, loaded -> {
            if (loaded.problem() != null) {
                statusBar.error(loaded.problem());
                return;
            }
            if (!loaded.range().isUsable()) {
                statusBar.error(loaded.range().problem());
                return;
            }
            then.accept(loaded, RebaseTodo.fromNewestFirst(loaded.range().commits(), loaded.range().base()));
        }, statusBar::error);
    }

    /** Why a commit cannot be edited in place, in the status bar's words. */
    static String problem(RebaseEligibility eligibility) {
        return switch (eligibility) {
            case ONTO -> "That commit is not on the branch that is checked out, so there is nothing"
                    + " here to rewrite. Check out its branch first.";
            case MERGE_COMMIT -> "A merge commit cannot be replayed, so it cannot be reworded,"
                    + " dropped or moved here.";
            case ROOT_COMMIT -> "The repository's first commit has nothing to be replayed onto, so"
                    + " it cannot be changed here.";
            default -> "That commit could not be read.";
        };
    }

    private static String notInRange(CommitInfo commit) {
        return commit.getShortHash() + " is not among the commits that would be replayed";
    }

    /**
     * @param pushed whether the oldest commit this plan rewrites is already on a remote
     */
    private void run(RebaseTodo plan, boolean pushed, CommitInfo commit, String verb, String doing) {
        if (plan == null) {
            statusBar.error(notInRange(commit));
            return;
        }
        List<String> problems = plan.problems();
        if (!problems.isEmpty()) {
            statusBar.error(problems.get(0));
            return;
        }
        if (pushed && !confirmRewritePushed(commit, verb)) {
            return;
        }
        operations.run(doing + " " + commit.getShortHash(), OperationRunner.Refresh.EVERYTHING,
                () -> gitService.rebaseInteractive(plan));
    }

    /**
     * Asks before rewriting history a remote already has: afterwards the branch and the
     * remote disagree, and only a force push settles it.
     */
    private static boolean confirmRewritePushed(CommitInfo commit, String verb) {
        // The second question after the user already chose the edit, and the one that
        // decides whether others' history changes: Enter is Cancel.
        return Dialogs.warn(verb + " commit")
                .icon(Icons.history())
                .title("Rewrite commits that were already pushed?")
                .subtitle("`" + commit.getShortHash() + "` · " + commit.getSubject())
                .message("It is on a remote already, or sits on commits that are. This rewrites them,"
                        + " so the next push has to be a force push, and anyone who pulled them will"
                        + " need to rebase onto the new ones.")
                .action(verb + " anyway")
                .safeDefault()
                .ask();
    }
}
