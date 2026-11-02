package com.jfx.git.core;

import com.jfx.git.testsupport.Engines;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The remote's default branch, from {@code origin/HEAD}, on every engine and in both of
 * git's ways of storing refs.
 *
 * <p>The answer was read from the loose file {@code refs/remotes/origin/HEAD} and, failing
 * that, from {@code origin/HEAD} among the remote branches. Since the engines leave the
 * symbolic {@code origin/HEAD} out of that list, the file was the only way - and a clone
 * that keeps its refs in a reftable has no such file, so a project whose default branch is
 * {@code develop} had pull requests and branch cleanup measured against {@code main}.
 */
@Tag("heavy")
class DefaultBranchTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();

    /** Windows will not delete a memory-mapped pack file, and {@code @TempDir} has to. */
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
                // The test's own failure is the one worth reporting.
            }
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theDefaultBranchIsReadFromALooseRefClone(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = open(factory, cloneOfDevelop("files"));

        assertEquals("origin/develop", DefaultBranch.remoteHead(backend), name);
        assertEquals("develop", DefaultBranch.name(backend), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void theDefaultBranchIsReadFromAReftableClone(String name, Supplier<GitBackend> factory) throws Exception {
        File clone = cloneOfDevelop("reftable");
        if (Engines.LIBGIT2.equals(name)) {
            // libgit2 does not read reftables at all: it refuses the repository outright,
            // rather than opening it and answering wrongly.
            assertThrows(GitOperationException.class, () -> factory.get().openRepository(clone));
            return;
        }
        GitBackend backend = open(factory, clone);

        assertEquals("origin/develop", DefaultBranch.remoteHead(backend), name);
        assertEquals("develop", DefaultBranch.name(backend), name);
    }

    /** What remoteHead now asks for, pinned per engine and per ref store. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aSymbolicRefIsAnsweredWithItsTargetAndAnyOtherRefWithNothing(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = open(factory, cloneOfDevelop("files"));

        assertEquals("refs/remotes/origin/develop", backend.symbolicRefTarget("refs/remotes/origin/HEAD"), name);
        assertNull(backend.symbolicRefTarget("refs/remotes/origin/main"), name + ": not symbolic");
        assertNull(backend.symbolicRefTarget("refs/remotes/origin/nothing"), name + ": no such ref");
        assertEquals("refs/heads/develop", backend.symbolicRefTarget("HEAD"), name);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A clone, keeping its refs as {@code refFormat} says, of an {@code origin} whose
     * default branch is {@code develop} and which also has a {@code main}: so falling back
     * to the usual names gives the wrong answer.
     */
    private File cloneOfDevelop(String refFormat) throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "the fixture is made with git");
        File origin = root.resolve("origin-" + refFormat + ".git").toFile();
        File seed = root.resolve("seed-" + refFormat).toFile();
        File clone = root.resolve("clone-" + refFormat).toFile();
        git(root.toFile(), "init", "--bare", "--initial-branch=develop", origin.getAbsolutePath());
        git(root.toFile(), "init", "--initial-branch=develop", seed.getAbsolutePath());
        Files.writeString(seed.toPath().resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
        git(seed, "add", "a.txt");
        git(seed, "commit", "-m", "first");
        git(seed, "branch", "main");
        git(seed, "push", origin.getAbsolutePath(), "develop", "main");
        git(root.toFile(), "clone", "--ref-format=" + refFormat, origin.getAbsolutePath(), clone.getAbsolutePath());
        return clone;
    }

    private static void git(File dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Test User",
                "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git " + String.join(" ", args) + " hung");
        assertEquals(0, process.exitValue(), () -> "git " + String.join(" ", args) + ": " + output);
    }

    private GitBackend open(Supplier<GitBackend> factory, File dir) {
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }
}
