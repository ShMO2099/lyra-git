package com.jfx.git.ui;

import com.jfx.git.core.GitService;
import com.jfx.git.core.Settings;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.testsupport.MainWindowFixture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The first graph page, kept between sessions: it must come back drawing exactly the same. */
class GraphPageCacheTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @BeforeEach
    void isolate() {
        Settings.useFile(tempDir.resolve("settings.properties"));
    }

    @AfterEach
    void restore() {
        Settings.useFile(null);
    }

    private GraphPage livePage(File repo) {
        GitService service = new GitService();
        try {
            service.openRepository(repo);
            return service.getCommitGraphPageVisible(1000);
        } finally {
            service.close();
        }
    }

    private String loadedSignature(File repo) throws Exception {
        CompletableFuture<String> loaded = new CompletableFuture<>();
        GraphPageCache.load(repo, RepoTab::signatureOf, loaded::complete);
        return loaded.get(10, TimeUnit.SECONDS);
    }

    /** Merges, tags, a stash, uncommitted work: the fixture repository has every row kind. */
    @Test
    void aSavedPageComesBackDrawingTheSame() throws Exception {
        try (MainWindowFixture fixture = MainWindowFixture.create(tempDir.resolve("fixture"))) {
            File repo = fixture.workTree().toFile();
            GraphPage page = livePage(repo);
            assertTrue(page.rows().size() > 5, "a page worth keeping");

            GraphPageCache.save(repo, page, "main");

            assertEquals(RepoTab.signatureOf(page, "main"), loadedSignature(repo),
                    "the rebuilt page draws exactly what the live one did");
        }
    }

    /**
     * The ref snapshot the page carries comes back with it - the current branch, the
     * branches, remotes and stashes - so the kept page is laid out and labelled from the
     * same refs as the live one.
     */
    @Test
    void aSavedPageKeepsItsRefSnapshot() throws Exception {
        try (MainWindowFixture fixture = MainWindowFixture.create(tempDir.resolve("fixture"))) {
            File repo = fixture.workTree().toFile();
            GraphPage page = livePage(repo);
            assertTrue(!page.refs().localBranches().isEmpty(), "a snapshot worth keeping");

            GraphPageCache.save(repo, page, page.refs().currentBranch());

            CompletableFuture<com.jfx.git.model.RefSnapshot> loaded = new CompletableFuture<>();
            GraphPageCache.load(repo, (kept, branch) -> kept.refs(), loaded::complete);
            assertEquals(page.refs(), loaded.get(10, TimeUnit.SECONDS));
        }
    }

    /** A damaged file is no page at all - and is removed, not read again next time. */
    @Test
    void aDamagedFileIsIgnored() throws Exception {
        File repo = Files.createDirectories(tempDir.resolve("nowhere")).toFile();
        Path file = GraphPageCache.fileFor(repo);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{0, 0, 0, 1, 5, 6, 7});

        CompletableFuture<String> loaded = new CompletableFuture<>();
        GraphPageCache.load(repo, RepoTab::signatureOf, loaded::complete);

        RobotWait.until("the damaged file was never cleared up", () -> !Files.exists(file));
        assertFalse(loaded.isDone(), "nothing was handed on");
    }
}
