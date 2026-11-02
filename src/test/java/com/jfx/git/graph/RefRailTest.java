package com.jfx.git.graph;

import com.jfx.git.model.RefInfo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefRailTest {

    private static RefInfo local(String name) {
        return new RefInfo("refs/heads/" + name, name, "abc123");
    }

    private static RefInfo remote(String name) {
        return new RefInfo("refs/remotes/" + name, name, "abc123");
    }

    private static RefInfo tag(String name) {
        return new RefInfo("refs/tags/" + name, name, "abc123");
    }

    @Test
    @DisplayName("a branch and its remote counterpart collapse into one chip")
    void collapsesTrackedPair() {
        var chips = RefRail.chipsFor(List.of(local("main"), remote("origin/main")), "main");

        assertEquals(1, chips.size());
        assertEquals("main", chips.get(0).name());
        assertEquals(RefRail.Kind.LOCAL, chips.get(0).kind());
        assertTrue(chips.get(0).tracked(), "the remote counterpart should be folded in");
    }

    @Test
    @DisplayName("a remote with no local counterpart on this commit keeps its own chip")
    void keepsUnmatchedRemote() {
        var chips = RefRail.chipsFor(List.of(local("main"), remote("origin/feature")), "main");

        assertEquals(2, chips.size());
        assertEquals("main", chips.get(0).name());
        assertEquals("origin/feature", chips.get(1).name());
        assertEquals(RefRail.Kind.REMOTE, chips.get(1).kind());
        assertFalse(chips.get(0).tracked(), "origin/feature is not main's counterpart");
    }

    @Test
    @DisplayName("a local branch whose remote sits elsewhere is not marked as tracked")
    void divergedBranchIsNotTracked() {
        // The remote ref is not on this commit at all, so the two have diverged and the
        // rail must not claim they are in sync.
        var chips = RefRail.chipsFor(List.of(local("main")), "main");

        assertEquals(1, chips.size());
        assertFalse(chips.get(0).tracked());
    }

    @Test
    @DisplayName("HEAD is marked on the branch that is checked out")
    void marksHead() {
        var chips = RefRail.chipsFor(List.of(local("main"), local("release")), "release");

        assertEquals(2, chips.size());
        // By name, not position: the checked-out branch also moves to the front.
        assertTrue(chips.stream().filter(c -> c.name().equals("release")).findFirst().orElseThrow().head(),
                "release is checked out");
        assertFalse(chips.stream().filter(c -> c.name().equals("main")).findFirst().orElseThrow().head(),
                "main is not");
    }

    @Test
    @DisplayName("nothing is marked as HEAD when the head is detached")
    void detachedHeadMarksNothing() {
        var chips = RefRail.chipsFor(List.of(local("main")), null);

        assertEquals(1, chips.size());
        assertFalse(chips.get(0).head());
    }

    @Test
    @DisplayName("origin/HEAD is dropped")
    void dropsRemoteHead() {
        var chips = RefRail.chipsFor(List.of(remote("origin/HEAD"), remote("origin/main")), null);

        assertEquals(1, chips.size());
        assertEquals("origin/main", chips.get(0).name());
    }

    @Test
    @DisplayName("only the first segment of a remote name is the remote")
    void stripsOnlyTheRemoteName() {
        // backup/before-reword pushed to origin is origin/backup/before-reword, and the
        // branch it belongs to is backup/before-reword - not backup.
        assertEquals("backup/before-reword", RefRail.strippedRemote("origin/backup/before-reword"));

        var chips = RefRail.chipsFor(
                List.of(local("backup/before-reword"), remote("origin/backup/before-reword")), null);

        assertEquals(1, chips.size(), "the pair should still collapse when the name has slashes");
        assertTrue(chips.get(0).tracked());
    }

    @Test
    @DisplayName("chips come out in display order: locals, then remotes, then tags")
    void ordersChips() {
        var chips = RefRail.chipsFor(
                List.of(tag("v1.0"), remote("origin/other"), local("main")), "main");

        assertEquals(List.of("main", "origin/other", "v1.0"),
                chips.stream().map(RefRail.Chip::name).toList());
    }

    @Test
    @DisplayName("the checked-out branch comes first, ahead of names that sort before it")
    void theCheckedOutBranchLeads() {
        // master fast-forwarded onto a feature branch: both on one commit, and the rail
        // has room for one chip and a "+1". It must be the branch the user is on.
        var chips = RefRail.chipsFor(
                List.of(local("editor-parity"), local("master"), local("backup"), tag("v2")), "master");

        assertEquals(List.of("master", "backup", "editor-parity", "v2"),
                chips.stream().map(RefRail.Chip::name).toList());
        assertTrue(chips.get(0).head());
    }

    @Test
    @DisplayName("with no branch checked out here, the order is unchanged")
    void noCheckedOutBranchKeepsTheDisplayOrder() {
        var chips = RefRail.chipsFor(List.of(local("b"), local("a")), "elsewhere");
        assertEquals(List.of("a", "b"), chips.stream().map(RefRail.Chip::name).toList());
    }

    @Test
    @DisplayName("a commit with no refs produces no chips")
    void noRefs() {
        assertTrue(RefRail.chipsFor(List.of(), "main").isEmpty());
        assertTrue(RefRail.chipsFor(null, "main").isEmpty());
    }

    /** G14. With tracking known, only the branch's own upstream is folded into it. */
    @Test
    @DisplayName("a remote folds into a branch only when that branch tracks it")
    void foldsOnlyTheRealUpstream() {
        var refs = List.of(local("main"), remote("origin/main"), remote("upstream/main"));

        var chips = RefRail.chipsFor(refs, "main", java.util.Map.of("main", "origin/main"));

        assertEquals(List.of("main", "upstream/main"), chips.stream().map(RefRail.Chip::name).toList());
        assertTrue(chips.get(0).tracked());
    }

    @Test
    @DisplayName("a branch with no upstream is not shown as tracked by a same-named remote")
    void noUpstreamMeansNoFold() {
        var chips = RefRail.chipsFor(List.of(local("main"), remote("upstream/main")), "main",
                java.util.Map.of());

        assertEquals(2, chips.size());
        assertFalse(chips.get(0).tracked());
    }

    @Test
    @DisplayName("a detached HEAD wears a HEAD chip, first")
    void detachedHeadIsMarked() {
        var chips = RefRail.chipsFor(List.of(tag("v1"), RefInfo.detachedHead("abc123")), null);

        assertEquals("HEAD", chips.get(0).name());
        assertTrue(chips.get(0).head());
        assertEquals("v1", chips.get(1).name());
    }
}
