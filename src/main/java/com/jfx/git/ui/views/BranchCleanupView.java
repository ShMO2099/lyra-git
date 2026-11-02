package com.jfx.git.ui.views;

import com.jfx.git.core.DefaultBranch;
import com.jfx.git.core.DeletedBranch;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitFlow;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.graph.RelativeTime;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.UiScale;
import com.jfx.git.ui.dialogs.Dialogs;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Branch cleanup: the local branches there is no more reason to keep, ticked off and
 * deleted together, with one Undo for all of them.
 *
 * <h2>Which branches</h2>
 * Two kinds, each with its reason beside it:
 * <ul>
 *   <li><b>merged</b> into the base - {@code origin/HEAD}'s branch, or else {@code main}
 *       or {@code master} ({@link DefaultBranch}) - so deleting it loses no commit the
 *       base does not have. Ticked to begin with.</li>
 *   <li><b>upstream gone</b>: it tracked a remote branch that has since been deleted -
 *       usually because its pull request was merged there, perhaps squashed, which is
 *       why it need not look merged here. Not ticked unless it is merged too: what it
 *       holds may exist nowhere else.</li>
 * </ul>
 * Never listed: the branch checked out, the base itself, and the long-lived branches -
 * {@code main}, {@code master}, {@code develop} and the two Git Flow names.
 *
 * <h2>Undo</h2>
 * Each branch is deleted on its own, its commit and configuration captured first
 * ({@link DeletedBranch}), so Undo brings back each branch as it was, upstream included.
 * A branch also deleted on the remote is gone for everyone who fetches; Undo restores
 * the local branch, and says it cannot do more.
 */
public final class BranchCleanupView implements RepoView {

    /** Kept whatever they hold: the branches a repository is organised around. */
    private static final Set<String> LONG_LIVED = Set.of("main", "master", "develop");

    /** One local branch the cleanup offers, as the scan found it. */
    public record Found(String branch, String tip, boolean merged, boolean gone, String upstream,
                        String remote, String remoteBranch, String subject, Instant date) {

        /** Whether its branch on the remote can be deleted too: it tracks one, and it is still there. */
        public boolean onRemote() {
            return remote != null && remoteBranch != null && !gone;
        }

        /** Why it is offered, in a phrase. */
        public String reason(String base) {
            String into = "merged into " + base;
            if (merged && gone) return "Upstream gone, " + into;
            if (merged) return Character.toUpperCase(into.charAt(0)) + into.substring(1);
            return "Upstream gone, not merged into " + (base == null ? "the base" : base);
        }
    }

    /** What a scan found: the base it measured against, and the branches. */
    public record Scan(String base, List<Found> branches) {
    }

    /** What deleting did: the branches it can bring back, and what went wrong. */
    record Outcome(List<DeletedBranch> deleted, List<String> deletedOnRemote, List<String> failures) {
    }

    /** One row: a branch, and the two ticks the user can change. */
    static final class Row {
        final Found found;
        final BooleanProperty selected = new SimpleBooleanProperty();
        final BooleanProperty deleteOnRemote = new SimpleBooleanProperty();

        Row(Found found) {
            this.found = found;
            selected.set(found.merged());
        }
    }

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs deletes and their undo, each refreshing the tab so the rest of it catches up. */
    private final OperationRunner operations;

    private final Label summary = new Label("Reading the branches…");
    private final TableView<Row> table = new TableView<>();
    private final Button deleteSelected = new Button("Delete selected");
    private final VBox body;
    private String base;
    private boolean closed;

    public BranchCleanupView(OperationRunner operations) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();

        Label intro = new Label("Local branches that are merged, or whose branch on the remote has been"
                + " deleted. Tick the ones to delete; Undo brings them back, upstream included.");
        intro.setWrapText(true);
        intro.getStyleClass().add("t-caption");
        summary.setWrapText(true);

