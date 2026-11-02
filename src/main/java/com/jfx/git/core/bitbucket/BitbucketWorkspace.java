package com.jfx.git.core.bitbucket;

/**
 * A workspace as Bitbucket Cloud's REST API v2 describes it - Bitbucket has no single
 * "list my repositories across everywhere" endpoint any more (the closest one, {@code
 * /2.0/repositories?role=member}, and {@code /2.0/user/permissions/repositories}, were
 * both removed - verified live: both now answer {@code 410 Gone} with a deprecation
 * notice), so {@link BitbucketClient#listRepositories} lists a user's workspaces
 * first and then lists each workspace's repositories in turn.
 *
 * @param slug the workspace's short identifier, used to address it in every other API call
 */
public record BitbucketWorkspace(String slug) {
}
