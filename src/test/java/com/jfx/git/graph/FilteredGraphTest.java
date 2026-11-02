package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** G5. A narrowed filter lays its rows out among themselves. */
class FilteredGraphTest {

    private static CommitInfo c(String hash, long t, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "T", "t@e.com",
                Instant.ofEpochSecond(t), List.of(parents), List.of());
    }

    private static List<GitGraphRow> page(CommitInfo... commits) {
        List<CommitInfo> list = List.of(commits);
        LaneAssigner.assign(list);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo commit : list) rows.add(new GitGraphRow(commit));
        return rows;
    }

    @Test
    void keptCommitsAreJoinedToTheirNearestKeptAncestor() {
        // Two lines: main (m3 -> m2 -> m1 -> base) and side (s2 -> s1 -> base).
        var rows = page(c("m3", 70, "m2"), c("s2", 60, "s1"), c("m2", 50, "m1"),
                c("s1", 40, "base"), c("m1", 30, "base"), c("base", 10));
        Set<String> keep = Set.of("m3", "s2", "base");

        var filtered = FilteredGraph.relayout(rows, r -> keep.contains(r.getCommit().getHash()));

        assertEquals(List.of("m3", "s2", "base"),
                filtered.stream().map(r -> r.getCommit().getHash()).toList());
        CommitInfo m3 = filtered.get(0).getLayout();
        CommitInfo s2 = filtered.get(1).getLayout();
        CommitInfo base = filtered.get(2).getLayout();
        assertEquals(List.of("base"), m3.getParentHashes(), "m3 reaches base through hidden m2, m1");
        assertEquals(List.of("base"), s2.getParentHashes());
        assertNotEquals(m3.getLane(), s2.getLane(), "two lines, two lanes");
        assertEquals(List.of(base.getLane()), m3.getParentLanes(), "m3's edge runs to base");
        assertEquals(List.of(s2.getLane()), s2.getParentLanes(),
                "s2's runs down its own lane, and joins base's at base's row");
        assertTrue(base.getChildLanes().contains(s2.getLane()), "where base receives it");
        assertEquals(List.of(), base.getPassingLanes(), "and nothing passes base to nowhere");
        assertEquals(2, base.getTotalLanes());
    }

    @Test
    void theFullPagesLayoutIsLeftAlone() {
        var rows = page(c("b", 20, "a"), c("x", 15, "a"), c("a", 10));
        int before = rows.get(1).getCommit().getLane();

        var filtered = FilteredGraph.relayout(rows, r -> !r.getCommit().getHash().equals("b"));

        assertEquals(before, rows.get(1).getCommit().getLane(), "the shared commit keeps its lane");
        assertSame(rows.get(1).getCommit(), filtered.get(0).getCommit(),
                "rows still hold the real commit, for selection and menus");
        assertNotSame(rows.get(1).getCommit(), filtered.get(0).getLayout());
        assertEquals(0, filtered.get(0).getLayout().getLane(), "x now leads its own graph");
    }

    @Test
    void aMergeKeepsItsRealParentsForTheNodeShape() {
        var rows = page(c("merge", 40, "main", "side"), c("side", 30, "base"),
                c("main", 20, "base"), c("base", 10));

        var filtered = FilteredGraph.relayout(rows, r -> !r.getCommit().getHash().equals("side"));

        assertEquals(2, filtered.get(0).getCommit().getParentHashes().size());
        assertEquals(List.of("main", "base"), filtered.get(0).getLayout().getParentHashes());
    }

    @Test
    void theUncommittedRowFollowsHeadThroughTheFilter() {
        var head = c("head", 20, "base");
        var base = c("base", 10);
        var pseudo = GitGraphRow.uncommittedPseudoCommit("head");
        List<CommitInfo> layout = List.of(pseudo, head, base);
        LaneAssigner.assign(layout);
        List<GitGraphRow> rows = new ArrayList<>(List.of(GitGraphRow.uncommitted(pseudo),
                new GitGraphRow(head), new GitGraphRow(base)));

        var filtered = FilteredGraph.relayout(rows,
                r -> r.isWipNode() || r.getCommit().getHash().equals("base"));

        assertTrue(filtered.get(0).isWipNode());
        assertEquals(List.of("base"), filtered.get(0).getLayout().getParentHashes());
    }
}
