package com.jfx.git.core;

import com.jfx.git.model.OperationState;
import com.jfx.git.model.PreparedMessage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The pure pieces behind the in-progress banner and the composer pre-fill: reading which
 * operation is stopped from git's marker files, cleaning git's prepared message, and
 * reading back a stopped step's author. Engine behaviour is pinned in
 * {@link BackendConformanceTest}.
 */
class OperationStateTest {

    @TempDir
    Path gitDir;

    private void touch(String name, String content) throws Exception {
        Path file = gitDir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void aCleanDirectoryHasNothingInProgress() {
        assertFalse(OperationState.detect(gitDir.toFile()).inProgress());
    }

    /**
     * A rebase that stopped on one of its own merges or picks also leaves MERGE_HEAD or
     * CHERRY_PICK_HEAD; it is the rebase that continues, so it wins.
     */
    @Test
    void aRebaseOutranksTheMarkersItsOwnStepsLeave() throws Exception {
        touch("rebase-merge/interactive", "");
        touch("rebase-merge/stopped-sha", "abc1234\n");
        touch("MERGE_HEAD", "def\n");
        touch("CHERRY_PICK_HEAD", "fed\n");

        OperationState state = OperationState.detect(gitDir.toFile());

        assertEquals(OperationState.Kind.REBASING, state.kind());
        assertTrue(state.interactive());
        assertEquals("Interactive rebase", state.label());
        assertEquals("abc1234", state.stoppedAt());
        assertTrue(state.canContinue() && state.canSkip() && state.canAbort());
    }

    @Test
    void anEditStopIsRecognised() throws Exception {
        touch("rebase-merge/amend", "abc\n");
        assertTrue(OperationState.detect(gitDir.toFile()).editStop());
    }

    @Test
    void eachMarkerNamesItsOperation() throws Exception {
        touch("REVERT_HEAD", "abc\n");
        assertEquals(OperationState.Kind.REVERTING, OperationState.detect(gitDir.toFile()).kind());

        touch("MERGE_HEAD", "abc\n");
        OperationState merge = OperationState.detect(gitDir.toFile());
        assertEquals(OperationState.Kind.MERGING, merge.kind());
        assertFalse(merge.canSkip(), "a merge has no step to skip");
    }

    @Test
    void anAmSessionIsNotARebase() throws Exception {
        touch("rebase-apply/applying", "");
        assertEquals(OperationState.Kind.APPLYING_MAILBOX, OperationState.detect(gitDir.toFile()).kind());
    }

    @Test
    void aRangeCherryPickBetweenStepsIsStillInProgress() throws Exception {
        touch("sequencer/todo", "pick 1234567 next one\n");
        assertEquals(OperationState.Kind.CHERRY_PICKING, OperationState.detect(gitDir.toFile()).kind());
    }

    // ------------------------------------------------------------------
    // The sides an operation names, for the conflict panel's headline
    // ------------------------------------------------------------------

    private static final String ONTO = "1111111111111111111111111111111111111111";
    private static final String ORIG = "2222222222222222222222222222222222222222";
    private static final String PICKED = "3333333333333333333333333333333333333333";

    @Test
    void aMergeNamesTheBranchItsMessageNamesAndTheBranchHeadIsOn() throws Exception {
        touch("HEAD", "ref: refs/heads/main\n");
        touch("MERGE_HEAD", PICKED + "\n");
        touch("MERGE_MSG", "Merge branch 'feature/simple' into main\n\n# Conflicts:\n#\ta.txt\n");

        OperationState merge = OperationState.detect(gitDir.toFile());

        assertEquals("feature/simple", merge.incoming());
        assertEquals("main", merge.target());
    }

    @Test
    void aMergeWithAMessageOfItsOwnIsNamedByItsHash() throws Exception {
        touch("HEAD", "ref: refs/heads/main\n");
        touch("MERGE_HEAD", PICKED + "\n");
        touch("MERGE_MSG", "Bring the release fixes back\n");

        assertEquals(PICKED, OperationState.detect(gitDir.toFile()).incoming(),
                "no name to read, so the hash, which the panel names by a branch at it if there is one");
    }

    @Test
    void theMergeMessageForms() {
        assertEquals("origin/main", OperationState.mergedName("Merge remote-tracking branch 'origin/main'"));
        assertEquals("v1.2", OperationState.mergedName("Merge tag 'v1.2'"));
        assertEquals("topic", OperationState.mergedName("Merge branch 'topic' of https://example.com/r.git"));
        assertNull(OperationState.mergedName("Fix the parser"));
    }

    @Test
    void aCherryPickNamesItsCommitAndADetachedHeadByHash() throws Exception {
        touch("HEAD", ORIG + "\n");
        touch("CHERRY_PICK_HEAD", PICKED + "\n");

        OperationState pick = OperationState.detect(gitDir.toFile());

        assertEquals(PICKED, pick.incoming());
        assertEquals(ORIG, pick.target(), "detached: the commit HEAD is on");
    }

    /** Native git keeps the onto name only in the reflog line that started the rebase. */
    @Test
    void aRebaseNamesItsBranchItsOntoFromTheReflogAndItsStep() throws Exception {
        touch("rebase-merge/head-name", "refs/heads/feature\n");
        touch("rebase-merge/onto", ONTO + "\n");
        touch("rebase-merge/msgnum", "3\n");
        touch("rebase-merge/end", "7\n");
        touch("logs/HEAD", ORIG + " " + ONTO + " T <t@example.com> 1700000000 +0000\tcheckout: moving from x to y\n"
                + ORIG + " " + ONTO + " T <t@example.com> 1700000001 +0000\trebase (start): checkout main\n"
                + ONTO + " " + PICKED + " T <t@example.com> 1700000002 +0000\trebase (pick): first\n");

        OperationState rebase = OperationState.detect(gitDir.toFile());

        assertEquals("feature", rebase.incoming());
        assertEquals("main", rebase.target());
        assertEquals(3, rebase.step());
        assertEquals(7, rebase.steps());
    }

    /** An older rebase's start line is not this one's: it moved HEAD somewhere else. */
    @Test
    void anOlderRebasesReflogLineIsNotBelieved() throws Exception {
        touch("rebase-merge/head-name", "refs/heads/feature\n");
        touch("rebase-merge/onto", ONTO + "\n");
        touch("logs/HEAD", ORIG + " " + PICKED + " T <t@example.com> 1700000001 +0000\trebase (start): checkout develop\n");

        assertEquals(ONTO, OperationState.detect(gitDir.toFile()).target());
    }

    /** JGit writes the onto name, and no step counters; the todo lists count instead. */
    @Test
    void aJGitRebaseNamesItsOntoAndCountsItsTodoLists() throws Exception {
        touch("rebase-merge/head-name", "refs/heads/feature\n");
        touch("rebase-merge/onto", ONTO + "\n");
        touch("rebase-merge/onto_name", "refs/heads/main\n");
        touch("rebase-merge/done", "pick 1234567 one\npick 2345678 two\n");
        touch("rebase-merge/git-rebase-todo", "# comment\npick 3456789 three\n\n");

        OperationState rebase = OperationState.detect(gitDir.toFile());

        assertEquals("main", rebase.target());
        assertEquals(2, rebase.step());
        assertEquals(3, rebase.steps());
    }

    @Test
    void aRebaseOfADetachedHeadNamesTheCommitItStartedFrom() throws Exception {
        touch("rebase-merge/head-name", "detached HEAD\n");
        touch("rebase-merge/orig-head", ORIG + "\n");

        assertEquals(ORIG, OperationState.detect(gitDir.toFile()).incoming());
    }

    @Test
    void bisectIsInProgressButNotStopped() throws Exception {
        touch("lyra-bisect", "start main\n");
        OperationState state = OperationState.detect(gitDir.toFile());
        assertEquals(OperationState.Kind.BISECTING, state.kind());
        assertFalse(state.canContinue());
    }

    // ------------------------------------------------------------------
    // C2: the prepared message
    // ------------------------------------------------------------------

    @Test
    void theConflictSummaryIsDroppedAndHashLinesAreKept() {
        String raw = "Fix the parser\n\n#123 root cause\n\n# Conflicts:\n#\ta.txt\n#\tb.txt\n";
        assertEquals("Fix the parser\n\n#123 root cause", PreparedMessage.clean(raw));
    }

    @Test
    void theOlderUncommentedSummaryIsDroppedToo() {
        String raw = "Revert \"x\"\n\nThis reverts commit abc.\n\nConflicts:\n\ta.txt\n";
        assertEquals("Revert \"x\"\n\nThis reverts commit abc.", PreparedMessage.clean(raw));
    }

    @Test
    void aMissingFileIsAnEmptyMessage() {
        assertEquals("", PreparedMessage.read(gitDir.toFile()));
    }

    // ------------------------------------------------------------------
    // C2: the stopped step's author
    // ------------------------------------------------------------------

    @Test
    void anAuthorScriptIsReadBackWithItsDateAndZone() {
        PersonIdent author = GitService.parseAuthorScript(
                "GIT_AUTHOR_NAME='O'\\''Brien'\nGIT_AUTHOR_EMAIL='ob@example.com'\n"
                        + "GIT_AUTHOR_DATE='@1700000000 +0130'\n");

        assertEquals("O'Brien", author.getName());
        assertEquals("ob@example.com", author.getEmailAddress());
        assertEquals(1700000000L, author.getWhenAsInstant().getEpochSecond());
        assertEquals(90 * 60, author.getZoneOffset().getTotalSeconds());
    }

    /**
     * C2 on JGit: a conflicted rebase step committed by hand keeps the original author,
     * as {@code git commit} does from the stopped step's author script.
     */
    @Test
    void aCommitMadeDuringAStoppedRebaseStepKeepsItsAuthor(@TempDir Path repo) throws Exception {
        String theirs;
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Resolver");
            config.setString("user", null, "email", "resolver@example.com");
            config.save();
            Files.writeString(repo.resolve("a.txt"), "base\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("base").call();
            git.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(repo.resolve("a.txt"), "feature\n");
            git.add().addFilepattern("a.txt").call();
            theirs = git.commit().setMessage("feature change")
                    .setAuthor("Original", "orig@example.com").call().getName();
            git.checkout().setName("main").call();
            Files.writeString(repo.resolve("a.txt"), "main\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("main change").call();
            git.checkout().setName("feature").call();
        }
        GitService service = new GitService();
        assertTrue(service.openRepository(repo.toFile()));
        try {
            assertTrue(service.rebase("main").isConflict());
            assertTrue(service.resolveConflict("a.txt", "both\n").isOk());

            assertTrue(service.commit("feature change, resolved", false).isOk());

            try (Git git = Git.open(repo.toFile())) {
                var head = git.log().setMaxCount(1).call().iterator().next();
                assertEquals("orig@example.com", head.getAuthorIdent().getEmailAddress(),
                        "resolving " + theirs + " does not make the resolver its author");
                assertEquals("resolver@example.com", head.getCommitterIdent().getEmailAddress());
            }
        } finally {
            service.abortOperation();
            service.close();
        }
    }

    // ------------------------------------------------------------------
    // O15: non-cone sparse checkout keeps its mode
    // ------------------------------------------------------------------

    @Test
    void aNonConeSparseCheckoutIsEditedInItsOwnMode(@TempDir Path repo) throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "git command line not on PATH");
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "T");
            config.setString("user", null, "email", "t@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
            for (String path : List.of("docs/a.txt", "src/b.txt", "top.txt")) {
                Files.createDirectories(repo.resolve(path).getParent());
                Files.writeString(repo.resolve(path), path);
            }
            git.add().addFilepattern(".").call();
            git.commit().setMessage("layout").call();
        }
        try (NativeGitBackend backend = new NativeGitBackend(repo.toFile())) {
            var runner = new NativeGitRunner(repo.toFile());
            assertTrue(runner.runWithInput("/*\n!/*/\n/docs/\n".getBytes(),
                    List.of("sparse-checkout", "set", "--no-cone", "--stdin")).isOk());

            assertEquals(List.of("docs"), backend.getSparseCheckoutPaths(),
                    "folder patterns read back as the folder names the dialog shows");

            GitResult set = backend.setSparseCheckoutPaths(List.of("src"));

            assertTrue(set.isOk(), set::getMessage);
            assertEquals("false", runner.run("config", "--get", "core.sparseCheckoutCone").trimmedStdout(),
                    "the repository stays in non-cone mode");
            assertTrue(Files.exists(repo.resolve("src/b.txt")));
            assertFalse(Files.exists(repo.resolve("docs/a.txt")));
            assertTrue(Files.exists(repo.resolve("top.txt")), "top-level files stay, as in cone mode");
            assertEquals(List.of("src"), backend.getSparseCheckoutPaths());
        }
    }
}
