package com.jfx.git.ui;

import com.jfx.git.core.Settings;
import com.jfx.git.ui.ThemeManager.Theme;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jfx.git.testsupport.ThemeColours.assertContrast;
import static com.jfx.git.testsupport.ThemeColours.contrast;
import static com.jfx.git.testsupport.ThemeColours.hex;
import static com.jfx.git.testsupport.ThemeColours.lightness;
import static com.jfx.git.testsupport.ThemeColours.luminance;
import static com.jfx.git.testsupport.ThemeColours.over;
import static com.jfx.git.testsupport.ThemeColours.resolve;
import static com.jfx.git.testsupport.ThemeColours.whiteness;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether each built-in theme can be read: the colours JavaFX actually resolves for the
 * tokens, held to contrast ratios against the grounds they are drawn on.
 *
 * <p>A theme's token file can define every name and still be unreadable - a grey that is
 * fine on one ground and invisible on the next - and nothing in the file says so. This
 * resolves each token through the real cascade (Primer's stylesheet, the theme's file, the
 * application's rules; see {@code ThemeColours}) and measures, which is also how the two
 * original themes are held to the same floor as the new ones: the thresholds are the
 * lowest a theme here already clears, not an ideal.
 *
 * <p>The ratios are WCAG's: 4.5 for text that is read, 3 for what is only seen (a colour
 * that marks a state, a muted caption, an icon).
 */
class ThemeReadabilityTest {

    /** What text may be drawn on: the regions of the main window, by the token that paints each. */
    private static final Map<String, String> GROUNDS = new LinkedHashMap<>();

    static {
        GROUNDS.put("panel", "-color-bg-default");
        GROUNDS.put("chrome", "-color-bg-subtle");
        GROUNDS.put("list", "-jfx-list-bg");
        GROUNDS.put("status bar", "-jfx-status-bg");
        GROUNDS.put("tab strip", "-jfx-tabs-bg");
    }

    private static final String[] READ_TEXT = {"-color-fg-default", "-jfx-fg-bright", "-color-fg-muted", "-color-accent-fg"};
    private static final String[] SEEN_TEXT = {"-color-fg-subtle", "-color-success-fg", "-color-danger-fg",
            "-color-warning-fg", "-jfx-attention"};

    @TempDir
    Path root;

    private Theme original;

    @BeforeEach
    void setUp() {
        FxHarness.start();
        // Every switch is remembered; into a file of this test's own, not the developer's.
        Settings.useFile(root.resolve("settings.properties"));
        original = FxHarness.call(ThemeManager::current);
    }

    @AfterEach
    void tearDown() {
        FxHarness.run(() -> ThemeManager.apply(original));
        Settings.useFile(null);
    }

    private static List<String> tokens(String... more) {
        List<String> all = new ArrayList<>(GROUNDS.values());
        all.addAll(List.of(READ_TEXT));
        all.addAll(List.of(SEEN_TEXT));
        all.addAll(List.of(more));
        return all.stream().distinct().toList();
    }

    @Test
    void textIsReadableOnEveryGroundInEveryTheme() {
        for (Theme theme : Theme.values()) {
            Map<String, Color> colours = resolve(theme, tokens());
            for (var ground : GROUNDS.entrySet()) {
                Color under = colours.get(ground.getValue());
                assertEquals(1.0, under.getOpacity(), 0.001, theme + "'s " + ground.getKey() + " is translucent");
                for (String token : READ_TEXT) {
                    assertContrast(4.5, colours.get(token), under, theme + ": " + token + " on the " + ground.getKey());
                }
                for (String token : SEEN_TEXT) {
                    assertContrast(3.0, colours.get(token), under, theme + ": " + token + " on the " + ground.getKey());
                }
            }
        }
    }

