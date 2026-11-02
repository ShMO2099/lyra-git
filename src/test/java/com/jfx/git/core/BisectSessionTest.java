package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BisectSession}'s own bookkeeping and midpoint choice, independent of either
 * engine's history walk - {@link GitServiceTest} and {@link NativeGitBackendTest} cover
 * the two walks feeding it real candidate lists and prove the whole thing converges on
 * a real repository; this is the pure logic underneath both.
 */
class BisectSessionTest {

    @Test
    void isNotActiveUntilStarted() {
        BisectSession session = new BisectSession();
        assertFalse(session.isActive());
    }

    @Test
    void startRemembersTheOriginalPositionAndActivates() {
        BisectSession session = new BisectSession();
        session.start("main");

        assertTrue(session.isActive());
        assertEquals("main", session.originalPosition());
    }

    @Test
    void resetClearsEverythingIncludingTheOriginalPosition() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markGood("g1");
        session.markBad("b1");

        session.reset();

        assertFalse(session.isActive());
        assertNull(session.originalPosition());
        assertFalse(session.hasBothEndpoints());
    }

    @Test
    void hasBothEndpointsIsFalseUntilAtLeastOneGoodAndOneBadAreMarked() {
        BisectSession session = new BisectSession();
        session.start("main");
        assertFalse(session.hasBothEndpoints());

        session.markBad("b1");
        assertFalse(session.hasBothEndpoints(), "a bad mark alone is not enough");

        session.markGood("g1");
        assertTrue(session.hasBothEndpoints());
    }

    @Test
    void pickNextReturnsTheMiddleOfTheCandidateList() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");

        // pickNext removes `bad` itself before choosing, so among {a,b,c,d,bad} the
        // remaining four {a,b,c,d} have their midpoint at index 4/2=2 -> "c".
        String next = session.pickNext(List.of("a", "b", "c", "d", "bad"));

        assertEquals("c", next);
    }

    @Test
    void pickNextReturnsNullWhenNothingRemainsToTest() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");

        // Only the bad commit itself is in range: nothing left to test, bad is the answer.
        String next = session.pickNext(List.of("bad"));

        assertNull(next);
    }

    @Test
    void pickNextSkipsCommitsMarkedSkipped() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");
        session.skip("b"); // would otherwise be the midpoint of {a,b,c}

        String next = session.pickNext(List.of("a", "b", "c", "bad"));

        assertNotEquals("b", next);
        assertTrue(List.of("a", "c").contains(next));
    }

    @Test
    void aSkippedCommitIsUnskippedIfLaterMarkedGood() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.skip("x");
        session.markBad("bad");

        session.markGood("x");

        // No direct getter for "is skipped", so this is proven indirectly: if marking
        // "x" good had not cleared its skip, pickNext would drop it from {x, bad} and
        // find nothing left to test (bad is removed too) - reporting "done" one step
        // too early instead of offering "x" as the only remaining candidate.
        assertEquals("x", session.pickNext(List.of("x", "bad")));
    }

    @Test
    void currentCandidateRoundTrips() {
        BisectSession session = new BisectSession();
        session.start("main");
        assertNull(session.currentCandidate());

        session.setCurrentCandidate("abc123");
        assertEquals("abc123", session.currentCandidate());
    }

    @Test
    void goodHashesAndBadHashReflectWhatWasMarked() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markGood("g1");
        session.markGood("g2");
        session.markBad("b1");

        assertEquals(java.util.Set.of("g1", "g2"), session.goodHashes());
        assertEquals("b1", session.badHash());
    }

    @Test
    void markingBadReplacesAnyPreviousBadMark() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("first-bad");
        session.markBad("second-bad");

        assertEquals("second-bad", session.badHash());
    }

    // ------------------------------------------------------------------
    // N10e. Taking git's own ranking when the engine can supply one
    // ------------------------------------------------------------------

    /**
     * {@code rev-list --bisect-all} hands back the range already ordered by how good a
     * bisection point each commit is, so the best one is simply the first - no midpoint
     * arithmetic, and on a branchy history a better split than the flat midpoint gives.
     */
    @Test
    void pickBestTakesTheHeadOfARankedList() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");

        assertEquals("best", session.pickBest(List.of("best", "second", "third")));
    }

    /**
     * The ranking does not know what has been skipped, so the best candidate may be one
     * already found untestable. The next entry down is the next-best bisection point.
     */
    @Test
    void pickBestSkipsPastAnythingAlreadyFoundUntestable() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");
        session.skip("best");
        session.skip("second");

        assertEquals("third", session.pickBest(List.of("best", "second", "third")));
    }

    /** The bad commit is in the range only because a good..bad range includes its endpoint. */
    @Test
    void pickBestNeverOffersTheBadCommitItself() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");

        assertEquals("other", session.pickBest(List.of("bad", "other")));
    }

    @Test
    void pickBestReturnsNullWhenNothingIsLeftToTest() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");
        session.skip("only");

        assertNull(session.pickBest(List.of("only", "bad")),
                "nothing left to test means bad is already the answer");
    }

    /** On a linear history git's ranking and the flat midpoint agree about correctness. */
    @Test
    void bothStrategiesRefuseTheSameCommits() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");
        session.skip("skipped");

        List<String> candidates = List.of("a", "skipped", "bad", "b");

        assertNotEquals("skipped", session.pickBest(candidates));
        assertNotEquals("bad", session.pickBest(candidates));
        assertNotEquals("skipped", session.pickNext(candidates));
        assertNotEquals("bad", session.pickNext(candidates));
    }

    // ------------------------------------------------------------------
    // O7: git's order of decisions
    // ------------------------------------------------------------------

    @Test
    void aBadMergeBaseMeansTheMarksAreReversed() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("old");
        session.markGood("new");

        var decision = session.decide(List.of("old"), List.of(), true);

        assertInstanceOf(BisectSession.MarksReversed.class, decision,
                "an empty range from reversed marks is not an answer");
    }

    @Test
    void anUntestedMergeBaseIsTestedFirst() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("sideGood");

        var decision = session.decide(List.of("base"), List.of("bad", "x", "y"), true);

        var next = assertInstanceOf(BisectSession.TestNext.class, decision);
        assertEquals("base", next.commit());
        assertTrue(next.mergeBase());

        session.markGood("base");
        var after = assertInstanceOf(BisectSession.TestNext.class,
                session.decide(List.of("base"), List.of("bad", "x", "y"), true));
        assertEquals("x", after.commit(), "once the merge base is good, the range is bisected");
    }

    @Test
    void onlySkippedCommitsLeftIsAmbiguous() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");
        session.skip("s1");
        session.skip("s2");
        session.skip("elsewhere");

        var decision = session.decide(List.of("good"), List.of("bad", "s1", "s2"), false);

        var ambiguous = assertInstanceOf(BisectSession.Ambiguous.class, decision);
        assertEquals(List.of("s1", "s2", "bad"), ambiguous.suspects(),
                "every skipped candidate, then the bad end - not a skipped commit outside the range");
    }

    @Test
    void noSkippedCommitsLeftIsAFoundAnswer() {
        BisectSession session = new BisectSession();
        session.start("main");
        session.markBad("bad");
        session.markGood("good");

        var found = assertInstanceOf(BisectSession.Found.class,
                session.decide(List.of("good"), List.of("bad"), true));
        assertEquals("bad", found.commit());
    }

    @Test
    void bestOfKeepsOnlyTheMaximalBases() {
        // a <- b <- c: c descends from both others.
        java.util.Map<String, java.util.Set<String>> ancestors = java.util.Map.of(
                "a", java.util.Set.of(), "b", java.util.Set.of("a"), "c", java.util.Set.of("a", "b"));

        List<String> best = BisectSession.bestOf(List.of("a", "c", "b", "c"),
                (x, y) -> ancestors.get(y).contains(x));

        assertEquals(List.of("c"), best);
    }

    // ------------------------------------------------------------------
    // O16: the session is on disk
    // ------------------------------------------------------------------

    @Test
    void aSessionIsReadBackFromItsGitDirectory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        BisectSession first = new BisectSession();
        first.sync(dir.toFile());
        first.start("main");
        first.markBad("b");
        first.markGood("g1");
        first.markGood("g2");
        first.skip("s");
        first.setCurrentCandidate("c");

        BisectSession second = new BisectSession();
        second.sync(dir.toFile());

        assertTrue(second.isActive());
        assertEquals("main", second.originalPosition());
        assertEquals("b", second.badHash());
        assertEquals(java.util.Set.of("g1", "g2"), second.goodHashes());
        assertEquals("c", second.currentCandidate());

        second.reset();
        first.sync(dir.toFile());
        assertFalse(first.isActive(), "a reset anywhere ends the session everywhere");
    }

    @Test
    void aBisectStartedOnTheCommandLineIsPickedUp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve("BISECT_START"), "feature\n");
        java.nio.file.Files.writeString(dir.resolve("BISECT_TERMS"), "new\nold\n");
        java.nio.file.Files.createDirectories(dir.resolve("refs/bisect"));
        java.nio.file.Files.writeString(dir.resolve("refs/bisect/new"), "bbbb\n");
        java.nio.file.Files.writeString(dir.resolve("refs/bisect/old-aaaa"), "aaaa\n");
        java.nio.file.Files.writeString(dir.resolve("packed-refs"),
                "# pack-refs with: peeled\ncccc refs/bisect/skip-cccc\n");

        BisectSession session = new BisectSession();
        session.sync(dir.toFile());

        assertTrue(session.isActive());
        assertEquals("feature", session.originalPosition());
        assertEquals("bbbb", session.badHash(), "custom terms are honoured");
        assertEquals(java.util.Set.of("aaaa"), session.goodHashes());

        session.reset();
        assertFalse(java.nio.file.Files.exists(dir.resolve("BISECT_START")),
                "ending it removes the command line's state as git bisect reset would");
    }
}
