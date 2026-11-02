package com.jfx.git.graph;

import com.jfx.git.ui.ThemeManager;

import javafx.scene.paint.Color;

/**
 * The colours the commit graph paints with.
 *
 * <p>Everything else in the application themes itself: AtlantaFX's Primer supplies the
 * semantic palette, each theme's token file supplies the rest, and a node changes colour
 * when the stylesheet swaps without any code knowing. The graph cannot join in, because
 * lanes are painted onto a {@code Canvas} and JavaFX CSS does not reach inside a bitmap.
 *
 * <p>So the palette lives here instead, one entry per built-in theme, and the rule the
 * token files state at the top of themselves applies to this class too: <strong>a theme
 * added to {@link ThemeManager.Theme} needs an entry here.</strong> Nothing in the compiler
 * enforces that, which is exactly how the light theme came to be drawn with the dark
 * theme's lanes; {@code GraphPaletteTest} makes the omission fail a build instead of
 * shipping, and checks the copies below against the token files they stand in for.
 *
 * <p>The sets are not inversions, and not copies of one another: each is generated for its
 * own ground (see {@link LaneSpec}) so a lane keeps its identity in the order of hues but is
 * only as bright as that ground can hold.
 */
public final class GraphPalette {

    private GraphPalette() {}

    /**
     * How many lane colours there are before the palette repeats.
     *
     * <p>Was eight, which sounds like plenty until you watch a real repository: colours
     * are handed out with {@code nextColor++}, so lane 0 and lane 8 were the same blue,
     * and on anything with real branching those two are frequently drawn side by side.
     */
    private static final int LANES = 18;

    /**
     * Angle between one lane's hue and the next.
     *
     * <p>The golden angle, and the choice matters because of <em>how</em> colours are
     * assigned: {@link LaneAssigner} gives consecutive lanes consecutive indices, so
     * consecutive indices are what must be far apart on the wheel. Stepping evenly — 360/18
     * = 20° — would put lanes 1 and 2 adjacent in hue as well as on screen, which is the
     * one place they must not be. Stepping by 137.5° never lands near a recent neighbour
     * until the palette is exhausted.
     */
    private static final double GOLDEN_ANGLE = 137.508;

    /** Where the sequence starts, so lane 0 stays the familiar blue. */
    private static final double HUE_ORIGIN = 212;

    /** How much of the dark themes' row tint the light ones use. */
    static final double LIGHT_TINT_STRENGTH = 0.5;

    /**
     * How one theme's lanes are generated: where the hue sequence starts, how saturated it
     * is, and the luminance band every lane is brought into.
     *
     * <p>The band is what matters. It is set from the theme's ground so the dimmest lane
     * still clears 3:1 against it (the contrast graphical objects are held to) and the
     * brightest is no louder than the rest - GraphPaletteTest measures both, per theme.
     */
    private record LaneSpec(double hueOrigin, double saturation, double brightness,
                            double low, double high) {
    }

    /**
     * Everything the canvas needs that a stylesheet would otherwise give it, for one theme.
     *
     * @param ground       the commit list's background, {@code -jfx-list-bg}: what a dot is cut
     *                     out of and an edge is cased in
     * @param overflow     the dotted line standing for lanes there was no room to draw
     * @param stash        a stash node and its tie line
     * @param attention    {@code -jfx-attention}, which the canvas cannot read
     * @param tintStrength how much of a row's branch tint is applied; see {@link #rowTint}
     */
    private record Palette(Color[] lanes, Color ground, Color overflow, Color stash,
                           Color attention, double tintStrength) {

        static Palette of(LaneSpec spec, String ground, String overflow, String stash,
                          String attention, double tintStrength) {
            Color[] lanes = new Color[LANES];
            for (int i = 0; i < LANES; i++) {
                double hue = (spec.hueOrigin() + i * GOLDEN_ANGLE) % 360;
                lanes[i] = intoBand(hue, spec.saturation(), spec.brightness(), spec.low(), spec.high());
            }
            return new Palette(lanes, Color.web(ground), Color.web(overflow), Color.web(stash),
                    Color.web(attention), tintStrength);
        }
    }

