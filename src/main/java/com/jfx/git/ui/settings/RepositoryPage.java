package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitBackendFactory;
import com.jfx.git.core.GitFlow;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.core.RepoStructureOps;
import com.jfx.git.core.RepositoryPreferences;
import com.jfx.git.core.RepositoryTuning;
import com.jfx.git.core.TextEncoding;
import com.jfx.git.model.PullMode;
import com.jfx.git.model.RefInfo;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static com.jfx.git.ui.settings.SettingsLayout.hint;
import static com.jfx.git.ui.settings.SettingsLayout.page;
import static com.jfx.git.ui.settings.SettingsLayout.row;
import static com.jfx.git.ui.settings.SettingsLayout.section;

/**
 * One open repository's own settings, as the last section of the Settings tab.
 *
 * <h2>Why here</h2>
 * They were two screens in the repository's tab - "Repository settings" for the three
 * preferences a repository may override, "Hooks &amp; housekeeping" for the rest - and a
 * handful of things that lived only in git's config with no screen at all: the commit
 * template, the hooks directory, Git Flow's branch names. Beside the application's own
 * settings they are found where settings are looked for, and the global value a
 * repository is overriding is one section away.
 *
 * <h2>Which repository</h2>
 * A picker over the repositories open in the window, starting on the one the user was
 * last working in. The page is built once, like every Settings page, so it follows the
 * picker itself: choosing another repository rebuilds the parts below it, and coming back
 * to the Settings tab from a repository moves it to that repository.
 *
 * <p>Built on a bare {@link SettingsTab} - no application, no repository - it says so and
 * shows nothing else.
 *
 * <h2>Threads</h2>
 * Everything that reads or writes the repository - its config, its hooks, its branches -
 * runs on the repository lane through {@link GitTaskRunner}. The preferences this
 * application keeps about the repository are in memory and are written as they change.
 */
public final class RepositoryPage {

    public static final String PICKER_ID = "repo-settings-picker";
    public static final String EMPTY_ID = "repo-settings-empty";
    public static final String PULL_ID = "repo-settings-pull";
    public static final String RESET_ID = "repo-settings-reset";
    public static final String TEMPLATE_APPLY_ID = "repo-template-apply";
    public static final String TEMPLATE_SUMMARY_ID = "repo-template-summary";
    public static final String TEMPLATE_DESCRIPTION_ID = "repo-template-description";
    public static final String TEMPLATE_SAVE_ID = "repo-template-save";
    public static final String TEMPLATE_STATUS_ID = "repo-template-status";
    public static final String HOOKS_PATH_ID = "repo-hooks-path";
    public static final String HOOKS_SAVE_ID = "repo-hooks-save";
    public static final String HOOKS_STATUS_ID = "repo-hooks-status";
    public static final String HOOKS_ID = "repo-hooks";
    public static final String SKIP_HOOKS_ID = "repo-skip-hooks";
    public static final String FLOW_PRODUCTION_ID = "repo-flow-production";
    public static final String FLOW_DEVELOP_ID = "repo-flow-develop";
    public static final String FLOW_FEATURE_ID = "repo-flow-feature";
    public static final String FLOW_RELEASE_ID = "repo-flow-release";
    public static final String FLOW_HOTFIX_ID = "repo-flow-hotfix";
    public static final String FLOW_VERSION_TAG_ID = "repo-flow-version-tag";
    public static final String FLOW_SAVE_ID = "repo-flow-save";
    public static final String FLOW_STATUS_ID = "repo-flow-status";
    public static final String ENCODING_ID = "repo-encoding";
    public static final String LFS_STATUS_ID = "repo-lfs-status";
    public static final String OPEN_LFS_ID = "repo-open-lfs";
    /** Where the conflict-prevention settings go; see {@link #conflictPrevention}. */
    public static final String CONFLICT_PREVENTION_ID = "repo-conflict-prevention";
    public static final String HOUSEKEEPING_ID = "repo-housekeeping";

    /** The local config key git reads the commit template's path from. */
    static final String TEMPLATE_KEY = "commit.template";
    /** The file a template is written to when the repository does not name one. */
    static final String TEMPLATE_FILE = "lyra-commit-template.txt";
    static final String HOOKS_PATH_KEY = "core.hooksPath";

    /** Offered intervals, mirroring the ones the General page offers. */
    private static final List<Integer> INTERVALS = List.of(0, 1, 5, 15, 60);

    /** Stands in for "no override" in the choices that have one. */
    private static final Object FOLLOW_GLOBAL = new Object() {
        @Override
        public String toString() {
            return "Follow the global setting";
        }
    };

