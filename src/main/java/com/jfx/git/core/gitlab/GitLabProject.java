package com.jfx.git.core.gitlab;

import com.google.gson.annotations.SerializedName;

/**
 * A project as GitLab's REST API v4 describes it - only the fields this app's clone
 * flow needs, out of the several dozen a real response carries.
 *
 * @param pathWithNamespace {@code group/project}, or {@code group/subgroup/project}
 *                          for a nested group - what the repo picker shows, and the
 *                          same string used (URL-encoded) to address this project in
 *                          every other API call
 * @param httpUrlToRepo     HTTPS remote, e.g. {@code https://gitlab.com/group/project.git}
 * @param sshUrlToRepo      SSH remote, e.g. {@code git@gitlab.com:group/project.git}
 * @param description       the project's own description, or {@code null}
 * @param visibility        {@code "public"}, {@code "internal"}, or {@code "private"} -
 *                           GitLab's three-way visibility, unlike GitHub's plain
 *                           public/private boolean; only {@code "public"} is treated
 *                           as not-private, since "internal" still restricts who can see it
 */
public record GitLabProject(
        @SerializedName("path_with_namespace") String pathWithNamespace,
        @SerializedName("http_url_to_repo") String httpUrlToRepo,
        @SerializedName("ssh_url_to_repo") String sshUrlToRepo,
        String description,
        String visibility) {

    public boolean isPrivate() {
        return !"public".equals(visibility);
    }
}
