package com.jfx.git.graph;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.Settings;
import com.jfx.git.model.RefInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which refs {@code GitBackend.getCommitGraphPage} treats as walk starts under the
 * user's Hide/Solo choice.
 *
 * <p>Points the settings store at a temporary file, so {@link #currentReadsStoredState}
 * neither reads nor overwrites the developer's own Hide/Solo choices.
 */
class GraphVisibilityTest {

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

    private static RefInfo local(String shortName) {
        return new RefInfo("refs/heads/" + shortName, shortName, "deadbeef");
    }

    @Test
    void allIsUnrestrictedAndExcludesNothing() {
        assertTrue(GraphVisibility.ALL.isUnrestricted());
        assertFalse(GraphVisibility.ALL.excludes(local("anything")));
    }

    @Test
    void aNullConstructorArgumentBecomesAnEmptySet() {
        var visibility = new GraphVisibility(null, null);
        assertTrue(visibility.hiddenRefs().isEmpty());
        assertTrue(visibility.isUnrestricted());
    }

    @Test
    void aHiddenRefIsExcludedAndEverythingElseIsNot() {
        var visibility = new GraphVisibility(Set.of("refs/heads/old"), null);
        assertFalse(visibility.isUnrestricted());
        assertTrue(visibility.excludes(local("old")));
        assertFalse(visibility.excludes(local("main")));
    }

    @Test
    void aSoloedRefIsTheOnlyOneNotExcluded() {
        var visibility = new GraphVisibility(Set.of(), "refs/heads/release");
        assertFalse(visibility.isUnrestricted());
        assertFalse(visibility.excludes(local("release")));
        assertTrue(visibility.excludes(local("main")));
        assertTrue(visibility.excludes(local("anything-else")));
    }

    @Test
    void soloOverridesHideForTheSameRef() {
        // Named in both sets at once: solo must win, matching the commercial clients' toggle
        // where choosing Solo on a branch supersedes any Hide state on it.
        var visibility = new GraphVisibility(Set.of("refs/heads/x"), "refs/heads/x");
        assertFalse(visibility.excludes(local("x")));
    }

    @Test
    void excludesIsFalseForANullRef() {
        assertFalse(GraphVisibility.ALL.excludes(null));
        assertFalse(new GraphVisibility(Set.of("refs/heads/x"), null).excludes(null));
    }

    @Test
    void localAndRemoteBranchesSharingAShortNameAreDistinctByFullName() {
        RefInfo localMain = new RefInfo("refs/heads/main", "main", "aaaa");
        RefInfo remoteMain = new RefInfo("refs/remotes/origin/main", "origin/main", "bbbb");

        var hideLocalOnly = new GraphVisibility(Set.of("refs/heads/main"), null);
        assertTrue(hideLocalOnly.excludes(localMain));
        assertFalse(hideLocalOnly.excludes(remoteMain));
    }

    @Test
    void currentReadsStoredState() {
        AppPreferences.setGraphHiddenBranches(Set.of("refs/heads/a"));
        AppPreferences.setGraphSoloBranch("refs/heads/b");

        GraphVisibility current = GraphVisibility.current();
        assertEquals(Set.of("refs/heads/a"), current.hiddenRefs());
        assertEquals("refs/heads/b", current.soloRef());
    }
}
