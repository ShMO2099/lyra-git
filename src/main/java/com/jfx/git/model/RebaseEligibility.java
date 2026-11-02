package com.jfx.git.model;

/**
 * Whether an interactive rebase can start from a given commit, and if not, why.
 *
 * <p>Separate from running one so the menu can ask before offering the action. The
 * distinction between the reasons matters to the caller: a commit on another branch is
 * simply not what this menu is for, while a merge on the current branch is worth showing
 * as unavailable so the user learns why.
 */
public enum RebaseEligibility {
    /** The commit is on this branch: replay it and everything after it. */
    OK(null),
    /**
     * The commit is elsewhere: replay this branch's own commits on top of it.
     *
     * <p>Not a refusal. It is the other half of what a rebase does, and the half a user
     * reaches for when moving work onto someone else's branch.
     */
    ONTO(null),
    MERGE_COMMIT("a merge commit"),
    ROOT_COMMIT("the first commit"),
    UNAVAILABLE("unavailable");

    private final String reason;

    RebaseEligibility(String reason) {
        this.reason = reason;
    }

    /** Short phrase for a menu label, or {@code null} when the action is available. */
    public String reason() {
        return reason;
    }

    /**
     * What a rebase from a commit at {@code position} would be, from the position alone.
     *
     * <p>A function of the position and nothing else, so it needs no engine. Both engines
     * carried a verbatim copy behind {@code checkRebaseFrom(CommitPosition)}, which made
     * the graph's context menu an engine call on the FX thread for no reason.
     */
    public static RebaseEligibility from(CommitPosition position) {
        if (position == null || !position.known()) return UNAVAILABLE;
        if (position.merge()) return MERGE_COMMIT;
        if (!position.onCurrentBranch()) return ONTO;
        if (position.root()) return ROOT_COMMIT;
        return OK;
    }
}
