package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns a column (lane), incoming child connections, outgoing parent connections,
 * and passing lanes to each commit in a topologically and chronologically sorted list,
 * producing the DAG layout used by the graph renderer.
 */
public final class LaneAssigner {

    private LaneAssigner() {}

    private static final class LaneSlot {
        String targetHash;
        boolean firstParent;
        int color;

        LaneSlot(String targetHash, boolean firstParent, int color) {
            this.targetHash = targetHash;
            this.firstParent = firstParent;
            this.color = color;
        }
    }

    /**
     * The open lanes, with an index from each awaited commit to the lanes waiting for it.
     *
     * <h2>Why an index</h2>
     * Every row asked "which lanes wait for this commit?" and "which lane waits for that
     * parent?" by scanning every open lane, and then asked "is this lane one of the
     * incoming ones?" of a list - a scan inside a scan, per row. On a 20,000-commit walk
     * with a dozen lanes open that was most of the layout's time, and the boxed lists it
     * built to hold the answers were most of its garbage. Every change to a lane goes
     * through {@link #set}, so the index cannot drift from the lanes it describes.
     */
    private static final class Lanes {
        private final List<LaneSlot> slots = new ArrayList<>();
        /** Awaited hash to the lanes waiting for it, lowest index first. */
        private final Map<String, BitSet> waiting = new HashMap<>();
        /** Emptied sets kept for reuse: most rows retire one entry and open another. */
        private final ArrayDeque<BitSet> spare = new ArrayDeque<>();

        int size() {
            return slots.size();
        }

        LaneSlot get(int index) {
            return slots.get(index);
        }

        void set(int index, LaneSlot slot) {
            LaneSlot old = slots.get(index);
            if (old != null && old.targetHash != null) {
                BitSet set = waiting.get(old.targetHash);
                if (set != null) {
                    set.clear(index);
                    if (set.isEmpty()) {
                        waiting.remove(old.targetHash);
                        spare.push(set);
                    }
                }
            }
            slots.set(index, slot);
            if (slot != null && slot.targetHash != null) {
                BitSet set = waiting.get(slot.targetHash);
                if (set == null) {
                    set = spare.isEmpty() ? new BitSet() : spare.pop();
                    waiting.put(slot.targetHash, set);
                }
                set.set(index);
            }
        }

        /** Appends a lane and returns its index. */
        int add(LaneSlot slot) {
            slots.add(null);
            int index = slots.size() - 1;
            set(index, slot);
            return index;
        }

        /** The lanes waiting for {@code hash}, or {@code null} when none is. Do not modify. */
        BitSet waitingFor(String hash) {
            return waiting.get(hash);
        }

        /** The lowest lane waiting for {@code hash}, or -1. */
        int indexOf(String hash) {
            BitSet set = waiting.get(hash);
            return set == null ? -1 : set.nextSetBit(0);
        }

