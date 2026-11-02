package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which engine the factory reaches for: Hybrid wherever git is installed, and git for
 * making a repository unless the user chose pure JGit.
 */
class GitBackendFactoryTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateSettings() {
        Settings.useFile(tempDir.resolve("settings.properties"));
    }

    @AfterEach
    void restoreSettings() {
        Settings.useFile(null);
    }

    /** Measured faster than libgit2 for status on a large tree; see autoBackend. */
    @Test
    void autoIsHybridWhereGitIsInstalled() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs git");
        GitBackendFactory.setEnginePreference(GitBackendFactory.BackendEngine.AUTO);

        assertInstanceOf(HybridGitBackend.class, GitBackendFactory.createDefault());
    }

    /**
     * A clone or an init goes to git on every engine that hands its writes to git, so
     * the tab that opens on the result is not looking at a checkout JGit made.
     */
    @Test
    void repositoriesAreMadeByGitUnlessJGitWasChosen() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs git");
        for (var engine : GitBackendFactory.BackendEngine.values()) {
            GitBackendFactory.setEnginePreference(engine);
            assertEquals(engine != GitBackendFactory.BackendEngine.JGIT,
                    GitBackendFactory.makesRepositoriesWithGit(), engine.name());
        }
    }

    /** Whichever engine makes it, the result is a repository with the chosen starting branch. */
    @Test
    void initMakesARepositoryOnEitherEngine() throws Exception {
        for (var engine : new GitBackendFactory.BackendEngine[]{
                GitBackendFactory.BackendEngine.JGIT, GitBackendFactory.BackendEngine.AUTO}) {
            GitBackendFactory.setEnginePreference(engine);
            File dir = tempDir.resolve("made-by-" + engine.name()).toFile();
            assertTrue(dir.mkdirs());

            GitResult result = GitBackendFactory.initRepository(dir, RepoInitTemplates.NOTHING);

            assertTrue(result.isOk(), engine + ": " + result.getMessage());
            assertTrue(Files.isDirectory(dir.toPath().resolve(".git")), engine.name());
        }
    }
}
