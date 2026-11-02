package com.jfx.git.graph;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.List;

/**
 * The two column widths that depend on what is actually loaded.
 *
 * <p>Five of the seven columns are fixed by the density alone. The other two are not: the
 * lane column is as wide as the page's branching needs, and the ref rail is as wide as
 * the longest branch name on it. Both are properties of the page rather than of a row, so
 * they are computed once when the graph is populated and every cell — and the header
 * above them — reads the same value. Letting a cell size either column to its own content
 * is what makes columns disagree from one row to the next.
 *
 * <p>The rail in particular started as a fixed 128&nbsp;px, which is fine until a
 * repository names its branches {@code origin/njh/attention-free-models} and the name is
 * clipped to nothing while most of the rail sits empty.
 */
public final class GraphMetrics {

    /**
     * Never narrower than this.
     *
     * <p>Enough for a short name like {@code main}, or for the {@code +n} chip on its own.
     * Below this the column stops reading as a column.
     */
    private static final double MIN_RAIL = 44;

    /**
     * The widest the ref column makes itself.
     *
     * <p>Enough for a name like {@code feature/login-redesign} in full. Past this it is
     * the reader's call: dragging the column's edge can take it further.
     */
    static final double MAX_AUTO_RAIL = 180;

    /** The widest the column can be dragged; beyond this it is eating the messages. */
    static final double MAX_RAIL = 360;

    /**
     * How much of the list's width the ref column may take for itself.
     *
     * <p>0.22 fitted {@code feature/login-redesign} in a 1480-pixel window on Windows, but
     * Linux's default sans-serif is about a fifth wider than Segoe UI, and there it came
     * out three pixels short of the name.
     */
    private static final double RAIL_SHARE = 0.24;

    /** What the widest chip on the page needs, padding included; 0 for a page with none. */
    private double railWanted;

    /** The width the user dragged the ref column to, or 0 while it sizes itself. */
    private double railOverride;

    private final ReadOnlyDoubleWrapper rail = new ReadOnlyDoubleWrapper(MIN_RAIL);
    private final ReadOnlyDoubleWrapper lane = new ReadOnlyDoubleWrapper(0);
    /** Lanes past the budget, drawn together in the last column; 0 when everything fits. */
    private final javafx.beans.property.ReadOnlyIntegerWrapper hiddenLanes =
            new javafx.beans.property.ReadOnlyIntegerWrapper(0);

    private final GraphDensity density;
    private final GraphNodeStyle nodeStyle;

    /** Lane budget for the current window width. */
    private int maxLanes;

    /** Widest lane index the loaded page actually uses, before the budget clamps it. */
    private int lanesInPage = 1;

    /**
     * The branch HEAD was on when this page was measured.
     *
     * <p>Kept because the rows need the same answer the widths were computed from, and
     * asking the backend per row meant a {@code git} subprocess inside a cell's
     * {@code updateItem} - on the FX thread, per visible row, on every scroll. The metrics
     * object is already the per-page state a header and its rows share, so it is where the
     * page's branch belongs too.
     */
    private String currentBranch = "";
    private com.jfx.git.model.RefSnapshot refs = com.jfx.git.model.RefSnapshot.EMPTY;

    public GraphMetrics(GraphDensity density) {
        this(density, GraphNodeStyle.current());
    }

    public GraphMetrics(GraphDensity density, GraphNodeStyle nodeStyle) {
        this.density = density;
        this.nodeStyle = nodeStyle;
        this.maxLanes = GraphDensity.MAX_DRAWN_LANES;
        this.rail.set(density.railWidth());
        this.lane.set(density.graphWidth(1, nodeStyle, maxLanes));
    }

    /**
     * How many lane columns this page will draw before further lanes share the last one.
     *
     * <p>Derived from the width the list actually has rather than fixed, because the same
     * number cannot be right for a laptop and a 4K display. It matters most in
     * chronological order, which holds many more lanes open at once — clipped at a fixed
     * fourteen, that order looks worse than the one it replaced.
     */
    public int maxLanes() { return maxLanes; }

    /**
     * Tells the metrics how much room the list has, in pixels.
     *
     * <p>Returns whether the budget changed, so the caller can avoid rebuilding rows for a
     * resize that made no difference — which is most of them, since a window has to change
     * by a whole lane's worth before the answer moves.
     */
    public boolean setAvailableWidth(double available) {
        availableWidth = available;
        // The ref column's ceiling is a share of this width, so it moves with the window.
        // That is a column width, bound straight into the header and every row; nothing
        // has to be rebuilt for it, which is why it does not affect the return value.
        applyRail();
        if (laneBudgetOverride > 0) {
            // The user has said how wide they want the graph. A window resize must not
            // quietly overrule that; it only changes what "automatic" would have chosen.
            return false;
        }
        int updated = density.maxDrawnLanes(available, nodeStyle);
        if (updated == maxLanes) return false;
        maxLanes = updated;
        lane.set(density.graphWidth(lanesInPage, nodeStyle, maxLanes));
        refreshHidden();
        return true;
    }