    @Test
    void aSelectedRowAndTheCurrentBranchRowStayReadable() {
        for (Theme theme : Theme.values()) {
            Map<String, Color> c = resolve(theme, "-jfx-list-bg", "-color-bg-default", "-jfx-selected-bg",
                    "-jfx-current-bg", "-jfx-current-fg", "-color-fg-default", "-jfx-fg-bright", "-color-fg-muted",
                    "-color-accent-fg", "-color-neutral-muted", "-jfx-list-alt-bg");
            // The selected row is painted over the list, the current branch's over the panel.
            Color selected = over(c.get("-jfx-selected-bg"), c.get("-jfx-list-bg"));
            assertContrast(4.5, c.get("-color-fg-default"), selected, theme + ": text on a selected row");
            assertContrast(4.5, c.get("-jfx-fg-bright"), selected, theme + ": the selected commit's subject");
            assertContrast(3.0, c.get("-color-fg-muted"), selected, theme + ": muted text on a selected row");
            assertContrast(3.0, c.get("-color-accent-fg"), selected, theme + ": a link on a selected row");

            Color current = over(c.get("-jfx-current-bg"), c.get("-color-bg-default"));
            assertContrast(4.5, c.get("-jfx-current-fg"), current, theme + ": the current branch's name");

            Color hover = over(c.get("-color-neutral-muted"), c.get("-jfx-list-bg"));
            assertContrast(4.5, c.get("-color-fg-default"), hover, theme + ": text under the pointer");
            Color alt = over(c.get("-jfx-list-alt-bg"), c.get("-jfx-list-bg"));
            assertContrast(4.5, c.get("-color-fg-default"), alt, theme + ": text on every second row");
        }
    }

    @Test
    void labelsOnFilledButtonsAndChipsAreReadable() {
        for (Theme theme : Theme.values()) {
            Map<String, Color> c = resolve(theme, "-color-fg-emphasis", "-color-accent-emphasis",
                    "-color-success-emphasis", "-color-danger-emphasis", "-jfx-fg-on-success", "-color-success-fg");
            // Bold labels on a fill: the primary, success and danger buttons, and the HEAD chip.
            // Primer's own light success green is 3.2:1 under white, so the two themes that
            // are Primer's keep what they have; a theme with colours of its own does better.
            double floor = theme == Theme.DARK || theme == Theme.LIGHT ? 3.0 : 4.5;
            for (String fill : List.of("-color-accent-emphasis", "-color-success-emphasis", "-color-danger-emphasis")) {
                assertContrast(floor, c.get("-color-fg-emphasis"), c.get(fill), theme + ": a button's label on " + fill);
            }
            // The tag chip: dark or light text on the bright success green, whichever the base needs.
            assertContrast(4.5, c.get("-jfx-fg-on-success"), c.get("-color-success-fg"), theme + ": a tag chip's label");
        }
    }

