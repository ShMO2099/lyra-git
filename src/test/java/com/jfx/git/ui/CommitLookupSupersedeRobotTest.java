package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.DelegatingGitBackend;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.SignatureStatus;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stepping through commits faster than git answers runs the lookups of the commit
 * arrived at, not of every commit passed on the way.
 *
 * <p>The details panel asks for each commit's signature - a git subprocess on the native
 * engine - and for its whole message. Both used to be queued for every commit selected:
 * holding the arrow key through 300 commits of a large repository queued 370 lookups,
 * and the panel, and "Load older commits" behind it, waited for every one.
 */
class CommitLookupSupersedeRobotTest extends ApplicationTest {

    private static final int COMMITS = 25;

    @TempDir
    Path dir;

    private CountingBackend backend;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
            for (int i = 0; i < COMMITS; i++) {
                Files.writeString(dir.resolve("a.txt"), i + "\n");
                git.add().addFilepattern("a.txt").call();
                git.commit().setMessage("Change " + i + "\n\nWhy change " + i + " was made.").call();
            }
        }
        GitService engine = new GitService();
        engine.openRepository(dir.toFile());
        backend = new CountingBackend(engine);
        tab = new RepoTab(new LyraApp(), "lookups", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    @Test
    void onlyTheCommitArrivedAtIsLookedUp() throws Exception {
        interact(() -> tab.refresh());
        RobotWait.untilOnFx("the graph never listed the commits",
                () -> commits().size() == COMMITS);
        RobotWait.drainGitWork();
        List<CommitInfo> commits = FxHarness.call(this::commits);
        backend.signatures.set(0);
        backend.messages.set(0);

        // Git answers slower than the selection moves: everything queued while the lane
        // is busy is still waiting when the next commit is selected.
        CountDownLatch release = holdTheLane();
        FxHarness.run(() -> commits.forEach(tab.getRightPanel()::showCommitDetails));
        release.countDown();
        RobotWait.drainGitWork();

        assertEquals(1, backend.signatures.get(),
                "one signature check, for the commit arrived at, not one per commit passed");
        assertEquals(1, backend.messages.get(),
                "one message read, for the commit arrived at, not one per commit passed");
        CommitInfo last = commits.get(commits.size() - 1);
        RobotWait.untilOnFx("the panel never showed the last commit's body", () -> {
            Label body = (Label) tab.getTabPane().getScene().lookup(".commit-body");
            return body != null && Objects.equals(CommitInfo.bodyOf(fullMessage(last)).strip(), body.getText());
        });
    }

    private List<CommitInfo> commits() {
        return tab.getCommitList().getItems().stream()
                .map(GitGraphRow::getCommit)
                .filter(Objects::nonNull)
                .toList();
    }

    private String fullMessage(CommitInfo commit) {
        return backend.engine.getCommitMessage(commit.getHash());
    }

    /** Occupies the repository's lane until released, as a slow git command would. */
    private CountDownLatch holdTheLane() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        FxHarness.run(() -> GitTaskRunner.run(tab.getGitService(), () -> {
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GitResult.ok();
        }, result -> { }));
        assertTrue(running.await(10, TimeUnit.SECONDS), "the lane never picked up the hold");
        return release;
    }

    /** The JGit engine, counting the per-commit lookups the details panel makes. */
    private static final class CountingBackend extends DelegatingGitBackend {
        private final GitBackend engine;
        final AtomicInteger signatures = new AtomicInteger();
        final AtomicInteger messages = new AtomicInteger();

        CountingBackend(GitBackend engine) {
            this.engine = engine;
        }

        @Override protected GitBackend reader() { return engine; }
        @Override protected GitBackend writer() { return engine; }

        @Override public boolean openRepository(java.io.File directory) throws com.jfx.git.core.GitOperationException {
            return engine.openRepository(directory);
        }
        @Override public boolean isOpen() { return engine.isOpen(); }
        @Override public void close() { engine.close(); }
        @Override public java.io.File getWorkTree() { return engine.getWorkTree(); }
        @Override public java.io.File getDirectory() { return engine.getDirectory(); }

        @Override public SignatureStatus getSignatureStatus(String hash) {
            signatures.incrementAndGet();
            return super.getSignatureStatus(hash);
        }
        @Override public String getCommitMessage(String hash) {
            messages.incrementAndGet();
            return super.getCommitMessage(hash);
        }
    }
}
