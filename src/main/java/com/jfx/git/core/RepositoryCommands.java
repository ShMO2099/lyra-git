package com.jfx.git.core;

import com.jfx.git.model.MergeMode;
import com.jfx.git.model.ResetMode;
import com.jfx.git.model.StashEntry;
import com.jfx.git.model.StashOptions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What callers build out of an engine's primitives: the conveniences and policies the app
 * asks for, written once over any {@link GitBackend}.
 *
 * <h2>Why not on the engine interface</h2>
 * These used to be {@code default} methods on {@link GitBackend}'s capability interfaces,
 * which made each of them something every engine could override - and the composites did:
 * {@link DelegatingGitBackend} forwarded {@code restoreTo} whole to its writer, so the
 * guard that stops the reflog's Undo from destroying uncommitted work was one more thing
 * each engine had to keep. Here nothing overrides them. The engine interface says only
 * what an engine must do; a policy is written, and tested, once.
 *
 * <p>Holds nothing but the backend, so making one per call is as good as keeping one.
 * Every method runs git, so call it where the backend's calls belong: on the repository's
 * {@link GitTaskRunner} lane.
 */
public final class RepositoryCommands {

    private final GitBackend git;

    public RepositoryCommands(GitBackend git) {
        this.git = Objects.requireNonNull(git, "git");
    }

    // ------------------------------------------------------------------
    // Merge and cherry-pick
    // ------------------------------------------------------------------

    /** The merge offered before there was a choice: default, or {@code --no-ff}. */
    public GitResult merge(String sourceBranch, boolean noFastForward) {
        return git.merge(sourceBranch, noFastForward ? MergeMode.NO_FF : MergeMode.DEFAULT, null);
    }

    /**
     * Cherry-picks several commits in the order given, each merge relative to the parent
     * {@code mainlines} names for it (1-based, git's {@code -m}; absent or {@code 0} means
     * "the usual choice", as for {@link IntegrationOps#cherryPick(String, int)}).
     *
     * <p>A batch used to be hashes only, so a merge in it was always taken against parent
     * 1 - right for "bring what this merge brought in", and silently wrong when the user
     * meant the other side. The graph now asks once per merge and passes the answers here.
     *
     * <p>Stops at the first failure and reports it, leaving the repository where git left
     * it - a half-applied sequence is the state the user has to resolve, and pretending
     * otherwise by continuing would bury the conflict under later picks. A stop on a
     * conflict is a conflict, however many picks went before it: the repository is
     * mid-cherry-pick with files to resolve either way. One pick at a time through
     * {@link IntegrationOps#cherryPick(String, int)}, which every engine implements, so
     * each mainline is carried on every engine.
     */
    public GitResult cherryPickAll(List<String> commitHashes, Map<String, Integer> mainlines) {
        if (commitHashes == null || commitHashes.isEmpty()) {
            return GitResult.failed("No commits selected");
        }
        Map<String, Integer> parents = mainlines == null ? Map.of() : mainlines;
        int applied = 0;
        for (String hash : commitHashes) {
            GitResult result = git.cherryPick(hash, parents.getOrDefault(hash, 0));
            if (!result.isOk()) {
                if (applied == 0) return result;
                String sofar = "Applied " + applied + " of " + commitHashes.size()
                        + " commits, then stopped at " + GitBackend.shortenHash(hash) + ": "
                        + result.getMessage();
                return result.isConflict()
                        ? GitResult.conflict(sofar, result.getConflicts())
                        : GitResult.refused(sofar, result.getRefusal());
            }
            applied++;
        }
        return GitResult.ok("Cherry-picked " + applied + " commits");
    }

    // ------------------------------------------------------------------
    // Restore and push
    // ------------------------------------------------------------------

    /**
     * Points the current branch back at {@code targetHash}, discarding what came after.
     *
     * <p>A hard reset, because a soft one would leave the rewritten work staged and make
     * the recovery ambiguous. That makes it destructive to uncommitted work, so it is
     * refused outright while the tree is dirty rather than quietly taking the changes
     * with it - the caller confirms the history part, but nobody gets to lose edits they
     * had not committed. This is the operation the reflog's Undo and the toolbar's
     * Undo/Redo run, which is why the guard lives where no engine can leave it out.
     */
    public GitResult restoreTo(String targetHash, String description) {
        if (targetHash == null || targetHash.isBlank()) {
            return GitResult.failed("There is no earlier state to restore to");
        }
        try {
            if (git.hasUncommittedChanges()) {
                return GitResult.failed(
                        "Commit or stash your changes first - restoring discards them.");
            }
        } catch (GitOperationException e) {
            return GitResult.failed("Could not check the working tree: " + e.getMessage());
        }
        GitResult reset = git.reset(targetHash, ResetMode.HARD);
        if (!reset.isOk()) {
            return reset.isConflict() ? reset : GitResult.failed("Could not restore: " + reset.getMessage());
        }
        String branch = git.getCurrentBranch();
        return GitResult.ok("Restored " + (branch == null || branch.isBlank() ? "HEAD" : branch)
                + " to " + GitBackend.shortenHash(targetHash)
                + (description == null || description.isBlank() ? "" : " (" + description + ")"));
    }

