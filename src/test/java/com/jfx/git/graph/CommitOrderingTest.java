package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommitOrderingTest {

    @Test
    @DisplayName("chronological order interleaves branches; grouping keeps them together")
    void orderDecidesTheShape() {
        // Two branches committed alternately. Grouped, one branch's commits run
        // contiguously; chronologically, they alternate exactly as they were written.
        var base = commit("base", 0);
        var a2 = commit("a2", 40, "a1");
        var a1 = commit("a1", 20, "base");
        var b2 = commit("b2", 30, "b1");
        var b1 = commit("b1", 10, "base");

        var input = java.util.List.of(a2, b2, a1, b1, base);

        var grouped = CommitOrdering.canonical(input, "a2", GraphOrder.BY_BRANCH)
                .stream().map(CommitInfo::getHash).toList();
        var chronological = CommitOrdering.canonical(input, "a2", GraphOrder.CHRONOLOGICAL)
                .stream().map(CommitInfo::getHash).toList();

        assertEquals(java.util.List.of("a2", "a1", "b2", "b1", "base"), grouped,
                "grouping should follow one branch to its end before starting the other");
        assertEquals(java.util.List.of("a2", "b2", "a1", "b1", "base"), chronological,
                "chronological should interleave the two branches by date");
    }

    /** A commit {@code minutes} after a fixed epoch, with the given parents. */
    private static CommitInfo commit(String hash, int minutes, String... parents) {
        return new CommitInfo(hash, hash, "A", "a@example.com",
                java.time.Instant.parse("2026-01-01T00:00:00Z")
                        .plus(minutes, java.time.temporal.ChronoUnit.MINUTES),
                java.util.List.of(parents), java.util.List.of());
    }

    /** Every commit shares a timestamp unless one is given, so ties are the default case. */
    private static CommitInfo c(String hash, long epoch, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "T", "t@e.com",
                Instant.ofEpochSecond(epoch), List.of(parents), List.of());
    }

    private static List<String> hashes(List<CommitInfo> commits) {
        return commits.stream().map(CommitInfo::getHash).toList();
    }

    @Test
    void everyCommitComesBeforeItsParents() {
        // c -> b -> a
        var ordered = CommitOrdering.canonical(List.of(
                c("a", 100), c("c", 300, "b"), c("b", 200, "a")));
        assertEquals(List.of("c", "b", "a"), hashes(ordered));
    }

    @Test
    void theOrderDoesNotDependOnTheInputOrder() {
        List<CommitInfo> commits = new ArrayList<>(List.of(
                c("a", 100), c("b", 200, "a"), c("c", 300, "b"),
                c("d", 250, "a"), c("e", 275, "d")));

        List<String> first = hashes(CommitOrdering.canonical(commits));
        for (int i = 0; i < 6; i++) {
            Collections.shuffle(commits);
            assertEquals(first, hashes(CommitOrdering.canonical(commits)),
                    "shuffling the input must not change the drawn order");
        }
    }

    /**
     * The whole reason this class exists: lane assignment is a pure function of order, so
     * two engines that order differently draw different graphs of the same history.
     */
    @Test
    void identicalHistoryOrdersIdenticallyWhicheverWalkProducedIt() {
        // The same five commits as two different walks might hand them over.
        List<CommitInfo> asTopo = List.of(
                c("e", 500, "c"), c("c", 300, "a"), c("d", 400, "b"), c("b", 200, "a"), c("a", 100));
        List<CommitInfo> asDate = List.of(
                c("e", 500, "c"), c("d", 400, "b"), c("c", 300, "a"), c("b", 200, "a"), c("a", 100));

        assertEquals(hashes(CommitOrdering.canonical(asTopo)),
                hashes(CommitOrdering.canonical(asDate)));
    }

    @Test
    void tiedTimestampsStillOrderDeterministically() {
        var one = CommitOrdering.canonical(List.of(c("bbb", 100), c("aaa", 100), c("ccc", 100)));
        var two = CommitOrdering.canonical(List.of(c("ccc", 100), c("bbb", 100), c("aaa", 100)));
        assertEquals(hashes(one), hashes(two));
    }

    // ------------------------------------------------------------------
    // Compactness and lane 0
    // ------------------------------------------------------------------

    /**
     * Taking the newest ready commit each time is a valid topological order but
     * interleaves every branch, holding a lane open for each. Following first-parent keeps
     * a branch on one lane until it ends.
     */
    @Test
    void followingFirstParentKeepsTheGraphNarrow() {
        // A trunk of ten commits, each with a one-commit feature branch hanging off it.
        List<CommitInfo> commits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String parent = i == 0 ? null : "m" + (i - 1);
            commits.add(parent == null ? c("m" + i, 1000 + i) : c("m" + i, 1000 + i, parent));
            commits.add(c("f" + i, 1000 + i, "m" + i));   // fork, same timestamp as trunk
        }

        var ordered = CommitOrdering.canonical(commits, "m9");
        LaneAssigner.assign(ordered);

        int maxLane = ordered.stream().mapToInt(CommitInfo::getLane).max().orElse(0);
        assertTrue(maxLane <= 2,
                "ten independent forks should not each hold their own lane, got " + maxLane);
    }

    /** The checked-out branch belongs in lane 0, even against same-second forks. */
    @Test
    void headStartsTheOrderSoTheTrunkGetsLaneZero() {
        // trunk m0 <- m1 <- m2, with forks off m0 and m1, all at the same instant.
        List<CommitInfo> commits = List.of(
                c("m0", 100), c("m1", 100, "m0"), c("m2", 100, "m1"),
                c("fa", 100, "m0"), c("fb", 100, "m1"));

        var ordered = CommitOrdering.canonical(commits, "m2");
        assertEquals("m2", ordered.get(0).getHash(), "HEAD is drawn first");

        LaneAssigner.assign(ordered);
        var laneOf = new java.util.HashMap<String, Integer>();
        ordered.forEach(x -> laneOf.put(x.getHash(), x.getLane()));

        assertEquals(0, laneOf.get("m2"));
        assertEquals(0, laneOf.get("m1"));
        assertEquals(0, laneOf.get("m0"));
        assertTrue(laneOf.get("fa") > 0, "a fork takes a side lane");
        assertTrue(laneOf.get("fb") > 0);
    }

    @Test
    void anUnknownHeadIsIgnoredRatherThanFatal() {
        var ordered = CommitOrdering.canonical(
                List.of(c("a", 100), c("b", 200, "a")), "not-in-this-page");
        assertEquals(List.of("b", "a"), hashes(ordered));
    }

    // ------------------------------------------------------------------
    // Windowing and robustness
    // ------------------------------------------------------------------

    /** A page is a window; parents below it have not been fetched and must not constrain. */
    @Test
    void parentsOutsideTheWindowDoNotBlockOrdering() {
        var ordered = CommitOrdering.canonical(List.of(
                c("b", 200, "a-not-fetched"), c("c", 300, "b")));
        assertEquals(List.of("c", "b"), hashes(ordered));
    }

    @Test
    void everyCommitSurvives() {
        List<CommitInfo> commits = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            commits.add(i == 0 ? c("c" + i, 1000) : c("c" + i, 1000 + i, "c" + (i - 1)));
        }
        assertEquals(50, CommitOrdering.canonical(commits).size());
    }

    @Test
    void emptyAndSingletonInputsAreHandled() {
        assertTrue(CommitOrdering.canonical(List.of()).isEmpty());
        assertTrue(CommitOrdering.canonical(null).isEmpty());
        assertEquals(1, CommitOrdering.canonical(List.of(c("a", 1))).size());
    }

    /** A merge's second parent belongs to the branch it came from, not to this line. */
    @Test
    void aMergeStillPrecedesBothParents() {
        var ordered = hashes(CommitOrdering.canonical(List.of(
                c("merge", 400, "main", "side"),
                c("main", 300, "base"), c("side", 200, "base"), c("base", 100)), "merge"));

        assertEquals("merge", ordered.get(0));
        assertTrue(ordered.indexOf("merge") < ordered.indexOf("main"));
        assertTrue(ordered.indexOf("merge") < ordered.indexOf("side"));
        assertTrue(ordered.indexOf("main") < ordered.indexOf("base"));
        assertTrue(ordered.indexOf("side") < ordered.indexOf("base"));
    }
}
