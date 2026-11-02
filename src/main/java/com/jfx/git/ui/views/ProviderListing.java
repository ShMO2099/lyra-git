package com.jfx.git.ui.views;

import com.jfx.git.core.BuildStatus;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.IntegrationEvents;
import com.jfx.git.core.ListPage;
import com.jfx.git.ui.dialogs.IntegrationDialog;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The list half of a pull request, merge request or issue view: a page at a time,
 * CI status for what is on screen, and nothing left running once the view closes.
 *
 * <h2>Why it exists</h2>
 * The provider dialogs these views replace each followed every page of their list before showing
 * anything, then queued one CI-status task per pull request on the single network lane,
 * none of them ever cancelled. A busy repository sat on "Loading..." for as long as that
 * took, and the next one's list waited behind the last one's status calls. They now
 * share this:
 * <ul>
 *   <li>the newest {@link ListPage#SIZE} as soon as one request answers, and a
 *       <em>Load more</em> button for the next page;</li>
 *   <li>the filter searches what is loaded and says so;</li>
 *   <li>CI status only for rows actually on screen, at most {@link #STATUS_IN_FLIGHT} at a
 *       time, each asked once;</li>
 *   <li>everything on {@link GitTaskRunner#queryProvider}, and cancelled when the view
 *       closes or reloads;</li>
 *   <li>"Reconnecting..." while a remembered token is restored, and the list loads by
 *       itself once the provider connects - from the prompt in the list or anywhere else.</li>
 * </ul>
 *
 * <p>Everything here runs on the FX thread except the {@link Source} and status suppliers.
 */
final class ProviderListing<T> {

    /** A page, or why there is none; built off the FX thread by the view's {@link Source}. */
    record Fetched<T>(ListPage<T> page, String error) {

        static <T> Fetched<T> ok(ListPage<T> page) {
            return new Fetched<>(page, null);
        }

        static <T> Fetched<T> failed(String error) {
            return new Fetched<>(null, error == null ? "unknown error" : error);
        }
    }

    /** Fetches one 1-based page; called on the provider pool. */
    interface Source<T> {
        Fetched<T> fetch(int page);
    }

    /**
     * CI status calls running at once; each is one or two requests. One fewer than the
     * provider pool's threads, so the next page or a merge never queues behind them.
     */
    static final int STATUS_IN_FLIGHT = 3;

    /** Rows asked about when the list has never been laid out - a view built but not shown. */
    private static final int UNSHOWN_ROWS = 10;

    private final ListView<T> view;
    private final TextField filter;
    private final String noun;
    private final String provider;
    private final Function<T, Object> key;
    private final BiPredicate<T, String> matches;

    private final Button loadMore = new Button("Load more");
    private final Label note = new Label();
    private final VBox area;

    private final List<T> all = new ArrayList<>();
    private final Set<Object> keys = new HashSet<>();
    private final List<GitTaskRunner.Task> tasks = new ArrayList<>();

    private Source<T> source;
    private int nextPage = 1;
    private boolean hasMore;
    private boolean loading;
    private boolean closed;
    /** Why page 1 failed, kept so typing in the filter does not paper over it. */
    private String failure;
    /** Bumped by every reload, so an answer to an older one is dropped. */
    private long generation;

    private String originUrl;
    private boolean originKnown;

    private InvalidationListener waitingForConnection;

    private Function<T, Supplier<BuildStatus>> statusFetch;
    private final Map<Object, BuildStatus> statuses = new HashMap<>();
    private final Set<Object> statusAsked = new HashSet<>();
    private final ArrayDeque<T> statusQueue = new ArrayDeque<>();
    private int statusRunning;
    private VirtualFlow<?> flow;

    /**
     * @param noun     what the list holds, plural - "pull requests"; used in every
     *                 placeholder so each view keeps its own wording
     * @param provider "GitHub", for the connect and reconnecting placeholders
     * @param key      identifies an item across pages, so an item that moved between
     *                 two requests is not listed twice
     * @param matches  whether an item matches the lower-cased filter text
     */
    ProviderListing(ListView<T> view, TextField filter, String noun, String provider,
                    Function<T, Object> key, BiPredicate<T, String> matches) {
        this.view = view;
        this.filter = filter;
        this.noun = noun;
        this.provider = provider;
        this.key = key;
        this.matches = matches;

        note.getStyleClass().add("t-caption");
        note.setWrapText(true);
        loadMore.setOnAction(e -> loadMore());
        loadMore.managedProperty().bind(loadMore.visibleProperty());
        loadMore.setVisible(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, note, spacer, loadMore);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(view, Priority.ALWAYS);
        view.setMaxHeight(Double.MAX_VALUE);
        area = new VBox(6, view, footer);

        filter.textProperty().addListener((obs, old, text) -> applyFilter());
        view.skinProperty().addListener((obs, old, skin) -> watchFlow());
    }

    /** The list and its footer, for {@link JfxDialog#browser}'s list slot. */
    Region area() {
        return area;
    }

    /**
     * The origin remote's URL as the caller already knew it - {@code RepoToolbar} reads it
     * on the repository lane when it refreshes. {@code null} means unknown, and
     * {@link #withOrigin} reads it itself, off the FX thread.
     */
    void knownOrigin(String url) {
        if (url != null) {
            originUrl = url;
            originKnown = true;
        }
    }

    /** Runs {@code then} with the origin URL ({@code null} when there is no origin), reading it once on the repository lane if need be. */
    void withOrigin(GitBackend gitService, Consumer<String> then) {
        if (originKnown) {
            then.accept(originUrl);
            return;
        }
        placeholder("Reading the origin remote…");
        GitTaskRunner.query(gitService, () -> gitService.getRemoteUrl("origin"),
                url -> {
                    if (closed) return;
                    originUrl = url;
                    originKnown = true;
                    then.accept(url);
                },
                error -> placeholder("Could not read the origin remote: " + error));
    }

    /**
     * Calls {@code onReady} now if {@code connected}, otherwise once it becomes true.
     *
     * <p>Meanwhile the list shows "Reconnecting to GitHub…" while a remembered token is
     * being restored, and the connect prompt otherwise - whose dialog, once it connects,
     * brings the list here without the user reopening it.
     */
    void whenConnected(BooleanSupplier connected, Runnable onReady) {
        stopWaitingForConnection();
        if (connected.getAsBoolean()) {
            onReady.run();
            return;
        }
        showDisconnected();
        waitingForConnection = obs -> {
            if (closed) return;
            if (connected.getAsBoolean()) {
                stopWaitingForConnection();
                onReady.run();
            } else {
                showDisconnected();
            }
        };
        IntegrationEvents.changesProperty().addListener(waitingForConnection);
        IntegrationEvents.restoringProperty().addListener(waitingForConnection);
    }

    private void showDisconnected() {
        if (IntegrationEvents.isRestoring()) {
            placeholder("Reconnecting to " + provider + "…");
        } else {
            view.setPlaceholder(IntegrationDialog.connectPrompt(provider));
        }
    }

    private void stopWaitingForConnection() {
        if (waitingForConnection != null) {
            IntegrationEvents.changesProperty().removeListener(waitingForConnection);
            IntegrationEvents.restoringProperty().removeListener(waitingForConnection);
            waitingForConnection = null;
        }
    }

    /** Asks each shown row's CI status through {@code fetch}, whose supplier answers null on failure. */
    void statuses(Function<T, Supplier<BuildStatus>> fetch) {
        this.statusFetch = fetch;
    }

    /** The status fetched for an item's key, or null if not (yet) known. */
    BuildStatus statusOf(Object itemKey) {
        return statuses.get(itemKey);
    }

    /** For a cell: the status of the item it shows. */
    BuildStatus statusOfItem(T item) {
        return statuses.get(key.apply(item));
    }

    /** Starts over from page 1 with {@code source}, dropping what was loaded and anything still running. */
    void load(Source<T> source) {
        this.source = source;
        reload();
    }

    /** {@link #load} again with the same source - after a merge, say. */
    void reload() {
        if (source == null || closed) return;
        cancelAll();
        generation++;
        all.clear();
        keys.clear();
        statuses.clear();
        statusAsked.clear();
        statusQueue.clear();
        statusRunning = 0;
        nextPage = 1;
        hasMore = false;
        failure = null;
        view.getItems().clear();
        placeholder("Loading " + noun + "…");
        fetchNext();
    }

    /** The next page, appended. */
    void loadMore() {
        if (loading || !hasMore || closed) return;
        fetchNext();
    }

    private void fetchNext() {
        Source<T> from = source;
        int page = nextPage;
        long asked = generation;
        loading = true;
        updateFooter();
        tasks.add(GitTaskRunner.queryProvider(() -> from.fetch(page),
                fetched -> {
                    if (closed || asked != generation) return;
                    loading = false;
                    if (fetched.error() != null) {
                        failed(page, fetched.error());
                        return;
                    }
                    nextPage = page + 1;
                    hasMore = fetched.page().hasMore();
                    for (T item : fetched.page().items()) {
                        if (keys.add(key.apply(item))) {
                            all.add(item);
                        }
                    }
                    applyFilter();
                },
                error -> {
                    if (closed || asked != generation) return;
                    loading = false;
                    failed(page, error);
                }));
    }

    private void failed(int page, String error) {
        if (page == 1) {
            failure = "Could not load " + noun + ": " + error;
            placeholder(failure);
            updateFooter();
        } else {
            updateFooter();
            note.setText("Could not load more: " + error);
        }
    }

    /** Shows the loaded items that match the filter, and says how many were searched. */
    void applyFilter() {
        String query = filter.getText();
        boolean filtering = query != null && !query.isBlank();
        // Load more replaces the items; the row the user had picked stays picked.
        T selected = view.getSelectionModel().getSelectedItem();
        if (!filtering) {
            view.getItems().setAll(all);
        } else {
            String needle = query.toLowerCase(Locale.ROOT);
            view.getItems().setAll(all.stream().filter(item -> matches.test(item, needle)).toList());
        }
        if (selected != null && view.getItems().contains(selected)) {
            view.getSelectionModel().select(selected);
        }
        if (source == null) {
            // Still connecting or reading the origin: that placeholder stays.
        } else if (all.isEmpty() && failure != null) {
            placeholder(failure);
        } else if (all.isEmpty() && loading) {
            placeholder("Loading " + noun + "…");
        } else if (filtering) {
            placeholder("No " + noun + " match \"" + query + "\".");
        } else {
            placeholder("No open " + noun + ".");
        }
        updateFooter();
        requestShownStatuses();
    }

    private void updateFooter() {
        loadMore.setVisible(hasMore || (loading && nextPage > 1));
        loadMore.setDisable(loading);
        loadMore.setText(loading && nextPage > 1 ? "Loading…" : "Load more");
        String query = filter.getText();
        if (all.isEmpty()) {
            note.setText("");
        } else if (query != null && !query.isBlank()) {
            note.setText("Filtering " + all.size() + " loaded" + (hasMore ? " - Load more to search further" : ""));
        } else {
            note.setText(hasMore ? "Newest " + all.size() + " open " + noun : all.size() + " open " + noun);
        }
    }

    void placeholder(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        view.setPlaceholder(label);
    }

    // ------------------------------------------------------------------
    // CI status for the rows on screen
    // ------------------------------------------------------------------

    /** Hooks the list's flow once it has a skin, so scrolling asks for the newly shown rows. */
    private void watchFlow() {
        if (view.lookup(".virtual-flow") instanceof VirtualFlow<?> found && found != flow) {
            flow = found;
            // needsLayout turns false at the end of each layout pass - after a scroll, a
            // resize or new items - which is exactly when the shown rows are known.
            found.needsLayoutProperty().addListener((obs, was, needs) -> {
                if (!needs) requestShownStatuses();
            });
        }
    }

    private void requestShownStatuses() {
        if (statusFetch == null || closed) return;
        for (T item : shownItems()) {
            Object k = key.apply(item);
            if (!statuses.containsKey(k) && statusAsked.add(k)) {
                statusQueue.add(item);
            }
        }
        pumpStatuses();
    }

    /** The items whose rows are on screen; the first few when the list has never been laid out. */
    private List<T> shownItems() {
        List<T> items = view.getItems();
        if (items.isEmpty()) return List.of();
        if (flow == null) watchFlow();
        if (flow == null || !isShowing()) {
            // Never laid out on screen, so no row is "shown" and none ever will be until it
            // is: the first few, as a list that opens at the top would show.
            return List.copyOf(items.subList(0, Math.min(UNSHOWN_ROWS, items.size())));
        }
        IndexedCell<?> first = flow.getFirstVisibleCell();
        IndexedCell<?> last = flow.getLastVisibleCell();
        if (first == null || last == null || first.getIndex() < 0) return List.of();
        int from = Math.min(first.getIndex(), items.size());
        int to = Math.min(last.getIndex() + 1, items.size());
        Set<T> shown = new LinkedHashSet<>(items.subList(from, Math.max(from, to)));
        return List.copyOf(shown);
    }

    private boolean isShowing() {
        return view.getScene() != null && view.getScene().getWindow() != null
                && view.getScene().getWindow().isShowing();
    }

    private void pumpStatuses() {
        while (statusRunning < STATUS_IN_FLIGHT && !statusQueue.isEmpty() && !closed) {
            T item = statusQueue.poll();
            Object k = key.apply(item);
            Supplier<BuildStatus> fetch = statusFetch.apply(item);
            long asked = generation;
            statusRunning++;
            tasks.add(GitTaskRunner.queryProvider(fetch,
                    status -> {
                        if (closed || asked != generation) return;
                        statusRunning--;
                        if (status != null) {
                            statuses.put(k, status);
                            view.refresh();
                        }
                        pumpStatuses();
                    },
                    error -> {
                        if (closed || asked != generation) return;
                        statusRunning--;
                        pumpStatuses();
                    }));
        }
    }

    // ------------------------------------------------------------------
    // Lifetime
    // ------------------------------------------------------------------

    /** Another task that should stop with the view. */
    void track(GitTaskRunner.Task task) {
        tasks.add(task);
    }

    /** Stops everything: queued work never starts, running work stops at its next request. */
    void close() {
        closed = true;
        stopWaitingForConnection();
        cancelAll();
        statusQueue.clear();
    }

    boolean isClosed() {
        return closed;
    }

    private void cancelAll() {
        tasks.forEach(GitTaskRunner.Task::cancel);
        tasks.clear();
    }

    /** The filter field, which takes the focus when the view opens. */
    TextField filterField() {
        return filter;
    }

    /** Everything loaded so far, unfiltered. */
    List<T> loaded() {
        return List.copyOf(all);
    }

    boolean hasMore() {
        return hasMore;
    }

    boolean isLoading() {
        return loading;
    }

    String noteText() {
        return note.getText();
    }

    /** The placeholder's text when it is a plain message; null for the connect prompt. */
    String placeholderText() {
        return view.getPlaceholder() instanceof Label label ? label.getText() : null;
    }
}