    /**
     * The palettes, per theme. The two originals' numbers are what the graph already drew
     * (the ground and attention are Primer's own and the token files' -jfx-attention); the
     * others are new.
     *
     * <p>The hue sequence starts where each theme's own accent is, not always at blue: lane 0
     * - the trunk the eye follows - is a green for the Dark Green and Light Green pair,
     * a frost cyan for Nord, a teal for Solarized, a lime for Gruvbox.
     */
    private static final java.util.Map<ThemeManager.Theme, Palette> PALETTES =
            new java.util.EnumMap<>(ThemeManager.Theme.class);

    static {
        PALETTES.put(ThemeManager.Theme.DARK, Palette.of(new LaneSpec(HUE_ORIGIN, 0.62, 1.00, 0.42, 0.66),
                "#0d1117", "#6e7681", "#d29922", "#f0883e", 1.0));
        PALETTES.put(ThemeManager.Theme.DARK_GREEN, Palette.of(new LaneSpec(138, 0.52, 0.80, 0.42, 0.62),
                "#111216", "#6f7581", "#e0a63a", "#f2994a", 1.0));
        PALETTES.put(ThemeManager.Theme.MIDNIGHT, Palette.of(new LaneSpec(228, 0.60, 1.00, 0.42, 0.68),
                "#0b1020", "#5f6b8c", "#e6b450", "#ff9f5a", 1.0));
        PALETTES.put(ThemeManager.Theme.NORD_DARK, Palette.of(new LaneSpec(193, 0.50, 0.85, 0.44, 0.64),
                "#0c121d", "#66728a", "#ebcb8b", "#d08770", 1.0));
        PALETTES.put(ThemeManager.Theme.SOLARIZED_DARK, Palette.of(new LaneSpec(175, 0.62, 0.85, 0.40, 0.62),
                "#00232d", "#5b7a80", "#d0a000", "#e8662d", 1.0));
        PALETTES.put(ThemeManager.Theme.GRUVBOX_DARK, Palette.of(new LaneSpec(70, 0.58, 0.85, 0.42, 0.64),
                "#0f1314", "#7c6f64", "#fabd2f", "#fe8019", 1.0));
        PALETTES.put(ThemeManager.Theme.LIGHT, Palette.of(new LaneSpec(HUE_ORIGIN, 0.85, 0.78, 0.22, 0.42),
                "#ffffff", "#8c959f", "#9a6700", "#bc4c00", LIGHT_TINT_STRENGTH));
        PALETTES.put(ThemeManager.Theme.LIGHT_GREEN, Palette.of(new LaneSpec(138, 0.72, 0.70, 0.22, 0.40),
                "#ffffff", "#8b929d", "#8a5a00", "#a84a06", LIGHT_TINT_STRENGTH));
        PALETTES.put(ThemeManager.Theme.PAPER, Palette.of(new LaneSpec(HUE_ORIGIN, 0.66, 0.70, 0.22, 0.40),
                "#fbf8ef", "#9a917c", "#8a5c0a", "#a84c08", LIGHT_TINT_STRENGTH));
        PALETTES.put(ThemeManager.Theme.NORD_LIGHT, Palette.of(new LaneSpec(193, 0.66, 0.70, 0.22, 0.40),
                "#f2f4f8", "#8f99ad", "#85651a", "#a8512f", LIGHT_TINT_STRENGTH));
        PALETTES.put(ThemeManager.Theme.SOLARIZED_LIGHT, Palette.of(new LaneSpec(175, 0.74, 0.65, 0.22, 0.40),
                "#fdf6e3", "#93a1a1", "#7f5c00", "#b0411a", LIGHT_TINT_STRENGTH));
        PALETTES.put(ThemeManager.Theme.GRUVBOX_LIGHT, Palette.of(new LaneSpec(70, 0.78, 0.66, 0.22, 0.40),
                "#fbf1c7", "#a89984", "#8c5c0a", "#a63a03", LIGHT_TINT_STRENGTH));
    }

