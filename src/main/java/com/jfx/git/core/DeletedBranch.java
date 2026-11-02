package com.jfx.git.core;

import com.jfx.git.model.RefInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A local branch as it was just before it was deleted - its commit, and the
 * configuration git deletes with it - so Undo puts back the branch the user had, not
 * only its commit.
 *
 * <p>{@code git branch -d} removes the branch's whole {@code [branch "x"]} section: its
 * upstream ({@code remote} and {@code merge}), where it pushes and whether it rebases on
 * pull. An Undo that recreated the branch at its commit and stopped there gave back a
 * branch that tracked nothing, whose next push asked where to go.
 *
 * @param config the branch's own keys that were set - {@code remote}, {@code merge},
 *               {@code pushRemote}, {@code rebase} - to their values
 */
public record DeletedBranch(String name, String tip, Map<String, String> config) {

    /** What {@code [branch "x"]} can hold that is worth putting back. */
    private static final List<String> KEYS = List.of("remote", "merge", "pushRemote", "rebase");

    public DeletedBranch {
        config = Map.copyOf(config == null ? Map.of() : config);
    }

    /**
     * Reads what deleting {@code name} would lose. On the git lane.
     *
     * @return {@code null} when there is no such local branch
     */
    public static DeletedBranch capture(GitBackend git, String name) {
        String tip = git.getLocalBranches().stream()
                .filter(ref -> ref.shortName().equals(name))
                .map(RefInfo::targetHash)
                .findFirst().orElse(null);
        return tip == null ? null : capture(git, name, tip);
    }

    /** {@link #capture(GitBackend, String)} for a branch whose commit is already known. */
    public static DeletedBranch capture(GitBackend git, String name, String tip) {
        Map<String, String> config = new LinkedHashMap<>();
        for (String key : KEYS) {
            String value = git.getLocalConfig("branch." + name + "." + key);
            if (value != null) config.put(key, value);
        }
        return new DeletedBranch(name, tip, config);
    }

    /** Whether the branch tracked something. */
    public boolean hadUpstream() {
        return config.containsKey("merge");
    }

    /** Recreates the branch at its commit, then its configuration. On the git lane. */
    public GitResult restore(GitBackend git) {
        GitResult created = git.createBranch(name, tip, false);
        if (!created.isOk()) return created;
        for (String key : KEYS) {
            String value = config.get(key);
            if (value == null) continue;
            GitResult set = git.setLocalConfig("branch." + name + "." + key, value);
            if (!set.isOk()) {
                return GitResult.failed("Restored branch " + name + ", but not its upstream: " + set.getMessage());
            }
        }
        return GitResult.ok("Restored branch " + name + (hadUpstream() ? " and its upstream" : ""));
    }
}
