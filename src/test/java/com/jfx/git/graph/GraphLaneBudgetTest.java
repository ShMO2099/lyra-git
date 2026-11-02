package com.jfx.git.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How wide the graph is allowed to grow, and who decides.
 *
 * <p>By default the window does: a budget derived from the width the list has. Dragging
 * the graph's edge overrides that, and the override has to survive a window resize or the
 * drag would be undone by the next layout pass.
 */
class GraphLaneBudgetTest {

    private static GraphMetrics metrics() {
        return new GraphMetrics(GraphDensity.COMPACT, GraphNodeStyle.DOT);
    }

    @Test
    void theBudgetFollowsTheWindowUntilSomethingIsDragged() {
        GraphMetrics m = metrics();
        m.setAvailableWidth(1200);
        int wide = m.maxLanes();

        m.setAvailableWidth(400);

        assertTrue(m.maxLanes() < wide, "a narrower window should draw fewer lanes");
        assertEquals(0, m.laneBudget(), "nothing has been dragged, so there is no override");
    }

    @Test
    void draggingFixesTheBudget() {
        GraphMetrics m = metrics();
        m.setAvailableWidth(1200);

        assertTrue(m.setLaneBudget(6));

        assertEquals(6, m.maxLanes());
        assertEquals(6, m.laneBudget());
    }

    /**
     * The property that makes the handle worth having. Without it, the first window
     * resize would silently undo the drag.
     */
    @Test
    void aWindowResizeDoesNotOverruleADraggedWidth() {
        GraphMetrics m = metrics();
        m.setAvailableWidth(1200);
        m.setLaneBudget(6);

        assertFalse(m.setAvailableWidth(400), "nothing should have changed");
        assertEquals(6, m.maxLanes(), "the dragged width has to survive a resize");
    }

    @Test
    void doubleClickingHandsTheBudgetBackToTheWindow() {
        GraphMetrics m = metrics();
        m.setAvailableWidth(1200);
        int automatic = m.maxLanes();
        m.setLaneBudget(6);

        assertTrue(m.clearLaneBudget());

        assertEquals(0, m.laneBudget());
        assertEquals(automatic, m.maxLanes(), "back to what the window would have chosen");
    }

    @Test
    void clearingRecomputesForTheWindowAsItIsNowRatherThanAsItWas() {
        GraphMetrics m = metrics();
        m.setAvailableWidth(1200);
        m.setLaneBudget(6);
        // The window shrank while the override was in force, so the remembered figure is
        // stale; clearing must use the current width, not the one the override replaced.
        m.setAvailableWidth(400);

        m.clearLaneBudget();

        assertEquals(GraphDensity.COMPACT.maxDrawnLanes(400, GraphNodeStyle.DOT), m.maxLanes());
    }

    @Test
    void theBudgetIsClampedToSomethingDrawable() {
        GraphMetrics m = metrics();

        m.setLaneBudget(-5);
        assertEquals(GraphDensity.MIN_DRAWN_LANES, m.maxLanes(),
                "a graph with no lanes is not a graph");

        m.setLaneBudget(10_000);
        assertTrue(m.maxLanes() <= 40,
                "past a point the graph has stopped being followable whatever is dragged");
    }

    @Test
    void clearingWhenNothingWasDraggedChangesNothing() {
        GraphMetrics m = metrics();
        m.setAvailableWidth(1200);

        assertFalse(m.clearLaneBudget(), "no override, so no repaint should be asked for");
    }

    /**
     * The budget is a ceiling, not a width. A linear history has one lane, so widening the
     * budget cannot widen the column — there is nothing there to show.
     */
    @Test
    void theColumnIsNeverWiderThanThePageNeeds() {
        GraphMetrics m = metrics();
        m.setAvailableWidth(1200);
        m.update(java.util.List.of(new GitGraphRow(
                new com.jfx.git.model.CommitInfo("a", "only", "T", "t@x",
                        java.time.Instant.now(), java.util.List.of(), java.util.List.of()))),
                "main");

        double atFour = m.laneWidthProperty().get();
        m.setLaneBudget(20);

        assertEquals(atFour, m.laneWidthProperty().get(), 0.5,
                "one lane of history stays one lane wide however far the handle is dragged");
    }
}
