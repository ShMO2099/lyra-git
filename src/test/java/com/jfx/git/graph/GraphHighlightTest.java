package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which commits count as related to the selection.
 *
 * <p>The walk, not the drawing. Whether a related commit is actually drawn faded is
 * {@code GraphFadeRenderTest}, and whether clicking one does it is
 * {@code CommitFadeRobotTest}.
 */
class GraphHighlightTest {

    private static CommitInfo commit(String hash, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "Tester", "t@example.com",
                Instant.now(), List.of(parents), List.of());
    }

    private static List<GitGraphRow> rows(CommitInfo... commits) {
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) {
            rows.add(new GitGraphRow(c));
        }
        return rows;
    }

    /**
     * A trunk with a side branch that never merges:
     *
     * <pre>
     *   d   (tip of trunk)
     *   |    s2
     *   c    |
     *   |    s1
     *   b---'
     *   |
     *   a
     * </pre>
     */
    private static List<GitGraphRow> divergentHistory() {
        return rows(
                commit("d", "c"),
                commit("s2", "s1"),
                commit("c", "b"),
                commit("s1", "b"),
                commit("b", "a"),
                commit("a"));
    }

    // ------------------------------------------------------------------

    @Test
    void searchKeepsMatchesBrightAndDimsEverythingElse() {
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(divergentHistory());

        // Highlight-mode search is the one fade the pointer does not drive: exactly the
        // matches stay bright.
        highlight.search(Set.of("b", "s1"));
        assertTrue(highlight.isActive());
        assertFalse(highlight.isFaded("b"));
        assertFalse(highlight.isFaded("s1"));
        assertTrue(highlight.isFaded("a"), "anything that did not match dims");
        assertFalse(highlight.isFaded(null),
                "synthetic rows (WIP, stashes) never take part in the fade");

        // Clearing hands the fade back to nothing rather than freezing the search set.
        highlight.clear();
        assertFalse(highlight.isActive());
        assertFalse(highlight.isFaded("a"));
    }








    // ------------------------------------------------------------------
    // Shapes that break a naive walk
    // ------------------------------------------------------------------





    // ------------------------------------------------------------------
    // Hovering a branch ref
    // ------------------------------------------------------------------

    @Test
    void hoveringABranchKeepsItsAncestryAndFadesTheRest() {
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(divergentHistory());

        highlight.hoverBranch("s2");

        assertFalse(highlight.isFaded("s2"), "the branch tip itself");
        assertFalse(highlight.isFaded("s1"), "its parent");
        assertFalse(highlight.isFaded("b"), "the fork point is on the branch");
        assertFalse(highlight.isFaded("a"), "and everything below it");
        assertTrue(highlight.isFaded("c"), "trunk built past the fork is not on s2");
        assertTrue(highlight.isFaded("d"), "nor is the trunk tip");
    }

    @Test
    void hoveringDoesNotReachDescendants() {
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(divergentHistory());

        highlight.hoverBranch("c");

        assertFalse(highlight.isFaded("c"), "the tip");
        assertTrue(highlight.isFaded("d"),
                "d is built on c, so it belongs to whatever branch points at d - not "
                        + "to the one being hovered");
    }


    @Test
    void hoveringSomethingOffThePageFadesNothing() {
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(divergentHistory());

        highlight.hoverBranch("below-the-loading-horizon");

        assertFalse(highlight.isActive(),
                "a tip the page has not loaded cannot be walked, and fading the whole "
                        + "graph would be a worse answer than fading none of it");
        assertFalse(highlight.isFaded("d"));
    }

    @Test
    void clearingDropsTheHoverToo() {
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(divergentHistory());
        highlight.hoverBranch("s2");
        assertTrue(highlight.isActive());

        highlight.clear();

        assertFalse(highlight.isActive());
        assertFalse(highlight.isFaded("c"), "nothing fades once the graph is reset");
    }

    // ------------------------------------------------------------------
    // Hovering a branch by name, from the sidebar
    // ------------------------------------------------------------------

    private static CommitInfo tipOf(String branch, String hash, String... parents) {
        return new CommitInfo(hash, "subject " + hash, "Tester", "t@example.com",
                Instant.now(), List.of(parents),
                List.of(new RefInfo("refs/heads/" + branch, branch, hash)));
    }

    /** The same divergent history, with a branch ref on each tip. */
    private static List<GitGraphRow> namedHistory() {
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : List.of(
                tipOf("main", "d", "c"),
                tipOf("side", "s2", "s1"),
                commit("c", "b"),
                commit("s1", "b"),
                commit("b", "a"),
                commit("a"))) {
            rows.add(new GitGraphRow(c));
        }
        return rows;
    }

    @Test
    void aBranchNameFindsItsTipAndFadesTheRest() {
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(namedHistory());

        highlight.hoverBranchNamed("side");

        assertFalse(highlight.isFaded("s2"), "the tip the name points at");
        assertFalse(highlight.isFaded("s1"));
        assertFalse(highlight.isFaded("b"), "shared history is on the branch");
        assertTrue(highlight.isFaded("c"), "the trunk past the fork is not");
        assertTrue(highlight.isFaded("d"));
    }

    @Test
    void aNameNotOnThePageFadesNothing() {
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(namedHistory());

        highlight.hoverBranchNamed("a-branch-below-the-horizon");

        assertFalse(highlight.isActive(),
                "the branch is real but its tip has not been loaded, and fading the "
                        + "whole graph is a worse answer than fading none of it");
    }

    /** Null is how the sidebar says the pointer left, so it has to end the preview. */
    @Test
    void aNullNameEndsTheHover() {
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(namedHistory());
        highlight.hoverBranchNamed("side");
        assertTrue(highlight.isActive(), "sanity: it faded");

        highlight.hoverBranchNamed(null);

        assertFalse(highlight.isActive());
        assertFalse(highlight.isFaded("d"));
    }

    /**
     * A merge tip holds both sides of what it merged.
     *
     * <p>Kept from the selection fade this replaced: the property is about the walk, not
     * about what triggers it, and a walk that followed only the first parent would fade
     * half of what the branch actually contains.
     */
    @Test
    void hoveringAMergeTipReachesBothSidesOfTheMerge() {
        List<GitGraphRow> merged = rows(
                commit("m", "c", "s1"),
                commit("c", "b"),
                commit("s1", "b"),
                commit("b"));
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(merged);

        highlight.hoverBranch("m");

        assertFalse(highlight.isFaded("c"));
        assertFalse(highlight.isFaded("s1"), "the second parent counts as much as the first");
        assertFalse(highlight.isFaded("b"));
    }

    /** Both sides of a diamond lead back to one commit; the walk must not loop. */
    @Test
    void aDiamondIsWalkedOnceRatherThanForever() {
        List<GitGraphRow> diamond = rows(
                commit("m", "l", "r"),
                commit("l", "base"),
                commit("r", "base"),
                commit("base"));
        GraphHighlight highlight = new GraphHighlight();
        highlight.attach(diamond);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> highlight.hoverBranch("m"));

        assertFalse(highlight.isFaded("base"), "the shared base is on the branch");
        assertFalse(highlight.isFaded("l"));
        assertFalse(highlight.isFaded("r"));
    }
}
