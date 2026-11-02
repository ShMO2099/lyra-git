package com.jfx.git.graph;

import com.jfx.git.core.AppPreferences;

/**
 * How tightly the commit graph is drawn.
 *
 * <p>Every measurement the row depends on travels together in one value rather than as
 * loose constants, because they are not independent: the dot radius has to scale with the
 * lane pitch or the dots touch, the avatar has to fit inside the row height, and the tint
 * has to lighten as the row grows or the bands turn into stripes. Splitting them across
 * seven fields would let a change to one leave the others behind.
 *
 * <p>{@link #COMPACT} is the default. {@link #EXPRESSIVE} is for reading the graph more
 * than the messages: it trades message width for a wider rail, a heavier branch tint and
 * a coloured edge strip down the side of each row.
 */
public enum GraphDensity {

    // The rail is deliberately narrow. Sizing it to the longest branch name in the
    // repository is the obvious thing to do and the wrong one: one
    // origin/njh/attention-free-models then charges every row in the history two hundred
    // pixels of commit message for a name that appears once. The rail is a place to see
    // that a ref is there and roughly which one; the full name is a hover away.
    //
    // The author column is wider than the avatar in it, because it also has to hold the
    // word "Author" at the top of it without clipping to "Aut…".
    // Age and hash fit their longest text in 11px mono (0.55em a character) plus the
    // cell's 8px of right padding: RelativeTime's "Sept 2024" and a stash's "stash@{0}",
    // nine characters each. Age was sized for "14d", so every commit over a week old -
    // most of any real history - read "14..." instead of its date.
    COMPACT("Compact", 26, 11, 4.0, 64, 16, 0.055, 0, 56, 66, 64),
    EXPRESSIVE("Expressive", 30, 13, 4.5, 76, 19, 0.10, 3, 60, 70, 68);

    /**
     * Lane columns to reserve width for when the window's width is not known.
     *
     * <p>Every row is drawn at the page's full lane count so the message column lines up,
     * so this is a budget rather than a limit on the history: past it, further lanes share
     * the last column rather than pushing the messages off the side of the window.
     *
     * <p>A fixed number is the wrong shape for it — it wastes a wide window and crushes a
     * narrow one — so {@link GraphMetrics} derives the real budget from the space the list
     * actually has. This is only the fallback for a list that has not been laid out yet.
     */
    public static final int MAX_DRAWN_LANES = 14;

    /** Never fewer than this, however narrow the window gets. */
    public static final int MIN_DRAWN_LANES = 4;

    /**
     * Most lanes worth drawing in {@code available} pixels of list width.
     *
     * <p>The graph may take up to a third of the row. Beyond that the messages — which are
     * what the list is for — start losing more than the graph gains, and a graph that wide
     * has stopped being followable anyway.
     */
    public int maxDrawnLanes(double available, GraphNodeStyle style) {
        if (available <= 0) return MAX_DRAWN_LANES;
        int budget = (int) (available / 3.0 / laneWidth(style));
        return Math.max(MIN_DRAWN_LANES, Math.min(budget, 40));
    }

    private final String label;
    private final int rowHeight;
    private final int laneWidth;
    private final double dotRadius;
    private final int railWidth;
    private final int avatarSize;
    private final double tintAlpha;
    private final int edgeWidth;
    private final int authorWidth;
    private final int ageWidth;
    private final int hashWidth;

    GraphDensity(String label, int rowHeight, int laneWidth, double dotRadius,
                 int railWidth, int avatarSize, double tintAlpha, int edgeWidth,
                 int authorWidth, int ageWidth, int hashWidth) {
        this.label = label;
        this.rowHeight = rowHeight;
        this.laneWidth = laneWidth;
        this.dotRadius = dotRadius;
        this.railWidth = railWidth;
        this.avatarSize = avatarSize;
        this.tintAlpha = tintAlpha;
        this.edgeWidth = edgeWidth;
        this.authorWidth = authorWidth;
        this.ageWidth = ageWidth;
        this.hashWidth = hashWidth;
    }

    /** Author column. Wide enough for its heading, not just for the avatar in it. */
    public int authorWidth() { return authorWidth; }

