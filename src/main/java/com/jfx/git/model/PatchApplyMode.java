package com.jfx.git.model;

/** Where an applied patch lands. */
public enum PatchApplyMode {
    /**
     * The changes are written to the working tree and left unstaged - {@code git apply}.
     * For looking at what a patch does before deciding anything.
     */
    WORKTREE("To the working tree, unstaged"),
    /**
     * The changes are written to the working tree and staged, ready to commit under
     * your own name and message - {@code git apply --index}.
     */
    INDEX("To the working tree, staged"),
    /**
     * Each patch in the file becomes a commit with its original author, date and
     * message - {@code git am}. Needs a patch that carries those: one saved from a
     * commit, not a bare diff.
     */
    AM("As commits, keeping their authors and messages");

    private final String label;

    PatchApplyMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
