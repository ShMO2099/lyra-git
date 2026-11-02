package com.jfx.git.graph;

import com.jfx.git.graph.RowPlan.Anchor;
import com.jfx.git.graph.RowPlan.EdgeShape;
import com.jfx.git.graph.RowPlan.Node;
import com.jfx.git.graph.RowPlan.NodeShape;
import com.jfx.git.graph.RowPlan.Segment;
import com.jfx.git.graph.RowPlan.Stroke;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.StashEntry;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a graph row draws, read off its plan rather than off its pixels.
 *
 * <p>The routing and overflow rules were once only checkable by painting a canvas and
 * sampling saturation; {@link GraphEdgeShapeTest} keeps one such check, as the smoke test
 * that the painter draws what it is planned. The plan
 * states them as data: which columns an edge joins, at which heights, turning which way,
 * stroked how and in which palette colour; whether the row carries the overflow marker;
 * what the node is.
 */
class RowPlanTest {

    /** The budget the narrow tests draw at: three columns of their own and an overflow column. */
    private static final int NARROW = GraphDensity.MIN_DRAWN_LANES;

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

    /** merge (trunk, side), side (trunk), trunk: one side branch leaving and rejoining. */
    private static List<GitGraphRow> mergeSideTrunk() {
        return page(c("merge", 30, "trunk", "side"), c("side", 20, "trunk"), c("trunk", 10));
    }

    /** A commit laid out by hand: its lane, the lanes meeting its row, and the page's width. */
    private static CommitInfo laidOut(int lane, List<Integer> children, List<Integer> parents,
                                      List<Integer> passing, int totalLanes, Map<Integer, Integer> colors) {
        CommitInfo commit = c("x" + lane, 10, "p");
        commit.setLane(lane);
        commit.setChildLanes(children);
        commit.setParentLanes(parents);
        commit.setPassingLanes(passing);
        commit.setTotalLanes(totalLanes);
        commit.setLaneColors(colors);
        return commit;
    }

    private static RowPlan dots(GitGraphRow row, int budget) {
        return RowPlanner.plan(row, budget, GraphNodeStyle.DOT);
    }

    private static List<Segment> stroked(RowPlan plan, Stroke stroke) {
        return plan.segments().stream().filter(s -> s.stroke() == stroke).toList();
    }

    private static Segment segment(int fromColumn, Anchor from, int toColumn, Anchor to,
                                   EdgeShape shape, Stroke stroke, int color) {
        return new Segment(fromColumn, from, toColumn, to, shape, stroke, color);
    }

    // ------------------------------------------------------------------
    // Edge shapes
    // ------------------------------------------------------------------

    @Test
    void aSideBranchArrivesDownItsOwnLaneAndTurnsIntoTheParent() {
        GitGraphRow trunkRow = mergeSideTrunk().get(2);
        CommitInfo trunk = trunkRow.getLayout();
        int side = trunk.getChildLanes().stream().filter(l -> l != trunk.getLane()).findFirst().orElseThrow();

        RowPlan plan = dots(trunkRow, 14);

        assertTrue(plan.segments().contains(segment(side, Anchor.TOP, trunk.getLane(), Anchor.MIDDLE,
                        EdgeShape.DOWN_THEN_ACROSS, Stroke.CASED, trunk.childLaneColorOf(side))),
                "the side lane comes down its own column and turns into the node, in the branch's colour: "
                        + plan.segments());
        assertTrue(plan.segments().contains(segment(trunk.getLane(), Anchor.TOP, trunk.getLane(), Anchor.NODE_TOP,
                        EdgeShape.STRAIGHT, Stroke.LANE, trunk.childLaneColorOf(trunk.getLane()))),
                "and the trunk's own lane arrives straight down to the top of the node");
    }

