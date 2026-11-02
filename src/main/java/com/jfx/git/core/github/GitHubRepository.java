package com.jfx.git.core.github;

import com.google.gson.annotations.SerializedName;

/**
 * A repository as GitHub's REST API describes it - only the fields this app's clone
 * flow needs, out of the several dozen a real response carries.
 *
 * @param fullName      {@code owner/repo}, what the repo picker shows
 * @param cloneUrl      HTTPS remote, e.g. {@code https://github.com/owner/repo.git}
 * @param sshUrl        SSH remote, e.g. {@code git@github.com:owner/repo.git}
 * @param description   the repository's own description, or {@code null}
 * @param isPrivate     whether this repository is private to the authenticated user
 */
public record GitHubRepository(
        @SerializedName("full_name") String fullName,
        @SerializedName("clone_url") String cloneUrl,
        @SerializedName("ssh_url") String sshUrl,
        String description,
        @SerializedName("private") boolean isPrivate) {
}
