package com.jfx.git.ui;

import javafx.scene.Node;

import java.util.List;

/**
 * A full screen shown inside a repository's tab in place of its graph - pull requests,
 * issues, a review, the reflog, LFS, housekeeping - with a "Back" bar above it.
 *
 * <h2>Why inside the tab rather than a window or a tab of its own</h2>
 * These were dialogs: each one another window on the taskbar, opening over the repository
 * it was about and modal, so nothing else in it could be touched while it was open. As a
 * tab of their own they would leave the repository's tab unselected - and the active
 * repository is what the shortcuts, the command palette and the refresh-on-focus all go
 * by. Shown in the repository's own tab they keep its toolbar, sidebar and status bar, and
 * the repository stays the one everything else acts on. Commercial clients show their pull requests
 * the same way.
 *
 * <p>Views stack: a review opened from the pull-request list comes back to the list. See
 * {@link RepoTab#showView} and {@link RepoTab#closeView}.
 */
public interface RepoView {

    /** What the header bar says this is, e.g. "Pull requests". */
    String title();

    /** The screen itself; created once and kept while the view is open. */
    Node content();

    /** Controls for the right of the header bar - a Refresh, a "New pull request". */
    default List<Node> actions() {
        return List.of();
    }

    /**
     * F5 while the view is in front: load what it shows again. Returns whether it did -
     * a view with nothing of its own to reload leaves F5 to refresh the repository.
     */
    default boolean refreshView() {
        return false;
    }

    /**
     * What takes the focus when the view comes to the front - a filter or a query field,
     * so the view can be typed into at once - or {@code null} for the view as a whole.
     */
    default Node focusTarget() {
        return null;
    }

    /**
     * Called once when the view leaves the tab for good - Back, a view below it closed,
     * or the tab itself closed. Cancel outstanding work here, as a dialog did when hidden.
     */
    default void closed() {
    }

    /** A view of {@code content} titled {@code title}, closing with {@code onClosed}. */
    static RepoView of(String title, Node content, Runnable onClosed) {
        return new RepoView() {
            @Override
            public String title() {
                return title;
            }

            @Override
            public Node content() {
                return content;
            }

            @Override
            public void closed() {
                if (onClosed != null) onClosed.run();
            }
        };
    }
}
