package com.jfx.git.core.gitea;

import com.google.gson.annotations.SerializedName;

/**
 * A pull request review as Gitea's API describes it.
 *
 * <p>GitHub has no counterpart in this application because GitHub lets a line comment be
 * posted on its own. Gitea does not: every line comment belongs to a review, so posting
 * one creates a review holding it ({@link GiteaClient#createReviewComment}), and reading
 * them means reading the reviews first ({@link GiteaClient#listReviewComments}).
 *
 * @param id            the review's id, which addresses its comments
 * @param user          who submitted it
 * @param state         {@code APPROVED}, {@code REQUEST_CHANGES}, {@code COMMENT},
 *                      {@code PENDING} (an unsubmitted draft, visible only to its author)
 *                      or {@code REQUEST_REVIEW}
 * @param body          the review's own summary text, often empty
 * @param commitId      the commit the review was made against
 * @param commentsCount how many line comments it holds; null from a server that does not say
 * @param htmlUrl       the review's place on the pull request's page
 */
public record GiteaReview(
        long id,
        GiteaUser user,
        String state,
        String body,
        @SerializedName("commit_id") String commitId,
        @SerializedName("comments_count") Integer commentsCount,
        @SerializedName("html_url") String htmlUrl) {
}
