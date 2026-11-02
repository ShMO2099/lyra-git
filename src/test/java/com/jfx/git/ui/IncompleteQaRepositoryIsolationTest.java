package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** U13: tab mutations stay scoped, and closing cannot take the backend from running Git work. */
@Timeout(60)
class IncompleteQaRepositoryIsolationTest {
    @TempDir Path root;
    private final List<GitService> services = new ArrayList<>();
    private final List<RepoTab> tabs = new ArrayList<>();
    private final CountDownLatch releaseWorker = new CountDownLatch(1);

    @BeforeAll static void toolkit() { FxHarness.start(); }

    @AfterEach
    void cleanUp() {
        releaseWorker.countDown();
        RobotWait.drainGitWork();
        FxHarness.run(() -> tabs.forEach(RepoTab::close));
        RobotWait.drainGitWork();
        services.forEach(GitService::close);
        FxHarness.closeShownStages();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    @Test
    void stagingInOneTabAndRefreshingTheOtherPreservesRepositoryIsolation() throws Exception {
        Path firstPath = initialize("first", "first base\n");
        Path secondPath = initialize("second", "second base\n");
        GitService first = open(firstPath, new GitService());
        GitService second = open(secondPath, new GitService());
        String firstHead = first.getHeadHash();
        String secondHead = second.getHeadHash();
        Files.writeString(firstPath.resolve("shared.txt"), "first edit\n");
        Files.writeString(secondPath.resolve("shared.txt"), "second edit\n");
        TabPane pane = FxHarness.call(() -> {
            RepoTab firstTab = tab("first", first);
            RepoTab secondTab = tab("second", second);
            TabPane result = new TabPane(firstTab, secondTab);
            FxHarness.showRegistered(result, 1150, 700);
            firstTab.refresh();
            secondTab.refresh();
            return result;
        });
        RobotWait.drainGitWork();
        FxHarness.run(() -> {
            pane.getSelectionModel().select(1);
            pane.getSelectionModel().select(0);
            tabs.getFirst().graphList().getSelectionModel().clearSelection();
            tabs.getFirst().graphList().getSelectionModel().select(0);
        });
        RobotWait.untilOnFx("first tab did not show its unstaged file", () ->
                rows(tabs.getFirst(), "staging-unstaged-list").contains("shared.txt"));
        FxHarness.run(() -> FxHarness.lookupAll(tabs.getFirst().getContent(), "row-action").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "Stage all".equals(button.getText()))
                .findFirst().orElseThrow().fire());
        RobotWait.until("first tab's action did not stage its repository", () ->
                first.getStagingState().staged().equals(List.of("shared.txt")));
        assertTrue(second.getStagingState().staged().isEmpty());
        assertEquals(List.of("shared.txt"), second.getStagingState().unstaged());
        assertEquals("second edit\n", Files.readString(secondPath.resolve("shared.txt")));

        assertTrue(second.stageFile("shared.txt").isOk()); // an outside client/index change
        FxHarness.run(() -> {
            pane.getSelectionModel().select(1);
            tabs.get(1).graphList().getSelectionModel().clearSelection();
            tabs.get(1).graphList().getSelectionModel().select(0);
            tabs.get(1).refreshIfChangedExternally();
        });
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("second tab did not reflect its changed index", () ->
                rows(tabs.get(1), "staging-staged-list").contains("shared.txt"));
        assertEquals(List.of("shared.txt"), first.getStagingState().staged());
        assertEquals(firstHead, first.getHeadHash());
        assertEquals(secondHead, second.getHeadHash());
        assertEquals("first edit\n", Files.readString(firstPath.resolve("shared.txt")));
    }

    @Test
    void closingWhileAWorkerIsBlockedDefersBackendReleaseUntilThatWorkerFinishes() throws Exception {
        Path path = initialize("busy", "base\n");
        RecordingService service = open(path, new RecordingService());
        String head = service.getHeadHash();
        RepoTab tab = FxHarness.call(() -> {
            RepoTab result = tab("busy", service);
            FxHarness.showRegistered(new TabPane(result), 1000, 650);
            result.refresh();
            return result;
        });
        RobotWait.drainGitWork();
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<String> headSeenBeforeRelease = new AtomicReference<>();
        GitTaskRunner.run(tab.getGitService(), () -> {
            workerEntered.countDown();
            try {
                if (!releaseWorker.await(20, TimeUnit.SECONDS)) return GitResult.failed("Test worker was not released");
                headSeenBeforeRelease.set(service.getHeadHash());
                return GitResult.ok();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return GitResult.failed("Test worker interrupted");
            }
        }, ignored -> { });
        assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
        Platform.runLater(() -> {
            try { tab.close(); }
            finally { closeReturned.countDown(); }
        });
        // Exercise the real bounded close path (currently five seconds), rather than
        // release immediately and miss a close that takes the backend from active work.
        assertTrue(closeReturned.await(10, TimeUnit.SECONDS), "Close must return while work is pending");
        assertFalse(service.wasClosed.get(), "A close timeout must leave the active worker's repository open");
        releaseWorker.countDown();
        RobotWait.drainGitWork();
        assertEquals(head, headSeenBeforeRelease.get(), "The active operation finishes with its backend intact");
        assertTrue(service.wasClosed.get(), "Queued close must release the repository afterwards");
    }

    private RepoTab tab(String title, GitService service) {
        RepoTab result = new RepoTab(new LyraApp(), title, service);
        tabs.add(result);
        return result;
    }

    private static List<?> rows(RepoTab tab, String style) {
        return FxHarness.lookupAll(tab.getContent(), style).stream()
                .filter(ListView.class::isInstance)
                .<List<?>>map(node -> List.copyOf(((ListView<?>) node).getItems()))
                .findFirst().orElse(List.of());
    }

    private <T extends GitService> T open(Path path, T service) {
        services.add(service);
        assertTrue(service.openRepository(path.toFile()));
        return service;
    }

    private Path initialize(String name, String content) throws Exception {
        Path path = Files.createDirectories(root.resolve(name));
        try (Git git = Git.init().setDirectory(path.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Isolated QA");
            config.setString("user", null, "email", "isolation@example.test");
            config.setBoolean("core", null, "autocrlf", false);
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(path.resolve("shared.txt"), content);
            git.add().addFilepattern("shared.txt").call();
            git.commit().setMessage(name + " base").call();
        }
        return path;
    }

    private static final class RecordingService extends GitService {
        private final AtomicBoolean wasClosed = new AtomicBoolean();
        @Override public void close() { wasClosed.set(true); super.close(); }
    }
}
