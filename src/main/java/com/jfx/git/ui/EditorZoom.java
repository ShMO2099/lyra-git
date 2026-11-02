package com.jfx.git.ui;

import com.jfx.git.core.Settings;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;

import java.util.List;

/**
 * How large the built-in editor's own text is drawn, independent of {@link UiZoom}.
 *
 * <p>A separate mechanism on purpose: {@link UiZoom} scales the whole interface as one
 * transform, which is right for the window but wrong for "zoom the code I'm reading" -
 * every toolbar and button growing along with the text is not what Ctrl+scroll over an
 * editor means in the tools this is modelled on (VS Code, IntelliJ). This only ever
 * changes {@code DiffTextArea}'s own font size.
 *
 * <p>Static and persisted, like {@link UiZoom}, for the same reason: nobody wants their
 * second tab's editor a different size from their first.
 */
public final class EditorZoom {

    /** Selectable levels, as percentages. */
    public static final List<Integer> LEVELS =
            List.of(50, 67, 75, 90, 100, 110, 125, 150, 175, 200);

    public static final int DEFAULT = 100;

    private static final String KEY = "editor.zoom";

    private static final ReadOnlyIntegerWrapper percent = new ReadOnlyIntegerWrapper(load());

    private EditorZoom() {
    }

    private static int load() {
        try {
            return clamp(Integer.parseInt(Settings.get(KEY, String.valueOf(DEFAULT))));
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }

    /** The current level, as a percentage. */
    public static int percent() {
        return percent.get();
    }

    /** Changes with the level, so every open editor can restyle without polling. */
    public static ReadOnlyIntegerProperty percentProperty() {
        return percent.getReadOnlyProperty();
    }

    /** The level as a multiplier, which is what a font size wants. */
    public static double factor() {
        return percent.get() / 100.0;
    }

    /**
     * Sets the level, clamped to the offered range and remembered.
     *
     * <p>Any percentage is accepted rather than only the listed ones, for the same
     * reason as {@link UiZoom#set}: clamping a stored value from another version to the
     * nearest legal size is friendlier than refusing it.
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
     * <p>Walks the list rather than multiplying, for the same reason as
     * {@link UiZoom#step}: zooming in and then out returns to exactly where it started.
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

    /** Forgets the stored level; for tests, which must not inherit the developer's. */
    public static void resetForTests() {
        percent.set(DEFAULT);
        Settings.remove(KEY);
    }
}
