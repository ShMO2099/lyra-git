package com.jfx.git.ui;

import com.jfx.git.core.GitService;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.StashEntry;
import com.jfx.git.model.StashOptions;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** A late file-list callback must not replace another detail view's count badge. */
class RightPanelCountRefreshRaceRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GatedGitService service;
    private DetailPanel panel;
    private RightPanelManager rightPanel;
    private CommitInfo baseCommit;
    private CommitInfo targetCommit;
    private StashEntry stash;
    private final List<QueryGate> gates = new ArrayList<>();

    @Override
    public void start(Stage stage) throws Exception {
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
                var config = git.getRepository().getConfig();
                config.setString("user", null, "name", "Test User");
                config.setString("user", null, "email", "test@example.test");
                config.setBoolean("core", null, "autocrlf", false);
                config.setBoolean("commit", null, "gpgsign", false);
                config.save();
            }

            service = new GatedGitService();
            assertTrue(service.openRepository(tempDir.toFile()));
            write("base.txt", "base 1\n");
            assertTrue(service.stageAll().isOk());
            assertTrue(service.commit("base", false).isOk());
            String baseHash = service.getHeadHash();

            write("base.txt", "base 2\n");
            write("extra.txt", "extra\n");
            assertTrue(service.stageAll().isOk());
            assertTrue(service.commit("two changed paths", false).isOk());
            String targetHash = service.getHeadHash();
            baseCommit = commitInfo(baseHash, "base", List.of());
            targetCommit = commitInfo(targetHash, "two changed paths", List.of(baseHash));

            write("base.txt", "stashed version\n");
            assertTrue(service.stash(StashOptions.everything("one changed path")).isOk());
            stash = service.getStashEntries().getFirst();
        }

        panel = new DetailPanel();
        DiffViewer diffViewer = new DiffViewer();
        rightPanel = new RightPanelManager(OperationRunner.of(service, new StatusBar(), () -> { }), panel, diffViewer,
                () -> "main");
        stage.setScene(new Scene(new HBox(panel, diffViewer), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        gates.forEach(QueryGate::release);
        RobotWait.drainGitWork();
        if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private CommitInfo commitInfo(String hash, String message, List<String> parents) {
        Instant when = Instant.now();
        return new CommitInfo(hash, message, "Test User", "test@example.test", when,
                when, parents, List.of());
    }

    private void write(String relative, String content) throws Exception {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private QueryGate gate(String key) {
        QueryGate gate = new QueryGate();
        gates.add(gate);
        service.gates.put(key, gate);
        return gate;
    }

    private void awaitStarted(QueryGate gate) throws InterruptedException {
        assertTrue(gate.started.await(15, TimeUnit.SECONDS), "the gated Git query never started");
    }

    private void expectVisiblePanel(String title, String count) {
        RobotWait.untilOnFx("the " + title + " detail panel did not show count '" + count + "'",
                () -> title.equals(panelTitle()) && count.equals(panelCount()));
    }

    private void assertVisiblePanel(String title, String count) {
        FxHarness.run(() -> {
            assertEquals(title, panelTitle());
            assertEquals(count, panelCount());
        });
    }

    private String panelTitle() {
        Label title = (Label) panel.lookup(".mode-header-title");
        return title == null ? null : title.getText();
    }

    private String panelCount() {
        Label count = (Label) panel.lookup(".mode-header .count-badge");
        return count == null ? null : count.getText();
    }

    private void finishInView(QueryGate destinationGate, String title, String count)
            throws InterruptedException {
        awaitStarted(destinationGate);
        // The previous query posts its FX callback before this next query enters its gate.
        // Queueing this inspection behind that callback makes the stale-write window exact.
        assertVisiblePanel(title, "");
        destinationGate.release();
        expectVisiblePanel(title, count);
    }

    @Test
    void lateCommitCountCannotOverwriteStashPanel() throws Exception {
        RobotWait.drainGitWork();
        QueryGate commitGate = gate("commit:" + targetCommit.getHash());
        QueryGate stashGate = gate("commit:" + stash.hash());

        FxHarness.run(() -> rightPanel.showCommitDetails(targetCommit));
        awaitStarted(commitGate);
        FxHarness.run(() -> rightPanel.showStashDetails(stash));

        commitGate.release();
        finishInView(stashGate, "Stash", "1 file");
    }

    @Test
    void lateCommitCountCannotOverwriteComparisonPanel() throws Exception {
        RobotWait.drainGitWork();
        QueryGate commitGate = gate("commit:" + targetCommit.getHash());
        QueryGate comparisonGate = gate(
                "between:" + baseCommit.getHash() + ":" + targetCommit.getHash());

        FxHarness.run(() -> rightPanel.showCommitDetails(targetCommit));
        awaitStarted(commitGate);
        FxHarness.run(() -> rightPanel.showComparison(baseCommit, targetCommit));

        commitGate.release();
        finishInView(comparisonGate, "Comparing", "2 files");
    }

    @Test
    void lateStashCountCannotOverwriteCommitPanel() throws Exception {
        RobotWait.drainGitWork();
        QueryGate stashGate = gate("commit:" + stash.hash());
        QueryGate commitGate = gate("commit:" + targetCommit.getHash());

        FxHarness.run(() -> rightPanel.showStashDetails(stash));
        awaitStarted(stashGate);
        FxHarness.run(() -> rightPanel.showCommitDetails(targetCommit));

        stashGate.release();
        finishInView(commitGate, "Commit", "2 files");
    }

    private static final class QueryGate {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicBoolean claimed = new AtomicBoolean();

        void release() {
            released.countDown();
        }

        void pauseOnce() {
            if (!claimed.compareAndSet(false, true)) return;
            started.countDown();
            try {
                if (!released.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release the gated Git query");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("gated Git query was interrupted", e);
            }
        }
    }

    private static final class GatedGitService extends GitService {
        final Map<String, QueryGate> gates = new ConcurrentHashMap<>();

        @Override
        public List<com.jfx.git.core.GitBackend.FileChange> getChangesInCommit(String hash) {
            QueryGate gate = gates.get("commit:" + hash);
            if (gate != null) gate.pauseOnce();
            return super.getChangesInCommit(hash);
        }

        @Override
        public List<com.jfx.git.core.GitBackend.FileChange> getChangesBetweenCommits(
                String fromHash, String toHash) {
            QueryGate gate = gates.get("between:" + fromHash + ":" + toHash);
            if (gate != null) gate.pauseOnce();
            return super.getChangesBetweenCommits(fromHash, toHash);
        }
    }
}
