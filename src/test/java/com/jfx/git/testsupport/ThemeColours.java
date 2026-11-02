package com.jfx.git.testsupport;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour a theme token really resolves to, and the contrast between two colours.
 *
 * <p>A token file says what a theme means to be; this asks JavaFX what it is, through the
 * same cascade the application runs (Primer, the theme's token file, the application's
 * stylesheets). That is the only reading that cannot disagree with the screen: a token
 * defined as another token, as a colour with an alpha, or not at all, all come back as a
 * paint or as a failure here.
 *
 * <p>Applies {@code theme} and leaves it applied; a test that cares restores the one it
 * found, on the FX thread, as the others that switch the theme do.
 */
public final class ThemeColours {

    private ThemeColours() {
    }

    /** Each token's colour under {@code theme}, in the order asked. Fails on one that resolves to nothing. */
    public static Map<String, Color> resolve(ThemeManager.Theme theme, List<String> tokens) {
        return FxHarness.call(() -> {
            StackPane root = new StackPane();
            Map<String, Region> probes = new LinkedHashMap<>();
            for (String token : tokens) {
                // An inline style resolves its lookups from the root, which is where the
                // token files put every token - so this reads them as a node would.
                Region probe = new Region();
                probe.setStyle("-fx-background-color: " + token + ";");
                probes.put(token, probe);
                root.getChildren().add(probe);
            }
            Scene scene = new Scene(root, 100, 100);
            ThemeManager.register(scene);
            try {
                ThemeManager.apply(theme);
                root.applyCss();

                Map<String, Color> resolved = new LinkedHashMap<>();
                probes.forEach((token, probe) -> {
                    Background background = probe.getBackground();
                    assertNotNull(background, theme + ": " + token + " resolves to nothing");
                    Paint paint = background.getFills().get(0).getFill();
                    assertTrue(paint instanceof Color, theme + ": " + token + " is not a colour: " + paint);
                    resolved.put(token, (Color) paint);
                });
                return resolved;
            } finally {
                ThemeManager.unregister(scene);
            }
        });
    }

    public static Map<String, Color> resolve(ThemeManager.Theme theme, String... tokens) {
        return resolve(theme, List.of(tokens));
    }

    public static Color resolve(ThemeManager.Theme theme, String token) {
        return resolve(theme, List.of(token)).get(token);
    }

    /** A translucent colour as it looks over an opaque ground. */
    public static Color over(Color top, Color ground) {
        double a = top.getOpacity();
        return Color.color(top.getRed() * a + ground.getRed() * (1 - a),
                top.getGreen() * a + ground.getGreen() * (1 - a),
                top.getBlue() * a + ground.getBlue() * (1 - a));
    }

    private static double channel(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Relative luminance as WCAG defines it, 0 to 1. */
    public static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    /**
     * How much white a colour carries, 0 to 1: its smallest channel, the grey all three share
     * (HWB's whiteness). A translucent white layer adds exactly this to whatever is under it,
     * so on a dark ground it is the measure of a haze.
     */
    public static double whiteness(Color c) {
        return Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
    }

    /** CIE L*, 0 to 100: lightness spaced as the eye sees it, where luminance crowds the darks together. */
    public static double lightness(Color c) {
        double y = luminance(c);
        return y > 216.0 / 24389 ? 116 * Math.cbrt(y) - 16 : y * 24389.0 / 27;
    }

    /** WCAG contrast ratio, 1 to 21. */
    public static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    public static String hex(Color c) {
        return String.format("#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
    }

    /** Fails unless {@code text} has at least {@code minimum}:1 against {@code ground}. */
    public static void assertContrast(double minimum, Color text, Color ground, String what) {
        double ratio = contrast(text, ground);
        assertTrue(ratio >= minimum, what + " is " + hex(text) + " on " + hex(ground) + ": "
                + String.format("%.2f", ratio) + ":1, under " + minimum + ":1");
    }
}