    /** Age column, in this platform's monospace; see {@link #monoWidth}. */
    public int ageWidth() { return monoWidth(ageWidth); }

    /** Hash column, in this platform's monospace; see {@link #monoWidth}. */
    public int hashWidth() { return monoWidth(hashWidth); }

    /**
     * A column width measured in 11px Consolas plus the cell's 8px of right padding, for
     * the monospace this platform actually has: the text part grows with it, the padding
     * does not. Unchanged where Consolas is installed.
     */
    private static int monoWidth(int width) {
        double scale = com.jfx.git.ui.MonospaceFallback.widthScale();
        return scale == 1.0 ? width : (int) Math.ceil((width - 8) * scale) + 8;
    }

    public String label() { return label; }

    /** Height of one row, and the value the list is told to assume for every cell. */
    public int rowHeight() { return rowHeight; }

    /** Horizontal distance between lane centres, with a dot at the node. */
    public int laneWidth() { return laneWidth; }

    /**
     * Horizontal distance between lane centres for {@code style}.
     *
     * <p>An avatar node is sixteen pixels across where a dot is eight, so the lanes have
     * to move apart or neighbouring commits overlap. This is the whole cost of the avatar
     * node, and the reason the dot remains an option.
     */
    public int laneWidth(GraphNodeStyle style) {
        return style == GraphNodeStyle.AUTHOR ? laneWidth + 7 : laneWidth;
    }

    public double dotRadius() { return dotRadius; }

    /** Radius of the node itself, which the avatar fills and the ring is drawn on. */
    public double nodeRadius(GraphNodeStyle style) {
        return style == GraphNodeStyle.AUTHOR ? Math.min(9, (rowHeight - 8) / 2.0) : dotRadius;
    }

    /** Author column width for {@code style}; zero when the node already says it. */
    public int authorWidth(GraphNodeStyle style) {
        return style.needsAuthorColumn() ? authorWidth : 0;
    }

    /** Fixed width of the ref column, so the message column never moves. */
    public int railWidth() { return railWidth; }

    public int avatarSize() { return avatarSize; }

    /** Opacity of the branch tint washed across the row background. */
    public double tintAlpha() { return tintAlpha; }

    /** Width of the coloured strip at the leading edge of a row; zero for none. */
    public int edgeWidth() { return edgeWidth; }

    /**
     * The seven columns a graph row is laid out in, in order:
     * edge, refs, lanes, message, author, age, hash.
     *
     * <p>Built here, and here only, so the header strip and the rows beneath it cannot
     * drift apart. Two grids each sizing a column to its own content is exactly how a
     * header ends up half a column out from the data under it — so every column is pinned
     * except the message, which takes what is left. The two that depend on the loaded page
     * rather than on the density — refs and lanes — are handed in by {@link GraphMetrics}.
     */
    public java.util.List<javafx.scene.layout.ColumnConstraints> columnConstraints(GraphNodeStyle style) {
        return columnConstraints(style, GraphColumn.visibleFor(style));
    }

    /**
     * The same seven columns, with the optional ones the user has hidden set to zero.
     *
     * <p>Hidden columns keep their place rather than being removed, so the grid stays
     * seven wide and every {@code addRow} and column index in the header and the cell
     * still refers to the same thing. A column model where the indices move when a column
     * is hidden is how a header ends up over the wrong data.
     */
    public java.util.List<javafx.scene.layout.ColumnConstraints> columnConstraints(
            GraphNodeStyle style, java.util.List<GraphColumn> order) {
        java.util.List<javafx.scene.layout.ColumnConstraints> columns =
                new java.util.ArrayList<>(order.size() + 1);
        columns.add(fixed(edgeWidth));
        for (GraphColumn column : order) {
            boolean shown = columnShown(column, style);
            switch (column) {
                case REFS -> columns.add(fixed(shown ? railWidth : 0));
                case GRAPH -> columns.add(fixed(shown ? graphWidth(1, style, MAX_DRAWN_LANES) : 0));
                // Hiding the message column has to collapse it rather than let it grow:
                // it is the one column that takes the slack, so leaving it growing would
                // hide the text and keep every pixel of the space it was using.
                case MESSAGE -> columns.add(shown ? growing() : fixed(0));
                case AUTHOR -> columns.add(fixed(shown ? authorWidth(style) : 0));
                // Wider when the dates are absolute: see DateDisplay.graphAgeWidth.
                case AGE -> columns.add(rightAligned(shown
                        ? com.jfx.git.ui.DateDisplay.graphAgeWidth(ageWidth()) : 0));
                case HASH -> columns.add(rightAligned(shown ? hashWidth() : 0));
            }
        }
        return java.util.List.copyOf(columns);
    }

