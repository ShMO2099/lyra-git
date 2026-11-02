package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure mode a light theme has is a colour that only exists in one of the two
 * token files, which leaves a control drawing black text on a dark ground. These
 * tests read the stylesheets and check that cannot happen.
 */
class ThemeManagerTest {

    private static final Pattern DEFINITION = Pattern.compile("(-jfx-[a-z0-9-]+)\\s*:");
    private static final Pattern USE = Pattern.compile("(-jfx-[a-z0-9-]+)\\s*;");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");

    private static String read(String resource) throws Exception {
        try (InputStream in = ThemeManagerTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing stylesheet " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    void bothThemesDefineTheSameTokens() throws Exception {
        Set<String> dark = matches(DEFINITION, read("/css/theme-dark.css"));
        Set<String> light = matches(DEFINITION, read("/css/theme-light.css"));

        assertFalse(dark.isEmpty());
        assertEquals(dark, light, "theme-dark.css and theme-light.css must define the same tokens");
    }

    @Test
    void everyTokenTheAppUsesIsDefined() throws Exception {
        Set<String> defined = matches(DEFINITION, read("/css/theme-dark.css"));

        Set<String> used = new TreeSet<>();
        used.addAll(matches(USE, read("/css/lyra.css")));
        used.addAll(matches(USE, read("/diff-styles.css")));

        assertFalse(used.isEmpty(), "the stylesheets should be using the theme tokens");
        used.removeAll(defined);
        assertTrue(used.isEmpty(), "undefined theme tokens: " + used);
    }

    @Test
    void theApplicationStylesheetsHardcodeNoColours() throws Exception {
        // A literal hex here is a colour that cannot follow the theme.
        for (String sheet : new String[]{"/css/lyra.css", "/diff-styles.css"}) {
            Matcher matcher = HEX.matcher(read(sheet));
            assertFalse(matcher.find(),
                    sheet + " still hardcodes " + (matcher.hitEnd() ? "" : matcher.group()));
        }
    }

    @Test
    void onlyTheTokenFilesHoldLiteralColours() throws Exception {
        // The inverse check: the token files are where the hex is supposed to live.
        assertTrue(HEX.matcher(read("/css/theme-dark.css")).find());
        assertTrue(HEX.matcher(read("/css/theme-light.css")).find());
    }

    @Test
    void themesRoundTripThroughTheirNames() {
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            assertFalse(theme.label().isBlank());
            assertNotNull(ThemeManager.url(theme.tokens()), "missing " + theme.tokens());
        }
    }

    @Test
    void thereAreSeveralThemesOfEachBase() {
        long dark = java.util.Arrays.stream(ThemeManager.Theme.values())
                .filter(t -> t.base() == ThemeManager.Base.DARK).count();
        long light = java.util.Arrays.stream(ThemeManager.Theme.values())
                .filter(t -> t.base() == ThemeManager.Base.LIGHT).count();
        assertTrue(dark >= 6, "dark themes: " + dark);
        assertTrue(light >= 6, "light themes: " + light);
        assertEquals(12, ThemeManager.Theme.values().length, "six of each, as asked for");
        assertEquals(ThemeManager.Base.DARK, ThemeManager.Theme.DARK.base());
        assertEquals(ThemeManager.Base.LIGHT, ThemeManager.Theme.LIGHT.base());
        assertEquals(ThemeManager.Base.DARK, ThemeManager.Theme.DARK_GREEN.base());
        assertEquals(ThemeManager.Base.LIGHT, ThemeManager.Theme.LIGHT_GREEN.base());
    }

