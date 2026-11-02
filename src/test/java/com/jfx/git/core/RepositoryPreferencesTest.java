package com.jfx.git.core;

import com.jfx.git.model.PullMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-repository overrides on top of the global settings.
 *
 * <p>The property that matters throughout is the fallback: an absent override means
 * "whatever the global setting says", read at the time of asking rather than copied when
 * the repository was first opened. Getting that wrong would freeze every repository at
 * whatever the global value happened to be the first time it was seen, which is the kind
 * of bug nobody reports because it looks like the setting simply not working.
 */
class RepositoryPreferencesTest {

    @TempDir
    Path tempDir;

    private File repository;
    private File otherRepository;

    @BeforeEach
    void useATempSettingsFile() throws Exception {
        Settings.useFile(tempDir.resolve("settings.properties"));
        repository = Files.createDirectories(tempDir.resolve("repo")).toFile();
        otherRepository = Files.createDirectories(tempDir.resolve("other")).toFile();
    }

    @AfterEach
    void restoreDefaults() {
        Settings.useFile(null);
    }

    // ------------------------------------------------------------------
    // Auto-fetch
    // ------------------------------------------------------------------

    @Test
    void withNoOverrideARepositoryFollowsTheGlobalInterval() {
        AppPreferences.setAutoFetchIntervalMinutes(15);

        assertEquals(Optional.empty(), RepositoryPreferences.autoFetchIntervalMinutes(repository));
        assertEquals(15, RepositoryPreferences.effectiveAutoFetchIntervalMinutes(repository));
    }

    @Test
    void anOverrideWinsOverTheGlobalInterval() {
        AppPreferences.setAutoFetchIntervalMinutes(15);
        RepositoryPreferences.setAutoFetchIntervalMinutes(repository, 1);

        assertEquals(1, RepositoryPreferences.effectiveAutoFetchIntervalMinutes(repository));
        assertEquals(15, RepositoryPreferences.effectiveAutoFetchIntervalMinutes(otherRepository),
                "one repository's override must not reach another");
    }

    /** Zero is a real choice - "never fetch this one" - not an absent override. */
    @Test
    void zeroIsAnOverrideAndNotAnAbsentOne() {
        AppPreferences.setAutoFetchIntervalMinutes(5);
        RepositoryPreferences.setAutoFetchIntervalMinutes(repository, 0);

        assertEquals(Optional.of(0), RepositoryPreferences.autoFetchIntervalMinutes(repository));
        assertEquals(0, RepositoryPreferences.effectiveAutoFetchIntervalMinutes(repository));
    }

    @Test
    void clearingAnOverrideReturnsToTheGlobalSetting() {
        AppPreferences.setAutoFetchIntervalMinutes(5);
        RepositoryPreferences.setAutoFetchIntervalMinutes(repository, 60);

        RepositoryPreferences.setAutoFetchIntervalMinutes(repository, null);

        assertEquals(Optional.empty(), RepositoryPreferences.autoFetchIntervalMinutes(repository));
        assertEquals(5, RepositoryPreferences.effectiveAutoFetchIntervalMinutes(repository));
    }

    /**
     * The global value is read when asked, not copied when the override was written -
     * otherwise changing the global setting would leave every repository on the old one.
     */
    @Test
    void theGlobalValueIsReadFreshEachTime() {
        AppPreferences.setAutoFetchIntervalMinutes(5);
        assertEquals(5, RepositoryPreferences.effectiveAutoFetchIntervalMinutes(repository));

        AppPreferences.setAutoFetchIntervalMinutes(60);

        assertEquals(60, RepositoryPreferences.effectiveAutoFetchIntervalMinutes(repository));
    }

    // ------------------------------------------------------------------
    // Engine and pull mode
    // ------------------------------------------------------------------

    @Test
    void theEngineOverrideIsPerRepository() {
        GitBackendFactory.setEnginePreference(GitBackendFactory.BackendEngine.AUTO);
        RepositoryPreferences.setEngine(repository, GitBackendFactory.BackendEngine.JGIT);

        assertEquals(GitBackendFactory.BackendEngine.JGIT,
                RepositoryPreferences.effectiveEngine(repository));
        assertEquals(GitBackendFactory.BackendEngine.AUTO,
                RepositoryPreferences.effectiveEngine(otherRepository));
    }

