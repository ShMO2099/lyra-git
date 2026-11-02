package com.jfx.git.graph;

import com.jfx.git.graph.RowPlan.Anchor;
import com.jfx.git.graph.RowPlan.EdgeShape;
import com.jfx.git.graph.RowPlan.Node;
import com.jfx.git.graph.RowPlan.NodeShape;
import com.jfx.git.graph.RowPlan.Segment;
import com.jfx.git.model.CommitInfo;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Draws a {@link RowPlan} onto a row's lane canvas.
 *
 * <p>Every decision is already in the plan. What is left is turning lane columns and
 * named heights into coordinates at this density, palette indices into the current
 * theme's colours, and strokes into canvas calls - all in logical coordinates: the cell
 * sizes the canvas and sets the zoom transform before handing it over.
 */
final class RowPainter {

    /**
     * Corner radius where an edge turns from vertical to horizontal.
     *
     * <p>Collapses when there is no room for it — two adjacent lanes have barely a lane
     * width between them, and a corner larger than the run it turns into renders as a
     * wobble rather than a corner.
     */
    private static final double CORNER = 6.0;

    private final GraphDensity density;
    private final int rowHeight;
    private final int midY;

    /**
     * The stash diamond's corners, reused: the canvas copies a polygon's points when it is
     * drawn, so one pair of arrays serves every stash row rather than two new ones a paint.
     */
    private final double[] diamondX = new double[4];
    private final double[] diamondY = new double[4];

    RowPainter(GraphDensity density) {
        this.density = density;
        this.rowHeight = density.rowHeight();
        this.midY = rowHeight / 2;
    }

    /**
     * Draws {@code plan}: the overflow marker, then each segment in order, then the node.
     *
     * @param commit the row's commit, whose author an avatar node shows; {@code null} on
     *               rows without one
     */
    void paint(GraphicsContext gc, RowPlan plan, CommitInfo commit) {
        if (plan.hasMarker()) {
            drawOverflowMarker(gc, x(plan, plan.marker()));
        }
        double r = radius(plan.node());
        for (Segment segment : plan.segments()) {
            drawSegment(gc, plan, segment, r);
        }
        if (plan.node() != null) {
            drawNode(gc, plan, plan.node(), commit, r);
        }
        gc.setLineDashes(null);
    }

    /**
     * Radius of the node's outline, which the {@code NODE_*} anchors sit on: the avatar's
     * when the node is one, the dot's for everything else.
     */
    private double radius(Node node) {
        return node != null && node.shape() == NodeShape.AVATAR
                ? density.nodeRadius(GraphNodeStyle.AUTHOR)
                : density.dotRadius();
    }

    private void drawSegment(GraphicsContext gc, RowPlan plan, Segment segment, double r) {
        double x1 = x(plan, segment.fromColumn()) - (segment.from() == Anchor.NODE_LEFT ? r : 0);
        double y1 = y(segment.from(), r);
        double x2 = x(plan, segment.toColumn()) - (segment.to() == Anchor.NODE_LEFT ? r : 0);
        double y2 = y(segment.to(), r);
        Color color = color(segment.color());
        switch (segment.stroke()) {
            case LANE -> {
                gc.setStroke(color);
                gc.setLineWidth(2.0);
                trace(gc, x1, y1, x2, y2, segment.shape());
            }
            case CASED -> {
                // Stroked twice: first in the row's own background at a wider stroke, so an
                // edge passing over a vertical lane does not merge into it and read as a
                // junction that is not there. Cheap: two strokes of the same path, only on
                // the edges that actually cross columns.
                gc.setStroke(GraphPalette.ground());
                gc.setLineWidth(3.6);
                trace(gc, x1, y1, x2, y2, segment.shape());
                gc.setStroke(color);
                gc.setLineWidth(2.0);
                trace(gc, x1, y1, x2, y2, segment.shape());
            }
            case DASHED -> {
                gc.setLineDashes(3);
                gc.setStroke(color);
                gc.setLineWidth(2.0);
                trace(gc, x1, y1, x2, y2, segment.shape());
                gc.setLineDashes(null);
            }
            case TIE -> {
                gc.setStroke(color);
                gc.setLineWidth(1.0);
                gc.setLineDashes(2);
                trace(gc, x1, y1, x2, y2, segment.shape());
                gc.setLineDashes(null);
            }
        }
    }

