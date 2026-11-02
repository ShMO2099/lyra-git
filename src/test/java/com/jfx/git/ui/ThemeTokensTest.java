package com.jfx.git.ui;

import atlantafx.base.theme.PrimerDark;

import com.jfx.git.ui.ThemeManager.Theme;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every built-in theme's token file against what the application reads.
 *
 * <p>The failure a theme has is a colour that exists in one file and not another, which
 * leaves a control drawing black text on a dark ground - and with six themes rather than
 * two there are more files to forget it in. These tests read the stylesheets, as text, and
 * check that cannot happen; none of them needs the toolkit, so they run in any build.
 *
 * <p>Three sources of colour meet here, and the tests are about the seams between them:
 * the theme's own token file, Primer's user-agent stylesheet for the theme's base, and the
 * application stylesheets that read both.
 */
class ThemeTokensTest {

    /** {@code -name:} - a declaration, in any of the stylesheets. */
    private static final Pattern DECLARED = Pattern.compile("(-(?:jfx|color)-[a-z0-9-]+)\\s*:");
    /** Every mention of a token, declared or read. */
    private static final Pattern MENTIONED = Pattern.compile("-(?:jfx|color)-[a-z0-9-]+");
    private static final Pattern DECLARATION = Pattern.compile("(-(?:jfx|color)-[a-z0-9-]+)\\s*:\\s*([^;]+);");

    /**
     * The two that put the application's own tokens on Primer's grounds and so restate none
     * of Primer's colours; every other theme is a ground of its own and restates them all.
     */
    private static final Set<Theme> PRIMER_AS_IS = EnumSet.of(Theme.DARK, Theme.LIGHT);

    private static final String[] APP_STYLESHEETS = {"/css/lyra.css", "/css/components.css", "/diff-styles.css"};

