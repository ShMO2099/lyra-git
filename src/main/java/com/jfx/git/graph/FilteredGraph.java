package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Lays out the rows a narrowing filter keeps, as a graph of their own.
 *
 * <h2>Why this exists</h2>
 * The filter used to hand the matching rows to the list with the lanes they had on the
 * full page. Each row then drew edges to neighbours that were no longer beside it: a lane
 * running down into a row that was filtered out simply continued into whatever unrelated
 * commit happened to be next, so the picture connected commits with no relationship at
 * all (G5).
 *
 * <p>Instead each kept commit is joined to its nearest kept ancestors on the page - the
 * same simplification {@code git log --graph -- <path>} makes - and the result is laid
 * out afresh. The copies carry only what the lane assigner reads; the rows still hold the
 * real {@link CommitInfo}, so selection, menus and details see the commit unchanged, and
 * the full page's layout is never touched.
 */
public final class FilteredGraph {

    private FilteredGraph() {}

    /**
     * At most this many rewritten parents per commit. A kept commit below a run of hidden
     * merges can reach dozens of kept ancestors, and a fan of edges that wide says less
     * than the nearest few.
     */
    static final int MAX_PARENTS = 4;

    /**
     * The rows of {@code all} that {@code keep} accepts, laid out among themselves.
     *
     * <p>Stash and load-more rows are dropped: a stash's position means "above its parent",
     * which a filtered view no longer shows. The uncommitted row is kept when accepted and
     * joined to HEAD's nearest kept ancestor.
     */
    public static List<GitGraphRow> relayout(List<GitGraphRow> all, Predicate<GitGraphRow> keep) {
        List<GitGraphRow> kept = new ArrayList<>();
        Set<String> shown = new HashSet<>();
        Map<String, CommitInfo> onPage = new HashMap<>();
        for (GitGraphRow row : all) {
            CommitInfo commit = row.getCommit();
            if (commit != null) onPage.put(commit.getHash(), commit);
            if (row.isStash() || row.isLoadMore()) continue;
            if (!keep.test(row)) continue;
            kept.add(row);
            if (commit != null) shown.add(commit.getHash());
        }

        // Nearest shown ancestors of every hidden commit, computed bottom-up: the page is
        // topological, so a commit's parents have already been visited when it is.
        Map<String, List<String>> frontier = new HashMap<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            CommitInfo commit = all.get(i).getCommit();
            if (commit == null || shown.contains(commit.getHash())) continue;
            frontier.put(commit.getHash(), nearestShown(commit.getParentHashes(), shown, onPage, frontier));
        }

        List<CommitInfo> layouts = new ArrayList<>(kept.size());
        List<GitGraphRow> rows = new ArrayList<>(kept.size());
        for (GitGraphRow row : kept) {
            CommitInfo source = row.getCommit() != null ? row.getCommit() : row.getLayout();
            if (source == null) {
                // An uncommitted row with no pseudo-commit: nothing to lay out.
                rows.add(row);
                continue;
            }
            CommitInfo copy = new CommitInfo(source.getHash(), "", "", "",
                    source.getAuthorDate(), source.getCommitterDate(),
                    nearestShown(source.getParentHashes(), shown, onPage, frontier), List.of());
            layouts.add(copy);
            rows.add(row.isWipNode() ? GitGraphRow.uncommitted(copy) : new GitGraphRow(row.getCommit(), copy));
        }
        int laneCount = LaneAssigner.assign(layouts);
        rows.forEach(row -> row.setTotalLanes(laneCount));
        return rows;
    }

    /**
     * What {@code parents} become once hidden commits are skipped: a shown parent stays, a
     * hidden one on the page is replaced by its own nearest shown ancestors, and one off
     * the page stays too - its lane runs off the bottom, exactly as on the full page.
     */
    private static List<String> nearestShown(List<String> parents, Set<String> shown,
                                             Map<String, CommitInfo> onPage,
                                             Map<String, List<String>> frontier) {
        Set<String> result = new LinkedHashSet<>();
        for (String parent : parents) {
            if (shown.contains(parent) || !onPage.containsKey(parent)) {
                result.add(parent);
            } else {
                // Hidden and on the page. Missing from the map only under clock skew,
                // where a parent sits above its child; dropping the edge is the safe answer.
                result.addAll(frontier.getOrDefault(parent, List.of()));
            }
            if (result.size() >= MAX_PARENTS) break;
        }
        List<String> list = new ArrayList<>(result);
        return list.size() > MAX_PARENTS ? list.subList(0, MAX_PARENTS) : list;
    }
}
