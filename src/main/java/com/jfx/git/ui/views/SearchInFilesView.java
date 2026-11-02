package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.TextSearch;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.GrepMatch;
import com.jfx.git.model.GrepQuery;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Search in files (Ctrl+Shift+F): every line in the repository that matches, grouped by
 * file, in the repository's tab - {@code git grep}, read the way the find bar reads a
 * query.
 *
 * <h2>Where it looks</h2>
 * The working tree by default, untracked files included and ignored ones not - what is on
 * disk now, which is what someone looking for a string usually means. Or HEAD, or the
 * commit selected in the graph: the code as it was, without checking anything out.
 *
 * <h2>One search at a time</h2>
 * As in {@code HistorySearchPanel}: a new search cancels the one still running, and a
 * generation count drops an answer from a search that has since been replaced - the
 * engines stop at their next check, so a stale answer can still arrive.
 *
 * <p>Enter on a result opens the file at that line; on a file, at its first match.
 */
public final class SearchInFilesView implements RepoView {

    /** More matches than this are not listed: past it, a narrower query is the useful move. */
    public static final int RESULT_LIMIT = 2000;

    /** Which tree is searched. */
    public enum Scope {
        WORKING_TREE("Working tree"),
        HEAD("HEAD"),
        SELECTED_COMMIT("Selected commit");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Opens a result: {@code revOrNull} null for the working tree. */
    @FunctionalInterface
    public interface Opener {
        void open(String revOrNull, String path, int line);
    }

    /**
     * One row of the results: a file, with how many of its lines matched ({@code line} 0),
     * or one matching line of it.
     */
    public record Hit(String path, int line, String text, int count) {
        public boolean isFile() {
            return line == 0;
        }
    }

    private final GitBackend backend;
    private final Supplier<CommitInfo> selectedCommit;
    private final Opener opener;

    private final TextField query = new TextField();
    private final ToggleButton matchCase = toggle("Aa", "Match case");
    private final ToggleButton wholeWord = toggle("Word", "Whole words only");
    private final ToggleButton regex = toggle(".*", "Regular expression");
    private final TextField paths = new TextField();
    private final ComboBox<Scope> scope = new ComboBox<>();
    private final Label summary = new Label();
    private final TreeView<Hit> results = new TreeView<>();
    private final VBox content;

    private GitTaskRunner.Task running;
    /** Bumped by every search and by closing, so a superseded answer is recognised. */
    private long generation;
    /** The revision the results on screen came from; null for the working tree. */
    private String shownRevision;

