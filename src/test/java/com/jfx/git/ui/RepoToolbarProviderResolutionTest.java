package com.jfx.git.ui;

import com.jfx.git.core.hosting.HostingProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which dialog the toolbar's "Pull requests" and "Issues" buttons open for a given
 * {@code origin} - {@link HostingProvider#forRemote}, the logic behind {@code
 * openPullOrMergeRequests} and {@code openIssues} - and what they say when the origin is
 * on a server no integration is set up for. Pinned here without building a toolbar, a
 * stage or a dialog. {@link FxHarness#start()} is still needed once: loading
 * {@code RepoToolbar} to call a static method on it runs {@code Control}'s class
 * initializer, which touches the JavaFX toolkit.
 */
class RepoToolbarProviderResolutionTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @Test
    void aGitLabRemoteResolvesToGitLab() {
        assertEquals(Optional.of(HostingProvider.GITLAB),
                HostingProvider.forRemote("https://gitlab.com/gitlab-org/gitlab-runner.git"));
    }

    @Test
    void aBitbucketRemoteResolvesToBitbucket() {
        assertEquals(Optional.of(HostingProvider.BITBUCKET),
                HostingProvider.forRemote("https://bitbucket.org/atlassian/localstack.git"));
    }

    @Test
    void aGitHubRemoteResolvesToGitHub() {
        assertEquals(Optional.of(HostingProvider.GITHUB),
                HostingProvider.forRemote("https://github.com/octocat/Hello-World.git"));
    }

    @Test
    void aCodebergRemoteResolvesToGitea() {
        assertEquals(Optional.of(HostingProvider.GITEA),
                HostingProvider.forRemote("https://codeberg.org/forgejo/forgejo.git"));
    }

    /** It used to fall back to GitHub, whose dialog could only say "not a GitHub repository". */
    @Test
    void anUnrecognisedRemoteResolvesToNoProvider() {
        assertEquals(Optional.empty(), HostingProvider.forRemote("https://example.invalid/some/repo.git"));
    }

    @Test
    void noRemoteAtAllResolvesToNoProvider() {
        assertEquals(Optional.empty(), HostingProvider.forRemote(null));
    }

    @Test
    void anUnknownHostIsNamedWithWhereToConfigureIt() {
        assertEquals("origin is on git.example.com:3000. Point GitHub Enterprise, GitLab or Gitea at this"
                        + " server in Integrations to browse its pull requests.",
                RepoToolbar.unknownHostMessage("https://git.example.com:3000/team/app.git", "pull requests"));
        assertEquals("origin is on git.example.com. Point GitHub Enterprise, GitLab or Gitea at this"
                        + " server in Integrations to browse its issues.",
                RepoToolbar.unknownHostMessage("git@git.example.com:team/app.git", "issues"));
    }

    /** An ssh port belongs to the ssh daemon, so it would point Integrations at the wrong address. */
    @Test
    void anSshPortIsLeftOutOfTheHost() {
        String message = RepoToolbar.unknownHostMessage("ssh://git@git.example.com:2222/team/app.git", "issues");
        assertFalse(message.contains("2222"), message);
    }

    @Test
    void aRemoteOnDiskHasNoHostToConfigure() {
        assertEquals("origin is not on a hosting server, so there are no pull requests to browse.",
                RepoToolbar.unknownHostMessage("C:/repos/project", "pull requests"));
    }
}
