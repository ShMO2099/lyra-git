package com.jfx.git.core.github;

import com.google.gson.annotations.SerializedName;

/**
 * A pull request as GitHub's REST API describes it - only the fields the PR list and
 * checkout flow need, out of the many dozen a real response carries. Full metadata
 * (reviewers, labels, milestones), build status, and in-app merge remain out of scope
 * for this first pass.
 *
 * @param number  the PR number, what GitHub's own {@code refs/pull/<number>/head} and
 *                {@link com.jfx.git.core.GitBackend#checkoutPullRequest} key off of
 * @param title   the PR's title
 * @param user    who opened it
 * @param draft   true for a draft PR, not yet ready for review
 * @param head    the source branch and its current tip commit
 * @param base    the branch this PR would merge into, and the commit it pointed at
 *                when the PR was opened - what reviewing its diff locally needs
 * @param htmlUrl the PR's page on github.com
 */
public record GitHubPullRequest(
        int number,
        String title,
        GitHubUser user,
        boolean draft,
        Head head,
        Base base,
        @SerializedName("html_url") String htmlUrl) {

    /** The source branch ({@code ref}) and its current tip commit ({@code sha}). */
    public record Head(String ref, String sha) {
    }

    /**
     * The branch ({@code ref}) this PR would merge into, and its tip commit
     * ({@code sha}) at PR-open time - the base half of the {@code base.sha..head.sha}
     * range a local review diff needs.
     */
    public record Base(String ref, String sha) {
    }
}