    /**
     * Pushes the current branch, capturing the lease itself when {@code force} is set.
     *
     * <p>For callers with nothing to confirm in between. Anything that shows a
     * confirmation should take {@link RemoteOps#pushLease} when it asks and push with
     * {@link RemoteOps#push(String, PushLease, boolean, boolean)}, so that what the user
     * agreed to overwrite is what actually gets overwritten.
     *
     * @see RemoteOps#push(String, PushLease, boolean, boolean) for where it goes
     */
    public GitResult push(String remote, boolean force, boolean includeTags, boolean setUpstream) {
        if (!force) {
            return git.push(remote, (PushLease) null, includeTags, setUpstream);
        }
        PushLease lease = git.pushLease(remote);
        if (lease == null) {
            return GitResult.failed(RemoteOps.noLeaseExplanation(remote));
        }
        return git.push(remote, lease, includeTags, setUpstream);
    }

    // ------------------------------------------------------------------
    // Stash
    // ------------------------------------------------------------------

    /** Stashes everything, untracked files included, with git's generated description. */
    public GitResult stash() {
        return git.stash(StashOptions.everything(null));
    }

    /**
     * Pops the stash whose commit is {@code hash}.
     *
     * <h2>Why a hash and not a position</h2>
     * {@code stash@{n}} is a position in a list git renumbers on every drop or pop, and
     * the number a menu item carries was read when its row was drawn. Between the click
     * and the refresh that follows it, the visible rows still say what they said and the
     * repository no longer agrees: with four stashes, dropping the second and then
     * clicking the row still labelled {@code stash@{2}} drops what is by then the fourth
     * one - a stash the user never pointed at, and one that only {@code git fsck} can get
     * back.
     *
     * <p>The window is one round trip wide, which is why no test has caught it and why
     * the stash panel is exactly where it bites: the entries are small, the list is
     * short, and people clear them in a burst.
     *
     * <p>So the position is resolved on the lane, immediately before use, from the commit
     * the user actually chose. If it is no longer in the stash, that is said plainly
     * rather than acted on approximately.
     */
    public GitResult stashPop(String hash) {
        int index = indexOfStash(hash);
        return index < 0 ? stashIsGone() : git.stashPop(index);
    }

    /** Applies the stash whose commit is {@code hash}. See {@link #stashPop(String)}. */
    public GitResult stashApply(String hash) {
        int index = indexOfStash(hash);
        return index < 0 ? stashIsGone() : git.stashApply(index);
    }

    /** Drops the stash whose commit is {@code hash}. See {@link #stashPop(String)}. */
    public GitResult stashDrop(String hash) {
        int index = indexOfStash(hash);
        return index < 0 ? stashIsGone() : git.stashDrop(index);
    }

    /**
     * {@link IntegrationOps#stashBranch(String, int)} for the stash whose commit is
     * {@code hash}. See {@link #stashPop(String)}.
     */
    public GitResult stashBranch(String branchName, String hash) {
        int index = indexOfStash(hash);
        return index < 0 ? stashIsGone() : git.stashBranch(branchName, index);
    }

    /** Where {@code hash} sits in the stash now, or -1 if it is not there. */
    private int indexOfStash(String hash) {
        if (hash == null || hash.isBlank()) return -1;
        for (StashEntry entry : git.getStashEntries()) {
            if (hash.equals(entry.hash())) return entry.index();
        }
        return -1;
    }

    private static GitResult stashIsGone() {
        return GitResult.failed("That stash is no longer in the list - it was applied,"
                + " dropped or renumbered since this menu was opened. Refresh and try again.");
    }

    // ------------------------------------------------------------------
    // LFS
    // ------------------------------------------------------------------

    /**
     * Whether this repository's root {@code .gitattributes} declares any {@code
     * filter=lfs} rule at all - a coarse, cheap "does this repository use LFS" signal for
     * a banner, read through {@link HistoryOps#getWorkingTreeBytes}. Only the root file:
     * {@code git lfs track} always writes there, and walking every nested
     * {@code .gitattributes} in a large repository is not worth paying for to decide
     * whether to show one banner once per open.
     */
    public boolean hasAnyLfsTrackedPaths() {
        byte[] bytes = git.getWorkingTreeBytes(".gitattributes");
        return bytes.length > 0 && new String(bytes, StandardCharsets.UTF_8).contains("filter=lfs");
    }
}
