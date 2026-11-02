package com.jfx.git.ui;

import javafx.scene.Node;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.List;

/**
 * The application's drawn icons.
 *
 * <p>These were emoji, and the reason they are not any more is not taste. JavaFX has no
 * colour-font support: no COLR, no CBDT. Whatever the platform's emoji font contains,
 * JavaFX renders those code points through a monochrome fallback, so they arrive flat
 * grey, at whatever weight and baseline the fallback happens to use, and differently on
 * each OS. The same goes for the typographic symbols that stood in for icons after the
 * emoji left - a pilcrow for whitespace, a pencil for edit, a gear, a cross - each drawn
 * by whichever font had the glyph, at its own weight, on its own baseline.
 *
 * <h2>One set, one colour</h2>
 *
 * <p>Every icon is an outline in the muted foreground, brightening when its control is
 * hovered. They used to be duotone and coloured by family - blue for remotes, green for
 * branches, amber for the stash, violet for history - on the argument that each hue
 * already meant that elsewhere. It did, and that was the problem: with five hues in one
 * toolbar and the same hues marking diff lines, status letters and ref chips beside it,
 * colour stopped telling the reader where to look. An icon says what a control is; colour
 * is kept for state - selection, the one primary action, what is added and removed.
 *
 * <p>{@link Tone#ACCENT} is the one exception, for a mark that <i>is</i> state: the dot
 * beside the branch that is checked out.
 *
 * <p>Nothing here sets a colour. The tone is a style class and the actual paint comes
 * from {@code components.css}, which means the icons re-theme on a stylesheet swap like
 * every other styled node - none of the repainting the graph's {@code Canvas} needs.
 */
public final class Icons {

    /** Whether an icon is furniture or is itself saying something. */
    public enum Tone {
        /** An ordinary icon: muted, brighter under the pointer. */
        DEFAULT("icon-default"),
        /** A mark that carries state, such as the current branch. */
        ACCENT("icon-accent");

        private final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private Icons() {
    }

    // ------------------------------------------------------------------
    // Toolbar icons, drawn on a 16x16 grid
    // ------------------------------------------------------------------

    public static Node open() {
        return outline(
                "M1.9 12.9V3.7a1 1 0 0 1 1-1h3.2l1.4 1.6h5.7a1 1 0 0 1 1 1v7.6a1 1 0 0 1-1 1"
                        + "H2.9a1 1 0 0 1-1-1z");
    }

    public static Node fetch() {
        return grid(16,
                line(path("M12.9 6.9A5.1 5.1 0 0 0 3.6 6.2M3.1 9.1a5.1 5.1 0 0 0 9.3.7")),
                line(path("M13.3 3.9v3h-3M2.7 12.1v-3h3")));
    }

    public static Node pull() {
        return outline(
                "M8 1.9v7.6M5.1 6.6L8 9.5l2.9-2.9M2.7 12.9h10.6");
    }

    public static Node push() {
        return outline(
                "M8 14.1V6.5M5.1 9.4L8 6.5l2.9 2.9M2.7 3.1h10.6");
    }

    public static Node branch() {
        return grid(16, 
                line(path("M4.5 5v6.4M4.5 6.2h4.9a2.1 2.1 0 0 1 2.1 2.1v2.5")),
                line(circleOutline(4.5, 3.1, 1.7)),
                line(circleOutline(11.5, 12.9, 1.7)));
    }

    public static Node stash() {
        return outline(
                "M2.1 9.3v3.6a1 1 0 0 0 1 1h9.8a1 1 0 0 0 1-1V9.3M8 1.9v6.4M5.4 5.7L8 8.3l2.6-2.6");
    }

    public static Node pop() {
        return outline(
                "M2.1 9.3v3.6a1 1 0 0 0 1 1h9.8a1 1 0 0 0 1-1V9.3M8 8.3V1.9M5.4 4.5L8 1.9l2.6 2.6");
    }

    public static Node undo() {
        return grid(16, 
                line(path("M2.6 8.6a5.4 5.4 0 1 0 1.7-3.9")),
                line(path("M2.1 2.2v3.4h3.4")));
    }

