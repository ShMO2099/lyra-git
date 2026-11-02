package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starred repositories, in the order they were favorited.
 *
 * <p>Points the store at a temporary file first, for the reason {@code SettingsTest}
 * gives: otherwise these read and overwrite the developer's own favorites.
 */
class FavoritesTest {

    @TempDir
    Path tempDir;

    private File repoA;
    private File repoB;
    private File repoC;

    @BeforeEach
    void useTempFile() throws Exception {
        Settings.useFile(tempDir.resolve("settings.properties"));
        repoA = java.nio.file.Files.createDirectory(tempDir.resolve("repo-a")).toFile();
        repoB = java.nio.file.Files.createDirectory(tempDir.resolve("repo-b")).toFile();
        repoC = java.nio.file.Files.createDirectory(tempDir.resolve("repo-c")).toFile();
    }

    @AfterEach
    void restoreDefault() {
        Settings.useFile(null);
    }

    @Test
    void nothingIsFavoritedByDefault() {
        assertTrue(Favorites.load().isEmpty());
        assertFalse(Favorites.isFavorite(repoA));
    }

    @Test
    void addingAppendsRatherThanPrepending() {
        Favorites.add(repoA);
        Favorites.add(repoB);
        Favorites.add(repoC);

        assertEquals(List.of(repoA, repoB, repoC), Favorites.load(),
                "the order favorited is the order the 1-9 shortcuts must address");
    }

    @Test
    void addingAnAlreadyFavoritedRepositoryDoesNotMoveIt() {
        Favorites.add(repoA);
        Favorites.add(repoB);

        Favorites.add(repoA); // re-favoriting (or a defensive double-call) must not reorder

        assertEquals(List.of(repoA, repoB), Favorites.load(),
                "re-adding must not bump anyone else out of their keyboard-shortcut slot");
    }

    @Test
    void removingLeavesTheRemainingOrderIntact() {
        Favorites.add(repoA);
        Favorites.add(repoB);
        Favorites.add(repoC);

        Favorites.remove(repoB);

        assertEquals(List.of(repoA, repoC), Favorites.load());
    }

    @Test
    void toggleFavoritesThenUnfavorites() {
        assertFalse(Favorites.isFavorite(repoA));

        Favorites.toggle(repoA);
        assertTrue(Favorites.isFavorite(repoA));

        Favorites.toggle(repoA);
        assertFalse(Favorites.isFavorite(repoA));
    }

    @Test
    void aFavoriteNoLongerOnDiskIsFilteredOutOnLoad() throws Exception {
        File gone = tempDir.resolve("repo-gone").toFile();
        java.nio.file.Files.createDirectory(gone.toPath());
        Favorites.add(gone);
        assertTrue(Favorites.isFavorite(gone));

        java.nio.file.Files.delete(gone.toPath());

        assertFalse(Favorites.isFavorite(gone), "a deleted repository is not still a usable favorite");
        assertTrue(Favorites.load().isEmpty());
    }
}
