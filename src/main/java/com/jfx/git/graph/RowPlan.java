package com.jfx.git.graph;

import java.util.List;

/**
 * What one row of the commit graph draws, decided before anything is drawn.
 *
 * <p>Which lanes cross a row, how each edge turns, which of them fall past the lane
 * budget and what the node looks like are decisions, and they used to be made in the
 * middle of stroking a canvas - so the only way to check one was to paint the row and
 * sample its pixels. A plan is those decisions as a value: {@link RowPlanner} works it out
 * from a {@link GitGraphRow}, the lane budget and the node style, and {@link RowPainter}
 * draws it.
 *
 * <p>Nothing in a plan is a pixel or a colour. Positions are lane columns and named
 * heights, colours are palette indices, so a change of density, zoom or theme draws the
 * same plan differently instead of needing a new one. What does need a new one - another
 * budget, another node style, another lane count for the page - is recorded in the plan
 * itself; see {@link #isFor}.
 *
 * @param lanes    lane columns the page has; the canvas is sized from it so that every row
 *                 is the same width, whatever this one draws
 * @param budget   the lane budget the plan was made for: lanes at or past its last column
 *                 share that column
 * @param style    the node style the plan was made for
 * @param marker   the column of the dotted overflow marker, or {@link #NO_MARKER}
 * @param segments every line on the row, in the order it is drawn
 * @param node     what stands at the row's own position; {@code null} for the row that
 *                 loads older commits, which draws nothing at all
 */
record RowPlan(int lanes, int budget, GraphNodeStyle style, int marker,
               List<Segment> segments, Node node) {

    /** {@link #marker} on a row with nothing past the budget to stand for. */
    static final int NO_MARKER = -1;

    /** A colour index meaning the stash colour rather than any lane's. */
    static final int STASH = -1;

    /** A colour index meaning the attention colour: the uncommitted row's ring. */
    static final int ATTENTION = -2;

    RowPlan {
        segments = List.copyOf(segments);
    }

    /** Whether the row carries the overflow column's marker. */
    boolean hasMarker() {
        return marker != NO_MARKER;
    }

    /**
     * Whether this plan still describes a row drawn at {@code budget} lanes in
     * {@code style} on a page of {@code lanes} lanes.
     */
    boolean isFor(int budget, GraphNodeStyle style, int lanes) {
        return this.budget == budget && this.style == style && this.lanes == lanes;
    }

    /**
     * Where an end of a segment sits within its column.
     *
     * <p>The row's own height and the node's outline, named rather than measured: how
     * tall the row is and how big the node is are the density's and the node style's
     * business, and they are only asked when the plan is drawn.
     */
    enum Anchor {
        /** The top edge of the row. */
        TOP,
        /** The top of the node's outline: where a lane arriving in the node's own column stops. */
        NODE_TOP,
        /** Half the row's height: where edges turn into and out of the node. */
        MIDDLE,
        /** The left point of the node at half height: where a stash's tie line leaves. */
        NODE_LEFT,
        /** The bottom of the node's outline: where a lane leaving down its own column starts. */
        NODE_BOTTOM,
        /** The bottom edge of the row. */
        BOTTOM
    }

    /**
     * Which way a segment runs between its ends.
     *
     * <p>Named for the path rather than inferred from the coordinates: both kinds of edge
     * that change column run top to bottom within their row, so the direction alone could
     * not tell an edge leaving a node from one arriving at it - and every edge used to be
     * drawn as the first kind, which put a side branch's turn under the child instead of
     * at the parent.
     */
    enum EdgeShape {
        /** A line from one end to the other; both ends are in the same column. */
        STRAIGHT,
        /** Leaving a node: across at the node's height, then down the other lane. */
        ACROSS_THEN_DOWN,
        /** Arriving at a node: down the edge's own lane, then across into the node. */
        DOWN_THEN_ACROSS
    }

    /** How a segment is stroked. */
    enum Stroke {
        /** A solid lane line. */
        LANE,
        /**
         * A solid line over a wider casing in the row's background, the way a transit map
         * draws one route crossing another: for edges that cross columns, so passing over
         * a vertical lane does not read as joining it.
         */
        CASED,
        /** A dashed lane line: the uncommitted changes' edge down to HEAD. */
        DASHED,
        /** A thin dotted line: a stash's tie back to the commit it was taken from. */
        TIE
    }

    /**
     * One line on the row.
     *
     * @param fromColumn the lane column the line starts in, already brought inside the budget
     * @param toColumn   the lane column it ends in, likewise
     * @param color      a lane palette index, or {@link #STASH}
     */
    record Segment(int fromColumn, Anchor from, int toColumn, Anchor to,
                   EdgeShape shape, Stroke stroke, int color) {
    }

    /** What a row's node is drawn as. */
    enum NodeShape {
        /** A filled dot; hollow for a merge. */
        DOT,
        /** The author's avatar ringed in the lane's colour; the ring thickens for a merge. */
        AVATAR,
        /**
         * A small hollow ring, for a commit whose lane is past the budget: still visibly a
         * commit on this row, without pretending to be on any particular lane.
         */
        OVERFLOW_RING,
        /** A stash: a diamond in a lane of its own, joined to nothing but its tie line. */
        STASH_DIAMOND,
        /** The uncommitted changes: a dashed ring on HEAD's lane. */
        WIP_RING
    }

    /**
     * The node at a row's own position.
     *
     * @param column the lane column it stands in, already brought inside the budget
     * @param color  a lane palette index, {@link #STASH} or {@link #ATTENTION}
     * @param merge  whether the commit has more than one parent
     */
    record Node(NodeShape shape, int column, int color, boolean merge) {
    }
}
