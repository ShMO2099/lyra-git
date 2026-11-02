package com.jfx.git.ui;

import javafx.beans.InvalidationListener;
import javafx.css.PseudoClass;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * A diff surface's vertical scrollbar: the thumb, and behind it marks for where the
 * additions and deletions are - VS Code's overview ruler and slider as one control,
 * the commercial clients' "scroll bar map". Deliberately its own small class, not folded into
 * {@link DiffViewer}: {@link com.jfx.git.ui.views.PrReviewView} builds the same
 * kind of surface from the same {@link DiffTextArea}/{@link DiffRows.Row} pieces and
 * needs the identical bar.
 *
 * <p><b>Instead of the native scrollbar, not beside or behind it.</b> {@link #attachTo}
 * turns the surface's own vertical bar off. An earlier version sat behind the real
 * {@link javafx.scene.control.ScrollBar} and intercepted its events, and lost: marks
 * hidden by the opaque thumb, a track filter that also caught thumb-drag presses and
 * drove the position into a feedback loop. There is nothing to fight now - this is the
 * only thing that scrolls the surface from the right-hand edge, and the native bar's
 * only remaining role, the mouse wheel, was never its own (Flowless's VirtualFlow handles
 * the wheel itself, which is how split view's left column has always scrolled with no
 * bar at all).
 *
 * <p><b>Geometry by row, never by pixel estimate.</b> Flowless's
 * {@code estimatedScrollY} and {@code totalHeightEstimate} are averages over whichever
 * cells have been measured so far: they drift as the user scrolls and jump when a tall
 * cell is first seen, which is the native bar's own twitch on a long file. Everything
 * here is placed in rows - {@link DiffTextArea#firstVisibleRow()}, the rows that fit the
 * viewport, the row count plus the blank tail after the last line - so a mark sits under
 * the thumb exactly when its row is on screen, and the thumb is positioned in one place,
 * {@link #layoutChildren()}, from that model. Mouse handlers only scroll the surface;
 * the scroll's own property change is what moves the thumb back.
 *
 * <p>Marks carry their own {@code diff-scroll-map-add}/{@code diff-scroll-map-delete}
 * style classes - not the gutter's {@code diff-gutter-add}/{@code diff-gutter-delete} -
 * rather than picking new colours in Java: there is no existing way to read a theme's
 * {@code -color-*} custom property from code, and inventing one just for this would
 * silently drift from the colourblind palette the moment it changes. The gutter's own
 * classes map to its "subtle" background tier, which reads as too faint for a strip this
 * thin; the scroll-map classes instead reuse the same "emphasis"/"edge" tier the diff
 * rows' own saturated left border already uses (see {@code diff-styles.css}), and the
 * thumb is translucent so they show through it.
 *
 * <p>Two independent lanes - deletions on the left half, additions on the right - rather
 * than one column: in {@link DiffViewer}'s split layout, a pure deletion is a real row
 * only on the left column and a pad on the right (and vice versa for a pure addition),
 * so a single merged lane would make half of every delete-only or add-only hunk
 * invisible on the map. {@link #setRows(List, List)}'s optional second list is that
 * other column, checked independently of the first.
 */
public final class DiffScrollMap extends Region {

    /** A single changed line must not vanish on a tall file; every real overview ruler
     *  makes the same trade. */
    private static final double MIN_MARK_HEIGHT = 1.5;
    /** A thumb short enough to lose is no thumb; VS Code draws its slider no shorter. */
    private static final double MIN_THUMB_HEIGHT = 20;
    /** On the thumb while the pointer holds it; hover alone is lost when a drag leaves the strip. */
    private static final PseudoClass DRAGGING = PseudoClass.getPseudoClass("dragging");

    /** One mark, kept as the run it stands for so a relayout need not re-derive it. */
    private record Mark(int start, int end, boolean add, Region node) {
    }

    /** The lanes of marks, under the thumb. */
    private final Pane lanes = new Pane();
    private final List<Mark> marks = new ArrayList<>();
    private final Region thumb = new Region();

    private DiffTextArea area;
    private final InvalidationListener relayout = obs -> requestLayout();

    private List<DiffRows.Row> rows = List.of();
    /** The diff's other column, in split layout; {@code null} outside it. */
    private List<DiffRows.Row> secondary;

    /**
     * Marks supplied as bare row indices rather than read off the rows themselves, with
     * {@link #markedRowCount} the row total they are indices into; {@code null}/-1 when
     * the map is reading the rows directly instead.
     *
     * <p>Needed because file and blame views render every row as plain context - their
     * rows come from {@link DiffRows#fileContent}/{@link DiffRows#plain}, which carry no
     * {@code +}/{@code -} marker for {@link #isAddAt}/{@link #isDeleteAt} to find - so
     * asking those rows where the changes are always answered "nowhere", and the strip
     * drew nothing at all in either mode. The caller knows where the changes are (from
     * the hunks behind the file) and hands that in directly.
     */
    private Set<Integer> markedAdds;
    private Set<Integer> markedDeletes;
    private int markedRowCount = -1;

    /** Pointer offset into the thumb while dragging; negative when not. */
    private double grabOffset = -1;
    /** The geometry the marks were last placed for, so a scroll pulse moves only the thumb. */
    private double marksPlacedFor = -1;

    public DiffScrollMap() {
        getStyleClass().add("diff-scroll-map");
        lanes.setManaged(false);
        lanes.setMouseTransparent(true);
        thumb.getStyleClass().add("diff-scroll-map-thumb");
        thumb.setManaged(false);
        thumb.setVisible(false);
        getChildren().addAll(lanes, thumb);

        setStripWidth(com.jfx.git.core.AppPreferences.diffScrollMapWidth());
        // The strip fills its row; the track inside it is placed against the text
        // surface's own top and height in layoutChildren. Binding the strip's height to
        // the surface's instead left it zero-tall when split view was first built: the
        // right column had no height yet, and the HBox does not revisit a child whose
        // preferred size changes while the HBox is itself mid-layout.

        addEventHandler(MouseEvent.MOUSE_PRESSED, this::pressed);
        addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            grabOffset = -1;
            thumb.pseudoClassStateChanged(DRAGGING, false);
        });
        // The wheel over the bar scrolls the text, as it does over any scrollbar. Same
        // sign convention as VirtualFlow's own handler.
        addEventHandler(ScrollEvent.SCROLL, e -> {
            if (area != null) {
                area.scrollYBy(-e.getDeltaY());
                e.consume();
            }
        });
    }

    /**
     * Re-points the bar at a new surface, replacing whatever it was attached to. The
     * surface sits in a {@link DiffScrollPane}, which has no vertical bar of its own -
     * this is it. A no-op when {@code newArea} is already the attached one, so a caller
     * can call this on every render.
     */
    public void attachTo(DiffTextArea newArea) {
        if (this.area == newArea) {
            return;
        }
        if (area != null) {
            area.estimatedScrollYProperty().removeListener(relayout);
            area.heightProperty().removeListener(relayout);
            area.widthProperty().removeListener(relayout);
            area.tailPaddingProperty().removeListener(relayout);
        }
        area = newArea;
        newArea.estimatedScrollYProperty().addListener(relayout);
        newArea.heightProperty().addListener(relayout);
        // Wrap changes row heights, and so how many rows fit the viewport.
        newArea.widthProperty().addListener(relayout);
        // The blank tail after the last line is part of the track.
        newArea.tailPaddingProperty().addListener(relayout);
        requestLayout();
    }

    /** Widens or narrows this bar - see {@link com.jfx.git.core.AppPreferences#diffScrollMapWidth()}. */
    public void setStripWidth(double width) {
        setPrefWidth(width);
        setMinWidth(width);
        setMaxWidth(width);
        requestLayout();
    }

    public void setRows(List<DiffRows.Row> rows) {
        setRows(rows, null);
    }

    /** @param secondary the diff's other column in split layout, or {@code null} in unified */
    public void setRows(List<DiffRows.Row> rows, List<DiffRows.Row> secondary) {
        this.rows = rows == null ? List.of() : rows;
        this.secondary = secondary;
        this.markedAdds = null;
        this.markedDeletes = null;
        this.markedRowCount = -1;
        rebuildMarks();
    }

    /**
     * Marks given positions directly, for a surface whose rows are all plain context and
     * so cannot say where the changes are themselves - the file and blame views, where
     * the changed lines are known from the hunks behind the file rather than from the
     * rows on screen. See {@link #markedAdds}.
     *
     * @param addRows    row indices to mark in the additions lane
     * @param deleteRows row indices to mark in the deletions lane
     * @param totalRows  how many rows the surface has, which the indices are relative to
     */
    public void setMarkedRows(Set<Integer> addRows, Set<Integer> deleteRows, int totalRows) {
        this.rows = List.of();
        this.secondary = null;
        this.markedAdds = addRows == null ? Set.of() : addRows;
        this.markedDeletes = deleteRows == null ? Set.of() : deleteRows;
        this.markedRowCount = Math.max(0, totalRows);
        rebuildMarks();
    }

    /** However many rows the marks are positioned against, whichever way they were set. */
    private int rowCount() {
        return markedRowCount >= 0 ? markedRowCount : rows.size();
    }

    /**
     * One node per contiguous run of marked rows, merged where runs share a track bucket;
     * positioned by {@link #layoutChildren()}.
     */
    private void rebuildMarks() {
        marks.clear();
        lanes.getChildren().clear();
        int count = rowCount();
        for (int[] run : bucketed(runsWhere(this::isDeleteAt, count), count)) {
            marks.add(mark(run, false));
        }
        for (int[] run : bucketed(runsWhere(this::isAddAt, count), count)) {
            marks.add(mark(run, true));
        }
        marksPlacedFor = -1;
        requestLayout();
    }

    /**
     * How finely a lane is divided before runs are merged: no track is this many pixels
     * tall, so two runs in one bucket would land on the same pixel row anyway.
     */
    static final int TRACK_BUCKETS = 1024;

    /**
     * {@code runs} with every run that shares a bucket with the one before it merged into
     * it. A thousand-hunk diff drew two thousand Regions - each with its own CSS pass and
     * layout - for a strip a few hundred pixels tall; this caps a lane at
     * {@link #TRACK_BUCKETS} nodes. A file of up to that many rows has a bucket per row,
     * so separate runs stay separate there exactly as before.
     */
    static List<int[]> bucketed(List<int[]> runs, int rowCount) {
        if (rowCount <= TRACK_BUCKETS || runs.size() <= 1) {
            return runs;
        }
        double rowsPerBucket = (double) rowCount / TRACK_BUCKETS;
        List<int[]> merged = new ArrayList<>();
        int[] current = null;
        int currentLastBucket = -1;
        for (int[] run : runs) {
            int firstBucket = (int) (run[0] / rowsPerBucket);
            if (current != null && firstBucket <= currentLastBucket) {
                current[1] = Math.max(current[1], run[1]);
            } else {
                current = new int[]{run[0], run[1]};
                merged.add(current);
            }
            currentLastBucket = (int) ((current[1] - 1) / rowsPerBucket);
        }
        return merged;
    }

    private Mark mark(int[] run, boolean add) {
        Region node = new Region();
        node.getStyleClass().add(add ? "diff-scroll-map-add" : "diff-scroll-map-delete");
        node.setManaged(false);
        lanes.getChildren().add(node);
        return new Mark(run[0], run[1], add, node);
    }

    private boolean isDeleteAt(int index) {
        if (markedDeletes != null) {
            return markedDeletes.contains(index);
        }
        return rows.get(index).isDelete()
                || (secondary != null && index < secondary.size() && secondary.get(index).isDelete());
    }

    private boolean isAddAt(int index) {
        if (markedAdds != null) {
            return markedAdds.contains(index);
        }
        return rows.get(index).isAdd()
                || (secondary != null && index < secondary.size() && secondary.get(index).isAdd());
    }

    /** Contiguous [start, end) index ranges where {@code test} holds. */
    private static List<int[]> runsWhere(IntPredicate test, int count) {
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < count; i++) {
            boolean match = test.test(i);
            if (match && start < 0) {
                start = i;
            } else if (!match && start >= 0) {
                runs.add(new int[]{start, i});
                start = -1;
            }
        }
        if (start >= 0) {
            runs.add(new int[]{start, count});
        }
        return runs;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /**
     * The surface in rows.
     *
     * @param virtualRows     the paragraphs plus the blank tail after the last one,
     *                        as rows - what the whole track stands for
     * @param rowsPerViewport how many rows the viewport shows at once
     * @param firstRow        the row at the top of the viewport, fractional
     */
    private record Geometry(double virtualRows, double rowsPerViewport, double firstRow) {
        /** The furthest down the top of the viewport can go. */
        double maxFirst() {
            return Math.max(0, virtualRows - rowsPerViewport);
        }

        boolean scrollable() {
            return maxFirst() > 0.01;
        }
    }

    private Geometry geometry() {
        if (area == null) {
            int count = rowCount();
            return new Geometry(count, count, 0);
        }
        double rowHeight = Math.max(1, area.rowHeight());
        double rowsPerViewport = area.getHeight() / rowHeight;
        double virtualRows = area.getParagraphs().size() + area.tailPadding() / rowHeight;
        return new Geometry(virtualRows, rowsPerViewport, area.firstVisibleRow());
    }

    /**
     * Where the track sits inside the strip: level with the text surface, as tall as it.
     *
     * <p>The strip fills its row, but the surface it scrolls may start lower (split
     * view's column header sits above it) and end higher (a native horizontal bar
     * below it), so the track is placed against the surface's own bounds rather than
     * the strip's. Read from the scene positions once both are laid out - the surface
     * precedes the strip in its row, so by the strip's own layout it always is.
     */
    private double trackTop() {
        if (area == null || area.getScene() == null || getScene() == null) {
            return 0;
        }
        javafx.geometry.Bounds surface = area.localToScene(area.getBoundsInLocal());
        javafx.geometry.Bounds mine = localToScene(getBoundsInLocal());
        if (surface == null || mine == null) {
            return 0;
        }
        return Math.max(0, surface.getMinY() - mine.getMinY());
    }

    private double trackHeight() {
        return area == null ? getHeight() : Math.min(getHeight(), area.getHeight());
    }

    /**
     * Places every mark and the thumb from the row model. The one place the thumb is
     * positioned: a drag scrolls the surface, the surface's scroll property invalidates
     * this, and the thumb follows - so the thumb can never disagree with the text.
     */
    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double top = trackTop();
        double height = trackHeight();
        lanes.resizeRelocate(0, top, width, Math.max(0, height));
        Geometry geometry = geometry();
        if (height <= 0 || geometry.virtualRows() <= 0) {
            thumb.setVisible(false);
            return;
        }
        // Marks depend on the track and the row total, not on the scroll position: a
        // pulse that only scrolled moves the thumb and leaves thousands of marks alone.
        double placement = geometry.virtualRows() * 1e7 + height * 1e3 + width;
        if (placement != marksPlacedFor) {
            double laneWidth = width / 2;
            for (Mark mark : marks) {
                double markTop = height * mark.start() / geometry.virtualRows();
                double markBottom = height * mark.end() / geometry.virtualRows();
                double markHeight = Math.max(MIN_MARK_HEIGHT, markBottom - markTop);
                double center = (markTop + markBottom) / 2;
                double y = Math.min(height - markHeight, Math.max(0, center - markHeight / 2));
                mark.node().resizeRelocate(mark.add() ? laneWidth : 0, y, laneWidth, markHeight);
            }
            marksPlacedFor = placement;
        }
        positionThumb(geometry, width, top, height);
    }

    private void positionThumb(Geometry geometry, double width, double top, double height) {
        if (!geometry.scrollable()) {
            thumb.setVisible(false);
            return;
        }
        double thumbHeight = Math.min(height,
                Math.max(MIN_THUMB_HEIGHT, height * geometry.rowsPerViewport() / geometry.virtualRows()));
        double travel = height - thumbHeight;
        // Unclamped this is height x firstRow / virtualRows - the same line the marks are
        // on - and a thumb held at its minimum height still reaches both ends.
        double y = travel * Math.min(1, geometry.firstRow() / geometry.maxFirst());
        thumb.resizeRelocate(0, snapPositionY(top + y), width, thumbHeight);
        thumb.setVisible(true);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private void pressed(MouseEvent e) {
        if (area == null || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        Geometry geometry = geometry();
        if (!geometry.scrollable()) {
            return;
        }
        double y = e.getY() - trackTop();
        if (thumb.isVisible() && thumb.getBoundsInParent().contains(e.getX(), e.getY())) {
            grabOffset = e.getY() - thumb.getLayoutY();
        } else {
            // On the track: the thumb comes to the pointer, centred on it, and the press
            // carries on as a drag from there - a jump, not a page, since the marks
            // behind the track are what the user is aiming at.
            grabOffset = thumb.getHeight() / 2;
            scrollThumbTo(y - grabOffset, geometry);
        }
        thumb.pseudoClassStateChanged(DRAGGING, true);
        e.consume();
    }

    private void dragged(MouseEvent e) {
        if (grabOffset < 0 || area == null) {
            return;
        }
        scrollThumbTo(e.getY() - trackTop() - grabOffset, geometry());
        e.consume();
    }

    /** Scrolls the surface so that the thumb's top edge would land {@code thumbY} down the track. */
    private void scrollThumbTo(double thumbY, Geometry geometry) {
        double travel = trackHeight() - thumb.getHeight();
        if (travel <= 0) {
            return;
        }
        double fraction = Math.max(0, Math.min(1, thumbY / travel));
        area.showRowAtTop(fraction * geometry.maxFirst());
    }
}
