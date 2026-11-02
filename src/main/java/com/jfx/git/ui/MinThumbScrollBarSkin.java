package com.jfx.git.ui;

import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SkinBase;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;

/**
 * A scrollbar whose thumb never shrinks past something you can see and grab.
 *
 * <p>JavaFX's own skin sizes the thumb to the visible share of the content, with a floor
 * of 1.5 times the bar's width. AtlantaFX draws bars 8 pixels wide, so on a long list -
 * a few thousand commits loaded into the graph - the thumb bottomed out at 12 pixels: a
 * fleck that was hard to find and harder to hit. The floor is a package-private method,
 * so neither CSS nor a subclass can raise it; this skin replaces the default instead, with
 * a {@value #MIN_THUMB}-pixel minimum (about where VS Code's sits).
 *
 * <p>It draws with the same {@code track} and {@code thumb} style classes, as direct
 * children of the bar, so the theme styles it exactly as before. Dragging maps the
 * pointer against <em>this</em> thumb's length, so a thumb held at its minimum still
 * follows the pointer from end to end, and pressing the track pages through
 * {@link ScrollBar#adjustValue}, which a list's virtual scrollbar answers with a screenful.
 * The arrow buttons are left out; the theme hides them anyway. The mouse wheel is the
 * scrolled control's own business and never reaches here.
 */
public final class MinThumbScrollBarSkin extends SkinBase<ScrollBar> {

    /** The shortest the thumb gets, in pixels, unless the track itself is shorter. */
    public static final double MIN_THUMB = 30;

    private final StackPane track = new StackPane();
    private final StackPane thumb = new StackPane();
    /** Pointer offset into the thumb along its length while dragging; negative when not. */
    private double grab = -1;

    public MinThumbScrollBarSkin(ScrollBar bar) {
        super(bar);
        track.getStyleClass().setAll("track");
        thumb.getStyleClass().setAll("thumb");
        getChildren().setAll(track, thumb);

        registerChangeListener(bar.valueProperty(), e -> bar.requestLayout());
        registerChangeListener(bar.minProperty(), e -> bar.requestLayout());
        registerChangeListener(bar.maxProperty(), e -> bar.requestLayout());
        registerChangeListener(bar.visibleAmountProperty(), e -> bar.requestLayout());
        registerChangeListener(bar.orientationProperty(), e -> bar.requestLayout());

        track.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            double length = trackLength();
            if (length > 0) {
                // A page toward the pointer, the way the default skin's track does it.
                bar.adjustValue(Math.max(0, Math.min(1, along(e) / length)));
            }
            e.consume();
        });
        thumb.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            grab = along(e) - thumbStart();
            e.consume();
        });
        thumb.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (grab < 0) return;
            double travel = trackLength() - thumbLength();
            if (travel > 0) {
                double fraction = Math.max(0, Math.min(1, (along(e) - grab) / travel));
                bar.setValue(bar.getMin() + fraction * (bar.getMax() - bar.getMin()));
            }
            e.consume();
        });
        thumb.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> grab = -1);
    }

    /**
     * Gives {@code control}'s own vertical scrollbars this skin - a list's or a tree's -
     * now if it is already skinned and whenever it is skinned again. Set before the bar's
     * first CSS pass, so the default skin is never built.
     */
    public static void installOn(Control control) {
        Runnable apply = () -> {
            for (Node node : control.lookupAll(".scroll-bar")) {
                if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL
                        && !(bar.getSkin() instanceof MinThumbScrollBarSkin)) {
                    bar.setSkin(new MinThumbScrollBarSkin(bar));
                }
            }
        };
        control.skinProperty().addListener((obs, old, skin) -> {
            if (skin != null) apply.run();
        });
        if (control.getSkin() != null) apply.run();
    }

    private boolean vertical() {
        return getSkinnable().getOrientation() == Orientation.VERTICAL;
    }

    /** The pointer's position along the bar, in the bar's own coordinates. */
    private double along(MouseEvent e) {
        Point2D point = getSkinnable().sceneToLocal(e.getSceneX(), e.getSceneY());
        return vertical() ? point.getY() - snappedTopInset() : point.getX() - snappedLeftInset();
    }

    private double trackLength() {
        return vertical() ? track.getHeight() : track.getWidth();
    }

    private double thumbLength() {
        return vertical() ? thumb.getHeight() : thumb.getWidth();
    }

    private double thumbStart() {
        return vertical() ? thumb.getLayoutY() - snappedTopInset() : thumb.getLayoutX() - snappedLeftInset();
    }

    /** The thumb's length for a track {@code length} long; see {@link #MIN_THUMB}. */
    static double thumbLengthFor(ScrollBar bar, double length) {
        double range = bar.getMax() - bar.getMin();
        double portion = range > 0 ? Math.min(1, bar.getVisibleAmount() / range) : 1;
        return Math.max(Math.min(MIN_THUMB, length), Math.min(length, length * portion));
    }

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        ScrollBar bar = getSkinnable();
        double range = bar.getMax() - bar.getMin();
        double fraction = range > 0 ? (bar.getValue() - bar.getMin()) / range : 0;
        fraction = Math.max(0, Math.min(1, fraction));
        track.resizeRelocate(x, y, w, h);
        if (vertical()) {
            double length = snapSizeY(thumbLengthFor(bar, h));
            thumb.resizeRelocate(x, snapPositionY(y + (h - length) * fraction), w, length);
        } else {
            double length = snapSizeX(thumbLengthFor(bar, w));
            thumb.resizeRelocate(snapPositionX(x + (w - length) * fraction), y, length, h);
        }
    }

    // The bar's breadth comes from the theme (-fx-pref-width: 8px on a vertical bar); these
    // only answer for a bar the theme has not sized.

    @Override
    protected double computePrefWidth(double height, double top, double right, double bottom, double left) {
        return vertical() ? left + right + 8 : left + right + 100;
    }

    @Override
    protected double computePrefHeight(double width, double top, double right, double bottom, double left) {
        return vertical() ? top + bottom + 100 : top + bottom + 8;
    }

    @Override
    protected double computeMinWidth(double height, double top, double right, double bottom, double left) {
        return vertical() ? left + right + 4 : left + right + MIN_THUMB;
    }

    @Override
    protected double computeMinHeight(double width, double top, double right, double bottom, double left) {
        return vertical() ? top + bottom + MIN_THUMB : top + bottom + 4;
    }

    @Override
    protected double computeMaxWidth(double height, double top, double right, double bottom, double left) {
        return vertical() ? getSkinnable().prefWidth(-1) : Double.MAX_VALUE;
    }

    @Override
    protected double computeMaxHeight(double width, double top, double right, double bottom, double left) {
        return vertical() ? Double.MAX_VALUE : getSkinnable().prefHeight(-1);
    }
}
