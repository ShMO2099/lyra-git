package com.jfx.git.core.github;

import com.google.gson.annotations.SerializedName;

/**
 * The authenticated GitHub user a personal access token belongs to.
 *
 * <p>Just enough to show "Connected as @login" with an avatar - this app has no use
 * for the rest of GitHub's user profile, so nothing else is kept even though the API
 * response carries dozens of other fields.
 */
public record GitHubUser(String login, @SerializedName("avatar_url") String avatarUrl) {
}
