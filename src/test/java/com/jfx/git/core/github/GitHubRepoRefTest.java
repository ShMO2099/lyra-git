package com.jfx.git.core.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GitHubRepoRef#fromRemoteUrl} has to handle every URL form git itself accepts
 * for a GitHub remote, and reject a host that merely happens to end in
 * {@code github.com} - see the class Javadoc for why that lookbehind exists.
 */
class GitHubRepoRefTest {

    @Test
    void parsesAnHttpsUrlWithTheGitSuffix() {
        var ref = GitHubRepoRef.fromRemoteUrl("https://github.com/octocat/Hello-World.git");
        assertTrue(ref.isPresent());
        assertEquals("octocat", ref.get().owner());
        assertEquals("Hello-World", ref.get().repo());
    }

    @Test
    void parsesAnHttpsUrlWithoutTheGitSuffix() {
        var ref = GitHubRepoRef.fromRemoteUrl("https://github.com/octocat/Hello-World");
        assertTrue(ref.isPresent());
        assertEquals("octocat", ref.get().owner());
        assertEquals("Hello-World", ref.get().repo());
    }

    @Test
    void parsesTheScpLikeSshSyntax() {
        var ref = GitHubRepoRef.fromRemoteUrl("git@github.com:octocat/Hello-World.git");
        assertTrue(ref.isPresent());
        assertEquals("octocat", ref.get().owner());
        assertEquals("Hello-World", ref.get().repo());
    }

    @Test
    void parsesAnExplicitSshUrl() {
        var ref = GitHubRepoRef.fromRemoteUrl("ssh://git@github.com/octocat/Hello-World.git");
        assertTrue(ref.isPresent());
        assertEquals("octocat", ref.get().owner());
        assertEquals("Hello-World", ref.get().repo());
    }

    @Test
    void toleratesATrailingSlash() {
        var ref = GitHubRepoRef.fromRemoteUrl("https://github.com/octocat/Hello-World/");
        assertTrue(ref.isPresent());
        assertEquals("Hello-World", ref.get().repo());
    }

    @Test
    void aNonGitHubHostIsEmpty() {
        assertTrue(GitHubRepoRef.fromRemoteUrl("https://gitlab.com/octocat/Hello-World.git").isEmpty());
    }

    @Test
    void aHostThatMerelyEndsInGithubDotComIsRejected() {
        assertTrue(GitHubRepoRef.fromRemoteUrl("https://mygithub.com/octocat/Hello-World.git").isEmpty());
        assertTrue(GitHubRepoRef.fromRemoteUrl("https://sub.github.com/octocat/Hello-World.git").isEmpty());
    }

    @Test
    void nullOrBlankIsEmpty() {
        assertTrue(GitHubRepoRef.fromRemoteUrl(null).isEmpty());
        assertTrue(GitHubRepoRef.fromRemoteUrl("   ").isEmpty());
        assertTrue(GitHubRepoRef.fromRemoteUrl("").isEmpty());
    }
}