    /**
     * @param selectedCommit the commit selected in the graph, for the "Selected commit"
     *                       scope; null when there is none
     * @param opener         opens a result's file at its line
     */
    public SearchInFilesView(GitBackend backend, Supplier<CommitInfo> selectedCommit, Opener opener) {
        this.backend = backend;
        this.selectedCommit = selectedCommit == null ? () -> null : selectedCommit;
        this.opener = opener == null ? (rev, path, line) -> { } : opener;

        query.setPromptText("Search in files");
        query.getStyleClass().add("search-in-files-query");
        query.setOnAction(e -> search());
        HBox.setHgrow(query, Priority.ALWAYS);
        Button go = new Button("Search");
        go.getStyleClass().add("btn-primary");
        go.setOnAction(e -> search());
        for (ToggleButton option : List.of(matchCase, wholeWord, regex)) {
            option.selectedProperty().addListener((obs, was, now) -> searchAgain());
        }
        HBox queryRow = new HBox(UiScale.SNUG, query, matchCase, wholeWord, regex, go);
        queryRow.setAlignment(Pos.CENTER_LEFT);

        paths.setPromptText("Files to include, e.g. *.java, src, !*.min.js");
        paths.setTooltip(new Tooltip("Comma-separated paths or patterns; a leading ! leaves matching files out"));
        paths.setOnAction(e -> search());
        HBox.setHgrow(paths, Priority.ALWAYS);
        scope.getItems().setAll(Scope.values());
        scope.setValue(Scope.WORKING_TREE);
        scope.setTooltip(new Tooltip("Search the files on disk, the last commit, or the commit selected in the graph"));
        scope.valueProperty().addListener((obs, was, now) -> searchAgain());
        Label in = new Label("In");
        HBox filterRow = new HBox(UiScale.SNUG, paths, in, scope);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        summary.getStyleClass().add("t-caption");
        summary.setWrapText(true);
        summary.setMinHeight(Region.USE_PREF_SIZE);

        results.setShowRoot(false);
        results.getStyleClass().add("search-in-files-results");
        results.setCellFactory(tree -> new HitCell());
        results.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
                e.consume();
            }
        });
        results.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) openSelected();
        });
        VBox.setVgrow(results, Priority.ALWAYS);

        content = new VBox(UiScale.STEP, queryRow, filterRow, summary, results);
        content.getStyleClass().add("search-in-files");
    }

    @Override
    public String title() {
        return "Search in files";
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public Node focusTarget() {
        return query;
    }

    /** F5: the same search again, for a working tree that has changed since. */
    @Override
    public boolean refreshView() {
        search();
        return true;
    }

    /** Stops a search still running; the results stay for when the view is opened again. */
    @Override
    public void closed() {
        cancelRunning();
        generation++;
    }

    /** Puts {@code text} in the query field and selects it, ready to search or replace. */
    public void setQuery(String text) {
        query.setText(text == null ? "" : text);
        query.selectAll();
    }

    /** Runs the query in the field, replacing any search still running. */
    public void search() {
        cancelRunning();
        long mine = ++generation;
        String text = query.getText() == null ? "" : query.getText();
        if (text.isEmpty()) {
            results.setRoot(null);
            summary.setText("");
            return;
        }
        TextSearch.Options options = new TextSearch.Options(
                matchCase.isSelected(), wholeWord.isSelected(), regex.isSelected());
        String problem = TextSearch.find("", text, options).error();
        if (problem != null) {
            summary.setText(problem);
            return;
        }
        String revision;
        String where;
        switch (scope.getValue() == null ? Scope.WORKING_TREE : scope.getValue()) {
            case HEAD -> {
                revision = "HEAD";
                where = "HEAD";
            }
            case SELECTED_COMMIT -> {
                CommitInfo commit = selectedCommit.get();
                if (commit == null || commit.getHash() == null) {
                    summary.setText("Select a commit in the graph to search it.");
                    return;
                }
                revision = commit.getHash();
                where = commit.getShortHash();
            }
            default -> {
                revision = null;
                where = "the working tree";
            }
        }
        GrepQuery grep = new GrepQuery(text, options.matchCase(), options.wholeWord(), options.regex(),
                paths.getText());
        summary.setText("Searching " + where + "…");
        // One more than is shown, so a capped search can be told from one that found
        // exactly the limit.
        running = GitTaskRunner.query(backend,
                () -> backend.grep(revision, grep, RESULT_LIMIT + 1),
                found -> {
                    if (mine != generation) return;
                    running = null;
                    show(revision, found);
                },
                error -> {
                    if (mine != generation) return;
                    running = null;
                    summary.setText("Search failed: " + error);
                });
    }

    /** A changed option or scope searches again - but only once there is something to search for. */
    private void searchAgain() {
        if (query.getText() != null && !query.getText().isEmpty()) search();
    }

    private void show(String revision, List<GrepMatch> found) {
        shownRevision = revision;
        boolean capped = found.size() > RESULT_LIMIT;
        List<GrepMatch> shown = capped ? found.subList(0, RESULT_LIMIT) : found;
        Map<String, List<GrepMatch>> byFile = new LinkedHashMap<>();
        for (GrepMatch match : shown) {
            byFile.computeIfAbsent(match.path(), path -> new java.util.ArrayList<>()).add(match);
        }
        TreeItem<Hit> root = new TreeItem<>(new Hit("", 0, "", shown.size()));
        for (Map.Entry<String, List<GrepMatch>> file : byFile.entrySet()) {
            TreeItem<Hit> fileItem = new TreeItem<>(new Hit(file.getKey(), 0, "", file.getValue().size()));
            for (GrepMatch match : file.getValue()) {
                fileItem.getChildren().add(new TreeItem<>(new Hit(match.path(), match.line(), match.text(), 1)));
            }
            fileItem.setExpanded(true);
            root.getChildren().add(fileItem);
        }
        root.setExpanded(true);
        results.setRoot(root);
        summary.setText(summaryFor(shown.size(), byFile.size(), capped));
        if (!root.getChildren().isEmpty()) {
            results.getSelectionModel().select(root.getChildren().get(0));
        }
    }

    /** "No matches", "1 match in 1 file", "Showing the first 2000 matches, in 40 files…". */
    static String summaryFor(int matches, int files, boolean capped) {
        if (matches == 0) return "No matches";
        String inFiles = files + (files == 1 ? " file" : " files");
        if (capped) {
            return "Showing the first " + matches + " matches, in " + inFiles
                    + ". Narrow the search to see the rest.";
        }
        return matches + (matches == 1 ? " match in " : " matches in ") + inFiles;
    }

    /** Opens the selected line - or, on a file, its first match. */
    private void openSelected() {
        TreeItem<Hit> item = results.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null) return;
        Hit hit = item.getValue();
        int line = hit.line();
        if (hit.isFile()) {
            if (item.getChildren().isEmpty()) return;
            line = item.getChildren().get(0).getValue().line();
        }
        opener.open(shownRevision, hit.path(), line);
    }

    private void cancelRunning() {
        if (running != null) {
            running.cancel();
            running = null;
        }
    }

    // ------------------------------------------------------------------
    // For tests
    // ------------------------------------------------------------------

    TextField queryField() {
        return query;
    }

    TextField pathsField() {
        return paths;
    }

    ComboBox<Scope> scopeBox() {
        return scope;
    }

    ToggleButton matchCaseToggle() {
        return matchCase;
    }

    TreeView<Hit> resultsTree() {
        return results;
    }

    String summaryText() {
        return summary.getText();
    }

    boolean isSearching() {
        return running != null;
    }

    private static ToggleButton toggle(String text, String tooltip) {
        ToggleButton toggle = new ToggleButton(text);
        // The find bar's options, so the two read as the same three choices.
        toggle.getStyleClass().addAll("btn-sm", "find-option");
        toggle.setTooltip(new Tooltip(tooltip));
        toggle.setAccessibleText(tooltip);
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        return toggle;
    }

    /** A file - its path and how many lines matched - or one line: its number and text. */
    private static final class HitCell extends TreeCell<Hit> {
        private final Label primary = new Label();
        private final Label secondary = new Label();
        private final HBox row = new HBox(UiScale.STEP, primary, secondary);

        HitCell() {
            row.setAlignment(Pos.CENTER_LEFT);
            secondary.setMinWidth(0);
            HBox.setHgrow(secondary, Priority.ALWAYS);
        }

        @Override
        protected void updateItem(Hit hit, boolean empty) {
            super.updateItem(hit, empty);
            if (empty || hit == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            primary.getStyleClass().setAll("label", hit.isFile() ? "search-in-files-path" : "t-mono");
            secondary.getStyleClass().setAll("label", hit.isFile() ? "t-caption" : "search-in-files-line");
            if (hit.isFile()) {
                primary.setText(hit.path());
                secondary.setText(hit.count() == 1 ? "1 match" : hit.count() + " matches");
            } else {
                primary.setText(String.valueOf(hit.line()));
                String text = hit.text().strip();
                secondary.setText(text.length() > 400 ? text.substring(0, 400) + "…" : text);
            }
            setText(null);
            setGraphic(row);
        }
    }
}
