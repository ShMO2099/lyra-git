package com.jfx.git.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All staging information from a single status call.
 *
 * <p>The staging panel once called {@code getUnstagedFiles()}, {@code getStagedFiles()}
 * and {@code getConflictingFiles()} separately, each paying the full cost of a status
 * scan on the FX thread. This bundles them so the panel can load its entire model from
 * one background query.
 *
 * <p>{@code branch} and {@code midOperation} are here for the same reason, one round
 * later. The commit button names the branch it will commit to and has to stay enabled
 * for a merge that resolved to nothing, so it asked the backend for both every time the
 * staged count changed - on the FX thread, on every click, and on the default engine
 * that is a {@code git status} subprocess and a second {@code git} call per click. Both
 * answers belong to the refresh that produced these lists and are all but free to
 * collect there, so they travel with it.
 *
 * @param branch       the branch a commit would land on - the current branch, as
 *                     {@code GitBackend.getCurrentBranch()} answers it: {@code ""} while
 *                     HEAD is detached, when there is no current branch and a commit lands
 *                     on no branch, and {@code ""} when it cannot be read; never the
 *                     commit's hash
 * @param midOperation a merge, rebase, cherry-pick or revert is part-finished, so there
 *                     is a commit to make even when nothing shows as changed
 */
public record StagingState(List<String> unstaged,
                           List<String> staged,
                           Set<String> conflicting,
                           Map<String, Character> status,
                           String branch,
                           boolean midOperation) {

    /**
     * The four-argument form, for a backend that has not been taught to report the
     * branch and the operation state.
     */
    public StagingState(List<String> unstaged, List<String> staged, Set<String> conflicting,
                        Map<String, Character> status) {
        this(unstaged, staged, conflicting, status, "", false);
    }

    /**
     * The three-argument form, for a backend that cannot report per-file status.
     *
     * <p>Every path then reads as a modification, which is what the panel showed before
     * it could tell the difference — so an engine that has not implemented this loses the
     * status letters and nothing else.
     */
    public StagingState(List<String> unstaged, List<String> staged, Set<String> conflicting) {
        this(unstaged, staged, conflicting, Map.of());
    }

    /** How this path changed: {@code A}, {@code M}, {@code D}, {@code R} or {@code U}. */
    public char statusOf(String path) {
        if (conflicting.contains(path)) return 'U';
        return status.getOrDefault(path, 'M');
    }

    /**
     * Whether there is anything to commit, on the same terms as
     * {@link com.jfx.git.core.StagingOps#hasUncommittedChanges()}.
     *
     * <p>The {@code midOperation} half is what keeps the commit button alive for a merge
     * that was resolved by taking one side verbatim: nothing differs by content, and the
     * merge still needs this commit to finish. Without it the button reads "Nothing
     * staged", stays disabled, and leaves no way to complete the merge at all.
     */
    public boolean hasUncommittedChanges() {
        return midOperation
                || !staged.isEmpty() || !unstaged.isEmpty() || !conflicting.isEmpty();
    }

    public static StagingState empty() {
        return new StagingState(List.of(), List.of(), Set.of());
    }
}
