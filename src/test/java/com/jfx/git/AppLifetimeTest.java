package com.jfx.git;

import com.jfx.git.core.Settings;
import com.jfx.git.core.TabSession;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.UiZoom;

import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A finished application can be collected.
 *
 * <p>The process-wide state an application registers with - the zoom level, the crash
 * presenter, the navigator, the status bars' activity-log button - is static, and so,
 * in a test JVM, is the window: TestFX hands one primary stage to every robot test. Each
 * held the application strongly, so every one ever started stayed in memory with its
 * whole interface, about 15 MB apiece, and the suite's heap only grew.
 */
class AppLifetimeTest {

    @TempDir
    Path tempDir;

    private Stage stage;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @BeforeEach
    void aRepositoryToOpen() throws Exception {
        // A real repository tab, since that is most of what an application holds - and not
        // the working directory, which the fallback would otherwise open: this project.
        Path repo = Files.createDirectories(tempDir.resolve("demo"));
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            Files.writeString(repo.resolve("a.txt"), "a\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").setAuthor("T", "t@example.com")
                    .setCommitter("T", "t@example.com").setSign(false).call();
        }
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", repo.toString());
    }

    @AfterEach
    void tearDown() {
        if (stage != null) {
            closeRepositoryTabs(stage);
            FxHarness.run(stage::close);
        }
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", null);
    }

    @Test
    void anApplicationWhoseWindowIsClosedIsCollected() throws Exception {
        stage = FxHarness.call(Stage::new);
        WeakReference<LyraApp> app = startOn(stage);

        closeRepositoryTabs(stage);
        FxHarness.run(stage::close);
        stage = null;

        assertNull(collected(app), "a closed application must not be kept by the state it registered with");
    }

    @Test
    void anApplicationIsNotKeptByAWindowItHandedOn() throws Exception {
        // As TestFX does: the next test's application starts on the same stage.
        stage = FxHarness.call(Stage::new);
        WeakReference<LyraApp> first = startOn(stage);
        closeRepositoryTabs(stage);
        startOn(stage);

        assertNull(collected(first), "the window's close guard and focus listener must not keep the first application");
    }

    @Test
    void aZoomedContainerNothingRefersToIsCollected() throws Exception {
        WeakReference<Pane> container = FxHarness.call(() -> {
            Region content = new Region();
            Pane host = new Pane(content);
            UiZoom.install(host, content);
            return new WeakReference<>(host);
        });

        assertNull(collected(container), "the zoom level must not keep what it scales");
    }

    /** Starts an application on {@code window} and waits for its repository tab to open. */
    private static WeakReference<LyraApp> startOn(Stage window) {
        LyraApp app = FxHarness.call(() -> {
            LyraApp started = new LyraApp();
            started.start(window);
            return started;
        });
        RobotWait.untilOnFx("the repository tab never opened",
                () -> app.getActiveRepoTab() != null && app.getActiveRepoTab().getGitService().isOpen());
        RobotWait.drainGitWork();
        return new WeakReference<>(app);
    }

    private static void closeRepositoryTabs(Stage window) {
        RobotWait.drainGitWork();
        FxHarness.run(() -> FxHarness.lookupAll(window.getScene().getRoot(), "tab-pane").stream()
                .filter(javafx.scene.control.TabPane.class::isInstance)
                .map(javafx.scene.control.TabPane.class::cast)
                .flatMap(pane -> pane.getTabs().stream())
                .filter(RepoTab.class::isInstance)
                .map(RepoTab.class::cast)
                .toList()
                .forEach(RepoTab::close));
    }

    /** {@code ref}'s referent after collections have had their chance; null once it is gone. */
    private static <T> T collected(WeakReference<T> ref) throws InterruptedException {
        for (int i = 0; i < 40 && ref.get() != null; i++) {
            dropTheToolkitsLastRenderPaths();
            System.gc();
            Thread.sleep(50);
            // Work already queued on the FX thread can still hold what it was queued with.
            FxHarness.run(() -> { });
        }
        return ref.get();
    }

    /**
     * Lets go of the render-root paths JavaFX keeps from the last frames it painted.
     *
     * <h2>Why</h2>
     * The toolkit finds a render root for each dirty region of a frame and keeps the path
     * to it in a static array ({@code ViewPainter.ROOT_PATHS}), clearing each path once its
     * region is painted - but a region it skips as empty is never painted, so that path
     * stays until some later frame has as many regions again. It runs down the closed
     * window's render tree to a text node, and from there to the node's scene and what the
     * scene's handlers hold: the whole application. Held by the toolkit, not by anything
     * the application registered with, and only when the window's last frame happened to
     * end that way - which on a busy machine it now and then did, so the test failed on CI
     * and passed on every rerun. Found from a heap dump of one of those failures.
     *
     * <p>By name, because these are the toolkit's internals. If the field is ever renamed,
     * or the tests run where it cannot be reached, this does nothing: the test goes back
     * to failing now and then, rather than passing when it should not.
     */
    private static void dropTheToolkitsLastRenderPaths() {
        try {
            Field paths = Class.forName("com.sun.javafx.tk.quantum.ViewPainter").getDeclaredField("ROOT_PATHS");
            paths.setAccessible(true);
            // Emptied slots are recreated on demand; a frame painting meanwhile finds its
            // region's path empty and skips that region once.
            Arrays.fill((Object[]) paths.get(null), null);
        } catch (ReflectiveOperationException | RuntimeException unreachable) {
            // Nothing to let go of from here.
        }
    }
}
