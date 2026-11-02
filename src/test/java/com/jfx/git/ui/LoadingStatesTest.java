package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TabPane;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repository that is opening looks like one that is opening - not like an empty one.
 *
 * <p>Before, every region showed the empty state while the data was on its way: "No
 * commits yet", blank count pills, a switcher reading "Branch", "Select a commit", and a
 * status bar with nothing in it. The worker is held here, so the tab is caught in exactly
 * that moment.
 */
class LoadingStatesTest {

    @TempDir
    Path tempDir;

    private final CountDownLatch release = new CountDownLatch(1);
    private RepoTab tab;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        RobotWait.drainGitWork();
        if (tab != null) FxHarness.run(tab::close);
        FxHarness.closeShownStages();
    }

    /** Holds {@code tab}'s repository lane until {@link #release} - everything queued after waits. */
    private void holdTheLane(RepoTab tab) {
        GitTaskRunner.run(tab.getGitService(), () -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GitResult.ok("held");
        }, r -> { });
    }

    private RepoTab opening(File dir) {
        return opening(dir, false);
    }

    /** A tab opening {@code dir}; with {@code held}, its lane is held first, so the open waits. */
    private RepoTab opening(File dir, boolean held) {
        return FxHarness.call(() -> {
            RepoTab t = new RepoTab(new LyraApp(), dir.getName());
            if (held) holdTheLane(t);
            FxHarness.show(new TabPane(t), 1180, 690);
            t.loadRepositoryAsync(dir, () -> { }, error -> { });
            return t;
        });
    }

    private static boolean visible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    @Test
    void whileTheRepositoryOpensEverySurfaceSaysSo() throws Exception {
        File dir;
        try (MainWindowFixture fixture = MainWindowFixture.create(tempDir)) {
            dir = fixture.workTree().toFile();
        }
        tab = opening(dir, true);

        FxHarness.run(() -> {
            Node placeholder = tab.getCommitList().getPlaceholder();
            assertTrue(placeholder.getStyleClass().contains("graph-opening"),
                    "the graph says it is opening, not that there are no commits");
            assertTrue(((Label) ((javafx.scene.Parent) placeholder).getChildrenUnmodifiable().get(1))
                    .getText().startsWith("Opening " + dir.getName()));
            assertTrue(tab.getGraphic() instanceof ProgressIndicator, "the tab spins");
            ComboBox<?> switcher = (ComboBox<?>) tab.getToolbar().lookup(".combo-box");
            assertEquals("Opening…", switcher.getPromptText(), "the switcher does not say \"Branch\"");
            assertTrue(switcher.isDisabled(), "nothing to switch to yet");
            assertTrue(tab.getContent().lookupAll(".count-badge").stream().noneMatch(LoadingStatesTest::visible),
                    "no empty count pills");
            assertTrue(tab.getContent().lookupAll(".label").stream()
                            .anyMatch(n -> "Loading…".equals(((Label) n).getText()) && visible(n)),
                    "the details panel says it is loading");
            ProgressBar busy = (ProgressBar) tab.getContent().lookup(".progress-bar");
            assertTrue(busy != null && visible(busy), "the status bar shows the open as work in progress");
        });

        release.countDown();
        RobotWait.untilOnFx("the graph never arrived", () -> !tab.getCommitList().getItems().isEmpty());
        RobotWait.drainGitWork();
        FxHarness.run(() -> {
            assertFalse(tab.getGraphic() instanceof ProgressIndicator, "the spinner goes once the graph is there");
            ComboBox<?> switcher = (ComboBox<?>) tab.getToolbar().lookup(".combo-box");
            assertFalse(switcher.isDisabled());
            assertTrue(tab.getContent().lookupAll(".count-badge").stream().anyMatch(LoadingStatesTest::visible),
                    "the counts show once they are known");
            ProgressBar busy = (ProgressBar) tab.getContent().lookup(".progress-bar");
            assertFalse(visible(busy), "and the status bar is idle again");
        });
    }

    @Test
    void anEmptyRepositorySaysSoAndOffersTheCommitPanel() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            git.getRepository().getConfig().setString("user", null, "name", "Test");
        }
        tab = opening(tempDir.toFile());
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the open never finished", () -> tab.getGraphic() == null);
        RobotWait.drainGitWork();
        FxHarness.run(() -> {
            Label empty = (Label) ((javafx.scene.Parent) tab.getCommitList().getPlaceholder())
                    .getChildrenUnmodifiable().get(0);
            assertTrue(empty.getText().startsWith("No commits yet"), empty.getText());
            assertTrue(tab.getContent().lookup(".composer-summary") != null,
                    "\"make one from the panel on the right\" - and the panel is there to make it in");
        });
    }

    /**
     * The graph is the first thing a refresh queues, and the toolbar no longer reads the
     * branches the sidebar has just read.
     *
     * <p>Since the ref snapshot (ADR 0002) neither reads them at all: the graph page's load
     * reads the branches, and the sidebar and the toolbar take them from that page. So the
     * page comes before the sidebar's own first read, and nothing asks for the branches.
     */
    @Test
    void aRefreshAsksForTheGraphFirstAndForBranchesOnce() throws Exception {
        try (MainWindowFixture fixture = MainWindowFixture.create(tempDir)) {
            GitService real = new GitService();
            real.openRepository(fixture.workTree().toFile());
            List<String> calls = Collections.synchronizedList(new ArrayList<>());
            GitBackend recording = (GitBackend) Proxy.newProxyInstance(GitBackend.class.getClassLoader(),
                    new Class<?>[]{GitBackend.class}, (proxy, method, args) -> {
                        calls.add(method.getName());
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            tab = FxHarness.call(() -> {
                RepoTab t = new RepoTab(new LyraApp(), "demo", recording);
                FxHarness.show(new TabPane(t), 1180, 690);
                return t;
            });
            RobotWait.drainGitWork();
            calls.clear();
            FxHarness.run(tab::refresh);
            RobotWait.drainGitWork();
            List<String> seen = List.copyOf(calls);
            // Either name: the composed engine forwards the "visible page" convenience as
            // itself, so it can arrive here as that or as the page it is made from.
            int graph = java.util.stream.IntStream.range(0, seen.size())
                    .filter(i -> seen.get(i).startsWith("getCommitGraphPage"))
                    .findFirst().orElse(-1);
            int sidebar = seen.indexOf("getTrackingStatus");
            assertTrue(graph >= 0 && sidebar > graph, "graph before the sidebar's own read: " + seen);
            assertEquals(0, seen.stream().filter("getLocalBranches"::equals).count(),
                    "the sidebar and the toolbar take the page's branches instead of reading them again: " + seen);
            // The tab owns this backend through the recording proxy; @AfterEach closes
            // the tab on the FX thread after draining its queued work.
        }
    }
}
