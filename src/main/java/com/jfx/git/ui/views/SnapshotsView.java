package com.jfx.git.ui.views;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.HistoryOps;
import com.jfx.git.core.SnapshotComparison;
import com.jfx.git.model.Snapshot;
import com.jfx.git.ui.DiffKind;
import com.jfx.git.ui.DiffViewer;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.RepoViewHost;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.SnapshotGuard;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.UiScale;
import com.jfx.git.ui.dialogs.Dialogs;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

/**
 * The working-tree snapshots kept for this repository, and a way back to any of them -
 * shown in the repository's tab in place of the graph. See
 * {@link com.jfx.git.core.WorkingTreeSnapshots} for when they are taken.
 *
 * <p>A snapshot is read against the working tree as it is now, not against the commit it
 * was taken on: "what would restoring this give me back?" is the question someone asks
 * here, usually just after a discard they regret.
 */
public final class SnapshotsView implements RepoView {

    /** The snapshot list's id, for tests. */
    public static final String LIST_ID = "snapshot-list";
    /** The changed-file list's id. */
    public static final String FILES_ID = "snapshot-files";
    public static final String RESTORE_FILE_ID = "snapshot-restore-file";
    public static final String RESTORE_ALL_ID = "snapshot-restore-all";
    public static final String DELETE_ID = "snapshot-delete";

