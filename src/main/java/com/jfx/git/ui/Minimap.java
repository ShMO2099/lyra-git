package com.jfx.git.ui;

import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;

import org.fxmisc.richtext.model.Paragraph;
import org.fxmisc.richtext.model.StyleSpan;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A minimap of the whole file beside its scrollbar - the one VS Code shows - for the
 * File view: every line drawn two pixels tall, one pixel per character, in the colours
 * the editor itself paints them, with a translucent slider over the part on screen.
 *
 * <p><b>Painted from the surface's own document.</b> The text and the style spans come
 * straight from the {@link DiffTextArea}'s paragraphs, so what the minimap shows is
 * what is on screen - the colours the syntax lane has delivered so far, the buffer as
 * the user is typing it - with no second model to keep in step. Only the rows the
 * minimap window covers are read on a repaint, so a twenty-thousand-line file costs the
 * same as a two-hundred-line one, and a line is read to {@link #MAX_COLUMNS} characters
 * and no further, so a minified single line costs the same as a short one.
 *
 * <p><b>Colours without a colour table.</b> There is no way to read a theme's
 * {@code -color-*} or {@code -jfx-syntax-*} token from Java, and copying the editor's
 * palette into code would drift from it. Instead a hidden probe {@link Text} with the
 * editor's own style classes ({@code text}, then the token's) has its CSS applied and
 * its fill read back: exactly the paint the editor uses for those classes, under
 * whichever theme and palette (colourblind included) is in force, because the probe sits
 * under the same {@code .code-area}-classed ancestor rules apply through. Cached per
 * class set, dropped on a theme change.
 *
 * <p><b>Geometry by row</b>, the same row model the scrollbar ({@link DiffScrollMap})
 * uses and for the same reason: Flowless's pixel estimates drift. When the file's
 * minimap is taller than the pane, the minimap scrolls in proportion, as VS Code's does,
 * so the slider still runs the full height.
 *
 * <p>The slider shows while the pointer is over the minimap (VS Code's default) and
 * while it is being dragged; see {@code diff-styles.css}.
 */
public final class Minimap extends Region {

    /** Width of the strip; VS Code renders up to 120 columns at one pixel each, and 100 reads the same. */
    static final double WIDTH = 100;
    /** Pixels per line, and per character column, at 100%. */
    static final int ROW_PX = 2;
    static final int COL_PX = 1;
    /** Characters read per line; the strip is this wide at one pixel each. */
    static final int MAX_COLUMNS = 100;
    /** Text is drawn a little translucent, as VS Code draws it, so the change tint and the slider read through it. */
    private static final double TEXT_ALPHA = 0.7;
    private static final double TINT_ALPHA = 0.35;
    private static final PseudoClass DRAGGING = PseudoClass.getPseudoClass("dragging");

    private final Canvas canvas = new Canvas();
    private final Scale canvasScale = new Scale(1, 1);
    private final Region slider = new Region();

    /** The colour probes; see the class javadoc. A Group takes no part in layout. */
    private final Group probes = new Group();
    private final Text textProbe = new Text("x");
    private final Region addProbe = new Region();
    private final Region deleteProbe = new Region();
    private final Map<String, Paint> fills = new HashMap<>();

    private DiffTextArea area;
    private Set<Integer> addedRows = Set.of();
    private Set<Integer> deletedRows = Set.of();
    /** Something about the content or its colours changed since the last paint. */
    private boolean dirty = true;
    /** The content pixel the last paint put at the top of the canvas; -1 for none. */
    private double paintedTop = -1;
    private final InvalidationListener follow = obs -> requestLayout();
    /** Pointer offset into the slider while dragging; negative when not. */
    private double grab = -1;

    /** Held so the weak registration below has something to keep alive. */
    private final ChangeListener<ThemeManager.Theme> themeListener = (obs, old, theme) -> {
        invalidateColours();
        markDirty();
    };

    public Minimap() {
        getStyleClass().add("minimap");
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);

        canvas.setManaged(false);
        canvas.setMouseTransparent(true);
        canvas.getTransforms().add(canvasScale);
        slider.getStyleClass().add("minimap-slider");
        slider.setManaged(false);
        slider.setMouseTransparent(true);

        // The editor's token rules are ".code-area .text.tm-*", so the probes need a
        // code-area ancestor - but only while a colour is being read (see fillFor):
        // carried permanently, the class made every "one text surface" lookup in the
        // tests count the probes too.
        probes.setManaged(false);
        probes.setMouseTransparent(true);
        probes.setOpacity(0);
        textProbe.getStyleClass().add("text");
        addProbe.getStyleClass().add("diff-scroll-map-add");
        deleteProbe.getStyleClass().add("diff-scroll-map-delete");
        probes.getChildren().addAll(textProbe, addProbe, deleteProbe);

        getChildren().addAll(canvas, slider, probes);

        ThemeManager.currentProperty().addListener(new WeakChangeListener<>(themeListener));

        addEventHandler(MouseEvent.MOUSE_PRESSED, this::pressed);
        addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            grab = -1;
            slider.pseudoClassStateChanged(DRAGGING, false);
        });
        addEventHandler(ScrollEvent.SCROLL, e -> {
            if (area != null) {
                area.scrollYBy(-e.getDeltaY());
                e.consume();
            }
        });
    }

    /** Follows {@code newArea}; a no-op when already following it. */
    public void attach(DiffTextArea newArea) {
        if (area == newArea) {
            return;
        }
        if (area != null) {
            area.estimatedScrollYProperty().removeListener(follow);
            area.heightProperty().removeListener(follow);
            area.widthProperty().removeListener(follow);
            area.tailPaddingProperty().removeListener(follow);
        }
        area = newArea;
        newArea.estimatedScrollYProperty().addListener(follow);
        newArea.heightProperty().addListener(follow);
        newArea.widthProperty().addListener(follow);
        newArea.tailPaddingProperty().addListener(follow);
        markDirty();
    }

    /**
     * Which rows this file's changes fall on, to tint - the same sets the scroll map
     * marks in File view. Empty when the hunks on hand do not describe this content.
     */
    public void setChangedRows(Set<Integer> added, Set<Integer> deleted) {
        this.addedRows = added == null ? Set.of() : added;
        this.deletedRows = deleted == null ? Set.of() : deleted;
        markDirty();
    }

    /** The rows that would be tinted, for tests. */
    Set<Integer> changedRows() {
        Set<Integer> all = new HashSet<>(addedRows);
        all.addAll(deletedRows);
        return all;
    }

    /** Repaints on the next layout pass; cheap to call often. */
    public void markDirty() {
        dirty = true;
        requestLayout();
    }

    /** Forgets every resolved colour, for after the theme or the palette changed. */
    public void invalidateColours() {
        fills.clear();
        fillsByIdentity.clear();
        addFill = null;
        deleteFill = null;
    }

    /** The change tints, read off their probes once per theme rather than on every paint. */
    private Paint addFill;
    private Paint deleteFill;

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /** The surface in rows; see {@link DiffScrollMap} for why rows and not pixels. */
    private record Geometry(int rows, double rowsPerViewport, double virtualRows, double firstRow) {
        double maxFirst() {
            return Math.max(0, virtualRows - rowsPerViewport);
        }

        /** The whole file's height on the minimap. */
        double contentHeight() {
            return virtualRows * ROW_PX;
        }

        double sliderHeight() {
            return rowsPerViewport * ROW_PX;
        }

        /** Where the slider sits in a pane {@code height} tall. */
        double sliderY(double height) {
            if (contentHeight() <= height) {
                return firstRow * ROW_PX;
            }
            double travel = height - sliderHeight();
            return maxFirst() <= 0 ? 0 : travel * Math.min(1, firstRow / maxFirst());
        }

        /** The content pixel at the top of the canvas: 0 until the file outgrows the pane, then in step with the scroll. */
        double top(double height) {
            return contentHeight() <= height ? 0 : firstRow * ROW_PX - sliderY(height);
        }
    }

    private Geometry geometry() {
        if (area == null) {
            return new Geometry(0, 0, 0, 0);
        }
        double rowHeight = Math.max(1, area.rowHeight());
        int rows = area.getParagraphs().size();
        return new Geometry(rows, area.getHeight() / rowHeight,
                rows + area.tailPadding() / rowHeight, area.firstVisibleRow());
    }

    /** Buffer pixels per logical pixel: the UI zoom times the display's own scale, so one-pixel columns stay one device pixel wide. */
    private double bufferScale() {
        double render = getScene() != null && getScene().getWindow() != null
                ? getScene().getWindow().getRenderScaleX() : 1;
        return Math.max(1, UiZoom.factor() * render);
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        Geometry geometry = geometry();

        double scale = bufferScale();
        double bufferWidth = Math.ceil(width * scale);
        double bufferHeight = Math.ceil(height * scale);
        if (canvas.getWidth() != bufferWidth || canvas.getHeight() != bufferHeight) {
            canvas.setWidth(bufferWidth);
            canvas.setHeight(bufferHeight);
            dirty = true;
        }
        if (canvasScale.getX() != 1 / scale) {
            canvasScale.setX(1 / scale);
            canvasScale.setY(1 / scale);
        }
        canvas.relocate(0, 0);

        double top = geometry.top(height);
        if (dirty || top != paintedTop) {
            paint(geometry, top, scale, bufferWidth, bufferHeight);
            paintedTop = top;
            dirty = false;
        }

        boolean scrollable = geometry.maxFirst() > 0.01;
        slider.setVisible(scrollable && height > 0);
        if (scrollable) {
            slider.resizeRelocate(0, snapPositionY(geometry.sliderY(height)), width,
                    Math.min(height, geometry.sliderHeight()));
        }
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    private void paint(Geometry geometry, double top, double scale, double bufferWidth, double bufferHeight) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, bufferWidth, bufferHeight);
        if (area == null || geometry.rows() == 0) {
            return;
        }
        // Whole device pixels: a 1x2 block under a fractional transform is what blurs
        // at 125% and 150%.
        int rowPx = Math.max(1, (int) Math.round(ROW_PX * scale));
        int colPx = Math.max(1, (int) Math.round(COL_PX * scale));
        double topPx = Math.round(top * scale);

        int first = Math.max(0, (int) Math.floor(top / ROW_PX));
        int last = Math.min(geometry.rows() - 1, (int) Math.ceil((top + getHeight()) / ROW_PX));
        // Two CSS passes per paint, and a paint per scroll pulse, until this kept them.
        // Kept only once they came from a scene's stylesheets, not the grey fallback.
        Paint addFill = this.addFill;
        Paint deleteFill = this.deleteFill;
        if (addFill == null || deleteFill == null) {
            addFill = backgroundOf(addProbe);
            deleteFill = backgroundOf(deleteProbe);
            if (getScene() != null) {
                this.addFill = addFill;
                this.deleteFill = deleteFill;
            }
        }
        for (int i = first; i <= last; i++) {
            double y = i * rowPx - topPx;
            boolean added = addedRows.contains(i);
            boolean deleted = deletedRows.contains(i);
            if (added || deleted) {
                gc.setGlobalAlpha(TINT_ALPHA);
                gc.setFill(added ? addFill : deleteFill);
                gc.fillRect(0, y, bufferWidth, rowPx);
            }
            gc.setGlobalAlpha(TEXT_ALPHA);
            paintLine(gc, area.getParagraph(i), y, rowPx, colPx);
        }
        gc.setGlobalAlpha(1);
    }

    /** Runs of printing characters as blocks, in each span's resolved fill. */
    private void paintLine(GraphicsContext gc, Paragraph<?, ?, Collection<String>> paragraph,
                           double y, int rowPx, int colPx) {
        String text = paragraph.getText();
        int column = 0;
        int offset = 0;
        for (StyleSpan<Collection<String>> span : paragraph.getStyleSpans()) {
            int end = Math.min(text.length(), offset + span.getLength());
            Paint fill = fillFor(span.getStyle());
            int runStart = -1;
            for (int k = offset; k < end && column < MAX_COLUMNS; k++) {
                char ch = text.charAt(k);
                if (ch == '\t') {
                    runStart = flush(gc, fill, runStart, column, y, rowPx, colPx);
                    column += 4 - column % 4;
                    continue;
                }
                if (isInk(ch)) {
                    if (runStart < 0) {
                        runStart = column;
                    }
                } else {
                    runStart = flush(gc, fill, runStart, column, y, rowPx, colPx);
                }
                column++;
            }
            flush(gc, fill, runStart, column, y, rowPx, colPx);
            offset = end;
            if (column >= MAX_COLUMNS) {
                return;
            }
        }
    }

    /** Draws the run [runStart, column) if there is one; returns "no run". */
    private static int flush(GraphicsContext gc, Paint fill, int runStart, int column,
                             double y, int rowPx, int colPx) {
        if (runStart >= 0 && column > runStart) {
            gc.setFill(fill);
            gc.fillRect(runStart * colPx, y, (column - runStart) * colPx, rowPx);
        }
        return -1;
    }

    /** A character that leaves a mark - not space, and not the glyph standing in for one. */
    private static boolean isInk(char ch) {
        return !Character.isWhitespace(ch)
                && ch != WhitespaceMapping.SPACE_MARKER.charAt(0)
                && ch != WhitespaceMapping.TAB_MARKER.charAt(0);
    }

    // ------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------

    /**
     * The fill the editor paints text carrying {@code classes} with. Package-private so
     * a test can hold it against a rendered {@link Text}'s own fill.
     */
    Paint fillFor(Collection<String> classes) {
        // By identity first: the text area shares one collection per combination of
        // classes (see DiffTextArea.spans), so a paint resolves each combination once
        // instead of filtering, sorting and joining the classes of every span it draws.
        Paint known = fillsByIdentity.get(classes);
        if (known != null) {
            return known;
        }
        Paint fill = fillForKey(classes);
        if (fillsByIdentity.size() > MAX_IDENTITY_FILLS) {
            fillsByIdentity.clear();
        }
        fillsByIdentity.put(classes, fill);
        return fill;
    }

    /** Fills by style collection, compared by identity; dropped with {@link #fills}. */
    private final Map<Collection<String>, Paint> fillsByIdentity = new java.util.IdentityHashMap<>();
    /** A guard against collections that are not shared growing it without end. */
    private static final int MAX_IDENTITY_FILLS = 4_096;

    private Paint fillForKey(Collection<String> classes) {
        List<String> tokens = classes.stream()
                .filter(c -> c.startsWith("tm-") || c.startsWith("syntax-"))
                .sorted()
                .toList();
        String key = String.join(" ", tokens);
        Paint cached = fills.get(key);
        if (cached != null) {
            return cached;
        }
        textProbe.getStyleClass().setAll("text");
        textProbe.getStyleClass().addAll(tokens);
        probes.getStyleClass().setAll("code-area");
        Paint fill;
        try {
            textProbe.applyCss();
            // Read before the class comes off again: taking it off re-applies CSS to the
            // probe at once, and with no rule matching any more the fill is back to
            // Text's default black.
            fill = textProbe.getFill();
        } finally {
            probes.getStyleClass().clear();
        }
        if (fill == null) {
            fill = Color.GRAY;
        }
        fills.put(key, fill);
        return fill;
    }

    private static Paint backgroundOf(Region probe) {
        probe.applyCss();
        Background background = probe.getBackground();
        if (background == null || background.getFills().isEmpty()) {
            return Color.GRAY;
        }
        return background.getFills().get(0).getFill();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private void pressed(MouseEvent e) {
        if (area == null || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        Geometry geometry = geometry();
        double height = getHeight();
        double sliderY = geometry.sliderY(height);
        if (slider.isVisible() && e.getY() >= sliderY && e.getY() <= sliderY + geometry.sliderHeight()) {
            grab = e.getY() - sliderY;
            slider.pseudoClassStateChanged(DRAGGING, true);
        } else {
            // Elsewhere on the map: bring that line to the middle of the viewport.
            double line = (e.getY() + geometry.top(height)) / ROW_PX;
            int row = (int) Math.round(Math.max(0, Math.min(geometry.rows() - 1, line)));
            area.showParagraphAtCenter(row);
            grab = -1;
        }
        e.consume();
    }

    private void dragged(MouseEvent e) {
        if (grab < 0 || area == null) {
            return;
        }
        Geometry geometry = geometry();
        double height = getHeight();
        double sliderTop = e.getY() - grab;
        double firstRow;
        if (geometry.contentHeight() <= height) {
            firstRow = sliderTop / ROW_PX;
        } else {
            double travel = height - geometry.sliderHeight();
            firstRow = travel <= 0 ? 0 : Math.max(0, Math.min(1, sliderTop / travel)) * geometry.maxFirst();
        }
        area.showRowAtTop(firstRow);
        e.consume();
    }

    /** The slider, for tests. */
    Region slider() {
        return slider;
    }

    /** The canvas, for tests. */
    Canvas canvas() {
        return canvas;
    }
}
