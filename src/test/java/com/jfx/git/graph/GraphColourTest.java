package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A branch keeps its colour.
 *
 * <p>Colours were handed out by a counter from the top of the page, so one new commit or
 * branch above a branch moved its colour - and every colour below it - on the next
 * refresh. A named tip now takes its colour from its name.
 */
class GraphColourTest {

    private static CommitInfo c(String hash, long t, List<RefInfo> refs, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "T", "t@e.com",
                Instant.ofEpochSecond(t), List.of(parents), refs);
    }

    private static List<RefInfo> branch(String name) {
        return List.of(new RefInfo("refs/heads/" + name, name, ""));
    }

    private static int colourOf(List<CommitInfo> commits, String hash) {
        LaneAssigner.assign(commits);
        return commits.stream().filter(c -> c.getHash().equals(hash)).findFirst().orElseThrow().getColorIndex();
    }

    /** main and a feature branch, with optionally an unrelated new tip above them. */
    private static List<CommitInfo> history(boolean withNewTip) {
        List<CommitInfo> commits = new ArrayList<>();
        if (withNewTip) {
            commits.add(c("new", 90, branch("brand-new-thing"), "root"));
        }
        commits.add(c("main", 80, branch("main"), "base"));
        commits.add(c("feat", 70, branch("feature/login"), "base"));
        commits.add(c("base", 20, List.of(), "root"));
        commits.add(c("root", 10, List.of()));
        return commits;
    }

    @Test
    void aBranchKeepsItsColourWhenANewTipAppearsAboveIt() {
        int before = colourOf(history(false), "feat");
        int after = colourOf(history(true), "feat");
        assertEquals(before, after, "feature/login is the same colour either way");
        assertEquals(colourOf(history(false), "main"), colourOf(history(true), "main"), "and so is main");
    }

    @Test
    void aRemoteBranchMatchesItsLocalNamesake() {
        List<CommitInfo> local = List.of(c("x", 10, branch("topic")));
        List<CommitInfo> remote = List.of(c("x", 10, List.of(new RefInfo("refs/remotes/origin/topic", "origin/topic", ""))));
        assertEquals(colourOf(local, "x"), colourOf(remote, "x"), "origin/topic is drawn like topic");
    }

    /**
     * {@code origin/HEAD} sorts before {@code origin/main} ('H' &lt; 'm') but is a pointer at
     * a branch, not one: the lane is named by the branch, so it is drawn like {@code main}.
     * Read for its branch part, the pointer seeded the lane from the word "HEAD".
     */
    @Test
    void aRemotesSymbolicHeadDoesNotNameTheLane() {
        List<CommitInfo> local = List.of(c("x", 10, branch("main")));
        List<CommitInfo> remote = List.of(c("x", 10, List.of(
                new RefInfo("refs/remotes/origin/HEAD", "origin/HEAD", ""),
                new RefInfo("refs/remotes/origin/main", "origin/main", ""))));
        assertEquals(colourOf(local, "x"), colourOf(remote, "x"), "origin/main names the lane, not origin/HEAD");

        // A remote with a slash in its name, once it is known to be one.
        List<CommitInfo> team = List.of(c("x", 10, List.of(
                new RefInfo("refs/remotes/team/core/HEAD", "team/core/HEAD", ""),
                new RefInfo("refs/remotes/team/core/main", "team/core/main", ""))));
        LaneAssigner.assign(team, List.of("team/core"));
        assertEquals(colourOf(local, "x"), team.get(0).getColorIndex(), "team/core/main names it, not team/core/HEAD");
    }

    @Test
    void twoBranchesSideBySideAreNeverTheSameColour() {
        // Two names whose seeds collide, on adjacent lanes.
        String first = "a0";
        String second = null;
        int seed = Math.floorMod(first.hashCode(), LaneAssigner.PALETTE_SIZE);
        for (int i = 1; second == null; i++) {
            String candidate = "b" + i;
            if (Math.floorMod(candidate.hashCode(), LaneAssigner.PALETTE_SIZE) == seed) second = candidate;
        }
        List<CommitInfo> commits = List.of(
                c("one", 30, branch(first), "root"),
                c("two", 20, branch(second), "root"),
                c("root", 10, List.of()));
        LaneAssigner.assign(commits);
        assertNotEquals(commits.get(0).getLane(), commits.get(1).getLane(), "precondition: two lanes");
        assertNotEquals(commits.get(0).getColorIndex(), commits.get(1).getColorIndex(),
                "colliding names on neighbouring lanes still get different colours");
    }

    @Test
    void theUncommittedRowTakesHeadsBranchColour() {
        CommitInfo wip = GitGraphRow.uncommittedPseudoCommit("head");
        List<CommitInfo> withWip = new ArrayList<>(List.of(wip, c("head", 10, branch("main"))));
        int wipColour = colourOf(withWip, GitGraphRow.UNCOMMITTED_HASH);
        int headAlone = colourOf(new ArrayList<>(List.of(c("head", 10, branch("main")))), "head");
        assertEquals(headAlone, wipColour, "main is main's colour whether or not there are uncommitted changes");
    }
}