        table.getStyleClass().add("branch-cleanup-table");
        table.setEditable(true);
        table.setPlaceholder(new Label("Nothing to clean up"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Row, Boolean> pick = checkColumn("", row -> row.selected, row -> true);
        pick.setMinWidth(36);
        pick.setMaxWidth(36);
        TableColumn<Row, String> branch = textColumn("Branch", row -> row.found.branch());
        TableColumn<Row, String> why = textColumn("Why", row -> row.found.reason(base));
        TableColumn<Row, String> last = textColumn("Last commit", row -> lastCommit(row.found));
        TableColumn<Row, Boolean> remote = checkColumn("Also delete on remote", row -> row.deleteOnRemote,
                row -> row.found.onRemote());
        table.getColumns().setAll(List.of(pick, branch, why, last, remote));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button all = new Button("Select all");
        all.setOnAction(e -> table.getItems().forEach(row -> row.selected.set(true)));
        Button none = new Button("Select none");
        none.setOnAction(e -> table.getItems().forEach(row -> row.selected.set(false)));
        deleteSelected.getStyleClass().add("btn-danger");
        deleteSelected.setOnAction(e -> deleteSelected());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(UiScale.STEP, all, none, spacer, deleteSelected);
        buttons.setAlignment(Pos.CENTER_LEFT);

        body = new VBox(UiScale.GAP, intro, summary, table, buttons);
        body.getStyleClass().add("branch-cleanup");
        rescan();
    }

    @Override
    public String title() {
        return "Clean up branches";
    }

    @Override
    public Node content() {
        return body;
    }

    /** F5: look again. */
    @Override
    public boolean refreshView() {
        rescan();
        return true;
    }

    @Override
    public void closed() {
        closed = true;
    }

    /** Reads the branches again, off the FX thread. */
    void rescan() {
        deleteSelected.setDisable(true);
        GitTaskRunner.query(gitService, () -> scan(gitService), scan -> {
            if (closed) return;
            show(scan);
        }, error -> summary.setText("Could not read the branches: " + error));
    }

    private void show(Scan scan) {
        base = scan.base();
        List<Row> rows = scan.branches().stream().map(Row::new).toList();
        table.getItems().setAll(rows);
        deleteSelected.setDisable(rows.isEmpty());
        if (base == null) {
            summary.setText("This repository has no origin/HEAD, main or master to measure merged"
                    + " branches against, so only branches whose upstream has gone are listed.");
        } else if (rows.isEmpty()) {
            summary.setText("Every local branch is either still in use or not yet merged into " + base + ".");
        } else {
            summary.setText(rows.size() + (rows.size() == 1 ? " branch" : " branches")
                    + " could go. Merged means merged into " + base + ".");
        }
    }

    /**
     * Finds the branches to offer. On the git lane.
     *
     * <p>Package-visible and free of JavaFX so a test can call it against a repository.
     */
    static Scan scan(GitBackend git) {
        String base = DefaultBranch.baseRef(git);
        Set<String> keep = new HashSet<>(LONG_LIVED);
        if (!git.isHeadDetached()) keep.add(git.getCurrentBranch());
        if (base != null) {
            keep.add(base);
            if (base.startsWith("origin/")) keep.add(base.substring("origin/".length()));
        }
        String defaultName = DefaultBranch.name(git);
        if (defaultName != null) keep.add(defaultName);
        GitFlow.Config flow = new GitFlow(git).config();
        if (flow != null) {
            keep.add(flow.production());
            keep.add(flow.develop());
        }
        Set<String> gone = git.getGoneBranches();
        Set<String> merged = base == null ? Set.of() : new HashSet<>(git.getBranchesMergedInto(base));
        Map<String, String> upstreams = git.getUpstreams();
        List<String> remotes = git.getRemoteNames();

        List<Found> found = new ArrayList<>();
        List<RefInfo> locals = new ArrayList<>(git.getLocalBranches());
        locals.sort(java.util.Comparator.comparing(RefInfo::shortName));
        for (RefInfo ref : locals) {
            String name = ref.shortName();
            if (keep.contains(name)) continue;
            boolean isMerged = merged.contains(name);
            boolean isGone = gone.contains(name);
            if (!isMerged && !isGone) continue;
            String upstream = upstreams.get(name);
            String remote = remoteOf(upstream, remotes);
            String remoteBranch = remote == null ? null : upstream.substring(remote.length() + 1);
            String tip = ref.targetHash();
            List<CommitInfo> newest = tip == null ? List.of() : git.getCommitsInRange(tip + "~1", tip, 1);
            String subject;
            Instant date = null;
            if (!newest.isEmpty()) {
                subject = newest.get(0).getSubject();
                date = newest.get(0).getAuthorDate();
            } else {
                String message = tip == null ? null : git.getCommitMessage(tip);
                subject = message == null ? "" : message.lines().findFirst().orElse("");
            }
            found.add(new Found(name, tip, isMerged, isGone, upstream, remote, remoteBranch, subject, date));
        }
        return new Scan(base, found);
    }

    /**
     * The longest configured remote {@code upstream} starts with, or {@code null} for an
     * upstream on no configured remote; see {@link com.jfx.git.model.RefNames#remoteOf}.
     */
    private static String remoteOf(String upstream, List<String> remotes) {
        if (upstream == null) return null;
        String remote = com.jfx.git.model.RefNames.remoteOf(upstream, remotes);
        return remotes.contains(remote) ? remote : null;
    }

    private static String lastCommit(Found found) {
        String when = found.date() == null ? "" : RelativeTime.of(found.date());
        return when.isEmpty() ? found.subject() : when + " · " + found.subject();
    }

    private void deleteSelected() {
        List<Row> chosen = table.getItems().stream().filter(row -> row.selected.get()).toList();
        if (chosen.isEmpty()) {
            summary.setText("Tick the branches to delete first.");
            return;
        }
        List<String> onRemote = chosen.stream()
                .filter(row -> row.deleteOnRemote.get() && row.found.onRemote())
                .map(row -> row.found.remote() + "/" + row.found.remoteBranch())
                .toList();
        if (!onRemote.isEmpty() && !confirmRemoteDeletion(onRemote)) {
            return;
        }
        List<Found> branches = chosen.stream().map(row -> row.found).toList();
        Set<String> alsoRemote = new HashSet<>();
        chosen.stream().filter(row -> row.deleteOnRemote.get() && row.found.onRemote())
                .forEach(row -> alsoRemote.add(row.found.branch()));
        deleteSelected.setDisable(true);
        AtomicReference<Outcome> made = new AtomicReference<>();
        operations.operation("Deleting " + branches.size() + (branches.size() == 1 ? " branch" : " branches"),
                        OperationRunner.Refresh.EVERYTHING)
                .showSuccess(result -> offerUndo(made.get()))
                // Some may have gone and some not: those that went are offered back, and the
                // failure is shown as well.
                .showFailure(result -> {
                    if (made.get() != null) offerUndo(made.get());
                    return false;
                })
                .afterwards(result -> {
                    if (!closed) rescan();
                })
                .run(() -> {
                    Outcome outcome;
                    try {
                        outcome = delete(gitService, branches, alsoRemote);
                    } catch (RuntimeException e) {
                        return GitResult.failed("Could not delete the branches: " + e.getMessage());
                    }
                    made.set(outcome);
                    return outcome.failures().isEmpty() ? GitResult.ok(deletedMessage(outcome))
                            : GitResult.failed("Some branches were not deleted:\n" + String.join("\n", outcome.failures()));
                });
    }

    /**
     * Asks before the ticked branches' remote counterparts go too. Cancel is the default:
     * Undo brings the local branches back, not these, and they go for everyone who fetches.
     *
     * @param upstreams the remote branches to delete, as {@code origin/feature}
     */
    private boolean confirmRemoteDeletion(List<String> upstreams) {
        int count = upstreams.size();
        String branches = count == 1 ? "1 branch" : count + " branches";
        return Dialogs.destructive("Delete on the remote")
                .icon(Icons.remoteBranch())
                .title("Delete " + branches + " on the remote too?")
                .subtitle("For everyone who fetches from it")
                .message("Undo brings back the local branches only; "
                        + (count == 1 ? "this one stays" : "these stay") + " deleted on the remote.")
                .affected("Deleted on the remote", upstreams)
                .action("Delete on the remote too")
                .safeDefault()
                .size(JfxDialog.Size.MD)
                .ask();
    }

    /**
     * Deletes each branch on its own, capturing it first, and on the remote where asked.
     * On the git lane; package-visible for tests.
     *
     * <p>Forced: a branch listed as merged is merged into the base, which {@code git
     * branch -d} does not know to check when something else is checked out; and one
     * listed only as gone was ticked by the user, who read why it was listed.
     */
    static Outcome delete(GitBackend git, List<Found> branches, Set<String> alsoOnRemote) {
        List<DeletedBranch> deleted = new ArrayList<>();
        List<String> remote = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Found found : branches) {
            if (GitTaskRunner.isCancelled()) break;
            DeletedBranch snapshot = found.tip() == null
                    ? DeletedBranch.capture(git, found.branch())
                    : DeletedBranch.capture(git, found.branch(), found.tip());
            GitResult result = git.deleteBranch(found.branch(), true);
            if (!result.isOk()) {
                failures.add(found.branch() + ": " + result.getMessage());
                continue;
            }
            if (snapshot != null) deleted.add(snapshot);
            if (alsoOnRemote.contains(found.branch()) && found.onRemote()) {
                GitResult gone = git.deleteRemoteBranch(found.remote(), found.remoteBranch());
                if (gone.isOk()) {
                    remote.add(found.upstream());
                } else {
                    failures.add(found.upstream() + ": " + gone.getMessage());
                }
            }
        }
        return new Outcome(deleted, remote, failures);
    }