    /**
     * Walks brightness until the colour's luminance lands inside {@code [low, high]}.
     *
     * <p>Two-sided, and it has to be. A one-sided correction that only lifts the dim hues
     * leaves the loud ones where they started: at a fixed HSB brightness a yellow is more
     * than twice the luminance of a blue, so the palette ends up with some lanes shouting
     * and others barely present, which reads as the graph emphasising branches at random.
     */
    private static Color intoBand(double hue, double saturation, double brightness,
                                  double low, double high) {
        Color colour = Color.hsb(hue, saturation, brightness);
        for (int step = 0; step < 60; step++) {
            double luminance = luminance(colour);
            if (luminance >= low && luminance <= high) break;
            brightness += luminance < low ? 0.015 : -0.015;
            if (brightness <= 0.12 || brightness >= 1.0) break;
            colour = Color.hsb(hue, saturation, brightness);
        }
        return colour;
    }

    /** Relative luminance, sRGB coefficients. */
    private static double luminance(Color colour) {
        return 0.2126 * colour.getRed() + 0.7152 * colour.getGreen() + 0.0722 * colour.getBlue();
    }

    /** The palette of the theme in effect; a theme with no entry draws with its base's default. */
    private static Palette palette() {
        ThemeManager.Theme theme = ThemeManager.current();
        Palette found = PALETTES.get(theme);
        return found != null ? found
                : PALETTES.get(theme.base() == ThemeManager.Base.LIGHT ? ThemeManager.Theme.LIGHT
                        : ThemeManager.Theme.DARK);
    }

    /**
     * Whether {@code theme} has a palette of its own rather than falling back to its base's.
     * For the test that says every built-in theme must.
     */
    static boolean hasPaletteOf(ThemeManager.Theme theme) {
        return PALETTES.containsKey(theme);
    }

    /** How many distinct lane colours there are before they cycle. */
    public static int laneCount() {
        return LANES;
    }

    /** Colour for a lane, by palette index; wraps past the end of the palette. */
    public static Color lane(int index) {
        Color[] lanes = palette().lanes();
        return lanes[Math.floorMod(index, lanes.length)];
    }

    /** Colour of a stash node and its tie line back to the history it came from. */
    public static Color stash() {
        return palette().stash();
    }

    /**
     * The quiet grey of the overflow marker: the dotted line in the graph's last column
     * standing for lanes there was no room to draw. The canvas twin of Primer's subtle
     * foreground, so it reads as "there is more" rather than as another branch.
     *
     * <p>Parsed once, with the rest of the palette: {@code Color.web} parses its string on
     * every call, and this was asked for per painted row - the overflow marker on every row
     * of a wide graph.
     */
    public static Color overflow() {
        return palette().overflow();
    }

    private static final Color TEXT_ON_LIGHT  = Color.web("#0b1017");
    private static final Color TEXT_ON_DARK   = Color.web("#ffffff");

    /** Work in progress and history rewrites - the canvas twin of {@code -jfx-attention}. */
    public static Color attention() {
        return palette().attention();
    }

    /**
     * The row background a commit dot is cut out of: the canvas twin of {@code -jfx-list-bg}.
     *
     * <p>Used as the stroke that separates a dot from the lanes running underneath it, and
     * as the fill that hollows out a merge. Both were a literal {@code #0d1117}, which on
     * a white ground drew a black ring instead of disappearing - the merge indicator was
     * inverted in the light theme rather than merely dim.
     */
    public static Color ground() {
        return palette().ground();
    }

    /**
     * The background for a row on {@code laneColor}'s branch, or {@code null} for none.
     *
     * <p>Deliberately derived from the lane rather than given its own scale: the band down
     * a run of commits and the line joining them are the same fact stated twice, and they
     * stop agreeing the moment they are maintained separately.
     */
    public static Color rowTint(Color laneColor, double alpha) {
        if (laneColor == null || alpha <= 0) return null;
        // Half strength on a light ground. The same alpha that is a faint wash over
        // near-black is a visible pastel over white - a page of pink, green and lavender
        // stripes that competed with the text they were meant to sit behind.
        double strength = alpha * palette().tintStrength();
        return Color.color(laneColor.getRed(), laneColor.getGreen(), laneColor.getBlue(), strength);
    }

    /** Readable text on top of an avatar filled with {@code background}. */
    public static Color textOn(Color background) {
        // Pure contrast maths rather than a per-theme choice: an avatar hue is picked
        // from a hash and can land anywhere.
        return luminance(background) > 0.55 ? TEXT_ON_LIGHT : TEXT_ON_DARK;
    }
}
