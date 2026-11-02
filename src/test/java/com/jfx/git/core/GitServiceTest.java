package com.jfx.git.core;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.ResetMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jfx.git.model.BisectStatus;

/**
 * Exercises {@link GitService} against a throwaway repository.
 *
 * <p>Focused on the behaviours that were previously broken: staging and committing
 * (which did not exist), the staged/unstaged split, stash pop semantics, and
 * conflict detection reporting failure rather than success.
 */
@Tag("heavy")
class GitServiceTest {

    @TempDir
    Path tempDir;

    private GitService service;

    @BeforeEach
    void setUp() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            // Keep checkouts byte-for-byte on Windows, where a global core.autocrlf
            // would otherwise rewrite LF to CRLF and break content comparisons.
            config.setString("core", null, "autocrlf", "false");
            config.setString("core", null, "eol", "lf");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        // Nested submodule repositories and their pack windows are released by
        // com.jfx.git.testsupport.TestEnvironment after every test; see that class for why closing
        // this service is not enough on Windows.
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void write(String name, String content) throws Exception {
        Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private void commitFile(String name, String content, String message) throws Exception {
        write(name, content);
        assertTrue(service.stageFile(name).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    /**
     * Commits with an explicit timestamp.
     *
     * <p>Git records commit dates to the second, so commits made in a loop all land on the
     * same one and any ordering that sorts by date has nothing to sort by. Tests about
     * order have to say what the order is rather than hoping the clock ticks.
     */
    private void commitFileAt(String name, String content, String message, Instant when)
            throws Exception {
        write(name, content);
        try (Git git = Git.open(tempDir.toFile())) {
            git.add().addFilepattern(name).call();
            PersonIdent who = new PersonIdent(
                    "Test User", "test@example.com", when, java.time.ZoneOffset.UTC);
            git.commit().setMessage(message).setAuthor(who).setCommitter(who).call();
        }
    }

    /** Row subjects in the order the graph would draw them, ignoring the WIP row. */
    private List<String> subjectsInOrder(GraphOrder order) {
        return service.getCommitGraphPage(20, order).rows().stream()
                .filter(row -> !row.isWipNode() && row.getCommit() != null)
                .map(row -> row.getCommit().getSubject())
                .toList();
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @Test
    void opensRepositoryFromSubdirectory() throws Exception {
        commitFile("a.txt", "hello", "initial");
        File nested = tempDir.resolve("src/main/java").toFile();
        assertTrue(nested.mkdirs());

        GitService fromSub = new GitService();
        try {
            assertTrue(fromSub.openRepository(nested), "should walk up to find the .git directory");
            assertNotNull(fromSub.getWorkTree());
        } finally {
            fromSub.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        service.close();
        service.close(); // must not throw or over-release the repository
        assertFalse(service.isOpen());
    }

    // ------------------------------------------------------------------
    // Repository creation
    // ------------------------------------------------------------------

    @Test
    void initRepositoryUsesTheConfiguredDefaultBranchName() throws Exception {
        AppPreferences.setDefaultBranchName("trunk");
        try {
            File dir = Files.createTempDirectory("lyra-init-branch-name").toFile();
            try {
                GitResult result = GitService.initRepository(dir);
                assertTrue(result.isOk(), result.getMessage());

                try (Git created = Git.open(dir)) {
                    assertEquals("refs/heads/trunk",
                            created.getRepository().getFullBranch());
                }
            } finally {
                deleteRecursively(dir.toPath());
            }
        } finally {
            AppPreferences.setDefaultBranchName(null);
        }
    }

    @Test
    void initRepositoryWithNoTemplatesLeavesTheWorkingTreeEmpty() throws Exception {
        File dir = Files.createTempDirectory("lyra-init-no-templates").toFile();
        try {
            GitResult result = GitService.initRepository(dir);
            assertTrue(result.isOk(), result.getMessage());

            assertFalse(new File(dir, ".gitignore").exists());
            assertFalse(new File(dir, "LICENSE").exists());
            assertFalse(new File(dir, "README.md").exists());
        } finally {
            deleteRecursively(dir.toPath());
        }
    }

    @Test
    void initRepositoryWithTemplatesWritesThemIntoTheWorkingTreeUnstaged() throws Exception {
        File dir = Files.createTempDirectory("lyra-init-templates").toFile();
        try {
            var templates = new RepoInitTemplates("Java", "MIT License", "Ada Lovelace", true);
            GitResult result = GitService.initRepository(dir, templates);
            assertTrue(result.isOk(), result.getMessage());

            assertTrue(new File(dir, ".gitignore").exists());
            assertTrue(new File(dir, "LICENSE").exists());
            assertTrue(new File(dir, "README.md").exists());

            // Left unstaged, the same "visible, not silently committed" choice
            // restoreFileFromCommit makes for its own result - the user reviews and
            // commits these themselves.
            try (Git created = Git.open(dir)) {
                var untracked = created.status().call().getUntracked();
                assertTrue(untracked.containsAll(java.util.Set.of(".gitignore", "LICENSE", "README.md")),
                        "expected all three starter files to show up as untracked: " + untracked);
            }
        } finally {
            deleteRecursively(dir.toPath());
        }
    }

    // ------------------------------------------------------------------
    // cloneRepository
    // ------------------------------------------------------------------

    /** A source repository with three commits on its default branch and a second, divergent branch. */
    private File cloneSource() throws Exception {
        File source = Files.createTempDirectory("lyra-clone-source").toFile();
        try (Git git = Git.init().setDirectory(source).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();
            for (String name : List.of("one", "two", "three")) {
                Files.writeString(source.toPath().resolve(name + ".txt"), name, StandardCharsets.UTF_8);
                git.add().addFilepattern(name + ".txt").call();
                git.commit().setMessage("add " + name).call();
            }
            String defaultBranch = git.getRepository().getBranch();
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            Files.writeString(source.toPath().resolve("feature.txt"), "feature", StandardCharsets.UTF_8);
            git.add().addFilepattern("feature.txt").call();
            git.commit().setMessage("add feature").call();
            git.checkout().setName(defaultBranch).call();
        }
        return source;
    }

    private static int commitCount(File repoDir) throws Exception {
        try (Git git = Git.open(repoDir)) {
            int count = 0;
            for (var _ : git.log().call()) {
                count++;
            }
            return count;
        }
    }

    @Test
    void cloneRepositoryCarriesTheFullHistoryByDefault() throws Exception {
        File source = cloneSource();
        File target = Files.createTempDirectory("lyra-clone-target").toFile();
        try {
            GitResult result = GitService.cloneRepository(source.getAbsolutePath(), target);
            assertTrue(result.isOk(), result.getMessage());
            assertEquals(3, commitCount(target), "every commit on the default branch should be present");
        } finally {
            deleteRecursively(source.toPath());
            deleteRecursively(target.toPath());
        }
    }

    @Test
    void cloningASpecificBranchChecksItOutInstead() throws Exception {
        File source = cloneSource();
        File target = Files.createTempDirectory("lyra-clone-target").toFile();
        try {
            GitResult result = GitService.cloneRepository(source.getAbsolutePath(), target, "feature", 0);
            assertTrue(result.isOk(), result.getMessage());

            try (Git cloned = Git.open(target)) {
                assertEquals("refs/heads/feature", cloned.getRepository().getFullBranch());
            }
            assertTrue(new File(target, "feature.txt").exists());
        } finally {
            deleteRecursively(source.toPath());
            deleteRecursively(target.toPath());
        }
    }

    @Test
    void aDepthLimitsTheClonedHistory() throws Exception {
        File source = cloneSource();
        File target = Files.createTempDirectory("lyra-clone-target").toFile();
        try {
            GitResult result = GitService.cloneRepository(source.getAbsolutePath(), target, null, 1);
            assertTrue(result.isOk(), result.getMessage());
            assertEquals(1, commitCount(target),
                    "a depth-1 clone should carry only the tip commit, though the source has three");
        } finally {
            deleteRecursively(source.toPath());
            deleteRecursively(target.toPath());
        }
    }

    @Test
    void aNonExistentBranchFailsCleanly() throws Exception {
        File source = cloneSource();
        File target = Files.createTempDirectory("lyra-clone-target").toFile();
        try {
            GitResult result = GitService.cloneRepository(source.getAbsolutePath(), target, "no-such-branch", 0);
            assertFalse(result.isOk());
        } finally {
            deleteRecursively(source.toPath());
            deleteRecursively(target.toPath());
        }
    }

    // ------------------------------------------------------------------
    // Submodule auto-update (AppPreferences.autoUpdateSubmodules)
    // ------------------------------------------------------------------

    /**
     * A plain {@code git checkout} moves the superproject's own recorded gitlink but
     * never touches the submodule's own working tree - real git's well-known
     * "submodule looks unchanged after switching branches" behaviour, and exactly the
     * staleness {@code autoUpdateSubmodules} exists to erase automatically. Advances
     * the submodule forward on {@code feature}, then switches back to the branch that
     * still expects the original commit, leaving the two states genuinely different.
     *
     * @return the submodule's advanced ("stale-if-not-updated") commit hash
     */
    private String createDivergingSubmoduleScenario(String mainBranch) throws Exception {
        File submoduleSource = Files.createTempDirectory("lyra-automod-source").toFile();
        try (Git subGit = Git.init().setDirectory(submoduleSource).call()) {
            var config = subGit.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();
            Files.writeString(submoduleSource.toPath().resolve("a.txt"), "a", StandardCharsets.UTF_8);
            subGit.add().addFilepattern("a.txt").call();
            subGit.commit().setMessage("commit A").call();
        }
        try {
            assertTrue(service.addSubmodule(submoduleSource.getAbsolutePath(), "libs/sub").isOk());
            assertTrue(service.stageFile(".gitmodules").isOk());
            assertTrue(service.stageFile("libs/sub").isOk());
            assertTrue(service.commit("add submodule", false).isOk());

            assertTrue(service.createBranch("feature", null, true).isOk());
            File subDirOnDisk = tempDir.resolve("libs/sub").toFile();
            String advancedCommit;
            try (Git subGit = Git.open(subDirOnDisk)) {
                Files.writeString(subDirOnDisk.toPath().resolve("b.txt"), "b", StandardCharsets.UTF_8);
                subGit.add().addFilepattern("b.txt").call();
                advancedCommit = subGit.commit().setMessage("commit B").call().getName();
            }
            assertTrue(service.stageFile("libs/sub").isOk());
            assertTrue(service.commit("advance submodule", false).isOk());
            assertTrue(service.checkoutBranch(mainBranch).isOk());
            return advancedCommit;
        } finally {
            deleteRecursively(submoduleSource.toPath());
        }
    }

    @Test
    void checkoutLeavesTheSubmoduleStaleWhenAutoUpdateIsOff() throws Exception {
        commitFile("base.txt", "base", "base commit");
        String mainBranch = service.getCurrentBranch();
        String advancedCommit = createDivergingSubmoduleScenario(mainBranch);

        assertFalse(AppPreferences.autoUpdateSubmodules(), "off by default");
        try (Git subGit = Git.open(tempDir.resolve("libs/sub").toFile())) {
            assertEquals(advancedCommit, subGit.getRepository().resolve("HEAD").getName(),
                    "a plain checkout never touches the submodule's own working tree");
        }
    }

    @Test
    void checkoutAutomaticallyUpdatesTheSubmoduleWhenThePreferenceIsOn() throws Exception {
        commitFile("base.txt", "base", "base commit");
        String mainBranch = service.getCurrentBranch();

        AppPreferences.setAutoUpdateSubmodules(true);
        try {
            String advancedCommit = createDivergingSubmoduleScenario(mainBranch);

            try (Git subGit = Git.open(tempDir.resolve("libs/sub").toFile())) {
                assertNotEquals(advancedCommit, subGit.getRepository().resolve("HEAD").getName(),
                        "auto-update should have brought the submodule back to what this branch recorded");
            }
        } finally {
            AppPreferences.setAutoUpdateSubmodules(false);
        }
    }

    @Test
    void aRepositoryWithNoSubmodulesIsUnaffectedEitherWay() throws Exception {
        commitFile("base.txt", "base", "base commit");
        String mainBranch = service.getCurrentBranch();
        assertTrue(service.createBranch("feature", null, true).isOk());
        commitFile("feature.txt", "feature", "feature commit");

        AppPreferences.setAutoUpdateSubmodules(true);
        try {
            assertTrue(service.checkoutBranch(mainBranch).isOk());
        } finally {
            AppPreferences.setAutoUpdateSubmodules(false);
        }
    }

    // ------------------------------------------------------------------
    // Staging and committing
    // ------------------------------------------------------------------

    @Test
    void stagesAndCommitsAFile() throws Exception {
        write("hello.txt", "content");

        assertEquals(List.of("hello.txt"), service.getStagingState().unstaged());
        assertTrue(service.getStagedFiles().isEmpty());

        assertTrue(service.stageFile("hello.txt").isOk());
        assertEquals(List.of("hello.txt"), service.getStagedFiles());

        GitResult commit = service.commit("add hello", false);
        assertTrue(commit.isOk(), commit.getMessage());
        assertFalse(service.hasUncommittedChanges());
    }

    @Test
    void commitWithoutMessageIsRejected() throws Exception {
        write("a.txt", "x");
        service.stageFile("a.txt");

        assertFalse(service.commit("   ", false).isOk());
    }

    @Test
    void commitWithNothingStagedIsRejected() {
        assertFalse(service.commit("empty", false).isOk());
    }

    /**
     * JGit's {@code CommitCommand} does not read {@code commit.gpgsign} the way native
     * git's own porcelain does, so honouring it silently would produce an unsigned
     * commit under a setting (the Profiles settings' "Sign commits with GPG")
     * that claims otherwise. Refusing is the point, not a limitation to work around.
     */
    @Test
    void commitIsRefusedWhenGpgSigningIsOnBecauseJGitCannotSign() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            git.getRepository().getConfig().setBoolean("commit", null, "gpgsign", true);
            git.getRepository().getConfig().save();
        }
        write("a.txt", "x");
        service.stageFile("a.txt");

        GitResult result = service.commit("should not be signed", false);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("gpgsign"), result.getMessage());
        assertTrue(result.getMessage().toLowerCase(java.util.Locale.ROOT).contains("native"),
                () -> "the message should point at the engine that actually signs: " + result.getMessage());
    }

    @Test
    void commitSucceedsWhenGpgSigningIsExplicitlyOff() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            git.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
            git.getRepository().getConfig().save();
        }
        write("a.txt", "x");
        service.stageFile("a.txt");

        assertTrue(service.commit("unsigned is fine", false).isOk());
    }

