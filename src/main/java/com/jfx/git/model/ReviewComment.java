package com.jfx.git.model;

/**
 * A PR/MR review comment anchored to one line of a diff, normalized across GitHub's
 * review comments, GitLab's diff discussions, and Bitbucket's inline comments so the
 * review UI ({@code com.jfx.git.ui.views.PrReviewView}) never needs to know which
 * provider it is looking at.
 *
 * @param path   the file the comment is anchored to, as it appears in the diff
 * @param line   the line number within {@code side}; {@code null} for a comment whose
 *               anchor could not be resolved to a specific line (kept rather than
 *               dropped, so nothing silently disappears)
 * @param side   which version of the line the comment refers to
 * @param author display name of whoever left the comment
 * @param body   the raw comment text, markdown included - a "suggested change" is not
 *               a separate field on any of the three providers, it is a
 *               ```suggestion fenced block inside this same body text
 */
public record ReviewComment(String path, Integer line, Side side, String author, String body) {

    public enum Side { OLD, NEW }
}
