package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.Submodule;
import com.jfx.git.model.SubmoduleState;

/**
 * Submodules are exercised against two real repositories - a superproject and a
 * library cloned into it - because every interesting case (uninitialised, out of
 * date, deinitialised) is a state of the working tree, not of a parsed string.
 */
@Tag("heavy")
class SubmoduleTest {

    @TempDir
    Path root;

    private Path superPath;
    private Path libraryPath;
    private GitService service;

    @BeforeEach
    void setUp() throws Exception {
        superPath = root.resolve("super");
        libraryPath = root.resolve("library");
        Files.createDirectories(superPath);
        Files.createDirectories(libraryPath);

        initRepo(libraryPath, "lib.txt", "one");
        initRepo(superPath, "app.txt", "main");

        service = new GitService();
        service.openRepository(superPath.toFile());
    }

    /**
     * Windows will not delete a memory-mapped pack file, and this test creates a
     * nested repository under {@code .git/modules} that {@code @TempDir} has to remove
     * afterwards. Reinstalling the cache also drops any window already open.
     */
    private static void releasePackFiles() {
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    static {
        releasePackFiles();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        // JGit hands out cached Repository instances; without evicting them the
        // submodule's packs stay open past the end of the test.
        org.eclipse.jgit.lib.RepositoryCache.clear();
        releasePackFiles();
    }

    private static void initRepo(Path dir, String file, String content) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            // Cloning a local path as a submodule is refused by default since the
            // CVE-2022-39253 fix; these are directories this test just created.
            config.setBoolean("protocol", "file", "allow", true);
            config.save();

            Files.writeString(dir.resolve(file), content, StandardCharsets.UTF_8);
            git.add().addFilepattern(file).call();
            git.commit().setMessage("initial").call();
        }
    }

    /** file:// URI for the library, which is what a submodule URL has to be here. */
    private String libraryUrl() {
        return libraryPath.toUri().toString();
    }

    @Test
    void aRepositoryWithoutSubmodulesReportsNone() {
        assertTrue(service.getSubmodules().isEmpty());
    }

    @Test
    void addingASubmoduleClonesItAndRecordsIt() {
        GitResult result = service.addSubmodule(libraryUrl(), "vendor/library");
        assertTrue(result.isOk(), result.getMessage());

        List<Submodule> submodules = service.getSubmodules();
        assertEquals(1, submodules.size());

        Submodule sub = submodules.get(0);
        assertEquals("vendor/library", sub.path());
        assertEquals(SubmoduleState.CURRENT, sub.state());
        assertFalse(sub.state().needsUpdate());
        // Freshly added, the two sides agree by definition.
        assertEquals(sub.shortRecorded(), sub.shortCheckedOut());
        assertNotNull(sub.url());
        assertTrue(Files.isRegularFile(superPath.resolve(".gitmodules")));
    }

    @Test
    void theSubmoduleCanBeOpenedAsItsOwnRepository() {
        assertTrue(service.addSubmodule(libraryUrl(), "vendor/library").isOk());

        File workTree = service.submoduleWorkTree("vendor/library");
        assertNotNull(workTree);

        GitService inner = new GitService();
        try {
            assertTrue(inner.openRepository(workTree));
            assertFalse(inner.getCommitGraphPage(10, GraphOrder.current()).rows().isEmpty());
        } finally {
            inner.close();
        }
    }

    @Test
    void anUninitialisedSubmoduleIsReportedAsSuchAndHasNoWorkTree() {
        assertTrue(service.addSubmodule(libraryUrl(), "vendor/library").isOk());
        assertTrue(service.stageAll().isOk());
        assertTrue(service.commit("add submodule", false).isOk());

        // Deinit is exactly the "cloned, then thrown away" state a fresh clone of the
        // superproject would land in.
        GitResult deinit = service.deinitSubmodule("vendor/library", false);
        assertTrue(deinit.isOk(), deinit.getMessage());

        Submodule sub = service.getSubmodules().get(0);
        assertEquals(SubmoduleState.UNINITIALIZED, sub.state());
        assertTrue(sub.state().needsUpdate());
        assertEquals("not initialised", sub.state().label());
        assertNull(service.submoduleWorkTree("vendor/library"));
    }

    @Test
    void updatingBringsADeinitialisedSubmoduleBack() {
        assertTrue(service.addSubmodule(libraryUrl(), "vendor/library").isOk());
        assertTrue(service.stageAll().isOk());
        assertTrue(service.commit("add submodule", false).isOk());
        assertTrue(service.deinitSubmodule("vendor/library", false).isOk());

        GitResult update = service.updateSubmodules(List.of("vendor/library"));
        assertTrue(update.isOk(), update.getMessage());

        assertEquals(SubmoduleState.CURRENT,
                service.getSubmodules().get(0).state());
        assertNotNull(service.submoduleWorkTree("vendor/library"));
    }

    @Test
    void aSubmoduleParkedOnAnotherCommitIsReportedOutOfDate() throws Exception {
        // A second commit in the library, so there is somewhere else to move to.
        String olderCommit;
        try (Git lib = Git.open(libraryPath.toFile())) {
            olderCommit = lib.getRepository().resolve("HEAD").getName();
            Files.writeString(libraryPath.resolve("lib.txt"), "two", StandardCharsets.UTF_8);
            lib.add().addFilepattern("lib.txt").call();
            lib.commit().setMessage("second").call();
        }

        assertTrue(service.addSubmodule(libraryUrl(), "vendor/library").isOk());
        assertEquals(SubmoduleState.CURRENT,
                service.getSubmodules().get(0).state());

        // Move the submodule's own HEAD backwards; the superproject still records the tip.
        try (Git sub = Git.open(service.submoduleWorkTree("vendor/library"))) {
            sub.checkout().setName(olderCommit).call();
        }

        Submodule sub = service.getSubmodules().get(0);
        assertEquals(SubmoduleState.OUT_OF_DATE, sub.state());
        assertTrue(sub.state().needsUpdate());
        // The two hashes are what tells the user which way it drifted.
        assertEquals(olderCommit.substring(0, 7), sub.shortCheckedOut());
        assertFalse(sub.shortRecorded().equals(sub.shortCheckedOut()));
    }

    @Test
    void deinitialisingTwiceIsNotAnError() {
        assertTrue(service.addSubmodule(libraryUrl(), "vendor/library").isOk());
        assertTrue(service.stageAll().isOk());
        assertTrue(service.commit("add submodule", false).isOk());

        assertTrue(service.deinitSubmodule("vendor/library", false).isOk());
        GitResult second = service.deinitSubmodule("vendor/library", false);
        assertTrue(second.isOk(), second.getMessage());
    }

    @Test
    void syncingReportsWhatItTouched() {
        assertTrue(service.syncSubmodules().isOk());

        assertTrue(service.addSubmodule(libraryUrl(), "vendor/library").isOk());
        GitResult synced = service.syncSubmodules();
        assertTrue(synced.isOk(), synced.getMessage());
        assertTrue(synced.getMessage().contains("1"), synced.getMessage());
    }

    @Test
    void aBlankUrlOrPathIsRejectedBeforeAnythingIsWritten() {
        assertFalse(service.addSubmodule("", "vendor/library").isOk());
        assertFalse(service.addSubmodule(libraryUrl(), "  ").isOk());
        assertTrue(service.getSubmodules().isEmpty());
        assertFalse(Files.exists(superPath.resolve(".gitmodules")));
    }

    // ------------------------------------------------------------------
    // Native engine parity
    // ------------------------------------------------------------------

    /**
     * B9. The plain {@code git submodule status} line prints the <em>checked-out</em>
     * commit - that is what its {@code +} marker means - so reading it as the recorded
     * hash showed the panel two identical columns while calling the submodule "out of
     * date". The recorded commit has to come from {@code --cached}.
     *
     * <p>The JGit twin of this is
     * {@link #aSubmoduleParkedOnAnotherCommitIsReportedOutOfDate}.
     */
    @Test
    void theNativeEngineReportsRecordedAndCheckedOutSeparately() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");

        String olderCommit;
        try (Git lib = Git.open(libraryPath.toFile())) {
            olderCommit = lib.getRepository().resolve("HEAD").getName();
            Files.writeString(libraryPath.resolve("lib.txt"), "two", StandardCharsets.UTF_8);
            lib.add().addFilepattern("lib.txt").call();
            lib.commit().setMessage("second").call();
        }
        assertTrue(service.addSubmodule(libraryUrl(), "vendor/library").isOk());
        try (Git sub = Git.open(service.submoduleWorkTree("vendor/library"))) {
            sub.checkout().setName(olderCommit).call();
        }

        NativeGitBackend native_ = new NativeGitBackend();
        try {
            assertTrue(native_.openRepository(superPath.toFile()));
            Submodule reported = native_.getSubmodules().stream()
                    .filter(sm -> sm.path().equals("vendor/library"))
                    .findFirst().orElse(null);

            assertNotNull(reported, "the submodule should be listed");
            assertEquals(SubmoduleState.OUT_OF_DATE, reported.state());
            assertEquals(olderCommit.substring(0, 7), reported.shortCheckedOut(),
                    "the checked-out column is where the submodule actually is");
            assertNotEquals(reported.shortRecorded(), reported.shortCheckedOut(),
                    "an out-of-date submodule must not show two identical columns");
        } finally {
            native_.close();
        }
    }

    /**
     * B4. The interface contract is "these submodules, or all of them when empty". Without
     * the path arguments, "Init / Update" on one submodule in the sidebar fetched and
     * moved every submodule in the repository.
     */
    @Test
    void theNativeEngineUpdatesOnlyTheSubmodulesAskedFor() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");

        Path secondLibrary = root.resolve("library2");
        Files.createDirectories(secondLibrary);
        initRepo(secondLibrary, "other.txt", "other");

        assertTrue(service.addSubmodule(libraryUrl(), "vendor/library").isOk());
        assertTrue(service.addSubmodule(secondLibrary.toUri().toString(), "vendor/other").isOk());
        assertTrue(service.stageAll().isOk());
        assertTrue(service.commit("add submodules", false).isOk());
        // Force: the second deinit runs against a tree the first one has already
        // changed, which git treats as local modification worth asking about.
        assertTrue(service.deinitSubmodule("vendor/library", true).isOk());
        assertTrue(service.deinitSubmodule("vendor/other", true).isOk());

        NativeGitBackend native_ = new NativeGitBackend();
        try {
            assertTrue(native_.openRepository(superPath.toFile()));

            GitResult update = native_.updateSubmodules(List.of("vendor/library"));

            assertTrue(update.isOk(), update.getMessage());
            assertEquals(SubmoduleState.CURRENT, stateOf(native_, "vendor/library"),
                    "the submodule that was asked for should be initialised");
            assertEquals(SubmoduleState.UNINITIALIZED, stateOf(native_, "vendor/other"),
                    "the one that was not asked for must be left alone");
        } finally {
            native_.close();
        }
    }

    private static SubmoduleState stateOf(GitBackend backend, String path) {
        return backend.getSubmodules().stream()
                .filter(sm -> sm.path().equals(path))
                .map(Submodule::state)
                .findFirst().orElseThrow(() -> new AssertionError(path + " is not listed"));
    }

    @Test
    void submoduleCallsOnAClosedServiceFailRatherThanThrow() {
        try (GitService closed = new GitService()) {
            assertTrue(closed.getSubmodules().isEmpty());
            assertFalse(closed.updateSubmodules(List.of()).isOk());
            assertFalse(closed.syncSubmodules().isOk());
            assertFalse(closed.deinitSubmodule("x", false).isOk());
            assertNull(closed.submoduleWorkTree("x"));
        }
    }

    @Test
    void everyStateHasAReadableLabel() {
        for (SubmoduleState state : SubmoduleState.values()) {
            assertFalse(state.label().isBlank());
        }
        assertFalse(SubmoduleState.CURRENT.needsUpdate());
        assertTrue(SubmoduleState.MISSING.needsUpdate());
        assertTrue(SubmoduleState.OUT_OF_DATE.needsUpdate());
    }
}
