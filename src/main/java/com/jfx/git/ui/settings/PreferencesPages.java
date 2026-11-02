package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.EngineDoctor;
import com.jfx.git.core.GitBackendFactory;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.core.SshSessionManager;
import com.jfx.git.core.libgit2.Libgit2Loader;
import com.jfx.git.ui.ThemeManager;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.jfx.git.ui.settings.SettingsLayout.hint;
import static com.jfx.git.ui.settings.SettingsLayout.page;
import static com.jfx.git.ui.settings.SettingsLayout.row;
import static com.jfx.git.ui.settings.SettingsLayout.section;

/**
 * The application's own settings: General, Appearance, Git Engine, External Tools, Diff
 * and SSH - the six pages that were the Preferences window, now sections of the Settings
 * tab.
 *
 * <p>A builder of pages rather than a window, so that the tab decides where they are
 * shown; the pages themselves are what they were, control for control.
 *
 * <p>Every control here is bound to something the application actually honours.
 * A settings screen full of switches that do nothing is worse than no settings screen,
 * so pages are added as the features behind them arrive rather than up front.
 *
 * <p>Changes apply as they are made - there is no OK/Cancel pair, because a theme that
 * only takes effect after a confirmation reads as broken.
 */
final class PreferencesPages {

    /** Run after a diff setting changes, so open viewers pick it up immediately. */
    private final Runnable onDiffSettingsChanged;

    /**
     * Run after the engine choice changes.
     *
     * <p>Separate from {@link #onDiffSettingsChanged} because changing engines has to
     * rebuild every open tab's backend, which is not what a diff-preference callback does.
     */
    private final java.util.function.Consumer<GitBackendFactory.BackendEngine> onEngineChanged;

    /**
     * Where a page says that something it did is done - "Cached credentials forgotten" -
     * when there is nothing to answer: the window's status line, not a dialog.
     */
    private final java.util.function.Consumer<String> notices;

