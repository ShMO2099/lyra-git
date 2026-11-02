package com.jfx.git.ui;

import com.jfx.git.core.Settings;
import com.jfx.git.ui.ThemeManager.Theme;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * A window's native title bar follows its scene's colour scheme, so {@link ThemeManager}
 * keeps every scene it themes in the scheme of the theme in effect - dark for the dark
 * theme, light for the light one - however and whenever the scene arrives.
 *
 * <p>Each test moves the theme both ways rather than checking one end. The scene's scheme
 * defaults to the platform's, so an assertion that a scene is {@code DARK} says nothing on
 * a desktop that is dark already; it is the switch to the other theme, and back, that
 * cannot pass by accident. Everything that touches a scene's preferences, the reads
 * included, runs on the FX thread.
 */
class ThemeColorSchemeTest {

    @TempDir
    Path root;

    private Path themes;
    private Theme original;

    @BeforeEach
    void setUp() throws Exception {
        FxHarness.start();
        Settings.useFile(root.resolve("settings.properties"));
        themes = Files.createDirectory(root.resolve("themes"));
        UserThemes.useDirectory(themes);
        original = FxHarness.call(ThemeManager::current);
    }

    @AfterEach
    void tearDown() {
        // The theme is static, FX-thread state: put back from the FX thread, because a
        // reset from this one has failed a later test through a delayed TestFX exception.
        // Taking the theme off also takes any user theme off.
        FxHarness.run(() -> ThemeManager.apply(original));
        UserThemes.useDirectory(null);
        Settings.useFile(null);
        FxHarness.closeShownStages();
    }

    private static void apply(Theme theme) {
        FxHarness.run(() -> ThemeManager.apply(theme));
    }

    private static ColorScheme schemeOf(Scene scene) {
        return FxHarness.call(() -> scene.getPreferences().getColorScheme());
    }

    /** A scene, registered with the theme manager as the application's own windows are. */
    private static Scene registered() {
        return FxHarness.call(() -> {
            Scene scene = new Scene(new StackPane(), 200, 100);
            ThemeManager.register(scene);
            return scene;
        });
    }

    private void userTheme(String name, String css) throws Exception {
        Files.writeString(themes.resolve(name + ".css"), css);
    }

    // ------------------------------------------------------------------
    // The mapping, registration and the switch
    // ------------------------------------------------------------------

    @Test
    void eachThemeDeclaresTheSchemeItIsDrawnIn() {
        assertEquals(ColorScheme.DARK, Theme.DARK.colorScheme());
        assertEquals(ColorScheme.LIGHT, Theme.LIGHT.colorScheme());
    }

    /** The title bar follows the base, whatever the theme is called: a dark theme is a dark bar. */
    @Test
    void everyThemesSchemeIsTheOneItsBaseDeclares() {
        assertEquals(ColorScheme.DARK, ThemeManager.Base.DARK.colorScheme());
        assertEquals(ColorScheme.LIGHT, ThemeManager.Base.LIGHT.colorScheme());
        for (Theme theme : Theme.values()) {
            ColorScheme expected = theme.base() == ThemeManager.Base.DARK ? ColorScheme.DARK : ColorScheme.LIGHT;
            assertEquals(expected, theme.colorScheme(), theme + " is a " + theme.base().id() + " theme");
        }
        assertEquals(ColorScheme.DARK, Theme.DARK_GREEN.colorScheme());
        assertEquals(ColorScheme.DARK, Theme.MIDNIGHT.colorScheme());
        assertEquals(ColorScheme.LIGHT, Theme.LIGHT_GREEN.colorScheme());
        assertEquals(ColorScheme.LIGHT, Theme.PAPER.colorScheme());
    }

    /**
     * Every theme, in turn, on a scene that is already registered: the scheme each leaves is
     * its own, whichever theme was in effect before it and whichever the desktop is set to.
     * The pairs matter - dark to dark and light to light change the stylesheet and not the
     * scheme, which is the transition a lazy "only when the base changes" would get wrong
     * the other way about.
     */
    @Test
    void movingBetweenAnyTwoThemesLeavesTheSceneInTheSchemeOfTheOneArrivedAt() {
        Scene scene = registered();
        for (Theme from : Theme.values()) {
            for (Theme to : Theme.values()) {
                apply(from);
                apply(to);
                assertEquals(to.colorScheme(), schemeOf(scene), from + " to " + to);
            }
        }
    }

    @Test
    void aSceneRegisteredUnderAThemeTakesThatThemesScheme() {
        for (Theme theme : Theme.values()) {
            apply(theme);

            Scene scene = registered();

            assertEquals(theme.colorScheme(), schemeOf(scene), "registered under " + theme);
        }
    }

