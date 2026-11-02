package com.jfx.git.model;

import java.util.List;

/**
 * The commits an interactive rebase would replay, or why it cannot.
 *
 * @param problem {@code null} when the range is usable, otherwise what to tell the user -
 *                the reason is worth carrying because "no commits" has several causes and
 *                they need different answers
 */
public record RebaseRange(List<CommitInfo> commits, String base, String problem) {

    public boolean isUsable() {
        return problem == null;
    }
}