    private static String read(String resource) throws Exception {
        try (InputStream in = ThemeTokensTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing stylesheet " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String themeFile(Theme theme) throws Exception {
        return read(theme.tokens());
    }

    /** Primer's stylesheet for the theme's base, which is what sits under the token file. */
    private static String primer(Theme theme) throws Exception {
        // A resource path inside AtlantaFX's jar ("/atlantafx/base/theme/primer-dark.css"),
        // or a URL to the same on a toolkit that resolves it; either way, the text of the CSS.
        String where = theme.userAgentStylesheet();
        try (InputStream in = where.startsWith("/")
                ? PrimerDark.class.getResourceAsStream(where)
                : URI.create(where).toURL().openStream()) {
            assertNotNull(in, "Primer's stylesheet " + where);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** What Primer's {@code .root} block defines: the semantic palette and the colour ramps. */
    private static Set<String> primerRoot(Theme theme) throws Exception {
        String css = primer(theme);
        int start = css.indexOf(".root {");
        assertTrue(start >= 0, "Primer's stylesheet has no .root block");
        return names(DECLARED, css.substring(start, css.indexOf('}', start)));
    }

    /**
     * What Primer's own controls read from the {@code .root} block: the ramp steps its rules
     * use directly. Not the same under both bases - the dark theme's hover fill is the ramp's
     * sixth step, the light theme's its first - which is why a light theme that restated only
     * what the dark one needed would have been caught out by a hovered button.
     */
    private static Set<String> primerReads(Theme theme) throws Exception {
        String css = primer(theme);
        int start = css.indexOf(".root {");
        Set<String> reads = names(MENTIONED, css.substring(0, start) + css.substring(css.indexOf('}', start)));
        // The chart palette is for Primer's chart styles, which this application has none of.
        reads.removeIf(token -> token.startsWith("-color-chart-"));
        return reads;
    }

    private static Set<String> names(Pattern pattern, String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.groupCount() > 0 ? matcher.group(1) : matcher.group());
        }
        return found;
    }

    private static Set<String> jfx(Set<String> tokens) {
        Set<String> only = new TreeSet<>();
        tokens.stream().filter(t -> t.startsWith("-jfx-")).forEach(only::add);
        return only;
    }

    private static String applicationCss() throws Exception {
        StringBuilder all = new StringBuilder();
        for (String sheet : APP_STYLESHEETS) {
            all.append(read(sheet)).append('\n');
        }
        return all.toString();
    }

    /** The comments are prose and name tokens that do not exist; only the rules count. */
    private static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    void everyBuiltInThemeHasATokenFileThatLoads() throws Exception {
        for (Theme theme : Theme.values()) {
            assertFalse(theme.label().isBlank(), theme.name());
            assertNotNull(ThemeManager.url(theme.tokens()), "missing " + theme.tokens());
            assertTrue(themeFile(theme).contains(".root"), theme + "'s file does not style .root");
        }
    }

    @Test
    void everyThemeDefinesTheSameApplicationTokensAsTheDarkOne() throws Exception {
        Set<String> expected = jfx(names(DECLARED, withoutComments(themeFile(Theme.DARK))));
        assertFalse(expected.isEmpty());

        for (Theme theme : Theme.values()) {
            Set<String> defined = jfx(names(DECLARED, withoutComments(themeFile(theme))));
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(defined);
            Set<String> extra = new TreeSet<>(defined);
            extra.removeAll(expected);
            assertTrue(missing.isEmpty(), theme + " does not define " + missing);
            assertTrue(extra.isEmpty(), theme + " defines tokens no other theme does: " + extra);
        }
    }

    @Test
    void everyApplicationTokenTheStylesheetsReadIsDefinedInEveryTheme() throws Exception {
        Set<String> read = jfx(names(MENTIONED, withoutComments(applicationCss())));
        assertFalse(read.isEmpty(), "the stylesheets should be using the application's tokens");

        for (Theme theme : Theme.values()) {
            Set<String> undefined = new TreeSet<>(read);
            undefined.removeAll(names(DECLARED, withoutComments(themeFile(theme))));
            assertTrue(undefined.isEmpty(), theme + " leaves these undefined, which draw black: " + undefined);
        }
    }

    /**
     * {@code -color-*} is Primer's vocabulary, defined by Primer under a theme and by the
     * theme's file over it. A token the application reads that neither defines is a typo,
     * and one that only Primer defines is fine in the two themes that are Primer's own.
     */
    @Test
    void everyPrimerTokenTheStylesheetsReadIsDefinedSomewhere() throws Exception {
        Set<String> read = new TreeSet<>(names(MENTIONED, withoutComments(applicationCss())));
        read.removeIf(token -> token.startsWith("-jfx-"));
        // The application defines a few per-control ones itself (-color-button-bg and its
        // states, inside the rules that set them), and the ones read through string
        // building ("-color-button-" + state) are not whole names.
        Set<String> declaredByApplication = names(DECLARED, withoutComments(applicationCss()));

        for (Theme theme : Theme.values()) {
            Set<String> available = new TreeSet<>(names(DECLARED, primer(theme)));
            available.addAll(names(DECLARED, withoutComments(themeFile(theme))));
            available.addAll(declaredByApplication);

            Set<String> undefined = new TreeSet<>();
            for (String token : read) {
                if (!token.endsWith("-") && !available.contains(token)) {
                    undefined.add(token);
                }
            }
            assertTrue(undefined.isEmpty(), "no stylesheet defines " + undefined + " under " + theme);
        }
    }

    /**
     * A theme with grounds of its own restates every Primer colour the application reads, and
     * every one Primer's own controls read - the palette and the ramp steps - rather than
     * inheriting Primer's. The inheritance is
     * what would go wrong: a hue Primer picked against its own blue-black ground, quietly
     * kept under a ground it was never tuned for, and nobody notices until the button hover.
     */
    @Test
    void aThemeWithItsOwnGroundsRestatesThePrimerColoursTheApplicationReads() throws Exception {
        for (Theme theme : Theme.values()) {
            if (PRIMER_AS_IS.contains(theme)) {
                continue;
            }
            Set<String> read = new TreeSet<>(names(MENTIONED, withoutComments(applicationCss())));
            read.addAll(primerReads(theme));
            Set<String> mine = names(DECLARED, withoutComments(themeFile(theme)));
            Set<String> primerRoot = primerRoot(theme);
            Set<String> inherited = new TreeSet<>();
            for (String token : read) {
                if (token.startsWith("-color-") && primerRoot.contains(token) && !mine.contains(token)) {
                    inherited.add(token);
                }
            }
            assertTrue(inherited.isEmpty(), theme + " inherits these from Primer's "
                    + theme.base().id() + " theme: " + inherited);

            // And the palette proper, whether the application reads each name or not: a
            // control Primer styles reads them even when no stylesheet of ours does.
            Set<String> palette = new LinkedHashSet<>();
            for (String token : primerRoot) {
                if (token.matches("-color-(fg|bg|border|neutral|accent|success|warning|danger)-"
                        + "(default|muted|subtle|emphasis|overlay|inset|fg|plus|emphasis-plus)")
                        || token.equals("-color-shadow-default")) {
                    palette.add(token);
                }
            }
            palette.removeAll(mine);
            assertTrue(palette.isEmpty(), theme + " does not restate Primer's " + palette);
        }
    }

    /** A theme file that says {@code -x: -y;} needs a {@code -y}, in the file or in Primer. */
    @Test
    void everyReferenceInAThemeFileResolves() throws Exception {
        for (Theme theme : Theme.values()) {
            String css = withoutComments(themeFile(theme));
            Set<String> available = new TreeSet<>(names(DECLARED, css));
            available.addAll(names(DECLARED, primer(theme)));

            Matcher declaration = DECLARATION.matcher(css);
            while (declaration.find()) {
                String value = declaration.group(2).strip();
                if (value.startsWith("-")) {
                    assertTrue(available.contains(value),
                            theme + ": " + declaration.group(1) + " refers to " + value + ", which nothing defines");
                }
            }
        }
    }

    /** Each value is a colour or a name: a typo like {@code #12345} makes JavaFX drop the line silently. */
    @Test
    void everyValueInAThemeFileIsAColourOrAReference() throws Exception {
        Pattern colour = Pattern.compile("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}|#[0-9a-fA-F]{3}"
                + "|rgba?\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*(,\\s*(0|1|0?\\.\\d+)\\s*)?\\)"
                + "|transparent|-(jfx|color)-[a-z0-9-]+");
        for (Theme theme : Theme.values()) {
            Matcher declaration = DECLARATION.matcher(withoutComments(themeFile(theme)));
            int seen = 0;
            while (declaration.find()) {
                seen++;
                String value = declaration.group(2).strip();
                assertTrue(colour.matcher(value).matches(),
                        theme + ": " + declaration.group(1) + " is " + value + ", which is not a colour");
            }
            assertTrue(seen > 100, theme + " defines only " + seen + " tokens");
        }
    }

    @Test
    void noTokenIsDefinedTwiceInOneFile() throws Exception {
        for (Theme theme : Theme.values()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            Matcher declaration = DECLARATION.matcher(withoutComments(themeFile(theme)));
            while (declaration.find()) {
                counts.merge(declaration.group(1), 1, Integer::sum);
            }
            counts.values().removeIf(count -> count == 1);
            assertEquals(Map.of(), counts, theme + " defines these more than once, so the first is dead");
        }
    }

    @Test
    void onlyTheTokenFilesHoldTheThemesLiteralColours() throws Exception {
        // The inverse of the check that the application sheets hardcode none: the token
        // files are where the hex is supposed to live, in every theme.
        for (Theme theme : Theme.values()) {
            assertTrue(Pattern.compile("#[0-9a-fA-F]{6}\\b").matcher(themeFile(theme)).find(), theme.name());
        }
    }

    @Test
    void everyThemesTokenFileIsUnique() {
        Set<String> files = new TreeSet<>();
        for (Theme theme : Theme.values()) {
            assertTrue(files.add(theme.tokens()), theme + " shares a token file with another theme");
        }
    }
}