    /** The width the list last reported, so clearing an override can recompute. */
    private double availableWidth;

    /** Lanes the user dragged the graph to, or 0 while the budget is automatic. */
    private int laneBudgetOverride;

    /**
     * Fixes the graph at {@code lanes} columns wide, however wide the window is.
     *
     * <p>This is what dragging the graph's edge sets. Note it is a <em>budget</em>, not a
     * width: the column is only ever as wide as the page's branching actually needs, so
     * dragging outward on a linear history moves nothing. That is the honest behaviour —
     * there are no lanes there to show — and it is why the handle snaps back to the
     * content rather than leaving an empty gutter.
     *
     * @return whether the budget changed, so the caller can skip a repaint
     */
    public boolean setLaneBudget(int lanes) {
        int clamped = Math.max(GraphDensity.MIN_DRAWN_LANES, Math.min(lanes, MAX_LANE_BUDGET));
        boolean changed = clamped != laneBudgetOverride || clamped != maxLanes;

        laneBudgetOverride = clamped;
        maxLanes = clamped;
        // Set even when the count did not change. A drag leaves the width part-way
        // between two lanes with the count already correct for it, and returning early
        // here left the column resting on that in-between width — the graph never snapped
        // when the pointer was released. Setting a property to what it already holds
        // notifies nobody, so doing it unconditionally costs nothing.
        lane.set(density.graphWidth(lanesInPage, nodeStyle, maxLanes));
        refreshHidden();
        return changed;
    }

    /**
     * Sets the lane column to an exact pixel width, while it is being dragged.
     *
     * <h2>Why the width is continuous but the lanes are not</h2>
     * The graph draws whole lanes: how many fit in the column is a division, and the
     * answer only changes as the edge crosses a multiple. The <em>column</em> has no such
     * constraint. Letting the width follow the pointer exactly, and letting the lane count
     * fall out of it, is what makes a drag move the boundary, the messages and the lanes
     * together rather than in eleven-pixel jumps.
     *
     * <p>Deliberately not remembered. This is what the graph looks like mid-gesture;
     * {@link #setLaneBudget} is what it settles on, and only that is worth storing.
     */
    public void previewLaneWidth(double pixels) {
        double perLane = density.laneWidth(nodeStyle);
        double min = GraphDensity.MIN_DRAWN_LANES * perLane + LANE_PADDING;
        double max = MAX_LANE_BUDGET * perLane + LANE_PADDING;
        double clamped = Math.max(min, Math.min(pixels, max));

        // Whole lanes only ever appear or vanish as the edge passes a multiple; between
        // those points the column simply gets wider.
        maxLanes = Math.max(GraphDensity.MIN_DRAWN_LANES,
                Math.min((int) Math.floor((clamped - LANE_PADDING) / perLane), MAX_LANE_BUDGET));
        laneBudgetOverride = maxLanes;
        lane.set(Math.min(clamped, density.graphWidth(lanesInPage, nodeStyle, MAX_LANE_BUDGET)));
        refreshHidden();
    }

    /** The padding {@link GraphDensity#graphWidth} adds beyond the lanes themselves. */
    static final double LANE_PADDING = 6;

    /** Hands the budget back to the window width. */
    public boolean clearLaneBudget() {
        if (laneBudgetOverride == 0) {
            return false;
        }
        laneBudgetOverride = 0;
        int updated = density.maxDrawnLanes(availableWidth, nodeStyle);
        if (updated == maxLanes) {
            return false;
        }
        maxLanes = updated;
        lane.set(density.graphWidth(lanesInPage, nodeStyle, maxLanes));
        refreshHidden();
        return true;
    }

    /** The budget the user set, or 0 while it follows the window. */
    public int laneBudget() {
        return laneBudgetOverride;
    }

    /** How many lanes the loaded page actually uses, before the budget clamps it. */
    public int lanesInPage() {
        return lanesInPage;
    }

    /** Beyond this the graph has stopped being followable, whatever the user drags. */
    static final int MAX_LANE_BUDGET = 40;

    /**
     * The node style this page is laid out for.
     *
     * <p>Read from here rather than from the preference directly, by every part of the
     * row. The style decides the lane pitch and whether an author column exists, so a cell
     * that answered the question separately from its header could disagree with it — and
     * did: the header reserved eleven-pixel lanes while the cell drew eighteen-pixel ones,
     * and the graph spilled into the commit message.
     */
    public GraphNodeStyle nodeStyle() { return nodeStyle; }

    /**
     * How many lanes the page has beyond what the budget draws one by one: they share
     * the graph's last column, which shows a dotted marker instead of any one of them.
     * 0 when every lane has a column of its own.
     */
    public javafx.beans.property.ReadOnlyIntegerProperty hiddenLanesProperty() {
        return hiddenLanes.getReadOnlyProperty();
    }

    public int hiddenLanes() { return hiddenLanes.get(); }

