package com.jfx.git.ui;

import com.jfx.git.core.ActivityLog;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The activity log: what the application did, in order, with how long each thing took.
 *
 * <h2>A tab, not a window</h2>
 * It was a non-modal window, so that it could be watched while doing something else. A
 * tab does that better: it stays where it was put, beside the repositories, and switching
 * to it and back is a keystroke rather than a hunt through the taskbar.
 *
 * <h2>Two views, because there are two questions</h2>
 * "Why is this repository in that state?" is answered by the repository log, and mixing
 * six open tabs' operations into it answers nothing. "Why did the application do that?"
 * is answered by the application log - startup, opens, closes - which is a much shorter
 * list nobody wants to hunt for among four hundred stage-and-commit lines. The two are
 * separate views over one {@link ActivityLog}, filtered by scope. The repository view
 * follows the repository the log was last asked for from: there is one log tab, and
 * asking for it from another repository's tab points it there.
 *
 * <h2>Two rows per operation</h2>
 * Each timed operation renders as the pair a duration is actually made of - a "started"
 * line and a "finished" line carrying the elapsed time. An operation still running shows
 * only its first line, which is precisely the state worth being able to see: a log where
 * the last thing says "started" and nothing follows is how a hang looks.
 */
public final class ActivityLogTab extends ToolTab {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withLocale(Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    /**
     * One rendered line.
     *
     * @param outcome the entry's verdict, or {@link ActivityLog.Outcome#RUNNING} for a
     *                "started" line, which has no verdict yet however the work ended
     */
    private record Row(String time, String text, String duration, ActivityLog.Outcome outcome) {
        /** Everything on the line, for the clipboard. */
        String flat() {
            return time + "  " + text + (duration.isEmpty() ? "" : "  " + duration);
        }
    }

    private final TextField filter = new TextField();
    private final CheckBox problemsOnly = new CheckBox("Problems only");
    private final LogView application = new LogView(ActivityLog.Scope.APPLICATION, null);
    private final LogView repositoryView;
    private final Tab applicationTab;
    private final Tab repositoryTab;
    private final TabPane views;
    private final Runnable unsubscribe;

    /**
     * @param repository the repository whose log to show beside the application's - the
     *                   active tab's, or null with none open
     */
    public ActivityLogTab(String repository) {
        super("Activity log");
        repositoryView = new LogView(ActivityLog.Scope.REPOSITORY, repository);

        filter.setPromptText("Filter the log…");
        filter.getStyleClass().add("file-filter-field");
        HBox.setHgrow(filter, Priority.ALWAYS);

        // Failures are what anyone opens a log for, and they are one line in three
        // hundred. Everything else is context you want available, not shown by default -
        // hence a toggle rather than two lists.
        problemsOnly.setTooltip(new javafx.scene.control.Tooltip(
                "Show only operations that failed or reported a conflict"));

        applicationTab = new Tab("Application", application.node);
        applicationTab.setClosable(false);
        repositoryTab = new Tab(repositoryTitle(repository), repositoryView.node);
        repositoryTab.setClosable(false);

        views = new TabPane(applicationTab, repositoryTab);
        views.getStyleClass().add("activity-log-tabs");
        // The repository log is the one with something in it during ordinary use.
        views.getSelectionModel().select(repository == null ? applicationTab : repositoryTab);
        VBox.setVgrow(views, Priority.ALWAYS);

        filter.textProperty().addListener((o, was, now) -> rerender());
        problemsOnly.selectedProperty().addListener((o, was, now) -> rerender());

        Button copy = new Button("Copy");
        copy.setTooltip(new javafx.scene.control.Tooltip(
                "Copy the visible lines, ready to paste into a bug report"));
        copy.setOnAction(e -> {
            LogView visible = views.getSelectionModel().getSelectedItem() == applicationTab
                    ? application : repositoryView;
            ClipboardContent content = new ClipboardContent();
            content.putString(visible.asText());
            Clipboard.getSystemClipboard().setContent(content);
        });

        Button clear = new Button("Clear");
        clear.setTooltip(new javafx.scene.control.Tooltip("Discard every recorded entry"));
        clear.setOnAction(e -> ActivityLog.clear());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(UiScale.STEP, filter, problemsOnly, spacer, copy, clear);
        bar.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(UiScale.GAP, bar, views);
        content.setPadding(new Insets(UiScale.EDGE));
        content.getStyleClass().add("activity-log-root");

        // The log is written from worker threads, so every repaint has to be marshalled.
        // Coalesced through a flag rather than posting one runLater per entry: a clone
        // logs faster than the toolkit can lay a list out, and a queue of a thousand
        // identical repaints is how a diagnostic view freezes the thing it is diagnosing.
        boolean[] repaintPending = {false};
        unsubscribe = ActivityLog.addListener(() -> {
            synchronized (repaintPending) {
                if (repaintPending[0]) return;
                repaintPending[0] = true;
            }
            Platform.runLater(() -> {
                synchronized (repaintPending) {
                    repaintPending[0] = false;
                }
                rerender();
            });
        });

        setContent(content);
        rerender();
    }

    /**
     * Points the repository view at {@code repository} and brings it forward; asked for
     * again from another repository's tab, the log follows. A null - asked for with no
     * repository in front - leaves it where it was.
     */
    public void showRepository(String repository) {
        if (repository == null) return;
        repositoryView.repository = repository;
        repositoryTab.setText(repositoryTitle(repository));
        views.getSelectionModel().select(repositoryTab);
        rerender();
    }

    /** The repository the repository view shows, or null. */
    public String repository() {
        return repositoryView.repository;
    }

    private static String repositoryTitle(String repository) {
        return repository == null ? "Repository" : "Repository — " + repository;
    }

    private void rerender() {
        String query = filter.getText();
        boolean onlyProblems = problemsOnly.isSelected();
        application.render(query, onlyProblems);
        repositoryView.render(query, onlyProblems);
    }

    /**
     * Stops listening to the log. The listener list is static, so without this the tab and
     * everything it references would stay reachable for the life of the process.
     */
    @Override
    public void dispose() {
        unsubscribe.run();
    }

    /** One scope's list, and the rendering that fills it. */
    private static final class LogView {
        private final ActivityLog.Scope scope;
        /** Which repository's entries; changes when the log is asked for from another repository. */
        private String repository;
        private final ListView<Row> list = new ListView<>();
        private final Label empty = new Label();
        private final VBox node;

        LogView(ActivityLog.Scope scope, String repository) {
            this.scope = scope;
            this.repository = repository;
            list.getStyleClass().add("activity-log-list");
            list.setCellFactory(lv -> new RowCell());
            list.setFixedCellSize(UiScale.ROW_HEIGHT);
            VBox.setVgrow(list, Priority.ALWAYS);

            empty.getStyleClass().add("t-caption");
            empty.setVisible(false);
            empty.setManaged(false);

            node = new VBox(UiScale.SNUG, empty, list);
        }

        void render(String query, boolean problemsOnly) {
            // Scroll position is preserved only while the user is not at the bottom: a
            // log that jumps to the newest line as you read the middle of it is unusable,
            // and one that does not follow the newest line while you watch it is useless.
            boolean atBottom = isPinnedToBottom();

            List<Row> rows = new ArrayList<>();
            for (ActivityLog.Entry entry : ActivityLog.entries(scope, repository)) {
                if (problemsOnly && entry.outcome() != ActivityLog.Outcome.FAILED
                        && entry.outcome() != ActivityLog.Outcome.WARNING) {
                    continue;
                }
                for (Row row : rowsFor(entry)) {
                    if (matches(row, query)) rows.add(row);
                }
            }

            list.getItems().setAll(rows);
            boolean nothing = rows.isEmpty();
            empty.setText(query != null && !query.isBlank()
                    ? "Nothing in this log matches \"" + query.strip() + "\"."
                    : "Nothing recorded here yet.");
            empty.setVisible(nothing);
            empty.setManaged(nothing);

            if (atBottom && !rows.isEmpty()) {
                list.scrollTo(rows.size() - 1);
            }
        }

        /**
         * Whether the newest line is on screen.
         *
         * <p>Read before the items are replaced, from the last visible index the skin
         * reports. An unrealised list (never laid out, so nothing is visible yet) counts
         * as pinned, which is what a freshly opened log should do.
         */
        private boolean isPinnedToBottom() {
            if (list.getItems().isEmpty()) return true;
            // Every scroll bar the skin made, filtered by orientation rather than asked
            // for with a pseudo-class selector: a horizontal one is present too, and
            // which of them a selector returns first is not something to rely on.
            for (javafx.scene.Node node : list.lookupAll(".scroll-bar")) {
                if (node instanceof javafx.scene.control.ScrollBar scrollBar
                        && scrollBar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                    return scrollBar.getValue() >= scrollBar.getMax() - 0.001;
                }
            }
            return true;
        }

        String asText() {
            StringBuilder sb = new StringBuilder();
            for (Row row : list.getItems()) {
                sb.append(row.flat()).append(System.lineSeparator());
            }
            return sb.toString();
        }

        private static boolean matches(Row row, String query) {
            if (query == null || query.isBlank()) return true;
            return row.text().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * The one or two lines an entry becomes.
     *
     * <p>A note is a single line - it happened at an instant, and pretending it started
     * and finished would invent a duration. Timed work gets both, so the log reads the
     * way the operations actually ran.
     */
    private static List<Row> rowsFor(ActivityLog.Entry entry) {
        String time = CLOCK.format(entry.startedAt());
        if (!entry.isTimed()) {
            if (entry.isRunning()) {
                return List.of(new Row(time, entry.label() + ": started.", "",
                        ActivityLog.Outcome.RUNNING));
            }
            return List.of(new Row(time, entry.label(), "", entry.outcome()));
        }
        String ended = switch (entry.outcome()) {
            case FAILED -> ": failed.";
            case WARNING -> ": finished with conflicts.";
            default -> ": finished.";
        };
        String detail = entry.detail() == null || entry.detail().isBlank()
                ? "" : "  " + entry.detail();
        return List.of(
                new Row(time, entry.label() + ": started.", "", ActivityLog.Outcome.RUNNING),
                new Row(CLOCK.format(entry.startedAt().plusMillis(entry.millis())),
                        entry.label() + ended + detail,
                        entry.millis() + "ms", entry.outcome()));
    }

    /** Timestamp, then the line, then the duration pushed to the far edge. */
    private static final class RowCell extends ListCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label time = new Label(row.time());
            time.getStyleClass().add("activity-log-time");

            Label text = new Label(row.text());
            text.getStyleClass().addAll("activity-log-text",
                    switch (row.outcome()) {
                        case FAILED -> "activity-log-failed";
                        case WARNING -> "activity-log-warned";
                        default -> "activity-log-ok";
                    });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label duration = new Label(row.duration());
            duration.getStyleClass().add("activity-log-duration");

            HBox line = new HBox(UiScale.STEP, time, text, spacer, duration);
            line.setAlignment(Pos.CENTER_LEFT);
            setGraphic(line);
        }
    }
}
