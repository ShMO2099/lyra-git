package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which branch an unlabelled commit is said to be on.
 *
 * <p>Only tips carry chips, so this is the answer to "what is this commit part of" for
 * everything below one. The interesting property is <em>nearest</em>: a commit is usually
 * contained by several branches and naming it after the wrong one is worse than saying
 * nothing.
 */
class GhostBranchesTest {

    private static RefInfo local(String name) {
        return new RefInfo("refs/heads/" + name, name, "");
    }

    private static RefInfo remote(String name) {
        return new RefInfo("refs/remotes/" + name, name, "");
    }

    private static RefInfo tag(String name) {
        return new RefInfo("refs/tags/" + name, name, "");
    }

    private static CommitInfo commit(String hash, List<RefInfo> refs, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "Tester", "t@example.com",
                Instant.now(), List.of(parents), refs);
    }

    private static GhostBranches ghosts(CommitInfo... commits) {
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) {
            rows.add(new GitGraphRow(c));
        }
        return GhostBranches.of(rows);
    }

    // ------------------------------------------------------------------

    @Test
    void anEmptyPageNamesNothing() {
        assertNull(GhostBranches.EMPTY.branchFor("anything"));
        assertEquals(0, GhostBranches.of(List.of()).size());
    }

    @Test
    void aTipIsNamedForItsOwnBranch() {
        GhostBranches g = ghosts(commit("tip", List.of(local("main"))));
        assertEquals("main", g.branchFor("tip"));
    }

    @Test
    void aCommitBelowATipTakesThatTipsName() {
        GhostBranches g = ghosts(
                commit("c", List.of(local("main")), "b"),
                commit("b", List.of(), "a"),
                commit("a", List.of()));

        assertEquals("main", g.branchFor("b"));
        assertEquals("main", g.branchFor("a"), "the whole ancestry belongs to main");
    }

    /**
     * The property the whole class exists for. Both branches contain the fork, but one of
     * them is closer, and that is the useful answer.
     */
    @Test
    void aCommitTakesTheNameOfTheNearestTipThatReachesIt() {
        //   feature (1 step away from mid)      main (3 steps away)
        GhostBranches g = ghosts(
                commit("main-tip", List.of(local("main")), "main-2"),
                commit("main-2", List.of(), "main-1"),
                commit("main-1", List.of(), "mid"),
                commit("feature-tip", List.of(local("feature")), "mid"),
                commit("mid", List.of()));

        assertEquals("feature", g.branchFor("mid"),
                "feature's tip is one step away; main's is three");
    }

    @Test
    void aLocalBranchWinsATieWithItsRemote() {
        GhostBranches g = ghosts(
                commit("tip", List.of(remote("origin/main"), local("main")), "below"),
                commit("below", List.of()));

        assertEquals("main", g.branchFor("below"),
                "the local branch is the one being worked on");
    }

    @Test
    void aRemoteBranchNamesCommitsNoLocalBranchReaches() {
        GhostBranches g = ghosts(
                commit("tip", List.of(remote("origin/feature")), "below"),
                commit("below", List.of()));

        assertEquals("origin/feature", g.branchFor("below"));
    }

    @Test
    void aTagIsNotABranchAndNamesNothing() {
        GhostBranches g = ghosts(
                commit("tip", List.of(tag("v1.0")), "below"),
                commit("below", List.of()));

        assertNull(g.branchFor("below"), "a tag says when, not which branch");
        assertNull(g.branchFor("tip"));
    }

    @Test
    void aCommitNoBranchReachesIsLeftUnnamed() {
        // A dangling commit, reachable from nothing on the page.
        GhostBranches g = ghosts(
                commit("tip", List.of(local("main")), "below"),
                commit("below", List.of()),
                commit("orphan", List.of()));

        assertNull(g.branchFor("orphan"),
                "inventing a name would be worse than leaving it blank");
    }

    @Test
    void bothSidesOfAMergeAreNamed() {
        GhostBranches g = ghosts(
                commit("merge", List.of(local("main")), "left", "right"),
                commit("left", List.of()),
                commit("right", List.of()));

        assertEquals("main", g.branchFor("left"));
        assertEquals("main", g.branchFor("right"), "a merge reaches both parents");
    }

    @Test
    void aCommitOffThePageIsNotNamed() {
        GhostBranches g = ghosts(commit("tip", List.of(local("main")), "not-loaded"));

        assertNull(g.branchFor("not-loaded"),
                "a commit that is not drawn does not need a label");
    }

    @Test
    void aDiamondIsWalkedOnceRatherThanForever() {
        GhostBranches g = ghosts(
                commit("m", List.of(local("main")), "l", "r"),
                commit("l", List.of(), "base"),
                commit("r", List.of(), "base"),
                commit("base", List.of()));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertEquals("main", g.branchFor("base"));
        });
        assertEquals(4, g.size());
    }

    /** G13. origin/HEAD sorts before origin/main ('H' < 'm') but is not a branch. */
    @Test
    void aRemotesHeadPointerNeverNamesACommit() {
        GhostBranches g = ghosts(
                commit("tip", List.of(remote("origin/HEAD"), remote("origin/main")), "below"),
                commit("below", List.of()));
        assertEquals("origin/main", g.branchFor("below"));
    }
}
