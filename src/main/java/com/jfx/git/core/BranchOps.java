package com.jfx.git.core;

import com.jfx.git.model.RefInfo;
import java.util.List;

/**
 * Branches, remote-tracking branches and tags: listing them, switching between them,
 * and creating, renaming, moving or deleting them.
 *
 * <p>One of the capability interfaces {@link GitBackend} composes; see its javadoc for
 * why the contract is split up rather than kept as one list.
 */
public interface BranchOps {

    // ------------------------------------------------------------------
    // Branches
    // ------------------------------------------------------------------

    List<RefInfo> getLocalBranches();

    /**
     * Remote-tracking branches, minus the refs the app fabricated for itself.
     *
     * @see GitBackend#isSyntheticRemoteRef(String)
     */
    List<RefInfo> getRemoteBranches();

    List<RefInfo> getTags();

    /**
     * The current branch: the short name of the branch HEAD points at - {@code main} - an
     * unborn one included.
     *
     * <p><b>Blank ({@code ""}) while HEAD is detached</b>, on every engine: a detached HEAD
     * has no current branch, and the commit it sits on is not a branch name - the engines
     * used to answer with its hash, full on one and short on another, and callers built
     * {@code refs/heads/<hash>} from it. Blank too when the repository cannot be read.
     * Never {@code null}. What the user reads instead - {@code Detached at abc1234} - is
     * {@link com.jfx.git.model.RefNames#headLabel}'s; {@link #isHeadDetached()} tells a
     * detached HEAD from an unreadable one. {@code BackendConformanceTest} and
     * {@code RefSnapshotConformanceTest} pin this across the engines.
     *
     * <p>A refresh's views do not call this: the graph page carries the current branch in
     * its {@link com.jfx.git.model.RefSnapshot}. It is for an operation that needs the
     * answer as of now, on the repository lane.
     */
    String getCurrentBranch();

    /**
     * Ahead/behind counts for the current branch's upstream.
     *
     * @return {@code int[]{ahead, behind}}, or both zero when there is no upstream
     */
    int[] getTrackingStatus();

    GitResult checkoutBranch(String branchName);

    /**
     * True when HEAD points at a commit rather than a branch — the detached
     * HEAD state. Commits made in this state are on no branch and can be lost on the
     * next checkout, which is why the UI shows a persistent banner (§3.4 of the parity
     * spec). False for an unborn repository too: there is nothing to lose yet.
     */
    boolean isHeadDetached();

    /**
     * The ref the symbolic ref {@code fullName} points at - {@code refs/remotes/origin/main}
     * for {@code refs/remotes/origin/HEAD} - or {@code null} when there is no such ref or
     * it is not symbolic.
     *
     * <p>Asked of the ref store rather than read as a file: a repository that keeps its refs
     * in a reftable has no file to read, and no engine lists a symbolic ref among the
     * branches.
     */
    String symbolicRefTarget(String fullName);

    /**
     * True when the current (detached) HEAD commit is not an ancestor of any local
     * branch or tag — i.e. checking out something else right now would leave it
     * reachable only through reflog. Commercial clients warn before that step; this is the
     * check that decides whether the warning is needed. Always false while HEAD is
     * attached, since leaving a branch tip never orphans it.
     */
    boolean isDetachedHeadOrphanRisk();

    GitResult checkoutRemoteBranch(String remoteBranch);
    GitResult createBranch(String name, String startPoint, boolean checkout);
    GitResult deleteBranch(String name, boolean force);
    GitResult renameBranch(String oldName, String newName);

    /**
     * The branch {@code localBranch} is configured to track, as a short name -
     * {@code origin/main} - or {@code null} when it tracks nothing.
     *
     * <p>What the configuration says, whether or not that branch still exists on the
     * remote: an upstream that has been deleted there is still the upstream, and is the
     * one a user most needs to be shown so they can change it.
     */
    String getUpstream(String localBranch);

    /**
     * {@link #getUpstream} for every local branch at once: short branch name to the short
     * name of what it tracks. A branch that tracks nothing has no entry.
     *
     * <p>One call rather than one per branch, because the sidebar asks on every refresh
     * and on the CLI engine each single answer is a subprocess.
     */
    java.util.Map<String, String> getUpstreams();

    /**
     * Makes {@code localBranch} track {@code upstream} - {@code git branch
     * --set-upstream-to}. Pull then merges from it, and the ahead and behind counts are
     * taken against it.
     *
     * <p>Refused when either branch does not exist. Nothing is fetched or pushed.
     *
     * @param upstream a remote-tracking branch's short name, {@code origin/main}
     */
    GitResult setUpstream(String localBranch, String upstream);

