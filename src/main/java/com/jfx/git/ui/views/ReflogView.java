package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ReflogItem;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.WorkingTreeGuard;
import com.jfx.git.ui.dialogs.JfxDialog;
import com.jfx.git.ui.dialogs.UndoPreviewDialog;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Every move this branch has made, and a way back to any of them - shown in the
 * repository's tab in place of the graph.
 *
 * <p>It was a modal dialog, which made the history it lists something to look at instead
 * of something to work from: nothing else in the repository could be touched while it was
 * open. As a view the toolbar stays live, so it has a Refresh for when Undo or a pull
 * there has moved the branch since it was read.
 */
public final class ReflogView implements RepoView {

    /** The restore button's id, for the tests that drive it. */
    public static final String RESTORE_ID = "reflog-restore";
    /** The list's id. */
    public static final String ENTRIES_ID = "reflog-entries";

    private static final int LIMIT = 200;

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs a restore, which refreshes the tab it is open in. */
    private final OperationRunner operations;

    private final ListView<ReflogItem> entries = new ListView<>();
    private final Label caption = new Label();
    private final Button restore = new Button("Restore branch to this point");
    private final Button refresh = new Button("Refresh");
    private final VBox content;

    /**
     * The branch, as last read with the history. Read on the lane with the entries rather
     * than in the constructor, which runs on the FX thread.
     */
    private String branch = "this branch";

    public ReflogView(OperationRunner operations) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();

        entries.setId(ENTRIES_ID);
        entries.setCellFactory(view -> new EntryCell());

        restore.setId(RESTORE_ID);
        restore.getStyleClass().add("btn-primary");
        restore.setDisable(true);
        restore.setOnAction(e -> confirmRestore());
        entries.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, item) -> restore.setDisable(item == null));

        refresh.getStyleClass().add("btn-ghost");
        refresh.setOnAction(e -> load());

        // The shared browser body, without its own padding: the view frame already has
        // the body inset, and the two together left a gutter twice the width of any other
        // screen's.
        content = JfxDialog.browser(null, null, entries, restore);
        content.setPadding(Insets.EMPTY);
        caption.getStyleClass().add("t-caption");
        caption.setWrapText(true);
        content.getChildren().addFirst(caption);
        describe();

        load();
    }

    @Override
    public String title() {
        return "Branch history";
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public List<Node> actions() {
        return List.of(refresh);
    }

    /** The reflog and the branch it belongs to, read together. */
    private record Loaded(List<ReflogItem> items, String branch) {}

    private void load() {
        entries.setPlaceholder(new Label("Reading history…"));
        GitTaskRunner.query(gitService,
                () -> new Loaded(gitService.getReflog(LIMIT), gitService.getCurrentBranch()),
                loaded -> {
                    String current = loaded.branch();
                    branch = current == null || current.isBlank() ? "this branch" : current;
                    describe();
                    entries.getItems().setAll(loaded.items());
                    entries.setPlaceholder(new Label("This branch has no recorded history yet."));
                },
                error -> {
                    entries.setPlaceholder(new Label("Could not read the history."));
                    statusBar.error(error);
                });
    }

    /** The heading names the branch, since the view's title cannot until the read is back. */
    private void describe() {
        caption.setText("Every move " + branch + " has made, most recent first."
                + " Restoring discards anything after the point you choose.");
    }

    /**
     * Confirms before moving the branch - unless the move would be refused anyway.
     *
     * <p>Restoring is a hard reset, which {@link RepositoryCommands#restoreTo} refuses while there
     * are uncommitted changes. That is checked first, the way the toolbar's Undo checks
     * it, so a dirty tree is told what to do rather than asked a question whose "OK" does
     * nothing.
     *
     * <p>Past that, the confirmation is the toolbar's undo preview: the commits leaving the
     * branch, the ones coming back, and the files that change on disk - rather than a
     * generic "are you sure" the reader learns to click through. The same reset deserves
     * the same answer wherever it is asked for, and an entry newer than HEAD (the tip, after
     * restoring an older one) is a move forward that the preview describes as one. It stays
     * a dialog: it is a question with an answer, not a place.
     */
    private void confirmRestore() {
        ReflogItem item = entries.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }

        String target = branch + " to " + item.shortNewHash();
        WorkingTreeGuard.whenClean(operations, "Restoring " + target,
                () -> UndoPreviewDialog.confirm(gitService, UndoPreviewDialog.Origin.BRANCH_HISTORY,
                        "Restore " + target + " (" + item.operation() + ")?",
                        item.restoreTarget(),
                        confirmed -> {
                            if (confirmed) restore(item);
                        }));
    }

    private void restore(ReflogItem item) {
        operations.operation("Restoring to " + item.shortNewHash(), OperationRunner.Refresh.EVERYTHING)
                .afterwards(result -> {
                    if (result.isOk()) load(); // the restore is itself a move worth showing
                })
                .run(() -> new RepositoryCommands(gitService).restoreTo(item.restoreTarget(), item.operation()));
    }

    /** Selector, what happened, and when - with rewrites called out. */
    private static final class EntryCell extends ListCell<ReflogItem> {

        private final Label selector = new Label();
        private final Label comment = new Label();
        private final Label ids = new Label();
        private final Label age = new Label();
        private final HBox row;

        EntryCell() {
            selector.getStyleClass().add("reflog-selector");
            selector.setMinWidth(72);
            comment.getStyleClass().add("reflog-comment");
            ids.getStyleClass().add("reflog-ids");
            age.getStyleClass().add("reflog-age");

            HBox.setHgrow(comment, Priority.ALWAYS);
            row = new HBox(10, selector, comment, ids, age);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(ReflogItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            selector.setText(item.selector());
            comment.setText(item.comment() == null ? item.operation() : item.comment());
            ids.setText(item.shortOldHash() + " → " + item.shortNewHash());
            age.setText(com.jfx.git.ui.DateDisplay.of(item.when(), com.jfx.git.ui.DateDisplay.Style.SHORT));

            // The entries worth finding are the ones that rewrote history; everything
            // else is an ordinary commit the user can already see in the graph.
            row.getStyleClass().removeAll("reflog-rewrite");
            if (item.isHistoryRewrite()) {
                row.getStyleClass().add("reflog-rewrite");
            }
            setGraphic(row);
        }
    }

    /** The rows as last read; for tests. */
    public List<ReflogItem> loadedEntries() {
        return List.copyOf(entries.getItems());
    }
}
