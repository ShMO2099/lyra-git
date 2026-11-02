package com.jfx.git.core.bitbucket;

/**
 * A pull request comment as Bitbucket Cloud's REST API v2 describes it. A plain
 * top-level PR comment and a line-anchored inline comment share this exact shape -
 * only an inline comment carries a non-null {@link #inline()} - so a comment with no
 * inline anchor at all is filtered out rather than modelled separately.
 *
 * @param id      the comment's own id
 * @param content the comment body, markdown included - Bitbucket has no suggested-
 *                change syntax the way GitHub/GitLab do, so a suggestion is not
 *                representable here at all, unlike on those two providers
 * @param user    who left the comment
 * @param inline  which file and line the comment is anchored to, or {@code null} for
 *                a plain top-level comment
 */
public record BitbucketComment(long id, Content content, BitbucketUser user, Inline inline) {

    public record Content(String raw) {
    }

    /**
     * @param path the file the comment is anchored to
     * @param to   the line number in the new (destination-side-after-merge) version,
     *             {@code null} for a comment on a removed line
     * @param from the line number in the old version, {@code null} for a comment on
     *             an added line
     */
    public record Inline(String path, Integer to, Integer from) {
    }
}
