package com.jfx.git.core.gitea;

import com.google.gson.annotations.SerializedName;

/**
 * A pull request as Gitea's API describes it - only the fields the pull request list,
 * checkout, merge and review flows need.
 *
 * <p>Shaped like {@link com.jfx.git.core.github.GitHubPullRequest}, whose dialog the Gitea
 * one is adapted from, plus three fields Gitea reports that GitHub's list did not need:
 * {@code state}, {@code mergeable} and {@code merged}. Gitea computes {@code mergeable}
 * ahead of time, so the merge button can say "this has conflicts" before it is pressed
 * rather than after a 405.
 *
 * <h2>Checking it out</h2>
 * Gitea and Forgejo publish every pull request's head as {@code refs/pull/<number>/head}
 * in the base repository, exactly as GitHub does - including one opened from a fork, whose
 * branch is otherwise only in the fork. So the GitHub checkout path,
 * {@link com.jfx.git.core.GitBackend#checkoutPullRequest}, works unchanged: it fetches that
 * ref from origin. No engine knows anything about Gitea.
 *
 * @param number    the pull request's index within its repository - what every
 *                  {@code /pulls/{index}} endpoint and {@code refs/pull/<number>/head} use
 * @param title     the pull request's title
 * @param user      who opened it
 * @param draft     true for a draft; servers that predate the field send nothing, which
 *                  reads as false - those marked work in progress with a title prefix
 * @param head      the source branch, its current tip commit, and the repository it is in
 * @param base      the branch this would merge into, and its tip when last computed
 * @param htmlUrl   the pull request's page on the server
 * @param state     {@code "open"} or {@code "closed"}
 * @param mergeable whether the server found it can be merged without conflicts
 * @param merged    whether it already has been
 */
public record GiteaPullRequest(
        int number,
        String title,
        GiteaUser user,
        boolean draft,
        Head head,
        Base base,
        @SerializedName("html_url") String htmlUrl,
        String state,
        boolean mergeable,
        boolean merged) {

    /**
     * The source branch ({@code ref}), its current tip commit ({@code sha}), and the
     * repository it lives in - the base repository itself, or a fork. {@code repo} is
     * null when the fork has since been deleted.
     */
    public record Head(String ref, String sha, GiteaRepository repo) {
    }

    /**
     * The branch ({@code ref}) this would merge into and its tip commit ({@code sha}) -
     * the base half of the range a local review diff needs.
     */
    public record Base(String ref, String sha) {
    }
}
