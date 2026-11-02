package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ProfileStore;
import com.jfx.git.core.Settings;
import com.jfx.git.core.TabSession;
import com.jfx.git.model.Profile;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the real Profiles menu events and verifies per-repository apply/reopen behavior. */
class IncompleteQaProfileMenuRobotTest extends ApplicationTest {
    private static String previousJavafxCache;

    @BeforeAll
    static void keepLoadedPlatformLibrariesOutsideTheDisposableHome() throws Exception {
        previousJavafxCache = System.getProperty("javafx.cachedir");
        Path cache = Files.createDirectories(Path.of("target", "test-javafx-native-cache").toAbsolutePath());
        // Windows holds loaded DLLs until the fork exits, after JUnit has deleted @TempDir.
        System.setProperty("javafx.cachedir", cache.toString());
    }

    @AfterAll
    static void restorePlatformLibraryCache() {
        if (previousJavafxCache == null) System.clearProperty("javafx.cachedir");
        else System.setProperty("javafx.cachedir", previousJavafxCache);
    }

    @TempDir Path temp;
    private Stage stage;
    private LyraApp app;
    private Path repoA;
    private Path repoB;
    private String originalUserHome;
    private String originalUserDirectory;
    private Thread.UncaughtExceptionHandler previousDefaultHandler;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        Settings.useFile(temp.resolve("settings.properties"));
        Path isolatedHome = Files.createDirectories(temp.resolve("home"));
        originalUserHome = System.getProperty("user.home");
        originalUserDirectory = System.getProperty("user.dir");
        previousDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        System.setProperty("user.home", isolatedHome.toString());
        System.setProperty("user.dir", isolatedHome.toString());
        TabSession.setOpenPaths(List.of(), 0);
        repoA = initRepo("repo-a");
        repoB = initRepo("repo-b");
        ProfileStore.save(new Profile("QA Work", "Synthetic Work", "work@example.test", "", false));
        app = new LyraApp();
        app.start(stage);
        stage.setWidth(800);
        stage.setHeight(600);
    }

    @AfterEach
    void cleanup() {
        try {
            RobotWait.drainGitWork();
            if (app != null) {
                com.jfx.git.core.SshSessionManager.factoryForKey(null);
                interact(() -> app.openRepositories().stream().map(LyraApp.OpenRepository::tab)
                        .filter(RepoTab.class::isInstance).map(RepoTab.class::cast)
                        .toList().forEach(app::closeTab));
            }
            com.jfx.git.core.SshSessionManager.shutdown();
            com.jfx.git.core.CrashReporter.install(report -> { });
            Thread.setDefaultUncaughtExceptionHandler(previousDefaultHandler);
            Settings.flush();
            Settings.useFile(null);
            // The stage is TestFX's primary stage, shared by every ApplicationTest in the
            // JVM, and JavaFX keeps the 800x600 set on it through later setScene and show
            // calls: every class after this one showed its scene in an 800-pixel window,
            // where RepoToolbarRobotTest found its 1200-pixel toolbar compact. sizeToScene,
            // while the window still shows, hands the size back to the scenes.
            if (stage != null) interact(stage::sizeToScene);
            interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
            org.eclipse.jgit.lib.RepositoryCache.clear();
        } finally {
            if (originalUserHome != null) System.setProperty("user.home", originalUserHome);
            if (originalUserDirectory != null) System.setProperty("user.dir", originalUserDirectory);
        }
    }

    @Test
    void applyingFromTheMenuChangesOnlyTheActiveRepoAndOpenProfileTabsReopensIt() throws Exception {
        RobotWait.untilOnFx("initial launchpad did not finish opening", () ->
                lookup(".tab-pane").queryAs(TabPane.class).getTabs().stream()
                        .anyMatch(tab -> tab instanceof WelcomeTab));
        interact(() -> {
            app.openRepository(repoA.toFile());
            app.openRepository(repoB.toFile());
        });
        RobotWait.untilOnFx("both repository tabs did not open", () -> app.openRepositories().size() == 2
                && app.getActiveRepoTab() != null && app.getActiveRepoTab().getGitService().isOpen());
        interact(() -> app.openRepository(repoA.toFile()));
        RobotWait.untilOnFx("repo A was not selected", () -> app.getActiveRepoTab() != null
                && repoA.toFile().equals(app.getActiveRepoTab().getGitService().getWorkTree()));

        Menu profiles = profilesMenu();
        fireShowing(profiles);
        RadioMenuItem work = profiles.getItems().stream().filter(RadioMenuItem.class::isInstance)
                .map(RadioMenuItem.class::cast).filter(item -> item.getText().equals("QA Work"))
                .findFirst().orElseThrow(() -> new AssertionError("profile switcher did not offer QA Work"));
        interact(work::fire);
        RobotWait.drainGitWork();

        assertEquals("Synthetic Work", localIdentity(repoA, "name"));
        assertEquals("work@example.test", localIdentity(repoA, "email"));
        assertNotEquals("Synthetic Work", localIdentity(repoB, "name"), "profile application is tab scoped");
        assertEquals("QA Work", ProfileStore.activeProfileName(ProfileStore.key(repoA.toFile())));
        assertEquals(List.of(ProfileStore.key(repoA.toFile())), ProfileStore.tabPaths("QA Work"));

        RepoTab tabA = app.getActiveRepoTab();
        interact(() -> app.closeTab(tabA));
        RobotWait.untilOnFx("repo A tab did not close", () -> app.openRepositories().stream()
                .noneMatch(repo -> repoA.toFile().equals(repo.directory())));
        profiles = profilesMenu();
        fireShowing(profiles);
        MenuItem reopen = profiles.getItems().stream().filter(item -> "Open \"QA Work\" tabs (1)".equals(item.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("profile reopen action is missing"));
        interact(reopen::fire);
        RobotWait.untilOnFx("profile action did not reopen repo A", () -> app.openRepositories().stream()
                .anyMatch(repo -> repoA.toFile().equals(repo.directory())
                        && repo.tab() instanceof RepoTab reopened && reopened.getGitService().isOpen()));
        assertEquals("Synthetic Work", localIdentity(repoA, "name"), "reopen must preserve the repo-local identity");
        assertTrue(app.openRepositories().stream().anyMatch(repo -> repoB.toFile().equals(repo.directory())),
                "opening profile tabs is additive and leaves unrelated tabs open");
    }

    private Menu profilesMenu() {
        return ((MenuBar) lookup(".menu-bar").query()).getMenus().stream()
                .filter(menu -> menu.getText().equals("Profiles")).findFirst().orElseThrow();
    }

    private void fireShowing(Menu menu) {
        interact(() -> Event.fireEvent(menu, new Event(Menu.ON_SHOWING)));
    }

    private Path initRepo(String name) throws Exception {
        Path dir = Files.createDirectory(temp.resolve(name));
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Synthetic Default");
            config.setString("user", null, "email", "default@example.test");
            config.setBoolean("core", null, "autocrlf", false);
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(dir.resolve("seed.txt"), name + " fixture\n");
            git.add().addFilepattern("seed.txt").call();
            git.commit().setMessage("seed " + name).call();
        }
        return dir;
    }

    private String localIdentity(Path repo, String field) throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().getConfig().getString("user", null, field);
        }
    }
}
