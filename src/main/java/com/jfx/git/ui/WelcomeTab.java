package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.Favorites;
import com.jfx.git.core.RecentRepositories;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The "New Tab" landing page: open/clone/create actions and searchable lists of
 * favorited and recently opened repositories.
 */
public class WelcomeTab extends Tab {

    private final LyraApp app;
    private final TextField search = new TextField();
    private final ListView<File> favoritesList = new ListView<>();
    private final ListView<File> recentList = new ListView<>();
    private List<File> allFavorites = List.of();
    private List<File> allRecent = List.of();

    /** One repository's row in either list. */
    private static final double ROW_HEIGHT = 36;

    public WelcomeTab(LyraApp app) {
        super("New Tab");
        this.app = app;
        setClosable(true);

        Label heading = new Label("Repositories");
        heading.getStyleClass().add("welcome-heading");

        // Cards, not buttons in a row. This screen exists to do one of three things, and
        // three equal-weight buttons with an emoji each said neither which was which nor
        // what any of them would actually do. A card has room for the second line that
        // answers that, and the drawn icons replace emoji that JavaFX renders flat grey
        // at a fallback font's baseline - the exact thing Icons was written to avoid.
        HBox actions = new HBox(UiScale.GAP,
                actionCard(Icons.open(), "Open", "A folder already under git",
                        app::openRepoDialog),
                actionCard(Icons.cloneRepo(), "Clone", "From a URL or a connected account",
                        app::cloneRepoDialog),
                actionCard(Icons.create(), "Create", "A new repository, ready for its first commit",
                        app::initRepoDialog));
        actions.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("Search repositories");
        search.getStyleClass().add("welcome-search");
        search.textProperty().addListener((obs, old, text) -> applyFilter(text));

        Label favoritesLabel = new Label("Favorites");
        favoritesLabel.getStyleClass().add("welcome-section");
        favoritesList.getStyleClass().add("welcome-recent-list");
        favoritesList.setCellFactory(lv -> new RepoCell(true));
        favoritesList.setPlaceholder(new Label("Star a repository below to pin it here."));
        favoritesList.setOnMouseClicked(this::openOnDoubleClick);
        favoritesList.setOnKeyPressed(this::openOnEnter);
        // As tall as what it holds. Favorites is a handful of repositories (the keyboard
        // shortcut only reaches nine of them); it was a fixed 150 pixels whether it held
        // five or none, so an empty one was a hole in the page with a sentence in it.
        favoritesList.setFixedCellSize(ROW_HEIGHT);
        favoritesList.prefHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(1, Math.min(9, favoritesList.getItems().size())) * ROW_HEIGHT + 2.0,
                favoritesList.getItems()));
        favoritesList.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox.setVgrow(favoritesList, Priority.NEVER);

        Label recentLabel = new Label("Recent");
        recentLabel.getStyleClass().add("welcome-section");

        recentList.getStyleClass().add("welcome-recent-list");
        recentList.setCellFactory(lv -> new RepoCell(false));
        recentList.setPlaceholder(new Label("No repositories opened yet."));
        recentList.setOnMouseClicked(this::openOnDoubleClick);
        recentList.setOnKeyPressed(this::openOnEnter);
        recentList.setFixedCellSize(ROW_HEIGHT);
        VBox.setVgrow(recentList, Priority.ALWAYS);

        VBox content = new VBox(UiScale.EDGE, heading, actions, search,
                favoritesLabel, favoritesList, recentLabel, recentList);
        content.setPadding(new Insets(40, UiScale.SECTION, UiScale.SECTION, UiScale.SECTION));
        content.setMaxWidth(880);

        // Centre the column but let it grow with the window.
        VBox centred = new VBox(content);
        centred.setAlignment(Pos.TOP_CENTER);
        centred.getStyleClass().add("welcome-root");
        VBox.setVgrow(content, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(centred);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("welcome-scroll");

        setContent(scroll);
        refresh();
    }

    /** The favourites as of the last refresh. */
    private java.util.Set<File> favoriteSet = java.util.Set.of();

    /**
     * The same favourites in canonical form, and each row's own canonical path as it is
     * first asked for. A recent entry can name a favourite by another spelling, which is
     * what the per-row {@code Favorites.isFavorite} fallback was for - but that re-read the
     * settings and resolved every favourite on disk for each such row, on every redraw.
     * Both are rebuilt by {@link #refresh}, which every change to the favourites calls.
     */
    private java.util.Set<String> favoriteCanonical = java.util.Set.of();
    private final java.util.Map<File, String> canonicalByFile = new java.util.HashMap<>();

    private boolean isFavorite(File item) {
        return favoriteSet.contains(item) || favoriteCanonical.contains(
                canonicalByFile.computeIfAbsent(item, Favorites::canonicalPath));
    }

    /** Enter opens the selected repository, as a double-click does. */
    private void openOnEnter(javafx.scene.input.KeyEvent e) {
        if (e.getCode() != javafx.scene.input.KeyCode.ENTER) return;
        ListView<?> source = (ListView<?>) e.getSource();
        File selected = (File) source.getSelectionModel().getSelectedItem();
        if (selected != null) {
            app.openRepository(selected);
            e.consume();
        }
    }

    private void openOnDoubleClick(javafx.scene.input.MouseEvent e) {
        if (e.getClickCount() != 2) {
            return;
        }
        ListView<?> source = (ListView<?>) e.getSource();
        File selected = (File) source.getSelectionModel().getSelectedItem();
        if (selected != null) app.openRepository(selected);
    }

    /** Reloads both lists against the current search text; call after a repository is opened, favorited or removed. */
    public final void refresh() {
        allFavorites = Favorites.load();
        // Read once per refresh, not once per row: isFavorite re-reads the settings and
        // resolves every favourite's canonical path, for each row the list draws.
        favoriteSet = new java.util.HashSet<>(allFavorites);
        java.util.Set<String> canonical = new java.util.HashSet<>();
        for (File favorite : allFavorites) {
            canonical.add(Favorites.canonicalPath(favorite));
        }
        favoriteCanonical = canonical;
        canonicalByFile.clear();
        allRecent = RecentRepositories.load();
        applyFilter(search.getText());
    }

    private void applyFilter(String query) {
        favoritesList.getItems().setAll(filtered(allFavorites, query));
        recentList.getItems().setAll(filtered(allRecent, query));
    }

    private static List<File> filtered(List<File> source, String query) {
        if (query == null || query.isBlank()) {
            return source;
        }
        String q = query.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(f -> f.getName().toLowerCase(Locale.ROOT).contains(q)
                        || f.getAbsolutePath().toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }

    /**
     * One of the three things this screen is for.
     *
     * <p>A button whose whole label is a word cannot say what pressing it involves;
     * "Clone" and "Create" both make a repository appear and differ entirely in what they
     * ask of you. The second line is where that difference lives, which is why these are
     * cards rather than wider buttons.
     */
    private Button actionCard(javafx.scene.Node icon, String title, String detail,
                              Runnable action) {
        Label name = new Label(title);
        name.getStyleClass().add("welcome-card-title");
        Label description = new Label(detail);
        description.getStyleClass().add("welcome-card-detail");
        // One line. A wrapping label reports the height it would need at its narrowest,
        // so three cards each holding nine words came out four hundred pixels tall.
        description.setMinWidth(0);

        VBox words = new VBox(UiScale.TIGHT, name, description);
        words.setAlignment(Pos.CENTER_LEFT);
        HBox body = new HBox(UiScale.GAP, icon, words);
        body.setAlignment(Pos.CENTER_LEFT);

        Button card = new Button();
        card.setGraphic(body);
        card.getStyleClass().add("welcome-action");
        card.setAccessibleText(title + ". " + detail);
        card.setOnAction(e -> action.run());
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        // Three equal thirds, whatever each one's words measure.
        card.setPrefWidth(0);
        card.setMinWidth(0);
        return card;
    }

    /** Star toggle, repository name in accent colour, and its muted full path. */
    private class RepoCell extends ListCell<File> {
        private final boolean inFavoritesList;

        RepoCell(boolean inFavoritesList) {
            this.inFavoritesList = inFavoritesList;
            // The list's width, not the path's: a row asking for the full length of
            // C:\Users\... gave the list a horizontal scrollbar.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(File item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }

            boolean favorite = isFavorite(item);

            // A button, so it can be reached and pressed from the keyboard and says what
            // it does; it was a star character in a label with a mouse handler.
            String what = favorite ? "Remove from favorites" : "Add to favorites";
            Button star = Icons.button(favorite ? Icons.starFilled() : Icons.star(), what, what);
            star.getStyleClass().addAll("btn-sm", "welcome-favorite-star");
            if (favorite) star.getStyleClass().add("welcome-favorite-star-active");
            star.setOnAction(e -> {
                Favorites.toggle(item);
                refresh();
            });

            Label name = new Label(item.getName());
            name.getStyleClass().add("welcome-repo-name");

            name.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

            // The path gives way, from its front: the end of a path is the part that
            // tells two repositories apart.
            Label path = new Label(item.getAbsolutePath());
            path.getStyleClass().add("welcome-repo-path");
            path.setMinWidth(0);
            path.setMaxWidth(Double.MAX_VALUE);
            path.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
            path.setTooltip(new javafx.scene.control.Tooltip(item.getAbsolutePath()));
            HBox.setHgrow(path, Priority.ALWAYS);

            HBox row = new HBox(UiScale.STEP, star, name, path);
            row.setAlignment(Pos.CENTER_LEFT);

            ContextMenu menu = new ContextMenu();
            MenuItem open = new MenuItem("Open");
            open.setOnAction(e -> app.openRepository(item));
            MenuItem favoriteToggle = new MenuItem(favorite ? "Remove from Favorites" : "Add to Favorites");
            favoriteToggle.setOnAction(e -> {
                Favorites.toggle(item);
                refresh();
            });
            menu.getItems().addAll(open, favoriteToggle);
            if (!inFavoritesList) {
                MenuItem forget = new MenuItem("Remove from recent");
                forget.setOnAction(e -> {
                    RecentRepositories.remove(item);
                    refresh();
                });
                menu.getItems().add(forget);
            }
            setContextMenu(menu);

            setText(null);
            setGraphic(row);
        }
    }
}
