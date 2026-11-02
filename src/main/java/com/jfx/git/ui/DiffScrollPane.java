package com.jfx.git.ui;

import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.Region;

/**
 * The viewport around a {@link DiffTextArea}: the text, and a horizontal scrollbar when
 * a line is wider than the view. Nothing vertical - that is {@link DiffScrollMap}'s job.
 *
 * <p>Replaces Flowless's {@code VirtualizedScrollPane}, which kept a vertical bar bound
 * both ways to the text's scroll estimate even while hidden. Its two listeners for that
 * axis are not guarded against each other, and the bar position is rounded to whole
 * numbers on the way out and back, so an estimate that does not round-trip exactly
 * nudged the text by a pixel, which moved the estimate, which nudged it again: the diff
 * "shaking" after a reload, and the "scrollbar keeps jumping" of older reports. With no
 * vertical bar there is nothing to write a position back. The horizontal bar here syncs
 * under a flag, in one direction at a time.
 *
 * <p>The mouse wheel needs no handling: Flowless's {@code VirtualFlow} inside the area
 * scrolls itself.
 */
public final class DiffScrollPane extends Region {

    private final DiffTextArea content;
    private final ScrollBar hbar = new ScrollBar();
    /** Set while this pane writes to the bar or the content, so the other side's listener stands down. */
    private boolean syncing;

    public DiffScrollPane(DiffTextArea content) {
        this.content = content;
        getStyleClass().add("diff-scroll");
        hbar.setOrientation(Orientation.HORIZONTAL);
        hbar.setMin(0);
        hbar.setUnitIncrement(16);
        hbar.setManaged(false);
        hbar.setVisible(false);
        getChildren().addAll(content, hbar);

        // Content moved (wheel, drag, a show call): the bar follows at the next layout.
        content.estimatedScrollXProperty().addListener(obs -> {
            if (!syncing) {
                requestLayout();
            }
        });
        content.totalWidthEstimateProperty().addListener(obs -> requestLayout());
        // The bar moved by hand: the content follows, once.
        hbar.valueProperty().addListener((obs, old, value) -> {
            if (syncing) {
                return;
            }
            syncing = true;
            try {
                content.scrollXToPixel(value.doubleValue());
            } finally {
                syncing = false;
            }
        });
    }

    /** The text area this pane scrolls. */
    public DiffTextArea getContent() {
        return content;
    }

    /** The horizontal bar, for tests. */
    ScrollBar horizontalBar() {
        return hbar;
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        Double estimate = content.totalWidthEstimateProperty().getValue();
        double total = estimate == null ? 0 : estimate;
        boolean needed = total > width + 0.5;
        hbar.setVisible(needed);
        double barHeight = needed ? snapSizeY(hbar.prefHeight(-1)) : 0;
        content.resizeRelocate(0, 0, width, Math.max(0, height - barHeight));
        if (!needed) {
            return;
        }
        hbar.resizeRelocate(0, height - barHeight, width, barHeight);
        // JavaFX's own ScrollPane idiom: the range is the hidden part, the visible
        // amount the viewport's share of it, the value the content's own offset.
        double max = Math.max(0, total - width);
        syncing = true;
        try {
            hbar.setMax(max);
            hbar.setVisibleAmount(total > 0 ? width / total * max : 0);
            hbar.setBlockIncrement(width);
            Double offset = content.estimatedScrollXProperty().getValue();
            hbar.setValue(Math.max(0, Math.min(max, offset == null ? 0 : offset)));
        } finally {
            syncing = false;
        }
    }
}
