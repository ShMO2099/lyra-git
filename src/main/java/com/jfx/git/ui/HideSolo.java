package com.jfx.git.ui;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.graph.GraphVisibility;
import com.jfx.git.model.RefNames;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Hide &amp; Solo, edited: the sidebar's eye, its Hide and Solo items, the section headers'
 * "Hide all" and "Show all", and keeping both straight when a remote or a branch is renamed.
 *
 * <p>What it edits is a {@link GraphVisibility} - the refs hidden from the graph and the one
 * it is soloed to, all by full ref name - and, apart from it, the stashes hidden from the
 * graph, by commit: a stash is no ref, and has no solo. Every edit is one read and one write
 * of what is stored, so two sidebars never undo each other's last change, and the store is
 * a {@link Store} so the rules can be checked without the settings file.
 *
 * <p>It does not refresh anything: the caller redraws once the edit is stored.
 */
final class HideSolo {

    /** Where Hide &amp; Solo is kept. */
    interface Store {
        /** The refs hidden from the graph, by full name, in the order they were hidden. */
        Set<String> hiddenRefs();

        void hiddenRefs(Set<String> refs);

        /** The ref the graph is soloed to, by full name, or null. */
        String soloRef();

        void soloRef(String ref);

        /** The stashes hidden from the graph, by commit hash. */
        Set<String> hiddenStashes();

        void hiddenStashes(Set<String> hashes);
    }

    /** The application's settings, where the graph's walk reads Hide &amp; Solo from. */
    static final Store PREFERENCES = new Store() {
        @Override
        public Set<String> hiddenRefs() {
            return AppPreferences.graphHiddenBranches();
        }

        @Override
        public void hiddenRefs(Set<String> refs) {
            AppPreferences.setGraphHiddenBranches(refs);
        }

        @Override
        public String soloRef() {
            return AppPreferences.graphSoloBranch();
        }

        @Override
        public void soloRef(String ref) {
            AppPreferences.setGraphSoloBranch(ref);
        }

        @Override
        public Set<String> hiddenStashes() {
            return AppPreferences.hiddenStashes();
        }

        @Override
        public void hiddenStashes(Set<String> hashes) {
            AppPreferences.setHiddenStashes(hashes);
        }
    };

    private final Store store;

    HideSolo(Store store) {
        this.store = store;
    }

    /** Hide &amp; Solo as stored now: what the graph's next walk starts from. */
    GraphVisibility visibility() {
        return new GraphVisibility(store.hiddenRefs(), store.soloRef());
    }

    /** The stashes hidden from the graph now, by commit hash. */
    Set<String> hiddenStashes() {
        return store.hiddenStashes();
    }

    /** Hides {@code fullName}, or shows it again if it is hidden. A solo elsewhere hides it anyway. */
    void toggleHidden(String fullName) {
        Set<String> hidden = new LinkedHashSet<>(store.hiddenRefs());
        if (!hidden.remove(fullName)) {
            hidden.add(fullName);
        }
        store.hiddenRefs(hidden);
    }

    /** Solos {@code fullName} - replacing any other solo - or clears the solo if it is the one soloed. */
    void toggleSolo(String fullName) {
        store.soloRef(fullName.equals(store.soloRef()) ? null : fullName);
    }

    /** A section's "Hide all": one write rather than one per ref. */
    void hideAll(Collection<String> fullNames) {
        Set<String> hidden = new LinkedHashSet<>(store.hiddenRefs());
        hidden.addAll(fullNames);
        store.hiddenRefs(hidden);
    }

    /** A section's "Show all": shows only that section's refs again, leaving the others hidden. */
    void showAll(Collection<String> fullNames) {
        Set<String> hidden = new LinkedHashSet<>(store.hiddenRefs());
        hidden.removeAll(fullNames);
        store.hiddenRefs(hidden);
    }

    /**
     * Carries Hide and Solo across a remote's rename. Both are kept by full ref name, so
     * without this a hidden {@code origin/old-experiment} reappears in the graph the moment
     * its remote is renamed, and the stored names point at nothing for ever. Everything
     * under {@code refs/remotes/<from>/} moves, as git's own rename moves the refs.
     */
    void remoteRenamed(String from, String to) {
        String oldPrefix = RefNames.REMOTES + from + "/";
        String newPrefix = RefNames.REMOTES + to + "/";
        Set<String> hidden = new LinkedHashSet<>();
        boolean changed = false;
        for (String ref : store.hiddenRefs()) {
            if (ref.startsWith(oldPrefix)) {
                hidden.add(newPrefix + ref.substring(oldPrefix.length()));
                changed = true;
            } else {
                hidden.add(ref);
            }
        }
        if (changed) {
            store.hiddenRefs(hidden);
        }
        String solo = store.soloRef();
        if (solo != null && solo.startsWith(oldPrefix)) {
            store.soloRef(newPrefix + solo.substring(oldPrefix.length()));
        }
    }

    /**
     * Carries Hide and Solo across one ref's rename - a local branch's. As for a remote:
     * without it a hidden branch comes back under its new name, and a soloed one leaves the
     * graph soloed to a ref that no longer exists, with no row left to clear it from.
     */
    void refRenamed(String from, String to) {
        Set<String> stored = store.hiddenRefs();
        if (stored.contains(from)) {
            Set<String> hidden = new LinkedHashSet<>();
            for (String ref : stored) {
                hidden.add(ref.equals(from) ? to : ref);
            }
            store.hiddenRefs(hidden);
        }
        if (from.equals(store.soloRef())) {
            store.soloRef(to);
        }
    }

    /** Hides the stash made at {@code hash}, or shows it again. */
    void toggleStashHidden(String hash) {
        Set<String> hidden = new LinkedHashSet<>(store.hiddenStashes());
        if (!hidden.remove(hash)) {
            hidden.add(hash);
        }
        store.hiddenStashes(hidden);
    }

    /** The stash section's "Hide all". */
    void hideStashes(Collection<String> hashes) {
        Set<String> hidden = new LinkedHashSet<>(store.hiddenStashes());
        hidden.addAll(hashes);
        store.hiddenStashes(hidden);
    }

    /** The stash section's "Show all". */
    void showStashes(Collection<String> hashes) {
        Set<String> hidden = new LinkedHashSet<>(store.hiddenStashes());
        hidden.removeAll(hashes);
        store.hiddenStashes(hidden);
    }
}
