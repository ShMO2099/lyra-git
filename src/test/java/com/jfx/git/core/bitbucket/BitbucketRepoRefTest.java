package com.jfx.git.core.bitbucket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BitbucketRepoRef#fromRemoteUrl} mirrors {@code GitHubRepoRefTest} - see there
 * for why the lookbehind rejecting a host that merely ends in {@code bitbucket.org}
 * exists.
 */
class BitbucketRepoRefTest {

    @Test
    void parsesAnHttpsUrlWithTheGitSuffix() {
        var ref = BitbucketRepoRef.fromRemoteUrl("https://bitbucket.org/atlassian/localstack.git");
        assertTrue(ref.isPresent());
        assertEquals("atlassian", ref.get().workspace());
        assertEquals("localstack", ref.get().repoSlug());
    }

    @Test
    void parsesAnHttpsUrlWithoutTheGitSuffix() {
        var ref = BitbucketRepoRef.fromRemoteUrl("https://bitbucket.org/atlassian/localstack");
        assertTrue(ref.isPresent());
        assertEquals("atlassian", ref.get().workspace());
        assertEquals("localstack", ref.get().repoSlug());
    }

    @Test
    void parsesTheScpLikeSshSyntax() {
        var ref = BitbucketRepoRef.fromRemoteUrl("git@bitbucket.org:atlassian/localstack.git");
        assertTrue(ref.isPresent());
        assertEquals("atlassian", ref.get().workspace());
        assertEquals("localstack", ref.get().repoSlug());
    }

    @Test
    void parsesAnExplicitSshUrl() {
        var ref = BitbucketRepoRef.fromRemoteUrl("ssh://git@bitbucket.org/atlassian/localstack.git");
        assertTrue(ref.isPresent());
        assertEquals("atlassian", ref.get().workspace());
        assertEquals("localstack", ref.get().repoSlug());
    }

    @Test
    void toleratesATrailingSlash() {
        var ref = BitbucketRepoRef.fromRemoteUrl("https://bitbucket.org/atlassian/localstack/");
        assertTrue(ref.isPresent());
        assertEquals("localstack", ref.get().repoSlug());
    }

    @Test
    void aNonBitbucketHostIsEmpty() {
        assertTrue(BitbucketRepoRef.fromRemoteUrl("https://github.com/octocat/Hello-World.git").isEmpty());
    }

    @Test
    void aHostThatMerelyEndsInBitbucketDotOrgIsRejected() {
        assertTrue(BitbucketRepoRef.fromRemoteUrl("https://mybitbucket.org/workspace/repo.git").isEmpty());
        assertTrue(BitbucketRepoRef.fromRemoteUrl("https://sub.bitbucket.org/workspace/repo.git").isEmpty());
    }

    @Test
    void nullOrBlankIsEmpty() {
        assertTrue(BitbucketRepoRef.fromRemoteUrl(null).isEmpty());
        assertTrue(BitbucketRepoRef.fromRemoteUrl("   ").isEmpty());
        assertTrue(BitbucketRepoRef.fromRemoteUrl("").isEmpty());
    }
}
