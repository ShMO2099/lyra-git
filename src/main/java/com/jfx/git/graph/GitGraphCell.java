package com.jfx.git.graph;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitOperationException;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.RebaseTodo;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.ResetMode;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.dialogs.Dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import com.jfx.git.model.CommitPosition;
import com.jfx.git.model.RebaseEligibility;

/**
 * One row of the commit graph: the lane drawing, ref chips, message and author.
 */
public class GitGraphCell extends ListCell<GitGraphRow> {

    /**
     * The measurements this cell draws at, fixed when the cell is created.
     *
     * <p>Read once rather than per row: a cell is recycled through hundreds of commits and
     * the density cannot change underneath it — switching the preference rebuilds the list.
     */
    private final GraphDensity density = GraphDensity.current();

    /** Draws each row's lane plan at that density. */
    private final RowPainter painter = new RowPainter(density);

    /** Whether rows carry a wash of their branch's colour; also fixed at construction. */
    private final boolean tintRows = com.jfx.git.core.AppPreferences.graphBranchTint();

    /**
     * What a commit is drawn as, and therefore how wide the lanes are and whether the row
     * carries an author column at all.
     *
     * <p>Taken from the shared metrics rather than read from the preference again, so this
     * cell cannot be drawing at a different lane pitch than its own header reserved.
     */
    private final GraphNodeStyle nodeStyle;

    private final int rowHeight;

    /** Column widths that depend on the loaded page; shared with the header and siblings. */
    private final GraphMetrics metrics;
    /** Which commits the selection made relevant; null when nothing fades. */
    private final GraphHighlight highlight;
    /** Nearest containing branch per commit, for the hover label. */
    private final java.util.function.Supplier<GhostBranches> ghosts;

    /**
     * Re-applies this row's fade when the selection changes.
     *
     * <p>Held in a field and registered weakly, like the theme listener: the highlight
     * outlives any cell built against it — a column change replaces the whole factory —
     * so a strong listener would pin every discarded cell for the life of the tab.
     */
    private final javafx.beans.InvalidationListener highlightListener;

    /**
     * True while this cell's pointer is the one fading the graph.
     *
     * <p>A chip hidden by recycling never sends the mouse-exited event that would end the
     * hover: refresh the graph while the pointer rests on a branch and the fade would
     * stay on with nothing under the cursor to explain it. Recycling has to end the hover
     * itself, and only the cell that started one may end it - otherwise any other row
     * being reused would cancel a hover that is still live.
     */
    private boolean drivingHover;

    /**
     * Repaints this row when the graph is dragged wider or narrower.
     *
     * <p>The lane drawing is sized from the shared metrics at paint time, so a changed
     * budget has to reach the canvas somehow. Doing it here rather than through
     * {@code ListView.refresh()} is what makes the drag smooth: refresh discards and
     * rebuilds every cell, which flickered the whole graph on each lane the pointer
     * crossed. Held and registered weakly, like the others.
     */
    private final javafx.beans.InvalidationListener laneWidthListener;

    private final GridPane root;
    private final Region edgeStrip;
    private final Canvas canvas;
    /** Shrinks the oversized buffer back to the logical size it stands in for. */
    private final javafx.scene.transform.Scale canvasScale =
            new javafx.scene.transform.Scale(1, 1);
    /** Wraps the canvas so the grid measures it after that shrink. */
    private final javafx.scene.Group canvasHolder;
    private final HBox tagsBox;
    private final Label messageLabel;
    /** The commit body, dimmed, on the same line as the subject. */
    private final Label bodyLabel;
    private final HBox messageBox;
    private final AuthorBadge avatar;
    private final Label ageLabel;
    private final Label hashLabel;

    /**
     * The floating label that shows a clipped ref's full name while it is hovered.
     *
     * <p>One per cell, unmanaged, and reused. Unmanaged is the point: the layout never
     * sees it, so showing a name cannot resize a column, move a sibling chip or reflow
     * the row.
     */
    private final Label hoverChip;

    /** The faint branch name shown while an unlabelled commit is hovered. */
    private final Label ghostChip;

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs what the row's menus start, each refreshing the whole tab once done. */
    private final OperationRunner operations;
    /** Reword, drop and move for the commit on this row; see {@link com.jfx.git.ui.CommitHistoryEdits}. */
    private final com.jfx.git.ui.CommitHistoryEdits historyEdits;
    /** What dropping a branch chip on another offers; the sidebar's rows share it. */
    private final com.jfx.git.ui.RefDropMenu refDrops;
    /** Saving and copying commits as patches; see {@link com.jfx.git.ui.PatchActions}. */
    private final com.jfx.git.ui.PatchActions patches;
    /** Opens a two-way comparison; the tab owns the panel that shows it. */
    private final BiConsumer<CommitInfo, CommitInfo> onCompare;

    /** Reusable ref-chip labels; grown on demand, hidden when the commit has fewer refs. */
    private final java.util.List<Label> chipPool = new java.util.ArrayList<>();

    /**
     * Context menus, built once per cell and reused.
     *
     * <p>A {@code ListView} recycles a few dozen cells and calls {@link #updateItem} on
     * each of them for every row that scrolls past, so anything allocated there is
     * garbage by the next frame. Rebuilding a menu of fifteen items per row per tick was
     * by far the largest cost on that path, and virtually none of those menus were ever
     * opened. They are built lazily instead — a cell that only ever shows synthetic rows
     * never builds one — and every action reads the row the cell is currently showing
     * rather than one captured when the menu was made.
     */
    private ContextMenu commitMenu;
    private ContextMenu stashMenu;
    /** Trash icon on the WIP row: discards every working-tree change at once. */
    private javafx.scene.control.Button wipDiscard;

    public GitGraphCell(OperationRunner operations) {
        this(operations, (a, b) -> { }, null);
    }

    public GitGraphCell(OperationRunner operations, BiConsumer<CommitInfo, CommitInfo> onCompare) {
        this(operations, onCompare, null);
    }

    /**
     * @param metrics the page-dependent column widths this cell shares with its header
     *                and its sibling rows; a cell given {@code null} sizes itself from the
     *                density alone, which is only right when it has no header to line up
     *                with
     */
    public GitGraphCell(OperationRunner operations,
                        BiConsumer<CommitInfo, CommitInfo> onCompare,
                        GraphMetrics metrics) {
        this(operations, onCompare, metrics, null);
    }

    /**
     * @param highlight which commits the selection has made relevant, so the rest can be
     *                  drawn faded; {@code null} for a graph that does not fade
     */
    public GitGraphCell(OperationRunner operations,
                        BiConsumer<CommitInfo, CommitInfo> onCompare,
                        GraphMetrics metrics,
                        GraphHighlight highlight) {
        this(operations, onCompare, metrics, highlight, null);
    }

    /**
     * @param ghosts the nearest containing branch per commit, consulted on hover so a
     *               commit with no ref of its own can still say what it is part of
     */
    public GitGraphCell(OperationRunner operations,
                        BiConsumer<CommitInfo, CommitInfo> onCompare,
                        GraphMetrics metrics,
                        GraphHighlight highlight,
                        java.util.function.Supplier<GhostBranches> ghosts) {
        this.ghosts = ghosts;
        this.highlight = highlight;
        if (highlight == null) {
            this.highlightListener = null;
        } else {
            this.highlightListener = obs -> refreshFade();
            highlight.revisionProperty().addListener(
                    new javafx.beans.WeakInvalidationListener(highlightListener));
        }
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar  = operations.statusBar();
        this.historyEdits = new com.jfx.git.ui.CommitHistoryEdits(operations);
        this.refDrops = new com.jfx.git.ui.RefDropMenu(operations);
        this.patches = new com.jfx.git.ui.PatchActions(operations);
        installRowDrop();
        this.onCompare = onCompare;
        this.metrics = metrics != null ? metrics : new GraphMetrics(GraphDensity.current());
        // A drop's labels name a remote by the page's own remotes.
        refDrops.setRemoteNames(this.metrics::remoteNames);
        this.laneWidthListener = obs -> redrawCurrentRow();
        this.metrics.laneWidthProperty().addListener(
                new javafx.beans.WeakInvalidationListener(laneWidthListener));
        this.nodeStyle = this.metrics.nodeStyle();

        this.rowHeight = density.rowHeight();

        this.canvas = new Canvas();
        this.canvas.setHeight(rowHeight);
        this.canvas.getStyleClass().add("lane-canvas");
        this.canvas.getTransforms().add(canvasScale);

        // The canvas is wrapped because it is sized in buffer pixels, not logical ones,
        // and a GridPane measures a non-resizable child by its untransformed bounds - so
        // a zoomed-in row would claim a lane column half as wide again as it draws. A
        // Group's bounds are its children's *after* their transforms, which is the
        // logical size the column was laid out for.
        this.canvasHolder = new javafx.scene.Group(canvas);

        // The branch strip at the leading edge. Zero-width in Compact, which leaves it
        // present but invisible rather than making the column list depend on the density.
        this.edgeStrip = new Region();
        this.edgeStrip.setPrefWidth(density.edgeWidth());
        this.edgeStrip.setMinWidth(density.edgeWidth());
        this.edgeStrip.setMaxWidth(density.edgeWidth());

        // Refs sit in a column of their own, right-aligned so they lean against the
        // graph. This is the whole point of the layout: a long branch name now costs the
        // ref column, not the commit message. Its width is bound below, because how much
        // room the names need is a property of the repository, not of the density.
        this.tagsBox = new HBox(3);
        this.tagsBox.setAlignment(Pos.CENTER_RIGHT);
        this.tagsBox.getStyleClass().add("graph-rail");

        // The ghost sits in the rail with the real chips, so a hovered commit says which
        // branch it is part of in the place the answer would be if it were a tip.
        this.ghostChip = new Label();
        this.ghostChip.getStyleClass().addAll("ref-chip", "ref-chip-ghost");
        this.ghostChip.setVisible(false);
        this.ghostChip.setManaged(false);
        this.tagsBox.getChildren().add(ghostChip);

        // Two labels rather than one rich-text node: the subject at full contrast and the
        // rest of the message dimmed beside it. A TextFlow would be the obvious tool and
        // the wrong one here - it does not ellipsise, and it rebuilds its runs whenever the
        // text changes, which on this path is every row of every scroll tick.
        this.messageLabel = new Label();
        this.messageLabel.getStyleClass().add("graph-message");
        this.bodyLabel = new Label();
        this.bodyLabel.getStyleClass().add("graph-body");
        this.bodyLabel.setMinWidth(0);
        // Asks for no width of its own, and grows into whatever the subject leaves. A
        // Label's default preferred width is its whole text, so the body was competing
        // with the subject for the column: a commit whose message carried a long trailer
        // - "Co-authored-by: ..." is the common one - squeezed the subject down to a bare
        // ellipsis, leaving a row that showed three dim dots where its title should be
        // and read, from across the panel, as a faded row.
        this.bodyLabel.setPrefWidth(0);
        HBox.setHgrow(bodyLabel, Priority.ALWAYS);

        this.messageBox = new HBox(0, messageLabel, bodyLabel);
        this.messageBox.setAlignment(Pos.CENTER_LEFT);
        // Asks for nothing and accepts whatever the column gives it; the two labels
        // ellipsise inside that. A box that asked for its content's width would make every
        // row as wide as its longest commit message.
        this.messageBox.setMinWidth(0);
        this.messageBox.setPrefWidth(0);
        this.messageLabel.setMaxWidth(Double.MAX_VALUE);

        // Only present when the node is a dot; with the author node the avatar is drawn
        // at the node itself and this column does not exist.
        this.avatar = new AuthorBadge(density.avatarSize());
        this.avatar.setManaged(nodeStyle.needsAuthorColumn());
        this.avatar.setVisible(nodeStyle.needsAuthorColumn());

        this.ageLabel = new Label();
        this.ageLabel.getStyleClass().add("graph-age");

        this.hashLabel = new Label();
        this.hashLabel.getStyleClass().add("graph-hash");
        // The short hash is the one field that gets pasted into a terminal, so it is a
        // click target rather than decoration.
        this.hashLabel.setOnMouseClicked(e -> onCommit(commit -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(commit.getHash());
            Clipboard.getSystemClipboard().setContent(content);
            statusBar.info("Copied " + commit.getShortHash());
        }));

