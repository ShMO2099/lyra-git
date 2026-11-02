package com.jfx.git.ui;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.graph.GraphVisibility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hide &amp; Solo's edits: the eye and the Hide and Solo items, a section's "Hide all" and
 * "Show all", the stash section's own Hide, and keeping both straight across a remote's
 * rename - each one read and one write of what is stored.
 */
class HideSoloTest {

    /** Hide &amp; Solo kept in memory, counting the writes. */
    private static final class Memory implements HideSolo.Store {
        Set<String> hidden = new LinkedHashSet<>();
        String solo;
        Set<String> stashes = new LinkedHashSet<>();
        int writes;

        @Override public Set<String> hiddenRefs() { return new LinkedHashSet<>(hidden); }
        @Override public void hiddenRefs(Set<String> refs) { hidden = new LinkedHashSet<>(refs); writes++; }
        @Override public String soloRef() { return solo; }
        @Override public void soloRef(String ref) { solo = ref; writes++; }
        @Override public Set<String> hiddenStashes() { return new LinkedHashSet<>(stashes); }
        @Override public void hiddenStashes(Set<String> hashes) { stashes = new LinkedHashSet<>(hashes); writes++; }
    }

    private final Memory store = new Memory();
    private final HideSolo editor = new HideSolo(store);

    @AfterEach
    void forgetTheSettings() {
        AppPreferences.setGraphHiddenBranches(null);
        AppPreferences.setGraphSoloBranch(null);
        AppPreferences.setHiddenStashes(null);
    }

    @Test
    void theEyeHidesARefAndShowsItAgainLeavingTheOthersInTheirOrder() {
        store.hidden.addAll(List.of("refs/heads/a", "refs/heads/b"));
        editor.toggleHidden("refs/tags/v1");
        assertEquals(List.of("refs/heads/a", "refs/heads/b", "refs/tags/v1"), List.copyOf(store.hidden));
        editor.toggleHidden("refs/heads/a");
        assertEquals(List.of("refs/heads/b", "refs/tags/v1"), List.copyOf(store.hidden));
    }

    @Test
    void aBranchAndARemoteBranchAndATagOfOneNameAreThreeRefs() {
        editor.toggleHidden("refs/heads/main");
        GraphVisibility visibility = editor.visibility();
        assertTrue(visibility.excludesName("refs/heads/main"));
        assertFalse(visibility.excludesName("refs/remotes/origin/main"));
        assertFalse(visibility.excludesName("refs/tags/main"));
    }

    @Test
    void soloReplacesAnotherSoloAndASecondSoloOfTheSameRefClearsIt() {
        editor.toggleSolo("refs/heads/a");
        assertEquals("refs/heads/a", store.solo);
        editor.toggleSolo("refs/remotes/origin/b");
        assertEquals("refs/remotes/origin/b", store.solo, "one solo at a time");
        editor.toggleSolo("refs/remotes/origin/b");
        assertNull(store.solo);
    }

    @Test
    void soloWinsOverHideAndLeavesTheHiddenSetAlone() {
        store.hidden.add("refs/heads/a");
        editor.toggleSolo("refs/heads/a");
        GraphVisibility visibility = editor.visibility();
        assertFalse(visibility.excludesName("refs/heads/a"), "soloed, so shown");
        assertTrue(visibility.excludesName("refs/heads/b"));
        assertEquals(Set.of("refs/heads/a"), store.hidden, "clearing the solo puts the hide back");
    }

    @Test
    void hideAllAddsASectionsRefsInOneWrite() {
        store.hidden.add("refs/tags/v1");
        editor.hideAll(List.of("refs/heads/a", "refs/heads/b", "refs/tags/v1"));
        assertEquals(List.of("refs/tags/v1", "refs/heads/a", "refs/heads/b"), List.copyOf(store.hidden));
        assertEquals(1, store.writes);
    }

    @Test
    void showAllShowsOnlyThatSectionsRefs() {
        store.hidden.addAll(List.of("refs/heads/a", "refs/tags/v1", "refs/remotes/origin/a"));
        editor.showAll(List.of("refs/heads/a", "refs/heads/b"));
        assertEquals(List.of("refs/tags/v1", "refs/remotes/origin/a"), List.copyOf(store.hidden));
        assertEquals(1, store.writes);
    }

