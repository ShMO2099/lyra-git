package com.jfx.git.core.gitea;

import com.google.gson.annotations.SerializedName;

/**
 * The Gitea user an access token belongs to.
 *
 * <p>Just enough to show "Connected as @login" with an avatar, the same two fields
 * {@link com.jfx.git.core.github.GitHubUser} keeps - Gitea's user record carries a few
 * dozen more that nothing here uses. {@code login} is also the name git sees when the
 * token is offered for HTTPS, which Gitea accepts as the username alongside the token.
 */
public record GiteaUser(String login, @SerializedName("avatar_url") String avatarUrl) {
}