    /**
     * The code area is on the panel ground, and its text is the syntax tokens. The grammar's
     * tokens for ignored files are tints, not text, so they are left out.
     */
    @Test
    void syntaxColoursAreReadableOnTheCodeGround() throws Exception {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("(-jfx-(?:syntax|tm)-[a-z0-9-]+)\\s*:").matcher(read("/css/theme-dark.css"));
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!token.matches(".*(ignored|untracked|carriage-return)$")) {
                tokens.add(token);
            }
        }
        assertTrue(tokens.size() > 60, "found only " + tokens.size() + " syntax tokens");
        tokens.add("-color-bg-default");

        for (Theme theme : Theme.values()) {
            Map<String, Color> c = resolve(theme, tokens);
            Color ground = c.get("-color-bg-default");
            for (String token : tokens) {
                if (!token.equals("-color-bg-default")) {
                    assertContrast(3.0, c.get(token), ground, theme + ": " + token + " in the code area");
                }
            }
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = ThemeReadabilityTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * No dark theme's grounds carry a white haze.
     *
     * <p>Some dark themes read as if a translucent white film lay over the whole window
     * (2026-10-20): Dark Green, Nord Dark and Gruvbox Dark, set beside Midnight. Nothing was
     * laid over them - the screen shows the tokens exactly ({@code ThemeGroundsOnScreenTest}) -
     * the grounds themselves were lifted greys, 11 to 23 per cent white in every channel where
     * Dark and Midnight carry at most 9. A white layer adds exactly that, a colour's smallest
     * channel, so that is what is held down: to 0.10 on the grounds text is read on, 0.13 on
     * the chrome and popups a step above them. A coloured ground is no haze however light it
     * is: Solarized's teal has no white in it at all.
     */
    @Test
    void noDarkThemeWashesItsGroundsWithWhite() {
        Map<String, Double> ceilings = new LinkedHashMap<>();
        ceilings.put("-color-bg-default", 0.10);
        ceilings.put("-jfx-list-bg", 0.10);
        ceilings.put("-jfx-status-bg", 0.10);
        ceilings.put("-color-bg-inset", 0.10);
        ceilings.put("-color-bg-subtle", 0.13);
        ceilings.put("-jfx-tabs-bg", 0.13);
        ceilings.put("-color-bg-overlay", 0.13);
        List<String> hazy = new ArrayList<>();
        for (Theme theme : Theme.values()) {
            if (theme.base() != ThemeManager.Base.DARK) {
                continue;
            }
            Map<String, Color> c = resolve(theme, List.copyOf(ceilings.keySet()));
            ceilings.forEach((token, ceiling) -> {
                Color ground = c.get(token);
                if (whiteness(ground) > ceiling) {
                    hazy.add(theme + ": " + token + " is " + hex(ground) + ", "
                            + String.format("%.3f", whiteness(ground)) + " white, over " + ceiling);
                }
            });
        }
        assertTrue(hazy.isEmpty(), String.join("\n", hazy));
    }

    /**
     * Body text reads on the code, the panels and the history as crisply as in the dark themes
     * that read cleanly - Midnight 12.0:1, Dark 12.3:1 - which a lifted ground cannot give:
     * Dark Green's was 10.1:1, Gruvbox Dark's 10.7:1, Nord Dark's 9.3:1. Solarized Dark is left
     * out: its ground carries no white (above) and stays base03 as published, so its body text
     * is held to its own floor below.
     */
    @Test
    void bodyTextReadsAsCrisplyAsInTheCleanDarkThemes() {
        for (Theme theme : Theme.values()) {
            if (theme.base() != ThemeManager.Base.DARK || theme == Theme.SOLARIZED_DARK) {
                continue;
            }
            Map<String, Color> c = resolve(theme, "-color-fg-default", "-color-bg-default", "-jfx-list-bg");
            assertContrast(11.0, c.get("-color-fg-default"), c.get("-color-bg-default"), theme + ": body text on the panel");
            assertContrast(11.0, c.get("-color-fg-default"), c.get("-jfx-list-bg"), theme + ": body text on the history");
        }
    }

    /**
     * Body text reads crisply on the code, the panels and the history in every theme, at no less
     * than the lowest a theme here clears: Gruvbox Light's 9.2:1.
     *
     * <p>Solarized's own body colours, base0 and base00, are low contrast by design, and even
     * lifted to clear 4.5:1 they left Solarized Dark at 6.7:1 and Solarized Light at 6.5:1 on
     * their panels (2026-10-21), the lowest of any theme by far. Each is lifted in its own hue
     * to 10:1, with the grounds and accents kept as published, and held there.
     */
    @Test
    void bodyTextReadsCrisplyInEveryTheme() {
        List<String> dim = new ArrayList<>();
        for (Theme theme : Theme.values()) {
            double floor = theme == Theme.SOLARIZED_DARK || theme == Theme.SOLARIZED_LIGHT ? 10.0 : 9.0;
            Map<String, Color> c = resolve(theme, "-color-fg-default", "-color-bg-default", "-jfx-list-bg");
            Color text = c.get("-color-fg-default");
            for (String ground : List.of("-color-bg-default", "-jfx-list-bg")) {
                double ratio = contrast(text, c.get(ground));
                if (ratio < floor) {
                    dim.add(theme + ": body text " + hex(text) + " on " + ground + " " + hex(c.get(ground)) + " is "
                            + String.format("%.2f", ratio) + ":1, under " + floor);
                }
            }
        }
        assertTrue(dim.isEmpty(), String.join("\n", dim));
    }

    /**
     * Darkening a ground must not flatten the layout. The themes that separate chrome, panels
     * and history by ground alone, with quiet borders, keep each a visible step from the next,
     * in CIE L* (where a step reads the same however dark the ground): Midnight's are 4.7
     * and 2.1. Nord Dark's history sat only 1.9 under its panels before its grounds were
     * darkened.
     */
    @Test
    void theGreyDarkThemesStepTheirGroundsVisibly() {
        for (Theme theme : List.of(Theme.DARK_GREEN, Theme.NORD_DARK, Theme.GRUVBOX_DARK)) {
            Map<String, Color> c = resolve(theme, "-color-bg-subtle", "-color-bg-default", "-jfx-list-bg");
            double chrome = lightness(c.get("-color-bg-subtle"));
            double panel = lightness(c.get("-color-bg-default"));
            double list = lightness(c.get("-jfx-list-bg"));
            assertTrue(chrome - panel >= 3.5, theme + ": chrome over the panels by " + String.format("%.1f", chrome - panel) + " L*");
            assertTrue(panel - list >= 2.0, theme + ": panels over the history by " + String.format("%.1f", panel - list) + " L*");
        }
    }

    @Test
    void theGreenThemesLayerChromePanelsAndHistoryInSteps() {
        // Chrome a step off the panels, the panels a step off the history: dark above to
        // below in the dark theme, the other way about in the light one.
        for (Theme theme : List.of(Theme.DARK_GREEN, Theme.LIGHT_GREEN)) {
            Map<String, Color> c = resolve(theme, "-color-bg-subtle", "-color-bg-default", "-jfx-list-bg",
                    "-jfx-tabs-bg", "-jfx-status-bg");
            double chrome = luminance(c.get("-color-bg-subtle"));
            double panel = luminance(c.get("-color-bg-default"));
            double list = luminance(c.get("-jfx-list-bg"));
            if (theme.base() == ThemeManager.Base.DARK) {
                assertTrue(chrome > panel && panel > list, theme + ": chrome > panels > history, in luminance");
            } else {
                assertTrue(chrome < panel && panel < list, theme + ": chrome < panels < history, in luminance");
            }
            assertEquals(c.get("-color-bg-subtle"), c.get("-jfx-tabs-bg"), theme + ": the tab strip is the chrome");
            assertEquals(c.get("-color-bg-default"), c.get("-jfx-status-bg"), theme + ": the status bar is a panel");
        }
    }

    @Test
    void theOriginalThemesKeepTheirPrimerGrounds() {
        // The region tokens are names for Primer's own two grounds here, so adding them
        // changed nothing: these are the colours the regions had before there were tokens.
        Map<String, Color> dark = resolve(Theme.DARK, "-jfx-list-bg", "-jfx-tabs-bg", "-jfx-tabs-selected-bg",
                "-jfx-status-bg", "-jfx-selected-bg", "-color-accent-subtle", "-jfx-current-fg", "-color-accent-fg");
        assertEquals(Color.web("#0d1117"), dark.get("-jfx-list-bg"));
        assertEquals(Color.web("#0d1117"), dark.get("-jfx-tabs-bg"));
        assertEquals(Color.web("#0d1117"), dark.get("-jfx-tabs-selected-bg"));
        assertEquals(Color.web("#161b22"), dark.get("-jfx-status-bg"));
        assertEquals(dark.get("-color-accent-subtle"), dark.get("-jfx-selected-bg"));
        assertEquals(dark.get("-color-accent-fg"), dark.get("-jfx-current-fg"));

        Map<String, Color> light = resolve(Theme.LIGHT, "-jfx-list-bg", "-jfx-tabs-bg", "-jfx-status-bg");
        assertEquals(Color.web("#ffffff"), light.get("-jfx-list-bg"));
        assertEquals(Color.web("#ffffff"), light.get("-jfx-tabs-bg"));
        assertEquals(Color.web("#f6f8fa"), light.get("-jfx-status-bg"));
    }
}
