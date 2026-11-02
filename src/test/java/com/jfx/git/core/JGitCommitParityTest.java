package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A JGit commit made the way {@code git commit} would make it (E3, C3, E7, E9).
 *
 * <p>Every case is a real repository and a real commit. The configuration that matters
 * lives in the repository's own config, or in a scratch "global" config reached through
 * {@code GIT_CONFIG_GLOBAL} for git and a replaced {@link SystemReader} for JGit - never
 * the developer's own {@code ~/.gitconfig}, which these tests neither read nor write.
 */
class JGitCommitParityTest {

    @TempDir
    Path root;

    private Path repoDir;
    private GitService service;
    private Path emptyGlobal;
    private SystemReader originalReader;

    @BeforeEach
    void setUp() throws Exception {
        originalReader = SystemReader.getInstance();
        emptyGlobal = root.resolve("empty-global.gitconfig");
        Files.writeString(emptyGlobal, "");
        repoDir = root.resolve("repo");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(repoDir.toFile());
        commitFile("a.txt", "one\n", "first");
    }

    @AfterEach
    void tearDown() {
        EffectiveConfig.gitAvailable = NativeGitRunner::isGitInstalled;
        EffectiveConfig.gitEnvironment = Map.of();
        SystemReader.setInstance(originalReader);
        if (service != null) service.close();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void write(String name, String content) throws Exception {
        Path file = repoDir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void commitFile(String name, String content, String message) throws Exception {
        write(name, content);
        assertTrue(service.stageFile(name).isOk());
        GitResult result = service.commit(message, false);
        assertTrue(result.isOk(), result.getMessage());
    }

    private RevCommit head() throws Exception {
        try (Git git = Git.open(repoDir.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve("HEAD"));
        }
    }

    /** Appends raw text to the repository's own config, for sections JGit would not write. */
    private void appendRepoConfig(String text) throws Exception {
        Path config = repoDir.resolve(".git/config");
        Files.writeString(config, Files.readString(config) + text);
        service.close();
        service = new GitService();
        service.openRepository(repoDir.toFile());
    }

    private void removeRepoIdentity() throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            var config = git.getRepository().getConfig();
            config.unsetSection("user", null);
            config.save();
        }
        service.close();
        service = new GitService();
        service.openRepository(repoDir.toFile());
    }

    /** JGit's user and system config replaced by empty scratch files. */
    private void isolateJGitFromTheRealGlobalConfig() {
        SystemReader.setInstance(new SystemReader.Delegate(originalReader) {
            @Override
            public FileBasedConfig openUserConfig(org.eclipse.jgit.lib.Config parent, FS fs) {
                return new FileBasedConfig(parent, emptyGlobal.toFile(), fs);
            }

            @Override
            public FileBasedConfig openSystemConfig(org.eclipse.jgit.lib.Config parent, FS fs) {
                return new FileBasedConfig(parent, root.resolve("empty-system.gitconfig").toFile(), fs);
            }

            @Override
            public String getenv(String variable) {
                // GIT_AUTHOR_NAME and friends on the build machine would make any
                // identity look configured.
                return variable.startsWith("GIT_") || variable.equals("EMAIL")
                        ? null : super.getenv(variable);
            }
        });
    }

    private static String forGitConfig(Path path) {
        return path.toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------
    // E3: identity and signing, includeIf and all
    // ------------------------------------------------------------------

    /**
     * The E3 scenario: the identity comes from a conditionally included file. JGit
     * alone would record the repository's plain user.email; git reads the include.
     */
    @Test
    void aConditionallyIncludedIdentityIsTheOneCommitted() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "resolving includeIf needs git");
        Path work = root.resolve("work.gitconfig");
        Files.writeString(work, "[user]\n\temail = work@example.com\n\tname = Work Self\n");
        appendRepoConfig("[includeIf \"onbranch:main\"]\n\tpath = " + forGitConfig(work) + "\n");

        commitFile("b.txt", "b\n", "as the work self");