    public static Node redo() {
        return grid(16, 
                line(path("M13.4 8.6a5.4 5.4 0 1 0-1.7-3.9")),
                line(path("M13.9 2.2v3.4h-3.4")));
    }

    public static Node history() {
        return grid(16, 
                line(circleOutline(8, 8, 5.5)),
                line(path("M8 4.6V8l2.4 1.6")));
    }

    /** An open circle with a filled centre - GitHub's own "issue opened" glyph. */
    public static Node issue() {
        return grid(16, 
                line(circleOutline(8, 8, 5.5)),
                fill(Tone.DEFAULT, new Circle(8, 8, 2.0)));
    }

    /** Two branches and the merge direction between them - a pull request. */
    public static Node pullRequest() {
        return grid(16, 
                line(path("M4.5 5v3.4a2.1 2.1 0 0 0 2.1 2.1h2.8")),
                line(path("M8.1 8.4l1.6 2.1-1.6 2.1")),
                line(circleOutline(4.5, 3.1, 1.7)),
                line(circleOutline(11.5, 12.9, 1.7)));
    }

    /**
     * The rounded corners are quadratic curves rather than SVG arcs.
     *
     * <p>Same rectangle, same 1.4 corner radius - but JavaFX's arc-to-curve conversion
     * rejected these exact quarter-circle arcs with
     * {@code 0 <= tfrom <= tto <= 1 required}, logging a warning every single time the
     * icon was rendered. At sixteen pixels a quadratic through the corner point is
     * indistinguishable from the arc it replaces.
     */
    /**
     * A document. Replaces the {@code 📄} the diff title bar carried.
     *
     * <p>An emoji there contradicted the reason this class exists: JavaFX has no
     * colour-font support, so a code point arrives flat grey at whatever baseline the
     * fallback font chooses, and differently on each platform. One drawn glyph beside a
     * dozen drawn glyphs is the whole argument.
     */
    public static Node file() {
        String body = "M3.6 1.9h5.1l3.7 3.7v8.5a1 1 0 0 1-1 1H3.6a1 1 0 0 1-1-1V2.9"
                + "a1 1 0 0 1 1-1z";
        return outline(body + "M8.7 1.9v3.7h3.7");
    }

    /** A plus. The launchpad's "Create", which was the emoji {@code ✚}. */
    public static Node create() {
        return grid(16, 
                line(circleOutline(8, 8, 5.4)),
                line(path("M8 5.2v5.6M5.2 8h5.6")));
    }

    /**
     * A cloud with an arrow coming down out of it: the launchpad's "Clone", previously
     * the emoji {@code ☁}, which said "cloud" without saying what would happen.
     */
    public static Node cloneRepo() {
        return outline(
                "M4.4 10.4a2.7 2.7 0 0 1-.2-5.3 3.6 3.6 0 0 1 6.9-.6 2.6 2.6 0 0 1 .5 5.2"
                        + "M8 8.1v5.4M5.9 11.4L8 13.5l2.1-2.1");
    }

    /** A speech bubble: a line in a review that someone has written on. */
    public static Node comment() {
        String body = "M2.4 3.6h11.2a1 1 0 0 1 1 1v5.2a1 1 0 0 1-1 1H7.1l-3.2 2.6v-2.6H2.4"
                + "a1 1 0 0 1-1-1V4.6a1 1 0 0 1 1-1z";
        return outline(body);
    }

    public static Node terminal() {
        String body = "M3.1 2.7H12.9Q14.3 2.7 14.3 4.1V11.9Q14.3 13.3 12.9 13.3H3.1"
                + "Q1.7 13.3 1.7 11.9V4.1Q1.7 2.7 3.1 2.7Z";
        return outline(body + "M4.6 6.2L6.8 8l-2.2 1.8M8.4 10.2h3.1");
    }

    // ------------------------------------------------------------------
    // Control icons, on the same 16x16 grid
    //
    // Each of these replaced a character typed into a button: a pilcrow, a pencil, a
    // gear, a cross. Characters are drawn by whichever font has them, so no two matched
    // in weight or sat on the same baseline, and a screen reader read out their names.
    // ------------------------------------------------------------------