    @Test
    void renamingARemoteCarriesItsHiddenBranchesAndSoloAcross() {
        store.hidden.addAll(List.of("refs/remotes/origin/old-experiment", "refs/heads/origin/x",
                "refs/remotes/origin2/y", "refs/remotes/origin/team/z"));
        store.solo = "refs/remotes/origin/main";
        editor.remoteRenamed("origin", "upstream");
        assertEquals(List.of("refs/remotes/upstream/old-experiment", "refs/heads/origin/x",
                "refs/remotes/origin2/y", "refs/remotes/upstream/team/z"), List.copyOf(store.hidden),
                "only what was under origin/ moves: not a local branch named origin/x, not origin2");
        assertEquals("refs/remotes/upstream/main", store.solo);
    }

    @Test
    void renamingARemoteWithNothingHiddenUnderItWritesNothing() {
        store.hidden.add("refs/heads/a");
        store.solo = "refs/heads/a";
        editor.remoteRenamed("origin", "upstream");
        assertEquals(0, store.writes);
        assertEquals(Set.of("refs/heads/a"), store.hidden);
        assertEquals("refs/heads/a", store.solo);
    }

    @Test
    void aRemoteWithASlashInItsNameIsRenamedWhole() {
        store.hidden.add("refs/remotes/team/core/topic");
        editor.remoteRenamed("team/core", "crew");
        assertEquals(Set.of("refs/remotes/crew/topic"), store.hidden);
    }

    @Test
    void renamingABranchCarriesItsHideAndSoloAcrossInPlace() {
        store.hidden.addAll(List.of("refs/heads/a", "refs/heads/feature", "refs/heads/feature/x"));
        store.solo = "refs/heads/feature";
        editor.refRenamed("refs/heads/feature", "refs/heads/topic");
        assertEquals(List.of("refs/heads/a", "refs/heads/topic", "refs/heads/feature/x"), List.copyOf(store.hidden),
                "that ref only, where it was: not one its name is the start of");
        assertEquals("refs/heads/topic", store.solo);
    }

    @Test
    void renamingABranchNeitherHiddenNorSoloedWritesNothing() {
        store.hidden.add("refs/heads/a");
        store.solo = "refs/heads/a";
        editor.refRenamed("refs/heads/feature", "refs/heads/topic");
        assertEquals(0, store.writes);
    }

    @Test
    void aStashIsHiddenAndShownByItsCommit() {
        editor.toggleStashHidden("s1");
        editor.toggleStashHidden("s2");
        assertEquals(Set.of("s1", "s2"), editor.hiddenStashes());
        editor.toggleStashHidden("s1");
        assertEquals(Set.of("s2"), editor.hiddenStashes());
        assertTrue(store.hidden.isEmpty() && store.solo == null, "a stash is no ref: Hide & Solo are untouched");
    }

    @Test
    void theStashSectionsHideAllAndShowAll() {
        store.stashes.add("old");
        editor.hideStashes(List.of("s1", "s2"));
        assertEquals(List.of("old", "s1", "s2"), List.copyOf(store.stashes));
        editor.showStashes(List.of("s1", "s2", "s3"));
        assertEquals(List.of("old"), List.copyOf(store.stashes));
    }

    @Test
    void theSettingsAreWhereTheGraphsWalkReadsThem() {
        HideSolo settings = new HideSolo(HideSolo.PREFERENCES);
        settings.toggleHidden("refs/heads/a");
        settings.toggleSolo("refs/tags/v1");
        settings.toggleStashHidden("s1");
        assertEquals(Set.of("refs/heads/a"), AppPreferences.graphHiddenBranches());
        assertEquals("refs/tags/v1", AppPreferences.graphSoloBranch());
        assertEquals(Set.of("s1"), AppPreferences.hiddenStashes());
        assertEquals(GraphVisibility.current(), settings.visibility());
    }
}
