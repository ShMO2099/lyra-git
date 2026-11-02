package com.jfx.git.core.hosting;

import com.jfx.git.core.ProviderHosts;
import com.jfx.git.core.RemoteUrl;
import com.jfx.git.core.bitbucket.BitbucketRepoRef;
import com.jfx.git.core.gitea.GiteaRepoRef;
import com.jfx.git.core.github.GitHubRepoRef;
import com.jfx.git.core.gitlab.GitLabProjectRef;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * The hosting services this application integrates with.
 *
 * <p>Each one had its own session class, its own restore method and its own branch in
 * every switch that needed to know them all. That was manageable at three; it stops being
 * so once a connected account also answers git's own logins and OAuth tokens need
 * refreshing - both have to visit every provider, and a hand-written list in each place is
 * where one gets left out. This is the one list.
 *
 * <p>{@link #id()} names stored settings and remembered tokens ({@code github@host}), so it
 * never changes once shipped.
 */
public enum HostingProvider {

    GITHUB("github", "GitHub", true),
    GITLAB("gitlab", "GitLab", true),
    BITBUCKET("bitbucket", "Bitbucket", false),
    /** Gitea and its fork Forgejo, which speak the same API; Codeberg unless configured. */
    GITEA("gitea", "Gitea", true);

    private final String id;
    private final String displayName;
    private final boolean selfHostable;

    HostingProvider(String id, String displayName, boolean selfHostable) {
        this.id = id;
        this.displayName = displayName;
        this.selfHostable = selfHostable;
    }

    /** Stable lower-case name used in settings keys and token accounts. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether a server other than the public one can be configured. */
    public boolean selfHostable() {
        return selfHostable;
    }

    /** The configured server's web address - what its remotes' URLs are made of. */
    public URI web() {
        return ProviderHosts.web(this);
    }

    /** The name a remembered credential is stored under; see {@link ProviderHosts#tokenAccount}. */
    public String tokenAccount() {
        return ProviderHosts.tokenAccount(this);
    }

    /**
     * The provider whose configured server a remote lives on, or empty when it is on none
     * of them.
     *
     * <h2>Why there is no fallback</h2>
     * Anything unrecognised used to count as GitHub, so a self-hosted Gitea or GitLab remote
     * opened the GitHub dialog, which then said "not a GitHub repository" - true, and no help.
     * Now an unknown host is reported as one, and the caller can say which host it is and
     * where to configure it.
     *
     * <h2>How a remote is matched</h2>
     * The host is compared exactly, never by suffix (so {@code evilgithub.com} is not
     * GitHub), and for a web remote so is the port, both by {@link RemoteUrl#isOn}. Each
     * provider's own repository parser is asked first, because it knows its server's
     * exceptions - github.com also serves ssh as {@code ssh.github.com}. A remote on the
     * right server whose path is not a repository there still belongs to that provider:
     * its dialog says the path is wrong, which is truer than "unknown host".
     *
     * <p>When two providers are configured on the same server, the first in declaration
     * order wins; the provider dialogs each re-check the remote themselves.
     */
    public static Optional<HostingProvider> forRemote(String url) {
        Optional<RemoteUrl> parsed = RemoteUrl.parse(url);
        if (parsed.isEmpty()) return Optional.empty();
        for (HostingProvider provider : values()) {
            if (provider.parsesRepository(url) || parsed.get().isOn(provider.web())) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /** Whether this provider's own parser reads {@code url} as one of its repositories. */
    private boolean parsesRepository(String url) {
        return switch (this) {
            case GITHUB -> GitHubRepoRef.fromRemoteUrl(url).isPresent();
            case GITLAB -> GitLabProjectRef.fromRemoteUrl(url).isPresent();
            case BITBUCKET -> BitbucketRepoRef.fromRemoteUrl(url).isPresent();
            case GITEA -> GiteaRepoRef.fromRemoteUrl(url).isPresent();
        };
    }

    /** The provider whose {@link #id()} is {@code id}, ignoring case. */
    public static Optional<HostingProvider> byId(String id) {
        if (id == null) return Optional.empty();
        String wanted = id.toLowerCase(Locale.ROOT);
        for (HostingProvider p : values()) {
            if (p.id.equals(wanted)) return Optional.of(p);
        }
        return Optional.empty();
    }
}
