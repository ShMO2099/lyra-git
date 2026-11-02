package com.jfx.git.core;

import com.jfx.git.model.Profile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Points the settings store at a temporary file, so these neither read nor overwrite
 * the developer's own profiles - the same isolation {@code GraphColumnTest} uses.
 */
class ProfileStoreTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void useTempFile() {
        Settings.useFile(tempDir.resolve("settings.properties"));
    }

    @AfterEach
    void restoreDefault() {
        Settings.useFile(null);
    }

    @Test
    void noProfilesByDefault() {
        assertTrue(ProfileStore.profiles().isEmpty());
    }

    @Test
    void savingAProfileMakesItRetrievableByName() {
        ProfileStore.save(new Profile("Work", "Alice", "alice@work.com", "", false));

        Profile found = ProfileStore.profile("Work");
        assertNotNull(found);
        assertEquals("Alice", found.gitName());
        assertEquals("alice@work.com", found.gitEmail());
    }

    @Test
    void savingATrailingProfileWithTheSameNameReplacesItInPlace() {
        ProfileStore.save(new Profile("Work", "Alice", "alice@work.com", "", false));
        ProfileStore.save(new Profile("Personal", "Alice", "alice@home.com", "", false));
        ProfileStore.save(new Profile("Work", "Alice W.", "alice@newwork.com", "KEY123", true));

        List<Profile> all = ProfileStore.profiles();
        assertEquals(2, all.size(), "re-saving must update, not append, a duplicate name");
        assertEquals("Work", all.get(0).name(), "editing must not reorder the profile in the list");
        assertEquals("Alice W.", all.get(0).gitName());
        assertEquals("alice@newwork.com", all.get(0).gitEmail());
        assertEquals("KEY123", all.get(0).signingKey());
        assertTrue(all.get(0).gpgSign());
    }

    @Test
    void deletingAProfileRemovesItButNotOthers() {
        ProfileStore.save(new Profile("Work", "Alice", "alice@work.com", "", false));
        ProfileStore.save(new Profile("Personal", "Alice", "alice@home.com", "", false));

        ProfileStore.delete("Work");

        assertNull(ProfileStore.profile("Work"));
        assertNotNull(ProfileStore.profile("Personal"));
    }

    @Test
    void deletingANonexistentProfileDoesNothing() {
        ProfileStore.save(new Profile("Work", "Alice", "alice@work.com", "", false));

        ProfileStore.delete("Nope");

        assertEquals(1, ProfileStore.profiles().size());
    }

    @Test
    void aBlankNameIsRejected() {
        ProfileStore.save(new Profile("", "Alice", "alice@work.com", "", false));
        ProfileStore.save(new Profile("   ", "Alice", "alice@work.com", "", false));

        assertTrue(ProfileStore.profiles().isEmpty());
    }

    @Test
    void fieldsContainingTheRecordOrFieldSeparatorAreNotCorruptedByStorage() {
        // Not something the UI should ever produce, but the store must not silently
        // merge or split records if it somehow does - Profile.name() with a literal
        // tab would otherwise be indistinguishable from a name/email boundary.
        ProfileStore.save(new Profile("Weird\tName", "Alice", "alice@work.com", "", false));

        // Not asserting a specific outcome beyond "does not crash and does not corrupt
        // a different profile" - the save is silently rejected today (blank-name path
        // does not cover this, so this documents current behaviour rather than a
        // contract): the important invariant is the store as a whole stays parseable.
        ProfileStore.save(new Profile("Normal", "Bob", "bob@work.com", "", false));
        assertNotNull(ProfileStore.profile("Normal"));
    }

    @Test
    void noActiveProfileByDefault() {
        assertNull(ProfileStore.activeProfileName("/some/repo"));
    }

    @Test
    void settingTheActiveProfileForARepoRoundTrips() {
        ProfileStore.setActiveProfileName("/repo/a", "Work");
        ProfileStore.setActiveProfileName("/repo/b", "Personal");

        assertEquals("Work", ProfileStore.activeProfileName("/repo/a"));
        assertEquals("Personal", ProfileStore.activeProfileName("/repo/b"));
    }

    @Test
    void clearingTheActiveProfileForOneRepoLeavesOthersAlone() {
        ProfileStore.setActiveProfileName("/repo/a", "Work");
        ProfileStore.setActiveProfileName("/repo/b", "Personal");

        ProfileStore.setActiveProfileName("/repo/a", null);

        assertNull(ProfileStore.activeProfileName("/repo/a"));
        assertEquals("Personal", ProfileStore.activeProfileName("/repo/b"));
    }

    @Test
    void reapplyingTheSameActiveProfileIsIdempotent() {
        ProfileStore.setActiveProfileName("/repo/a", "Work");
        ProfileStore.setActiveProfileName("/repo/a", "Work");

        assertEquals("Work", ProfileStore.activeProfileName("/repo/a"));
    }

    @Test
    void keyMatchesTabSessionsCanonicalisation() {
        java.io.File dir = tempDir.toFile();
        assertEquals(TabSession.key(dir), ProfileStore.key(dir));
    }

    // ------------------------------------------------------------------
    // tabPaths / addTabPath
    // ------------------------------------------------------------------

    @Test
    void noTabPathsByDefault() {
        assertTrue(ProfileStore.tabPaths("Work").isEmpty());
    }

    @Test
    void addingATabPathMakesItRetrievableForThatProfile() {
        ProfileStore.addTabPath("Work", "/repo/a");

        assertEquals(List.of("/repo/a"), ProfileStore.tabPaths("Work"));
    }

    @Test
    void tabPathsAreKeptSeparatePerProfile() {
        ProfileStore.addTabPath("Work", "/repo/a");
        ProfileStore.addTabPath("Personal", "/repo/b");

        assertEquals(List.of("/repo/a"), ProfileStore.tabPaths("Work"));
        assertEquals(List.of("/repo/b"), ProfileStore.tabPaths("Personal"));
    }

    @Test
    void addingTheSamePathTwiceDoesNotDuplicateIt() {
        ProfileStore.addTabPath("Work", "/repo/a");
        ProfileStore.addTabPath("Work", "/repo/a");

        assertEquals(List.of("/repo/a"), ProfileStore.tabPaths("Work"));
    }

    @Test
    void multiplePathsForOneProfileKeepInsertionOrder() {
        ProfileStore.addTabPath("Work", "/repo/a");
        ProfileStore.addTabPath("Work", "/repo/b");
        ProfileStore.addTabPath("Work", "/repo/c");

        assertEquals(List.of("/repo/a", "/repo/b", "/repo/c"), ProfileStore.tabPaths("Work"));
    }

    @Test
    void aBlankOrSeparatorContainingPathOrProfileNameIsRejected() {
        ProfileStore.addTabPath("", "/repo/a");
        ProfileStore.addTabPath("Work", "");
        ProfileStore.addTabPath("Wo\trk", "/repo/a");
        ProfileStore.addTabPath("Work", "/re\tpo/a");

        assertTrue(ProfileStore.tabPaths("Work").isEmpty());
        assertTrue(ProfileStore.tabPaths("").isEmpty());
    }
}