    @Test
    void switchingThemeMovesEveryRegisteredScene() {
        apply(Theme.DARK);
        Scene first = registered();
        Scene second = registered();
        assertEquals(ColorScheme.DARK, schemeOf(first));
        assertEquals(ColorScheme.DARK, schemeOf(second));

        apply(Theme.LIGHT);
        assertEquals(ColorScheme.LIGHT, schemeOf(first), "to light");
        assertEquals(ColorScheme.LIGHT, schemeOf(second), "to light");

        FxHarness.run(ThemeManager::toggle);
        assertEquals(ColorScheme.DARK, schemeOf(first), "and back, by the toggle");
        assertEquals(ColorScheme.DARK, schemeOf(second), "and back, by the toggle");
    }

    /** The scheme is an explicit choice, not the platform's: a light app on a dark desktop stays light. */
    @Test
    void theSchemeIsSetOutrightNotLeftToThePlatform() {
        ColorScheme platform = FxHarness.call(() -> Platform.getPreferences().getColorScheme());
        Theme againstThePlatform = platform == ColorScheme.DARK ? Theme.LIGHT : Theme.DARK;
        apply(againstThePlatform);

        Scene scene = registered();

        assertNotEquals(platform, schemeOf(scene));
        assertEquals(againstThePlatform.colorScheme(), schemeOf(scene));
    }

    @Test
    void aShownWindowTakesTheSwitchToo() {
        apply(Theme.DARK);
        Stage stage = FxHarness.call(() -> FxHarness.showRegistered(new StackPane(), 200, 100));
        Scene scene = FxHarness.call(stage::getScene);
        assertEquals(ColorScheme.DARK, schemeOf(scene));

        apply(Theme.LIGHT);
        assertEquals(ColorScheme.LIGHT, schemeOf(scene));

        apply(Theme.DARK);
        assertEquals(ColorScheme.DARK, schemeOf(scene));
    }

    // ------------------------------------------------------------------
    // Roots that have no scene yet, or lose it, or change it
    // ------------------------------------------------------------------

    /**
     * A dialog pane is styled before its scene exists, and a theme switch cannot reach it
     * then. Both arrival themes are tried: on one of them the platform's own scheme is
     * the right answer, and the listener that gives the scene its scheme would go unnoticed.
     */
    @Test
    void aRootStyledBeforeItHasASceneTakesTheSchemeOfTheThemeInEffectWhenTheSceneArrives() {
        for (Theme arrival : Theme.values()) {
            Theme styledUnder = arrival.counterpart();
            apply(styledUnder);
            DialogPane pane = FxHarness.call(() -> {
                DialogPane styled = new DialogPane();
                ThemeManager.style(styled);
                return styled;
            });

            // Switched with the pane in no scene at all, so there was nothing to hand it to.
            apply(arrival);
            Scene arrived = FxHarness.call(() -> new Scene(pane));
            assertEquals(arrival.colorScheme(), schemeOf(arrived),
                    "the theme now (" + arrival + "), not the one it was styled under");

            // And once it has one, it is reached like any other.
            apply(styledUnder);
            assertEquals(styledUnder.colorScheme(), schemeOf(arrived), "switched on again");
        }
    }

    @Test
    void aRootMovedIntoAnotherSceneBringsTheSchemeAlong() {
        for (Theme arrival : Theme.values()) {
            apply(arrival.counterpart());
            StackPane content = FxHarness.call(StackPane::new);
            Scene first = FxHarness.call(() -> {
                Scene scene = new Scene(content, 200, 100);
                ThemeManager.register(scene);
                return scene;
            });

            // The root leaves for a scene nobody registered, after the theme has changed.
            apply(arrival);
            Scene second = FxHarness.call(() -> {
                first.setRoot(new Group());
                return new Scene(content, 200, 100);
            });

            assertEquals(arrival.colorScheme(), schemeOf(second), "moved under " + arrival);
        }
    }

    /**
     * The real thing: a dialog puts its pane into its scene when it is shown and takes it
     * out again when it closes. One kept for reuse is closed across a theme switch, which
     * has no scene to reach, and must open in the scheme of the theme it finds.
     */
    @Test
    void aDialogReopenedAfterAThemeSwitchOpensInTheNewScheme() {
        apply(Theme.DARK);
        Dialog<Void> dialog = FxHarness.call(() -> {
            Dialog<Void> kept = new Dialog<>();
            kept.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            ThemeManager.style(kept);
            return kept;
        });
        try {
            FxHarness.run(dialog::show);
            assertEquals(ColorScheme.DARK, paneScheme(dialog), "first shown");

            apply(Theme.LIGHT);
            assertEquals(ColorScheme.LIGHT, paneScheme(dialog), "switched while open");

            FxHarness.run(dialog::close);
            apply(Theme.DARK);
            FxHarness.run(dialog::show);
            assertEquals(ColorScheme.DARK, paneScheme(dialog), "reopened after a switch made while closed");

            FxHarness.run(dialog::close);
            apply(Theme.LIGHT);
            FxHarness.run(dialog::show);
            assertEquals(ColorScheme.LIGHT, paneScheme(dialog), "and the other way");
        } finally {
            FxHarness.run(dialog::close);
        }
    }

    private static ColorScheme paneScheme(Dialog<?> dialog) {
        return FxHarness.call(() -> dialog.getDialogPane().getScene().getPreferences().getColorScheme());
    }