    @Test
    void aMergeLeavesAcrossThenDownToTheSideBranch() {
        GitGraphRow mergeRow = mergeSideTrunk().get(0);
        CommitInfo merge = mergeRow.getLayout();
        int side = merge.getParentLanes().stream().filter(l -> l != merge.getLane()).findFirst().orElseThrow();

        RowPlan plan = dots(mergeRow, 14);

        assertEquals(List.of(
                        segment(merge.getLane(), Anchor.NODE_BOTTOM, merge.getLane(), Anchor.BOTTOM,
                                EdgeShape.STRAIGHT, Stroke.LANE, merge.colorOfLane(merge.getLane())),
                        segment(merge.getLane(), Anchor.MIDDLE, side, Anchor.BOTTOM,
                                EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, merge.colorOfLane(side))),
                plan.segments(),
                "down its own lane from the node, and across at the node's height then down the side lane");
        assertEquals(new Node(NodeShape.DOT, merge.getLane(), merge.getColorIndex(), true), plan.node(),
                "a merge is a hollow dot");
    }

    @Test
    void aLaneCrossingTheRowRunsStraightThroughIt() {
        GitGraphRow sideRow = mergeSideTrunk().get(1);
        CommitInfo side = sideRow.getLayout();
        int passing = side.getPassingLanes().get(0);

        RowPlan plan = dots(sideRow, 14);

        assertEquals(segment(passing, Anchor.TOP, passing, Anchor.BOTTOM,
                        EdgeShape.STRAIGHT, Stroke.LANE, side.colorOfLane(passing)),
                plan.segments().get(0), "passing lanes are drawn first, top to bottom");
        assertEquals(new Node(NodeShape.DOT, side.getLane(), side.getColorIndex(), false), plan.node());
        assertFalse(plan.hasMarker(), "nothing past the budget, nothing to mark");
    }

    @Test
    void anEdgeKeepsTheColourOfTheBranchItArrivesFrom() {
        CommitInfo commit = laidOut(0, List.of(0, 1), List.of(0), List.of(), 2, Map.of(0, 4, 1, 7));
        commit.setChildLaneColors(Map.of(1, 9));

        RowPlan plan = dots(new GitGraphRow(commit), 14);

        assertEquals(9, plan.segments().get(1).color(), "the arriving edge carries its branch's colour");
        assertEquals(4, plan.segments().get(2).color(), "the leaving one the lane it leaves into");
        assertEquals(4, plan.node().color());
    }

    @Test
    void theAuthorStyleDrawsAnAvatarAtTheNode() {
        GitGraphRow mergeRow = mergeSideTrunk().get(0);

        RowPlan plan = RowPlanner.plan(mergeRow, 14, GraphNodeStyle.AUTHOR);

        assertEquals(NodeShape.AVATAR, plan.node().shape());
        assertTrue(plan.node().merge(), "with the merge's thicker ring");
        assertEquals(dots(mergeRow, 14).segments(), plan.segments(), "the edges do not depend on the style");
    }

    @Test
    void aFilteredMergeKeepsItsShapeButDrawsTheFilteredEdges() {
        List<GitGraphRow> rows = mergeSideTrunk();
        List<GitGraphRow> filtered = FilteredGraph.relayout(rows, r -> !r.getCommit().getHash().equals("side"));

        RowPlan full = dots(rows.get(0), 14);
        RowPlan narrowed = dots(filtered.get(0), 14);

        assertEquals(1, stroked(full, Stroke.CASED).size(), "the full page's merge turns out to the side");
        assertEquals(List.of(), stroked(narrowed, Stroke.CASED),
                "the filtered one has only trunk left to join: one straight edge");
        assertTrue(narrowed.node().merge(), "but it is still a merge, read from the real commit");
    }

    // ------------------------------------------------------------------
    // Overflow
    // ------------------------------------------------------------------

