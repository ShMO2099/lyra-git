package com.jfx.git.ui.settings;

import com.jfx.git.core.GitBackendFactory;
import com.jfx.git.ui.SettingsSection;
import com.jfx.git.ui.Shortcuts;
import com.jfx.git.ui.ToolTab;
import com.jfx.git.ui.UiScale;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Every application-wide setting in one tab: a list of sections on the left, the
 * selected one on the right.
 *
 * <h2>Why a tab</h2>
 * Preferences, the two profile editors, Integrations and the shortcut sheet were five
 * windows, each its own entry on the taskbar and each in front of the work it was set up
 * for. A tab sits beside the repositories instead: switch to it, change something, switch
 * back and see the change, without a window to find or close. Asking for it again
 * selects it (see {@code LyraApp.showToolTab}); asking for a section selects that
 * section.
 *
 * <h2>Built as visited</h2>
 * A page is built the first time its section is shown and kept after that, so opening
 * Settings at Integrations does not first read the global gitconfig or ask every engine
 * for its version. What a page holds onto past its own controls - Integrations follows
 * the provider sessions - is released in {@link #dispose()}, when the tab closes.
 */
public final class SettingsTab extends ToolTab {

    private final ListView<SettingsSection> nav = new ListView<>();
    private final VBox pageHolder = new VBox();
    private final ScrollPane scroll = new ScrollPane(pageHolder);
    private final Map<SettingsSection, Region> built = new EnumMap<>(SettingsSection.class);

    private final PreferencesPages preferences;
    private final Supplier<List<Shortcuts.Binding>> bindings;
    private IntegrationsPane integrations;
    /** The open repositories, for the Repository section; {@code null} with no application. */
    private final RepositoryPage.Repositories repositories;
    private RepositoryPage repositoryPage;

    /** A tab with no application behind it: diff and engine changes are only stored. For tests and tours. */
    public SettingsTab() {
        this(null, null, List::of);
    }

    /**
     * @param onDiffSettingsChanged run after a diff setting changes, so open viewers follow
     * @param onEngineChanged       stores the engine choice and rebuilds open repositories' engines
     * @param bindings              the live keyboard map, read whenever the shortcut page is built
     */
    public SettingsTab(Runnable onDiffSettingsChanged,
                       Consumer<GitBackendFactory.BackendEngine> onEngineChanged,
                       Supplier<List<Shortcuts.Binding>> bindings) {
        this(onDiffSettingsChanged, onEngineChanged, bindings, null);
    }

    /**
     * @param repositories the repositories open in the window, for the Repository
     *                     section; {@code null} leaves that section empty
     */
    public SettingsTab(Runnable onDiffSettingsChanged,
                       Consumer<GitBackendFactory.BackendEngine> onEngineChanged,
                       Supplier<List<Shortcuts.Binding>> bindings,
                       RepositoryPage.Repositories repositories) {
        this(onDiffSettingsChanged, onEngineChanged, bindings, repositories, null);
    }

    /**
     * @param notices where a page says something it did is done, when there is nothing to
     *                answer - the window's status line; {@code null} says nothing
     */
    public SettingsTab(Runnable onDiffSettingsChanged,
                       Consumer<GitBackendFactory.BackendEngine> onEngineChanged,
                       Supplier<List<Shortcuts.Binding>> bindings,
                       RepositoryPage.Repositories repositories,
                       Consumer<String> notices) {
        super("Settings");
        this.repositories = repositories;
        this.preferences = new PreferencesPages(onDiffSettingsChanged, onEngineChanged, notices);
        this.bindings = bindings == null ? List::of : bindings;

        nav.getItems().setAll(SettingsSection.values());
        nav.getStyleClass().add("nav-list");
        nav.setPrefWidth(200);
        nav.setMinWidth(160);
        nav.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SettingsSection section, boolean empty) {
                super.updateItem(section, empty);
                setText(empty || section == null ? null : section.label());
            }
        });
        nav.getSelectionModel().selectedItemProperty().addListener((obs, old, section) -> show(section));

        // A column of readable width rather than the whole window: a hint line or a text
        // field stretched across 1,100 pixels is a line nobody can follow to its end.
        pageHolder.setPadding(new Insets(UiScale.SECTION, UiScale.SECTION, UiScale.SECTION, UiScale.SECTION));
        pageHolder.setMaxWidth(840);

        VBox column = new VBox(pageHolder);
        column.getStyleClass().add("settings-page");
        scroll.setContent(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("preferences-scroll");

        HBox root = new HBox(nav, scroll);
        root.getStyleClass().add("settings-root");
        root.setPadding(new Insets(UiScale.EDGE, 0, 0, UiScale.EDGE));
        HBox.setHgrow(scroll, Priority.ALWAYS);
        setContent(root);

        nav.getSelectionModel().select(SettingsSection.GENERAL);

        // Coming back to Settings from a repository is asking about that repository.
        selectedProperty().addListener((obs, was, now) -> {
            if (now && repositoryPage != null) repositoryPage.followCurrent();
        });
    }

    /** Shows the Repository section, on {@code repository} when it is open. */
    public void selectRepository(java.io.File repository) {
        page(SettingsSection.REPOSITORY);
        repositoryPage.select(repository);
        select(SettingsSection.REPOSITORY);
    }

    /** Shows {@code section}, building its page if this is its first time on screen. */
    public void select(SettingsSection section) {
        if (section == null) return;
        nav.getSelectionModel().select(section);
        nav.scrollTo(section);
        if (section == SettingsSection.KEYBOARD_SHORTCUTS
                && built.get(section) instanceof ShortcutsPage shortcuts) {
            // Opened by its own key, the sheet is opened to look something up.
            Platform.runLater(shortcuts::focusFilter);
        }
    }

    /** The section on screen. */
    public SettingsSection selectedSection() {
        return nav.getSelectionModel().getSelectedItem();
    }

    /** The page for {@code section}, built now if it has not been; for tests that read a page's controls. */
    public Region page(SettingsSection section) {
        return built.computeIfAbsent(section, this::build);
    }

    private void show(SettingsSection section) {
        if (section == null) return;
        pageHolder.getChildren().setAll(page(section));
        // Each section starts at its top; the last one's scroll position means nothing here.
        scroll.setVvalue(0);
    }

    private Region build(SettingsSection section) {
        return switch (section) {
            case GENERAL -> preferences.generalPage();
            case APPEARANCE -> preferences.appearancePage();
            case GIT_ENGINE -> preferences.gitEnginePage();
            case EXTERNAL_TOOLS -> preferences.externalToolsPage();
            case DIFF -> preferences.diffPage();
            case SSH -> preferences.sshPage();
            case COMMIT -> new CommitPage();
            case PROFILES -> new ProfilesPage();
            case INTEGRATIONS -> integrations = new IntegrationsPane();
            case KEYBOARD_SHORTCUTS -> new ShortcutsPage(bindings);
            case REPOSITORY -> (repositoryPage = new RepositoryPage(repositories)).region();
        };
    }

    @Override
    public void dispose() {
        if (integrations != null) {
            integrations.dispose();
            integrations = null;
        }
        if (repositoryPage != null) {
            // Kept, emptied: it is still the page the tab holds for the section.
            repositoryPage.dispose();
        }
    }
}
