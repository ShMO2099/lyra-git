package com.jfx.git;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.RobotWait;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link LyraApp#findOpenTab(List, File)} must see a tab that is still opening its
 * repository. Startup restores the last session and then opens any command-line path;
 * the restored tabs are all mid-open at that point, so matching only on the backend's
 * work tree (null until the open finishes) gave the same repository a second tab.
 */
class LyraAppOpenTabTest {

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
    }

    @Test
    void aTabStillOpeningItsRepositoryIsFoundByThatRepositorysPath() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call().close();
        File dir = tempDir.toFile();
        tab = FxHarness.call(() -> {
            RepoTab loading = new RepoTab(new LyraApp(), "Loading…");
            // Hold the tab's lane so the open queued behind it cannot finish: the tab is
            // then exactly where a restored session's tabs are when the command line is read.
            GitTaskRunner.run(loading.getGitService(), () -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return GitResult.ok("held");
            }, r -> { });
            loading.loadRepositoryAsync(dir, () -> { }, error -> { });
            return loading;
        });

        assertNull(tab.getGitService().getWorkTree(),
                "precondition: the open has not finished, so the work tree is still unknown");
        assertSame(tab, LyraApp.findOpenTab(List.of(tab), dir),
                "a second open of the same folder must focus the loading tab, not add another");
        assertNull(LyraApp.findOpenTab(List.of(tab), tempDir.resolve("elsewhere").toFile()),
                "a different folder is not the tab's repository");
    }
}
