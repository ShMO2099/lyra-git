package com.jfx.git.testsupport;

import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Layout properties a screenshot shows and a test can state: text that is cut off, a
 * scrollbar inside a list that should have grown, controls meant to share a row, sizes
 * off the type scale, a symbol standing in for an icon, too many filled buttons.
 *
 * <p>All of these read the laid-out scene graph, so call them on the FX thread after
 * {@code applyCss()} and {@code layout()} - {@link MainWindowFixture#layOut()} does both.
 */
public final class LayoutAssert {

    /** The type scale: caption, body, title, display. Anything else is a size someone improvised. */
    public static final Set<Double> TYPE_SCALE = Set.of(11.0, 13.0, 15.0, 20.0);

    /** Symbols that have been used in place of icons; an icon is drawn, not typed. */
    public static final Set<String> GLYPH_ICONS = Set.of(
            "⧉", "←", "✎", "⟲", "↗", "↑", "↓", "¶", "·", "≡", "⌃", "◈", "◆", "◇", "⇄",
            "⚙", "⌕", "✕", "⚠", "★", "☆", "⛶", "⋯");

    private LayoutAssert() {
    }

    /** Fails if {@code labeled} shows less than its whole text - an ellipsis, or a clipped run. */
    public static void assertNotTruncated(Labeled labeled) {
        String full = labeled.getText();
        if (full == null || full.isEmpty()) return;
        Text shown = (Text) labeled.lookup(".text");
        String visible = shown == null ? full : shown.getText();
        assertEquals(full, visible, "\"" + full + "\" is cut off on screen as \"" + visible + "\"");
    }

    /** Every visible labelled control under {@code root} whose text is cut off. */
    public static List<String> truncatedTexts(Parent root) {
        List<String> cut = new ArrayList<>();
        for (Node node : visibleNodes(root, n -> n instanceof Labeled)) {
            Labeled labeled = (Labeled) node;
            String full = labeled.getText();
            if (full == null || full.isEmpty()) continue;
            Text shown = (Text) labeled.lookup(".text");
            if (shown != null && !full.equals(shown.getText())) {
                cut.add(full + " -> " + shown.getText());
            }
        }
        return cut;
    }

    /** Fails if any scrollbar under {@code root} is showing - the content was meant to fit. */
    public static void assertNoVisibleScrollBars(Parent root) {
        List<String> showing = new ArrayList<>();
        for (Node node : visibleNodes(root, n -> n instanceof ScrollBar)) {
            ScrollBar bar = (ScrollBar) node;
            if (bar.getWidth() > 0 && bar.getHeight() > 0 && bar.getVisibleAmount() < bar.getMax() - bar.getMin()) {
                showing.add((bar.getOrientation() == Orientation.VERTICAL ? "vertical" : "horizontal")
                        + " in " + describe(bar.getParent()));
            }
        }
        assertTrue(showing.isEmpty(), "scrollbars showing: " + showing);
    }

    /** Fails unless {@code a} and {@code b} sit on one row: their vertical centres within 4px. */
    public static void assertSameRow(Node a, Node b) {
        Bounds ba = a.localToScene(a.getLayoutBounds());
        Bounds bb = b.localToScene(b.getLayoutBounds());
        double ca = ba.getMinY() + ba.getHeight() / 2;
        double cb = bb.getMinY() + bb.getHeight() / 2;
        assertTrue(Math.abs(ca - cb) <= 4,
                describe(a) + " (y " + ca + ") and " + describe(b) + " (y " + cb + ") are not on one row");
    }

    /**
     * Fails if any visible text under {@code root} is sized off {@link #TYPE_SCALE}. Code
     * surfaces are skipped: the editor's size is the user's zoom, not the scale. So are the
     * initials in an avatar, which are part of a drawn mark and sized to its circle.
     */
    public static void assertTextSizesOnScale(Parent root) {
        Set<String> off = new TreeSet<>();
        for (Node node : visibleNodes(root, n -> n instanceof Text)) {
            if (insideStyleClass(node, "code-area") || insideStyleClass(node, "styled-text-area")
                    || insideStyleClass(node, "author-badge")) continue;
            Text text = (Text) node;
            if (text.getText() == null || text.getText().isBlank()) continue;
            double size = Math.round(text.getFont().getSize() * 2) / 2.0;
            if (!TYPE_SCALE.contains(size)) {
                off.add(size + "px: \"" + abbreviate(text.getText()) + "\" in " + describe(text.getParent()));
            }
        }
        assertTrue(off.isEmpty(), "text off the type scale " + TYPE_SCALE + ":\n  " + String.join("\n  ", off));
    }

    /** Fails if a visible control's only text is a symbol from {@link #GLYPH_ICONS}. */
    public static void assertNoGlyphIcons(Parent root) {
        List<String> glyphs = new ArrayList<>();
        for (Node node : visibleNodes(root, n -> n instanceof Labeled)) {
            String text = ((Labeled) node).getText();
            if (text != null && GLYPH_ICONS.contains(text.strip())) {
                glyphs.add("\"" + text.strip() + "\" in " + describe(node));
            }
        }
        assertTrue(glyphs.isEmpty(), "symbols used as icons: " + glyphs);
    }

    /**
     * Visible buttons painted with a saturated fill - the ones that read as "the" action.
     * A region should have at most one.
     */
    public static int countFilledButtons(Parent root) {
        int filled = 0;
        for (Node node : visibleNodes(root, n -> n instanceof ButtonBase)) {
            if (hasSaturatedFill((Region) node)) filled++;
        }
        return filled;
    }

    /** Visible nodes under {@code root} (itself included) that satisfy {@code test}. */
    public static List<Node> visibleNodes(Parent root, Predicate<Node> test) {
        List<Node> found = new ArrayList<>();
        collect(root, test, found);
        return found;
    }

    public static void failWith(String message, List<String> problems) {
        if (!problems.isEmpty()) fail(message + ":\n  " + String.join("\n  ", problems));
    }

    // ------------------------------------------------------------------

    private static void collect(Node node, Predicate<Node> test, List<Node> found) {
        if (!node.isVisible() || node.getOpacity() == 0) return;
        if (test.test(node)) found.add(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, test, found);
            }
        }
    }

    private static boolean insideStyleClass(Node node, String styleClass) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains(styleClass)) return true;
        }
        return false;
    }

    private static boolean hasSaturatedFill(Region region) {
        Background background = region.getBackground();
        if (background == null || background.getFills().isEmpty()) return false;
        // The last fill is the surface. The ones under it are edges - a border, or a focus
        // ring, drawn as a layer a pixel larger - and an accent ring round a quiet button
        // is not a filled button.
        BackgroundFill surface = background.getFills().get(background.getFills().size() - 1);
        return surface.getFill() instanceof Color c && c.getOpacity() > 0.5 && c.getSaturation() > 0.45
                && c.getBrightness() > 0.35;
    }

    static String describe(Node node) {
        if (node == null) return "(none)";
        String id = node.getId() != null ? "#" + node.getId() : "";
        String classes = node.getStyleClass().isEmpty() ? "" : "." + String.join(".", node.getStyleClass());
        String text = node instanceof Labeled l && l.getText() != null ? " \"" + abbreviate(l.getText()) + "\"" : "";
        return node.getClass().getSimpleName() + id + classes + text;
    }

    private static String abbreviate(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 40 ? one.substring(0, 40) + "…" : one;
    }

    /** Whether {@code node} is a {@link Button} whose text reads {@code text}; for lookups. */
    public static boolean isButton(Node node, String text) {
        return node instanceof Button b && text.equals(b.getText());
    }
}
