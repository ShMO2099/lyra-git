package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the provider clients point.
 *
 * <p>Getting this wrong is quiet: an unusable base URL does not fail here, it fails later
 * as an integration that will not connect for reasons the user cannot see. So the
 * fallback to the public host has to hold for every shape of bad input.
 */
class ProviderHostsTest {

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

    @Test
    void withNothingConfiguredTheClientsTalkToThePublicHosts() {
        assertEquals(ProviderHosts.GITHUB_PUBLIC, ProviderHosts.github());
        assertEquals(ProviderHosts.GITLAB_PUBLIC, ProviderHosts.gitlab());
        assertEquals("", ProviderHosts.githubSetting());
        assertEquals("", ProviderHosts.gitlabSetting());
    }

    /**
     * People paste what the browser shows them, not the API root. Appending the API path
     * is the difference between working and failing for reasons nobody can see.
     */
    @Test
    void aBareServerUrlGetsTheApiPathItNeeds() {
        ProviderHosts.setGithub("https://ghe.example.com");
        ProviderHosts.setGitlab("https://gitlab.example.com");

        assertEquals(URI.create("https://ghe.example.com/api/v3"), ProviderHosts.github());
        assertEquals(URI.create("https://gitlab.example.com/api/v4"), ProviderHosts.gitlab());
    }

    /** An install behind a path prefix has to be left exactly as the user gave it. */
    @Test
    void aUrlThatAlreadyNamesAPathIsLeftAlone() {
        ProviderHosts.setGithub("https://example.com/github/api/v3");

        assertEquals(URI.create("https://example.com/github/api/v3"), ProviderHosts.github());
    }

    @Test
    void aTrailingSlashDoesNotProduceADoubleSlash() {
        ProviderHosts.setGithub("https://ghe.example.com/");
        ProviderHosts.setGitlab("https://gitlab.example.com/api/v4/");

        assertEquals(URI.create("https://ghe.example.com/api/v3"), ProviderHosts.github());
        assertEquals(URI.create("https://gitlab.example.com/api/v4"), ProviderHosts.gitlab());
    }

    @Test
    void blankGoesBackToThePublicHost() {
        ProviderHosts.setGithub("https://ghe.example.com");
        assertNotEquals(ProviderHosts.GITHUB_PUBLIC, ProviderHosts.github());

        ProviderHosts.setGithub("   ");

        assertEquals(ProviderHosts.GITHUB_PUBLIC, ProviderHosts.github());
        assertEquals("", ProviderHosts.githubSetting());
    }

    /**
     * A settings file is meant to be hand-edited, so it will sometimes be hand-edited
     * wrongly - and the answer to that is the public host, not an exception on startup.
     */
    @Test
    void anUnusableUrlFallsBackInsteadOfThrowing() {
        for (String nonsense : new String[]{"not a url", "/just/a/path", "ghe.example.com", ":::"}) {
            Settings.put("integration.github.baseUrl", nonsense);
            assertEquals(ProviderHosts.GITHUB_PUBLIC, ProviderHosts.github(),
                    "should have fallen back for: " + nonsense);
        }
    }

    // ------------------------------------------------------------------
    // T4: tokens belong to a server, and never travel over plain http
    // ------------------------------------------------------------------

    @Test
    void plainHttpIsRefusedExceptOnThisMachine() {
        assertThrows(IllegalArgumentException.class, () -> ProviderHosts.setGithub("http://ghe.example.com"));
        assertEquals("", ProviderHosts.githubSetting(), "nothing was stored");
        assertNull(ProviderHosts.problemWith("http://localhost:8080"));
        assertNull(ProviderHosts.problemWith("https://ghe.example.com"));
        assertNotNull(ProviderHosts.problemWith("ftp://ghe.example.com"));
    }

    /** A hand-edited http:// setting is not honoured either. */
    @Test
    void aHandEditedHttpSettingFallsBackToThePublicHost() {
        Settings.put("integration.gitlab.baseUrl", "http://gitlab.example.com");

        assertEquals(ProviderHosts.GITLAB_PUBLIC, ProviderHosts.gitlab());
    }