    /** Two overlapping sheets: copy to the clipboard. */
    public static Node copy() {
        return grid(16, 
                line(path("M6.4 5.4h6.2a1 1 0 0 1 1 1v6.2a1 1 0 0 1-1 1H6.4a1 1 0 0 1-1-1V6.4"
                        + "a1 1 0 0 1 1-1z")),
                line(path("M10.6 3.4v-.2a1 1 0 0 0-1-1H3.4a1 1 0 0 0-1 1v6.2a1 1 0 0 0 1 1h.2")));
    }

    /** A cross: close, dismiss, clear. */
    public static Node close() {
        return outline("M4 4l8 8M12 4l-8 8");
    }

    /** A pencil: edit this. */
    public static Node edit() {
        return outline("M10.9 2.6l2.5 2.5-7.6 7.6-3.2.7.7-3.2zM9.5 4l2.5 2.5");
    }

    /** An arrow leaving a box: open somewhere outside the application. */
    public static Node external() {
        return outline("M9.3 2.7h4v4M13.3 2.7L7.6 8.4M11.3 9.4v2.9a1 1 0 0 1-1 1H3.7"
                + "a1 1 0 0 1-1-1V5.7a1 1 0 0 1 1-1h2.9");
    }

    /** An anticlockwise arrow over a dot: put back an earlier version. */
    public static Node restore() {
        return grid(16, 
                line(path("M2.6 8.6a5.4 5.4 0 1 0 1.7-3.9")),
                line(path("M2.1 2.2v3.4h3.4")),
                fill(Tone.DEFAULT, new Circle(8, 8.6, 1.2)));
    }

    public static Node chevronUp() {
        return outline("M3.8 10.2L8 6l4.2 4.2");
    }

    public static Node chevronDown() {
        return outline("M3.8 5.8L8 10l4.2-4.2");
    }

    /**
     * Two chevrons pointing apart: show the lines folded away here. On the 12px grid,
     * because it sits in the code gutter, where a row is one line of text high.
     */
    public static Node unfold() {
        return outlineSmall("M3.2 4.7L6 1.9l2.8 2.8M3.2 7.3L6 10.1l2.8-2.8");
    }

    /** A pilcrow, drawn: whitespace-only differences. */
    public static Node pilcrow() {
        return outline("M11.6 2.6H6.9a2.9 2.9 0 0 0 0 5.8h1.3M8.2 2.6v10.8M10.7 2.6v10.8");
    }

    /** A row of dots: show the whitespace characters themselves. */
    public static Node whitespaceDots() {
        return grid(16, 
                fill(Tone.DEFAULT, new Circle(3.6, 8, 1.2)),
                fill(Tone.DEFAULT, new Circle(8, 8, 1.2)),
                fill(Tone.DEFAULT, new Circle(12.4, 8, 1.2)));
    }

    /** A line that turns back on itself: wrap long lines. */
    public static Node wrap() {
        return outline("M2.4 3.8h11.2M2.4 8h8.9a2.1 2.1 0 0 1 0 4.2H8.2M9.8 10.4L8 12.2l1.8 1.8"
                + "M2.4 12.2h2.9");
    }

    /** Three sliders: choose what is shown. The column chooser and the engine menu. */
    public static Node settings() {
        return grid(16, 
                line(path("M2.4 4.2h4.2M9.4 4.2h4.2M2.4 8h1.2M6.4 8h7.2M2.4 11.8h6.2M11.4 11.8h2.2")),
                line(circleOutline(8, 4.2, 1.4)),
                line(circleOutline(5, 8, 1.4)),
                line(circleOutline(10, 11.8, 1.4)));
    }

    /** A magnifying glass. */
    public static Node search() {
        return grid(16, 
                line(circleOutline(7, 7, 4.2)),
                line(path("M10.1 10.1l3.5 3.5")));
    }

    /** A triangle with a mark in it: something here needs attention. */
    public static Node warning() {
        return grid(16, 
                line(path("M8 2.6l5.9 10.4H2.1z")),
                line(path("M8 6.6v3.1M8 11.3v.2")));
    }

    /** A star, outlined: not a favourite yet. */
    public static Node star() {
        return outline(STAR);
    }

