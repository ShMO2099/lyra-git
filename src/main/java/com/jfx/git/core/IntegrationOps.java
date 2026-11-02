package com.jfx.git.core;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.MergeMode;
import com.jfx.git.model.OperationState;
import com.jfx.git.model.RebaseEligibility;
import com.jfx.git.model.RebaseRange;
import com.jfx.git.model.ResetMode;
import com.jfx.git.model.StashOptions;
import java.util.List;

/**
 * Bringing one line of history into another, and undoing it: merge, rebase, cherry-pick,
 * revert, reset and stash.
 *
 * <p>These are grouped because they share a failure mode rather than a data type - each
 * can stop half-done and leave the repository in a state only another call from this
 * same interface can finish or abandon.
 *
 * <p>One of the capability interfaces {@link GitBackend} composes; see its javadoc for
 * why the contract is split up rather than kept as one list.
 */
public interface IntegrationOps {

    // ------------------------------------------------------------------
    // Merge / rebase / cherry-pick
    // ------------------------------------------------------------------

    /**
     * Merges {@code sourceBranch} into the branch that is checked out.
     *
     * <p>{@link MergeMode#DEFAULT} passes no fast-forward flag at all, so {@code merge.ff}
     * and {@code branch.<name>.mergeoptions} decide, exactly as on the command line; the
     * other modes override that configuration for this merge, as their flags do in git.
     *
     * <p>{@link MergeMode#SQUASH} stages the combined changes and stops: HEAD does not
     * move, no {@code MERGE_HEAD} is written, and {@code SQUASH_MSG} is left for the
     * commit that finishes it - with {@code message}, or "Squash merge branch '...'", as
     * its subject line (see {@code SquashMessage}). It is a squash even where the
     * configuration says {@code merge.ff = false}, which on its own makes git refuse
     * {@code --squash} as if the user had combined it with {@code --no-ff}.
     *
     * @param mode    how to join the branch in; {@code null} is {@link MergeMode#DEFAULT}
     * @param message the merge commit's message; blank or null keeps the engine's own.
     *                Ignored by a fast-forward, which makes no commit to put it on
     */
    GitResult merge(String sourceBranch, MergeMode mode, String message);

    GitResult rebase(String upstream);
    GitResult rebaseInteractive(RebaseTodo todo);

    // ------------------------------------------------------------------
    // Whatever is in progress: continue, skip, abort
    // ------------------------------------------------------------------

    /**
     * The multi-step operation the repository is part-way through, or
     * {@link OperationState#none()}.
     *
     * <p>Deliberately no default: an engine that silently answered "nothing in progress"
     * would hide the only buttons that can finish or abandon a stopped rebase. Every
     * engine implements it, and {@code BackendConformanceTest} pins that they agree.
     */
    OperationState getOperationState();

    /**
     * Carries on with whatever {@link #getOperationState()} reports, once the user has
     * resolved and staged what stopped it: {@code rebase --continue},
     * {@code cherry-pick --continue}, {@code revert --continue}, {@code am --continue},
     * or for a merge the commit that finishes it (with git's prepared message).
     *
     * <p>Never opens an editor: git's own prepared message is accepted as it stands.
     */
    GitResult continueOperation();

    /** Drops the step the operation stopped on and carries on without it. */
    GitResult skipOperation();

    /**
     * Abandons the operation and puts HEAD, the index and the working tree back where they
     * were before it started - the operation's own {@code --abort}, never a hard reset,
     * which would destroy unrelated local changes and leave the operation's state behind.
     */
    GitResult abortOperation();

    /**
     * The commits an interactive rebase from {@code startHash} would replay, or why it
     * cannot start there - {@link RebaseEligibility#from} says which from the commit's
     * {@link com.jfx.git.model.CommitPosition}, which the graph already has.
     */
    RebaseRange     getRebaseRange(String startHash);

    /**
     * The commits HEAD gained since {@code ancestorHash}, oldest first - the order a
     * follow-up {@link RebaseTodo} needs them in.
     *
     * <p>Built for chaining an interactive rebase onto commits a batch operation just
     * created (cherry-picking with reword/squash - see {@code
     * GitGraphCell.cherryPickSelected}): those commits get new hashes the moment they
     * are created, so a plan built from the originally-selected {@link CommitInfo}s
     * cannot be replayed against them directly. Walking from HEAD back to the hash it
     * held before the batch ran is what recovers them.
     *
     * @return empty if {@code ancestorHash} cannot be resolved, is not an ancestor of
     *         HEAD, or is HEAD itself (nothing gained)
     */
    List<CommitInfo> getCommitsSince(String ancestorHash);

    GitResult cherryPick(String commitHash);

    /**
     * Cherry-picks {@code commitHash}, taking a merge commit's changes relative to parent
     * number {@code mainline} (1-based, git's {@code -m}).
     *
     * <p>A merge has no single diff to replay, so git refuses one without {@code -m}; the
     * graph used to offer the action on merges and it always failed. {@code mainline} of
     * {@code 0} means "the usual choice": parent 1 for a merge - the branch that was
     * merged into - and nothing for an ordinary commit, which is also what
     * {@link #cherryPick(String)} does.
     */
    GitResult cherryPick(String commitHash, int mainline);

