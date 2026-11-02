package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitService;
import com.jfx.git.core.LfsOps;
import com.jfx.git.core.NativeGitBackend;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Git LFS view against a real repository, on the engine that can run git-lfs and
 * on one that cannot.
 */
class LfsViewTest {

    @TempDir
    Path root;

    private Path workTree;
    private GitBackend backend;
    private final AtomicInteger changes = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        FxHarness.start();
        workTree = Files.createDirectory(root.resolve("work"));
        try (Git git = Git.init().setDirectory(workTree.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(workTree.resolve("a.txt"), "a\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
        }
    }

    @AfterEach
    void tearDown() {
        RobotWait.drainGitWork();
        if (backend != null) {
            backend.close();
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private LfsView open(GitBackend engine) throws Exception {
        backend = engine;
        assertTrue(backend.openRepository(workTree.toFile()));
        LfsView view = FxHarness.call(() -> new LfsView(backend, changes::incrementAndGet));
        // The view reads the repository's state on the git lane when it opens.
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the view never finished reading the repository",
                () -> !"Checking…".equals(label(view, LfsView.STATUS_ID).getText()));
        return view;
    }

    private static Label label(LfsView view, String id) {
        return (Label) view.content().lookup("#" + id);
    }

    @SuppressWarnings("unchecked")
    private static ListView<String> patterns(LfsView view) {
        return (ListView<String>) view.content().lookup("#" + LfsView.PATTERNS_ID);
    }

    private static Button button(LfsView view, String text) {
        return (Button) FxHarness.lookupAll(view.content(), "button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("no \"" + text + "\" button"));
    }

    private void track(LfsView view, String pattern) {
        FxHarness.run(() -> {
            ((TextField) view.content().lookup("#" + LfsView.PATTERN_FIELD_ID)).setText(pattern);
            button(view, "Track").fire();
        });
        RobotWait.drainGitWork();
    }

    @Test
    void aPatternIsTrackedListedAndThenReleasedAgain() throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled() && NativeGitRunner.isGitLfsInstalled(), "needs git and git-lfs");
        LfsView view = open(new NativeGitBackend());
        FxHarness.run(() -> assertEquals(List.of(), List.copyOf(patterns(view).getItems())));

        track(view, "*.psd");
        RobotWait.untilOnFx("the pattern never appeared in the list",
                () -> List.of("*.psd").equals(List.copyOf(patterns(view).getItems())));
        assertTrue(Files.readString(workTree.resolve(".gitattributes")).contains("*.psd filter=lfs"));
        FxHarness.run(() -> assertEquals("",
                ((TextField) view.content().lookup("#" + LfsView.PATTERN_FIELD_ID)).getText(),
                "the box is cleared for the next pattern"));
        assertTrue(changes.get() > 0, "the tab is told, since .gitattributes has just changed");

        FxHarness.run(() -> {
            patterns(view).getSelectionModel().select("*.psd");
            button(view, "Stop tracking").fire();
        });
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the pattern never left the list", () -> patterns(view).getItems().isEmpty());
    }

    /** The view does not hide what an engine cannot do; the engine's refusal says what would work. */
    @Test
    void onAnEngineThatCannotRunLfsTheRefusalIsShownAndThePatternsAreStillRead() throws Exception {
        Files.writeString(workTree.resolve(".gitattributes"), "*.bin filter=lfs diff=lfs merge=lfs -text\n");
        LfsView view = open(new GitService());
        FxHarness.run(() -> assertEquals(List.of("*.bin"), List.copyOf(patterns(view).getItems()),
                "reading .gitattributes needs no git-lfs"));

        track(view, "*.psd");

        RobotWait.untilOnFx("the refusal was never shown",
                () -> label(view, LfsView.RESULT_ID).getText().contains(LfsOps.NEEDS_NATIVE));
        FxHarness.run(() -> assertEquals(List.of("*.bin"), List.copyOf(patterns(view).getItems())));
    }

    @Test
    void trackingNeedsAPatternToTrack() throws Exception {
        LfsView view = open(new GitService());

        FxHarness.run(() -> {
            assertTrue(button(view, "Track").isDisabled(), "nothing typed yet");
            assertTrue(button(view, "Stop tracking").isDisabled(), "nothing selected yet");
            Node field = view.content().lookup("#" + LfsView.PATTERN_FIELD_ID);
            ((TextField) field).setText("*.psd");
            assertTrue(!button(view, "Track").isDisabled());
        });
    }
}