    /** A star, filled: a favourite. */
    public static Node starFilled() {
        return grid(16, fill(Tone.ACCENT, path(STAR)));
    }

    private static final String STAR = "M8 2.2l1.8 3.7 4 .6-2.9 2.8.7 4L8 11.4l-3.6 1.9.7-4"
            + "-2.9-2.8 4-.6z";

    /** Four corners pulling outward: give this the whole window. */
    public static Node expand() {
        return outline("M2.8 6.2V2.8h3.4M9.8 2.8h3.4v3.4M13.2 9.8v3.4H9.8M6.2 13.2H2.8V9.8");
    }

    /** Three dots: more actions behind this. */
    public static Node more() {
        return grid(16, 
                fill(Tone.DEFAULT, new Circle(3.4, 8, 1.3)),
                fill(Tone.DEFAULT, new Circle(8, 8, 1.3)),
                fill(Tone.DEFAULT, new Circle(12.6, 8, 1.3)));
    }

    /** A bin: discard. */
    public static Node trash() {
        return outline("M2.8 4.4h10.4M6.2 4.4V2.9h3.6v1.5M4.2 4.4l.6 8.2a1 1 0 0 0 1 .9h4.4"
                + "a1 1 0 0 0 1-.9l.6-8.2M6.7 6.8v4.2M9.3 6.8v4.2");
    }

    /** A commit on its line: the detail panel is showing one commit. */
    public static Node commit() {
        return grid(16, 
                line(circleOutline(8, 8, 2.9)),
                line(path("M1.8 8h3.3M10.9 8h3.3")));
    }

    /** Two arrows passing: the detail panel is comparing two commits. */
    public static Node compare() {
        return outline("M2.6 5.4h10M10.2 3l2.4 2.4-2.4 2.4M13.4 10.6h-10M5.8 8.2L3.4 10.6l2.4 2.4");
    }

    // ------------------------------------------------------------------
    // Dialog subjects, on the same 16x16 grid
    //
    // Drawn for the icon tile at the head of a dialog, where the tile's colour says how
    // risky the question is and the glyph says what it is about. They replaced JavaFX's
    // glossy "?" and "!" bitmaps, which said only that a question was being asked.
    // ------------------------------------------------------------------

    /** Two lines joining: a merge. */
    public static Node merge() {
        return grid(16,
                line(circleOutline(4.5, 3.1, 1.7)),
                line(circleOutline(4.5, 12.9, 1.7)),
                line(circleOutline(11.5, 8.4, 1.7)),
                line(path("M4.5 4.8v6.4M4.5 4.8c0 2.4 2.1 3.6 5.3 3.6")));
    }

    /** Three lines and the direction they replay in: an interactive rebase. */
    public static Node rebase() {
        return outline("M2.4 4h6.8M2.4 8h6.8M2.4 12h6.8M12.6 3v10M11.1 11.5l1.5 1.5 1.5-1.5");
    }

    /** Two columns of dots: a handle to drag a row by. */
    public static Node grip() {
        List<Node> dots = new ArrayList<>();
        for (double y : new double[]{4.2, 8, 11.8}) {
            dots.add(fill(Tone.DEFAULT, new Circle(6, y, 1.1)));
            dots.add(fill(Tone.DEFAULT, new Circle(10, y, 1.1)));
        }
        return grid(16, dots.toArray(Node[]::new));
    }

    /** A padlock: something that needs a secret. */
    public static Node lock() {
        return outline("M4 7.2h8a1 1 0 0 1 1 1v4.7a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8.2a1 1 0 0 1 1-1z"
                + "M5.4 7.2V5.3a2.6 2.6 0 0 1 5.2 0v1.9M8 9.9v1.4");
    }

    /** A key: an SSH key, a passphrase. */
    public static Node key() {
        return grid(16,
                line(circleOutline(5.2, 10.8, 2.7)),
                line(path("M7.1 8.9l6.1-6.1M11.4 4.6l1.6 1.6M9.8 6.2l1.2 1.2")));
    }

