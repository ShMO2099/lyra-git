package com.jfx.git.core;

import com.jfx.git.core.RebaseTodo.Action;

import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.ResetMode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reflog exists to undo the things that cannot otherwise be undone, so the tests
 * that matter are the round-trips: rewrite history, then get it back exactly.
 */
class ReflogTest {

    @TempDir
    Path tempDir;

    private GitService service;

    @BeforeEach
    void setUp() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
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
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private List<CommitInfo> log() {
        List<CommitInfo> log = new ArrayList<>();
        for (var row : service.getCommitGraphPage(50, GraphOrder.current()).rows()) {
            if (!row.isWipNode()) {
                log.add(row.getCommit());
            }
        }
        return log;
    }

    private List<String> messages() {
        return log().stream().map(CommitInfo::getSubject).toList();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Test
    void everyCommitLeavesAReflogEntry() throws Exception {
        commit("a.txt", "a", "first");
        commit("b.txt", "b", "second");

        List<ReflogItem> log = service.getReflog(20);
        assertEquals(2, log.size());
        assertEquals(0, log.get(0).index(), "the newest move comes first");
        assertTrue(log.get(0).comment().contains("second"), log.get(0).comment());
    }

    @Test
    void theFirstEntryHasNoEarlierStateToReturnTo() throws Exception {
        commit("a.txt", "a", "first");

        ReflogItem creation = service.getReflog(20).get(0);
        assertTrue(creation.isBranchCreation(),
                "the commit that created the branch has no previous position");
        assertEquals("-", creation.shortOldHash());
    }

    @Test
    void entriesUseGitsOwnSelectorNotation() throws Exception {
        commit("a.txt", "a", "first");
        commit("b.txt", "b", "second");

        assertEquals("HEAD@{0}", service.getReflog(20).get(0).selector());
        assertEquals("HEAD@{1}", service.getReflog(20).get(1).selector());
    }

    @Test
    void theLimitIsRespected() throws Exception {
        for (int i = 0; i < 5; i++) {
            commit("f" + i + ".txt", "v", "commit " + i);
        }
        assertEquals(2, service.getReflog(2).size());
    }

    @Test
    void anEmptyRepositoryHasAnEmptyReflog() {
        assertTrue(service.getReflog(10).isEmpty());
    }

    // ------------------------------------------------------------------
    // Classifying
    // ------------------------------------------------------------------

    @Test
    void aRebaseIsMarkedAsRewritingHistory() throws Exception {
        commit("base.txt", "base", "base");
        commit("a.txt", "a", "add a");
        commit("b.txt", "b", "add b");

        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 2)).withAction(0, Action.DROP);
        assertTrue(service.rebaseInteractive(plan).isOk());

        // Something in the reflog must own up to having rewritten history, or the user
        // has no way to find the point to go back to.
        assertTrue(service.getReflog(20).stream().anyMatch(ReflogItem::isHistoryRewrite),
                service.getReflog(20).stream().map(ReflogItem::comment).toList().toString());
    }

    @Test
    void anOrdinaryCommitIsNotAHistoryRewrite() throws Exception {
        commit("a.txt", "a", "first");
        assertFalse(service.getReflog(20).get(0).isHistoryRewrite());
    }

    @Test
    void theOperationIsTakenFromGitsComment() throws Exception {
        commit("a.txt", "a", "first");
        commit("b.txt", "b", "second");
        assertEquals("commit", service.getReflog(20).get(0).operation());
        // Git labels the very first commit differently, and that label is worth keeping.
        assertEquals("commit (initial)", service.getReflog(20).get(1).operation());
    }

    // ------------------------------------------------------------------
    // Restoring - the point of the whole thing
    // ------------------------------------------------------------------

    @Test
    void restoringUndoesARebase() throws Exception {
        commit("base.txt", "base", "base");
        commit("a.txt", "a", "add a");
        commit("b.txt", "b", "add b");
        List<String> before = messages();
        String topBefore = log().get(0).getHash();

        RebaseTodo plan = RebaseTodo.fromNewestFirst(log().subList(0, 2)).withAction(0, Action.DROP);
        assertTrue(service.rebaseInteractive(plan).isOk());
        assertFalse(messages().contains("add a"), "the rebase really did drop it");

        // What a user does: find the row describing the state they want back - the commit
        // that last put HEAD where it was - and restore to it.
        ReflogItem beforeRebase = service.getReflog(30).stream()
                .filter(item -> topBefore.equals(item.newHash()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reflog entry for the pre-rebase state"));
        assertTrue(new RepositoryCommands(service).restoreTo(beforeRebase.restoreTarget(), beforeRebase.operation()).isOk());

        assertEquals(before, messages(), "history should be exactly as it was");
        assertEquals(topBefore, log().get(0).getHash(), "including commit identity");
        assertTrue(tempDir.resolve("a.txt").toFile().exists(), "and the working tree");
    }

    @Test
    void restoringUndoesAHardReset() throws Exception {
        commit("a.txt", "a", "first");
        commit("b.txt", "b", "second");
        String beforeReset = log().get(0).getHash();

        assertTrue(service.reset(log().get(1).getHash(),
                ResetMode.HARD).isOk());
        assertEquals(1, log().size(), "the reset really did discard a commit");

        ReflogItem entry = service.getReflog(20).stream()
                .filter(item -> beforeReset.equals(item.newHash()))
                .findFirst().orElseThrow();
        assertTrue(new RepositoryCommands(service).restoreTo(entry.restoreTarget(), entry.operation()).isOk());
        assertEquals(beforeReset, log().get(0).getHash());
    }

    @Test
    void restoringIsRefusedWhileThereAreUncommittedChanges() throws Exception {
        commit("a.txt", "a", "first");
        commit("b.txt", "b", "second");
        ReflogItem entry = service.getReflog(20).get(0);

        Files.writeString(tempDir.resolve("a.txt"), "edited", StandardCharsets.UTF_8);
        GitResult result = new RepositoryCommands(service).restoreTo(entry.restoreTarget(), entry.operation());

        assertFalse(result.isOk());
        assertTrue(result.describe().contains("stash"), result.describe());
        assertEquals("edited", Files.readString(tempDir.resolve("a.txt")),
                "a refused restore must not touch the user's work");
        assertEquals(2, log().size(), "and must not move the branch");
    }

    @Test
    void restoringToNothingIsRefused() throws Exception {
        commit("a.txt", "a", "first");
        assertFalse(new RepositoryCommands(service).restoreTo(null, "x").isOk());
        assertFalse(new RepositoryCommands(service).restoreTo("", "x").isOk());
    }

    @Test
    void aRestoreIsItselfRecorded() throws Exception {
        commit("a.txt", "a", "first");
        commit("b.txt", "b", "second");
        int before = service.getReflog(50).size();

        ReflogItem entry = service.getReflog(20).get(1);
        assertTrue(new RepositoryCommands(service).restoreTo(entry.restoreTarget(), entry.operation()).isOk());

        // Undoing is itself a move, so it can be undone in turn.
        assertTrue(service.getReflog(50).size() > before);
    }
}