        PersonIdent author = head().getAuthorIdent();
        assertEquals("work@example.com", author.getEmailAddress());
        assertEquals("Work Self", author.getName());
        assertEquals("work@example.com", head().getCommitterIdent().getEmailAddress());
    }

    @Test
    void signingTurnedOnInAConditionalIncludeRefusesTheCommit() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "resolving includeIf needs git");
        Path work = root.resolve("sign.gitconfig");
        Files.writeString(work, "[commit]\n\tgpgsign = true\n[gpg]\n\tformat = ssh\n");
        appendRepoConfig("[includeIf \"onbranch:main\"]\n\tpath = " + forGitConfig(work) + "\n");
        String before = head().getName();
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());

        GitResult result = service.commit("must not be unsigned", false);

        assertFalse(result.isOk(), "JGit cannot sign, so the commit must be refused");
        assertTrue(result.getMessage().contains("gpgsign"), result.getMessage());
        assertTrue(result.getMessage().contains("ssh"), result.getMessage());
        assertEquals(before, head().getName());
    }

    /**
     * Merge, cherry-pick and the rest commit through JGit's own commands, which read the
     * identity the way JGit does - includeIf dropped. They must commit as git would, and
     * leave nothing of that behind in the repository's config.
     */
    @Test
    void mergesAndCherryPicksCommitAsTheConditionallyIncludedIdentity() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "resolving includeIf needs git");
        assertTrue(service.createBranch("side", null, true).isOk());
        commitFile("side.txt", "s\n", "on side");
        commitFile("pick.txt", "p\n", "to pick");
        String toPick = head().getName();
        assertTrue(service.checkoutBranch("main").isOk());
        commitFile("main.txt", "m\n", "on main");
        Path work = root.resolve("work.gitconfig");
        Files.writeString(work, "[user]\n\temail = work@example.com\n\tname = Work Self\n");
        appendRepoConfig("[includeIf \"onbranch:main\"]\n\tpath = " + forGitConfig(work) + "\n");
        String config = Files.readString(repoDir.resolve(".git/config"));

        GitResult picked = service.cherryPick(toPick, 0);
        assertTrue(picked.isOk(), picked.getMessage());
        assertEquals("work@example.com", head().getCommitterIdent().getEmailAddress(),
                "a cherry-pick keeps its author but is committed by whoever picks it");

        GitResult merged = service.merge("side", com.jfx.git.model.MergeMode.NO_FF, null);
        assertTrue(merged.isOk(), merged.getMessage());
        assertEquals("work@example.com", head().getCommitterIdent().getEmailAddress());
        assertEquals("Work Self", head().getAuthorIdent().getName());

        assertEquals(config, Files.readString(repoDir.resolve(".git/config")),
                "the identity stands in memory for the call only, never in the file");
    }

    /**
     * Where a commit cannot be made as git would - signing required here - an operation
     * that needs one is refused, but a fast-forward still goes ahead, as it does in git.
     */
    @Test
    void whenNoCommitCanBeMadeOnlyFastForwardsGoAhead() throws Exception {
        assertTrue(service.createBranch("ahead", null, true).isOk());
        commitFile("ahead.txt", "a\n", "ahead");
        String aheadTip = head().getName();
        assertTrue(service.checkoutBranch("main").isOk());
        assertTrue(service.createBranch("other", null, true).isOk());
        commitFile("other.txt", "o\n", "other");
        String other = head().getName();
        assertTrue(service.checkoutBranch("main").isOk());
        String start = head().getName();
        appendRepoConfig("[commit]\n\tgpgsign = true\n");

        GitResult picked = service.cherryPick(other, 0);
        assertFalse(picked.isOk(), "a cherry-pick always commits, and this one could not be signed");
        assertTrue(picked.getMessage().contains("gpgsign"), picked.getMessage());
        assertEquals(start, head().getName());

        GitResult forced = service.merge("other", com.jfx.git.model.MergeMode.NO_FF, null);
        assertFalse(forced.isOk(), "--no-ff always makes a merge commit");
        assertEquals(start, head().getName());

        GitResult fastForward = service.merge("ahead", com.jfx.git.model.MergeMode.DEFAULT, null);
        assertTrue(fastForward.isOk(), "a fast-forward makes no commit: " + fastForward.getMessage());
        assertEquals(aheadTip, head().getName());
    }

    /** Without git, an includeIf JGit cannot evaluate is a reason to stop, not to guess. */
    @Test
    void withoutGitAnApplicableIncludeIfRefusesTheCommit() throws Exception {
        EffectiveConfig.gitAvailable = () -> false;
        appendRepoConfig("[includeIf \"onbranch:main\"]\n\tpath = elsewhere.gitconfig\n");
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());

        GitResult result = service.commit("unknowable identity", false);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("includeIf"), result.getMessage());
    }

    /** A gitdir: condition for some other directory cannot apply, so it is no reason to refuse. */
    @Test
    void withoutGitAnIncludeIfForAnotherDirectoryDoesNotBlock() throws Exception {
        EffectiveConfig.gitAvailable = () -> false;
        appendRepoConfig("[includeIf \"gitdir:" + forGitConfig(root.resolve("elsewhere")) + "/\"]\n"
                + "\tpath = elsewhere.gitconfig\n");

        commitFile("b.txt", "b\n", "unaffected");
    }

    @Test
    void noConfiguredIdentityIsRefusedRatherThanInvented_withGit() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs git");
        EffectiveConfig.gitEnvironment = Map.of(
                "GIT_CONFIG_GLOBAL", emptyGlobal.toString(), "GIT_CONFIG_NOSYSTEM", "1");
        assumeTrue(System.getenv("GIT_AUTHOR_NAME") == null && System.getenv("EMAIL") == null,
                "the build environment supplies an identity");
        removeRepoIdentity();
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());

        GitResult result = service.commit("who am I", false);

        assertFalse(result.isOk(), "git refuses without an identity, and so must this");
        assertTrue(result.getMessage().contains("user.email"), result.getMessage());
    }

    @Test
    void noConfiguredIdentityIsRefusedRatherThanInvented_withoutGit() throws Exception {
        EffectiveConfig.gitAvailable = () -> false;
        isolateJGitFromTheRealGlobalConfig();
        removeRepoIdentity();
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());

        GitResult result = service.commit("who am I", false);

        assertFalse(result.isOk(), () -> "JGit would have invented an identity: " + result.getMessage());
        assertTrue(result.getMessage().contains("identity"), result.getMessage());
    }

    // ------------------------------------------------------------------
    // C3: the commit itself
    // ------------------------------------------------------------------

    @Test
    void theMessageIsWhitespaceCleanedLikeGitCommitDashM() throws Exception {
        commitFile("b.txt", "b\n", "\n\nsubject   \n\n\n\nbody line  \n\n");

        assertEquals("subject\n\nbody line\n", head().getFullMessage());
    }

    @Test
    void commitCleanupVerbatimIsHonoured() throws Exception {
        appendRepoConfig("[commit]\n\tcleanup = verbatim\n");

        commitFile("b.txt", "b\n", "subject   \n\n\n# kept\n");

        assertEquals("subject   \n\n\n# kept\n", head().getFullMessage());
    }

    @Test
    void commitCleanupStripRemovesCommentLines() throws Exception {
        appendRepoConfig("[commit]\n\tcleanup = strip\n");

        commitFile("b.txt", "b\n", "subject\n# a comment\nbody\n");

        assertEquals("subject\nbody\n", head().getFullMessage());
    }

    /** A message-only amend keeps working: JGit's own emptiness check would refuse it. */
    @Test
    void aMessageOnlyAmendIsAllowed() throws Exception {
        commitFile("b.txt", "b\n", "tyop");
        String tree = head().getTree().getName();

        GitResult result = service.commit("typo fixed", true);

        assertTrue(result.isOk(), result.getMessage());
        assertEquals("typo fixed", head().getFullMessage().strip());
        assertEquals(tree, head().getTree().getName());
    }

    /** Git's rule: an amend that would leave the commit identical to its parent is refused. */
    @Test
    void anAmendThatWouldEmptyTheCommitIsRefused() throws Exception {
        commitFile("b.txt", "b\n", "add b");
        try (Git git = Git.open(repoDir.toFile())) {
            git.rm().addFilepattern("b.txt").call();
        }

        GitResult result = service.commit("add b", true);

        assertFalse(result.isOk(), "the amended commit would change nothing");
        assertTrue(result.getMessage().contains("empty"), result.getMessage());
    }

    /**
     * A resolved cherry-pick whose result equals HEAD is empty, and git refuses it
     * ("The previous cherry-pick is now empty"); JGit's default allowed it.
     */
    @Test
    void anEmptyCommitMidCherryPickIsRefused() throws Exception {
        String before = head().getName();
        Files.writeString(repoDir.resolve(".git/CHERRY_PICK_HEAD"), before + "\n");

        GitResult result = service.commit("empty pick", false);

        assertFalse(result.isOk(), "an empty cherry-pick commit must be refused");
        assertEquals(before, head().getName());
    }

    /** Git keeps the picked commit's author when a conflicted cherry-pick is concluded. */
    @Test
    void concludingACherryPickKeepsTheOriginalAuthor() throws Exception {
        String base = head().getName();
        try (Git git = Git.open(repoDir.toFile())) {
            git.checkout().setCreateBranch(true).setName("side").call();
            Files.writeString(repoDir.resolve("a.txt"), "side\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("side change")
                    .setAuthor(new PersonIdent("Original Author", "original@example.com"))
                    .call();
            String picked = git.getRepository().resolve("HEAD").getName();
            git.checkout().setName("main").call();
            Files.writeString(repoDir.resolve("a.txt"), "main\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("main change").call();
            var pick = git.cherryPick().include(git.getRepository().resolve(picked)).call();
            assertEquals(org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.CONFLICTING, pick.getStatus());
        }
        assertNotNull(base);
        write("a.txt", "resolved\n");
        assertTrue(service.stageFile("a.txt").isOk());

        GitResult result = service.commit("side change", false);

        assertTrue(result.isOk(), result.getMessage());
        assertEquals("original@example.com", head().getAuthorIdent().getEmailAddress());
        assertEquals("test@example.com", head().getCommitterIdent().getEmailAddress());
    }

    @Test
    void aNonUtf8CommitEncodingIsRefused() throws Exception {
        appendRepoConfig("[i18n]\n\tcommitEncoding = ISO-8859-1\n");
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());

        GitResult result = service.commit("café", false);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("ISO-8859-1"), result.getMessage());
    }

    @Test
    void aPrepareCommitMsgHookRefusesTheCommitUnlessHooksAreSkipped() throws Exception {
        Path hook = repoDir.resolve(".git/hooks/prepare-commit-msg");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\necho added >> \"$1\"\n");
        hook.toFile().setExecutable(true);
        write("b.txt", "b\n");
        assertTrue(service.stageFile("b.txt").isOk());

        GitResult refused = service.commit("needs the hook", false);
        assertFalse(refused.isOk());
        assertTrue(refused.getMessage().contains("prepare-commit-msg"), refused.getMessage());

        assertTrue(service.commit("hook skipped deliberately", false, true).isOk());
    }

    // ------------------------------------------------------------------
    // E7: hooks directory
    // ------------------------------------------------------------------

    @Test
    void aHooksPathStartingWithATildeIsTheHomeDirectory() throws Exception {
        appendRepoConfig("[core]\n\thooksPath = ~/team-hooks\n");

        assertEquals(new File(System.getProperty("user.home"), "team-hooks"), service.hooksDirectory());
    }

    @Test
    void aLinkedWorktreeListsTheSharedHooks() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "creating a linked worktree needs git");
        Path linked = root.resolve("linked");
        var added = new NativeGitRunner(repoDir.toFile())
                .run("worktree", "add", "-b", "wt", linked.toString());
        assertTrue(added.isOk(), added.stderr());
        Path hook = repoDir.resolve(".git/hooks/pre-push");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\nexit 0\n");
        hook.toFile().setExecutable(true);

        GitService inWorktree = new GitService();
        try {
            inWorktree.openRepository(linked.toFile());
            List<RepoStructureOps.Hook> hooks = inWorktree.getHooks();
            assertEquals(List.of("pre-push"), hooks.stream().map(RepoStructureOps.Hook::name).toList());
        } finally {
            inWorktree.close();
        }
    }

    // ------------------------------------------------------------------
    // E9: sparse checkout, read-only
    // ------------------------------------------------------------------

    @Test
    void sparseCheckoutSetUpByGitIsReportedAndListed() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs git to set sparse checkout up");
        commitFile("dir1/x.txt", "x\n", "dir1");
        commitFile("dir2/y.txt", "y\n", "dir2");
        var set = new NativeGitRunner(repoDir.toFile()).run("sparse-checkout", "set", "--cone", "dir1");
        assertTrue(set.isOk(), set.stderr());

        GitService fresh = new GitService();
        try {
            fresh.openRepository(repoDir.toFile());
            assertTrue(fresh.isSparseCheckoutEnabled());
            assertEquals(List.of("dir1"), fresh.getSparseCheckoutPaths());
        } finally {
            fresh.close();
        }
    }

    @Test
    void coneDirectoriesAreTheRecursiveOnes() {
        assertEquals(List.of("a/b", "c"), GitService.coneDirectories(List.of(
                "/*", "!/*/", "/a/", "!/a/*/", "/a/b/", "/c/")));
    }

    // ------------------------------------------------------------------
    // commit.template
    // ------------------------------------------------------------------

    @Test
    void theCommitTemplateIsRead() throws Exception {
        Path template = root.resolve("template.txt");
        Files.writeString(template, "Subject\n\n# explain why\n");
        appendRepoConfig("[commit]\n\ttemplate = " + forGitConfig(template) + "\n");

        assertEquals("Subject\n\n# explain why\n", service.getCommitTemplate());
    }
}
