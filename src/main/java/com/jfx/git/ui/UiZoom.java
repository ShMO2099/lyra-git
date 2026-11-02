package com.jfx.git.ui;

import com.jfx.git.core.Settings;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;

import java.util.List;

/**
 * How large the whole interface is drawn.
 *
 * <h2>Why a transform rather than a font size</h2>
 * The obvious implementation is to set {@code -fx-font-size} on the root and let every
 * control follow it. That works right up to the commit graph, which is painted onto a
 * {@link javafx.scene.canvas.Canvas} from pixel measurements in {@code GraphDensity}: the
 * text beside a lane would grow while the lane stayed put, and the two would part company
 * a little more at every step. A scale transform moves everything together, which is the
 * only version of "zoom" that leaves the graph looking like itself.
 *
 * <p>The cost is that the canvas is a raster and is resampled rather than redrawn, so
 * lanes are slightly soft at fractional levels. Whole multiples are exact. Redrawing the
 * canvas at the zoomed resolution would sharpen it and is a larger change than this.
 *
 * <h2>One zoom, not one per tab</h2>
 * Zoom belongs to the window rather than to a repository: nobody wants their second tab
 * at a different size from their first. It is therefore static state, persisted, with the
 * status bar of every tab reading the same value.
 */
public final class UiZoom {

    /** Selectable levels, as percentages. */
    public static final List<Integer> LEVELS =
            List.of(50, 67, 75, 90, 100, 110, 125, 150, 175, 200);

    public static final int DEFAULT = 100;

    private static final String KEY = "ui.zoom";

    private static final ReadOnlyIntegerWrapper percent = new ReadOnlyIntegerWrapper(load());

    private UiZoom() {
    }

    private static int load() {
        try {
            return clamp(Integer.parseInt(Settings.get(KEY, String.valueOf(DEFAULT))));
        } catch (NumberFormatException e) {
            // A value written by hand or by a newer version is not worth failing over.
            return DEFAULT;
        }
    }

    /** The current level, as a percentage. */
    public static int percent() {
        return percent.get();
    }

    /** Changes with the level, so a status bar can show it without polling. */
    public static ReadOnlyIntegerProperty percentProperty() {
        return percent.getReadOnlyProperty();
    }

    /** The level as a multiplier, which is what a transform wants. */
    public static double factor() {
        return percent.get() / 100.0;
    }

    /**
     * Sets the level, clamped to the offered range and remembered.
     *
     * <p>Any percentage is accepted rather than only the listed ones: the list is what the
     * menu offers, not a rule about what is representable, and clamping a stored value
     * from another version to the nearest legal size is friendlier than refusing it.
     */
    public static void set(int newPercent) {
        int clamped = clamp(newPercent);
        if (clamped != percent.get()) {
            percent.set(clamped);
            Settings.put(KEY, String.valueOf(clamped));
        }
    }

    /** The next level up, or the largest when already there. */
    public static void zoomIn() {
        set(step(1));
    }

    /** The next level down, or the smallest when already there. */
    public static void zoomOut() {
        set(step(-1));
    }

    public static void reset() {
        set(DEFAULT);
    }

    /**
     * The level {@code direction} steps away from the current one.
     *
     * <p>Walks the list rather than multiplying, so zooming in and then out returns to
     * exactly where it started instead of drifting through 100 → 125 → 93.
     */
    private static int step(int direction) {
        int current = percent.get();
        if (direction > 0) {
            for (int level : LEVELS) {
                if (level > current) {
                    return level;
                }
            }
            return LEVELS.get(LEVELS.size() - 1);
        }
        for (int i = LEVELS.size() - 1; i >= 0; i--) {
            if (LEVELS.get(i) < current) {
                return LEVELS.get(i);
            }
        }
        return LEVELS.get(0);
    }

    private static int clamp(int value) {
        return Math.max(LEVELS.get(0), Math.min(value, LEVELS.get(LEVELS.size() - 1)));
    }

    /**
     * Scales {@code content} inside {@code container}, and keeps it filling it.
     *
     * <p>Both halves are needed. A scale transform changes how a node is drawn but not the
     * size it was laid out at, so scaling alone leaves the interface rendered large inside
     * its original, now-too-small rectangle — with a strip of empty window beside it at
     * levels above 100% and a cropped one below. Dividing the content's size by the factor
     * gives it exactly the room that, once scaled, covers the container.
     */
    public static void install(Pane container, Region content) {
        Scale scale = new Scale();
        content.getTransforms().add(scale);

        // The size is driven through prefWidth/prefHeight, not resize(). A Pane autosizes
        // its children to their preferred size on every layout pass, so a direct resize is
        // undone by the next one — which showed up as the interface staying the size of
        // its own content, about fifty pixels wide, however large the window was.
        //
        // The minimum is released for the same reason: zoomed in, the content is asked to
        // be smaller than the window, and a toolbar's minimum width would otherwise win
        // and push the layout back out again.
        content.setMinSize(0, 0);

        Runnable apply = () -> {
            double factor = factor();
            scale.setX(factor);
            scale.setY(factor);
            content.setPrefWidth(container.getWidth() / factor);
            content.setPrefHeight(container.getHeight() / factor);
            container.requestLayout();
        };

        // Scaled content is drawn outside its layout bounds, so without a clip a zoomed-in
        // interface paints over whatever sits beyond the window's edge.
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(container.widthProperty());
        clip.heightProperty().bind(container.heightProperty());
        container.setClip(clip);

        // Weakly, held by the container: the level is static and outlives every window it
        // scales, and a strong listener kept each one - in a test JVM, every application
        // ever started, with its whole interface - for the life of the process.
        javafx.beans.value.ChangeListener<Number> onLevel = (obs, old, value) -> apply.run();
        container.getProperties().put(UiZoom.class, onLevel);
        percent.addListener(new javafx.beans.value.WeakChangeListener<>(onLevel));
        container.widthProperty().addListener((obs, old, value) -> apply.run());
        container.heightProperty().addListener((obs, old, value) -> apply.run());
        apply.run();
    }

    /** Forgets the stored level; for tests, which must not inherit the developer's. */
    public static void resetForTests() {
        percent.set(DEFAULT);
        Settings.remove(KEY);
    }
}
