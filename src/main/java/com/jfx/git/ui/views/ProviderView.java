package com.jfx.git.ui.views;

import com.jfx.git.ui.RepoView;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import java.util.List;

/**
 * What the pull request, merge request and issue screens share as {@link RepoView}s: a
 * title, a Refresh in the header bar, and a {@link ProviderListing} that stops its work
 * when the view leaves the tab.
 *
 * <h2>Why a Refresh</h2>
 * As dialogs these were reloaded by closing and opening them again. A view stays in the
 * tab while the user checks a request out, pushes and comes back to it, so the header
 * carries the reload that reopening used to be.
 *
 * <h2>Why {@link #isOpen}</h2>
 * The toolbar keeps the view it opened and brings that one back when its button is
 * pressed again, rather than stacking a second copy with a second set of requests - but
 * only while the first is still in the tab. Once Back, a closed tab or anything else has
 * closed it, its listing has stopped for good and a new view has to be made.
 *
 * <p>Package-private constructor: the screens that extend this live beside
 * {@link ProviderListing}, which is package-private too.
 */
public abstract class ProviderView implements RepoView {

    private final String title;
    private final Button refresh = new Button("Refresh");

    ProviderView(String title) {
        this.title = title;
        refresh.setTooltip(new Tooltip("Load the list again from the server"));
        refresh.setOnAction(e -> listing().reload());
    }

    /** The list this view shows. */
    abstract ProviderListing<?> listing();

    @Override
    public String title() {
        return title;
    }

    @Override
    public List<Node> actions() {
        return List.of(refresh);
    }

    /**
     * Opens this view's new-request form filled in from {@code draft}. Only the pull and
     * merge request views have such a form; the rest ignore it.
     */
    public void prefill(com.jfx.git.ui.dialogs.CreatePullRequestDialog.Draft draft) {
    }

    /** F5: loads the list again, as Refresh does. */
    @Override
    public boolean refreshView() {
        listing().reload();
        return true;
    }

    /** The filter, so a view opens ready to be typed into. */
    @Override
    public Node focusTarget() {
        return listing().filterField();
    }

    /** Stops the listing: queued requests never start, running ones stop at their next request. */
    @Override
    public void closed() {
        listing().close();
    }

    /** Whether the view is still in its tab - not yet closed by Back, the tab, or anything else. */
    public boolean isOpen() {
        return !listing().isClosed();
    }
}
