package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.StashEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The engine-independent half of a graph page: start points, decorations, the WIP row. */
class GraphPageBuilderTest {

    private static CommitInfo c(String hash, long t, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "T", "t@e.com",
                Instant.ofEpochSecond(t), Instant.ofEpochSecond(t), List.of(parents), List.of());
    }

    private static RefInfo ref(String full, String hash) {
        return new RefInfo(full, RefInfo.shortNameOf(full), hash);
    }

    private static final List<RefInfo> REFS = List.of(
            ref("refs/heads/main", "m2"),
            ref("refs/heads/side", "s1"),
            ref("refs/remotes/origin/main", "m1"),
            ref("refs/remotes/origin/pr/12", "p1"),
            ref("refs/lyra/pr/3", "j1"),
            ref("refs/stash", "w1"),
            ref("refs/notes/commits", "n1"),
            ref("refs/tags/v1", "m0"));

    /** G1. The same start set as JGit: never stash internals, notes or the app's own refs. */
    @Test
    void startPointsAreHeadBranchesRemotesAndTagsOnly() {
        List<String> starts = GraphPageBuilder.startPoints(REFS, GraphVisibility.ALL, "m2");

        assertEquals(List.of("m2", "s1", "m1", "m0"), starts,
                "HEAD first, deduplicated, and nothing from refs/stash, refs/notes, refs/lyra "
                        + "or a synthetic pr ref");
    }

    /** G8. An unborn HEAD is simply not a start point, rather than a failing argument. */
    @Test
    void anUnbornHeadIsLeftOutOfTheStartPoints() {
        assertEquals(List.of("m2", "s1", "m1", "m0"),
                GraphPageBuilder.startPoints(REFS, GraphVisibility.ALL, null));
    }

    @Test
    void hiddenAndUnsoloedRefsNeitherStartTheWalkNorDecorate() {
        var hide = new GraphVisibility(Set.of("refs/heads/side", "refs/tags/v1"), null);
        assertFalse(GraphPageBuilder.startPoints(REFS, hide, "m2").contains("s1"));

        var decorations = GraphPageBuilder.decorations(REFS, hide, "m2", "refs/heads/main");
        assertFalse(decorations.containsKey("s1"), "a hidden branch loses its chip");
        assertFalse(decorations.containsKey("m0"), "so does a hidden tag");
        assertTrue(decorations.containsKey("m1"));

        var solo = new GraphVisibility(Set.of(), "refs/heads/side");
        var soloDecorations = GraphPageBuilder.decorations(REFS, solo, "m2", "refs/heads/main");
        assertEquals(Set.of("m2", "s1"), soloDecorations.keySet(),
                "solo keeps its own chip and the checked-out branch's, and nothing else");
    }

    /**
     * A solo on a ref that is gone - the soloed branch deleted since - solos nothing, as a
     * hidden name no ref has any more hides nothing. It left the graph showing HEAD's
     * history alone, with no row left in the sidebar to clear the solo from.
     */
    @Test
    void aSoloOnARefThatIsGoneSolosNothing() {
        var gone = new GraphVisibility(Set.of("refs/heads/side"), "refs/heads/deleted-since");

        assertEquals(List.of("m2", "m1", "m0"), GraphPageBuilder.startPoints(REFS, gone, "m2"),
                "every ref starts the walk again, except the one still hidden");
        assertEquals(Set.of("m2", "m1", "m0"),
                GraphPageBuilder.decorations(REFS, gone, "m2", "refs/heads/main").keySet(),
                "and decorates");

        var stashes = List.of(stashOn("w1", "lost"));
        assertTrue(GraphPageBuilder.startPoints(REFS, gone, "m2", stashes, (hash, from) -> false).contains("lost"),
                "a stash's base is walked again, as with no solo");
    }

    private static StashEntry stashOn(String hash, String base) {
        return new StashEntry(0, hash, base, "WIP on side: " + base + " work", "T", Instant.EPOCH);
    }

    /**
     * A stash's base starts the walk when nothing reaches it any more - the branch it was
     * made on rewritten since - and not when what reaches it is a branch the user hid.
     */
    @Test
    void aStashBaseStartsTheWalkUnlessAHiddenRefReachesIt() {
        var stashes = List.of(stashOn("w1", "lost"), stashOn("w2", "s0"));
        Map<String, List<String>> holders = Map.of("lost", List.of(), "s0", List.of("refs/heads/side"));
        java.util.function.BiPredicate<String, java.util.Collection<String>> reachedFrom = (hash, from) -> {
            assertEquals(List.of("refs/heads/side"), List.copyOf(from), "only the hidden refs are asked about");
            return holders.get(hash).stream().anyMatch(from::contains);
        };
        java.util.function.BiPredicate<String, java.util.Collection<String>> neverAsked = (hash, from) -> {
            throw new AssertionError("no existing ref is hidden, so nothing to ask about " + hash);
        };

        List<String> all = GraphPageBuilder.startPoints(REFS, GraphVisibility.ALL, "m2", stashes, neverAsked);
        assertTrue(all.containsAll(List.of("lost", "s0")), "nothing hidden: every base is walked");

        var gone = new GraphVisibility(Set.of("refs/heads/deleted-since"), null);
        assertTrue(GraphPageBuilder.startPoints(REFS, gone, "m2", stashes, neverAsked)
                .containsAll(List.of("lost", "s0")), "a hidden name no ref has any more hides nothing");

        var hide = new GraphVisibility(Set.of("refs/heads/side"), null);
        List<String> hidden = GraphPageBuilder.startPoints(REFS, hide, "m2", stashes, reachedFrom);
        assertTrue(hidden.contains("lost"), "only the stash reaches it: drawn, as commercial clients do");
        assertFalse(hidden.contains("s0"), "on the hidden branch: Hide means hide");

        var solo = new GraphVisibility(Set.of(), "refs/heads/main");
        assertEquals(List.of("m2"), GraphPageBuilder.startPoints(REFS, solo, "m2", stashes, neverAsked),
                "solo shows one ref's history and nothing else");
    }

    @Test
    void aDetachedHeadIsMarked() {
        var decorations = GraphPageBuilder.decorations(REFS, GraphVisibility.ALL, "m1", null);
        List<RefInfo> atHead = decorations.get("m1");
        assertTrue(atHead.get(0).isDetachedHead(), "the marker sorts first: " + atHead);

        assertTrue(GraphPageBuilder.decorations(REFS, GraphVisibility.ALL, "m2", "refs/heads/main")
                .values().stream().flatMap(List::stream).noneMatch(RefInfo::isDetachedHead));
    }

    @Test
    void upstreamsAreKeptOnlyForRemoteTrackingBranches() {
        var upstreams = GraphPageBuilder.upstreamShortNames(Map.of(
                "refs/heads/main", "refs/remotes/origin/main",
                "refs/heads/local", "refs/heads/main"));
        assertEquals(Map.of("main", "origin/main"), upstreams);
    }

    private static GraphPage build(List<CommitInfo> walk, String head, boolean dirty, List<StashEntry> stashes) {
        return build(walk, head, dirty, stashes, GraphOrder.BY_BRANCH);
    }

    private static GraphPage build(List<CommitInfo> walk, String head, boolean dirty,
                                   List<StashEntry> stashes, GraphOrder order) {
        return GraphPageBuilder.build(new GraphPageBuilder.Input(walk, true, head,
                order, 50, dirty, stashes, null, Map.of()));
    }

    /** G4. The uncommitted row takes HEAD's lane and draws an edge down to it. */
    @Test
    void theUncommittedRowSitsOnHeadsLane() {
        // A newer branch tip above HEAD would take lane 0 first under the old drawing.
        var base = c("base", 10);
        var head = c("head", 20, "base");
        var page = build(List.of(head, base), "head", true, List.of());

        GitGraphRow wip = page.rows().get(0);
        assertTrue(wip.isWipNode());
        assertNull(wip.getCommit(), "the WIP row still carries no commit");
        CommitInfo headLayout = page.rows().get(1).getCommit();
        assertEquals(headLayout.getLane(), wip.getLayout().getLane());
        assertEquals(List.of(headLayout.getLane()), wip.getLayout().getParentLanes(),
                "an edge leaves the WIP node for HEAD's lane");
        assertTrue(headLayout.getChildLanes().contains(wip.getLayout().getLane()),
                "and arrives at HEAD");
    }

    @Test
    void theUncommittedRowReachesAHeadThatIsNotTheFirstCommit() {
        // HEAD is detached one below main's tip, so the tip is drawn first.
        var base = c("base", 10);
        var head = c("head", 20, "base");
        var tip = c("tip", 30, "head");
        var page = build(List.of(tip, head, base), "head", true, List.of());

        GitGraphRow wip = page.rows().get(0);
        CommitInfo headRow = page.rows().stream().filter(r -> r.getCommit() != null
                && r.getCommit().getHash().equals("head")).findFirst().orElseThrow().getCommit();
        assertEquals(List.of(headRow.getLane()), wip.getLayout().getParentLanes(),
                "the WIP edge runs to wherever HEAD is");
        assertTrue(headRow.getChildLanes().contains(wip.getLayout().getLane()),
                "and the lane it reserved is the one arriving at HEAD");
    }

    /** G6. A stash row inserted into a page must not break the lanes that cross it. */
    @Test
    void aStashRowCarriesTheLanesCrossingIt() {
        // Chronologically: m2, s2, m1, s1, base - so side's lane passes m1's row, and a
        // stash taken from m1 opens a row that side's lane has to cross.
        var base = c("base", 10);
        var s1 = c("s1", 20, "base");
        var m1 = c("m1", 30, "base");
        var s2 = c("s2", 35, "s1");
        var m2 = c("m2", 40, "m1");
        var stash = new StashEntry(0, "st", "m1", "WIP on main", "T", Instant.ofEpochSecond(45));
        var page = build(List.of(m2, s2, m1, s1, base), "m2", false, List.of(stash),
                GraphOrder.CHRONOLOGICAL);

        GitGraphRow stashRow = page.rows().stream().filter(GitGraphRow::isStash).findFirst().orElseThrow();
        GitGraphRow anchorRow = page.rows().get(page.rows().indexOf(stashRow) + 1);
        assertEquals("m1", anchorRow.getCommit().getHash());

        CommitInfo anchor = anchorRow.getCommit();
        assertEquals(anchor.getLane(), stashRow.getTieLane(), "the tie points at the parent's lane");
        java.util.TreeSet<Integer> expected = new java.util.TreeSet<>(anchor.getChildLanes());
        expected.addAll(anchor.getPassingLanes());
        assertEquals(List.copyOf(expected), stashRow.getThroughLanes());
        assertEquals(2, stashRow.getThroughLanes().size(),
                "main's own lane and side's passing lane both cross the stash row");
        assertEquals(anchor.colorOfLane(anchor.getPassingLanes().get(0)),
                stashRow.throughLaneColor(anchor.getPassingLanes().get(0)),
                "a crossing lane keeps its branch's colour");
    }
}