    /** Visibility including the node-style rule, so order and hiding stay independent. */
    private static boolean columnShown(GraphColumn column, GraphNodeStyle style) {
        if (column == GraphColumn.AUTHOR && !style.needsAuthorColumn()) {
            return false;
        }
        return column.visible();
    }

    /**
     * Declaration-order variant; kept because several tests reason positionally about
     * that layout. Live consumers use {@link #columnConstraints(GraphNodeStyle, java.util.List)}.
     */
    public java.util.List<javafx.scene.layout.ColumnConstraints> columnConstraints(
            GraphNodeStyle style, java.util.Set<GraphColumn> visible) {
        return java.util.List.of(
                fixed(edgeWidth),
                fixed(visible.contains(GraphColumn.REFS) ? railWidth : 0),
                fixed(visible.contains(GraphColumn.GRAPH)
                        ? graphWidth(1, style, MAX_DRAWN_LANES) : 0),
                // Hiding the message column has to collapse it rather than let it grow:
                // it is the one column that takes the slack, so leaving it growing would
                // hide the text and keep every pixel of the space it was using.
                visible.contains(GraphColumn.MESSAGE) ? growing() : fixed(0),
                fixed(visible.contains(GraphColumn.AUTHOR) ? authorWidth(style) : 0),
                rightAligned(visible.contains(GraphColumn.AGE) ? ageWidth() : 0),
                rightAligned(visible.contains(GraphColumn.HASH) ? hashWidth() : 0));
    }

    private static javafx.scene.layout.ColumnConstraints fixed(double width) {
        var c = new javafx.scene.layout.ColumnConstraints(width, width, width);
        c.setHgrow(javafx.scene.layout.Priority.NEVER);
        return c;
    }

    private static javafx.scene.layout.ColumnConstraints growing() {
        var c = new javafx.scene.layout.ColumnConstraints();
        c.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        // Both, and for different reasons.
        //
        // minWidth, so a long subject is ellipsised rather than setting a floor that
        // shoves the age and hash columns off the end of the row.
        //
        // prefWidth, because a grid's preferred width is the sum of its columns' - so a
        // column whose preferred width is "however long this commit message happens to
        // be" makes the whole row preferred-wider than the window. The list then grows a
        // horizontal scrollbar and the age and hash columns sit somewhere off to the
        // right, several seconds of scrolling away. The column takes what is left over at
        // layout time through Hgrow; it must not ask for anything up front.
        c.setMinWidth(0);
        c.setPrefWidth(0);
        return c;
    }

    private static javafx.scene.layout.ColumnConstraints rightAligned(double width) {
        var c = fixed(width);
        c.setHalignment(javafx.geometry.HPos.RIGHT);
        return c;
    }

    /** Canvas width for a graph {@code lanes} columns wide, bounded by {@code maxLanes}. */
    public double graphWidth(int lanes, GraphNodeStyle style, int maxLanes) {
        return Math.min(Math.max(1, lanes), maxLanes) * laneWidth(style) + 6;
    }

    /**
     * X-coordinate of the centre of a lane column.
     *
     * <p>Clamped to {@link #MAX_DRAWN_LANES}: a lane past the reserved width shares the
     * last column rather than being drawn outside the canvas, where it would simply not
     * appear and leave a commit dot missing with no explanation.
     */
    public int laneX(int lane, GraphNodeStyle style, int maxLanes) {
        int drawn = Math.min(Math.max(lane, 0), maxLanes - 1);
        int width = laneWidth(style);
        return drawn * width + width / 2;
    }

    @Override
    public String toString() { return label; }

    /** The density the user has chosen. */
    public static GraphDensity current() {
        return AppPreferences.graphDensity();
    }
}
