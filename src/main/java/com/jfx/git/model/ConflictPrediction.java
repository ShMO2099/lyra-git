package com.jfx.git.model;

import java.util.List;

/**
 * What merging one commit into another would leave conflicted, worked out without
 * touching the working tree, the index or HEAD.
 *
 * <p>The answer behind the status bar's early warning: "this branch conflicts with
 * origin/main in three files" while there is still time to do something about it.
 *
 * @param supported whether the engine could answer at all - false on a git too old for
 *                  {@code merge-tree --write-tree}, for histories with no common ancestor,
 *                  and for a revision that does not resolve
 * @param paths     the files that would conflict, sorted; empty for a clean merge
 */
public record ConflictPrediction(boolean supported, List<String> paths) {

    public ConflictPrediction {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    /** The engine cannot say. */
    public static ConflictPrediction unsupported() {
        return new ConflictPrediction(false, List.of());
    }

    /** The merge would go through cleanly. */
    public static ConflictPrediction clean() {
        return new ConflictPrediction(true, List.of());
    }

    /** Whether there is something to warn about. */
    public boolean hasConflicts() {
        return supported && !paths.isEmpty();
    }
}
