package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.RefSnapshot;
import com.jfx.git.model.StashEntry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ref snapshot riding on the graph page (ADR 0002): built into the page, read by the
 * lane colours and the history window, and kept apart from what the page draws.
 */
class RefSnapshotPageTest {

    private static CommitInfo c(String hash, long t, List<RefInfo> refs, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "T", "t@e.com",
                Instant.ofEpochSecond(t), Instant.ofEpochSecond(t), List.of(parents), refs);
    }

    private static RefInfo ref(String full, String hash) {
        return new RefInfo(full, RefInfo.shortNameOf(full), hash);
    }

    private static RefSnapshot snapshot(String current, String head, List<RefInfo> remotes, List<String> remoteNames) {
        return new RefSnapshot(current, head, List.of(ref("refs/heads/main", head)), remotes, List.of(),
                Map.of(), List.of(), remoteNames);
    }

    // ------------------------------------------------------------------
    // The builder
    // ------------------------------------------------------------------

    @Test
    void theBuiltPageCarriesTheSnapshotItWasBuiltFrom() {
        RefSnapshot refs = snapshot("main", "a", List.of(), List.of("origin"));
        GraphPage page = GraphPageBuilder.build(GraphPageBuilder.Input.of(
                List.of(c("a", 10, List.of())), true, GraphOrder.CHRONOLOGICAL, 50, false, refs, List.of(), null));

        assertSame(refs, page.refs());
        assertEquals(RefSnapshot.EMPTY, new GraphPage(List.of(), false).refs(), "a page built without refs has none");
    }

    /** The upstreams the rows fold their chips by come from the snapshot: remote-tracking ones only. */
    @Test
    void theRowsUpstreamsAreTheSnapshots() {
        RefSnapshot refs = new RefSnapshot("main", "a", List.of(ref("refs/heads/main", "a"), ref("refs/heads/b", "a")),
                List.of(ref("refs/remotes/origin/main", "a")), List.of(),
                Map.of("refs/heads/main", "refs/remotes/origin/main", "refs/heads/b", "refs/heads/main"),
                List.of(), List.of("origin"));
        GraphPage page = GraphPageBuilder.build(GraphPageBuilder.Input.of(
                List.of(c("a", 10, refs.refs())), true, GraphOrder.CHRONOLOGICAL, 50, false, refs, List.of(), null));

        assertEquals(Map.of("main", "origin/main"), page.rows().get(0).getCommit().getUpstreams());
    }

    /** A remote's symbolic HEAD handed in anyway neither starts the walk nor decorates a row. */
    @Test
    void aSymbolicRemoteHeadNeitherStartsTheWalkNorDecorates() {
        List<RefInfo> refs = List.of(ref("refs/heads/main", "m1"),
                ref("refs/remotes/origin/main", "o1"), ref("refs/remotes/origin/HEAD", "o1"),
                ref("refs/remotes/origin/feature/HEAD", "f1"));
        GraphVisibility hideOriginMain = new GraphVisibility(Set.of("refs/remotes/origin/main"), null);

        assertEquals(List.of("m1", "f1"), GraphPageBuilder.startPoints(refs, hideOriginMain, "m1"),
                "origin/HEAD does not walk hidden origin/main back in; a branch ending in /HEAD is a branch");
        var chips = GraphPageBuilder.decorations(refs, GraphVisibility.ALL, "m1", "refs/heads/main");
        assertEquals(List.of("refs/remotes/origin/main"), chips.get("o1").stream().map(RefInfo::fullName).toList());
        assertEquals(List.of("refs/remotes/origin/feature/HEAD"),
                chips.get("f1").stream().map(RefInfo::fullName).toList());
    }

    /** The hidden stashes stay in the snapshot; the page is handed only the others. */
    @Test
    void theShownStashesLeaveOutTheHiddenOnes() {
        StashEntry kept = new StashEntry(0, "s0", "a", "WIP on main: one", "T", null);
        StashEntry hidden = new StashEntry(1, "s1", "a", "WIP on main: two", "T", null);
        RefSnapshot refs = snapshot("main", "a", List.of(), List.of()).withStashes(List.of(kept, hidden));

        assertEquals(List.of(kept), GraphPageBuilder.shownStashes(refs, Set.of("s1")));
        assertEquals(List.of(kept, hidden), refs.stashes());
    }

    // ------------------------------------------------------------------
    // Lane colours
    // ------------------------------------------------------------------

    /** team/core/topic seeds its lane like topic once team/core is known to be a remote. */
    @Test
    void aRemoteBranchSeedsItsColourByTheLongestRemote() {
        List<RefInfo> remote = List.of(ref("refs/remotes/team/core/topic", "x"));
        List<RefInfo> local = List.of(ref("refs/heads/topic", "x"));

        List<CommitInfo> asRemote = List.of(c("x", 10, remote));
        LaneAssigner.assign(asRemote, List.of("team/core"));
        List<CommitInfo> asLocal = List.of(c("x", 10, local));
        LaneAssigner.assign(asLocal, List.of("team/core"));
        List<CommitInfo> unknown = List.of(c("x", 10, remote));
        LaneAssigner.assign(unknown);

        assertEquals(asLocal.get(0).getColorIndex(), asRemote.get(0).getColorIndex());
        assertEquals(Math.floorMod("core/topic".hashCode(), LaneAssigner.PALETTE_SIZE), unknown.get(0).getColorIndex(),
                "with no remotes known the first segment is the remote");
    }

    // ------------------------------------------------------------------
    // The history window
    // ------------------------------------------------------------------

    /** A page's chips are drawn against its own snapshot's current branch - blank when detached. */
    @Test
    void aLoadedPageTakesItsBranchFromItsSnapshot() {
        GraphPage attached = new GraphPage(List.of(), false, snapshot("main", "a", List.of(), List.of()));
        GraphPage detached = new GraphPage(List.of(), false, snapshot("", "a", List.of(), List.of()));

        assertEquals("main", HistoryWindow.Loaded.of(attached).branch());
        assertEquals("", HistoryWindow.Loaded.of(detached).branch());
        HistoryWindow.Walked walked = HistoryWindow.Walk.page(10).run(limit -> attached, () -> false);
        assertEquals("main", walked.loaded().branch(), "no second read of the current branch");
    }

    /**
     * A refresh whose page draws what the one on screen does leaves the page alone - but
     * hands its snapshot on, since refs the page does not draw (a new remote, a branch
     * whose tip is further down) can still have changed.
     */
    @Test
    void aRefreshThatDrawsTheSameStillHandsOnItsNewerSnapshot() {
        List<GitGraphRow> rows = List.of(new GitGraphRow(c("a", 10, List.of(ref("refs/heads/main", "a")))));
        RefSnapshot before = snapshot("main", "a", List.of(), List.of("origin"));
        RefSnapshot after = snapshot("main", "a", List.of(), List.of("origin", "upstream"));
        List<GraphPage> pages = new ArrayList<>(List.of(new GraphPage(rows, false, before),
                new GraphPage(rows, false, after)));
        List<RefSnapshot> handedOn = new ArrayList<>();
        List<HistoryWindow.Change> changes = new ArrayList<>();
        HistoryWindow window = new HistoryWindow(new HistoryWindow.Source() {
            @Override
            public HistoryWindow.Request load(HistoryWindow.Walk walk, Consumer<HistoryWindow.Walked> onLoaded,
                                              Consumer<String> onFailed, Runnable onDropped) {
                GraphPage next = pages.remove(0);
                onLoaded.accept(walk.run(limit -> next, () -> false));
                return () -> { };
            }

            @Override
            public HistoryWindow.Request reaches(String hash, Consumer<Boolean> onAnswer, Consumer<String> onFailed,
                                                Runnable onDropped) {
                onAnswer.accept(false);
                return () -> { };
            }
        }, new HistoryWindow.Listener() {
            @Override
            public void pageChanged(HistoryWindow.Change change, boolean first) {
                changes.add(change);
            }

            @Override
            public void refreshed(GraphPage page) {
                handedOn.add(page.refs());
            }
        }, new GraphHighlight(), () -> 50);

        window.refresh();
        window.refresh();

        assertEquals(List.of(HistoryWindow.Change.REFRESHED), changes, "the second page drew the same and was left");
        assertEquals(List.of(before, after), handedOn, "both snapshots were handed on");
        assertSame(before, window.refs(), "the page on screen keeps the snapshot it was drawn from");
        assertTrue(window.currentBranch().equals("main"));
        assertFalse(window.refs().remoteNames().contains("upstream"));
    }
}
