package com.jfx.git.ui;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monospace text where Consolas is not installed.
 *
 * <p>The stylesheets set monospace text - hashes, ref names, diffs, the editor - as
 * {@code 'Consolas', 'JetBrains Mono', monospace}. JavaFX reads only the first family of
 * such a list: where Consolas is missing, which is Linux, it does not try the others but
 * falls back to the proportional System font. So there this supplies, for each of the
 * application's stylesheets, a second one that gives the same selectors a monospace family
 * that is there: JetBrains Mono when it is installed, otherwise the platform's own
 * monospace (JavaFX's logical {@code monospace}, which fontconfig resolves on Linux). It is
 * added after the stylesheet it answers, and wins over it for the family alone; sizes and
 * weights still come from the original rules.
 *
 * <p>Where Consolas is installed - Windows - there is nothing to add, and the stylesheets
 * are used exactly as they are.
 */
public final class MonospaceFallback {

    /** A declaration that names Consolas first, which is the one JavaFX would honour. */
    private static final Pattern CONSOLAS_FIRST =
            Pattern.compile("-fx-font-family\\s*:\\s*['\"]?Consolas\\b");
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** By stylesheet resource; an empty value is a stylesheet that needs nothing. */
    private static final Map<String, Optional<String>> SHEETS = new ConcurrentHashMap<>();
    private static volatile String family;
    private static volatile Double scale;

    private MonospaceFallback() {
    }

    /**
     * The stylesheet to add after {@code resource}, as a {@code data:} URL, or null where
     * none is needed: Consolas is installed, or {@code resource} names no Consolas rule.
     * Needs the JavaFX toolkit, which it asks for the installed families.
     */
    public static String forStylesheet(String resource) {
        String substitute = family();
        if (substitute == null) {
            return null;
        }
        return SHEETS.computeIfAbsent(resource, r -> Optional.ofNullable(build(r, substitute)))
                .orElse(null);
    }

    /**
     * How much wider this platform's monospace is than Consolas, whose 0.55em a character
     * fixed column widths are measured in: exactly 1 where Consolas is installed, and never
     * less, so a narrower font keeps the width it was given. DejaVu Sans Mono, Linux's
     * usual one, is 0.60em, and a 64-pixel hash column cut {@code stash@{0}} to "stash...".
     */
    public static double widthScale() {
        Double known = scale;
        if (known == null) {
            String substitute = family();
            if (substitute == null) {
                return 1.0;
            }
            String name = substitute.equals("monospace") ? "Monospaced" : substitute.replace("'", "");
            Text probe = new Text("0");
            probe.setFont(Font.font(name, 100));
            known = Math.max(1.0, probe.getLayoutBounds().getWidth() / 55.0);
            scale = known;
        }
        return known;
    }

    /** Adds {@link #forStylesheet}'s sheet to {@code sheets}, if there is one and it is not there. */
    public static void addTo(List<String> sheets, String resource) {
        String sheet = forStylesheet(resource);
        if (sheet != null && !sheets.contains(sheet)) {
            sheets.add(sheet);
        }
    }

    /**
     * The family to use instead of Consolas, or null when Consolas is installed - or when
     * there is no toolkit to ask yet, which is not remembered.
     */
    private static String family() {
        String known = family;
        if (known == null) {
            List<String> installed;
            try {
                installed = Font.getFamilies();
            } catch (RuntimeException noToolkit) {
                return null;
            }
            known = installed.contains("Consolas") ? ""
                    : installed.contains("JetBrains Mono") ? "'JetBrains Mono'" : "monospace";
            family = known;
        }
        return known.isEmpty() ? null : known;
    }

    private static String build(String resource, String substitute) {
        String css;
        try (InputStream in = MonospaceFallback.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
        String overrides = overrides(css, substitute);
        return overrides.isEmpty() ? null
                : "data:text/css;base64," + Base64.getEncoder()
                        .encodeToString(overrides.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One rule per rule of {@code css} whose family names Consolas first, with the same
     * selectors and {@code substitute} as the family. The application's stylesheets are
     * flat - selectors and a block, no nesting - which is all this reads.
     */
    static String overrides(String css, String substitute) {
        String text = COMMENT.matcher(css).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (true) {
            int open = text.indexOf('{', at);
            int close = open < 0 ? -1 : text.indexOf('}', open);
            if (close < 0) {
                break;
            }
            String selectors = text.substring(at, open).trim();
            Matcher declares = CONSOLAS_FIRST.matcher(text.substring(open + 1, close));
            if (!selectors.isEmpty() && !selectors.startsWith("@") && declares.find()) {
                out.append(selectors).append(" {\n    -fx-font-family: ")
                        .append(substitute).append(";\n}\n");
            }
            at = close + 1;
        }
        return out.toString();
    }
}
