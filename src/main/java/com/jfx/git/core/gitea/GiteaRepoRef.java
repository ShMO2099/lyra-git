package com.jfx.git.core.gitea;

import com.jfx.git.core.ProviderHosts;
import com.jfx.git.core.RemoteUrl;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * An {@code owner/repo} pair recovered from a remote's URL - what Gitea's API needs to
 * address a repository, but a git remote only ever records as a URL. Matches every form
 * git accepts: HTTPS, the {@code git@host:} scp-like syntax and explicit {@code ssh://},
 * with or without a trailing {@code .git}.
 *
 * <h2>Which server</h2>
 * Only the server the Gitea integration is pointed at - Codeberg, or the one configured in
 * {@link ProviderHosts}. Gitea is self-hosted by nature, so there is no well-known host to
 * guess from, and a remote on another server named with the connected server's token
 * would address somebody else's repository there, or nothing. The host is compared the
 * way DNS compares names, case-insensitively and never by suffix; for a web remote the
 * port must match too, while an ssh remote's port belongs to the ssh daemon and is not
 * compared. A server installed under a path prefix has the prefix removed from web
 * remotes' paths.
 *
 * <p>Gitea has no nested groups: a repository is always exactly {@code owner/name}, so
 * any other number of path segments is not a repository on it.
 */
public record GiteaRepoRef(String owner, String repo) {

    public static Optional<GiteaRepoRef> fromRemoteUrl(String url) {
        return fromRemoteUrl(url, ProviderHosts.giteaWeb());
    }

    /** Against an explicit server; {@link #fromRemoteUrl(String)} uses the configured one. */
    public static Optional<GiteaRepoRef> fromRemoteUrl(String url, URI server) {
        Optional<RemoteUrl> parsed = RemoteUrl.parse(url);
        if (parsed.isEmpty() || !parsed.get().isOn(server)) return Optional.empty();
        List<String> segments = List.of(parsed.get().pathUnder(server).split("/"));
        if (segments.size() != 2 || segments.get(0).isBlank() || segments.get(1).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GiteaRepoRef(segments.get(0), segments.get(1)));
    }
}