    private void drawNode(GraphicsContext gc, RowPlan plan, Node node, CommitInfo commit, double r) {
        double x = x(plan, node.column());
        Color color = color(node.color());
        Color ground = GraphPalette.ground();
        switch (node.shape()) {
            case DOT -> drawDotNode(gc, x, midY, r, color, ground, node.merge());
            case AVATAR -> drawAuthorNode(gc, commit, x, midY, r, color, ground, node.merge());
            case OVERFLOW_RING -> {
                double small = density.dotRadius();
                gc.setFill(ground);
                gc.fillOval(x - small, midY - small, small * 2, small * 2);
                gc.setStroke(color);
                gc.setLineWidth(1.6);
                gc.strokeOval(x - small, midY - small, small * 2, small * 2);
            }
            case STASH_DIAMOND -> {
                diamondX[0] = x;     diamondY[0] = midY - r;
                diamondX[1] = x + r; diamondY[1] = midY;
                diamondX[2] = x;     diamondY[2] = midY + r;
                diamondX[3] = x - r; diamondY[3] = midY;
                gc.setFill(color);
                gc.fillPolygon(diamondX, diamondY, 4);
            }
            case WIP_RING -> {
                gc.setStroke(color);
                gc.setLineWidth(2.0);
                gc.setLineDashes(3);
                gc.strokeOval(x - r, midY - r, r * 2.0, r * 2.0);
                gc.setLineDashes(null);
            }
        }
    }

    /**
     * The dotted line in the graph's last column that stands for every lane past the
     * budget. See {@link GraphMetrics#hiddenLanesProperty()} for the count the header
     * shows beside it.
     */
    private void drawOverflowMarker(GraphicsContext gc, double x) {
        gc.setStroke(GraphPalette.overflow());
        gc.setLineWidth(1.5);
        gc.setLineDashes(2, 3);
        gc.strokeLine(x, 0, x, rowHeight);
        gc.setLineDashes(null);
    }

    /**
     * A filled circle, hollow for a merge.
     *
     * <p>Shape carries the meaning and colour only reinforces it. The hole in a merge is
     * the row's own background rather than a literal: it used to be {@code #0d1117}, which
     * on a white ground drew a black pip where a hole was intended.
     */
    private static void drawDotNode(GraphicsContext gc, double x, double y, double r,
                                    Color color, Color ground, boolean merge) {
        if (merge) {
            gc.setFill(ground);
            gc.fillOval(x - r, y - r, r * 2.0, r * 2.0);
            gc.setStroke(color);
            gc.setLineWidth(2.4);
            gc.strokeOval(x - r, y - r, r * 2.0, r * 2.0);
        } else {
            gc.setFill(color);
            gc.fillOval(x - r, y - r, r * 2.0, r * 2.0);
            gc.setStroke(ground);
            gc.setLineWidth(1.5);
            gc.strokeOval(x - r, y - r, r * 2.0, r * 2.0);
        }
    }

