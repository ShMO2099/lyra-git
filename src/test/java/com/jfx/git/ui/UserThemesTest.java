package com.jfx.git.ui;

import com.jfx.git.core.Settings;
import com.jfx.git.ui.UserThemes.UserTheme;

import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User themes: found in their folder, checked, laid over a built-in theme, and taken off
 * again.
 */
class UserThemesTest {

    @TempDir
    Path root;

    private Path themes;

    @BeforeEach
    void setUp() throws Exception {
        FxHarness.start();
        Settings.useFile(root.resolve("settings.properties"));
        themes = Files.createDirectory(root.resolve("themes"));
        UserThemes.useDirectory(themes);
    }

    @AfterEach
    void tearDown() {
        FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.DARK));
        UserThemes.useDirectory(null);
        Settings.useFile(null);
        FxHarness.closeShownStages();
    }

    private Path theme(String name, String css) throws Exception {
        Path file = themes.resolve(name + ".css");
        Files.writeString(file, css);
        return file;
    }

    // ------------------------------------------------------------------
    // Reading them
    // ------------------------------------------------------------------

    @Test
    void themesAreTheStylesheetsInTheFolderByName() throws Exception {
        theme("zebra", "/* base: light */ .root { -jfx-attention: red; }");
        theme("Aurora", "/* base: dark */ .root { -jfx-attention: green; }");
        Files.writeString(themes.resolve("notes.txt"), "not a theme");

        List<UserTheme> found = UserThemes.list();

        assertEquals(List.of("Aurora", "zebra"), found.stream().map(UserTheme::name).toList());
        assertEquals(ThemeManager.Theme.DARK, found.get(0).base());
        assertEquals(ThemeManager.Theme.LIGHT, found.get(1).base());
        assertEquals(List.of(), found.get(0).problems());
        assertNotNull(UserThemes.named("zebra"));
        assertNull(UserThemes.named("missing"));
    }

    @Test
    void withNoFolderThereAreNoThemesAndNoError() {
        UserThemes.useDirectory(root.resolve("never-made"));

        assertEquals(List.of(), UserThemes.list());
    }

    /** A theme being edited is wrong most of the time; it is listed, and what is wrong is said. */
    @Test
    void whatIsWrongWithAThemeIsReportedNotFatal() throws Exception {
        theme("unbased", ".root { -jfx-attention: red; }");
        theme("typo", "/* base: dark */ .root { -jfx-atention: red; -jfx-attention: blue; }");
        theme("unclosed", "/* base: light */ .root { -jfx-attention: red; ");
        theme("empty", "/* base: dark */");

        UserTheme unbased = UserThemes.named("unbased");
        assertEquals(ThemeManager.Theme.DARK, unbased.base(), "dark is assumed");
        assertEquals(1, unbased.problems().size());
        assertTrue(unbased.problems().get(0).contains("base: dark"), unbased.problems().toString());

        List<String> typo = UserThemes.named("typo").problems();
        assertEquals(1, typo.size(), typo.toString());
        assertTrue(typo.get(0).contains("-jfx-atention"), "the misspelt token is named: " + typo);

        assertTrue(UserThemes.named("unclosed").problems().stream().anyMatch(p -> p.contains("braces")));
        assertTrue(UserThemes.named("empty").problems().stream().anyMatch(p -> p.contains("sets nothing")));
    }

    /** A token named only in a comment is not a token that was set. */
    @Test
    void commentsAreNotCheckedAsRules() throws Exception {
        theme("commented", "/* base: dark\n   -jfx-made-up: does not count { */\n.root { -color-accent-fg: #fff; }");

        assertEquals(List.of(), UserThemes.named("commented").problems());
    }

    @Test
    void theApplicationsOwnTokensAreKnown() {
        assertTrue(UserThemes.knownTokens().containsAll(List.of("-jfx-attention", "-jfx-syntax-keyword")),
                UserThemes.knownTokens().toString());
    }

    @Test
    void openingAnEmptyFolderLeavesAnExampleThatIsItselfAValidTheme() throws Exception {
        UserThemes.useDirectory(root.resolve("fresh"));

        Path dir = UserThemes.ensureDirectory();

        UserTheme example = UserThemes.named("example");
        assertNotNull(example, "an example to copy, rather than an empty window");
        assertEquals(dir.resolve("example.css"), example.file());
        assertEquals(List.of(), example.problems(), "the example must not teach a mistake");

        Files.writeString(dir.resolve("example.css"), "/* base: light */ .root { -jfx-attention: red; }");
        UserThemes.ensureDirectory();
        assertEquals(ThemeManager.Theme.LIGHT, UserThemes.named("example").base(),
                "a folder that has themes is left alone: the user's edits are not written over");
    }

    // ------------------------------------------------------------------
    // Applying them
    // ------------------------------------------------------------------

    /** A region painted in one of the application's tokens, in a registered, shown scene. */
    private Region swatch() {
        return FxHarness.call(() -> {
            Region region = new Region();
            region.setStyle("-fx-background-color: -jfx-attention;");
            StackPane pane = new StackPane(region);
            FxHarness.show(pane, 200, 100);
            ThemeManager.register(pane.getScene());
            return region;
        });
    }

    private static Color fill(Region region) {
        return FxHarness.call(() -> {
            Scene scene = region.getScene();
            scene.getRoot().applyCss();
            return (Color) region.getBackground().getFills().get(0).getFill();
        });
    }

    @Test
    void aUserThemeChangesWhatItSetsAndIsRememberedAndComesOffAgain() throws Exception {
        theme("mine", "/* base: dark */ .root { -jfx-attention: #010203; }");
        Region swatch = swatch();
        FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.DARK));
        Color builtIn = fill(swatch);

        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("mine")));

        assertEquals(Color.web("#010203"), fill(swatch), "the token the theme set");
        assertEquals("mine", ThemeManager.currentUserTheme().name());
        assertEquals(ThemeManager.Theme.DARK, ThemeManager.current());

        // What a restart does.
        FxHarness.run(() -> {
            ThemeManager.applySaved();
            assertEquals("mine", ThemeManager.currentUserTheme().name(), "remembered across a restart");
        });

        FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.DARK));
        assertEquals(builtIn, fill(swatch), "choosing a built-in theme is that theme as it is");
        assertNull(ThemeManager.currentUserTheme());
        FxHarness.run(() -> {
            ThemeManager.applySaved();
            assertNull(ThemeManager.currentUserTheme(), "and that too is remembered");
        });
    }

    @Test
    void aThemeBuiltOnLightSwitchesTheBuiltInThemeToLight() throws Exception {
        theme("pale", "/* base: light */ .root { -jfx-attention: #0a0b0c; }");
        swatch();

        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("pale")));

        assertEquals(ThemeManager.Theme.LIGHT, ThemeManager.current());
    }

    /** {@code base: dark} and {@code base: light} are the two defaults; the others name themselves. */
    @Test
    void aThemeMayBuildOnAnyBuiltInTheme() throws Exception {
        theme("a", "/* base: dark-green */ .root { -jfx-attention: #010203; }");
        theme("b", "/* BASE: Midnight */ .root { -jfx-attention: #010203; }");
        theme("c", "/* base: light-green */ .root { -jfx-attention: #010203; }");
        theme("d", "/* base: paper */ .root { -jfx-attention: #010203; }");
        theme("e", "/* base: dark */ .root { -jfx-attention: #010203; }");

        assertEquals(ThemeManager.Theme.DARK_GREEN, UserThemes.named("a").base());
        assertEquals(ThemeManager.Theme.MIDNIGHT, UserThemes.named("b").base());
        assertEquals(ThemeManager.Theme.LIGHT_GREEN, UserThemes.named("c").base());
        assertEquals(ThemeManager.Theme.PAPER, UserThemes.named("d").base());
        assertEquals(ThemeManager.Theme.DARK, UserThemes.named("e").base(), "dark still means the default dark theme");
        for (String name : List.of("a", "b", "c", "d", "e")) {
            assertEquals(List.of(), UserThemes.named(name).problems(), name);
        }
    }

    @Test
    void aThemeThatNamesNoSuchBaseIsDarkAndSaysSo() throws Exception {
        theme("odd", "/* base: solarized */ .root { -jfx-attention: #010203; }");
        // The word has to stand alone: "database: light" in a sentence is not a base.
        theme("prose", "/* the database: light on disk, and so on */ .root { -jfx-attention: #010203; }");

        UserTheme odd = UserThemes.named("odd");
        assertEquals(ThemeManager.Theme.DARK, odd.base());
        assertEquals(1, odd.problems().size(), odd.problems().toString());
        assertTrue(odd.problems().get(0).contains("solarized"), odd.problems().toString());
        assertTrue(odd.problems().get(0).contains("dark-green"), "it lists what would do: " + odd.problems());

        assertTrue(UserThemes.named("prose").problems().get(0).contains("does not say which theme"),
                UserThemes.named("prose").problems().toString());
    }

    /** What the user theme changes is on top of the base it names, and the window follows that base. */
    @Test
    void aThemeBuiltOnADarkGreenThemeIsLaidOverThatTheme() throws Exception {
        theme("green-plus", "/* base: dark-green */ .root { -jfx-attention: #010203; }");
        Region attention = swatch();
        Region list = FxHarness.call(() -> {
            Region region = new Region();
            region.setStyle("-fx-background-color: -jfx-list-bg;");
            StackPane pane = new StackPane(region);
            FxHarness.show(pane, 200, 100);
            ThemeManager.register(pane.getScene());
            return region;
        });

        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("green-plus")));

        assertEquals(ThemeManager.Theme.DARK_GREEN, ThemeManager.current());
        // Its own change, over the base's other colours: the Dark Green ground is still there.
        assertEquals(Color.web("#010203"), fill(attention));
        assertEquals(Color.web("#111216"), fill(list));
    }

    /** Reload: the same theme applied again shows what its file says now, not what it said. */
    @Test
    void applyingAThemeAgainPicksUpAnEditToItsFile() throws Exception {
        Path file = theme("mine", "/* base: dark */ .root { -jfx-attention: #010203; }");
        Region swatch = swatch();
        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("mine")));
        assertEquals(Color.web("#010203"), fill(swatch));

        Files.writeString(file, "/* base: dark */ .root { -jfx-attention: #040506; }");
        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("mine")));

        assertEquals(Color.web("#040506"), fill(swatch));
    }

    /** A theme whose file has gone since it was chosen: the built-in theme, not an error at startup. */
    @Test
    void aRememberedThemeThatHasBeenDeletedFallsBackToTheBuiltInOne() throws Exception {
        Path file = theme("mine", "/* base: light */ .root { -jfx-attention: #010203; }");
        swatch();
        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("mine")));
        Files.delete(file);

        FxHarness.run(ThemeManager::applySaved);

        assertNull(ThemeManager.currentUserTheme());
        assertFalse(ThemeManager.current() == null);
    }
}
