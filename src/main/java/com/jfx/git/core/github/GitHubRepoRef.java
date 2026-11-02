package com.jfx.git.core.github;

import com.jfx.git.core.ProviderHosts;
import com.jfx.git.core.RemoteUrl;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * An {@code owner/repo} pair recovered from a remote's URL - what the GitHub REST API
 * needs to address a repository, but a git remote only ever records as a URL. Matches
 * every form git itself accepts for a GitHub remote: HTTPS, the {@code git@} SCP-like
 * syntax, and explicit {@code ssh://}, with or without a trailing {@code .git}.
 *
 * <h2>Which server</h2>
 * The server the GitHub integration is pointed at - github.com, or the GitHub Enterprise
 * host configured in {@link ProviderHosts}. A remote on any other host is not a
 * repository the connected client can address: a github.com remote looked up on an
 * Enterprise server names somebody else's repository there, or nothing. So only the
 * configured host matches, compared the way DNS compares names - case-insensitively -
 * and never by suffix, so {@code mygithub.com} and {@code sub.github.com} do not match.
 */
public record GitHubRepoRef(String owner, String repo) {

    public static Optional<GitHubRepoRef> fromRemoteUrl(String url) {
        return fromRemoteUrl(url, ProviderHosts.githubWeb());
    }

    /** Against an explicit server; {@link #fromRemoteUrl(String)} uses the configured one. */
    public static Optional<GitHubRepoRef> fromRemoteUrl(String url, URI server) {
        Optional<RemoteUrl> parsed = RemoteUrl.parse(url);
        if (parsed.isEmpty()) return Optional.empty();
        RemoteUrl remote = parsed.get();
        boolean onServer = remote.isOn(server)
                // github.com also serves ssh on port 443 as ssh.github.com.
                || ("github.com".equalsIgnoreCase(server.getHost()) && !remote.isWeb()
                        && remote.host().equals("ssh.github.com"));
        if (!onServer) return Optional.empty();
        List<String> segments = List.of(remote.pathUnder(server).split("/"));
        if (segments.size() != 2 || segments.get(0).isBlank() || segments.get(1).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRepoRef(segments.get(0), segments.get(1)));
    }
}
