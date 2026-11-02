package com.jfx.git.core;

import com.jfx.git.model.PullMode;
import java.util.List;

/**
 * Anything that talks to a remote, plus the remote list itself.
 *
 * <p>One of the capability interfaces {@link GitBackend} composes; see its javadoc for
 * why the contract is split up rather than kept as one list.
 */
public interface RemoteOps {

    // ------------------------------------------------------------------
    // Remotes and network
    // ------------------------------------------------------------------

    List<String> getRemoteNames();

    /** The configured push/fetch URL for {@code remoteName}, or {@code null} if it has none. */
    String getRemoteUrl(String remoteName);

    /**
     * The URL pushes to {@code remoteName} go to when it has one of its own
     * ({@code remote.<name>.pushurl}), or {@code null} when pushes use the fetch URL.
     */
    String getRemotePushUrl(String remoteName);

    GitResult addRemote(String name, String uri);
    GitResult removeRemote(String name);

    /**
     * Renames a remote, taking everything that refers to it along: its remote-tracking
     * branches, its fetch refspecs, and every branch whose upstream or push remote it is
     * - {@code git remote rename}.
     *
     * <p>Refused when {@code oldName} is not a remote, when {@code newName} already is
     * one, or when {@code newName} is not a name a ref can carry.
     */
    GitResult renameRemote(String oldName, String newName);

    /**
     * Changes where {@code name} is fetched from or, with {@code push}, where it is
     * pushed to - {@code git remote set-url [--push]}.
     *
     * <p>A blank {@code url} with {@code push} removes the separate push URL, so pushes
     * go to the fetch URL again. A blank {@code url} without it is refused: a remote has
     * to be fetched from somewhere. So is a remote that does not exist, rather than one
     * being created with a URL and nothing else.
     */
    GitResult setRemoteUrl(String name, String url, boolean push);

    /**
     * Fetches one remote, pruning the branches it no longer has; {@link #fetchAll()} for
     * a single name.
     */
    GitResult fetchRemote(String name);

    /**
     * Deletes {@code branch} on {@code remote} - {@code git push <remote>
     * :refs/heads/<branch>} - and with it this clone's remote-tracking ref for it. Local
     * branches are not touched, including one of the same name that tracks it.
     *
     * <p>A push like any other: a {@code pre-push} hook sees it, and an engine that
     * cannot run one refuses rather than skip it.
     *
     * <p>A branch the remote does not have is a failure that says so - see
     * {@link #alreadyGoneExplanation} - but this clone's stale record of it is removed
     * all the same, since that record is what the user was looking at.
     *
     * @param branch the branch's name on the remote, without the remote's own name:
     *               {@code feature/x}, not {@code origin/feature/x}
     */
    GitResult deleteRemoteBranch(String remote, String branch);

    /**
     * What both engines say when asked to delete a branch the remote does not have.
     *
     * <p>Shared because left to themselves they disagree: git sends the deletion, is
     * told "deleting a non-existent ref" as a warning and exits 0, while JGit reports
     * the update as rejected.
     */
    static String alreadyGoneExplanation(String remote, String branch) {
        return remote + " has no branch named " + branch
                + ". It may already have been deleted there.";
    }

    GitResult fetchAll();

    /**
     * Fetches every remote without ever putting up a credential or host-key prompt.
     *
     * <p>For a scheduled background fetch, which runs on the same single {@link
     * GitTaskRunner} worker as every user-initiated git action. A prompt nothing is
     * watching for would sit there indefinitely, and every later action - including a
     * plain commit - would queue silently behind it. Failing fast when a remote needs
     * something this session has not already supplied is the point, not a shortcoming:
     * the next user-initiated fetch or pull prompts normally and, once answered, this
     * method benefits from the same cache where the engine keeps one.
     */
    GitResult fetchAllSilently();
    GitResult pull(PullMode mode);

    /**
     * The expectation a force push to {@code remote} would be held to, or {@code null}
     * when none can be formed and a forced push must therefore be refused.
     *
     * <p>Separate from {@link #push} so the UI can capture the lease at the moment it asks
     * the user to confirm, rather than letting a background fetch redefine it in between.
     *
     * <p>{@code null} covers two cases that both mean "do not force": a detached HEAD, and
     * a branch that exists on the remote but has no remote-tracking ref here. The second
     * is the important one - it is exactly the case where force-with-lease used to
     * degrade into a plain force.
     *
     * <p>The lease is taken on the branch {@link PushTarget} says the push writes, not on
     * a same-named branch of {@code origin}, and carries whether its commit was ever part
     * of the local branch - see {@link PushLease#integrated()}.
     *
     * @param remote the remote to push to, or {@code null} to follow the branch's
     *               configuration the way {@code git push} does
     */
    PushLease pushLease(String remote);