    /**
     * Makes {@code localBranch} track nothing - {@code git branch --unset-upstream}.
     * Refused when it already tracks nothing, so the caller hears that nothing changed.
     */
    GitResult unsetUpstream(String localBranch);

    /**
     * The local branches whose upstream has gone: one is configured, but the
     * remote-tracking branch it names no longer exists here - deleted on the server and
     * pruned by a fetch, typically after its pull request was merged. What {@code git
     * branch -vv} marks {@code [gone]}.
     *
     * <p>A branch that tracks nothing is not gone; neither is one whose remote has been
     * removed from the configuration altogether, since git then no longer works out an
     * upstream for it at all.
     *
     * <p>Abstract: a default answering "none" would make the branch cleanup screen report
     * a tidy repository on an engine that never looked.
     *
     * @return short branch names, sorted
     */
    java.util.Set<String> getGoneBranches();

    /**
     * The local branches whose tip {@code target} already contains - {@code git
     * for-each-ref --merged=<target> refs/heads}: deleting one of them loses no commit
     * that {@code target} does not have.
     *
     * <p>{@code target} itself is included when it is a local branch, as git includes it;
     * the caller decides what it leaves out.
     *
     * @param target any revision: {@code main}, {@code origin/main}, a hash
     * @return short branch names, sorted; empty when {@code target} does not resolve
     */
    List<String> getBranchesMergedInto(String target);

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    GitResult createTag(String name, String message, String commitish);
    GitResult deleteTag(String name);

    /**
     * The object ID stored directly in {@code refs/tags/<tagName>}, or {@code null} when
     * that tag does not exist. Unlike {@link RefInfo#targetHash()}, this is not peeled:
     * for an annotated tag it names the tag object itself.
     *
     * <p>Backends that cannot inspect raw tag refs must say so explicitly rather than
     * returning {@code null}, which means that the tag is absent.
     */
    default String getRawTagObjectId(String tagName) {
        throw new UnsupportedOperationException("Reading raw tag object IDs is unsupported by this backend");
    }

    /**
     * Creates {@code refs/tags/<tagName>} pointing directly at {@code objectId}, preserving
     * the original object (including an annotated tag's raw message, tagger and signature).
     * Fails if the ref already exists, so a delayed Undo cannot replace a newer tag.
     *
     * <p>Backends that cannot restore exact tag refs report an unsupported-operation
     * failure rather than silently recreating a different tag object.
     */
    default GitResult restoreTagRef(String tagName, String objectId) {
        return GitResult.failed("Exact tag ref restoration is unsupported by this backend");
    }

    /**
     * Deletes {@code tagName} from {@code remoteName} - {@code git push <remote>
     * :refs/tags/<tagName>} - without touching the local tag. Commercial clients treat
     * this as a distinct, separately-confirmed action from a local delete: pushing a
     * tag does not mean it stays gone if only removed locally.
     */
    GitResult deleteRemoteTag(String remoteName, String tagName);

    /**
     * Pushes {@code tagName} to {@code remoteName} - {@code git push <remote>
     * refs/tags/<tagName>} - without pushing the current branch or any other tag.
     * The single-tag counterpart to the toolbar's bulk "Push with tags"
     * ({@link #push}'s {@code includeTags} flag), for a tag created or moved after
     * the last such push.
     */
    GitResult pushTag(String remoteName, String tagName);

    /**
     * Whether moving {@code tagName} to {@code newCommitish} would be a fast-forward -
     * i.e. the tag's current commit is an ancestor of the new one, or the tag already
     * sits there. False when the two commits are unrelated, or the new one is an
     * ancestor of the old (moving the tag backward) - a real, more surprising rewrite
     * that a caller should confirm more emphatically than an ordinary move, the same
     * "fast-forward is quiet, anything else asks first" distinction a branch checkout
     * or a push already makes.
     */
    boolean isTagMoveFastForward(String tagName, String newCommitish);

    /**
     * Moves {@code tagName} to point at {@code newCommitish}. Always a real delete-
     * and-recreate under the hood - an annotated tag's object is immutable content,
     * so nothing can be repointed in place - preserving the original message when the
     * tag being moved was annotated. Does not refuse a non-fast-forward move itself;
     * a caller should check {@link #isTagMoveFastForward} first and confirm with the
     * user before calling this for one, the same division of responsibility
     * {@code createBranch} already has with an unusual start point.
     */
    GitResult moveTag(String tagName, String newCommitish);
}
