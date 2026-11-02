package com.jfx.git.graph;

import javafx.beans.binding.Bindings;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * The trailing edge of the ref column, draggable.
 *
 * <p>The column sizes itself to its names within a share of the window, which is right
 * until it is not: a repository of long namespaced branches on a narrow window, or a
 * reader who would rather give that width to the commit messages. Either way it is their
 * call, and a column whose width cannot be changed is a decision made once for everybody.
 *
 * <p>The same gesture as {@link LaneDivider} one boundary to the right: drag to set,
 * double-click to hand the width back.
 */
public final class RailDivider extends Region {

    private static final String ACTIVE = "graph-rail-resizer-active";

    /** Movement below this is a click, not a drag. */
    private static final double DRAG_THRESHOLD = 3;

    private double pressSceneX;
    private double pressWidth;
    private boolean dragged;

    private Runnable onWidthCommitted;

    public RailDivider(GraphDensity density, GraphMetrics metrics) {
        double edgeWidth = density.edgeWidth();

        // Its own class, not the lane divider's: they look alike, but they are two
        // handles, and anything that finds one by class must not be handed the other.
        getStyleClass().add("graph-rail-resizer");
        setCursor(Cursor.H_RESIZE);
        setMinWidth(LaneDivider.GRIP);
        setPrefWidth(LaneDivider.GRIP);
        setMaxWidth(LaneDivider.GRIP);
        setMaxHeight(Double.MAX_VALUE);
        Tooltip.install(this, new Tooltip(
                "Drag to set the width of the branch column - double-click to fit it to the names"));

        translateXProperty().bind(Bindings.createDoubleBinding(
                () -> edgeWidth + metrics.railWidthProperty().get() - LaneDivider.GRIP / 2,
                metrics.railWidthProperty()));

        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            pressSceneX = e.getSceneX();
            pressWidth = metrics.railWidthProperty().get();
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
            if (dragged) {
                metrics.previewRailWidth(pressWidth + moved);
            }
            e.consume();
        });

        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            getStyleClass().remove(ACTIVE);
            if (dragged) {
                metrics.setRailWidth(metrics.railWidthProperty().get());
                commit();
            }
            e.consume();
        });

        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                metrics.clearRailWidth();
                commit();
                e.consume();
            }
        });
    }

    /** Called when a drag ends or the width is handed back, so it can be remembered. */
    public void setOnWidthCommitted(Runnable handler) {
        this.onWidthCommitted = handler;
    }

    private void commit() {
        if (onWidthCommitted != null) {
            onWidthCommitted.run();
        }
    }
}