    // ------------------------------------------------------------------
    // Patches
    // ------------------------------------------------------------------

    /**
     * Writes {@code commits} to {@code out} as patches in mail form - what
     * {@code git format-patch --stdout} writes - oldest first, so the file applies in
     * the order the work was done whatever order the commits were named in.
     *
     * <p>Each patch carries its commit's author, date and message, so
     * {@link #applyPatch} in {@link com.jfx.git.model.PatchApplyMode#AM} turns it back
     * into that commit. A merge commit is refused: it has a diff per parent and no patch
     * that means "this commit". So is a commit that changes a binary file on an engine
     * that cannot encode one, rather than a patch being written that silently leaves
     * the file out.
     *
     * @param commits full or abbreviated hashes, in any order
     */
    GitResult createPatch(List<String> commits, java.io.File out);

    /**
     * Applies a patch file - one written by {@link #createPatch}, by
     * {@code git format-patch}, or a plain {@code diff}.
     *
     * <p>{@link com.jfx.git.model.PatchApplyMode#AM} makes commits, and can stop on a
     * conflict the way a cherry-pick does: the result is then a conflict, the operation
     * state is {@code APPLYING_MAILBOX}, and {@link #continueOperation},
     * {@link #skipOperation} and {@link #abortOperation} finish it. An engine that
     * cannot make commits from a patch refuses that mode by name.
     */
    GitResult applyPatch(java.io.File patch, com.jfx.git.model.PatchApplyMode mode);

    /** Why a merge commit cannot be saved as a patch; shared so the engines agree. */
    static String mergeHasNoPatch(String shortHash) {
        return shortHash + " is a merge commit. A merge has a diff for each parent and no single"
                + " patch; save the commits it merged instead.";
    }

    GitResult revert(String commitHash);

    /**
     * Reverts {@code commitHash}; for a merge, undoes what it brought in relative to
     * parent {@code mainline} (git's {@code -m}). {@code 0} picks parent 1 for a merge.
     *
     * @see #cherryPick(String, int)
     */
    GitResult revert(String commitHash, int mainline);

    GitResult reset(String commitish, ResetMode mode);

    // ------------------------------------------------------------------
    // Stash
    // ------------------------------------------------------------------

    /**
     * The stash, newest first, with enough detail to draw each entry on the graph.
     *
     * <p>The only stash listing in the contract. A second one returning display strings
     * used to sit beside it; its one caller wanted the count, and placing a stash next to
     * the commit it was taken from needs that commit's hash, which a formatted line would
     * have had to be parsed back out of.
     */
    List<com.jfx.git.model.StashEntry> getStashEntries();

    /**
     * {@code git stash push} with the choices the stash dialog and composer offer.
     *
     * <p>A partial stash follows git's semantics rather than an intuitive reading of
     * them: the stash's index commit records the <em>whole</em> index, the selected paths
     * go back to HEAD in the index and the working tree, and every other path is left
     * exactly as it was. Options {@link StashOptions#problem()} rejects are refused on
     * every engine without touching the repository. Nothing to save is a failure - "No
     * local changes to save" - rather than a success that created nothing.
     */
    GitResult stash(StashOptions options);

    GitResult    stashPop(int index);
    GitResult    stashApply(int index);
    GitResult    stashDrop(int index);

    /**
     * {@code git stash branch <name> stash@{index}}: a new branch at the commit the stash
     * was taken from, checked out, with the stash applied onto it - index included - and
     * then dropped.
     *
     * <p>The way back for a stash that no longer applies where it is: on the commit it
     * was made from, it applies cleanly by construction. If applying still stops (an
     * untracked file in the way, say), the branch stays checked out and the stash stays
     * in the list, as git leaves them, and the result is a conflict.
     */
    GitResult stashBranch(String branchName, int index);

    // ------------------------------------------------------------------
    // Working-tree snapshots
    // ------------------------------------------------------------------

    /**
     * Records the whole working tree - tracked and untracked files, not ignored ones - as
     * a commit whose parent is HEAD, under {@link WorkingTreeSnapshots#REF_PREFIX}.
     *
     * <p>Built through a temporary index, so the real index, the working tree, HEAD and
     * the stash list are all left exactly as they were. A file over
     * {@link WorkingTreeSnapshots#MAX_FILE_BYTES} keeps its staged version; more than
     * {@link WorkingTreeSnapshots#MAX_UNTRACKED_FILES} untracked files and nothing is
     * taken.
     *
     * @param reason what the snapshot is for, shown in the snapshot list
     * @return the new snapshot's id, or null when none was taken: the working tree is the
     *         same as HEAD's, or as the newest snapshot's, or over the untracked cap
     * @throws GitOperationException when git failed to build it
     */
    String snapshotWorkingTree(String reason);

    /** Every snapshot, newest first. */
    List<com.jfx.git.model.Snapshot> listSnapshots();

    /** Deletes the snapshot {@code id}. */
    GitResult deleteSnapshot(String id);

    /**
     * Writes files from snapshot {@code id} back into the working tree, leaving the index
     * alone. Files the snapshot does not have are never deleted.
     *
     * @param paths the files to restore, or null or empty for every file in the snapshot
     */
    GitResult restoreFromSnapshot(String id, List<String> paths);
}
