package com.jfx.git.graph;

import com.jfx.git.core.AppPreferences;

/**
 * The order commits are listed in, which is what decides the shape of the graph.
 *
 * <p>Both are topological — a parent never appears above its child, which the renderer
 * requires. They differ in what happens when several commits are eligible at once, and
 * that single choice is the whole difference between the two pictures.
 *
 * <p>{@link #BY_BRANCH} follows one line of history to its end before starting another,
 * so a branch's commits sit together in a contiguous run and few lanes are open at any
 * row. It answers "what happened on this branch".
 *
 * <p>{@link #CHRONOLOGICAL} always takes the newest eligible commit, so branches
 * interleave by date exactly as they were written. It answers "what happened across the
 * project this morning" — and it is what commercial clients draw. The cost is lanes: on a
 * repository with four hundred remote branches this holds dozens open at once, which is
 * only readable if the graph column has the width for them.
 */
public enum GraphOrder {

    BY_BRANCH("Group by branch"),
    CHRONOLOGICAL("Chronological");

    private final String label;

    GraphOrder(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether one line of history is followed to its end before another begins. */
    public boolean followsFirstParent() {
        return this == BY_BRANCH;
    }

    @Override
    public String toString() {
        return label;
    }

    public static GraphOrder current() {
        return AppPreferences.graphOrder();
    }
}
