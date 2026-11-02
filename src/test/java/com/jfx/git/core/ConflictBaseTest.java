package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The base-branch patterns and the choice between the branches they match. */
class ConflictBaseTest {

    @Test
    void aSingleBranchMatchesOnlyItself() {
        assertTrue(ConflictBase.matches("origin/main", "origin/main"));
        assertFalse(ConflictBase.matches("origin/main", "origin/main2"));
        assertFalse(ConflictBase.matches("origin/main", "upstream/main"));
    }

    @Test
    void oneStarStaysInASegmentAndTwoCrossThem() {
        assertTrue(ConflictBase.matches("origin/*", "origin/main"));
        assertFalse(ConflictBase.matches("origin/*", "origin/release/1.0"));
        assertTrue(ConflictBase.matches("origin/**", "origin/release/1.0"));
        assertTrue(ConflictBase.matches("**/main", "upstream/main"));
        assertTrue(ConflictBase.matches("origin/release-?", "origin/release-2"));
        assertFalse(ConflictBase.matches("origin/release-?", "origin/release-10"));
    }

    @Test
    void anExclusionWinsWhicheverLineItIsOn() {
        String patterns = "!origin/wip/**\norigin/**";
        assertTrue(ConflictBase.matches(patterns, "origin/main"));
        assertFalse(ConflictBase.matches(patterns, "origin/wip/spike"));
        assertFalse(ConflictBase.matches("origin/**\n!origin/wip/**", "origin/wip/spike"));
    }

    @Test
    void blankLinesCommentsAndDotsAreNotPatterns() {
        String patterns = "\n# the release lines\n  origin/release.1  \r\n";
        assertTrue(ConflictBase.matches(patterns, "origin/release.1"));
        assertFalse(ConflictBase.matches(patterns, "origin/releasex1"), "a dot is a dot");
        assertFalse(ConflictBase.matches("", "origin/main"), "no patterns, no bases");
        assertFalse(ConflictBase.matches("!origin/main", "origin/main"));
    }

    @Test
    void candidatesLeaveOutTheBranchItselfItsUpstreamAndRemoteHeads() {
        List<String> remotes = List.of("origin/HEAD", "origin/main", "origin/feature", "origin/develop",
                "fork/feature", "upstream/main");
        assertEquals(List.of("origin/main", "origin/develop"),
                ConflictBase.candidates(remotes, "origin/**", "feature", "origin/feature", "origin/main"));
        assertEquals(List.of("origin/develop", "origin/main", "upstream/main"),
                ConflictBase.candidates(remotes, "**/main\norigin/develop", "feature", null, null),
                "sorted by name with nothing preferred, fork/feature left out as this branch's copy");
    }

    @Test
    void candidatesAreCapped() {
        List<String> remotes = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> String.format("origin/b%02d", i)).toList();
        List<String> found = ConflictBase.candidates(remotes, "origin/**", "x", null, "origin/b42");
        assertEquals(ConflictBase.MAX_CANDIDATES, found.size());
        assertEquals("origin/b42", found.get(0), "the remote's default branch is never the one cut");
    }

    @Test
    void theNearestMergeBaseWinsAndATieGoesToTheDefaultBranch() {
        Map<String, Integer> distance = Map.of("origin/develop", 3, "origin/main", 7, "origin/other", -1);
        assertEquals("origin/develop", ConflictBase.choose(
                List.of("origin/main", "origin/develop", "origin/other"), distance::get, "origin/main"));

        Map<String, Integer> tied = Map.of("origin/develop", 4, "origin/main", 4);
        assertEquals("origin/main", ConflictBase.choose(
                List.of("origin/develop", "origin/main"), tied::get, "origin/main"));
        assertEquals("origin/develop", ConflictBase.choose(
                List.of("origin/develop", "origin/main"), tied::get, null), "otherwise the first");

        assertNull(ConflictBase.choose(List.of("origin/other"), distance::get, null),
                "no shared history, no base");
    }
}
