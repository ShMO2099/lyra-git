package com.jfx.git.model;

/**
 * Where a commit sits relative to the branch that is checked out.
 *
 * <p>One answer serving several menu items, because they all turn on the same fact and
 * each would otherwise pay for its own history walk. {@code onCurrentBranch} is the one
 * that matters most: the graph draws every branch, so most commits on screen are not on
 * the one HEAD points at.
 *
 * @param known  false when the commit could not be read at all
 * @param pushed whether any remote-tracking ref can reach this commit. A pushed commit
 *               rewrites history on force-push, which is why squash warns.
 */
public record CommitPosition(boolean onCurrentBranch, boolean merge, boolean root,
                             boolean known, boolean pushed) {

    /**
     * Nothing could be established about the commit.
     *
     * <p>{@code pushed} is true: it is what puts the force-push warning in front of a
     * history edit, and "could not tell" has to keep that warning rather than skip it.
     */
    public static CommitPosition unknown() {
        return new CommitPosition(false, false, false, false, true);
    }
}