    /** Whether lanes from {@code totalLanes} spill past the budget into a shared last column. */
    public boolean overflows(int totalLanes) {
        return totalLanes > maxLanes;
    }

    /** Re-derives {@link #hiddenLanes} after the lane count or the budget moved. */
    private void refreshHidden() {
        hiddenLanes.set(lanesInPage > maxLanes ? lanesInPage - (maxLanes - 1) : 0);
    }

    public ReadOnlyDoubleProperty railWidthProperty() { return rail.getReadOnlyProperty(); }

    public ReadOnlyDoubleProperty laneWidthProperty() { return lane.getReadOnlyProperty(); }

    public double railWidth() { return rail.get(); }

    /**
     * Recomputes both widths for a freshly loaded page.
     *
     * @param rows          the rows about to be shown
     * @param currentBranch the branch HEAD is on, which decides the HEAD marker's width
     */
    public void update(List<GitGraphRow> rows, String currentBranch) {
        int lanes = 1;
        String widest = "";

        for (GitGraphRow row : rows) {
            lanes = Math.max(lanes, row.getTotalLanes());

            var commit = row.getCommit();
            if (commit == null) continue;
            for (RefRail.Chip chip : RefRail.chipsOf(commit, currentBranch)) {
                // Compared by length first and measured once at the end: measuring every
                // chip on a thousand-commit page would cost a thousand text layouts to
                // arrive at a single number.
                String text = chipText(chip);
                if (text.length() > widest.length()) widest = text;
            }
        }

        lanesInPage = lanes;
        this.currentBranch = currentBranch == null ? "" : currentBranch;
        lane.set(density.graphWidth(lanes, nodeStyle, maxLanes));
        refreshHidden();

        railWanted = widest.isEmpty() ? 0 : measure(widest) + chipPadding();
        applyRail();
    }

    /**
     * Sets the ref column's width from what its names need and what the window can spare.
     *
     * <p>It is as wide as its widest name, up to a ceiling - the rail is paid for out of
     * every row, and one very long name must not tax them all. That ceiling used to be the
     * density's fixed 64 or 76 pixels however wide the window was, which fits "main" and
     * almost nothing else: on any repository with namespaced branches every chip in the
     * column read "featu…", and a column of identical truncations names nothing. So the
     * ceiling is now a share of the list's width, between the density's figure and
     * {@link #MAX_AUTO_RAIL}. A repository whose branches are all called "dev" still gets
     * the narrow column and hands the difference to the commit messages.
     */
    private void applyRail() {
        if (railOverride > 0) {
            rail.set(railOverride);
            return;
        }
        double ceiling = clamp(availableWidth * RAIL_SHARE, density.railWidth(), MAX_AUTO_RAIL);
        rail.set(clamp(railWanted, MIN_RAIL, ceiling));
    }

    /** Shows the ref column at {@code width} while its edge is being dragged; nothing is kept. */
    public void previewRailWidth(double width) {
        rail.set(clamp(width, MIN_RAIL, MAX_RAIL));
    }

    /**
     * Fixes the ref column at {@code width}, however wide the window or the names are.
     *
     * <p>What releasing a drag of the column's edge sets, and what is restored at startup.
     */
    public void setRailWidth(double width) {
        railOverride = clamp(width, MIN_RAIL, MAX_RAIL);
        applyRail();
    }

    /** Hands the ref column's width back to {@link #applyRail}. */
    public void clearRailWidth() {
        railOverride = 0;
        applyRail();
    }

    /** The width the ref column is fixed at, or 0 while it sizes itself. */
    public double railWidthOverride() {
        return railOverride;
    }

    /** The branch HEAD is on, as of the last {@link #update}; never {@code null}, blank while detached. */
    public String currentBranch() {
        return currentBranch;
    }

    /**
     * The newest ref snapshot, for what a row's menu needs to know about the refs beyond
     * its own commit - the branch names a new branch must not take.
     */
    public void setRefs(com.jfx.git.model.RefSnapshot refs) {
        this.refs = refs == null ? com.jfx.git.model.RefSnapshot.EMPTY : refs;
    }

    /** The local branches' names, from the newest ref snapshot. */
    public List<String> localBranches() {
        return refs.localBranchNames();
    }

    /** The configured remotes, from the newest ref snapshot. */
    public List<String> remoteNames() {
        return refs.remoteNames();
    }

    /** What a chip will actually read, marks included, so the measurement matches. */
    static String chipText(RefRail.Chip chip) {
        String text = chip.name();
        if (chip.tracked()) text = text + " ⇅";
        if (chip.head()) text = "● " + text;
        return text;
    }

    /** Chip border, inner padding and the rail's own padding either side. */
    private static double chipPadding() {
        return 26;
    }

    private static double measure(String text) {
        if (text.isEmpty()) return 0;
        Text probe = new Text(text);
        // Must match .ref-chip in lyra.css: a measurement taken at the wrong size is
        // worse than no measurement, because it looks deliberate.
        probe.setFont(Font.font("System", FontWeight.BOLD, 11));
        return probe.getLayoutBounds().getWidth();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
