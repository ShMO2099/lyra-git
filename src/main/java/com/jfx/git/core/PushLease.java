package com.jfx.git.core;

import java.util.List;

/**
 * What a forced push expects to find on the remote before it overwrites anything.
 *
 * <p>A force push without a lease is just {@code --force}: it discards whatever the
 * remote had, including commits this clone has never seen. The lease is the promise that
 * makes force safe - "overwrite {@code refs/heads/main} only if it is still at
 * {@code abc123}, the commit I last fetched" - and it has to be an <em>explicit</em>
 * object id. Treating "no remote-tracking ref, so no lease" as "force away" is how the
 * protection silently disappears exactly when it matters, on the branch nobody has
 * fetched.
 *
 * <p>The lease is captured before the user is asked to confirm, and handed back to
 * {@link RemoteOps#push(String, PushLease, boolean, boolean)} afterwards. Recomputing it
 * inside the push would let a background fetch move the remote-tracking ref in between,
 * so the user would confirm overwriting one commit and the push would lease a different
 * one - which is the caveat git itself documents for {@code --force-with-lease}.
 *
 * <h2>Why a lease alone is not enough</h2>
 * The lease says "the remote is still where this clone last saw it". It does not say the
 * user ever <em>looked</em>: auto-fetch moves {@code origin/feature} without anything on
 * screen changing, so a colleague's commit can arrive there, become the lease, and be
 * overwritten by a force push from someone who never had it. That is the gap git closes
 * with {@code --force-if-includes}, and this record carries the same answer:
 * {@link #integrated()} is whether the leased commit is reachable from the local branch
 * or from its reflog - whether it was ever part of this branch here. When it was not,
 * {@link #wouldDiscard()} lists what the push would throw away, and the push is refused
 * until the user has seen that list and said so ({@link #confirmingDiscard()}).
 *
 * @param remote            the remote the lease applies to
 * @param branch            the local branch being pushed
 * @param remoteBranch      the branch on the remote the push writes - not always the
 *                          same name; see {@link PushTarget}
 * @param expectedObjectId  the commit the remote branch must still be at, or {@code null}
 *                          when the expectation is that it does not exist yet
 * @param integrated        whether {@code expectedObjectId} is reachable from the local
 *                          branch or its reflog; always true for an absent branch
 * @param wouldDiscard      the commits on the remote branch that the local branch does
 *                          not contain, as {@code "abbrev subject"} lines, newest first,
 *                          capped; what a force push removes from the remote
 * @param discardConfirmed  whether the user has seen {@code wouldDiscard} for a lease
 *                          that is not integrated, and chosen to overwrite it anyway
 */
public record PushLease(String remote, String branch, String remoteBranch, String expectedObjectId,
                        boolean integrated, List<String> wouldDiscard, boolean discardConfirmed) {

    /** How many discarded commits a lease lists before it stops counting individually. */
    public static final int DISCARD_LIST_LIMIT = 20;

    public PushLease {
        remoteBranch = remoteBranch == null || remoteBranch.isBlank() ? branch : remoteBranch;
        wouldDiscard = wouldDiscard == null ? List.of() : List.copyOf(wouldDiscard);
    }

    /** A lease on a same-named remote branch, with nothing yet known about integration. */
    public PushLease(String remote, String branch, String expectedObjectId) {
        this(remote, branch, branch, expectedObjectId, true, List.of(), false);
    }

    /** A lease on a remote branch known to be at {@code expectedObjectId}. */
    public static PushLease at(String remote, String branch, String expectedObjectId) {
        return new PushLease(remote, branch, expectedObjectId);
    }

    /** A lease on {@code remoteBranch} of {@code remote}, pushed from local {@code branch}. */
    public static PushLease at(String remote, String branch, String remoteBranch,
                               String expectedObjectId) {
        return new PushLease(remote, branch, remoteBranch, expectedObjectId, true, List.of(), false);
    }

    /**
     * A lease saying the remote has no such branch.
     *
     * <p>Publishing a branch is not an overwrite, so this needs no force at all: an
     * ordinary push already fails if the branch turned up in the meantime, which is the
     * same protection by a shorter route.
     */
    public static PushLease absent(String remote, String branch) {
        return absent(remote, branch, branch);
    }

    public static PushLease absent(String remote, String branch, String remoteBranch) {
        return new PushLease(remote, branch, remoteBranch, null, true, List.of(), false);
    }

    /** This lease, with what the engine found about whether its commit was ever integrated. */
    public PushLease withIntegration(boolean isIntegrated, List<String> discarded) {
        return new PushLease(remote, branch, remoteBranch, expectedObjectId, isIntegrated,
                discarded, discardConfirmed);
    }

    /**
     * This lease, with the user's explicit agreement to overwrite commits that were never
     * part of the local branch. Only for a caller that has shown {@link #wouldDiscard()}.
     */
    public PushLease confirmingDiscard() {
        return new PushLease(remote, branch, remoteBranch, expectedObjectId, integrated,
                wouldDiscard, true);
    }

    /** Whether this lease expects the remote branch not to exist. */
    public boolean expectsNoRemoteBranch() {
        return expectedObjectId == null;
    }

    /** The full ref name the lease is against, on the remote. */
    public String ref() {
        return "refs/heads/" + remoteBranch;
    }

    /**
     * Why a force push under this lease must not go ahead yet, or {@code null} when it
     * may: the leased commit was never part of the local branch, and nobody has agreed to
     * discard it. Shared so both engines refuse with the same words.
     */
    public String refusalIfNotIntegrated() {
        if (expectsNoRemoteBranch() || integrated || discardConfirmed) return null;
        StringBuilder text = new StringBuilder("Force push refused: ")
                .append(remote).append('/').append(remoteBranch)
                .append(" has commits this branch never contained, so forcing would discard"
                        + " work you have not seen. Pull or rebase onto them first.");
        if (!wouldDiscard.isEmpty()) {
            text.append(" They are:");
            for (String line : wouldDiscard) {
                text.append("\n  ").append(line);
            }
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return expectsNoRemoteBranch()
                ? ref() + " (must not exist on " + remote + ")"
                : ref() + "@" + expectedObjectId + " on " + remote;
    }
}
