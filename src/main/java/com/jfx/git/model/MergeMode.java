package com.jfx.git.model;

/**
 * How a local merge should join the other branch in: the choice {@code git merge} offers
 * through {@code --ff-only}, {@code --no-ff} and {@code --squash}.
 *
 * <p>{@link #DEFAULT} passes none of them, so {@code merge.ff} and
 * {@code branch.<name>.mergeoptions} decide exactly as they do on the command line. Each
 * of the others is the user overriding that configuration for this one merge, which is
 * what the flag does in git too.
 */
public enum MergeMode {

    /** Whatever {@code merge.ff} says; out of the box, fast-forward when possible. */
    DEFAULT("Default (as configured)",
            "Fast-forwards when the branch is simply ahead, otherwise makes a merge commit - "
                    + "unless merge.ff in this repository's configuration says otherwise."),

    /** {@code --ff-only}: move the branch forward or do nothing at all. */
    FF_ONLY("Fast-forward only",
            "Moves this branch up to the other one. If they have diverged nothing is "
                    + "merged, and no merge commit is made."),

    /** {@code --no-ff}: a merge commit even when a fast-forward was possible. */
    NO_FF("Always create a merge commit",
            "Records the merge as its own commit even when a fast-forward was possible, so "
                    + "the branch stays visible as a unit in the history."),

    /**
     * {@code --squash}: the combined changes staged on this branch, with no commit made
     * and no record that a merge happened.
     */
    SQUASH("Squash",
            "Stages everything the branch changed as one set of changes and stops before "
                    + "committing. Nothing records the merge, so the branch still reads as "
                    + "unmerged afterwards. The message is offered in the commit box.");

    private final String label;
    private final String explanation;

    MergeMode(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }

    /** What the choice is called where the user picks it. */
    public String label() {
        return label;
    }

    /** One or two sentences on what the choice will do, for the line under the picker. */
    public String explanation() {
        return explanation;
    }

    /**
     * Whether a message typed for this merge can end up on a commit.
     *
     * <p>Fast-forward only never makes a commit, so a message would be silently dropped;
     * the dialog disables the field rather than accept text it is going to throw away.
     * A squash's message becomes the subject the commit box is pre-filled with.
     */
    public boolean takesMessage() {
        return this != FF_ONLY;
    }

    @Override
    public String toString() {
        return label;
    }
}
