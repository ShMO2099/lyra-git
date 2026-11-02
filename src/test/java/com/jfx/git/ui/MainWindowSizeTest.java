package com.jfx.git.ui;

import com.jfx.git.ui.MainWindowSize.Size;

import javafx.geometry.Rectangle2D;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules for how small the main window may get, apart from any window: the layout's
 * floor, scaled by the zoom, plus the window's frame, and never more than the screen.
 */
class MainWindowSizeTest {

    private static final Rectangle2D BIG_SCREEN = new Rectangle2D(0, 0, 2560, 1400);
    private static final Size NO_FRAME = new Size(0, 0);

    @Test
    void theMinimumIsTheLayoutFloorPlusTheWindowFrame() {
        Size minimum = MainWindowSize.minimum(new Size(900, 450), 1.0, new Size(16, 39), BIG_SCREEN);
        assertEquals(916, minimum.width());
        assertEquals(489, minimum.height());
    }

    @Test
    void theMinimumGrowsWithTheZoom() {
        // The zoom scales the whole interface, so at 125% every pane's floor is a quarter
        // wider on screen; the frame is the platform's and does not scale.
        Size minimum = MainWindowSize.minimum(new Size(900, 450), 1.25, new Size(16, 39), BIG_SCREEN);
        assertEquals(900 * 1.25 + 16, minimum.width());
        assertEquals(Math.ceil(450 * 1.25 + 39), minimum.height());
    }

    @Test
    void theMinimumNeverExceedsTheScreen() {
        // A small laptop screen at a large zoom: the floor no longer fits, and a minimum
        // larger than the screen would leave the window's edges unreachable.
        Rectangle2D laptop = new Rectangle2D(0, 0, 1366, 728);
        Size minimum = MainWindowSize.minimum(new Size(900, 450), 2.0, new Size(16, 39), laptop);
        assertEquals(1366, minimum.width());
        assertEquals(728, minimum.height());
    }

    @Test
    void theMinimumIsWholePixels() {
        Size minimum = MainWindowSize.minimum(new Size(901, 451), 1.1, NO_FRAME, BIG_SCREEN);
        assertEquals(Math.ceil(901 * 1.1), minimum.width());
        assertEquals(Math.ceil(451 * 1.1), minimum.height());
    }

    @Test
    void aSizeBelowTheMinimumOpensAtTheMinimum() {
        Size minimum = new Size(1000, 600);
        Size opening = MainWindowSize.opening(new Size(180, 1300), minimum, BIG_SCREEN);
        assertEquals(1000, opening.width(), "a width below the minimum is raised to it");
        assertEquals(1300, opening.height(), "a height above it is kept");
    }

    @Test
    void aSizeLargerThanTheScreenOpensAtTheScreen() {
        Rectangle2D laptop = new Rectangle2D(0, 0, 1366, 728);
        Size opening = MainWindowSize.opening(new Size(1280, 840), new Size(900, 500), laptop);
        assertEquals(1280, opening.width());
        assertEquals(728, opening.height(), "the window must not open taller than the screen it is on");
    }

    @Test
    void aSizeBetweenTheTwoIsKept() {
        Size opening = MainWindowSize.opening(new Size(1280, 840), new Size(900, 500), BIG_SCREEN);
        assertEquals(new Size(1280, 840), opening);
    }

    @Test
    void theRepositoryLayoutFloorHoldsItsThreePanesAtTheirOwnFloors() {
        // The window's floor is the sum of the panes' floors, not a number of its own: a
        // pane that raises its floor raises the window's with it.
        Size floor = RepoTab.minimumSize();
        assertTrue(floor.width() >= SidebarPane.MIN_WIDTH + RepoTab.graphFloor() + RepoTab.DETAIL_MIN_WIDTH,
                "the floor " + floor.width() + " must hold the sidebar, the graph and the details at their floors");
        assertTrue(RepoTab.graphFloor() > 200,
                "the graph's floor must leave room for its fixed columns and a commit subject");
    }
}