    /**
     * AUTO is a way of choosing an engine, not an engine. Stored as an override it would
     * mean what no override means, while never matching
     * {@link GitBackendFactory#engineOf} - so every open of the repository would re-open
     * it to arrive where it already was. The dialog does not offer it; this covers the
     * settings file being edited by hand, which it is meant to be.
     */
    @Test
    void aStoredAutoEngineIsNotTreatedAsAnOverride() {
        GitBackendFactory.setEnginePreference(GitBackendFactory.BackendEngine.JGIT);
        Settings.put(RepositoryPreferences.key(repository, "git.engine"),
                GitBackendFactory.BackendEngine.AUTO.name());

        assertEquals(Optional.empty(), RepositoryPreferences.engine(repository));
        assertEquals(GitBackendFactory.BackendEngine.JGIT,
                RepositoryPreferences.effectiveEngine(repository),
                "it should fall through to the global setting, not pin AUTO");
        assertFalse(RepositoryPreferences.hasOverrides(repository));
    }

    @Test
    void thePullModeOverrideIsPerRepository() {
        AppPreferences.setDefaultPullMode(PullMode.MERGE);
        RepositoryPreferences.setPullMode(repository, PullMode.REBASE);

        assertEquals(PullMode.REBASE, RepositoryPreferences.effectivePullMode(repository));
        assertEquals(PullMode.MERGE, RepositoryPreferences.effectivePullMode(otherRepository));
    }

    @Test
    void theGlobalPullModeDefaultsToMerge() {
        assertEquals(PullMode.MERGE, AppPreferences.defaultPullMode());
    }

    // ------------------------------------------------------------------
    // Robustness
    // ------------------------------------------------------------------

    /**
     * The settings file invites hand-editing, so it will sometimes be hand-edited wrongly.
     * A bad value has to read as "no override" rather than stopping the repository opening.
     */
    @Test
    void nonsenseInTheFileFallsBackInsteadOfThrowing() {
        AppPreferences.setAutoFetchIntervalMinutes(5);
        Settings.put(RepositoryPreferences.key(repository, "autoFetch.minutes"), "soon");
        Settings.put(RepositoryPreferences.key(repository, "git.engine"), "TELEPATHY");
        Settings.put(RepositoryPreferences.key(repository, "pull.mode"), "VIBES");

        assertEquals(5, RepositoryPreferences.effectiveAutoFetchIntervalMinutes(repository));
        assertEquals(GitBackendFactory.getEnginePreference(),
                RepositoryPreferences.effectiveEngine(repository));
        assertEquals(AppPreferences.defaultPullMode(),
                RepositoryPreferences.effectivePullMode(repository));
    }

    /**
     * The same repository reached by a different but equivalent path is one repository.
     * Otherwise opening it from the recent list and from a file dialog would give it two
     * sets of settings.
     */
    @Test
    void anEquivalentPathIsTheSameRepository() {
        RepositoryPreferences.setAutoFetchIntervalMinutes(repository, 42);

        File roundabout = new File(repository, "." + File.separator);
        assertEquals(Optional.of(42), RepositoryPreferences.autoFetchIntervalMinutes(roundabout));
    }

    @Test
    void hasOverridesReportsWhatIsActuallySet() {
        assertFalse(RepositoryPreferences.hasOverrides(repository));

        RepositoryPreferences.setPullMode(repository, PullMode.FF_ONLY);
        assertTrue(RepositoryPreferences.hasOverrides(repository));

        RepositoryPreferences.clear(repository);
        assertFalse(RepositoryPreferences.hasOverrides(repository));
    }

    @Test
    void aNullRepositoryIsToleratedRatherThanFatal() {
        assertDoesNotThrow(() -> {
            assertEquals(Optional.empty(), RepositoryPreferences.autoFetchIntervalMinutes(null));
            RepositoryPreferences.setPullMode(null, PullMode.REBASE);
            RepositoryPreferences.clear(null);
            assertFalse(RepositoryPreferences.hasOverrides(null));
        });
    }

    /** The keys have to be legible, because the settings file is meant to be readable. */
    @Test
    void theKeyNamesTheRepositoryInPlainText() {
        String key = RepositoryPreferences.key(repository, "pull.mode");

        assertTrue(key.startsWith("repo["), key);
        assertTrue(key.endsWith("].pull.mode"), key);
        assertTrue(key.contains("repo" + File.separator) || key.contains(repository.getName()), key);
    }
}
