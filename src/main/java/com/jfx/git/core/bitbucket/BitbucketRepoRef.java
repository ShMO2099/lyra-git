package com.jfx.git.core.bitbucket;

import com.jfx.git.core.RemoteUrl;

import java.net.URI;
import java.util.Optional;

/**
 * A {@code workspace/repo_slug} pair recovered from a remote's URL - see {@link
 * com.jfx.git.core.github.GitHubRepoRef} for the matching GitHub version this mirrors;
 * Bitbucket's addressing is always exactly two segments, unlike GitLab's nested
 * groups, so the shape here matches GitHub's rather than GitLab's.
 *
 * <p>Bitbucket Cloud only - {@code bitbucket.org}, compared case-insensitively and never
 * by suffix. Data Center speaks a different API; see
 * {@link com.jfx.git.core.ProviderHosts}.
 */
public record BitbucketRepoRef(String workspace, String repoSlug) {

    private static final URI BITBUCKET = URI.create("https://bitbucket.org");

    public static Optional<BitbucketRepoRef> fromRemoteUrl(String url) {
        Optional<RemoteUrl> parsed = RemoteUrl.parse(url);
        if (parsed.isEmpty() || !parsed.get().isOn(BITBUCKET)) return Optional.empty();
        String[] segments = parsed.get().path().split("/");
        if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new BitbucketRepoRef(segments[0], segments[1]));
    }
}
