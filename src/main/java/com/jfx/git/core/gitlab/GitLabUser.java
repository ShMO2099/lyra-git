package com.jfx.git.core.gitlab;

import com.google.gson.annotations.SerializedName;

/**
 * A user as GitLab's REST API v4 describes it - GitLab calls the login handle
 * {@code username}, unlike GitHub's {@code login}.
 */
public record GitLabUser(String username, @SerializedName("avatar_url") String avatarUrl) {
}
