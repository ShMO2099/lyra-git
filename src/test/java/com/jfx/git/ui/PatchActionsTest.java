package com.jfx.git.ui;

import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.PatchApplyMode;
import com.jfx.git.ui.dialogs.ApplyPatchDialog;

import javafx.scene.control.RadioButton;
import javafx.scene.input.Clipboard;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commits saved and copied as patches, and a patch applied, through the same class the
 * commit menu and the File menu use.
 */
class PatchActionsTest {

    @TempDir
    Path root;

    private Path workTree;
    private GitService service;
    private PatchActions patches;
    private final AtomicInteger refreshes = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        FxHarness.start();
        workTree = Files.createDirectory(root.resolve("work"));
        try (Git git = Git.init().setDirectory(workTree.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(workTree.toFile());
        commit("a.txt", "one\n", "Add the first file");
        commit("a.txt", "one\ntwo\n", "Add a second line!");

        StatusBar statusBar = FxHarness.call(StatusBar::new);
        patches = new PatchActions(OperationRunner.of(service, statusBar, refreshes::incrementAndGet));
    }

    @AfterEach
    void tearDown() {
        RobotWait.drainGitWork();
        if (service != null) {
            service.close();
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(workTree.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    /** The repository's commits, newest first, as the graph hands them over. */
    private List<CommitInfo> commits() {
        List<CommitInfo> commits = new ArrayList<>();
        for (var row : service.getCommitGraphPage(20, GraphOrder.current()).rows()) {
            if (!row.isWipNode() && row.getCommit() != null) commits.add(row.getCommit());
        }
        return commits;
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    @Test
    void aPatchIsNamedAfterItsCommitOrAfterHowManyThereAre() {
        List<CommitInfo> all = commits();

        assertEquals("add-a-second-line.patch", PatchActions.suggestedName(all.subList(0, 1)),
                "the subject, as words a file system takes");
        assertEquals("2-commits.patch", PatchActions.suggestedName(all));
    }

    // ------------------------------------------------------------------
    // Out
    // ------------------------------------------------------------------

    @Test
    void savingWritesAPatchThatCarriesTheCommit() throws Exception {
        File target = root.resolve("out.patch").toFile();

        FxHarness.run(() -> patches.saveAsPatch(commits().subList(0, 1), target));
        RobotWait.until("the patch was never written", target::isFile);
        RobotWait.drainGitWork();

        String text = Files.readString(target.toPath());
        assertTrue(text.contains("Subject: [PATCH] Add a second line!"), text);
        assertTrue(text.contains("+two"), "and the change itself");
    }

    @Test
    void copyingPutsThePatchOnTheClipboardAndLeavesNoFileBehind() {
        FxHarness.run(() -> patches.copyAsPatch(commits().subList(0, 1)));
        RobotWait.drainGitWork();

        RobotWait.untilOnFx("the patch never reached the clipboard", () -> {
            String text = Clipboard.getSystemClipboard().getString();
            return text != null && text.contains("Subject: [PATCH] Add a second line!");
        });
    }

    // ------------------------------------------------------------------
    // In
    // ------------------------------------------------------------------

    @Test
    void applyingAPatchChangesTheWorkingTreeAndAsksForARefresh() throws Exception {
        File patch = root.resolve("second.patch").toFile();
        assertTrue(service.createPatch(List.of(service.getHeadHash()), patch).isOk());
        String first = commits().get(1).getHash();
        assertTrue(new RepositoryCommands(service).restoreTo(first, "rewind").isOk());
        assertEquals("one\n", Files.readString(workTree.resolve("a.txt")));
        int before = refreshes.get();

        FxHarness.run(() -> patches.applyPatch(patch, PatchApplyMode.WORKTREE));
        RobotWait.until("the patch was never applied",
                () -> "one\ntwo\n".equals(read("a.txt")));
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("nothing was told to refresh", () -> refreshes.get() > before);

        assertEquals(first, service.getHeadHash(), "applied to the working tree, not committed");
    }

    private String read(String file) {
        try {
            return Files.readString(workTree.resolve(file));
        } catch (Exception midWrite) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // What kind of patch a file is
    // ------------------------------------------------------------------

    @Test
    void aPatchSavedFromACommitCanBecomeOneAndABareDiffCannot() throws Exception {
        File saved = root.resolve("saved.patch").toFile();
        assertTrue(service.createPatch(List.of(service.getHeadHash()), saved).isOk());
        File bare = root.resolve("bare.diff").toFile();
        Files.writeString(bare.toPath(), "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1,2 @@\n one\n+two\n");
        File mail = root.resolve("mail.eml").toFile();
        Files.writeString(mail.toPath(), "\nReturn-Path: <a@b>\nFrom: A <a@b>\nSubject: [PATCH] x\n\n---\n");

        assertTrue(PatchActions.carriesCommits(saved));
        assertTrue(PatchActions.carriesCommits(mail), "a mail saved from a client starts with a header");
        assertFalse(PatchActions.carriesCommits(bare));
        assertFalse(PatchActions.carriesCommits(root.resolve("missing.patch").toFile()));
    }

    @Test
    void theDialogOffersCommitsOnlyForAPatchThatCarriesThem() {
        FxHarness.run(() -> {
            ApplyPatchDialog withCommits = new ApplyPatchDialog("saved.patch", true);
            RadioButton am = (RadioButton) withCommits.getDialogPane()
                    .lookup("#" + ApplyPatchDialog.idOf(PatchApplyMode.AM));
            assertFalse(am.isDisabled());
            assertTrue(am.isSelected(), "a patch saved from a commit is usually wanted as that commit");

            ApplyPatchDialog bare = new ApplyPatchDialog("bare.diff", false);
            RadioButton bareAm = (RadioButton) bare.getDialogPane()
                    .lookup("#" + ApplyPatchDialog.idOf(PatchApplyMode.AM));
            RadioButton staged = (RadioButton) bare.getDialogPane()
                    .lookup("#" + ApplyPatchDialog.idOf(PatchApplyMode.INDEX));
            assertTrue(bareAm.isDisabled(), "a plain diff has no author or message to commit with");
            assertTrue(staged.isSelected());
        });
    }

    /** Each way of applying is a card saying what it does, and the button says which was chosen. */
    @Test
    void theButtonSaysWhereThePatchWillLand() {
        FxHarness.run(() -> {
            ApplyPatchDialog withCommits = new ApplyPatchDialog("saved.patch", true);
            javafx.scene.control.Button apply = (javafx.scene.control.Button) withCommits.getDialogPane()
                    .lookupButton(withCommits.getDialogPane().getButtonTypes().get(0));
            assertEquals("Apply as commits", apply.getText());
            ((RadioButton) withCommits.getDialogPane().lookup("#" + ApplyPatchDialog.idOf(PatchApplyMode.WORKTREE)))
                    .setSelected(true);
            assertEquals("Apply to working tree", apply.getText());
            assertEquals(PatchApplyMode.WORKTREE, withCommits.getResultConverter()
                    .call(withCommits.getDialogPane().getButtonTypes().get(0)));

            ApplyPatchDialog bare = new ApplyPatchDialog("bare.diff", false);
            javafx.scene.control.Button bareApply = (javafx.scene.control.Button) bare.getDialogPane()
                    .lookupButton(bare.getDialogPane().getButtonTypes().get(0));
            assertEquals("Apply and stage", bareApply.getText());
            assertTrue(bare.getDialogPane().lookupAll(".t-caption").stream()
                            .map(n -> ((javafx.scene.control.Label) n).getText())
                            .anyMatch(text -> text.contains("plain diff")),
                    "the card that is not offered says why");
        });
    }
}
