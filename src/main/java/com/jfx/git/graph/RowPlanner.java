package com.jfx.git.graph;

import com.jfx.git.graph.RowPlan.Anchor;
import com.jfx.git.graph.RowPlan.EdgeShape;
import com.jfx.git.graph.RowPlan.Node;
import com.jfx.git.graph.RowPlan.NodeShape;
import com.jfx.git.graph.RowPlan.Segment;
import com.jfx.git.graph.RowPlan.Stroke;
import com.jfx.git.model.CommitInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out a {@link RowPlan} for a row of the commit graph.
 *
 * <p>Every decision about the lane drawing is made here: which edges a row draws, which
 * way each one turns, what falls past the lane budget and what the node is. The painter
 * only turns the answer into canvas calls.
 */
final class RowPlanner {

    private RowPlanner() {
    }

    /**
     * The plan for {@code row} at {@code budget} lanes in {@code style}, worked out once.
     *
     * <p>A plan depends on the row's layout, the page's lane count, the budget and the
     * node style. The last three are checked on every call: a dragged lane divider or a
     * window resize moves the budget, and a filter or a stash row can move the lane count.
     * The layout itself is never re-checked, because it never changes under a row - every
     * page load, filter and stash placement makes new rows over a layout worked out for
     * them ({@link GraphPageBuilder}, {@link FilteredGraph}, {@link StashRows}) - so the
     * filter's copies get plans of their own rather than inheriting the full page's.
     *
     * <p>Kept on the row, not the cell: a list recycles a few dozen cells through every
     * row on the page, so a plan kept on the cell would be worked out again on every
     * scroll tick.
     *
     * @param budget lane columns drawn one by one, at least one; see {@link GraphMetrics#maxLanes()}
     */
    static RowPlan planFor(GitGraphRow row, int budget, GraphNodeStyle style) {
        RowPlan kept = row.plan();
        if (kept != null && kept.isFor(budget, style, row.getTotalLanes())) {
            return kept;
        }
        RowPlan plan = plan(row, budget, style);
        row.keepPlan(plan);
        return plan;
    }

    /** Works out {@code row}'s plan afresh; {@link #planFor} is what painting uses. */
    static RowPlan plan(GitGraphRow row, int budget, GraphNodeStyle style) {
        return switch (row.getKind()) {
            case LOAD_MORE -> blank(row, budget, style);
            case UNCOMMITTED -> uncommitted(row, budget, style);
            case STASH -> stash(row, budget, style);
            case COMMIT -> row.getCommit() == null ? blank(row, budget, style) : commit(row, budget, style);
        };
    }

    /** The load-more row: the page's width, with nothing drawn in it. */
    private static RowPlan blank(GitGraphRow row, int budget, GraphNodeStyle style) {
        return new RowPlan(row.getTotalLanes(), budget, style, RowPlan.NO_MARKER, List.of(), null);
    }

