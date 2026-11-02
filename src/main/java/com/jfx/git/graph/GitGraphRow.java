package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;

import java.util.List;

/**
 * A row in the commit graph.
 *
 * <p>Most rows are commits, but two are synthetic: the "uncommitted changes" node shown
 * at the top when the tree is dirty, and the "load more" row at the bottom when history
 * continues past what has been walked. The second exists so a truncated graph says so
 * rather than simply appearing to end.
 *
 * <p>Previously this class wrapped {@code PlotCommit<PlotLane>}; it now uses the
 * backend-agnostic {@link CommitInfo} whose graph-layout fields were populated by
 * {@link LaneAssigner}.
 */
public class GitGraphRow {

    /** What a row stands for. */
    public enum Kind { COMMIT, UNCOMMITTED, LOAD_MORE, STASH }

    /**
     * Hash of the pseudo-commit that stands for the working tree in lane assignment.
     *
     * <p>Not hexadecimal, so it can never collide with a real commit. It never leaves the
     * layout: {@link #getCommit()} is still {@code null} for the uncommitted row.
     */
    public static final String UNCOMMITTED_HASH = "(uncommitted)";

    private final CommitInfo commit;
    private final Kind kind;

    /**
     * Where this row's lanes, edges and colours are read from.
     *
     * <p>Usually the commit itself, as laid out for the whole page. Two cases differ: the
     * uncommitted row carries a pseudo-commit whose parent is HEAD, so the lane assigner
     * gives it HEAD's lane and an edge down to it (G4); and a row in a narrowed filter
     * view carries a copy laid out over the filtered rows only, so the lanes it draws
     * connect the commits actually on screen rather than the ones filtered out between
     * them (G5). Keeping the copy here rather than re-laying-out the shared
     * {@link CommitInfo} means clearing the filter needs no second layout pass.
     */
    private final CommitInfo layout;

    /**
     * For a stash row, the layout of the commit it sits above: the lanes crossing into
     * that commit's row from above are the ones crossing this row too, and its lane is
     * where the stash's tie line points (G6).
     */
    private CommitInfo anchor;

    /**
     * Lane columns the page is drawn at.
     *
     * <p>Commit rows read this from their {@link CommitInfo}; the synthetic rows have no
     * commit to read it from, so the backend sets it on them directly to keep every row
     * the same width.
     */
    private int totalLanes = 1;

    /**
     * The stash this row stands for, when {@link #getKind()} is {@link Kind#STASH}.
     *
     * <p>A stash is a commit, but not one on any branch, so it carries its own type
     * rather than being squeezed into {@link CommitInfo} and then having to be told apart
     * again everywhere downstream.
     */
    private final com.jfx.git.model.StashEntry stash;

    /** Creates a commit row. */
    public GitGraphRow(CommitInfo commit) {
        this(commit, commit);
    }

    /** Creates a commit row drawn from a layout other than the commit's own; see {@link #layout}. */
    public GitGraphRow(CommitInfo commit, CommitInfo layout) {
        this.commit = commit;
        this.layout = layout != null ? layout : commit;
        this.stash = null;
        this.kind = Kind.COMMIT;
    }

    /** Creates a stash row, drawn at {@code lane} beside the commit it was taken from. */
    public GitGraphRow(com.jfx.git.model.StashEntry stash, int lane) {
        this.commit = null;
        this.layout = null;
        this.stash = stash;
        this.kind = Kind.STASH;
        this.stashLane = lane;
    }

    private int stashLane;

    public com.jfx.git.model.StashEntry getStash() {
        return stash;
    }

    public boolean isStash() {
        return kind == Kind.STASH;
    }

    /** Lane column a stash node is drawn in. */
    public int getStashLane() {
        return stashLane;
    }

    /** Sets the commit row this stash row sits directly above; see {@link #anchor}. */
    public void setAnchor(CommitInfo anchor) {
        this.anchor = anchor;
    }

    /**
     * The lane a stash's tie line points at: the lane of the commit it was taken from,
     * or lane 0 when that is not known.
     */
    public int getTieLane() {
        return anchor == null ? 0 : anchor.getLane();
    }

