package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Git's {@code safe.directory} rule on every engine (E4).
 *
 * <p>A repository owned by another user cannot be made in a test, so the refusal itself
 * is simulated where it enters: a native engine that reports git's own message. What is
 * real is everything this application does with it - that a composite engine treats it
 * as final instead of falling back to JGit, which has no such check, and that "Trust this
 * folder" writes exactly what git's message recommends. That write goes to a scratch
 * global config through {@code GIT_CONFIG_GLOBAL}, never the developer's own.
 */
class SafeDirectoryTest {

    /** Git 2.55's refusal, verbatim apart from the path. */
    private static final String GIT_REFUSAL = """
            fatal: detected dubious ownership in repository at 'C:/shared/repo'
            'C:/shared/repo' is owned by:
            \tBUILTIN/Administrators (S-1-5-32-544)
            but the current user is:
            \tHOST/someone (S-1-5-21-1-2-3-1001)
            To add an exception for this directory, call:

            \tgit config --global --add safe.directory C:/shared/repo""";

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    @Test
    void recognisesGitsAndLibgit2sRefusals() {
        assertTrue(SafeDirectory.isDubiousOwnership(GIT_REFUSAL));
        assertTrue(SafeDirectory.isDubiousOwnership(
                "repository path 'C:/shared/repo/' is not owned by current user"));
        assertFalse(SafeDirectory.isDubiousOwnership("fatal: not a git repository"));
        assertFalse(SafeDirectory.isDubiousOwnership(null));
    }

    @Test
    void takesThePathToTrustFromGitsOwnMessage() {
        assertEquals("C:/shared/repo", SafeDirectory.suggestedPath(GIT_REFUSAL).orElseThrow());
        assertEquals("/srv/repo", SafeDirectory.suggestedPath(
                "call:\n\n\tgit config --global --add safe.directory '/srv/repo'").orElseThrow());
    }

    @Test
    void honoursSafeDirectoryTheWayGitDoes() {
        File dir = root.resolve("repo").toFile();
        assertTrue(dir.mkdirs());
        String path = SafeDirectory.gitPath(dir);

        assertTrue(SafeDirectory.isListed(List.of(path), dir));
        assertTrue(SafeDirectory.isListed(List.of(path + "/"), dir));
        assertTrue(SafeDirectory.isListed(List.of("*"), dir));
        assertTrue(SafeDirectory.isListed(List.of(SafeDirectory.gitPath(root.toFile()) + "/*"), dir));
        assertFalse(SafeDirectory.isListed(List.of(), dir));
        assertFalse(SafeDirectory.isListed(List.of(path + "-other"), dir));
        // An empty value resets the list: what came before it no longer counts.
        assertFalse(SafeDirectory.isListed(List.of("*", ""), dir));
    }

    /** The user's own repository must not be refused - the check runs on every JGit open. */
    @Test
    void aRepositoryTheUserOwnsIsNotRefused() throws Exception {
        File dir = root.resolve("mine").toFile();
        Git.init().setDirectory(dir).call().close();

        assertNull(SafeDirectory.ownershipProblem(dir, new File(dir, ".git")));
        GitService service = new GitService();
        try {
            assertTrue(service.openRepository(dir));
        } finally {
            service.close();
        }
    }

    @Test
    void trustingAFolderAddsItToTheGlobalSafeDirectoryListOnce() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs git");
        Path global = root.resolve("global.gitconfig");
        Files.writeString(global, "");
        Map<String, String> env = Map.of("GIT_CONFIG_GLOBAL", global.toString());

        assertTrue(SafeDirectory.trust("C:/shared/repo", env).isOk());
        assertTrue(SafeDirectory.trust("C:/shared/repo", env).isOk());

        String written = Files.readString(global);
        assertTrue(written.contains("directory = C:/shared/repo"), written);
        assertEquals(written.indexOf("C:/shared/repo"), written.lastIndexOf("C:/shared/repo"),
                "trusting twice must not add the entry twice: " + written);
    }

    /**
     * The core of E4. Hybrid used to log the native refusal at FINE and serve everything
     * from JGit, which opened the repository without any ownership check - reopening
     * exactly the hole git's refusal closes.
     */
    @Test
    void hybridTreatsNativeDubiousOwnershipAsFinalAndNeverOpensJGit() throws Exception {
        File dir = root.resolve("repo").toFile();
        Git.init().setDirectory(dir).call().close();
        NativeGitBackend refusing = new NativeGitBackend() {
            @Override
            public boolean openRepository(File d) {
                throw new DubiousOwnershipException(GIT_REFUSAL);
            }
        };
        GitService jgit = new GitService();
        try (HybridGitBackend hybrid = new HybridGitBackend(refusing, jgit)) {
            DubiousOwnershipException thrown =
                    assertThrows(DubiousOwnershipException.class, () -> hybrid.openRepository(dir));

            assertTrue(thrown.getMessage().contains("dubious ownership"), thrown.getMessage());
            assertFalse(jgit.isOpen(), "JGit must not open a repository git refused");
            assertFalse(hybrid.isOpen());
        }
    }

    /** The same refusal reported only through a message still counts. */
    @Test
    void hybridRecognisesTheRefusalByItsMessageToo() throws Exception {
        File dir = root.resolve("repo2").toFile();
        Git.init().setDirectory(dir).call().close();
        NativeGitBackend refusing = new NativeGitBackend() {
            @Override
            public boolean openRepository(File d) {
                throw new GitOperationException("Not a git repository: " + d + " (" + GIT_REFUSAL + ")");
            }
        };
        GitService jgit = new GitService();

        assertThrows(DubiousOwnershipException.class,
                () -> {
                    try (var hybrid = new HybridGitBackend(refusing, jgit)) {
                        hybrid.openRepository(dir);
                    }
                });
        assertFalse(jgit.isOpen());
    }

    @Test
    void anOrdinaryNativeFailureStillFallsBackToJGit() throws Exception {
        File dir = root.resolve("repo3").toFile();
        Git.init().setDirectory(dir).call().close();
        NativeGitBackend failing = new NativeGitBackend() {
            @Override
            public boolean openRepository(File d) {
                throw new GitOperationException("git exploded");
            }
        };
        GitService jgit = new GitService();
        HybridGitBackend hybrid = new HybridGitBackend(failing, jgit);
        try {
            assertTrue(hybrid.openRepository(dir));
            assertTrue(jgit.isOpen());
        } finally {
            hybrid.close();
        }
    }
}
