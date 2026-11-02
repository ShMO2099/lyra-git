package com.jfx.git.core.gitlab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GitLabProjectRef#fromRemoteUrl} has to handle every URL form git accepts for
 * a GitLab remote, including nested groups - a form GitHub and Bitbucket have no
 * equivalent of - and reject a host that merely happens to end in {@code gitlab.com}.
 */
class GitLabProjectRefTest {

    @Test
    void parsesAnHttpsUrlWithTheGitSuffix() {
        var ref = GitLabProjectRef.fromRemoteUrl("https://gitlab.com/gitlab-org/gitlab-runner.git");
        assertTrue(ref.isPresent());
        assertEquals("gitlab-org/gitlab-runner", ref.get().fullPath());
    }

    @Test
    void parsesAnHttpsUrlWithoutTheGitSuffix() {
        var ref = GitLabProjectRef.fromRemoteUrl("https://gitlab.com/gitlab-org/gitlab-runner");
        assertTrue(ref.isPresent());
        assertEquals("gitlab-org/gitlab-runner", ref.get().fullPath());
    }

    @Test
    void parsesTheScpLikeSshSyntax() {
        var ref = GitLabProjectRef.fromRemoteUrl("git@gitlab.com:gitlab-org/gitlab-runner.git");
        assertTrue(ref.isPresent());
        assertEquals("gitlab-org/gitlab-runner", ref.get().fullPath());
    }

    @Test
    void parsesAnExplicitSshUrl() {
        var ref = GitLabProjectRef.fromRemoteUrl("ssh://git@gitlab.com/gitlab-org/gitlab-runner.git");
        assertTrue(ref.isPresent());
        assertEquals("gitlab-org/gitlab-runner", ref.get().fullPath());
    }

    @Test
    void parsesANestedSubgroup() {
        var ref = GitLabProjectRef.fromRemoteUrl("https://gitlab.com/gitlab-org/frontend/some-project.git");
        assertTrue(ref.isPresent());
        assertEquals("gitlab-org/frontend/some-project", ref.get().fullPath());
    }

    @Test
    void toleratesATrailingSlash() {
        var ref = GitLabProjectRef.fromRemoteUrl("https://gitlab.com/gitlab-org/gitlab-runner/");
        assertTrue(ref.isPresent());
        assertEquals("gitlab-org/gitlab-runner", ref.get().fullPath());
    }

    @Test
    void aNonGitLabHostIsEmpty() {
        assertTrue(GitLabProjectRef.fromRemoteUrl("https://github.com/octocat/Hello-World.git").isEmpty());
    }

    @Test
    void aHostThatMerelyEndsInGitlabDotComIsRejected() {
        assertTrue(GitLabProjectRef.fromRemoteUrl("https://mygitlab.com/group/project.git").isEmpty());
        assertTrue(GitLabProjectRef.fromRemoteUrl("https://sub.gitlab.com/group/project.git").isEmpty());
    }

    @Test
    void nullOrBlankIsEmpty() {
        assertTrue(GitLabProjectRef.fromRemoteUrl(null).isEmpty());
        assertTrue(GitLabProjectRef.fromRemoteUrl("   ").isEmpty());
        assertTrue(GitLabProjectRef.fromRemoteUrl("").isEmpty());
    }

    @Test
    void encodedPathReplacesSlashesWithPercentTwoF() {
        var ref = new GitLabProjectRef("gitlab-org/frontend/some-project");
        assertEquals("gitlab-org%2Ffrontend%2Fsome-project", ref.encodedPath());
    }
}