    /**
     * A repository this page can show.
     *
     * @param owner     what the repository is open in - its tab - so the same folder
     *                  opened again in a new tab is told from the one it replaced
     * @param directory its working tree
     * @param git       its backend - the one its tab hands out; every read and write goes
     *                  through it, on that repository's lane
     * @param onChanged run after a change the repository's tab should act on - the
     *                  auto-fetch interval, a new template for the composer
     * @param openLfs   opens Git LFS in the repository's tab
     */
    public record Target(Object owner, File directory, GitBackend git, Runnable onChanged, Runnable openLfs) {

        /** A repository with no tab behind it; for tests. */
        public static Target of(GitBackend git) {
            return new Target(git, git.getWorkTree(), git, () -> { }, () -> { });
        }

        String name() {
            return directory == null ? "" : directory.getName();
        }
    }

    /** The application's open repositories, as this page sees them. */
    public interface Repositories {

        /** The repositories open now, in the order of the tab strip. */
        List<Target> open();

        /** The repository the user was last working in, or {@code null}. */
        File current();
    }

    private final Repositories repositories;
    private final VBox root;
    private final ComboBox<Target> picker = new ComboBox<>();
    private final Label where = hint("");
    private final Region pickerRow;
    private final Label empty = hint("Open a repository to change its settings.");
    private final VBox parts = new VBox(10);

    /** The repository the parts below the picker were built for. */
    private Target shown;
    /** True while the picker is refilled from code, so its listener does not rebuild. */
    private boolean syncing;

    /** Put each preference control back to "follow the global setting"; see {@link #refreshing}. */
    private final List<Runnable> refreshers = new ArrayList<>();
    /**
     * True while {@link #refreshers} run. JavaFX fires value listeners on programmatic
     * changes too, so resetting the controls would otherwise write each setting again
     * just after {@code clear()} removed it.
     */
    private boolean refreshing;
    private Runnable updateReset = () -> { };

