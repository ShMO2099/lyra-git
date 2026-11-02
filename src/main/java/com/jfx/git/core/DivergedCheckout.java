package com.jfx.git.core;

/**
 * The refusal a pull-request checkout gives when the local branch and the pull request
 * have diverged, written by both engines and read back by the dialogs that ask what to
 * do about it.
 *
 * <p>Built only here, so the engines cannot drift apart in wording without the
 * conformance test noticing: one message in one shape for the person, and a
 * {@link Refusal.Diverged} beside it for the dialog that asks what to do. It used to be
 * the message alone, read back with a pattern, because a {@link GitResult} carried only
 * a status and text.
 */
public final class DivergedCheckout {

    private DivergedCheckout() {
    }

    /**
     * The refusal for {@code localName}, which has {@code localOnly} commits that
     * {@code what} ("pull request #7", "refs/merge-requests/3/head") does not.
     */
    public static GitResult refusal(String localName, String what, int localOnly) {
        return GitResult.refused("Not checked out: the local branch " + localName + " has "
                + localOnly + (localOnly == 1 ? " commit" : " commits") + " that " + what
                + " does not, and " + what + " has moved on without them. Updating the branch"
                + " would discard those commits, so it was left as it is.",
                new Refusal.Diverged(localName, localOnly));
    }

    /**
     * How many commits of its own the local branch has, when {@code result} is this
     * refusal; {@code -1} for any other result.
     */
    public static int localCommits(GitResult result) {
        return result != null && result.getRefusal() instanceof Refusal.Diverged diverged
                ? diverged.localCommits() : -1;
    }
}