    /**
     * A commit's lanes, edges and node.
     *
     * <p>Passing lanes run straight through. An edge arriving from a child in another lane
     * comes straight down its own lane and turns into the node at the node's height
     * ({@link EdgeShape#DOWN_THEN_ACROSS}); an edge leaving for a parent in another lane
     * runs across at the node's height and turns down ({@link EdgeShape#ACROSS_THEN_DOWN}).
     * So a branch line stays in its lane until the row where it actually joins.
     *
     * <p>Lanes past the budget all land in the last column. Drawn one on top of another
     * they would read as one confused line; instead that column shows a dotted marker for
     * all of them, and edges between a drawn lane and the marker still connect - but only
     * by their leg at the node's height. The marker stands for the lanes above and below
     * it; drawing each hidden lane's run down that one column is the stacking the marker
     * replaces.
     */
    private static RowPlan commit(GitGraphRow row, int budget, GraphNodeStyle style) {
        // Everything positional is read from the layout; what the node looks like (merge)
        // from the real commit, whose parents a filtered layout has rewritten.
        CommitInfo real = row.getCommit();
        CommitInfo layout = row.getLayout() != null ? row.getLayout() : real;
        int lanes = row.getTotalLanes();
        int myLane = layout.getLane();
        int me = column(myLane, budget);

        boolean overflowing = lanes > budget;
        // Lanes at or past this column are hidden; MAX_VALUE when nothing overflows.
        int lastColumn = overflowing ? budget - 1 : Integer.MAX_VALUE;
        boolean mine = myLane >= lastColumn;
        int marker = overflowing && (mine
                || anyAtOrPast(layout.getPassingLanes(), lastColumn)
                || anyAtOrPast(layout.getChildLanes(), lastColumn)
                || anyAtOrPast(layout.getParentLanes(), lastColumn))
                ? lastColumn : RowPlan.NO_MARKER;

        List<Segment> segments = new ArrayList<>(layout.getPassingLanes().size()
                + layout.getChildLanes().size() + layout.getParentLanes().size());

        // 1. Passing lanes: straight through, top to bottom.
        for (int passLane : layout.getPassingLanes()) {
            if (passLane >= lastColumn) continue;
            int px = column(passLane, budget);
            segments.add(new Segment(px, Anchor.TOP, px, Anchor.BOTTOM,
                    EdgeShape.STRAIGHT, Stroke.LANE, layout.colorOfLane(passLane)));
        }

        // 2. Arriving from children: down their own lane, then into the node, in the colour
        //    of the branch they come from.
        for (int childLane : layout.getChildLanes()) {
            if (mine && childLane >= lastColumn) continue;
            int cx = column(childLane, budget);
            int color = layout.childLaneColorOf(childLane);
            if (childLane >= lastColumn) {
                // From the overflow column: only the leg into the node, at its height.
                segments.add(new Segment(cx, Anchor.MIDDLE, me, Anchor.MIDDLE,
                        EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, color));
            } else if (childLane == myLane) {
                segments.add(new Segment(cx, Anchor.TOP, cx, Anchor.NODE_TOP,
                        EdgeShape.STRAIGHT, Stroke.LANE, color));
            } else {
                segments.add(new Segment(cx, Anchor.TOP, me, Anchor.MIDDLE,
                        EdgeShape.DOWN_THEN_ACROSS, Stroke.CASED, color));
            }
        }

        // 3. Leaving for parents: across from the node, then down.
        for (int parentLane : layout.getParentLanes()) {
            if (mine && parentLane >= lastColumn) continue;
            int px = column(parentLane, budget);
            int color = layout.colorOfLane(parentLane);
            if (parentLane >= lastColumn) {
                // Into the overflow column: only the leg out of the node.
                segments.add(new Segment(me, Anchor.MIDDLE, px, Anchor.MIDDLE,
                        EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, color));
            } else if (parentLane == myLane) {
                segments.add(new Segment(px, Anchor.NODE_BOTTOM, px, Anchor.BOTTOM,
                        EdgeShape.STRAIGHT, Stroke.LANE, color));
            } else {
                segments.add(new Segment(me, Anchor.MIDDLE, px, Anchor.BOTTOM,
                        EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, color));
            }
        }

        // 4. The node. One in the overflow column is a small hollow ring.
        NodeShape shape = mine ? NodeShape.OVERFLOW_RING
                : style == GraphNodeStyle.AUTHOR ? NodeShape.AVATAR : NodeShape.DOT;
        boolean merge = real.getParentHashes().size() > 1;
        Node node = new Node(shape, me, layout.getColorIndex(), merge);
        return new RowPlan(lanes, budget, style, marker, segments, node);
    }

