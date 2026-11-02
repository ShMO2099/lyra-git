package com.jfx.git;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.ui.dialogs.NewRepositoryDialog;
import com.jfx.git.ui.CommandPalette;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.WelcomeTab;
import com.jfx.git.core.RecentRepositories;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.dialogs.BitbucketRepoPickerDialog;
import com.jfx.git.ui.dialogs.DialogParts;
import com.jfx.git.ui.dialogs.GitHubRepoPickerDialog;
import com.jfx.git.ui.dialogs.GitLabRepoPickerDialog;
import com.jfx.git.ui.dialogs.JfxDialog;
import com.jfx.git.ui.help.About;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class LyraApp extends Application implements com.jfx.git.ui.AppNavigator.Target {

    private static final Logger LOGGER = Logger.getLogger(LyraApp.class.getName());

    private Stage primaryStage;
    private MenuBar menuBar;
    private TabPane tabPane;
    /** Pinned trailing tab that acts as the "new tab" button. */
    private Tab newTabButton;

    /** The strip's own rules: pinning "＋" last, cycling, and numbering. */
    private com.jfx.git.ui.TabStrip tabStrip;
    /** Suppresses the session write while the session is being read back in. */
    private boolean restoringTabs;

    /**
     * The repository tab most recently in front - what Settings' Repository section
     * starts on, since Settings itself is in front by the time it asks.
     */
    private RepoTab lastRepoTab;

    /**
     * The last guard for the editor: quitting with unsaved edits asks, per tab that has
     * them, rather than discarding. Cancel on any one tab keeps the window open.
     *
     * <p>This and {@link #refreshOnReturn} are held here and registered on the window
     * weakly. The window can outlive the application - TestFX hands one primary stage to
     * every robot test in a JVM - and a strong registration kept every application ever
     * started on it, each with its whole scene graph, for the rest of the run.
     */
    private final javafx.event.EventHandler<javafx.stage.WindowEvent> closeGuard = e -> {
        for (Tab t : tabPane.getTabs()) {
            if (t instanceof RepoTab repoTab && !repoTab.confirmClose()) {
                e.consume();
                return;
            }
        }
    };

    /**
     * Coming back to the window is the moment to find out what happened while it was
     * not being looked at. Another git client, a terminal, an IDE's own commit button -
     * none of them tell this application anything, and until now none of them had to:
     * it only ever refreshed after its own operations, so a repository changed elsewhere
     * left the file lists showing a snapshot while every diff they opened was read live.
     * The tab decides whether anything actually moved; this only says when to ask.
     */
    private final javafx.beans.value.ChangeListener<Boolean> refreshOnReturn = (obs, was, focused) -> {
        if (!focused) return;
        RepoTab active = getActiveRepoTab();
        if (active != null) {
            active.refreshIfChangedExternally();
        }
    };

    @Override
    public void start(Stage primaryStage) {
        startupBegan = System.nanoTime();
        startupMark("start() entered");
        this.primaryStage = primaryStage;
        // Every dialog from here on belongs to this window: none gets a taskbar button of
        // its own, or falls behind it. See DialogOwner.
        com.jfx.git.ui.DialogOwner.useMainWindow(primaryStage);
        com.jfx.git.ui.AppNavigator.use(this);

        com.jfx.git.ui.ThemeManager.applySaved();

        // JGit's pack cache, sized for a desktop reading one large history rather than a
        // server holding hundreds. Its defaults - 10 MB of pack data and 10 MB of delta
        // bases - are a server's per-repository share, and a repository whose packs are
        // larger re-reads them on every graph page, diff and blame. A twentieth of the
        // heap, between 32 and 256 MB, and a quarter of that for delta bases. No gain
        // showed on a 20,000-commit synthetic history, whose whole pack fits the default;
        // the case this is for is a pack that does not.
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        long packLimit = Math.max(32L << 20, Math.min(256L << 20, Runtime.getRuntime().maxMemory() / 20));
        config.setPackedGitLimit(packLimit);
        config.setDeltaBaseCacheLimit((int) Math.max(8L << 20, packLimit / 4));
        // Memory-mapped pack files stay off on Windows, where a mapping locks the file
        // against the git CLI and other clients.
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            config.setPackedGitMMAP(false);
        }
        config.install();

        // Without this, git@host: remotes fail with "Transport not supported" - JGit
        // ships no SSH implementation of its own. On its own thread: building it loads
        // Apache MINA and the ssh-agent connector's JNA, none of which the first frame
        // needs, and every remote operation waits for it - see installInBackground.
        com.jfx.git.core.SshSessionManager.installInBackground();

        // Uncaught exceptions reach the user instead of stderr. JavaFX's own handler
        // prints and carries on, which leaves a window that has quietly stopped doing
        // something with no way to find out what. Nothing is sent anywhere - see
        // CrashReporter.
        //
        // This application reaches the crash presenter and the status bars' activity-log
        // button below only through a weak reference: both are static and outlive it - a
        // test JVM starts dozens - and holding the application itself kept the last one
        // alive, with every window and tab it had built.
        java.lang.ref.WeakReference<LyraApp> self = new java.lang.ref.WeakReference<>(this);
        com.jfx.git.core.CrashReporter.install(report -> {
            LyraApp app = self.get();
            if (app != null) app.showCrashReport(report);
        });

        // No TokenStore.warmUp() any more. The keychain detection it ran - two PowerShell
        // launches on Windows - is now an in-process DPAPI call there, and the restore
        // of remembered integrations below asks the same question on the provider pool
        // anyway; two threads asking at once only queued one behind the other.

        // The git engine is chosen by loading libgit2 - hashing and binding a native
        // library - or by asking `git --version`. Started now, it is usually settled by
        // the time the first tab asks for an engine; otherwise the first tab waited for it
        // on this thread.
        com.jfx.git.core.GitBackendFactory.warmUp();

        // Every status bar and notification can open the activity log.
        com.jfx.git.ui.StatusBar.setActivityLogOpener(() -> {
            LyraApp app = self.get();
            if (app != null) app.showActivityLog();
        });

        // At most once a day, and only when a feed is named in Preferences.
        if (com.jfx.git.core.UpdateChecker.isDue(java.time.Instant.now())) {
            checkForUpdates(false);
        }

        // Editor drafts are crash artefacts, not a document store. Anything a month old,
        // or belonging to a repository that is gone, is swept now rather than left to
        // accumulate as plaintext source under the user's profile.
        com.jfx.git.core.EditorDrafts.sweepInBackground();

        // Reconnects whatever the user asked to be remembered, off this thread: three
        // HTTPS round trips on the way to a window would be three round trips of blank
        // screen. See IntegrationSessions.
        com.jfx.git.core.IntegrationSessions.restoreInBackground();

        BorderPane root = new BorderPane();
        menuBar = buildMenuBar();
        root.setTop(menuBar);

        tabPane = new TabPane();
        root.setCenter(tabPane);

        // A pinned "+" tab that spawns a new launchpad page when picked, mirroring
        // the tab strip in commercial Git clients.
        newTabButton = new Tab("＋");
        newTabButton.setClosable(false);
        tabPane.getTabs().add(newTabButton);
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == newTabButton) {
                openWelcomeTab();
            } else if (selected instanceof RepoTab repoTab) {
                lastRepoTab = repoTab;
                // A tab whose open is still held back opens when it is first looked at.
                if (repoTab.openIfPending()) return;
                // A tab that has been in the background for ten minutes is exactly as
                // stale as a window that has; the same check answers both.
                repoTab.refreshIfChangedExternally();
            } else if (selected instanceof RestoredTab restored) {
                // Built once this selection change has finished: swapping tabs inside the
                // notification is the re-entrant list edit TabStrip warns about, and a
                // selection that moves on in the meantime builds nothing.
                Platform.runLater(() -> {
                    if (tabPane.getSelectionModel().getSelectedItem() == restored) {
                        materialize(restored);
                    }
                });
            }
        });

        // Tabs reorder by dragging, as commercial clients' do; TabStrip keeps the "＋" last and
        // records the session once the strip has settled.
        tabStrip = new com.jfx.git.ui.TabStrip(tabPane, newTabButton);
        tabStrip.install(this::rememberOpenTabs);

        // The zoom scales the whole window, so the interface sits inside a plain pane that
        // exists only to give the scaled content something to be measured against.
        javafx.scene.layout.Pane zoomHost = new javafx.scene.layout.Pane(root);
        com.jfx.git.ui.UiZoom.install(zoomHost, root);

        Scene scene = new Scene(zoomHost, 1280, 840);
        try {
            scene.getStylesheets().add(getClass().getResource("/css/lyra.css").toExternalForm());
        } catch (Exception ex) {
            LOGGER.warning("Could not load css/lyra.css");
        }
        // Registering after the app stylesheet keeps the token file first in the list.
        com.jfx.git.ui.ThemeManager.register(scene);

        buildShortcuts(primaryStage).installOn(scene);

        // A stage handed over again - as tests do - may still be bound to an old tab.
        primaryStage.titleProperty().unbind();
        primaryStage.setTitle("Lyra");
        // The window says which repository and branch it is showing - in the taskbar and
        // the window switcher too, where "Lyra" six times over said nothing.
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            primaryStage.titleProperty().unbind();
            if (selected instanceof RepoTab repoTab) {
                primaryStage.titleProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                        () -> repoTab.windowTitleProperty().get().isEmpty()
                                ? "Lyra" : repoTab.windowTitleProperty().get() + " — Lyra",
                        repoTab.windowTitleProperty()));
            } else if (selected instanceof com.jfx.git.ui.ToolTab tool) {
                primaryStage.setTitle(tool.getText() + " — Lyra");
            } else {
                primaryStage.setTitle("Lyra");
            }
        });

        // A folder dropped on the window opens as a repository, the way a file dropped on
        // an editor opens. Branch drags carry text, not files, so they pass through.
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()
                    && e.getDragboard().getFiles().stream().anyMatch(File::isDirectory)) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                e.consume();
            }
        });
        scene.setOnDragDropped(e -> {
            if (!e.getDragboard().hasFiles()) return;
            boolean opened = false;
            for (File dropped : e.getDragboard().getFiles()) {
                if (dropped.isDirectory()) {
                    openRepository(dropped);
                    opened = true;
                }
            }
            e.setDropCompleted(opened);
            e.consume();
        });
        primaryStage.setScene(scene);
        if (launchedFromMain) {
            installWindowMinimum();
        }
        primaryStage.addEventHandler(javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST,
                new javafx.event.WeakEventHandler<>(closeGuard));
        primaryStage.focusedProperty().addListener(
                new javafx.beans.value.WeakChangeListener<>(refreshOnReturn));

        primaryStage.show();
        startupMark("shown");

        // A second copy started now is refused (see main) and asks this one to come forward.
        if (instance != null) {
            instance.answerShowRequests(() -> Platform.runLater(() -> {
                LyraApp app = self.get();
                if (app != null) app.bringToFront();
            }));
        }

        // The first line of the application log, so a session's timeline has a
        // beginning - "the app was restarted at some point" is otherwise invisible in a
        // log that only records repository work.
        com.jfx.git.core.ActivityLog.note("Lyra started");

        // The tabs are built after the first frame, not before it. JavaFX paints only
        // after start() returns, so every tab built here - a graph, a sidebar, a text
        // editor each - was time the window spent unpainted. A runLater was not enough:
        // queued straight after show(), it could run before the first pulse, and the
        // window still waited for the tabs. So it waits for that pulse - its layout pass,
        // after which the frame goes to the renderer - and only then queues the tabs.
        afterFirstLayout(scene, () -> {
            startupMark("first layout pulse");
            Platform.runLater(this::openStartupTabs);
        });
    }

    /**
     * Runs {@code action} once, after the next layout pass of {@code scene}.
     *
     * <p>A window that is not showing gets no pulses, so there the action is only
     * queued - a stage that never appears must still end up with its tabs.
     */
    private static void afterFirstLayout(Scene scene, Runnable action) {
        if (scene.getWindow() == null || !scene.getWindow().isShowing()) {
            Platform.runLater(action);
            return;
        }
        Runnable[] once = new Runnable[1];
        once[0] = () -> {
            scene.removePostLayoutPulseListener(once[0]);
            action.run();
        };
        scene.addPostLayoutPulseListener(once[0]);
        // A pulse only happens when something asks for one; showing a new scene does,
        // but nothing guarantees it has not already been taken by the time this runs.
        Platform.requestNextPulse();
    }

    /** When start() was entered, for the startup marks; 0 before then. */
    private static long startupBegan;

    /**
     * One timing line for measuring startup: the milliseconds since start() was entered,
     * and since the process started. FINE, so free unless asked for; run with
     * {@code -Dlyra.startupTiming=true} to have them at INFO. Marked: start() entered,
     * the window shown, its first layout pulse, and the startup tabs being built.
     */
    static void startupMark(String what) {
        java.util.logging.Level level = Boolean.getBoolean("lyra.startupTiming")
                ? java.util.logging.Level.INFO : java.util.logging.Level.FINE;
        if (!LOGGER.isLoggable(level)) {
            return;
        }
        long sinceStart = (System.nanoTime() - startupBegan) / 1_000_000;
        String sinceProcess = ProcessHandle.current().info().startInstant()
                .map(began -> java.time.Duration.between(began, java.time.Instant.now()).toMillis() + " ms")
                .orElse("?");
        LOGGER.log(level, "startup: " + what + " at +" + sinceStart + " ms (process +" + sinceProcess + ")");
    }

    /** What the window opens on: the last session, a repository named on the command line, or the launchpad. */
    private void openStartupTabs() {
        startupMark("openStartupTabs begin");
        try {
            if (TrainingRun.repository() != null) {
                // An AOT training run (package-*.sh --aot): one known repository, nothing
                // from the user's session, and out once its first page is drawn.
                if (repositoryRootOf(TrainingRun.repository()) == null) {
                    // Not the offer to make one: nobody is there to answer it. A failed
                    // exit, so the packaging script stops rather than ship that cache.
                    LOGGER.severe("Training run: " + TrainingRun.repository() + " is not a repository");
                    System.exit(1);
                    return;
                }
                loadRepository(TrainingRun.repository());
                TrainingRun.exitWhenDrawn(this);
                return;
            }
            openStartupTabsFromSession();
        } finally {
            startupMark("openStartupTabs end");
        }
    }

    private void openStartupTabsFromSession() {
        // Last session's whole strip first; the single-repository fallbacks only apply
        // when there was nothing to restore, so reopening never duplicates a tab.
        boolean restored = restoreTabs();
        // An explicit `lyra /path/to/repo` beats every fallback below it, including
        // whatever the session happened to restore - it is a direct request, not a guess.
        // getParameters() is null unless the real JavaFX launcher started this
        // Application - which is exactly the case in every robot test, where TestFX
        // constructs it directly. Twelve of them died here on an NPE that had nothing to
        // do with what they were testing.
        File commandLineRepo = repositoryFromArgs(
                getParameters() == null ? List.of() : getParameters().getRaw());
        if (commandLineRepo != null) {
            loadRepository(commandLineRepo);
        } else if (!restored) {
            String lastRepo = com.jfx.git.core.Settings.get("repo.last", null);
            if (lastRepo != null && new File(lastRepo).exists()) {
                loadRepository(new File(lastRepo));
            } else {
                File cwd = new File(System.getProperty("user.dir"));
                if (looksLikeRepository(cwd)) {
                    loadRepository(cwd);
                }
            }
        }
        // Always leave the user on something: the launchpad, never an empty window
        // or the error dialog the old startup path produced for a non-repository.
        if (tabPane.getTabs().size() == 1) {
            openWelcomeTab();
        }
    }

    /**
     * The first argument that resolves to an existing, git-managed directory -
     * what commercial clients do with {@code <app> /path/to/repo}. A leading {@code -}
     * is skipped rather than treated as a path, in case a future flag is ever added
     * ahead of it.
     *
     * <p>A pure function of the raw argument list rather than reading {@link
     * javafx.application.Application#getParameters()} itself, so it can be tested
     * directly - {@code getParameters()} is only ever populated by the real JavaFX
     * launcher, which nothing in this codebase's test suite drives {@code
     * LyraApp} through. The caller guards for the null that follows from that.
     */
    static File repositoryFromArgs(List<String> args) {
        for (String arg : args) {
            if (arg == null || arg.isBlank() || arg.startsWith("-")) {
                continue;
            }
            File candidate = new File(arg);
            if (!candidate.isDirectory()) {
                continue;
            }
            File root = repositoryRootOf(candidate);
            if (root != null) {
                return root;
            }
        }
        return null;
    }

    private MenuBar buildMenuBar() {
        Menu fileMenu = new Menu("File");

        MenuItem openItem = new MenuItem("Open Repository…");
        openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        openItem.setOnAction(e -> openRepoDialog());

        MenuItem initItem = new MenuItem("New Repository…");
        initItem.setOnAction(e -> initRepoDialog());

        MenuItem cloneItem = new MenuItem("Clone Repository…");
        cloneItem.setOnAction(e -> cloneRepoDialog());

        // Acts on the repository in front; with none open there is nothing to apply to.
        MenuItem applyPatch = new MenuItem("Apply Patch…");
        applyPatch.setOnAction(e -> {
            RepoTab active = getActiveRepoTab();
            if (active != null) {
                active.promptApplyPatch();
            }
        });
        fileMenu.setOnShowing(e -> applyPatch.setDisable(getActiveRepoTab() == null));

        MenuItem closeTab = new MenuItem("Close Repository");
        closeTab.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        closeTab.setOnAction(e -> {
            RepoTab active = getActiveRepoTab();
            if (active != null) {
                closeTab(active);
            } else if (tabPane.getSelectionModel().getSelectedItem() instanceof com.jfx.git.ui.ToolTab tool) {
                // Ctrl+W closes what is in front; with Settings there, that is Settings.
                closeToolTab(tool);
            }
        });

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> Platform.exit());

        fileMenu.getItems().addAll(openItem, initItem, cloneItem,
                new SeparatorMenuItem(), applyPatch,
                new SeparatorMenuItem(), closeTab, exitItem);

        Menu profileMenu = buildProfilesMenu();

        Menu integrationsMenu = new Menu("Integrations");
        MenuItem manageIntegrations = new MenuItem("Manage Cloud Integrations…");
        manageIntegrations.setOnAction(e -> openSettings(com.jfx.git.ui.SettingsSection.INTEGRATIONS));
        integrationsMenu.getItems().add(manageIntegrations);

        MenuItem preferences = new MenuItem("Settings…");
        preferences.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Shortcut+,"));
        preferences.setOnAction(e -> showSettings());
        fileMenu.getItems().add(fileMenu.getItems().size() - 1, preferences);
        fileMenu.getItems().add(fileMenu.getItems().size() - 1, new SeparatorMenuItem());

        Menu viewMenu = new Menu("View");
        // A radio group rather than a "Dark mode" checkbox: the label then says which
        // theme is active, instead of which one you are about to get.
        ToggleGroup themes = new ToggleGroup();
        java.util.Map<com.jfx.git.ui.ThemeManager.Theme, RadioMenuItem> themeItems =
                new java.util.EnumMap<>(com.jfx.git.ui.ThemeManager.Theme.class);
        com.jfx.git.ui.ThemeManager.Base group = null;
        for (com.jfx.git.ui.ThemeManager.Theme theme : com.jfx.git.ui.ThemeManager.Theme.values()) {
            // The dark themes, a rule, the light ones: the same grouping as Preferences.
            if (group != null && group != theme.base()) {
                viewMenu.getItems().add(new SeparatorMenuItem());
            }
            group = theme.base();
            RadioMenuItem item = new RadioMenuItem(theme.displayName());
            item.setToggleGroup(themes);
            item.setOnAction(e -> com.jfx.git.ui.ThemeManager.apply(theme));
            themeItems.put(theme, item);
            viewMenu.getItems().add(item);
        }
        // Ticked when the menu opens rather than once when it is built: the theme can be
        // changed in Preferences or by the palette's toggle, and the menu would go on
        // showing the one that was in effect at startup.
        viewMenu.setOnShowing(e -> themeItems.forEach((theme, item) ->
                item.setSelected(theme == com.jfx.git.ui.ThemeManager.current())));
        themeItems.get(com.jfx.git.ui.ThemeManager.current()).setSelected(true);
        // Also in Undo's own menu, which is where it belongs - but that button is off
        // when there is nothing to undo, and the reflog must stay reachable then.
        MenuItem reflogItem = new MenuItem("Branch History (Reflog)…");
        reflogItem.setOnAction(e -> withRepo(tab -> tab.getToolbar().openReflog()));
        viewMenu.getItems().addAll(new SeparatorMenuItem(), reflogItem);

        // No "Git Engine" menu. Which library runs git is a setting - Preferences has a
        // page for it - not something a menu bar should offer between View and Profiles
        // as if it were a thing people switch during the day.

        Menu helpMenu = new Menu("Help");
        MenuItem activityItem = new MenuItem("Activity Logs");
        activityItem.setAccelerator(new KeyCodeCombination(
                KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        activityItem.setOnAction(e -> showActivityLog());
        MenuItem shortcutsItem = new MenuItem("Keyboard Shortcuts");
        shortcutsItem.setAccelerator(new KeyCodeCombination(KeyCode.SLASH, KeyCombination.SHORTCUT_DOWN));
        shortcutsItem.setOnAction(e -> openSettings(com.jfx.git.ui.SettingsSection.KEYBOARD_SHORTCUTS));
        // The palette is where every command is, and it had no menu entry: it could only
        // be found by already knowing Ctrl+P.
        MenuItem paletteItem = new MenuItem("Command Palette");
        paletteItem.setAccelerator(new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN));
        paletteItem.setOnAction(e -> showCommandPalette(primaryStage));
        MenuItem updatesItem = new MenuItem("Check for Updates…");
        updatesItem.setOnAction(e -> checkForUpdates(true));
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> About.showAboutDialog());
        helpMenu.getItems().addAll(paletteItem, activityItem, shortcutsItem,
                new SeparatorMenuItem(), updatesItem, aboutItem);

        MenuBar menuBar = new MenuBar();
        menuBar.getMenus().addAll(fileMenu, viewMenu, profileMenu, integrationsMenu, helpMenu);
        return menuBar;
    }

    /**
     * Shows a crash the user can copy, and nothing else.
     *
     * <p>Deliberately not modal-and-fatal: the exception has already happened and the
     * rest of the application is usually still working, so taking the window away would
     * lose more than it protects. The one thing offered is the report itself.
     *
     * <p>Called from whichever thread crashed, so it hops to the FX thread itself.
     */
    void showCrashReport(com.jfx.git.core.CrashReporter.Report report) {
        Platform.runLater(() -> {
            // Copying is not an answer to the dialog: a footer button that leaves it open
            // and says it worked, where a "Copy report" button type copied and closed in one.
            javafx.scene.control.Button copy = new javafx.scene.control.Button("Copy report",
                    com.jfx.git.ui.Icons.copy());
            copy.setOnAction(e -> {
                var content = new javafx.scene.input.ClipboardContent();
                content.putString(report.details());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                copy.setText("Copied");
            });
            String summary = report.summary();
            com.jfx.git.ui.dialogs.Dialogs.error("Unexpected error")
                    .title("Something went wrong")
                    // The exception's own words, set as code: they are a class and a message.
                    .subtitle(summary.contains("`") ? summary : "`" + summary + "`")
                    .message("Lyra is probably still usable. To report this, copy the report:"
                            + " nothing is sent anywhere.")
                    .details(report.details())
                    .footerLeft(copy)
                    // Stack frames are long lines; at the narrow size they scroll sideways.
                    .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD)
                    .answer();
        });
    }

    // ------------------------------------------------------------------
    // Repository actions
    // ------------------------------------------------------------------

    /**
     * Selects the open tab of {@code type}, or adds the one {@code create} builds just
     * before the "+" and selects that. One of each kind: Settings asked for twice is the
     * same tab brought forward, not a second copy of every setting.
     */
    public <T extends com.jfx.git.ui.ToolTab> T showToolTab(Class<T> type, java.util.function.Supplier<T> create) {
        for (Tab tab : tabPane.getTabs()) {
            if (type.isInstance(tab)) {
                tabPane.getSelectionModel().select(tab);
                return type.cast(tab);
            }
        }
        T tab = create.get();
        // The tab's own close button; closeToolTab covers Ctrl+W, which bypasses it.
        tab.setOnClosed(e -> tab.dispose());
        tabPane.getTabs().add(tabPane.getTabs().size() - 1, tab);
        tabPane.getSelectionModel().select(tab);
        return tab;
    }

    /**
     * Closes an application tab the way its close button would, for Ctrl+W - which has to
     * do it by hand, since removing a tab from the strip fires none of its close events.
     */
    private void closeToolTab(com.jfx.git.ui.ToolTab tab) {
        tabPane.getTabs().remove(tab);
        tab.dispose();
        if (tabPane.getTabs().size() == 1) {
            openWelcomeTab();
        }
    }

    /** {@link com.jfx.git.ui.AppNavigator}: the Settings tab at {@code section}. */
    @Override
    public void openSettings(com.jfx.git.ui.SettingsSection section) {
        showSettings().select(section);
    }

    /**
     * The Settings tab, brought forward on whichever section it was showing - File >
     * Preferences and Ctrl+, name no section, and jumping back to General from the page
     * the user had open would lose their place.
     */
    public com.jfx.git.ui.settings.SettingsTab showSettings() {
        return showToolTab(com.jfx.git.ui.settings.SettingsTab.class,
                () -> new com.jfx.git.ui.settings.SettingsTab(this::applyPreferencesToTabs,
                        this::applyEngineToTabs, () -> shortcuts == null ? List.of() : shortcuts.bindings(),
                        settingsRepositories(), this::showTransientHint));
    }

    /** {@link com.jfx.git.ui.AppNavigator}: Settings at the Repository section, on {@code repository}. */
    @Override
    public void openRepositorySettings(File repository) {
        showSettings().selectRepository(repository);
    }

    /**
     * The open repositories as Settings' Repository section sees them: the tabs that
     * have been built - a restored tab nobody has looked at yet has no backend to ask -
     * each with a way to refresh it and to open its Git LFS view.
     */
    private com.jfx.git.ui.settings.RepositoryPage.Repositories settingsRepositories() {
        return new com.jfx.git.ui.settings.RepositoryPage.Repositories() {
            @Override
            public List<com.jfx.git.ui.settings.RepositoryPage.Target> open() {
                List<com.jfx.git.ui.settings.RepositoryPage.Target> open = new java.util.ArrayList<>();
                for (OpenRepository repository : openRepositories()) {
                    if (repository.tab() instanceof RepoTab repoTab && repository.directory() != null) {
                        open.add(new com.jfx.git.ui.settings.RepositoryPage.Target(repoTab,
                                repository.directory(), repoTab.getGitService(), repoTab::refresh, () -> {
                                    selectTab(repoTab);
                                    repoTab.getSidebar().promptLfs();
                                }));
                    }
                }
                return open;
            }

            @Override
            public File current() {
                RepoTab last = lastRepoTab;
                if (last == null || !tabPane.getTabs().contains(last)) return null;
                File workTree = last.getGitService().getWorkTree();
                return workTree != null ? workTree : last.getRequestedDirectory();
            }
        };
    }

    /** {@link com.jfx.git.ui.AppNavigator}: the activity log. */
    @Override
    public void openActivityLog() {
        showActivityLog();
    }

    /** Adds a launchpad page and selects it. */
    public WelcomeTab openWelcomeTab() {
        WelcomeTab welcome = new WelcomeTab(this);
        tabPane.getTabs().add(tabPane.getTabs().size() - 1, welcome);
        tabPane.getSelectionModel().select(welcome);
        return welcome;
    }

    /** Opens {@code dir}, replacing the active launchpad page if there is one. */
    public void openRepository(File dir) {
        loadRepository(dir);
    }

    public void openRepoDialog() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Git Repository");
        File selectedDir = chooser.showDialog(primaryStage);
        if (selectedDir != null) {
            loadRepository(selectedDir);
        }
    }

    /**
     * Asks the release feed whether there is a newer version.
     *
     * <p>Unprompted, at startup, it says something only when there is an update, and
     * then as a link in the status bar. Asked for from the Help menu it always answers -
     * including when no feed is set, since "nothing happened" is not an answer to a menu
     * item: "up to date" in the status bar, anything else in a dialog.
     *
     * @param asked whether the user asked, as opposed to the daily check
     */
    private void checkForUpdates(boolean asked) {
        GitTaskRunner.queryDetached(com.jfx.git.core.UpdateChecker::check, result -> {
            boolean available = result.status() == com.jfx.git.core.UpdateChecker.Status.UPDATE_AVAILABLE;
            if (available) {
                com.jfx.git.ui.StatusBar.announceUpdate(result.version(), result.url());
            }
            if (!asked) {
                return;
            }
            showUpdateCheck(result, this::showTransientHint);
        }, error -> {
            if (asked) {
                com.jfx.git.ui.dialogs.Dialogs.error("Updates")
                        .icon(com.jfx.git.ui.Icons.fetch())
                        .title("Could not check for updates")
                        .message(error == null || error.isBlank() ? "The check stopped without saying why."
                                : error)
                        .show();
            }
        });
    }

    /**
     * What an update check asked for from the Help menu found, one title per outcome.
     *
     * @param hint where "up to date" is said: the status bar, or a brief notice with no
     *             repository open. It needs no answer, so it gets no dialog; the outcomes
     *             that ask something of the user still do
     */
    static void showUpdateCheck(com.jfx.git.core.UpdateChecker.Result result,
                                java.util.function.Consumer<String> hint) {
        if (result.status() == com.jfx.git.core.UpdateChecker.Status.UP_TO_DATE) {
            hint.accept("Lyra " + com.jfx.git.core.AppVersion.current() + " is the latest version");
            return;
        }
        com.jfx.git.ui.dialogs.Dialogs.Message notice;
        switch (result.status()) {
            case UPDATE_AVAILABLE -> {
                notice = com.jfx.git.ui.dialogs.Dialogs.info("Updates")
                        .icon(com.jfx.git.ui.Icons.fetch())
                        .title("Lyra " + result.version() + " is available")
                        .message("You have " + com.jfx.git.core.AppVersion.current() + ".");
                if (!result.url().isBlank()) {
                    ButtonType open = new ButtonType("Open release page",
                            javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                    notice.message("You have " + com.jfx.git.core.AppVersion.current()
                                    + ". The release page has the download and what changed.")
                            .buttons(open, ButtonType.CLOSE)
                            // A notice relabels its confirming button Close; this one opens the page.
                            .action(open.getText());
                    if (notice.answer() == open) {
                        com.jfx.git.core.UrlOpener.open(result.url());
                    }
                    return;
                }
            }
            case NOT_CONFIGURED -> notice = com.jfx.git.ui.dialogs.Dialogs.info("Updates")
                    .title("No update feed is set")
                    .message("Name one in Settings, under General, and check again.");
            case UNKNOWN_VERSION -> notice = com.jfx.git.ui.dialogs.Dialogs.info("Updates")
                    .title("The latest release is " + result.version())
                    .message("This is a " + com.jfx.git.core.AppVersion.current()
                            + ", so there is nothing to compare it with.");
            default -> notice = com.jfx.git.ui.dialogs.Dialogs.error("Updates")
                    .icon(com.jfx.git.ui.Icons.fetch())
                    .title("Could not check for updates")
                    .message(result.message());
        }
        notice.answer();
    }

    /**
     * Creates a new repository with {@code git init} and opens it, optionally seeding
     * it with a {@code .gitignore}, a LICENSE, and/or a README - the same starter files
     * GitHub's and commercial clients' own "create a new repository" flows both offer. The form
     * itself is {@link NewRepositoryDialog}, shared with the offer made when a folder
     * that is not a repository is opened.
     */
    public void initRepoDialog() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select a directory for the new repository");
        File dir = chooser.showDialog(primaryStage);
        if (dir == null) return;
        new NewRepositoryDialog(dir, NewRepositoryDialog.Purpose.CREATE, () -> loadRepository(dir)).show();
    }

    /**
     * The folder is not a repository and no folder above it is one either: offer to
     * initialise it, through the same form "New repository" uses, and open it after.
     * Commercial clients make the same offer; the alternative is an error that tells the user
     * what they already know and leaves them to find the menu item.
     */
    private void offerToInitialise(File dir) {
        new NewRepositoryDialog(dir, NewRepositoryDialog.Purpose.NOT_A_REPOSITORY,
                () -> loadRepository(dir)).show();
    }

    /** The clone form's fields, by id: tests find them, and the error under each, by these. */
    public static final String CLONE_URL_ID = "clone-url";
    public static final String CLONE_TARGET_ID = "clone-target";
    public static final String CLONE_BRANCH_ID = "clone-branch";
    public static final String CLONE_DEPTH_ID = "clone-depth";

    /** What Clone says under the URL when it was left empty. */
    public static final String CLONE_URL_MISSING = "Paste the repository's URL, or browse a connected account.";
    /** What Clone says under "Clone into" when it was left empty. */
    public static final String CLONE_TARGET_MISSING = "Choose the folder to clone into.";

    /**
     * The clone form: where from, where to, and - under "Branch and history" - which branch
     * and how much of it.
     *
     * <h2>In place</h2>
     * The clone runs with the form open ({@link JfxDialog#actInPlace}), what it is doing at
     * the left of the footer from git's and JGit's own stages: a clone of a large repository
     * is minutes, and "Cloning…" alone for all of them looks like a hang. A failure lands
     * there too - and on the field it is about, a folder that is taken or a URL that is not
     * a repository - with everything typed still in place; git's whole output is behind Show
     * Details. The form used to close first and report in an alert, and the URL had to be
     * pasted again after a typo in the folder. Cancel, or closing the window, stops the
     * clone, and what it had written is removed.
     *
     * <h2>The folder follows the URL</h2>
     * Until one is typed or chosen, "Clone into" is the repository's own name beside the
     * repository open now, or in the home folder, and the hint under it names the folder
     * that will be made. Browse to a folder with things in it and the clone goes inside
     * it; browse to an empty one and the clone is that folder.
     */
    public void cloneRepoDialog() {
        Dialog<Void> dialog = new Dialog<>();
        com.jfx.git.ui.DialogOwner.adopt(dialog);
        JfxDialog.shell(dialog, JfxDialog.Size.MD, "Clone repository");
        JfxDialog.header(dialog, Icons.cloneRepo(), JfxDialog.Tone.NEUTRAL, "Clone a repository",
                "A copy of a remote repository on this computer, opened in a tab of its own").ruled();
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(dialog, ButtonType.OK, "Clone");

        TextField url = new TextField();
        url.setId(CLONE_URL_ID);
        url.setPromptText("https://github.com/owner/repo.git");
        DialogParts.Field urlField = DialogParts.field("Repository URL", url).trailing(providerMenu(url))
                .hint("HTTPS or SSH. Browse picks one from a connected GitHub, GitLab, Bitbucket or Gitea account.");

        // Where a clone goes when only its name is known.
        File[] parent = {defaultCloneParent()};
        TextField target = new TextField();
        target.setId(CLONE_TARGET_ID);
        target.setPromptText(new File(parent[0], "repo").getPath());
        Button browse = new Button("Browse…");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        DialogParts.Field targetField = DialogParts.field("Clone into", target).trailing(browse);

        TextField branch = new TextField();
        branch.setId(CLONE_BRANCH_ID);
        branch.setPromptText("main");
        DialogParts.Field branchField = DialogParts.field("Branch", branch).optional()
                .hint("Blank checks out the remote's default branch.");
        TextField depth = new TextField();
        depth.setId(CLONE_DEPTH_ID);
        depth.setPromptText("50");
        DialogParts.Field depthField = DialogParts.field("Clone depth", depth).optional()
                .hint("Blank fetches the whole history; a number, only that many recent commits.");
        // Two short fields side by side rather than two full-width rows for a name and a number.
        GridPane history = DialogParts.pair(branchField, depthField);

        pane.setContent(DialogParts.stack(urlField, targetField,
                DialogParts.section("Branch and history"), history));
        dialog.setResultConverter(button -> null);

        // Clone waits for what can be checked as typed; a field left empty is said when it is pressed.
        Button clone = (Button) pane.lookupButton(ButtonType.OK);
        java.util.function.Supplier<File> into = () -> cloneTarget(trimmed(target), parent[0]);
        Runnable decide = () -> {
            if (pane.getContent().isDisabled()) {
                return; // cloning: the button is the clone's until it ends
            }
            clone.setDisable(cloneTargetProblem(into.get()) != null
                    || cloneBranchProblem(trimmed(branch)) != null || cloneDepthProblem(trimmed(depth)) != null);
        };

        // The folder follows the URL until one is typed or chosen.
        String[] filled = {""};
        url.textProperty().addListener((obs, old, text) -> {
            urlField.error(null);
            String typed = trimmed(target);
            if (typed.isEmpty() || typed.equals(filled[0])) {
                String name = cloneFolderName(text);
                filled[0] = name.isEmpty() ? "" : new File(parent[0], name).getPath();
                target.setText(filled[0]);
            }
        });
        Runnable checkTarget = () -> {
            File dir = into.get();
            targetField.error(cloneTargetProblem(dir));
            targetField.hint(cloneTargetHint(dir, trimmed(target)));
            decide.run();
        };
        target.textProperty().addListener((obs, old, text) -> checkTarget.run());
        branch.textProperty().addListener((obs, old, text) -> {
            branchField.error(cloneBranchProblem(trimmed(branch)));
            decide.run();
        });
        depth.textProperty().addListener((obs, old, text) -> {
            depthField.error(cloneDepthProblem(trimmed(depth)));
            decide.run();
        });
        checkTarget.run();

        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Clone into");
            File start = existingFolder(into.get(), parent[0]);
            if (start != null) chooser.setInitialDirectory(start);
            File chosen = chooser.showDialog(pane.getScene() == null ? null : pane.getScene().getWindow());
            if (chosen == null) return;
            if (!hasFiles(chosen)) {
                // An empty folder, made for the clone: the clone is that folder.
                filled[0] = "";
                target.setText(chosen.getPath());
            } else {
                // A folder with things in it is where the clone goes, not the clone.
                parent[0] = chosen;
                String name = cloneFolderName(url.getText());
                filled[0] = name.isEmpty() ? chosen.getPath() : new File(chosen, name).getPath();
                target.setText(filled[0]);
            }
        });

        java.util.concurrent.atomic.AtomicReference<GitTaskRunner.Task> running =
                new java.util.concurrent.atomic.AtomicReference<>();
        // Cancel, Escape and the window's close button stop the clone, not only the window.
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, e -> {
            GitTaskRunner.Task task = running.getAndSet(null);
            if (task != null) task.cancel();
        });
        JfxDialog.actInPlace(dialog, ButtonType.OK, "Starting the clone…", progress -> {
            String source = trimmed(url);
            File dir = into.get();
            urlField.error(source.isEmpty() ? CLONE_URL_MISSING : null);
            targetField.error(dir == null ? CLONE_TARGET_MISSING : cloneTargetProblem(dir));
            if (source.isEmpty() || dir == null || !targetField.error().isEmpty()
                    || cloneBranchProblem(trimmed(branch)) != null || cloneDepthProblem(trimmed(depth)) != null) {
                progress.failed("");
                decide.run();
                (source.isEmpty() ? url : target).requestFocus();
                return;
            }
            // The whole path, for a folder typed as a bare name: where it went, in the field.
            target.setText(dir.getPath());
            pane.setExpandableContent(null);
            String name = repositoryName(source);
            String wanted = trimmed(branch);
            int depthValue = trimmed(depth).isEmpty() ? 0 : Integer.parseInt(trimmed(depth));
            progress.say("Cloning " + name + "…");
            // Detached: a clone writes into a folder no tab has open, so it has no reason to
            // queue behind - or in front of - another repository's work. Polls the dialog as
            // well as the task: closed, nobody is waiting for the clone any more.
            GitTaskRunner.Task task = GitTaskRunner.runDetached(
                    () -> com.jfx.git.core.GitBackendFactory.cloneRepository(source, dir, wanted, depthValue,
                            () -> GitTaskRunner.isCancelled() || progress.cancelled()),
                    (GitResult result) -> {
                        running.set(null);
                        if (result.isOk()) {
                            progress.succeeded();
                            loadRepository(dir);
                        } else if (!progress.cancelled()) {
                            cloneFailed(dialog, name, result, progress,
                                    urlField, targetField, branchField);
                            decide.run();
                        }
                    });
            running.set(task);
            task.onProgress(p -> {
                if (running.get() == task) {
                    progress.say(p.stage() + (p.fraction() < 0 ? "" : " " + Math.round(p.fraction() * 100) + "%"));
                }
            });
        });
        dialog.show();
    }

    /** "Browse ▾" beside the clone URL: a connected account's repositories, by provider. */
    private static javafx.scene.control.MenuButton providerMenu(TextField url) {
        javafx.scene.control.MenuButton browse = new javafx.scene.control.MenuButton("Browse");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        MenuItem gitHub = new MenuItem("GitHub…");
        gitHub.setOnAction(e -> new GitHubRepoPickerDialog().showAndWait()
                .ifPresent(repo -> url.setText(repo.cloneUrl())));
        MenuItem gitLab = new MenuItem("GitLab…");
        gitLab.setOnAction(e -> new GitLabRepoPickerDialog().showAndWait()
                .ifPresent(repo -> url.setText(repo.httpUrlToRepo())));
        MenuItem bitbucket = new MenuItem("Bitbucket…");
        bitbucket.setOnAction(e -> new BitbucketRepoPickerDialog().showAndWait()
                .ifPresent(repo -> url.setText(repo.cloneUrl())));
        MenuItem gitea = new MenuItem(com.jfx.git.core.hosting.HostingProvider.GITEA.displayName() + "…");
        gitea.setOnAction(e -> new com.jfx.git.ui.dialogs.GiteaRepoPickerDialog().showAndWait()
                .ifPresent(repo -> url.setText(repo.cloneUrl())));
        browse.getItems().addAll(gitHub, gitLab, bitbucket, gitea);
        // Read each time the menu opens: a remembered token restored, or an account
        // connected, after the form appeared used to leave its item disabled.
        browse.setOnShowing(e -> {
            gitHub.setDisable(!GitHubSession.isConnected());
            gitLab.setDisable(!GitLabSession.isConnected());
            bitbucket.setDisable(!BitbucketSession.isConnected());
            gitea.setDisable(!com.jfx.git.core.gitea.GiteaSession.isConnected());
        });
        return browse;
    }

    /**
     * Says why a clone failed, with the form still open: on the field it is about when it is
     * about one, at the left of the footer, and git's whole output behind Show Details.
     */
    private static void cloneFailed(Dialog<?> dialog, String name, GitResult result, JfxDialog.Progress progress,
                                    DialogParts.Field url, DialogParts.Field target, DialogParts.Field branch) {
        String raw = result.getMessage();
        String said = failureSentence(raw, "Clone failed:");
        DialogParts.Field about = !(result.getRefusal() instanceof com.jfx.git.core.Refusal.BadInput bad) ? null
                : switch (bad.field()) {
                    case URL -> url;
                    case TARGET -> target;
                    case BRANCH -> branch;
                    case PATH -> null;
                };
        String output = raw == null ? "" : raw.strip();
        if (output.lines().count() > 1) {
            javafx.scene.control.TextArea details = new javafx.scene.control.TextArea(output);
            details.setEditable(false);
            details.setWrapText(false);
            details.setPrefRowCount((int) Math.min(12, output.lines().count() + 1));
            details.getStyleClass().add("jfx-details");
            dialog.getDialogPane().setExpandableContent(details);
        }
        if (about != null) {
            about.error(said);
            progress.failed("Could not clone " + name + ".");
        } else {
            progress.failed(said);
        }
    }

    /** What is wrong with the clone depth as typed, or null; blank is the whole history. */
    static String cloneDepthProblem(String depth) {
        String wanted = depth == null ? "" : depth.trim();
        if (wanted.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(wanted) < 0 ? "Clone depth cannot be negative." : null;
        } catch (NumberFormatException notANumber) {
            return "Clone depth must be a whole number.";
        }
    }

    /** What git would refuse about the branch to check out, or null; blank is the remote's default. */
    static String cloneBranchProblem(String branch) {
        String name = branch == null ? "" : branch.trim();
        String problem = name.isEmpty() ? null : com.jfx.git.model.RefNames.branchNameProblem(name);
        return problem == null ? null : "Not a branch name git accepts. " + problem;
    }

    /**
     * The folder "Clone into" names: the path as typed when it is a whole one, a bare name
     * beside {@code parent}. Null for nothing typed.
     */
    static File cloneTarget(String typed, File parent) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) {
            return null;
        }
        File dir = new File(text);
        return (dir.isAbsolute() || parent == null ? dir : new File(parent, text)).getAbsoluteFile();
    }

    /** What keeps {@code dir} from taking a clone, or null: a file there, or a folder with things in it. */
    static String cloneTargetProblem(File dir) {
        if (dir == null) {
            return null;
        }
        if (dir.isFile()) {
            return folderName(dir) + " is a file. Choose a folder.";
        }
        if (hasFiles(dir)) {
            return folderName(dir) + " already exists and has files in it. Choose a new folder, or an empty one.";
        }
        return null;
    }

    /**
     * The folder the clone makes, named under the field. Where it is, too, for one typed as a
     * bare name; a whole path is in the field already.
     */
    static String cloneTargetHint(File dir, String typed) {
        if (dir == null || cloneTargetProblem(dir) != null) {
            return "A new folder, or an empty one.";
        }
        boolean whole = typed != null && new File(typed.trim()).isAbsolute();
        String where = whole || dir.getParentFile() == null ? "" : " in " + dir.getParentFile().getPath();
        return (dir.isDirectory() ? "Clones into the empty folder " : "Creates the folder ")
                + folderName(dir) + where + ".";
    }

    /** "demo" for https://github.com/shop/demo.git: the folder a clone gets. Empty while there is no telling. */
    static String cloneFolderName(String url) {
        String path = url == null ? "" : url.strip().replaceAll("[/\\\\]+$", "");
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        int cut = Math.max(path.lastIndexOf('/'), Math.max(path.lastIndexOf('\\'), path.lastIndexOf(':')));
        if (cut < 0) {
            return ""; // "https" on the way to "https://": nothing to name it by yet
        }
        String name = path.substring(cut + 1).replaceAll("[*?\"<>|]+", "-");
        return name.equals(".") || name.equals("..") ? "" : name;
    }

    /** Whether {@code dir} is a folder with something in it. One that cannot be read is left for git to judge. */
    static boolean hasFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        try (var entries = java.nio.file.Files.newDirectoryStream(dir.toPath())) {
            return entries.iterator().hasNext();
        } catch (java.io.IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** A folder's name, or its whole path for a drive's root, which has none. */
    private static String folderName(File dir) {
        return dir.getName().isEmpty() ? dir.getPath() : dir.getName();
    }

    /** The nearest folder that exists at or above {@code dir}, else {@code fallback} if it does; for a chooser to open on. */
    private static File existingFolder(File dir, File fallback) {
        for (File f = dir; f != null; f = f.getParentFile()) {
            if (f.isDirectory()) {
                return f;
            }
        }
        return fallback != null && fallback.isDirectory() ? fallback : null;
    }

    /** Where a clone goes when only its name is known: beside the repository open now, or in the home folder. */
    private File defaultCloneParent() {
        RepoTab tab = tabPane == null ? null : getActiveRepoTab();
        if (tab == null) {
            tab = lastRepoTab;
        }
        File open = tab == null ? null : tab.getRequestedDirectory();
        File beside = open == null ? null : open.getAbsoluteFile().getParentFile();
        return beside != null ? beside : new File(System.getProperty("user.home"));
    }

    private static String trimmed(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    /** "demo" for https://github.com/shop/demo.git or git@github.com:shop/demo: what a clone is called. */
    static String repositoryName(String url) {
        String path = url.strip().replaceAll("[/\\\\]+$", "");
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        int cut = Math.max(path.lastIndexOf('/'), Math.max(path.lastIndexOf('\\'), path.lastIndexOf(':')));
        String name = path.substring(cut + 1);
        return name.isBlank() ? url.strip() : name;
    }

    /**
     * What a failure comes to in a sentence: the operation's own prefix ({@code "Clone
     * failed:"}) dropped, and of git's output the last line that says what was fatal - or,
     * with no such line, the first.
     *
     * <p>Not git's "Could not read from remote repository." when it said why before it: that
     * line closes every transport failure, and the one above it - "'x' does not appear to be
     * a git repository" - is the one that says what to change.
     */
    static String failureSentence(String raw, String prefix) {
        String text = raw == null ? "" : raw.strip();
        if (prefix != null && text.startsWith(prefix)) {
            text = text.substring(prefix.length()).strip();
        }
        String said = null;
        for (String line : text.lines().toList()) {
            String l = line.strip();
            String lower = l.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("fatal:") || lower.startsWith("error:")) {
                String reason = l.substring(l.indexOf(':') + 1).strip();
                boolean generic = reason.toLowerCase(java.util.Locale.ROOT)
                        .startsWith("could not read from remote repository");
                if (!generic || said == null) {
                    said = reason;
                }
            }
        }
        if (said == null || said.isEmpty()) {
            said = text.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        }
        return said.isEmpty() ? "Git gave no reason." : Character.toUpperCase(said.charAt(0)) + said.substring(1);
    }

    /**
     * An operation's failure, said in a sentence under a title that names what failed - see
     * {@link #failureSentence} - with all of git's output, when there is more than that
     * line, behind Show Details.
     */
    private static com.jfx.git.ui.dialogs.Dialogs.Message failed(String windowTitle, javafx.scene.Node icon,
                                                                   String title, String subtitle,
                                                                   String raw, String prefix) {
        String text = raw == null ? "" : raw.strip();
        if (prefix != null && text.startsWith(prefix)) {
            text = text.substring(prefix.length()).strip();
        }
        var message = com.jfx.git.ui.dialogs.Dialogs.error(windowTitle).icon(icon).title(title).subtitle(subtitle)
                .message(failureSentence(raw, prefix))
                .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD);
        if (text.lines().count() > 1) {
            message.details(raw.strip());
        }
        return message;
    }

    /**
     * Adds a tab for the repository at {@code picked}, selects it and opens it - or
     * selects the one already open, building it first if it is a restored tab not yet
     * looked at.
     */
    private void loadRepository(File picked) {
        // The repository's root, not whichever folder was picked: opening src/ of an
        // open repository used to create a second tab on it, and store the session and
        // recent list against a subfolder.
        File root = repositoryRootOf(canonical(picked));
        File dir = root != null ? root : picked;
        // Nothing here is a repository, so the engines would only refuse it: the offer
        // to make one instead. A bare repository has no .git entry but opens fine, so it
        // goes on to the engines like any other.
        if (root == null && !looksBare(dir)) {
            offerToInitialise(dir);
            return;
        }
        // Focus the existing tab rather than opening a second handle on the same repo -
        // two tabs sharing a repository used to fight over the packfile handles.
        RepoTab existing = findOpenTab(dir);
        if (existing != null) {
            tabPane.getSelectionModel().select(existing);
            return;
        }
        RestoredTab waiting = findRestoredTab(dir);
        if (waiting != null) {
            materialize(waiting);
            return;
        }

        // Opening from a launchpad page replaces it, the way a browser new-tab
        // page gives way to the site you navigate to.
        Tab active = tabPane.getSelectionModel().getSelectedItem();
        int insertAt = tabPane.getTabs().size() - 1;
        if (active instanceof WelcomeTab) {
            insertAt = tabPane.getTabs().indexOf(active);
            tabPane.getTabs().remove(active);
        }
        showAndOpen(addRepoTab(dir, insertAt));
    }

    /**
     * Builds the tab for {@code dir} at {@code insertAt}, named and holding its open back.
     *
     * <p>Added at once so the user gets feedback; the open itself runs on a background
     * thread so the FX thread stays responsive. Named after its folder from the start, and
     * under the name the user gave it, if any.
     */
    private RepoTab addRepoTab(File dir, int insertAt) {
        RepoTab newTab = new RepoTab(this, dir.getName());
        newTab.expect(dir);
        newTab.setAlias(com.jfx.git.core.TabSession.alias(tabKey(newTab)));
        // The editor's unsaved-changes prompt covers file switches; this covers the tab
        // itself. Consuming the close request leaves the tab open when the user cancels.
        newTab.setOnCloseRequest(e -> {
            if (!newTab.confirmClose()) {
                e.consume();
            }
        });
        newTab.setOnClosed(e -> newTab.close());
        tabPane.getTabs().add(insertAt, newTab);
        installTabMenu(newTab);
        newTab.deferOpen(() -> openTab(newTab, dir));
        return newTab;
    }

    /** Brings {@code tab} to the front, which is what opens it: see the strip's selection listener. */
    private void showAndOpen(RepoTab tab) {
        tabPane.getSelectionModel().select(tab);
        // Covers the strip where it was already selected.
        tab.openIfPending();
    }

    /**
     * Replaces a restored tab's placeholder with the real tab, in the same place, and
     * opens it.
     */
    private RepoTab materialize(RestoredTab placeholder) {
        int index = tabPane.getTabs().indexOf(placeholder);
        if (index < 0) {
            return null;
        }
        RepoTab tab;
        // One session write for the swap, after it, rather than one per list change -
        // the first of which would have recorded the repository twice.
        boolean wasRestoring = restoringTabs;
        restoringTabs = true;
        try {
            tab = addRepoTab(placeholder.directory(), index);
            showAndOpen(tab);
            tabPane.getTabs().remove(placeholder);
        } finally {
            restoringTabs = wasRestoring;
        }
        rememberOpenTabs();
        return tab;
    }

    /**
     * A tab from the last session that has not been looked at yet: its name, its path in
     * the tooltip, and nothing else.
     *
     * <h2>Why not a {@link RepoTab} waiting to open</h2>
     * That is what a restored session used to build - a toolbar, a status bar, a diff
     * viewer with its text area, minimap and find bar, the panels - per saved repository,
     * on the FX thread, before the window could show the one being looked at. And once
     * that one had opened, every other tab opened after it in the background: a full
     * refresh, a file watcher and auto-fetch each, all on the repository lane the user's
     * own work shares. A tab nobody has looked at this session now costs a label.
     */
    static final class RestoredTab extends Tab {

        private final File directory;

        RestoredTab(File directory, String alias) {
            super(alias != null ? alias : directory.getName());
            this.directory = directory;
            setTooltip(new javafx.scene.control.Tooltip(directory.getAbsolutePath()));
            // Seen for at most a frame, between the tab being picked and being built.
            Label opening = new Label("Opening " + directory.getName() + "…");
            opening.getStyleClass().add("text-muted");
            setContent(new javafx.scene.layout.StackPane(opening));
        }

        File directory() {
            return directory;
        }
    }

    /** The restored tab still waiting for {@code dir}, or null. */
    private RestoredTab findRestoredTab(File dir) {
        File wanted = canonical(dir);
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof RestoredTab restored && canonical(restored.directory()).equals(wanted)) {
                return restored;
            }
        }
        return null;
    }

    /** Right-click on a tab not built yet: rename and close, as on a real one. */
    private void installTabMenu(RestoredTab tab) {
        String key = com.jfx.git.core.TabSession.key(tab.directory());
        MenuItem rename = new MenuItem("Rename tab…");
        rename.setOnAction(e -> {
            String alias = com.jfx.git.core.TabSession.alias(key);
            askTabName(alias == null ? tab.directory().getName() : alias, tab.directory()).ifPresent(name -> {
                String trimmed = name.isBlank() ? null : name.trim();
                com.jfx.git.core.TabSession.setAlias(key, trimmed);
                tab.setText(trimmed == null ? tab.directory().getName() : trimmed);
            });
        });
        MenuItem resetName = new MenuItem("Use folder name");
        resetName.setOnAction(e -> {
            com.jfx.git.core.TabSession.setAlias(key, null);
            tab.setText(tab.directory().getName());
        });
        MenuItem close = new MenuItem("Close");
        close.setOnAction(e -> {
            tabPane.getTabs().remove(tab);
            if (tabPane.getTabs().size() == 1) {
                openWelcomeTab();
            }
        });
        ContextMenu menu = new ContextMenu();
        menu.setOnShowing(e -> resetName.setDisable(com.jfx.git.core.TabSession.alias(key) == null));
        menu.getItems().addAll(rename, resetName, new SeparatorMenuItem(), close);
        tab.setContextMenu(menu);
    }

    private void openTab(RepoTab newTab, File dir) {
        newTab.loadRepositoryAsync(dir,
                () -> {
                    // The last repository looked at, not merely the last to finish opening.
                    if (tabPane.getSelectionModel().getSelectedItem() == newTab) {
                        com.jfx.git.core.Settings.put("repo.last", dir.getAbsolutePath());
                    }
                    RecentRepositories.add(dir);
                    // The work tree is known now; the stored name is keyed by it.
                    newTab.setAlias(com.jfx.git.core.TabSession.alias(tabKey(newTab)));
                    rememberOpenTabs();
                },
                error -> {
                    LOGGER.warning("Could not open " + dir + ": " + error);
                    // Open failed: discard the placeholder tab.
                    tabPane.getTabs().remove(newTab);
                    newTab.close();
                    // Leave the user on something rather than an empty window.
                    if (tabPane.getTabs().size() == 1) {
                        openWelcomeTab();
                    }
                    if (com.jfx.git.core.SafeDirectory.isDubiousOwnership(error)) {
                        offerToTrust(dir, error);
                        return;
                    }
                    // The engine's reason, not only the path: "Could not open" alone left
                    // a permissions problem, a corrupt index and a wrong folder all
                    // looking the same.
                    failed("Open repository", com.jfx.git.ui.Icons.folder(),
                            "Could not open " + dir.getName(), "`" + dir.getAbsolutePath() + "`",
                            error, null).show();
                });
    }

    /**
     * Git refused the repository because someone else owns it: who owns it, and the fix
     * git's message recommends, offered as one decision. See
     * {@link com.jfx.git.ui.TrustFolderPrompt}.
     */
    private void offerToTrust(File dir, String gitMessage) {
        new com.jfx.git.ui.TrustFolderPrompt(dir, gitMessage, () -> loadRepository(dir)).show();
    }

    // ------------------------------------------------------------------
    // Tab strip
    // ------------------------------------------------------------------

    /**
     * Gives {@code tab} its right-click menu: rename, and close.
     *
     * <p>The rename is stored against the repository path rather than the tab, so it
     * survives closing and reopening — a tab called "work" that reverts to "repo" the
     * next morning is not a name, it is a note.
     */
    private void installTabMenu(RepoTab tab) {
        MenuItem rename = new MenuItem("Rename tab…");
        rename.setOnAction(e -> promptRenameTab(tab));

        MenuItem resetName = new MenuItem("Use folder name");
        resetName.setOnAction(e -> {
            tab.setAlias(null);
            com.jfx.git.core.TabSession.setAlias(tabKey(tab), null);
        });

        MenuItem close = new MenuItem("Close");
        close.setOnAction(e -> closeTab(tab));

        ContextMenu menu = new ContextMenu();
        menu.setOnShowing(e -> resetName.setDisable(tab.alias() == null));
        menu.getItems().addAll(rename, resetName, new SeparatorMenuItem(), close);
        tab.setContextMenu(menu);
    }

    private void promptRenameTab(RepoTab tab) {
        // The folder it was asked to open, or - for a tab opened another way - the one it has.
        File dir = tab.getRequestedDirectory() != null ? tab.getRequestedDirectory()
                : tab.getGitService().getWorkTree();
        // A tab still opening may not know its folder's name yet; the folder it was given does.
        String folder = !tab.folderTitle().isBlank() || dir == null ? tab.folderTitle() : dir.getName();
        askTabName(tab.alias() == null ? folder : tab.alias(), dir).ifPresent(name -> {
            tab.setAlias(name);
            com.jfx.git.core.TabSession.setAlias(tabKey(tab), tab.alias());
        });
    }

    /**
     * What a repository tab should be called, asked the same way for a tab that is open
     * and one restored but not built yet; empty when cancelled.
     *
     * @param current what the tab is called now
     * @param dir     the repository folder, or null when it is not known
     */
    private static java.util.Optional<String> askTabName(String current, File dir) {
        return com.jfx.git.ui.dialogs.Dialogs.prompt("Rename tab")
                .icon(com.jfx.git.ui.Icons.edit())
                .title(current == null || current.isBlank() ? "Rename this tab" : "Rename the " + current + " tab")
                .subtitle(dir == null ? null : "`" + dir.getAbsolutePath() + "`")
                .label("Tab name")
                .initial(current)
                .placeholder(dir == null ? null : dir.getName())
                .hint("Only the tab is renamed; the folder keeps its name. Use folder name, in the"
                        + " tab's menu, puts it back.")
                .action("Rename")
                .ask();
    }

    /** Closes a repository tab, releasing its repository handle first. */
    public void closeTab(RepoTab tab) {
        // The menu item bypasses Tab's close-request event, so it asks here instead -
        // one confirmation either way, never two.
        if (!tab.confirmClose()) {
            return;
        }
        tab.close();
        tabPane.getTabs().remove(tab);
        if (tabPane.getTabs().size() == 1) {
            openWelcomeTab();
        }
    }

    private static String tabKey(RepoTab tab) {
        // A tab still opening - or not yet asked to, in a restored session - is keyed by
        // the folder it was given. Skipping it, as this once did, wrote a session missing
        // every tab that had not finished opening.
        File dir = tab.getGitService().getWorkTree();
        return com.jfx.git.core.TabSession.key(dir != null ? dir : tab.getRequestedDirectory());
    }

    /** Writes the open set so the next session can reopen it. */
    private void rememberOpenTabs() {
        if (restoringTabs || tabPane == null) {
            return;
        }
        List<String> paths = new ArrayList<>();
        int active = 0;
        for (Tab tab : tabPane.getTabs()) {
            String key;
            if (tab instanceof RepoTab repoTab) {
                key = tabKey(repoTab);
            } else if (tab instanceof RestoredTab restored) {
                // Not looked at this session, but still part of it.
                key = com.jfx.git.core.TabSession.key(restored.directory());
            } else {
                continue;
            }
            if (key == null) {
                // Nothing to name it by; it is not a tab worth restoring.
                continue;
            }
            if (tab == tabPane.getSelectionModel().getSelectedItem()) {
                active = paths.size();
            }
            paths.add(key);
        }
        com.jfx.git.core.TabSession.setOpenPaths(paths, active);
    }

    /**
     * Reopens last session's tabs.
     *
     * <p>Missing directories are skipped rather than reported: a repository that was
     * moved or deleted between sessions is not an error the user needs a dialog about on
     * startup, and the tabs that do still exist should open regardless.
     *
     * @return true when at least one repository was reopened
     */
    private boolean restoreTabs() {
        List<String> paths = com.jfx.git.core.TabSession.openPaths();
        if (paths.isEmpty()) {
            return false;
        }
        // The repositories still there, by root, once each.
        List<File> roots = new ArrayList<>();
        for (String path : paths) {
            File dir = new File(path);
            File root = dir.isDirectory() ? repositoryRootOf(canonical(dir)) : null;
            if (root != null && !roots.contains(root)) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            return false;
        }
        int active = Math.max(0, Math.min(com.jfx.git.core.TabSession.activeIndex(), roots.size() - 1));
        RepoTab first = null;
        restoringTabs = true;
        try {
            // Only the tab that will be in front is built; the rest are placeholders
            // until they are first selected. See RestoredTab.
            for (int i = 0; i < roots.size(); i++) {
                File root = roots.get(i);
                int insertAt = tabPane.getTabs().size() - 1;
                if (i == active) {
                    first = addRepoTab(root, insertAt);
                } else {
                    RestoredTab placeholder = new RestoredTab(root,
                            com.jfx.git.core.TabSession.alias(com.jfx.git.core.TabSession.key(root)));
                    tabPane.getTabs().add(insertAt, placeholder);
                    installTabMenu(placeholder);
                }
            }
        } finally {
            restoringTabs = false;
        }
        showAndOpen(first);
        // Deliberately no write here. The open is asynchronous, so the front tab has no
        // repository yet; it records the session, placeholders included, as it completes.
        return true;
    }

    private RepoTab findOpenTab(File dir) {
        return findOpenTab(tabPane.getTabs(), dir);
    }

    /**
     * The tab already showing {@code dir}, including one still loading it. The work tree
     * is only known once the background open finishes, so a tab mid-open is matched by
     * the folder it was asked for - without that, a command-line path that the restored
     * session was already opening got a second tab.
     */
    static RepoTab findOpenTab(List<? extends Tab> tabs, File dir) {
        File canonical = canonical(dir);
        for (Tab tab : tabs) {
            if (tab instanceof RepoTab repoTab) {
                File open = repoTab.getGitService().getWorkTree();
                if (open == null) open = repoTab.getRequestedDirectory();
                if (open != null && canonical(open).equals(canonical)) {
                    return repoTab;
                }
            }
        }
        return null;
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (Exception e) {
            return f.getAbsoluteFile();
        }
    }

    private static boolean looksLikeRepository(File dir) {
        return repositoryRootOf(dir) != null;
    }

    /** {@code dir} itself, or the nearest ancestor with a {@code .git} entry - {@code null} if none does. */
    static File repositoryRootOf(File dir) {
        File probe = dir;
        while (probe != null) {
            if (new File(probe, ".git").exists()) return probe;
            probe = probe.getParentFile();
        }
        return null;
    }

    /**
     * Whether {@code dir} is a bare repository: no {@code .git} entry, because the folder
     * is the git directory itself - a {@code HEAD} file beside {@code objects/} and
     * {@code refs/}. The same three markers git's own discovery checks for.
     */
    static boolean looksBare(File dir) {
        return dir != null
                && new File(dir, "HEAD").isFile()
                && new File(dir, "objects").isDirectory()
                && new File(dir, "refs").isDirectory();
    }

    // ------------------------------------------------------------------
    // Keyboard map
    // ------------------------------------------------------------------

    /**
     * The application's shortcuts.
     *
     * <p>Every git action here routes through the active tab rather than a stored
     * backend, so a shortcut always acts on the repository the user is looking at. Where
     * no repository is open the action reports that instead of doing nothing silently.
     */
    /** A combination with every modifier up: the plain key and nothing but the key. */
    private static KeyCodeCombination bareKey(KeyCode code) {
        return new KeyCodeCombination(code,
                KeyCombination.ModifierValue.UP, KeyCombination.ModifierValue.UP,
                KeyCombination.ModifierValue.UP, KeyCombination.ModifierValue.UP,
                KeyCombination.ModifierValue.UP);
    }

    /** The live keyboard map; the cheat-sheet reads it so it can never drift. */
    private com.jfx.git.ui.Shortcuts shortcuts;

    private com.jfx.git.ui.Shortcuts buildShortcuts(Stage stage) {
        com.jfx.git.ui.Shortcuts shortcuts = new com.jfx.git.ui.Shortcuts();
        this.shortcuts = shortcuts;

        shortcuts.add(KeyCode.P, "Command palette", () -> showCommandPalette(stage));
        shortcuts.add(new KeyCodeCombination(KeyCode.SLASH, KeyCombination.SHORTCUT_DOWN),
                "Show keyboard shortcuts",
                () -> openSettings(com.jfx.git.ui.SettingsSection.KEYBOARD_SHORTCUTS));
        shortcuts.addShift(KeyCode.L, "Activity logs", this::showActivityLog);
        shortcuts.add(KeyCode.COMMA, "Settings", this::showSettings);

        // --- Git actions ---
        shortcuts.section("Git actions");
        shortcuts.addShift(KeyCode.S, "Stage all",
                () -> withRepo(tab -> tab.getRightPanel().stageAllFiles()));
        shortcuts.addShift(KeyCode.U, "Unstage all",
                () -> withRepo(tab -> tab.getRightPanel().unstageAllFiles()));
        shortcuts.add(KeyCode.ENTER, "Commit — or stash, on the Stash tab",
                () -> withRepo(tab -> tab.getRightPanel().submitComposer()));
        // Ctrl+Shift+Enter is commit and push, whatever the Commit page's setting, so
        // "Stage all and commit" moved to Ctrl+Alt+Enter to make room for it.
        shortcuts.addShift(KeyCode.ENTER, "Commit and push",
                () -> withRepo(tab -> tab.getRightPanel().commitAndPush()));
        shortcuts.add(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
                "Stage all and commit",
                () -> withRepo(tab -> tab.getRightPanel().stageAllAndCommit()));
        // A git client with no way to say "look again" is missing one: the window now
        // rechecks on focus, but a repository can change while it is being watched, and
        // the empty-diff message tells the user to refresh - which has to be something
        // they can actually do. F5 because that is what every other application means by
        // it; bare, so it stands aside while a text field has focus, as the map's
        // no-modifier rule already provides for.
        shortcuts.add(bareKey(KeyCode.F5), "Refresh this repository",
                () -> withRepo(RepoTab::refreshFromKeyboard));
        shortcuts.add(KeyCode.B, "New branch",
                () -> withRepo(tab -> tab.getToolbar().promptNewBranch()));
        shortcuts.add(KeyCode.L, "Fetch all",
                () -> withRepo(tab -> tab.getToolbar().fetchAll()));
        // Text-sensitive: an editable text control (the built-in editor, the commit
        // message box) gets its own undo/redo instead - otherwise fixing a typo with
        // Ctrl+Z would undo a git operation, since the control never sees the key at
        // all (installOn's filter runs first).
        shortcuts.addTextSensitive(KeyCode.Z, "Undo the last action",
                () -> withRepo(tab -> tab.getToolbar().undo()));
        shortcuts.addTextSensitiveShift(KeyCode.Z, "Redo",
                () -> withRepo(tab -> tab.getToolbar().redo()));

        // --- Navigation and panels ---
        shortcuts.section("Navigation and panels");
        // To whatever has text on screen: the open file - diff, editor, file history -
        // and the graph's search box only when there is none. It used to go to the graph
        // whatever was in front, so a search inside a file had no key at all.
        shortcuts.add(KeyCode.F, "Find in the open file, or search the graph",
                () -> withRepo(tab -> tab.find(false)));
        shortcuts.add(KeyCode.H, "Find and replace in the file being edited",
                () -> withRepo(tab -> tab.find(true)));
        shortcuts.add(KeyCode.G, "Go to line in the open file",
                () -> withRepo(RepoTab::goToLine));
        shortcuts.add(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
                "Filter the sidebar", () -> withRepo(tab -> tab.focusSidebarFilter()));
        shortcuts.addShift(KeyCode.F, "Search in files", () -> withRepo(RepoTab::openSearchInFiles));
        shortcuts.add(KeyCode.J, "Toggle the sidebar",
                () -> withRepo(RepoTab::toggleSidebar));
        shortcuts.add(KeyCode.K, "Toggle the commit detail panel",
                () -> withRepo(RepoTab::toggleDetailPanel));
        // Bare, so a text field keeps its own Esc; the built-in editor takes Esc itself
        // and asks about unsaved edits the same way.
        shortcuts.add(bareKey(KeyCode.ESCAPE), "Close the file and go back to the history",
                () -> withRepo(RepoTab::closeDiff));
        // F7, as diff views elsewhere use it. Bare, so it stands aside in a text field.
        shortcuts.add(bareKey(KeyCode.F7), "Next change in the open file",
                () -> withRepo(tab -> tab.stepChange(1)));
        shortcuts.add(new KeyCodeCombination(KeyCode.F7, KeyCombination.SHIFT_DOWN),
                "Previous change in the open file",
                () -> withRepo(tab -> tab.stepChange(-1)));

        // --- Graph keyboard navigation (§3.2). Bare keys, so the guard above them
        // stands aside while any editable control has focus — typing "j" into the
        // commit box types a letter, it does not walk history.
        shortcuts.add(bareKey(KeyCode.J), "One commit older", () -> withRepo(t -> t.navigateRows(1)));
        shortcuts.add(bareKey(KeyCode.K), "One commit newer", () -> withRepo(t -> t.navigateRows(-1)));
        shortcuts.add(bareKey(KeyCode.H), "To the parent commit", () -> withRepo(RepoTab::navigateToParent));
        shortcuts.add(bareKey(KeyCode.L), "To a child commit", () -> withRepo(RepoTab::navigateToChild));
        shortcuts.add(new KeyCodeCombination(KeyCode.J,
                        KeyCombination.SHIFT_DOWN), "Jump to the next named commit",
                () -> withRepo(t -> t.navigateToNextRef(true)));
        shortcuts.add(new KeyCodeCombination(KeyCode.K,
                        KeyCombination.SHIFT_DOWN), "Jump to the previous named commit",
                () -> withRepo(t -> t.navigateToNextRef(false)));
        shortcuts.add(new KeyCodeCombination(KeyCode.HOME, KeyCombination.SHORTCUT_DOWN),
                "To the newest loaded commit", () -> withRepo(t -> t.navigateToEdge(true)));
        shortcuts.add(new KeyCodeCombination(KeyCode.END, KeyCombination.SHORTCUT_DOWN),
                "To the oldest loaded commit", () -> withRepo(t -> t.navigateToEdge(false)));

        // Ctrl+W already closes a repository from the File menu's accelerator, so it is
        // deliberately not repeated here - a scene filter would consume the key before
        // the menu item ever saw it, and two owners of one binding is how they drift.
        // Both the main and the numpad keys, and PLUS as well as EQUALS: "Ctrl and the
        // plus key" is one gesture to a user and four key codes to the toolkit.
        for (KeyCode in : new KeyCode[] {KeyCode.EQUALS, KeyCode.PLUS, KeyCode.ADD}) {
            shortcuts.add(in, "Zoom in", com.jfx.git.ui.UiZoom::zoomIn);
        }
        for (KeyCode out : new KeyCode[] {KeyCode.MINUS, KeyCode.SUBTRACT}) {
            shortcuts.add(out, "Zoom out", com.jfx.git.ui.UiZoom::zoomOut);
        }
        shortcuts.add(KeyCode.DIGIT0, "Reset zoom", com.jfx.git.ui.UiZoom::reset);
        shortcuts.add(KeyCode.NUMPAD0, "Reset zoom", com.jfx.git.ui.UiZoom::reset);

        shortcuts.add(KeyCode.T, "New tab", this::openWelcomeTab);
        shortcuts.add(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN),
                "Next tab", () -> cycleTab(1));
        shortcuts.add(new KeyCodeCombination(KeyCode.TAB,
                        KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                "Previous tab", () -> cycleTab(-1));

        // Ctrl+1..9 select a tab directly; 9 means "the last one" the way browsers do,
        // so it still lands somewhere useful when fewer than nine are open.
        KeyCode[] digits = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4,
                KeyCode.DIGIT5, KeyCode.DIGIT6, KeyCode.DIGIT7, KeyCode.DIGIT8, KeyCode.DIGIT9};
        for (int i = 0; i < digits.length; i++) {
            int index = i;
            shortcuts.add(digits[i], "Switch to tab " + (i + 1), () -> selectRepoTab(index));
        }

        // Ctrl+Alt+1..9 opens the Nth favorited repository - the commercial clients' binding.
        shortcuts.section("Favorites");
        for (int i = 0; i < digits.length; i++) {
            int index = i;
            shortcuts.add(new KeyCodeCombination(digits[i],
                            KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN),
                    "Open favorite repository " + (i + 1), () -> openFavorite(index));
        }
        return shortcuts;
    }

    /** Opens the Nth starred repository (0-based), if that many are favorited. */
    private void openFavorite(int index) {
        List<File> favorites = com.jfx.git.core.Favorites.load();
        if (index < favorites.size()) {
            openRepository(favorites.get(index));
        }
    }

    /**
     * Opens the activity log tab, pointed at the repository in front.
     *
     * <p>Reachable with no repository open - the application scope has entries from the
     * moment the process starts, and "what happened before I opened anything" is a
     * question worth being able to ask. The repository is read before the log tab is
     * brought forward, since once it is in front there is no repository in front; with
     * none, an open log keeps the repository it was showing.
     */
    private void showActivityLog() {
        RepoTab active = getActiveRepoTab();
        String repository = active == null ? null : active.folderTitle();
        com.jfx.git.ui.ActivityLogTab log = showToolTab(com.jfx.git.ui.ActivityLogTab.class,
                () -> new com.jfx.git.ui.ActivityLogTab(repository));
        log.showRepository(repository);
    }

    private void showCommandPalette(Stage stage) {
        CommandPalette palette = new CommandPalette(this);
        palette.setX(stage.getX() + stage.getWidth() / 2 - 300);
        palette.setY(stage.getY() + stage.getHeight() / 2 - 200);
        palette.show();
    }

    /** Moves the selection {@code step} tabs along; see {@link com.jfx.git.ui.TabStrip}. */
    private void cycleTab(int step) {
        tabStrip.cycle(step);
    }

    /** The keys bound to the shortcut described as {@code description}, as this platform writes them; "" for none. */
    public String shortcutFor(String description) {
        if (shortcuts == null) return "";
        return shortcuts.bindings().stream()
                .filter(binding -> binding.description().equals(description))
                .findFirst().map(com.jfx.git.ui.Shortcuts.Binding::display).orElse("");
    }

    /**
     * A repository in the tab strip and the folder it is for.
     *
     * @param tab       a {@link RepoTab}, or a restored tab not built yet (see {@link RestoredTab})
     * @param directory the repository's working tree, or the folder asked for while it opens
     */
    public record OpenRepository(Tab tab, File directory) {
    }

    /**
     * Every repository in the strip, in strip order - the restored tabs nobody has looked
     * at yet included. Listing only the built ones left those out of the palette's
     * "Switch to" and offered them again as recent repositories to open.
     */
    public List<OpenRepository> openRepositories() {
        List<OpenRepository> open = new java.util.ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof RepoTab repoTab) {
                File workTree = repoTab.getGitService().getWorkTree();
                open.add(new OpenRepository(tab, workTree != null ? workTree : repoTab.getRequestedDirectory()));
            } else if (tab instanceof RestoredTab restored) {
                open.add(new OpenRepository(tab, restored.directory()));
            }
        }
        return open;
    }

    /** Brings {@code tab} to the front; a restored tab is built by being selected. */
    public void selectTab(Tab tab) {
        tabPane.getSelectionModel().select(tab);
    }

    /** Runs {@code action} against the active repository tab, or says there is none. */
    private void withRepo(java.util.function.Consumer<RepoTab> action) {
        RepoTab tab = getActiveRepoTab();
        if (tab == null) {
            showTransientHint("Open a repository first");
            return;
        }
        action.accept(tab);
    }

    /** Selects the {@code index}-th repository tab; see {@link com.jfx.git.ui.TabStrip}. */
    private void selectRepoTab(int index) {
        // TabStrip.selectRepoTab counts RepoTabs only, so the restored tabs not built
        // yet would shift every number after them. Its rule, counting those too.
        List<Tab> repoTabs = tabPane.getTabs().stream()
                .filter(tab -> tab instanceof RepoTab || tab instanceof RestoredTab)
                .toList();
        if (repoTabs.isEmpty()) {
            return;
        }
        int target = index == 8 ? repoTabs.size() - 1 : Math.min(index, repoTabs.size() - 1);
        tabPane.getSelectionModel().select(repoTabs.get(target));
    }

    /**
     * Says something that needs no answer, in the status bar of the repository tab in
     * front - or, when the tab in front has none (no repository open, Settings), in a
     * brief non-modal notice at the foot of the window.
     *
     * <p>Some callers are here <em>because</em> no repository tab is open, which is
     * exactly when there is no status bar. Others - "up to date", "cached credentials
     * forgotten" - were dialogs that only said a thing was done; the notice says the same
     * without a window the user has to dismiss.
     */
    private void showTransientHint(String message) {
        RepoTab tab = getActiveRepoTab();
        if (tab != null) {
            tab.getStatusBar().info(message);
            return;
        }
        if (primaryStage == null || primaryStage.getScene() == null) {
            LOGGER.info(message);
            return;
        }
        Label hint = new Label(message);
        hint.getStyleClass().add("transient-hint");
        Popup popup = new Popup();
        popup.getContent().add(hint);
        popup.setAutoHide(true);
        // A Popup owns its own scene, so it inherits none of the window's styling
        // - including the theme variables the hint's colours are expressed in.
        popup.getScene().getStylesheets().setAll(primaryStage.getScene().getStylesheets());
        popup.show(primaryStage,
                primaryStage.getX() + primaryStage.getWidth() / 2 - 100,
                primaryStage.getY() + primaryStage.getHeight() - 80);
        com.jfx.git.ui.Motion.fadeIn(popup);
        PauseTransition dismiss = new PauseTransition(Duration.seconds(3));
        dismiss.setOnFinished(e -> popup.hide());
        dismiss.play();
    }

    /**
     * The global-identity editor, the named-profiles manager, and a switcher naming
     * which profile (if any) is active on the currently-selected repository tab.
     *
     * <p>The switcher is rebuilt every time the menu opens rather than once at startup,
     * unlike the theme and engine menus above: which profiles exist and which is active
     * both depend on state that changes while the app runs (a profile created just now,
     * a different tab selected since the menu was last opened), not a preference fixed
     * for the session.
     */
    private Menu buildProfilesMenu() {
        Menu menu = new Menu("Profiles");

        MenuItem manageGlobal = new MenuItem("Manage Global Profile…");
        manageGlobal.setOnAction(e -> openSettings(com.jfx.git.ui.SettingsSection.PROFILES));

        MenuItem manageProfiles = new MenuItem("Manage Profiles…");
        manageProfiles.setOnAction(e -> openSettings(com.jfx.git.ui.SettingsSection.PROFILES));

        menu.getItems().addAll(manageGlobal, manageProfiles);

        menu.setOnShowing(e -> {
            // Drop everything after the two manage items and rebuild the switcher
            // section fresh - simplest way to keep it honest about both the current
            // profile list and which tab is now active, neither known when the menu
            // was built.
            menu.getItems().setAll(manageGlobal, manageProfiles);

            List<com.jfx.git.model.Profile> profiles = com.jfx.git.core.ProfileStore.profiles();
            if (profiles.isEmpty()) {
                return;
            }

            // The active-tab switcher only makes sense with a repository tab actually
            // selected; "open a profile's tabs" below does not need one at all.
            RepoTab active = getActiveRepoTab();
            if (active != null) {
                menu.getItems().add(new SeparatorMenuItem());
                String repoKey = com.jfx.git.core.ProfileStore.key(active.getGitService().getWorkTree());
                String activeName = com.jfx.git.core.ProfileStore.activeProfileName(repoKey);
                ToggleGroup group = new ToggleGroup();
                for (com.jfx.git.model.Profile profile : profiles) {
                    RadioMenuItem item = new RadioMenuItem(profile.name());
                    item.setToggleGroup(group);
                    item.setSelected(profile.name().equals(activeName));
                    item.setOnAction(ev -> applyProfileToActiveTab(profile));
                    menu.getItems().add(item);
                }
            }

            // "Tabs per profile" (Wave 4 item #4.4), scoped down to its additive half -
            // see openProfileTabs's own javadoc for why. Only profiles that have
            // actually been applied to something show up here; nothing to reopen
            // otherwise.
            List<MenuItem> openTabItems = new ArrayList<>();
            for (com.jfx.git.model.Profile profile : profiles) {
                int count = com.jfx.git.core.ProfileStore.tabPaths(profile.name()).size();
                if (count == 0) {
                    continue;
                }
                MenuItem openTabs = new MenuItem("Open \"" + profile.name() + "\" tabs (" + count + ")");
                openTabs.setOnAction(ev -> openProfileTabs(profile));
                openTabItems.add(openTabs);
            }
            if (!openTabItems.isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
                menu.getItems().addAll(openTabItems);
            }
        });

        return menu;
    }

    /**
     * Writes {@code profile}'s identity into the active tab's repository's own local
     * config and remembers the choice, so reselecting this repository's tab (or
     * reopening the menu) shows the same profile still selected. Also records the
     * repository as belonging to this profile's own remembered tab set - see {@link
     * #openProfileTabs} for what that set is for and why it works this way.
     */
    private void applyProfileToActiveTab(com.jfx.git.model.Profile profile) {
        RepoTab tab = getActiveRepoTab();
        if (tab == null) return;
        String repoKey = com.jfx.git.core.ProfileStore.key(tab.getGitService().getWorkTree());
        // Only the repository's config changes: nothing on screen draws it.
        tab.operations().operation("Applying profile " + profile.name(), com.jfx.git.ui.OperationRunner.Refresh.NONE)
                .onSuccess(result -> {
                    com.jfx.git.core.ProfileStore.setActiveProfileName(repoKey, profile.name());
                    com.jfx.git.core.ProfileStore.addTabPath(profile.name(), repoKey);
                })
                .run(() -> tab.getGitService().setLocalIdentity(
                        profile.gitName(), profile.gitEmail(), profile.signingKey(), profile.gpgSign()));
    }

    /**
     * Opens every repository {@code profile} has previously been applied to that is
     * not already open, as new tabs.
     *
     * <p>This is the deliberately scoped-down half of the commercial clients' "tabs per
     * profile" (`PARITY_PLAN.md` Wave 4 item #4.4, re-audited and picked back up):
     * The commercial version closes every tab that does not belong to the profile being
     * switched to, treating a profile as an exclusive workspace. This app already
     * lets two repositories sit on two different profiles at once with no paywall
     * behind it (§3.5) - a real, tested, already-shipped capability - so an exclusive
     * "close everything else" switch would silently undo that on every use of this
     * menu, a net loss relative to what already shipped rather than a gain. Adding
     * the profile's remembered group to whatever is already open instead keeps the
     * part of the feature that is actually useful (quickly reopening a set of
     * repositories associated with one identity) without the destructive part.
     *
     * <p>No identity re-application here: a path only ever joins a profile's tab set
     * via {@link #applyProfileToActiveTab}, which already wrote that identity into
     * the repository's own {@code .git/config} at the time - it is durable, so
     * reopening the repository does not need to set it again. A moved or deleted
     * repository is skipped silently, the same way {@link #restoreTabs} already
     * treats a stale session path.
     */
    private void openProfileTabs(com.jfx.git.model.Profile profile) {
        for (String path : com.jfx.git.core.ProfileStore.tabPaths(profile.name())) {
            File dir = new File(path);
            if (dir.isDirectory() && looksLikeRepository(dir)) {
                loadRepository(dir);
            }
        }
    }

    /** Pushes a changed diff preference into every open repository tab. */
    private void applyPreferencesToTabs() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof RepoTab repoTab) {
                repoTab.applyPreferences();
            }
        }
    }

    /**
     * Persists the engine choice and rebuilds the backend of every open tab.
     *
     * <p>Saving the preference alone would only affect repositories opened afterwards,
     * which is what made the switcher look like it did nothing.
     */
    public void applyEngineToTabs(com.jfx.git.core.GitBackendFactory.BackendEngine engine) {
        com.jfx.git.core.GitBackendFactory.setEnginePreference(engine);
        for (Tab tab : tabPane.getTabs()) {
            // A repository whose own settings name an engine keeps it; see hasEngineOverride.
            if (tab instanceof RepoTab repoTab && !repoTab.hasEngineOverride()) {
                repoTab.switchEngine(engine);
            }
        }
    }

    public RepoTab getActiveRepoTab() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        return selected instanceof RepoTab repoTab ? repoTab : null;
    }

    @Override
    public void stop() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof RepoTab repoTab) {
                repoTab.close();
            }
        }
        GitTaskRunner.shutdown();
        com.jfx.git.core.SshSessionManager.shutdown();
    }

    /**
     * Gives the window its minimum: the repository layout's floor with the menu bar and the
     * tab strip above it, scaled by the zoom and held to the screen. See MainWindowSize.
     */
    void installWindowMinimum() {
        com.jfx.git.ui.MainWindowSize.install(primaryStage, () -> {
            com.jfx.git.ui.MainWindowSize.Size layout = RepoTab.minimumSize();
            return new com.jfx.git.ui.MainWindowSize.Size(layout.width(), layout.height() + windowBarsHeight());
        });
    }

    /** What the window puts above every tab - the menu bar and the tab strip - in layout pixels. */
    private double windowBarsHeight() {
        // Before its first CSS pass a control has no skin, and nothing to measure.
        menuBar.applyCss();
        tabPane.applyCss();
        javafx.scene.Node strip = tabPane.lookup(".tab-header-area");
        return menuBar.prefHeight(-1) + (strip == null ? 0 : strip.prefHeight(-1));
    }

    /** Shows the window over everything else, out of the taskbar if it was minimised there. */
    private void bringToFront() {
        if (primaryStage.isIconified()) {
            primaryStage.setIconified(false);
        }
        primaryStage.toFront();
        primaryStage.requestFocus();
    }

    /**
     * This process's claim on its settings location (see SingleInstance), held for as long
     * as it runs. Null when main did not run, or could not lock the location at all.
     */
    private static com.jfx.git.core.SingleInstance instance;

    /**
     * True when main started this process: the real application, rather than a test that
     * builds one and hands it TestFX's primary stage. Only the real window gets a minimum
     * size. That stage is shared by every test class in the JVM, and a minimum set on it
     * would outlive the test and clamp the next class's window.
     */
    private static boolean launchedFromMain;

    /**
     * Every launch goes through here: the packaged launchers name this class, and so does
     * the JavaFX Maven plugin. With JavaFX on the module path the JDK's launcher starts the
     * toolkit before it calls this, then this decides whether there is an application to
     * launch at all.
     */
    public static void main(String[] args) {
        launchedFromMain = true;
        java.nio.file.Path lockFile = com.jfx.git.core.SingleInstance.lockFileFor(
                com.jfx.git.core.Settings.file());
        try {
            java.util.Optional<com.jfx.git.core.SingleInstance> claim =
                    com.jfx.git.core.SingleInstance.claim(lockFile);
            if (claim.isEmpty()) {
                // Before anything opens a repository, reads the session or writes a setting:
                // all of that belongs to the copy already running.
                LOGGER.info("Lyra is already running against " + lockFile.getParent() + "; exiting");
                com.jfx.git.ui.AlreadyRunningNotice.showAndExit(lockFile);
                return;
            }
            instance = claim.get();
        } catch (java.io.IOException e) {
            // A folder that cannot be locked is no reason to refuse to start.
            LOGGER.log(java.util.logging.Level.WARNING,
                    "Could not check for another running copy; starting anyway", e);
        }
        launch(args);
    }

    /**
     * The run that trains an ahead-of-time cache (JDK 25, JEP 483/514/515): started with
     * {@code -Dlyra.training=<repository>} and {@code -XX:AOTCacheOutput=app.aot} by
     * {@code package-*.sh --aot}. It opens that one repository, waits until its first page
     * has been drawn and the repository lane has gone quiet, and exits - so the cache
     * holds the classes, linkage and profiles of a real startup, and nothing a person
     * would have to sit through.
     */
    static final class TrainingRun {

        /** Gives up on a repository that never settles, rather than hanging a build. */
        private static final java.time.Duration LIMIT = java.time.Duration.ofMinutes(2);

        private TrainingRun() {
        }

        /** The repository to train on, or null in an ordinary run. */
        static File repository() {
            String path = System.getProperty("lyra.training");
            return path == null || path.isBlank() ? null : new File(path);
        }

        static void exitWhenDrawn(LyraApp app) {
            long deadline = System.nanoTime() + LIMIT.toNanos();
            javafx.animation.Timeline poll = new javafx.animation.Timeline();
            poll.setCycleCount(javafx.animation.Animation.INDEFINITE);
            poll.getKeyFrames().add(new javafx.animation.KeyFrame(Duration.millis(250), e -> {
                RepoTab tab = app.getActiveRepoTab();
                boolean busy = tab != null && GitTaskRunner.repositoryWorkWithin(tab.getGitService(), 2_000);
                boolean settled = tab != null && tab.getGitService().isOpen() && !busy;
                if (!settled && System.nanoTime() < deadline) {
                    return;
                }
                poll.stop();
                LOGGER.info(settled ? "Training run: first page drawn, exiting"
                        : "Training run: gave up waiting for the repository (tab "
                        + (tab == null ? "missing" : tab.getGitService().isOpen() ? "open" : "not open")
                        + ", repository lane " + (busy ? "busy" : "quiet")
                        + ", tabs " + app.tabPane.getTabs().stream().map(Tab::getText).toList()
                        + "), exiting");
                afterFirstLayout(app.primaryStage.getScene(), () -> Platform.runLater(TrainingRun::exit));
            }));
            poll.play();
        }

        private static void exit() {
            Platform.exit();
            // The cache is written as the JVM exits. Platform.exit ends the toolkit, but a
            // stray non-daemon thread would keep the process - and the build - waiting.
            Thread reaper = new Thread(() -> {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException ignored) {
                    return;
                }
                System.exit(0);
            }, "training-exit");
            reaper.setDaemon(true);
            reaper.start();
        }
    }
}