    /**
     * Lanes that run straight through this stash row, in ascending order.
     *
     * <p>A stash row is inserted between two commit rows after the layout was made, so
     * every edge crossing that gap has to be drawn across it too - otherwise each lane
     * passing a stash row visibly breaks. Those edges are exactly the ones entering the
     * anchor commit's row from above: its incoming child lanes and its passing lanes.
     */
    public List<Integer> getThroughLanes() {
        if (anchor == null) return List.of();
        java.util.TreeSet<Integer> lanes = new java.util.TreeSet<>(anchor.getChildLanes());
        lanes.addAll(anchor.getPassingLanes());
        return List.copyOf(lanes);
    }

    /** Palette index of {@code lane} on this stash row, borrowed from the anchor. */
    public int throughLaneColor(int lane) {
        return anchor == null ? lane : anchor.colorOfLane(lane);
    }

    /** Creates the work-in-progress row, drawn in lane 0 with no edge. */
    public GitGraphRow() {
        this(Kind.UNCOMMITTED, null);
    }

    /**
     * Creates the work-in-progress row from its laid-out pseudo-commit.
     *
     * @param pseudo a commit named {@link #UNCOMMITTED_HASH} whose only parent is HEAD,
     *               already through lane assignment
     */
    public static GitGraphRow uncommitted(CommitInfo pseudo) {
        return new GitGraphRow(Kind.UNCOMMITTED, pseudo);
    }

    /** The pseudo-commit for an uncommitted row whose HEAD is {@code headHash} (null when unborn). */
    public static CommitInfo uncommittedPseudoCommit(String headHash) {
        java.time.Instant now = java.time.Instant.now();
        return new CommitInfo(UNCOMMITTED_HASH, "", "", "", now, now,
                headHash == null ? List.of() : List.of(headHash), List.of());
    }

    private GitGraphRow(Kind kind, CommitInfo layout) {
        this.commit = null;
        this.layout = layout;
        this.stash = null;
        this.kind = kind;
    }

    /** Creates the row that stands for the history still unwalked below. */
    public static GitGraphRow loadMore() {
        return new GitGraphRow(Kind.LOAD_MORE, null);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /** The commit on this row, or {@code null} for synthetic rows. */
    public CommitInfo getCommit() { return commit; }

    public Kind getKind() { return kind; }

    /**
     * Where this row's lanes are read from; see {@link #layout}. {@code null} for rows
     * with nothing laid out: stash, load-more, and an uncommitted row built without its
     * pseudo-commit.
     */
    public CommitInfo getLayout() { return layout; }

    public boolean isWipNode()   { return kind == Kind.UNCOMMITTED; }
    public boolean isLoadMore()  { return kind == Kind.LOAD_MORE; }

    /**
     * True for rows that carry no commit from the branch's history.
     *
     * <p>A stash row counts: it has a commit behind it, but the actions that apply to
     * history — revert, rebase from here — do not apply to a stash.
     */
    public boolean isSynthetic() { return kind != Kind.COMMIT; }

    /**
     * Whether this commit is reachable from HEAD.
     *
     * <p>Delegated to the {@link CommitInfo} so the graph cell can read it without
     * calling Git per row. Falls back to {@code true} for synthetic rows.
     */
    public boolean isOnCurrentBranch() {
        return commit == null || commit.isOnCurrentBranch();
    }

    /** Convenience: sets onCurrentBranch on the underlying CommitInfo. */
    public void setOnCurrentBranch(boolean onCurrentBranch) {
        if (commit != null) commit.setOnCurrentBranch(onCurrentBranch);
    }

    /** Lane columns this row should reserve; the same for every row on a page. */
    public int getTotalLanes() {
        return layout != null ? layout.getTotalLanes() : totalLanes;
    }

    public void setTotalLanes(int totalLanes) {
        this.totalLanes = Math.max(1, totalLanes);
        if (layout != null) layout.setTotalLanes(totalLanes);
    }

    /**
     * The lane drawing this row was last painted from, or {@code null} before its first
     * paint; see {@link RowPlanner#planFor}, which decides whether it still applies.
     */
    private RowPlan plan;

    RowPlan plan() {
        return plan;
    }

    void keepPlan(RowPlan plan) {
        this.plan = plan;
    }
}