        this.root = new GridPane();
        this.root.getStyleClass().add("graph-row");
        this.root.setAlignment(Pos.CENTER_LEFT);
        this.root.setPadding(Insets.EMPTY);
        this.root.setPrefHeight(rowHeight);
        this.root.setMinHeight(rowHeight);
        this.root.setMaxHeight(rowHeight);

        // Columns come from the density so this row and the header above it are laid out
        // from one definition, in the user's stored order (drag-to-reorder). The two that
        // depend on the loaded page rather than the density are bound to the shared
        // metrics, at whichever position reordering has put them.
        java.util.Set<GraphColumn> shownColumns = GraphColumn.visibleFor(nodeStyle);
        java.util.List<GraphColumn> order = GraphColumn.ordered();
        java.util.Map<GraphColumn, javafx.scene.Node> nodes = java.util.Map.of(
                GraphColumn.REFS, tagsBox,
                GraphColumn.GRAPH, canvasHolder,
                GraphColumn.MESSAGE, messageBox,
                GraphColumn.AUTHOR, avatar,
                GraphColumn.AGE, ageLabel,
                GraphColumn.HASH, hashLabel);
        var cells = new javafx.scene.Node[order.size() + 1];
        cells[0] = edgeStrip;
        for (int i = 0; i < order.size(); i++) {
            cells[i + 1] = nodes.get(order.get(i));
        }
        this.root.addRow(0, cells);
        this.root.getColumnConstraints().setAll(density.columnConstraints(nodeStyle, order));
        if (shownColumns.contains(GraphColumn.REFS)) {
            bindWidth(this.root.getColumnConstraints().get(1 + order.indexOf(GraphColumn.REFS)),
                    this.metrics.railWidthProperty());
        }
        if (shownColumns.contains(GraphColumn.GRAPH)) {
            bindWidth(this.root.getColumnConstraints().get(1 + order.indexOf(GraphColumn.GRAPH)),
                    this.metrics.laneWidthProperty());
        }

        // A zero-width column does not clip what is in it - the label would still paint
        // across its neighbour - so the contents are hidden as well as the column.
        hideUnless(tagsBox, shownColumns.contains(GraphColumn.REFS));
        hideUnless(canvasHolder, shownColumns.contains(GraphColumn.GRAPH));
        hideUnless(messageBox, shownColumns.contains(GraphColumn.MESSAGE));
        hideUnless(avatar, shownColumns.contains(GraphColumn.AUTHOR));
        hideUnless(ageLabel, shownColumns.contains(GraphColumn.AGE));
        hideUnless(hashLabel, shownColumns.contains(GraphColumn.HASH));

        tagsBox.minWidthProperty().bind(this.metrics.railWidthProperty());
        tagsBox.prefWidthProperty().bind(this.metrics.railWidthProperty());
        tagsBox.maxWidthProperty().bind(this.metrics.railWidthProperty());

        for (var node : this.root.getChildren()) {
            GridPane.setValignment(node, javafx.geometry.VPos.CENTER);
        }

        // Added after the valignment pass, and unmanaged, so the grid neither positions
        // it nor counts it as occupying a cell.
        this.hoverChip = new Label();
        this.hoverChip.setManaged(false);
        this.hoverChip.setVisible(false);
        this.hoverChip.setMouseTransparent(true);
        this.root.getChildren().add(hoverChip);

        // The selected row's bar, drawn over the row's leading edge rather than as a
        // border on it. A border is part of the layout: the selected row's columns all
        // moved two pixels right, and every lane line broke at that row.
        javafx.scene.layout.Region selectionBar = new javafx.scene.layout.Region();
        selectionBar.getStyleClass().add("graph-selection-bar");
        selectionBar.setManaged(false);
        selectionBar.setMouseTransparent(true);
        selectionBar.resizeRelocate(0, 0, 2, rowHeight);
        this.root.heightProperty().addListener((obs, old, height) ->
                selectionBar.resizeRelocate(0, 0, 2, height.doubleValue()));
        this.root.getChildren().add(selectionBar);

        // Bound once rather than re-bound per row: the cell asks what it is currently
        // showing when the pointer arrives, so recycling cannot leave a stale handler.
        this.root.setOnMouseEntered(e -> showGhostChip(true));
        this.root.setOnMouseExited(e -> showGhostChip(false));

