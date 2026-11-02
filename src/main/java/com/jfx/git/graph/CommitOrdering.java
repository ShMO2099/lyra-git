package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Puts commits into one canonical order, whichever engine produced them.
 *
 * <h2>Why this exists</h2>
 * The graph's shape is a pure function of the order its commits arrive in — lane
 * assignment just walks the list — so two engines that order the same history
 * differently draw visibly different graphs of the same repository. That is exactly what
 * happened: JGit walked with {@code TOPO + COMMIT_TIME_DESC} while the CLI used
 * {@code git log --topo-order}, which are different algorithms. On one repository they
 * diverged by the eighth commit and produced graphs 14 and 32 lanes wide.
 *
 * <p>Neither ordering is wrong; they are just different. So rather than trying to make
 * one engine imitate the other's walk, both hand their commits here and the ordering is
 * decided once, in Java, where it can be tested.
 *
 * <h2>The order</h2>
 * Topological — every commit appears before all of its parents, which the renderer
 * requires — with ties broken by <em>committer</em> date, newest first, and by hash where
 * even that is equal. Committer date because it is the clock the walk selects commits
 * by: ordering by author date let a rebased branch (old author dates, new committer
 * dates) sink below commits the walk had chosen after it. The hash tie-break is what
 * makes the result deterministic rather than merely consistent.
 *
 * <h2>Pages: why the order is decided a chunk at a time</h2>
 * A page is the newest part of a date-ordered walk. Ordering the whole window at once and
 * cutting it afterwards had two faults. Following first-parent down a long, unbranched
 * trunk used the entire window before any other tip was started, so a branch pushed
 * today but forked a fortnight ago fell off the page entirely (G2). And because how far
 * that line ran depended on how big the window was, asking for a second page reshuffled
 * the rows already on screen (G3).
 *
 * <p>{@link #page} therefore splits the walk into fixed chunks of {@link #CHUNK} commits
 * and orders each one in turn, carrying only what the previous chunks decided. A row's
 * position then depends on the walk up to the end of its own chunk and nothing after it,
 * so a bigger page is always the smaller page with more rows after it — and no tip is
 * ever more than one chunk away from its place in date order. Chunk boundaries only fall
 * between different committer timestamps (see {@link #TIE_SPAN}), because engines may
 * list commits of the same second in different orders and must still agree on which
 * chunk each belongs to.
 */
public final class CommitOrdering {

    private CommitOrdering() {}

    /**
     * Commits ordered together. Large enough that a branch's run stays contiguous on a
     * normal page, small enough that a fresh tip is never pushed further down than this.
     */
    public static final int CHUNK = 500;

    /**
     * How far a chunk may grow to avoid cutting between commits of the same second.
     *
     * <p>Past this the chunk is cut at exactly {@link #CHUNK} rather than grown further:
     * an imported history can stamp thousands of commits with one timestamp, and a chunk
     * that grows without bound would need an unbounded fetch to decide where it ends.
     */
    public static final int TIE_SPAN = 100;

    /**
     * How many further commits an engine will walk to find an old HEAD.
     *
     * <p>The checked-out commit belongs on the page (G4) even when it is older than
     * everything else there, but not at the price of walking the whole of a very large
     * history to reach it.
     */
    public static final int HEAD_REACH = 10_000;

    /** One page of the stream, as {@link #page} decided it. */
    public record Page(List<CommitInfo> commits, boolean hasMore) {}

    /**
     * How many commits an engine should walk for a page of {@code limit}.
     *
     * <p>Enough for the last chunk the page touches to be complete, including its
     * timestamp tie, plus one to know whether history continues.
     */
    public static int fetchCount(int limit) {
        return Math.max(0, limit) + CHUNK + TIE_SPAN + 1;
    }

    /** How many commits to walk so that the chunk holding walk position {@code index} is complete. */
    public static int fetchCountThrough(int index) {
        return Math.max(0, index) + CHUNK + TIE_SPAN + 2;
    }

    /**
     * The first rows of the canonical stream over {@code walk}.
     *
     * <p>At least {@code limit} rows when the walk has them, and more when that is what it
     * takes to include {@code headHash}: the uncommitted row hangs off HEAD, and a page
     * without HEAD on it has nothing to hang it from.
     *
     * @param walk         commits in the walk's own order: newest committer date first
     * @param walkComplete the walk reached the end of history rather than a fetch limit
     * @param headHash     HEAD's commit, or {@code null} when unborn or unknown
     */
    public static Page page(List<CommitInfo> walk, boolean walkComplete, String headHash,
                            GraphOrder order, int limit) {
        if (walk == null || walk.isEmpty() || limit <= 0) {
            return new Page(List.of(), walk != null && !walk.isEmpty() || !walkComplete);
        }
        int headIndex = -1;
        if (headHash != null) {
            for (int i = 0; i < walk.size(); i++) {
                if (headHash.equals(walk.get(i).getHash())) {
                    headIndex = i;
                    break;
                }
            }
        }

        List<CommitInfo> out = new ArrayList<>();
        Stream stream = new Stream();
        int start = 0;
        while (start < walk.size()) {
            boolean headPlaced = headIndex < start;
            if (out.size() >= limit && headPlaced) break;
            int end = chunkEnd(walk, start, walkComplete);
            // Not enough of the walk to know where this chunk ends: everything decided so
            // far is final, and nothing past it can be decided yet.
            if (end < 0) break;
            orderChunk(walk.subList(start, end), headHash, order, stream, out);
            start = end;
        }

        boolean hasMore = start < walk.size() || !walkComplete;
        int keep = limit;
        if (headIndex >= 0) {
            for (int i = 0; i < out.size(); i++) {
                if (headHash.equals(out.get(i).getHash())) {
                    keep = Math.max(limit, i + 1);
                    break;
                }
            }
        }
        if (out.size() > keep) {
            out = new ArrayList<>(out.subList(0, keep));
            hasMore = true;
        }
        return new Page(out, hasMore);
    }

    /**
     * Where the chunk starting at {@code start} ends, or {@code -1} when the walk does not
     * reach far enough to say.
     */
    static int chunkEnd(List<CommitInfo> walk, int start, boolean walkComplete) {
        int size = walk.size();
        int base = start + CHUNK;
        if (base >= size) return walkComplete ? size : -1;
        long tie = seconds(walk.get(base - 1));
        int end = base;
        while (end < size && seconds(walk.get(end)) == tie) {
            end++;
            if (end - base > TIE_SPAN) return base;
        }
        if (end == size && !walkComplete) return -1;
        return end;
    }

    /** What earlier chunks decided that later ones depend on. */
    private static final class Stream {
        CommitInfo last;
    }

    /**
     * Returns {@code commits} in canonical order.
     *
     * <p>Only parents present in {@code commits} constrain the order — a page of history
     * is a window, and the parents below it have not been fetched. Commits left unplaced
     * by a cycle (which git cannot produce, but a corrupt or truncated read might) are
     * appended by date rather than dropped, so a bad input costs ordering rather than
     * rows.
     */
    public static List<CommitInfo> canonical(List<CommitInfo> commits) {
        return canonical(commits, null);
    }

    /**
     * The same, starting from {@code headHash} where it is present.
     *
     * <p>Which commit comes first decides which line of history gets lane 0, and the
     * checked-out branch is the one that should have it. Left to the date tie-break alone,
     * a feature branch committed in the same second as the trunk can win and push the
     * trunk into a side lane — correct topologically, wrong to look at.
     *
     * @param headHash commit HEAD points at, or {@code null} if it is not known
     */
    public static List<CommitInfo> canonical(List<CommitInfo> commits, String headHash) {
        return canonical(commits, headHash, GraphOrder.BY_BRANCH);
    }

    /**
     * The same, in the order the user asked for, over the whole list as one chunk.
     *
     * <p>The two orders differ in exactly one place: what happens when several commits are
     * eligible at once. {@link GraphOrder#BY_BRANCH} continues down first-parent, keeping a
     * branch on one lane until it ends. {@link GraphOrder#CHRONOLOGICAL} takes the newest,
     * which is what interleaves branches by date and is what commercial clients draw.
     */
    public static List<CommitInfo> canonical(List<CommitInfo> commits, String headHash,
                                             GraphOrder order) {
        if (commits == null || commits.size() < 2) {
            return commits == null ? List.of() : new ArrayList<>(commits);
        }
        List<CommitInfo> out = new ArrayList<>(commits.size());
        orderChunk(commits, headHash, order, new Stream(), out);
        return out;
    }

    /**
     * Orders one chunk onto the end of {@code out}.
     *
     * <p>Every commit of an earlier chunk is already in {@code out}, so only children
     * inside this chunk can hold a commit back. A child in a <em>later</em> chunk — which
     * only clock skew produces — is deliberately not waited for: waiting would make this
     * chunk's order depend on how far the walk had gone, which is the instability the
     * chunking exists to remove.
     */
    private static void orderChunk(List<CommitInfo> commits, String headHash, GraphOrder order,
                                   Stream stream, List<CommitInfo> out) {
        boolean followFirstParent = order == null || order.followsFirstParent();

        Map<String, CommitInfo> byHash = new HashMap<>(commits.size() * 2);
        for (CommitInfo c : commits) {
            byHash.put(c.getHash(), c);
        }

        // How many commits in this chunk list each one as a parent. A commit can only be
        // emitted once every child that would sit above it already has been.
        Map<String, Integer> pendingChildren = new HashMap<>(commits.size() * 2);
        for (CommitInfo c : commits) {
            pendingChildren.putIfAbsent(c.getHash(), 0);
            for (String parent : c.getParentHashes()) {
                if (byHash.containsKey(parent)) {
                    pendingChildren.merge(parent, 1, Integer::sum);
                }
            }
        }

        PriorityQueue<CommitInfo> ready = new PriorityQueue<>(CommitOrdering::compare);
        for (CommitInfo c : commits) {
            if (pendingChildren.getOrDefault(c.getHash(), 0) == 0) {
                ready.add(c);
            }
        }

        Set<String> emitted = new HashSet<>(commits.size() * 2);

        // Start on HEAD so the checked-out branch takes lane 0 and, through first-parent
        // chaining below, keeps it all the way down. Failing that, carry on the line the
        // previous chunk was following, so a branch crossing a chunk boundary is not cut
        // in two.
        CommitInfo continuation = null;
        if (followFirstParent) {
            CommitInfo head = headHash == null ? null : byHash.get(headHash);
            if (head != null && pendingChildren.getOrDefault(headHash, 0) == 0) {
                continuation = head;
            } else if (stream.last != null && !stream.last.getParentHashes().isEmpty()) {
                String p0 = stream.last.getParentHashes().get(0);
                if (byHash.containsKey(p0) && pendingChildren.getOrDefault(p0, 0) == 0) {
                    continuation = byHash.get(p0);
                }
            }
        }

        int placed = 0;
        while (continuation != null || !ready.isEmpty()) {
            // Follow one line of history as far as it goes before starting another.
            //
            // Taking strictly the newest ready commit each time is also a valid
            // topological order, but it interleaves every branch by date: on a repository
            // with 412 remote branches that held 63 lanes open at once and drew a wall of
            // parallel lines. Continuing down first-parent keeps a branch on one lane
            // until it actually ends, which is both what the history means and what makes
            // the picture readable.
            CommitInfo next;
            if (continuation != null && !emitted.contains(continuation.getHash())) {
                next = continuation;
                ready.remove(next);
            } else {
                next = ready.poll();
            }
            continuation = null;
            if (next == null || !emitted.add(next.getHash())) continue;
            out.add(next);
            stream.last = next;
            placed++;

            List<String> parents = next.getParentHashes();
            for (int i = 0; i < parents.size(); i++) {
                CommitInfo parent = byHash.get(parents.get(i));
                if (parent == null) continue;
                int remaining = pendingChildren.merge(parents.get(i), -1, Integer::sum);
                if (remaining != 0) continue;

                ready.add(parent);
                // Only the first parent continues this line; the others are branches
                // merged in, and belong to whatever lane they came from. Left unset in
                // chronological order, so the queue's newest-first ordering decides
                // instead and branches interleave by date.
                if (i == 0 && followFirstParent) continuation = parent;
            }
        }

        if (placed != commits.size()) {
            // Unreachable for well-formed history; keep the rows rather than lose them.
            List<CommitInfo> leftovers = new ArrayList<>();
            for (CommitInfo c : commits) {
                if (!emitted.contains(c.getHash())) leftovers.add(c);
            }
            leftovers.sort(CommitOrdering::compare);
            out.addAll(leftovers);
            if (!leftovers.isEmpty()) stream.last = leftovers.get(leftovers.size() - 1);
        }
    }

    private static long seconds(CommitInfo c) {
        return c.getCommitterDate() == null ? 0 : c.getCommitterDate().getEpochSecond();
    }

    /** Newest committer date first, then by hash so equal timestamps still order deterministically. */
    private static int compare(CommitInfo a, CommitInfo b) {
        int byDate = b.getCommitterDate().compareTo(a.getCommitterDate());
        return byDate != 0 ? byDate : a.getHash().compareTo(b.getHash());
    }
}