        int firstFree() {
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i) == null) return i;
            }
            return -1;
        }

        void trimTrailingNulls() {
            while (!slots.isEmpty() && slots.get(slots.size() - 1) == null) {
                slots.remove(slots.size() - 1);
            }
        }
    }

    /**
     * Palette size the renderer offers; colours cycle beyond it.
     *
     * <p>Kept as a plain constant rather than read from {@code GraphPalette}, so this
     * class stays free of JavaFX and its tests stay headless. {@code GraphPaletteTest}
     * asserts the two agree — which is the only thing that could go wrong.
     */
    static final int PALETTE_SIZE = 18;

    /**
     * Lays out {@code commits} and returns how many lane columns the page needs.
     *
     * <p>The width is a property of the whole page, not of a row: every row is drawn at
     * the same width so the message column beside the graph stays aligned.
     *
     * <p>The per-row lists and maps below are scratch, reused from row to row: each
     * {@code CommitInfo} setter takes its own immutable copy, so building fresh ones per
     * row only doubled the garbage a page load leaves behind.
     */
    public static int assign(List<CommitInfo> commits) {
        return assign(commits, List.of());
    }

    /**
     * {@link #assign(List)}, knowing the configured remotes: a remote branch's colour is
     * seeded from its branch part, and for a remote whose own name has a slash in it -
     * {@code team/core} - that is what follows the whole remote name, not the first
     * segment (see {@link com.jfx.git.model.RefNames#branchOf}).
     */
    public static int assign(List<CommitInfo> commits, java.util.Collection<String> remoteNames) {
        if (commits == null || commits.isEmpty()) return 1;

        Lanes activeLanes = new Lanes();
        int nextColor = 0;
        int widestLane = 0;
        // For the uncommitted row, whose colour comes from HEAD's branch (its parent).
        // Built only when that row is on the page: a 20,000-entry map per layout for the
        // one row that might need it was the largest single allocation here.
        Map<String, CommitInfo> byHash = null;

        List<Integer> incomingLanes = new ArrayList<>();
        BitSet incoming = new BitSet();
        Map<Integer, Integer> arrivingColors = new HashMap<>();
        List<Integer> passingLanes = new ArrayList<>();
        List<Integer> parentLanes = new ArrayList<>();
        Map<Integer, Integer> colors = new HashMap<>();

        for (CommitInfo commit : commits) {
            String hash = commit.getHash();
            List<String> parents = commit.getParentHashes();

            // 1. Find all active lanes waiting for THIS commit from above.
            incomingLanes.clear();
            incoming.clear();
            int firstParentLane = -1;
            BitSet waiting = activeLanes.waitingFor(hash);
            if (waiting != null) {
                for (int i = waiting.nextSetBit(0); i >= 0; i = waiting.nextSetBit(i + 1)) {
                    incomingLanes.add(i);
                    incoming.set(i);
                    if (activeLanes.get(i).firstParent && firstParentLane < 0) {
                        firstParentLane = i;
                    }
                }
            }

            // 2. Assign myLane. Prefer the lowest lane index (primary branch).
            int myLane;
            if (!incomingLanes.isEmpty()) {
                myLane = firstParentLane >= 0 ? firstParentLane : incomingLanes.get(0);
            } else {
                myLane = activeLanes.firstFree();
                if (myLane < 0) {
                    myLane = activeLanes.add(null);
                }
                // A named tip takes its branch's own colour, so the branch keeps it across
                // refreshes; a tip with no name takes the next one in turn.
                if (byHash == null && GitGraphRow.UNCOMMITTED_HASH.equals(hash)) {
                    byHash = new HashMap<>();
                    for (CommitInfo each : commits) byHash.put(each.getHash(), each);
                }
                int tipColor = tipColor(commit, byHash, activeLanes, myLane, remoteNames);
                if (tipColor < 0) {
                    tipColor = nextColor++ % PALETTE_SIZE;
                }
                activeLanes.set(myLane, new LaneSlot(null, false, tipColor));
            }
            commit.setLane(myLane);
            int myColor = activeLanes.get(myLane).color;

            // 3. Child connections: all incoming lanes connect to this node.
            //    Free all secondary incoming lanes (they converged into myLane), noting
            //    first the colour each arrives in: the branch it comes from.
            arrivingColors.clear();
            for (int incomingLane : incomingLanes) {
                arrivingColors.put(incomingLane, activeLanes.get(incomingLane).color);
                if (incomingLane != myLane) {
                    activeLanes.set(incomingLane, null);
                }
            }
            commit.setChildLanes(incomingLanes);
            commit.setChildLaneColors(arrivingColors);

            // 4. Passing lanes: active slots not involved in incoming/myLane.
            passingLanes.clear();
            for (int i = 0; i < activeLanes.size(); i++) {
                LaneSlot slot = activeLanes.get(i);
                if (i != myLane && slot != null && slot.targetHash != null && !incoming.get(i)) {
                    passingLanes.add(i);
                }
            }
            commit.setPassingLanes(passingLanes);

            // 5. Parent connections (outgoing to below).
            parentLanes.clear();
            if (parents.isEmpty()) {
                // Root commit: free myLane.
                activeLanes.set(myLane, null);
            } else {
                // 5a. First parent (direct branch continuation)
                String p0 = parents.get(0);
                int existingSlot = activeLanes.indexOf(p0);
                if (existingSlot >= 0 && existingSlot != myLane) {
                    if (myLane < existingSlot) {
                        // This lane is the primary/lower lane; it keeps p0.
                        // The higher lane (existingSlot) is a side branch also waiting for p0.
                        activeLanes.set(myLane, new LaneSlot(p0, true, myColor));
                        parentLanes.add(myLane);
                    } else {
                        // A side branch whose parent a lower lane already waits for. It
                        // keeps its own lane down to the parent and joins there - the
                        // way commercial clients draw it - rather than turning into the lower lane
                        // straight under this commit. Not first-parent, so the parent
                        // still takes the lower lane and this one ends at its row.
                        //
                        // Freeing this lane here instead also let a merge parent below
                        // claim it at once, and that parent's colour then replaced this
                        // commit's own in step 6.
                        activeLanes.set(myLane, new LaneSlot(p0, false, myColor));
                        parentLanes.add(myLane);
                    }
                } else {
                    // Continue in myLane as firstParent.
                    activeLanes.set(myLane, new LaneSlot(p0, true, myColor));
                    parentLanes.add(myLane);
                }

                // 5b. Merge parents (additional parents)
                for (int pIdx = 1; pIdx < parents.size(); pIdx++) {
                    String pOther = parents.get(pIdx);
                    int pSlot = activeLanes.indexOf(pOther);
                    if (pSlot >= 0) {
                        if (!parentLanes.contains(pSlot)) {
                            parentLanes.add(pSlot);
                        }
                    } else {
                        pSlot = activeLanes.firstFree();
                        int pColor = nextColor++ % PALETTE_SIZE;
                        if (pSlot < 0) {
                            pSlot = activeLanes.add(new LaneSlot(pOther, false, pColor));
                        } else {
                            activeLanes.set(pSlot, new LaneSlot(pOther, false, pColor));
                        }
                        if (!parentLanes.contains(pSlot)) {
                            parentLanes.add(pSlot);
                        }
                    }
                }
            }
            commit.setParentLanes(parentLanes);

            // 6. Record the colour of every lane this row draws, so the renderer can
            //    colour by branch rather than by column position.
            colors.clear();
            colors.put(myLane, myColor);
            for (int pass : passingLanes) {
                colors.put(pass, colorAt(activeLanes, pass, pass));
            }
            for (int child : incomingLanes) {
                // An incoming lane was cleared in step 3 when it converged here, so its
                // colour is this commit's: the edge belongs to the branch it merges into.
                colors.putIfAbsent(child, child == myLane ? myColor : colorAt(activeLanes, child, myColor));
            }
            for (int parent : parentLanes) {
                colors.put(parent, colorAt(activeLanes, parent, myColor));
            }
            commit.setLaneColors(colors);

            widestLane = Math.max(widestLane, myLane);
            for (int lane : passingLanes) widestLane = Math.max(widestLane, lane);
            for (int lane : incomingLanes) widestLane = Math.max(widestLane, lane);
            for (int lane : parentLanes) widestLane = Math.max(widestLane, lane);

            activeLanes.trimTrailingNulls();
        }

        int laneCount = widestLane + 1;
        for (CommitInfo commit : commits) {
            commit.setTotalLanes(laneCount);
        }
        return laneCount;
    }

    /**
     * The colour a branch tip opening a lane takes from its name, or -1 for a tip with no
     * name.
     *
     * <p>Colours used to be handed out by a counter, top to bottom. One new commit or
     * branch above another branch then moved every colour below it on the next refresh,
     * and the branch the user was following changed colour under them. Seeded from the
     * name instead, a branch is the same colour on every refresh and page. The name is
     * the tip's first local branch, else its first remote branch without the remote's
     * name (so {@code origin/x} matches {@code x}), else its first tag; the uncommitted
     * row takes HEAD's.
     *
     * <p>A seed that matches a neighbouring lane's colour moves on along the palette,
     * whose consecutive entries are far apart in hue, so two branches side by side are
     * never drawn alike.
     */
    private static int tipColor(CommitInfo tip, Map<String, CommitInfo> byHash,
                        Lanes activeLanes, int lane, java.util.Collection<String> remoteNames) {
        CommitInfo named = tip;
        if (GitGraphRow.UNCOMMITTED_HASH.equals(tip.getHash())
                && !tip.getParentHashes().isEmpty()) {
            named = byHash == null ? null : byHash.get(tip.getParentHashes().get(0));
        }
        String name = named == null ? null : branchName(named, remoteNames);
        if (name == null) {
            return -1;
        }
        int color = Math.floorMod(name.hashCode(), PALETTE_SIZE);
        for (int tries = 0; tries < PALETTE_SIZE && clashesWithNeighbour(activeLanes, lane, color); tries++) {
            color = (color + 1) % PALETTE_SIZE;
        }
        return color;
    }

    /**
     * The name a commit's colour is seeded from; see {@link #tipColor}. A remote branch
     * seeds like its local namesake: {@code origin/topic} and {@code team/core/topic} are
     * both {@code topic}. A remote's symbolic {@code HEAD} names nothing: it sorts before
     * the branch it points at ('H' &lt; 'm'), and seeded the lane from the word "HEAD".
     */
    private static String branchName(CommitInfo commit, java.util.Collection<String> remoteNames) {
        if (commit.getRefs().isEmpty()) {
            // Most tips that open a lane carry no ref at all; copying and sorting an
            // empty list for each of them was pure overhead.
            return null;
        }
        var refs = new ArrayList<>(commit.getRefs());
        refs.sort(com.jfx.git.model.RefInfo.DISPLAY_ORDER);
        for (var ref : refs) {
            if (ref.isLocalBranch()) return ref.shortName();
        }
        for (var ref : refs) {
            if (ref.isRemoteBranch()
                    && !com.jfx.git.model.RefNames.isSymbolicHead(ref.shortName(), remoteNames)) {
                return com.jfx.git.model.RefNames.branchOf(ref.shortName(), remoteNames);
            }
        }
        for (var ref : refs) {
            if (ref.isTag()) return ref.shortName();
        }
        return null;
    }

    private static boolean clashesWithNeighbour(Lanes lanes, int lane, int color) {
        int below = lane - 1;
        int above = lane + 1;
        if (below >= 0 && below < lanes.size()) {
            LaneSlot slot = lanes.get(below);
            if (slot != null && slot.color == color) return true;
        }
        if (above < lanes.size()) {
            LaneSlot slot = lanes.get(above);
            if (slot != null && slot.color == color) return true;
        }
        return false;
    }

    /** Colour of the slot at {@code index}, or {@code fallback} when it is free. */
    private static int colorAt(Lanes lanes, int index, int fallback) {
        if (index < 0 || index >= lanes.size()) return fallback;
        LaneSlot slot = lanes.get(index);
        return slot != null ? slot.color : fallback;
    }
}
