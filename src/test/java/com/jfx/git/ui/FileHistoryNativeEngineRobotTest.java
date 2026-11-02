package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.core.ThreadGuard;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * File History driven against the <em>CLI</em> engine.
 *
 * <h2>Why a second file-history robot test</h2>
 * {@link FileHistoryEndToEndRobotTest} covers what the view renders, and does it on JGit -
 * where every backend call is an in-process object read. That is the right engine for
 * checking pixels and the wrong one for checking threads: {@link ThreadGuard} fires at the
 * subprocess spawn in {@link NativeGitRunner}, so a JGit test satisfies the
 * no-git-on-the-FX-thread rule without ever testing it.
 *
 * <p>This view is exactly where that mattered. {@code DiffViewer} takes its whole-file
 * content and image blobs as {@code Function}s and calls them on the FX thread; for the
 * main panel those read a working-tree file, but here they mean {@code git show} against a
 * historical commit. The fix reads ahead on the worker - and a fix to a threading bug that
 * only ever runs on the engine without threads to get wrong is not a verified fix.
 *
 * <p>So the assertions below are ordinary ones about content, and the real subject is the
 * rule {@code TestEnvironment} applies after every test: if any of this had spawned git on
 * the thread that draws, these fail and name the call site.
 */
class FileHistoryNativeEngineRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private NativeGitBackend backend;
    private RepoTab tab;

    private static String text(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }

    private static List<String> numbered(int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            lines.add("line" + i);
        }
        return lines;
    }

    @Override
    public void start(Stage stage) throws Exception {
        // Declared fixture setup: start() is the FX thread, and building a repository here
        // is git on it. See ThreadGuard.buildingFixture().
        try (var _ = ThreadGuard.buildingFixture()) {
            NativeGitRunner init = new NativeGitRunner(tempDir.toFile());
            if (!init.run("init", "-b", "main").isOk()) {
                init.run("init");
            }
            init.run("config", "user.name", "Test User");
            init.run("config", "user.email", "test@example.com");
            init.run("config", "core.autocrlf", "false");

            // Two commits far enough apart that the diff has a gap, so the rendered rows
            // carry an expander - the control whose click used to read a blob on the FX
            // thread.
            List<String> v1 = numbered(40);
            commit(init, text(v1), "v1 - initial file");

            List<String> v2 = new ArrayList<>(v1);
            v2.set(2, "line3-edited");
            v2.set(36, "line37-edited");
            commit(init, text(v2), "v2 - two edits with a wide gap between them");
        }

        backend = new NativeGitBackend();
        assertTrue(backend.openRepository(tempDir.toFile()));

        tab = new RepoTab(new LyraApp(), "test", backend);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    private void commit(NativeGitRunner git, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve("History.java"), content, StandardCharsets.UTF_8);
        assertTrue(git.run("add", "History.java").isOk());
        assertTrue(git.run("commit", "-m", message).isOk());
    }

    @AfterEach
    void tidyUp() {
        // Every selection runs git subprocesses on the lane; let the last one finish before
        // the backend is closed under it.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (backend != null) {
            backend.close();
        }
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits until whatever the last selection started has been drawn: the reads run on the
     * git lane and render in a callback on the FX thread, so drain the one and flush the
     * other. The callback is also where git on the FX thread would happen, so it must have
     * run before the test ends and the TestEnvironment rule looks.
     */
    private void awaitRender() {
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void openHistory() {
        interact(() -> tab.openFileHistory("History.java", false));
        // Two hops: the commit list loads, then selects the newest commit, whose diff and
        // read-ahead are a second round of reads.
        RobotWait.untilOnFx("the file's history never loaded", () -> FxHarness
                .lookupAll(tab.getContent(), "file-history-list").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.ListView<?> list
                        && !list.getItems().isEmpty()));
        awaitRender();
    }

    private List<Node> expanders() {
        return FxHarness.lookupAll(tab.getContent(), "diff-expander-toggle");
    }

    /**
     * The path the round-9 fix changed: the expander needs the file as it stood at the
     * selected commit, which used to be fetched when it was clicked.
     */
    @Test
    void expandingContextInHistoryNeverRunsGitOnTheFxThread() {
        openHistory();

        // A diff with a wide unchanged middle renders an expander for the hidden lines.
        RobotWait.untilOnFx("no expander was rendered", () -> !expanders().isEmpty());
        assertFalse(expanders().isEmpty(),
                "the fixture's two distant edits should leave a collapsed gap to expand");

        Node caret = expanders().get(0);
        clickOn(caret);
        settle();

        // Whether the click revealed rows is FileHistoryEndToEndRobotTest's subject; here
        // it only has to have happened without freezing the thread that drew it. The
        // TestEnvironment rule is what fails this test if it did.
        assertNotNull(tab.getContent());
    }

    /** Selecting through history, which is what re-fills the read-ahead each time. */
    @Test
    void browsingHistoryNeverRunsGitOnTheFxThread() {
        openHistory();

        var commits = FxHarness.lookupAll(tab.getContent(), "list-view").stream()
                .filter(n -> n instanceof javafx.scene.control.ListView<?>)
                .map(n -> (javafx.scene.control.ListView<?>) n)
                .filter(list -> !list.getItems().isEmpty())
                .findFirst()
                .orElse(null);
        assumeCommitsAreListed(commits);

        for (int i = 0; i < commits.getItems().size(); i++) {
            int index = i;
            interact(() -> commits.getSelectionModel().select(index));
            awaitRender();
        }

        assertTrue(commits.getItems().size() >= 2, "both fixture commits should be listed");
    }

    private static void assumeCommitsAreListed(javafx.scene.control.ListView<?> commits) {
        assertNotNull(commits, "the history view should list this file's commits");
    }
}
