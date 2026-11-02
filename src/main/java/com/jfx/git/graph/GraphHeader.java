package com.jfx.git.graph;

import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;

/**
 * The column titles above the commit graph.
 *
 * <p>Takes its column widths from {@link GraphDensity#columnConstraints()} and its two
 * page-dependent ones from the same {@link GraphMetrics} the rows read, so the titles sit
 * over the data they name rather than near it.
 *
 * <p>The lane column is left unlabelled. It is between one and fourteen columns of
 * eleven pixels, so no word fits in it at the narrow end, and a heading that appears only
 * on wide repositories would be worse than none.
 */
public final class GraphHeader extends GridPane {

    public GraphHeader(GraphDensity density, GraphMetrics metrics) {
        this(density, metrics, GraphNodeStyle.current());
    }

    public GraphHeader(GraphDensity density, GraphMetrics metrics, GraphNodeStyle nodeStyle) {
        getStyleClass().add("graph-header");

        java.util.Set<GraphColumn> shown = GraphColumn.visibleFor(nodeStyle);
        java.util.List<GraphColumn> order = GraphColumn.ordered();

        Region edge = new Region();
        Label refs = title(GraphColumn.REFS.headerTitle());
        // The graph column has no title, but it says when some lanes did not fit: those
        // share the last column under a dotted marker, and "+N" here says how many.
        Label lanes = new Label();
        lanes.getStyleClass().addAll("graph-header-title", "graph-lane-overflow");
        lanes.setMaxWidth(Double.MAX_VALUE);
        lanes.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        lanes.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> metrics.hiddenLanes() > 0 ? "+" + metrics.hiddenLanes() : "",
                metrics.hiddenLanesProperty()));
        javafx.scene.control.Tooltip overflowTip = new javafx.scene.control.Tooltip();
        overflowTip.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> metrics.hiddenLanes() + " lanes share the graph's last column, drawn dotted."
                        + " Drag the graph's right edge to make room for them.",
                metrics.hiddenLanesProperty()));
        lanes.tooltipProperty().bind(javafx.beans.binding.Bindings.when(
                metrics.hiddenLanesProperty().greaterThan(0))
                .then(overflowTip).otherwise((javafx.scene.control.Tooltip) null));
        Label commit = title(GraphColumn.MESSAGE.headerTitle());
        // With the author node there is no author column to head, but the column still
        // exists at zero width so the grid stays seven wide in both styles.
        // Held when it is a real heading, so the filter dropdown has something to hang
        // off. Under the avatar node style there is no author column and no heading.
        this.authorTitle = nodeStyle.needsAuthorColumn()
                ? title(GraphColumn.AUTHOR.headerTitle()) : null;
        Region author = authorTitle != null ? authorTitle : new Region();
        Label age = title(GraphColumn.AGE.headerTitle());
        Label hash = title(GraphColumn.HASH.headerTitle());

        // The rail's chips are right-aligned against the graph, so its title is too.
        refs.setMaxWidth(Double.MAX_VALUE);
        refs.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        // Laid out in the user's stored order (drag-to-reorder), not declaration order.
        java.util.Map<GraphColumn, javafx.scene.Node> nodes = java.util.Map.of(
                GraphColumn.REFS, refs,
                GraphColumn.GRAPH, lanes,
                GraphColumn.MESSAGE, commit,
                GraphColumn.AUTHOR, author,
                GraphColumn.AGE, age,
                GraphColumn.HASH, hash);
        var cells = new javafx.scene.Node[order.size() + 1];
        cells[0] = edge;
        for (int i = 0; i < order.size(); i++) {
            cells[i + 1] = nodes.get(order.get(i));
        }
        // addRow, not getChildren().add: only addRow assigns grid indices, and a child
        // without one piles into column 0 with everything else.
        addRow(0, cells);

        // A hidden column is zero-wide but does not clip what is in it, so the title
        // goes with it rather than painting across its neighbour.
        hideUnless(refs, shown.contains(GraphColumn.REFS));
        hideUnless(lanes, shown.contains(GraphColumn.GRAPH));
        hideUnless(commit, shown.contains(GraphColumn.MESSAGE));
        hideUnless(author, shown.contains(GraphColumn.AUTHOR));
        hideUnless(age, shown.contains(GraphColumn.AGE));
        hideUnless(hash, shown.contains(GraphColumn.HASH));

        // Right-clicking the header is how commercial clients offer this, so it is offered here.
        setOnContextMenuRequested(e -> {
            showColumnMenu(e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // Drag a heading onto another to move the whole column: every cell of the
        // header participates - including the unlabelled lane region as a target -
        // because a drop target you cannot see is not a target.
        installDrag(edge, null);
        for (GraphColumn column : order) {
            installDrag(nodes.get(column), column);
        }

        getColumnConstraints().setAll(density.columnConstraints(nodeStyle, order));
        // Only bound while the column is drawn, at whichever position the user has
        // dragged it to: +1 skips the leading edge slot. A hidden column is pinned at
        // zero, and a binding would immediately widen it again the next time the page
        // is measured.
        if (shown.contains(GraphColumn.REFS)) {
            bindAll(getColumnConstraints().get(1 + order.indexOf(GraphColumn.REFS)),
                    metrics.railWidthProperty());
        }
        if (shown.contains(GraphColumn.GRAPH)) {
            bindAll(getColumnConstraints().get(1 + order.indexOf(GraphColumn.GRAPH)),
                    metrics.laneWidthProperty());
        }
    }

    /**
     * Makes {@code node} a drag source and drop target for column reordering.
     *
     * @param column the column this header cell belongs to; null for the edge strip,
     *               which can receive drops but is itself immovable
     */
    private void installDrag(javafx.scene.Node node, GraphColumn column) {
        node.setOnDragDetected(e -> {
            if (column != null) {
                var board = node.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                board.setContent(java.util.Map.of(
                        javafx.scene.input.DataFormat.PLAIN_TEXT, column.name()));
            }
            e.consume();
        });
        node.setOnDragOver(e -> {
            Object payload = e.getDragboard().getContent(javafx.scene.input.DataFormat.PLAIN_TEXT);
            // Only over a real column. The unlabelled edge strip has no column to sit
            // beside, so the drop handler below refuses it - and accepting here anyway
            // gave the cursor a move sign over a target that would then do nothing. A
            // drop target that lies about itself is worse than one that is simply absent.
            if (column != null
                    && payload instanceof String name && !name.equals(column.name())) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
            }
            e.consume();
        });
        node.setOnDragDropped(e -> {
            Object payload = e.getDragboard().getContent(javafx.scene.input.DataFormat.PLAIN_TEXT);
            boolean done = false;
            if (payload instanceof String name && column != null) {
                try {
                    GraphColumn.reordered(GraphColumn.valueOf(name), column);
                    columnsChanged();
                    done = true;
                } catch (IllegalArgumentException ignored) {
                    // Not one of ours; nothing to move.
                }
            }
            e.setDropCompleted(done);
            e.consume();
        });
    }

    private static void bindAll(javafx.scene.layout.ColumnConstraints column,
                                javafx.beans.value.ObservableValue<? extends Number> width) {
        column.prefWidthProperty().bind(width);
        column.minWidthProperty().bind(width);
        column.maxWidthProperty().bind(width);
    }

    private static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("graph-header-title");
        return label;
    }

    /**
     * The Author column's filter dropdown.
     *
     * <p>Commercial clients hang one off that column's heading, so the question "show me only
     * this person's commits" is asked where the answer is already displayed. Supplied by
     * the tab, which is what knows the page and owns the filter.
     */
    public interface AuthorFilter {

        /** Every author the graph could be narrowed to. */
        java.util.List<String> authors();

        /** The one currently chosen, or null for everyone. */
        String chosen();

        /** Narrows to {@code author}, or widens again when null. */
        void choose(String author);
    }

    private AuthorFilter authorFilter;

    /** The Author heading, or null when the avatar node retires that column. */
    private final Label authorTitle;

    /** Notified when a column is switched on or off, so the graph can be rebuilt. */
    private Runnable onColumnsChanged;

    /**
     * The column menu, one per header.
     *
     * <h2>Why one, and why not static</h2>
     * It used to be built fresh on every request and shown without dismissing whatever
     * was already up. A {@code ContextMenu} shown from a handler that consumes the event
     * does not auto-hide, so each right-click stacked another copy on the last and the
     * header filled with overlapping menus. One instance, hidden before it is shown
     * again, cannot do that.
     *
     * <p>It was briefly static, on the reasoning that the header is replaced whenever the
     * columns change so an instance could not outlive the change it caused. That was
     * wrong in a way a robot test found: the callback is static too, so with two
     * repositories open only the most recently built tab would ever rebuild — changing a
     * column in one window silently reordered the other. The header being replaced is
     * handled by the replacement being given the same callback, which is the caller's
     * job and costs one line.
     */
    private javafx.scene.control.ContextMenu columnMenu;

    /** Registers what to do when this header's menu changes something. */
    public void setOnColumnsChanged(Runnable handler) {
        this.onColumnsChanged = handler;
    }

    /**
     * Hangs a filter dropdown off the Author heading.
     *
     * <p>The heading becomes clickable and grows a caret. When a filter is in force the
     * heading shows the chosen name rather than the word "Author": a column that is
     * quietly hiding most of the history has to say so where the user is looking, not
     * only inside a menu they would have to reopen to check.
     */
    public void setAuthorFilter(AuthorFilter filter) {
        this.authorFilter = filter;
        if (filter == null || authorTitle == null) {
            return;
        }
        authorTitle.setCursor(javafx.scene.Cursor.HAND);
        authorTitle.getStyleClass().add("graph-header-filter");
        authorTitle.setOnMouseClicked(e -> showAuthorMenu());
        refreshAuthorTitle();
    }

    /** Puts the chosen author, or the column's name, above the column. */
    public void refreshAuthorTitle() {
        if (authorTitle == null) {
            return;
        }
        String chosen = authorFilter == null ? null : authorFilter.chosen();
        authorTitle.setText(chosen == null
                ? GraphColumn.AUTHOR.headerTitle() + " ▾"
                : chosen + " ▾");
        authorTitle.setTooltip(new javafx.scene.control.Tooltip(chosen == null
                ? "Show only one author's commits"
                : "Showing only " + chosen + " — choose All authors to widen again"));
    }

    private void showAuthorMenu() {
        if (authorFilter == null) {
            return;
        }
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();

        javafx.scene.control.RadioMenuItem all =
                new javafx.scene.control.RadioMenuItem("All authors");
        all.setSelected(authorFilter.chosen() == null);
        all.setOnAction(e -> {
            authorFilter.choose(null);
            refreshAuthorTitle();
        });
        menu.getItems().addAll(all, new javafx.scene.control.SeparatorMenuItem());

        for (String author : authorFilter.authors()) {
            javafx.scene.control.RadioMenuItem item =
                    new javafx.scene.control.RadioMenuItem(author);
            item.setSelected(author.equals(authorFilter.chosen()));
            item.setOnAction(e -> {
                authorFilter.choose(author);
                refreshAuthorTitle();
            });
            menu.getItems().add(item);
        }
        menu.show(authorTitle, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /**
     * The show/hide menu, its entries rebuilt to match current state.
     *
     * <p>The Author entry is disabled rather than dropped under the avatar node style: it
     * says the column exists and why it is unavailable, where a menu that silently grows
     * and shrinks by one entry just reads as inconsistent.
     */
    public javafx.scene.control.ContextMenu columnMenu() {
        if (columnMenu == null) {
            columnMenu = new javafx.scene.control.ContextMenu();
        }
        columnMenu.getItems().clear();

        boolean avatarNode = !GraphNodeStyle.current().needsAuthorColumn();
        for (GraphColumn column : GraphColumn.values()) {
            javafx.scene.control.CheckMenuItem item =
                    new javafx.scene.control.CheckMenuItem(column.label());
            item.setSelected(column.visible());
            if (column == GraphColumn.AUTHOR && avatarNode) {
                item.setDisable(true);
                item.setText(column.label() + " — shown on the commit node");
            }
            item.setOnAction(e -> {
                column.setVisible(item.isSelected());
                columnsChanged();
            });
            columnMenu.getItems().add(item);
        }

        columnMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        javafx.scene.control.MenuItem reset =
                new javafx.scene.control.MenuItem("Reset columns to default layout");
        reset.setOnAction(e -> {
            GraphColumn.resetToDefault();
            columnsChanged();
        });
        columnMenu.getItems().add(reset);
        return columnMenu;
    }

    /**
     * Shows the menu at a point on screen, replacing any copy already open.
     *
     * <p>Hiding first is the whole fix for the stacking: {@code show} on a menu that is
     * already showing leaves the old placement behind.
     */
    public void showColumnMenu(double screenX, double screenY) {
        javafx.scene.control.ContextMenu menu = columnMenu();
        menu.hide();
        menu.show(this, screenX, screenY);
    }

    /** Shows the menu under {@code anchor}, for the gear button. */
    public void showColumnMenu(javafx.scene.Node anchor) {
        javafx.scene.control.ContextMenu menu = columnMenu();
        menu.hide();
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void columnsChanged() {
        if (onColumnsChanged != null) {
            onColumnsChanged.run();
        }
    }

    private static void hideUnless(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /**
     * Insets this header to match the list beneath it.
     *
     * <p>Two things push a row's content out of line with a heading sitting above it, and
     * neither is visible until you measure:
     *
     * <ul>
     *   <li>the list draws a border, so its cells start an inset in from its left edge —
     *       about a pixel, which is enough to read as sloppy rather than as a bug;</li>
     *   <li>the vertical scrollbar takes width from the cells but not from this header, so
     *       the right-hand columns drift by a scrollbar — and only once there is enough
     *       history to scroll, which makes it look intermittent.</li>
     * </ul>
     *
     * <p>Both are read from the list itself rather than guessed at, because a theme can
     * change either one.
     */
    public void alignWith(javafx.scene.control.ListView<?> list) {
        Runnable bind = () -> {
            javafx.scene.control.ScrollBar bar =
                    list.lookup(".scroll-bar:vertical") instanceof javafx.scene.control.ScrollBar found
                            ? found : null;

            var right = bar == null
                    ? javafx.beans.binding.Bindings.createDoubleBinding(() -> 0.0)
                    : javafx.beans.binding.Bindings.createDoubleBinding(
                            () -> bar.isVisible() ? bar.getWidth() : 0.0,
                            bar.visibleProperty(), bar.widthProperty());

            paddingProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(
                    () -> new javafx.geometry.Insets(0, right.get(), 0, list.getInsets().getLeft()),
                    right, list.insetsProperty()));
        };

        if (list.getSkin() != null) {
            bind.run();
        } else {
            // The scrollbar does not exist until the list has a skin. One shot, and then
            // gone: the header is rebuilt on every column change while the list outlives
            // all of them, so a listener left behind accumulates one stale header per
            // rebuild - each re-skin, a theme swap among them, would then re-bind padding
            // through headers that left the scene long ago.
            list.skinProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends javafx.scene.control.Skin<?>> obs,
                                    javafx.scene.control.Skin<?> oldSkin,
                                    javafx.scene.control.Skin<?> skin) {
                    if (skin != null) {
                        list.skinProperty().removeListener(this);
                        bind.run();
                    }
                }
            });
        }
    }
}
