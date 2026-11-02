package com.jfx.git.ui;

/**
 * What a {@link LoadedDiff} compares, which is what decides the hunk actions it offers.
 *
 * <p>The kind travels with the diff rather than being configured on the viewer beside it.
 * A handler installed on the viewer outlived the diff it was installed for, so a commit's
 * diff opened after the staging area could still offer to stage its hunks.
 */
public enum DiffKind {
    /** The index against the working tree: stage, or discard. */
    UNSTAGED,
    /** HEAD against the index: unstage. */
    STAGED,
    /** An unmerged path: its stages against each other, which describes no single file. */
    CONFLICT,
    /** A commit against its first parent: revert, into the working tree. */
    COMMIT,
    /** A stash against the commit it was made on. */
    STASH,
    /** Two revisions chosen to compare. */
    COMPARISON,
    /** A working-tree snapshot against today's working tree. */
    SNAPSHOT;

    /**
     * Whether a reload of the same path is the same change seen again, newer, rather than
     * a different one - so context the user revealed in it can stay revealed.
     *
     * <p>A commit, a stash or a comparison is fixed: another of the same kind for the same
     * path is the same change only if it is the same diff (File history stepping from
     * commit to commit is not).
     */
    boolean isLive() {
        return this == UNSTAGED || this == STAGED || this == CONFLICT;
    }
}