    PreferencesPages(Runnable onDiffSettingsChanged,
                     java.util.function.Consumer<GitBackendFactory.BackendEngine> onEngineChanged,
                     java.util.function.Consumer<String> notices) {
        this.onDiffSettingsChanged = onDiffSettingsChanged == null ? () -> { } : onDiffSettingsChanged;
        this.onEngineChanged = onEngineChanged == null
                ? GitBackendFactory::setEnginePreference
                : onEngineChanged;
        this.notices = notices == null ? message -> { } : notices;
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    /**
     * The commercial clients' General category: repository defaults and history-loading behaviour,
     * as distinct from {@link #appearancePage()}'s theme and graph presentation.
     */
    Region generalPage() {
        VBox page = page("General");

        ChoiceBox<Integer> autoFetch = new ChoiceBox<>();
        autoFetch.getItems().setAll(0, 1, 5, 15, 60);
        autoFetch.setValue(AppPreferences.autoFetchIntervalMinutes());
        autoFetch.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer minutes) {
                return minutes == null || minutes <= 0 ? "Off" : "Every " + minutes + " min";
            }

            @Override
            public Integer fromString(String text) {
                return AppPreferences.autoFetchIntervalMinutes();
            }
        });
        autoFetch.valueProperty().addListener((obs, old, selected) ->
                AppPreferences.setAutoFetchIntervalMinutes(selected == null ? 0 : selected));

        TextField defaultBranch = new TextField(AppPreferences.defaultBranchName());
        defaultBranch.setPrefColumnCount(16);
        defaultBranch.textProperty().addListener((obs, old, text) ->
                AppPreferences.setDefaultBranchName(text));

        CheckBox autoStash = new CheckBox("Stash, retry and re-apply when changes block a checkout, pull or merge");
        // The id and the stored key keep their old names, so the setting carries over.
        autoStash.setId("autostash-on-checkout-pull");
        autoStash.setSelected(AppPreferences.autoStashOnCheckoutPull());
        autoStash.selectedProperty().addListener((obs, old, on) -> AppPreferences.setAutoStashOnCheckoutPull(on));

        CheckBox autoUpdateSubmodules = new CheckBox("Automatically update submodules after checkout or pull");
        autoUpdateSubmodules.setSelected(AppPreferences.autoUpdateSubmodules());
        autoUpdateSubmodules.selectedProperty().addListener((obs, old, on) ->
                AppPreferences.setAutoUpdateSubmodules(on));

        TextField initialCommits = new TextField(Integer.toString(AppPreferences.initialGraphCommits()));
        initialCommits.setPrefColumnCount(8);
        // Digits only, mirroring the field's own meaning: anything else cannot become a
        // commit count and would only leave setInitialGraphCommits parsing garbage.
        initialCommits.textProperty().addListener((obs, old, text) -> {
            if (text != null && !text.matches("\\d*")) {
                initialCommits.setText(old);
                return;
            }
            try {
                AppPreferences.setInitialGraphCommits(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                // Blank or mid-edit; the stored value is left as it was until this parses.
            }
        });

        CheckBox showAll = new CheckBox("Keep loading older commits while scrolling");
        showAll.setSelected(AppPreferences.showAllCommitsInGraph());
        showAll.selectedProperty().addListener((obs, old, on) -> {
            AppPreferences.setShowAllCommitsInGraph(on);
            initialCommits.setDisable(on);
        });
        initialCommits.setDisable(showAll.isSelected());

        CheckBox updateCheck = new CheckBox("Look for a newer version once a day");
        updateCheck.setSelected(AppPreferences.updateCheckEnabled());
        updateCheck.selectedProperty().addListener((obs, old, on) -> AppPreferences.setUpdateCheckEnabled(on));
        TextField updateFeed = new TextField(AppPreferences.updateFeedUrl());
        updateFeed.setId("update-feed");
        updateFeed.setPromptText("https://api.github.com/repos/owner/repo/releases/latest");
        updateFeed.setPrefColumnCount(30);
        updateFeed.textProperty().addListener((obs, old, text) -> AppPreferences.setUpdateFeedUrl(text));

        page.getChildren().addAll(
                section("Updates"),
                updateCheck,
                row("Release feed", updateFeed),
                hint("Where releases are published. Nothing is asked of anyone while this is blank, "
                        + "and nothing is ever downloaded: a newer version is a link in the status bar."),
                section("Auto-Fetch"),
                row("Interval", autoFetch),
                hint("Fetches every remote in the background on this schedule. Never "
                        + "prompts for credentials it does not already have - a remote "
                        + "needing a login or an SSH passphrase for the first time is "
                        + "skipped silently until the next fetch you start yourself."),
                section("Repositories"),
                row("Default branch name", defaultBranch),
                hint("Used for a new repository's initial branch, the way \"git init -b\" "
                        + "would take it."),
                autoUpdateSubmodules,
                hint("Off by default: unlike auto-fetch, this runs unasked as a side effect "
                        + "of a checkout or pull you already triggered. A repository with no "
                        + "submodules is never affected either way."),
                autoStash,
                hint("Without asking first. Off, a checkout or pull that your uncommitted changes "
                        + "are in the way of asks whether to do this; its \"Always do this\" turns "
                        + "this on. A re-apply that conflicts keeps your changes in the stash."),
                section("Commit graph"),
                row("Initial commits", initialCommits),
                hint("How many commits the graph walks before its first page stops. "
                        + "Scrolling to the end still loads more; this only changes where "
                        + "the first page ends."),
                showAll,
                hint("Walks the whole history up front instead of paging it in on scroll. "
                        + "Slower to open a large repository, quicker to search or filter "
                        + "across everything once it has."),
                hint("All three take effect the next time a repository is opened or refreshed."));
        page.getChildren().addAll(ConflictPreventionSettings.generalRows());
        return page;
    }

    Region appearancePage() {
        VBox page = page("Appearance");

        // Dark themes, a rule, then the light ones, each named for what it is. A choice box
        // takes a Separator among its items but not a heading, so the dark/light grouping is
        // the rule plus the base in the name (see Theme#displayName), which is also what
        // survives when the box is closed and shows only the chosen one.
        ChoiceBox<Object> theme = new ChoiceBox<>();
        theme.setId("built-in-theme");
        boolean ruled = false;
        for (ThemeManager.Theme builtIn : ThemeManager.Theme.values()) {
            if (builtIn.base() == ThemeManager.Base.LIGHT && !ruled) {
                theme.getItems().add(new javafx.scene.control.Separator());
                ruled = true;
            }
            theme.getItems().add(builtIn);
        }
        theme.setValue(ThemeManager.current());
        theme.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Object value) {
                return value instanceof ThemeManager.Theme builtIn ? builtIn.displayName() : "";
            }

            @Override
            public Object fromString(String text) {
                return ThemeManager.current();
            }
        });
        // --- A theme of the user's own, laid over one of the two above.
        ChoiceBox<String> custom = new ChoiceBox<>();
        custom.setId("custom-theme");
        Label customProblems = hint("");
        customProblems.setId("custom-theme-problems");
        boolean[] syncing = new boolean[1];
        Runnable reloadCustom = () -> {
            syncing[0] = true;
            custom.getItems().setAll(NO_CUSTOM_THEME);
            com.jfx.git.ui.UserThemes.list().forEach(found -> custom.getItems().add(found.name()));
            var inEffect = ThemeManager.currentUserTheme();
            custom.setValue(inEffect != null && custom.getItems().contains(inEffect.name())
                    ? inEffect.name() : NO_CUSTOM_THEME);
            syncing[0] = false;
            describeProblems(customProblems, com.jfx.git.ui.UserThemes.named(
                    NO_CUSTOM_THEME.equals(custom.getValue()) ? null : custom.getValue()));
        };
        custom.valueProperty().addListener((obs, old, selected) -> {
            if (syncing[0] || selected == null) return;
            var chosen = NO_CUSTOM_THEME.equals(selected) ? null : com.jfx.git.ui.UserThemes.named(selected);
            syncing[0] = true;
            if (chosen == null) {
                ThemeManager.apply(theme.getValue() instanceof ThemeManager.Theme builtIn
                        ? builtIn : ThemeManager.current());
            } else {
                ThemeManager.applyUser(chosen);
                // The built-in choice follows: it is the theme this one is laid over.
                theme.setValue(chosen.base());
            }
            syncing[0] = false;
            describeProblems(customProblems, chosen);
        });

        theme.valueProperty().addListener((obs, old, selected) -> {
            if (selected instanceof ThemeManager.Theme builtIn && !syncing[0]) {
                // A built-in theme chosen by hand is that theme as it is.
                ThemeManager.apply(builtIn);
                syncing[0] = true;
                custom.setValue(NO_CUSTOM_THEME);
                syncing[0] = false;
                describeProblems(customProblems, null);
            }
        });

        Button openThemes = new Button("Open themes folder");
        openThemes.setOnAction(e -> {
            try {
                java.nio.file.Path folder = com.jfx.git.ui.UserThemes.ensureDirectory();
                GitTaskRunner.runDetached(() -> com.jfx.git.core.FileOpener.openFolder(folder.toFile()),
                        result -> {
                            if (!result.isOk()) {
                                customProblems.setText(result.getMessage() + " It is at " + folder + ".");
                                customProblems.setVisible(true);
                                customProblems.setManaged(true);
                            }
                        });
                reloadCustom.run();
            } catch (java.io.IOException failed) {
                customProblems.setText("The themes folder could not be created: " + failed.getMessage());
            }
        });
        Button reloadThemes = new Button("Reload");
        reloadThemes.setOnAction(e -> {
            var inEffect = ThemeManager.currentUserTheme();
            if (inEffect != null) {
                // Read again from disk: both what it sets, and what is wrong with it.
                var fresh = com.jfx.git.ui.UserThemes.named(inEffect.name());
                syncing[0] = true;
                if (fresh != null) {
                    ThemeManager.applyUser(fresh);
                } else {
                    ThemeManager.apply(ThemeManager.current());
                }
                syncing[0] = false;
            }
            reloadCustom.run();
        });
        // A row of their own: beside the picker they were cut to "Open themes fol..." and
        // "Relo..." in a dialog of the default width.
        openThemes.setMinWidth(Region.USE_PREF_SIZE);
        reloadThemes.setMinWidth(Region.USE_PREF_SIZE);
        HBox themeButtons = new HBox(8, openThemes, reloadThemes);
        reloadCustom.run();

        ChoiceBox<com.jfx.git.graph.GraphDensity> density = new ChoiceBox<>();
        density.getItems().setAll(com.jfx.git.graph.GraphDensity.values());
        density.setValue(AppPreferences.graphDensity());
        density.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                AppPreferences.setGraphDensity(selected);
            }
        });

        ChoiceBox<com.jfx.git.graph.GraphOrder> order = new ChoiceBox<>();
        order.getItems().setAll(com.jfx.git.graph.GraphOrder.values());
        order.setValue(AppPreferences.graphOrder());
        order.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                AppPreferences.setGraphOrder(selected);
            }
        });

        ChoiceBox<com.jfx.git.graph.GraphNodeStyle> nodeStyle = new ChoiceBox<>();
        nodeStyle.getItems().setAll(com.jfx.git.graph.GraphNodeStyle.values());
        nodeStyle.setValue(AppPreferences.graphNodeStyle());
        nodeStyle.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                AppPreferences.setGraphNodeStyle(selected);
            }
        });

        CheckBox branchTint = new CheckBox("Tint commit rows by branch");
        branchTint.setSelected(AppPreferences.graphBranchTint());
        branchTint.selectedProperty().addListener((obs, old, on) ->
                AppPreferences.setGraphBranchTint(on));

        CheckBox reduceMotion = new CheckBox("Reduce motion");
        reduceMotion.setId("reduce-motion");
        // What is in effect, which until the user chooses is the system's setting. Shown
        // before the listener below exists, so opening this page stores nothing.
        reduceMotion.setSelected(com.jfx.git.core.AppPreferences.reduceMotion());
        reduceMotion.selectedProperty().addListener((obs, old, on) ->
                com.jfx.git.core.AppPreferences.setReduceMotion(on));

        CheckBox rememberTabs = new CheckBox("Reopen last session's tabs at startup");
        rememberTabs.setSelected(com.jfx.git.core.TabSession.remember());
        rememberTabs.selectedProperty().addListener((obs, old, on) ->
                com.jfx.git.core.TabSession.setRemember(on));

        page.getChildren().addAll(
                section("Appearance"),
                row("Theme", theme),
                hint("Applies immediately, and is remembered for next time."),
                row("Custom theme", custom),
                themeButtons,
                hint("A stylesheet of your own in the themes folder, laid over the theme above. "
                        + "The folder opens with an example to copy."),
                customProblems,
                reduceMotion,
                hint("No fades or slides when notifications, files and popups appear. "
                        + "Follows your system's setting until you change it here."),
                section("Tabs"),
                rememberTabs,
                hint("Repositories that have moved or been deleted are skipped silently "
                        + "rather than reported on startup."),
                section("Commit graph"),
                row("Order", order),
                hint("Group by branch keeps each branch's commits together and the graph "
                        + "narrow; Chronological interleaves every branch by date, which "
                        + "is livelier to look at but holds far more lanes open at once."),
                row("Commit node", nodeStyle),
                hint("The author's avatar sits on the graph itself and needs no separate "
                        + "Author column; the dot keeps the graph narrower, which matters "
                        + "on a deeply branched history."),
                row("Density", density),
                hint("Compact fits more history on screen; Expressive widens the ref "
                        + "column and marks each row's branch down its leading edge."),
                branchTint,
                hint("A wash of the branch's own colour, so a run of commits on one "
                        + "branch reads as a band."),
                hint("Both take effect the next time a repository is opened or refreshed."));
        page.getChildren().addAll(datesSection());
        return page;
    }

    /**
     * Dates: relative or absolute, and the absolute format - for the graph, the file
     * history, the reflog, blame and every tooltip behind a relative age.
     *
     * <p>A custom pattern is stored only once it is one: a half-typed pattern would
     * otherwise be what every date on screen tried to format with.
     */
    private java.util.List<javafx.scene.Node> datesSection() {
        ChoiceBox<com.jfx.git.ui.DateDisplay.Mode> mode = new ChoiceBox<>();
        mode.setId("date-mode");
        mode.getItems().setAll(com.jfx.git.ui.DateDisplay.Mode.values());
        mode.setValue(com.jfx.git.ui.DateDisplay.mode());

        ChoiceBox<com.jfx.git.ui.DateDisplay.Format> format = new ChoiceBox<>();
        format.setId("date-format");
        format.getItems().setAll(com.jfx.git.ui.DateDisplay.Format.values());
        format.setValue(com.jfx.git.ui.DateDisplay.format());

        Label preview = new Label();
        preview.setId("date-preview");
        preview.getStyleClass().add("text-muted");
        preview.setMinWidth(Region.USE_PREF_SIZE);
        HBox formatControls = new HBox(10, format, preview);
        formatControls.setAlignment(Pos.CENTER_LEFT);

        TextField pattern = new TextField(com.jfx.git.ui.DateDisplay.customPattern());
        pattern.setId("date-pattern");
        pattern.setPromptText("dd.MM.yyyy HH:mm");
        pattern.setPrefColumnCount(18);
        Label problem = hint("");
        problem.setId("date-pattern-problem");
        problem.getStyleClass().add("status-error");
        Region patternRow = row("Pattern", pattern);

        Runnable showPreview = () -> preview.setText("Now: " + com.jfx.git.ui.DateDisplay.absolute(java.time.Instant.now()));
        Runnable showPattern = () -> {
            boolean custom = format.getValue() == com.jfx.git.ui.DateDisplay.Format.CUSTOM;
            patternRow.setVisible(custom);
            patternRow.setManaged(custom);
            String wrong = custom ? com.jfx.git.ui.DateDisplay.problemWith(pattern.getText()) : null;
            problem.setText(wrong == null ? "" : wrong);
            problem.setVisible(wrong != null);
            problem.setManaged(wrong != null);
        };

        mode.valueProperty().addListener((obs, old, selected) -> {
            if (selected == null) return;
            com.jfx.git.ui.DateDisplay.setMode(selected);
            onDiffSettingsChanged.run();
        });
        format.valueProperty().addListener((obs, old, selected) -> {
            if (selected == null) return;
            com.jfx.git.ui.DateDisplay.setFormat(selected);
            showPattern.run();
            showPreview.run();
            onDiffSettingsChanged.run();
        });
        pattern.textProperty().addListener((obs, old, text) -> {
            showPattern.run();
            // Refused, not stored: the last good pattern stays in use until this is one.
            if (com.jfx.git.ui.DateDisplay.setCustomPattern(text)) {
                showPreview.run();
                if (format.getValue() == com.jfx.git.ui.DateDisplay.Format.CUSTOM) {
                    onDiffSettingsChanged.run();
                }
            }
        });
        showPattern.run();
        showPreview.run();

        return java.util.List.of(
                section("Dates"),
                row("Show dates as", mode),
                hint("Relative reads as an age - 5m, 3d, 2 days ago - with the exact time in "
                        + "its tooltip. Absolute writes the time itself, in the format below."),
                row("Format", formatControls),
                patternRow,
                problem,
                hint("Used for absolute dates, and wherever an exact time is shown beside a "
                        + "relative one. A custom pattern uses letters such as yyyy, MM, dd, HH "
                        + "and mm."));
    }

    Region externalToolsPage() {
        VBox page = page("External Tools");

        ChoiceBox<AppPreferences.EditorMode> mode = new ChoiceBox<>();
        mode.getItems().setAll(AppPreferences.EditorMode.values());
        mode.setValue(AppPreferences.editorMode());

        TextField command = new TextField(AppPreferences.editorCommand());
        command.setPromptText("code " + AppPreferences.FILE_TOKEN);
        command.setPrefColumnCount(30);
        command.textProperty().addListener((obs, old, text) ->
                AppPreferences.setEditorCommand(text));

        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose an editor");
            File chosen = chooser.showOpenDialog(browse.getScene() == null ? null : browse.getScene().getWindow());
            if (chosen != null) {
                // Quote it: an installation path with spaces is the normal case.
                command.setText("\"" + chosen.getAbsolutePath() + "\" "
                        + AppPreferences.FILE_TOKEN);
            }
        });

        HBox commandRow = new HBox(8, command, browse);
        commandRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(command, Priority.ALWAYS);

        // The same grid every other row uses, so this one lines up with them rather than
        // being a hand-built HBox that happens to look similar.
        Region commandLine = row("Command", commandRow);

        Label commandHint = hint(AppPreferences.FILE_TOKEN
                + " is replaced with the file's path. If you leave it out, "
                + "the path is added at the end.");

        // The command line is only meaningful for a custom editor.
        Runnable sync = () -> {
            boolean custom = mode.getValue() == AppPreferences.EditorMode.CUSTOM;
            commandLine.setVisible(custom);
            commandLine.setManaged(custom);
            commandHint.setVisible(custom);
            commandHint.setManaged(custom);
        };
        mode.valueProperty().addListener((obs, old, selected) -> {
            AppPreferences.setEditorMode(selected);
            sync.run();
        });
        sync.run();

        // --- Diff and merge tools: the same shape twice, a choice and a command for it.
        ChoiceBox<com.jfx.git.core.ExternalTools.Mode> diffMode = toolModeChoice(
                AppPreferences.diffToolMode(), AppPreferences::setDiffToolMode);
        TextField diffCommand = toolCommandField(AppPreferences.diffToolCommand(),
                "meld " + com.jfx.git.core.ExternalTools.LOCAL + " " + com.jfx.git.core.ExternalTools.REMOTE,
                AppPreferences::setDiffToolCommand);
        diffCommand.setId("diff-tool-command");
        Region diffCommandLine = row("Command", diffCommand);
        Label diffCommandHint = hint(com.jfx.git.core.ExternalTools.LOCAL + " is the older version and "
                + com.jfx.git.core.ExternalTools.REMOTE + " the newer. Leave them out and both paths "
                + "are added at the end.");
        showOnlyForCustom(diffMode, diffCommandLine, diffCommandHint);

        ChoiceBox<com.jfx.git.core.ExternalTools.Mode> mergeMode = toolModeChoice(
                AppPreferences.mergeToolMode(), AppPreferences::setMergeToolMode);
        TextField mergeCommand = toolCommandField(AppPreferences.mergeToolCommand(),
                "kdiff3 " + com.jfx.git.core.ExternalTools.BASE + " " + com.jfx.git.core.ExternalTools.LOCAL
                        + " " + com.jfx.git.core.ExternalTools.REMOTE + " -o " + com.jfx.git.core.ExternalTools.MERGED,
                AppPreferences::setMergeToolCommand);
        mergeCommand.setId("merge-tool-command");
        Region mergeCommandLine = row("Command", mergeCommand);
        Label mergeCommandHint = hint(com.jfx.git.core.ExternalTools.LOCAL + " is your side, "
                + com.jfx.git.core.ExternalTools.REMOTE + " the side being merged in, "
                + com.jfx.git.core.ExternalTools.BASE + " their common ancestor, and "
                + com.jfx.git.core.ExternalTools.MERGED + " the file the tool writes its result to.");
        showOnlyForCustom(mergeMode, mergeCommandLine, mergeCommandHint);

        TextField terminalCommand = toolCommandField(AppPreferences.terminalCommand(),
                "wt -d " + com.jfx.git.core.TerminalLauncher.DIR_TOKEN, AppPreferences::setTerminalCommand);
        terminalCommand.setId("terminal-command");
        String detected = com.jfx.git.core.TerminalLauncher.describeDetected();
        String terminalHint = "Leave blank to use "
                + (detected.isBlank() ? "the first terminal found on this machine" : detected)
                + ". " + com.jfx.git.core.TerminalLauncher.DIR_TOKEN
                + " is replaced with the folder to open in.";

        CheckBox drafts = new CheckBox("Keep a recoverable copy of unsaved edits");
        drafts.setSelected(AppPreferences.editorDraftsEnabled());
        drafts.selectedProperty().addListener((obs, old, on) -> {
            AppPreferences.setEditorDraftsEnabled(on);
            if (!on) {
                offerToPurgeDrafts();
            }
        });

        Button openDrafts = new Button("Open drafts folder");
        // openFolder, on a worker: this used to call the file opener with a directory,
        // which refused it, and the button did nothing at all.
        openDrafts.setOnAction(e -> GitTaskRunner.runDetached(
                () -> com.jfx.git.core.FileOpener.openFolder(com.jfx.git.core.EditorDrafts.directory().toFile()),
                result -> { }));

        page.getChildren().addAll(
                section("Editor"),
                row("Edit files with", mode),
                hint("\"Edit this file\" in the diff viewer uses this. "
                        + "The built-in editor types straight into the file view "
                        + "and saves to the working tree."),
                commandLine,
                commandHint,
                section("Diff tool"),
                row("Compare files with", diffMode),
                hint("Adds a button to the diff header that opens the same comparison in "
                        + "this tool. The built-in diff is unaffected."),
                diffCommandLine,
                diffCommandHint,
                section("Merge tool"),
                row("Resolve conflicts with", mergeMode),
                hint("Adds \"Resolve in external merge tool\" to a conflicted file's menu. "
                        + "The built-in resolver stays on the Resolve button."),
                mergeCommandLine,
                mergeCommandHint,
                section("Terminal"),
                row("Command", terminalCommand),
                hint(terminalHint),
                section("Unsaved work"),
                drafts,
                // Said plainly, and with the location in it: this writes the contents of
                // source files outside the repository, and someone working on a private
                // one should be able to see where before deciding.
                hint("While the built-in editor holds unsaved changes, a copy is kept in "
                        + com.jfx.git.core.EditorDrafts.directory()
                        + " so a crash does not lose it. The copy is deleted as soon as "
                        + "the file is saved."),
                openDrafts);
        return page;
    }

    /** The custom-theme choice that means "the built-in theme as it is". */
    static final String NO_CUSTOM_THEME = "None";

    /** Says what is wrong with a user theme under its picker, or nothing when nothing is. */
    private static void describeProblems(Label label, com.jfx.git.ui.UserThemes.UserTheme theme) {
        boolean any = theme != null && !theme.problems().isEmpty();
        label.setText(any ? String.join("\n", theme.problems()) : "");
        label.setVisible(any);
        label.setManaged(any);
    }

    private static ChoiceBox<com.jfx.git.core.ExternalTools.Mode> toolModeChoice(
            com.jfx.git.core.ExternalTools.Mode current,
            java.util.function.Consumer<com.jfx.git.core.ExternalTools.Mode> store) {
        ChoiceBox<com.jfx.git.core.ExternalTools.Mode> choice = new ChoiceBox<>();
        choice.getItems().setAll(com.jfx.git.core.ExternalTools.Mode.values());
        choice.setValue(current);
        choice.valueProperty().addListener((obs, old, selected) -> store.accept(selected));
        return choice;
    }

    private static TextField toolCommandField(String current, String example,
                                              java.util.function.Consumer<String> store) {
        TextField field = new TextField(current);
        field.setPromptText(example);
        field.setPrefColumnCount(30);
        field.textProperty().addListener((obs, old, text) -> store.accept(text));
        return field;
    }

    /** A command line means something only for a custom tool; the other modes need none. */
    private static void showOnlyForCustom(ChoiceBox<com.jfx.git.core.ExternalTools.Mode> mode, Region... rows) {
        Runnable sync = () -> {
            boolean custom = mode.getValue() == com.jfx.git.core.ExternalTools.Mode.CUSTOM;
            for (Region row : rows) {
                row.setVisible(custom);
                row.setManaged(custom);
            }
        };
        mode.valueProperty().addListener((obs, old, selected) -> sync.run());
        sync.run();
    }

    /**
     * Offers to remove what is already stored when drafting is turned off.
     *
     * <p>Switching it off is usually about not wanting the contents of these files on disk
     * at all, and leaving yesterday's copies behind would honour the letter of that and
     * not the point of it.
     */
    private void offerToPurgeDrafts() {
        // Counted the way discardAll deletes: a .tmp left by a crash is a copy of the
        // user's text as much as a finished draft, and was never offered for deletion.
        int existing = com.jfx.git.core.EditorDrafts.storedFileCount();
        if (existing == 0) {
            return;
        }
        // Off the disk for good - no snapshot, no Undo - so Enter keeps them.
        if (com.jfx.git.ui.dialogs.Dialogs.destructive("Delete drafts")
                .title(existing == 1 ? "Delete the stored draft as well?"
                        : "Delete the " + existing + " stored drafts as well?")
                .subtitle("Drafting is now off")
                .message((existing == 1 ? "It is a recoverable copy" : "They are recoverable copies")
                        + " of unsaved edits, kept from before. Deleting cannot be undone.")
                .action(existing == 1 ? "Delete draft" : "Delete drafts")
                .safeDefault()
                .ask()) {
            com.jfx.git.core.EditorDrafts.discardAll();
        }
    }

    Region diffPage() {
        VBox page = page("Diff");

        CheckBox split = new CheckBox("Open diffs side by side");
        split.setSelected(AppPreferences.splitDiffByDefault());
        split.selectedProperty().addListener((obs, old, on) -> {
            AppPreferences.setSplitDiffByDefault(on);
            onDiffSettingsChanged.run();
        });

        CheckBox wholeFile = new CheckBox("Show the whole file by default");
        wholeFile.setSelected(AppPreferences.wholeFileByDefault());
        wholeFile.selectedProperty().addListener((obs, old, on) -> {
            AppPreferences.setWholeFileByDefault(on);
            onDiffSettingsChanged.run();
        });

        TextField scrollMapWidth = new TextField(Integer.toString(AppPreferences.diffScrollMapWidth()));
        scrollMapWidth.setPrefColumnCount(4);
        // Digits only, mirroring the General page's initialCommits field: anything else
        // cannot become a pixel width and would only leave the setter parsing garbage.
        scrollMapWidth.textProperty().addListener((obs, old, text) -> {
            if (text != null && !text.matches("\\d*")) {
                scrollMapWidth.setText(old);
                return;
            }
            try {
                AppPreferences.setDiffScrollMapWidth(Integer.parseInt(text));
                onDiffSettingsChanged.run();
            } catch (NumberFormatException ignored) {
                // Blank or mid-edit; the stored value is left as it was until this parses.
            }
        });

        CheckBox scrollBeyond = new CheckBox("Scroll past the last line");
        scrollBeyond.setSelected(AppPreferences.scrollBeyondLastLine());
        scrollBeyond.selectedProperty().addListener((obs, old, on) -> {
            AppPreferences.setScrollBeyondLastLine(on);
            onDiffSettingsChanged.run();
        });

        CheckBox minimap = new CheckBox("Show a minimap when viewing a whole file");
        minimap.setSelected(AppPreferences.editorMinimap());
        minimap.selectedProperty().addListener((obs, old, on) -> {
            AppPreferences.setEditorMinimap(on);
            onDiffSettingsChanged.run();
        });

        CheckBox colourblind = new CheckBox("Colourblind-safe palette (blue and amber)");
        colourblind.setSelected(AppPreferences.colourblindPalette());
        colourblind.selectedProperty().addListener((obs, old, on) -> {
            AppPreferences.setColourblindPalette(on);
            onDiffSettingsChanged.run();
        });

        CheckBox confirmDiscard = new CheckBox("Ask before discarding a file's changes");
        confirmDiscard.setSelected(AppPreferences.confirmDiscard());
        confirmDiscard.selectedProperty().addListener((obs, old, on) ->
                AppPreferences.setConfirmDiscard(on));

        page.getChildren().addAll(
                section("Layout"), split, wholeFile,
                row("Diff scrollbar width", scrollMapWidth),
                hint("How wide the diff's scrollbar and its change markers are, in pixels."),
                section("Code view"), scrollBeyond, minimap,
                section("Colour"), colourblind,
                section("Safety"), confirmDiscard,
                hint("A file, a hunk or some lines. A snapshot is taken first, and the notification"
                        + " that follows offers Undo. Discard all changes always asks."));
        return page;
    }

    Region sshPage() {
        VBox page = page("SSH");

        Label status = new Label(SshSessionManager.status());
        status.setWrapText(true);

        Label agent = new Label(SshSessionManager.hasAgent()
                ? "An ssh-agent is running; keys it holds are used without prompting."
                : "No ssh-agent was found. Passphrase-protected keys will prompt each session.");
        agent.setWrapText(true);

        // Commercial clients' General page has this; ours lives beside the SSH status it
        // relates to. Only the in-memory cache is cleared - nothing was persisted.
        Button forget = new Button("Forget all credentials");
        forget.setOnAction(e -> {
            com.jfx.git.core.CredentialsManager.getInstance().clear();
            notices.accept("Cached credentials forgotten: the next fetch or push will prompt again");
        });

        // The public half is shown in the dialog once the key exists, ready to copy.
        Button generateKey = new Button("Generate key…");
        generateKey.setOnAction(e -> new com.jfx.git.ui.dialogs.GenerateSshKeyDialog("id_ed25519", "").showAndWait());

        page.getChildren().addAll(
                section("Status"), status, agent,
                section("Keys"), generateKey,
                hint("A key for one identity only - a work account and a personal one on the "
                        + "same host - is chosen in that profile, under Profiles."),
                section("Credentials"), forget,
                hint("Logins are asked of your git credential helper first (Git Credential"
                        + " Manager, osxkeychain, libsecret), and one you type here is handed"
                        + " to it once it has worked - so a helper that stores keeps it. This"
                        + " application itself only caches them for the session."),
                hint("Keys are read from ~/.ssh, and ~/.ssh/config is honoured - "
                        + "including IdentityFile, User, Port and ProxyJump."));
        return page;
    }

    Region gitEnginePage() {
        VBox page = page("Git Engine");

        ChoiceBox<GitBackendFactory.BackendEngine> engineChoice = new ChoiceBox<>();
        // Only offer engines that exist on this machine. Listing an unavailable one and
        // then quietly refusing the selection leaves the control showing a choice that
        // was never saved.
        for (GitBackendFactory.BackendEngine engine : GitBackendFactory.BackendEngine.values()) {
            if (GitBackendFactory.isEngineAvailable(engine)) {
                engineChoice.getItems().add(engine);
            }
        }
        engineChoice.setValue(GitBackendFactory.getEnginePreference());
        engineChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(GitBackendFactory.BackendEngine value) {
                return value == null ? "" : value.label();
            }

            @Override
            public GitBackendFactory.BackendEngine fromString(String text) {
                for (GitBackendFactory.BackendEngine engine : GitBackendFactory.BackendEngine.values()) {
                    if (engine.label().equals(text)) return engine;
                }
                return GitBackendFactory.getEnginePreference();
            }
        });

        Label statusInfo = new Label();
        statusInfo.getStyleClass().add("status-info");
        statusInfo.setWrapText(true);
        updateEngineInfo(statusInfo);

        engineChoice.valueProperty().addListener((obs, old, selected) -> {
            if (selected == null || selected == old) return;
            // Rebuilds every open tab's backend, not just the stored preference.
            onEngineChanged.accept(selected);
            updateEngineInfo(statusInfo);
        });

        page.getChildren().addAll(
                section("Backend engine"),
                row("Active engine", engineChoice),
                hint("Auto picks the fastest engine present: bundled libgit2, then native git, then JGit. "
                        + "Changing this rebuilds the engine for every open repository straight away."),
                section("What each engine does"),
                hint("""
                     Bundled libgit2 runs status, staging and branch reads in-process through the \
                     Java FFM API, and delegates history, diffs and network transport.
                     Native git shells out to the git on your PATH for everything.
                     JGit is pure Java and needs no native git for anything except creating
                     and removing worktrees, which every engine shells out to git for."""),
                section("Availability on this machine"),
                statusInfo,
                section("Engine doctor"),
                hint("Runs the same checks against every engine on this machine and reports "
                        + "where they disagree. Uses a throwaway repository of its own - your "
                        + "own repositories are not touched."),
                doctorButton());
        return page;
    }

    /**
     * Runs {@link EngineDoctor} and shows what it found.
     *
     * <p>Here rather than anywhere else because this is the page where the user is being
     * asked to trust that the choice above does not matter. Being able to check that on
     * their own machine, against their own git, is the difference between a claim and a
     * fact.
     */
    private javafx.scene.Node doctorButton() {
        Button run = new Button("Run engine doctor");
        Label result = new Label();
        result.getStyleClass().add("text-muted");
        result.setWrapText(true);

        run.setOnAction(e -> {
            run.setDisable(true);
            result.setText("Running…");
            GitTaskRunner.queryDetached(EngineDoctor::run, report -> {
                run.setDisable(false);
                result.setText(report.summary());
                showDoctorReport(report);
            }, error -> {
                run.setDisable(false);
                result.setText("Could not run: " + error);
            });
        });

        VBox box = new VBox(8, run, result);
        return box;
    }

    /**
     * What the doctor found. Where the engines disagree, each disagreement is in the body,
     * one engine's answer to a line so they can be compared; the full table - every
     * check, disagreements first, in columns - is behind Show Details.
     */
    private void showDoctorReport(EngineDoctor.Report report) {
        List<EngineDoctor.Check> wrong = report.disagreements();
        String engines = String.join(" · ", report.engines());
        com.jfx.git.ui.dialogs.Dialogs.Message shown;
        if (!report.isOk()) {
            shown = com.jfx.git.ui.dialogs.Dialogs.error("Engine doctor")
                    .title("The engine doctor could not finish")
                    .message(report.summary());
        } else if (report.engines().size() < 2) {
            shown = com.jfx.git.ui.dialogs.Dialogs.info("Engine doctor")
                    .title("Nothing to compare")
                    .message(report.summary());
        } else if (wrong.isEmpty()) {
            shown = com.jfx.git.ui.dialogs.Dialogs.info("Engine doctor")
                    .icon(com.jfx.git.ui.Icons.check())
                    .tone(com.jfx.git.ui.dialogs.JfxDialog.Tone.SUCCESS)
                    .title("All " + report.engines().size() + " engines agree on all "
                            + report.checks().size() + " checks")
                    .message("Each engine answered the same questions about a throwaway repository,"
                            + " and the answers matched.");
        } else {
            List<String> rows = new ArrayList<>();
            int width = report.engines().stream().mapToInt(String::length).max().orElse(0);
            for (EngineDoctor.Check check : wrong) {
                rows.add(check.question());
                check.answers().forEach((engine, answer) ->
                        rows.add("  " + pad(engine, width) + "  " + answer));
            }
            shown = com.jfx.git.ui.dialogs.Dialogs.warn("Engine doctor")
                    .title("Engines disagree on " + wrong.size() + " of " + report.checks().size()
                            + (report.checks().size() == 1 ? " check" : " checks"))
                    .message("Each engine answered the same questions about a throwaway repository."
                            + " Where they differ, the engine chosen under Git Engine changes what you"
                            + " see.")
                    .affected(wrong.size() == 1 ? "THE DISAGREEMENT" : "THE DISAGREEMENTS", rows)
                    .buttons(ButtonType.CLOSE);
        }
        if (!report.checks().isEmpty()) {
            shown.details(doctorTable(report));
        }
        shown.subtitle(report.engines().isEmpty() ? null : engines)
                .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD)
                .answer();
    }

    /** Every check in columns, the disagreements first: what Show Details holds, and what a bug report wants. */
    static String doctorTable(EngineDoctor.Report report) {
        List<EngineDoctor.Check> ordered = new ArrayList<>(report.disagreements());
        report.checks().stream().filter(EngineDoctor.Check::agrees).forEach(ordered::add);
        int question = ordered.stream().mapToInt(c -> c.question().length()).max().orElse(0);
        int engine = report.engines().stream().mapToInt(String::length).max().orElse(0);
        StringBuilder table = new StringBuilder();
        for (EngineDoctor.Check check : ordered) {
            if (check.agrees()) {
                table.append("OK    ").append(pad(check.question(), question)).append("  ")
                        .append(check.describe()).append('\n');
            } else {
                table.append("DIFF  ").append(check.question()).append('\n');
                check.answers().forEach((name, answer) -> table.append("        ")
                        .append(pad(name, engine)).append("  ").append(answer).append('\n'));
            }
        }
        return table.toString().stripTrailing();
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private void updateEngineInfo(Label label) {
        StringBuilder sb = new StringBuilder();

        sb.append("• Bundled libgit2 (Java FFM): ");
        if (Libgit2Loader.isAvailable()) {
            sb.append("available");
        } else {
            sb.append("unavailable — ").append(Libgit2Loader.unavailableReason());
        }

        sb.append("\n• Native git CLI: ")
                .append(NativeGitRunner.isGitInstalled()
                        ? "available (" + NativeGitRunner.getGitVersion() + ")"
                        : "unavailable — " + java.util.Objects.requireNonNullElse(
                                NativeGitRunner.gitUnavailableReason(), "not found on PATH"));

        sb.append("\n• Pure Java (JGit): always available");

        sb.append("\n• Git LFS: ");
        if (!NativeGitRunner.isGitLfsInstalled()) {
            sb.append("not found on PATH");
        } else if (!NativeGitRunner.isGitLfsFilterConfiguredGlobally()) {
            sb.append("installed (").append(NativeGitRunner.getGitLfsVersion())
                    .append("), but not initialised — run \"git lfs install\" to enable"
                            + " automatic smudging on checkout");
        } else {
            sb.append("available (").append(NativeGitRunner.getGitLfsVersion()).append(")");
        }
        // Only the Native engine ever sees this: JGit has no smudge filter pipeline
        // wired into this codebase, so an LFS-tracked checkout stays a pointer file
        // there regardless of what is installed on the machine.
        sb.append(" — Native engine only; JGit cannot materialise LFS content");

        label.setText(sb.toString());
    }
}
