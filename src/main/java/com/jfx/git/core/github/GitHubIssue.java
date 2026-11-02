package com.jfx.git.core.github;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * An issue as GitHub's REST API describes it - only the fields the issue list needs,
 * out of the many dozen a real response carries. In-app creation, commenting, and
 * closing remain out of scope for this first pass (Wave 4, §4.3 Integrations Phase C
 * of the parity plan); "Open in browser" is the only action offered.
 *
 * @param number  the issue number
 * @param title   the issue's title
 * @param user    who opened it
 * @param state   {@code "open"} or {@code "closed"}
 * @param htmlUrl the issue's page on github.com
 * @param labels  the labels attached to it
 */
public record GitHubIssue(
        int number,
        String title,
        GitHubUser user,
        String state,
        @SerializedName("html_url") String htmlUrl,
        List<Label> labels,
        @SerializedName("pull_request") Object pullRequest) {

    /** @param name a label's display name, e.g. {@code "bug"} */
    public record Label(String name) {
    }

    /**
     * GitHub's issues endpoint also returns pull requests - every PR is internally an
     * issue, and the API does not separate them by default. A real issue never carries
     * this key at all; a PR always does, whatever its value.
     */
    public boolean isPullRequest() {
        return pullRequest != null;
    }
}
