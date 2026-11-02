package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.control.TabPane;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a change made outside the application reloads.
 *
 * <p>Any change used to reload everything - the graph page re-walked, every ref re-read,
 * the banners re-checked - including the commonest one, a file saved from an editor, which
 * changes nothing the graph or the sidebar shows.
 */
class RefreshScopeTest {

    @TempDir
    Path tempDir;

    private RepoTab tab;
    private GitService real;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tearDown() {
        RobotWait.drainGitWork();
        if (tab != null) FxHarness.run(tab::close);
        if (real != null) real.close();
        FxHarness.closeShownStages();
    }

    private List<String> openRecording(MainWindowFixture fixture) throws Exception {
        real = new GitService();
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
        return calls;
    }

    private static long count(List<String> calls, String prefix) {
        synchronized (calls) {
            return calls.stream().filter(name -> name.startsWith(prefix)).count();
        }
    }

    @Test
    void anEditToAModifiedFileDoesNotReloadTheGraph() throws Exception {
        try (MainWindowFixture fixture = MainWindowFixture.create(tempDir)) {
            List<String> calls = openRecording(fixture);
            String modified = real.getStagingState().unstaged().get(0);
            // Recorded first, so the check below compares against the state on screen.
            FxHarness.run(tab::refreshIfChangedExternally);
            RobotWait.drainGitWork();
            calls.clear();

            Files.writeString(fixture.workTree().resolve(modified), "edited again elsewhere\n");
            FxHarness.run(tab::refreshIfChangedExternally);
            RobotWait.drainGitWork();

            assertEquals(0, count(calls, "getCommitGraphPage"),
                    "the graph does not depend on the working tree: " + calls);
            assertEquals(0, count(calls, "getReflog"), "nor does the undo history: " + calls);
        }
    }

    @Test
    void aNewBranchMadeElsewhereReloadsTheGraph() throws Exception {
        try (MainWindowFixture fixture = MainWindowFixture.create(tempDir)) {
            List<String> calls = openRecording(fixture);
            FxHarness.run(tab::refreshIfChangedExternally);
            RobotWait.drainGitWork();
            calls.clear();

            try (Git git = Git.open(fixture.workTree().toFile())) {
                git.branchCreate().setName("made-in-a-terminal").call();
            }
            FxHarness.run(tab::refreshIfChangedExternally);
            RobotWait.drainGitWork();

            assertTrue(count(calls, "getCommitGraphPage") >= 1, "a ref moved, so the graph reloads: " + calls);
        }
    }

    /** A check asked for while one is running is run afterwards, not dropped. */
    @Test
    void aCheckAskedForDuringAnotherStillHappens() throws Exception {
        try (MainWindowFixture fixture = MainWindowFixture.create(tempDir)) {
            List<String> calls = openRecording(fixture);
            FxHarness.run(tab::refreshIfChangedExternally);
            RobotWait.drainGitWork();
            calls.clear();

            FxHarness.run(() -> {
                tab.refreshIfChangedExternally();
                tab.refreshIfChangedExternally();
            });
            // The second is queued from the FX thread once the first finishes, so wait for
            // the outcome rather than for the lane to empty once.
            RobotWait.until("the second check never ran", () -> count(calls, "getStagingState") >= 2);
            RobotWait.drainGitWork();

            assertEquals(2, count(calls, "getStagingState"),
                    "the second check ran after the first instead of being dropped: " + calls);
        }
    }
}
