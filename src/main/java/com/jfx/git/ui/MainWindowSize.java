package com.jfx.git.ui;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;
import java.util.function.Supplier;

/**
 * How small the main window may be made, and the size it opens at.
 *
 * <h2>Why</h2>
 * The window had no minimum at all. Dragged narrow it kept a 180-pixel slice of the menu
 * bar and the sidebar's headings, with the toolbar, the graph and the details cut away,
 * and nothing about it said the rest was still there. Short of that, the panes pushed each
 * other about: the sidebar slid off the left edge and the status bar off the bottom.
 *
 * <h2>Where the number comes from</h2>
 * Not from here. The floor is the repository layout's - {@link RepoTab#minimumSize()}, the
 * sum of the sidebar's, the graph's and the details panel's own floors, and the bars above
 * and below them - so a pane that needs more room raises the window's minimum with it.
 * This adds what the layout cannot know: the zoom it is drawn at (the whole interface is
 * scaled, see {@link UiZoom}), the window's own frame, and the screen it is on. A minimum
 * larger than the screen would leave the window's edges out of reach, so the screen wins.
 *
 * <h2>The opening size</h2>
 * The window opens at the size it asks for, raised to the minimum and held to the screen.
 * Nothing restores a remembered size yet; when something does, it goes through
 * {@link #opening} too, so one saved below the minimum comes back at the minimum.
 */
public final class MainWindowSize {

    /** A width and a height. */
    public record Size(double width, double height) {
    }

    private MainWindowSize() {
    }

    /**
     * The smallest the window may be: {@code floor} drawn at {@code zoom}, plus the
     * window's {@code frame}, in whole pixels, and no larger than {@code screen}.
     *
     * @param floor  the layout's floor, in layout pixels before the zoom
     * @param frame  the window's border and title bar, which the zoom does not scale
     * @param screen the visual bounds of the screen the window is on
     */
    public static Size minimum(Size floor, double zoom, Size frame, Rectangle2D screen) {
        double width = Math.ceil(floor.width() * zoom + frame.width());
        double height = Math.ceil(floor.height() * zoom + frame.height());
        return new Size(Math.min(width, screen.getWidth()), Math.min(height, screen.getHeight()));
    }

    /** The size to open at: {@code requested}, raised to {@code minimum} and held to {@code screen}. */
    public static Size opening(Size requested, Size minimum, Rectangle2D screen) {
        double width = Math.min(Math.max(requested.width(), minimum.width()), screen.getWidth());
        double height = Math.min(Math.max(requested.height(), minimum.height()), screen.getHeight());
        return new Size(width, height);
    }

    /**
     * Gives {@code stage} its minimum and keeps it current: when the zoom changes, when the
     * window first shows (which is when its frame can be measured) and when it moves to
     * another screen. Call before the stage first shows, so it opens at a size that fits.
     *
     * @param floor the layout's floor in layout pixels; asked again whenever any of that changes
     */
    public static void install(Stage stage, Supplier<Size> floor) {
        Fitter fitter = new Fitter(stage, floor);
        // The zoom is static and outlives the window; the listener is held by the stage and
        // registered weakly, as UiZoom's own is, so a test's stage does not stay reachable.
        stage.getProperties().put(MainWindowSize.class, fitter);
        UiZoom.percentProperty().addListener(new WeakChangeListener<>(fitter.onChange));
        stage.xProperty().addListener(fitter.onChange);
        stage.yProperty().addListener(fitter.onChange);
        stage.showingProperty().addListener((obs, was, showing) -> {
            if (showing) {
                fitter.fit();
                fitter.keepOnScreen();
            }
        });
        fitter.fit();
        fitter.openWithin();
    }

    /** The minimum for one stage, and what it was last worked out from. */
    private static final class Fitter {
        private final Stage stage;
        private final Supplier<Size> floor;
        private final ChangeListener<Object> onChange = (obs, was, now) -> fit();
        private Rectangle2D lastScreen;
        private double lastZoom;
        private Size lastFrame;

        Fitter(Stage stage, Supplier<Size> floor) {
            this.stage = stage;
            this.floor = floor;
        }

        /**
         * Sets the minimum, if anything it depends on changed. The window moves a pixel at a
         * time while it is dragged, and the floor is asked for only when the screen under it
         * changes. A window below a raised minimum is grown to it by the stage itself.
         */
        void fit() {
            Rectangle2D screen = screen();
            double zoom = UiZoom.factor();
            Size frame = frame();
            if (screen.equals(lastScreen) && zoom == lastZoom && frame.equals(lastFrame)) {
                return;
            }
            lastScreen = screen;
            lastZoom = zoom;
            lastFrame = frame;
            Size minimum = minimum(floor.get(), zoom, frame, screen);
            stage.setMinWidth(minimum.width());
            stage.setMinHeight(minimum.height());
        }

        /** Before the first show: the scene's size, raised to the minimum and held to the screen. */
        void openWithin() {
            if (stage.isShowing() || stage.getScene() == null) {
                return;
            }
            Size requested = new Size(stage.getScene().getWidth(), stage.getScene().getHeight());
            Size opening = opening(requested, new Size(stage.getMinWidth(), stage.getMinHeight()), screen());
            // Left alone when it already fits, so the window opens exactly as it always has.
            if (!opening.equals(requested)) {
                stage.setWidth(opening.width());
                stage.setHeight(opening.height());
            }
        }

        /**
         * Once shown, with the frame counted: a window opened taller or wider than the
         * screen is brought inside it, so the status bar and the title bar can be reached.
         */
        void keepOnScreen() {
            Rectangle2D screen = screen();
            if (stage.getWidth() > screen.getWidth()) {
                stage.setWidth(screen.getWidth());
            }
            if (stage.getHeight() > screen.getHeight()) {
                stage.setHeight(screen.getHeight());
            }
            if (stage.getX() < screen.getMinX() || stage.getX() + stage.getWidth() > screen.getMaxX()) {
                stage.setX(Math.max(screen.getMinX(), screen.getMaxX() - stage.getWidth()));
            }
            if (stage.getY() < screen.getMinY() || stage.getY() + stage.getHeight() > screen.getMaxY()) {
                stage.setY(Math.max(screen.getMinY(), screen.getMaxY() - stage.getHeight()));
            }
        }

        /** The border and title bar, once there is a window to measure; nothing before. */
        private Size frame() {
            if (!stage.isShowing() || stage.getScene() == null
                    || Double.isNaN(stage.getWidth()) || Double.isNaN(stage.getHeight())) {
                return new Size(0, 0);
            }
            return new Size(Math.max(0, stage.getWidth() - stage.getScene().getWidth()),
                    Math.max(0, stage.getHeight() - stage.getScene().getHeight()));
        }

        /** The visual bounds of the screen most of the window is on, or the primary one. */
        private Rectangle2D screen() {
            // Before its first show a stage has no position or size at all (NaN).
            List<Screen> screens = Double.isNaN(stage.getX()) || Double.isNaN(stage.getY())
                    || Double.isNaN(stage.getWidth()) || Double.isNaN(stage.getHeight())
                    ? List.of()
                    : Screen.getScreensForRectangle(stage.getX(), stage.getY(),
                            Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()));
            return (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
        }
    }
}
