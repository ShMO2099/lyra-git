package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.model.StashOptions;

import javafx.scene.Node;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toolbar says how much work is waiting.
 *
 * <h2>What this adds</h2>
 * Pull and Push looked identical whether the branch was up to date or eleven commits
 * behind. The only way to find out was to press one and read the status bar, which
 * delivers the answer after the decision rather than before it.
 *
 * <p>Asserted against a real upstream rather than a stub: the counts come from
 * {@code getTrackingStatus}, and a test that fed it numbers directly would pass whether
 * or not the toolbar was actually asking git.
 */
class ToolbarPendingWorkRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    private Stage stage;
    private Path upstream;
    private Path clone;

    @Override
    public void start(Stage stage) throws Exception {
        // A bare "remote" with a commit already in it, then a clone of that. The order
        // matters: cloning an *empty* bare repository gives a working copy with no branch
        // and no upstream, so getTrackingStatus() answers [0, 0] for want of anything to
        // compare with, and every assertion below would be measuring the absence of a
        // remote instead of the presence of pending work.
        upstream = tempDir.resolve("origin.git");
        Path seed = tempDir.resolve("seed");
        clone = tempDir.resolve("work");
        Git.init().setBare(true).setDirectory(upstream.toFile()).call().close();

        try (Git git = Git.init().setDirectory(seed.toFile()).call()) {
            configure(git);
            Files.writeString(seed.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.remoteAdd().setName("origin")
                    .setUri(new org.eclipse.jgit.transport.URIish(upstream.toUri().toString()))
                    .call();
            git.push().setRemote("origin").call();
        }

        try (Git git = Git.cloneRepository()
                .setURI(upstream.toUri().toString())
                .setDirectory(clone.toFile())
                .call()) {
            configure(git);
        }

        service = new GitService();
        service.openRepository(clone.toFile());

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        this.stage = stage;
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // The refresh's queries run on the git lane; the backend must outlive them.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        // The stage is TestFX's primary stage: one window, handed to every ApplicationTest
        // in the JVM. JavaFX keeps a width set on it through every later setScene and show,
        // so after the compact test each class that followed showed its 1200-pixel scene in
        // an 800-pixel window - narrow enough to push HistorySearchRobotTest's "Search all
        // history" button out of the graph column, where its click landed on the details
        // panel. sizeToScene, while the window still shows, gives it its scene's size and
        // stops treating the width as one somebody set, so the next scene sizes it again.
        if (stage != null) interact(stage::sizeToScene);
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private static void configure(Git git) throws Exception {
        var config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Test User");
        config.setString("user", null, "email", "test@example.com");
        config.setBoolean("commit", null, "gpgsign", false);
        config.save();
    }

    /**
     * Refreshes the tab and waits for the toolbar to show the result. The toolbar's queries
     * run on the git lane and report back on the FX thread, so this waits for the lane to
     * empty and then for the FX thread to apply what it reported. It waits for the refresh
     * rather than for a particular button state because half of these tests assert that a
     * button stays as it started, which a wait for a change could never observe.
     */
    private void refreshTab() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The Pull or Push button, by the caption stored apart from its responsive text. */
    private SplitMenuButton syncButton(String caption) {
        return assertInstanceOf(SplitMenuButton.class, tab.getToolbar().toolButton(caption));
    }

    /**
     * Confirms the caption remains accessible and any pending arrow remains visible in
     * either toolbar layout. Compact mode intentionally keeps only the count on screen.
     */
    private void assertSyncLabel(String caption, String badge) {
        SplitMenuButton button = syncButton(caption);
        boolean compact = tab.getToolbar().isCompact();
        assertEquals(badge == null ? caption : caption + " " + badge,
                button.getAccessibleText(), "the action name survives the responsive layout");
        if (compact) {
            assertEquals(badge == null ? "" : badge, button.getText(),
                    "compact mode keeps the count while hiding the caption");
            assertEquals(badge == null
                            ? javafx.scene.control.ContentDisplay.GRAPHIC_ONLY
                            : javafx.scene.control.ContentDisplay.LEFT,
                    button.getContentDisplay());
        } else {
            assertEquals(badge == null ? caption : caption + "  " + badge, button.getText());
            assertEquals(javafx.scene.control.ContentDisplay.TOP, button.getContentDisplay());
        }
    }

    private static boolean isPending(Node button) {
        return button.getStyleClass().contains("sync-pending");
    }

    @Test
    void anUpToDateBranchMarksNothingAsPending() {
        refreshTab();

        assertSyncLabel("Pull", null);
        assertSyncLabel("Push", null);
        assertFalse(isPending(syncButton("Pull")), "an up-to-date branch is not urgent");
        assertFalse(isPending(syncButton("Push")));
    }

    @Test
    void aLocalCommitMarksPushAsPending() throws Exception {
        try (Git git = Git.open(clone.toFile())) {
            Files.writeString(clone.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("second").call();
        }

        refreshTab();

        int[] tracking = service.getTrackingStatus();
        assertNotNull(tracking, "the clone should have an upstream to be ahead of");
        assertTrue(tracking[0] > 0, "git really is ahead");
        assertSyncLabel("Push", "↑1");
        assertTrue(isPending(syncButton("Push")),
                "the one action worth taking should be the one that is marked");
        assertFalse(isPending(syncButton("Pull")),
                "nothing arrived, so pulling is not the pending action");
    }

    @Test
    void anUpstreamCommitMarksPullAsPending() throws Exception {
        // Push a commit from a second clone, then fetch: the first clone is now behind.
        Path other = tempDir.resolve("other");
        try (Git git = Git.cloneRepository()
                .setURI(upstream.toUri().toString())
                .setDirectory(other.toFile())
                .call()) {
            configure(git);
            Files.writeString(other.resolve("b.txt"), "b\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("b.txt").call();
            git.commit().setMessage("from elsewhere").call();
            git.push().call();
        }
        assertTrue(service.fetchAll().isOk());

        refreshTab();

        int[] tracking = service.getTrackingStatus();
        assertNotNull(tracking, "the clone should have an upstream to be behind");
        assertTrue(tracking[1] > 0, "git really is behind");
        assertSyncLabel("Pull", "↓1");
        assertTrue(isPending(syncButton("Pull")));
        assertFalse(isPending(syncButton("Push")));
    }

    /** Acting on it clears it: a count that survives its own pull is worse than none. */
    @Test
    void theCountClearsOnceTheWorkIsDone() throws Exception {
        try (Git git = Git.open(clone.toFile())) {
            Files.writeString(clone.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("second").call();
        }
        refreshTab();
        assertTrue(isPending(syncButton("Push")));

        assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());
        refreshTab();

        assertSyncLabel("Push", null);
        assertFalse(isPending(syncButton("Push")));
    }

    /** A compact bar still exposes the arrow count and clears it after the push. */
    @Test
    void compactToolbarKeepsAndClearsPendingArrowCount() throws Exception {
        try (Git git = Git.open(clone.toFile())) {
            Files.writeString(clone.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("second").call();
        }
        refreshTab();

        interact(() -> stage.setWidth(800));
        RobotWait.untilOnFx("the toolbar did not enter compact mode at 800px",
                () -> tab.getToolbar().isCompact());
        assertSyncLabel("Push", "↑1");
        assertTrue(isPending(syncButton("Push")), "the pending push remains marked in compact mode");

        assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());
        refreshTab();

        assertSyncLabel("Push", null);
        assertFalse(isPending(syncButton("Push")), "the compact pending state clears after the push");
        assertTrue(tab.getToolbar().isCompact(), "the count clears without changing the compact layout");
    }

    /** A plain toolbar button, by its caption. */
    private javafx.scene.control.Button toolbarButton(String caption) {
        return (javafx.scene.control.Button) tab.getToolbar().toolButton(caption);
    }

    @Test
    void popIsOffUntilThereIsSomethingToPop() throws Exception {
        refreshTab();

        assertTrue(toolbarButton("Pop").isDisabled(),
                "a clean clone has an empty stash, and popping it can only fail");
        assertEquals("Nothing stashed",
                toolbarButton("Pop").getTooltip().getText(),
                "and the tooltip should say why rather than describing an action that "
                        + "is not available");
    }

    @Test
    void stashingTurnsPopOnAndSaysHowManyAreWaiting() throws Exception {
        Files.writeString(clone.resolve("a.txt"), "one\nchanged\n", StandardCharsets.UTF_8);
        assertTrue(service.stash(StashOptions.everything("first bit of work")).isOk());

        refreshTab();

        assertFalse(toolbarButton("Pop").isDisabled(), "there is now a stash to pop");
        assertEquals("Apply and drop the stash",
                toolbarButton("Pop").getTooltip().getText(),
                "one stash is not 'the latest of 1 stashes'");

        Files.writeString(clone.resolve("a.txt"), "one\nagain\n", StandardCharsets.UTF_8);
        assertTrue(service.stash(StashOptions.everything("second bit")).isOk());
        refreshTab();

        assertEquals("Apply and drop the latest of 2 stashes",
                toolbarButton("Pop").getTooltip().getText(),
                "the count matters here: Pop takes one of them, not all of them");
    }

    /** And it goes back off once the last one has been taken. */
    @Test
    void popTurnsOffAgainWhenTheStashEmpties() throws Exception {
        Files.writeString(clone.resolve("a.txt"), "one\nchanged\n", StandardCharsets.UTF_8);
        assertTrue(service.stash(StashOptions.everything("work")).isOk());
        refreshTab();
        assertFalse(toolbarButton("Pop").isDisabled(), "sanity: it turned on");

        assertTrue(service.stashPop(0).isOk());
        refreshTab();

        assertTrue(toolbarButton("Pop").isDisabled(),
                "the stash is empty again, so the button goes back to unavailable");
    }

    // ------------------------------------------------------------------
    // Provider buttons
    // ------------------------------------------------------------------

    /** The caption a toolbar button was given. */
    private static String caption(javafx.scene.control.Button button) {
        return RepoToolbar.captionOf(button);
    }

    /** The pull/merge-request button, under whichever name it currently carries. */
    private javafx.scene.control.Button providerButton() {
        for (String caption : new String[] {"Pull requests", "Merge requests"}) {
            try {
                return toolbarButton(caption);
            } catch (IllegalArgumentException notThisName) {
                // the other name, then
            }
        }
        throw new AssertionError("no pull/merge-request button on the toolbar");
    }

    @Test
    void aGitHubStyleRemoteKeepsTheProviderButtonsAvailable() {
        refreshTab();

        assertFalse(providerButton().isDisabled(),
                "the clone has an origin, so there is something to browse");
        assertTrue(providerButton().isVisible() && providerButton().isManaged(),
                "and the buttons are on the bar");
        assertEquals("Pull requests", caption(providerButton()));
        assertFalse(toolbarButton("Issues").isDisabled());
    }

    @Test
    void noOriginTurnsTheProviderButtonsOff() {
        assertTrue(service.removeRemote("origin").isOk());

        refreshTab();

        assertTrue(providerButton().isDisabled(),
                "without a remote the pull-request dialog has no repository to query, "
                        + "and opening it only to fail inside is worse than not opening");
        assertTrue(toolbarButton("Issues").isDisabled());
        assertFalse(providerButton().isManaged(),
                "and with no host at all the buttons leave the bar rather than sit there greyed out");
        assertFalse(toolbarButton("Issues").isVisible());
        assertEquals("This repository has no origin remote, so there is nothing to browse",
                providerButton().getTooltip().getText());
    }

    /** GitLab does not have pull requests, and the button should not claim it does. */
    @Test
    void aGitLabRemoteRenamesTheButtonToMergeRequests() {
        assertTrue(service.removeRemote("origin").isOk());
        assertTrue(service.addRemote("origin", "https://gitlab.com/group/project.git").isOk());

        refreshTab();

        assertEquals("Merge requests", caption(providerButton()),
                "the label should name what the host calls the thing");
        assertFalse(providerButton().isDisabled());
    }

    /**
     * The provider buttons open their dialogs with the origin the refresh already read.
     * They used to ask git for it on the FX thread - a {@code git remote} subprocess on the
     * CLI engine, and a frozen window for as long as it took.
     */
    @Test
    void theProviderButtonsUseTheOriginTheRefreshRead() {
        refreshTab();

        String cached = WaitForAsyncUtils.waitForAsyncFx(5000, () -> tab.getToolbar().cachedOriginUrlForTest());
        assertEquals(service.getRemoteUrl("origin"), cached);
        assertNotNull(cached);
    }

    /** Bitbucket has no issue-tracker API left, so that button has nothing behind it. */
    @Test
    void aBitbucketRemoteLeavesIssuesUnavailable() {
        assertTrue(service.removeRemote("origin").isOk());
        assertTrue(service.addRemote("origin", "https://bitbucket.org/team/repo.git").isOk());

        refreshTab();

        assertFalse(providerButton().isDisabled(), "pull requests still work there");
        assertTrue(toolbarButton("Issues").isDisabled());
        assertEquals("Bitbucket removed its issue tracker API, so there is nothing to read",
                toolbarButton("Issues").getTooltip().getText());
    }

    /** Gitea has pull requests and issues both, under those names. */
    @Test
    void aCodebergRemoteKeepsPullRequestsAndIssues() {
        assertTrue(service.removeRemote("origin").isOk());
        assertTrue(service.addRemote("origin", "https://codeberg.org/team/repo.git").isOk());

        refreshTab();

        assertEquals("Pull requests", caption(providerButton()));
        assertFalse(providerButton().isDisabled());
        assertFalse(toolbarButton("Issues").isDisabled());
        assertEquals("Browse this repository's open issues", toolbarButton("Issues").getTooltip().getText());
    }

    /**
     * A server nobody configured still gets live buttons: the click names the host and
     * opens Integrations, the way out for a self-hosted server. The tooltip says so first.
     */
    @Test
    void anUnknownHostKeepsTheButtonsAndSaysWhy() {
        assertTrue(service.removeRemote("origin").isOk());
        assertTrue(service.addRemote("origin", "https://git.example.com/team/repo.git").isOk());

        refreshTab();

        assertEquals("Pull requests", caption(providerButton()));
        assertFalse(providerButton().isDisabled());
        assertFalse(toolbarButton("Issues").isDisabled());
        assertEquals("origin is on a server no integration is set up for",
                providerButton().getTooltip().getText());
    }
}
