package com.jfx.git.ui;

import javafx.scene.control.Tab;

/**
 * A tab that is part of the application rather than a repository: Settings, the activity
 * log. At most one of each kind is open - asking again selects it (see
 * {@code LyraApp.showToolTab}) - and none is remembered in the session; a restart brings
 * back the repositories, not the settings page that happened to be open.
 *
 * <p>Code that walks the tab strip treats these like the launchpad tab: not a repository,
 * so the active repository is none while one is in front, and repository shortcuts say so.
 */
public abstract class ToolTab extends Tab {

    protected ToolTab(String title) {
        super(title);
        setClosable(true);
    }

    /** Called when the tab is closed; release listeners and cancel work here. */
    public void dispose() {
    }
}
