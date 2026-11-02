package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.StashEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places stash entries onto an already-laid-out graph page.
 *
 * <p>Runs after {@link LaneAssigner} rather than inside it: a stash is not part of the
 * commit DAG — nothing descends from it and no branch reaches it — so letting it occupy a
 * lane would push real branches sideways and change the shape of the history being read.
 * Instead each stash is drawn as a detached node on the row just above the commit it was
 * taken from, in a lane past the widest one in use. The row it opens is crossed by every
 * lane entering that commit from above, and its tie line points at that commit's lane;
 * see {@link GitGraphRow#getThroughLanes()}.
 */
public final class StashRows {

    private StashRows() {}

    /**
     * Drops any stash whose hash the user has hidden from the graph (Hide & Solo's
     * stash-specific hide - see {@code AppPreferences.hiddenStashes()} for why stashes
     * get their own hidden set instead of joining the ref-based one).
     */
    public static List<StashEntry> visible(List<StashEntry> stashes, Set<String> hiddenHashes) {
        if (stashes == null || stashes.isEmpty() || hiddenHashes == null || hiddenHashes.isEmpty()) {
            return stashes;
        }
        return stashes.stream().filter(s -> !hiddenHashes.contains(s.hash())).toList();
    }

    /**
     * Interleaves stash rows into {@code rows}.
     *
     * <p>Stashes whose parent commit is not on this page are dropped rather than
     * bunched at the end: a node with nothing to sit beside says nothing about where the
     * work came from, which is the only reason to draw it on the graph at all.
     *
     * @param rows        the page, with the uncommitted row already at the front if any
     * @param stashes     stash entries, newest first
     * @param laneCount   lanes the commit graph itself occupies
     * @return the number of lane columns the page now needs
     */
    public static int interleave(List<GitGraphRow> rows, List<StashEntry> stashes, int laneCount) {
        if (stashes == null || stashes.isEmpty()) return laneCount;

        // Where each commit sits, so a stash can be inserted directly above its parent.
        Map<String, Integer> rowOfCommit = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            CommitInfo commit = rows.get(i).getCommit();
            if (commit != null) rowOfCommit.put(commit.getHash(), i);
        }

        // Group by insertion point first: several stashes can share a parent, and
        // inserting them one at a time would invalidate every index after the first.
        Map<Integer, List<StashEntry>> byRow = new LinkedHashMap<>();
        for (StashEntry stash : stashes) {
            Integer index = stash.parentHash() == null ? null : rowOfCommit.get(stash.parentHash());
            if (index == null) continue;
            byRow.computeIfAbsent(index, k -> new ArrayList<>()).add(stash);
        }
        if (byRow.isEmpty()) return laneCount;

        int stashLane = laneCount;

        // Insert from the bottom up so earlier indices stay valid as the list grows.
        List<Integer> targets = new ArrayList<>(byRow.keySet());
        targets.sort(java.util.Comparator.reverseOrder());
        for (int index : targets) {
            List<StashEntry> here = byRow.get(index);
            // The commit this group sits above: the stash rows borrow its incoming lanes so
            // nothing crossing the gap they open is broken, and aim their tie line at its
            // lane rather than at lane 0.
            CommitInfo anchor = rows.get(index).getLayout();
            for (int i = here.size() - 1; i >= 0; i--) {
                GitGraphRow stashRow = new GitGraphRow(here.get(i), stashLane);
                stashRow.setAnchor(anchor);
                rows.add(index, stashRow);
            }
        }

        int totalLanes = stashLane + 1;
        for (GitGraphRow row : rows) {
            row.setTotalLanes(totalLanes);
        }
        return totalLanes;
    }
}
