package com.jfx.git.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.shape.Shape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The drawn toolbar and sidebar icons.
 *
 * <p>Nothing here checks what an icon looks like — that is what eyes are for. What is
 * worth testing is the wiring, because every icon gets its colour from a style class and
 * nothing else: a shape that reaches the scene without a tone class, or with neither
 * role class, is not a broken colour but an invisible icon, and it fails silently.
 */
class IconsTest {

    private static final List<String> TONES = List.of("icon-default", "icon-accent");

    private static List<Shape> shapesOf(Node icon) {
        List<Shape> found = new ArrayList<>();
        collect(icon, found);
        return found;
    }

    private static void collect(Node node, List<Shape> into) {
        if (node instanceof Shape shape) into.add(shape);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, into);
        }
    }

    /** Every icon the application draws, with the name it is known by. */
    private static List<Object[]> allIcons() {
        List<Object[]> icons = new ArrayList<>();
        icons.add(new Object[]{"open", (Supplier<Node>) Icons::open});
        icons.add(new Object[]{"fetch", (Supplier<Node>) Icons::fetch});
        icons.add(new Object[]{"pull", (Supplier<Node>) Icons::pull});
        icons.add(new Object[]{"push", (Supplier<Node>) Icons::push});
        icons.add(new Object[]{"branch", (Supplier<Node>) Icons::branch});
        icons.add(new Object[]{"stash", (Supplier<Node>) Icons::stash});
        icons.add(new Object[]{"pop", (Supplier<Node>) Icons::pop});
        icons.add(new Object[]{"undo", (Supplier<Node>) Icons::undo});
        icons.add(new Object[]{"redo", (Supplier<Node>) Icons::redo});
        icons.add(new Object[]{"history", (Supplier<Node>) Icons::history});
        icons.add(new Object[]{"terminal", (Supplier<Node>) Icons::terminal});
        icons.add(new Object[]{"currentBranch", (Supplier<Node>) Icons::currentBranch});
        icons.add(new Object[]{"localBranch", (Supplier<Node>) Icons::localBranch});
        icons.add(new Object[]{"remoteBranch", (Supplier<Node>) Icons::remoteBranch});
        icons.add(new Object[]{"tag", (Supplier<Node>) Icons::tag});
        icons.add(new Object[]{"stashMark", (Supplier<Node>) Icons::stashMark});
        icons.add(new Object[]{"folder", (Supplier<Node>) Icons::folder});
        icons.add(new Object[]{"file", (Supplier<Node>) Icons::file});
        icons.add(new Object[]{"create", (Supplier<Node>) Icons::create});
        icons.add(new Object[]{"cloneRepo", (Supplier<Node>) Icons::cloneRepo});
        icons.add(new Object[]{"comment", (Supplier<Node>) Icons::comment});
        icons.add(new Object[]{"issue", (Supplier<Node>) Icons::issue});
        icons.add(new Object[]{"pullRequest", (Supplier<Node>) Icons::pullRequest});
        icons.add(new Object[]{"eyeOpen", (Supplier<Node>) Icons::eyeOpen});
        icons.add(new Object[]{"eyeClosed", (Supplier<Node>) Icons::eyeClosed});
        icons.add(new Object[]{"copy", (Supplier<Node>) Icons::copy});
        icons.add(new Object[]{"close", (Supplier<Node>) Icons::close});
        icons.add(new Object[]{"edit", (Supplier<Node>) Icons::edit});
        icons.add(new Object[]{"external", (Supplier<Node>) Icons::external});
        icons.add(new Object[]{"restore", (Supplier<Node>) Icons::restore});
        icons.add(new Object[]{"chevronUp", (Supplier<Node>) Icons::chevronUp});
        icons.add(new Object[]{"chevronDown", (Supplier<Node>) Icons::chevronDown});
        icons.add(new Object[]{"unfold", (Supplier<Node>) Icons::unfold});
        icons.add(new Object[]{"pilcrow", (Supplier<Node>) Icons::pilcrow});
        icons.add(new Object[]{"whitespaceDots", (Supplier<Node>) Icons::whitespaceDots});
        icons.add(new Object[]{"wrap", (Supplier<Node>) Icons::wrap});
        icons.add(new Object[]{"settings", (Supplier<Node>) Icons::settings});
        icons.add(new Object[]{"search", (Supplier<Node>) Icons::search});
        icons.add(new Object[]{"warning", (Supplier<Node>) Icons::warning});
        icons.add(new Object[]{"star", (Supplier<Node>) Icons::star});
        icons.add(new Object[]{"starFilled", (Supplier<Node>) Icons::starFilled});
        icons.add(new Object[]{"expand", (Supplier<Node>) Icons::expand});
        icons.add(new Object[]{"more", (Supplier<Node>) Icons::more});
        icons.add(new Object[]{"trash", (Supplier<Node>) Icons::trash});
        icons.add(new Object[]{"commit", (Supplier<Node>) Icons::commit});
        icons.add(new Object[]{"compare", (Supplier<Node>) Icons::compare});
        icons.add(new Object[]{"check", (Supplier<Node>) Icons::check});
        return icons;
    }

    @SuppressWarnings("unchecked")
    @Test
    void everyIconDrawsSomething() {
        for (Object[] entry : allIcons()) {
            String name = (String) entry[0];
            Node icon = ((Supplier<Node>) entry[1]).get();
            assertFalse(shapesOf(icon).isEmpty(), name + " drew no shapes at all");
        }
    }

    /**
     * Colour arrives only through a tone class, so every shape must carry one.
     *
     * <p>Without it the shape inherits whatever the platform default is — black on a dark
     * ground, which is an icon that is present, laid out, and invisible.
     */
    @SuppressWarnings("unchecked")
    @Test
    void everyShapeCarriesExactlyOneTone() {
        for (Object[] entry : allIcons()) {
            String name = (String) entry[0];
            for (Shape shape : shapesOf(((Supplier<Node>) entry[1]).get())) {
                long tones = shape.getStyleClass().stream().filter(TONES::contains).count();
                assertEquals(1, tones,
                        name + " has a shape with " + tones + " tone classes: "
                                + shape.getStyleClass());
            }
        }
    }

    /** Fill or stroke, never neither: the role class is what decides which one paints. */
    @SuppressWarnings("unchecked")
    @Test
    void everyShapeHasARole() {
        for (Object[] entry : allIcons()) {
            String name = (String) entry[0];
            for (Shape shape : shapesOf(((Supplier<Node>) entry[1]).get())) {
                var classes = shape.getStyleClass();
                boolean hasRole = classes.contains("icon-line")
                        || classes.contains("icon-solid");
                assertTrue(hasRole, name + " has a shape with no role class: " + classes);
            }
        }
    }

    /**
     * An icon is an outline and nothing else: no tint layer behind it.
     *
     * <p>The set was duotone once, each icon a low-opacity wash under its outline in one of
     * five family hues. A leftover tint shape would now have no rule painting it, which is
     * JavaFX's default black - a dark blob behind one icon in a row of clean ones.
     */
    @SuppressWarnings("unchecked")
    @Test
    void noIconCarriesATintLayer() {
        for (Object[] entry : allIcons()) {
            String name = (String) entry[0];
            for (Shape shape : shapesOf(((Supplier<Node>) entry[1]).get())) {
                assertFalse(shape.getStyleClass().contains("icon-tint"),
                        name + " still has a tint layer: " + shape.getStyleClass());
            }
        }
    }

    /**
     * One icon, one tone.
     *
     * <p>Accent is for a mark that is itself state - the current-branch dot, a filled
     * star. An icon that mixed it with the default tone would be half furniture and half
     * signal, which is what the coloured toolbar was.
     */
    @SuppressWarnings("unchecked")
    @Test
    void anIconUsesASingleTone() {
        for (Object[] entry : allIcons()) {
            String name = (String) entry[0];
            var tones = shapesOf(((Supplier<Node>) entry[1]).get()).stream()
                    .flatMap(s -> s.getStyleClass().stream())
                    .filter(TONES::contains)
                    .distinct()
                    .toList();
            assertEquals(1, tones.size(), name + " mixes tones: " + tones);
        }
    }

    /**
     * An icon-only control says what it is to something that cannot see the icon.
     *
     * <p>A character in a button was at least read out, if wrongly ("pilcrow sign"); a
     * drawn icon is silent. The factory is the one place that makes the name unavoidable.
     */
    @Test
    void anIconButtonCarriesItsNameAndTooltip() {
        var button = FxHarness.call(() -> Icons.button(Icons.copy(), "Copy path", "Copy the path"));
        assertEquals("Copy path", button.getAccessibleText());
        assertEquals("Copy the path", button.getTooltip().getText());
        assertTrue(button.getStyleClass().containsAll(List.of("btn-ghost", "btn-icon")),
                "an icon button is a ghost: no fill until hovered");
        assertTrue(button.getText() == null || button.getText().isEmpty(),
                "the icon is the content; there is no character standing in for it");

        var toggle = FxHarness.call(() -> Icons.toggle(Icons.wrap(), "Wrap long lines", null));
        assertEquals("Wrap long lines", toggle.getAccessibleText());
    }

    /** The 12px marks ask for the lighter stroke; the 16px ones must not. */
    @Test
    void onlyTheSmallMarksAskForTheThinStroke() {
        assertTrue(shapesOf(Icons.folder()).stream()
                        .anyMatch(s -> s.getStyleClass().contains("icon-line-small")),
                "a sidebar mark should use the small stroke");
        assertTrue(shapesOf(Icons.pull()).stream()
                        .noneMatch(s -> s.getStyleClass().contains("icon-line-small")),
                "a toolbar icon should use the full stroke");
    }

    /**
     * No typed symbol where a drawn icon belongs.
     *
     * <p>A string that is nothing but one of these characters is an icon written as text:
     * drawn by whichever font happens to have it, at that font's weight and baseline, and
     * read out by a screen reader as "pilcrow sign". {@link Icons} draws each of them
     * instead. Arrows and the middle dot are left alone: {@code ↑2 ↓3} is notation inside
     * text, and the dot is the whitespace marker the diff itself renders.
     *
     * <p>The allowlist is empty and stays that way: it held the files whose screens had not
     * been restyled yet.
     */
    @Test
    void noUiClassUsesASymbolGlyphAsAnIcon() throws java.io.IOException {
        java.util.Set<String> glyphs = java.util.Set.of("⧉", "✕", "⚙", "⌕", "¶", "≡", "⌃", "⛶",
                "⋯", "◈", "◆", "◇", "⇄", "★", "☆", "✎", "⚠", "⟲", "↗", "←");
        java.util.Set<String> stillToMigrate = java.util.Set.of();
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile("\"([^\"\\\\]{1,2})\"");

        java.util.List<String> offenders = new ArrayList<>();
        try (var paths = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/com/jfx/git"))) {
            for (java.nio.file.Path file : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("Icons.java") || stillToMigrate.contains(name)) {
                    continue;
                }
                int line = 0;
                for (String text : java.nio.file.Files.readAllLines(file)) {
                    line++;
                    if (text.strip().startsWith("*") || text.strip().startsWith("//")) {
                        continue;
                    }
                    var matcher = literal.matcher(text);
                    while (matcher.find()) {
                        if (glyphs.contains(matcher.group(1).strip())) {
                            offenders.add(name + ":" + line + " " + matcher.group(0));
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "these should be drawn icons, not typed symbols: " + offenders);
    }

    /**
     * No emoji where a drawn icon belongs.
     *
     * <p>This class's whole reason for existing is that JavaFX has no colour-font
     * support: an emoji code point arrives flat grey, at whatever baseline the platform's
     * fallback font uses, and differently on each OS. The rule was written down and then
     * quietly broken three times - a document in the diff title bar and a folder, a cloud
     * and a plus on the launchpad - because nothing checked.
     *
     * <p>Scoped to the astral pictographic block, which is where colour presentation is
     * the default and JavaFX's limitation actually bites. The application also uses
     * typographic marks from the older symbol block - the close X, the gear, the warning
     * triangle, the favourites star - and those are deliberate: they have monochrome
     * presentation by default, render consistently, and are text rather than iconography.
     */
    @Test
    void noUiClassUsesAnEmojiWhereAnIconBelongs() throws java.io.IOException {
        java.nio.file.Path ui = java.nio.file.Path.of("src/main/java/com/jfx/git/ui");
        java.util.List<String> offenders = new ArrayList<>();
        try (var paths = java.nio.file.Files.walk(ui)) {
            for (java.nio.file.Path file : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                // Icons.java itself names the emoji it replaced, in prose.
                if (file.getFileName().toString().equals("Icons.java")) {
                    continue;
                }
                int line = 0;
                for (String text : java.nio.file.Files.readAllLines(file)) {
                    line++;
                    if (text.strip().startsWith("*") || text.strip().startsWith("//")) {
                        continue; // a comment may discuss one
                    }
                    for (int i = 0; i < text.length(); ) {
                        int cp = text.codePointAt(i);
                        i += Character.charCount(cp);
                        boolean pictographic = cp >= 0x1F300 && cp <= 0x1FAFF;
                        if (pictographic) {
                            offenders.add(file.getFileName() + ":" + line
                                    + " U+" + Integer.toHexString(cp).toUpperCase());
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "these should be drawn icons, not emoji: " + offenders);
    }
}
