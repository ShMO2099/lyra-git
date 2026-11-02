package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.CommitSearch;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * The results of a search over the whole history, as a flat list under the filter bar.
 *
 * <h2>Why a list rather than the graph</h2>
 * The graph's filter only ever sees the rows it has loaded, and a search that reaches the
 * rest would have to load everything between here and the oldest match before it could
 * draw a single lane - which is the cost the paging exists to avoid. sourcegit answers
 * the same question with a flat list, and so does this: the matches are shown at once,
 * and only the one the user picks is paged into the graph ({@link #setOnOpen}).
 *
 * <h2>One search at a time</h2>
 * A new search cancels the one before it. The repository lane is a single thread, so a
 * content search still walking a long history would otherwise hold up both the newer
 * search and every refresh queued behind it. Cancelling is cooperative - the engines stop
 * at their next check - so a result can still arrive from a search that has been replaced;
 * a generation count makes sure it is dropped rather than shown.
 */
public final class HistorySearchPanel extends VBox {

    /**
     * How many matches are shown. A search that finds more says so rather than listing
     * them all: past a few hundred, the useful move is a narrower query, not a longer
     * scroll.
     */
    static final int RESULT_LIMIT = 500;

    private final GitBackend backend;
    private final Label summary = new Label();
    private final ListView<CommitInfo> results = new ListView<>();

    private Consumer<CommitInfo> onOpen = commit -> { };
    private GitTaskRunner.Task running;
    /** Bumped by every search and by closing, so a superseded result is recognised. */
    private long generation;
    private CommitSearch lastQuery = CommitSearch.EMPTY;

    public HistorySearchPanel(GitBackend backend) {
        this.backend = backend;
        getStyleClass().add("history-search");

        summary.getStyleClass().add("history-search-summary");
        summary.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summary, Priority.ALWAYS);

        Button close = Icons.button(Icons.close(), "Close the history search",
                "Close the history search");
        close.getStyleClass().addAll("btn-sm", "row-action");
        close.setOnAction(e -> close());

        HBox header = new HBox(6, summary, close);
        header.setAlignment(Pos.CENTER_LEFT);

        results.getStyleClass().add("history-search-results");
        results.setCellFactory(list -> new ResultCell());
        results.setPrefHeight(200);
        results.setMaxHeight(240);
        results.setPlaceholder(new Label(""));
        // Following the selection rather than a click means the keyboard works too:
        // arrowing down the list walks the graph and the detail panel along with it.
        results.getSelectionModel().selectedItemProperty().addListener((obs, old, commit) -> {
            if (commit != null) onOpen.accept(commit);
        });

        getChildren().addAll(header, results);
        setVisible(false);
        setManaged(false);
    }

    /** What to do with a result the user picks: select it in the graph, loading as needed. */
    public void setOnOpen(Consumer<CommitInfo> onOpen) {
        this.onOpen = onOpen == null ? commit -> { } : onOpen;
    }

    /**
     * Starts a search, replacing any still running, and shows the panel.
     *
     * <p>An empty query closes the panel instead: there is nothing to look for, and
     * "0 results" would read as a statement about the repository.
     */
    public void search(CommitSearch query) {
        if (query == null || query.isEmpty()) {
            close();
            return;
        }
        cancelRunning();
        long mine = ++generation;
        lastQuery = query;
        results.getItems().clear();
        summary.setText("Searching all history for " + query.describe() + "…");
        setVisible(true);
        setManaged(true);
        // One more than is shown, so a capped search can be told from one that found
        // exactly the limit.
        running = GitTaskRunner.query(backend,
                () -> backend.searchCommits(query, RESULT_LIMIT + 1),
                found -> {
                    if (mine != generation) return;
                    running = null;
                    show(found);
                },
                error -> {
                    if (mine != generation) return;
                    running = null;
                    summary.setText("Search failed: " + error);
                });
    }

    /** Hides the panel and abandons any search still running. */
    public void close() {
        cancelRunning();
        generation++;
        results.getItems().clear();
        summary.setText("");
        lastQuery = CommitSearch.EMPTY;
        setVisible(false);
        setManaged(false);
    }

    /** Whether a search is running whose answer has not arrived. */
    public boolean isSearching() {
        return running != null;
    }

    /** The results header, e.g. "12 results" - for tests and for the tab's status. */
    public String summaryText() {
        return summary.getText();
    }

    /** The results list, for tests to click. */
    ListView<CommitInfo> resultsList() {
        return results;
    }

    private void cancelRunning() {
        if (running != null) {
            running.cancel();
            running = null;
        }
    }

    private void show(List<CommitInfo> found) {
        boolean capped = found.size() > RESULT_LIMIT;
        List<CommitInfo> shown = capped ? found.subList(0, RESULT_LIMIT) : found;
        results.getItems().setAll(shown);
        summary.setText(summaryFor(shown.size(), capped) + " — " + lastQuery.describe());
    }

    /** "No commits match", "1 result", "N results (limit reached)". */
    static String summaryFor(int count, boolean capped) {
        if (count == 0) return "No commits match";
        String text = count + (count == 1 ? " result" : " results");
        return capped ? text + " (limit reached)" : text;
    }

    /** One match: short hash, subject, author and age, on one line like a graph row. */
    private static final class ResultCell extends ListCell<CommitInfo> {
        private final Label hash = new Label();
        private final Label subject = new Label();
        private final Label author = new Label();
        private final Label age = new Label();
        private final HBox row = new HBox(8, hash, subject, author, age);

        ResultCell() {
            hash.getStyleClass().addAll("t-mono", "history-search-hash");
            subject.getStyleClass().add("history-search-subject");
            author.getStyleClass().add("t-caption");
            age.getStyleClass().add("t-caption");
            subject.setMaxWidth(Double.MAX_VALUE);
            subject.setMinWidth(0);
            HBox.setHgrow(subject, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(CommitInfo commit, boolean empty) {
            super.updateItem(commit, empty);
            if (empty || commit == null) {
                setGraphic(null);
                setText(null);
                setTooltip(null);
                return;
            }
            hash.setText(commit.getShortHash());
            subject.setText(commit.getSubject());
            author.setText(commit.getAuthorName());
            age.setText(DateDisplay.compact(commit.getAuthorDate()));
            setText(null);
            setGraphic(row);
            setTooltip(new Tooltip(commit.getHash() + "\n" + commit.getAuthorName()
                    + " <" + commit.getAuthorEmail() + ">  "
                    + DateDisplay.absolute(commit.getAuthorDate())));
        }
    }
}
