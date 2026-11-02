package com.jfx.git.core.gitlab;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * An issue as GitLab's REST API v4 describes it - only the fields the issue list
 * needs. Unlike GitHub, GitLab's issues and merge requests are genuinely separate
 * endpoints - {@code /issues} never returns a merge request - so no filtering is
 * needed the way {@link com.jfx.git.core.github.GitHubIssue#isPullRequest()} exists
 * for. Labels also arrive as a plain list of strings here, not a list of objects.
 *
 * @param iid    the issue's number as shown in GitLab's own UI
 * @param title  the issue's title
 * @param author who opened it
 * @param state  {@code "opened"} or {@code "closed"} - GitLab's own spelling, not
 *               GitHub's {@code "open"}
 * @param webUrl the issue's page on gitlab.com
 * @param labels the labels attached to it, by name
 */
public record GitLabIssue(
        int iid,
        String title,
        GitLabUser author,
        String state,
        @SerializedName("web_url") String webUrl,
        List<String> labels) {
}
