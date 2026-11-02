package com.jfx.git.core.gitlab;

import com.jfx.git.core.ProviderHosts;
import com.jfx.git.core.RemoteUrl;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A project's full path recovered from a remote's URL - what GitLab's REST API needs
 * to address a project, but a git remote only ever records as a URL. Unlike GitHub or
 * Bitbucket, GitLab projects can live under nested groups ({@code group/subgroup
 * /project}), so this holds the whole path as one string rather than splitting it into
 * an owner and a repo - which is also exactly the shape GitLab's own API expects,
 * URL-encoded as a single path segment.
 *
 * <p>Matched against the server the GitLab integration is configured for - gitlab.com or
 * a self-managed host, see {@link ProviderHosts} - by host, case-insensitively, and by
 * port for a web remote. The path is taken from a real URL parse, so an explicit ssh port
 * ({@code ssh://git@host:2222/group/project}) is a port and not the first group, and a
 * server installed under a path prefix has that prefix removed.
 */
public record GitLabProjectRef(String fullPath) {

    public static Optional<GitLabProjectRef> fromRemoteUrl(String url) {
        return fromRemoteUrl(url, ProviderHosts.gitlabWeb());
    }

    /** Against an explicit server; {@link #fromRemoteUrl(String)} uses the configured one. */
    public static Optional<GitLabProjectRef> fromRemoteUrl(String url, URI server) {
        Optional<RemoteUrl> parsed = RemoteUrl.parse(url);
        if (parsed.isEmpty() || !parsed.get().isOn(server)) return Optional.empty();
        String path = parsed.get().pathUnder(server);
        // A project always sits in a namespace: one segment is a user or group page.
        if (!path.contains("/")) return Optional.empty();
        return Optional.of(new GitLabProjectRef(path));
    }

    /** {@link #fullPath} percent-encoded as a single path segment, the way GitLab's API addresses a project by path. */
    public String encodedPath() {
        return URLEncoder.encode(fullPath, StandardCharsets.UTF_8);
    }
}
