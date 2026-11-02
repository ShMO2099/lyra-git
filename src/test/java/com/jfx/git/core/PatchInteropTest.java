package com.jfx.git.core;

import com.jfx.git.model.PatchApplyMode;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A patch written by one engine and applied by another.
 *
 * <p>The conformance suite has each engine apply what it wrote itself, which would pass
 * if JGit's patches were in a dialect only JGit understood. A patch exists to leave the
 * application: what matters is that real git takes one written here, and that one
 * written by real git is taken here.
 */
class PatchInteropTest {

    @TempDir
    Path root;

    private File source;
    private File target;
    private String base;

    static {
        var windows = new org.eclipse.jgit.storage.file.WindowCacheConfig();
        windows.setPackedGitMMAP(false);
        windows.install();
    }

    @BeforeEach
    void setUp() throws Exception {
        source = root.resolve("source").toFile();
        target = root.resolve("target").toFile();
        try (Git git = Git.init().setDirectory(source).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(source.toPath().resolve("a.txt"), "one\n");
            git.add().addFilepattern("a.txt").call();
            base = git.commit().setMessage("first").call().getName();

            // An author who is not the committer, with a name outside ASCII, and a
            // message with a body: the things a bare diff would lose.
            Files.writeString(source.toPath().resolve("a.txt"), "one\ntwo\n");
            Files.writeString(source.toPath().resolve("new file.txt"), "café\n", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setAuthor(new PersonIdent("Zoë Müller", "zoe@example.org"))
                    .setMessage("Add a second line – and a file\n\nWhy: the body has to travel too.\nOn two lines.\n")
                    .call();
        }
        // The same history up to the base, where the patch will be applied.
        try (Git clone = Git.cloneRepository().setURI(source.toURI().toString()).setDirectory(target).call()) {
            var config = clone.getRepository().getConfig();
            config.setString("user", null, "name", "Someone Else");
            config.setString("user", null, "email", "else@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            clone.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(base).call();
        }
    }

    @AfterEach
    void tearDown() {
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private static RevCommit head(File repository) throws Exception {
        try (Git git = Git.open(repository)) {
            RevCommit commit = git.log().setMaxCount(1).call().iterator().next();
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                return walk.parseCommit(commit.getId());
            }
        }
    }

    @Test
    void aPatchWrittenByJGitBecomesTheSameCommitUnderRealGit() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs a git executable");
        File patch = root.resolve("from-jgit.patch").toFile();
        try (GitService writer = new GitService()) {
            assertTrue(writer.openRepository(source));
            GitResult saved = writer.createPatch(List.of(writer.getHeadHash()), patch);
            assertTrue(saved.isOk(), saved::getMessage);
        }

        NativeGitBackend reader = new NativeGitBackend();
        try {
            assertTrue(reader.openRepository(target));
            GitResult applied = reader.applyPatch(patch, PatchApplyMode.AM);
            assertTrue(applied.isOk(), applied::getMessage);
        } finally {
            reader.close();
        }

        RevCommit commit = head(target);
        assertEquals("Zoë Müller", commit.getAuthorIdent().getName(), "the author, not whoever applied it");
        assertEquals("zoe@example.org", commit.getAuthorIdent().getEmailAddress());
        assertEquals("Someone Else", commit.getCommitterIdent().getName());
        assertEquals("Add a second line – and a file\n\nWhy: the body has to travel too.\nOn two lines.",
                commit.getFullMessage().strip());
        assertEquals("one\ntwo\n", Files.readString(target.toPath().resolve("a.txt")));
        assertEquals("café\n", Files.readString(target.toPath().resolve("new file.txt"), StandardCharsets.UTF_8),
                "a new file, with a space in its name and a character outside ASCII in it");
    }

    @Test
    void aPatchWrittenByRealGitIsAppliedByJGit() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "needs a git executable");
        File patch = root.resolve("from-git.patch").toFile();
        NativeGitBackend writer = new NativeGitBackend();
        try {
            assertTrue(writer.openRepository(source));
            GitResult saved = writer.createPatch(List.of(writer.getHeadHash()), patch);
            assertTrue(saved.isOk(), saved::getMessage);
        } finally {
            writer.close();
        }

        try (GitService reader = new GitService()) {
            assertTrue(reader.openRepository(target));
            GitResult applied = reader.applyPatch(patch, PatchApplyMode.INDEX);
            assertTrue(applied.isOk(), applied::getMessage);

            assertEquals(List.of("a.txt", "new file.txt"),
                    reader.getStagingState().staged().stream().sorted().toList());
            assertEquals(base, reader.getHeadHash(), "staged for a commit of the user's own, not committed");
        }
        assertEquals("one\ntwo\n", Files.readString(target.toPath().resolve("a.txt")));
        assertEquals("café\n", Files.readString(target.toPath().resolve("new file.txt"), StandardCharsets.UTF_8));
    }

    /** JGit cannot encode a binary change, and a patch that left it out would apply as a lie. */
    @Test
    void jgitRefusesToWriteAPatchForABinaryChangeAndRealGitWritesOne() throws Exception {
        byte[] image = new byte[256];
        for (int i = 0; i < image.length; i++) image[i] = (byte) i;
        String binaryCommit;
        try (Git git = Git.open(source)) {
            Files.write(source.toPath().resolve("logo.bin"), image);
            git.add().addFilepattern("logo.bin").call();
            binaryCommit = git.commit().setMessage("add a logo").call().getName();
        }
        File patch = root.resolve("binary.patch").toFile();

        try (GitService jgit = new GitService()) {
            assertTrue(jgit.openRepository(source));
            GitResult refused = jgit.createPatch(List.of(binaryCommit), patch);
            assertFalse(refused.isOk());
            assertTrue(refused.getMessage().contains("logo.bin"), refused.getMessage());
            assertTrue(refused.getMessage().contains("Native Git engine"), refused.getMessage());
            assertFalse(patch.exists(), "nothing is written that looks like a patch and is not one");
        }

        assumeTrue(NativeGitRunner.isGitInstalled(), "needs a git executable");
        NativeGitBackend git = new NativeGitBackend();
        try {
            assertTrue(git.openRepository(source));
            assertTrue(git.createPatch(List.of(binaryCommit), patch).isOk());
            assertTrue(Files.readString(patch.toPath(), StandardCharsets.ISO_8859_1).contains("GIT binary patch"));
        } finally {
            git.close();
        }
    }
}
