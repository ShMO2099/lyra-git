package com.jfx.git.graph;

import javafx.beans.binding.Bindings;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * The draggable boundary between the commit graph and the messages beside it.
 *
 * <h2>Full height, not just the heading</h2>
 * It first lived in the column header, which is where the boundary is named — and that is
 * the wrong place to grab it. The header strip is twenty-two pixels tall in a window
 * hundreds of pixels deep, so the handle was a sliver at the very top that had to be aimed
 * at. Commercial clients let you take the boundary anywhere down the graph, because the boundary
 * runs the whole way down. This does the same: it stands over the header and the list
 * together, so wherever the eye is, the grip is beside it.
 *
 * <h2>Everything moves together; only the settling is quantised</h2>
 * The graph draws whole lanes, so the width it comes to rest at is always a multiple of
 * one. Two earlier attempts each got half of this right. Applying the rounded width on
 * every drag event made the boundary jump between lanes and stand still in between, which
 * read as the handle lagging the pointer. Moving only the line and leaving the graph until
 * release cured the lag and replaced it with a different wrongness: a boundary visibly not
 * bounding anything.
 *
 * <p>So the column width follows the pointer exactly and the <em>lane count</em> falls out
 * of it — a lane appears or vanishes as the edge crosses a multiple, while the boundary,
 * the messages and the drawing all travel together. On release the width is rounded to
 * whole lanes and remembered, which is the only moment anything is written down.
 */
public final class LaneDivider extends Region {

    /** Wide enough to hit without stealing a noticeable strip of the rows behind it. */
    public static final double GRIP = 7;

    /** Marks the line while it is being dragged rather than merely pointed at. */
    private static final String ACTIVE = "graph-lane-resizer-active";

    /** Below this a press is a click, not a drag, and must not pin the width. */
    private static final double DRAG_THRESHOLD = 3;

    private final double perLane;
    private final double edgeWidth;

    private double pressSceneX;
    private double pressWidth;
    private boolean dragged;

    /** Notified once, when a drag finishes, so the width can be remembered. */
    private Runnable onBudgetCommitted;

    public LaneDivider(GraphDensity density, GraphMetrics metrics, GraphNodeStyle nodeStyle) {
        this.perLane = density.laneWidth(nodeStyle);
        this.edgeWidth = density.edgeWidth();

        getStyleClass().add("graph-lane-resizer");
        setCursor(Cursor.H_RESIZE);
        setMinWidth(GRIP);
        setPrefWidth(GRIP);
        setMaxWidth(GRIP);
        // Fills the height of whatever it is laid over; the width above keeps it a strip.
        setMaxHeight(Double.MAX_VALUE);
        Tooltip.install(this, new Tooltip(
                "Drag to set how wide the graph may grow — double-click to follow the window"));

        // Stays bound throughout, drag included. The width it follows is continuous while
        // the pointer is down, so the line tracks it without ever being driven by hand.
        translateXProperty().bind(Bindings.createDoubleBinding(
                () -> edgeWidth + metrics.railWidthProperty().get()
                        + metrics.laneWidthProperty().get() - GRIP / 2,
                metrics.railWidthProperty(), metrics.laneWidthProperty()));

        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            pressSceneX = e.getSceneX();
            pressWidth = metrics.laneWidthProperty().get();
            dragged = false;
            e.consume();
        });

        addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            double moved = e.getSceneX() - pressSceneX;
            if (Math.abs(moved) >= DRAG_THRESHOLD) {
                dragged = true;
                if (!getStyleClass().contains(ACTIVE)) {
                    getStyleClass().add(ACTIVE);
                }
            }
            // Measured from where the press began rather than accumulated per event: a
            // drag that outruns the graph's limits and comes back tracks the pointer
            // again, where accumulated deltas would have drifted by whatever was clamped.
            metrics.previewLaneWidth(pressWidth + moved);
            e.consume();
        });

        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            getStyleClass().remove(ACTIVE);
            if (dragged) {
                // Rounds the width the pointer left behind to the lanes actually drawn, so
                // the edge comes to rest against the graph rather than beside it.
                metrics.setLaneBudget(lanesFor(metrics.laneWidthProperty().get()));
                commit();
            }
            e.consume();
        });

        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                metrics.clearLaneBudget();
                commit();
                e.consume();
            }
        });
    }

    /** The number of whole lanes a column {@code width} pixels wide holds. */
    private int lanesFor(double width) {
        return (int) Math.round((width - GraphMetrics.LANE_PADDING) / perLane);
    }

    /** Registers what to do once a drag has settled on a width. */
    public void setOnBudgetCommitted(Runnable handler) {
        this.onBudgetCommitted = handler;
    }

    private void commit() {
        if (onBudgetCommitted != null) {
            onBudgetCommitted.run();
        }
    }
}
