package com.jfx.git;

import com.jfx.git.core.ActivityLog;
import com.jfx.git.core.Settings;
import com.jfx.git.core.TabSession;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.ui.ActivityLogTab;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.SettingsSection;
import com.jfx.git.ui.ToolTab;
import com.jfx.git.ui.WelcomeTab;
import com.jfx.git.ui.settings.SettingsTab;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings and the activity log are tabs of the main window, not windows of their own.
 *
 * <p>Each is opened from wherever the application offers it, at most once - asking again
 * brings the open one forward - and stops listening to the rest of the application when
 * it is closed. Neither is a repository: they are not numbered, not remembered in the
 * session, and closing the last repository with one of them open leaves the user on it.
 */
@Tag("heavy")
class ToolTabsTest {

    @TempDir
    Path tempDir;

    private LyraApp app;
    private Stage stage;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @BeforeEach
    void startTheApplication() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("demo"));
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            Files.writeString(repo.resolve("a.txt"), "a");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").setAuthor("T", "t@example.com")
                    .setCommitter("T", "t@example.com").setSign(false).call();
        }
        // No session to restore, so the window opens on this one repository.
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", repo.toString());
        FxHarness.run(() -> {
            stage = new Stage();
            app = new LyraApp();
            app.start(stage);
        });
        RobotWait.untilOnFx("the repository never opened", () ->
                app.getActiveRepoTab() != null && app.getActiveRepoTab().getGitService().isOpen());
        RobotWait.drainGitWork();
    }

    @AfterEach
    void tearDown() {
        RobotWait.drainGitWork();
        TabSession.setOpenPaths(List.of(), 0);
        Settings.put("repo.last", null);
        GitHubSession.disconnect();
        if (stage != null) {
            FxHarness.run(() -> {
                for (Tab tab : List.copyOf(tabs())) {
                    if (tab instanceof RepoTab repoTab) repoTab.close();
                    if (tab instanceof ToolTab tool) tool.dispose();
                }
                stage.close();
            });
        }
    }

    private List<Tab> tabs() {
        return ((TabPane) ((javafx.scene.layout.BorderPane) ((javafx.scene.layout.Pane)
                stage.getScene().getRoot()).getChildren().get(0)).getCenter()).getTabs();
    }

    private <T> List<T> tabsOf(Class<T> type) {
        return tabs().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private Tab selected() {
        return tabs().get(0).getTabPane().getSelectionModel().getSelectedItem();
    }

    /** A real key press with Ctrl (and Shift) held, where the application's keyboard map hears it. */
    private void press(KeyCode code, boolean shift) {
        FxHarness.run(() -> Event.fireEvent(stage.getScene().getRoot(), new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", code, shift, true, false, false)));
    }

    /**
     * Presses {@code tab}'s own close button, the way a user closes it: the strip's close
     * request and the tab's closed event both run, which removing it from the list would skip.
     */
    private void clickCloseButtonOf(Tab tab) {
        RobotWait.untilOnFx("the tab never got a close button", () -> closeButtonOf(tab) != null);
        FxHarness.run(() -> {
            Node button = closeButtonOf(tab);
            Event.fireEvent(button, new MouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, false, null));
            Event.fireEvent(button, new MouseEvent(MouseEvent.MOUSE_RELEASED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                    false, false, false, false, false, false, false, false, false, false, null));
        });
    }

    /** The close button in the header showing {@code tab}'s title, or null before the skin has built it. */
    private Node closeButtonOf(Tab tab) {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        for (Node button : stage.getScene().getRoot().lookupAll(".tab-close-button")) {
            for (Node up = button.getParent(); up != null; up = up.getParent()) {
                if (up.getStyleClass().contains("tab")) {
                    Node label = up.lookup(".tab-label");
                    if (label instanceof Label l && tab.getText().equals(l.getText())) {
                        return button;
                    }
                    break;
                }
            }
        }
        return null;
    }

    /** The File menu's item called {@code text}. */
    private javafx.scene.control.MenuItem fileMenuItem(String text) {
        javafx.scene.control.MenuBar bar = (javafx.scene.control.MenuBar) ((javafx.scene.layout.BorderPane)
                ((javafx.scene.layout.Pane) stage.getScene().getRoot()).getChildren().get(0)).getTop();
        return bar.getMenus().stream().filter(m -> m.getText().equals("File")).findFirst().orElseThrow()
                .getItems().stream().filter(i -> text.equals(i.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no File > " + text));
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    @Test
    void settingsIsOneTabOpenedAtTheSectionAskedFor() {
        int windowsBefore = FxHarness.call(() -> (int) Window.getWindows().stream().filter(Window::isShowing).count());

        FxHarness.run(() -> app.openSettings(SettingsSection.SSH));
        FxHarness.run(() -> {
            assertEquals(1, tabsOf(SettingsTab.class).size());
            SettingsTab settings = tabsOf(SettingsTab.class).get(0);
            assertSame(settings, selected(), "opened in front");
            assertEquals(SettingsSection.SSH, settings.selectedSection());
            assertEquals("Settings — Lyra", stage.getTitle(), "the window says what is in front");
            assertEquals("＋", tabs().get(tabs().size() - 1).getText(), "and the ＋ stays last");
        });

        // Elsewhere, then asked for again at another section: the same tab, moved there.
        FxHarness.run(() -> app.selectTab(tabsOf(RepoTab.class).get(0)));
        FxHarness.run(() -> app.openSettings(SettingsSection.INTEGRATIONS));
        FxHarness.run(() -> {
            assertEquals(1, tabsOf(SettingsTab.class).size(), "asked twice, one tab");
            SettingsTab settings = tabsOf(SettingsTab.class).get(0);
            assertSame(settings, selected());
            assertEquals(SettingsSection.INTEGRATIONS, settings.selectedSection());
        });

        assertEquals(windowsBefore,
                FxHarness.call(() -> (int) Window.getWindows().stream().filter(Window::isShowing).count()),
                "no window was opened for it");
    }

    @Test
    void ctrlCommaOpensSettingsWhereItWasAndCtrlSlashAtTheShortcuts() {
        press(KeyCode.COMMA, false);
        FxHarness.run(() -> {
            SettingsTab settings = tabsOf(SettingsTab.class).get(0);
            assertSame(settings, selected());
            assertEquals(SettingsSection.GENERAL, settings.selectedSection(), "a new Settings tab starts at General");
            settings.select(SettingsSection.DIFF);
            app.selectTab(tabsOf(RepoTab.class).get(0));
        });

        press(KeyCode.COMMA, false);
        FxHarness.run(() -> assertEquals(SettingsSection.DIFF, tabsOf(SettingsTab.class).get(0).selectedSection(),
                "Ctrl+, names no section, so the page the user left is the one they come back to"));

        press(KeyCode.SLASH, false);
        FxHarness.run(() -> {
            SettingsTab settings = tabsOf(SettingsTab.class).get(0);
            assertEquals(SettingsSection.KEYBOARD_SHORTCUTS, settings.selectedSection());
            List<String> texts = FxHarness.lookupAll(settings.page(SettingsSection.KEYBOARD_SHORTCUTS), "t-body")
                    .stream().map(n -> ((Label) n).getText()).toList();
            assertTrue(texts.contains("Command palette"), "the sheet reads the live keyboard map: " + texts);
            assertTrue(texts.contains("Settings"), texts::toString);
        });
    }

    /**
     * The sidebar's "Repository settings…" is Settings' Repository section, on the
     * repository it was asked from - one tab, not a view in the repository's tab.
     */
    @Test
    void repositorySettingsOpenSettingsOnTheRepositoryAskedFrom() {
        RepoTab repo = FxHarness.call(() -> tabsOf(RepoTab.class).get(0));
        FxHarness.run(() -> repo.getSidebar().openRepositorySettings());
        FxHarness.run(() -> {
            SettingsTab settings = tabsOf(SettingsTab.class).get(0);
            assertSame(settings, selected());
            assertEquals(SettingsSection.REPOSITORY, settings.selectedSection());
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<com.jfx.git.ui.settings.RepositoryPage.Target> picker =
                    (javafx.scene.control.ComboBox<com.jfx.git.ui.settings.RepositoryPage.Target>)
                            settings.page(SettingsSection.REPOSITORY)
                                    .lookup("#" + com.jfx.git.ui.settings.RepositoryPage.PICKER_ID);
            assertSame(repo, picker.getValue().owner(), "on the repository it was asked from");
            assertTrue(repo.currentView() == null, "nothing opened in the repository's own tab");
        });
        RobotWait.drainGitWork();

        // Opened again from a repository, Settings is the same tab, moved to that repository.
        FxHarness.run(() -> app.selectTab(repo));
        FxHarness.run(() -> app.openRepositorySettings(repo.getGitService().getWorkTree()));
        FxHarness.run(() -> assertEquals(1, tabsOf(SettingsTab.class).size()));
        RobotWait.drainGitWork();
    }

    /**
     * Closing Settings stops its Integrations page following the provider sessions: a
     * session connected afterwards reaches no page, which it would if the page were still
     * listening - and kept alive by the listener.
     */
    @Test
    void closingSettingsDisposesIt() {
        FxHarness.run(() -> app.openSettings(SettingsSection.INTEGRATIONS));
        SettingsTab settings = FxHarness.call(() -> tabsOf(SettingsTab.class).get(0));
        Node integrations = FxHarness.call(() -> settings.page(SettingsSection.INTEGRATIONS));

        clickCloseButtonOf(settings);
        RobotWait.untilOnFx("the Settings tab never closed", () -> tabsOf(SettingsTab.class).isEmpty());
        FxHarness.run(() -> {
            assertTrue(tabsOf(WelcomeTab.class).isEmpty(),
                    "closing Settings goes back to the repository, not to a new launchpad");
            assertSame(tabsOf(RepoTab.class).get(0), selected());
        });

        GitHubSession.connect("token", new GitHubUser("octocat", null));
        FxHarness.run(() -> { });
        FxHarness.run(() -> assertFalse(FxHarness.lookupAll(integrations, "state-chip").stream()
                        .anyMatch(n -> ((Label) n).getText().contains("octocat")),
                "a closed Settings tab no longer follows the sessions"));
    }

    // ------------------------------------------------------------------
    // The activity log
    // ------------------------------------------------------------------

    @Test
    void theActivityLogIsOneTabShowingTheRepositoryItWasOpenedFrom() {
        press(KeyCode.L, true);
        FxHarness.run(() -> {
            assertEquals(1, tabsOf(ActivityLogTab.class).size());
            ActivityLogTab log = tabsOf(ActivityLogTab.class).get(0);
            assertSame(log, selected());
            assertEquals("demo", log.repository(), "the repository in front when it was asked for");
        });

        // Asked for from the log itself there is no repository in front: it stays put.
        FxHarness.run(() -> app.openActivityLog());
        FxHarness.run(() -> {
            assertEquals(1, tabsOf(ActivityLogTab.class).size(), "asked twice, one tab");
            assertEquals("demo", tabsOf(ActivityLogTab.class).get(0).repository());
        });
    }

    @Test
    void closingTheActivityLogStopsItFollowingTheLog() {
        FxHarness.run(() -> app.openActivityLog());
        ActivityLogTab log = FxHarness.call(() -> tabsOf(ActivityLogTab.class).get(0));

        clickCloseButtonOf(log);
        RobotWait.untilOnFx("the log tab never closed", () -> tabsOf(ActivityLogTab.class).isEmpty());

        ActivityLog.note("after the log closed");
        FxHarness.run(() -> { });
        FxHarness.run(() -> {
            for (Node node : FxHarness.lookupAll(log.getContent(), "activity-log-list")) {
                assertFalse(((ListView<?>) node).getItems().stream()
                                .anyMatch(item -> String.valueOf(item).contains("after the log closed")),
                        "a closed log tab no longer repaints");
            }
        });
    }

    // ------------------------------------------------------------------
    // Living beside the repositories
    // ------------------------------------------------------------------

    @Test
    void applicationTabsAreNotRememberedInTheSession() {
        FxHarness.run(() -> {
            app.openSettings(SettingsSection.GENERAL);
            app.openActivityLog();
        });
        RobotWait.drainGitWork();
        FxHarness.run(() -> { });

        assertEquals(1, TabSession.openPaths().size(), "only the repository: " + TabSession.openPaths());
        assertTrue(TabSession.openPaths().get(0).endsWith("demo"), TabSession.openPaths().toString());
        assertEquals(0, TabSession.activeIndex());
    }

    /**
     * Closing the last repository with Settings open leaves Settings in front - there is
     * still something to look at, so no launchpad is added - and closing Settings then,
     * with nothing left, opens the launchpad rather than an empty strip.
     */
    @Test
    void closingTheLastRepositoryWithSettingsOpenLeavesTheUserOnSettings() {
        FxHarness.run(() -> app.openSettings(SettingsSection.GENERAL));
        FxHarness.run(() -> app.closeTab(tabsOf(RepoTab.class).get(0)));
        FxHarness.run(() -> {
            assertTrue(tabsOf(RepoTab.class).isEmpty());
            assertTrue(tabsOf(WelcomeTab.class).isEmpty(), "Settings is still there to look at");
            assertSame(tabsOf(SettingsTab.class).get(0), selected());
            assertEquals(2, tabs().size(), "Settings and the ＋");
        });

        // Ctrl+W - File > Close - with Settings in front closes Settings. Fired as the menu
        // item: Ctrl+W is the item's accelerator, and Monocle cannot match accelerators.
        FxHarness.run(() -> fileMenuItem("Close Repository").fire());
        RobotWait.untilOnFx("Ctrl+W never closed Settings", () -> tabsOf(SettingsTab.class).isEmpty());
        FxHarness.run(() -> {
            assertEquals(1, tabsOf(WelcomeTab.class).size(), "with nothing left, the launchpad");
            assertSame(tabsOf(WelcomeTab.class).get(0), selected());
        });
    }
}
