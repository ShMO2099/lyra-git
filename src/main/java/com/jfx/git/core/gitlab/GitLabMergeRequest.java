package com.jfx.git.core.gitlab;

import com.google.gson.annotations.SerializedName;

/**
 * A merge request as GitLab's REST API v4 describes it - only the fields the list and
 * checkout flow need.
 *
 * @param iid          the merge request's number as shown in GitLab's own UI, and what
 *                     {@code refs/merge-requests/<iid>/head} keys off of - not {@code id},
 *                     which is a global identifier across every project on the instance
 * @param title        the merge request's title
 * @param author       who opened it
 * @param draft        true for a draft merge request, not yet ready for review
 * @param sourceBranch the branch this merge request would merge from
 * @param targetBranch the branch this merge request would merge into
 * @param sha          the source branch's current HEAD commit - what a CI status
 *                     lookup keys off of, the same role {@code head.sha} plays on a
 *                     {@link com.jfx.git.core.github.GitHubPullRequest}
 * @param diffRefs     the three commit shas GitLab's own diff/discussion APIs key a
 *                     position off of - what reviewing this merge request's diff
 *                     locally, and posting a new discussion on it, both need
 * @param webUrl       the merge request's page on gitlab.com
 */
public record GitLabMergeRequest(
        int iid,
        String title,
        GitLabUser author,
        boolean draft,
        @SerializedName("source_branch") String sourceBranch,
        @SerializedName("target_branch") String targetBranch,
        String sha,
        @SerializedName("diff_refs") DiffRefs diffRefs,
        @SerializedName("web_url") String webUrl) {

    /**
     * @param baseSha  the merge base of the source and target branches when the diff
     *                 was computed
     * @param startSha the target branch's tip at diff time
     * @param headSha  the source branch's tip at diff time - the same commit
     *                 {@link #sha} already names, repeated here because a diff
     *                 position needs all three shas together as one unit
     */
    public record DiffRefs(@SerializedName("base_sha") String baseSha,
                            @SerializedName("start_sha") String startSha,
                            @SerializedName("head_sha") String headSha) {
    }
}
