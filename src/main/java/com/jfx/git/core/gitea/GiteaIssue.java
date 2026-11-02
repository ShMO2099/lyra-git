package com.jfx.git.core.gitea;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * An issue as Gitea's API describes it - only the fields the issue list needs.
 *
 * <p>The same shape as {@link com.jfx.git.core.github.GitHubIssue}. Gitea, like GitHub,
 * keeps pull requests in the same table as issues; {@link GiteaClient} asks for
 * {@code type=issues} so the server leaves them out, and {@link #isPullRequest()} is the
 * check that holds even if a server ignores that parameter.
 *
 * @param number      the issue number
 * @param title       the issue's title
 * @param user        who opened it
 * @param state       {@code "open"} or {@code "closed"}
 * @param htmlUrl     the issue's page on the server
 * @param labels      the labels attached to it
 * @param pullRequest present only when this "issue" is really a pull request
 */
public record GiteaIssue(
        int number,
        String title,
        GiteaUser user,
        String state,
        @SerializedName("html_url") String htmlUrl,
        List<Label> labels,
        @SerializedName("pull_request") Object pullRequest) {

    /** @param name a label's display name, e.g. {@code "bug"} */
    public record Label(String name) {
    }

    /** Whether the server sent a pull request here; a real issue has {@code pull_request} null or absent. */
    public boolean isPullRequest() {
        return pullRequest != null;
    }
}