    // ------------------------------------------------------------------
    // Skip hooks
    // ------------------------------------------------------------------

    /**
     * A real, executable {@code pre-commit} hook that always blocks - unlike GPG
     * signing, this is not an assumption to trust: JGit's {@code CommitCommand} runs a
     * repository's real hook scripts when they exist (verified against its own
     * source), so a script that actually exits non-zero is what proves both that
     * hooks run at all here and that {@code skipHooks} bypasses them for real.
     */
    private void installBlockingPreCommitHook() throws Exception {
        Path hooksDir = tempDir.resolve(".git").resolve("hooks");
        Files.createDirectories(hooksDir);
        Path hook = hooksDir.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);
    }

    @Test
    void aRealPreCommitHookBlocksAnOrdinaryCommit() throws Exception {
        installBlockingPreCommitHook();
        write("a.txt", "x");
        service.stageFile("a.txt");

        assertFalse(service.commit("should be blocked", false).isOk(),
                "a pre-commit hook that exits non-zero must block the commit");
    }

    @Test
    void skipHooksBypassesARealBlockingPreCommitHook() throws Exception {
        installBlockingPreCommitHook();
        write("a.txt", "x");
        service.stageFile("a.txt");

        GitResult result = service.commit("bypasses the hook", false, true);

        assertTrue(result.isOk(), result.getMessage());
    }

    @Test
    void skipHooksDefaultsToFalseOnTheTwoArgOverload() throws Exception {
        installBlockingPreCommitHook();
        write("a.txt", "x");
        service.stageFile("a.txt");

        assertFalse(service.commit("two-arg overload must still run hooks", false).isOk());
    }

    // ------------------------------------------------------------------
    // Signature status
    // ------------------------------------------------------------------

    @Test
    void anOrdinaryCommitHasNoSignature() throws Exception {
        commitFile("a.txt", "x", "plain commit");
        String head = resolveHead();

        assertEquals(com.jfx.git.model.SignatureStatus.NONE, service.getSignatureStatus(head));
    }

    @Test
    void signatureStatusOfANonexistentCommitIsNone() {
        assertEquals(com.jfx.git.model.SignatureStatus.NONE,
                service.getSignatureStatus("0000000000000000000000000000000000000000"));
    }

    // ------------------------------------------------------------------
    // Profiles: applying an identity to this repository's local config
    // ------------------------------------------------------------------

    @Test
    void setLocalIdentityWritesToTheRepositorysOwnConfig() throws Exception {
        assertTrue(service.setLocalIdentity("Alice", "alice@work.com", "", false).isOk());

        try (Git git = Git.open(tempDir.toFile())) {
            var config = git.getRepository().getConfig();
            assertEquals("Alice", config.getString("user", null, "name"));
            assertEquals("alice@work.com", config.getString("user", null, "email"));
        }
    }

    @Test
    void aCommitMadeAfterApplyingAnIdentityCarriesThatIdentity() throws Exception {
        assertTrue(service.setLocalIdentity("Alice", "alice@work.com", "", false).isOk());

        write("a.txt", "x");
        service.stageFile("a.txt");
        assertTrue(service.commit("as alice", false).isOk());

        try (Git git = Git.open(tempDir.toFile());
             var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            var head = walk.parseCommit(git.getRepository().resolve("HEAD"));
            assertEquals("Alice", head.getAuthorIdent().getName());
            assertEquals("alice@work.com", head.getAuthorIdent().getEmailAddress());
        }
    }

    @Test
    void aBlankSigningKeyUnsetsAnyPreviouslyStoredOne() throws Exception {
        assertTrue(service.setLocalIdentity("Alice", "alice@work.com", "KEY123", true).isOk());
        assertTrue(service.setLocalIdentity("Alice", "alice@work.com", "", false).isOk());

        try (Git git = Git.open(tempDir.toFile())) {
            var config = git.getRepository().getConfig();
            assertNull(config.getString("user", null, "signingkey"));
            assertFalse(config.getBoolean("commit", "gpgsign", false));
        }
    }

    // ------------------------------------------------------------------
    // getHeadHash / getCommitsSince - chaining a rebase over a batch's new commits
    // ------------------------------------------------------------------

    @Test
    void getHeadHashMatchesWhatWasJustCommitted() throws Exception {
        commitFile("a.txt", "x", "first");
        String head = resolveHead();

        assertEquals(head, service.getHeadHash());
    }

    @Test
    void getCommitsSinceReturnsNothingWhenNothingWasAdded() throws Exception {
        commitFile("a.txt", "x", "first");
        String head = service.getHeadHash();

        assertTrue(service.getCommitsSince(head).isEmpty());
    }

    @Test
    void getCommitsSinceReturnsNewCommitsOldestFirst() throws Exception {
        commitFile("a.txt", "x", "base");
        String base = service.getHeadHash();

        commitFile("b.txt", "y", "second");
        commitFile("c.txt", "z", "third");

        List<CommitInfo> since = service.getCommitsSince(base);
        assertEquals(2, since.size());
        assertEquals("second", since.get(0).getSubject(), "oldest of the new commits comes first");
        assertEquals("third", since.get(1).getSubject());
    }

    @Test
    void getCommitsSinceAnUnresolvableAncestorIsEmpty() {
        assertTrue(service.getCommitsSince("0000000000000000000000000000000000000000").isEmpty());
        assertTrue(service.getCommitsSince(null).isEmpty());
    }

    /**
     * The exact shape {@code GitGraphCell.cherryPickSelected}'s reword/squash follow-up
     * needs: cherry-pick creates new commits with new hashes, so the commits the
     * follow-up rebase must act on can only be found by walking from the pre-pick HEAD
     * forward, never by reusing the originally-selected {@link CommitInfo}s.
     */
    @Test
    void getCommitsSinceRecoversCherryPickedCommitsByTheirNewHashes() throws Exception {
        commitFile("base.txt", "b", "base");
        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("feature", null, true).isOk());
        commitFile("f1.txt", "1", "feature one");
        String featureFirst = service.getHeadHash();
        commitFile("f2.txt", "2", "feature two");
        String featureSecond = service.getHeadHash();

        // Trunk moves on too, so the cherry-picked commits land on a different parent
        // than their originals - otherwise the replay is content-for-content identical
        // to the source and git legitimately gives it the same hash, which would prove
        // nothing about recovering a genuinely new commit.
        assertTrue(service.checkoutBranch(trunk).isOk());
        commitFile("trunk-only.txt", "t", "trunk moved on");
        String preCherryPickHead = service.getHeadHash();

        assertTrue(new RepositoryCommands(service).cherryPickAll(List.of(featureFirst, featureSecond), java.util.Map.of()).isOk());

        List<CommitInfo> recovered = service.getCommitsSince(preCherryPickHead);
        assertEquals(2, recovered.size());
        assertEquals("feature one", recovered.get(0).getSubject());
        assertEquals("feature two", recovered.get(1).getSubject());
        assertNotEquals(featureFirst, recovered.get(0).getHash(),
                "a cherry-picked commit gets a new hash, not the original's");
        assertNotEquals(recovered.get(0).getHash(), recovered.get(1).getHash());
    }

    // ------------------------------------------------------------------
    // LFS detection: .gitattributes-based path tracking
    // ------------------------------------------------------------------

    private void commitGitattributes(String rule) throws Exception {
        write(".gitattributes", rule);
        assertTrue(service.stageFile(".gitattributes").isOk());
        assertTrue(service.commit("track via gitattributes", false).isOk());
    }

    @Test
    void aPathMatchingAnLfsGitattributesRuleIsTracked() throws Exception {
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");
        write("asset.psd", "not actually binary, just here to exist");

        var tracked = service.lfsTrackedPaths(List.of("asset.psd"));

        assertEquals(Set.of("asset.psd"), tracked);
    }

    @Test
    void aPathNotMatchingAnyRuleIsNotTracked() throws Exception {
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");
        write("readme.md", "just text");

        assertTrue(service.lfsTrackedPaths(List.of("readme.md")).isEmpty());
    }

    @Test
    void severalPathsAreCheckedInOneCallAndOnlyTheMatchingOnesComeBack() throws Exception {
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");
        write("a.psd", "x");
        write("b.psd", "y");
        write("c.txt", "z");

        var tracked = service.lfsTrackedPaths(List.of("a.psd", "b.psd", "c.txt"));

        assertEquals(Set.of("a.psd", "b.psd"), tracked);
    }

    @Test
    void aNestedGitattributesFileIsHonoured() throws Exception {
        Files.createDirectories(tempDir.resolve("sub"));
        write("sub/.gitattributes", "*.bin filter=lfs\n");
        assertTrue(service.stageFile("sub/.gitattributes").isOk());
        assertTrue(service.commit("nested rule", false).isOk());
        write("sub/data.bin", "x");

        assertEquals(Set.of("sub/data.bin"), service.lfsTrackedPaths(List.of("sub/data.bin")));
    }

    @Test
    void aPathIsTrackedEvenBeforeItsContentBecomesAPointer() throws Exception {
        // The point of reading .gitattributes rather than only sniffing content: a
        // file just handed to `git lfs track` is LFS-tracked immediately, before the
        // next commit ever turns its working-tree copy into a pointer.
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");
        write("fresh.psd", "still ordinary bytes, not yet a pointer, but already tracked");

        assertEquals(Set.of("fresh.psd"), service.lfsTrackedPaths(List.of("fresh.psd")));
    }

    @Test
    void noRulesAtAllMeansNothingIsTracked() throws Exception {
        commitFile("a.txt", "x", "plain");
        assertTrue(service.lfsTrackedPaths(List.of("a.txt")).isEmpty());
    }

    @Test
    void emptyOrNullPathsAskNothingOfTheRepository() {
        assertTrue(service.lfsTrackedPaths(List.of()).isEmpty());
        assertTrue(service.lfsTrackedPaths(null).isEmpty());
    }

    @Test
    void hasAnyLfsTrackedPathsIsFalseWithNoGitattributesAtAll() throws Exception {
        commitFile("a.txt", "x", "plain");
        assertFalse(new RepositoryCommands(service).hasAnyLfsTrackedPaths());
    }

    @Test
    void hasAnyLfsTrackedPathsIsFalseWhenGitattributesHasNoLfsRule() throws Exception {
        commitGitattributes("*.txt text=auto\n");
        assertFalse(new RepositoryCommands(service).hasAnyLfsTrackedPaths());
    }

    @Test
    void hasAnyLfsTrackedPathsIsTrueWhenTheRootGitattributesDeclaresOne() throws Exception {
        commitGitattributes("*.psd filter=lfs diff=lfs merge=lfs -text\n");
        assertTrue(new RepositoryCommands(service).hasAnyLfsTrackedPaths());
    }

    /**
     * Reads the working tree directly, not HEAD - a rule just added by {@code git lfs
     * track} but not yet committed should already trip the banner, the same reasoning
     * {@link #aPathIsTrackedEvenBeforeItsContentBecomesAPointer} applies per-path.
     */
    @Test
    void hasAnyLfsTrackedPathsSeesAnUncommittedRuleToo() throws Exception {
        write(".gitattributes", "*.psd filter=lfs diff=lfs merge=lfs -text\n");
        assertTrue(new RepositoryCommands(service).hasAnyLfsTrackedPaths());
    }

    /**
     * JGit has no smudge filter pipeline wired into this codebase at all - unlike
     * {@code canMaterializeLfsContent()} on the native engine, whose answer depends on
     * the real machine's own git-lfs setup, this one must be unconditionally false:
     * every JGit checkout of an LFS-tracked path writes the raw pointer, regardless of
     * what is installed elsewhere.
     */
    @Test
    void jgitCanNeverMaterializeLfsContent() {
        assertFalse(service.canMaterializeLfsContent());
    }

    /**
     * JGit 7.2 has no sparse-checkout support at all (confirmed directly against its
     * jar - not one class or method mentions "sparse" anywhere), so this is a
     * permanent engine limitation, the same shape {@code jgitCanNeverMaterializeLfsContent}
     * already covers for LFS: unconditionally disabled, and a request to narrow the
     * tree is refused rather than silently ignored.
     */
    @Test
    void jgitHasNoSparseCheckoutSupport() {
        assertFalse(service.isSparseCheckoutEnabled());
        assertTrue(service.getSparseCheckoutPaths().isEmpty());
        assertFalse(service.setSparseCheckoutPaths(List.of("src")).isOk());
        assertFalse(service.disableSparseCheckout().isOk());
    }

    /** Regression: the unstaged list used to include already-staged files. */
    @Test
    void stagedFilesAreNotAlsoReportedAsUnstaged() throws Exception {
        commitFile("tracked.txt", "v1", "initial");

        write("tracked.txt", "v2");
        write("brand-new.txt", "new");
        service.stageFile("tracked.txt");

        assertEquals(List.of("tracked.txt"), service.getStagedFiles());
        assertEquals(List.of("brand-new.txt"), service.getStagingState().unstaged(),
                "a staged file must not appear in the unstaged list");
    }

    @Test
    void unstageMovesFileBack() throws Exception {
        write("a.txt", "x");
        service.stageFile("a.txt");
        assertEquals(List.of("a.txt"), service.getStagedFiles());

        assertTrue(service.unstageFile("a.txt").isOk());
        assertTrue(service.getStagedFiles().isEmpty());
        assertEquals(List.of("a.txt"), service.getStagingState().unstaged());
    }

    @Test
    void stageAllAndUnstageAll() throws Exception {
        write("a.txt", "1");
        write("b.txt", "2");

        assertTrue(service.stageAll().isOk());
        assertEquals(List.of("a.txt", "b.txt"), service.getStagedFiles());

        assertTrue(service.unstageAll().isOk());
        assertTrue(service.getStagedFiles().isEmpty());
    }

    @Test
    void amendReplacesPreviousCommit() throws Exception {
        commitFile("a.txt", "one", "first message");

        assertTrue(service.commit("corrected message", true).isOk());
        assertEquals("corrected message", service.getHeadCommitMessage().trim());
    }

    @Test
    void discardRevertsWorkingTreeChange() throws Exception {
        commitFile("a.txt", "original", "initial");
        write("a.txt", "modified");

        assertTrue(service.discardChanges("a.txt").isOk());
        assertEquals("original", Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void discardDeletesUntrackedFile() throws Exception {
        commitFile("a.txt", "x", "initial");
        write("junk.txt", "junk");

        assertTrue(service.discardChanges("junk.txt").isOk());
        assertFalse(tempDir.resolve("junk.txt").toFile().exists());
    }

    // ------------------------------------------------------------------
    // Branches
    // ------------------------------------------------------------------

    @Test
    void createsChecksOutAndDeletesBranch() throws Exception {
        commitFile("a.txt", "x", "initial");
        String original = service.getCurrentBranch();

        assertTrue(service.createBranch("feature", null, true).isOk());
        assertEquals("feature", service.getCurrentBranch());

        assertTrue(service.checkoutBranch(original).isOk());
        assertTrue(service.deleteBranch("feature", false).isOk());
        assertFalse(service.getLocalBranches().stream()
                .map(ref -> ref.shortName()).toList().contains("feature"));
    }

    @Test
    void renameBranch() throws Exception {
        commitFile("a.txt", "x", "initial");
        String original = service.getCurrentBranch();

        assertTrue(service.renameBranch(original, "renamed").isOk());
        assertEquals("renamed", service.getCurrentBranch());
    }

    @Test
    void checkoutOfUnknownBranchFailsWithoutThrowing() {
        GitResult result = service.checkoutBranch("does-not-exist");
        assertFalse(result.isOk());
        assertNotNull(result.getMessage());
    }

    // ------------------------------------------------------------------
    // Merge and conflicts
    // ------------------------------------------------------------------

    @Test
    void cleanMergeSucceeds() throws Exception {
        commitFile("base.txt", "base", "initial");
        String main = service.getCurrentBranch();

        service.createBranch("side", null, true);
        commitFile("side.txt", "side", "side commit");

        service.checkoutBranch(main);
        GitResult merge = new RepositoryCommands(service).merge("side", false);
        assertTrue(merge.isOk(), merge.getMessage());
    }

    /** Regression: a conflicting merge used to be reported to the user as success. */
    @Test
    void conflictingMergeIsReportedAsConflict() throws Exception {
        commitFile("shared.txt", "original\n", "initial");
        String main = service.getCurrentBranch();

        service.createBranch("side", null, true);
        commitFile("shared.txt", "side change\n", "side edit");

        service.checkoutBranch(main);
        commitFile("shared.txt", "main change\n", "main edit");

        GitResult merge = new RepositoryCommands(service).merge("side", false);
        assertFalse(merge.isOk(), "a conflicting merge must not report success");
        assertTrue(merge.isConflict(), "expected CONFLICT, got " + merge.getStatus());
        assertTrue(merge.getConflicts().contains("shared.txt"));
        assertTrue(service.getConflictingFiles().contains("shared.txt"));
    }

    @Test
    void conflictStagesAreReadable() throws Exception {
        commitFile("shared.txt", "original\n", "initial");
        String main = service.getCurrentBranch();

        service.createBranch("side", null, true);
        commitFile("shared.txt", "theirs\n", "side edit");

        service.checkoutBranch(main);
        commitFile("shared.txt", "ours\n", "main edit");
        new RepositoryCommands(service).merge("side", false);

        assertEquals("ours\n", service.getConflictStage("shared.txt", 2));
        assertEquals("theirs\n", service.getConflictStage("shared.txt", 3));
    }

    @Test
    void resolveConflictWritesAndStages() throws Exception {
        commitFile("shared.txt", "original\n", "initial");
        String main = service.getCurrentBranch();

        service.createBranch("side", null, true);
        commitFile("shared.txt", "theirs\n", "side edit");

        service.checkoutBranch(main);
        commitFile("shared.txt", "ours\n", "main edit");
        new RepositoryCommands(service).merge("side", false);

        assertTrue(service.resolveConflict("shared.txt", "resolved\n").isOk());
        assertTrue(service.getConflictingFiles().isEmpty());
        assertEquals("resolved\n", Files.readString(tempDir.resolve("shared.txt")));
    }

    @Test
    void abortMergeRestoresHead() throws Exception {
        commitFile("shared.txt", "original\n", "initial");
        String main = service.getCurrentBranch();

        service.createBranch("side", null, true);
        commitFile("shared.txt", "theirs\n", "side edit");

        service.checkoutBranch(main);
        commitFile("shared.txt", "ours\n", "main edit");
        new RepositoryCommands(service).merge("side", false);

        assertTrue(service.abortOperation().isOk());
        assertTrue(service.getConflictingFiles().isEmpty());
        assertEquals("ours\n", Files.readString(tempDir.resolve("shared.txt")));
    }

    // ------------------------------------------------------------------
    // Stash
    // ------------------------------------------------------------------

    /** Regression: "pop" used to only apply, leaving the stash in place forever. */
    @Test
    void stashPopAppliesAndDropsTheEntry() throws Exception {
        commitFile("a.txt", "original\n", "initial");
        write("a.txt", "modified\n");

        assertTrue(new RepositoryCommands(service).stash().isOk());
        assertEquals(1, service.getStashEntries().size());
        assertEquals("original\n", Files.readString(tempDir.resolve("a.txt")));

        assertTrue(service.stashPop(0).isOk());
        assertEquals("modified\n", Files.readString(tempDir.resolve("a.txt")));
        assertTrue(service.getStashEntries().isEmpty(), "pop must drop the stash entry");

        assertFalse(service.stashPop(0).isOk(), "a second pop must report that nothing is stashed");
    }

    @Test
    void stashApplyKeepsTheEntry() throws Exception {
        commitFile("a.txt", "original\n", "initial");
        write("a.txt", "modified\n");
        new RepositoryCommands(service).stash();

        assertTrue(service.stashApply(0).isOk());
        assertEquals(1, service.getStashEntries().size(), "apply must keep the stash");
    }

    // ------------------------------------------------------------------
    // R6-2. Stashes addressed by commit, not by position
    // ------------------------------------------------------------------

    /** Three stashes, newest first, so position and identity can disagree. */
    private void threeStashes() throws Exception {
        commitFile("a.txt", "original\n", "initial");
        for (String each : new String[]{"first", "second", "third"}) {
            write("a.txt", each + "\n");
            assertTrue(new RepositoryCommands(service).stash().isOk(), "could not stash " + each);
        }
        assertEquals(3, service.getStashEntries().size());
    }

    /**
     * The defect the hash-addressed calls replace.
     *
     * <p>{@code stash@{n}} is a position git renumbers on every drop, and a menu item
     * carries the number its row was drawn with. Drop one entry and then act on a number
     * read before that drop, and a different stash goes - one the user never pointed at,
     * recoverable only through {@code git fsck}.
     *
     * <p>Reproduced here with no UI at all: capture the positions, drop one, and watch
     * what those positions now mean. That is exactly what two quick clicks produce.
     */
    @Test
    void positionsRenumberUnderneathAStalePosition() throws Exception {
        threeStashes();
        var before = service.getStashEntries();
        String wasAtOne = before.get(1).hash();
        String wasAtTwo = before.get(2).hash();

        assertTrue(new RepositoryCommands(service).stashDrop(before.get(0).hash()).isOk());

        assertEquals(wasAtOne, service.getStashEntries().get(0).hash(),
                "the entry that was at 1 is now at 0");
        assertEquals(wasAtTwo, service.getStashEntries().get(1).hash(),
                "so position 1 now means what position 2 used to - the whole problem");
    }

    /**
     * And the consequence, spelled out: a position read before a drop deletes the wrong
     * stash afterwards.
     *
     * <p>This is what the two-click sequence did through the menus, and it is why the
     * positional calls are not what a row's menu may use. The positional API stays -
     * "pop the most recent stash" is a real thing a toolbar button means - but it means
     * a position, and this is what that costs when the position is stale.
     */
    @Test
    void aStalePositionDropsAStashTheUserNeverChose() throws Exception {
        threeStashes();
        var before = service.getStashEntries();
        String middle = before.get(1).hash();
        String oldest = before.get(2).hash();

        // Click one: drop the newest.
        assertTrue(new RepositoryCommands(service).stashDrop(before.get(0).hash()).isOk());
        // Click two, carrying the number the row was drawn with, meaning "the oldest".
        assertTrue(service.stashDrop(2 - 1).isOk());

        var left = service.getStashEntries();
        assertEquals(1, left.size());
        assertEquals(middle, left.get(0).hash(),
                "the stale position took the oldest entry, which nobody selected");
        assertTrue(left.stream().noneMatch(e -> oldest.equals(e.hash())),
                "gone, and only git fsck could get it back");
    }

    @Test
    void applyingByHashPicksTheRightEntry() throws Exception {
        threeStashes();
        var entries = service.getStashEntries();

        // The oldest stash holds "first"; applying it by hash must restore that.
        assertTrue(new RepositoryCommands(service).stashApply(entries.get(2).hash()).isOk());

        assertEquals("first\n", Files.readString(tempDir.resolve("a.txt")));
        assertEquals(3, service.getStashEntries().size(), "apply keeps every entry");
    }

    @Test
    void popByHashRemovesThatEntryAndLeavesTheRest() throws Exception {
        threeStashes();
        String middle = service.getStashEntries().get(1).hash();

        assertTrue(new RepositoryCommands(service).stashPop(middle).isOk());

        assertEquals("second\n", Files.readString(tempDir.resolve("a.txt")));
        assertEquals(2, service.getStashEntries().size());
        assertTrue(service.getStashEntries().stream().noneMatch(e -> middle.equals(e.hash())),
                "the popped entry is the one that went");
    }

    @Test
    void stashingACleanTreeFails() throws Exception {
        commitFile("a.txt", "x", "initial");
        assertFalse(new RepositoryCommands(service).stash().isOk());
    }

    // ------------------------------------------------------------------
    // Tags, reset, history
    // ------------------------------------------------------------------

    @Test
    void createsAndDeletesTag() throws Exception {
        commitFile("a.txt", "x", "initial");

        assertTrue(service.createTag("v1.0", "release", "HEAD").isOk());
        assertEquals(List.of("v1.0"),
                service.getTags().stream().map(ref -> ref.shortName()).toList());

        assertTrue(service.deleteTag("v1.0").isOk());
        assertTrue(service.getTags().isEmpty());
    }

    /**
     * A non-null message must produce a real annotated tag object - not just a ref
     * that happens to carry a message somewhere - proven by peeling the ref and
     * parsing what it actually points at.
     */
    @Test
    void aNonNullMessageProducesARealAnnotatedTagObject() throws Exception {
        commitFile("a.txt", "x", "initial");
        assertTrue(service.createTag("v1.0", "release notes", "HEAD").isOk());

        try (Git git = Git.open(tempDir.toFile())) {
            var ref = git.getRepository().getRefDatabase().exactRef("refs/tags/v1.0");
            assertNotNull(ref);
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                var tagObject = walk.parseTag(ref.getObjectId());
                assertEquals("release notes", tagObject.getFullMessage().trim());
            }
        }
    }

    /** A {@code null} message is the lightweight case: the ref points straight at the commit. */
    @Test
    void aNullMessageProducesALightweightTagPointingDirectlyAtTheCommit() throws Exception {
        commitFile("a.txt", "x", "initial");
        assertTrue(service.createTag("v1.0", null, "HEAD").isOk());

        try (Git git = Git.open(tempDir.toFile())) {
            var ref = git.getRepository().getRefDatabase().exactRef("refs/tags/v1.0");
            assertNotNull(ref);
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                assertEquals(org.eclipse.jgit.lib.Constants.OBJ_COMMIT,
                        walk.parseAny(ref.getObjectId()).getType());
            }
        }
    }

    // ------------------------------------------------------------------
    // deleteRemoteTag
    // ------------------------------------------------------------------

    /**
     * {@code deleteRemoteTag} pushes an empty source refspec - it must remove the tag
     * on the remote while leaving the local tag completely untouched, unlike a plain
     * {@code deleteTag}.
     */
    @Test
    void deleteRemoteTagRemovesItFromTheRemoteButKeepsTheLocalTag() throws Exception {
        commitFile("a.txt", "x", "initial");
        assertTrue(service.createTag("v1.0", null, "HEAD").isOk());

        Path remoteDir = Files.createTempDirectory("lyra-delete-remote-tag");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());
            // pushTag, not "push with tags": that follows annotated tags only, as
            // git's --follow-tags does, and v1.0 here is lightweight.
            assertTrue(service.pushTag("origin", "v1.0").isOk());

            try (Git bare = Git.open(remoteDir.toFile())) {
                assertNotNull(bare.getRepository().getRefDatabase().exactRef("refs/tags/v1.0"));
            }

            GitResult result = service.deleteRemoteTag("origin", "v1.0");
            assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());

            try (Git bare = Git.open(remoteDir.toFile())) {
                assertNull(bare.getRepository().getRefDatabase().exactRef("refs/tags/v1.0"));
            }
            assertEquals(List.of("v1.0"),
                    service.getTags().stream().map(ref -> ref.shortName()).toList());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    @Test
    void deleteRemoteTagFailsCleanlyWhenTheRemoteDoesNotExist() throws Exception {
        commitFile("a.txt", "x", "initial");
        assertTrue(service.createTag("v1.0", null, "HEAD").isOk());

        GitResult result = service.deleteRemoteTag("origin", "v1.0");
        assertFalse(result.isOk());
    }

    // ------------------------------------------------------------------
    // pushTag
    // ------------------------------------------------------------------

    /**
     * {@code pushTag} must publish exactly the one named tag - not the current branch,
     * not any other tag - the single-tag counterpart to the toolbar's bulk "Push with
     * tags".
     */
    @Test
    void pushTagPublishesOnlyTheNamedTagToTheRemote() throws Exception {
        commitFile("a.txt", "x", "initial");
        assertTrue(service.createTag("v1.0", null, "HEAD").isOk());
        assertTrue(service.createTag("v2.0", null, "HEAD").isOk());

        Path remoteDir = Files.createTempDirectory("lyra-push-tag");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());

            GitResult result = service.pushTag("origin", "v1.0");
            assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());

            try (Git bare = Git.open(remoteDir.toFile())) {
                var refs = bare.getRepository().getRefDatabase();
                assertNotNull(refs.exactRef("refs/tags/v1.0"));
                assertNull(refs.exactRef("refs/tags/v2.0"), "only v1.0 was asked for");
                assertTrue(refs.getRefs().stream().noneMatch(r -> r.getName().startsWith("refs/heads/")),
                        "pushTag must not also push the current branch");
            }
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    @Test
    void pushTagFailsCleanlyWhenTheRemoteDoesNotExist() throws Exception {
        commitFile("a.txt", "x", "initial");
        assertTrue(service.createTag("v1.0", null, "HEAD").isOk());

        GitResult result = service.pushTag("origin", "v1.0");
        assertFalse(result.isOk());
    }

    // ------------------------------------------------------------------
    // moveTag / isTagMoveFastForward
    // ------------------------------------------------------------------

    @Test
    void movingATagToADescendantCommitIsAFastForwardAndActuallyMovesIt() throws Exception {
        commitFile("a.txt", "one", "first");
        assertTrue(service.createTag("v1.0", null, "HEAD").isOk());
        String original = headHash();
        commitFile("a.txt", "two", "second");
        String descendant = headHash();

        assertTrue(service.isTagMoveFastForward("v1.0", descendant));

        GitResult result = service.moveTag("v1.0", descendant);
        assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());
        assertEquals(descendant, tagTargetHash("v1.0"));
        assertNotEquals(original, tagTargetHash("v1.0"));
    }

    @Test
    void movingATagBackwardToAnAncestorIsNotAFastForwardButStillWorksIfAsked() throws Exception {
        commitFile("a.txt", "one", "first");
        String ancestor = headHash();
        commitFile("a.txt", "two", "second");
        assertTrue(service.createTag("v2.0", null, "HEAD").isOk());

        assertFalse(service.isTagMoveFastForward("v2.0", ancestor),
                "moving to an earlier commit on the same line of history is not a fast-forward");

        assertTrue(service.moveTag("v2.0", ancestor).isOk());
        assertEquals(ancestor, tagTargetHash("v2.0"));
    }

    @Test
    void movingATagToAnUnrelatedBranchIsNotAFastForward() throws Exception {
        commitFile("a.txt", "one", "first");
        String mainBranch = service.getCurrentBranch();
        // Branch off before the tagged commit exists, so "side" and the tag's commit
        // sit on genuinely diverging lines of history from here on - neither is an
        // ancestor of the other.
        assertTrue(service.createBranch("side", null, false).isOk());

        commitFile("a.txt", "two", "second, only on " + mainBranch);
        assertTrue(service.createTag("v1.0", null, "HEAD").isOk());

        assertTrue(service.checkoutBranch("side").isOk());
        commitFile("side.txt", "side", "side commit, diverging from the tag");
        String sideTip = headHash();

        assertFalse(service.isTagMoveFastForward("v1.0", sideTip),
                "an unrelated commit on a diverging branch is neither an ancestor nor a descendant");
    }

    @Test
    void movingAnAnnotatedTagPreservesItsOriginalMessage() throws Exception {
        commitFile("a.txt", "one", "first");
        assertTrue(service.createTag("v1.0", "release notes", "HEAD").isOk());
        commitFile("a.txt", "two", "second");
        String descendant = headHash();

        assertTrue(service.moveTag("v1.0", descendant).isOk());

        try (Git git = Git.open(tempDir.toFile())) {
            var ref = git.getRepository().getRefDatabase().exactRef("refs/tags/v1.0");
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                var tagObject = walk.parseTag(ref.getObjectId());
                assertEquals("release notes", tagObject.getFullMessage().trim());
            }
        }
    }

    @Test
    void movingALightweightTagStaysLightweight() throws Exception {
        commitFile("a.txt", "one", "first");
        assertTrue(service.createTag("v1.0", null, "HEAD").isOk());
        commitFile("a.txt", "two", "second");
        String descendant = headHash();

        assertTrue(service.moveTag("v1.0", descendant).isOk());

        try (Git git = Git.open(tempDir.toFile())) {
            var ref = git.getRepository().getRefDatabase().exactRef("refs/tags/v1.0");
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                assertEquals(org.eclipse.jgit.lib.Constants.OBJ_COMMIT,
                        walk.parseAny(ref.getObjectId()).getType());
            }
        }
    }

    private String headHash() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    private String tagTargetHash(String tagName) throws Exception {
        return service.getTags().stream()
                .filter(r -> r.shortName().equals(tagName))
                .findFirst().orElseThrow().targetHash();
    }

    @Test
    void undoLastCommitKeepsChangesStaged() throws Exception {
        commitFile("a.txt", "one", "first");
        commitFile("b.txt", "two", "second");

        assertTrue(service.reset("HEAD~1", ResetMode.SOFT).isOk());
        assertEquals("first", service.getHeadCommitMessage().trim());
        assertEquals(List.of("b.txt"), service.getStagedFiles());
    }

    @Test
    void undoOnASingleCommitRepositoryFails() throws Exception {
        commitFile("a.txt", "x", "only commit");
        assertFalse(service.reset("HEAD~1", ResetMode.SOFT).isOk());
    }

    @Test
    void resetHardDiscardsWorkingTreeChanges() throws Exception {
        commitFile("a.txt", "original", "initial");
        write("a.txt", "changed");

        assertTrue(service.reset("HEAD", ResetMode.HARD).isOk());
        assertEquals("original", Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void revertCreatesAnInverseCommit() throws Exception {
        commitFile("a.txt", "original\n", "initial");
        commitFile("a.txt", "changed\n", "change it");

        String head = service.getCommitGraphPage(GitService.DEFAULT_GRAPH_LIMIT, GraphOrder.current()).rows().stream()
                .filter(r -> !r.isWipNode())
                .findFirst()
                .orElseThrow()
                .getCommit()
                .getHash();

        assertTrue(service.revert(head).isOk());
        assertEquals("original\n", Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void graphContainsCommitsAndWipRow() throws Exception {
        commitFile("a.txt", "x", "first");
        assertEquals(1, service.getCommitGraphPage(GitService.DEFAULT_GRAPH_LIMIT, GraphOrder.current()).rows().size());

        write("b.txt", "dirty");
        var rows = service.getCommitGraphPage(GitService.DEFAULT_GRAPH_LIMIT, GraphOrder.current()).rows();
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).isWipNode(), "the WIP row should come first");
    }

    @Test
    void graphRespectsTheLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            commitFile("f" + i + ".txt", "v", "commit " + i);
        }
        assertEquals(3, service.getCommitGraphPage(3, GraphOrder.current()).rows().size());
    }

    // ------------------------------------------------------------------
    // Hide & Solo (GraphVisibility)
    // ------------------------------------------------------------------

    private List<String> subjectsWith(com.jfx.git.graph.GraphVisibility visibility) {
        return service.getCommitGraphPage(20, GraphOrder.CHRONOLOGICAL, visibility).rows().stream()
                .filter(row -> !row.isWipNode() && row.getCommit() != null)
                .map(row -> row.getCommit().getSubject())
                .toList();
    }

    @Test
    void unrestrictedVisibilityMatchesTheTwoArgOverload() throws Exception {
        commitFile("m0.txt", "m0", "commit M0");
        assertTrue(service.createBranch("side", null, true).isOk());
        commitFile("s0.txt", "s0", "commit S0");

        assertEquals(subjectsInOrder(GraphOrder.CHRONOLOGICAL),
                subjectsWith(com.jfx.git.graph.GraphVisibility.ALL));
    }

    @Test
    void hidingABranchExcludesCommitsOnlyItReaches() throws Exception {
        commitFile("m0.txt", "m0", "commit M0");
        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("side", null, true).isOk());
        commitFile("s0.txt", "s0", "commit S0 on side");
        assertTrue(service.checkoutBranch(trunk).isOk());

        var hideSide = new com.jfx.git.graph.GraphVisibility(
                java.util.Set.of("refs/heads/side"), null);
        List<String> subjects = subjectsWith(hideSide);

        assertTrue(subjects.contains("commit M0"), "trunk commit is reachable from HEAD too");
        assertFalse(subjects.contains("commit S0 on side"),
                "hiding side's only ref should drop the commit reachable only through it");
    }

    @Test
    void soloingABranchShowsOnlyItAndHead() throws Exception {
        commitFile("m0.txt", "m0", "commit M0");
        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("side1", null, true).isOk());
        commitFile("s1.txt", "s1", "commit S1 on side1");
        assertTrue(service.checkoutBranch(trunk).isOk());
        assertTrue(service.createBranch("side2", null, true).isOk());
        commitFile("s2.txt", "s2", "commit S2 on side2");
        assertTrue(service.checkoutBranch(trunk).isOk());

        var soloSide1 = new com.jfx.git.graph.GraphVisibility(java.util.Set.of(), "refs/heads/side1");
        List<String> subjects = subjectsWith(soloSide1);

        assertTrue(subjects.contains("commit M0"), "HEAD's own history is never solo'd away");
        assertTrue(subjects.contains("commit S1 on side1"), "the solo'd branch stays");
        assertFalse(subjects.contains("commit S2 on side2"),
                "solo overrides everything not named, including branches that were not hidden");
    }

    @Test
    void soloOverridesHideRatherThanCombiningWithIt() throws Exception {
        commitFile("m0.txt", "m0", "commit M0");
        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("side", null, true).isOk());
        commitFile("s0.txt", "s0", "commit S0 on side");
        assertTrue(service.checkoutBranch(trunk).isOk());

        // Hidden and solo'd at once: solo should win, not both apply and hide everything.
        var conflicting = new com.jfx.git.graph.GraphVisibility(
                java.util.Set.of("refs/heads/side"), "refs/heads/side");
        assertTrue(subjectsWith(conflicting).contains("commit S0 on side"),
                "solo names the branch, so it must show even though the hidden set also names it");
    }

    // ------------------------------------------------------------------
    // Graph paging
    // ------------------------------------------------------------------

    @Test
    void aTruncatedGraphSaysThereIsMore() throws Exception {
        for (int i = 0; i < 5; i++) {
            commitFile("f" + i + ".txt", "v", "commit " + i);
        }
        var page = service.getCommitGraphPage(3, GraphOrder.current());
        assertEquals(3, page.rows().size());
        assertTrue(page.hasMore(), "two commits are still unwalked");
    }

    @Test
    void aCompleteGraphSaysThereIsNotMore() throws Exception {
        for (int i = 0; i < 5; i++) {
            commitFile("f" + i + ".txt", "v", "commit " + i);
        }
        // Exactly the whole history: the boundary that a naive "did we fill up?" check
        // gets wrong, reporting more when there is nothing left.
        var exact = service.getCommitGraphPage(5, GraphOrder.current());
        assertEquals(5, exact.rows().size());
        assertFalse(exact.hasMore());

        var beyond = service.getCommitGraphPage(50, GraphOrder.current());
        assertEquals(5, beyond.rows().size());
        assertFalse(beyond.hasMore());
    }

    @Test
    void pagingFurtherKeepsTheEarlierRowsIdentical() throws Exception {
        for (int i = 0; i < 8; i++) {
            commitFile("f" + i + ".txt", "v", "commit " + i);
        }
        var first = service.getCommitGraphPage(3, GraphOrder.current());
        var second = service.getCommitGraphPage(6, GraphOrder.current());

        assertEquals(6, second.rows().size());
        // The rows already on screen must come back the same, in the same order, or the
        // graph would visibly reshuffle when the user asks for more.
        for (int i = 0; i < first.rows().size(); i++) {
            assertEquals(first.rows().get(i).getCommit().getHash(),
                    second.rows().get(i).getCommit().getHash(),
                    "row " + i + " changed identity between pages");
        }
    }

    @Test
    void pagingKeepsLanePositionsStable() throws Exception {
        // A branch and a merge, so there is more than one lane to get wrong.
        commitFile("base.txt", "base", "base");
        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("side", null, true).isOk());
        commitFile("side.txt", "side", "on side");
        assertTrue(service.checkoutBranch(trunk).isOk());
        commitFile("main.txt", "main", "on main");
        assertTrue(new RepositoryCommands(service).merge("side", true).isOk());

        var narrow = service.getCommitGraphPage(3, GraphOrder.current());
        var wide = service.getCommitGraphPage(10, GraphOrder.current());
        for (int i = 0; i < narrow.rows().size(); i++) {
            assertEquals(narrow.rows().get(i).getCommit().getLane(),
                    wide.rows().get(i).getCommit().getLane(),
                    "lane moved for row " + i + ", so its colour would change");
        }
    }

    @Test
    void theWipRowDoesNotCountAgainstThePage() throws Exception {
        for (int i = 0; i < 5; i++) {
            commitFile("f" + i + ".txt", "v", "commit " + i);
        }
        write("dirty.txt", "uncommitted");

        var page = service.getCommitGraphPage(3, GraphOrder.current());
        assertTrue(page.rows().get(0).isWipNode());
        assertEquals(4, page.rows().size(), "three commits plus the WIP row");
        assertTrue(page.hasMore());
    }

    @Test
    void graphRowsKnowWhetherTheyAreOnTheCurrentBranch() throws Exception {
        commitFile("base.txt", "base", "base");
        String trunk = service.getCurrentBranch();
        assertTrue(service.createBranch("side", null, true).isOk());
        commitFile("side.txt", "side", "only on side");
        assertTrue(service.checkoutBranch(trunk).isOk());
        commitFile("main.txt", "main", "only on main");

        var byMessage = new java.util.HashMap<String, Boolean>();
        for (var row : service.getCommitGraphPage(20, GraphOrder.current()).rows()) {
            if (!row.isWipNode()) {
                byMessage.put(row.getCommit().getSubject(), row.isOnCurrentBranch());
            }
        }

        // The graph draws both branches; only one of these is reachable from HEAD.
        assertEquals(Boolean.TRUE, byMessage.get("only on main"));
        assertEquals(Boolean.FALSE, byMessage.get("only on side"));
        assertEquals(Boolean.TRUE, byMessage.get("base"), "a shared ancestor is on the branch");
    }

    @Test
    void graphLanesPreservePrimaryBranchContinuityAcrossForksAndMerges() throws Exception {
        commitFile("m0.txt", "m0", "commit M0");
        String trunk = service.getCurrentBranch();

        // Fork featureA from M0
        assertTrue(service.createBranch("featureA", null, true).isOk());
        commitFile("fa.txt", "fa", "commit FA");

        // Back to trunk
        assertTrue(service.checkoutBranch(trunk).isOk());
        commitFile("m1.txt", "m1", "commit M1");

        // Fork featureB from M1
        assertTrue(service.createBranch("featureB", null, true).isOk());
        commitFile("fb.txt", "fb", "commit FB");

        // Back to trunk and make M2
        assertTrue(service.checkoutBranch(trunk).isOk());
        commitFile("m2.txt", "m2", "commit M2");

        // Stated rather than inherited. Trunk-on-lane-0 is a property of BY_BRANCH, and
        // reading the mode from the saved user preference meant this asserted whatever the
        // developer had last picked in the Preferences dialog.
        var page = service.getCommitGraphPage(20, GraphOrder.BY_BRANCH);
        var laneBySubject = new java.util.HashMap<String, Integer>();
        for (var row : page.rows()) {
            if (!row.isWipNode()) {
                laneBySubject.put(row.getCommit().getSubject(), row.getCommit().getLane());
            }
        }

        // Commits on main trunk must all stay in Lane 0
        assertEquals(0, laneBySubject.get("commit M2"), "trunk commit M2 should be lane 0");
        assertEquals(0, laneBySubject.get("commit M1"), "trunk commit M1 should be lane 0");
        assertEquals(0, laneBySubject.get("commit M0"), "trunk commit M0 should be lane 0");

        // Side branches must take side lanes (> 0)
        assertTrue(laneBySubject.get("commit FB") > 0, "featureB should be on a side lane");
        assertTrue(laneBySubject.get("commit FA") > 0, "featureA should be on a side lane");
    }

    /**
     * Chronological order interleaves branches strictly by date.
     *
     * <p>The dates are set explicitly and deliberately disagree with the branch structure:
     * the two side commits sit either side of the trunk's second commit, so any ordering
     * that groups a branch together produces a different sequence from this one.
     */
    @Test
    void chronologicalOrderIsStrictlyNewestFirst() throws Exception {
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        commitFileAt("m0.txt", "m0", "commit M0", t0);
        String trunk = service.getCurrentBranch();

        assertTrue(service.createBranch("side", null, true).isOk());
        commitFileAt("s1.txt", "s1", "commit S1", t0.plusSeconds(60));

        assertTrue(service.checkoutBranch(trunk).isOk());
        commitFileAt("m1.txt", "m1", "commit M1", t0.plusSeconds(120));

        assertTrue(service.checkoutBranch("side").isOk());
        commitFileAt("s2.txt", "s2", "commit S2", t0.plusSeconds(180));

        assertEquals(List.of("commit S2", "commit M1", "commit S1", "commit M0"),
                subjectsInOrder(GraphOrder.CHRONOLOGICAL),
                "chronological order should follow the clock, not the branch");
    }

    /**
     * The two orders really are two orders.
     *
     * <p>This is the assertion that fails if the parameter is ever quietly ignored — which
     * is the state the code was in when the mode came from a stored preference and no
     * caller could influence it.
     */
    @Test
    void theTwoGraphOrdersDrawDifferentPictures() throws Exception {
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        commitFileAt("m0.txt", "m0", "commit M0", t0);
        String trunk = service.getCurrentBranch();

        assertTrue(service.createBranch("side", null, true).isOk());
        commitFileAt("s1.txt", "s1", "commit S1", t0.plusSeconds(60));

        assertTrue(service.checkoutBranch(trunk).isOk());
        commitFileAt("m1.txt", "m1", "commit M1", t0.plusSeconds(120));

        assertTrue(service.checkoutBranch("side").isOk());
        commitFileAt("s2.txt", "s2", "commit S2", t0.plusSeconds(180));

        List<String> byBranch = subjectsInOrder(GraphOrder.BY_BRANCH);
        List<String> chronological = subjectsInOrder(GraphOrder.CHRONOLOGICAL);

        assertEquals(4, byBranch.size());
        assertTrue(byBranch.containsAll(chronological),
                "both orders draw the same commits");
        assertNotEquals(byBranch, chronological,
                "grouping by branch and following the clock should not agree here");
    }

    /** Grouping by branch keeps a branch's commits together rather than interleaved. */
    @Test
    void byBranchOrderKeepsABranchContiguous() throws Exception {
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        commitFileAt("m0.txt", "m0", "commit M0", t0);
        String trunk = service.getCurrentBranch();

        assertTrue(service.createBranch("side", null, true).isOk());
        commitFileAt("s1.txt", "s1", "commit S1", t0.plusSeconds(60));

        assertTrue(service.checkoutBranch(trunk).isOk());
        commitFileAt("m1.txt", "m1", "commit M1", t0.plusSeconds(120));

        assertTrue(service.checkoutBranch("side").isOk());
        commitFileAt("s2.txt", "s2", "commit S2", t0.plusSeconds(180));

        List<String> order = subjectsInOrder(GraphOrder.BY_BRANCH);
        int s1 = order.indexOf("commit S1");
        int s2 = order.indexOf("commit S2");

        assertEquals(1, Math.abs(s1 - s2),
                "the side branch's commits should sit next to each other, not either side "
                        + "of a trunk commit: " + order);
    }

    @Test
    void anEmptyRepositoryHasNothingMoreToLoad() {
        var page = service.getCommitGraphPage(10, GraphOrder.current());
        assertTrue(page.rows().isEmpty());
        assertFalse(page.hasMore());
    }

    @Test
    void fileHistoryListsCommitsTouchingThePath() throws Exception {
        commitFile("a.txt", "one", "first");
        commitFile("a.txt", "two", "second");
        commitFile("other.txt", "x", "unrelated");

        assertEquals(2, service.getFileHistoryCommits("a.txt", 0).size());
    }

    @Test
    void blameNamesTheAuthor() throws Exception {
        commitFile("a.txt", "line one\n", "initial");

        String blame = service.getBlame("a.txt");
        assertTrue(blame.contains("Test User"), blame);
        assertTrue(blame.contains("line one"), blame);
    }

    /** Regression: blame indexed HEAD's lines, so a longer working file overran the result. */
    @Test
    void blameHandlesWorkingFileLongerThanHead() throws Exception {
        commitFile("a.txt", "one\n", "initial");
        write("a.txt", "one\ntwo\nthree\n");

        String blame = service.getBlame("a.txt");
        assertTrue(blame.contains("three"), "blame must cover the added lines: " + blame);
    }

    // ------------------------------------------------------------------
    // Ignore-whitespace diffs
    // ------------------------------------------------------------------

    /** A re-indented line is a change normally, but not with ignore-whitespace on. */
    @Test
    void ignoreWhitespaceHidesIndentationOnlyChanges() throws Exception {
        commitFile("a.txt", "if (x) {\n    doThing();\n}\n", "initial");
        write("a.txt", "if (x) {\n\t\tdoThing();\n}\n");

        String normal = utf8(service.getUnstagedDiffBytes("a.txt", false));
        assertTrue(normal.contains("-    doThing();"), normal);
        assertTrue(normal.contains("+\t\tdoThing();"), normal);

        String ignoring = utf8(service.getUnstagedDiffBytes("a.txt", true));
        assertFalse(ignoring.contains("doThing"),
                "a whitespace-only change should vanish when ignoring whitespace: " + ignoring);
    }

    /** Ignoring whitespace must not hide a genuine edit on the same line. */
    @Test
    void ignoreWhitespaceStillReportsRealChanges() throws Exception {
        commitFile("a.txt", "value = 1\n", "initial");
        write("a.txt", "value   =   2\n");

        String ignoring = utf8(service.getUnstagedDiffBytes("a.txt", true));
        assertTrue(ignoring.contains("value"), ignoring);
        assertTrue(ignoring.contains("+value   =   2"), ignoring);
    }

    @Test
    void ignoreWhitespaceAppliesToStagedDiffs() throws Exception {
        commitFile("a.txt", "one\n  two\n", "initial");
        write("a.txt", "one\n\ttwo\n");
        service.stageFile("a.txt");

        assertTrue(utf8(service.getStagedDiffBytes("a.txt", false)).contains("two"));
        assertFalse(utf8(service.getStagedDiffBytes("a.txt", true)).contains("two"));
    }

    @Test
    void ignoreWhitespaceAppliesToCommitDiffs() throws Exception {
        commitFile("a.txt", "alpha\n    beta\n", "initial");
        commitFile("a.txt", "alpha\n\t\tbeta\n", "reindent");

        var head = service.getCommitGraphPage(GitService.DEFAULT_GRAPH_LIMIT, GraphOrder.current()).rows().stream()
                .filter(r -> !r.isWipNode()).findFirst().orElseThrow().getCommit();

        assertTrue(utf8(service.getDiffBytesForFileInCommit(head, "a.txt", false)).contains("beta"));
        assertFalse(utf8(service.getDiffBytesForFileInCommit(head, "a.txt", true)).contains("beta"));
    }

    @Test
    void stagedAndUnstagedDiffsAreDistinct() throws Exception {
        commitFile("a.txt", "original\n", "initial");

        write("a.txt", "staged\n");
        service.stageFile("a.txt");
        write("a.txt", "working\n");

        assertTrue(utf8(service.getStagedDiffBytes("a.txt", false)).contains("staged"));
        assertTrue(utf8(service.getUnstagedDiffBytes("a.txt", false)).contains("working"));
    }

    // ------------------------------------------------------------------
    // Hunk revert
    // ------------------------------------------------------------------

    @Test
    void revertHunkRestoresTheOriginalLines() throws Exception {
        commitFile("a.txt", "one\ntwo\nthree\n", "initial");
        write("a.txt", "one\nCHANGED\nthree\n");

        GitResult result = service.revertHunk("a.txt", new HunkEdit(1,
                List.of("one", "CHANGED", "three"),
                List.of("one", "two", "three")));

        assertTrue(result.isOk(), result.getMessage());
        assertEquals("one\ntwo\nthree\n", Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void revertHunkCanRemoveAddedLines() throws Exception {
        commitFile("a.txt", "one\ntwo\n", "initial");
        write("a.txt", "one\ninserted\ntwo\n");

        assertTrue(service.revertHunk("a.txt", new HunkEdit(1,
                List.of("one", "inserted", "two"),
                List.of("one", "two"))).isOk());
        assertEquals("one\ntwo\n", Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void revertHunkCanRestoreDeletedLines() throws Exception {
        commitFile("a.txt", "one\nthree\n", "initial");

        assertTrue(service.revertHunk("a.txt", new HunkEdit(1,
                List.of("one", "three"),
                List.of("one", "two", "three"))).isOk());
        assertEquals("one\ntwo\nthree\n", Files.readString(tempDir.resolve("a.txt")));
    }

    /**
     * The motivating case for context matching: reverting a hunk from an older commit,
     * where later edits have pushed the content away from its recorded line numbers.
     */
    @Test
    void revertHunkFindsContentThatHasMovedSinceTheCommit() throws Exception {
        commitFile("a.txt", "one\nCHANGED\nthree\n", "initial");
        // Ten lines get prepended, so the hunk's recorded position is now far off.
        write("a.txt", "pad\n".repeat(10) + "one\nCHANGED\nthree\n");

        GitResult result = service.revertHunk("a.txt", new HunkEdit(1,
                List.of("one", "CHANGED", "three"),
                List.of("one", "two", "three")));

        assertTrue(result.isOk(), result.getMessage());
        assertEquals("pad\n".repeat(10) + "one\ntwo\nthree\n",
                Files.readString(tempDir.resolve("a.txt")));
        assertTrue(result.getMessage().contains("offset"), result.getMessage());
    }

    @Test
    void revertHunkFailsWhenTheContentIsGone() throws Exception {
        commitFile("a.txt", "totally different\n", "initial");

        GitResult result = service.revertHunk("a.txt", new HunkEdit(1,
                List.of("one", "CHANGED", "three"),
                List.of("one", "two", "three")));

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("no longer applies"), result.getMessage());
    }

    @Test
    void revertHunkPrefersTheNearestOfSeveralMatches() throws Exception {
        // The same block appears twice; the hint must decide which one is rewritten.
        commitFile("a.txt", "dup\nx\ndup\n", "initial");

        assertTrue(service.revertHunk("a.txt", new HunkEdit(3, List.of("dup"), List.of("restored"))).isOk());
        assertEquals("dup\nx\nrestored\n", Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void revertHunkPreservesAMissingTrailingNewline() throws Exception {
        commitFile("a.txt", "one\n", "initial");
        write("a.txt", "CHANGED");

        assertTrue(service.revertHunk("a.txt", new HunkEdit(1, List.of("CHANGED"), List.of("one"))).isOk());
        assertEquals("one", Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void revertHunkRejectsAMissingFile() {
        assertFalse(service.revertHunk("nope.txt", new HunkEdit(1, List.of("x"), List.of("y"))).isOk());
    }

    // ------------------------------------------------------------------
    // Remotes
    // ------------------------------------------------------------------

    @Test
    void addsAndRemovesRemote() {
        assertTrue(service.getRemoteNames().isEmpty());

        assertTrue(service.addRemote("origin", "https://example.com/repo.git").isOk());
        assertEquals(List.of("origin"), service.getRemoteNames());

        assertTrue(service.removeRemote("origin").isOk());
        assertTrue(service.getRemoteNames().isEmpty());
    }

    @Test
    void restoringAFileFromACommitWritesItsBlobBackToTheWorkTree() throws Exception {
        Files.createDirectories(tempDir.resolve("deep"));
        commitFile("deep/nested.txt", "version one", "first");
        String first;
        try (Git git = Git.open(tempDir.toFile())) {
            first = git.getRepository().resolve("HEAD").getName();
        }
        write("deep/nested.txt", "version two");
        assertTrue(service.stageFile("deep/nested.txt").isOk());
        assertTrue(service.commit("second", false).isOk());

        // Overwrite with uncommitted noise, then roll the file back to the first commit.
        write("deep/nested.txt", "uncommitted mess");
        Files.deleteIfExists(tempDir.resolve("deep/nested.txt"));

        var result = service.restoreFileFromCommit(first, "deep/nested.txt");
        assertTrue(result.isOk(), () -> "restore failed: " + result.getMessage());
        assertEquals("version one",
                java.nio.file.Files.readString(tempDir.resolve("deep/nested.txt")),
                "the file must come back exactly as the named commit had it");
        // Left unstaged, so what happened stays visible in the diff.
        assertTrue(service.getStagingState().unstaged().contains("deep/nested.txt"),
                "a restored file is a working-tree edit, not a staged one");

        // A path the commit never had is refused, not invented.
        assertTrue(service.restoreFileFromCommit(first, "no/such/file.txt").getMessage()
                        .contains("does not exist"),
                "restoring a blob the commit lacks must fail legibly");
    }

    @Test
    void discardAllRestoresTrackedFilesAndRemovesUntrackedOnes() throws Exception {
        commitFile("tracked.txt", "original", "add tracked");
        // A tracked modification and a tracked deletion.
        write("tracked.txt", "modified");
        assertTrue(service.discardChanges("tracked.txt").isOk());

        write("tracked.txt", "modified again");
        Files.deleteIfExists(tempDir.resolve("tracked.txt"));
        write("untracked.txt", "scratch");

        var result = service.discardAllChanges();
        assertTrue(result.isOk(), () -> "discardAll failed: " + result.getMessage());

        assertEquals("original", Files.readString(tempDir.resolve("tracked.txt")),
                "the deleted tracked file must come back at HEAD's version");
        assertFalse(Files.exists(tempDir.resolve("untracked.txt")),
                "untracked files are what the trash icon promises to remove");
        assertFalse(service.hasUncommittedChanges(),
                "after discarding everything the work tree must be clean");
    }

    @Test
    void detachingHeadAndReattachingItViaCreateBranch() throws Exception {
        commitFile("a.txt", "a", "first");
        String first;
        try (Git git = Git.open(tempDir.toFile())) {
            first = git.getRepository().resolve("HEAD").getName();
        }
        commitFile("b.txt", "b", "second");

        assertFalse(service.isHeadDetached(), "starts on a branch");

        // Checkout an older commit directly - the detached state.
        assertTrue(service.checkoutBranch(first).isOk());
        assertTrue(service.isHeadDetached(),
                "checking out a commit must leave HEAD detached");

        // The recovery gesture: branch here re-attaches HEAD.
        assertTrue(service.createBranch("rescued", null, true).isOk());
        assertFalse(service.isHeadDetached(),
                "branching from here re-attaches HEAD");
        assertEquals("rescued", service.getCurrentBranch());
    }

    @Test
    void detachedHeadOrphanRiskIsFalseWhileAttached() throws Exception {
        commitFile("a.txt", "a", "first");
        assertFalse(service.isDetachedHeadOrphanRisk(), "attached HEAD is never at risk");
    }

    @Test
    void detachedHeadOnACommitNoRefReachesIsAnOrphanRisk() throws Exception {
        commitFile("a.txt", "a", "first");
        String first;
        try (Git git = Git.open(tempDir.toFile())) {
            first = git.getRepository().resolve("HEAD").getName();
        }

        // Detach, then commit here: this new commit is on no branch and no tag, so
        // it is exactly the "would be stranded" case - unlike checking out an older
        // commit that main still contains, which is never actually at risk.
        assertTrue(service.checkoutBranch(first).isOk());
        commitFile("b.txt", "b", "orphan candidate");

        assertTrue(service.isHeadDetached());
        assertTrue(service.isDetachedHeadOrphanRisk(),
                "no branch or tag is an ancestor of a commit made only while detached");
    }

    @Test
    void detachedHeadStillReachableFromATagIsNotAnOrphanRisk() throws Exception {
        commitFile("a.txt", "a", "first");
        String tagged;
        try (Git git = Git.open(tempDir.toFile())) {
            tagged = git.getRepository().resolve("HEAD").getName();
        }
        assertTrue(service.createTag("keepsake", null, tagged).isOk());
        commitFile("b.txt", "b", "second");

        assertTrue(service.checkoutBranch(tagged).isOk());
        assertTrue(service.isHeadDetached());
        assertFalse(service.isDetachedHeadOrphanRisk(),
                "the tag still reaches this commit, so leaving it costs nothing");
    }

    @Test
    void pushedStateFollowsTheRemoteTrackingRefs() throws Exception {
        commitFile("a.txt", "a", "first");
        String first;
        try (Git git = Git.open(tempDir.toFile())) {
            first = git.getRepository().resolve("HEAD").getName();
        }
        commitFile("b.txt", "b", "second");

        // Nothing remote exists yet: neither commit counts as pushed.
        assertFalse(service.getCommitPosition(first).pushed(), "no remotes, nothing pushed");
        try (Git git = Git.open(tempDir.toFile())) {
            assertFalse(service.getCommitPosition(
                    git.getRepository().resolve("HEAD").getName()).pushed());
        }

        // Point a remote-tracking ref at the first commit only — as it would look
        // after pushing the first commit and committing the second locally. A raw
        // RefUpdate, because branchCreate would force the name under refs/heads/.
        try (Git git = Git.open(tempDir.toFile())) {
            org.eclipse.jgit.lib.RefUpdate update = git.getRepository()
                    .updateRef("refs/remotes/origin/main");
            update.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(first));
            org.eclipse.jgit.lib.RefUpdate.Result result = update.update();
            assertNotEquals(org.eclipse.jgit.lib.RefUpdate.Result.REJECTED, result);
        }

        assertTrue(service.getCommitPosition(first).pushed(),
                "a remote-tracking ref reaches the first commit");
        try (Git git = Git.open(tempDir.toFile())) {
            assertFalse(service.getCommitPosition(git.getRepository().resolve("HEAD").getName()).pushed(),
                    "the local-only second commit is not on any remote");
        }
    }

    @Test
    void fetchWithoutRemotesFails() {
        GitResult result = service.fetchAll();
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("No remotes"));
    }

    // ------------------------------------------------------------------
    // fetchAllSilently (background auto-fetch)
    // ------------------------------------------------------------------

    /**
     * A bare local repository needs no credentials at all, so JGit's transport never
     * calls {@link CredentialsManager#get}. This is the proof that {@link
     * GitService#fetchAllSilently()} is not merely "always fails" - it must still
     * complete a fetch that was never going to prompt in the first place.
     */
    @Test
    void fetchAllSilentlySucceedsAgainstARemoteThatNeedsNoCredentials() throws Exception {
        Path remoteDir = Files.createTempDirectory("lyra-silent-fetch-remote");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            commitFile("a.txt", "a", "first");
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());

            GitResult result = service.fetchAllSilently();
            assertTrue(result.isOk(), () -> "expected success, got: " + result.getMessage());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    // ------------------------------------------------------------------
    // Remote URLs and pull-request checkout
    // ------------------------------------------------------------------

    @Test
    void getRemoteUrlReturnsTheConfiguredUri() {
        assertTrue(service.addRemote("origin", "https://example.invalid/repo.git").isOk());
        assertEquals("https://example.invalid/repo.git", service.getRemoteUrl("origin"));
    }

    @Test
    void getRemoteUrlIsNullForAnUnknownRemote() {
        assertNull(service.getRemoteUrl("origin"));
    }

    /**
     * GitHub publishes every pull request's head commit at {@code refs/pull/<n>/head} on
     * the remote, regardless of whether its source branch still exists - simulated here
     * with a raw {@code RefUpdate} on a bare local "remote", the same technique {@link
     * #pushedStateFollowsTheRemoteTrackingRefs} uses for a remote-tracking ref. The local
     * repo is rolled back afterward so the PR's commit is reachable only through that ref,
     * proving {@code checkoutPullRequest} actually fetches it rather than finding it
     * already present.
     */
    @Test
    void checkoutPullRequestFetchesTheHeadRefAndChecksItOutAsALocalBranch() throws Exception {
        commitFile("a.txt", "a", "first");
        String first;
        try (Git git = Git.open(tempDir.toFile())) {
            first = git.getRepository().resolve("HEAD").getName();
        }
        commitFile("b.txt", "b", "pr head");
        String prHead;
        try (Git git = Git.open(tempDir.toFile())) {
            prHead = git.getRepository().resolve("HEAD").getName();
        }

        Path remoteDir = Files.createTempDirectory("lyra-pr-remote");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());

            try (Git bare = Git.open(remoteDir.toFile())) {
                org.eclipse.jgit.lib.RefUpdate update = bare.getRepository().updateRef("refs/pull/7/head");
                update.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(prHead));
                org.eclipse.jgit.lib.RefUpdate.Result result = update.update();
                assertNotEquals(org.eclipse.jgit.lib.RefUpdate.Result.REJECTED, result);
            }

            assertTrue(service.reset(first, ResetMode.HARD).isOk());

            GitResult checkout = service.checkoutPullRequest("origin", 7, false);
            assertTrue(checkout.isOk(), () -> "expected success, got: " + checkout.getMessage());
            assertEquals("pr-7", service.getCurrentBranch());
            try (Git git = Git.open(tempDir.toFile())) {
                assertEquals(prHead, git.getRepository().resolve("HEAD").getName());
            }
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    @Test
    void checkoutPullRequestCanBeRepeatedOnAnAlreadyCheckedOutPullRequest() throws Exception {
        commitFile("a.txt", "a", "first");
        commitFile("b.txt", "b", "pr head");
        String prHead;
        try (Git git = Git.open(tempDir.toFile())) {
            prHead = git.getRepository().resolve("HEAD").getName();
        }

        Path remoteDir = Files.createTempDirectory("lyra-pr-remote-repeat");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());

            try (Git bare = Git.open(remoteDir.toFile())) {
                org.eclipse.jgit.lib.RefUpdate update = bare.getRepository().updateRef("refs/pull/3/head");
                update.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(prHead));
                update.update();
            }

            assertTrue(service.checkoutPullRequest("origin", 3, false).isOk());
            assertTrue(service.checkoutBranch(service.getLocalBranches().stream()
                    .map(r -> r.shortName()).filter(n -> !n.equals("pr-3")).findFirst().orElseThrow()).isOk());

            GitResult second = service.checkoutPullRequest("origin", 3, false);
            assertTrue(second.isOk(), () -> "expected success, got: " + second.getMessage());
            assertEquals("pr-3", service.getCurrentBranch());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    /**
     * {@link GitService#checkoutRemoteRef} is the general form {@link
     * GitService#checkoutPullRequest} is built on - this pins the shape GitLab's own
     * numbered ref ({@code refs/merge-requests/<iid>/head}) needs, fetched from a
     * configured remote by name.
     */
    @Test
    void checkoutRemoteRefFetchesAnArbitraryRefFromAConfiguredRemote() throws Exception {
        commitFile("a.txt", "a", "first");
        String first;
        try (Git git = Git.open(tempDir.toFile())) {
            first = git.getRepository().resolve("HEAD").getName();
        }
        commitFile("b.txt", "b", "mr head");
        String mrHead;
        try (Git git = Git.open(tempDir.toFile())) {
            mrHead = git.getRepository().resolve("HEAD").getName();
        }

        Path remoteDir = Files.createTempDirectory("lyra-mr-remote");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());

            try (Git bare = Git.open(remoteDir.toFile())) {
                org.eclipse.jgit.lib.RefUpdate update = bare.getRepository().updateRef("refs/merge-requests/7/head");
                update.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(mrHead));
                org.eclipse.jgit.lib.RefUpdate.Result result = update.update();
                assertNotEquals(org.eclipse.jgit.lib.RefUpdate.Result.REJECTED, result);
            }

            assertTrue(service.reset(first, ResetMode.HARD).isOk());

            GitResult checkout = service.checkoutRemoteRef("origin", "refs/merge-requests/7/head", "mr-7", false);
            assertTrue(checkout.isOk(), () -> "expected success, got: " + checkout.getMessage());
            assertEquals("mr-7", service.getCurrentBranch());
            try (Git git = Git.open(tempDir.toFile())) {
                assertEquals(mrHead, git.getRepository().resolve("HEAD").getName());
            }
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    /**
     * The other real use {@link GitService#checkoutRemoteRef} exists for: Bitbucket
     * Cloud has no numbered ref for a pull request the way GitHub/GitLab do, so a PR
     * from a fork has to be fetched straight from the fork's own clone URL - with no
     * remote configured for it at all, proven here against a second, independent bare
     * repository this test's own working repo has never heard of.
     */
    @Test
    void checkoutRemoteRefCanFetchDirectlyFromARawUrlWithNoConfiguredRemote() throws Exception {
        commitFile("a.txt", "a", "first");

        Path forkDir = Files.createTempDirectory("lyra-fork-remote");
        try {
            try (Git fork = Git.init().setDirectory(forkDir.toFile()).call()) {
                var config = fork.getRepository().getConfig();
                config.setString("user", null, "name", "Fork Author");
                config.setString("user", null, "email", "fork@example.com");
                config.save();
                Files.writeString(forkDir.resolve("feature.txt"), "feature", StandardCharsets.UTF_8);
                fork.add().addFilepattern("feature.txt").call();
                fork.commit().setMessage("feature work").call();
            }
            String forkHead;
            try (Git fork = Git.open(forkDir.toFile())) {
                forkHead = fork.getRepository().resolve("HEAD").getName();
                String forkBranch = fork.getRepository().getBranch();

                GitResult checkout = service.checkoutRemoteRef(
                        forkDir.toString(), "refs/heads/" + forkBranch, "pr-99", false);
                assertTrue(checkout.isOk(), () -> "expected success, got: " + checkout.getMessage());
            }

            assertEquals("pr-99", service.getCurrentBranch());
            try (Git git = Git.open(tempDir.toFile())) {
                assertEquals(forkHead, git.getRepository().resolve("HEAD").getName());
            }
        } finally {
            deleteRecursively(forkDir);
        }
    }

    /**
     * {@code fetchRemoteRef} is {@code checkoutRemoteRef} minus the checkout half -
     * makes a PR/MR's commit locally resolvable (so its diff can be computed and
     * reviewed) without disturbing whatever branch and working tree the user
     * currently has checked out. Proven both ways: the fetched commit becomes
     * resolvable, and HEAD/the current branch/the working tree stay exactly where
     * they started.
     */
    @Test
    void fetchRemoteRefMakesTheCommitResolvableWithoutTouchingHeadOrTheWorkingTree() throws Exception {
        commitFile("a.txt", "a", "first");
        String originalHead;
        String originalBranch = service.getCurrentBranch();
        try (Git git = Git.open(tempDir.toFile())) {
            originalHead = git.getRepository().resolve("HEAD").getName();
        }

        Path remoteDir = Files.createTempDirectory("lyra-fetch-only-remote");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());

            // A commit that exists on the remote but not yet locally - the same shape
            // a PR's own head commit has before it is reviewed.
            Path otherClone = Files.createTempDirectory("lyra-fetch-only-other-clone");
            String remoteOnlyCommit;
            try {
                try (Git clone = Git.cloneRepository().setURI(remoteDir.toString())
                        .setDirectory(otherClone.toFile()).call()) {
                    var config = clone.getRepository().getConfig();
                    config.setString("user", null, "name", "Other Author");
                    config.setString("user", null, "email", "other@example.com");
                    config.save();
                    Files.writeString(otherClone.resolve("pr.txt"), "pr change", StandardCharsets.UTF_8);
                    clone.add().addFilepattern("pr.txt").call();
                    clone.commit().setMessage("pr commit").call();
                    clone.push().call();
                    remoteOnlyCommit = clone.getRepository().resolve("HEAD").getName();
                }
            } finally {
                deleteRecursively(otherClone);
            }

            GitResult fetch = service.fetchRemoteRef("origin", "refs/heads/" + originalBranch);
            assertTrue(fetch.isOk(), () -> "expected success, got: " + fetch.getMessage());

            try (Git git = Git.open(tempDir.toFile())) {
                assertEquals(remoteOnlyCommit,
                        git.getRepository().resolve(GitBackend.INTERNAL_REF_PREFIX + "review/fetched").getName(),
                        "the fetched commit should be resolvable");
                assertEquals(originalHead, git.getRepository().resolve("HEAD").getName(),
                        "HEAD must not move");
            }
            assertEquals(originalBranch, service.getCurrentBranch(), "the current branch must not change");
            assertFalse(Files.exists(tempDir.resolve("pr.txt")),
                    "the working tree must not be touched - this was a fetch, not a checkout");
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    // ------------------------------------------------------------------
    // Bisect
    // ------------------------------------------------------------------

    /**
     * Commits a linear chain of {@code count} commits, the file reading "good" up to
     * (and not including) {@code firstBadIndex}, "bad" from there on - so bisecting
     * between the first and last commit has one, unambiguous right answer to find.
     *
     * @return the hash of commit {@code firstBadIndex} (0-based), the answer a correct
     *         bisect must land on
     */
    private String commitChainWithABugPartway(int count, int firstBadIndex) throws Exception {
        String firstBadHash = null;
        for (int i = 0; i < count; i++) {
            // Distinct content every commit (the index is embedded), or two consecutive
            // "good" - or "bad" - writes are identical and the second has nothing to
            // stage, since only the marker word actually needs to stay stable.
            String marker = i >= firstBadIndex ? "bad" : "good";
            write("f.txt", marker + " " + i);
            assertTrue(service.stageFile("f.txt").isOk());
            assertTrue(service.commit("commit " + i, false).isOk());
            if (i == firstBadIndex) {
                firstBadHash = resolveHead();
            }
        }
        return firstBadHash;
    }

    private String resolveHead() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    /** Drives a bisect session to completion the way a user actually would: read the file, mark, repeat. */
    private BisectStatus runBisectToCompletion(String goodHash, String badHash) throws Exception {
        assertTrue(service.bisectStart().isOk());
        BisectStatus status = service.bisectMarkBad(badHash);
        assertTrue(status.active());
        status = service.bisectMarkGood(goodHash);

        int guard = 0;
        while (!status.done()) {
            assertTrue(++guard < 50, "bisect did not converge - likely an infinite loop in pickNext");
            String content = Files.readString(tempDir.resolve("f.txt")).strip();
            status = content.startsWith("bad") ? service.bisectMarkBad(status.currentCommitHash())
                    : service.bisectMarkGood(status.currentCommitHash());
        }
        return status;
    }

    @Test
    void bisectFindsTheExactFirstBadCommitOnALinearHistory() throws Exception {
        write("f.txt", "seed");
        assertTrue(service.stageFile("f.txt").isOk());
        assertTrue(service.commit("seed", false).isOk());
        String goodHash = resolveHead();

        String firstBad = commitChainWithABugPartway(15, 7);
        String badHash = resolveHead();

        BisectStatus result = runBisectToCompletion(goodHash, badHash);

        assertTrue(result.done());
        assertEquals(firstBad, result.currentCommitHash(),
                "bisect should converge on exactly the commit that introduced the bug");

        // Back on the original branch, not left detached on some candidate.
        assertTrue(service.bisectReset().isOk());
        assertFalse(service.isHeadDetached());
    }

    @Test
    void bisectWithOnlyABadMarkWaitsForAGoodOne() throws Exception {
        commitChainWithABugPartway(5, 2);
        assertTrue(service.bisectStart().isOk());

        BisectStatus status = service.bisectMarkBad(resolveHead());

        assertTrue(status.active());
        assertFalse(status.done());
        assertNull(status.currentCommitHash(), "no candidate exists until a good commit is also marked");
    }

    @Test
    void bisectStatusReflectsTheCurrentCandidateWithoutAdvancingIt() throws Exception {
        write("f.txt", "good");
        assertTrue(service.stageFile("f.txt").isOk());
        assertTrue(service.commit("good", false).isOk());
        String goodHash = resolveHead();
        commitChainWithABugPartway(9, 4);
        String badHash = resolveHead();

        assertTrue(service.bisectStart().isOk());
        service.bisectMarkBad(badHash);
        BisectStatus afterMark = service.bisectMarkGood(goodHash);

        BisectStatus queried = service.bisectStatus();

        assertEquals(afterMark.currentCommitHash(), queried.currentCommitHash(),
                "asking for status must not itself pick a new candidate");
    }

    @Test
    void bisectResetReturnsToTheOriginalBranchWithoutFinishing() throws Exception {
        String originalBranch = service.getCurrentBranch();
        commitChainWithABugPartway(5, 2);
        String badHash = resolveHead();

        assertTrue(service.bisectStart().isOk());
        service.bisectMarkBad(badHash);

        GitResult reset = service.bisectReset();

        assertTrue(reset.isOk());
        assertEquals(originalBranch, service.getCurrentBranch());
        assertFalse(service.isHeadDetached());
    }

    @Test
    void bisectResetWithNoActiveSessionFails() {
        GitResult result = service.bisectReset();
        assertFalse(result.isOk());
    }

    @Test
    void bisectSkipTriesADifferentCandidateWithoutMarkingItGoodOrBad() throws Exception {
        write("f.txt", "seed");
        assertTrue(service.stageFile("f.txt").isOk());
        assertTrue(service.commit("seed", false).isOk());
        String goodHash = resolveHead();
        commitChainWithABugPartway(11, 6);
        String badHash = resolveHead();

        assertTrue(service.bisectStart().isOk());
        service.bisectMarkBad(badHash);
        BisectStatus first = service.bisectMarkGood(goodHash);
        String firstCandidate = first.currentCommitHash();

        BisectStatus afterSkip = service.bisectSkip();

        assertNotEquals(firstCandidate, afterSkip.currentCommitHash(),
                "skipping must move to a different candidate");
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover handle on Windows is not this test's problem.
                }
            });
        }
    }

    /** A diff the engine hands over as bytes, read as the UTF-8 these fixtures write. */
    private static String utf8(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