    // ------------------------------------------------------------------
    // Letting go
    // ------------------------------------------------------------------

    @Test
    void anUnregisteredSceneStopsFollowingTheTheme() {
        ColorScheme platform = FxHarness.call(() -> Platform.getPreferences().getColorScheme());
        Theme withThePlatform = platform == ColorScheme.DARK ? Theme.DARK : Theme.LIGHT;
        Theme againstThePlatform = withThePlatform == Theme.DARK ? Theme.LIGHT : Theme.DARK;
        apply(withThePlatform);
        StackPane content = FxHarness.call(StackPane::new);
        Scene scene = FxHarness.call(() -> {
            Scene registered = new Scene(content, 200, 100);
            ThemeManager.register(registered);
            ThemeManager.unregister(registered);
            return registered;
        });

        // Both of the ways it was followed: the switch, and the root's next scene. Either
        // would leave the scheme of the theme in effect, which is now not the platform's.
        apply(againstThePlatform);
        assertEquals(platform, schemeOf(scene), "not reached by the switch");

        Scene later = FxHarness.call(() -> {
            scene.setRoot(new Group());
            return new Scene(content, 200, 100);
        });
        assertEquals(platform, schemeOf(later), "nor handed to the scene the root moves to");
    }

    /**
     * The registration model holds roots weakly so a dismissed dialog is collected; giving
     * its scene a scheme, and a listener on its root, must not change that.
     */
    @Test
    void aDismissedWindowIsStillCollected() throws Exception {
        List<WeakReference<?>> dismissed = FxHarness.call(() -> {
            StackPane windowRoot = new StackPane();
            Scene windowScene = new Scene(windowRoot, 200, 100);
            ThemeManager.register(windowScene);

            DialogPane pane = new DialogPane();
            Scene paneScene = new Scene(pane);
            ThemeManager.style(pane);

            // A switch while both are alive, so each has been given a scheme more than once.
            ThemeManager.apply(ThemeManager.opposite());
            ThemeManager.apply(ThemeManager.opposite());
            return List.of(new WeakReference<>(windowRoot), new WeakReference<>(windowScene),
                    new WeakReference<>(pane), new WeakReference<>(paneScene));
        });

        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline && dismissed.stream().anyMatch(ref -> ref.get() != null)) {
            System.gc();
            Thread.sleep(50);
        }

        for (WeakReference<?> ref : dismissed) {
            assertNull(ref.get(), "still reachable: " + ref.get());
        }
        // The registry prunes what was collected on the next switch rather than failing on it.
        apply(Theme.DARK);
        assertTrue(FxHarness.call(() -> ThemeManager.current() == Theme.DARK));
    }

    // ------------------------------------------------------------------
    // User themes
    // ------------------------------------------------------------------

    /** A user theme is laid over a built-in one, and the window follows the one underneath. */
    @Test
    void aUserThemeKeepsTheSchemeOfTheBuiltInThemeItBuildsOn() throws Exception {
        userTheme("pale", "/* base: light */ .root { -jfx-attention: #0a0b0c; }");
        userTheme("deep", "/* base: dark */ .root { -jfx-attention: #010203; }");
        apply(Theme.DARK);
        Scene scene = registered();
        assertEquals(ColorScheme.DARK, schemeOf(scene));

        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("pale")));
        assertEquals(ColorScheme.LIGHT, schemeOf(scene), "light-based, over a dark window");

        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("deep")));
        assertEquals(ColorScheme.DARK, schemeOf(scene), "dark-based, over a light one");

        // Reload applies the same theme again; it must not drift.
        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("deep")));
        assertEquals(ColorScheme.DARK, schemeOf(scene), "reloaded");

        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("pale")));
        apply(Theme.DARK);
        assertEquals(ColorScheme.DARK, schemeOf(scene), "a built-in theme chosen as it is");
    }

    @Test
    void aSceneOpenedWhileAUserThemeIsInEffectGetsItsBasesScheme() throws Exception {
        userTheme("pale", "/* base: light */ .root { -jfx-attention: #0a0b0c; }");
        userTheme("deep", "/* base: dark */ .root { -jfx-attention: #010203; }");

        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("pale")));
        assertEquals(ColorScheme.LIGHT, schemeOf(registered()), "over the light base");

        FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named("deep")));
        assertEquals(ColorScheme.DARK, schemeOf(registered()), "over the dark base");

        // The pane that has no scene when the user theme goes on gets its base's too, from
        // either direction.
        for (String name : List.of("pale", "deep")) {
            String other = name.equals("pale") ? "deep" : "pale";
            FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named(other)));
            DialogPane pane = FxHarness.call(() -> {
                DialogPane styled = new DialogPane();
                ThemeManager.style(styled);
                return styled;
            });
            FxHarness.run(() -> ThemeManager.applyUser(UserThemes.named(name)));

            Scene arrived = FxHarness.call(() -> new Scene(pane));

            assertEquals(UserThemes.named(name).base().colorScheme(), schemeOf(arrived), name);
        }
    }
}