    /**
     * Pushes the current branch, forcing only within {@code lease}.
     *
     * <p>Where it goes is {@link PushTarget}'s answer: the configured push remote and the
     * configured remote branch, not {@code origin} and the same name. A branch with no
     * upstream yet is published - its upstream recorded - whatever {@code setUpstream}
     * says; one that already has an upstream keeps it unless {@code setUpstream} asks.
     *
     * @param remote      the remote to push to, or {@code null} to follow configuration
     * @param lease       {@code null} for an ordinary push; otherwise the expectation captured
     *                    by {@link #pushLease}, which must still describe the current branch
     *                    and the same target. A lease whose commit was never integrated
     *                    locally is refused unless {@link PushLease#discardConfirmed()}.
     * @param includeTags also push the annotated tags that point into what is pushed and
     *                    that the remote lacks ({@code --follow-tags}); a tag the remote
     *                    already has at a different commit is reported, not fatal
     * @param setUpstream record the pushed branch as the upstream even when the branch
     *                    already tracks something else
     */
    GitResult push(String remote, PushLease lease, boolean includeTags, boolean setUpstream);

    /** Why a forced push was refused; shared so the engines and {@link RepositoryCommands} say the same thing. */
    static String noLeaseExplanation(String remote) {
        // Null means "wherever this branch is configured to push", which is not
        // necessarily origin any more; naming origin there would send the user to look
        // at the wrong remote.
        String target = remote == null || remote.isBlank() ? "the remote" : remote;
        return "Force push refused: this clone has no record of what " + target
                + " currently has for this branch, so there is nothing to force safely "
                + "against. Fetch " + target + " first, review what arrives, then force "
                + "push.";
    }

    /**
     * Fetches a GitHub pull request's head commit from {@code remoteName} - GitHub
     * exposes every PR at {@code refs/pull/<number>/head} whether or not its source
     * branch still exists - and checks it out as a local branch named {@code pr-<number>},
     * saying what to do when that branch already exists and has diverged from the pull
     * request.
     *
     * <p>A branch that is only behind is fast-forwarded and one that is only ahead is left
     * alone, either way. A diverged one is refused unless {@code resetToFetched}: the
     * refusal is a {@link DivergedCheckout} - a {@link Refusal.Diverged} carrying how many
     * local commits the branch has - so the caller can ask. With {@code resetToFetched} the branch is moved
     * onto the pull request's head - its own commits stay reachable in its reflog - and
     * checked out; uncommitted changes are carried over or the checkout refuses, as a
     * plain {@code git checkout} would, never overwritten.
     */
    GitResult checkoutPullRequest(String remoteName, int number, boolean resetToFetched);

    /**
     * Fetches an arbitrary ref from a remote - by configured name, or by a raw URL for
     * a source with no permanent remote at all - into a private tracking ref, then
     * checks it out as {@code localBranchName}, with the same choice for a diverged
     * {@code localBranchName} that {@link #checkoutPullRequest} offers. The general form
     * behind {@link #checkoutPullRequest}, needed
     * because not every provider exposes a PR/MR at a predictable numbered ref the way
     * GitHub does: GitLab's merge requests do (at {@code refs/merge-requests/<iid>
     * /head}), but Bitbucket Cloud has no equivalent, so a Bitbucket checkout resolves
     * to this method with a plain {@code refs/heads/<branch>} - against {@code origin}
     * when the source is the same repository, or against the source's own clone URL
     * directly when it is a fork, verified to work by fetching from a raw URL with no
     * remote configured for it at all.
     */
    GitResult checkoutRemoteRef(String remoteNameOrUrl, String remoteRef, String localBranchName,
                                boolean resetToFetched);

    /**
     * Fetches {@code remoteRef} from {@code remoteNameOrUrl} into a disposable local
     * tracking ref, without checking anything out or touching the working tree or
     * HEAD - unlike {@link #checkoutRemoteRef}, which does both. Reviewing a PR/MR's
     * diff needs its base and head commits present locally, but must not disturb
     * whatever the user currently has checked out to get them; this is that half of
     * {@link #checkoutRemoteRef} on its own.
     */
    GitResult fetchRemoteRef(String remoteNameOrUrl, String remoteRef);

    /**
     * The best common ancestor of two commits, or {@code null} when they have none or
     * either cannot be resolved.
     *
     * <p>What a pull request's diff is taken against. Diffing from the base branch's tip
     * shows every change made on the base since the branch point as a revert inside the
     * pull request; the providers themselves diff from the merge-base, and a review here
     * has to show what they show.
     */
    String mergeBase(String commitA, String commitB);
}
