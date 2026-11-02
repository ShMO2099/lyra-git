package com.jfx.git.core.gitea;

import com.jfx.git.core.ProviderHosts;
import com.jfx.git.core.Settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GiteaRepoRef#fromRemoteUrl} has to take every URL form git accepts for a remote,
 * and accept only the one server the integration is configured for - Gitea has no public
 * host to recognise, so a near miss is somebody else's server.
 */
class GiteaRepoRefTest {

    private static final URI CODEBERG = URI.create("https://codeberg.org");
    private static final URI SELF_HOSTED = URI.create("https://git.example.com:3000");

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

    private static void assertRef(String owner, String repo, Optional<GiteaRepoRef> ref) {
        assertTrue(ref.isPresent(), "expected a match");
        assertEquals(owner, ref.get().owner());
        assertEquals(repo, ref.get().repo());
    }

    @Test
    void parsesAnHttpsUrlWithAndWithoutTheGitSuffix() {
        assertRef("forgejo", "forgejo", GiteaRepoRef.fromRemoteUrl("https://codeberg.org/forgejo/forgejo.git", CODEBERG));
        assertRef("forgejo", "forgejo", GiteaRepoRef.fromRemoteUrl("https://codeberg.org/forgejo/forgejo", CODEBERG));
        assertRef("forgejo", "forgejo", GiteaRepoRef.fromRemoteUrl("https://codeberg.org/forgejo/forgejo/", CODEBERG));
    }

    @Test
    void parsesTheScpLikeAndExplicitSshForms() {
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("git@codeberg.org:alice/demo.git", CODEBERG));
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("ssh://git@codeberg.org/alice/demo.git", CODEBERG));
    }

    /** An ssh port is the ssh daemon's, not part of the path and not the web server's. */
    @Test
    void anSshPortIsAPortNotAnOwner() {
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("ssh://git@git.example.com:2222/alice/demo.git",
                SELF_HOSTED));
    }

    @Test
    void aWebRemoteMustBeOnTheConfiguredPort() {
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("https://git.example.com:3000/alice/demo.git",
                SELF_HOSTED));
        assertTrue(GiteaRepoRef.fromRemoteUrl("https://git.example.com/alice/demo.git", SELF_HOSTED).isEmpty(),
                "port 443 is a different server from port 3000");
    }

    @Test
    void theHostIsComparedWithoutCaseButNeverBySuffix() {
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("https://CodeBerg.org/alice/demo.git", CODEBERG));
        assertTrue(GiteaRepoRef.fromRemoteUrl("https://mycodeberg.org/alice/demo.git", CODEBERG).isEmpty());
        assertTrue(GiteaRepoRef.fromRemoteUrl("https://sub.codeberg.org/alice/demo.git", CODEBERG).isEmpty());
        assertTrue(GiteaRepoRef.fromRemoteUrl("https://github.com/alice/demo.git", CODEBERG).isEmpty());
    }

    /** Gitea has no nested groups: anything but owner/name is not a repository there. */
    @Test
    void onlyOwnerAndNameIsARepository() {
        assertTrue(GiteaRepoRef.fromRemoteUrl("https://codeberg.org/group/sub/demo.git", CODEBERG).isEmpty());
        assertTrue(GiteaRepoRef.fromRemoteUrl("https://codeberg.org/alice", CODEBERG).isEmpty());
    }

    @Test
    void aServerUnderAPathPrefixHasThePrefixRemoved() {
        URI prefixed = URI.create("https://example.com/gitea");
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("https://example.com/gitea/alice/demo.git", prefixed));
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("git@example.com:alice/demo.git", prefixed));
    }

    @Test
    void theConfiguredServerIsCodebergUntilAnotherIsSet() {
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("https://codeberg.org/alice/demo.git"));

        ProviderHosts.setGitea("https://git.example.com:3000");

        assertTrue(GiteaRepoRef.fromRemoteUrl("https://codeberg.org/alice/demo.git").isEmpty());
        assertRef("alice", "demo", GiteaRepoRef.fromRemoteUrl("https://git.example.com:3000/alice/demo.git"));
    }

    @Test
    void nullBlankAndLocalPathsAreEmpty() {
        assertTrue(GiteaRepoRef.fromRemoteUrl(null, CODEBERG).isEmpty());
        assertTrue(GiteaRepoRef.fromRemoteUrl("  ", CODEBERG).isEmpty());
        assertTrue(GiteaRepoRef.fromRemoteUrl("C:\\repos\\demo", CODEBERG).isEmpty());
    }
}
