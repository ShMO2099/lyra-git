package com.jfx.git.core.hosting;

import com.jfx.git.core.ProviderHosts;
import com.jfx.git.core.Settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HostingProvider#forRemote} decides which provider's dialogs a repository's
 * {@code origin} opens. It matches the configured server exactly - host and, for a web
 * remote, port - and answers empty for anything else, rather than the old "everything
 * unknown is GitHub".
 */
class HostingProviderForRemoteTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void useATempSettingsFile() {
        Settings.useFile(tempDir.resolve("settings.properties"));
    }

    @AfterEach
    void restoreDefault() {
        Settings.useFile(null);
    }

    private static void assertProvider(HostingProvider expected, String url) {
        assertEquals(Optional.of(expected), HostingProvider.forRemote(url), url);
    }

    private static void assertUnknown(String url) {
        assertEquals(Optional.empty(), HostingProvider.forRemote(url), url);
    }

    @Test
    void eachPublicHostResolvesToItsProvider() {
        assertProvider(HostingProvider.GITHUB, "https://github.com/octocat/Hello-World.git");
        assertProvider(HostingProvider.GITLAB, "https://gitlab.com/gitlab-org/gitlab-runner.git");
        assertProvider(HostingProvider.BITBUCKET, "https://bitbucket.org/atlassian/localstack.git");
        assertProvider(HostingProvider.GITEA, "https://codeberg.org/forgejo/forgejo.git");
    }

    @Test
    void sshAndScpStyleRemotesResolveToo() {
        assertProvider(HostingProvider.GITHUB, "git@github.com:octocat/Hello-World.git");
        assertProvider(HostingProvider.GITHUB, "ssh://git@github.com/octocat/Hello-World.git");
        assertProvider(HostingProvider.GITHUB, "ssh://git@ssh.github.com:443/octocat/Hello-World.git");
        assertProvider(HostingProvider.GITLAB, "git@gitlab.com:group/sub/project.git");
        assertProvider(HostingProvider.BITBUCKET, "git@bitbucket.org:atlassian/localstack.git");
        assertProvider(HostingProvider.GITEA, "git@codeberg.org:alice/demo.git");
        assertProvider(HostingProvider.GITEA, "ssh://git@codeberg.org:2222/alice/demo.git");
    }

    @Test
    void hostsCompareIgnoringCase() {
        assertProvider(HostingProvider.GITHUB, "https://GitHub.com/octocat/Hello-World.git");
    }

    @Test
    void aSelfHostedGiteaWithAPortResolvesOnlyOnThatPort() {
        ProviderHosts.setGitea("https://git.example.com:3000");
        assertProvider(HostingProvider.GITEA, "https://git.example.com:3000/alice/demo.git");
        assertProvider(HostingProvider.GITEA, "git@git.example.com:alice/demo.git");
        // The same host on another port is another server.
        assertUnknown("https://git.example.com/alice/demo.git");
        assertUnknown("https://git.example.com:8443/alice/demo.git");
        // Configuring a server replaces Codeberg rather than adding to it.
        assertUnknown("https://codeberg.org/forgejo/forgejo.git");
    }

    @Test
    void aSelfHostedGitLabWithAPortResolves() {
        ProviderHosts.setGitlab("https://gitlab.example.com:8443");
        assertProvider(HostingProvider.GITLAB, "https://gitlab.example.com:8443/group/project.git");
        assertProvider(HostingProvider.GITLAB, "git@gitlab.example.com:group/project.git");
        assertUnknown("https://gitlab.example.com/group/project.git");
    }

    @Test
    void gitHubMatchesOnlyItsConfiguredHost() {
        ProviderHosts.setGithub("https://ghe.example.com");
        assertProvider(HostingProvider.GITHUB, "https://ghe.example.com/team/app.git");
        assertUnknown("https://github.com/octocat/Hello-World.git");
    }

    @Test
    void lookalikeAndSuffixHostsAreRejected() {
        assertUnknown("https://evilgithub.com/octocat/Hello-World.git");
        assertUnknown("https://github.com.evil.example/octocat/Hello-World.git");
        assertUnknown("https://sub.github.com/octocat/Hello-World.git");
        assertUnknown("git@notgitlab.com:group/project.git");
        assertUnknown("https://codeberg.org.example/alice/demo.git");
        assertUnknown("https://mybitbucket.org/team/repo.git");
    }

    @Test
    void anUnknownHostResolvesToNothing() {
        assertUnknown("https://example.invalid/some/repo.git");
        assertUnknown("git@git.example.com:some/repo.git");
    }

    @Test
    void aPlainHttpRemoteIsNotTheHttpsServer() {
        assertUnknown("http://github.com/octocat/Hello-World.git");
    }

    @Test
    void anOddPathOnAKnownServerStillBelongsToThatProvider() {
        // Its dialog says the path is not a repository there, which is truer than
        // "unknown host".
        assertProvider(HostingProvider.GITHUB, "https://github.com/octocat/Hello-World/extra.git");
    }

    @Test
    void noRemoteAndLocalPathsResolveToNothing() {
        assertUnknown(null);
        assertUnknown("");
        assertUnknown("C:/repos/project");
        assertUnknown("/srv/git/project.git");
        assertUnknown("file:///srv/git/project.git");
    }
}
