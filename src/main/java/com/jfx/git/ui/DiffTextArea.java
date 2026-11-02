package com.jfx.git.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.control.IndexRange;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.model.TwoDimensional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The read-only text surface the diff viewer renders into.
 *
 * <p>Beyond being read-only it carries one behaviour {@link StyleClassedTextArea} cannot:
 * <b>copying with whitespace shown</b>. Whitespace markers have to be substituted into
 * the buffer to be drawn at all, which means the buffer no longer holds what the user
 * selected - a plain copy would hand back {@code ·} and {@code →} where there were
 * spaces and tabs, and pasting that into a file corrupts it. Each paragraph keeps its
 * original text alongside a display-to-source offset map, and {@link #copy()} rebuilds
 * the selection from those.
 *
 * <p>Nothing here computes a height. The area is virtualized inside a scroll pane, so
 * wrapped lines simply take the room they need.
 */
public class DiffTextArea extends StyleClassedTextArea {

    /** The size {@link EditorZoom}'s 100% level means; overridden in {@code diff-styles.css}
     * no longer, so this is the one place that number lives. */
    private static final int BASE_FONT_SIZE_PX = 13;

    /** One entry per paragraph, holding its source text and display-to-source map. */
    private final List<WhitespaceMapping> paragraphs = new ArrayList<>();
    /** Skips the remapping entirely when nothing was substituted. */
    private boolean substituted;

    /** Held so the weak registration in the constructor has something to keep alive. */
    private final javafx.beans.value.ChangeListener<Number> zoomListener = (obs, old, value) -> {
        applyInlineStyle();
        // The row height the tail is measured against changes with the font.
        scheduleTail();
    };

    /** Whether the last line may be scrolled to the top; see {@link #setScrollBeyondLastLine}. */
    private boolean scrollBeyondLastLine;
    /**
     * Re-measures the tail after the viewport or the rows change - later, never in the
     * same call. Both fire from inside a layout pass (the scroll pane sizing this area,
     * Flowless placing cells), and swapping a stylesheet from inside Flowless's own
     * layout left its cell list half-built: a {@code NoSuchElementException} out of
     * {@code Navigator.fillViewportFrom} on the next split render.
     */
    private final javafx.beans.InvalidationListener tailListener = obs -> scheduleTail();
    /**
     * {@link #tailListener} for the end of each layout pass. A change listener, not an
     * invalidation one: an invalidation listener fires again only once someone has read
     * the property since, and nothing here reads {@code needsLayout} - so whether the
     * pass after a zoom re-measured depended on unrelated code having looked. The zoom's
     * own re-measure lands before that pass, against cells rebuilt but not yet styled
     * (default-font rows), and the tail kept that wrong row height.
     */
    private final javafx.beans.value.ChangeListener<Boolean> layoutListener = (obs, was, now) -> scheduleTail();
    private boolean tailScheduled;

    /** Pseudo-class RichTextFX puts on the last paragraph's box; see {@link #rowHeight()}. */
    private static final javafx.css.PseudoClass LAST_PARAGRAPH =
            javafx.css.PseudoClass.getPseudoClass("last-paragraph");

    /**
     * Blank space after the last line, in pixels, so that line can be scrolled to the
     * top of the viewport; 0 unless {@link #setScrollBeyondLastLine} asked for it. A
     * property, because the scrollbar's geometry includes it and must follow it.
     */
    private final javafx.beans.property.ReadOnlyDoubleWrapper tail =
            new javafx.beans.property.ReadOnlyDoubleWrapper(this, "tailPadding", 0);

    public DiffTextArea() {
        // Undo history only while the area can be typed into. Every render replaces the
        // whole document, and a read-only surface kept each one as an undoable change -
        // the previous file's full text and styles, retained for as long as the area
        // lived, growing with every file viewed. Becoming editable starts a fresh history,
        // which is also what an editing session wants: Ctrl+Z must not walk back past
        // the file into whatever was rendered before it.
        editableProperty().addListener((obs, was, now) -> setUndoManager(now
                ? org.fxmisc.richtext.util.UndoUtils.defaultUndoManager(this)
                : org.fxmisc.richtext.util.UndoUtils.noOpUndoManager()));
        // Editable by default in RichTextFX, so this flips it and the listener installs
        // the no-op manager in place of the default one.
        setEditable(false);
        getStyleClass().add("code-area");

        // The caret blinks on a timer in the toolkit's animation pulse for as long as the
        // area is focused and editable, and RichTextFX never asks whether its window is
        // still there. A window closed with the caret in it - Esc out of the merge tool,
        // whose result editor takes focus on every jump to a conflict - kept the timer
        // running, and the timer kept the area, its window and everything in it: 17 MB per
        // merge tool, for the life of the process. So the blink stops when the area leaves
        // a showing window, which ends the timer, and resumes when it is in one again.
        // Through the scene: an area taken out of its scene stops listening to the window.
        sceneProperty()
                .flatMap(javafx.scene.Scene::windowProperty)
                .flatMap(javafx.stage.Window::showingProperty)
                .orElse(false)
                .addListener((obs, was, showing) -> pauseCaretBlink(!showing));

        applyInlineStyle();
        // Weakly, the way RepoTab registers on its static properties: the zoom outlives
        // every area, and a strong listener pinned each one - every file ever opened, with
        // its whole text and styles - for the life of the process.
        EditorZoom.percentProperty().addListener(
                new javafx.beans.value.WeakChangeListener<>(zoomListener));
        // A filter, not a handler: it must run before VirtualizedScrollPane's own
        // bubbling-phase scroll handling, so consuming a Ctrl+scroll here stops the
        // normal scroll-the-content behaviour from also happening at the same time.
        addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (handleZoomScroll(e.isControlDown(), e.getDeltaY())) {
                e.consume();
            }
        });
    }

    /** The caret's blink rate, set aside while the area is out of sight; null while it blinks. */
    private javafx.util.Duration blinkWhileShown;

    /**
     * Stops or resumes the caret's blink. A zero rate is RichTextFX's "always on", which
     * needs no timer. Not {@code CaretVisibility.OFF}: that subscribes the caret to an
     * event stream RichTextFX keeps in a static field, and pins the area just as the
     * timer did.
     */
    private void pauseCaretBlink(boolean pause) {
        org.fxmisc.richtext.CaretNode caret = getCaretSelectionBind().getUnderlyingCaret();
        if (pause && blinkWhileShown == null) {
            blinkWhileShown = caret.getBlinkRate();
            caret.setBlinkRate(javafx.util.Duration.ZERO);
        } else if (!pause && blinkWhileShown != null) {
            caret.setBlinkRate(blinkWhileShown);
            blinkWhileShown = null;
        }
    }

    /** The one inline style this area carries: the zoomed font size. */
    private void applyInlineStyle() {
        setStyle("-fx-font-size: " + (BASE_FONT_SIZE_PX * EditorZoom.factor()) + "px;");
    }

    // ------------------------------------------------------------------
    // Row model
    //
    // What the scrollbar and the minimap measure the surface in. Flowless's pixel
    // estimates (estimatedScrollY, totalHeightEstimate) are averages over whichever
    // cells happen to have been measured so far, so they move underfoot as the user
    // scrolls and jump when a tall cell is first seen; a thumb driven by them creeps
    // and resizes on its own. Rows are exact, and a row's height is uniform without
    // wrapping, so "row 12.25 is at the top" is a position both a strip and a click
    // can agree on.
    // ------------------------------------------------------------------

    /** The Flowless flow this area renders through; null before it is built. */
    private org.fxmisc.flowless.VirtualFlow<?, ?> flow;

    private org.fxmisc.flowless.VirtualFlow<?, ?> flow() {
        if (flow == null) {
            for (javafx.scene.Node child : getChildrenUnmodifiable()) {
                if (child instanceof org.fxmisc.flowless.VirtualFlow<?, ?> found) {
                    flow = found;
                    break;
                }
            }
        }
        return flow;
    }

    /**
     * The height of one unwrapped row, measured off the paragraph boxes on screen - the
     * shortest, so a wrapped line or the padded last paragraph never passes for the
     * norm. Before the first layout, an estimate from the font size.
     *
     * <p>Read straight off the flow's visible cells: a CSS lookup over the subtree walks
     * every text node in every cell, and this runs on every scroll pulse.
     */
    double rowHeight() {
        double best = Double.MAX_VALUE;
        for (org.fxmisc.flowless.Cell<?, ?> cell : visibleCells()) {
            if (cell.getNode() instanceof javafx.scene.layout.Region box && box.getHeight() > 0
                    && !box.getPseudoClassStates().contains(LAST_PARAGRAPH)) {
                best = Math.min(best, box.getHeight());
            }
        }
        return best < Double.MAX_VALUE ? best : BASE_FONT_SIZE_PX * EditorZoom.factor() * 1.4;
    }

    /**
     * The flow's cells on screen, or none when they cannot be read right now.
     *
     * <p>Flowless fills its viewport when the list is first asked for after a change, and
     * asked while its cell list is still catching up with replaced text - a deferred
     * re-measure landing after the content was swapped, or the area taken out of the
     * scene - it looks for a cell that is not there and throws
     * {@code NoSuchElementException} out of {@code CellListManager.getPresentCell}. Every
     * reader here has an answer for "no cells yet", so that is what such a moment is.
     */
    private List<? extends org.fxmisc.flowless.Cell<?, ?>> visibleCells() {
        org.fxmisc.flowless.VirtualFlow<?, ?> f = flow();
        if (f == null) {
            return List.of();
        }
        try {
            return f.visibleCells();
        } catch (RuntimeException midRefill) {
            return List.of();
        }
    }

    /** Blank space after the last line, in pixels; see {@link #setScrollBeyondLastLine}. */
    double tailPadding() {
        return tail.get();
    }

    /** {@link #tailPadding()}, to follow. */
    javafx.beans.property.ReadOnlyDoubleProperty tailPaddingProperty() {
        return tail.getReadOnlyProperty();
    }

    /**
     * Lets the last line be scrolled to the top of the viewport, the way every code
     * editor allows, by padding the last paragraph's box with a viewport's worth of
     * blank space less one row.
     *
     * <p>Done through CSS rather than a blank paragraph: a paragraph would be text - in
     * the line count, under the caret, in a copy, in the file on save. The padding is a
     * one-rule stylesheet this area installs on itself (see {@link #tailStylesheet}),
     * aimed at RichTextFX's {@code :last-paragraph} box, which lays out its text and
     * gutter inside the insets so nothing but the blank space grows. Only for surfaces
     * whose scrollbar is a {@link DiffScrollMap}: a native bar sizes its thumb from
     * Flowless's height estimate, which jumps when the tall last cell is first measured.
     */
    public void setScrollBeyondLastLine(boolean on) {
        if (scrollBeyondLastLine == on) {
            return;
        }
        scrollBeyondLastLine = on;
        if (on) {
            heightProperty().addListener(tailListener);
            // Every layout pass, coalesced: the first one is when the row height is
            // known, and a zoom or a wrap changes it again later.
            needsLayoutProperty().addListener(layoutListener);
        } else {
            heightProperty().removeListener(tailListener);
            needsLayoutProperty().removeListener(layoutListener);
        }
        applyTail();
    }

    /** {@link #applyTail()} once the current pass is over, however many times it was asked for. */
    private void scheduleTail() {
        if (tailScheduled) {
            return;
        }
        tailScheduled = true;
        javafx.application.Platform.runLater(() -> {
            tailScheduled = false;
            applyTail();
        });
    }

    /** The stylesheet carrying the current tail, so the next one can replace it; null for none. */
    private String tailStylesheet;

    private void applyTail() {
        if (scrollBeyondLastLine && getScene() == null) {
            // Taken out of the scene since this was scheduled: nothing to measure the row
            // against, and the next layout in a scene schedules it again.
            return;
        }
        double wanted = 0;
        if (scrollBeyondLastLine) {
            // Snapped up to 4px steps: a window being dragged taller would otherwise swap
            // the stylesheet on every pixel. Up, not down - a tail even a pixel short
            // leaves the last line a pixel below the top, and Flowless then reports the
            // row above it as the first visible one; a few pixels over costs nothing.
            wanted = Math.ceil(Math.max(0, getHeight() - rowHeight()) / 4) * 4;
        }
        if (wanted == tail.get()) {
            return;
        }
        if (tailStylesheet != null) {
            getStylesheets().remove(tailStylesheet);
            tailStylesheet = null;
        }
        if (wanted > 0) {
            tailStylesheet = tailStylesheet(wanted);
            getStylesheets().add(tailStylesheet);
        }
        tail.set(wanted);
    }

    /**
     * A stylesheet of one rule, padding the last paragraph's box by {@code tail} pixels
     * below its text.
     *
     * <p>A literal value in a generated sheet, not a looked-up token: JavaFX hands a
     * looked-up custom size to the insets converter already converted to a plain number,
     * which the converter rejects ({@code ClassCastException: Double cannot be cast to
     * Size}) and then drops the whole {@code -fx-padding} declaration - from a
     * stylesheet and from an inline style alike. A {@code data:} URL keeps it in memory,
     * and a changed value is a changed URL, so the style cache never serves a stale one.
     * The top inset repeats RichTextFX's own ({@code -0.5}, from its
     * {@code styled-text-area.css}), which this rule would otherwise displace.
     */
    static String tailStylesheet(double tail) {
        String rule = ".code-area .paragraph-box:last-paragraph { -fx-padding: -0.5 0 " + tail + "px 0; }";
        return "data:text/css;base64," + java.util.Base64.getEncoder()
                .encodeToString(rule.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The row at the top of the viewport, with the part of it already scrolled past:
     * 12.25 means the top edge is a quarter of the way into row 12. Read off the flow's
     * first visible cell and how far above the edge it sits; 0 before the first layout.
     *
     * <p>Not through {@code firstVisibleParToAllParIndex()}: RichTextFX answers that by
     * asking Flowless for a cell, and Flowless lays itself out first to be sure - a
     * forced layout on every scroll pulse, from inside other nodes' layout.
     */
    double firstVisibleRow() {
        org.fxmisc.flowless.VirtualFlow<?, ?> f = flow();
        List<? extends org.fxmisc.flowless.Cell<?, ?>> cells = visibleCells();
        if (f == null || cells.isEmpty()) {
            return 0;
        }
        int first;
        try {
            first = f.getFirstVisibleIndex();
        } catch (RuntimeException midRefill) {
            return 0; // see visibleCells()
        }
        if (first < 0) {
            return 0;
        }
        javafx.scene.Node node = cells.get(0).getNode();
        // Against the uniform row height, not this cell's own: the last paragraph's box
        // carries the tail and would make a few pixels look like a fraction of nothing.
        double above = Math.max(0, -node.getLayoutY());
        return first + Math.min(0.999, above / rowHeight());
    }

    /**
     * The last paragraph with a cell on screen, or -1 before the first layout. Read off
     * the flow's present cells like {@link #firstVisibleRow()}, for the same reason: the
     * RichTextFX answer ({@code lastVisibleParToAllParIndex}) lays the flow out first,
     * and the scroll listeners asked it on every pulse.
     */
    int lastVisibleIndex() {
        org.fxmisc.flowless.VirtualFlow<?, ?> f = flow();
        if (f == null || visibleCells().isEmpty()) {
            return -1;
        }
        try {
            int first = f.getFirstVisibleIndex();
            if (first < 0) {
                return -1;
            }
            double height = getHeight();
            int count = getParagraphs().size();
            int last = first;
            for (int i = first + 1; i < count; i++) {
                var cell = f.getCellIfVisible(i);
                if (cell.isEmpty() || cell.get().getNode().getLayoutY() >= height) {
                    break;
                }
                last = i;
            }
            return Math.min(last, count - 1);
        } catch (RuntimeException midRefill) {
            return -1; // see visibleCells()
        }
    }

    /**
     * Scrolls so that {@code row} is at the top of the viewport; a fractional row puts
     * that much of the paragraph above the edge. The one scroll primitive the scrollbar
     * and the minimap use - by row, never by pixel estimate.
     */
    void showRowAtTop(double row) {
        int count = getParagraphs().size();
        if (count == 0) {
            return;
        }
        double clamped = Math.max(0, Math.min(count - 1, row));
        int paragraph = (int) Math.floor(clamped);
        double offset = (clamped - paragraph) * rowHeight();
        try {
            // A viewport-tall region starting offset into the paragraph: the only way to
            // show all of it is with the paragraph's top exactly offset above the edge.
            showParagraphRegion(paragraph,
                    new javafx.geometry.BoundingBox(0, offset, 1, Math.max(1, getHeight())));
        } catch (RuntimeException notLaidOutYet) {
            // Flowless asks for a cell that does not exist yet while its list is still
            // catching up with replaced text; the next request lands.
        }
    }

    /**
     * A small, directly-testable seam for the scroll filter above, so a test can drive
     * this without having to construct a real {@code ScrollEvent} by hand (its
     * constructor is a long, easy-to-miscount list of positional doubles and booleans).
     *
     * @return whether this was a zoom gesture that should be consumed
     */
    boolean handleZoomScroll(boolean controlDown, double deltaY) {
        if (!controlDown) {
            return false;
        }
        if (deltaY > 0) {
            EditorZoom.zoomIn();
        } else if (deltaY < 0) {
            EditorZoom.zoomOut();
        }
        return true;
    }

    /**
     * Replaces the content with {@code lines}, optionally drawing spaces and tabs.
     *
     * @param lines          one entry per paragraph; {@code null} renders as blank
     * @param showWhitespace substitute visible markers for spaces and tabs
     */
    public void setLines(List<String> lines, boolean showWhitespace) {
        setRows(lines, index -> showWhitespace);
    }

    /**
     * The same, deciding per line whether markers apply.
     *
     * <p>Used so the {@code @@} scaffolding around a diff is not peppered with middots
     * while the code between it is.
     */
    public void setRows(List<String> lines, IntPredicate showWhitespaceFor) {
        setContent(lines, showWhitespaceFor, index -> null, index -> List.of());
    }

    /**
     * Replaces the text, every paragraph's style and every character style in one
     * document edit.
     *
     * <p>The render used to be a text replacement followed by one {@code setParagraphStyle}
     * and one {@code setStyleSpans} per row: about twelve thousand separate edits for a
     * six-thousand-line highlighted file, each its own change event through every
     * subscriber (the caret, the selection, the find bar, the minimap) and, while undo
     * was on, its own retained undo record. Built as a document first, the whole render
     * is one change.
     *
     * @param paragraphStyle a row's paragraph style class, or null for none
     * @param ranges         a row's character styles in source coordinates; empty for none
     */
    public void setContent(List<String> lines, IntPredicate showWhitespaceFor,
                           java.util.function.IntFunction<String> paragraphStyle,
                           java.util.function.IntFunction<List<StyleRange>> ranges) {
        paragraphs.clear();
        substituted = false;

        org.fxmisc.richtext.model.ReadOnlyStyledDocumentBuilder<Collection<String>, String, Collection<String>>
                builder = new org.fxmisc.richtext.model.ReadOnlyStyledDocumentBuilder<>(
                        getSegOps(), Collections.emptyList(), Math.max(1, lines.size()));
        for (int i = 0; i < lines.size(); i++) {
            boolean visible = showWhitespaceFor.test(i);
            substituted |= visible;
            WhitespaceMapping mapping = visible
                    ? WhitespaceMapping.visible(lines.get(i))
                    : WhitespaceMapping.identity(lines.get(i));
            paragraphs.add(mapping);
            String display = mapping.display();
            List<StyleRange> rowRanges = ranges.apply(i);
            org.fxmisc.richtext.model.StyleSpans<Collection<String>> styles =
                    rowRanges == null || rowRanges.isEmpty() || display.isEmpty()
                            ? org.fxmisc.richtext.model.StyleSpans.singleton(NO_STYLE, display.length())
                            : spans(display.length(), rowRanges, mapping::displayOffset);
            builder.addParagraph(display, styles, paragraphStyleOf(paragraphStyle.apply(i)));
        }
        if (lines.isEmpty()) {
            // A document always has a paragraph; an empty one is what replaceText("") left.
            builder.addParagraph("", org.fxmisc.richtext.model.StyleSpans.singleton(NO_STYLE, 0),
                    NO_STYLE);
        }
        replace(0, getLength(), builder.build());
        // RichTextFX joins the replacement's first paragraph onto what is left of the old
        // first one, and when the new one is empty the join keeps the old paragraph - and
        // its style: a file opening on a blank line kept the previous diff's hunk-header
        // band. Only then is a second edit needed.
        Collection<String> first = lines.isEmpty() ? NO_STYLE : paragraphStyleOf(paragraphStyle.apply(0));
        if (!getParagraph(0).getParagraphStyle().equals(first)) {
            setParagraphStyle(0, first);
        }
    }

    /** The style of unstyled text and of a plain paragraph: RichTextFX's own initial one. */
    private static final Collection<String> NO_STYLE = Collections.emptyList();

    /** One shared collection per paragraph class, rather than one per row. */
    private static final java.util.Map<String, Collection<String>> PARAGRAPH_STYLES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Collection<String> paragraphStyleOf(String styleClass) {
        return styleClass == null ? NO_STYLE
                : PARAGRAPH_STYLES.computeIfAbsent(styleClass, Collections::singletonList);
    }

    /**
     * Restyles rows {@code from} to {@code from + rows.size() - 1} in one edit, each row's
     * ranges replacing whatever character styles it had. For colours that arrive after
     * the text - a chunk of syntax highlighting - without paying one edit per row.
     *
     * @param rows each row's ranges in source coordinates; empty clears a row's styles
     */
    public void restyleRows(int from, List<List<StyleRange>> rows) {
        restyle(from, rows, true);
    }

    /**
     * {@link #restyleRows}, in the paragraphs' own offsets rather than through the
     * load-time whitespace mapping - for a buffer being edited, whose paragraphs no
     * longer match what was loaded.
     */
    public void restyleParagraphs(int from, List<List<StyleRange>> rows) {
        restyle(from, rows, false);
    }

    private void restyle(int from, List<List<StyleRange>> rows, boolean mapped) {
        int available = mapped ? paragraphs.size() : getParagraphs().size();
        int count = Math.min(rows.size(), available - from);
        if (from < 0 || count <= 0) {
            return;
        }
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        for (int k = 0; k < count; k++) {
            WhitespaceMapping mapping = mapped ? paragraphs.get(from + k) : null;
            int length = mapped ? mapping.display().length() : getParagraphLength(from + k);
            List<StyleRange> ranges = rows.get(k);
            if (ranges == null || ranges.isEmpty() || length == 0) {
                builder.add(NO_STYLE, length);
            } else {
                builder.addAll(spans(length, ranges,
                        mapped ? mapping::displayOffset : offset -> offset));
            }
            if (k < count - 1) {
                // The line break between two rows; RichTextFX counts it as a character.
                builder.add(NO_STYLE, 1);
            }
        }
        // From an absolute offset: the spans run across many paragraphs, line breaks
        // included, which is the one-edit form RichTextFX restyles a range in.
        setStyleSpans(getAbsolutePosition(from, 0), builder.create());
    }

    /** Source text of the paragraph at {@code index}, whitespace markers undone. */
    public String sourceLine(int index) {
        return index >= 0 && index < paragraphs.size() ? paragraphs.get(index).source() : "";
    }

    /** A character range of one paragraph, in the paragraph's own source coordinates. */
    public record StyleRange(int start, int end, String styleClass) {
    }

    /**
     * Applies every style layer of one paragraph in a single pass.
     *
     * <p>Layers have to be combined rather than applied one after another: syntax
     * highlighting colours the text and the intra-line diff tints behind it, and a second
     * {@code setStyle} call over the same characters would replace the first rather than
     * join it. Ranges arrive in source coordinates, because that is what the lexer and
     * the word diff both work in, and are translated here to wherever whitespace markers
     * pushed those characters.
     */
    public void applyStyles(int paragraph, List<StyleRange> ranges) {
        if (paragraph < 0 || paragraph >= paragraphs.size() || ranges.isEmpty()) {
            return;
        }
        WhitespaceMapping mapping = paragraphs.get(paragraph);
        int length = mapping.display().length();
        if (length == 0) {
            return;
        }
        setStyleSpans(paragraph, 0, spans(length, ranges, mapping::displayOffset));
    }

    /**
     * The spans for one paragraph of {@code length} characters, every layer combined.
     *
     * @param toDisplay maps a source offset to where it is drawn
     */
    static org.fxmisc.richtext.model.StyleSpans<Collection<String>> spans(
            int length, List<StyleRange> ranges, java.util.function.IntUnaryOperator toDisplay) {
        // Cut the line where any range starts or ends; between two cuts every character
        // carries the same layers. This used to build a list per character - an
        // ArrayList for each of the 300,000 characters of a 6,000-line file - and then
        // compare neighbours to merge them back into runs.
        int n = ranges.size();
        int[] from = new int[n];
        int[] to = new int[n];
        int[] cuts = new int[2 * n + 2];
        int cutCount = 0;
        cuts[cutCount++] = 0;
        cuts[cutCount++] = length;
        for (int k = 0; k < n; k++) {
            StyleRange range = ranges.get(k);
            from[k] = Math.max(0, toDisplay.applyAsInt(range.start()));
            to[k] = Math.min(length, toDisplay.applyAsInt(range.end()));
            if (from[k] < to[k]) {
                cuts[cutCount++] = from[k];
                cuts[cutCount++] = to[k];
            }
        }
        java.util.Arrays.sort(cuts, 0, cutCount);

        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        Collection<String> pending = null;
        int pendingLength = 0;
        for (int c = 0; c + 1 < cutCount; c++) {
            int a = cuts[c];
            int b = cuts[c + 1];
            if (a >= b) {
                continue;
            }
            List<String> layers = null;
            for (int k = 0; k < n; k++) {
                // In range order, duplicates kept: the order the layers were stacked in.
                if (from[k] <= a && to[k] >= b && from[k] < to[k]) {
                    if (layers == null) {
                        layers = new ArrayList<>(2);
                    }
                    layers.add(ranges.get(k).styleClass());
                }
            }
            Collection<String> style = layers == null ? Collections.emptyList() : intern(layers);
            if (style == pending) {
                pendingLength += b - a;
            } else {
                if (pending != null) {
                    builder.add(pending, pendingLength);
                }
                pending = style;
                pendingLength = b - a;
            }
        }
        if (pending == null) {
            builder.add(Collections.emptyList(), length);
        } else {
            builder.add(pending, pendingLength);
        }
        return builder.create();
    }

    /**
     * One shared, unmodifiable instance per distinct layer list. A file uses a few dozen
     * combinations of token classes, so sharing them keeps a document's styles to a
     * handful of objects, and lets the minimap resolve a colour once per combination by
     * identity rather than by sorting and joining the classes of every span it paints.
     */
    private static final java.util.Map<List<String>, List<String>> LAYERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Far beyond any real grammar's combinations; a guard against unbounded growth, not a tuning knob. */
    private static final int MAX_INTERNED_LAYERS = 8_192;

    static List<String> intern(List<String> layers) {
        List<String> shared = LAYERS.get(layers);
        if (shared != null) {
            return shared;
        }
        if (LAYERS.size() > MAX_INTERNED_LAYERS) {
            LAYERS.clear();
        }
        List<String> copy = List.copyOf(layers);
        shared = LAYERS.putIfAbsent(copy, copy);
        return shared == null ? copy : shared;
    }

    @Override
    public void copy() {
        IndexRange selection = getSelection();
        if (!substituted || selection.getLength() == 0) {
            super.copy();
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sourceBetween(selection.getStart(), selection.getEnd()));
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Maps a display-offset range back onto the original text. */
    private String sourceBetween(int displayStart, int displayEnd) {
        TwoDimensional.Position start = offsetToPosition(displayStart, Bias.Forward);
        TwoDimensional.Position end = offsetToPosition(displayEnd, Bias.Backward);

        StringBuilder out = new StringBuilder();
        for (int paragraph = start.getMajor(); paragraph <= end.getMajor(); paragraph++) {
            String source = sourceLine(paragraph);
            int from = paragraph == start.getMajor() ? sourceOffset(paragraph, start.getMinor()) : 0;
            int to = paragraph == end.getMajor() ? sourceOffset(paragraph, end.getMinor()) : source.length();
            if (from < to) {
                out.append(source, from, to);
            }
            if (paragraph < end.getMajor()) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private int sourceOffset(int paragraph, int displayColumn) {
        if (paragraph < 0 || paragraph >= paragraphs.size()) {
            return 0;
        }
        return paragraphs.get(paragraph).sourceOffset(displayColumn);
    }
}