    /** Brings every branch of {@code deleted} back. On the git lane. */
    static GitResult restore(GitBackend git, List<DeletedBranch> deleted) {
        List<String> failures = new ArrayList<>();
        for (DeletedBranch branch : deleted) {
            GitResult result = branch.restore(git);
            if (!result.isOk()) failures.add(result.getMessage());
        }
        if (!failures.isEmpty()) return GitResult.failed(String.join("\n", failures));
        return GitResult.ok(deleted.size() == 1 ? "Restored branch " + deleted.get(0).name()
                : "Restored " + deleted.size() + " branches");
    }

    /** "Deleted 3 branches, and 1 on the remote (Undo restores the local ones)". */
    private static String deletedMessage(Outcome outcome) {
        List<DeletedBranch> deleted = outcome.deleted();
        String message = deleted.size() == 1 ? "Deleted branch " + deleted.get(0).name()
                : "Deleted " + deleted.size() + " branches";
        if (!outcome.deletedOnRemote().isEmpty()) {
            message += ", and " + outcome.deletedOnRemote().size() + " on the remote (Undo restores the local ones)";
        }
        return message;
    }

    /** Says what went, with Undo, which brings the local branches back as they were. */
    private void offerUndo(Outcome outcome) {
        List<DeletedBranch> deleted = outcome.deleted();
        if (deleted.isEmpty()) return;
        statusBar.offerUndo(deletedMessage(outcome), () ->
                operations.operation("Restoring branches", OperationRunner.Refresh.EVERYTHING)
                        .afterwards(result -> {
                            if (!closed) rescan();
                        })
                        .run(() -> restore(gitService, deleted)));
    }

    // ------------------------------------------------------------------
    // Cells
    // ------------------------------------------------------------------

    private static TableColumn<Row, String> textColumn(String title, Function<Row, String> value) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        column.setEditable(false);
        return column;
    }

    /** A tick bound to {@code property} of each row, live only where {@code enabled} says. */
    private static TableColumn<Row, Boolean> checkColumn(String title, Function<Row, BooleanProperty> property,
                                                         java.util.function.Predicate<Row> enabled) {
        TableColumn<Row, Boolean> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> property.apply(data.getValue()));
        column.setSortable(false);
        column.setCellFactory(col -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(e -> {
                    Row row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) property.apply(row).set(box.isSelected());
                });
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                Row row = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                box.setSelected(Boolean.TRUE.equals(value));
                box.setDisable(!enabled.test(row));
                box.setAccessibleText((title.isEmpty() ? "Delete " : title + ": ") + row.found.branch());
                setGraphic(box);
            }
        });
        return column;
    }

    // ------------------------------------------------------------------
    // For tests
    // ------------------------------------------------------------------

    TableView<Row> tableView() {
        return table;
    }

    String summaryText() {
        return summary.getText();
    }

    Button deleteButton() {
        return deleteSelected;
    }
}
