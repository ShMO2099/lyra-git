package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CommitOrdering#page}: the rows of a page are a prefix of every bigger page, and
 * no tip is starved off the first page by a long trunk.
 *
 * <p>Each history here is handed over the way an engine's walk hands it over - newest
 * committer date first - because that is the only input the page ever sees.
 */
class CommitOrderingPagingTest {

    private static CommitInfo c(String hash, long committed, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "T", "t@e.com",
                Instant.ofEpochSecond(committed), Instant.ofEpochSecond(committed),
                List.of(parents), List.of());
    }

    /** As a date walk returns them: newest committer date first, hash breaking ties. */
    private static List<CommitInfo> walkOrder(List<CommitInfo> commits) {
        List<CommitInfo> walk = new ArrayList<>(commits);
        walk.sort(Comparator.comparing(CommitInfo::getCommitterDate).reversed()
                .thenComparing(CommitInfo::getHash));
        return walk;
    }

    private static List<String> hashes(List<CommitInfo> commits) {
        return commits.stream().map(CommitInfo::getHash).toList();
    }

    /**
     * A busy repository: a linear trunk with a merged side branch every 25 commits and a
     * few long-lived branches forking off it, all interleaved in time.
     */
    private static List<CommitInfo> busyHistory(int trunkLength) {
        List<CommitInfo> commits = new ArrayList<>();
        long t = 1_000_000;
        String prev = null;
        for (int i = 0; i < trunkLength; i++) {
            t += 60;
            String hash = String.format("m%05d", i);
            if (i > 0 && i % 25 == 0) {
                // A two-commit side branch off the previous trunk commit, merged here.
                String s1 = String.format("s%05da", i);
                String s2 = String.format("s%05db", i);
                commits.add(c(s1, t - 50, prev));
                commits.add(c(s2, t - 40, s1));
                commits.add(c(hash, t, prev, s2));
            } else {
                commits.add(prev == null ? c(hash, t) : c(hash, t, prev));
            }
            if (i % 300 == 150) {
                // A long-lived branch, forked here and committed to much later.
                String fork = hash;
                for (int k = 0; k < 5; k++) {
                    String f = String.format("f%05d_%d", i, k);
                    commits.add(c(f, t + 30_000L + k * 7, fork));
                    fork = f;
                }
            }
            prev = hash;
        }
        return commits;
    }

    @Test
    void aBiggerPageStartsWithExactlyTheSmallerPage() {
        List<CommitInfo> walk = walkOrder(busyHistory(2400));
        String head = String.format("m%05d", 2399);

        for (GraphOrder order : GraphOrder.values()) {
            var small = CommitOrdering.page(walk, true, head, order, 700);
            var big = CommitOrdering.page(walk, true, head, order, 1400);
            assertEquals(700, small.commits().size());
            assertTrue(small.hasMore());
            assertEquals(hashes(small.commits()), hashes(big.commits()).subList(0, 700),
                    order + ": loading more must not reshuffle the rows already on screen");
        }
    }

    /**
     * What an engine really does: walks a different amount of history for each page. The
     * page must not depend on how much further the walk went.
     */
    @Test
    void thePageDoesNotDependOnHowFarTheWalkWent() {
        List<CommitInfo> all = walkOrder(busyHistory(2400));
        String head = String.format("m%05d", 2399);
        int limit = 600;

        var exact = CommitOrdering.page(all.subList(0, CommitOrdering.fetchCount(limit)), false,
                head, GraphOrder.BY_BRANCH, limit);
        var generous = CommitOrdering.page(all, true, head, GraphOrder.BY_BRANCH, limit);

        assertEquals(limit, exact.commits().size(), "the prescribed fetch must be enough for a full page");
        assertEquals(hashes(generous.commits()), hashes(exact.commits()));
    }

    @Test
    void lanesOfTheSmallerPageSurviveLoadingMore() {
        List<CommitInfo> walk = walkOrder(busyHistory(1500));
        String head = String.format("m%05d", 1499);

        List<CommitInfo> small = CommitOrdering.page(walk, true, head, GraphOrder.BY_BRANCH, 600).commits();
        List<Integer> smallLanes = new ArrayList<>();
        LaneAssigner.assign(small);
        small.forEach(x -> smallLanes.add(x.getLane()));

        List<CommitInfo> big = CommitOrdering.page(walk, true, head, GraphOrder.BY_BRANCH, 1200).commits();
        LaneAssigner.assign(big);
        for (int i = 0; i < smallLanes.size(); i++) {
            assertEquals(smallLanes.get(i), big.get(i).getLane(), "row " + i + " changed lane");
        }
    }

    /**
     * G2. A long, unbranched trunk used to be followed through the whole window before any
     * other tip started, so a branch pushed today but forked long ago was not on the page.
     */
    @Test
    void aFreshBranchForkedLongAgoIsOnTheFirstPage() {
        List<CommitInfo> commits = new ArrayList<>();
        long t = 5_000_000;
        String prev = null;
        for (int i = 0; i < 2000; i++) {
            t += 60;
            String hash = String.format("m%05d", i);
            commits.add(prev == null ? c(hash, t) : c(hash, t, prev));
            prev = hash;
        }
        // Forked from commit 100 - nineteen hundred trunk commits ago - and pushed now.
        commits.add(c("feature-tip", t + 5, "m00100"));
        List<CommitInfo> walk = walkOrder(commits);

        var page = CommitOrdering.page(walk, true, "m01999", GraphOrder.BY_BRANCH, 1000);
        int at = hashes(page.commits()).indexOf("feature-tip");

        assertTrue(at >= 0, "the new branch must be on the first page");
        assertTrue(at <= CommitOrdering.CHUNK + CommitOrdering.TIE_SPAN,
                "and no further down than one ordering chunk, was row " + at);
        assertEquals("m01999", page.commits().get(0).getHash(), "HEAD still comes first");
    }

    /** G4. The uncommitted row hangs off HEAD, so HEAD must be on the page however old. */
    @Test
    void anOldHeadIsStillOnThePage() {
        List<CommitInfo> commits = new ArrayList<>();
        long t = 7_000_000;
        String prev = null;
        for (int i = 0; i < 3000; i++) {
            t += 60;
            String hash = String.format("m%05d", i);
            commits.add(prev == null ? c(hash, t) : c(hash, t, prev));
            prev = hash;
        }
        List<CommitInfo> walk = walkOrder(commits);

        String head = "m00200";   // an old tag checked out
        var page = CommitOrdering.page(walk, true, head, GraphOrder.BY_BRANCH, 100);

        assertTrue(hashes(page.commits()).contains(head));
        assertEquals(head, page.commits().get(page.commits().size() - 1).getHash(),
                "the page grows just far enough to include HEAD");
        assertTrue(page.hasMore());
    }

    /** G11. The walk selects by committer date, so the order must sort by it too. */
    @Test
    void orderingUsesTheCommitterDateNotTheAuthorDate() {
        // "rebased" was authored long ago but committed (rebased) most recently.
        var base = c("base", 100);
        var old = new CommitInfo("rebased", "rebased", "A", "a@e", Instant.ofEpochSecond(150),
                Instant.ofEpochSecond(900), List.of("base"), List.of());
        var other = new CommitInfo("other", "other", "A", "a@e", Instant.ofEpochSecond(500),
                Instant.ofEpochSecond(500), List.of("base"), List.of());

        var ordered = CommitOrdering.canonical(List.of(base, old, other), null, GraphOrder.CHRONOLOGICAL);

        assertEquals(List.of("rebased", "other", "base"), hashes(ordered));
    }

    /**
     * Engines may list commits of the same second in either order. A chunk boundary never
     * falls inside such a tie, so both still agree on every page.
     */
    @Test
    void commitsOfTheSameSecondNeverStraddleAChunkBoundary() {
        List<CommitInfo> commits = new ArrayList<>();
        for (int i = 0; i < CommitOrdering.CHUNK + 40; i++) {
            // Everything from CHUNK-10 to CHUNK+10 shares one timestamp.
            long time = i < CommitOrdering.CHUNK - 10 ? 20_000 - i
                    : i <= CommitOrdering.CHUNK + 10 ? 10_000 : 5_000 - i;
            commits.add(c(String.format("x%05d", i), time));
        }
        List<CommitInfo> walkA = walkOrder(commits);
        List<CommitInfo> walkB = new ArrayList<>(walkA);
        // Reverse the tied run, as another engine might list it.
        int from = walkB.indexOf(commits.get(CommitOrdering.CHUNK - 10));
        java.util.Collections.reverse(walkB.subList(from, from + 21));

        int end = CommitOrdering.chunkEnd(walkA, 0, true);
        assertNotEquals(CommitOrdering.CHUNK, end, "the boundary moves past the tie");
        assertEquals(hashes(CommitOrdering.page(walkA, true, null, GraphOrder.BY_BRANCH, CommitOrdering.CHUNK).commits()),
                hashes(CommitOrdering.page(walkB, true, null, GraphOrder.BY_BRANCH, CommitOrdering.CHUNK).commits()));
    }

    @Test
    void aShortWalkThatEndedIsOnePageWithNothingMore() {
        List<CommitInfo> walk = walkOrder(List.of(c("a", 1), c("b", 2, "a"), c("c", 3, "b")));
        var page = CommitOrdering.page(walk, true, "c", GraphOrder.BY_BRANCH, 3);
        assertEquals(List.of("c", "b", "a"), hashes(page.commits()));
        assertFalse(page.hasMore());

        var cut = CommitOrdering.page(walk, true, "c", GraphOrder.BY_BRANCH, 2);
        assertEquals(List.of("c", "b"), hashes(cut.commits()));
        assertTrue(cut.hasMore());
    }
}
