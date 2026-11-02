package com.jfx.git.core.gitea;

import com.google.gson.annotations.SerializedName;

/**
 * A repository as Gitea's API describes it - only the fields the clone, fork and pull
 * request flows need, out of the several dozen a real response carries.
 *
 * <p>The first five components are {@link com.jfx.git.core.github.GitHubRepository}'s,
 * in the same order, so the repo picker adapts by renaming. The rest are fields Gitea
 * always sends and the GitHub record never needed: a fork's owner and name address it
 * when a pull request is opened from it, and the default branch is what a new pull
 * request targets.
 *
 * @param fullName      {@code owner/repo}, what the repo picker shows
 * @param cloneUrl      HTTPS remote, e.g. {@code https://codeberg.org/owner/repo.git}
 * @param sshUrl        SSH remote, e.g. {@code git@codeberg.org:owner/repo.git}
 * @param description   the repository's own description, or empty
 * @param isPrivate     whether this repository is private
 * @param name          the repository's name without its owner
 * @param owner         the user or organisation that owns it
 * @param htmlUrl       the repository's page on the server
 * @param defaultBranch the branch the server shows first, e.g. {@code main}
 * @param fork          whether this repository is itself a fork
 */
public record GiteaRepository(
        @SerializedName("full_name") String fullName,
        @SerializedName("clone_url") String cloneUrl,
        @SerializedName("ssh_url") String sshUrl,
        String description,
        @SerializedName("private") boolean isPrivate,
        String name,
        GiteaUser owner,
        @SerializedName("html_url") String htmlUrl,
        @SerializedName("default_branch") String defaultBranch,
        boolean fork) {
}