    @Test
    void tokensAreStoredPerServer() {
        assertEquals("github@github.com", ProviderHosts.tokenAccount("github"));
        assertEquals("bitbucket@bitbucket.org", ProviderHosts.tokenAccount("bitbucket"));

        ProviderHosts.setGithub("https://GHE.example.com:8443");

        assertEquals("github@ghe.example.com:8443", ProviderHosts.tokenAccount("github"),
                "a token remembered for one server is never offered to another");
        assertEquals(URI.create("https://GHE.example.com:8443"), ProviderHosts.githubWeb());
    }

    // ------------------------------------------------------------------
    // Gitea / Forgejo: Codeberg by default, any server by configuration
    // ------------------------------------------------------------------

    @Test
    void giteaDefaultsToCodeberg() {
        assertEquals(URI.create("https://codeberg.org/api/v1"), ProviderHosts.gitea());
        assertEquals(ProviderHosts.GITEA_DEFAULT, ProviderHosts.gitea());
        assertEquals(URI.create("https://codeberg.org"), ProviderHosts.giteaWeb());
        assertEquals("", ProviderHosts.giteaSetting());
        assertEquals("gitea@codeberg.org", ProviderHosts.tokenAccount("gitea"));
        assertEquals("gitea@codeberg.org", com.jfx.git.core.hosting.HostingProvider.GITEA.tokenAccount());
    }

    @Test
    void aSelfHostedGiteaServerGetsTheApiPath() {
        ProviderHosts.setGitea("https://git.example.com:3000/");

        assertEquals(URI.create("https://git.example.com:3000/api/v1"), ProviderHosts.gitea());
        assertEquals(URI.create("https://git.example.com:3000"), ProviderHosts.giteaWeb());
        assertEquals("gitea@git.example.com:3000", ProviderHosts.tokenAccount("gitea"));
        assertEquals("https://git.example.com:3000/", ProviderHosts.giteaSetting());
        assertEquals(ProviderHosts.gitea(), ProviderHosts.api(com.jfx.git.core.hosting.HostingProvider.GITEA));
        assertEquals(ProviderHosts.giteaWeb(), ProviderHosts.web(com.jfx.git.core.hosting.HostingProvider.GITEA));
    }

    /** Typing Codeberg's own address in is the same as leaving it blank. */
    @Test
    void codebergTypedInFullIsStillCodeberg() {
        ProviderHosts.setGitea("https://codeberg.org");

        assertEquals(ProviderHosts.GITEA_DEFAULT, ProviderHosts.gitea());
        assertEquals(URI.create("https://codeberg.org"), ProviderHosts.giteaWeb());
    }

    @Test
    void aGiteaServerUnderAPathPrefixKeepsIt() {
        ProviderHosts.setGitea("https://example.com/gitea/api/v1");

        assertEquals(URI.create("https://example.com/gitea/api/v1"), ProviderHosts.gitea());
        assertEquals(URI.create("https://example.com/gitea"), ProviderHosts.giteaWeb());
    }

    @Test
    void giteaRefusesPlainHttpLikeTheOthers() {
        assertThrows(IllegalArgumentException.class, () -> ProviderHosts.setGitea("http://git.example.com"));
        assertEquals("", ProviderHosts.giteaSetting());

        ProviderHosts.setGitea("http://localhost:3000");
        assertEquals(URI.create("http://localhost:3000/api/v1"), ProviderHosts.gitea());

        Settings.put("integration.gitea.baseUrl", "http://git.example.com");
        assertEquals(ProviderHosts.GITEA_DEFAULT, ProviderHosts.gitea(), "a hand-edited http:// is not honoured");
    }

    @Test
    void blankGoesBackToCodeberg() {
        ProviderHosts.setGitea("https://git.example.com");

        ProviderHosts.setGitea("");

        assertEquals(ProviderHosts.GITEA_DEFAULT, ProviderHosts.gitea());
        assertEquals("", ProviderHosts.giteaSetting());
    }

    @Test
    void theSettingSurvivesBeingWrittenAndReadBack() {
        ProviderHosts.setGitlab("https://gitlab.example.com");
        Settings.flush();
        Settings.useFile(tempDir.resolve("settings.properties"));

        assertEquals("https://gitlab.example.com", ProviderHosts.gitlabSetting());
    }
}
