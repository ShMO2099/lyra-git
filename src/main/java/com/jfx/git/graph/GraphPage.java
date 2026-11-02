package com.jfx.git.graph;

import com.jfx.git.model.RefSnapshot;

import java.util.List;

/**
 * One page of the commit graph, with the ref snapshot it was laid out from.
 *
 * <p>Lives here rather than in {@code com.jfx.git.model} because it is a list of
 * {@link GitGraphRow}: putting it in the model package would have model depending on
 * graph while graph already depends on model.
 *
 * <p>The snapshot rides on the page (ADR 0002): the read that decides where the walk
 * starts and what decorates each row is the repository's refs as one read saw them, and
 * every view the refresh feeds takes its refs from it rather than reading them again.
 *
 * @param hasMore history continues past the last row, so a caller can offer to walk
 *                further instead of letting the graph appear to end
 * @param refs    the refs the page was built from; {@link RefSnapshot#EMPTY} for a page
 *                built without a repository
 */
public record GraphPage(List<GitGraphRow> rows, boolean hasMore, RefSnapshot refs) {

    public GraphPage {
        refs = refs == null ? RefSnapshot.EMPTY : refs;
    }

    /** A page with no refs read: a test's, or one built without a repository. */
    public GraphPage(List<GitGraphRow> rows, boolean hasMore) {
        this(rows, hasMore, RefSnapshot.EMPTY);
    }
}
