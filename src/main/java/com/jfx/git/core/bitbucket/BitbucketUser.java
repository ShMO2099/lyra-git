package com.jfx.git.core.bitbucket;

import com.google.gson.annotations.SerializedName;

/**
 * A user (or team) as Bitbucket Cloud's REST API v2 describes it. {@code username} can
 * be {@code null} for an account that has opted out of a public handle - {@code
 * displayName} is always present and is what this app falls back to for showing who
 * authored something.
 */
public record BitbucketUser(String username, @SerializedName("display_name") String displayName) {

    /** {@link #username} if the account has one, otherwise {@link #displayName}. */
    public String handle() {
        return username != null && !username.isBlank() ? username : displayName;
    }
}
