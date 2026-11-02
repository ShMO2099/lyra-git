package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where an edge between two lanes changes lane - the shape of the graph commercial clients draw.
 *
 * <p>A side branch whose parent sits on a lower lane keeps its own lane all the way down
 * and turns into the parent at the parent's row. It used to turn into the lower lane
 * straight under its own commit, so a short branch looked like it left the trunk upward
 * and to the right rather than running alongside it and joining. A merge still turns at
 * the merge: across, then down to the parent it brings in.
 */
class GraphRoutingTest {

    private static CommitInfo c(String hash, long t, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "T", "t@e.com",
                Instant.ofEpochSecond(t), List.of(parents), List.of());
    }

    @Test
    void aSideBranchRunsDownItsOwnLaneAndJoinsAtTheParent() {
        // m (trunk, lane 0) and f (side, lane 1) share parent p, with a trunk commit
        // between them: f's lane must carry on through that row and arrive at p.
        CommitInfo m = c("m", 50, "x");
        CommitInfo f = c("f", 40, "p");
        CommitInfo x = c("x", 30, "p");
        CommitInfo p = c("p", 10);
        LaneAssigner.assign(List.of(m, f, x, p));

        assertEquals(0, m.getLane());
        assertEquals(1, f.getLane(), "the side branch has a lane of its own");
        assertEquals(List.of(1), f.getParentLanes(), "and leaves its node straight down that lane");
        assertTrue(x.getPassingLanes().contains(1), "running past the trunk commit in between");
        assertEquals(f.getColorIndex(), x.colorOfLane(1), "in the side branch's colour");
        assertEquals(0, p.getLane(), "the parent stays on the trunk");
        assertTrue(p.getChildLanes().contains(1), "and receives the side lane at its own row");
        assertEquals(f.getColorIndex(), p.childLaneColorOf(1),
                "the curve into the parent is still the side branch's colour");
    }

    @Test
    void aSideBranchWhoseParentIsTheNextRowStillJoinsThere() {
        // The screenshot's case: merge, then the branch commit, then the parent directly.
        CommitInfo merge = c("merge", 30, "trunk", "side");
        CommitInfo side = c("side", 20, "trunk");
        CommitInfo trunk = c("trunk", 10);
        LaneAssigner.assign(List.of(merge, side, trunk));

        assertEquals(List.of(0, 1), merge.getParentLanes(), "the merge turns across then down at the merge");
        assertEquals(1, side.getLane());
        assertEquals(List.of(1), side.getParentLanes(), "the branch goes straight down");
        assertEquals(List.of(0, 1), trunk.getChildLanes(), "and turns into the trunk at the trunk's row");
    }

    @Test
    void aMergeStillOpensANewLaneForTheParentItBringsIn() {
        CommitInfo merge = c("merge", 30, "a", "b");
        CommitInfo a = c("a", 20, "root");
        CommitInfo b = c("b", 15, "root");
        CommitInfo root = c("root", 10);
        LaneAssigner.assign(List.of(merge, a, b, root));

        assertEquals(0, merge.getLane());
        assertEquals(List.of(0, 1), merge.getParentLanes());
        assertEquals(1, b.getLane(), "b on the lane the merge opened for it");
    }

    @Test
    void aSideBranchMergeKeepsItsOwnNodeColour() {
        // s is a side commit whose first parent the trunk already waits for, and which is
        // itself a merge. Its lane used to be freed and handed straight to its merge
        // parent, whose new colour then replaced s's own.
        CommitInfo top = c("top", 60, "p");
        CommitInfo s = c("s", 50, "p", "q");
        CommitInfo p = c("p", 40, "root");
        CommitInfo q = c("q", 30, "root");
        CommitInfo root = c("root", 10);
        LaneAssigner.assign(List.of(top, s, p, q, root));

        int ownColour = s.getColorIndex();
        assertEquals(s.getLane(), s.getParentLanes().get(0), "s keeps its lane for its first parent");
        int mergeLane = s.getParentLanes().get(1);
        assertTrue(mergeLane != s.getLane(), "so the merge parent gets a lane of its own");
        assertTrue(ownColour != s.colorOfLane(mergeLane),
                "and s's node keeps its colour: " + ownColour + " vs " + s.colorOfLane(mergeLane));
    }
}
