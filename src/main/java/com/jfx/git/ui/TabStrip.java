package com.jfx.git.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.List;

/**
 * The tab strip's own rules, separated from the window that owns it.
 *
 * <h2>Why this is not just a {@link TabPane}</h2>
 * The strip carries one thing that is not a document: the trailing <em>＋</em>, which is a
 * button wearing a tab's clothes. Every rule here exists because of it — it must not be
 * dragged into the middle, must not be a destination when cycling, and must not count
 * when tabs are numbered. Left in the application class those rules sit in three separate
 * listeners and can only be exercised by opening the window; here they can be tested
 * against a real {@code TabPane} without one.
 */
public final class TabStrip {

    private final TabPane pane;
    private final Tab newTabButton;

    /** Guards the re-entrant edit {@link #pinNewTabButtonLast()} makes to the same list. */
    private boolean pinning;
    /** True while a correction is already queued, so a burst of changes queues one. */
    private boolean pinQueued;
    private Runnable onChanged;

    /**
     * @param pane         the strip to govern
     * @param newTabButton the pinned trailing tab, which is kept last and never selected
     */
    public TabStrip(TabPane pane, Tab newTabButton) {
        this.pane = pane;
        this.newTabButton = newTabButton;
    }

    /**
     * Turns on dragging and starts enforcing the strip's rules.
     *
     * <p>{@code onChanged} is notified after every change the strip settles into, which is
     * where the caller records the session — after the pin, so it never sees the momentary
     * order a drag leaves behind.
     */
    public void install(Runnable onChanged) {
        this.onChanged = onChanged;
        pane.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        pane.getTabs().addListener((ListChangeListener<Tab>) change -> {
            if (pinning) {
                return;
            }
            if (needsPinning()) {
                queuePin();
            } else {
                notifyChanged();
            }
        });
    }

    /**
     * Queues the correction for after the current change notification.
     *
     * <p>Correcting the list from inside its own listener does not work: JavaFX is part
     * way through delivering a change against that list, and the re-added tab is dropped
     * — the ＋ disappears from the strip altogether rather than moving to the end. It has
     * to happen once the notification has finished, which is what {@code runLater} buys.
     *
     * <p>Queued at most once, so a drag that reports several changes still corrects once
     * and records the session once.
     */
    private void queuePin() {
        if (pinQueued) {
            return;
        }
        pinQueued = true;
        javafx.application.Platform.runLater(() -> {
            pinQueued = false;
            pinNewTabButtonLast();
            notifyChanged();
        });
    }

    private void notifyChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /** Whether the ＋ is present but no longer last. */
    private boolean needsPinning() {
        List<Tab> tabs = pane.getTabs();
        int last = tabs.size() - 1;
        return last >= 0 && tabs.get(last) != newTabButton && tabs.contains(newTabButton);
    }

    /**
     * Moves the ＋ back to the end if a drag displaced it.
     *
     * <p>The selection is restored afterwards because removing a tab moves it, and a drag
     * that also changed which repository is in front would be a surprise.
     *
     * @return true when the strip had to be corrected
     */
    public boolean pinNewTabButtonLast() {
        if (!needsPinning()) {
            return false;
        }
        List<Tab> tabs = pane.getTabs();
        pinning = true;
        try {
            Tab selected = pane.getSelectionModel().getSelectedItem();
            tabs.remove(newTabButton);
            tabs.add(newTabButton);
            if (selected != null && selected != newTabButton) {
                pane.getSelectionModel().select(selected);
            }
            return true;
        } finally {
            pinning = false;
        }
    }

    /** The tabs that are documents, in strip order — everything but the ＋. */
    public List<Tab> documentTabs() {
        return pane.getTabs().stream().filter(tab -> tab != newTabButton).toList();
    }

    /**
     * Moves the selection {@code step} tabs along, wrapping at both ends.
     *
     * <p>Cycling onto the ＋ would spawn a launchpad page rather than show one, so it is
     * never a destination.
     */
    public void cycle(int step) {
        List<Tab> tabs = documentTabs();
        if (tabs.size() < 2) {
            return;
        }
        int current = tabs.indexOf(pane.getSelectionModel().getSelectedItem());
        if (current < 0) {
            current = 0;
        }
        pane.getSelectionModel().select(tabs.get(Math.floorMod(current + step, tabs.size())));
    }

    /**
     * Selects the {@code index}-th repository tab.
     *
     * <p>Counts only repository tabs, and treats index 8 — {@code Ctrl+9} — as "the last
     * one", which is what browsers do and what makes the key useful with fewer than nine
     * tabs open. Anything else is clamped to the end rather than ignored.
     */
    public void selectRepoTab(int index) {
        List<Tab> repoTabs = pane.getTabs().stream()
                .filter(tab -> tab instanceof RepoTab)
                .toList();
        if (repoTabs.isEmpty()) {
            return;
        }
        int target = index == 8 ? repoTabs.size() - 1 : Math.min(index, repoTabs.size() - 1);
        pane.getSelectionModel().select(repoTabs.get(target));
    }
}
