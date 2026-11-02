package com.jfx.git.graph;

import com.jfx.git.core.AppPreferences;

import java.util.EnumSet;
import java.util.Set;

/**
 * The commit graph's columns, and which of them are drawn.
 *
 * <h2>All six can be turned off</h2>
 * A commercial client's header menu lists <em>every</em> column — Branch/Tag, Graph, Commit
 * message, Author, Date/Time and SHA — and unticking one removes it outright rather than
 * blanking it. Leaving a repository showing only Branch/Tag and Graph is a legitimate
 * thing to want, so nothing here is privileged.
 *
 * <h2>Two names per column</h2>
 * {@link #label()} is the commercial clients' wording, used in the menu, because someone
 * coming from one already knows what they are looking for. {@link #headerTitle()}
 * is the short form written above the column itself, where "Branch / Tag" does not fit a
 * rail that is sixty-four pixels wide and "Date / Time" does not fit a column reading
 * "19h". The two are kept together here rather than at the two call sites so they cannot
 * come to describe different columns.
 *
 * <h2>The Author column is not simply a preference</h2>
 * {@link GraphNodeStyle#AUTHOR} already retires the author column by putting the avatar
 * on the node. So the column is shown only when the style needs it <em>and</em> the user
 * has not hidden it — see {@link #visibleFor}. Without that, turning the avatar on would
 * leave an empty column headed "Author".
 */
public enum GraphColumn {

    /** Branch and tag chips. Ours is the rail; commercial clients call it Branch/Tag. */
    REFS("Branch / Tag", "Refs"),

    /** The lanes themselves. */
    GRAPH("Graph", ""),

    /** The commit subject. */
    MESSAGE("Commit message", "Commit"),

    /** Who wrote the commit. Absent by construction under the avatar node style. */
    AUTHOR("Author", "Author"),

    /** How long ago it landed. Ours reads as relative time, hence the short title. */
    AGE("Date / Time", "Age"),

    /** The abbreviated commit hash. */
    HASH("SHA", "SHA");

    private final String label;
    private final String headerTitle;

    GraphColumn(String label, String headerTitle) {
        this.label = label;
        this.headerTitle = headerTitle;
    }

    /** The commercial clients' name for this column, used in the menu. */
    public String label() {
        return label;
    }

    /** The short form written above the column; blank when it is left unlabelled. */
    public String headerTitle() {
        return headerTitle;
    }

    @Override
    public String toString() {
        return label;
    }

    /** Whether the user has this column switched on. All six start on. */
    public boolean visible() {
        return AppPreferences.graphColumnVisible(this);
    }

    public void setVisible(boolean visible) {
        AppPreferences.setGraphColumnVisible(this, visible);
    }

    /** Puts every column back in place and in declaration order: the menu's "reset". */
    public static void resetToDefault() {
        for (GraphColumn column : values()) {
            column.setVisible(true);
        }
        AppPreferences.setGraphColumnOrder(java.util.List.of(values()));
    }

    /**
     * All six columns in the user's stored order.
     *
     * <p>Order is orthogonal to visibility: hidden columns keep their place in the
     * grid (at zero width), so reordering never changes which columns exist, only
     * where each sits between its neighbours.
     */
    public static java.util.List<GraphColumn> ordered() {
        return AppPreferences.graphColumnOrder();
    }

    /**
     * Moves {@code moved} so it sits at {@code target}'s position, persisting the new
     * order and returning it.
     *
     * <p>Saving is the point, not a detail: the drag handler stays two lines precisely
     * because it can call this and ignore the result. It is emphatically <em>not</em>
     * pure - which is what this said before - so it cannot be used to preview an order
     * without committing to it. Testable without a scene all the same, since the rule
     * itself is just list arithmetic.
     */
    public static java.util.List<GraphColumn> reordered(GraphColumn moved, GraphColumn target) {
        if (moved == null || target == null || moved == target) {
            return ordered();
        }
        java.util.List<GraphColumn> current = new java.util.ArrayList<>(ordered());
        current.remove(moved);
        current.add(current.indexOf(target), moved);
        AppPreferences.setGraphColumnOrder(current);
        return java.util.List.copyOf(current);
    }

    /**
     * The columns actually drawn under {@code style}.
     *
     * <p>The single place the node-style rule is applied, so the header, the row and the
     * menu cannot disagree about whether there is an author column today.
     */
    public static Set<GraphColumn> visibleFor(GraphNodeStyle style) {
        Set<GraphColumn> shown = EnumSet.noneOf(GraphColumn.class);
        for (GraphColumn column : values()) {
            if (column == AUTHOR && !style.needsAuthorColumn()) {
                continue;
            }
            if (column.visible()) {
                shown.add(column);
            }
        }
        return shown;
    }
}
