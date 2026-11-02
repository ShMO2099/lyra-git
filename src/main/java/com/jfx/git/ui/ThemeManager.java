package com.jfx.git.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;

import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.beans.value.ChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The built-in themes, light and dark, and the switch between them.
 *
 * <p>Two layers make a theme. AtlantaFX's Primer stylesheet - PrimerDark or PrimerLight,
 * whichever the theme's {@link Base} says - supplies the semantic palette:
 * {@code -color-fg-default}, {@code -color-accent-fg} and the rest, which the application
 * stylesheets are written against, so most of the app changes theme without any code
 * knowing about it. On top of that sits a token file per theme. The two original themes'
 * files carry only the handful of colours Primer has no name for - syntax highlighting,
 * the colourblind diff palette, the graph accents, and where each region's background
 * comes from - while the others restate Primer's own names too, which is how a theme gets
 * a different ground rather than just different accents.
 *
 * <p>A third thing is not CSS at all: the colour scheme each scene reports to the
 * toolkit (see {@link Theme#colorScheme()}). That is what a system-decorated window's
 * title bar follows - Primer cannot reach it - so a dark theme under a light title bar
 * is fixed here, by keeping every scene's scheme in step with the theme.
 *
 * <p>Scenes register themselves so a later switch reaches windows that already exist;
 * they are held weakly-in-effect by pruning closed ones on each apply, because a
 * dialog that has been dismissed must not keep its scene graph alive.
 */
public final class ThemeManager {

    /**
     * Which of AtlantaFX's two stylesheets a theme is written over.
     *
     * <p>Everything Primer styles - every control's shape, spacing and states - is the
     * same in all themes of a base; a theme changes colours, not geometry. The base is
     * therefore what decides the user-agent stylesheet and the colour scheme the native
     * title bar is drawn in, and the theme's own token file carries the rest.
     */
    public enum Base {
        DARK("dark", ColorScheme.DARK),
        LIGHT("light", ColorScheme.LIGHT);

        private final String id;
        private final ColorScheme colorScheme;

        Base(String id, ColorScheme colorScheme) {
            this.id = id;
            this.colorScheme = colorScheme;
        }

        /** The word a user theme's {@code base:} comment and the picker's grouping use. */
        public String id() {
            return id;
        }

        String userAgentStylesheet() {
            return this == DARK
                    ? new PrimerDark().getUserAgentStylesheet()
                    : new PrimerLight().getUserAgentStylesheet();
        }

        /**
         * The colour scheme a scene wearing a theme of this base declares.
         *
         * <p>A window's native title bar follows its scene's scheme, and the scheme
         * defaults to the operating system's - so without this a dark theme on a light
         * desktop keeps a light title bar, and a light theme on a dark desktop a dark one.
         * Always one or the other, never the platform's own: the theme is the user's
         * choice for this application, whatever the desktop is set to.
         */
        ColorScheme colorScheme() {
            return colorScheme;
        }
    }

    /**
     * The built-in themes: a label, the base each is written over, and its token file.
     *
     * <p>Declared dark ones first, then light, because that is the order every list of
     * them (Preferences, the View menu, the tours) shows. The constant's {@link #name()}
     * is what is stored under {@code ui.theme}, so the first two keep the names an
     * existing user already has saved and none may be renamed - a new theme is a new
     * constant, and an old name that no longer exists falls back to {@link #DARK}.
     */
    public enum Theme {
        DARK("Dark", Base.DARK, "/css/theme-dark.css"),
        DARK_GREEN("Dark Green", Base.DARK, "/css/theme-dark-green.css"),
        MIDNIGHT("Midnight", Base.DARK, "/css/theme-midnight.css"),
        NORD_DARK("Nord Dark", Base.DARK, "/css/theme-nord-dark.css"),
        SOLARIZED_DARK("Solarized Dark", Base.DARK, "/css/theme-solarized-dark.css"),
        GRUVBOX_DARK("Gruvbox Dark", Base.DARK, "/css/theme-gruvbox-dark.css"),
        LIGHT("Light", Base.LIGHT, "/css/theme-light.css"),
        LIGHT_GREEN("Light Green", Base.LIGHT, "/css/theme-light-green.css"),
        PAPER("Paper", Base.LIGHT, "/css/theme-paper.css"),
        NORD_LIGHT("Nord Light", Base.LIGHT, "/css/theme-nord-light.css"),
        SOLARIZED_LIGHT("Solarized Light", Base.LIGHT, "/css/theme-solarized-light.css"),
        GRUVBOX_LIGHT("Gruvbox Light", Base.LIGHT, "/css/theme-gruvbox-light.css");

        private final String label;
        private final Base base;
        private final String tokens;

        Theme(String label, Base base, String tokens) {
            this.label = label;
            this.base = base;
            this.tokens = tokens;
        }

        public String label() {
            return label;
        }

        public Base base() {
            return base;
        }

        /**
         * The label, with the base added where the name does not already say it
         * ({@code Midnight (dark)}), for the lists that mix dark and light themes: a name
         * like Paper or Midnight says which it is only to someone who already knows.
         */
        public String displayName() {
            return label.toLowerCase(Locale.ROOT).contains(base.id()) ? label : label + " (" + base.id() + ")";
        }

        /**
         * The name a user theme gives after {@code base:}, and the id the picker keeps:
         * {@code dark}, {@code light}, {@code dark-green}, {@code midnight} and so on.
         * The two defaults answer to their bare base so that {@code base: dark} - which is
         * all a user theme could say before there was a choice - still means what it did.
         */
        public String id() {
            return this == DARK || this == LIGHT ? base.id() : name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        String tokens() {
            return tokens;
        }

        String userAgentStylesheet() {
            return base.userAgentStylesheet();
        }

        /** The colour scheme a scene wearing this theme declares; see {@link Base#colorScheme()}. */
        ColorScheme colorScheme() {
            return base.colorScheme();
        }

        /**
         * The theme a toggle switches this one to: the same family on the other base where
         * there is one (Dark Green and Light Green), otherwise the other base's
         * default. Someone who chose a family for its colours should find them again on the
         * far side of the toggle, not be sent to the default look.
         */
        public Theme counterpart() {
            return switch (this) {
                case DARK -> LIGHT;
                case LIGHT -> DARK;
                case DARK_GREEN -> LIGHT_GREEN;
                case LIGHT_GREEN -> DARK_GREEN;
                case NORD_DARK -> NORD_LIGHT;
                case NORD_LIGHT -> NORD_DARK;
                case SOLARIZED_DARK -> SOLARIZED_LIGHT;
                case SOLARIZED_LIGHT -> SOLARIZED_DARK;
                case GRUVBOX_DARK -> GRUVBOX_LIGHT;
                case GRUVBOX_LIGHT -> GRUVBOX_DARK;
                case MIDNIGHT -> LIGHT;
                case PAPER -> DARK;
            };
        }

        /** The built-in theme a stored or typed name refers to, or {@code null}. */
        public static Theme fromName(String name) {
            if (name == null) {
                return null;
            }
            String wanted = name.strip();
            for (Theme theme : values()) {
                if (theme.name().equalsIgnoreCase(wanted) || theme.id().equalsIgnoreCase(wanted)) {
                    return theme;
                }
            }
            return null;
        }
    }

    private static final String PREF_KEY = "ui.theme";
    /** The user theme in effect, by name; blank when a built-in theme is used as it is. */
    private static final String USER_PREF_KEY = "ui.userTheme";

    /** The user theme laid over the built-in one, or null; see {@link UserThemes}. */
    private static UserThemes.UserTheme currentUser;
    /** That theme's stylesheet as it sits in every registered list, or null. */
    private static String userSheet;

    /**
     * Application stylesheets, in cascade order.
     *
     * <p>The shared component vocabulary loads before the application sheet so a rule of
     * equal specificity in {@code lyra.css} still wins — a component can be adjusted
     * where it is used without having to out-specify the definition.
     */
    private static final String[] APP_STYLESHEETS = {
            "/css/components.css",
            "/css/lyra.css",
    };

    /**
     * Registered scenes and dialog panes.
     *
     * <p>Weak, so a dismissed dialog is collected normally. The obvious alternative -
     * dropping scenes with no window - is wrong: a scene is registered before it is
     * attached to its stage, and that heuristic silently unregisters live windows.
     */
    private static final List<WeakReference<Parent>> roots = new ArrayList<>();
    private static Theme current = Theme.DARK;

    /**
     * Gives the colour scheme to whichever scene a tracked root ends up in.
     *
     * <p>A dialog pane's stylesheets are on the pane, so they follow it wherever it goes.
     * The scheme is the scene's, and a pane does not stay in one: a dialog puts its pane
     * back into its scene when it is shown and takes it out again when it closes (the
     * scene is left holding a dummy root), and a pane is often registered before it has
     * a scene at all. A theme switch only reaches a pane that is in a scene at that
     * moment, so a dialog kept for reuse would come back in the scheme it left in - the
     * wrong title bar, on the very first frame, until the next switch. Hence the listener:
     * when a scene arrives, or changes, it gets the scheme of the theme in effect then.
     *
     * <p>One instance, installed on every tracked root's {@code scene} property. It holds
     * nothing but a static call, so the root keeps the listener alive and not the other
     * way round - the weak design of {@link #roots} is untouched.
     */
    private static final ChangeListener<Scene> SCENE_ARRIVED =
            (observable, was, scene) -> applyColorScheme(scene);

    /**
     * The current theme, observable.
     *
     * <p>Styled nodes need nothing of the sort — they follow the stylesheet swap on their
     * own. A {@code Canvas} does not: it keeps whatever was last painted on it, and the
     * commit graph is painted rather than styled. Without something to listen to, the
     * lanes would simply stay in the old theme's colours until an unrelated refresh
     * happened to repaint them.
     *
     * <p>Listeners should be attached weakly ({@code WeakChangeListener}), because this
     * property is static and outlives every window that reads it.
     */
    private static final javafx.beans.property.ReadOnlyObjectWrapper<Theme> currentTheme =
            new javafx.beans.property.ReadOnlyObjectWrapper<>(Theme.DARK);

    public static javafx.beans.property.ReadOnlyObjectProperty<Theme> currentProperty() {
        return currentTheme.getReadOnlyProperty();
    }

    private ThemeManager() {
    }

    /** The theme chosen last time this user ran the application. */
    public static Theme saved() {
        Theme found = Theme.fromName(com.jfx.git.core.Settings.get(PREF_KEY, Theme.DARK.name()));
        // An unknown value in preferences - a theme a later version dropped, a hand-edited
        // file - is not worth failing over.
        return found != null ? found : Theme.DARK;
    }

    public static Theme current() {
        return current;
    }

    /** The user theme in effect, or {@code null} when a built-in theme is used as it is. */
    public static UserThemes.UserTheme currentUserTheme() {
        return currentUser;
    }

    /**
     * Applies what was in effect when the application last ran: the built-in theme, and
     * the user theme over it if there was one and its file is still there.
     */
    public static void applySaved() {
        // Read first: applying a built-in theme is "use it as it is", and forgets the other.
        UserThemes.UserTheme user = UserThemes.named(com.jfx.git.core.Settings.get(USER_PREF_KEY, ""));
        if (user != null) {
            applyUser(user);
        } else {
            apply(saved());
        }
    }

    /**
     * The saved theme, applied without writing anything back - for the notice a second copy
     * of the application shows before it exits. The settings file belongs to the copy that
     * is running, and {@link #applySaved} stores the theme's name when the file has none.
     * A user theme's own stylesheet is left out: its base gives the notice its colours.
     */
    public static void applySavedWithoutSaving() {
        UserThemes.UserTheme user = UserThemes.named(com.jfx.git.core.Settings.get(USER_PREF_KEY, ""));
        Theme theme = user != null ? user.base() : saved();
        current = theme;
        Application.setUserAgentStylesheet(theme.userAgentStylesheet());
        currentTheme.set(theme);
    }

    /**
     * Lays a user theme over the built-in theme it says it builds on, everywhere, and
     * remembers it. {@code null} goes back to the built-in theme as it is.
     *
     * <p>Applying the same theme again re-reads its file - which is what Reload in
     * Preferences does after an edit.
     */
    public static void applyUser(UserThemes.UserTheme theme) {
        if (theme == null) {
            apply(current);
            return;
        }
        String previous = userSheet;
        String sheet;
        try {
            // The stylesheet itself, as a data: URL, rather than the file's address.
            // JavaFX keeps a parsed stylesheet by its URL, so an edited file under the
            // same one would go on looking the way it did before the edit; and a
            // file: URL with something appended to make it differ is not found at all.
            // The content is the address here, so a change to one is a change to both.
            sheet = "data:text/css;base64," + java.util.Base64.getEncoder()
                    .encodeToString(java.nio.file.Files.readAllBytes(theme.file()));
        } catch (java.io.IOException | RuntimeException unreadable) {
            // Gone or locked since it was listed: the built-in theme, not half a switch.
            apply(theme.base());
            return;
        }
        currentUser = theme;
        userSheet = sheet;
        applyTheme(theme.base(), previous);
        remember(USER_PREF_KEY, theme.name());
    }

    /**
     * Stores {@code value} unless it is already what is stored. {@link #applySaved} goes
     * through the same path as a user's choice, and {@code Settings.put} schedules a
     * rewrite of the whole settings file for every call, changed or not - so every launch
     * rewrote the file to say what it already said. A missing key counts as blank, which
     * is how both keys read back.
     */
    private static void remember(String key, String value) {
        if (!value.equals(com.jfx.git.core.Settings.get(key, ""))) {
            com.jfx.git.core.Settings.put(key, value);
        }
    }

    /**
     * Registers a scene so it follows theme changes, and applies the current theme
     * to it now. Safe to call twice for the same scene.
     */
    public static void register(Scene scene) {
        if (scene == null) {
            return;
        }
        // The scene's own stylesheet list is the one that matters, but it is the root
        // that can be held weakly, and it keeps the scene alive for as long as it is
        // in use.
        swap(scene.getStylesheets(), current);
        applyColorScheme(scene);
        track(scene.getRoot());
    }

    public static void unregister(Scene scene) {
        if (scene != null) {
            roots.removeIf(ref -> ref.get() == null || ref.get() == scene.getRoot());
            // Stops following the theme altogether: a scene that is not reached by a
            // switch must not be handed the scheme of whichever theme is in effect when it
            // next changes root either. Harmless if it was never installed.
            scene.getRoot().sceneProperty().removeListener(SCENE_ARRIVED);
        }
    }

    private static void track(Parent root) {
        if (root == null) {
            return;
        }
        roots.removeIf(ref -> ref.get() == null);
        for (WeakReference<Parent> ref : roots) {
            if (ref.get() == root) {
                return;
            }
        }
        roots.add(new WeakReference<>(root));
        // Once per root, here rather than in the callers: both register and style come
        // through, and a root already in the list returned above with its listener in place.
        root.sceneProperty().addListener(SCENE_ARRIVED);
    }

    /**
     * Gives {@code scene} the colour scheme of the theme in effect. {@code null} - a root
     * with no scene yet, or one that has just left its scene - is nothing to do.
     *
     * <p>Always an explicit scheme, never {@code null}: the scene's scheme is
     * null-coalescing and {@code null} would hand the choice back to the platform. On the
     * JavaFX thread, like the stylesheet lists beside every call: a scene that is showing
     * passes the change on to its window, whose title bar is the point of it.
     */
    private static void applyColorScheme(Scene scene) {
        if (scene != null) {
            scene.getPreferences().setColorScheme(current.colorScheme());
        }
    }

    /**
     * Themes a dialog.
     *
     * <p>A dialog owns a separate scene, so it inherits nothing from the main window
     * beyond the user-agent stylesheet. Without this its {@code -jfx-} tokens go
     * unresolved and JavaFX falls back to black, which on a dark ground means
     * invisible text. The dialog pane is the root of its own scene, so the {@code
     * .root} selector in the token files matches it.
     */
    public static void style(javafx.scene.control.DialogPane pane) {
        if (pane == null) {
            return;
        }
        swap(pane.getStylesheets(), current);
        // The pane may already be in the dialog's scene, or arrive in it later; track
        // covers the second, this the first.
        applyColorScheme(pane.getScene());
        // Tracked as well, so a dialog left open across a theme switch follows it.
        track(pane);
    }

    /**
     * Convenience for {@code style(dialog.getDialogPane())} - and gives the dialog the main
     * window as its owner (see {@link DialogOwner}), since every dialog passes through here
     * on its way to the screen.
     */
    public static void style(javafx.scene.control.Dialog<?> dialog) {
        if (dialog != null) {
            style(dialog.getDialogPane());
            DialogOwner.adopt(dialog);
        }
    }

    /**
     * Switches to a built-in theme as it is, updates every registered scene, and
     * remembers the choice. Any user theme is dropped: one written over the dark theme
     * laid over the light one is not a theme anybody chose.
     */
    public static void apply(Theme theme) {
        String previous = userSheet;
        currentUser = null;
        userSheet = null;
        remember(USER_PREF_KEY, "");
        applyTheme(theme, previous);
    }

    /** @param previousUserSheet the user stylesheet to take out of every list, or null */
    private static void applyTheme(Theme theme, String previousUserSheet) {
        current = theme;
        Application.setUserAgentStylesheet(theme.userAgentStylesheet());

        roots.removeIf(ref -> ref.get() == null);
        for (WeakReference<Parent> ref : roots) {
            Parent root = ref.get();
            if (root == null) {
                continue;
            }
            // A scene root carries its stylesheets on the scene; a dialog pane carries
            // its own. Update whichever list this root actually owns.
            Scene scene = root.getScene();
            List<String> sheets = scene != null && scene.getRoot() == root
                    ? scene.getStylesheets()
                    : root.getStylesheets();
            if (previousUserSheet != null) {
                sheets.remove(previousUserSheet);
            }
            swap(sheets, theme);
            // The scheme is the scene's whichever list the sheets were in. A user theme
            // gets here as its base (applyUser passes theme.base()), so it keeps the
            // scheme its base would have; the pane that is in no scene waits for
            // SCENE_ARRIVED.
            applyColorScheme(scene);
        }

        remember(PREF_KEY, theme.name());

        // Last, so anything repainting in response sees the new stylesheets already in
        // place. Avatars are drawn on each paint, so they pick up the new colours then.
        currentTheme.set(theme);
    }

    /** The other theme - what a toggle switches to; see {@link Theme#counterpart()}. */
    public static Theme opposite() {
        return current.counterpart();
    }

    public static void toggle() {
        apply(opposite());
    }

    /**
     * Leaves exactly one token stylesheet in {@code sheets}, plus the application
     * stylesheet.
     *
     * <p>Both token files are removed first: leaving the old one behind would let
     * whichever loaded last win, which is how a half-switched, unreadable window
     * happens.
     */
    private static void swap(List<String> sheets, Theme theme) {
        for (Theme other : Theme.values()) {
            String url = url(other.tokens());
            if (url != null) {
                sheets.remove(url);
            }
        }
        String tokens = url(theme.tokens());
        if (tokens != null) {
            // Tokens go first so the stylesheets that consume them load after.
            sheets.add(0, tokens);
        }
        for (String sheet : APP_STYLESHEETS) {
            String app = url(sheet);
            if (app != null && !sheets.contains(app)) {
                sheets.add(app);
            }
        }
        // The user's theme goes last of all, so that what it sets wins over both the
        // base theme's tokens and the application's own rules.
        if (userSheet != null) {
            sheets.remove(userSheet);
            sheets.add(userSheet);
        }
    }

    static String url(String resource) {
        var found = ThemeManager.class.getResource(resource);
        return found == null ? null : found.toExternalForm();
    }
}
