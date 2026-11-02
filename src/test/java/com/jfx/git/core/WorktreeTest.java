package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Listing is native and always tested; creating and removing go through the git
 * command line, so those cases are skipped rather than failed on a machine without
 * it - the feature genuinely is unavailable there, and the code says so.
 */
class WorktreeTest {

    @TempDir
    Path root;

    private Path repoPath;
    private GitService service;

    @BeforeEach
    void setUp() throws Exception {
        repoPath = root.resolve("repo");
        Files.createDirectories(repoPath);

        try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();

            Files.writeString(repoPath.resolve("a.txt"), "one", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("initial").call();
            git.branchCreate().setName("feature").call();
        }

        service = new GitService();
        service.openRepository(repoPath.toFile());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Listing - no git CLI needed
    // ------------------------------------------------------------------

    @Test
    void aPlainRepositoryHasExactlyOneWorktree() throws Exception {
        List<Worktrees.Worktree> trees = service.getWorktrees();
        assertEquals(1, trees.size());

        Worktrees.Worktree main = trees.get(0);
        assertTrue(main.main());
        assertFalse(main.detached());
        assertFalse(main.missing());
        assertEquals(repoPath.toFile().getCanonicalFile(), canonical(main.path()));
        assertFalse(service.getWorktrees().size() > 1);
    }

    @Test
    void theMainWorktreeReportsItsBranch() {
        assertEquals("master", service.getWorktrees().get(0).branch());
        assertEquals("master", service.getWorktrees().get(0).describe());
    }

    @Test
    void aDetachedHeadIsReportedAsDetached() throws Exception {
        String head;
        try (Git git = Git.open(repoPath.toFile())) {
            head = git.getRepository().resolve("HEAD").getName();
            git.checkout().setName(head).call();
        }

        Worktrees.Worktree main = service.getWorktrees().get(0);
        assertTrue(main.detached());
        assertEquals(head.substring(0, 7), main.branch());
        assertTrue(main.describe().startsWith("detached at "));
    }

    @Test
    void aClosedServiceListsNoWorktreesRatherThanThrowing() {
        try (GitService closed = new GitService()) {
            assertTrue(closed.getWorktrees().isEmpty());
            assertFalse(closed.getWorktrees().size() > 1);
            assertFalse(closed.pruneWorktrees().isOk());
            assertFalse(closed.addWorktree(new File("x"), "main", false).isOk());
            assertFalse(closed.removeWorktree(new File("x"), false).isOk());
            assertFalse(closed.setWorktreeLocked(new File("x"), true).isOk());
        }
    }

    @Test
    void aWorktreeWhoseDirectoryIsGoneIsReportedMissing() throws Exception {
        // Hand-write the administrative layout git would create, then point it at a
        // directory that does not exist.
        File admin = new File(service.getDirectory(), "worktrees/ghost");
        assertTrue(admin.mkdirs());
        File absent = root.resolve("gone").toFile();
        Files.writeString(admin.toPath().resolve("gitdir"),
                new File(absent, ".git").getPath(), StandardCharsets.UTF_8);
        Files.writeString(admin.toPath().resolve("HEAD"),
                "ref: refs/heads/feature", StandardCharsets.UTF_8);

        Worktrees.Worktree ghost = named(service.getWorktrees(), "ghost");
        assertNotNull(ghost);
        assertEquals("feature", ghost.branch());
        assertTrue(ghost.missing());
        assertEquals("missing - prune it", ghost.describe());
    }

    /**
     * O13. git 2.48+ with {@code worktree.useRelativePaths} writes {@code gitdir}
     * relative to the administrative directory. Read as-is it resolved against the
     * application's own working directory, and a healthy worktree read "missing - prune it".
     */
    @Test
    void aRelativeGitdirPointerIsResolvedFromItsAdministrativeDirectory() throws Exception {
        File admin = new File(service.getDirectory(), "worktrees/relative");
        assertTrue(admin.mkdirs());
        File tree = root.resolve("relative-tree").toFile();
        assertTrue(tree.mkdirs());
        // Four levels up from .git/worktrees/relative is the test root, beside the repository.
        Files.writeString(admin.toPath().resolve("gitdir"),
                "../../../../relative-tree/.git", StandardCharsets.UTF_8);
        Files.writeString(admin.toPath().resolve("HEAD"), "ref: refs/heads/feature", StandardCharsets.UTF_8);

        Worktrees.Worktree relative = named(service.getWorktrees(), "relative");

        assertNotNull(relative);
        assertFalse(relative.missing(), relative::describe);
        assertEquals(tree.getCanonicalFile(), canonical(relative.path()));
    }

    /**
     * O13. Opened from a linked worktree, the linked tree was listed as "(main)" with its
     * own branch, and the real main tree was not listed at all.
     */
    @Test
    void openedFromALinkedWorktreeTheMainEntryIsStillTheMainTree() throws Exception {
        assumeTrue(Worktrees.cliAvailable(), "git command line not on PATH");
        File target = root.resolve("linked").toFile();
        assertTrue(service.addWorktree(target, "feature", false).isOk());

        GitService fromLinked = new GitService();
        try {
            assertTrue(fromLinked.openRepository(target));
            List<Worktrees.Worktree> trees = fromLinked.getWorktrees();

            Worktrees.Worktree main = trees.get(0);
            assertTrue(main.main());
            assertEquals(repoPath.toFile().getCanonicalFile(), canonical(main.path()));
            assertEquals(service.getCurrentBranch(), main.branch(), "the main tree's own branch");
            Worktrees.Worktree linked = named(trees, "linked");
            assertNotNull(linked, () -> "the linked tree is listed as itself: " + trees);
            assertEquals("feature", linked.branch());
        } finally {
            fromLinked.close();
        }
    }

    @Test
    void aLockedWorktreeSaysSo() throws Exception {
        File admin = new File(service.getDirectory(), "worktrees/pinned");
        assertTrue(admin.mkdirs());
        File tree = root.resolve("pinned").toFile();
        assertTrue(tree.mkdirs());
        Files.writeString(admin.toPath().resolve("gitdir"),
                new File(tree, ".git").getPath(), StandardCharsets.UTF_8);
        Files.writeString(admin.toPath().resolve("HEAD"),
                "ref: refs/heads/feature", StandardCharsets.UTF_8);
        Files.writeString(admin.toPath().resolve("locked"), "", StandardCharsets.UTF_8);

        Worktrees.Worktree pinned = named(service.getWorktrees(), "pinned");
        assertNotNull(pinned);
        assertTrue(pinned.locked());
        assertEquals("feature (locked)", pinned.describe());
    }

    @Test
    void anIncompleteAdministrativeEntryIsSkippedNotFatal() throws Exception {
        // A directory with no gitdir file is what a half-finished prune leaves behind.
        assertTrue(new File(service.getDirectory(), "worktrees/broken").mkdirs());

        assertEquals(1, service.getWorktrees().size());
    }

    @Test
    void theMainWorktreeSortsFirst() throws Exception {
        File admin = new File(service.getDirectory(), "worktrees/aaa");
        assertTrue(admin.mkdirs());
        File tree = root.resolve("aaa").toFile();
        assertTrue(tree.mkdirs());
        Files.writeString(admin.toPath().resolve("gitdir"),
                new File(tree, ".git").getPath(), StandardCharsets.UTF_8);
        Files.writeString(admin.toPath().resolve("HEAD"),
                "ref: refs/heads/feature", StandardCharsets.UTF_8);

        List<Worktrees.Worktree> trees = service.getWorktrees();
        assertEquals(2, trees.size());
        // "(main)" would sort after "aaa" alphabetically; it must not.
        assertTrue(trees.get(0).main());
        assertTrue(service.getWorktrees().size() > 1);
    }

    // ------------------------------------------------------------------
    // Mutations - these need the git CLI
    // ------------------------------------------------------------------

    @Test
    void addingAWorktreeChecksOutABranchInItsOwnDirectory() {
        assumeTrue(Worktrees.cliAvailable(), "git command line not on PATH");

        File target = root.resolve("repo-feature").toFile();
        GitResult added = service.addWorktree(target, "feature", false);
        assertTrue(added.isOk(), added.getMessage());

        assertTrue(new File(target, "a.txt").isFile());
        Worktrees.Worktree tree = named(service.getWorktrees(), target.getName());
        assertNotNull(tree);
        assertEquals("feature", tree.branch());
        assertFalse(tree.missing());
        assertFalse(tree.main());
    }

    @Test
    void aWorktreeCanCreateItsBranchAsItGoes() {
        assumeTrue(Worktrees.cliAvailable(), "git command line not on PATH");

        File target = root.resolve("repo-fresh").toFile();
        GitResult added = service.addWorktree(target, "brand-new", true);
        assertTrue(added.isOk(), added.getMessage());

        assertTrue(service.getLocalBranches().stream()
                .map(ref -> ref.shortName())
                .anyMatch("brand-new"::equals));
    }

    @Test
    void removingAWorktreeTakesItsDirectoryWithIt() {
        assumeTrue(Worktrees.cliAvailable(), "git command line not on PATH");

        File target = root.resolve("repo-temp").toFile();
        assertTrue(service.addWorktree(target, "feature", false).isOk());

        GitResult removed = service.removeWorktree(target, false);
        assertTrue(removed.isOk(), removed.getMessage());
        assertFalse(target.exists());
        assertEquals(1, service.getWorktrees().size());
    }

    @Test
    void lockingAndUnlockingRoundTrips() {
        assumeTrue(Worktrees.cliAvailable(), "git command line not on PATH");

        File target = root.resolve("repo-locked").toFile();
        assertTrue(service.addWorktree(target, "feature", false).isOk());

        assertTrue(service.setWorktreeLocked(target, true).isOk());
        assertTrue(named(service.getWorktrees(), target.getName()).locked());

        assertTrue(service.setWorktreeLocked(target, false).isOk());
        assertFalse(named(service.getWorktrees(), target.getName()).locked());
    }

    @Test
    void pruningDropsAnEntryWhoseDirectoryWasDeleted() throws Exception {
        assumeTrue(Worktrees.cliAvailable(), "git command line not on PATH");

        File target = root.resolve("repo-doomed").toFile();
        assertTrue(service.addWorktree(target, "feature", false).isOk());
        deleteRecursively(target);

        assertTrue(named(service.getWorktrees(), target.getName()).missing());
        assertTrue(service.pruneWorktrees().isOk());
        assertEquals(1, service.getWorktrees().size());
    }

    @Test
    void anExistingNonEmptyDirectoryIsRefusedBeforeGitIsRun() throws Exception {
        File occupied = root.resolve("occupied").toFile();
        assertTrue(occupied.mkdirs());
        Files.writeString(occupied.toPath().resolve("something"), "x", StandardCharsets.UTF_8);

        GitResult result = service.addWorktree(occupied, "feature", false);
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("not empty"), result.getMessage());
    }

    @Test
    void creatingABranchWithoutNamingItIsRefused() {
        GitResult result = service.addWorktree(root.resolve("x").toFile(), "", true);
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("branch name"), result.getMessage());
    }

    // ------------------------------------------------------------------

    @Test
    void gitOutputIsReducedToItsLastMeaningfulLine() {
        assertEquals("Preparing worktree",
                Worktrees.lastLine("Preparing worktree\n\n", "fallback"));
        assertEquals("fallback", Worktrees.lastLine("", "fallback"));
        assertEquals("fallback", Worktrees.lastLine("   \n \n", "fallback"));
        assertEquals("fallback", Worktrees.lastLine(null, "fallback"));
    }

    private static Worktrees.Worktree named(List<Worktrees.Worktree> trees, String name) {
        return trees.stream().filter(w -> w.name().equals(name)).findFirst().orElse(null);
    }

    private static File canonical(File file) throws Exception {
        return file.getCanonicalFile();
    }

    private static void deleteRecursively(File file) throws Exception {
        try (var paths = Files.walk(file.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}
