package com.jfx.git.core;

import com.jfx.git.model.Profile;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The composition {@code LyraApp.applyProfileToActiveTab} performs when a profile is
 * picked from the "Profiles" menu switcher: {@link ProfileStore#save} a profile, {@link
 * GitBackend#setLocalIdentity} it onto a real repository, {@link
 * ProfileStore#setActiveProfileName} remember the choice, {@link
 * ProfileStore#addTabPath} record the repository as belonging to that profile's own
 * tab set (Wave 4 item #4.4's deliberately additive-only "tabs per profile" - see
 * {@code LyraApp.openProfileTabs}'s own javadoc for why it does not also close
 * tabs the way the commercial clients' version does), then commit and confirm the identity
 * actually took.
 *
 * <p>Deliberately not driven through the real {@code MenuBar}: this session found
 * {@code ContextMenu} interaction under Monocle Headless fragile enough to cost hours
 * chasing a duplicate-node lookup and an {@code interact()}/{@code showAndWait()}
 * deadlock (see {@code BisectRobotTest}'s class Javadoc), and nothing else in this test
 * suite exercises the top-level {@code MenuBar} at all - not the theme switcher, not the
 * engine switcher. What actually matters here - that the three pieces the menu action
 * calls compose into a correct end-to-end identity switch - is exactly what this proves,
 * without gambling on menu-bar rendering nobody has established works under Monocle.
 */
class ProfileApplicationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Settings.useFile(tempDir.resolve("settings.properties"));
        try (Git git = Git.init().setDirectory(tempDir.resolve("repo").toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Default User");
            config.setString("user", null, "email", "default@example.com");
            config.save();
        }
    }

    @AfterEach
    void tearDown() {
        Settings.useFile(null);
    }

    private File repoDir() {
        return tempDir.resolve("repo").toFile();
    }

    @Test
    void applyingAProfileSwitchesTheRepositorysCommittedIdentity() throws Exception {
        Profile work = new Profile("Work", "Alice at Work", "alice@work.example", "", false);
        ProfileStore.save(work);

        GitService service = new GitService();
        try {
            service.openRepository(repoDir());
            String repoKey = ProfileStore.key(repoDir());

            // The action a menu click performs: apply, then remember the choice.
            GitResult applied = service.setLocalIdentity(
                    work.gitName(), work.gitEmail(), work.signingKey(), work.gpgSign());
            assertTrue(applied.isOk(), applied.getMessage());
            ProfileStore.setActiveProfileName(repoKey, work.name());
            ProfileStore.addTabPath(work.name(), repoKey);

            Files.writeString(repoDir().toPath().resolve("a.txt"), "x", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("a.txt").isOk());
            assertTrue(service.commit("from the switched profile", false).isOk());

            try (Git git = Git.open(repoDir());
                 var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                var head = walk.parseCommit(git.getRepository().resolve("HEAD"));
                assertEquals("Alice at Work", head.getAuthorIdent().getName());
                assertEquals("alice@work.example", head.getAuthorIdent().getEmailAddress());
            }
            assertEquals("Work", ProfileStore.activeProfileName(repoKey),
                    "the switch should be remembered for this repository");
            assertEquals(java.util.List.of(repoKey), ProfileStore.tabPaths("Work"),
                    "applying a profile should also add the repository to its remembered tab set");
        } finally {
            service.close();
        }
    }

    /**
     * The data half of "Open '&lt;profile&gt;' tabs": once a profile has been applied
     * to several repositories over time, {@link ProfileStore#tabPaths} is exactly the
     * list {@code LyraApp.openProfileTabs} would reopen - proven here against real
     * repositories rather than bare strings, so a canonicalisation mismatch between
     * what gets stored and what a later lookup expects would show up as a real
     * failure, not pass by coincidence.
     */
    @Test
    void aProfileAppliedToSeveralRepositoriesRemembersAllOfThemForReopening() throws Exception {
        Profile work = new Profile("Work", "Alice Work", "alice@work.example", "", false);
        ProfileStore.save(work);

        File repoA = tempDir.resolve("repo").toFile();
        File repoB = tempDir.resolve("repoB").toFile();
        Git.init().setDirectory(repoB).call().close();

        try (GitService a = new GitService(); GitService b = new GitService()) {
            a.openRepository(repoA);
            b.openRepository(repoB);

            String keyA = ProfileStore.key(repoA);
            String keyB = ProfileStore.key(repoB);
            assertTrue(a.setLocalIdentity(work.gitName(), work.gitEmail(), "", false).isOk());
            ProfileStore.setActiveProfileName(keyA, work.name());
            ProfileStore.addTabPath(work.name(), keyA);
            assertTrue(b.setLocalIdentity(work.gitName(), work.gitEmail(), "", false).isOk());
            ProfileStore.setActiveProfileName(keyB, work.name());
            ProfileStore.addTabPath(work.name(), keyB);

            assertEquals(java.util.List.of(keyA, keyB), ProfileStore.tabPaths("Work"));
        }
    }

    @Test
    void twoRepositoriesCanBeOnDifferentProfilesAtOnce() throws Exception {
        ProfileStore.save(new Profile("Work", "Alice Work", "alice@work.example", "", false));
        ProfileStore.save(new Profile("Personal", "Alice Home", "alice@home.example", "", false));

        File repoA = tempDir.resolve("repoA").toFile();
        File repoB = tempDir.resolve("repoB").toFile();
        Git.init().setDirectory(repoA).call().close();
        Git.init().setDirectory(repoB).call().close();

        try (GitService a = new GitService(); GitService b = new GitService()) {
            a.openRepository(repoA);
            b.openRepository(repoB);

            a.setLocalIdentity("Alice Work", "alice@work.example", "", false);
            ProfileStore.setActiveProfileName(ProfileStore.key(repoA), "Work");
            ProfileStore.addTabPath("Work", ProfileStore.key(repoA));
            b.setLocalIdentity("Alice Home", "alice@home.example", "", false);
            ProfileStore.setActiveProfileName(ProfileStore.key(repoB), "Personal");
            ProfileStore.addTabPath("Personal", ProfileStore.key(repoB));

            assertEquals("Work", ProfileStore.activeProfileName(ProfileStore.key(repoA)));
            assertEquals("Personal", ProfileStore.activeProfileName(ProfileStore.key(repoB)));
            assertEquals(java.util.List.of(ProfileStore.key(repoA)), ProfileStore.tabPaths("Work"),
                    "each profile's own tab set should stay independent of the other's");
            assertEquals(java.util.List.of(ProfileStore.key(repoB)), ProfileStore.tabPaths("Personal"));

            try (Git git = Git.open(repoA)) {
                assertEquals("Alice Work", git.getRepository().getConfig().getString("user", null, "name"));
            }
            try (Git git = Git.open(repoB)) {
                assertEquals("Alice Home", git.getRepository().getConfig().getString("user", null, "name"));
            }
        }
    }
}
