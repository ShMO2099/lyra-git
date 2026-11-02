package com.jfx.git.core;

import com.jfx.git.core.bitbucket.BitbucketRepoRef;
import com.jfx.git.core.github.GitHubRepoRef;
import com.jfx.git.core.gitlab.GitLabProjectRef;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R8: remote URLs parsed once, generically, and matched against the server each
 * provider is configured for - so a self-hosted GitHub Enterprise or GitLab matches its
 * remotes, a host's case does not matter, and an ssh port is a port.
 */
class RemoteUrlTest {

    @Test
    void everyFormGitAcceptsIsParsed() {
        assertEquals(new RemoteUrl("https", "github.com", -1, "o/r"),
                RemoteUrl.parse("https://github.com/o/r.git").orElseThrow());
        assertEquals(new RemoteUrl("ssh", "github.com", -1, "o/r"),
                RemoteUrl.parse("git@github.com:o/r.git").orElseThrow());
        assertEquals(new RemoteUrl("ssh", "gitlab.com", 2222, "g/p"),
                RemoteUrl.parse("ssh://git@gitlab.com:2222/g/p").orElseThrow(),
                "the port is a port, not the first group");
        assertEquals(new RemoteUrl("https", "git.example.com", 8443, "sub/g/p"),
                RemoteUrl.parse("https://user:pw@Git.Example.COM:8443/sub/g/p.git/").orElseThrow());
    }

    @Test
    void localPathsAreNotRemotes() {
        assertEquals(Optional.empty(), RemoteUrl.parse("C:\\repos\\thing"));
        assertEquals(Optional.empty(), RemoteUrl.parse("/srv/git/thing.git"));
        assertEquals(Optional.empty(), RemoteUrl.parse("file:///srv/git/thing.git"));
        assertEquals(Optional.empty(), RemoteUrl.parse("../sibling"));
    }

    @Test
    void theHostIsComparedCaseInsensitively() {
        assertEquals(Optional.of(new GitHubRepoRef("octocat", "Hello-World")),
                GitHubRepoRef.fromRemoteUrl("https://GitHub.com/octocat/Hello-World.git",
                        URI.create("https://github.com")));
    }

    @Test
    void aGitHubEnterpriseRemoteMatchesTheConfiguredServerOnly() {
        URI ghe = URI.create("https://ghe.example.com");

        assertEquals(Optional.of(new GitHubRepoRef("team", "app")),
                GitHubRepoRef.fromRemoteUrl("git@ghe.example.com:team/app.git", ghe));
        assertEquals(Optional.empty(),
                GitHubRepoRef.fromRemoteUrl("https://github.com/team/app.git", ghe),
                "a github.com repository must not be looked up on the Enterprise server");
    }

    @Test
    void aSelfManagedGitLabUnderAPathPrefixIsMatched() {
        URI gitlab = URI.create("https://example.com/gitlab");

        assertEquals(Optional.of(new GitLabProjectRef("group/sub/project")),
                GitLabProjectRef.fromRemoteUrl("https://example.com/gitlab/group/sub/project.git", gitlab));
        assertEquals(Optional.of(new GitLabProjectRef("group/project")),
                GitLabProjectRef.fromRemoteUrl("ssh://git@example.com:2222/group/project.git", gitlab));
    }

    @Test
    void aWebRemoteOnAnotherPortIsAnotherServer() {
        URI gitlab = URI.create("https://git.example.com:8443");

        assertTrue(GitLabProjectRef.fromRemoteUrl("https://git.example.com:8443/g/p.git", gitlab).isPresent());
        assertTrue(GitLabProjectRef.fromRemoteUrl("https://git.example.com/g/p.git", gitlab).isEmpty());
    }

    @Test
    void theGitLabSshPortIsNoLongerReadAsAGroup() {
        assertEquals(Optional.of(new GitLabProjectRef("g/p")),
                GitLabProjectRef.fromRemoteUrl("ssh://git@gitlab.com:2222/g/p", URI.create("https://gitlab.com")));
    }

    @Test
    void bitbucketIsMatchedByHostNotBySuffix() {
        assertTrue(BitbucketRepoRef.fromRemoteUrl("git@BitBucket.org:ws/repo.git").isPresent());
        assertTrue(BitbucketRepoRef.fromRemoteUrl("https://notbitbucket.org/ws/repo.git").isEmpty());
    }
}
