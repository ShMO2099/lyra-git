package com.jfx.git.core;

import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.StagingState;
import com.jfx.git.model.SubmoduleState;
import com.jfx.git.testsupport.Engines;
import com.jfx.git.testsupport.LocalGitDaemon;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Repository-level checks for the completed submodule QA flows. */
class ManualCompletedIntegrationSubmoduleTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private final List<LocalGitDaemon> daemons = new ArrayList<>();
    private int fixtureNumber;

    static {
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @AfterEach
    void closeBackends() {
        for (GitBackend backend : opened) backend.close();
        try {
            LocalGitDaemon.closeAll(daemons);
        } finally {
            org.eclipse.jgit.lib.RepositoryCache.clear();
        }
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void addingASubmodulePreservesExistingStagedAndUnstagedEdits(
            String name, Supplier<GitBackend> factory) throws Exception {
        Path superPath = root.resolve("super-" + fixtureNumber++);
        Path libraryWork = root.resolve("library-work-" + fixtureNumber++);
        Path exports = root.resolve("git-exports-" + fixtureNumber++);
        Path libraryPath = exports.resolve("library.git");
        initRepository(superPath, "app.txt", "base app\n");
        createLibraryRepository(libraryWork, libraryPath, "library v1\n");
        LocalGitDaemon daemon = startGitDaemon(exports);
        Files.writeString(superPath.resolve("staged.txt"), "staged before add\n");

        GitBackend backend = open(factory, superPath);
        String originalHead = backend.getHeadHash();
        assertTrue(backend.stageFile("staged.txt").isOk(), name);
        Files.writeString(superPath.resolve("app.txt"), "pre-existing unstaged edit\n");

        GitResult added = backend.addSubmodule(daemon.url("library.git"), "vendor/library");

        assertTrue(added.isOk(), name + ": " + added.getMessage());
        assertEquals(originalHead, backend.getHeadHash(), name + ": adding does not commit");
        assertEquals("pre-existing unstaged edit\n", Files.readString(superPath.resolve("app.txt")), name);
        assertTrue(backend.getStagedFiles().containsAll(
                Set.of("staged.txt", ".gitmodules", "vendor/library")),
                name + ": keep earlier staged work and stage the registration/gitlink: "
                        + backend.getStagedFiles());
        assertTrue(backend.getStagingState().unstaged().contains("app.txt"),
                name + ": existing working edit remains unstaged: " + backend.getStagingState().unstaged());
        assertEquals("library v1\n", Files.readString(superPath.resolve("vendor/library/library.txt")), name);
        assertTrue(backend.getSubmodules().stream()
                .anyMatch(submodule -> submodule.path().equals("vendor/library")
                        && submodule.state() == SubmoduleState.CURRENT), name);
    }

    @Test
    void hybridDeinitAndReinitKeepTheSuperprojectClean() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(NativeGitRunner.isGitInstalled(),
                "Hybrid submodule lifecycle requires native Git");
        Path superPath = root.resolve("hybrid-super");
        Path libraryWork = root.resolve("hybrid-library-work");
        Path exports = root.resolve("hybrid-git-exports");
        Path libraryPath = exports.resolve("library.git");
        initRepository(superPath, "app.txt", "main\n");
        createLibraryRepository(libraryWork, libraryPath, "library\n");
        LocalGitDaemon daemon = startGitDaemon(exports);
        GitBackend backend = open(HybridGitBackend::new, superPath);

        assertTrue(backend.addSubmodule(daemon.url("library.git"), "vendor/library").isOk());
        assertTrue(backend.stageAll().isOk());
        assertTrue(backend.commit("add library", false).isOk());
        String headWithSubmodule = backend.getHeadHash();

        assertTrue(backend.deinitSubmodule("vendor/library", false).isOk());
        assertFalse(backend.hasUncommittedChanges());
        assertCleanStagingAndGraph(backend);
        assertEquals(headWithSubmodule, backend.getHeadHash());
        assertNull(backend.submoduleWorkTree("vendor/library"));

        assertTrue(backend.updateSubmodules(List.of("vendor/library")).isOk());
        assertEquals(SubmoduleState.CURRENT, backend.getSubmodules().get(0).state());
        assertCleanStagingAndGraph(backend);
        assertEquals(headWithSubmodule, backend.getHeadHash());
    }

    private void assertCleanStagingAndGraph(GitBackend backend) {
        StagingState state = backend.getStagingState();
        assertTrue(state.unstaged().isEmpty(), state::toString);
        assertTrue(state.staged().isEmpty(), state::toString);
        assertTrue(state.conflicting().isEmpty(), state::toString);
        assertFalse(state.hasUncommittedChanges(), state::toString);
        assertTrue(backend.getCommitGraphPage(30, GraphOrder.current()).rows().stream().noneMatch(GitGraphRow::isWipNode),
                "a clean superproject has no uncommitted graph row");
    }

    private GitBackend open(Supplier<GitBackend> factory, Path directory) throws Exception {
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(directory.toFile()));
        opened.add(backend);
        return backend;
    }

    private static void initRepository(Path directory, String file, String contents) throws Exception {
        Files.createDirectories(directory);
        try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Manual QA Test");
            config.setString("user", null, "email", "manual-qa@example.test");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(directory.resolve(file), contents);
            git.add().addFilepattern(file).call();
            git.commit().setMessage("initial").call();
        }
    }

    /** Serve a real local remote over loopback so CLI engines never depend on file transport policy. */
    private void createLibraryRepository(Path workTree, Path bareRepository, String contents) throws Exception {
        initRepository(workTree, "library.txt", contents);
        Files.writeString(workTree.resolve(".gitattributes"), "* text eol=lf\n");
        try (Git work = Git.open(workTree.toFile())) {
            work.add().addFilepattern(".gitattributes").call();
            work.commit().setMessage("enforce LF checkout").call();
        }
        Files.createDirectories(bareRepository.getParent());
        try (Git bare = Git.cloneRepository().setURI(workTree.toString())
                .setDirectory(bareRepository.toFile()).setBare(true).call()) {
            assertEquals("main", bare.getRepository().getBranch());
        }
    }

    private LocalGitDaemon startGitDaemon(Path exportRoot) throws Exception {
        LocalGitDaemon daemon = LocalGitDaemon.start(exportRoot);
        daemons.add(daemon);
        return daemon;
    }
}
