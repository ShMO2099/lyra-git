package com.jfx.git.core.gitea;

/**
 * A review comment anchored to one line of a pull request's diff, in the shape
 * {@link com.jfx.git.core.github.GitHubReviewComment} has.
 *
 * <h2>Why it is not Gitea's own JSON</h2>
 * Gitea says which side of the diff a comment is on by which of two fields holds the
 * line: {@code position} for the new file, {@code original_position} for the old one,
 * the other being zero. That is the same information as GitHub's {@code line} plus
 * {@code side}, so {@link GiteaClient} translates it once, and the review dialog's adapter
 * reads both providers the same way. Note Gitea's "position" is a file line number, not
 * GitHub's old position-within-the-diff - the names match, the meaning does not.
 *
 * <p>A "suggested change" is a <code>```suggestion</code> fenced block inside
 * {@link #body}, as on GitHub; Gitea renders it as markdown.
 *
 * @param id       the comment's own id
 * @param body     the raw comment text, markdown included
 * @param path     the file the comment is anchored to
 * @param line     the line number on {@code side}; null when Gitea gave neither
 * @param side     {@code "LEFT"} (the base/old version) or {@code "RIGHT"} (the head/new
 *                 version) of the diff
 * @param user     who left the comment
 * @param commitId the commit this comment's line is relative to
 * @param htmlUrl  the comment's own permalink on the server
 * @param reviewId the review the comment belongs to - Gitea has no comment that is not
 *                 part of a review
 */
public record GiteaReviewComment(
        long id,
        String body,
        String path,
        Integer line,
        String side,
        GiteaUser user,
        String commitId,
        String htmlUrl,
        long reviewId) {
}