    /**
     * A stash: a detached diamond in its own lane, with a dotted tie to its parent's lane.
     *
     * <p>Deliberately not a dot on a lane line: a stash has no children and is not on any
     * branch, so joining it to the graph would draw a relationship that does not exist.
     * The tie shows where it came from without implying it is part of the history.
     *
     * <p>The row was inserted after the page was laid out, so every lane that crosses the
     * gap it opened runs straight through it - left out, each of those lanes visibly broke
     * at every stash row. A crossing lane past the budget is left to the overflow marker.
     *
     * <p>The tie and the diamond take no notice of the budget beyond being kept inside it:
     * a stash's lane is past every commit lane, so on a page that overflows the diamond
     * sits in the overflow column, and the tie runs to its parent's column, which may be
     * that column too.
     */
    private static RowPlan stash(GitGraphRow row, int budget, GraphNodeStyle style) {
        int lanes = row.getTotalLanes();
        boolean overflowing = lanes > budget;
        int lastColumn = budget - 1;
        int marker = RowPlan.NO_MARKER;

        List<Integer> through = row.getThroughLanes();
        List<Segment> segments = new ArrayList<>(through.size() + 1);
        for (int lane : through) {
            if (overflowing && lane >= lastColumn) {
                marker = lastColumn;
                continue;
            }
            int x = column(lane, budget);
            segments.add(new Segment(x, Anchor.TOP, x, Anchor.BOTTOM,
                    EdgeShape.STRAIGHT, Stroke.LANE, row.throughLaneColor(lane)));
        }

        // Across from the diamond's left point, then down into the lane of the commit it
        // was taken from, which is the row directly below - with the same rounded turn
        // every other edge has.
        int me = column(row.getStashLane(), budget);
        segments.add(new Segment(me, Anchor.NODE_LEFT, column(row.getTieLane(), budget), Anchor.BOTTOM,
                EdgeShape.ACROSS_THEN_DOWN, Stroke.TIE, RowPlan.STASH));

        Node node = new Node(NodeShape.STASH_DIAMOND, me, RowPlan.STASH, false);
        return new RowPlan(lanes, budget, style, marker, segments, node);
    }

    /**
     * The uncommitted changes: a dashed ring on HEAD's lane, with a dashed edge down to HEAD.
     *
     * <p>The changes sit on top of HEAD, and the edge says so. The row's layout is a
     * pseudo-commit whose parent is HEAD, so the lane assigner has already put it on HEAD's
     * lane and reserved the lane down to it. A row built without one (an engine that did
     * not lay it out) falls back to lane 0 with no edge.
     *
     * <p>Not overflow-aware: on HEAD's lane past the budget the ring and its edge are drawn
     * in the overflow column, without the marker a commit row there would carry.
     */
    private static RowPlan uncommitted(GitGraphRow row, int budget, GraphNodeStyle style) {
        CommitInfo layout = row.getLayout();
        int lane = layout == null ? 0 : layout.getLane();
        int me = column(lane, budget);

        List<Segment> segments = new ArrayList<>(layout == null ? 0 : layout.getParentLanes().size());
        if (layout != null) {
            for (int parentLane : layout.getParentLanes()) {
                int px = column(parentLane, budget);
                int color = layout.colorOfLane(parentLane);
                if (parentLane == lane) {
                    segments.add(new Segment(me, Anchor.NODE_BOTTOM, me, Anchor.BOTTOM,
                            EdgeShape.STRAIGHT, Stroke.DASHED, color));
                } else {
                    segments.add(new Segment(me, Anchor.MIDDLE, px, Anchor.BOTTOM,
                            px == me ? EdgeShape.STRAIGHT : EdgeShape.ACROSS_THEN_DOWN,
                            Stroke.DASHED, color));
                }
            }
        }

        Node node = new Node(NodeShape.WIP_RING, me, RowPlan.ATTENTION, false);
        return new RowPlan(row.getTotalLanes(), budget, style, RowPlan.NO_MARKER, segments, node);
    }

    /**
     * The column {@code lane} is drawn in: a lane past the budget shares the last column
     * rather than falling off the canvas, where it would leave a node missing with no
     * explanation. The same clamp as {@link GraphDensity#laneX}.
     */
    private static int column(int lane, int budget) {
        return Math.min(Math.max(lane, 0), budget - 1);
    }

    /** Whether any of {@code lanes} is at or past {@code column}. */
    private static boolean anyAtOrPast(List<Integer> lanes, int column) {
        for (int i = 0, n = lanes.size(); i < n; i++) {
            if (lanes.get(i) >= column) return true;
        }
        return false;
    }
}
