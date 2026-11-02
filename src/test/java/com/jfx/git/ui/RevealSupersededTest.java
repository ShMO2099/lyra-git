package com.jfx.git.ui;

import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.testsupport.MainWindowFixture;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Of two reveals, the one asked for last is the one the graph lands on.
 *
 * <p>A tag Hide/Solo excludes is revealed in two steps: the repository is asked whether the
 * graph reaches its commit, and history is paged in only when it does. That answer, landing
 * after a newer reveal had started, used to start paging for the tag and cancel the newer
 * reveal - so the click the user made last lost to the one before it.
 */
class RevealSupersededTest {

    @TempDir
    Path tempDir;

    private static String hashOf(MainWindowFixture window, String ref) throws Exception {
        try (Git git = Git.open(window.workTree().toFile())) {
            var repo = git.getRepository();
            return repo.parseCommit(repo.resolve(ref)).getName();
        }
    }

    private static Predicate<GitGraphRow> commit(String hash) {
        return row -> row != null && row.getCommit() != null && hash.equals(row.getCommit().getHash());
    }

    @Test
    void anExcludedTagsLateAnswerDoesNotOverrideANewerReveal() throws Exception {
        String soloBefore = com.jfx.git.core.AppPreferences.graphSoloBranch();
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RobotWait.drainGitWork();
            RepoTab tab = window.tab();
            String tagged = hashOf(window, "refs/tags/v1.0^{}");
            String oldest = hashOf(window, "refs/tags/v1.0^{}~3");
            com.jfx.git.core.AppPreferences.setGraphSoloBranch("refs/heads/main");
            FxHarness.run(() -> {
                tab.setGraphLimitForTest(2);
                tab.refresh();
            });
            RobotWait.drainGitWork();
            FxHarness.run(() -> assertTrue(tab.getCommitList().getItems().stream()
                            .noneMatch(commit(tagged).or(commit(oldest))),
                    "precondition: both commits are beyond the loaded page"));

            // The tag Solo excludes first, then - before its answer is back - another commit.
            FxHarness.run(() -> {
                tab.revealCommit(tagged, true);
                tab.revealCommit(oldest);
            });
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the reveal asked for last did not land",
                    () -> commit(oldest).test(tab.getCommitList().getSelectionModel().getSelectedItem())
                            && tab.getStatusBar().busyDepth() == 0);
        } finally {
            com.jfx.git.core.AppPreferences.setGraphSoloBranch(soloBefore);
        }
    }

    /**
     * A later click that needs no paging is newer too. It selected its commit and returned
     * before reaching the history window, so the reveal still paging for the click before
     * it landed afterwards and took the selection back.
     */
    @Test
    void aClickThatNeedsNoPagingSupersedesARevealStillPaging() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RobotWait.drainGitWork();
            RepoTab tab = window.tab();
            String older = hashOf(window, "refs/heads/" + MainWindowFixture.LONG_BRANCH);
            FxHarness.run(() -> {
                tab.setGraphLimitForTest(2);
                tab.refresh();
            });
            RobotWait.drainGitWork();
            String onPage = FxHarness.call(() -> tab.getCommitList().getItems().stream()
                    .filter(row -> row.getCommit() != null && !row.isSynthetic())
                    .map(row -> row.getCommit().getHash())
                    .reduce((first, second) -> second).orElseThrow());
            FxHarness.run(() -> assertTrue(tab.getCommitList().getItems().stream().noneMatch(commit(older)),
                    "precondition: the branch is beyond the loaded page"));

            // A branch beyond the page, then - before its paging has landed - a commit on it.
            FxHarness.run(() -> {
                tab.revealCommit(older);
                tab.revealCommit(onPage);
            });
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the status bar stayed busy", () -> tab.getStatusBar().busyDepth() == 0);
            FxHarness.run(() -> assertTrue(
                    commit(onPage).test(tab.getCommitList().getSelectionModel().getSelectedItem()),
                    "the reveal asked for first took the selection from the click made after it"));
        }
    }
}
