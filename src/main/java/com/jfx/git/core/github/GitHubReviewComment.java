package com.jfx.git.core.github;

import com.google.gson.annotations.SerializedName;

/**
 * A pull request review comment as GitHub's REST API describes it - a comment anchored
 * to one line of a diff, as opposed to a plain issue-style comment on the PR as a
 * whole. A "suggested change" is not a separate field here: it is a
 * <code>```suggestion</code> fenced block inside {@link #body}, which GitHub renders
 * specially - nothing on the wire distinguishes it from an ordinary comment.
 *
 * @param id       the comment's own id
 * @param body     the raw comment text, markdown included
 * @param path     the file the comment is anchored to
 * @param line     the line number within {@code side} of the current diff - absent
 *                 (Gson leaves it {@code null}) on a comment anchored to an outdated
 *                 position no longer part of the diff
 * @param side     {@code "LEFT"} (the base/old version) or {@code "RIGHT"} (the head/
 *                 new version) of the diff
 * @param user     who left the comment
 * @param commitId the commit this comment's position is relative to
 * @param htmlUrl  the comment's own permalink on github.com
 */
public record GitHubReviewComment(
        long id,
        String body,
        String path,
        Integer line,
        String side,
        GitHubUser user,
        @SerializedName("commit_id") String commitId,
        @SerializedName("html_url") String htmlUrl) {
}