    @Test
    void aRowReceivingLanesPastTheBudgetCarriesTheMarker() {
        // The root of eight tips: every lane arrives here.
        CommitInfo root = laidOut(0, List.of(0, 1, 2, 3, 4, 5, 6, 7), List.of(), List.of(), 8, Map.of());

        RowPlan plan = dots(new GitGraphRow(root), NARROW);

        assertEquals(NARROW - 1, plan.marker(), "the last column is the marker's");
        assertEquals(List.of(
                        segment(0, Anchor.TOP, 0, Anchor.NODE_TOP, EdgeShape.STRAIGHT, Stroke.LANE, 0),
                        segment(1, Anchor.TOP, 0, Anchor.MIDDLE, EdgeShape.DOWN_THEN_ACROSS, Stroke.CASED, 1),
                        segment(2, Anchor.TOP, 0, Anchor.MIDDLE, EdgeShape.DOWN_THEN_ACROSS, Stroke.CASED, 2),
                        segment(3, Anchor.MIDDLE, 0, Anchor.MIDDLE, EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, 3),
                        segment(3, Anchor.MIDDLE, 0, Anchor.MIDDLE, EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, 4),
                        segment(3, Anchor.MIDDLE, 0, Anchor.MIDDLE, EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, 5),
                        segment(3, Anchor.MIDDLE, 0, Anchor.MIDDLE, EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, 6),
                        segment(3, Anchor.MIDDLE, 0, Anchor.MIDDLE, EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, 7)),
                plan.segments(),
                "lanes with a column come down it; the hidden ones only join the node from the "
                        + "marker, at its height - nothing stacks down the overflow column");
    }

    @Test
    void passingLanesPastTheBudgetAreLeftToTheMarker() {
        CommitInfo commit = laidOut(0, List.of(0), List.of(0), List.of(1, 5, 6), 8, Map.of());

        RowPlan plan = dots(new GitGraphRow(commit), NARROW);

        assertEquals(NARROW - 1, plan.marker());
        assertEquals(List.of(1), plan.segments().stream()
                        .filter(s -> s.from() == Anchor.TOP && s.to() == Anchor.BOTTOM)
                        .map(Segment::fromColumn).toList(),
                "only the passing lane with a column of its own is drawn");
    }

    @Test
    void aParentPastTheBudgetIsOnlyTheLegOutOfTheNode() {
        CommitInfo commit = laidOut(1, List.of(), List.of(1, 6), List.of(), 8, Map.of());

        RowPlan plan = dots(new GitGraphRow(commit), NARROW);

        assertEquals(NARROW - 1, plan.marker());
        assertEquals(segment(1, Anchor.MIDDLE, NARROW - 1, Anchor.MIDDLE,
                        EdgeShape.ACROSS_THEN_DOWN, Stroke.CASED, 6),
                plan.segments().get(1), "across into the marker, and no further");
    }

    @Test
    void aCommitInTheOverflowColumnIsASmallRing() {
        CommitInfo commit = laidOut(6, List.of(1, 6), List.of(6), List.of(), 8, Map.of(6, 2));

        RowPlan plan = dots(new GitGraphRow(commit), NARROW);

        assertEquals(new Node(NodeShape.OVERFLOW_RING, NARROW - 1, 2, false), plan.node(),
                "still a commit on this row, without pretending to be on a lane");
        assertEquals(NARROW - 1, plan.marker(), "and the marker stands for its lane");
        assertEquals(List.of(segment(1, Anchor.TOP, NARROW - 1, Anchor.MIDDLE,
                        EdgeShape.DOWN_THEN_ACROSS, Stroke.CASED, 1)),
                plan.segments(), "a drawn lane still joins it; its own lane is the marker's to draw");
        assertEquals(NodeShape.OVERFLOW_RING,
                RowPlanner.plan(new GitGraphRow(commit), NARROW, GraphNodeStyle.AUTHOR).node().shape(),
                "in either node style");
    }

    @Test
    void aRowWithNothingPastTheBudgetHasNoMarkerOnAPageThatOverflows() {
        CommitInfo commit = laidOut(0, List.of(0), List.of(0, 1), List.of(2), 8, Map.of());

        assertFalse(dots(new GitGraphRow(commit), NARROW).hasMarker());
    }