        setPadding(Insets.EMPTY);
        setPrefHeight(rowHeight);
        setMinHeight(rowHeight);
        setMaxHeight(rowHeight);
    }

    /**
     * Draws the lanes of the row this cell is already showing again, at the new width.
     *
     * <p>Only the canvas. A lane-width change moves nothing else on the row - the chips
     * follow the rail's width, the text follows the column - and this runs for every live
     * row on every drag event of the lane divider. It used to re-run the whole of
     * {@code updateItem}: chip pool, styles, tooltips, menus and all, about 190 KB of
     * garbage and most of a millisecond per drag event, to repaint one canvas a row.
     */
    private void redrawCurrentRow() {
        GitGraphRow row = getItem();
        if (!isEmpty() && row != null) {
            paintCanvas(row);
        }
    }

    /**
     * Paints the lane canvas for {@code row}, whatever kind of row it is.
     *
     * <p>What the row draws is its {@link RowPlan}, worked out once per row and lane
     * budget rather than on every paint; see {@link RowPlanner#planFor}.
     */
    private void paintCanvas(GitGraphRow row) {
        RowPlan plan = RowPlanner.planFor(row, metrics.maxLanes(), nodeStyle);
        // Width comes from the whole page, not this row: sizing each canvas to its own
        // widest lane left the message column shifting in and out as the graph narrowed.
        double width = resizeCanvas(plan.lanes());
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, rowHeight);
        painter.paint(gc, plan, row.getCommit());
    }

    /** Takes a hidden column's contents out of the row entirely, not merely out of view. */
    private static void hideUnless(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private static void bindWidth(ColumnConstraints column, javafx.beans.value.ObservableValue<Number> width) {
        column.prefWidthProperty().bind(width);
        column.minWidthProperty().bind(width);
        column.maxWidthProperty().bind(width);
    }

    // ------------------------------------------------------------------
    // ListView cell lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void updateItem(GitGraphRow row, boolean empty) {
        super.updateItem(row, empty);

        if (empty || row == null) {
            setGraphic(null);
            setContextMenu(null);
            return;
        }

        hideAllChips();
        hideWipDiscard();
        // Set before the synthetic rows take their early returns, so a recycled cell
        // cannot show the WIP node or a stash still wearing the last commit's fade.
        refreshFade();

        if (row.isLoadMore()) {
            clearBody();
            messageLabel.setText("Load older commits…");
            styleAs(messageLabel, "graph-message", "graph-load-more");
            clearMeta();
            paintRow(null);
            setContextMenu(null);
            paintCanvas(row);
            setGraphic(root);
            return;
        }

        if (row.isWipNode()) {
            clearBody();
            messageLabel.setText("Uncommitted changes");
            styleAs(messageLabel, "graph-message", "graph-wip");
            clearMeta();
            ageLabel.setText("WIP");
            styleAs(ageLabel, "graph-age", "graph-wip");
            paintRow(null);
            setContextMenu(null);
            paintCanvas(row);
            attachWipDiscard();
            setGraphic(root);
            return;
        }

        if (row.isStash()) {
            var stash = row.getStash();
            clearBody();
            messageLabel.setText("📥 " + stash.shortDescription());
            setTip(messageLabel, stash.message());
            styleAs(messageLabel, "graph-message", "graph-stash");
            clearMeta();
            ageLabel.setText(com.jfx.git.ui.DateDisplay.compact(stash.when()));
            setLazyTip(ageLabel, ageTip);
            styleAs(ageLabel, "graph-age", "graph-stash");
            hashLabel.setText(stash.selector());
            paintRow(null);
            if (stashMenu == null) {
                stashMenu = buildStashMenu();
            }
            setContextMenu(stashMenu);
            paintCanvas(row);
            setGraphic(root);
            return;
        }

        CommitInfo commit = row.getCommit();
        // Lanes come from the row, not the commit: a narrowed filter view lays its rows
        // out afresh without touching the page's shared layout (see GitGraphRow.layout).
        CommitInfo layout = row.getLayout() != null ? row.getLayout() : commit;

        // Every subject is drawn the same, whichever branch it sits on. Commits off the
        // current branch used to be dimmed and italicised, which meant most of the graph
        // was faded before the user had done anything - see the note where
        // .graph-off-branch used to live.
        messageLabel.setText(commit.getSubject());
        styleAs(messageLabel, "graph-message");

        // Dimmed, on the same line. Hidden rather than blank when there is no body, so a
        // subject with nothing after it keeps the full width of the column.
        String body = commit.getBodySummary();
        bodyLabel.setText(body.isEmpty() ? "" : "  " + body);
        bodyLabel.setVisible(!body.isEmpty());
        bodyLabel.setManaged(!body.isEmpty());

        if (nodeStyle.needsAuthorColumn()) {
            avatar.setAuthor(commit.getAuthorName(), commit.getAuthorEmail());
            avatar.setVisible(true);
            avatar.setManaged(true);
            setTip(avatar, commit.getAuthorName());
        }

        ageLabel.setText(com.jfx.git.ui.DateDisplay.compact(commit.getAuthorDate()));
        setLazyTip(ageLabel, ageTip);
        styleAs(ageLabel, "graph-age");

        hashLabel.setText(commit.getShortHash());
        setLazyTip(hashLabel, hashTip);

        paintRow(GraphPalette.lane(layout.getColorIndex()));

        // Ref chips — pooled to avoid allocating new Label nodes on every scroll tick,
        // and grouped so a branch and its remote counterpart share one chip.
        int chipCount = 0;
        // A cell can be laid out after the repository behind it has been closed - a
        // refresh queued just before the tab went away fires during a later pulse. The
        // null answer simply renders the row without current-branch emphasis.
        var chips = RefRail.chipsOf(commit, currentBranchOrNull());
        int limit = maxChips();
        for (RefRail.Chip spec : chips) {
            if (chipCount == limit) {
                // The rail is a fixed width, so the tail becomes a count rather than
                // pushing the message column sideways — which is the whole point of it.
                Label overflow = chipAt(chipCount);
                overflow.setText("+" + (chips.size() - chipCount));
                setTip(overflow, remainingNames(chips, chipCount));
                styleAs(overflow, "ref-chip", "ref-chip-more");
                overflow.setUserData(null);
                // Not a branch, whatever this pooled label stood for on its last row.
                overflow.getProperties().remove(CHIP_REF);
                show(overflow);
                chipCount++;
                break;
            }
            Label chip = chipAt(chipCount);
            updateChipLabel(chip, spec, Math.min(chips.size(), maxChips() + 1),
                    GraphPalette.lane(layout.getColorIndex()));
            show(chip);
            chipCount++;
        }

        if (commitMenu == null) {
            commitMenu = buildContextMenu();
        }
        setContextMenu(commitMenu);

        paintCanvas(row);

        setGraphic(root);
    }

    // ------------------------------------------------------------------
    // Reading the row this cell is currently showing
    // ------------------------------------------------------------------

    /**
     * The commit on the row this cell currently shows, or {@code null}.
     *
     * <p>Menu actions read this at fire time rather than capturing a commit when the menu
     * was built, because the menu outlives any single row: the cell it belongs to is
     * recycled through hundreds of commits as the list scrolls.
     */
    private CommitInfo currentCommit() {
        GitGraphRow row = getItem();
        return row == null ? null : row.getCommit();
    }

    private com.jfx.git.model.StashEntry currentStash() {
        GitGraphRow row = getItem();
        return row == null ? null : row.getStash();
    }

    /** Runs {@code action} against the commit on the current row, if there is one. */
    private void onCommit(java.util.function.Consumer<CommitInfo> action) {
        CommitInfo commit = currentCommit();
        if (commit != null) action.accept(commit);
    }

    /** Runs {@code action} against the stash on the current row, if there is one. */
    private void onStash(java.util.function.Consumer<com.jfx.git.model.StashEntry> action) {
        var stash = currentStash();
        if (stash != null) action.accept(stash);
    }

    /**
     * What is wrong with {@code typed} as a branch name, or null: git's own ref-name rules,
     * so the prompt refuses a name before it closes rather than the create failing after.
     */
    static String branchNameProblem(String typed) {
        return branchNameProblem(typed, null);
    }

    /**
     * What git would refuse about {@code typed} as a new branch - its ref-name rules, then a
     * name already in use - or null; see {@link com.jfx.git.model.RefNames#newBranchNameProblem}.
     *
     * @param taken the local branch names, or null when not known
     */
    static String branchNameProblem(String typed, java.util.Collection<String> taken) {
        return com.jfx.git.model.RefNames.newBranchNameProblem(typed.trim(), taken);
    }

    /** The local branches a new one must not be named after, from the page's snapshot. */
    private java.util.List<String> takenBranches() {
        return metrics == null ? null : metrics.localBranches();
    }

    /**
     * Replaces the style classes on {@code node}, but only when they actually differ.
     *
     * <p>{@code setAll} fires a list change and forces a CSS re-application on the node
     * even when the new classes are identical to the old ones. On a virtualised list
     * that is a style recalculation for every visible row on every scroll tick, for a
     * result that is almost always the same as what was already there.
     */
    /**
     * Re-reads whether the row this cell is showing should be faded.
     *
     * <p>Called both when the cell takes a new row and when the selection moves. The
     * second is why it exists: updating in place keeps the fade in the same frame as the
     * selection without disturbing the virtual flow.
     *
     * <p>Synthetic rows never fade — the WIP node and stashes are not commits and have no
     * ancestry to be outside of.
     */
    private void refreshFade() {
        GitGraphRow row = getItem();
        applyFade(highlight != null
                && row != null
                && !row.isSynthetic()
                && row.getCommit() != null
                && highlight.isFaded(row.getCommit().getHash()));
    }

    /**
     * Fades or unfades the whole row.
     *
     * <p>Toggled on the row rather than set every time: a {@code ListView} calls
     * {@code updateItem} for every row that scrolls past, and adding a class already
     * present would grow the list without bound.
     */
    /**
     * Dims the message column of an unrelated row.
     *
     * <h2>The message, not the row</h2>
     * Fading the whole row takes the lanes, the ref chips and the avatar down with the
     * text - and the lanes are the one thing you are reading when you ask what a branch
     * contains. Commercial clients dim only the subject, which is what leaves the graph legible
     * while the answer is on screen: the shape stays, the prose recedes.
     */
    private void applyFade(boolean faded) {
        boolean already = messageBox.getStyleClass().contains("graph-message-faded");
        if (faded && !already) {
            messageBox.getStyleClass().add("graph-message-faded");
        } else if (!faded && already) {
            messageBox.getStyleClass().remove("graph-message-faded");
        }
    }

    private static void styleAs(javafx.scene.Node node, String... classes) {
        var current = node.getStyleClass();
        if (current.size() == classes.length) {
            boolean same = true;
            for (int i = 0; i < classes.length; i++) {
                if (!classes[i].equals(current.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) return;
        }
        current.setAll(classes);
    }

    // ------------------------------------------------------------------
    // Ref chip helpers
    // ------------------------------------------------------------------

    private void hideAllChips() {
        // The overlay goes too: this cell is about to show a different commit, and a name
        // left floating from the previous one would be pointing at the wrong row.
        hoverChip.setVisible(false);
        endHover();
        hideGhostChip();
        for (Label chip : chipPool) {
            chip.setVisible(false);
            chip.setManaged(false);
        }
    }

    /**
     * Shows or hides the faint branch name a hovered commit belongs to.
     *
     * <p>Only for commits with no ref of their own. A tip already wears its chip, and
     * printing the same name twice in the same rail would read as two branches.
     *
     * <p>Nothing is added to or removed from the rail: the chip lives there permanently
     * and is unmanaged when idle, so appearing cannot reflow the row or shift the message
     * column sideways as the pointer travels down the graph.
     */
    private void showGhostChip(boolean hovering) {
        GitGraphRow row = getItem();
        CommitInfo commit = row == null ? null : row.getCommit();
        if (!hovering || ghosts == null || commit == null
                || row.isSynthetic() || !commit.getRefs().isEmpty()) {
            hideGhostChip();
            return;
        }

        String branch = ghosts.get().branchFor(commit.getHash());
        if (branch == null || branch.isBlank()) {
            hideGhostChip();
            return;
        }
        ghostChip.setText(branch);
        ghostChip.setVisible(true);
        ghostChip.setManaged(true);
    }

    private void hideGhostChip() {
        ghostChip.setVisible(false);
        ghostChip.setManaged(false);
    }

    /** The pooled chip at {@code index}, created and added to the rail if it is new. */
    private Label chipAt(int index) {
        while (index >= chipPool.size()) {
            Label chip = new Label();

            chip.setOnMouseEntered(e -> {
                // Named chips lift to show the full name; the "+N" chip does not. Its
                // text is "+3", so an overlay of it says nothing the row does not already
                // say - what a reader wants there is the list of what is hidden, and that
                // is the one place a tooltip earns its keep.
                if (chip.getUserData() instanceof RefRail.Kind) {
                    showFullName(chip);
                }
                hoverBranchAt(chip);
            });
            chip.setOnMouseExited(e -> {
                hoverChip.setVisible(false);
                endHover();
            });
            installRefDrag(chip);

            chipPool.add(chip);
            tagsBox.getChildren().add(chip);
        }
        return chipPool.get(index);
    }

    /**
     * Widest a remote-only chip may be <em>when it is sharing the rail</em>.
     *
     * <p>Left to itself an {@code HBox} shrinks every child by the same proportion, so a
     * long {@code origin/release-0.9} beside a short {@code v0.9.0} truncated both and the
     * tag came out as "v…". A remote with no local counterpart on this commit is the least
     * useful thing on the rail, so it is the one that yields.
     *
     * <p>Only when it is actually competing, though. Applying this to a lone chip is what
     * clipped {@code origin/njh/attention-free-models} down to {@code origin/njh…} while
     * most of the rail sat empty beside it.
     */
    private static final double SHARED_REMOTE_CHIP_MAX = 96.0;

    private static void show(Label chip) {
        chip.setVisible(true);
        chip.setManaged(true);
    }

    /**
     * How many chips are drawn before the rest become a count.
     *
     * <p>Read from the rail's actual width. The column sizes itself to hold its widest
     * name once, so at any width it chooses for itself there is room for one chip and a
     * {@code +n}; splitting that between two chips would shorten both, and the column
     * exists so that a name can be read. Two or three are shown only once the reader has
     * dragged the column wide enough to hold them.
     */
    private int maxChips() {
        double width = metrics.railWidth();
        if (width >= 320) return 3;
        return width >= 230 ? 2 : 1;
    }

    /**
     * Shows {@code chip}'s full name, floating over the row.
     *
     * <p>A separate label rather than the chip growing. The rail is only wide enough for a
     * few characters, so most names are clipped and the pointer is how you read them —
     * which makes this a hot path for the eye, and it must not disturb anything. The
     * overlay is {@code managed = false}, so the layout does not see it at all: no column
     * resizes, no sibling chip shifts, no reflow of the row. It is positioned in the row's
     * own coordinates over the chip it stands for and grows rightwards over the graph and
     * the message, because the rail is the first column and a name expanding leftwards
     * would run straight off the edge of the window.
     */
    private void showFullName(Label chip) {
        String full = chip.getText();
        if (full == null || full.isBlank()) return;

        // Nothing to expand when the name already fits. The overlay exists because the
        // rail truncates; over a chip reading "main" in full it drew "main" again, one
        // pixel higher and with a shadow - the same string twice for no reason. A small
        // tolerance because a label's width and its preferred width differ by rounding
        // even when nothing is cut.
        if (chip.getWidth() >= chip.prefWidth(-1) - 0.5) {
            hoverChip.setVisible(false);
            return;
        }

        hoverChip.setText(full);
        // Same classes, so the overlay is the same chip - a tag still reads as a tag, and
        // HEAD keeps its emphasis - with the shadow marking it as lifted off the row.
        hoverChip.getStyleClass().setAll(chip.getStyleClass());
        hoverChip.getStyleClass().add("ref-chip-expanded");
        hoverChip.setVisible(true);

        // The style classes set above decide this label's font, padding and border, and
        // none of them are in effect until CSS has been applied to it. Asking for a width
        // first measures an unstyled label, comes back short, and the label then
        // ellipsises the very name it exists to show in full - which is how hovering
        // "main" produced "● …" over a chip that was displaying "main" perfectly well
        // underneath. A node not yet in a scene has no CSS to apply, so it is skipped
        // rather than measured wrongly.
        if (hoverChip.getScene() == null) {
            hoverChip.setVisible(false);
            return;
        }
        hoverChip.applyCss();

        double width = hoverChip.prefWidth(-1);
        if (width <= 0) {
            hoverChip.setVisible(false);
            return;
        }

        var bounds = root.sceneToLocal(chip.localToScene(chip.getBoundsInLocal()));
        hoverChip.resize(width, bounds.getHeight());
        hoverChip.relocate(bounds.getMinX(), bounds.getMinY());
        hoverChip.toFront();
    }

    private static String remainingNames(java.util.List<RefRail.Chip> chips, int from) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < chips.size(); i++) {
            if (i > from) text.append('\n');
            text.append(chips.get(i).name());
        }
        return text.toString();
    }

    /**
     * Applies name and style to a pooled chip label in-place.
     *
     * <p>The HEAD marker and the tracked mark are drawn into the text rather than as extra
     * nodes: a chip is already the smallest thing on the row, and two more nodes each
     * would triple the rail's node count for two characters.
     */
    /**
     * Fades everything off the branch this chip names.
     *
     * <p>Branches only. A tag marks a point rather than a line of work, and a tag's
     * ancestry is the ancestry of whatever commit it happens to sit on - dimming the
     * graph for it would answer a question nobody asked.
     */
    private void hoverBranchAt(Label chip) {
        if (highlight == null || !(chip.getUserData() instanceof RefRail.Kind kind)) {
            return;
        }
        if (kind == RefRail.Kind.TAG) {
            return;
        }
        GitGraphRow row = getItem();
        if (row == null || row.isSynthetic() || row.getCommit() == null) {
            return;
        }
        highlight.hoverBranch(row.getCommit().getHash());
        drivingHover = true;
    }

    /** Ends a hover this cell started, however it is ending. */
    private void endHover() {
        if (drivingHover) {
            drivingHover = false;
            if (highlight != null) {
                highlight.clearHover();
            }
        }
    }

    /** Where a chip keeps the branch it stands for; see {@link #installRefDrag}. */
    private static final String CHIP_REF = "lyra.chip-ref";

    /**
     * The branch {@code spec} can be dragged as, or {@code null} for a chip that is not a
     * branch: a tag, and the "HEAD" chip of a detached checkout, which names no branch.
     */
    static com.jfx.git.ui.RefDropMenu.Ref draggableRef(RefRail.Chip spec) {
        if (spec == null) return null;
        return switch (spec.kind()) {
            case LOCAL -> "HEAD (detached)".equals(spec.tooltip())
                    ? null : com.jfx.git.ui.RefDropMenu.Ref.local(spec.name());
            case REMOTE -> com.jfx.git.ui.RefDropMenu.Ref.remote(spec.name());
            case TAG -> null;
        };
    }

    private static com.jfx.git.ui.RefDropMenu.Ref refOf(Label chip) {
        return chip.getProperties().get(CHIP_REF) instanceof com.jfx.git.ui.RefDropMenu.Ref ref ? ref : null;
    }

    /**
     * Makes a chip something to drag onto another branch, and to drop one on.
     *
     * <p>Registered once, when the chip is created, and reading the branch from the chip
     * at the moment of the gesture: chips are pooled, and the one that was {@code main}
     * on this row is {@code v1.0} on the next. The dragboard carries the same text the
     * sidebar's rows do, so a chip can be dropped on a sidebar row and a row on a chip.
     */
    private void installRefDrag(Label chip) {
        chip.setOnDragDetected(e -> {
            com.jfx.git.ui.RefDropMenu.Ref ref = refOf(chip);
            if (ref == null) return;
            // The lifted name would otherwise hang over the row for the whole drag.
            hoverChip.setVisible(false);
            javafx.scene.input.Dragboard db = chip.startDragAndDrop(javafx.scene.input.TransferMode.ANY);
            ClipboardContent content = new ClipboardContent();
            content.putString(ref.encode());
            db.setContent(content);
            com.jfx.git.ui.RefDropMenu.dragImage(db, chip);
            e.consume();
        });
        chip.setOnDragOver(e -> {
            com.jfx.git.ui.RefDropMenu.Ref source =
                    com.jfx.git.ui.RefDropMenu.Ref.decode(e.getDragboard().getString());
            if (e.getGestureSource() != chip && com.jfx.git.ui.RefDropMenu.accepts(source, refOf(chip))) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY_OR_MOVE);
                com.jfx.git.ui.RefDropMenu.markTarget(chip, true);
            }
            e.consume();
        });
        chip.setOnDragExited(e -> com.jfx.git.ui.RefDropMenu.markTarget(chip, false));
        chip.setOnDragDropped(e -> {
            com.jfx.git.ui.RefDropMenu.markTarget(chip, false);
            com.jfx.git.ui.RefDropMenu.Ref source =
                    com.jfx.git.ui.RefDropMenu.Ref.decode(e.getDragboard().getString());
            com.jfx.git.ui.RefDropMenu.Ref target = refOf(chip);
            boolean ok = com.jfx.git.ui.RefDropMenu.accepts(source, target);
            if (ok) {
                refDrops.show(chip, e.getScreenX(), e.getScreenY(), source, target, currentBranchOrNull());
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /**
     * The branch a drop anywhere on this row is taken to mean: its local branch if it has
     * one, else its remote branch. {@code null} for a row with no branch on it.
     */
    private com.jfx.git.ui.RefDropMenu.Ref rowRef() {
        GitGraphRow row = getItem();
        if (row == null || row.isSynthetic() || row.getCommit() == null) return null;
        com.jfx.git.ui.RefDropMenu.Ref remote = null;
        for (RefRail.Chip spec : RefRail.chipsOf(row.getCommit(), currentBranchOrNull())) {
            com.jfx.git.ui.RefDropMenu.Ref ref = draggableRef(spec);
            if (ref == null) continue;
            if (ref.kind() == com.jfx.git.ui.RefDropMenu.Kind.LOCAL) return ref;
            if (remote == null) remote = ref;
        }
        return remote;
    }

    /**
     * Lets a branch be dropped on the row rather than on the chip.
     *
     * <p>A chip is a few characters wide in a column sized to hold one name, which is a
     * small thing to hit at the end of a drag down a long graph. The row is the target
     * the eye is already on. A drop that lands on a chip is handled by the chip, which
     * knows exactly which branch it is; this is for everywhere else on the row.
     */
    private void installRowDrop() {
        setOnDragOver(e -> {
            com.jfx.git.ui.RefDropMenu.Ref source =
                    com.jfx.git.ui.RefDropMenu.Ref.decode(e.getDragboard().getString());
            if (com.jfx.git.ui.RefDropMenu.accepts(source, rowRef())) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY_OR_MOVE);
                com.jfx.git.ui.RefDropMenu.markTarget(this, true);
            }
            e.consume();
        });
        setOnDragExited(e -> com.jfx.git.ui.RefDropMenu.markTarget(this, false));
        setOnDragDropped(e -> {
            com.jfx.git.ui.RefDropMenu.markTarget(this, false);
            com.jfx.git.ui.RefDropMenu.Ref source =
                    com.jfx.git.ui.RefDropMenu.Ref.decode(e.getDragboard().getString());
            com.jfx.git.ui.RefDropMenu.Ref target = rowRef();
            boolean ok = com.jfx.git.ui.RefDropMenu.accepts(source, target);
            if (ok) {
                refDrops.show(this, e.getScreenX(), e.getScreenY(), source, target, currentBranchOrNull());
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    private void updateChipLabel(Label chip, RefRail.Chip spec, int shownOnRow, Color lane) {
        chip.setText(GraphMetrics.chipText(spec));
        // Pooled, so written on every use: what this chip can be dragged as, if anything.
        com.jfx.git.ui.RefDropMenu.Ref ref = draggableRef(spec);
        if (ref == null) {
            chip.getProperties().remove(CHIP_REF);
        } else {
            chip.getProperties().put(CHIP_REF, ref);
        }
        // No tooltip on a chip that carries a name. Hovering already lifts the chip and
        // shows the name in full, in the chip's own styling and without a delay, so a
        // tooltip repeating it put the same string on screen twice - identically so for a
        // remote chip, whose tooltip text was its name and nothing else. Cleared rather
        // than skipped because chips are pooled: this one may have been the "+N" chip on
        // a previous row, and that one does have a tooltip.
        setTip(chip, null);
        // Chips are pooled across rows, so the handler registered once at creation reads
        // the kind from here rather than closing over a spec that has since moved on.
        chip.setUserData(spec.kind());

        chip.setMaxWidth(shownOnRow > 1 && spec.kind() == RefRail.Kind.REMOTE
                ? SHARED_REMOTE_CHIP_MAX
                : metrics.railWidth() - 10.0);

        String styleClass = switch (spec.kind()) {
            case TAG -> "ref-chip-tag";
            case REMOTE -> "ref-chip-remote";
            case LOCAL -> "ref-chip-local";
        };
        if (spec.head()) {
            styleAs(chip, "ref-chip", styleClass, "ref-chip-head");
        } else {
            styleAs(chip, "ref-chip", styleClass);
        }
        chip.setStyle(laneStyle(spec, lane));
    }

    /**
     * A branch chip in its lane's colour, so the name and the line it labels read as one
     * thing - the link commercial clients draw, and the one a graph of a dozen lanes most needs.
     * A tag keeps its own green pennant: it marks a commit, not a lane. Remote chips keep
     * their dashed outline and quiet text, in the lane's colour.
     */
    public static String laneStyle(RefRail.Chip spec, Color lane) {
        if (lane == null || spec.kind() == RefRail.Kind.TAG) return "";
        String[] styles = LANE_STYLES.computeIfAbsent(lane, GitGraphCell::laneStyles);
        if (spec.head()) return styles[0];
        return spec.kind() == RefRail.Kind.REMOTE ? styles[1] : styles[2];
    }

    /**
     * Each lane colour's three chip styles - HEAD, remote, local - built once.
     *
     * <p>Every chip on every row asked for its style afresh, through three
     * {@code String.format} calls, to arrive at one of a few dozen strings. Handing back
     * the same instance also lets {@code setStyle} see an unchanged value and skip the
     * CSS pass a new string would cost it. Keyed by the colour itself, so a theme change -
     * a different palette - simply finds different keys.
     */
    private static final java.util.Map<Color, String[]> LANE_STYLES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String[] laneStyles(Color lane) {
        String solid = hex(lane, 1);
        return new String[]{
                "-fx-background-color: " + solid + "; -fx-border-color: " + solid
                        + "; -fx-text-fill: " + hex(GraphPalette.textOn(lane), 1) + ";",
                "-fx-border-color: " + solid + ";",
                "-fx-border-color: " + solid + "; -fx-background-color: " + hex(lane, 0.22) + ";"
        };
    }

    private static String hex(Color c, double alpha) {
        return String.format(java.util.Locale.ROOT, "rgba(%d, %d, %d, %.2f)",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255), alpha * c.getOpacity());
    }

    // ------------------------------------------------------------------
    // Row chrome
    // ------------------------------------------------------------------

    /**
     * One tooltip per node, created once and retargeted.
     *
     * <p>A {@code Tooltip} is a {@code PopupControl}, so building one per row per tick
     * would undo most of what the rest of this class is careful about. They cannot be
     * shared between columns either — a tooltip carries a single text, so one instance
     * across the age, hash and author columns would show whichever was written last on
     * all three.
     */
    private final java.util.Map<javafx.scene.Node, javafx.scene.control.Tooltip> tooltips =
            new java.util.IdentityHashMap<>();

    /** Sets {@code node}'s tooltip text, creating its tooltip the first time. */
    private void setTip(javafx.scene.Node node, String text) {
        lazyTips.remove(node);
        if (text == null || text.isBlank()) {
            // Uninstalled, not blanked. A Tooltip left installed with empty text still
            // pops up - as an empty box - so blanking one is not the same as removing it,
            // and pooled nodes really do get their tooltip taken away: a chip that was
            // the "+N" chip on one row carries a name on the next.
            var existing = tooltips.remove(node);
            if (existing != null) {
                javafx.scene.control.Tooltip.uninstall(node, existing);
            }
            return;
        }
        tipFor(node).setText(text);
    }

    /**
     * Gives {@code node} a tooltip whose text is worked out when it is about to show.
     *
     * <p>For text that costs something to build and is almost never read: the absolute
     * date under an age, formatted through a {@code DateTimeFormatter}, and the hash
     * under "Click to copy". Both were built for every row of every scroll tick, to sit
     * in a tooltip nobody hovered. The supplier reads the row the cell is showing when
     * the tooltip opens, so recycling cannot leave it describing an earlier row.
     */
    private void setLazyTip(javafx.scene.Node node, Supplier<String> text) {
        tipFor(node);
        lazyTips.put(node, text);
    }

    private javafx.scene.control.Tooltip tipFor(javafx.scene.Node node) {
        return tooltips.computeIfAbsent(node, n -> {
            var created = new javafx.scene.control.Tooltip();
            created.setShowDelay(javafx.util.Duration.millis(400));
            created.setOnShowing(e -> {
                Supplier<String> lazy = lazyTips.get(n);
                if (lazy != null) {
                    created.setText(lazy.get());
                }
            });
            javafx.scene.control.Tooltip.install(n, created);
            return created;
        });
    }

    /** Tooltip texts computed on showing; see {@link #setLazyTip}. */
    private final java.util.Map<javafx.scene.Node, Supplier<String>> lazyTips =
            new java.util.IdentityHashMap<>();

    /** The absolute time under the age column, for whichever row the cell now shows. */
    private final Supplier<String> ageTip = () -> {
        GitGraphRow row = getItem();
        if (row == null) return "";
        if (row.isStash()) return com.jfx.git.ui.DateDisplay.other(row.getStash().when());
        return row.getCommit() == null ? "" : com.jfx.git.ui.DateDisplay.other(row.getCommit().getAuthorDate());
    };

    private final Supplier<String> hashTip = () -> {
        CommitInfo commit = currentCommit();
        return commit == null ? "" : "Click to copy " + commit.getHash();
    };

    /** Hides the dimmed body run, for rows that are not commits. */
    private void clearBody() {
        bodyLabel.setText("");
        bodyLabel.setVisible(false);
        bodyLabel.setManaged(false);
    }

    /** Blanks the author, age and hash columns for a row that has none of them. */
    private void clearMeta() {
        avatar.clearAuthor();
        avatar.setVisible(false);
        avatar.setManaged(false);
        setTip(avatar, null);
        // With the author node there is no avatar column to blank, and the node itself is
        // cleared with the rest of the canvas.
        ageLabel.setText("");
        setTip(ageLabel, null);
        styleAs(ageLabel, "graph-age");
        hashLabel.setText("");
        setTip(hashLabel, null);
    }

    /**
     * Washes the row in its branch's colour, and colours the leading edge strip.
     *
     * <p>Derived from the lane rather than given a scale of its own: the band down a run
     * of commits and the line joining them are the same fact stated twice, and they stop
     * agreeing the moment they are maintained separately. Synthetic rows pass {@code null}
     * — a stash or the load-more row belongs to no branch, and tinting it would claim it
     * did.
     */
    private void paintRow(Color laneColor) {
        if (laneColor == null || !tintRows) {
            root.setBackground(null);
            edgeStrip.setBackground(null);
            return;
        }
        root.setBackground(tintBackground(laneColor, density.tintAlpha()));
        if (density.edgeWidth() > 0) {
            edgeStrip.setBackground(solidBackground(laneColor));
        }
    }

    /**
     * Backgrounds by colour, built once.
     *
     * <p>There are eight lane colours and two alphas, so this settles at a handful of
     * entries within the first screenful and allocates nothing afterwards. Building a
     * {@code Background} per row per scroll tick is the same mistake as building a menu
     * there, in a smaller coat.
     *
     * <p>Static, and shared by every cell: the values depend only on the colour, and the
     * thirty live cells would otherwise each keep their own copy of the same eight.
     */
    private static final java.util.Map<Color, Background> TINT_BACKGROUNDS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Color, Background> SOLID_BACKGROUNDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Background tintBackground(Color laneColor, double alpha) {
        return TINT_BACKGROUNDS.computeIfAbsent(laneColor, c -> new Background(
                new BackgroundFill(GraphPalette.rowTint(c, alpha), CornerRadii.EMPTY, Insets.EMPTY)));
    }

    private static Background solidBackground(Color color) {
        return SOLID_BACKGROUNDS.computeIfAbsent(color, c -> new Background(
                new BackgroundFill(c, CornerRadii.EMPTY, Insets.EMPTY)));
    }

    /** Drops the cached backgrounds; the palette they were built from has changed. */
    public static void clearBackgroundCache() {
        TINT_BACKGROUNDS.clear();
        SOLID_BACKGROUNDS.clear();
    }

    // ------------------------------------------------------------------
    // Context menu
    // ------------------------------------------------------------------

    /**
     * Starts an interactive rebase from {@code from}.  The commit range is read on a
     * worker thread; the editor is shown on the FX thread; the rebase runs on a worker.
     */
    private void startInteractiveRebase(CommitInfo from) {
        GitTaskRunner.query(gitService,
                () -> gitService.getRebaseRange(from.getHash()),
                range -> {
                    if (!range.isUsable()) {
                        statusBar.error(range.problem());
                        return;
                    }
                    var dialog = new com.jfx.git.ui.dialogs.InteractiveRebaseDialog(
                            com.jfx.git.core.RebaseTodo.fromNewestFirst(
                                    range.commits(), range.base()));
                    dialog.showAndWait().ifPresent(plan ->
                            run(() -> gitService.rebaseInteractive(plan), "Rebasing " + plan.size() + " commits"));
                },
                statusBar::error);
    }

    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem checkout = new MenuItem("Checkout commit (detached HEAD)");
        checkout.setOnAction(e -> onCommit(commit -> {
            // Undo on the toolbar steps back over a checkout, so Enter checks out.
            if (Dialogs.confirm("Check out commit")
                    .icon(Icons.commit())
                    .title("Check out `" + commit.getShortHash() + "`?")
                    .subtitle("Detached HEAD: on this commit, not on a branch")
                    .message("Commits you make here belong to no branch until you create one."
                            + " The banner that appears offers Create branch here.")
                    .action("Check out commit")
                    .ask()) {
                // Like every checkout, one that local changes are in the way of offers auto-stash.
                operations.operation("Checking out " + commit.getShortHash(), OperationRunner.Refresh.EVERYTHING)
                        .withAutoStash(com.jfx.git.core.AutoStash.Operation.CHECKOUT,
                                () -> gitService.checkoutBranch(commit.getHash()));
            }
        }));

        MenuItem branchHere = new MenuItem("Create branch here…");
        branchHere.setOnAction(e -> onCommit(commit ->
                Dialogs.prompt("New branch")
                        .icon(Icons.branch())
                        .title("Create a branch at `" + commit.getShortHash() + "`")
                        .subtitle(commit.getSubject())
                        .label("Branch name")
                        .placeholder("feature/search")
                        .hint("The new branch is checked out straight away.")
                        .validator(text -> branchNameProblem(text, takenBranches()))
                        .action("Create and check out")
                        .ask()
                        .map(String::trim)
                        .filter(name -> !name.isBlank())
                        // Checked out straight away: a checkout, so it offers auto-stash too.
                        .ifPresent(name -> operations.operation("Creating branch", OperationRunner.Refresh.EVERYTHING)
                                .withAutoStash(com.jfx.git.core.AutoStash.Operation.CHECKOUT,
                                        () -> gitService.createBranch(name, commit.getHash(), true)))));

        MenuItem tagHere = new MenuItem("Create tag here…");
        tagHere.setOnAction(e -> onCommit(commit ->
                new com.jfx.git.ui.dialogs.NewTagDialog("On `" + commit.getShortHash() + "` · " + commit.getSubject())
                        .showAndWait()
                        .filter(r -> !r.name().isBlank())
                        .ifPresent(r -> run(
                                () -> gitService.createTag(r.name(), r.messageOrNull(), commit.getHash()),
                                "Creating tag"))));

        MenuItem cherryPick = new MenuItem("Cherry-pick onto current branch");
        cherryPick.setOnAction(e -> onCommit(commit -> chooseMainline(commit, "Cherry-pick")
                .ifPresent(parent -> run(() -> gitService.cherryPick(commit.getHash(), parent),
                        "Cherry-picking " + commit.getShortHash()))));

        MenuItem revert = new MenuItem("Revert commit");
        revert.setOnAction(e -> onCommit(commit -> chooseMainline(commit, "Revert")
                .ifPresent(parent -> run(() -> gitService.revert(commit.getHash(), parent),
                        "Reverting " + commit.getShortHash()))));

        MenuItem resetSoft = new MenuItem("Reset current branch here (soft)");
        resetSoft.setOnAction(e -> onCommit(commit ->
                run(() -> gitService.reset(commit.getHash(), ResetMode.SOFT), "Resetting (soft)")));

        MenuItem resetMixed = new MenuItem("Reset current branch here (mixed)");
        resetMixed.setOnAction(e -> onCommit(commit ->
                run(() -> gitService.reset(commit.getHash(), ResetMode.MIXED), "Resetting (mixed)")));

        MenuItem resetHard = new MenuItem("Reset current branch here (hard)");
        resetHard.setOnAction(e -> onCommit(commit -> {
            // Both halves can be brought back - the uncommitted work from the snapshot
            // guarded() takes, the branch by Undo - so Enter resets.
            String branch = currentBranchOrNull();
            if (Dialogs.destructive("Reset branch")
                    .icon(Icons.undo())
                    .title("Hard reset " + (branch == null ? "the current branch" : "`" + branch + "`")
                            + " to `" + commit.getShortHash() + "`?")
                    .subtitle(commit.getSubject())
                    .message("Uncommitted changes to tracked files are thrown away. A snapshot of them"
                            + " is taken first, which Working-tree snapshots can restore, and Undo on"
                            + " the toolbar moves the branch back.")
                    .action("Reset hard")
                    .ask()) {
                run(com.jfx.git.ui.SnapshotGuard.guarded(gitService,
                        "Before a hard reset to " + commit.getShortHash(),
                        () -> gitService.reset(commit.getHash(), ResetMode.HARD)), "Resetting (hard)");
            }
        }));

        MenuItem rebaseInteractive = new MenuItem("Interactive rebase from here…");
        rebaseInteractive.setOnAction(e -> onCommit(this::startInteractiveRebase));

        // One commit changed in place: the rebase editor's plan, without the editor.
        MenuItem reword = new MenuItem("Reword…");
        reword.setOnAction(e -> onCommit(historyEdits::reword));
        MenuItem drop = new MenuItem("Drop commit…");
        drop.setOnAction(e -> onCommit(historyEdits::drop));
        MenuItem moveUp = new MenuItem("Move up");
        moveUp.setOnAction(e -> onCommit(historyEdits::moveUp));
        MenuItem moveDown = new MenuItem("Move down");
        moveDown.setOnAction(e -> onCommit(historyEdits::moveDown));
        List<MenuItem> singleEdits = List.of(reword, drop, moveUp, moveDown);

        // Relabelled when the menu opens: one commit, or however many are selected.
        MenuItem savePatch = new MenuItem("Save as patch…");
        savePatch.setOnAction(e -> patches.saveAsPatch(patchTargets(),
                getScene() == null ? null : getScene().getWindow()));
        MenuItem copyPatch = new MenuItem("Copy as patch");
        copyPatch.setOnAction(e -> patches.copyAsPatch(patchTargets()));

        MenuItem copyHash = new MenuItem("Copy commit hash");
        copyHash.setOnAction(e -> onCommit(commit -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(commit.getHash());
            Clipboard.getSystemClipboard().setContent(content);
            statusBar.info("Copied " + commit.getShortHash());
        }));

        // Starts a session with this row as the known-bad end - the ordinary way to
        // begin a bisect is "this is where I noticed the problem". The other end (a
        // known-good commit) and every mark after that come from the banner
        // RightPanelManager shows once a session is active, not from this menu -
        // by then the checked-out candidate, not an arbitrary graph row, is what a
        // mark actually has to apply to.
        MenuItem bisectStartHere = new MenuItem("Bisect: start here (mark bad)…");
        bisectStartHere.setOnAction(e -> onCommit(commit -> {
            if (Dialogs.confirm("Start bisect")
                    .icon(Icons.search())
                    .title("Start a bisect with `" + commit.getShortHash() + "` as bad?")
                    .subtitle(commit.getSubject())
                    .message("Mark a known-good commit next, from the banner that appears. Git then"
                            + " checks out the commits in between, one at a time, for you to test.")
                    .action("Start bisect")
                    .ask()) {
                // Two steps, one operation: the session, then this row marked bad in it.
                operations.operation("Starting bisect", OperationRunner.Refresh.EVERYTHING).start(steps ->
                        steps.run(gitService::bisectStart, started -> {
                            if (!started.isOk()) {
                                steps.finish(started);
                                return;
                            }
                            steps.read(() -> gitService.bisectMarkBad(commit.getHash()),
                                    status -> steps.finish(GitResult.ok(status.message())));
                        }));
            }
        }));

        // --- Multi-selection entries ---
        // Built once and relabelled when the menu opens, because how many commits are
        // selected is not known until then.
        MenuItem squash = new MenuItem("Squash commits…");
        squash.setOnAction(e -> squashSelected());

        MenuItem compare = new MenuItem("Compare these two commits");
        compare.setOnAction(e -> compareSelected());

        MenuItem cherryPickMany = new MenuItem("Cherry-pick these commits…");
        cherryPickMany.setOnAction(e -> cherryPickSelected());

        // One handler for everything that depends on state at open time. ContextMenu has
        // a single onShowing property, so a second assignment would silently discard the
        // first rather than adding to it.
        menu.setOnShowing(e -> {
            updateMultiSelectionItems(squash, compare, cherryPickMany);
            // They act on the one row that was clicked, which with several selected is
            // not obviously which; the selection has its own entries.
            boolean single = selectedCommits().size() <= 1;
            singleEdits.forEach(item -> item.setVisible(single));
            int patched = patchTargets().size();
            savePatch.setText(patched > 1 ? "Save " + patched + " commits as patch…" : "Save as patch…");
            copyPatch.setText(patched > 1 ? "Copy " + patched + " commits as patch" : "Copy as patch");

            GitGraphRow row = getItem();
            CommitInfo commit = row == null ? null : row.getCommit();
            if (commit == null) return;
            String hash = commit.getHash();

            // Eligibility needs a real ancestry test, so it is answered when the menu
            // opens rather than for every row the graph draws.
            //
            // The row's own onCurrentBranch flag is not enough: it comes from a walk
            // bounded by the page size and defaults to true for anything older, so
            // deriving the menu from it silently offers revert on commits that are not on
            // this branch and refuses cherry-pick on commits that are. Asking the backend
            // costs one walk, on a worker thread, only when a menu is actually opened.
            applyEligibility(new CommitPosition(
                            row.isOnCurrentBranch(),
                            commit.getParentHashes().size() > 1,
                            commit.getParentHashes().isEmpty(),
                            true,
                            // Menu time is the wrong place for a remote walk; the
                            // force-push warning does its own check when it matters.
                            false),
                    cherryPick, revert, rebaseInteractive, singleEdits);

            GitTaskRunner.query(gitService,
                    () -> gitService.getCommitPosition(hash),
                    position -> applyEligibility(position, cherryPick, revert, rebaseInteractive, singleEdits),
                    statusBar::error);

            // A second "start here" while one is already running would silently
            // discard the marks already made - refused rather than offered, the same
            // choice this menu makes for cherry-pick/revert eligibility above.
            GitTaskRunner.query(gitService, () -> gitService.bisectStatus(),
                    status -> {
                        bisectStartHere.setDisable(status.active());
                        bisectStartHere.setText(status.active()
                                ? "Bisect: session already running (end it from the banner)"
                                : "Bisect: start here (mark bad)…");
                    },
                    error -> { });
        });

        menu.getItems().addAll(
                checkout, branchHere, tagHere, new SeparatorMenuItem(),
                bisectStartHere, new SeparatorMenuItem(),
                cherryPick, revert, rebaseInteractive, new SeparatorMenuItem(),
                reword, drop, moveUp, moveDown, new SeparatorMenuItem(),
                squash, compare, cherryPickMany, new SeparatorMenuItem(),
                resetSoft, resetMixed, resetHard, new SeparatorMenuItem(),
                savePatch, copyPatch, copyHash);
        return menu;
    }

    // ------------------------------------------------------------------
    // Multi-selection
    // ------------------------------------------------------------------

    /**
     * Shows, hides and labels the entries that act on more than one commit.
     *
     * <p>They stay hidden for a single selection rather than being disabled: a menu of
     * greyed-out entries for the common case is noise.
     */
    private void updateMultiSelectionItems(MenuItem squash, MenuItem compare, MenuItem cherryPickMany) {
        List<CommitInfo> selected = selectedCommits();
        int count = selected.size();
        boolean many = count > 1;

        squash.setVisible(many);
        cherryPickMany.setVisible(many);
        compare.setVisible(count == 2);
        if (!many) return;

        cherryPickMany.setText("Cherry-pick " + count + " commits…");

        // Squashing rewrites a contiguous run of history; a gap means the commits in
        // between would be folded in too, which is not what selecting around them meant.
        boolean contiguous = isContiguousSelection(selected);
        squash.setDisable(!contiguous);
        squash.setText(contiguous
                ? "Squash " + count + " commits into one…"
                : "Squash (select commits with no gaps between them)");
    }

    /**
     * What "as patch" acts on: the selection when several commits are selected and the
     * row that was clicked is one of them, otherwise that row alone.
     */
    private List<CommitInfo> patchTargets() {
        CommitInfo clicked = currentCommit();
        List<CommitInfo> selected = selectedCommits();
        if (selected.size() > 1 && clicked != null
                && selected.stream().anyMatch(c -> c.getHash().equals(clicked.getHash()))) {
            return selected;
        }
        GitGraphRow row = getItem();
        return clicked == null || row == null || row.isSynthetic() ? List.of() : List.of(clicked);
    }

    /** Selected commit rows, newest first, ignoring synthetic rows. */
    private List<CommitInfo> selectedCommits() {
        if (getListView() == null) return List.of();
        List<CommitInfo> commits = new ArrayList<>();
        for (GitGraphRow row : getListView().getSelectionModel().getSelectedItems()) {
            if (row != null && !row.isSynthetic() && row.getCommit() != null) {
                commits.add(row.getCommit());
            }
        }
        return commits;
    }

    /**
     * Whether the selection is a run of adjacent rows.
     *
     * <p>Checked against displayed positions rather than parentage: the graph is already
     * in topological order, and a selection with a visible gap is one the user did not
     * mean to squash whatever sits in the gap into.
     */
    private boolean isContiguousSelection(List<CommitInfo> selected) {
        var indices = new ArrayList<>(getListView().getSelectionModel().getSelectedIndices());
        if (indices.size() != selected.size()) return false;
        java.util.Collections.sort(indices);
        for (int i = 1; i < indices.size(); i++) {
            if (indices.get(i) != indices.get(i - 1) + 1) return false;
        }
        return true;
    }

    /**
     * Folds the selected run into a single commit.
     *
     * <p>Implemented as an interactive rebase rather than a reset: the commits may sit
     * above others that must be preserved, and a rebase is what git itself uses to fold
     * history. The oldest of the run is picked and the rest squashed into it.
     */
    private void squashSelected() {
        List<CommitInfo> selected = selectedCommits();
        if (selected.size() < 2) {
            statusBar.error("Select two or more commits to squash");
            return;
        }
        // The list is newest first, so the last entry is the one the others fold into.
        CommitInfo oldest = selected.get(selected.size() - 1);
        Set<String> hashes = new java.util.HashSet<>();
        for (CommitInfo c : selected) hashes.add(c.getHash());

        GitTaskRunner.query(gitService,
                () -> gitService.getRebaseRange(oldest.getHash()),
                range -> {
                    if (!range.isUsable()) {
                        statusBar.error(range.problem());
                        return;
                    }
                    RebaseTodo base = RebaseTodo.fromNewestFirst(range.commits(), range.base());
                    if (base.squashing(hashes, null) == null) {
                        statusBar.error("Those commits are not all part of the range being replayed");
                        return;
                    }
                    // The message dialog is filled from the range's commits, not the
                    // selected rows: a graph row carries only its subject (G15), and the
                    // range was read with whole messages, newest first like the rows.
                    List<CommitInfo> folded = range.commits().stream()
                            .filter(c -> hashes.contains(c.getHash()))
                            .toList();

                    // The push check is its own round trip, so the dialog chain nests
                    // once: range → position → (warning?) → message → rebase.
                    GitTaskRunner.query(gitService,
                            () -> gitService.getCommitPosition(oldest.getHash()),
                            position -> {
                                if (position.pushed() && !confirmForcePush(selected)) {
                                    return;
                                }
                                collectMessageAndSquash(folded, hashes, base);
                            },
                            statusBar::error);
                },
                statusBar::error);
    }

    /** A cherry-pick's outcome together with the HEAD it started from. */
    private record CherryPick(String before, GitResult result) { }

    /**
     * The branch HEAD is on, for deciding which chip is marked as current and for menus;
     * {@code null} while HEAD is detached, when there is none.
     *
     * <p>Taken from the page's {@link GraphMetrics} - every cell has one - because this is
     * called from {@code updateItem}, per visible row on every scroll. The metrics were
     * handed the page's own ref snapshot's current branch when the page was measured, so
     * reading it back costs nothing, never touches git on the FX thread, and cannot
     * disagree with the widths or with the sidebar beside it.
     */
    private String currentBranchOrNull() {
        String branch = metrics == null ? null : metrics.currentBranch();
        return branch == null || branch.isBlank() ? null : branch;
    }

    
    /**
     * Asks before rewriting pushed history: squashing commits a remote already has
     * turns the next push into a force push.
     */
    private boolean confirmForcePush(List<CommitInfo> selected) {
        // What is known is that the oldest of them is on a remote; the newer ones may not
        // be yet. A second "anyway" over work others may have pulled: Enter cancels.
        List<String> rows = selected.stream()
                .map(c -> c.getShortHash() + "  " + c.getSubject())
                .toList();
        return Dialogs.warn("Squash commits")
                .icon(Icons.history())
                .title("Squash " + selected.size() + " commits that were pushed?")
                .subtitle("`" + selected.get(selected.size() - 1).getShortHash()
                        + "` is already on a remote")
                .message("Squashing rewrites them, so the branch and the remote disagree and the next"
                        + " push has to be a force push. Anyone who pulled them will need to rebase.")
                .affected("Squashed into one", rows)
                .action("Squash anyway")
                .safeDefault()
                .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD)
                .ask();
    }

    /**
     * Message dialog, then the rebase. Split out of {@link #squashSelected()} for the
     * push-warning detour.
     *
     * @param selected the commits being folded, carrying their whole messages
     */
    private void collectMessageAndSquash(List<CommitInfo> selected, Set<String> hashes,
                                         RebaseTodo base) {
        new com.jfx.git.ui.dialogs.SquashMessageDialog(selected)
                .showAndWait()
                .ifPresent(message -> {
                    RebaseTodo plan = base.squashing(hashes, message);
                    run(() -> gitService.rebaseInteractive(plan), "Squashing " + selected.size() + " commits");
                });
    }

    /** Opens the two selected commits as a two-way comparison. */
    private void compareSelected() {
        List<CommitInfo> selected = selectedCommits();
        if (selected.size() != 2) {
            statusBar.error("Select exactly two commits to compare");
            return;
        }
        // Newest first in the list; comparing older→newer reads as "what changed since".
        onCompare.accept(selected.get(1), selected.get(0));
    }

    /**
     * Opens the selected commits in an editor before applying them.
     *
     * <p>Cherry-picking several commits is where order matters, so the same editor the
     * interactive rebase uses is offered, extended with reword and squash: cherry-pick
     * every kept commit as usual, then chain an ordinary interactive rebase over just the
     * commits that batch created to reword or fold the ones the user flagged. Cherry-
     * picking gives each commit a new hash, so that follow-up plan cannot reuse the
     * originally-selected {@link CommitInfo}s - it is built from {@link
     * com.jfx.git.core.GitBackend#getCommitsSince}, walked from the HEAD this cherry-pick
     * started from.
     */
    private void cherryPickSelected() {
        List<CommitInfo> selected = selectedCommits();
        if (selected.isEmpty()) return;

        RebaseTodo plan = RebaseTodo.fromNewestFirst(selected, "HEAD");
        var dialog = new com.jfx.git.ui.dialogs.InteractiveRebaseDialog(
                plan, "Cherry-pick commits", "Apply " + selected.size() + " commits",
                com.jfx.git.ui.dialogs.InteractiveRebaseDialog.CHERRY_PICK_ACTIONS);

        dialog.showAndWait().ifPresent(edited -> {
            // Oldest first: cherryPickAll and the follow-up rebase both need to apply
            // in replay order, and the dialog hands the plan back in that order already.
            List<RebaseTodo.Step> kept = new ArrayList<>();
            for (int i = 0; i < edited.size(); i++) {
                RebaseTodo.Step step = edited.step(i);
                if (step.action() != RebaseTodo.Action.DROP) {
                    kept.add(step);
                }
            }
            if (kept.isEmpty()) {
                statusBar.info("Every commit was dropped, so nothing was applied");
                return;
            }
            // Every kept commit is cherry-picked, including the ones flagged reword or
            // squash - those markers apply to the follow-up rebase, not to this pick.
            List<String> hashes = kept.stream().map(s -> s.commit().getHash()).toList();
            // A merge in the batch has one diff per parent. It used to be taken against
            // parent 1 without asking - the dialog the single-commit action shows was
            // skipped. Asked once per merge, oldest first, before anything is applied, so
            // cancelling any of them leaves the repository untouched.
            java.util.Map<String, Integer> mainlines = new java.util.HashMap<>();
            for (RebaseTodo.Step step : kept) {
                if (step.commit().getParentHashes().size() < 2) continue;
                var parent = chooseMainline(step.commit(), "Cherry-pick");
                if (parent.isEmpty()) {
                    statusBar.info("Cherry-pick cancelled; nothing was applied");
                    return;
                }
                mainlines.put(step.commit().getHash(), parent.get());
            }
            boolean needsFollowUp = kept.stream()
                    .anyMatch(s -> s.action() == RebaseTodo.Action.REWORD
                            || s.action() == RebaseTodo.Action.SQUASH);

            // The pick and its follow-up rebase are one operation: one busy, one verdict.
            operations.operation("Cherry-picking " + hashes.size() + " commits", OperationRunner.Refresh.EVERYTHING)
                    .start(steps ->
                            // The pre-pick HEAD is read on the worker, immediately before the
                            // pick that needs it, rather than here on the FX thread - it is a
                            // git call like any other, and the two belong to the same unit of
                            // work anyway.
                            steps.read(() -> {
                                String before = gitService.getHeadHash();
                                return new CherryPick(before, commands().cherryPickAll(hashes, mainlines));
                            }, picked -> {
                                if (!picked.result().isOk() || !needsFollowUp) {
                                    steps.finish(picked.result());
                                    return;
                                }
                                rewordOrSquashJustCherryPicked(steps, picked.before(), kept, picked.result());
                            }));
        });
    }

    /**
     * The second phase of a cherry-pick that reworded or squashed: replaces the
     * originally-selected commits' hashes in {@code kept} with the ones cherry-picking
     * actually produced, then runs an ordinary interactive rebase over exactly that
     * range to apply the reword/squash markers.
     */
    private void rewordOrSquashJustCherryPicked(OperationRunner.Steps steps, String preCherryPickHead,
                                                List<RebaseTodo.Step> kept, GitResult pickResult) {
        // A git log, and this runs from the completion callback of the pick - so on the
        // FX thread. Off it goes to the worker, and the rest of the method continues when
        // it comes back.
        steps.read(() -> gitService.getCommitsSince(preCherryPickHead),
                commits -> rewordOrSquashJustCherryPicked(steps, preCherryPickHead, kept, pickResult, commits));
    }

    private void rewordOrSquashJustCherryPicked(OperationRunner.Steps steps, String preCherryPickHead,
                                                List<RebaseTodo.Step> kept, GitResult pickResult,
                                                List<CommitInfo> newCommits) {
        if (newCommits.size() != kept.size()) {
            // Cherry-picking applied fewer commits than planned (nothing to fold or
            // reword should ever produce this, but a partial success must not be
            // silently followed by a rebase over the wrong range) - or conflict/failure
            // reporting already covers it, so report what actually happened and stop.
            steps.finish(pickResult);
            return;
        }

        RebaseTodo followUp = RebaseTodo.fromOldestFirst(newCommits, preCherryPickHead);
        for (int i = 0; i < kept.size(); i++) {
            RebaseTodo.Step original = kept.get(i);
            if (original.action() == RebaseTodo.Action.REWORD
                    || original.action() == RebaseTodo.Action.SQUASH) {
                followUp = followUp.withAction(i, original.action());
                if (original.message() != null) {
                    followUp = followUp.withMessage(i, original.message());
                }
            }
        }

        RebaseTodo finalFollowUp = followUp;
        steps.run(() -> gitService.rebaseInteractive(finalFollowUp), steps::finish);
    }

    /**
     * Enables, disables and labels the three position-dependent entries.
     *
     * <p>Cherry-pick copies a commit onto the current branch, so it is pointless when the
     * commit is already there. Revert is the mirror image: it undoes a commit that is in
     * this history, so it needs one that is. Rebase reads two ways — replaying from a
     * commit on this branch, or moving this branch onto one elsewhere — and the label has
     * to say which, because the results differ.
     */
    /**
     * The {@code -m} parent for cherry-picking or reverting {@code commit}.
     *
     * <p>An ordinary commit answers {@code 0} at once - there is only one diff to use. A
     * merge has one per parent, and git refuses to guess, which is why these actions
     * used to fail on every merge. Parent 1, the branch that was merged <em>into</em>, is
     * nearly always what is meant ("undo what this merge brought in"), so it is offered
     * first and preselected; the others stay reachable for the rarer case.
     *
     * @return the chosen parent (1-based), {@code 0} for a non-merge, or empty if the
     *         user cancelled
     */
    private java.util.Optional<Integer> chooseMainline(CommitInfo commit, String action) {
        List<String> parents = commit.getParentHashes();
        if (parents.size() < 2) {
            return java.util.Optional.of(0);
        }
        java.util.Map<String, String> subjects = loadedSubjects(parents);
        List<Integer> numbers = java.util.stream.IntStream.rangeClosed(1, parents.size()).boxed().toList();
        return Dialogs.choose(action + " merge commit", numbers)
                .icon(Icons.merge())
                .title(action + " `" + commit.getShortHash() + "` relative to which parent?")
                .subtitle("Parent 1, the mainline, is the branch it was merged into: nearly always the one")
                .text(n -> "Parent " + n + " · " + GitBackend.shortenHash(parents.get(n - 1))
                        + (n == 1 ? " (mainline)" : ""))
                // Each side named by what it says, not only by its hash.
                .description(n -> subjects.get(parents.get(n - 1)))
                .action(action + " merge")
                .ask();
    }

    /**
     * The subjects of {@code hashes} among the rows the graph has loaded, by hash. Read
     * from the list rather than asked of git: this runs on the FX thread, and a parent
     * past the loaded page simply goes without one.
     */
    private java.util.Map<String, String> loadedSubjects(List<String> hashes) {
        java.util.Map<String, String> found = new java.util.HashMap<>();
        if (getListView() == null) {
            return found;
        }
        for (GitGraphRow row : getListView().getItems()) {
            CommitInfo commit = row == null ? null : row.getCommit();
            if (commit != null && hashes.contains(commit.getHash())) {
                found.put(commit.getHash(), commit.getSubject());
                if (found.size() == hashes.size()) {
                    break;
                }
            }
        }
        return found;
    }

    private void applyEligibility(CommitPosition position,
                                  MenuItem cherryPick, MenuItem revert, MenuItem rebaseInteractive,
                                  List<MenuItem> singleEdits) {
        boolean onBranch = position.known() && position.onCurrentBranch();

        cherryPick.setDisable(onBranch);
        cherryPick.setText(onBranch
                ? "Cherry-pick onto current branch (already on it)"
                : "Cherry-pick onto current branch");

        revert.setDisable(!onBranch);
        revert.setText(onBranch ? "Revert commit" : "Revert commit (not on this branch)");

        // A merge has one diff per parent, so both actions ask which side is the mainline
        // before running - say so in the label rather than surprising with a dialog.
        if (position.known() && position.merge()) {
            cherryPick.setText(cherryPick.getText().replace("Cherry-pick", "Cherry-pick merge") + "…");
            revert.setText(revert.getText().replace("Revert commit", "Revert merge commit") + "…");
        }

        var eligibility = RebaseEligibility.from(position);
        // In-place edits replay the branch from this commit, so they need exactly what
        // "rebase from here" needs: on this branch, not a merge, not the first commit.
        singleEdits.forEach(item -> item.setDisable(eligibility != RebaseEligibility.OK));
        String branch = currentBranchOrNull();
        rebaseInteractive.setVisible(true);
        rebaseInteractive.setDisable(eligibility != RebaseEligibility.OK
                && eligibility != RebaseEligibility.ONTO);
        rebaseInteractive.setText(switch (eligibility) {
            case OK -> "Interactive rebase from here…";
            case ONTO -> "Interactive rebase " + (branch == null ? "current branch" : branch)
                    + " onto this commit…";
            default -> "Interactive rebase… (" + eligibility.reason() + ")";
        });
    }

    /**
     * The WIP row's trash icon: one click to discard every working-tree change.
     * Lives beside the message rather than behind a context menu, because the gesture
     * it replaces - right-clicking each file in turn - is exactly the tedium it exists
     * to spare. Still guarded by its own confirmation.
     *
     * <p>That confirmation is not governed by "Ask before discarding a file's changes",
     * and always shows. This one is every file at once, untracked ones deleted, from a
     * single click on a row clicked all day; and its Undo is weakest exactly where the
     * loss is largest - past {@code WorkingTreeSnapshots.MAX_UNTRACKED_FILES} no snapshot
     * is taken at all.
     */
    private void attachWipDiscard() {
        if (wipDiscard == null) {
            wipDiscard = com.jfx.git.ui.Icons.button(com.jfx.git.ui.Icons.trash(),
                    "Discard all changes", "Discard all changes");
            wipDiscard.getStyleClass().addAll("btn-sm", "row-action", "wip-discard");
            wipDiscard.setOnAction(e -> {
                // SnapshotGuard.discard takes a snapshot first and offers Undo from it -
                // except past MAX_UNTRACKED_FILES, where it takes none, and says so here.
                if (Dialogs.destructive("Discard all changes")
                        .title("Discard every uncommitted change?")
                        .subtitle("All files in the working tree")
                        .message("Modified files go back to their last commit and untracked files are"
                                + " deleted. A snapshot is taken first, so the notification that follows"
                                + " can undo it - unless there are more than "
                                + com.jfx.git.core.WorkingTreeSnapshots.MAX_UNTRACKED_FILES
                                + " untracked files, when none is taken.")
                        .action("Discard all")
                        .ask()) {
                    com.jfx.git.ui.SnapshotGuard.discard(operations,
                            "Discarding all changes", "Before discarding all changes", null,
                            gitService::discardAllChanges, null, OperationRunner.Refresh.EVERYTHING);
                }
            });
        }
        wipDiscard.setVisible(true);
        wipDiscard.setManaged(true);
        if (!messageBox.getChildren().contains(wipDiscard)) {
            messageBox.getChildren().add(wipDiscard);
        }
    }

    /** Takes the trash icon back off the row; a recycled cell must not carry it into a commit row. */
    private void hideWipDiscard() {
        if (wipDiscard != null) {
            wipDiscard.setVisible(false);
            wipDiscard.setManaged(false);
        }
    }

    private void run(Supplier<GitResult> work, String description) {
        operations.run(description, OperationRunner.Refresh.EVERYTHING, work);
    }

    // ------------------------------------------------------------------
    // Lane canvas - what a row draws is its RowPlan, drawn by RowPainter
    // ------------------------------------------------------------------

    /**
     * Sizes the canvas for {@code totalLanes} and returns the width it now has.
     *
     * <p>The assignment is guarded because {@code Canvas.setWidth} resizes the backing
     * buffer and clears it, and the lane count is a property of the whole page rather
     * than of a row — so on almost every call the width being set is the width already
     * there. Setting it anyway threw away a buffer per row per scroll tick to arrive at
     * the same number.
     */
    private double resizeCanvas(int totalLanes) {
        double width = density.graphWidth(totalLanes, nodeStyle, metrics.maxLanes());

        // How many canvas pixels to spend per logical unit. JavaFX already renders a
        // canvas at the display's density, but it knows nothing about the zoom: under a
        // 150% transform the buffer is still the logical size and is simply magnified,
        // which is what left the lanes and the author discs soft. Sizing the buffer by the
        // zoom and shrinking the node back by the same factor spends real pixels instead.
        double scale = com.jfx.git.ui.UiZoom.factor();
        double bufferWidth = Math.ceil(width * scale);
        double bufferHeight = Math.ceil(rowHeight * scale);

        if (canvas.getWidth() != bufferWidth || canvas.getHeight() != bufferHeight) {
            canvas.setWidth(bufferWidth);
            canvas.setHeight(bufferHeight);
        }
        if (canvasScale.getX() != 1 / scale) {
            canvasScale.setX(1 / scale);
            canvasScale.setY(1 / scale);
        }

        // Everything drawn afterwards stays in logical coordinates: the transform does the
        // multiplying, so no drawing code has to know the zoom exists.
        canvas.getGraphicsContext2D().setTransform(scale, 0, 0, scale, 0, 0);
        return width;
    }

    /** Stash actions, matching what the sidebar offers for the same entry. */
    /** The stash and cherry-pick commands the menus run, over this cell's backend. */
    private com.jfx.git.core.RepositoryCommands commands() {
        return new com.jfx.git.core.RepositoryCommands(gitService);
    }

    private ContextMenu buildStashMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem apply = new MenuItem("Apply stash (keep it)");
        // By hash rather than by stash@{n}: the position was read when this row was
        // drawn and git renumbers the list on every pop or drop. See
        // RepositoryCommands.stashPop(String).
        apply.setOnAction(e -> onStash(stash -> run(() -> commands().stashApply(stash.hash()),
                "Applying " + stash.selector())));

        MenuItem pop = new MenuItem("Pop stash (apply and drop)");
        pop.setOnAction(e -> onStash(stash -> run(() -> commands().stashPop(stash.hash()),
                "Popping " + stash.selector())));

        MenuItem branch = new MenuItem("Create branch from stash…");
        branch.setOnAction(e -> onStash(stash -> new com.jfx.git.ui.dialogs.StashBranchDialog(stash)
                .showAndWait()
                .ifPresent(name -> run(() -> commands().stashBranch(name, stash.hash()),
                        "Creating " + name + " from " + stash.selector()))));

        MenuItem drop = new MenuItem("Drop stash…");
        drop.setOnAction(e -> onStash(stash -> {
            // Nothing here brings a dropped stash back, so Enter is Cancel.
            if (Dialogs.destructive("Drop stash")
                    .icon(Icons.stash())
                    .title("Drop `" + stash.selector() + "`?")
                    .subtitle(stash.shortDescription())
                    .message("The changes it holds are deleted, and this cannot be undone.")
                    .action("Drop stash")
                    .safeDefault()
                    .ask()) {
                run(() -> commands().stashDrop(stash.hash()), "Dropping " + stash.selector());
            }
        }));

        MenuItem copyHash = new MenuItem("Copy stash commit hash");
        copyHash.setOnAction(e -> onStash(stash -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(stash.hash());
            Clipboard.getSystemClipboard().setContent(content);
            statusBar.info("Copied " + stash.shortHash());
        }));

        menu.getItems().addAll(apply, pop, branch, drop, new SeparatorMenuItem(), copyHash);
        return menu;
    }
}
