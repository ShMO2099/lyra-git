package com.jfx.git.ui;

import java.util.function.Supplier;

/**
 * Where the toolbar, sidebar and detail panel open a {@link RepoView}: their repository's
 * tab. {@link RepoTab} hands each of them {@link RepoTab#openView} as one of these.
 *
 * <p>A hook rather than the tab itself, because opening a view is all they need of it -
 * passing the whole tab down would invite every panel to reach into its siblings.
 */
@FunctionalInterface
public interface RepoViewHost {

    /**
     * Shows the view {@code create} makes - unless a view of {@code kind} is already open
     * in the tab, which then comes to the front instead. Opening the reflog twice should
     * not stack two reflogs, nor start a second read of it.
     */
    void open(Class<? extends RepoView> kind, Supplier<? extends RepoView> create);

    /** For a panel used outside a repository tab: says so, rather than silently doing nothing. */
    RepoViewHost NONE = (kind, create) -> {
        throw new IllegalStateException(kind.getSimpleName() + " needs a repository tab to open in");
    };
}
