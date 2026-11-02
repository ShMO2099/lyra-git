package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.StashEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StashRowsTest {

    private static CommitInfo commit(String hash, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "Tester", "t@e.com",
                Instant.EPOCH, List.of(parents), List.of());
    }

    private static StashEntry stash(int index, String hash, String parent) {
        return new StashEntry(index, hash, parent, "WIP on main: " + parent + " subject",
                "Tester", Instant.EPOCH);
    }

    private static List<GitGraphRow> rowsOf(CommitInfo... commits) {
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) rows.add(new GitGraphRow(c));
        return rows;
    }

    @Test
    void aStashIsInsertedAboveTheCommitItWasTakenFrom() {
        List<GitGraphRow> rows = rowsOf(commit("ccc", "bbb"), commit("bbb", "aaa"), commit("aaa"));

        StashRows.interleave(rows, List.of(stash(0, "s1", "bbb")), 1);

        assertEquals(4, rows.size());
        assertTrue(rows.get(1).isStash());
        assertEquals("s1", rows.get(1).getStash().hash());
        assertEquals("bbb", rows.get(2).getCommit().getHash(),
                "the stash sits directly above its parent");
    }

    @Test
    void stashesGoInTheirOwnLaneBeyondTheGraph() {
        List<GitGraphRow> rows = rowsOf(commit("bbb", "aaa"), commit("aaa"));

        int lanes = StashRows.interleave(rows, List.of(stash(0, "s1", "aaa")), 3);

        assertEquals(4, lanes, "one lane past the three the graph uses");
        // rows is now [bbb, stash, aaa]: the stash sits directly above its parent.
        assertTrue(rows.get(1).isStash());
        assertEquals(3, rows.get(1).getStashLane());
        assertEquals(4, rows.get(0).getTotalLanes(),
                "every row widens so the message column stays aligned");
    }

    /** Several stashes taken from the same commit must all survive the insert. */
    @Test
    void multipleStashesOnOneCommitAreAllInserted() {
        List<GitGraphRow> rows = rowsOf(commit("bbb", "aaa"), commit("aaa"));

        StashRows.interleave(rows, List.of(stash(0, "s1", "aaa"), stash(1, "s2", "aaa")), 1);

        assertEquals(4, rows.size());
        assertTrue(rows.get(1).isStash());
        assertTrue(rows.get(2).isStash());
        assertEquals("s1", rows.get(1).getStash().hash(), "newest stash first");
        assertEquals("s2", rows.get(2).getStash().hash());
        assertEquals("aaa", rows.get(3).getCommit().getHash());
    }

    @Test
    void insertingIntoSeveralPlacesKeepsEveryStashBesideItsOwnParent() {
        List<GitGraphRow> rows = rowsOf(commit("ccc", "bbb"), commit("bbb", "aaa"), commit("aaa"));

        StashRows.interleave(rows, List.of(stash(0, "s1", "aaa"), stash(1, "s2", "ccc")), 1);

        assertEquals(5, rows.size());
        assertEquals("s2", rows.get(0).getStash().hash());
        assertEquals("ccc", rows.get(1).getCommit().getHash());
        assertEquals("s1", rows.get(3).getStash().hash());
        assertEquals("aaa", rows.get(4).getCommit().getHash());
    }

    /**
     * A node with nothing to sit beside says nothing about where the work came from,
     * which is the only reason to draw it on the graph.
     */
    @Test
    void aStashWhoseParentIsOffThePageIsNotDrawn() {
        List<GitGraphRow> rows = rowsOf(commit("bbb", "aaa"), commit("aaa"));

        int lanes = StashRows.interleave(rows, List.of(stash(0, "s1", "not-on-this-page")), 1);

        assertEquals(2, rows.size());
        assertEquals(1, lanes, "no stash lane is reserved when nothing is placed");
    }

    @Test
    void anEmptyStashLeavesThePageAlone() {
        List<GitGraphRow> rows = rowsOf(commit("aaa"));
        assertEquals(2, StashRows.interleave(rows, List.of(), 2));
        assertEquals(1, rows.size());
    }

    @Test
    void theUncommittedRowIsNotMistakenForACommit() {
        List<GitGraphRow> rows = new ArrayList<>();
        rows.add(new GitGraphRow());               // uncommitted
        rows.add(new GitGraphRow(commit("aaa")));

        StashRows.interleave(rows, List.of(stash(0, "s1", "aaa")), 1);

        assertTrue(rows.get(0).isWipNode());
        assertTrue(rows.get(1).isStash());
        assertEquals("aaa", rows.get(2).getCommit().getHash());
    }

    // ------------------------------------------------------------------
    // visible (Hide & Solo's stash-specific hide, Wave 5)
    // ------------------------------------------------------------------

    @Test
    void visibleDropsAHiddenHashAndKeepsTheRest() {
        List<StashEntry> stashes = List.of(stash(0, "s1", "aaa"), stash(1, "s2", "bbb"));

        List<StashEntry> visible = StashRows.visible(stashes, Set.of("s1"));

        assertEquals(1, visible.size());
        assertEquals("s2", visible.get(0).hash());
    }

    @Test
    void visibleWithNoHiddenHashesReturnsEveryStash() {
        List<StashEntry> stashes = List.of(stash(0, "s1", "aaa"));
        assertEquals(stashes, StashRows.visible(stashes, Set.of()));
        assertEquals(stashes, StashRows.visible(stashes, null));
    }

    @Test
    void visibleOnAnEmptyListStaysEmpty() {
        assertTrue(StashRows.visible(List.of(), Set.of("s1")).isEmpty());
    }

    // ------------------------------------------------------------------
    // StashEntry
    // ------------------------------------------------------------------

    @Test
    void theDescriptionDropsGitsRepeatedBranchAndHash() {
        StashEntry entry = new StashEntry(0, "s1", "aaa",
                "WIP on main: 1a2b3c4 fix the parser", "Tester", Instant.EPOCH);
        assertEquals("fix the parser", entry.shortDescription());
    }

    @Test
    void aDescriptionWithoutAHashIsLeftAlone() {
        StashEntry entry = new StashEntry(0, "s1", "aaa",
                "On main: my own stash name", "Tester", Instant.EPOCH);
        assertEquals("my own stash name", entry.shortDescription());
    }

    @Test
    void theSelectorMatchesGitsOwnNotation() {
        assertEquals("stash@{2}",
                new StashEntry(2, "s", "p", "m", "a", Instant.EPOCH).selector());
    }
}
