package com.jfx.git.graph;

import com.jfx.git.testsupport.ThemeColours;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the rule {@link GraphPalette} states but the compiler cannot enforce: a colour
 * added for one theme must be added for the other.
 *
 * <p>This is the check that was missing. The lane palette lived as a single hardcoded
 * array because a {@code Canvas} cannot read CSS, and once it was in Java nothing kept it
 * in step with the token files — so the light theme was drawn with the dark theme's
 * lanes, and the merge indicator was drawn with the dark theme's background, which on
 * white produced a black pip where a hole was intended.
 */
class GraphPaletteTest {

    private static ThemeManager.Theme original;

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
        original = FxHarness.call(ThemeManager::current);
    }

    @AfterAll
    static void restoreTheme() {
        FxHarness.run(() -> ThemeManager.apply(original));
    }

    private static Color[] lanesUnder(ThemeManager.Theme theme) {
        return FxHarness.call(() -> {
            ThemeManager.apply(theme);
            Color[] lanes = new Color[GraphPalette.laneCount()];
            for (int i = 0; i < lanes.length; i++) {
                lanes[i] = GraphPalette.lane(i);
            }
            return lanes;
        });
    }

    @Test
    @DisplayName("both themes define a full, distinct set of lane colours")
    void bothThemesAreComplete() {
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            Color[] lanes = lanesUnder(theme);

            // A short palette would not fail outright - lane() wraps - it would quietly
            // hand two lanes the same colour. Distinctness is what catches that.
            Set<Color> distinct = new HashSet<>(java.util.Arrays.asList(lanes));
            assertEquals(lanes.length, distinct.size(),
                    theme + " repeats a lane colour, which means its palette is short");
        }
    }

    @Test
    @DisplayName("the light theme is retuned, not the dark one reused")
    void lightIsNotACopyOfDark() {
        Color[] dark = lanesUnder(ThemeManager.Theme.DARK);
        Color[] light = lanesUnder(ThemeManager.Theme.LIGHT);

        assertEquals(dark.length, light.length);
        for (int i = 0; i < dark.length; i++) {
            assertNotEquals(dark[i], light[i],
                    "lane " + i + " is the same colour in both themes, so one of them is "
                            + "wearing the other's palette");
        }
    }

    @Test
    @DisplayName("light lanes are dark enough to read on a light ground")
    void lightLanesHoldTheirContrast() {
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            if (theme.base() != ThemeManager.Base.LIGHT) continue;
            for (Color lane : lanesUnder(theme)) {
                assertTrue(luminance(lane) < 0.5,
                        theme + ": lane " + lane + " is too pale to sit on a light ground");
            }
        }
    }

    @Test
    @DisplayName("dark lanes are bright enough to read on a dark ground")
    void darkLanesHoldTheirContrast() {
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            if (theme.base() != ThemeManager.Base.DARK) continue;
            for (Color lane : lanesUnder(theme)) {
                assertTrue(luminance(lane) > 0.3,
                        theme + ": lane " + lane + " is too dim to sit on a dark ground");
            }
        }
    }

    @Test
    @DisplayName("every built-in theme has a palette of its own, not its base's")
    void everyThemeHasAPalette() {
        // palette() falls back to the base's when a theme has no entry, so that a theme
        // added without one still draws - and so nothing but this notices it is missing.
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            assertTrue(GraphPalette.hasPaletteOf(theme), theme + " has no entry in GraphPalette.PALETTES");
        }
    }

    /**
     * The one that says the lanes read on every background they are drawn over - measured, in
     * WCAG's terms, against what the theme's own tokens resolve to. 3:1 is what a graphical
     * object (a line that carries meaning) has to clear; the washes a row can carry are
     * held to a little less, because a lane is only ever drawn over one of them at a time
     * and the wash is itself faint.
     */
    @Test
    @DisplayName("every lane reads on the list, on a selected row, on a hovered one and on an alternate one")
    void lanesReadOnEveryBackgroundTheyAreDrawnOver() {
        ThemeManager.Theme original = FxHarness.call(ThemeManager::current);
        try {
            for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
                Map<String, Color> c = ThemeColours.resolve(theme, "-jfx-list-bg", "-jfx-list-alt-bg",
                        "-jfx-selected-bg", "-color-neutral-muted");
                Color list = c.get("-jfx-list-bg");
                Map<String, Color> backgrounds = new java.util.LinkedHashMap<>();
                backgrounds.put("the list", list);
                backgrounds.put("an alternate row", ThemeColours.over(c.get("-jfx-list-alt-bg"), list));
                backgrounds.put("a hovered row", ThemeColours.over(c.get("-color-neutral-muted"), list));
                backgrounds.put("a selected row", ThemeColours.over(c.get("-jfx-selected-bg"), list));

                Color[] lanes = lanesUnder(theme);
                for (var background : backgrounds.entrySet()) {
                    double minimum = background.getKey().equals("the list") ? 3.0 : 1.8;
                    for (int i = 0; i < lanes.length; i++) {
                        ThemeColours.assertContrast(minimum, lanes[i], background.getValue(),
                                theme + ": lane " + i + " on " + background.getKey());
                    }
                }
            }
        } finally {
            FxHarness.run(() -> ThemeManager.apply(original));
        }
    }

    @Test
    @DisplayName("the canvas's copies match the tokens they stand for, in every theme")
    void theCanvasCopiesMatchTheTokenFiles() {
        // The canvas cannot read CSS, so GraphPalette holds the ground, the attention
        // colour and the stash colour as literals. A copy that drifts from its token draws a
        // dot cut out of a colour the row is not, or a WIP marker in another orange from the
        // WIP label beside it - the kind of difference that is seen and not found.
        ThemeManager.Theme original = FxHarness.call(ThemeManager::current);
        try {
            for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
                Map<String, Color> c = ThemeColours.resolve(theme, "-jfx-list-bg", "-jfx-attention",
                        "-color-warning-fg", "-color-fg-subtle");
                Color ground = FxHarness.call(GraphPalette::ground);
                Color attention = FxHarness.call(GraphPalette::attention);
                Color stash = FxHarness.call(GraphPalette::stash);
                Color overflow = FxHarness.call(GraphPalette::overflow);

                assertEquals(ThemeColours.hex(c.get("-jfx-list-bg")), ThemeColours.hex(ground),
                        theme + ": GraphPalette.ground() is not -jfx-list-bg");
                assertEquals(ThemeColours.hex(c.get("-jfx-attention")), ThemeColours.hex(attention),
                        theme + ": GraphPalette.attention() is not -jfx-attention");
                assertEquals(ThemeColours.hex(c.get("-color-warning-fg")), ThemeColours.hex(stash),
                        theme + ": GraphPalette.stash() is not the warning foreground");
                // Quiet, but there: the marker has to be seen to say "more lanes than fit".
                ThemeColours.assertContrast(2.0, overflow, ground, theme + ": the overflow marker");
                assertTrue(ThemeColours.contrast(overflow, ground) < ThemeColours.contrast(lanesUnder(theme)[0], ground),
                        theme + ": the overflow marker is louder than a lane, so it reads as one");
            }
        } finally {
            FxHarness.run(() -> ThemeManager.apply(original));
        }
    }

    @Test
    @DisplayName("no lane is much louder than its neighbours")
    void lanesCarryEqualWeight() {
        // A palette generated at one fixed brightness has yellow-greens shouting and
        // blues whispering. Each hue is corrected towards a luminance band instead, and
        // this is what says the correction actually ran.
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            Color[] lanes = lanesUnder(theme);
            double min = 1, max = 0;
            for (Color lane : lanes) {
                min = Math.min(min, luminance(lane));
                max = Math.max(max, luminance(lane));
            }
            assertTrue(max - min < 0.34,
                    theme + " lanes span " + String.format("%.2f", max - min)
                            + " in luminance, so some shout and others whisper");
        }
    }

    @Test
    @DisplayName("consecutive lanes are far apart in hue")
    void consecutiveLanesAreDistinct() {
        // LaneAssigner hands out colours with nextColor++, so consecutive indices are what
        // end up drawn side by side. The golden angle is chosen for exactly this.
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            Color[] lanes = lanesUnder(theme);
            for (int i = 1; i < lanes.length; i++) {
                double separation = hueDistance(lanes[i - 1].getHue(), lanes[i].getHue());
                assertTrue(separation > 60,
                        theme + ": lanes " + (i - 1) + " and " + i + " are only "
                                + String.format("%.0f", separation) + "° apart in hue");
            }
        }
    }

    @Test
    @DisplayName("the assigner hands out as many colours as the palette has")
    void assignerAgreesWithPalette() {
        // LaneAssigner keeps its own constant so its tests stay headless. This is the
        // seam where the two could silently drift apart.
        assertEquals(GraphPalette.laneCount(), LaneAssigner.PALETTE_SIZE,
                "LaneAssigner.PALETTE_SIZE and GraphPalette disagree on the palette size");
    }

    private static double luminance(Color colour) {
        return 0.2126 * colour.getRed() + 0.7152 * colour.getGreen() + 0.0722 * colour.getBlue();
    }

    private static double hueDistance(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    @Test
    @DisplayName("the ground a dot is cut out of follows the theme")
    void groundFollowsTheme() {
        // The bug this replaces: a literal #0d1117 painted the merge commit's hollow
        // centre, so on the light theme it drew a black pip rather than a hole.
        Color dark = FxHarness.call(() -> {
            ThemeManager.apply(ThemeManager.Theme.DARK);
            return GraphPalette.ground();
        });
        Color light = FxHarness.call(() -> {
            ThemeManager.apply(ThemeManager.Theme.LIGHT);
            return GraphPalette.ground();
        });

        assertNotEquals(dark, light);
        assertEquals(Color.web("#0d1117"), dark);
        assertEquals(Color.web("#ffffff"), light);
    }

    @Test
    @DisplayName("the attention colour matches the token the stylesheets already define")
    void attentionMatchesTokenFiles() {
        // -jfx-attention in theme-dark.css and theme-light.css. The canvas cannot read
        // them, so these two values are a copy - and a copy that drifts is a bug.
        assertEquals(Color.web("#f0883e"), FxHarness.call(() -> {
            ThemeManager.apply(ThemeManager.Theme.DARK);
            return GraphPalette.attention();
        }));
        assertEquals(Color.web("#bc4c00"), FxHarness.call(() -> {
            ThemeManager.apply(ThemeManager.Theme.LIGHT);
            return GraphPalette.attention();
        }));
    }

    @Test
    @DisplayName("a row tint keeps its lane's hue and only changes the alpha")
    void rowTintIsTheLaneColour() {
        Color lane = Color.web("#58a6ff");
        Color tint = FxHarness.call(() -> {
            ThemeManager.apply(ThemeManager.Theme.DARK);
            return GraphPalette.rowTint(lane, 0.055);
        });

        assertEquals(lane.getRed(), tint.getRed(), 0.001);
        assertEquals(lane.getGreen(), tint.getGreen(), 0.001);
        assertEquals(lane.getBlue(), tint.getBlue(), 0.001);
        assertEquals(0.055, tint.getOpacity(), 0.001);
    }

    @Test
    @DisplayName("a row tint is half as strong on the light theme's white ground")
    void rowTintIsQuieterOnALightGround() {
        Color lane = Color.web("#0969da");
        Color tint = FxHarness.call(() -> {
            ThemeManager.apply(ThemeManager.Theme.LIGHT);
            return GraphPalette.rowTint(lane, 0.10);
        });
        FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.DARK));

        // The alpha that is a faint wash over near-black is a pastel stripe over white.
        assertEquals(0.10 * GraphPalette.LIGHT_TINT_STRENGTH, tint.getOpacity(), 0.001);
        assertEquals(lane.getBlue(), tint.getBlue(), 0.001, "the hue is still the lane's");
    }

    @Test
    @DisplayName("no tint is produced when tinting is off")
    void noTintAtZeroAlpha() {
        org.junit.jupiter.api.Assertions.assertNull(GraphPalette.rowTint(Color.RED, 0));
        org.junit.jupiter.api.Assertions.assertNull(GraphPalette.rowTint(null, 0.5));
    }

    @Test
    @DisplayName("avatar text is chosen for contrast, not for the theme")
    void textOnPicksByContrast() {
        // An avatar hue comes from a hash and can land anywhere, so the text on top is
        // decided by the hue underneath it rather than by which theme is active.
        assertEquals(Color.web("#0b1017"), GraphPalette.textOn(Color.web("#9fd0ff")));
        assertEquals(Color.web("#ffffff"), GraphPalette.textOn(Color.web("#1a3a5c")));
    }
}
