package com.jfx.git;

import com.jfx.git.core.Settings;
import com.jfx.git.core.TabSession;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.RobotWait;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restored session paints first and opens the tab being looked at first.
 *
 * <p>Startup used to build and open every restored tab inside {@code start()} - before
 * JavaFX could draw the window - and queue them all on one worker, the visible one
 * wherever it happened to fall.
 */
class LazyTabsTest {

    @TempDir
    Path tempDir;

    private LyraApp app;
    private Stage stage;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tearDown() {
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", null);
        RobotWait.drainGitWork();
        if (stage != null) {
            FxHarness.run(() -> {
                for (Tab tab : tabs()) {
                    if (tab instanceof RepoTab repoTab) repoTab.close();
                }
                stage.close();
            });
        }
    }

    private List<Tab> tabs() {
        return ((TabPane) ((javafx.scene.layout.BorderPane) ((javafx.scene.layout.Pane)
                stage.getScene().getRoot()).getChildren().get(0)).getCenter()).getTabs();
    }

    private Path repo(String name) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(dir.resolve("a.txt"), name);
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first in " + name).setAuthor("T", "t@example.com")
                    .setCommitter("T", "t@example.com").setSign(false).call();
        }
        return dir;
    }

    @Test
    void theWindowPaintsFirstAndTheVisibleTabOpensFirst() throws Exception {
        Path alpha = repo("alpha");
        Path beta = repo("beta");
        Path gamma = repo("gamma");
        TabSession.setOpenPaths(List.of(alpha.toString(), beta.toString(), gamma.toString()), 2);

        int tabsWhenStartReturned = FxHarness.call(() -> {
            stage = new Stage();
            app = new LyraApp();
            app.start(stage);
            return tabs().size();
        });
        assertEquals(1, tabsWhenStartReturned,
                "start() returns with only the + tab: the window is drawn before any repository tab is built");

        RobotWait.untilOnFx("the restored tabs never appeared", () -> tabs().size() == 4);
        FxHarness.run(() -> assertEquals(List.of("alpha", "beta", "gamma", "＋"),
                tabs().stream().map(Tab::getText).toList(),
                "named after their folders at once, not \"Loading…\""));

        RobotWait.untilOnFx("no tab ever opened", () -> tabs().stream()
                .anyMatch(t -> t instanceof RepoTab r && r.getGitService().isOpen()));
        FxHarness.run(() -> {
            RepoTab visible = (RepoTab) tabs().get(2);
            assertTrue(visible.isSelected(), "the session's active tab is the one selected");
            assertTrue(visible.getGitService().isOpen(), "and the first to open");
        });
        RobotWait.drainGitWork();
        assertEquals(3, TabSession.openPaths().size(),
                "tabs still waiting to open stay in the session");
        assertEquals(2, TabSession.activeIndex(), "and the one in front is still the one in front");

        FxHarness.run(() -> {
            assertTrue(tabs().get(0) instanceof LyraApp.RestoredTab,
                    "a tab nobody has looked at is not built, let alone opened in the background");
            assertTrue(tabs().get(1) instanceof LyraApp.RestoredTab);
            assertTrue(tabs().get(1).getTooltip().getText().endsWith("beta"), "its path is in the tooltip");
        });

        // Looking at one builds it, in the same place, and opens it.
        FxHarness.run(() -> tabs().get(1).getTabPane().getSelectionModel().select(1));
        RobotWait.untilOnFx("selecting a restored tab never built and opened it", () ->
                tabs().get(1) instanceof RepoTab r && r.getGitService().isOpen());
        FxHarness.run(() -> {
            assertEquals(List.of("alpha", "beta", "gamma", "＋"), tabs().stream().map(Tab::getText).toList());
            assertTrue(tabs().get(1).isSelected());
            assertTrue(tabs().get(0) instanceof LyraApp.RestoredTab, "the others still wait");
        });
        RobotWait.drainGitWork();
        assertEquals(3, TabSession.openPaths().size(), "the swap leaves the session whole");
        assertEquals(1, TabSession.activeIndex(), "and records the tab now in front");
    }

    /** Opening a repository whose restored tab is still a placeholder builds that tab, not a second one. */
    @Test
    void openingARestoredRepositoryBuildsItsTab() throws Exception {
        Path alpha = repo("alpha");
        Path beta = repo("beta");
        TabSession.setOpenPaths(List.of(alpha.toString(), beta.toString()), 0);

        FxHarness.run(() -> {
            stage = new Stage();
            app = new LyraApp();
            app.start(stage);
        });
        RobotWait.untilOnFx("the restored tabs never appeared", () -> tabs().size() == 3);

        FxHarness.run(() -> app.openRepository(beta.toFile()));
        RobotWait.untilOnFx("the restored tab never opened", () ->
                tabs().get(1) instanceof RepoTab r && r.getGitService().isOpen());
        FxHarness.run(() -> {
            assertEquals(3, tabs().size(), "no second tab for the same repository");
            assertTrue(tabs().get(1).isSelected());
        });
        RobotWait.drainGitWork();
    }

    /**
     * The palette's "Switch to" lists the restored tabs too, and switching to one builds it.
     * It read only the built tabs, so a repository restored but not looked at yet could
     * not be reached from it - and was offered as a recent repository to open instead.
     */
    @Test
    void thePaletteCanSwitchToARestoredTab() throws Exception {
        Path alpha = repo("alpha");
        Path beta = repo("beta");
        TabSession.setOpenPaths(List.of(alpha.toString(), beta.toString()), 0);

        FxHarness.run(() -> {
            stage = new Stage();
            app = new LyraApp();
            app.start(stage);
        });
        RobotWait.untilOnFx("the front tab never opened", () ->
                tabs().size() == 3 && tabs().get(0) instanceof RepoTab r && r.getGitService().isOpen());
        RobotWait.drainGitWork();

        Tab waiting = FxHarness.call(() -> {
            List<LyraApp.OpenRepository> open = app.openRepositories();
            assertEquals(List.of("alpha", "beta"), open.stream().map(o -> o.tab().getText()).toList(),
                    "every repository in the strip, the one not built yet included");
            assertTrue(open.get(1).tab() instanceof LyraApp.RestoredTab);
            assertEquals(beta.toFile().getAbsolutePath(), open.get(1).directory().getAbsolutePath(),
                    "with its folder, so the palette does not offer it again as a recent repository");
            return open.get(1).tab();
        });

        FxHarness.run(() -> app.selectTab(waiting));
        RobotWait.untilOnFx("switching to a restored tab never built and opened it", () ->
                tabs().get(1) instanceof RepoTab r && r.getGitService().isOpen());
        FxHarness.run(() -> assertTrue(tabs().get(1).isSelected()));
        RobotWait.drainGitWork();
    }

    /** Closing a tab nobody opened takes it out of the next session. */
    @Test
    void closingARestoredTabForgetsIt() throws Exception {
        Path alpha = repo("alpha");
        Path beta = repo("beta");
        TabSession.setOpenPaths(List.of(alpha.toString(), beta.toString()), 0);

        FxHarness.run(() -> {
            stage = new Stage();
            app = new LyraApp();
            app.start(stage);
        });
        RobotWait.untilOnFx("the front tab never opened", () ->
                tabs().size() == 3 && tabs().get(0) instanceof RepoTab r && r.getGitService().isOpen());
        RobotWait.drainGitWork();

        FxHarness.run(() -> tabs().remove(1));
        RobotWait.until("the closed tab stayed in the session", () -> TabSession.openPaths().size() == 1);
        assertTrue(TabSession.openPaths().get(0).endsWith("alpha"), TabSession.openPaths().toString());
    }
}
