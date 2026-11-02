package com.jfx.git.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** How {@link GitCommand} lays out options, revisions and paths - and that git agrees. */
class GitCommandTest {

    @TempDir
    Path dir;

    private NativeGitRunner git;

    @BeforeEach
    void setUp() {
        git = new NativeGitRunner(dir.toFile());
    }

    @Test
    void revisionsComeAfterEndOfOptionsAndPathsAfterTheSeparator() {
        assertEquals(List.of("diff", "-p", "--end-of-options", "a..b", "--", "x", "-y"),
                GitCommand.of("diff", "-p").revs("a..b").paths("x", "-y").args());
    }

    @Test
    void separatorsAppearOnlyWhenThereIsSomethingToSeparate() {
        assertEquals(List.of("status"), GitCommand.of("status").args());
        assertEquals(List.of("add", "--", "f"), GitCommand.of("add").paths("f").args());
        assertEquals(List.of("merge", "--end-of-options", "topic"), GitCommand.of("merge").revs("topic").args());
    }

    @Test
    void endOfRevisionsMakesEveryRevisionHaveToBeOne() {
        assertEquals(List.of("checkout", "--end-of-options", "main", "--"),
                GitCommand.of("checkout").revs("main").endOfRevisions().args());
        assertEquals(List.of("submodule", "update", "--"),
                GitCommand.of("submodule", "update").endOfRevisions().args());
    }

    @Test
    void conditionalOptionsKeepTheirOrder() {
        assertEquals(List.of("revert", "--no-edit", "-m", "2", "--end-of-options", "abc"),
                GitCommand.of("revert", "--no-edit").optsIf(true, "-m", "2").optsIf(false, "-w")
                        .revs("abc").args());
    }

    /** A revision named like an option is looked up, and fails as a revision. */
    @Test
    void gitReadsADashedRevisionAsARevision() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Native Git is not installed on this system");
        assertTrue(git.run("init", "-b", "main").isOk());
        git.run("config", "user.name", "T");
        git.run("config", "user.email", "t@example.com");
        Files.writeString(dir.resolve("f"), "1\n");
        git.run("add", "-A");
        assertTrue(git.run("commit", "-q", "-m", "one").isOk());

        var log = git.run(GitCommand.of("log", "--format=%s").revs("--all").endOfRevisions().args());
        assertFalse(log.isOk(), "--all is a revision here, and there is none of that name");
        var ok = git.run(GitCommand.of("log", "--format=%s").revs("HEAD").paths("f").args());
        assertTrue(ok.isOk(), ok::stderr);
        assertEquals("one", ok.trimmedStdout());
    }
}
