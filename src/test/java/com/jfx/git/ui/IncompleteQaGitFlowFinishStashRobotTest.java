package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitFlow;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finishing a git-flow branch over uncommitted changes. Finish checks out the branches it
 * merges into, so local changes in the way used to stop it part-way - or, carried across,
 * end up on develop by accident. The confirmation that lists the steps now says the
 * changes are stashed first and re-applied when it is done, and its button says so.
 *
 * <p>The fixture: {@code main} and {@code develop} with {@code shared.txt}; a feature that
 * edits line 1 of it; and, uncommitted on the feature, an edit to line 5 and an untracked
 * {@code scratch.txt}. Checking out develop over that edit is refused, because the file
 * differs between the two branches. JGit here: the finish itself, stash and all, is
 * {@code GitFlowTest}'s on every engine, and this is the screen's wiring to it.
 */
class IncompleteQaGitFlowFinishStashRobotTest extends ApplicationTest {

    private static final String BASE = "line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String FEATURE = "feature line 1\nline 2\nline 3\nline 4\nline 5\n";
    private static final String FEATURE_WITH_LOCAL_EDIT = "feature line 1\nline 2\nline 3\nline 4\nlocal line 5\n";
    private static final String SCRATCH = "untracked scratch\n";

    @TempDir
    Path root;

    private Path work;
    private GitBackend backend;
    private RepoTab tab;

    /** The engine under test, opened on {@code workTree}; the CLI subclass swaps it. */
    protected GitBackend openEngine(Path workTree) throws Exception {
        GitService service = new GitService();
        assertTrue(service.openRepository(workTree.toFile()));
        return service;
    }

    @Override
    public void start(Stage stage) throws Exception {
        work = root.resolve("work");
        try (var fixture = ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Synthetic QA");
                config.setString("user", null, "email", "qa@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
                Files.writeString(work.resolve("shared.txt"), BASE, StandardCharsets.UTF_8);
                git.add().addFilepattern(".").call();
                git.commit().setMessage("base").call();
            }
            backend = openEngine(work);
            assertTrue(new GitFlow(backend).init(GitFlow.Config.defaults("main")).isOk());
            assertTrue(new GitFlow(backend).start(GitFlow.Kind.FEATURE, "login").isOk());
            Files.writeString(work.resolve("shared.txt"), FEATURE, StandardCharsets.UTF_8);
            assertTrue(backend.stageFile("shared.txt").isOk());
            assertTrue(backend.commit("feature edit", false).isOk());
        }
        tab = new RepoTab(new LyraApp(), "git flow finish", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @BeforeEach
    void loadTheTab() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    @Test
    void finishingOverLocalChangesSaysTheyAreStashedAndCancellingChangesNothing() throws Exception {
        makeLocalChanges();
        RepoSnapshot before = RepoSnapshot.of(work);

        interact(() -> tab.getSidebar().confirmFinishFlow("feature/login"));
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        assertEquals("Stash and finish feature",
                FxHarness.call(() -> ((Button) confirm.lookupButton(ButtonType.OK)).getText()),
                "the button says the changes are stashed as part of finishing");
        java.util.List<String> steps = FxHarness.call(() -> confirm.lookupAll(".jfx-affected-row").stream()
                .map(n -> ((javafx.scene.control.Label) n).getText()).toList());
        assertTrue(steps.contains("1. Merge feature/login into develop"), steps.toString());
        String said = FxHarness.call(confirm::getContentText);
        assertTrue(said.contains("uncommitted changes") && said.contains("stash"),
                "the confirmation says the local changes are stashed first: " + said);
        press(confirm, ButtonType.CANCEL.getText());
        RobotWait.drainGitWork();

        assertEquals(before, RepoSnapshot.of(work), "a cancelled finish changes nothing");
    }

    @Test
    void stashingAndFinishingMergesTheFeatureAndReappliesTheLocalChangesOnDevelop() throws Exception {
        String developTip = resolve("refs/heads/develop");
        String featureTip = resolve("refs/heads/feature/login");
        makeLocalChanges();

        interact(() -> tab.getSidebar().confirmFinishFlow("feature/login"));
        press(RobotWait.untilDialogWithButton("OK"), "Stash and finish feature");
        RobotWait.until("the feature was never finished", () -> resolve("refs/heads/feature/login") == null);
        RobotWait.drainGitWork();

        try (Git git = Git.open(work.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            assertEquals("develop", git.getRepository().getBranch(), "finishing ends on develop");
            RevCommit merge = walk.parseCommit(git.getRepository().resolve("refs/heads/develop"));
            assertEquals(2, merge.getParentCount(), "a feature is finished with a merge commit");
            assertEquals(developTip, merge.getParent(0).name());
            assertEquals(featureTip, merge.getParent(1).name());
        }
        assertEquals(FEATURE_WITH_LOCAL_EDIT, read("shared.txt"),
                "the local edit is re-applied on top of the finished feature");
        assertEquals(SCRATCH, read("scratch.txt"), "the untracked file is back byte for byte");
        Status status;
        try (Git git = Git.open(work.toFile())) {
            status = git.status().call();
            assertTrue(git.stashList().call().isEmpty(), "a clean re-apply leaves no stash behind");
        }
        assertEquals(Set.of("shared.txt"), status.getModified(), "the edit is back, unstaged");
        assertEquals(Set.of("scratch.txt"), status.getUntracked());
    }

    /** An edit to line 5 of the feature's version, and an untracked file. */
    private void makeLocalChanges() throws Exception {
        Files.writeString(work.resolve("shared.txt"), FEATURE_WITH_LOCAL_EDIT, StandardCharsets.UTF_8);
        Files.writeString(work.resolve("scratch.txt"), SCRATCH, StandardCharsets.UTF_8);
    }

    /** Presses the button that reads {@code button}: the verb a question in the dialog shell shows on OK. */
    private void press(DialogPane pane, String button) {
        interact(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(bt -> button.equals(((Button) pane.lookupButton(bt)).getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("no \"" + button + "\" button; has "
                            + pane.getButtonTypes().stream()
                                    .map(bt -> ((Button) pane.lookupButton(bt)).getText()).toList()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    private String resolve(String revision) {
        try (Git git = Git.open(work.toFile())) {
            var id = git.getRepository().resolve(revision);
            return id == null ? null : id.name();
        } catch (Exception e) {
            throw new AssertionError("could not resolve " + revision, e);
        }
    }

    private String read(String file) throws Exception {
        return Files.readString(work.resolve(file), StandardCharsets.UTF_8);
    }
}