    @Test
    void themesAreListedDarkOnesFirstThenLightOnes() {
        // Preferences, the View menu and the tours all show them in declaration order and
        // put one rule between the groups, so the order is the grouping.
        boolean seenLight = false;
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            if (theme.base() == ThemeManager.Base.LIGHT) {
                seenLight = true;
            }
            assertFalse(seenLight && theme.base() == ThemeManager.Base.DARK,
                    theme + " is dark but listed after a light theme");
        }
    }

    @Test
    void everyThemeHasADistinctLabelAndIdAndNamesItsBaseInTheList() {
        Set<String> labels = new TreeSet<>();
        Set<String> ids = new TreeSet<>();
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            assertTrue(labels.add(theme.label()), "two themes are labelled " + theme.label());
            assertTrue(ids.add(theme.id()), "two themes have the id " + theme.id());
            assertTrue(theme.displayName().toLowerCase().contains(theme.base().id()),
                    theme.displayName() + " does not say which base it is, in a list that mixes them");
            assertEquals(theme, ThemeManager.Theme.fromName(theme.name()));
            assertEquals(theme, ThemeManager.Theme.fromName(theme.id()));
        }
        assertEquals("Midnight (dark)", ThemeManager.Theme.MIDNIGHT.displayName());
        assertEquals("Dark Green", ThemeManager.Theme.DARK_GREEN.displayName(), "already says it");
        assertEquals("dark", ThemeManager.Theme.DARK.id(), "base: dark, as a user theme has always said it");
        assertEquals("light", ThemeManager.Theme.LIGHT.id());
        assertNull(ThemeManager.Theme.fromName("solarized"));
        assertNull(ThemeManager.Theme.fromName(null));
    }

    @Test
    void theToggleStaysInTheFamilyAndFlipsTheBase() {
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            ThemeManager.Theme other = theme.counterpart();
            assertNotEquals(theme.base(), other.base(), theme + " toggles to a theme of its own base");
        }
        assertEquals(ThemeManager.Theme.LIGHT, ThemeManager.Theme.DARK.counterpart());
        assertEquals(ThemeManager.Theme.DARK, ThemeManager.Theme.LIGHT.counterpart());
        assertEquals(ThemeManager.Theme.LIGHT_GREEN, ThemeManager.Theme.DARK_GREEN.counterpart());
        assertEquals(ThemeManager.Theme.DARK_GREEN, ThemeManager.Theme.LIGHT_GREEN.counterpart());
        assertEquals(ThemeManager.Theme.NORD_LIGHT, ThemeManager.Theme.NORD_DARK.counterpart());
        assertEquals(ThemeManager.Theme.NORD_DARK, ThemeManager.Theme.NORD_LIGHT.counterpart());
        assertEquals(ThemeManager.Theme.SOLARIZED_LIGHT, ThemeManager.Theme.SOLARIZED_DARK.counterpart());
        assertEquals(ThemeManager.Theme.SOLARIZED_DARK, ThemeManager.Theme.SOLARIZED_LIGHT.counterpart());
        assertEquals(ThemeManager.Theme.GRUVBOX_LIGHT, ThemeManager.Theme.GRUVBOX_DARK.counterpart());
        assertEquals(ThemeManager.Theme.GRUVBOX_DARK, ThemeManager.Theme.GRUVBOX_LIGHT.counterpart());
        // The two with no twin go to the other base's default rather than somewhere arbitrary.
        assertEquals(ThemeManager.Theme.LIGHT, ThemeManager.Theme.MIDNIGHT.counterpart());
        assertEquals(ThemeManager.Theme.DARK, ThemeManager.Theme.PAPER.counterpart());
    }

    // ------------------------------------------------------------------
    // The saved choice
    // ------------------------------------------------------------------

    /** Runs {@code body} with {@code ui.theme} read from and written to a file of this test's own. */
    private void withSettings(java.nio.file.Path dir, Runnable body) {
        com.jfx.git.core.Settings.useFile(dir.resolve("settings.properties"));
        try {
            body.run();
        } finally {
            com.jfx.git.core.Settings.useFile(null);
        }
    }

    @Test
    void aUserWhoSavedDarkOrLightComesBackToTheSameTheme(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        // What an installation from before there were more themes has on disk: the enum
        // constant's name, as ThemeManager.apply wrote it.
        withSettings(dir, () -> {
            com.jfx.git.core.Settings.put("ui.theme", "DARK");
            assertEquals(ThemeManager.Theme.DARK, ThemeManager.saved());
            com.jfx.git.core.Settings.put("ui.theme", "LIGHT");
            assertEquals(ThemeManager.Theme.LIGHT, ThemeManager.saved());
        });
    }

    @Test
    void aSavedChoiceIsReadBackForEveryTheme(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        FxHarness.start();
        ThemeManager.Theme original = FxHarness.call(ThemeManager::current);
        withSettings(dir, () -> {
            try {
                for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
                    FxHarness.run(() -> ThemeManager.apply(theme));
                    assertEquals(theme.name(), com.jfx.git.core.Settings.get("ui.theme", ""),
                            "stored under the constant's name, which must never change");
                    assertEquals(theme, ThemeManager.saved());
                }
            } finally {
                FxHarness.run(() -> ThemeManager.apply(original));
            }
        });
    }

    @Test
    void nothingSavedAndNonsenseSavedBothComeBackDark(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        withSettings(dir, () -> {
            assertEquals(ThemeManager.Theme.DARK, ThemeManager.saved(), "nothing saved");
            for (String nonsense : new String[]{"", "  ", "SOLARIZED_MIDNIGHT", "midnight-v2", "null"}) {
                com.jfx.git.core.Settings.put("ui.theme", nonsense);
                assertEquals(ThemeManager.Theme.DARK, ThemeManager.saved(), "saved as '" + nonsense + "'");
            }
        });
    }

    @Test
    void theNoticeOfASecondCopyTakesTheSavedThemeAndWritesNothing(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // The settings file belongs to the copy already running. applySaved stores the
        // theme's name when the file has none, which is exactly the file a second copy
        // must leave as it found it.
        FxHarness.start();
        ThemeManager.Theme original = FxHarness.call(ThemeManager::current);
        java.nio.file.Path file = dir.resolve("settings.properties");
        try {
            for (String contents : new String[]{"ui.zoom=125\n", "ui.theme=PAPER\n"}) {
                java.nio.file.Files.writeString(file, contents);
                withSettings(dir, () -> {
                    FxHarness.run(ThemeManager::applySavedWithoutSaving);
                    com.jfx.git.core.Settings.flush();
                });
                assertEquals(contents.contains("PAPER") ? ThemeManager.Theme.PAPER : ThemeManager.Theme.DARK,
                        FxHarness.call(ThemeManager::current), "the theme the running copy saved");
                assertEquals(contents, java.nio.file.Files.readString(file), "the settings file was written to");
            }

            java.nio.file.Files.writeString(file, "ui.zoom=125\n");
            withSettings(dir, () -> {
                FxHarness.run(ThemeManager::applySaved);
                com.jfx.git.core.Settings.flush();
            });
            assertTrue(java.nio.file.Files.readString(file).contains("ui.theme=DARK"),
                    "applySaved does write the missing name - which is why the notice cannot use it");
        } finally {
            FxHarness.run(() -> ThemeManager.apply(original));
        }
    }

    @Test
    void aSavedNameIsReadLeniently(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        // A hand-edited settings file: the case and the id as well as the constant's name.
        withSettings(dir, () -> {
            com.jfx.git.core.Settings.put("ui.theme", "dark_green");
            assertEquals(ThemeManager.Theme.DARK_GREEN, ThemeManager.saved());
            com.jfx.git.core.Settings.put("ui.theme", "Paper");
            assertEquals(ThemeManager.Theme.PAPER, ThemeManager.saved());
            com.jfx.git.core.Settings.put("ui.theme", " light ");
            assertEquals(ThemeManager.Theme.LIGHT, ThemeManager.saved());
        });
    }

    /**
     * The end-to-end check: build a real scene, switch theme, and read the colour
     * JavaFX actually resolved. This is what proves the user-agent stylesheet, the
     * token file and the application stylesheet are all reaching the node - a
     * missing one shows up here as a fallback colour rather than the expected hex.
     */
    @Test
    void switchingThemeChangesTheColourAControlResolves() {
        FxHarness.start();

        ThemeManager.Theme original = ThemeManager.current();
        try {
            javafx.scene.paint.Color light = FxHarness.call(() -> {
                javafx.scene.control.Label label = new javafx.scene.control.Label("origin/main");
                label.getStyleClass().add("welcome-repo-name");
                javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(label);
                javafx.scene.Scene scene = new javafx.scene.Scene(root, 200, 60);

                ThemeManager.register(scene);
                ThemeManager.apply(ThemeManager.Theme.LIGHT);
                root.applyCss();
                return (javafx.scene.paint.Color) label.getTextFill();
            });

            javafx.scene.paint.Color dark = FxHarness.call(() -> {
                javafx.scene.control.Label label = new javafx.scene.control.Label("origin/main");
                label.getStyleClass().add("welcome-repo-name");
                javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(label);
                javafx.scene.Scene scene = new javafx.scene.Scene(root, 200, 60);

                ThemeManager.register(scene);
                ThemeManager.apply(ThemeManager.Theme.DARK);
                root.applyCss();
                return (javafx.scene.paint.Color) label.getTextFill();
            });

            // Primer's accent foreground: #0969da on white, #58a6ff on near-black.
            assertEquals(javafx.scene.paint.Color.web("#0969da"), light);
            assertEquals(javafx.scene.paint.Color.web("#58a6ff"), dark);
        } finally {
            FxHarness.run(() -> ThemeManager.apply(original));
        }
    }

    /** The colourblind diff palette is app-defined, so check a -jfx- token too. */
    @Test
    void anApplicationTokenResolvesDifferentlyInEachTheme() {
        FxHarness.start();

        ThemeManager.Theme original = ThemeManager.current();
        try {
            javafx.scene.paint.Color light = attentionColour(ThemeManager.Theme.LIGHT);
            javafx.scene.paint.Color dark = attentionColour(ThemeManager.Theme.DARK);

            assertEquals(javafx.scene.paint.Color.web("#bc4c00"), light);
            assertEquals(javafx.scene.paint.Color.web("#f0883e"), dark);
        } finally {
            FxHarness.run(() -> ThemeManager.apply(original));
        }
    }

    private static javafx.scene.paint.Color attentionColour(ThemeManager.Theme theme) {
        return FxHarness.call(() -> {
            javafx.scene.control.Label label = new javafx.scene.control.Label("WIP");
            label.getStyleClass().add("graph-wip");
            javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(label);
            javafx.scene.Scene scene = new javafx.scene.Scene(root, 200, 60);

            ThemeManager.register(scene);
            ThemeManager.apply(theme);
            root.applyCss();
            return (javafx.scene.paint.Color) label.getTextFill();
        });
    }

    @Test
    void anUnknownSavedThemeFallsBackToDarkRatherThanFailing() {
        // Writes to the store ThemeManager actually reads. This used to poke
        // java.util.prefs, which the theme moved out of long ago, so it never planted the
        // unknown value at all — it passed whenever the settings file happened to hold no
        // theme, and started failing the moment another test class ran first and left one
        // behind. A test that green-lights an untouched code path is worse than none.
        String previous = com.jfx.git.core.Settings.get("ui.theme", null);
        try {
            com.jfx.git.core.Settings.put("ui.theme", "SOLARIZED_MIDNIGHT");
            assertEquals(ThemeManager.Theme.DARK, ThemeManager.saved());
        } finally {
            if (previous == null) {
                com.jfx.git.core.Settings.remove("ui.theme");
            } else {
                com.jfx.git.core.Settings.put("ui.theme", previous);
            }
        }
    }
}