    /** @param repositories the open repositories, or {@code null} for a page with none */
    RepositoryPage(Repositories repositories) {
        this.repositories = repositories;
        root = page("Repository");

        picker.setId(PICKER_ID);
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(Target target) {
                return target == null ? "" : target.name();
            }

            @Override
            public Target fromString(String text) {
                return null;
            }
        });
        picker.valueProperty().addListener((obs, old, picked) -> {
            if (!syncing) show(picked);
        });
        // A repository opened or closed since the list was read is there, or gone, when
        // the list is opened - without the page having to follow the tab strip.
        picker.setOnShowing(e -> refresh(null));
        pickerRow = row("Repository", picker);
        where.setId("repo-settings-where");
        empty.setId(EMPTY_ID);

        refresh(null);
    }

    /** The page's root, as the Settings tab shows it. */
    Region region() {
        return root;
    }

    /**
     * Moves the page to {@code repository}, when it is open; reads the open repositories
     * again either way. {@code null} keeps the repository shown if it is still open.
     */
    void select(File repository) {
        refresh(repository);
    }

    /** Moves the page to the repository the user was last working in. */
    void followCurrent() {
        refresh(repositories == null ? null : repositories.current());
    }

    /** The repository shown, or {@code null} on the empty page; for tests. */
    File shownRepository() {
        return shown == null ? null : shown.directory();
    }

    /** Drops what the page holds of the repository it was showing; the tab is closing. */
    void dispose() {
        shown = null;
        refreshers.clear();
        updateReset = () -> { };
        parts.getChildren().clear();
        syncing = true;
        picker.getItems().clear();
        syncing = false;
    }

    private void refresh(File prefer) {
        List<Target> open = repositories == null ? List.of() : List.copyOf(repositories.open());
        Target pick = prefer == null ? null : find(open, prefer);
        if (pick == null && shown != null) {
            pick = open.stream().filter(t -> t.owner() == shown.owner()).findFirst().orElse(null);
        }
        if (pick == null && repositories != null) pick = find(open, repositories.current());
        if (pick == null && !open.isEmpty()) pick = open.get(0);

        syncing = true;
        try {
            picker.getItems().setAll(open);
            picker.setValue(pick);
        } finally {
            syncing = false;
        }
        show(pick);
    }

    private static Target find(List<Target> open, File directory) {
        if (directory == null) return null;
        for (Target target : open) {
            if (sameFile(target.directory(), directory)) return target;
        }
        return null;
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    /** Builds the parts for {@code target}, unless they already show it. */
    private void show(Target target) {
        if (target == null) {
            shown = null;
            refreshers.clear();
            parts.getChildren().clear();
            root.getChildren().setAll(root.getChildren().get(0), empty);
            return;
        }
        if (shown != null && shown.owner() == target.owner() && sameFile(shown.directory(), target.directory())) {
            shown = target;
            return;
        }
        shown = target;
        where.setText(target.directory() == null ? "" : target.directory().getAbsolutePath());
        refreshers.clear();
        parts.getChildren().setAll(
                general(target),
                commitTemplate(target),
                hooks(target),
                gitFlow(target),
                encoding(target),
                lfs(target),
                conflictPrevention(target),
                housekeeping(target));
        root.getChildren().setAll(root.getChildren().get(0), pickerRow, where, parts);
    }

    private static VBox part(Node... children) {
        return new VBox(10, children);
    }

    // ------------------------------------------------------------------
    // General: the preferences a repository may override
    // ------------------------------------------------------------------

    /**
     * Auto-fetch, engine, pull and performance - what was the repository's own settings
     * screen. Each is "follow the global setting" or one value, written the moment it
     * changes; see {@link RepositoryPreferences}.
     */
    private VBox general(Target target) {
        File repository = target.directory();
        Button reset = new Button("Use the global settings for everything");
        reset.setId(RESET_ID);
        reset.setMinWidth(Region.USE_PREF_SIZE);
        updateReset = () -> reset.setDisable(!RepositoryPreferences.hasOverrides(repository));
        updateReset.run();
        reset.setOnAction(e -> {
            RepositoryPreferences.clear(repository);
            target.onChanged().run();
            // Put back in place rather than rebuilt: the change is visible in the controls
            // in front of the user, and a rebuild would read the repository all over again.
            refreshing = true;
            try {
                refreshers.forEach(Runnable::run);
            } finally {
                refreshing = false;
            }
            updateReset.run();
        });

        Label engineNote = hint("");
        return part(
                section("General"),
                row("Fetch every", autoFetchChoice(target)),
                hint("How often this repository fetches in the background. Useful when one "
                        + "repository is behind a slow remote, or when only the one you are "
                        + "working in should be checked."),
                row("Engine", engineChoice(target, engineNote)),
                engineNote,
                hint("Which engine opens this repository. A repository whose config only the "
                        + "real git honours - LFS, custom filters, credential helpers - can "
                        + "ask for the native engine while everything else uses the fastest "
                        + "available."),
                row("Pull with", pullChoice(target)),
                hint("What the Pull button does when clicked rather than opened."),
                performance(target),
                reset);
    }

    private ChoiceBox<Object> autoFetchChoice(Target target) {
        File repository = target.directory();
        ChoiceBox<Object> choice = new ChoiceBox<>();
        List<Object> options = new ArrayList<>();
        options.add(FOLLOW_GLOBAL);
        options.addAll(INTERVALS);
        choice.getItems().setAll(options);
        choice.setConverter(converter(value -> value == FOLLOW_GLOBAL
                ? FOLLOW_GLOBAL + " (" + describeInterval(AppPreferences.autoFetchIntervalMinutes()) + ")"
                : describeInterval((Integer) value)));
        choice.setValue(RepositoryPreferences.autoFetchIntervalMinutes(repository)
                .map(Object.class::cast).orElse(FOLLOW_GLOBAL));
        choice.valueProperty().addListener((o, old, value) -> {
            if (refreshing || value == null) return;
            RepositoryPreferences.setAutoFetchIntervalMinutes(repository,
                    value == FOLLOW_GLOBAL ? null : (Integer) value);
            updateReset.run();
            target.onChanged().run();
        });
        refreshers.add(() -> choice.setValue(FOLLOW_GLOBAL));
        return choice;
    }

    private static String describeInterval(int minutes) {
        if (minutes <= 0) return "never";
        if (minutes == 1) return "every minute";
        if (minutes == 60) return "every hour";
        return "every " + minutes + " minutes";
    }

    private ChoiceBox<Object> engineChoice(Target target, Label note) {
        File repository = target.directory();
        ChoiceBox<Object> choice = new ChoiceBox<>();
        List<Object> options = new ArrayList<>();
        options.add(FOLLOW_GLOBAL);
        for (var engine : GitBackendFactory.BackendEngine.values()) {
            // AUTO is a way of choosing an engine, not an engine; as an override it would
            // never match the engine a repository opened with. See RepositoryPreferences.engine.
            if (engine == GitBackendFactory.BackendEngine.AUTO) continue;
            if (GitBackendFactory.isEngineAvailable(engine)) options.add(engine);
        }
        choice.getItems().setAll(options);
        choice.setConverter(converter(value -> value == FOLLOW_GLOBAL
                ? FOLLOW_GLOBAL + " (" + GitBackendFactory.getEnginePreference().label() + ")"
                : ((GitBackendFactory.BackendEngine) value).label()));
        choice.setValue(RepositoryPreferences.engine(repository)
                .map(Object.class::cast).orElse(FOLLOW_GLOBAL));
        choice.valueProperty().addListener((o, old, value) -> {
            if (refreshing || value == null) return;
            RepositoryPreferences.setEngine(repository,
                    value == FOLLOW_GLOBAL ? null : (GitBackendFactory.BackendEngine) value);
            // Not switched underneath the open tab: the swap is a real operation with its
            // own failure mode, and taking effect at the next open is predictable.
            note.setText("Takes effect the next time this repository is opened.");
            updateReset.run();
            target.onChanged().run();
        });
        refreshers.add(() -> {
            choice.setValue(FOLLOW_GLOBAL);
            note.setText("");
        });
        return choice;
    }

    private ChoiceBox<Object> pullChoice(Target target) {
        File repository = target.directory();
        ChoiceBox<Object> choice = new ChoiceBox<>();
        choice.setId(PULL_ID);
        List<Object> options = new ArrayList<>();
        options.add(FOLLOW_GLOBAL);
        options.addAll(List.of(PullMode.values()));
        choice.getItems().setAll(options);
        choice.setConverter(converter(value -> value == FOLLOW_GLOBAL
                ? FOLLOW_GLOBAL + " (" + describePull(AppPreferences.defaultPullMode()) + ")"
                : describePull((PullMode) value)));
        choice.setValue(RepositoryPreferences.pullMode(repository)
                .map(Object.class::cast).orElse(FOLLOW_GLOBAL));
        choice.valueProperty().addListener((o, old, value) -> {
            if (refreshing || value == null) return;
            RepositoryPreferences.setPullMode(repository, value == FOLLOW_GLOBAL ? null : (PullMode) value);
            updateReset.run();
            target.onChanged().run();
        });
        refreshers.add(() -> choice.setValue(FOLLOW_GLOBAL));
        return choice;
    }

    private static String describePull(PullMode mode) {
        return switch (mode) {
            case MERGE -> "Merge";
            case FF_ONLY -> "Fast-forward only";
            case REBASE -> "Rebase";
        };
    }

    /**
     * The opt-in that speeds up git in this repository; see {@link RepositoryTuning}.
     *
     * <p>The repository's own git config rather than a preference of this application's,
     * so "Use the global settings" leaves it alone. Reading and changing it run git, on the
     * repository lane; the box stays disabled until the current state is known.
     */
    private VBox performance(Target target) {
        File repository = target.directory();
        CheckBox box = new CheckBox("Speed up this repository");
        box.setDisable(true);
        Label status = hint("Checking…");
        boolean[] loading = {true};

        GitTaskRunner.query(target.git(), () -> RepositoryTuning.read(repository),
                state -> {
                    box.setSelected(state.enabled());
                    box.setDisable(!state.available());
                    status.setText(state.available() ? "" : "Needs git installed.");
                    loading[0] = false;
                },
                error -> status.setText("Could not read: " + error));

        box.selectedProperty().addListener((o, was, on) -> {
            if (loading[0]) return;
            box.setDisable(true);
            status.setText(on ? "Turning on - writing the commit-graph can take a while…" : "Turning off…");
            GitTaskRunner.run(target.git(),
                    () -> on ? RepositoryTuning.enable(repository) : RepositoryTuning.disable(repository),
                    result -> {
                        box.setDisable(false);
                        status.setText(result.getMessage());
                        if (!result.isOk()) {
                            loading[0] = true;
                            box.setSelected(was);
                            loading[0] = false;
                        }
                    });
        });
        return part(
                row("Speed", box),
                status,
                hint("Turns on git's file-system monitor and untracked-file cache, so status "
                        + "re-checks only what changed, and keeps a commit-graph so history "
                        + "is walked faster. This changes the repository's own git config - "
                        + "your terminal's git uses it too - and runs a small background "
                        + "process that watches the folder. Helps the Auto and Native engines."));
    }

    // ------------------------------------------------------------------
    // Commit template
    // ------------------------------------------------------------------

    /** What the template part shows, read on the repository lane. */
    record TemplateState(boolean applied, String text) {
    }

    /**
     * The commit template: a summary and a description that every new commit message
     * starts from - git's own {@code commit.template}, so a terminal commit gets it too.
     *
     * <p>It edits the file the repository's config already names. When the repository
     * names none - including when a template comes from the global config - the text is
     * written to a file in the repository's git directory and named in its local config,
     * so the global template is left as it was for every other repository.
     */
    private VBox commitTemplate(Target target) {
        GitBackend git = target.git();
        CheckBox apply = new CheckBox("Apply this template to commit messages");
        apply.setId(TEMPLATE_APPLY_ID);
        TextField summary = new TextField();
        summary.setId(TEMPLATE_SUMMARY_ID);
        summary.setPromptText("feat: ");
        TextArea description = new TextArea();
        description.setId(TEMPLATE_DESCRIPTION_ID);
        description.setPrefRowCount(4);
        description.setWrapText(true);
        Button save = new Button("Save commit template");
        save.setId(TEMPLATE_SAVE_ID);
        save.setMinWidth(Region.USE_PREF_SIZE);
        Label status = hint("Reading…");
        status.setId(TEMPLATE_STATUS_ID);
        boolean[] loading = {true};
        apply.setDisable(true);
        save.setDisable(true);

        GitTaskRunner.query(target.git(), () -> readTemplate(git), state -> {
            String[] split = splitTemplate(state.text());
            summary.setText(split[0]);
            description.setText(split[1]);
            apply.setSelected(state.applied());
            apply.setDisable(false);
            save.setDisable(false);
            status.setText(state.applied() || state.text().isEmpty() ? ""
                    : "This is the template from your global git config. Saving gives this "
                            + "repository a template of its own.");
            loading[0] = false;
        }, error -> status.setText("Could not read the commit template: " + error));

        Runnable write = () -> {
            String text = joinTemplate(summary.getText(), description.getText());
            save.setDisable(true);
            status.setText("Saving…");
            GitTaskRunner.run(target.git(), () -> writeTemplate(git, text), result -> {
                save.setDisable(false);
                status.setText(result.describe());
                if (result.isOk()) {
                    loading[0] = true;
                    apply.setSelected(true);
                    loading[0] = false;
                    target.onChanged().run();
                }
            });
        };
        save.setOnAction(e -> write.run());
        apply.selectedProperty().addListener((o, was, on) -> {
            if (loading[0]) return;
            if (on) {
                write.run();
                return;
            }
            status.setText("Turning the template off…");
            GitTaskRunner.run(target.git(), () -> unsetLocal(git, TEMPLATE_KEY), result -> {
                status.setText(result.isOk()
                        ? "Off. The file is kept, so ticking the box again brings the same text back."
                        : result.describe());
                target.onChanged().run();
            });
        });

        return part(
                section("Commit template"),
                apply,
                row("Summary", summary),
                row("Description", description),
                save,
                status,
                hint("New commit messages start from this text, here and in git on the command "
                        + "line. Lines starting with # are left out of the commit."));
    }

    /** Off the FX thread: whether the local config names a template, and its text. */
    static TemplateState readTemplate(GitBackend git) {
        String local = git.getLocalConfig(TEMPLATE_KEY);
        if (local == null || local.isBlank()) {
            String inherited = git.getCommitTemplate();
            return new TemplateState(false, inherited == null ? "" : inherited);
        }
        File file = resolveConfiguredPath(local.trim(), git.getWorkTree());
        try {
            return new TemplateState(true, file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "");
        } catch (IOException e) {
            throw new IllegalStateException(file + ": " + e.getMessage(), e);
        }
    }

    /** Off the FX thread: writes {@code text} as the template and names its file in the local config. */
    static GitResult writeTemplate(GitBackend git, String text) {
        String local = git.getLocalConfig(TEMPLATE_KEY);
        boolean named = local != null && !local.isBlank();
        File file = named ? resolveConfiguredPath(local.trim(), git.getWorkTree())
                : new File(git.getDirectory(), TEMPLATE_FILE);
        try {
            if (file.getParentFile() != null) Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return GitResult.failed("Could not write " + file + ": " + e.getMessage());
        }
        if (!named) {
            // Forward slashes: git reads them on every platform, and a backslash in a
            // config value is an escape character.
            GitResult stored = git.setLocalConfig(TEMPLATE_KEY, file.getAbsolutePath().replace('\\', '/'));
            if (!stored.isOk()) return stored;
        }
        return GitResult.ok("Saved. New commit messages in this repository start from it.");
    }

    /** Removes {@code key} from the local config, and says so when it was not there to remove. */
    static GitResult unsetLocal(GitBackend git, String key) {
        if (git.getLocalConfig(key) == null) return GitResult.ok();
        return git.setLocalConfig(key, null);
    }

    /** A config path as git reads it: {@code ~} is the home folder, a relative path is from the work tree. */
    static File resolveConfiguredPath(String value, File workTree) {
        if (value.equals("~") || value.startsWith("~/") || value.startsWith("~\\")) {
            return new File(System.getProperty("user.home"), value.length() > 2 ? value.substring(2) : "");
        }
        File file = new File(value);
        return file.isAbsolute() || workTree == null ? file : new File(workTree, value);
    }

    /** A template's first line and what follows the blank line after it. */
    static String[] splitTemplate(String text) {
        String normalised = text == null ? "" : text.replace("\r\n", "\n").stripTrailing();
        int newline = normalised.indexOf('\n');
        if (newline < 0) return new String[]{normalised, ""};
        return new String[]{normalised.substring(0, newline), normalised.substring(newline + 1).strip()};
    }

    /** The template file's text: the summary, a blank line, the description, one trailing newline. */
    static String joinTemplate(String summary, String description) {
        String first = summary == null ? "" : summary.stripTrailing();
        String rest = description == null ? "" : description.replace("\r\n", "\n").strip();
        return rest.isEmpty() ? first + "\n" : first + "\n\n" + rest + "\n";
    }

    // ------------------------------------------------------------------
    // Git hooks
    // ------------------------------------------------------------------

    /**
     * Which hooks run on this repository's commits, where they are read from, and whether
     * the composer skips them unless told otherwise.
     *
     * <p>Hooks run someone else's code on your commit, from a directory nothing shows you.
     * The list is the other half of the composer's Skip hooks switch. A hook that is there
     * but not executable is called out: git ignores it silently, and "my pre-commit hook
     * stopped running" is hard to work out from inside git.
     */
    private VBox hooks(Target target) {
        GitBackend git = target.git();
        File repository = target.directory();

        TextField path = new TextField();
        path.setId(HOOKS_PATH_ID);
        path.setPromptText("Blank uses the repository's own .git/hooks");
        HBox.setHgrow(path, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        Button save = new Button("Save");
        save.setId(HOOKS_SAVE_ID);
        save.setMinWidth(Region.USE_PREF_SIZE);
        HBox pathControls = new HBox(UiScale.STEP, path, browse, save);
        pathControls.setAlignment(Pos.CENTER_LEFT);
        Label status = hint("");
        status.setId(HOOKS_STATUS_ID);

        ListView<RepoStructureOps.Hook> list = new ListView<>();
        list.setId(HOOKS_ID);
        list.setCellFactory(view -> new HookCell());
        Label none = new Label("No hooks run on commits here.");
        none.setWrapText(true);
        list.setPlaceholder(none);
        list.setPrefHeight(140);
        Label summary = hint("Reading…");

        Runnable loadHooks = () -> GitTaskRunner.query(target.git(), git::getHooks, found -> {
            list.getItems().setAll(found);
            summary.setText(describeHooks(found));
        }, error -> summary.setText("Could not read the hooks directory: " + error));

        GitTaskRunner.query(target.git(), () -> {
            String configured = git.getLocalConfig(HOOKS_PATH_KEY);
            return configured == null ? "" : configured;
        }, path::setText, error -> status.setText("Could not read core.hooksPath: " + error));
        loadHooks.run();

        Runnable store = () -> {
            String wanted = path.getText() == null ? "" : path.getText().trim();
            save.setDisable(true);
            status.setText("Saving…");
            GitTaskRunner.run(target.git(), () -> wanted.isEmpty() ? unsetLocal(git, HOOKS_PATH_KEY)
                            : git.setLocalConfig(HOOKS_PATH_KEY, wanted),
                    result -> {
                        save.setDisable(false);
                        status.setText(!result.isOk() ? result.describe()
                                : wanted.isEmpty() ? "Hooks are read from the repository's own .git/hooks."
                                : "Hooks are read from " + wanted + ".");
                        loadHooks.run();
                    });
        };
        save.setOnAction(e -> store.run());
        path.setOnAction(e -> store.run());
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Hooks directory");
            if (repository != null && repository.isDirectory()) chooser.setInitialDirectory(repository);
            File chosen = chooser.showDialog(root.getScene() == null ? null : root.getScene().getWindow());
            if (chosen == null) return;
            path.setText(chosen.getAbsolutePath().replace('\\', '/'));
            store.run();
        });

        Button refresh = new Button("Check again");
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        // A hook added in a terminal while this is open is exactly the case worth catching.
        refresh.setOnAction(e -> loadHooks.run());

        CheckBox skip = new CheckBox("Skip git hooks by default when committing");
        skip.setId(SKIP_HOOKS_ID);
        skip.setSelected(RepositoryPreferences.skipHooksByDefault(repository));
        skip.selectedProperty().addListener((o, was, on) -> {
            if (refreshing) return;
            RepositoryPreferences.setSkipHooksByDefault(repository, on);
            updateReset.run();
            target.onChanged().run();
        });
        refreshers.add(() -> skip.setSelected(false));

        return part(
                section("Git hooks"),
                row("Hooks directory", pathControls),
                status,
                hint("This repository's core.hooksPath - a shared hooks folder a team keeps in "
                        + "the repository, say. The global one is on the Profiles page."),
                summary,
                list,
                refresh,
                skip,
                hint("Starts the commit box with Skip hooks ticked in this repository, for hooks "
                        + "that are slow or broken and not yours to fix. It can still be unticked "
                        + "for any one commit."));
    }

    private static String describeHooks(List<RepoStructureOps.Hook> found) {
        if (found.isEmpty()) {
            return "Sample hooks that git creates in every repository are not listed - git never runs those.";
        }
        long inert = found.stream().filter(h -> !h.executable()).count();
        String summary = found.size() + (found.size() == 1 ? " hook runs" : " hooks run") + " in this repository.";
        if (inert == 0) return summary;
        return summary + " " + inert + (inert == 1 ? " is not executable, so git" : " are not executable, so git")
                + " silently ignores " + (inert == 1 ? "it" : "them") + ".";
    }

    /** Name, then path, with a non-executable hook marked as the dead letter it is. */
    private static final class HookCell extends ListCell<RepoStructureOps.Hook> {
        @Override
        protected void updateItem(RepoStructureOps.Hook hook, boolean empty) {
            super.updateItem(hook, empty);
            if (empty || hook == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            Label name = new Label(hook.name());
            name.setMinWidth(Region.USE_PREF_SIZE);
            Label path = new Label(hook.path());
            path.getStyleClass().add("text-muted");
            path.setMinWidth(0);
            Label verdict = new Label(hook.executable() ? "active" : "inert");
            verdict.getStyleClass().addAll("state-chip", hook.executable() ? "state-chip-ok" : "state-chip-warn");
            verdict.setMinWidth(Region.USE_PREF_SIZE);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, name, path, spacer, verdict);
            row.setAlignment(Pos.CENTER_LEFT);
            // Inside the list's width: a long path is cut rather than scrolling the list sideways.
            row.setMinWidth(0);
            row.setPrefWidth(1);
            setTooltip(hook.executable() ? new Tooltip(hook.path())
                    : new Tooltip("git will not run this: the file is not marked executable."));
            setText(null);
            setGraphic(row);
        }
    }

    // ------------------------------------------------------------------
    // Git Flow
    // ------------------------------------------------------------------

    /** What the Git Flow part shows, read on the repository lane. */
    private record FlowState(GitFlow.Config config, List<String> branches) {
    }

    /**
     * Git Flow's six settings, with "Initialize" for a repository not set up for it and
     * "Save" for one that is. Both are {@link GitFlow#init}, which records the settings and
     * creates the develop branch when it is missing.
     */
    private VBox gitFlow(Target target) {
        GitBackend git = target.git();
        ChoiceBox<String> production = new ChoiceBox<>();
        production.setId(FLOW_PRODUCTION_ID);
        TextField develop = flowField(FLOW_DEVELOP_ID);
        TextField feature = flowField(FLOW_FEATURE_ID);
        TextField release = flowField(FLOW_RELEASE_ID);
        TextField hotfix = flowField(FLOW_HOTFIX_ID);
        TextField versionTag = flowField(FLOW_VERSION_TAG_ID);
        versionTag.setPromptText("Often v, or nothing");
        Button save = new Button("Initialize");
        save.setId(FLOW_SAVE_ID);
        save.setMinWidth(Region.USE_PREF_SIZE);
        save.setDisable(true);
        Label status = hint("Reading…");
        status.setId(FLOW_STATUS_ID);

        GitTaskRunner.query(target.git(), () -> new FlowState(new GitFlow(git).config(),
                git.getLocalBranches().stream().map(RefInfo::shortName).toList()), state -> {
            production.getItems().setAll(state.branches());
            if (state.branches().isEmpty()) {
                status.setText("Git Flow needs a branch to release from, and this repository has no commits yet.");
                return;
            }
            GitFlow.Config start = state.config() != null ? state.config() : GitFlow.Config.defaults(
                    state.branches().contains("main") ? "main"
                            : state.branches().contains("master") ? "master" : state.branches().get(0));
            production.setValue(state.branches().contains(start.production())
                    ? start.production() : state.branches().get(0));
            develop.setText(start.develop());
            feature.setText(start.featurePrefix());
            release.setText(start.releasePrefix());
            hotfix.setText(start.hotfixPrefix());
            versionTag.setText(start.versionTagPrefix());
            save.setText(state.config() == null ? "Initialize" : "Save");
            save.setDisable(false);
            status.setText(state.config() == null
                    ? "Not set up. Initialize records these names and creates the develop branch if it is missing."
                    : "");
        }, error -> status.setText("Could not read the Git Flow settings: " + error));

        save.setOnAction(e -> {
            GitFlow.Config wanted = new GitFlow.Config(production.getValue(), develop.getText(),
                    feature.getText(), release.getText(), hotfix.getText(), versionTag.getText());
            save.setDisable(true);
            status.setText("Saving…");
            GitTaskRunner.run(target.git(), () -> new GitFlow(git).init(wanted), result -> {
                save.setDisable(false);
                status.setText(result.describe());
                if (result.isOk()) {
                    save.setText("Save");
                    target.onChanged().run();
                }
            });
        });

        return part(
                section("Git Flow"),
                row("Production branch", production),
                row("Develop branch", develop),
                row("Feature prefix", feature),
                row("Release prefix", release),
                row("Hotfix prefix", hotfix),
                row("Version tag prefix", versionTag),
                save,
                status);
    }

    private static TextField flowField(String id) {
        TextField field = new TextField();
        field.setId(id);
        field.setPrefColumnCount(16);
        return field;
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    private VBox encoding(Target target) {
        File repository = target.directory();
        ChoiceBox<TextEncoding> choice = new ChoiceBox<>();
        choice.setId(ENCODING_ID);
        choice.getItems().setAll(TextEncoding.selectable());
        choice.setConverter(converter(TextEncoding::label));
        choice.setValue(RepositoryPreferences.defaultEncoding(repository));
        choice.valueProperty().addListener((o, old, value) -> {
            if (refreshing || value == null) return;
            RepositoryPreferences.setDefaultEncoding(repository, value);
            updateReset.run();
        });
        refreshers.add(() -> choice.setValue(TextEncoding.GUESS));
        return part(
                section("Encoding"),
                row("Default encoding", choice),
                hint("What a file in this repository is decoded as when it is first shown. Guess "
                        + "reads UTF-8 and falls back for anything that is not; a repository of "
                        + "Shift-JIS or Windows-1252 sources reads right from the start with its "
                        + "own. The diff header can still change it for one file."));
    }

    // ------------------------------------------------------------------
    // LFS
    // ------------------------------------------------------------------

    /** What the LFS line says, read on the repository lane. */
    private record LfsState(boolean installed, boolean usable, int patterns) {
    }

    private VBox lfs(Target target) {
        GitBackend git = target.git();
        Label status = hint("Checking…");
        status.setId(LFS_STATUS_ID);
        GitTaskRunner.query(target.git(), () -> new LfsState(NativeGitRunner.isGitLfsInstalled(),
                        git.canMaterializeLfsContent(), git.lfsTrackedPatterns().size()),
                state -> status.setText(describeLfs(state)),
                error -> status.setText("Could not read this repository's LFS state: " + error));
        Button open = new Button("Open Git LFS…");
        open.setId(OPEN_LFS_ID);
        open.setMinWidth(Region.USE_PREF_SIZE);
        open.setOnAction(e -> target.openLfs().run());
        return part(section("Git LFS"), status, open);
    }

    private static String describeLfs(LfsState state) {
        String tracked = state.patterns() == 0 ? "No files are tracked with LFS."
                : state.patterns() + (state.patterns() == 1 ? " pattern is" : " patterns are") + " tracked with LFS.";
        if (!state.installed()) {
            return "Git LFS is not installed on this machine, so its files stay as small pointer files here. "
                    + tracked;
        }
        if (!state.usable()) {
            return "Git LFS is installed, but it is not set up for this repository, or the engine in use "
                    + "cannot run it. " + tracked;
        }
        return "Git LFS is installed and set up for this repository. " + tracked;
    }

    // ------------------------------------------------------------------
    // Conflict prevention
    // ------------------------------------------------------------------

    /**
     * The early conflict warning's settings for this repository: whether it warns, and
     * which branches count as a base; see {@link ConflictPreventionSettings}, which brings
     * its own heading.
     */
    private VBox conflictPrevention(Target target) {
        VBox slot = part(ConflictPreventionSettings.section(target.directory()));
        slot.setId(CONFLICT_PREVENTION_ID);
        return slot;
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    /**
     * git's own housekeeping, offered with git's verdict on whether it is needed.
     *
     * <p>A repository that has never been packed is slow at everything this application
     * does, and nothing tells the user that the fix for "this feels slow" is a command
     * they have never heard of.
     */
    private VBox housekeeping(Target target) {
        GitBackend git = target.git();
        Label verdict = hint("Checking…");
        verdict.setId(HOUSEKEEPING_ID);
        Button run = new Button("Run housekeeping");
        run.setMinWidth(Region.USE_PREF_SIZE);
        run.setDisable(true);

        GitTaskRunner.query(target.git(), git::needsMaintenance, needed -> {
            run.setDisable(false);
            verdict.setText(needed
                    ? "This repository has enough loose objects that git would benefit from packing "
                            + "them. It is safe to run at any time and does nothing if git decides there "
                            + "is nothing to do."
                    : "Nothing to do - git's own threshold says this repository is in good shape. "
                            + "Running it anyway is harmless.");
        }, error -> verdict.setText("Could not check: " + error));

        run.setOnAction(e -> {
            run.setDisable(true);
            verdict.setText("Running…");
            GitTaskRunner.run(target.git(), git::runMaintenance, result -> {
                verdict.setText(result.describe());
                run.setDisable(false);
            });
        });
        return part(section("Housekeeping"), verdict, run);
    }

    // ------------------------------------------------------------------

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> text) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : text.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }
}
