package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which branch each commit belongs to, for commits that carry no ref of their own.
 *
 * <h2>What a ghost branch answers</h2>
 * Only a branch's tip wears a chip. Every commit below it is unlabelled, so the question
 * "which branch is this commit on" is answered by scrolling up until a chip appears — and
 * on an interleaved graph that is genuinely hard to do by eye. Hovering a commit should
 * simply say the name, faintly, in the place a real chip would be.
 *
 * <h2>Nearest, and why it is computed once</h2>
 * A commit is usually contained by several branches; the useful answer is the closest tip
 * that reaches it. That is a shortest-path question, so it is answered by walking
 * <em>outward from every tip at once</em> — a breadth-first sweep over parent edges where
 * the first tip to arrive at a commit is by construction the nearest one. One pass over
 * the page assigns every commit, rather than a fresh search on each hover.
 *
 * <p>Local branches are seeded before remote ones, so a commit reachable from both
 * {@code main} and {@code origin/main} at the same distance is named for the local branch
 * — which is the one the user is working on.
 */
public final class GhostBranches {

    /** Nothing known; every lookup misses. */
    public static final GhostBranches EMPTY = new GhostBranches(Map.of());

    private final Map<String, String> branchOf;

    private GhostBranches(Map<String, String> branchOf) {
        this.branchOf = branchOf;
    }

    /**
     * The nearest branch containing {@code hash}, or null when nothing on the page
     * reaches it.
     */
    public String branchFor(String hash) {
        return hash == null ? null : branchOf.get(hash);
    }

    /** How many commits were attributed; for tests. */
    public int size() {
        return branchOf.size();
    }

    /**
     * Attributes every commit on {@code rows} to its nearest containing branch.
     *
     * <p>Bounded to the page for the same reason the selection fade is: a commit below the
     * loading horizon is not drawn, so naming it would be work nobody sees.
     */
    public static GhostBranches of(List<GitGraphRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return EMPTY;
        }

        Map<String, CommitInfo> byHash = new HashMap<>();
        for (GitGraphRow row : rows) {
            CommitInfo commit = row.getCommit();
            if (commit != null) {
                byHash.put(commit.getHash(), commit);
            }
        }

        // Seed one frontier with every branch tip on the page, locals first so they win a
        // tie. A tag is not a branch and never names a commit here.
        Map<String, String> nearest = new HashMap<>();
        Deque<String> frontier = new ArrayDeque<>();
        seed(rows, nearest, frontier, true);
        seed(rows, nearest, frontier, false);

        // Breadth-first over parents: everything at distance n is settled before anything
        // at n+1, so the first name to reach a commit is the nearest one.
        while (!frontier.isEmpty()) {
            String hash = frontier.poll();
            CommitInfo commit = byHash.get(hash);
            if (commit == null) {
                continue;
            }
            String name = nearest.get(hash);
            for (String parent : commit.getParentHashes()) {
                if (byHash.containsKey(parent) && !nearest.containsKey(parent)) {
                    nearest.put(parent, name);
                    frontier.add(parent);
                }
            }
        }
        return new GhostBranches(Map.copyOf(nearest));
    }

    /** Adds the tips of local or remote branches to the frontier, in display order. */
    private static void seed(List<GitGraphRow> rows,
                             Map<String, String> nearest,
                             Deque<String> frontier,
                             boolean local) {
        List<CommitInfo> tips = new ArrayList<>();
        for (GitGraphRow row : rows) {
            CommitInfo commit = row.getCommit();
            if (commit != null && !commit.getRefs().isEmpty()) {
                tips.add(commit);
            }
        }
        for (CommitInfo tip : tips) {
            for (RefInfo ref : tip.getRefs().stream().sorted(RefInfo.DISPLAY_ORDER).toList()) {
                boolean wanted = local ? ref.isLocalBranch() : ref.isRemoteBranch();
                // origin/HEAD is a pointer at the remote's default branch, not a branch,
                // and it sorts before its target ('H' < 'm'), so without this every
                // commit below origin/main was labelled "origin/HEAD".
                if (!wanted || isRemoteHead(ref)) {
                    continue;
                }
                // A tip already named by an earlier seed keeps that name; the frontier is
                // walked in seeding order, so locals are settled before remotes.
                if (nearest.putIfAbsent(tip.getHash(), ref.shortName()) == null) {
                    frontier.add(tip.getHash());
                }
                break;
            }
        }
    }

    /**
     * Whether {@code ref} is a remote's symbolic {@code HEAD}, such as {@code origin/HEAD}.
     * The engines leave these out of every snapshot; refs handed in from elsewhere meet the
     * same rule ({@link com.jfx.git.model.RefNames#isSymbolicHead}) as far as it goes with no
     * remotes known: the first segment is taken as the remote, so a remote named
     * {@code team/core} has its {@code HEAD} read as a branch here, as the page's
     * decorations already do (see {@code GraphPageBuilder.isGraphRef}).
     */
    static boolean isRemoteHead(RefInfo ref) {
        return ref.isRemoteBranch() && com.jfx.git.model.RefNames.isSymbolicHead(ref.fullName(), List.of());
    }
}
