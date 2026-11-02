package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JGit engine's refusal to skip a hook it cannot run (C4).
 *
 * <p>JGit hands hooks to the platform {@code FS}, and on Windows without {@code sh} that
 * answers "not supported" and the commit or push carries on as though the hook had
 * passed. {@link GitService} refuses instead - but a machine that has Git for Windows
 * (every machine these tests run on) runs hooks fine, so the refusal was never exercised.
 * {@link GitService#platformRunsHooks} is the probe, replaced here with the answer that
 * machine would give.
 */
class JGitHookGuardTest {

    @TempDir
    Path root;

    private Predicate<Repository> realProbe;
    private GitService service;
    private File work;
    private File remote;

    @BeforeEach
    void setUp() throws Exception {
        realProbe = GitService.platformRunsHooks;
        work = root.resolve("work").toFile();
        remote = root.resolve("remote.git").toFile();
        Git.init().setDirectory(remote).setBare(true).setInitialBranch("main").call().close();
        try (Git git = Git.init().setDirectory(work).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(work.toPath().resolve("a.txt"), "a\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.remoteAdd().setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(remote.toURI().toURL())).call();
        }
        service = new GitService();
        assertTrue(service.openRepository(work));
    }

    @AfterEach
    void tearDown() {
        GitService.platformRunsHooks = realProbe;
        if (service != null) service.close();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void installHook(String name) throws Exception {
        Path hook = work.toPath().resolve(".git/hooks/" + name);
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);
    }

    private String remoteRef(String ref) throws Exception {
        try (Git git = Git.open(remote)) {
            var found = git.getRepository().exactRef(ref);
            return found == null ? null : found.getObjectId().getName();
        }
    }

    /** This machine cannot run hooks for JGit: Windows with no {@code sh}. */
    private static void hooksCannotRun() {
        GitService.platformRunsHooks = repository -> false;
    }

    // ------------------------------------------------------------------

    @Test
    void aPushIsRefusedRatherThanSkippingAPrePushHook() throws Exception {
        installHook("pre-push");
        hooksCannotRun();

        GitResult pushed = new RepositoryCommands(service).push("origin", false, false, true);

        assertFalse(pushed.isOk(), "the push must not go ahead without its hook");
        assertTrue(pushed.getMessage().contains("pre-push hook"), pushed::getMessage);
        assertNull(remoteRef("refs/heads/main"), "nothing reached the remote");
    }

    @Test
    void pushingATagIsRefusedToo() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        installHook("pre-push");
        hooksCannotRun();

        GitResult pushed = service.pushTag("origin", "v1");

        assertFalse(pushed.isOk());
        assertTrue(pushed.getMessage().contains("pre-push hook"), pushed::getMessage);
        assertNull(remoteRef("refs/tags/v1"));
    }

    @Test
    void deletingARemoteTagIsAPushAndIsRefusedToo() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        assertTrue(service.pushTag("origin", "v1").isOk(), "published before any hook exists");
        String published = remoteRef("refs/tags/v1");
        assertNotNull(published);
        installHook("pre-push");
        hooksCannotRun();

        GitResult deleted = service.deleteRemoteTag("origin", "v1");

        assertFalse(deleted.isOk());
        assertTrue(deleted.getMessage().contains("pre-push hook"), deleted::getMessage);
        assertEquals(published, remoteRef("refs/tags/v1"), "the remote tag is still there");
    }

    @Test
    void withNoPrePushHookThePushGoesAheadEvenWhereHooksCannotRun() throws Exception {
        hooksCannotRun();

        GitResult pushed = new RepositoryCommands(service).push("origin", false, false, true);

        assertTrue(pushed.isOk(), pushed::getMessage);
        assertEquals(service.getHeadHash(), remoteRef("refs/heads/main"));
    }

    @Test
    void theCommitGuardUsesTheSameProbe() throws Exception {
        installHook("pre-commit");
        hooksCannotRun();
        Files.writeString(work.toPath().resolve("b.txt"), "b\n");
        assertTrue(service.stageFile("b.txt").isOk());
        String before = service.getHeadHash();

        GitResult refused = service.commit("needs its hook", false);

        assertFalse(refused.isOk());
        assertTrue(refused.getMessage().contains("pre-commit hook"), refused::getMessage);
        assertEquals(before, service.getHeadHash());
        GitResult skipped = service.commit("skips it on purpose", false, true);
        assertTrue(skipped.isOk(), "Skip hooks is the deliberate way past it: " + skipped.getMessage());
    }
}
