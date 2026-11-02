package com.jfx.git.ui;

import com.jfx.git.LyraApp;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import com.jfx.git.model.PullMode;

/** Ctrl+P quick-action launcher. */
public class CommandPalette extends Stage {

    private static final Logger LOGGER = Logger.getLogger(CommandPalette.class.getName());

    private final ListView<PaletteItem> resultsList;
    private final ObservableList<PaletteItem> allItems = FXCollections.observableArrayList();

    public CommandPalette(LyraApp app) {
        initStyle(StageStyle.UNDECORATED);
        // Owned, so it neither gets a taskbar button nor opens behind the window it serves.
        DialogOwner.adopt(this);

        TextField searchField = new TextField();
        searchField.setPromptText("Type a command or branch name…");
        searchField.getStyleClass().add("palette-search");

        resultsList = new ListView<>();
        resultsList.getStyleClass().add("list-dark");
        // The category as a fixed column rather than a prefix on the command. Every entry
        // read "Git: Fetch all", so the first four characters of every row were the same
        // four characters and the part being searched for started at a ragged offset.
        // Split out, the commands line up and the category becomes scannable instead of
        // repeated.
        resultsList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PaletteItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label category = new Label(item.category());
                category.getStyleClass().add("palette-category");
                Label command = new Label(item.command());
                command.getStyleClass().add("palette-command");
                javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
                javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
                // The key it also has: the palette is where people go to find a command,
                // which makes it the place to learn its shortcut.
                Label keys = new Label(item.shortcut());
                keys.getStyleClass().addAll("palette-shortcut", "t-caption");
                javafx.scene.layout.HBox row =
                        new javafx.scene.layout.HBox(UiScale.GAP, category, command, spacer, keys);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        });

        VBox root = new VBox(searchField, resultsList);
        root.getStyleClass().addAll("dialog-root", "palette-root");

        Scene scene = new Scene(root, 600, 400);
        try {
            scene.getStylesheets().add(getClass().getResource("/css/lyra.css").toExternalForm());
        } catch (Exception ex) {
            LOGGER.warning("Could not load css/lyra.css for palette");
        }
        com.jfx.git.ui.ThemeManager.register(scene);
        setScene(scene);

        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterResults(newVal));

        searchField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    resultsList.requestFocus();
                    resultsList.getSelectionModel().selectFirst();
                    e.consume();
                }
                case ESCAPE -> close();
                case ENTER -> {
                    if (resultsList.getSelectionModel().isEmpty()) {
                        resultsList.getSelectionModel().selectFirst();
                    }
                    executeSelected();
                    e.consume();
                }
                default -> { }
            }
        });

        resultsList.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                executeSelected();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                close();
            }
        });

        resultsList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) executeSelected();
        });

        focusedProperty().addListener((obs, old, isFocused) -> {
            if (!isFocused) close();
        });

        populateCommands(app);
        // Apply recent-first ordering when the palette first opens, before the user types.
        filterResults("");
        setOnShown(e -> {
            searchField.requestFocus();
            Motion.fadeIn(this);
        });
    }

    /** The last few commands run from the palette, newest first; offered first next time. */
    private static final java.util.Deque<String> RECENT = new java.util.ArrayDeque<>();
    private static final int RECENT_KEPT = 5;

    private void filterResults(String query) {
        resultsList.setItems(FXCollections.observableArrayList(ranked(allItems, query, List.copyOf(RECENT))));
        resultsList.getSelectionModel().selectFirst();
    }

    /**
     * The items for {@code query}, best first. With no query, the recently used ones lead.
     * With one, a command matches when the query's letters appear in it in order - "fa"
     * finds "Fetch all", "psh" finds "Push" - and those where they appear together, or at
     * the start of a word, rank above those where they are scattered.
     */
    static List<PaletteItem> ranked(List<PaletteItem> items, String query, List<String> recent) {
        if (query == null || query.isBlank()) {
            List<PaletteItem> out = new java.util.ArrayList<>();
            for (String text : recent) {
                items.stream().filter(i -> i.displayText().equals(text)).findFirst().ifPresent(out::add);
            }
            items.stream().filter(i -> !out.contains(i)).forEach(out::add);
            return out;
        }
        String q = query.toLowerCase(Locale.ROOT).strip();
        record Scored(PaletteItem item, int score, int order) {
        }
        List<Scored> scored = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            int score = score(items.get(i).displayText().toLowerCase(Locale.ROOT), q);
            if (score > 0) scored.add(new Scored(items.get(i), score, i));
        }
        scored.sort(java.util.Comparator.comparingInt((Scored s) -> -s.score()).thenComparingInt(Scored::order));
        return scored.stream().map(Scored::item).toList();
    }

    /** How well {@code query} matches {@code text}: 0 for not at all, higher for better. */
    static int score(String text, String query) {
        int at = text.indexOf(query);
        if (at >= 0) {
            boolean wordStart = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            return 10_000 - at + (wordStart ? 1_000 : 0);
        }
        // Letters in order, scattered: each one found after the last, rewarded for
        // following straight on and for starting a word.
        int score = 1;
        int from = 0;
        int previous = -2;
        for (char c : query.toCharArray()) {
            if (c == ' ') continue;
            int found = text.indexOf(c, from);
            if (found < 0) return 0;
            if (found == previous + 1) score += 5;
            if (found == 0 || !Character.isLetterOrDigit(text.charAt(found - 1))) score += 3;
            previous = found;
            from = found + 1;
        }
        return score;
    }

    private void executeSelected() {
        PaletteItem selected = resultsList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            RECENT.remove(selected.displayText());
            RECENT.addFirst(selected.displayText());
            while (RECENT.size() > RECENT_KEPT) RECENT.removeLast();
            close();
            selected.action().run();
        }
    }

    private void populateCommands(LyraApp app) {
        RepoTab activeTab = app.getActiveRepoTab();
        if (activeTab != null) {
            RepoToolbar toolbar = activeTab.getToolbar();

            allItems.add(new PaletteItem("Git: Fetch all", toolbar::triggerFetchAll, app.shortcutFor("Fetch all")));
            allItems.add(new PaletteItem("Git: Branch history (reflog)", toolbar::openReflog));
            allItems.add(new PaletteItem("Git: Pull (fast-forward if possible)",
                    () -> toolbar.triggerPull(PullMode.MERGE)));
            allItems.add(new PaletteItem("Git: Pull (fast-forward only)",
                    () -> toolbar.triggerPull(PullMode.FF_ONLY)));
            allItems.add(new PaletteItem("Git: Pull (rebase)",
                    () -> toolbar.triggerPull(PullMode.REBASE)));
            allItems.add(new PaletteItem("Git: Push", toolbar::triggerPush));
            allItems.add(new PaletteItem("Git: Push to…", toolbar::promptPushTo));
            allItems.add(new PaletteItem("Git: Stash changes", toolbar::triggerStash));
            allItems.add(new PaletteItem("Git: Stash with options…", toolbar::triggerStashWithOptions));
            allItems.add(new PaletteItem("Git: Working-tree snapshots", activeTab::openSnapshots));
            allItems.add(new PaletteItem("Git: Stage all", () -> activeTab.getRightPanel().stageAllFiles(),
                    app.shortcutFor("Stage all")));
            allItems.add(new PaletteItem("Git: Unstage all", () -> activeTab.getRightPanel().unstageAllFiles(),
                    app.shortcutFor("Unstage all")));
            allItems.add(new PaletteItem("Git: Commit staged changes",
                    () -> activeTab.getRightPanel().commitStaged()));
            allItems.add(new PaletteItem("Git: Refresh this repository", activeTab::refresh,
                    app.shortcutFor("Refresh this repository")));
            allItems.add(new PaletteItem("Git: Apply patch…", activeTab::promptApplyPatch));
            allItems.add(new PaletteItem("Git: Git LFS…", () -> activeTab.getSidebar().promptLfs()));
            // Always offered: the SUBMODULES section, whose menu also has it, is hidden
            // until the repository has a submodule.
            allItems.add(new PaletteItem("Repository: Add submodule…",
                    () -> activeTab.getSidebar().promptAddSubmodule()));
            allItems.add(new PaletteItem("View: Repository settings",
                    () -> activeTab.getSidebar().openRepositorySettings()));
            allItems.add(new PaletteItem("View: Open terminal here", toolbar::openTerminal));
            allItems.add(new PaletteItem("Git: Undo last action", toolbar::undo, app.shortcutFor("Undo the last action")));
            allItems.add(new PaletteItem("Git: Redo", toolbar::redo, app.shortcutFor("Redo")));
            allItems.add(new PaletteItem("Git: New branch…", () -> toolbar.promptNewBranch(), app.shortcutFor("New branch")));
            allItems.add(new PaletteItem("Git: Clean up branches…", () -> activeTab.getSidebar().openBranchCleanup()));
            allItems.add(new PaletteItem("File: Search in files…", activeTab::openSearchInFiles,
                    app.shortcutFor("Search in files")));

            // File operations reach the same code the browser's context menu uses, so
            // "create a file" is available without hunting for the right folder first.
            allItems.add(new PaletteItem("File: Create file…",
                    () -> promptCreateFile(activeTab)));
            allItems.add(new PaletteItem("File: Ignore a path…",
                    () -> promptIgnore(activeTab)));
            allItems.add(new PaletteItem("File: Edit current file",
                    () -> activeTab.getRightPanel().editCurrentFile()));

            // Branch checkout entries - the previous version built a repo-less
            // GitService here, discarded the result and never added any items.
            //
            // Read from the sidebar's cache rather than from git: this method runs on the
            // FX thread every time the palette opens, and asking the backend made that a
            // subprocess per Ctrl+P. The sidebar loaded the same list on its worker.
            for (String name : activeTab.getSidebar().cachedLocalBranches()) {
                allItems.add(new PaletteItem("Checkout: " + name,
                        () -> toolbar.checkoutBranch(name)));
            }
            // Remotes, from the same cached load: what the sidebar's menu on a remote's
            // row does, without finding the row first.
            SidebarPane sidebar = activeTab.getSidebar();
            allItems.add(new PaletteItem("Remote: Add remote…", sidebar::promptAddRemote));
            for (String remote : sidebar.cachedRemoteNames()) {
                allItems.add(new PaletteItem("Remote: Fetch " + remote, () -> sidebar.fetchRemote(remote)));
                allItems.add(new PaletteItem("Remote: Edit URL of " + remote + "…",
                        () -> sidebar.promptEditRemote(remote)));
            }
            allItems.add(new PaletteItem("Git: Set upstream of current branch…",
                    () -> sidebar.promptSetUpstream(sidebar.cachedCurrentBranch())));

            // Git Flow: setting up is always offered; the rest once the repository is.
            var flow = sidebar.cachedFlowConfig();
            allItems.add(new PaletteItem(flow == null ? "Git Flow: Set up…" : "Git Flow: Settings…",
                    sidebar::promptFlowSetup));
            if (flow != null) {
                for (com.jfx.git.core.GitFlow.Kind kind : com.jfx.git.core.GitFlow.Kind.values()) {
                    allItems.add(new PaletteItem("Git Flow: Start " + kind.word() + "…",
                            () -> sidebar.promptStartFlow(kind)));
                }
                String onBranch = sidebar.cachedCurrentBranch();
                com.jfx.git.core.GitFlow.Kind kind = com.jfx.git.core.GitFlow.kindOf(flow, onBranch);
                if (kind != null) {
                    allItems.add(new PaletteItem("Git Flow: Finish " + onBranch + "…",
                            () -> sidebar.confirmFinishFlow(onBranch)));
                }
            }

            // The same dialog as the sidebar's "Merge into … with options…", from the same
            // cached load. The current branch is left out: merging it into itself is not
            // a merge.
            String current = activeTab.getSidebar().cachedCurrentBranch();
            for (String name : activeTab.getSidebar().cachedLocalBranches()) {
                if (name.equals(current)) continue;
                allItems.add(new PaletteItem("Merge: " + name + " into current…",
                        () -> activeTab.getSidebar().promptMerge(name)));
            }
        }

        allItems.add(new PaletteItem("File: Open repository", app::openRepoDialog));
        allItems.add(new PaletteItem("File: New repository", app::initRepoDialog));
        allItems.add(new PaletteItem("File: Clone repository", app::cloneRepoDialog));
        allItems.add(new PaletteItem("View: Settings", app::showSettings, app.shortcutFor("Settings")));
        allItems.add(new PaletteItem("View: Global profile", () -> app.openSettings(SettingsSection.PROFILES)));
        allItems.add(new PaletteItem("View: Cloud integrations", () -> app.openSettings(SettingsSection.INTEGRATIONS)));
        allItems.add(new PaletteItem("View: Keyboard shortcuts", () -> app.openSettings(SettingsSection.KEYBOARD_SHORTCUTS),
                app.shortcutFor("Show keyboard shortcuts")));
        allItems.add(new PaletteItem("View: Switch theme",
                () -> com.jfx.git.ui.ThemeManager.apply(com.jfx.git.ui.ThemeManager.opposite())));
        // Deliberately outside the repository block: "what happened before I opened
        // anything" is a question worth being able to ask with no repository open.
        // Every open repository, and the recent ones that are not: the palette as a way to
        // move between repositories without reaching for the mouse.
        java.util.Set<String> open = new java.util.HashSet<>();
        for (LyraApp.OpenRepository repository : app.openRepositories()) {
            var tab = repository.tab();
            if (tab != activeTab) {
                allItems.add(new PaletteItem("Repository: Switch to " + tab.getText(), () -> app.selectTab(tab)));
            }
            if (repository.directory() != null) open.add(repository.directory().getAbsolutePath());
        }
        for (java.io.File recent : com.jfx.git.core.RecentRepositories.load()) {
            if (!open.contains(recent.getAbsolutePath())) {
                allItems.add(new PaletteItem("Repository: Open " + recent.getName(), () -> app.openRepository(recent)));
            }
        }

        allItems.add(new PaletteItem("View: Activity logs", app::openActivityLog,
                app.shortcutFor("Activity logs")));

        resultsList.setItems(allItems);
    }

    /**
     * Creates a file from a path typed straight into a dialog.
     *
     * <p>Accepts {@code foo/bar.txt} and makes {@code foo} on the way, which is the whole
     * point of typing a path rather than navigating to a folder first.
     */
    private static void promptCreateFile(RepoTab tab) {
        var workspace = new com.jfx.git.core.WorkspaceFiles(tab.getGitService().getWorkTree());

        com.jfx.git.ui.dialogs.Dialogs.prompt("Create file")
                .icon(Icons.file())
                .title("Create a file")
                .subtitle(workspace.workTree() == null ? null
                        : "Relative to the root of `" + workspace.workTree().getName() + "`")
                .label("Path")
                .placeholder("src/main/App.java")
                .hint("Missing folders are created. The file opens in the editor.")
                .validator(path -> WorkspaceTreeView.newPathProblem(workspace.workTree(), path.trim()))
                .action("Create file")
                .ask()
                .filter(path -> !path.isBlank())
                .ifPresent(path -> {
                    var result = workspace.createFile(path.trim());
                    tab.getStatusBar().report(result);
                    if (result.isOk()) {
                        tab.getRightPanel().openWorkingTreeFile(path.trim());
                        tab.refresh();
                    }
                });
    }

    /**
     * Adds a rule for a typed path to the root {@code .gitignore}. The rule is anchored, as
     * the ignore menus' "This file" is ({@code IgnoreScope.FILE}): the hint says so, because
     * a pattern typed here only matches at the top level.
     */
    private static void promptIgnore(RepoTab tab) {
        var workspace = new com.jfx.git.core.WorkspaceFiles(tab.getGitService().getWorkTree());

        com.jfx.git.ui.dialogs.Dialogs.prompt("Ignore a path")
                .icon(Icons.eyeClosed())
                .title("Add an ignore rule")
                .subtitle("To `.gitignore` at the repository root")
                .label("Path or pattern")
                .placeholder("build/")
                .hint("Written with a leading /, so it matches from the repository root only:"
                        + " *.log becomes /*.log, which leaves .log files in folders alone.")
                .action("Add rule")
                .ask()
                .filter(path -> !path.isBlank())
                .ifPresent(path -> {
                    var result = workspace.ignore(path.trim(), com.jfx.git.core.WorkspaceFiles.IgnoreScope.FILE);
                    tab.getStatusBar().report(result);
                    if (result.isOk()) tab.refresh();
                });
    }

    /**
     * One command, split at the colon its label already carried.
     *
     * <p>{@code displayText} stays the whole string, because that is what the filter
     * matches against - typing "git" should still find every git command, and typing
     * "fetch" should still find it by its command half.
     */
    public record PaletteItem(String displayText, Runnable action, String shortcut) {

        public PaletteItem(String displayText, Runnable action) {
            this(displayText, action, "");
        }

        /** The part before the colon: "Git", "File", "View", "Checkout". */
        public String category() {
            int colon = displayText.indexOf(':');
            return colon < 0 ? "" : displayText.substring(0, colon);
        }

        /** Everything after it, which is the part actually being looked for. */
        public String command() {
            int colon = displayText.indexOf(':');
            return colon < 0 ? displayText : displayText.substring(colon + 1).strip();
        }
    }
}
