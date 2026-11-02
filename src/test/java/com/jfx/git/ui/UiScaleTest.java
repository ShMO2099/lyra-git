package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keeps {@link UiScale} and {@code components.css} telling the same story.
 *
 * <p>The type scale has to exist in both places: the stylesheet sets the sizes, because
 * that is what can follow a theme, and {@code UiScale} carries them for the layout code,
 * because JavaFX containers take gaps and paddings as constructor arguments rather than
 * from CSS. Nothing in the compiler connects the two halves, so a size changed in one and
 * not the other would drift silently — which is exactly the class of problem the scale was
 * introduced to remove.
 */
class UiScaleTest {

    private static final Pattern RULE =
            Pattern.compile("\\.(t-[a-z]+)\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern FONT_SIZE =
            Pattern.compile("-fx-font-size:\\s*([0-9.]+)px");

    private static final Pattern ANY_RULE =
            Pattern.compile("([^{}]+)\\{([^}]*)}", Pattern.DOTALL);

    /**
     * Rules sizing the code itself - the text, its gutter, blame and the markers drawn in
     * it. Those follow the editor's zoom and monospace metrics, not the interface's scale.
     */
    private static boolean isCodeSurface(String selector) {
        String s = selector.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.contains("code-area") || s.contains("diff-line-number") || s.contains("diff-marker")
                || s.contains("blame-") || s.contains("-row .text") || s.contains("diff-expander-toggle");
    }

    private static Map<String, Double> stylesheetSizes() throws IOException {
        try (InputStream in = UiScale.class.getResourceAsStream("/css/components.css")) {
            assertNotNull(in, "components.css should be on the classpath");
            String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            Map<String, Double> sizes = new LinkedHashMap<>();
            Matcher rules = RULE.matcher(css);
            while (rules.find()) {
                Matcher size = FONT_SIZE.matcher(rules.group(2));
                if (size.find()) {
                    sizes.put(rules.group(1), Double.parseDouble(size.group(1)));
                }
            }
            return sizes;
        }
    }

    @Test
    void everyTypeStepIsDefinedInTheStylesheet() throws IOException {
        Map<String, Double> sizes = stylesheetSizes();
        assertEquals(6, sizes.size(),
                "six classes over four sizes; found " + sizes.keySet());
        assertTrue(sizes.keySet().containsAll(java.util.List.of(
                "t-display", "t-title", "t-body", "t-label", "t-caption", "t-eyebrow")));
    }

    @Test
    void stylesheetSizesMatchTheConstants() throws IOException {
        Map<String, Double> sizes = stylesheetSizes();
        assertEquals(UiScale.DISPLAY, sizes.get("t-display"), ".t-display and UiScale.DISPLAY");
        assertEquals(UiScale.TITLE, sizes.get("t-title"), ".t-title and UiScale.TITLE");
        assertEquals(UiScale.BODY, sizes.get("t-body"), ".t-body and UiScale.BODY");
        assertEquals(UiScale.BODY, sizes.get("t-label"), ".t-label is the body size in bold");
        assertEquals(UiScale.CAPTION, sizes.get("t-caption"), ".t-caption and UiScale.CAPTION");
        assertEquals(UiScale.CAPTION, sizes.get("t-eyebrow"), ".t-eyebrow is the caption size in bold");
    }

    /**
     * Unclassed text starts at the body size.
     *
     * <p>AtlantaFX sets the root at 14px - above every step - so without this every list
     * cell and checkbox with no class of its own out-sized the headings over it.
     */
    @Test
    void theRootStartsAtTheBodySize() throws IOException {
        try (InputStream in = UiScale.class.getResourceAsStream("/css/components.css")) {
            String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher root = Pattern.compile("(?m)^\\.root\\s*\\{([^}]*)}").matcher(css);
            assertTrue(root.find(), "components.css should set the root size");
            Matcher size = FONT_SIZE.matcher(root.group(1));
            assertTrue(size.find(), ".root should carry a font size");
            assertEquals(UiScale.BODY, Double.parseDouble(size.group(1)));
        }
    }

    /**
     * The steps have to be distinct and ordered, and far enough apart to read as rank.
     *
     * <p>Six steps a pixel apart was the old scale's failure: 12 beside 13 does not read
     * as a smaller thing, it reads as a mistake. Below the body size, rank is weight and
     * colour; the sizes themselves stay at least two pixels apart.
     */
    @Test
    void stepsAreDistinctEnoughToReadAsRank() {
        double[] scale = {UiScale.DISPLAY, UiScale.TITLE, UiScale.BODY, UiScale.CAPTION};
        for (int i = 1; i < scale.length; i++) {
            assertTrue(scale[i - 1] - scale[i] >= 2,
                    "step " + i + " (" + scale[i] + ") should be at least 2px below "
                            + scale[i - 1]);
        }
    }

    @Test
    void spacingStepsAreDistinctAndOrdered() {
        double[] spacing = {UiScale.TIGHT, UiScale.SNUG, UiScale.STEP, UiScale.GAP, UiScale.EDGE,
                UiScale.SECTION};
        for (int i = 1; i < spacing.length; i++) {
            assertTrue(spacing[i] > spacing[i - 1],
                    "spacing step " + i + " should be larger than the one before it");
        }
    }

    /**
     * A section may not be pinned open taller than its own cap.
     *
     * <p>This is the invariant the sidebar regression broke: sections were given a fixed
     * height equal to the cap, so a list with one item reserved the space of a full one.
     */
    @Test
    void sectionCapIsTallerThanASingleRow() {
        assertTrue(UiScale.SECTION_MAX_HEIGHT > UiScale.ROW_HEIGHT * 4,
                "the cap should hold several rows, or it is not a cap but a size");
    }

    /** The panels tick at the same rate as the graph beside them. */
    @Test
    void rowHeightMatchesTheCompactGraphRow() {
        assertEquals(com.jfx.git.graph.GraphDensity.COMPACT.rowHeight(), UiScale.ROW_HEIGHT,
                "a file list beside the graph should not tick at a different rate");
    }

    /**
     * No stylesheet rule may invent a size of its own.
     *
     * <p>This is the test the scale was missing, and the reason it drifted: the six steps
     * were declared and then twelve sizes were in use beside them, because nothing stopped
     * a rule that wanted "slightly smaller" from writing 10.5px. Every size in every
     * stylesheet has to be one of the steps, so the next such rule fails here rather than
     * shipping.
     */
    @Test
    void noStylesheetInventsASizeOutsideTheScale() throws IOException {
        java.util.Set<Double> allowed = java.util.Set.of(
                UiScale.DISPLAY, UiScale.TITLE, UiScale.BODY, UiScale.CAPTION);
        java.util.regex.Pattern size =
                java.util.regex.Pattern.compile("-fx-font-size:\\s*([0-9.]+)px");

        java.util.List<String> offenders = new java.util.ArrayList<>();
        for (String sheet : java.util.List.of("/css/components.css", "/css/lyra.css",
                "/css/theme-dark.css", "/css/theme-light.css", "/diff-styles.css")) {
            try (java.io.InputStream in = UiScaleTest.class.getResourceAsStream(sheet)) {
                if (in == null) continue;
                String css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Matcher rules = ANY_RULE.matcher(css);
                while (rules.find()) {
                    String selector = rules.group(1).strip();
                    if (isCodeSurface(selector)) continue;
                    var matcher = size.matcher(rules.group(2));
                    while (matcher.find()) {
                        double value = Double.parseDouble(matcher.group(1));
                        if (!allowed.contains(value)) {
                            offenders.add(sheet + " " + selector + " → " + value + "px");
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "every size must be a step of the scale " + allowed + "; found " + offenders);
    }
}
