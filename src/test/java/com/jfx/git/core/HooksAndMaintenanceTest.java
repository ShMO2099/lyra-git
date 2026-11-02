package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.testsupport.Engines;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Listing the hooks a repository would run, and asking git to tidy up.
 *
 * <p>Run against every engine for the same reason {@link BackendConformanceTest} exists:
 * both of these have a JGit implementation and a native one that answer the same question
 * different ways - {@code rev-parse --git-path hooks} against {@code core.hooksPath}, and
 * git's own object count against a directory listing - and an engine that quietly
 * disagreed would tell one user their pre-commit hook exists and another that it does not.
 */
class HooksAndMaintenanceTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private int fixtureCount;

    static {
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @AfterEach
    void tearDown() {
        for (GitBackend backend : opened) {
            try {
                backend.close();
            } catch (Exception ignored) {
                // The test's own failure is the interesting one.
            }
        }
        opened.clear();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    private GitBackend fixture(Supplier<GitBackend> factory) throws Exception {
        File dir = root.resolve("repo" + (fixtureCount++)).toFile();
        assertTrue(dir.mkdirs());
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(dir.toPath().resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
        }
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static void writeHook(GitBackend backend, String name, boolean executable) throws Exception {
        Path hooks = backend.getDirectory().toPath().resolve("hooks");
        Files.createDirectories(hooks);
        Path script = hooks.resolve(name);
        Files.writeString(script, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        script.toFile().setExecutable(executable);
    }

    /**
     * Git creates a {@code .sample} per hook in every new repository and runs none of
     * them. Listing those would bury the one script that is actually running.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void sampleHooksAreNotReportedAsHooks(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        Path hooks = backend.getDirectory().toPath().resolve("hooks");
        Files.createDirectories(hooks);
        Files.writeString(hooks.resolve("pre-commit.sample"), "#!/bin/sh\nexit 0\n");

        assertTrue(backend.getHooks().isEmpty(),
                () -> "samples are not hooks: " + backend.getHooks());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aRealHookIsListedWithItsPath(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        writeHook(backend, "pre-commit", true);

        List<RepoStructureOps.Hook> found = backend.getHooks();

        assertEquals(1, found.size(), () -> "expected one hook, got " + found);
        assertEquals("pre-commit", found.get(0).name());
        assertTrue(found.get(0).path().endsWith("pre-commit"), found.get(0).path());
    }

    /**
     * A hook that is present but not executable is ignored by git without a word, which
     * is a genuinely hard thing to work out from inside git. It has to be listed and
     * marked, not hidden.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aNonExecutableHookIsListedAndMarked(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory);
        writeHook(backend, "commit-msg", false);

        List<RepoStructureOps.Hook> found = backend.getHooks();

        assertEquals(1, found.size(), () -> "expected one hook, got " + found);
        // Windows has no executable bit, and reports every readable file as executable;
        // asserting "false" there would be asserting a property of the filesystem.
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            assertFalse(found.get(0).executable(),
                    "git will not run this, and the user has to be able to see that");
        }
    }

    /** A fresh repository has nothing worth packing, and must not nag about it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSmallRepositoryDoesNotNeedHousekeeping(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        assertFalse(backend.needsMaintenance(),
                "a one-commit repository is not what gc.auto is for");
    }

    /** And running it anyway has to be safe and leave the repository working. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void housekeepingRunsAndLeavesTheRepositoryUsable(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory);

        GitResult result = backend.runMaintenance();

        assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());
        assertEquals("main", backend.getCurrentBranch());
        assertFalse(backend.getCommitGraphPage(10, GraphOrder.current()).rows().isEmpty(),
                "history should still be readable");
    }
}
