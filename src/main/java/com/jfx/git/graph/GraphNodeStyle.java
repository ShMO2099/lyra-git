package com.jfx.git.graph;

import com.jfx.git.core.AppPreferences;

/**
 * What a commit is drawn as where its lane meets its row.
 *
 * <p>The two are not the same picture at different sizes; they buy different things.
 *
 * <p>{@link #DOT} is compact. Lanes can sit eleven pixels apart, so a deeply branched
 * history fits across a narrow window, and the graph column stays a thin ribbon beside the
 * messages. Who wrote a commit is answered by a column of its own.
 *
 * <p>{@link #AUTHOR} puts the author's avatar at the node, ringed in the lane's colour.
 * It says two things in one position, and it retires the author column entirely — but it
 * needs the lanes far enough apart not to overlap, so a graph of the same depth takes
 * about half as much again in width.
 */
public enum GraphNodeStyle {

    /** A filled circle, hollow for a merge. The author gets a column. */
    DOT("Dot"),

    /** The author's avatar, ringed in the lane colour. No author column. */
    AUTHOR("Author avatar");

    private final String label;

    GraphNodeStyle(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True when the row needs a separate column to say who wrote the commit. */
    public boolean needsAuthorColumn() {
        return this == DOT;
    }

    @Override
    public String toString() {
        return label;
    }

    /** The style the user has chosen. */
    public static GraphNodeStyle current() {
        return AppPreferences.graphNodeStyle();
    }
}