    @Test
    void aBudgetThatHoldsEveryLaneHidesNothing() {
        CommitInfo root = laidOut(0, List.of(0, 1, 2, 3, 4, 5, 6, 7), List.of(), List.of(), 8, Map.of());

        RowPlan plan = dots(new GitGraphRow(root), 8);

        assertFalse(plan.hasMarker());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), plan.segments().stream().map(Segment::fromColumn).toList());
        assertTrue(plan.segments().stream().allMatch(s -> s.from() == Anchor.TOP),
                "every lane comes down its own column");
    }

    // ------------------------------------------------------------------
    // Stash rows
    // ------------------------------------------------------------------

    private static GitGraphRow stashRow(CommitInfo anchor, int stashLane, int totalLanes) {
        GitGraphRow row = new GitGraphRow(new StashEntry(0, "st", anchor == null ? null : anchor.getHash(),
                "WIP on main", "T", Instant.ofEpochSecond(45)), stashLane);
        row.setAnchor(anchor);
        row.setTotalLanes(totalLanes);
        return row;
    }

    @Test
    void aStashRowCarriesTheLanesCrossingItAndATieToItsParent() {
        // The anchor receives lanes 0 and 1 from above and has lane 2 passing it.
        CommitInfo anchor = laidOut(1, List.of(1, 0), List.of(1), List.of(2), 4, Map.of(0, 5, 1, 6, 2, 7));

        RowPlan plan = dots(stashRow(anchor, 3, 4), 14);

        assertEquals(List.of(
                        segment(0, Anchor.TOP, 0, Anchor.BOTTOM, EdgeShape.STRAIGHT, Stroke.LANE, 5),
                        segment(1, Anchor.TOP, 1, Anchor.BOTTOM, EdgeShape.STRAIGHT, Stroke.LANE, 6),
                        segment(2, Anchor.TOP, 2, Anchor.BOTTOM, EdgeShape.STRAIGHT, Stroke.LANE, 7),
                        segment(3, Anchor.NODE_LEFT, 1, Anchor.BOTTOM,
                                EdgeShape.ACROSS_THEN_DOWN, Stroke.TIE, RowPlan.STASH)),
                plan.segments(),
                "every lane crossing the gap runs straight through, in its branch's colour, and the "
                        + "dotted tie leaves the diamond's left point and turns down into the parent's lane");
        assertEquals(new Node(NodeShape.STASH_DIAMOND, 3, RowPlan.STASH, false), plan.node());
        assertFalse(plan.hasMarker());
    }

    @Test
    void crossingLanesPastTheBudgetAreLeftToTheMarker() {
        CommitInfo anchor = laidOut(0, List.of(0), List.of(0), List.of(1, 5, 6), 8, Map.of());

        RowPlan plan = dots(stashRow(anchor, 7, 8), NARROW);

        assertEquals(NARROW - 1, plan.marker(), "one marker for both hidden lanes");
        assertEquals(List.of(0, 1), stroked(plan, Stroke.LANE).stream().map(Segment::fromColumn).toList());
        assertEquals(NARROW - 1, plan.node().column(), "the stash's lane, past every commit lane, shares the last column");
    }

    @Test
    void theTieIsKeptInsideTheBudgetAndNoMore() {
        // Pinned as it was: the tie is not overflow-aware beyond the clamp. With the parent
        // in the overflow column too, the tie still turns from the diamond's left point
        // down that same column, and nothing crosses the row to call for the marker.
        CommitInfo anchor = laidOut(6, List.of(), List.of(6), List.of(), 9, Map.of());

        RowPlan plan = dots(stashRow(anchor, 8, 9), NARROW);

        assertEquals(List.of(segment(NARROW - 1, Anchor.NODE_LEFT, NARROW - 1, Anchor.BOTTOM,
                        EdgeShape.ACROSS_THEN_DOWN, Stroke.TIE, RowPlan.STASH)),
                plan.segments());
        assertFalse(plan.hasMarker());
    }

    @Test
    void aStashWithNoKnownParentTiesToLaneZero() {
        RowPlan plan = dots(stashRow(null, 2, 3), 14);

        assertEquals(List.of(segment(2, Anchor.NODE_LEFT, 0, Anchor.BOTTOM,
                        EdgeShape.ACROSS_THEN_DOWN, Stroke.TIE, RowPlan.STASH)),
                plan.segments(), "nothing crosses it, and the tie falls back to lane 0");
    }

    @Test
    void aStashRowOnABuiltPageCrossesWhatItsAnchorReceives() {
        // From GraphPageBuilderTest's G6 page: side's lane passes m1, and the stash from m1
        // opens a row between m1 and the commit above it.
        var base = c("base", 10);
        var s1 = c("s1", 20, "base");
        var m1 = c("m1", 30, "base");
        var s2 = c("s2", 35, "s1");
        var m2 = c("m2", 40, "m1");
        List<GitGraphRow> rows = page(m2, s2, m1, s1, base);
        int lanes = StashRows.interleave(rows, List.of(new StashEntry(0, "st", "m1", "WIP on main", "T",
                Instant.ofEpochSecond(45))), m2.getTotalLanes());
        GitGraphRow stash = rows.stream().filter(GitGraphRow::isStash).findFirst().orElseThrow();

        RowPlan plan = dots(stash, 14);

        assertEquals(stash.getThroughLanes(), stroked(plan, Stroke.LANE).stream().map(Segment::fromColumn).toList());
        assertEquals(m1.getLane(), stroked(plan, Stroke.TIE).get(0).toColumn());
        assertEquals(lanes, plan.lanes(), "the stash's own lane widens the page");
    }

    // ------------------------------------------------------------------
    // The uncommitted row
    // ------------------------------------------------------------------

    @Test
    void theUncommittedRowHasADashedEdgeDownToHead() {
        CommitInfo pseudo = GitGraphRow.uncommittedPseudoCommit("head");
        CommitInfo head = c("head", 20, "base");
        CommitInfo base = c("base", 10);
        LaneAssigner.assign(List.of(pseudo, head, base));

        RowPlan plan = dots(GitGraphRow.uncommitted(pseudo), 14);

        assertEquals(List.of(segment(pseudo.getLane(), Anchor.NODE_BOTTOM, head.getLane(), Anchor.BOTTOM,
                        EdgeShape.STRAIGHT, Stroke.DASHED, pseudo.colorOfLane(head.getLane()))),
                plan.segments(), "down HEAD's lane from the ring, dashed");
        assertEquals(new Node(NodeShape.WIP_RING, head.getLane(), RowPlan.ATTENTION, false), plan.node());
    }

    @Test
    void anEdgeToHeadInAnotherLaneTurnsDownIntoIt() {
        CommitInfo pseudo = laidOut(0, List.of(), List.of(2), List.of(), 3, Map.of(2, 8));

        RowPlan plan = dots(GitGraphRow.uncommitted(pseudo), 14);

        assertEquals(List.of(segment(0, Anchor.MIDDLE, 2, Anchor.BOTTOM,
                        EdgeShape.ACROSS_THEN_DOWN, Stroke.DASHED, 8)),
                plan.segments(), "across at the ring's height, then down - dashed, not cased");
    }

    @Test
    void anUncommittedRowWithoutALayoutIsALoneRingInLaneZero() {
        RowPlan plan = dots(new GitGraphRow(), 14);

        assertEquals(List.of(), plan.segments());
        assertEquals(new Node(NodeShape.WIP_RING, 0, RowPlan.ATTENTION, false), plan.node());
    }

    @Test
    void theLoadMoreRowDrawsNothingAtThePagesWidth() {
        GitGraphRow row = GitGraphRow.loadMore();
        row.setTotalLanes(5);

        RowPlan plan = dots(row, 14);

        assertEquals(5, plan.lanes());
        assertEquals(List.of(), plan.segments());
        assertNull(plan.node());
        assertFalse(plan.hasMarker());
    }

    // ------------------------------------------------------------------
    // Worked out once
    // ------------------------------------------------------------------

    @Test
    void aRowKeepsItsPlanWhileNothingItDependsOnMoves() {
        GitGraphRow row = mergeSideTrunk().get(0);

        RowPlan first = RowPlanner.planFor(row, 14, GraphNodeStyle.DOT);

        assertSame(first, RowPlanner.planFor(row, 14, GraphNodeStyle.DOT), "painted again, planned once");
    }

    @Test
    void aNewBudgetGivesANewPlan() {
        CommitInfo root = laidOut(0, List.of(0, 1, 2, 3, 4, 5, 6, 7), List.of(), List.of(), 8, Map.of());
        GitGraphRow row = new GitGraphRow(root);

        RowPlan wide = RowPlanner.planFor(row, 12, GraphNodeStyle.DOT);
        RowPlan narrow = RowPlanner.planFor(row, NARROW, GraphNodeStyle.DOT);

        assertFalse(wide.hasMarker());
        assertTrue(narrow.hasMarker(), "dragging the graph narrower re-plans the row");
        assertSame(narrow, RowPlanner.planFor(row, NARROW, GraphNodeStyle.DOT));
        assertEquals(wide, RowPlanner.planFor(row, 12, GraphNodeStyle.DOT), "and wider again gives the first plan back");
    }

    @Test
    void aNewNodeStyleGivesANewPlan() {
        GitGraphRow row = mergeSideTrunk().get(0);

        RowPlanner.planFor(row, 14, GraphNodeStyle.DOT);

        assertEquals(NodeShape.AVATAR, RowPlanner.planFor(row, 14, GraphNodeStyle.AUTHOR).node().shape());
    }

    @Test
    void aNewLaneCountForThePageGivesANewPlan() {
        GitGraphRow row = mergeSideTrunk().get(0);
        RowPlan before = RowPlanner.planFor(row, NARROW, GraphNodeStyle.DOT);

        row.setTotalLanes(9);   // a stash row, or a filter, widened the page
        RowPlan after = RowPlanner.planFor(row, NARROW, GraphNodeStyle.DOT);

        assertNotSame(before, after);
        assertEquals(9, after.lanes(), "the canvas is sized for the page it is now on");
    }

    @Test
    void filteredRowsArePlannedFromTheirOwnLayout() {
        List<GitGraphRow> rows = mergeSideTrunk();
        RowPlan full = RowPlanner.planFor(rows.get(0), 14, GraphNodeStyle.DOT);

        List<GitGraphRow> filtered = FilteredGraph.relayout(rows, r -> !r.getCommit().getHash().equals("side"));
        RowPlan narrowed = RowPlanner.planFor(filtered.get(0), 14, GraphNodeStyle.DOT);

        assertNotEquals(full, narrowed, "the filter's copy does not inherit the full page's plan");
        assertSame(full, RowPlanner.planFor(rows.get(0), 14, GraphNodeStyle.DOT),
                "and clearing the filter finds the full page's plan where it was left");
        assertEquals(Set.of(Stroke.LANE), Set.copyOf(narrowed.segments().stream().map(Segment::stroke).toList()));
    }

    // ------------------------------------------------------------------
    // The plan is plain data
    // ------------------------------------------------------------------

    @Test
    void thePlanReachesForNoJavaFx() throws Exception {
        for (String name : List.of("RowPlan", "RowPlanner")) {
            Path source = Path.of("src/main/java/com/jfx/git/graph", name + ".java");
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("javafx."), name + " reaches into JavaFX");
        }
    }
}
