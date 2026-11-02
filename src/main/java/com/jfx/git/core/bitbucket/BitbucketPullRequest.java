package com.jfx.git.core.bitbucket;

import com.google.gson.annotations.SerializedName;

/**
 * A pull request as Bitbucket Cloud's REST API v2 describes it - only the fields the
 * list and checkout flow need. Bitbucket has no draft-PR concept, so unlike {@link
 * com.jfx.git.core.github.GitHubPullRequest} there is no {@code draft} field here.
 *
 * <p>Bitbucket publishes no numbered ref for a PR the way GitHub ({@code refs/pull})
 * or GitLab ({@code refs/merge-requests}) do, so checkout has to fetch the source
 * branch by name instead - from {@code origin} when {@link #isFromFork()} is false, or
 * directly from the fork's own clone URL when it is true. {@link Endpoint#repository}
 * carries the {@code full_name} needed to tell the two cases apart.
 *
 * @param id     the PR's number, shown in Bitbucket's own UI as {@code #<id>}
 * @param title  the PR's title
 * @param author who opened it
 * @param source the branch (and repository) this PR would merge from
 * @param destination the branch this PR would merge into
 * @param links  the PR's own links, including its page on bitbucket.org
 */
public record BitbucketPullRequest(
        int id,
        String title,
        BitbucketUser author,
        Endpoint source,
        Endpoint destination,
        Links links) {

    public record Endpoint(Branch branch, Repository repository, Commit commit) {
    }

    public record Branch(String name) {
    }

    /** The endpoint branch's current tip - what a CI status lookup keys off of. */
    public record Commit(String hash) {
    }

    public record Repository(@SerializedName("full_name") String fullName) {

        /**
         * This repository's HTTPS clone URL, constructed rather than fetched: the PR
         * API embeds only {@code self}/{@code html}/{@code avatar} links on a source
         * or destination repository, never {@code clone} - but Bitbucket's clone URL
         * for any repository is always {@code https://bitbucket.org/<full_name>.git},
         * verified against a real repository's own {@code links.clone} while writing
         * this, so there is nothing to look up.
         */
        public String cloneUrl() {
            return "https://bitbucket.org/" + fullName + ".git";
        }
    }

    public record Links(LinkHref html) {
    }

    public record LinkHref(String href) {
    }

    /** The PR's page on bitbucket.org, or {@code null} if the response carried no link. */
    public String htmlUrl() {
        return links == null || links.html() == null ? null : links.html().href();
    }

    /**
     * True when the source branch lives in a different repository than the
     * destination - a fork, which has no configured remote in this app and must be
     * fetched by clone URL instead of by name.
     */
    public boolean isFromFork() {
        return source != null && destination != null
                && source.repository() != null && destination.repository() != null
                && !source.repository().fullName().equals(destination.repository().fullName());
    }
}
