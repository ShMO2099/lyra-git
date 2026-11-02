package com.jfx.git.ui;

/**
 * The type and spacing steps every panel outside the commit graph is built from.
 *
 * <p>The graph has had a system of this shape for a while: {@link
 * com.jfx.git.graph.GraphDensity} carries every measurement a row depends on as one
 * value, because they are not independent of each other. Everything to the left and
 * right of the graph had nothing — type was set at nine different sizes across the
 * stylesheet with no scale behind the choices, and spacing was whatever number the
 * nearest {@code VBox} constructor happened to be given.
 *
 * <p>These constants are the Java half of {@code components.css}. Sizes belong in the
 * stylesheet, where they can follow a theme; gaps and paddings cannot, because JavaFX
 * layout containers take them as arguments. Both halves have to agree, which is what
 * {@code UiScaleTest} checks.
 *
 * <h2>Density</h2>
 * <p>The panels are fixed at the compact step for now, deliberately. Extending {@code
 * GraphDensity}'s Compact/Expressive choice out here is consistent and doubles the
 * surface every measurement has to be right on; the constants are shaped so that pass
 * can happen later without the call sites changing — they read a name, not a number.
 */
public final class UiScale {

    private UiScale() {
    }

    // ------------------------------------------------------------------
    // Type — mirrors the .t-* classes in components.css
    // ------------------------------------------------------------------

    /** A dialog's own title, or the launchpad heading. One per window. */
    public static final double DISPLAY = 20;
    /** A heading inside a panel: a commit subject, a person's name. */
    public static final double TITLE = 15;
    /**
     * Running content and the default for everything: list rows, buttons, inputs. Labels
     * ({@code .t-label}) share this size and differ by weight.
     */
    public static final double BODY = 13;
    /**
     * A secondary fact about the thing beside it - dates, emails, counts, hints - and, in
     * bold, a column title or eyebrow ({@code .t-eyebrow}).
     */
    public static final double CAPTION = 11;

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    /** A button, field or combo box at the default size. Set by padding, not fixed. */
    public static final double CONTROL_H = 28;
    /** A {@code .btn-sm} control: row actions, segmented switches inside a panel. */
    public static final double CONTROL_H_SM = 24;
    /** The repository toolbar: an icon over its caption. */
    public static final double TOOLBAR_H = 40;

    // ------------------------------------------------------------------
    // Spacing
    // ------------------------------------------------------------------

    /** Between parts of one thing — a name and the email under it. */
    public static final double TIGHT = 2;
    /** Between rows of a list, or a label and its control. */
    public static final double SNUG = 4;
    /** The default gap between siblings in a panel. */
    public static final double STEP = 8;
    /** Between one section of a panel and the next. */
    public static final double GAP = 12;
    /** Panel and dialog edge inset. On the 4px grid like every other step. */
    public static final double EDGE = 16;
    /** Between the major blocks of a page: a dialog's groups, the launchpad's sections. */
    public static final double SECTION = 24;

    /**
     * Height of a row in a file or branch list.
     *
     * <p>Matches {@code GraphDensity.COMPACT.rowHeight()} so a file list beside the graph
     * does not tick at a different rate from the commits next to it.
     */
    public static final int ROW_HEIGHT = 26;

    /**
     * Tallest a sidebar section may grow before it scrolls internally.
     *
     * <p>Sections used to be pinned at exactly this height whatever they held, which is
     * why a LOCAL section with one branch in it reserved 260 pixels to show it. It is a
     * cap now, not a size.
     */
    public static final int SECTION_MAX_HEIGHT = 260;
}
