package com.jfx.git.ui;

import com.jfx.git.core.Settings;
import com.jfx.git.testsupport.MainWindowFixture;
import com.jfx.git.ui.ThemeManager.Theme;

import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.jfx.git.testsupport.ThemeColours.hex;
import static com.jfx.git.testsupport.ThemeColours.resolve;
import static com.jfx.git.testsupport.ThemeColours.whiteness;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the main window really paints in each theme, read from its pixels.
 *
 * <p>Some dark themes looked as if a translucent white film lay over the window
 * (2026-10-20). A film like that can come from places a token file does not show - a
 * translucent light fill on a pane, an opacity under one on a container, an effect - so
 * this renders the window and measures the screen rather than the stylesheets: the large
 * areas of colour are the theme's own grounds and nothing laid over them, and in a dark
 * theme those areas carry no more white, between them, than Midnight's and Dark's do with
 * room to spare. {@code ThemeReadabilityTest} holds each ground token on its own.
 */
class ThemeGroundsOnScreenTest {

    /** The tokens a region of the main window takes its ground from. */
    private static final List<String> GROUNDS = List.of("-color-bg-default", "-color-bg-subtle", "-color-bg-inset",
            "-jfx-list-bg", "-jfx-list-alt-bg", "-jfx-tabs-bg", "-jfx-tabs-selected-bg", "-jfx-status-bg");

    /** A colour covering this much of the window is a ground, not text, an icon or a row's tint. */
    private static final double LARGE = 0.08;

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

    @Test
    void theWindowShowsItsGroundsWithNothingLaidOverThem() throws Exception {
        // Each history row is washed with its branch's colour, a few per cent of it; with the
        // wash on, a run of rows on one branch is a large area of a colour no token names.
        // GraphPaletteTest holds the wash; here only the grounds under it are wanted.
        com.jfx.git.core.AppPreferences.setGraphBranchTint(false);
        List<String> problems = new ArrayList<>();
        try (MainWindowFixture window = MainWindowFixture.create(root.resolve("repo"))) {
            // Shown: a scene that never is keeps some regions' old ground in its second
            // snapshot after a theme switch (the sidebar's section panes did), which the
            // screen never shows.
            window.open(1280, 800, Theme.DARK, true);
            for (Theme theme : Theme.values()) {
                Map<String, Color> tokens = resolve(theme, GROUNDS);
                FxHarness.run(() -> ThemeManager.apply(theme));
                window.layOut();
                Map<Color, Double> large = largeAreas(window);

                double covered = large.values().stream().mapToDouble(Double::doubleValue).sum();
                String found = large.entrySet().stream()
                        .sorted(Map.Entry.<Color, Double>comparingByValue().reversed())
                        .map(e -> hex(e.getKey()) + " " + Math.round(100 * e.getValue()) + "%")
                        .collect(Collectors.joining(", "));
                for (Color colour : large.keySet()) {
                    if (!tokens.containsValue(colour)) {
                        problems.add(theme + ": " + hex(colour) + " covers a large part of the window and is none of"
                                + " its grounds - something is laid over them (" + found + ")");
                    }
                }
                if (covered < 0.6) {
                    problems.add(theme + ": the grounds cover only " + found);
                }
                if (theme.base() == ThemeManager.Base.DARK) {
                    double white = large.entrySet().stream()
                            .mapToDouble(e -> whiteness(e.getKey()) * e.getValue()).sum() / covered;
                    if (white > 0.11) {
                        problems.add(theme + ": the window's grounds carry " + String.format("%.3f", white)
                                + " white on average, a haze over it; Midnight and Dark carry about 0.06 (" + found + ")");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Each colour covering at least {@link #LARGE} of the window, with the share it covers. */
    private static Map<Color, Double> largeAreas(MainWindowFixture window) {
        return FxHarness.call(() -> {
            WritableImage shot = window.scene().getRoot().snapshot(null, null);
            PixelReader pixels = shot.getPixelReader();
            int width = (int) shot.getWidth();
            int height = (int) shot.getHeight();
            Map<Integer, Integer> counts = new HashMap<>();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    counts.merge(pixels.getArgb(x, y), 1, Integer::sum);
                }
            }
            double total = (double) width * height;
            Map<Color, Double> large = new HashMap<>();
            counts.forEach((argb, count) -> {
                if (count / total >= LARGE) {
                    large.put(Color.rgb((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff, ((argb >>> 24) & 0xff) / 255.0),
                            count / total);
                }
            });
            return large;
        });
    }
}