    /** A shield with a tick: decide whether to trust something. */
    public static Node shield() {
        return outline("M8 1.9l5 1.9v3.9c0 3-2.1 5.4-5 6.4-2.9-1-5-3.4-5-6.4V3.8z"
                + "M5.9 8l1.5 1.5 2.8-3");
    }

    /** A circled i: a notice, About. */
    public static Node info() {
        return grid(16,
                line(circleOutline(8, 8, 5.5)),
                line(path("M8 7.3v3.6M8 5.1v.2")));
    }

    /** A circled cross: something failed. */
    public static Node error() {
        return grid(16,
                line(circleOutline(8, 8, 5.5)),
                line(path("M6.1 6.1l3.8 3.8M9.9 6.1l-3.8 3.8")));
    }

    // ------------------------------------------------------------------
    // Icon-only controls
    // ------------------------------------------------------------------

    /**
     * A button that is only an icon.
     *
     * <p>The name is not optional. A button showing a character had at least that
     * character for a screen reader to read out, wrongly; a drawn icon has nothing, so the
     * accessible text and the tooltip are what say what it does. Tests find these by that
     * text or by an id, never by what the icon looks like.
     *
     * @param name    what the button does, for assistive technology
     * @param tooltip the same for the pointer, usually with its shortcut
     */
    public static javafx.scene.control.Button button(Node icon, String name, String tooltip) {
        javafx.scene.control.Button button = new javafx.scene.control.Button();
        dress(button, icon, name, tooltip);
        return button;
    }

    /** {@link #button} for a control that stays pressed. */
    public static javafx.scene.control.ToggleButton toggle(Node icon, String name, String tooltip) {
        javafx.scene.control.ToggleButton toggle = new javafx.scene.control.ToggleButton();
        dress(toggle, icon, name, tooltip);
        return toggle;
    }

    private static void dress(javafx.scene.control.ButtonBase control, Node icon, String name, String tooltip) {
        control.setGraphic(icon);
        control.getStyleClass().addAll("btn-ghost", "btn-icon");
        control.setAccessibleText(name);
        if (tooltip != null && !tooltip.isBlank()) {
            control.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        }
        // Not below the icon's own size when a row runs out of room: an icon that shrinks
        // is an icon that disappears.
        control.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    }

    // ------------------------------------------------------------------
    // Sidebar marks, drawn on a 12x12 grid
    //
    // Smaller path data rather than a scaled Group: scaling a Group scales the stroke
    // with it, so a 16px icon shown at 11 arrives with a hairline that does not match
    // anything else on screen.
    // ------------------------------------------------------------------

    /** The branch that is checked out. Filled, because it is the one you are on. */
    public static Node currentBranch() {
        Circle dot = new Circle(6, 6, 3.4);
        return grid(12, fill(Tone.ACCENT, dot));
    }

    /** A local branch you are not on. */
    public static Node localBranch() {
        return grid(12, 
                line(path("M3.2 1.8v8.4M3.2 4.6h3.6a1.6 1.6 0 0 1 1.6 1.6v3.4")),
                line(circleOutline(3.2, 1.8, 1.1)),
                line(circleOutline(8.4, 10.2, 1.1)));
    }

    /** A branch on a remote. A globe, so it does not need its colour decoded. */
    public static Node remoteBranch() {
        return grid(12, 
                line(circleOutline(6, 6, 4.1)),
                line(path("M1.9 6h8.2M6 1.9c1.8 2.1 1.8 6.1 0 8.2M6 1.9c-1.8 2.1-1.8 6.1 0 8.2")));
    }

    /** A remote itself, as against a branch on one: a server, two units high. */
    public static Node remote() {
        return outlineSmall(
                "M2 1.9h8v3.4H2zM2 6.7h8v3.4H2zM3.8 3.6h.9M3.8 8.4h.9");
    }

    public static Node tag() {
        return outlineSmall(
                "M1.9 5.6V2.4a.6.6 0 0 1 .6-.6h3.2l4.5 4.5-3.8 3.8z");
    }

    public static Node stashMark() {
        return outlineSmall(
                "M1.6 5.4h8.8v3.9a.8.8 0 0 1-.8.8H2.4a.8.8 0 0 1-.8-.8zM6 1.4v3.2");
    }