    private static final DateTimeFormatter EXACT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault());

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs restores, which refresh the tab, and deletes, which change nothing it shows. */
    private final OperationRunner operations;

    private final ListView<Snapshot> snapshots = new ListView<>();
    private final ListView<HistoryOps.FileChange> files = new ListView<>();
    private final DiffViewer diffViewer = new DiffViewer();
    private final Label caption = new Label();
    private final Label filesCaption = new Label();
    private final Button restoreFile = new Button("Restore file");
    private final Button restoreAll = new Button("Restore all…");
    private final Button delete = new Button("Delete…");
    private final Button refresh = new Button("Refresh");
    private final SplitPane content;

    /** The working tree's copy of the file on show, for the viewer's whole-file view. */
    private byte[] shownNow;
    private String shownPath;
    private boolean closed;

    /** Opens the view in {@code host}'s tab, or brings it forward when it is already open. */
    public static void open(RepoViewHost host, OperationRunner operations) {
        host.open(SnapshotsView.class, () -> new SnapshotsView(operations));
    }

    public SnapshotsView(OperationRunner operations) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();

        snapshots.setId(LIST_ID);
        snapshots.setCellFactory(list -> new SnapshotCell());
        snapshots.setPlaceholder(new Label("Reading snapshots…"));
        snapshots.getSelectionModel().selectedItemProperty().addListener((obs, old, picked) -> showSnapshot(picked));

        files.setId(FILES_ID);
        files.setCellFactory(list -> new FileCell());
        files.setPlaceholder(new Label("Pick a snapshot to compare it with your files."));
        files.setPrefHeight(180);
        files.getSelectionModel().selectedItemProperty().addListener((obs, old, change) -> showFile(change));

        restoreFile.setId(RESTORE_FILE_ID);
        restoreFile.setDisable(true);
        restoreFile.setTooltip(new Tooltip("Put this file back as the snapshot has it"));
        restoreFile.setOnAction(e -> restoreSelectedFile());
        restoreAll.setId(RESTORE_ALL_ID);
        restoreAll.getStyleClass().add("btn-primary");
        restoreAll.setDisable(true);
        restoreAll.setOnAction(e -> confirmRestoreAll());
        delete.setId(DELETE_ID);
        delete.setDisable(true);
        delete.setOnAction(e -> deleteSelected());
        refresh.getStyleClass().add("btn-ghost");
        refresh.setOnAction(e -> load());

        caption.getStyleClass().add("t-caption");
        caption.setWrapText(true);
        int minutes = AppPreferences.snapshotIntervalMinutes();
        caption.setText((minutes > 0
                ? "Copies of your uncommitted work, taken every " + minutes + " minutes"
                : "Copies of your uncommitted work")
                + " and just before anything that discards it. Untracked files are included;"
                + " the index and the stash are never touched.");

        HBox snapshotActions = new HBox(UiScale.STEP, delete, spacer(), restoreAll);
        snapshotActions.setAlignment(Pos.CENTER_RIGHT);
        VBox left = new VBox(UiScale.GAP, caption, snapshots, snapshotActions);
        VBox.setVgrow(snapshots, Priority.ALWAYS);
        left.setMinWidth(0);

        filesCaption.getStyleClass().add("t-caption");
        filesCaption.setText("Differences from your files now");
        HBox fileActions = new HBox(UiScale.STEP, filesCaption, spacer(), restoreFile);
        fileActions.setAlignment(Pos.CENTER_LEFT);
        diffViewer.setEmbedded(true);
        diffViewer.setFileContentSupplier(path -> path.equals(shownPath) ? shownNow : null);
        diffViewer.setReaderRepository(gitService);
        VBox right = new VBox(UiScale.GAP, fileActions, files, diffViewer);
        VBox.setVgrow(diffViewer, Priority.ALWAYS);
        right.setMinWidth(0);

        content = new SplitPane(left, right);
        content.setOrientation(Orientation.HORIZONTAL);
        SplitPane.setResizableWithParent(left, false);
        left.setPrefWidth(340);
        content.widthProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Number> obs,
                                Number oldWidth, Number newWidth) {
                if (newWidth.doubleValue() > 0) {
                    content.setDividerPositions(Math.min(0.5, 340.0 / newWidth.doubleValue()));
                    content.widthProperty().removeListener(this);
                }
            }
        });

        load();
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    @Override
    public String title() {
        return "Snapshots";
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public List<Node> actions() {
        return List.of(refresh);
    }

    @Override
    public void closed() {
        closed = true;
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void load() {
        String keep = selectedId();
        GitTaskRunner.queryLatest(gitService, this,
                gitService::listSnapshots,
                loaded -> {
                    if (closed) return;
                    snapshots.getItems().setAll(loaded);
                    snapshots.setPlaceholder(new Label("No snapshots yet. One is taken before every discard."));
                    Snapshot again = loaded.stream().filter(s -> s.id().equals(keep)).findFirst()
                            .orElse(loaded.isEmpty() ? null : loaded.get(0));
                    if (again != null) {
                        snapshots.getSelectionModel().select(again);
                    } else {
                        showSnapshot(null);
                    }
                },
                error -> {
                    snapshots.setPlaceholder(new Label("Could not read the snapshots."));
                    statusBar.error(error);
                });
    }

    private String selectedId() {
        Snapshot selected = snapshots.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.id();
    }

    private void showSnapshot(Snapshot snapshot) {
        files.getItems().clear();
        diffViewer.close();
        restoreAll.setDisable(snapshot == null);
        delete.setDisable(snapshot == null);
        restoreFile.setDisable(true);
        if (snapshot == null) {
            files.setPlaceholder(new Label("Pick a snapshot to compare it with your files."));
            return;
        }
        files.setPlaceholder(new Label("Comparing…"));
        GitTaskRunner.queryLatest(gitService, List.of(this, "changes"),
                () -> SnapshotComparison.changes(gitService, snapshot),
                changes -> {
                    if (closed || snapshot != snapshots.getSelectionModel().getSelectedItem()) return;
                    files.getItems().setAll(changes);
                    files.setPlaceholder(new Label("Your files are the same as this snapshot."));
                    if (!changes.isEmpty()) files.getSelectionModel().select(0);
                },
                error -> {
                    files.setPlaceholder(new Label("Could not compare this snapshot."));
                    statusBar.error(error);
                });
    }

    /** The diff, and the working tree's copy for the viewer's whole-file view, read together. */
    private record Shown(byte[] diff, byte[] now) {
    }

    private void showFile(HistoryOps.FileChange change) {
        Snapshot snapshot = snapshots.getSelectionModel().getSelectedItem();
        restoreFile.setDisable(change == null || change.status() == 'A');
        if (change == null || snapshot == null) return;
        String path = change.path();
        GitTaskRunner.queryLatest(gitService, List.of(this, "diff"),
                () -> new Shown(SnapshotComparison.diff(gitService, snapshot, path),
                        change.status() == 'D' ? null : gitService.getWorkingTreeBytes(path)),
                shown -> {
                    if (closed || files.getSelectionModel().getSelectedItem() != change) return;
                    shownPath = path;
                    shownNow = shown.now();
                    diffViewer.setComparedSides(DiffViewer.DiffSides.between("Snapshot", "Working tree"));
                    if (shown.now() == null) {
                        diffViewer.setFileDiffBytes(DiffKind.SNAPSHOT, path, shown.diff());
                    } else {
                        diffViewer.setFileDiffBytes(DiffKind.SNAPSHOT, path, shown.diff(), shown.now());
                    }
                },
                statusBar::error);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void restoreSelectedFile() {
        Snapshot snapshot = snapshots.getSelectionModel().getSelectedItem();
        HistoryOps.FileChange change = files.getSelectionModel().getSelectedItem();
        if (snapshot == null || change == null || change.status() == 'A') return;
        restore(snapshot, List.of(change.path()), "Restoring " + change.path());
    }

    /**
     * Asks before writing every file back: restoring replaces what is on disk now, and a
     * snapshot taken first is what makes that answer reversible, which the question says.
     */
    private void confirmRestoreAll() {
        Snapshot snapshot = snapshots.getSelectionModel().getSelectedItem();
        if (snapshot == null) return;
        if (Dialogs.confirm("Restore snapshot")
                .icon(Icons.restore())
                .title("Restore every file in this snapshot?")
                .subtitle(snapshot.reason() + " · " + EXACT.format(snapshot.time()))
                .message("Files that differ are replaced; files the snapshot does not have are left alone."
                        + " Your files as they are now are snapshotted first, so this can be undone"
                        + " from here too.")
                .action("Restore all files")
                .ask()) {
            restore(snapshot, null, "Restoring the snapshot");
        }
    }

    private void restore(Snapshot snapshot, List<String> paths, String busy) {
        operations.operation(busy, OperationRunner.Refresh.EVERYTHING)
                .afterwards(result -> {
                    if (!closed) load();
                })
                .run(SnapshotGuard.guarded(gitService, "Before restoring a snapshot",
                        () -> gitService.restoreFromSnapshot(snapshot.id(), paths)));
    }

    /**
     * Asks before deleting, as dropping a stash does: nothing else refers to a snapshot's
     * commit, so a deleted one cannot be brought back, and the button sits beside Restore
     * all. The question names the snapshot by its reason and exact time, because snapshots
     * taken for the same reason read alike in the list.
     */
    private void deleteSelected() {
        Snapshot snapshot = snapshots.getSelectionModel().getSelectedItem();
        if (snapshot == null) return;
        // Cancel is the default: nothing can bring a deleted snapshot back.
        if (Dialogs.destructive("Delete snapshot")
                .title("Delete the snapshot “" + snapshot.reason() + "”?")
                .subtitle("Taken " + EXACT.format(snapshot.time()))
                .message("The copy of your files it holds cannot be brought back. Your files as they"
                        + " are now are not touched.")
                .action("Delete snapshot")
                .safeDefault()
                .ask()) {
            delete(snapshot);
        }
    }

    private void delete(Snapshot snapshot) {
        operations.operation("Deleting the snapshot", OperationRunner.Refresh.NONE)
                .afterwards(result -> {
                    if (!closed) load();
                })
                .run(() -> gitService.deleteSnapshot(snapshot.id()));
    }

    /** The snapshots as last read; for tests. */
    public List<Snapshot> loadedSnapshots() {
        return List.copyOf(snapshots.getItems());
    }

    /** The files the selected snapshot differs in; for tests. */
    public List<HistoryOps.FileChange> shownChanges() {
        return List.copyOf(files.getItems());
    }

    // ------------------------------------------------------------------
    // Cells
    // ------------------------------------------------------------------

    /** Why it was taken, then on which branch, how much, and when. */
    private static final class SnapshotCell extends ListCell<Snapshot> {
        private final Label reason = new Label();
        private final Label meta = new Label();
        private final VBox box = new VBox(2, reason, meta);
        private final Tooltip exact = new Tooltip();

        SnapshotCell() {
            reason.getStyleClass().add("snapshot-reason");
            meta.getStyleClass().addAll("snapshot-meta", "t-caption");
        }

        @Override
        protected void updateItem(Snapshot item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            reason.setText(item.reason());
            String branch = item.branch() == null || item.branch().isBlank() ? "" : item.branch() + " · ";
            meta.setText(branch + item.fileCount() + (item.fileCount() == 1 ? " file" : " files")
                    + " · " + com.jfx.git.graph.RelativeTime.of(item.time()));
            exact.setText(EXACT.format(item.time()) + " · " + item.shortHash());
            setTooltip(exact);
            setGraphic(box);
        }
    }

    /** The status letter and the path, as the staging list shows them. */
    private static final class FileCell extends ListCell<HistoryOps.FileChange> {
        private final Label status = new Label();
        private final Label path = new Label();
        private final Label counts = new Label();
        private final HBox row = new HBox(UiScale.STEP, status, path, spacer(), counts);

        FileCell() {
            status.getStyleClass().add("t-mono");
            status.setMinWidth(Region.USE_PREF_SIZE);
            counts.getStyleClass().addAll("t-mono", "t-caption");
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(HistoryOps.FileChange item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setTooltip(null);
                return;
            }
            status.setText(String.valueOf(item.status()));
            path.setText(item.path());
            counts.setText(item.added() + item.deleted() == 0 ? "" : "+" + item.added() + " −" + item.deleted());
            setTooltip(new Tooltip(switch (item.status()) {
                case 'A' -> "Only in your files: added since the snapshot";
                case 'D' -> "Only in the snapshot: deleted since";
                default -> "Changed since the snapshot";
            }));
            setGraphic(row);
        }
    }
}
