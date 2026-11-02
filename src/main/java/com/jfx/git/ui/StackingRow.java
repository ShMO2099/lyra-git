package com.jfx.git.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

/**
 * A row in two parts: a lead that takes the width left over, and a trail beside it at its
 * preferred width - for as long as that leaves the lead a comfortable width. When it would
 * not, the trail moves to a line of its own under the lead, as wide as the row, and a
 * trail that wraps ({@link Flow}) wraps there.
 *
 * <p>Decided on every layout pass, from both parts' preferred widths as they are then. The
 * graph's filter bar did this with a listener on its own width, which decided only when the
 * bar was resized: typing brought a clear button into the trail, the trail outgrew the room
 * it had been given, and nothing moved it - its last control was cut short instead. A part
 * asking for layout reaches this row like any other change, and the row decides again.
 */
final class StackingRow extends Region {

    private final Node lead;
    private final Node trail;
    private final double leadComfortable;
    private final double hgap;
    private final double vgap;

    /**
     * @param leadComfortable the narrowest the lead is left beside the trail
     * @param hgap            between the lead and the trail when they are side by side
     * @param vgap            between them when the trail is on a line of its own
     */
    StackingRow(Node lead, Node trail, double leadComfortable, double hgap, double vgap) {
        this.lead = lead;
        this.trail = trail;
        this.leadComfortable = leadComfortable;
        this.hgap = hgap;
        this.vgap = vgap;
        getChildren().setAll(lead, trail);
    }

    /** Whether the trail is on a line of its own at the row's current width. */
    boolean isStacked() {
        return !besideFits(insideWidth(getWidth()));
    }

    private boolean besideFits(double insideWidth) {
        return insideWidth >= leadComfortable + hgap + trailWidth();
    }

    private double trailWidth() {
        return snapSizeX(trail.prefWidth(-1));
    }

    private double insideWidth(double width) {
        return width - snappedLeftInset() - snappedRightInset();
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinWidth(double height) {
        return snappedLeftInset() + Math.max(lead.minWidth(-1), trail.minWidth(-1)) + snappedRightInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + Math.max(lead.prefWidth(-1), leadComfortable) + hgap + trailWidth()
                + snappedRightInset();
    }

    /** No shorter than it needs: squeezed, the stacked trail would be drawn over what follows. */
    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        double inside = width < 0 ? -1 : insideWidth(width);
        double height;
        if (inside < 0 || besideFits(inside)) {
            double trailWidth = trailWidth();
            double leadWidth = inside < 0 ? lead.prefWidth(-1) : inside - hgap - trailWidth;
            height = Math.max(lead.prefHeight(leadWidth), trail.prefHeight(trailWidth));
        } else {
            height = lead.prefHeight(inside) + vgap + trail.prefHeight(inside);
        }
        return snappedTopInset() + snapSizeY(height) + snappedBottomInset();
    }

    @Override
    protected void layoutChildren() {
        double x = snappedLeftInset();
        double y = snappedTopInset();
        double width = insideWidth(getWidth());
        double height = getHeight() - y - snappedBottomInset();
        if (besideFits(width)) {
            double trailWidth = trailWidth();
            double leadWidth = width - hgap - trailWidth;
            layoutInArea(lead, x, y, leadWidth, height, 0, Insets.EMPTY, true, false,
                    HPos.LEFT, VPos.CENTER);
            layoutInArea(trail, x + leadWidth + hgap, y, trailWidth, height, 0, Insets.EMPTY, true, false,
                    HPos.LEFT, VPos.CENTER);
        } else {
            double leadHeight = snapSizeY(lead.prefHeight(width));
            layoutInArea(lead, x, y, width, leadHeight, 0, Insets.EMPTY, true, false,
                    HPos.LEFT, VPos.CENTER);
            layoutInArea(trail, x, y + leadHeight + vgap, width, Math.max(0, height - leadHeight - vgap),
                    0, Insets.EMPTY, true, false, HPos.LEFT, VPos.TOP);
        }
    }

    /**
     * A flow for the trail: its preferred width is all of it on one line.
     *
     * <p>A plain {@code FlowPane} prefers its {@code prefWrapLength} - 400 unless set - or
     * more, whatever it holds, so the row could not ask it how wide it is.
     */
    static final class Flow extends FlowPane {

        Flow(double hgap, double vgap, Node... children) {
            super(hgap, vgap, children);
        }

        @Override
        protected double computePrefWidth(double height) {
            // Summed as FlowPane sums a line when deciding where it ends, plus a hundredth
            // of a pixel: given this width, rounded to the pixel, the last child can never
            // come out a rounding error too wide for the line and wrap on its own.
            double gap = snapSpaceX(getHgap());
            double line = 0;
            double toLastEnd = 0;
            for (Node child : getManagedChildren()) {
                Insets margin = getMargin(child);
                double left = margin == null ? 0 : snapSpaceX(margin.getLeft());
                double right = margin == null ? 0 : snapSpaceX(margin.getRight());
                double min = child.minWidth(-1);
                double width = left + snapSizeX(Math.min(Math.max(child.prefWidth(-1), min),
                        Math.max(min, child.maxWidth(-1)))) + right;
                toLastEnd = line + width;
                line += width + gap;
            }
            return snappedLeftInset() + toLastEnd + 0.01 + snappedRightInset();
        }

        @Override
        protected double computePrefHeight(double width) {
            return super.computePrefHeight(width < 0 ? computePrefWidth(-1) : width);
        }
    }
}