    /**
     * The author's avatar at the node, ringed in the lane's colour.
     *
     * <p>Says who and where in one position, which is what lets the row drop its author
     * column entirely. The ring keeps doing the job the dot's outline did — it is the
     * lane's colour, and it thickens for a merge — so nothing that was readable from the
     * shape is lost.
     *
     * <p>A gap is cleared in the row's background first, so lanes running behind the node
     * do not appear to pass through the avatar.
     */
    private static void drawAuthorNode(GraphicsContext gc, CommitInfo commit, double x, double y,
                                       double r, Color color, Color ground, boolean merge) {
        double ring = merge ? 2.6 : 2.0;

        gc.setFill(ground);
        gc.fillOval(x - r - 1, y - r - 1, (r + 1) * 2.0, (r + 1) * 2.0);

        // Inside the ring, so the ring is not drawn over the face.
        double inner = r - ring / 2.0;
        if (commit != null) {
            Avatars.paintOn(gc, commit.getAuthorName(), commit.getAuthorEmail(), x, y, inner);
        }

        gc.setStroke(color);
        gc.setLineWidth(ring);
        gc.strokeOval(x - r + ring / 2.0, y - r + ring / 2.0,
                (r - ring / 2.0) * 2.0, (r - ring / 2.0) * 2.0);
    }

    /** A straight segment as a line; a turning one through {@link #traceEdge}. */
    private static void trace(GraphicsContext gc, double x1, double y1, double x2, double y2,
                              EdgeShape shape) {
        if (shape == EdgeShape.STRAIGHT) {
            gc.strokeLine(x1, y1, x2, y2);
        } else {
            traceEdge(gc, x1, y1, x2, y2, shape);
        }
    }

    /**
     * Traces one edge: vertical, a rounded corner, a horizontal run, another corner.
     *
     * <p>Replaces a cubic bezier whose control points sat at the row's vertical midpoint.
     * That curve was never vertical and never horizontal — it was diagonal for the entire
     * row — so an edge travelling three or four lanes cut across every lane in between at
     * a shallow angle, and the casing had to fight to keep it apart from them.
     *
     * <p>Turning at right angles keeps lanes parallel where they are running and puts
     * crossings at ninety degrees, which is the angle the eye separates most easily.
     */
    private static void traceEdge(GraphicsContext gc, double x1, double y1, double x2, double y2,
                                  EdgeShape shape) {
        if (x1 == x2) {
            gc.strokeLine(x1, y1, x2, y2);
            return;
        }

        double direction = x2 > x1 ? 1 : -1;
        double run = Math.abs(x2 - x1);
        double rise = Math.abs(y2 - y1);
        double radius = Math.min(CORNER, Math.min(run, rise));
        double vertical = y2 > y1 ? 1 : -1;

        gc.beginPath();
        gc.moveTo(x1, y1);
        if (shape == EdgeShape.ACROSS_THEN_DOWN) {
            // Leaving a node: across first, then turn and drop away to the parent.
            gc.lineTo(x2 - direction * radius, y1);
            gc.quadraticCurveTo(x2, y1, x2, y1 + vertical * radius);
            gc.lineTo(x2, y2);
        } else {
            // Arriving from a child above: fall down its own lane to the node's height,
            // turn, then across into the node.
            gc.lineTo(x1, y2 - vertical * radius);
            gc.quadraticCurveTo(x1, y2, x1 + direction * radius, y2);
            gc.lineTo(x2, y2);
        }
        gc.stroke();
    }

    /** X-coordinate of the centre of a lane column, at this density and the plan's style. */
    private int x(RowPlan plan, int column) {
        return density.laneX(column, plan.style(), plan.budget());
    }

    /** Y-coordinate of {@code anchor}, around a node of radius {@code r}. */
    private double y(Anchor anchor, double r) {
        return switch (anchor) {
            case TOP -> 0;
            case NODE_TOP -> midY - r;
            case MIDDLE, NODE_LEFT -> midY;
            case NODE_BOTTOM -> midY + r;
            case BOTTOM -> rowHeight;
        };
    }

    /** The theme's colour for a plan's colour index, read now so a theme switch shows at once. */
    private static Color color(int index) {
        return switch (index) {
            case RowPlan.STASH -> GraphPalette.stash();
            case RowPlan.ATTENTION -> GraphPalette.attention();
            default -> GraphPalette.lane(index);
        };
    }
}