    /** A funnel: a list showing only what matches. */
    public static Node filter() {
        return outlineSmall("M1.6 2.4h8.8l-3.4 4.2v3.6l-2-1.2v-2.4z");
    }

    /** A tick: this checks out - a verified signature. */
    public static Node check() {
        return outlineSmall("M2.2 6.4l2.4 2.4 5.2-5.6");
    }

    /** Visible in the graph: an open eye. Click toggles a branch to hidden. */
    public static Node eyeOpen() {
        return outlineSmall(
                "M1 6c1.4-2.4 3.2-3.6 5-3.6S9.6 3.6 11 6c-1.4 2.4-3.2 3.6-5 3.6S2.4 8.4 1 6z"
                        + "M6 7.6A1.6 1.6 0 1 0 6 4.4a1.6 1.6 0 0 0 0 3.2z");
    }

    /** Hidden from the graph: an eye with a line through it. */
    public static Node eyeClosed() {
        return outlineSmall(
                "M1 6c1.4-2.4 3.2-3.6 5-3.6S9.6 3.6 11 6c-1.4 2.4-3.2 3.6-5 3.6S2.4 8.4 1 6z"
                        + "M6 7.6A1.6 1.6 0 1 0 6 4.4a1.6 1.6 0 0 0 0 3.2zM1.8 1.8l8.4 8.4");
    }

    /** A folder grouping {@code feature/x} and {@code feature/y}. */
    public static Node folder() {
        return outlineSmall(
                "M1.5 9.5V2.6a.7.7 0 0 1 .7-.7h2.3l1 1.2h4.3a.7.7 0 0 1 .7.7v5.7a.7.7 0 0 1-.7.7"
                        + "H2.2a.7.7 0 0 1-.7-.7z");
    }

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    /**
     * The shapes of one icon, in a box exactly the size of the grid they were drawn on.
     *
     * <p>A bare group is as large as its ink, and no two icons have the same ink: a
     * chevron is shorter than a clock, a stroke's round cap pokes half its width past the
     * path. Buttons sized from that came out 28 by 32 for one icon and 30 by 28 for the
     * next, so a row of icon buttons did not line up. The box makes every icon the same
     * size to lay out, whatever is drawn in it.
     */
    private static Node grid(double size, Node... shapes) {
        javafx.scene.layout.Pane box = new javafx.scene.layout.Pane(shapes);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        if (size < 16) {
            // Marked with a class rather than having the stroke set directly: a value set
            // on the node wins over the stylesheet permanently, and an icon that ignores
            // CSS is exactly the thing this class exists to avoid.
            for (Node shape : shapes) {
                if (shape.getStyleClass().contains("icon-line")) {
                    shape.getStyleClass().add("icon-line-small");
                }
            }
        }
        return box;
    }

    /** One stroked path on the 16px grid. */
    private static Node outline(String linePath) {
        return grid(16, line(path(linePath)));
    }

    /** One stroked path on the 12px grid, with the lighter stroke that size needs. */
    private static Node outlineSmall(String linePath) {
        return grid(12, line(path(linePath)));
    }

    private static SVGPath path(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        return svg;
    }

    /**
     * A circle as a path, so it can be stroked by the same rule as everything else.
     *
     * <p>A {@link Circle} would do, but {@code .icon-line} sets {@code -fx-fill:
     * transparent} and a filled-by-default Circle then needs its own rule; expressing it
     * as two arcs keeps every stroked shape under one selector.
     */
    private static SVGPath circleOutline(double cx, double cy, double r) {
        return path("M" + (cx - r) + " " + cy
                + "a" + r + " " + r + " 0 1 0 " + (r * 2) + " 0"
                + "a" + r + " " + r + " 0 1 0 " + (-r * 2) + " 0");
    }

    private static <T extends Shape> T line(T shape) {
        shape.getStyleClass().addAll(Tone.DEFAULT.styleClass, "icon-line");
        return shape;
    }

    /** A shape that is solid rather than outlined - a dot, a filled star. */
    private static <T extends Shape> T fill(Tone tone, T shape) {
        shape.getStyleClass().addAll(tone.styleClass, "icon-solid");
        return shape;
    }
}
