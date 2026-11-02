package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.HybridGitBackend;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.model.StagingState;
import com.jfx.git.model.SubmoduleState;
import com.jfx.git.testsupport.LocalGitDaemon;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The visible graph and staging state follow a Hybrid submodule lifecycle in one tab. */
class ManualQaSubmoduleRefreshRobotTest extends ApplicationTest {

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path root;

    private Path repositoryPath;
    private Path libraryPath;
    private LocalGitDaemon gitDaemon;
    private String libraryUrl;
    private HybridGitBackend backend;
    private RepoTab tab;

    static {
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @BeforeEach
    void requireNativeGit() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "Hybrid submodule UI coverage requires native Git");
    }

    @Override
    public void start(Stage stage) throws Exception {
        repositoryPath = root.resolve("superproject");
        Path libraryWork = root.resolve("library-work");
        Path exportRoot = root.resolve("git-exports");
        libraryPath = exportRoot.resolve("library.git");
        try (var fixture = ThreadGuard.buildingFixture()) {
            initialize(repositoryPath, "app.txt", "main\n");
            createLibraryRepository(libraryWork, libraryPath, "library\n");
            gitDaemon = LocalGitDaemon.start(exportRoot);
            libraryUrl = gitDaemon.url("library.git");
            try (Git superproject = Git.open(repositoryPath.toFile())) {
                try (var ignored = superproject.submoduleAdd()
                        .setURI(libraryUrl)
                        .setPath("vendor/library")
                        .call()) {
                    // The nested checkout is now a real submodule in the superproject.
                }
                superproject.add().addFilepattern(".gitmodules")
                        .addFilepattern("vendor/library").call();
                superproject.commit().setMessage("add library").call();
            }
            backend = new HybridGitBackend();
            assertTrue(backend.openRepository(repositoryPath.toFile()));
            assertTrue(backend.getNativeBackend().isOpen(), "Hybrid CLI status writer remains open");
            assertTrue(backend.getJgitBackend().isOpen(), "Hybrid JGit graph reader remains open");
        }

        tab = new RepoTab(new LyraApp(), "submodule-refresh", backend);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        try {
            RobotWait.drainGitWork();
            if (tab != null) interact(tab::close);
            else if (backend != null) backend.close();
            interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
            org.eclipse.jgit.lib.RepositoryCache.clear();
        } finally {
            // Last, and whatever else failed: an unstopped daemon outlives the whole run.
            if (gitDaemon != null) gitDaemon.close();
        }
    }

    @Test
    void deinitAndReinitRefreshTheSameTabsGraphAndStagingState() throws Exception {
        refreshAndWait();
        String head = backend.getHeadHash();
        assertCleanGraphAndStatus();

        openSubmoduleMenu();
        clickOn("Deinitialise…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Deinitialise");
        RobotWait.until("the submodule was not deinitialised", () ->
                !Files.exists(repositoryPath.resolve("vendor/library/library.txt"))
                        && backend.getSubmodules().stream().anyMatch(module ->
                        module.path().equals("vendor/library")
                                && module.state() == SubmoduleState.UNINITIALIZED));

        RobotWait.drainGitWork();
        assertEquals(head, backend.getHeadHash());
        refreshAndWait();
        assertCleanGraphAndStatus();

        openSubmoduleMenu();
        clickOn("Init / update");
        RobotWait.until("the submodule was not reinitialized", () ->
                Files.isRegularFile(repositoryPath.resolve("vendor/library/library.txt"))
                        && backend.getSubmodules().stream().anyMatch(module ->
                        module.path().equals("vendor/library")
                                && module.state() == SubmoduleState.CURRENT));

        RobotWait.drainGitWork();
        assertEquals("library\n", Files.readString(repositoryPath.resolve("vendor/library/library.txt")));
        assertEquals(head, backend.getHeadHash());
        refreshAndWait();
        assertCleanGraphAndStatus();

        // A real gitlink change is still dirty. This guards against fixing deinit by
        // suppressing every submodule from status or from the graph's WIP decision.
        Path modulePath = repositoryPath.resolve("vendor/library");
        try (Git module = Git.open(modulePath.toFile())) {
            Files.writeString(modulePath.resolve("library.txt"), "library changed\n");
            module.add().addFilepattern("library.txt").call();
            module.commit().setMessage("advance library")
                    .setAuthor("Manual QA Test", "manual-qa@example.test")
                    .setCommitter("Manual QA Test", "manual-qa@example.test")
                    .setSign(false).call();
        }
        assertTrue(backend.stageFile("vendor/library").isOk(), "stage the changed gitlink");
        refreshAndWait();
        StagingState changedGitlink = backend.getStagingState();
        assertTrue(changedGitlink.staged().contains("vendor/library"), changedGitlink::toString);
        StagingState jgitChangedGitlink = backend.getJgitBackend().getStagingState();
        assertTrue(jgitChangedGitlink.staged().contains("vendor/library"), jgitChangedGitlink::toString);
        assertTrue(backend.getJgitBackend().hasUncommittedChanges(),
                "JGit must retain a real staged gitlink change");
        RobotWait.untilOnFx("a changed submodule gitlink did not produce a WIP row", () ->
                tab.graphList().getItems().stream().anyMatch(GitGraphRow::isWipNode));
        assertNotNull(tab.getGraphic(), "a changed submodule gitlink should badge the tab");
        assertEquals("uncommitted changes", tab.getGraphic().getAccessibleText());
    }

    private void assertCleanGraphAndStatus() {
        StagingState state = backend.getStagingState();
        assertClean(state, "Hybrid status writer");
        StagingState jgitState = backend.getJgitBackend().getStagingState();
        assertClean(jgitState, "JGit graph reader");
        assertFalse(backend.getJgitBackend().hasUncommittedChanges(),
                "JGit dirty check must agree with a clean deinitialised submodule");
        RobotWait.untilOnFx("the visible commit graph retained a stale WIP row", () ->
                tab.graphList().getItems().stream().noneMatch(GitGraphRow::isWipNode));
        RobotWait.untilOnFx("a clean repository retained its dirty tab badge", () -> tab.getGraphic() == null);
    }

    private static void assertClean(StagingState state, String engine) {
        assertTrue(state.staged().isEmpty(), engine + ": " + state);
        assertTrue(state.unstaged().isEmpty(), engine + ": " + state);
        assertTrue(state.conflicting().isEmpty(), engine + ": " + state);
        assertFalse(state.hasUncommittedChanges(), engine + ": " + state);
    }

    private void refreshAndWait() {
        interact(tab::refresh);
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void openSubmoduleMenu() {
        TitledPane[] foundSection = new TitledPane[1];
        RobotWait.untilOnFx("no SUBMODULES section appeared", () -> {
            foundSection[0] = FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                    .filter(node -> node instanceof TitledPane pane && headingOf(pane).startsWith("SUBMODULES"))
                    .map(TitledPane.class::cast)
                    .findFirst().orElse(null);
            return foundSection[0] != null;
        });
        TitledPane section = foundSection[0];
        if (!section.isExpanded()) clickOn(section);
        Node[] foundRow = new Node[1];
        RobotWait.untilOnFx("no vendor/library row appeared", () -> {
            foundRow[0] = FxHarness.lookupAll(tab.getContent(), "list-cell").stream()
                    .filter(node -> node instanceof javafx.scene.control.ListCell<?> cell
                            && cell.getText() != null && cell.getText().contains("vendor/library"))
                    .findFirst().orElse(null);
            return foundRow[0] != null;
        });
        Node row = foundRow[0];
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        RobotWait.untilOnFx("the submodule action menu did not appear", () ->
                lookup(".context-menu").tryQuery().isPresent());
    }

    private static String headingOf(TitledPane pane) {
        if (pane.getGraphic() instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (child instanceof javafx.scene.control.Label label
                        && label.getText() != null && !label.getText().isBlank()) {
                    return label.getText();
                }
            }
        }
        return pane.getText() == null ? "" : pane.getText();
    }

    private static void initialize(Path directory, String fileName, String content) throws Exception {
        Files.createDirectories(directory);
        try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Manual QA Robot");
            config.setString("user", null, "email", "manual-qa@example.test");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(directory.resolve(fileName), content);
            git.add().addFilepattern(fileName).call();
            git.commit().setMessage("initial").call();
        }
    }

    /** The test remote stays on loopback; attributes pin checkout bytes across Git configs. */
    private void createLibraryRepository(Path workTree, Path bareRepository, String content) throws Exception {
        initialize(workTree, "library.txt", content);
        Files.writeString(workTree.resolve(".gitattributes"), "* text eol=lf\n");
        try (Git work = Git.open(workTree.toFile())) {
            work.add().addFilepattern(".gitattributes").call();
            work.commit().setMessage("enforce LF checkout").call();
        }
        Files.createDirectories(bareRepository.getParent());
        try (Git bare = Git.cloneRepository().setURI(workTree.toString())
                .setDirectory(bareRepository.toFile()).setBare(true).call()) {
            assertEquals("main", bare.getRepository().getBranch());
        }
    }

}
