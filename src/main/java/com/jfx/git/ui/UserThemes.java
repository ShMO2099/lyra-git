package com.jfx.git.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Themes the user wrote: stylesheets in a folder beside the settings file.
 *
 * <p>A user theme is not another built-in theme. It is a stylesheet laid over one that
 * exists, saying which in a comment - {@code base: dark} or {@code base: light} for the two
 * defaults, or any other built-in's id, such as {@code base: dark-green} - and changing
 * only what it wants to: usually a handful of the {@code -jfx-} and {@code -color-} colour
 * tokens on {@code .root}, which is where every colour in the application comes from.
 * Whatever it does not mention keeps the base theme's value, so a theme of three lines is
 * a complete theme.
 *
 * <p>A theme with something wrong in it is still listed and still applied as far as it
 * goes. What is wrong is reported beside it in Preferences - a stylesheet being edited
 * is wrong most of the time, and refusing to load one would make it impossible to see
 * the half that works.
 */
public final class UserThemes {

    /**
     * One stylesheet from the themes folder.
     *
     * @param name     the file's name without {@code .css}, which is what the picker shows
     * @param base     the built-in theme it is laid over, which also decides the title bar's scheme
     * @param problems what is wrong with it, in sentences; empty when nothing is
     */
    public record UserTheme(String name, Path file, ThemeManager.Theme base, List<String> problems) {
    }

    private static final Pattern BASE = Pattern.compile("\\bbase\\s*:\\s*([a-z][a-z0-9-]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("(-jfx-[A-Za-z0-9-]+)\\s*:");
    private static final Pattern DECLARATION = Pattern.compile("-[A-Za-z][A-Za-z0-9-]*\\s*:");

    /** Set by tests; {@code null} means the real folder beside the settings file. */
    private static Path directoryOverride;

    private UserThemes() {
    }

    /** Where user themes are kept: a {@code themes} folder beside the settings file. */
    public static Path directory() {
        if (directoryOverride != null) {
            return directoryOverride;
        }
        Path settings = com.jfx.git.core.Settings.file();
        Path parent = settings.toAbsolutePath().getParent();
        return parent == null ? Path.of("themes") : parent.resolve("themes");
    }

    /** Points the folder somewhere else; {@code null} restores the default. For tests. */
    static void useDirectory(Path directory) {
        directoryOverride = directory;
    }

    /** Every {@code .css} file in the themes folder, by name; empty when there is no folder. */
    public static List<UserTheme> list() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<UserTheme> themes = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".css"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(file -> themes.add(read(file)));
        } catch (IOException e) {
            return List.of();
        }
        return themes;
    }

    /** The theme called {@code name} in the folder, or {@code null}. */
    public static UserTheme named(String name) {
        if (name == null || name.isBlank()) return null;
        for (UserTheme theme : list()) {
            if (theme.name().equals(name)) return theme;
        }
        return null;
    }

    /** Reads one stylesheet: which theme it builds on, and anything wrong with it. */
    static UserTheme read(Path file) {
        String fileName = file.getFileName().toString();
        String name = fileName.toLowerCase(Locale.ROOT).endsWith(".css")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        List<String> problems = new ArrayList<>();
        String css;
        try {
            css = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            problems.add("It could not be read: " + e.getMessage());
            return new UserTheme(name, file, ThemeManager.Theme.DARK, List.copyOf(problems));
        }

        ThemeManager.Theme base = ThemeManager.Theme.DARK;
        Matcher declared = BASE.matcher(css);
        if (!declared.find()) {
            problems.add("It does not say which theme it builds on, so dark is assumed. Add a comment:"
                    + " /* base: dark */ or /* base: light */.");
        } else if (ThemeManager.Theme.fromName(declared.group(1)) != null) {
            base = ThemeManager.Theme.fromName(declared.group(1));
        } else {
            problems.add("It builds on \"" + declared.group(1) + "\", which is not a theme, so dark is"
                    + " assumed. Use one of: " + builtInIds() + ".");
        }

        String withoutComments = css.replaceAll("(?s)/\\*.*?\\*/", "");
        if (!DECLARATION.matcher(withoutComments).find()) {
            problems.add("It sets nothing, so it looks exactly like the " + base.label().toLowerCase(Locale.ROOT)
                    + " theme.");
        }
        long open = withoutComments.chars().filter(c -> c == '{').count();
        long close = withoutComments.chars().filter(c -> c == '}').count();
        if (open != close) {
            problems.add("Its braces do not match (" + open + " opened, " + close + " closed), so everything"
                    + " after the mistake is ignored.");
        }

        Set<String> known = knownTokens();
        Set<String> unknown = new LinkedHashSet<>();
        Matcher tokens = TOKEN.matcher(withoutComments);
        while (tokens.find()) {
            if (!known.isEmpty() && !known.contains(tokens.group(1))) {
                unknown.add(tokens.group(1));
            }
        }
        for (String token : unknown) {
            problems.add("It sets " + token + ", which is not one of the application's colours, so that"
                    + " line changes nothing.");
        }
        return new UserTheme(name, file, base, List.copyOf(problems));
    }

    /** The ids a {@code base:} comment may name. */
    private static String builtInIds() {
        return java.util.Arrays.stream(ThemeManager.Theme.values())
                .map(ThemeManager.Theme::id).collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * The application's own colour tokens: every {@code -jfx-} name the dark theme's
     * token file defines. Every other theme file defines the same set; a test holds them
     * in step.
     */
    static Set<String> knownTokens() {
        Set<String> tokens = new LinkedHashSet<>();
        try (InputStream in = UserThemes.class.getResourceAsStream("/css/theme-dark.css")) {
            if (in == null) return tokens;
            Matcher matcher = TOKEN.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            while (matcher.find()) {
                tokens.add(matcher.group(1));
            }
        } catch (IOException e) {
            // An empty set switches the unknown-token check off rather than flagging everything.
        }
        return tokens;
    }

    /**
     * Makes sure the themes folder exists, and puts a worked example in it when it has no
     * themes in it at all - so "Open themes folder" opens on something to copy rather
     * than on an empty window and a guess at the format.
     *
     * @return the folder
     */
    public static Path ensureDirectory() throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        if (list().isEmpty()) {
            Files.writeString(dir.resolve("example.css"), EXAMPLE, StandardCharsets.UTF_8);
        }
        return dir;
    }

    static final String EXAMPLE = """
            /* base: dark

               A Lyra theme. It is laid over the built-in theme named above - dark,
               light, or another such as dark-green or paper - and changes only what
               it sets here. Everything else keeps the base theme's value, so a theme can
               be as short as one line.

               Save it under any name ending in .css in this folder, then choose it in
               Settings > Appearance. Use Reload there after editing it. */

            .root {
                /* The accent: selection, focus rings, the primary button, links. */
                -color-accent-fg: #2dd4bf;
                -color-accent-emphasis: #0d9488;

                /* Backgrounds, darkest to lightest. */
                -color-bg-default: #0b1215;
                -color-bg-subtle: #111c20;

                /* Work in progress and history rewrites: "look at this", short of an error. */
                -jfx-attention: #fbbf24;

                /* Syntax highlighting in the diff and the editor. */
                -jfx-syntax-keyword: #f472b6;
                -jfx-syntax-string: #a3e635;
            }
            """;
}
